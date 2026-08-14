package com.xianxia.sect.core.util

import com.xianxia.sect.ui.components.SystemBarFreezeScope
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SystemBarHidePolicy 双守卫策略测试（荣耀 X70 键盘频闪根治）：
 * 输入对话框冻结期间或键盘可见期间，hideSystemBars 必须跳过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemBarHidePolicyTest {

    @After
    fun tearDown() {
        // 冻结计数归零（exitFreeze 对未冻结状态是安全 no-op；resetForTest 为
        // core/ui 模块 internal 不可跨模块访问，改用公开 API 清理）
        while (SystemBarFreezeScope.isFrozen) {
            SystemBarFreezeScope.exitFreeze()
        }
        ImeVisibilityTracker.resetForTest()
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
