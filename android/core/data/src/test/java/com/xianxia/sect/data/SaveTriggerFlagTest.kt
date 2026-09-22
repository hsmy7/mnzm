package com.xianxia.sect.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动保存触发判据守卫（审计 §16 #6 方案 A + SR-4 月变触发）。
 *
 * 锁三件事：
 * ① 两个默认值 = 存档重构方案 D6 的拍板形态（月变与 onStop 均**开**）——改动默认值
 *    必须重新对应到方案/用户拍板，不接受"顺手改"；
 * ② 判定是"三前置全真才落盘"，任一不满足即不保存（无槽位/引擎未加载时快照无意义）；
 * ③ 关闭态 = 回滚臂：旗标关 ⇒ 无论其他前置如何都不落盘（逐行回到"仅手动保存"的历史行为）。
 */
class SaveTriggerFlagTest {

    @Test
    fun `month-change auto-save is on by default per plan D6`() {
        assertTrue(
            "默认必须为 true（方案 D6 拍板：自动存档 = 游戏月月变钩子 + onStop）",
            SaveTriggerFlag.autoSaveOnMonthChange
        )
    }

    @Test
    fun `background save is on by default per plan D6`() {
        assertTrue(
            "默认必须为 true（D6 打开 870be9771 基建；关闭态仅作回滚臂保留）",
            SaveTriggerFlag.saveOnBackground
        )
    }

    @Test
    fun `no save when flag is off regardless of other preconditions`() {
        assertFalse(shouldAutoSave(flagOn = false, hasActiveSlot = true, engineLoaded = true))
    }

    @Test
    fun `no save without an active slot`() {
        assertFalse(shouldAutoSave(flagOn = true, hasActiveSlot = false, engineLoaded = true))
    }

    @Test
    fun `no save when engine is not loaded`() {
        assertFalse(shouldAutoSave(flagOn = true, hasActiveSlot = true, engineLoaded = false))
    }

    @Test
    fun `save only when all three preconditions hold`() {
        assertTrue(shouldAutoSave(flagOn = true, hasActiveSlot = true, engineLoaded = true))
    }
}
