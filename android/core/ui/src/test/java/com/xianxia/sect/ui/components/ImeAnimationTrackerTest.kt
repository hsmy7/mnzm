package com.xianxia.sect.ui.components

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
 * ImeAnimationTracker 计数式动画跟踪测试：
 * 多窗口并发动画（Activity + Dialog 窗口）、任一动画进行中全局为真、
 * 全部结束才触发恢复回调、detach 复位、结束溢出防御。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeAnimationTrackerTest {

    @After
    fun tearDown() {
        ImeAnimationTracker.resetForTest()
    }

    @Test
    fun `动画开始 - 全局为动画中`() {
        ImeAnimationTracker.onImeAnimationEvent(end = false)
        assertTrue(ImeAnimationTracker.isAnimating)
        assertEquals(1, ImeAnimationTracker.animatingCountForTest())
    }

    @Test
    fun `动画结束 - 全局恢复非动画中`() {
        ImeAnimationTracker.onImeAnimationEvent(end = false)
        ImeAnimationTracker.onImeAnimationEvent(end = true)
        assertFalse(ImeAnimationTracker.isAnimating)
        assertEquals(0, ImeAnimationTracker.animatingCountForTest())
    }

    @Test
    fun `多窗口并发动画 - 任一结束不得提前恢复`() {
        // 窗口 A 动画开始 → 窗口 B 动画开始 → A 结束 → 仍全局动画中
        ImeAnimationTracker.onImeAnimationEvent(end = false)
        ImeAnimationTracker.onImeAnimationEvent(end = false)
        ImeAnimationTracker.onImeAnimationEvent(end = true)
        assertTrue("A 窗口结束后 B 仍在动画 → 全局仍动画中", ImeAnimationTracker.isAnimating)
        assertEquals(1, ImeAnimationTracker.animatingCountForTest())
        // B 结束 → 全局恢复
        ImeAnimationTracker.onImeAnimationEvent(end = true)
        assertFalse(ImeAnimationTracker.isAnimating)
    }

    @Test
    fun `全部结束 - 恢复回调仅触发一次`() {
        var callbacks = 0
        ImeAnimationTracker.addOnAnimationEndedListener { callbacks++ }
        ImeAnimationTracker.onImeAnimationEvent(end = false)
        ImeAnimationTracker.onImeAnimationEvent(end = false)
        ImeAnimationTracker.onImeAnimationEvent(end = true)
        assertEquals("首个窗口结束（count 未归零）不通知", 0, callbacks)
        ImeAnimationTracker.onImeAnimationEvent(end = true)
        assertEquals("全部结束（count 归零）通知一次", 1, callbacks)
    }

    @Test
    fun `重复结束 - 溢出防御归零不产生负数`() {
        ImeAnimationTracker.onImeAnimationEvent(end = true)
        assertEquals("无动画进行中的结束事件应防溢出归零", 0, ImeAnimationTracker.animatingCountForTest())
        assertFalse(ImeAnimationTracker.isAnimating)
    }

    @Test
    fun `detach 全部窗口 - 全局回落非动画中`() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        ImeAnimationTracker.attach(activity.window)
        ImeAnimationTracker.onImeAnimationEvent(end = false)
        assertTrue(ImeAnimationTracker.isAnimating)
        ImeAnimationTracker.detach(activity.window)
        assertFalse("无存活窗口时全局回落非动画中", ImeAnimationTracker.isAnimating)
        assertEquals(0, ImeAnimationTracker.animatingCountForTest())
    }
}
