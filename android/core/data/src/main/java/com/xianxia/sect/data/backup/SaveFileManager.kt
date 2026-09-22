package com.xianxia.sect.data.backup

import android.util.Log
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.result.StorageResult
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 存档文件管理器 — 双缓冲回退机制的核心。
 *
 * 职责：
 * 1. **原子写入**：write-tmp → fsync → rename 流程，确保写入崩溃不损坏现有存档
 * 2. **备份回退**：主文件（.sav）损坏时自动尝试备份文件（.bak）
 * 3. **完整性校验**：CRC32C 文件头快速检测截断/部分写入
 * 4. **崩溃清理**：启动时清理遗留的 .tmp 临时文件
 *
 * 文件布局：
 * ```
 * {filesDir}/saves/
 *   ├── slot_{N}.sav    ← 主存档文件
 *   ├── slot_{N}.bak    ← 前一有效备份（崩溃恢复用）
 * ```
 *
 * 文件头格式（.sav / .bak 共用，16 字节定长头）：
 * Offset  Size  Field
 *   0      4    Magic: 0x58 0x53 0x42 0x4B ("XSBK")
 *   4      2    Format version (major=0x01, minor=0x01 → 0x0101，自 0x0101 起字节 11 记录 CRC 算法)
 *   6      4    CRC of payload (bytes 12..end，算法见字节 11；0x0100 旧格式无标识)
 *  10      2    Flags (bit 0: lz4-compressed payload) + CRC 算法标识（0x0101 起：0=CRC32, 1=CRC32C）
 *  12      4    Uncompressed payload length (uint32, big-endian)
 *  16      N    Payload (SerializationModule.serializeAndCompressSaveData 输出)
 *
 * 格式版本 0x0101：字节 11 记录 CRC 算法标识，读取按标识精确校验；
 * 旧格式（0x0100）无算法标识，通过双算法探测兼容读取。
 * 0x0101 恒写 CRC32C（自实现查表，全 API 可算）——java.util.zip.CRC32C 仅 API 34+ 存在，
 * 自实现保证 API<34 设备也能校验 API≥34 设备写入的文件。
 */
