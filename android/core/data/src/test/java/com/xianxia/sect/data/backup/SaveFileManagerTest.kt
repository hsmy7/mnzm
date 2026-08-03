package com.xianxia.sect.data.backup

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.CRC32C

/**
 * SaveFileManager 单元测试。
 *
 * 测试策略：
 * - 使用 JUnit TemporaryFolder 管理临时文件
 * - 直接构造合法/非法的备份文件验证 CRC32C 校验和原子写入
 * - serialization 层通过自定义 SaveSerializer 绕过（readWithFallback 返回原始字节）
 * - 固定 SDK 34：生产代码 API 34+ 用 CRC32C、更低版本回退 CRC32，
 *   辅助函数直接写 CRC32C，测试环境必须对齐 API 34 分支
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveFileManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var manager: SaveFileManager

    @Before
    fun setUp() {
        val fakeSerializer = SaveSerializer { data -> data.gameData.sectName.encodeToByteArray() }
        manager = SaveFileManager(
            saveSerializer = fakeSerializer
        ).also {
            it.initialize(tempFolder.root)
        }
    }

    @Test
    fun `valid CRC32C passes verification`() {
        val slot = 1
        val payload = "test-payload-data".encodeToByteArray()
        writeValidSavFile(slot, payload)

        val result = manager.readWithFallback(slot)
        assertEquals("SUCCESS 状态", BackupStatus.SUCCESS, result.status)
        assertNotNull("payload 非空", result.payload)
        assertArrayEquals("payload 内容一致", payload, result.payload)
        assertEquals("来源为 sav", "sav", result.source)
    }

    @Test
    fun `corrupted sav falls back to bak`() {
        val slot = 2
        val payload = "original-data".encodeToByteArray()
        val bakPayload = "backup-data".encodeToByteArray()

        // 写入合法 .sav
        writeValidSavFile(slot, payload)
        // 写入合法 .bak
        writeValidBakFile(slot, bakPayload)
        // 破坏 .sav（覆盖一个字节）
        val savFile = getSavFile(slot)
        val corrupted = savFile.readBytes()
        corrupted[16] = (corrupted[16].toInt() xor 0xFF).toByte() // 翻转 payload 首字节
        savFile.writeBytes(corrupted)

        val result = manager.readWithFallback(slot)
        assertEquals("RECOVERED 状态", BackupStatus.RECOVERED, result.status)
        assertNotNull("从 bak 恢复的 payload 非空", result.payload)
        assertArrayEquals("payload 内容为 bak 数据", bakPayload, result.payload)
        assertEquals("来源为 bak", "bak", result.source)
    }

    @Test
    fun `both files corrupted returns CORRUPTED`() {
        val slot = 3
        val payload = "data".encodeToByteArray()

        // 写入并破坏 .sav
        writeValidSavFile(slot, payload)
        val savFile = getSavFile(slot)
        savFile.writeBytes(byteArrayOf(0, 0, 0, 0)) // 完全破坏

        // 写入并破坏 .bak
        writeValidBakFile(slot, payload)
        val bakFile = getBakFile(slot)
        bakFile.writeBytes(byteArrayOf(0, 0, 0, 0)) // 完全破坏

        val result = manager.readWithFallback(slot)
        assertEquals("CORRUPTED 状态", BackupStatus.CORRUPTED, result.status)
        assertNull("payload 为空", result.payload)
    }

    @Test
    fun `truncated file fails CRC32C`() {
        val slot = 4
        val payload = "test".encodeToByteArray()
        writeValidSavFile(slot, payload)

        // 截断文件（去掉 payload 后半部分）
        val savFile = getSavFile(slot)
        val truncated = savFile.readBytes().copyOfRange(0, 18) // 只有头部 + 2 字节 payload
        savFile.writeBytes(truncated)

        val result = manager.readWithFallback(slot)
        assertEquals("CRC32C 检测到截断", BackupStatus.CORRUPTED, result.status)
    }

    @Test
    fun `invalid magic fails verification`() {
        val slot = 5
        val payload = "data".encodeToByteArray()
        writeValidSavFile(slot, payload)

        // 破坏 Magic 字节
        val savFile = getSavFile(slot)
        val corrupted = savFile.readBytes()
        corrupted[0] = 0x00 // 原本是 0x58
        savFile.writeBytes(corrupted)

        val result = manager.readWithFallback(slot)
        assertEquals("Magic 不匹配", BackupStatus.CORRUPTED, result.status)
    }

    @Test
    fun `tmp cleanup removes stale tmp files`() {
        val slot = 6
        val tmpFile = File(tempFolder.root, "saves/slot_${slot}.sav.tmp")
        tmpFile.writeBytes(byteArrayOf(1, 2, 3))
        // 修改文件时间为 10 分钟前（超过 5 分钟的阈值）
        tmpFile.setLastModified(System.currentTimeMillis() - 10 * 60 * 1000L)

        manager.cleanupOrphanedTmp()

        assertFalse("遗留 .tmp 已清理", tmpFile.exists())
    }

    @Test
    fun `tmp cleanup preserves recent temp files`() {
        val slot = 7
        val tmpFile = File(tempFolder.root, "saves/slot_${slot}.sav.tmp")
        tmpFile.writeBytes(byteArrayOf(1, 2, 3))
        // 修改文件时间为 1 分钟前（不超过 5 分钟的阈值）
        tmpFile.setLastModified(System.currentTimeMillis() - 60 * 1000L)

        manager.cleanupOrphanedTmp()

        assertTrue("近期 .tmp 保留", tmpFile.exists())
        tmpFile.delete()
    }

    @Test
    fun `verifySlot detects valid and missing files`() {
        val slot = 8
        val payload = "verify-test".encodeToByteArray()
        writeValidSavFile(slot, payload)

        // 只有 .sav，无 .bak
        val integrity = manager.verifySlot(slot)
        assertTrue("primary 存在", integrity.primaryExists)
        assertFalse("backup 不存在", integrity.backupExists)
        assertTrue("primary 校验有效", integrity.primaryValid == true)
    }

    @Test
    fun `no files returns CORRUPTED`() {
        val slot = 99
        val result = manager.readWithFallback(slot)
        assertEquals("无文件时返回 CORRUPTED", BackupStatus.CORRUPTED, result.status)
    }

    // ============================================================
    // 辅助方法：直接构造合法备份文件
    // ============================================================

    private fun writeValidSavFile(slot: Int, payload: ByteArray) {
        val file = getSavFile(slot)
        file.parentFile?.mkdirs()
        file.writeBytes(buildValidHeader(payload) + payload)
    }

    private fun writeValidBakFile(slot: Int, payload: ByteArray) {
        val file = getBakFile(slot)
        file.parentFile?.mkdirs()
        file.writeBytes(buildValidHeader(payload) + payload)
    }

    private fun buildValidHeader(payload: ByteArray): ByteArray {
        val crc32c = computeCrc32c(payload)
        val header = ByteArray(16)

        // Magic: XSBK
        header[0] = 0x58; header[1] = 0x53; header[2] = 0x42; header[3] = 0x4B
        // Format version
        header[4] = 0x01; header[5] = 0x00
        // CRC32C (big-endian)
        header[6] = ((crc32c shr 24) and 0xFF).toByte()
        header[7] = ((crc32c shr 16) and 0xFF).toByte()
        header[8] = ((crc32c shr 8) and 0xFF).toByte()
        header[9] = (crc32c and 0xFF).toByte()
        // Flags: compressed
        header[10] = 0x01; header[11] = 0x00
        // Payload length (big-endian)
        val len = payload.size
        header[12] = ((len shr 24) and 0xFF).toByte()
        header[13] = ((len shr 16) and 0xFF).toByte()
        header[14] = ((len shr 8) and 0xFF).toByte()
        header[15] = (len and 0xFF).toByte()

        return header
    }

    private fun computeCrc32c(data: ByteArray): Int {
        val crc = CRC32C()
        crc.update(data)
        return crc.value.toInt()
    }

    private fun getSavFile(slot: Int) = File(tempFolder.root, "saves/slot_${slot}.sav")
    private fun getBakFile(slot: Int) = File(tempFolder.root, "saves/slot_${slot}.bak")
}
