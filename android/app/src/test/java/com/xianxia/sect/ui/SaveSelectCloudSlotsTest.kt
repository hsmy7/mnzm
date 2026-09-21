package com.xianxia.sect.ui

import com.xianxia.sect.data.cloud.CloudSaveEntry
import com.xianxia.sect.data.cloud.CloudSaveSummary
import com.xianxia.sect.ui.model.SaveSelectMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SaveSelectCloudSlotsTest — 云槽位卡可见性守卫（SR-3）。
 *
 * 守护目标：
 * 1. LOAD_SAVE 模式显示云端槽位存档（slot_N 列表原样透传，排序不动）；
 * 2. NEW_GAME 模式隐藏云槽位卡（新游戏=建本地档，云端槽位与新游戏无关）；
 * 3. LEGACY 空列表透传（上游 queryCloudSlotEntries 已模式门控，UI 层不重复判定）。
 */
class SaveSelectCloudSlotsTest {

    private fun entry(slot: Int, sect: String = "青云宗") = CloudSaveEntry(
        slot = slot,
        archiveName = "slot_$slot",
        saveId = slot.toLong(),
        sizeBytes = 1024L,
        modifiedTimeMs = 1_700_000_000_000L,
        summary = CloudSaveSummary(
            gameYear = 3,
            gameMonth = 2,
            sectName = sect,
            discipleCount = 10,
            spiritStones = 500L,
            appVersion = "1.0.0"
        )
    )

    @Test
    fun `load save mode shows cloud slots in order`() {
        val slots = listOf(entry(1), entry(3), entry(6))
        assertEquals(slots, visibleCloudSlots(SaveSelectMode.LOAD_SAVE, slots))
    }

    @Test
    fun `new game mode hides cloud slots`() {
        val slots = listOf(entry(1), entry(2))
        assertTrue(visibleCloudSlots(SaveSelectMode.NEW_GAME, slots).isEmpty())
    }

    @Test
    fun `empty list stays empty in both modes`() {
        assertTrue(visibleCloudSlots(SaveSelectMode.LOAD_SAVE, emptyList()).isEmpty())
        assertTrue(visibleCloudSlots(SaveSelectMode.NEW_GAME, emptyList()).isEmpty())
    }
}
