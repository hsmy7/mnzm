package com.xianxia.sect.core.animation

import com.xianxia.sect.core.camera.CameraState
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * CameraAnimator 时间源确定性测试（2026-08-10 新增，WP2）。
 *
 * 覆盖维度：
 * - 注入假 [TimeSource] 后动画插值完全确定（不依赖墙钟）
 * - EaseOutCubic 中点插值位置（t=0.5 → 1-(0.5)³ = 0.875）
 * - 完成后到达目标且不再写入相机
 * - cancel 停止动画
 */
class CameraAnimatorTest {

    /** 可控假时间源（确定性动画进度的关键） */
    private class FakeTimeSource : TimeSource {
        var now = 0L
        override fun nanoTime(): Long = now
    }

    /** 记录写入的假相机状态 */
    private class FakeCameraState : CameraState {
        override var cameraX: Float = 0f
            private set
        override var cameraY: Float = 0f
            private set
        override var scale: Float = 1f
            private set
        override val viewportWidth: Int = 768
        override val viewportHeight: Int = 768
        override val worldWidth: Float = 768f
        override val worldHeight: Float = 768f
        var writeCount = 0
            private set

        override fun updateViewport(w: Int, h: Int) = Unit
        override fun setPosition(x: Float, y: Float) {
            cameraX = x
            cameraY = y
            writeCount++
        }

        override fun applyScale(newScale: Float) {
            scale = newScale
            writeCount++
        }

        override fun pan(dx: Float, dy: Float) = Unit
        override fun centerOn(wx: Float, wy: Float) = Unit
        override fun zoom(delta: Float, focusX: Float, focusY: Float) = Unit
        override fun isVisible(wx: Float, wy: Float, margin: Float): Boolean = true
        override fun reset() = Unit
    }

    @Test
    fun `animateTo - 假时间源中点插值位置确定`() = runTest {
        val time = FakeTimeSource()
        val camera = FakeCameraState()
        val animator = CameraAnimator(camera, this, time)

        animator.animateTo(CameraTarget(100f, 200f), durationMs = 400)

        // 首帧：t=0 → 位置 = 起点
        advanceTimeBy(16)
        assertEquals(0f, camera.cameraX, 0.001f)

        // t=200ms（中点）：eased = 1-(0.5)³ = 0.875 → x = 87.5
        time.now = 200_000_000L
        advanceTimeBy(16)
        assertEquals(87.5f, camera.cameraX, 0.001f)
        assertEquals(175f, camera.cameraY, 0.001f)

        // 动画未完成：测试结束时取消协程，防 runTest 检测活跃子协程
        animator.cancel()
    }

    @Test
    fun `animateTo - 完成时到达目标且停止写入`() = runTest {
        val time = FakeTimeSource()
        val camera = FakeCameraState()
        val animator = CameraAnimator(camera, this, time)

        animator.animateTo(CameraTarget(100f, 200f), durationMs = 400)
        advanceTimeBy(16)
        time.now = 400_000_000L
        advanceTimeBy(16)

        // 完成：位置到达目标
        assertEquals(100f, camera.cameraX, 0.001f)
        assertEquals(200f, camera.cameraY, 0.001f)
        assertFalse("动画完成后 isRunning 应为 false", animator.isRunning)

        // 继续推进虚拟时间：不再写入
        val writesAtCompletion = camera.writeCount
        advanceTimeBy(1000)
        assertEquals("动画完成后不得继续写入相机", writesAtCompletion, camera.writeCount)
    }

    @Test
    fun `animateTo - 缩放目标同步插值`() = runTest {
        val time = FakeTimeSource()
        val camera = FakeCameraState()
        val animator = CameraAnimator(camera, this, time)

        animator.animateTo(CameraTarget(0f, 0f, scale = 2f), durationMs = 400)
        advanceTimeBy(16)
        time.now = 200_000_000L
        advanceTimeBy(16)

        // 缩放同样按 EaseOutCubic：1 + (2-1)×0.875 = 1.875
        assertEquals(1.875f, camera.scale, 0.001f)

        // 动画未完成：测试结束时取消协程，防 runTest 检测活跃子协程
        animator.cancel()
    }

