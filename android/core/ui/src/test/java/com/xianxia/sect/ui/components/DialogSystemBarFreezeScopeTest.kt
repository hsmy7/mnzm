package com.xianxia.sect.ui.components

import android.app.Activity
import android.view.Window
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
 * DialogSystemBarFreezeScope 按窗口冻结作用域状态机测试：
 * 窗口计数隔离、嵌套计数、0↔1 翻转回调、未冻结 no-op、监听器增删、异常隔离。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DialogSystemBarFreezeScopeTest {

    private val activity: Activity by lazy {
        Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    private val otherActivity: Activity by lazy {
        Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        DialogSystemBarFreezeScope.resetForTest()
    }

    @Test
    fun `enterFreeze - 窗口进入冻结且 isFrozen 为真`() {
        val window: Window = activity.window
        assertFalse("测试前应为未冻结状态", DialogSystemBarFreezeScope.isFrozen(window))
        DialogSystemBarFreezeScope.enterFreeze(window)
        assertTrue("enterFreeze 后应冻结", DialogSystemBarFreezeScope.isFrozen(window))
    }

    @Test
    fun `exitFreeze - 解冻后 isFrozen 为假`() {
        val window: Window = activity.window
        DialogSystemBarFreezeScope.enterFreeze(window)
        DialogSystemBarFreezeScope.exitFreeze(window)
        assertFalse("exitFreeze 后应解冻", DialogSystemBarFreezeScope.isFrozen(window))
    }

    @Test
    fun `嵌套计数 - 内层退出后外层仍冻结`() {
        val window: Window = activity.window
        DialogSystemBarFreezeScope.enterFreeze(window)
        DialogSystemBarFreezeScope.enterFreeze(window)
        DialogSystemBarFreezeScope.exitFreeze(window)
        assertTrue("内层退出后外层仍冻结", DialogSystemBarFreezeScope.isFrozen(window))
        DialogSystemBarFreezeScope.exitFreeze(window)
        assertFalse("全部退出后应解冻", DialogSystemBarFreezeScope.isFrozen(window))
    }

    @Test
    fun `窗口隔离 - 不同窗口冻结互不影响`() {
        val w1: Window = activity.window
        val w2: Window = otherActivity.window
        DialogSystemBarFreezeScope.enterFreeze(w1)
        assertTrue(w1.let { DialogSystemBarFreezeScope.isFrozen(it) })
        assertFalse("另一窗口应不受影响", DialogSystemBarFreezeScope.isFrozen(w2))
        DialogSystemBarFreezeScope.enterFreeze(w2)
        DialogSystemBarFreezeScope.exitFreeze(w1)
        assertTrue("w2 冻结独立保持", DialogSystemBarFreezeScope.isFrozen(w2))
    }

    @Test
    fun `未冻结 - exitFreeze 是安全 no-op`() {
        val window: Window = activity.window
        DialogSystemBarFreezeScope.exitFreeze(window)
        assertFalse(DialogSystemBarFreezeScope.isFrozen(window))
    }

    @Test
    fun `isFrozen(null) - 返回 false`() {
        assertFalse(DialogSystemBarFreezeScope.isFrozen(null))
    }

    @Test
    fun `冻结翻转 - 0到1与1到0各触发一次回调且仅翻转时触发`() {
        val window: Window = activity.window
        var flipCount = 0
        DialogSystemBarFreezeScope.addOnFrozenChangedListener(window) { flipCount += 1 }

        DialogSystemBarFreezeScope.enterFreeze(window)
        assertEquals("0→1 应触发一次", 1, flipCount)
        DialogSystemBarFreezeScope.enterFreeze(window)
        assertEquals("嵌套进入不触发", 1, flipCount)
        DialogSystemBarFreezeScope.exitFreeze(window)
        assertEquals("嵌套退出不触发", 1, flipCount)
        DialogSystemBarFreezeScope.exitFreeze(window)
        assertEquals("1→0 应触发一次", 2, flipCount)
    }

    @Test
    fun `移除监听器 - 之后翻转不再通知`() {
        val window: Window = activity.window
        var flipCount = 0
        val listener = { flipCount += 1 }
        DialogSystemBarFreezeScope.addOnFrozenChangedListener(window, listener)
        DialogSystemBarFreezeScope.removeOnFrozenChangedListener(window, listener)
        DialogSystemBarFreezeScope.enterFreeze(window)
        DialogSystemBarFreezeScope.exitFreeze(window)
        assertEquals("移除后不应再通知", 0, flipCount)
    }

    @Test
    fun `异常监听器 - 不影响其他监听器与冻结语义`() {
        val window: Window = activity.window
        DialogSystemBarFreezeScope.addOnFrozenChangedListener(window) {
            throw IllegalStateException("宿主已销毁")
        }
        var normalCount = 0
        DialogSystemBarFreezeScope.addOnFrozenChangedListener(window) { normalCount += 1 }
        DialogSystemBarFreezeScope.enterFreeze(window)
        DialogSystemBarFreezeScope.exitFreeze(window)
        assertEquals("异常监听器不应阻断通知（0→1 与 1→0 各一次）", 2, normalCount)
        assertFalse("冻结语义应正常落定", DialogSystemBarFreezeScope.isFrozen(window))
    }

    // ── 泄漏自愈（对齐 SystemBarFreezeScope）──

    @Test
    fun `泄漏自愈 - 冻结超时强制解冻并通知翻转`() {
        val window: Window = activity.window
        var flipCount = 0
        DialogSystemBarFreezeScope.addOnFrozenChangedListener(window) { flipCount += 1 }
        var now = 1_000L
        DialogSystemBarFreezeScope.freezeClock = { now }
        DialogSystemBarFreezeScope.enterFreeze(window)
        assertTrue(DialogSystemBarFreezeScope.isFrozen(window))
        // 推进时钟超过 10 分钟阈值
        now += 10 * 60 * 1000L + 1
        assertFalse("冻结超时应自愈解冻", DialogSystemBarFreezeScope.isFrozen(window))
        assertEquals("自愈应触发 0→1 进入与 1→0 解冻各一次", 2, flipCount)
    }

    @Test
    fun `泄漏自愈 - 阈值内不触发`() {
        val window: Window = activity.window
        var now = 1_000L
        DialogSystemBarFreezeScope.freezeClock = { now }
        DialogSystemBarFreezeScope.enterFreeze(window)
        now += 60_000L
        assertTrue("阈值内应保持冻结", DialogSystemBarFreezeScope.isFrozen(window))
    }

    @Test
    fun `泄漏自愈 - 嵌套冻结超时整体自愈解冻`() {
        val window: Window = activity.window
        var now = 1_000L
        DialogSystemBarFreezeScope.freezeClock = { now }
        DialogSystemBarFreezeScope.enterFreeze(window)
        DialogSystemBarFreezeScope.enterFreeze(window)
        now += 10 * 60 * 1000L + 1
        assertFalse("嵌套冻结超时应整体自愈解冻", DialogSystemBarFreezeScope.isFrozen(window))
        // 自愈后旧条目已清理，exitFreeze 安全 no-op
        DialogSystemBarFreezeScope.exitFreeze(window)
        assertFalse(DialogSystemBarFreezeScope.isFrozen(window))
    }
}
