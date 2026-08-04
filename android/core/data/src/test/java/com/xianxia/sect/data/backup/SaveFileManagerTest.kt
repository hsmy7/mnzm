package com.xianxia.sect.data.backup

import com.xianxia.sect.data.result.StorageResult
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

    /** 与 SaveFileManager 的 MAX_BACKUP_SIZE_MB 对齐（测试超限场景） */
    private companion object {
        const val MAX_BACKUP_SIZE_MB = 100
        const val MAX_BACKUP_SIZE_BYTES = MAX_BACKUP_SIZE_MB * 1024 * 1024
    }

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

    @Test
    fun `initialize is idempotent - repeated calls do not throw`() {
        // 2026-08-04 接线修复：StorageFacade.initialize 可能重复调用
        manager.initialize(tempFolder.root)

        assertTrue("重复初始化后备份目录仍存在", File(tempFolder.root, "saves").exists())

        // 重复初始化后文件操作仍正常（slot 须在 0..DEFAULT_MAX_SLOTS 内）
        val slot = 5
        writeValidSavFile(slot, "after-reinit".encodeToByteArray())
        val result = manager.readWithFallback(slot)
        assertEquals("重复初始化后文件操作正常", BackupStatus.SUCCESS, result.status)
    }

    @Test
    fun `uninitialized manager throws IllegalStateException on file operations`() {
        // 守卫语义保留：未初始化时文件操作必须显式失败（而非静默写错位置）
        val uninitialized = SaveFileManager(
            saveSerializer = SaveSerializer { data -> data.gameData.sectName.encodeToByteArray() }
        )

        assertThrows(IllegalStateException::class.java) { uninitialized.readWithFallback(1) }
        assertThrows(IllegalStateException::class.java) { uninitialized.atomicWrite(1, mockSaveData()) }
    }

    // ============================================================
    // T8（2026-08-05）：CRC 算法跨 API 一致性
    // ============================================================

    @Test
    fun `legacy 0x0100 header with CRC32 read on sdk 34 passes`() {
        // 根因场景：API<34 设备（旧 App 写 CRC32）换机到 API≥34 设备，
        // 旧格式无算法标识 → 双算法探测（CRC32 命中）
        val slot = 2
        val payload = "legacy-crc32-payload".encodeToByteArray()
        val file = getSavFile(slot)
        file.parentFile?.mkdirs()
        file.writeBytes(buildLegacyHeader(payload, useCrc32c = false) + payload)

        val result = manager.readWithFallback(slot)
        assertEquals(BackupStatus.SUCCESS, result.status)
        assertArrayEquals(payload, result.payload)
    }

    @Test
    fun `legacy 0x0100 header with CRC32C read on sdk 34 passes`() {
        // 同设备旧格式（API≥34 写 CRC32C）兼容
        val slot = 3
        val payload = "legacy-crc32c-payload".encodeToByteArray()
        val file = getSavFile(slot)
        file.parentFile?.mkdirs()
        file.writeBytes(buildLegacyHeader(payload, useCrc32c = true) + payload)

        val result = manager.readWithFallback(slot)
        assertEquals(BackupStatus.SUCCESS, result.status)
        assertArrayEquals(payload, result.payload)
    }

    @Test
    fun `new format with unknown algorithm byte rejected`() {
        // 0x0101 + 未知算法标识 → 判损坏（安全侧）
        val slot = 4
        val payload = "bad-algo".encodeToByteArray()
        val file = getSavFile(slot)
        file.parentFile?.mkdirs()
        val header = buildValidHeader(payload)
        header[4] = 0x01; header[5] = 0x01 // 升级为 0x0101
        header[11] = 0x02 // 未知算法标识
        file.writeBytes(header + payload)

        val result = manager.readWithFallback(slot)
        assertEquals(BackupStatus.CORRUPTED, result.status)
    }

    @Test
    fun `unknown future format version rejected`() {
        // C7 修复：任意未来版本（0xFFFF）即使 CRC 正确也必须判损坏，
        // 防止格式演进后旧 App 按当前格式静默误解析新文件
        val slot = 10
        val payload = "future-format".encodeToByteArray()
        val file = getSavFile(slot)
        file.parentFile?.mkdirs()
        val header = buildValidHeader(payload)
        header[4] = 0xFF.toByte(); header[5] = 0xFF.toByte() // 未来版本 0xFFFF（CRC 仍正确）
        file.writeBytes(header + payload)

        val result = manager.readWithFallback(slot)
        assertEquals(BackupStatus.CORRUPTED, result.status)
    }

    @Test
    fun `new format roundtrip write then read succeeds`() {
        // 0x0101 写入（sdk 34 → CRC32C + 算法标识）→ 读取精确校验通过
        val slot = 5
        val writeResult = manager.atomicWrite(slot, mockSaveData())
        assertTrue(writeResult is StorageResult.Success)

        val read = manager.readWithFallback(slot)
        assertEquals(BackupStatus.SUCCESS, read.status)
    }

    // ============================================================
    // T9（2026-08-05）：超限跳过备份但主保存必写
    // ============================================================

    @Test
    fun `oversized payload writes main sav and returns Skipped`() {
        // 修复前：超限时主保存+备份一并跳过且返回 success（静默丢档）
        // 修复后：主保存必执行，备份跳过并如实返回 Skipped
        val slot = 0
        val bigManager = SaveFileManager(
            saveSerializer = SaveSerializer { data -> ByteArray(MAX_BACKUP_SIZE_BYTES + 1) }
        ).also { it.initialize(tempFolder.root) }

        val result = bigManager.atomicWrite(slot, mockSaveData())

        assertTrue("应返回 Skipped，实际 $result", result is StorageResult.Skipped)
        assertTrue("主 .sav 必须存在", getSavFile(slot).exists())
        assertFalse("备份 .bak 不写入", getBakFile(slot).exists())
    }

    @Test
    fun `normal size write returns Success and writes both files`() {
        val slot = 6
        val result = manager.atomicWrite(slot, mockSaveData())

        assertTrue("应返回 Success，实际 $result", result is StorageResult.Success)
        assertTrue("主 .sav 存在", getSavFile(slot).exists())
        assertTrue("备份 .bak 存在", getBakFile(slot).exists())
    }

    // ============================================================
    // C11（2026-08-05）：rename 原子覆盖优先，消除 delete-rename 崩溃窗口
    // ============================================================

    @Test
    fun `atomicWrite overwrites existing sav without delete window`() {
        // C11 修复前：先 delete() 再 renameTo——两者之间崩溃 → .sav 缺失走 .bak
        // 修复后：先试无 delete 的 rename 原子覆盖，.sav 全程存在
        val slot = 2
        manager.atomicWrite(slot, mockSaveData())
        val firstRead = manager.readWithFallback(slot)
        assertEquals("首次写入 SUCCESS", BackupStatus.SUCCESS, firstRead.status)

        // 覆盖写入（已有 .sav 的场景）
        val overwriteManager = SaveFileManager(
            saveSerializer = SaveSerializer { data -> "第二版:${data.gameData.sectName}".encodeToByteArray() }
        ).also { it.initialize(tempFolder.root) }
        val second = overwriteManager.atomicWrite(slot, mockSaveData())
        assertTrue("覆盖写入应 Success，实际 $second", second is StorageResult.Success)

        val reread = overwriteManager.readWithFallback(slot)
        assertEquals("覆盖后读取 SUCCESS", BackupStatus.SUCCESS, reread.status)
        assertArrayEquals("读取到第二版内容", "第二版:测试宗".encodeToByteArray(), reread.payload)
    }

    // ============================================================
    // C6（2026-08-05）：备份修复失败如实反馈
    // ============================================================

    @Test
    fun `backup recovered but sav repair failure signaled`() {
        val slot = 3
        val bakPayload = "backup-payload".encodeToByteArray()
        writeValidBakFile(slot, bakPayload)

        // .sav 损坏
        val savFile = getSavFile(slot)
        savFile.parentFile?.mkdirs()
        savFile.writeBytes(byteArrayOf(0, 0, 0, 0))

        // copyTo 修复失败路径：把 .sav 路径换成**非空目录**（copyTo overwrite 先 delete 必失败）
        savFile.delete()
        savFile.mkdirs()
        File(savFile, "blocker").writeBytes(byteArrayOf(1))

        val result = manager.readWithFallback(slot)
        assertEquals("RECOVERED 状态", BackupStatus.RECOVERED, result.status)
        assertArrayEquals("payload 为 bak 数据", bakPayload, result.payload)
        assertTrue("修复失败必须如实标记", result.repairFailed)
    }

    @Test
    fun `backup recovered with successful repair - repairFailed false`() {
        val slot = 4
        val bakPayload = "repairable-payload".encodeToByteArray()
        writeValidSavFile(slot, "corrupted-old".encodeToByteArray())
        writeValidBakFile(slot, bakPayload)
        // 破坏 .sav 但保留可覆盖路径（copyTo 能成功）
        val savFile = getSavFile(slot)
        val corrupted = savFile.readBytes()
        corrupted[16] = (corrupted[16].toInt() xor 0xFF).toByte()
        savFile.writeBytes(corrupted)

        val result = manager.readWithFallback(slot)
        assertEquals("RECOVERED 状态", BackupStatus.RECOVERED, result.status)
        assertFalse("修复成功 repairFailed 应为 false", result.repairFailed)
        // .sav 已被 .bak 覆盖修复
        val reread = manager.readWithFallback(slot)
        assertEquals("修复后 .sav 可直读", BackupStatus.SUCCESS, reread.status)
    }

    private fun mockSaveData(): com.xianxia.sect.data.model.SaveData {
        return com.xianxia.sect.data.model.SaveData(
            version = "test",
            gameData = com.xianxia.sect.core.model.GameData(sectName = "测试宗"),
            disciples = emptyList(),
            equipmentStacks = emptyList(),
            equipmentInstances = emptyList(),
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            storageBags = emptyList(),
            teams = emptyList(),
            battleLogs = emptyList(),
            alliances = emptyList(),
            productionSlots = emptyList()
        )
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

    /** 构造旧格式（0x0100）文件头，可选 CRC32/CRC32C（旧 App 按 SDK 分支写入） */
    private fun buildLegacyHeader(payload: ByteArray, useCrc32c: Boolean): ByteArray {
        val crc = if (useCrc32c) computeCrc32c(payload) else computeCrc32(payload)
        val header = buildValidHeader(payload)
        header[6] = ((crc shr 24) and 0xFF).toByte()
        header[7] = ((crc shr 16) and 0xFF).toByte()
        header[8] = ((crc shr 8) and 0xFF).toByte()
        header[9] = (crc and 0xFF).toByte()
        return header
    }

    private fun computeCrc32(data: ByteArray): Int {
        val crc = java.util.zip.CRC32()
        crc.update(data)
        return crc.value.toInt()
    }

    private fun computeCrc32c(data: ByteArray): Int {
        val crc = CRC32C()
        crc.update(data)
        return crc.value.toInt()
    }

    private fun getSavFile(slot: Int) = File(tempFolder.root, "saves/slot_${slot}.sav")
    private fun getBakFile(slot: Int) = File(tempFolder.root, "saves/slot_${slot}.bak")
}