    @Test
    fun `animateTo - 负时长瞬时完成不死循环`() = runTest {
        val time = FakeTimeSource()
        val camera = FakeCameraState()
        val animator = CameraAnimator(camera, this, time)

        // 对抗性审查修复：非正时长直接瞬时跳转目标——
        // 旧实现下 progress 恒负 → t 恒 0 → `if (t >= 1f) break` 永不触发 → 死循环
        animator.animateTo(CameraTarget(100f, 200f, scale = 2f), durationMs = -100L)

        assertEquals("负时长应瞬时到达目标 X", 100f, camera.cameraX, 0.001f)
        assertEquals("负时长应瞬时到达目标 Y", 200f, camera.cameraY, 0.001f)
        assertEquals("负时长应瞬时应用缩放", 2f, camera.scale, 0.001f)
        assertFalse("负时长不启动协程动画", animator.isRunning)

        // 无活跃协程：runTest 不因悬挂子协程失败
    }

    @Test
    fun `animateTo - 零时长同样瞬时完成`() = runTest {
        val time = FakeTimeSource()
        val camera = FakeCameraState()
        val animator = CameraAnimator(camera, this, time)

        animator.animateTo(CameraTarget(50f, 60f), durationMs = 0L)

        assertEquals(50f, camera.cameraX, 0.001f)
        assertEquals(60f, camera.cameraY, 0.001f)
        assertFalse(animator.isRunning)
    }

    @Test
    fun `cancel - 停止动画后不再写入相机`() = runTest {
        val time = FakeTimeSource()
        val camera = FakeCameraState()
        val animator = CameraAnimator(camera, this, time)

        animator.animateTo(CameraTarget(100f, 200f), durationMs = 400)
        advanceTimeBy(16)
        val writesBeforeCancel = camera.writeCount

        animator.cancel()
        assertFalse(animator.isRunning)

        time.now = 1_000_000_000L
        advanceTimeBy(1000)
        assertEquals("cancel 后不得再写入相机", writesBeforeCancel, camera.writeCount)
    }

    @Test
    fun `animateTo - 位置序列与原实现参考曲线全等（迁移回归）`() = runTest {
        // 2026-08-13 EngineTween 迁移回归测试：原实现（协程内逐帧 delay + 手写插值循环）
        // 每帧按 墙钟 elapsed → EaseOutCubic(t) → lerp 写入相机。
        // 迁移后由 EngineTween 驱动，本测试逐帧采样位置序列并与参考曲线全等——
        // 若驱动方式改变（起始时刻捕获偏移/跳帧/缓动曲线漂移/缺末帧），序列即偏离。
        val time = FakeTimeSource()
        val camera = FakeCameraState()
        val animator = CameraAnimator(camera, this, time)

        animator.animateTo(CameraTarget(100f, 200f), durationMs = 400)
        advanceTimeBy(16)

        // 以 16ms 帧节拍采样 0..400ms 共 26 帧（含完成帧）
        val samples = mutableListOf<Pair<Long, Float>>()
        repeat(25) { frame ->
            time.now = frame * 16L * 1_000_000L
            advanceTimeBy(16)
            samples += (frame * 16L) to camera.cameraX
        }
        time.now = 400L * 1_000_000L
        advanceTimeBy(16)
        samples += 400L to camera.cameraX

        // 参考曲线：x(t) = 0 + (100-0) × EaseOutCubic(t/400)，与原实现同公式
        assertEquals("首帧 t=0 应写起点", 0f, samples.first().second, 0.001f)
        assertEquals("末帧应达目标", 100f, samples.last().second, 0.001f)
        for ((elapsedMs, x) in samples) {
            val t = (elapsedMs.toFloat() / 400f).coerceIn(0f, 1f)
            val eased = 1f - (1f - t) * (1f - t) * (1f - t)
            assertEquals(
                "t=${elapsedMs}ms 处位置应与参考曲线全等",
                100f * eased, x, 0.001f
            )
        }
    }
}