@Singleton
class SaveFileManager @Inject constructor(
    private val saveSerializer: SaveSerializer
) {
    companion object {
        private const val TAG = "SaveFileManager"

        /** 最大备份保留天数 */
        private const val MAX_BACKUP_AGE_DAYS = 7

        /** 最大备份文件大小（MB） */
        private const val MAX_BACKUP_SIZE_MB = 100
    }

    /** 备份目录 */
    private lateinit var backupDir: File

    /**
     * 初始化备份目录。必须在首次调用任何文件操作前调用。
     * 由 [StorageFacade.initialize] 在启动时调用。幂等：重复调用直接返回。
     */
    fun initialize(baseDir: File) {
        if (::backupDir.isInitialized) {
            Log.d(TAG, "SaveFileManager 已初始化，跳过重复初始化")
            return
        }
        backupDir = File(baseDir, StorageConstants.BACKUP_DIR_NAME)
        if (!backupDir.exists()) {
            backupDir.mkdirs()
            Log.i(TAG, "创建备份目录: ${backupDir.absolutePath}")
        }
    }

    // ============================================================
    // 原子写入
    // ============================================================

    /**
     * 原子写入存档数据。
     *
     * 流程（主保存无条件执行，备份降级只告警不阻断）：
     * 1. 序列化 payload → 写入 `.sav.tmp` 文件 (FileOutputStream)
     * 2. fsync 强制刷盘
     * 3. **备份轮转**：把当前有效 `.sav` 复制为 `.bak`（真备份 = 前一版本快照，
     *    见 [rotateCurrentSaveToBackup]）——必须在 `.sav` 被覆盖前执行
     * 4. 重命名 `.sav.tmp` → `.sav`（同一文件系统上的原子操作）——**主保存必执行**
     * 5. payload 超 100MB / 轮转失败 → 返回 [StorageResult.Skipped]（带原因，不谎报成功）
     * 6. 删除残留 `.sav.tmp`（清理）
     *
     * **返回语义**：`Success` = `.sav` 已落盘且 `.bak` 已更新为前一版本；
     * `Skipped(message)` = `.sav` 已落盘但备份降级（超限/轮转失败）；
     * `Failure` = `.sav` 未落盘（IO 失败）。调用方据此向 UI 如实反馈（审计 §12-B/§12-C）。
     */
    @Suppress("TooGenericExceptionCaught") // 异常显式包装进 Result 上抛, 非静默吞噬
    fun atomicWrite(slot: Int, saveData: SaveData): StorageResult<Unit> {
        ensureInitialized()
        if (!isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        val savFile = getSavFile(slot)
        val bakFile = getBakFile(slot)
        val tmpFile = getTmpFile(slot)

        return try {
            // 1. 序列化
            val payload = saveSerializer.serializeAndCompressSaveData(saveData)

            // 2. 写入 .tmp（write-tmp）
            writeFileAtomic(tmpFile, payload)

            // 2'. 备份轮转：把"当前有效 .sav"变成 .bak（真备份 = **前一版本**快照）
            //     —— 必须在 .sav 被 rename 覆盖**之前**执行，否则拿到的是新内容；
            //     失败/超限只降级告警，不阻断主保存（返回原因供 UI 如实提示）
            val backupWarning = rotateCurrentSaveToBackup(savFile, bakFile, slot, payload.size)

            // 3. 重命名 .tmp → .sav（原子交换）——主保存无条件执行
            // 先试无 delete 的 rename 原子覆盖（Linux/Android rename() 原子替换目标），
            // 避免 delete 与 renameTo 之间进程崩溃导致 .sav 缺失；
            // 失败回退 delete+rename，兼容 renameTo 遇已存在目标失败的存储（如 FAT32/exFAT）
            if (!tmpFile.renameTo(savFile)) {
                if (savFile.exists()) {
                    savFile.delete()
                }
                if (!tmpFile.renameTo(savFile)) {
                    tmpFile.delete()
                    return StorageResult.failure(
                        StorageError.IO_ERROR,
                        "重命名 .tmp → .sav 失败 slot=$slot"
                    )
                }
            }

            // 4. 清除残留 .tmp
            if (tmpFile.exists()) tmpFile.delete()

            // 5. 主保存成功；备份轮转若降级（超限/失败）⇒ 如实返回 skipped 原因
            if (backupWarning != null) {
                StorageResult.skipped(backupWarning)
            } else {
                StorageResult.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "备份写入失败 slot=$slot", e)
            // 清理残留 .tmp
            if (getTmpFile(slot).exists()) getTmpFile(slot).delete()
            // 备份失败不阻断主保存——返回 failure 但由调用方决定是否中断
            StorageResult.failure(StorageError.BACKUP_FAILED, "备份写入失败: ${e.message}")
        }
    }

    /**
     * 备份轮转：把**当前有效 `.sav`** 复制为 `.bak`（真备份 = 前一版本快照）。
     *
     * 语义修正（审计 §12-B）：旧实现用**同一个 payload** 同时写 `.sav` 与 `.bak`
     * ⇒ `.bak ≡ .sav`，对"内容本身错 / 误覆盖 / 逻辑 bug"零防护（只能防同一次写坏）。
     * 轮转后 `.bak` 恒为**上一次成功保存**的内容 ⇒ [readWithFallback] 回退即可退回上一存档点。
     *
     * 时机：必须在 `.sav` 被 rename 覆盖**之前**调用。
     * 写盘：流拷贝 + `fsync` + rename（`.bak` 不会半写）；任何失败只降级、不阻断主保存。
     *
     * @return 降级原因（null = 轮转完成或无需轮转）
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // 三种降级各自 early-return 原因串，为守卫风格
    private fun rotateCurrentSaveToBackup(
        savFile: File,
        bakFile: File,
        slot: Int,
        payloadSize: Int
    ): String? {
        // 超限优先判定（与旧契约一致：超限恒报 Skipped，无论有无可轮转的旧档）
        if (payloadSize > MAX_BACKUP_SIZE_MB * 1024 * 1024) {
            Log.w(TAG, "存档数据过大 (${payloadSize / 1024 / 1024}MB)，跳过备份轮转（主保存不受影响）")
            return "备份因超过 ${MAX_BACKUP_SIZE_MB}MB 上限被跳过（主保存成功，非阻断）"
        }
        // 首次保存：没有"前一版本"可轮转，`.bak` 保持不存在（readWithFallback 会走 CORRUPTED）
        if (!savFile.exists()) return null
        val bakTmpFile = File(backupDir, "slot_${slot}.bak.tmp")
        return try {
            savFile.inputStream().use { input ->
                FileOutputStream(bakTmpFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (bakFile.exists() && !bakFile.delete()) {
                Log.w(TAG, "旧 .bak 删除失败 slot=$slot（继续 rename 覆盖）")
            }
            if (bakTmpFile.renameTo(bakFile)) {
                Log.d(TAG, "备份轮转完成 slot=$slot（.bak = 上一次保存）")
                null
            } else {
                bakTmpFile.delete()
                Log.w(TAG, ".bak 轮转 rename 失败 slot=$slot（非阻断，.sav 仍有效）")
                "备份轮转失败（.bak 未更新，主保存成功）"
            }
        } catch (e: Exception) {
            bakTmpFile.delete()
            Log.w(TAG, ".bak 轮转失败 slot=$slot（非阻断）", e)
            "备份轮转失败（.bak 未更新，主保存成功）"
        }
    }

    // ============================================================
    // 带回退的读取
    // ============================================================

    /**
     * 读取存档数据，带自动回退：
     * 1. 试 .sav → CRC32C 校验
     * 2. 有效 → 返回 SUCCESS
     * 3. .sav 损坏 → 试 .bak → CRC32C 校验
     * 4. .bak 有效 → 返回 RECOVERED
     * 5. 都损坏 → 返回 CORRUPTED
     *
     * @param readOnly SR-7 文件层退役判据：`CLOUD_ONLY` 下旧 `.sav` 只是**只读应急源**，
     *   置 true 时跳过"用 `.bak` 覆盖 `.sav`"的修复性写回（本方法内唯一的写点），
     *   其余读/校验/回退语义逐字不变。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun readWithFallback(slot: Int, readOnly: Boolean = false): BackupReadResult {
        ensureInitialized()
        if (!isValidSlot(slot)) {
            return BackupReadResult(BackupStatus.CORRUPTED, null, "none")
        }

        // 尝试主文件 .sav
        val savFile = getSavFile(slot)
        if (savFile.exists()) {
            val savPayload = readAndVerify(savFile)
            if (savPayload != null) {
                return BackupReadResult(BackupStatus.SUCCESS, savPayload, "sav")
            }
            Log.w(TAG, ".sav 校验失败 slot=$slot，尝试 .bak 回退")
        }

        // 回退到 .bak
        val bakFile = getBakFile(slot)
        if (bakFile.exists()) {
            val bakPayload = readAndVerify(bakFile)
            if (bakPayload != null) {
                Log.w(TAG, ".bak 恢复成功 slot=$slot")
                // 恢复后修复 .sav（用 .bak 覆盖 .sav）
                // 修复 .sav 失败必须如实反映：.sav 保持损坏时读取将持续回退 .bak，
                // 调用方需通过 repairFailed 感知
                // readOnly（CLOUD_ONLY 应急源）⇒ 不写回：数据照旧返回，文件保持原样
                var repairFailed = false
                if (!readOnly) {
                    try {
                        bakFile.copyTo(savFile, overwrite = true)
                    } catch (e: Exception) {
                        repairFailed = true
                        Log.e(TAG, "修复 .sav 失败 slot=$slot——将持续回退 .bak 直至下次成功保存", e)
                    }
                }
                return BackupReadResult(BackupStatus.RECOVERED, bakPayload, "bak", repairFailed)
            }
            Log.e(TAG, ".bak 也损坏 slot=$slot")
        }

        return BackupReadResult(BackupStatus.CORRUPTED, null, "none")
    }

    // ============================================================
    // 完整性校验
    // ============================================================

    /** 校验指定槽位的备份文件完整性 */
    fun verifySlot(slot: Int): BackupIntegrity {
        ensureInitialized()

        val savFile = getSavFile(slot)
        val bakFile = getBakFile(slot)

        val savValid = if (savFile.exists()) readAndVerify(savFile) != null else null
        val bakValid = if (bakFile.exists()) readAndVerify(bakFile) != null else null

        return BackupIntegrity(
            primaryExists = savFile.exists(),
            backupExists = bakFile.exists(),
            primaryValid = savValid,
            backupValid = bakValid
        )
    }

    // ============================================================
    // 清理
    // ============================================================

    /** 启动时清理崩溃遗留的 .tmp 文件（超过 5 分钟视为遗留） */
    fun cleanupOrphanedTmp() {
        ensureInitialized()
        val now = System.currentTimeMillis()
        val files = backupDir.listFiles() ?: return
        var cleanedCount = 0
        for (file in files) {
            if (file.name.endsWith(".tmp") && now - file.lastModified() > 5 * 60 * 1000L) {
                if (file.delete()) cleanedCount++ else Log.w(TAG, "删除遗留 .tmp 失败: ${file.name}")
            }
        }
        if (cleanedCount > 0) {
            Log.i(TAG, "清理了 $cleanedCount 个遗留 .tmp 文件")
        }
    }

    /**
     * 清理孤儿备份文件。
     *
     * 只清理超过保留期的孤儿 .bak（对应 .sav 已不存在——主档已丢的废弃备份）。
     * .sav 本身永不清理（它是 DB 损坏时的恢复点）。
     */
    fun cleanExpiredBackups() {
        ensureInitialized()
        val now = System.currentTimeMillis()
        val cutoff = now - MAX_BACKUP_AGE_DAYS * 24 * 60 * 60 * 1000L
        val files = backupDir.listFiles() ?: return
        var cleanedCount = 0
        for (file in files) {
            val isOrphanBak = file.name.endsWith(".bak") &&
                file.lastModified() < cutoff &&
                !File(file.absolutePath.removeSuffix(".bak") + ".sav").exists()
            if (isOrphanBak) {
                if (file.delete()) cleanedCount++ else Log.w(TAG, "删除孤儿 .bak 失败: ${file.name}")
            }
        }
        if (cleanedCount > 0) {
            Log.i(TAG, "清理了 $cleanedCount 个孤儿 .bak 文件")
        }
    }

    /** 删除槽位的所有备份文件（删除存档时调用） */
    fun deleteSlot(slot: Int) {
        ensureInitialized()
        getSavFile(slot).delete()
        getBakFile(slot).delete()
        getTmpFile(slot).delete()
    }

    // ═══════════════════════════════════════════════════════════
    // 槽位删除 tombstone——跨 DB/文件原子删除
    // ═══════════════════════════════════════════════════════════

    /**
     * 标记槽位已删除（写 tombstone 文件）。
     *
     * delete() 跨 DB 与 .sav/.bak 非原子——DB 删除提交后、文件删除前崩溃
     * 会留下"DB 空、文件在"的窗口，下次 load 从备份复活已删存档。tombstone
     * 使两个崩溃窗口都收敛为"已删"语义：load 见到 tombstone 即返回空档。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun markSlotDeleted(slot: Int) {
        ensureInitialized()
        try {
            getTombstoneFile(slot).writeText(System.currentTimeMillis().toString())
        } catch (e: Exception) {
            Log.w(TAG, "写删除 tombstone 失败 slot=$slot", e)
        }
    }

    /** 槽位是否处于"已删除"状态（tombstone 存在） */
    fun isSlotDeleted(slot: Int): Boolean {
        if (!::backupDir.isInitialized) return false
        return getTombstoneFile(slot).exists()
    }

    /** 清除删除 tombstone（删除流程完整完成后调用） */
    fun clearSlotDeleted(slot: Int) {
        if (!::backupDir.isInitialized) return
        getTombstoneFile(slot).delete()
    }

    private fun getTombstoneFile(slot: Int): File = File(backupDir, "slot_${slot}.deleted")

    // ============================================================
    // 内部方法
    // ============================================================

    private fun ensureInitialized() {
        check(::backupDir.isInitialized) { "SaveFileManager 未初始化 — 请先调用 initialize()" }
    }


    private fun getSavFile(slot: Int): File = File(backupDir, "slot_${slot}.sav")
    private fun getBakFile(slot: Int): File = File(backupDir, "slot_${slot}.bak")
    private fun getTmpFile(slot: Int): File = File(backupDir, "slot_${slot}.sav.tmp")

    /**
     * write-tmp 原子写入：
     * 1. 写入 .tmp（FileOutputStream，无缓冲绕过）
     * 2. fsync 强制刷盘
     * 3. 关闭文件（rename 前置条件）
     */
    private fun writeFileAtomic(file: File, payload: ByteArray) {
        FileOutputStream(file).use { fos ->
            // 写入文件头（16 字节）
            val header = buildHeader(payload)
            fos.write(header)
            // 写入负载
            fos.write(payload)
            // fsync 强制刷盘
            fos.fd.sync()
        }
    }


    /**
     * 读取文件并校验 CRC。
     * @return payload（不含文件头），校验失败返回 null
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private fun readAndVerify(file: File): ByteArray? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < HEADER_SIZE) {
                Log.w(TAG, "文件过短: ${file.name} (${bytes.size} < $HEADER_SIZE)")
                return null
            }

            // 验证 Magic
            for (i in 0..3) {
                if (bytes[i] != MAGIC[i]) {
                    Log.w(TAG, "Magic 不匹配: ${file.name}")
                    return null
                }
            }

            // 格式版本（大端序）
            val formatVersion = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
            // 仅接受已知格式版本——任意未来版本若 CRC 碰巧正确会按当前格式静默误解析
            if (formatVersion != FORMAT_VERSION_LEGACY && formatVersion != FORMAT_VERSION) {
                Log.w(TAG, "未知格式版本判损坏: ${file.name} (0x${formatVersion.toString(16)})")
                return null
            }
            // CRC 算法标识（字节 11，仅 0x0101+ 有效；旧格式该字节为保留位 0）
            val algoByte = bytes[11].toInt() and 0xFF

            // 读取 CRC（大端序）
            val storedCrc = ((bytes[6].toInt() and 0xFF) shl 24) or
                    ((bytes[7].toInt() and 0xFF) shl 16) or
                    ((bytes[8].toInt() and 0xFF) shl 8) or
                    (bytes[9].toInt() and 0xFF)

            // 提取 payload
            val payload = bytes.copyOfRange(HEADER_SIZE, bytes.size)

            // 校验 CRC（0x0101 按标识精确校验；0x0100 旧格式双算法探测）
            if (!verifyCrc(storedCrc, payload, formatVersion, algoByte)) {
                Log.w(TAG, "CRC 不匹配: ${file.name} (stored=$storedCrc, version=${formatVersion.toString(16)})")
                return null
            }

            payload
        } catch (e: Exception) {
            Log.e(TAG, "读取文件失败: ${file.name}", e)
            null
        }
    }


}

// ============================================================
// 数据类
// ============================================================

/**
 * 带恢复来源的读取结果。
 * @param repairFailed RECOVERED 时 .bak→.sav 修复是否失败
 *   （true 表示 .sav 保持损坏、后续读取将持续回退 .bak，直至下次成功保存重写）
 */
data class BackupReadResult(
    val status: BackupStatus,
    val payload: ByteArray?,
    val source: String,  // "sav" | "bak" | "none"
    val repairFailed: Boolean = false
)

/** 读取状态 */
enum class BackupStatus {
    /** 主文件成功 */
    SUCCESS,
    /** 主文件损坏，从备份恢复 */
    RECOVERED,
    /** 主文件和备份均损坏或不存在 */
    CORRUPTED
}

/** 完整性校验结果 */
data class BackupIntegrity(
    val primaryExists: Boolean,
    val backupExists: Boolean,
    val primaryValid: Boolean?,
    val backupValid: Boolean?
)

private fun isValidSlot(slot: Int): Boolean = slot in 0..StorageConstants.DEFAULT_MAX_SLOTS
