package com.xianxia.sect.ui.util

import android.view.ActionMode
import android.view.Window
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [ActionModeSafeCallback] 回归守卫（Bugly #3026）：
 *
 * 1. 创建期拦截——FloatingActionMode 在 [Window.Callback.onWindowStartingActionMode]
 *    返回 null 时即构造并 show PopupWindow（早于 onActionModeStarted），
 *    销毁期必须返回 stub 短路创建
 * 2. 缺口 3——finishActiveActionMode 在无活跃 ActionMode 时也必须置销毁态
 * 3. resetForResume——返回前台后恢复文本选择能力
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionModeSafeCallbackTest {

    private lateinit var delegate: Window.Callback
    private lateinit var callback: ActionModeSafeCallback

    @Before
    fun setUp() {
        delegate = mock(Window.Callback::class.java)
        callback = ActionModeSafeCallback(delegate, RuntimeEnvironment.getApplication())
    }

    @Test
    fun `onWindowStartingActionMode - 正常状态转发给 delegate`() {
        val actionModeCallback = mock(ActionMode.Callback::class.java)
        callback.onWindowStartingActionMode(actionModeCallback, ActionMode.TYPE_FLOATING)
        verify(delegate).onWindowStartingActionMode(actionModeCallback, ActionMode.TYPE_FLOATING)

        // delegate 返回 null（未 stub 默认）——正常路径不应拦截
        val result = callback.onWindowStartingActionMode(actionModeCallback, ActionMode.TYPE_FLOATING)
        assertNull("正常状态应透传 delegate 的返回", result)
    }

    @Test
    fun `onWindowStartingActionMode - 销毁期返回 stub 而非 null`() {
        // 缺口 1 回归：销毁期若返回 null，框架会构造 FloatingActionMode 并
        // 立即 show PopupWindow（崩溃点）。stub 短路创建。
        callback.finishActiveActionMode()
        val actionModeCallback = mock(ActionMode.Callback::class.java)

        val result = callback.onWindowStartingActionMode(actionModeCallback, ActionMode.TYPE_FLOATING)
        assertNotNull("销毁期必须返回非 null stub", result)
        verify(delegate, never()).onWindowStartingActionMode(actionModeCallback, ActionMode.TYPE_FLOATING)
    }

    @Test
    fun `onWindowStartingActionMode - 单参弃用重载销毁期同样拦截`() {
        callback.finishActiveActionMode()
        val actionModeCallback = mock(ActionMode.Callback::class.java)

        val result = callback.onWindowStartingActionMode(actionModeCallback)
        assertNotNull("单参重载销毁期也必须返回 stub", result)
    }

    @Test
    fun `finishActiveActionMode - 无活跃 ActionMode 时也进入销毁态`() {
        // 缺口 3 回归：旧实现仅在 activeActionMode != null 时置位，
        // 窗口拆卸期间残留焦点触发的新 ActionMode 将不被拦截
        assertTrue(!callback.isTearingDown)
        callback.finishActiveActionMode()
        assertTrue("finishActiveActionMode 必须无条件置销毁态", callback.isTearingDown)
    }

    @Test
    fun `onActionModeStarted - 销毁期立即 finish 新 ActionMode`() {
        callback.finishActiveActionMode()
        val mode = mock(ActionMode::class.java)

        callback.onActionModeStarted(mode)
        verify(mode).finish()
        verify(delegate, never()).onActionModeStarted(mode)
    }

    @Test
    fun `onActionModeStarted - 正常状态记录并转发`() {
        val mode = mock(ActionMode::class.java)
        callback.onActionModeStarted(mode)

        assertSame("正常状态应记录活跃 ActionMode", mode, callback.activeActionMode)
        verify(delegate).onActionModeStarted(mode)
    }

    @Test
    fun `onActionModeFinished - 清除跟踪引用`() {
        val mode = mock(ActionMode::class.java)
        callback.onActionModeStarted(mode)
        callback.onActionModeFinished(mode)

        assertNull("ActionMode 结束后应清除引用", callback.activeActionMode)
    }

    @Test
    fun `resetForResume - 恢复文本选择能力`() {
        // 回归守卫：旧实现 onStop 置位后永不复位，返回前台文本选择永久失效
        callback.finishActiveActionMode()
        assertTrue(callback.isTearingDown)

        callback.resetForResume()
        assertTrue("resetForResume 必须清除销毁态", !callback.isTearingDown)
    }

    @Test
    fun `resetForResume - 复位后 onWindowStartingActionMode 恢复正常转发`() {
        callback.finishActiveActionMode()
        callback.resetForResume()
        val actionModeCallback = mock(ActionMode.Callback::class.java)

        val result = callback.onWindowStartingActionMode(actionModeCallback, ActionMode.TYPE_FLOATING)
        assertNull("复位后不应再拦截", result)
        verify(delegate).onWindowStartingActionMode(actionModeCallback, ActionMode.TYPE_FLOATING)
    }
}
