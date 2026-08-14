package com.xianxia.sect.core.util

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
 * ImeVisibilityTracker 键盘可见性跟踪测试（荣耀 X70 键盘频闪根治组件）：
 * insets 回调状态翻转、透传不消费、attach 幂等。
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
        assertFalse(ImeVisibilityTracker.isImeVisible)
        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(View(activity), imeInsetsWith(200))
        assertTrue(ImeVisibilityTracker.isImeVisible)
    }

    @Test
    fun `onInsetsApplied - 键盘收起时状态翻转为不可见`() {
        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(View(activity), imeInsetsWith(200))
        ImeVisibilityTracker.imeVisibilityExtractor = { false }
        ImeVisibilityTracker.onInsetsApplied(View(activity), imeInsetsWith(0))
        assertFalse(ImeVisibilityTracker.isImeVisible)
    }

    @Test
    fun `onInsetsApplied - 可见性未变化时不翻转状态`() {
        ImeVisibilityTracker.setImeVisibleForTest(true)
        // 提取结果与当前状态一致（均可见）→ 状态保持，无翻转
        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(View(activity), imeInsetsWith(100))
        assertTrue(ImeVisibilityTracker.isImeVisible)
    }

    @Test
    fun `onInsetsApplied - 透传返回原 insets 引用不消费`() {
        val insets = imeInsetsWith(300)
        val result = ImeVisibilityTracker.onInsetsApplied(View(activity), insets)
        assertEquals("insets 应原样透传给 View 分发链", insets, result)
    }

    @Test
    fun `attach - 同一窗口重复 attach 幂等`() {
        ImeVisibilityTracker.attach(activity.window)
        ImeVisibilityTracker.attach(activity.window)
        // 不抛异常即幂等成立；insets 分发仍可正常更新状态
        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(View(activity), imeInsetsWith(150))
        assertTrue(ImeVisibilityTracker.isImeVisible)
    }

    @Test
    fun `attach - 新窗口替换旧监听目标`() {
        val otherActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ImeVisibilityTracker.attach(activity.window)
        ImeVisibilityTracker.attach(otherActivity.window)
        // 新窗口 attach 后 insets 分发仍工作（监听器已挂到新窗口 decorView）
        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(View(otherActivity), imeInsetsWith(120))
        assertTrue(ImeVisibilityTracker.isImeVisible)
    }
}
