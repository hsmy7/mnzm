package com.xianxia.sect.ui.components

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SystemBarHidePolicy 冻结策略测试（荣耀 X70 键盘频闪根治，
 * 2026-09 IME 状态机根治升级：判定收敛至 ImeStateMachine 单一真相源）：
 * 输入对话框冻结期间 / 键盘可见期间 / 键盘动画期间，hideSystemBars 必须跳过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemBarHidePolicyTest {

    @After
    fun tearDown() {
        SystemBarFreezeScope.resetForTest()
        ImeVisibilityTracker.resetForTest()
        ImeAnimationTracker.resetForTest()
    }

    @Test
    fun `无冻结且键盘不可见 - 不跳过隐藏`() {
        assertFalse(SystemBarHidePolicy.shouldSkipHide())
    }

    @Test
    fun `输入对话框冻结期间 - 跳过隐藏`() {
        SystemBarFreezeScope.enterFreeze()
        assertTrue(SystemBarHidePolicy.shouldSkipHide())
        assertTrue(SystemBarHidePolicy.skipReason().contains("frozen=true"))
    }

    @Test
    fun `键盘可见期间 - 跳过隐藏`() {
        ImeVisibilityTracker.setImeVisibleForTest(true)
        assertTrue(SystemBarHidePolicy.shouldSkipHide())
        assertTrue(SystemBarHidePolicy.skipReason().contains("imeVisible=true"))
    }

    @Test
    fun `键盘动画进行中 - 跳过隐藏`() {
        ImeAnimationTracker.setAnimatingForTest(true)
        assertTrue("动画期系统栏零切换（M9：hide×IME 动画并发即闪屏来源）", SystemBarHidePolicy.shouldSkipHide())
        assertTrue(SystemBarHidePolicy.skipReason().contains("imeAnimating=true"))
    }

    @Test
    fun `冻结且键盘可见 - 跳过隐藏且原因完整`() {
        SystemBarFreezeScope.enterFreeze()
        ImeVisibilityTracker.setImeVisibleForTest(true)
        assertTrue(SystemBarHidePolicy.shouldSkipHide())
        assertTrue(SystemBarHidePolicy.skipReason().contains("frozen=true"))
        assertTrue(SystemBarHidePolicy.skipReason().contains("imeVisible=true"))
    }

    @Test
    fun `冻结解除且键盘收起 - 恢复隐藏`() {
        SystemBarFreezeScope.enterFreeze()
        ImeVisibilityTracker.setImeVisibleForTest(true)
        SystemBarFreezeScope.exitFreeze()
        ImeVisibilityTracker.setImeVisibleForTest(false)
        assertFalse(SystemBarHidePolicy.shouldSkipHide())
    }
}
