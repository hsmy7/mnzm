package com.xianxia.sect.ui

import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.ui.model.SaveSelectMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SaveSlotDispatchTest — 存档槽位点击分发守卫（R0.4）。
 *
 * 守护目标：读取失败槽位（isLoadError）不得走加载路径（数据损坏，加载必失败），
 * 也不得被当作空档走"点击创建新游戏"（会静默覆盖损坏存档）。
 * 新建模式下允许覆盖确认弹窗——那是用户显式选择的重建路径。
 */
class SaveSlotDispatchTest {

    /** 记录分发结果的回调收集器 */
    private class DispatchRecorder {
        var cloudLoad: Int? = null
        var cloudInfo: Int? = null
        var newGameDialog: Int? = null
        var overwriteConfirm: Int? = null
        var loadSlot: Int? = null

        fun dispatch(slot: SaveSlot, mode: SaveSelectMode) = dispatchSlotClick(
            slot = slot,
            mode = mode,
            cloudSaveInfo = null,
            onCloudSaveLoad = { cloudLoad = slot.slot },
            onShowCloudInfo = { cloudInfo = slot.slot },
            onStartNewGameDialog = { newGameDialog = slot.slot },
            onShowOverwriteConfirm = { overwriteConfirm = slot.slot },
            onLoadSlot = { loadSlot = slot.slot }
        )
    }

    private fun slot(
        slotId: Int = 1,
        isEmpty: Boolean = false,
        isLoadError: Boolean = false
    ) = SaveSlot(
        slot = slotId,
        name = if (isEmpty || isLoadError) "" else "Save $slotId",
        timestamp = if (isEmpty || isLoadError) 0L else 1000L,
        gameYear = 1,
        gameMonth = 1,
        sectName = if (isEmpty || isLoadError) "" else "青云宗",
        discipleCount = 0,
        spiritStones = 0L,
        isEmpty = isEmpty,
        isLoadError = isLoadError
    )

    @Test
    fun `dispatchSlotClick - load error slot in LOAD mode does nothing`() {
        val recorder = DispatchRecorder()
        recorder.dispatch(slot(isLoadError = true), SaveSelectMode.LOAD_SAVE)
        assertNull("读取失败槽位点击必须无操作", recorder.loadSlot)
        assertNull(recorder.newGameDialog)
        assertNull(recorder.overwriteConfirm)
        assertNull(recorder.cloudLoad)
        assertNull(recorder.cloudInfo)
    }

    @Test
    fun `dispatchSlotClick - load error slot in NEW_GAME mode shows overwrite confirm`() {
        val recorder = DispatchRecorder()
        recorder.dispatch(slot(isLoadError = true), SaveSelectMode.NEW_GAME)
        assertEquals("新建模式下读取失败槽位走覆盖确认（显式重建路径）", 1, recorder.overwriteConfirm)
        assertNull(recorder.loadSlot)
    }

    @Test
    fun `dispatchSlotClick - empty slot in LOAD mode starts new game dialog`() {
        val recorder = DispatchRecorder()
        recorder.dispatch(slot(isEmpty = true), SaveSelectMode.LOAD_SAVE)
        assertEquals(1, recorder.newGameDialog)
    }

    @Test
    fun `dispatchSlotClick - filled slot in LOAD mode loads`() {
        val recorder = DispatchRecorder()
        recorder.dispatch(slot(), SaveSelectMode.LOAD_SAVE)
        assertEquals(1, recorder.loadSlot)
    }

    @Test
    fun `dispatchSlotClick - filled slot in NEW_GAME mode shows overwrite confirm`() {
        val recorder = DispatchRecorder()
        recorder.dispatch(slot(), SaveSelectMode.NEW_GAME)
        assertEquals(1, recorder.overwriteConfirm)
    }
}
