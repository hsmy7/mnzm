package com.xianxia.sect.data.backup

import com.xianxia.sect.data.result.StorageResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SaveFileManager API<34（CRC32 分支）测试（T8 2026-08-05）。
 *
 * API<34 设备无 java.util.zip.CRC32C，写入走 CRC32 + 0x0101 格式的算法标识（0=CRC32）；
 * 读取按标识精确校验，保证与 API≥34 设备（CRC32C）跨 API 一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SaveFileManagerSdk33Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `roundtrip on sdk 33 uses CRC32 branch and passes`() {
        val manager = SaveFileManager(
            saveSerializer = SaveSerializer { data -> data.gameData.sectName.encodeToByteArray() }
        ).also { it.initialize(tempFolder.root) }
        val slot = 1

        val writeResult = manager.atomicWrite(slot, mockSaveData())
        assertTrue("sdk 33 写入应成功，实际 $writeResult", writeResult is StorageResult.Success)

        val read = manager.readWithFallback(slot)
        assertEquals(BackupStatus.SUCCESS, read.status)
    }

    @Test
    fun `api 34 written CRC32C file readable on sdk 33`() {
        // 对抗性审查整改（2026-08-05）：自实现 CRC32C 前，API≥34 设备写 algo=1(CRC32C)
        // 文件在 API<34 设备无 CRC32C 实现必判损坏（反向换机数据丢失）——现必须可读
        val manager = SaveFileManager(
            saveSerializer = SaveSerializer { data -> data.gameData.sectName.encodeToByteArray() }
        ).also { it.initialize(tempFolder.root) }
        val slot = 2
        val payload = "cross-api-crc32c".encodeToByteArray()

        // 模拟 API≥34 设备：0x0101 + algo=1 + java.util.zip.CRC32C 计算的校验值
        val crc = java.util.zip.CRC32C().apply { update(payload) }.value.toInt()
        val header = ByteArray(16)
        header[0] = 0x58; header[1] = 0x53; header[2] = 0x42; header[3] = 0x4B
        header[4] = 0x01; header[5] = 0x01 // 0x0101
        header[6] = ((crc shr 24) and 0xFF).toByte()
        header[7] = ((crc shr 16) and 0xFF).toByte()
        header[8] = ((crc shr 8) and 0xFF).toByte()
        header[9] = (crc and 0xFF).toByte()
        header[10] = 0x01
        header[11] = 1 // algo=CRC32C
        val len = payload.size
        header[12] = ((len shr 24) and 0xFF).toByte()
        header[13] = ((len shr 16) and 0xFF).toByte()
        header[14] = ((len shr 8) and 0xFF).toByte()
        header[15] = (len and 0xFF).toByte()
        java.io.File(tempFolder.root, "saves/slot_$slot.sav").apply {
            parentFile?.mkdirs()
            writeBytes(header + payload)
        }

        val result = manager.readWithFallback(slot)
        assertEquals("API≥34 写的 CRC32C 文件在 API<34 应可读", BackupStatus.SUCCESS, result.status)
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
}
