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
     * 流程（主保存无条件执行，超限只跳过备份）：
     * 1. 序列化 payload → 写入 .tmp 文件 (FileOutputStream)
     * 2. fsync 强制刷盘
     * 3. 重命名 .tmp → .sav（同一文件系统上的原子操作）——**主保存必执行**
     * 4. payload 超 100MB → 跳过 .bak 写入，返回 [StorageResult.Skipped]（不再谎报成功）
     * 5. 复制 .sav → .bak（保留历史快照）
     * 6. 删除 .tmp（清理）
     *
     * payload 超限时仍必须先完成 .sav 主保存，仅跳过 .bak 备份写入。
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

            // 4. 检查备份文件大小限制（主保存已成功，跳过备份不阻断）
            if (payload.size > MAX_BACKUP_SIZE_MB * 1024 * 1024) {
                Log.w(TAG, "存档数据过大 (${payload.size / 1024 / 1024}MB)，跳过备份写入（主保存已成功）")
                return StorageResult.skipped(
                    "备份因超过 ${MAX_BACKUP_SIZE_MB}MB 上限被跳过（主保存成功，非阻断）"
                )
            }

            // 5. 原子写 .bak（write-tmp → rename）
            val bakTmpFile = getBakTmpFile(slot)
            writeFileAtomic(bakTmpFile, payload)
            bakFile.delete()
            if (!bakTmpFile.renameTo(bakFile)) {
                bakTmpFile.delete()
                Log.w(TAG, ".bak rename 失败 slot=$slot（非阻断，.sav 仍有效）")
            }

            // 6. 清除残留 .tmp
            if (tmpFile.exists()) tmpFile.delete()

            StorageResult.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "备份写入失败 slot=$slot", e)
            // 清理残留 .tmp
            if (getTmpFile(slot).exists()) getTmpFile(slot).delete()
            // 备份失败不阻断主保存——返回 failure 但由调用方决定是否中断
            StorageResult.failure(StorageError.BACKUP_FAILED, "备份写入失败: ${e.message}")
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
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun readWithFallback(slot: Int): BackupReadResult {
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
                var repairFailed = false
                try {
                    bakFile.copyTo(savFile, overwrite = true)
                } catch (e: Exception) {
                    repairFailed = true
                    Log.e(TAG, "修复 .sav 失败 slot=$slot——将持续回退 .bak 直至下次成功保存", e)
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
    // 信息查询
    // ============================================================

    /** 获取备份信息（用于 UI 展示） */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun getBackupInfo(slot: Int): BackupInfo? {
        ensureInitialized()
        if (!isValidSlot(slot)) return null

        val savFile = getSavFile(slot)
        val bakFile = getBakFile(slot)

        return try {
            BackupInfo(
                slot = slot,
                primaryExists = savFile.exists(),
                backupExists = bakFile.exists(),
                primaryValid = null, // 惰性校验：用户点击时再调用 verifySlot
                backupValid = null,
                primaryTimestamp = if (savFile.exists()) savFile.lastModified() else null,
                backupTimestamp = if (bakFile.exists()) bakFile.lastModified() else null,
                primarySizeBytes = if (savFile.exists()) savFile.length() else null,
                backupSizeBytes = if (bakFile.exists()) bakFile.length() else null
            )
        } catch (e: Exception) {
            Log.w(TAG, "获取备份信息失败 slot=$slot", e)
            null
        }
    }

    // ============================================================
    // 内部方法
    // ============================================================

    private fun ensureInitialized() {
        check(::backupDir.isInitialized) { "SaveFileManager 未初始化 — 请先调用 initialize()" }
    }


    private fun getSavFile(slot: Int): File = File(backupDir, "slot_${slot}.sav")
    private fun getBakFile(slot: Int): File = File(backupDir, "slot_${slot}.bak")
    private fun getBakTmpFile(slot: Int): File = File(backupDir, "slot_${slot}.bak.tmp")
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

/** 备份信息（用于 UI 展示） */
data class BackupInfo(
    val slot: Int,
    val primaryExists: Boolean,
    val backupExists: Boolean,
    val primaryValid: Boolean?,
    val backupValid: Boolean?,
    val primaryTimestamp: Long?,
    val backupTimestamp: Long?,
    val primarySizeBytes: Long?,
    val backupSizeBytes: Long?
)

private fun isValidSlot(slot: Int): Boolean = slot in 0..StorageConstants.DEFAULT_MAX_SLOTS
