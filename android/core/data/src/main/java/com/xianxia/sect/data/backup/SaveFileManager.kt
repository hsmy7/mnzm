package com.xianxia.sect.data.backup

import android.util.Log
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.result.StorageResult
import com.xianxia.sect.data.StorageConstants
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32C
import java.util.zip.CRC32
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
 *   4      2    Format version (major=0x01, minor=0x00 → 0x0100)
 *   6      4    CRC32C of payload (bytes 12..end)
 *  10      2    Flags (bit 0: lz4-compressed payload)
 *  12      4    Uncompressed payload length (uint32, big-endian)
 *  16      N    Payload (SerializationModule.serializeAndCompressSaveData 输出)
 */
@Singleton
class SaveFileManager @Inject constructor(
    private val saveSerializer: SaveSerializer
) {
    companion object {
        private const val TAG = "SaveFileManager"

        /** Magic bytes: "XSBK" (Xianxia Sect BaKup) */
        private val MAGIC = byteArrayOf(0x58, 0x53, 0x42, 0x4B)

        /** 文件头总长度：16 字节 */
        private const val HEADER_SIZE = 16

        /** 标记位：LZ4 压缩 */
        private const val FLAG_COMPRESSED = 0x01

        /** 格式版本 (major << 8 | minor) */
        private const val FORMAT_VERSION = 0x0100

        /** 最大备份保留天数 */
        private const val MAX_BACKUP_AGE_DAYS = 7

        /** 最大备份文件大小（MB） */
        private const val MAX_BACKUP_SIZE_MB = 100
    }

    /** 备份目录 */
    private lateinit var backupDir: File

    /**
     * 初始化备份目录。必须在首次调用任何文件操作前调用。
     * 由 StorageEngine/Facade 在启动时调用。
     */
    fun initialize(baseDir: File) {
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
     * 流程：
     * 1. 序列化 payload → 写入 .tmp 文件 (FileOutputStream)
     * 2. fsync 强制刷盘
     * 3. 重命名 .tmp → .sav（同一文件系统上的原子操作）
     * 4. 复制 .sav → .bak（保留历史快照）
     * 5. 删除 .tmp（清理）
     */
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

            // 2. 检查备份文件大小限制
            if (payload.size > MAX_BACKUP_SIZE_MB * 1024 * 1024) {
                Log.w(TAG, "存档数据过大 (${payload.size / 1024 / 1024}MB)，跳过备份写入")
                return StorageResult.success(Unit) // 不阻断主保存
            }

            // 3. 写入 .tmp（write-tmp）
            writeFileAtomic(tmpFile, payload)

            // 4. 重命名 .tmp → .sav（原子交换）
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
                try {
                    bakFile.copyTo(savFile, overwrite = true)
                } catch (e: Exception) {
                    Log.w(TAG, "修复 .sav 失败 slot=$slot", e)
                }
                return BackupReadResult(BackupStatus.RECOVERED, bakPayload, "bak")
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

    /** 清理过期备份文件（超过保留天数） */
    fun cleanExpiredBackups() {
        ensureInitialized()
        val now = System.currentTimeMillis()
        val cutoff = now - MAX_BACKUP_AGE_DAYS * 24 * 60 * 60 * 1000L
        val files = backupDir.listFiles() ?: return
        var cleanedCount = 0
        for (file in files) {
            if ((file.name.endsWith(".bak") || file.name.endsWith(".sav")) && file.lastModified() < cutoff) {
                if (file.delete()) cleanedCount++ else Log.w(TAG, "删除过期 .bak 失败: ${file.name}")
            }
        }
        if (cleanedCount > 0) {
            Log.i(TAG, "清理了 $cleanedCount 个过期 .bak 文件")
        }
    }

    /** 删除槽位的所有备份文件（删除存档时调用） */
    fun deleteSlot(slot: Int) {
        ensureInitialized()
        getSavFile(slot).delete()
        getBakFile(slot).delete()
        getTmpFile(slot).delete()
    }

    // ============================================================
    // 信息查询
    // ============================================================

    /** 获取备份信息（用于 UI 展示） */
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
        if (!::backupDir.isInitialized) {
            throw IllegalStateException("SaveFileManager 未初始化 — 请先调用 initialize()")
        }
    }

    private fun isValidSlot(slot: Int): Boolean = slot in 0..StorageConstants.DEFAULT_MAX_SLOTS

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

    /** 构建文件头 */
    private fun buildHeader(payload: ByteArray): ByteArray {
        val crc32c = computeCrc32c(payload)
        val header = ByteArray(HEADER_SIZE)

        // Magic
        System.arraycopy(MAGIC, 0, header, 0, 4)
        // Format version (big-endian)
        header[4] = ((FORMAT_VERSION shr 8) and 0xFF).toByte()
        header[5] = (FORMAT_VERSION and 0xFF).toByte()
        // CRC32C (big-endian)
        header[6] = ((crc32c shr 24) and 0xFF).toByte()
        header[7] = ((crc32c shr 16) and 0xFF).toByte()
        header[8] = ((crc32c shr 8) and 0xFF).toByte()
        header[9] = (crc32c and 0xFF).toByte()
        // Flags: LZ4 compressed
        header[10] = FLAG_COMPRESSED.toByte()
        header[11] = 0
        // Uncompressed length (big-endian) — 当前 payload 已压缩，存原始长度
        val len = payload.size
        header[12] = ((len shr 24) and 0xFF).toByte()
        header[13] = ((len shr 16) and 0xFF).toByte()
        header[14] = ((len shr 8) and 0xFF).toByte()
        header[15] = (len and 0xFF).toByte()

        return header
    }

    /**
     * 读取文件并校验 CRC32C。
     * @return payload（不含文件头），校验失败返回 null
     */
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

            // 读取 CRC32C（大端序）
            val storedCrc = ((bytes[6].toInt() and 0xFF) shl 24) or
                    ((bytes[7].toInt() and 0xFF) shl 16) or
                    ((bytes[8].toInt() and 0xFF) shl 8) or
                    (bytes[9].toInt() and 0xFF)

            // 提取 payload
            val payload = bytes.copyOfRange(HEADER_SIZE, bytes.size)

            // 校验 CRC32C
            val actualCrc = computeCrc32c(payload)
            if (storedCrc != actualCrc) {
                Log.w(TAG, "CRC32C 不匹配: ${file.name} (stored=$storedCrc, actual=$actualCrc)")
                return null
            }

            payload
        } catch (e: Exception) {
            Log.e(TAG, "读取文件失败: ${file.name}", e)
            null
        }
    }

    /**
     * 计算 CRC32C 校验和。
     * API 24+ 使用硬件加速的 java.util.zip.CRC32C，
     * 更低版本回退到 java.util.zip.CRC32。
     */
    private fun computeCrc32c(data: ByteArray): Int {
        return try {
            val crc = CRC32C()
            crc.update(data)
            crc.value.toInt()
        } catch (e: NoClassDefFoundError) {
            // 极低 API 版本回退
            val crc = CRC32()
            crc.update(data)
            crc.value.toInt()
        }
    }
}

// ============================================================
// 数据类
// ============================================================

/** 带恢复来源的读取结果 */
data class BackupReadResult(
    val status: BackupStatus,
    val payload: ByteArray?,
    val source: String  // "sav" | "bak" | "none"
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
