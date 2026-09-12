package com.xianxia.sect.ui.components

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ImeStateMachine 统一判定状态机测试
 * （docs/ime-android-system-research.md M3/M6/M9/M10）：
 * 聚合键盘可见性（isVisible 真值）、键盘动画状态、输入对话框冻结为单一真相源。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeStateMachineTest {

    @After
    fun tearDown() {
        SystemBarFreezeScope.resetForTest()
        ImeVisibilityTracker.resetForTest()
        ImeAnimationTracker.resetForTest()
        InputSessionStateMachine.resetForTest()
    }

    // ── isSystemBarFrozen（任一条件成立即冻结）──

    @Test
    fun `全部空闲 - 系统栏不冻结`() {
        assertFalse(ImeStateMachine.isSystemBarFrozen())
    }

    @Test
    fun `输入对话框冻结 - 系统栏冻结`() {
        SystemBarFreezeScope.enterFreeze()
        assertTrue(ImeStateMachine.isSystemBarFrozen())
    }

    @Test
    fun `键盘可见 - 系统栏冻结`() {
        ImeVisibilityTracker.setImeVisibleForTest(true)
        assertTrue(ImeStateMachine.isSystemBarFrozen())
    }

    @Test
    fun `键盘动画中 - 系统栏冻结`() {
        ImeAnimationTracker.setAnimatingForTest(true)
        assertTrue("动画期系统栏零切换", ImeStateMachine.isSystemBarFrozen())
    }

    // ── canRestoreSystemBars（键盘不可见且无动画才放行）──

    @Test
    fun `键盘不可见且无动画 - 恢复放行`() {
        assertTrue(ImeStateMachine.canRestoreSystemBars())
    }

    @Test
    fun `键盘可见 - 恢复不放行`() {
        ImeVisibilityTracker.setImeVisibleForTest(true)
        assertFalse(ImeStateMachine.canRestoreSystemBars())
    }

    @Test
    fun `键盘动画中 - 恢复不放行`() {
        ImeAnimationTracker.setAnimatingForTest(true)
        assertFalse(ImeStateMachine.canRestoreSystemBars())
    }

    // ── freezeReason ──

    @Test
    fun `冻结原因 - 全部空闲返回无`() {
        assertTrue(ImeStateMachine.freezeReason().contains("无"))
    }

    @Test
    fun `冻结原因 - 动画与可见性并列`() {
        ImeVisibilityTracker.setImeVisibleForTest(true)
        ImeAnimationTracker.setAnimatingForTest(true)
        val reason = ImeStateMachine.freezeReason()
        assertTrue(reason.contains("imeVisible=true"))
        assertTrue(reason.contains("imeAnimating=true"))
    }
}
