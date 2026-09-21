package com.xianxia.sect.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台保存触发判据守卫（审计 §16 #6 方案 A）。
 *
 * 硬要求：**旗标默认关 ⇒ 零行为变更**。本测试锁两件事：
 * ① 默认值必须是 `false`（回摆属产品决策，不得被顺手改成 true）；
 * ② 判定是"三前置全真才保存"，任一不满足即不落盘（无槽位/引擎未加载时保存无意义）。
 */
class SaveTriggerFlagTest {

    @Test
    fun `flag defaults to off so onStop behaviour stays unchanged`() {
        assertFalse(
            "默认必须为 false（默认关 = 回滚臂；打开等于产品回摆，须先拍板）",
            SaveTriggerFlag.saveOnBackground
        )
    }

    @Test
    fun `no save when flag is off regardless of other preconditions`() {
        assertFalse(shouldSaveOnBackground(flagOn = false, hasActiveSlot = true, engineLoaded = true))
    }

    @Test
    fun `no save without an active slot`() {
        assertFalse(shouldSaveOnBackground(flagOn = true, hasActiveSlot = false, engineLoaded = true))
    }

    @Test
    fun `no save when engine is not loaded`() {
        assertFalse(shouldSaveOnBackground(flagOn = true, hasActiveSlot = true, engineLoaded = false))
    }

    @Test
    fun `save only when all three preconditions hold`() {
        assertTrue(shouldSaveOnBackground(flagOn = true, hasActiveSlot = true, engineLoaded = true))
    }
}
