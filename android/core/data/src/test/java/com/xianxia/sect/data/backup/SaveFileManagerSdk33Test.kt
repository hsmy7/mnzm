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
