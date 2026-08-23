package com.xianxia.sect.ui.components

import android.app.Activity
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ImeVisibilityTracker 键盘可见性跟踪测试（荣耀 X70 键盘频闪根治组件，
 * 2026-08 GT 系列根治升级为多窗口语义）：
 * 状态翻转、透传不消费、attach 幂等、多窗口独立状态、detach 清理、翻转回调。
 *
 * 可见性提取通过注入 [ImeVisibilityTracker.imeVisibilityExtractor] 控制——
 * Robolectric 对 android.view.WindowInsets 的 ime 类型支持不全，
 * 状态机逻辑与框架 insets 解析解耦验证（提取函数默认实现为
 * androidx.core 官方 isVisible，真机可靠）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeVisibilityTrackerTest {

    private val activity: Activity by lazy {
        Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        ImeVisibilityTracker.resetForTest()
    }

    private fun imeInsetsWith(height: Int): WindowInsetsCompat =
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, height))
            .build()

    @Test
    fun `onInsetsApplied - 键盘弹出时状态翻转为可见`() {
        ImeVisibilityTracker.attach(activity.window)
        assertFalse(ImeVisibilityTracker.isImeVisible)
        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(View(activity), imeInsetsWith(200), activity.window)
        assertTrue(ImeVisibilityTracker.isImeVisible)
        assertTrue("窗口级查询应同步为可见", ImeVisibilityTracker.isImeVisibleFor(activity.window))
    }

    @Test
    fun `onInsetsApplied - 键盘收起时状态翻转为不可见`() {
        ImeVisibilityTracker.attach(activity.window)
        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(View(activity), imeInsetsWith(200), activity.window)
        ImeVisibilityTracker.imeVisibilityExtractor = { false }
        ImeVisibilityTracker.onInsetsApplied(View(activity), imeInsetsWith(0), activity.window)
        assertFalse(ImeVisibilityTracker.isImeVisible)
        assertFalse("窗口级查询应同步为不可见", ImeVisibilityTracker.isImeVisibleFor(activity.window))
    }

    @Test
    fun `onInsetsApplied - 可见性未变化时不翻转状态`() {
        ImeVisibilityTracker.attach(activity.window)
        ImeVisibilityTracker.setImeVisibleForTest(true)
        // 提取结果与当前状态一致（均可见）→ 状态保持，无翻转
        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(View(activity), imeInsetsWith(100), activity.window)
        assertTrue(ImeVisibilityTracker.isImeVisible)
    }

    @Test
    fun `onInsetsApplied - 透传返回原 insets 引用不消费`() {
        ImeVisibilityTracker.attach(activity.window)
        val insets = imeInsetsWith(300)
        val result = ImeVisibilityTracker.onInsetsApplied(View(activity), insets, activity.window)
        assertEquals("insets 应原样透传给 View 分发链", insets, result)
    }

    @Test
    fun `attach - 同一窗口重复 attach 幂等且仅追加回调`() {
        var flipCount = 0
        ImeVisibilityTracker.attach(activity.window) { flipCount++ }
        ImeVisibilityTracker.attach(activity.window) { flipCount++ }
        // 不抛异常即幂等成立；insets 分发仍可正常更新状态，且两次回调均已注册
        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(View(activity), imeInsetsWith(150), activity.window)
        assertTrue(ImeVisibilityTracker.isImeVisible)
        assertEquals("重复 attach 只追加回调不重装监听", 1, ImeVisibilityTracker.windowCountForTest())
        assertEquals("两个翻转回调均应触发", 2, flipCount)
    }

    @Test
    fun `多窗口 - 任一窗口键盘可见全局为真且状态独立`() {
        val dialogActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ImeVisibilityTracker.attach(activity.window)
        ImeVisibilityTracker.attach(dialogActivity.window)
        assertEquals(2, ImeVisibilityTracker.windowCountForTest())

        // Dialog 窗口键盘弹出（Activity 窗口无键盘）
        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(
            View(dialogActivity), imeInsetsWith(200), dialogActivity.window
        )
        assertTrue("任一窗口键盘可见 → 全局可见", ImeVisibilityTracker.isImeVisible)
        assertTrue(ImeVisibilityTracker.isImeVisibleFor(dialogActivity.window))
        assertFalse("Activity 窗口自身应仍不可见", ImeVisibilityTracker.isImeVisibleFor(activity.window))

        // 另一窗口状态翻转不影响本窗口
        ImeVisibilityTracker.imeVisibilityExtractor = { false }
        ImeVisibilityTracker.onInsetsApplied(View(activity), imeInsetsWith(0), activity.window)
        assertTrue("Dialog 窗口仍可见 → 全局保持可见", ImeVisibilityTracker.isImeVisible)

        // Dialog 窗口键盘收起 → 全局回落
        ImeVisibilityTracker.onInsetsApplied(View(dialogActivity), imeInsetsWith(0), dialogActivity.window)
        assertFalse(ImeVisibilityTracker.isImeVisible)
    }

    @Test
    fun `detach - 解除跟踪后条目移除且全局回落`() {
        ImeVisibilityTracker.attach(activity.window)
        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(View(activity), imeInsetsWith(200), activity.window)
        assertTrue(ImeVisibilityTracker.isImeVisible)

        ImeVisibilityTracker.detach(activity.window)
        assertEquals(0, ImeVisibilityTracker.windowCountForTest())
        assertFalse("窗口销毁后全局应回落为不可见", ImeVisibilityTracker.isImeVisible)
        assertFalse(ImeVisibilityTracker.isImeVisibleFor(activity.window))
    }

    @Test
    fun `attach - 翻转回调在窗口状态翻转时触发且无变化不触发`() {
        var flipCount = 0
        ImeVisibilityTracker.attach(activity.window) { flipCount++ }
        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(View(activity), imeInsetsWith(200), activity.window)
        assertEquals(1, flipCount)
        ImeVisibilityTracker.imeVisibilityExtractor = { false }
        ImeVisibilityTracker.onInsetsApplied(View(activity), imeInsetsWith(0), activity.window)
        assertEquals(2, flipCount)
        // 可见性无变化 → 不触发回调
        ImeVisibilityTracker.onInsetsApplied(View(activity), imeInsetsWith(0), activity.window)
        assertEquals(2, flipCount)
    }

    // ── 双信号检测（2026-08 第四根因键盘频闪根治）──
    // 默认提取器 = insets.isVisible(ime) || insets.getInsets(ime).bottom > 0：
    // ADJUST_PAN 等不 resize 的窗口在部分国产 ROM 上可见性标志可能不翻转，
    // IME 底部高度作为兜底信号（解冻恢复链路的二次校验依赖全局可见性准确性）。

    @Test
    fun `双信号 - isVisible 为 false 但 IME 底部高度大于 0 时判为可见`() {
        ImeVisibilityTracker.attach(activity.window)
        // 不注入提取器，直接验证默认双信号实现
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 200))
            .setVisible(WindowInsetsCompat.Type.ime(), false)
            .build()
        ImeVisibilityTracker.onInsetsApplied(View(activity), insets, activity.window)
        assertTrue("底部高度信号应兜底判为可见", ImeVisibilityTracker.isImeVisible)
    }

    @Test
    fun `双信号 - isVisible 为 true 但底部高度为 0 时仍判为可见`() {
        ImeVisibilityTracker.attach(activity.window)
        val insets = WindowInsetsCompat.Builder()
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        ImeVisibilityTracker.onInsetsApplied(View(activity), insets, activity.window)
        assertTrue("可见性标志信号应保持生效", ImeVisibilityTracker.isImeVisible)
    }
}
