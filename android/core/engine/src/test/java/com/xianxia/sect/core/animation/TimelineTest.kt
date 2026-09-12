package com.xianxia.sect.core.animation

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Timeline] 多段编排测试——段顺序/时刻、重复、暂停恢复、0 时长段链、帧率无关性。
 */
class TimelineTest {

    /** 可控假时间源（确定性推进的关键） */
    private class FakeTimeSource : TimeSource {
        var now = 0L
        override fun nanoTime(): Long = now
    }

    private companion object {
        const val EPS = 0.001f
    }

    @Test
    fun `step - 多段按序执行且结束时刻精确`() {
        val time = FakeTimeSource()
        val ticks = mutableListOf<Pair<Long, Int>>()   // (墙钟ms, 段索引)
        val timeline = Timeline(time)
            .step(100L) { ticks += time.now / 1_000_000L to 0 }
            .step(200L) { ticks += time.now / 1_000_000L to 1 }
            .step(150L) { ticks += time.now / 1_000_000L to 2 }
        timeline.play()

        driveUntilFinished(time, timeline, 10L)

        assertEquals(listOf(100L to 0, 300L to 1, 450L to 2), ticks)
        assertTrue(timeline.isFinished)
        assertEquals("整条进度应为 1", 1f, timeline.progress, EPS)
    }

    @Test
    fun `step - onUpdate 段内以缓动曲线推进`() {
        val time = FakeTimeSource()
        val values = mutableListOf<Float>()
        val timeline = Timeline(time)
            .step(100L, easing = EasingConstants.EASE_IN_CUBIC, onUpdate = { values += it })
        timeline.play()

        time.now = 50_000_000L; timeline.update()
        assertEquals("50ms 处 EaseInCubic(0.5)=0.125", 0.125f, values.last(), EPS)
        time.now = 100_000_000L; timeline.update()
        assertTrue(timeline.isFinished)
    }

    @Test
    fun `repeat - 重复播放 N 次后完成`() {
        val time = FakeTimeSource()
        val ticks = mutableListOf<Long>()
        val timeline = Timeline(time)
            .step(50L) { ticks += time.now / 1_000_000L }
            .step(30L) { ticks += time.now / 1_000_000L }
            .repeat(3)
        timeline.play()

        driveUntilFinished(time, timeline, 10L)

        // 每遍：段0@+50, 段1@+30 → 时刻 50,80, 130,160, 210,240
        assertEquals(listOf(50L, 80L, 130L, 160L, 210L, 240L), ticks)
        assertTrue(timeline.isFinished)
    }

    @Test
    fun `repeat - times=1 只播一遍`() {
        val time = FakeTimeSource()
        var tickCount = 0
        val timeline = Timeline(time).step(50L) { tickCount++ }.repeat(1)
        timeline.play()
        driveUntilFinished(time, timeline, 10L)
        assertEquals(1, tickCount)
    }

    @Test
    fun `repeat - 非法参数报错`() {
        assertThrows(IllegalArgumentException::class.java) {
            Timeline().repeat(0)
        }
    }

    @Test
    fun `pause - 暂停跨段冻结，恢复后按剩余时长继续`() {
        val time = FakeTimeSource()
        val ticks = mutableListOf<Pair<Long, Int>>()
        val timeline = Timeline(time)
            .step(100L) { ticks += time.now / 1_000_000L to 0 }
            .step(100L) { ticks += time.now / 1_000_000L to 1 }
        timeline.play()

        time.now = 100_000_000L; timeline.update()   // 段0 完成 → 段1 开始
        assertEquals(1, ticks.size)
        timeline.pause()
        time.now = 600_000_000L; timeline.update()   // 暂停 500ms
        assertEquals("暂停期间不得完成段1", 1, ticks.size)
        timeline.resume()

        driveUntilFinished(time, timeline, 10L)
        // 段1 在恢复后 100ms 完成：墙钟 700ms
        assertEquals(listOf(100L to 0, 700L to 1), ticks)
    }

    @Test
    fun `cancel - 停止后后续段不执行`() {
        val time = FakeTimeSource()
        val ticks = mutableListOf<Int>()
        val timeline = Timeline(time)
            .step(100L) { ticks += 0 }
            .step(100L) { ticks += 1 }
        timeline.play()

        time.now = 100_000_000L; timeline.update()
        timeline.cancel()
        assertTrue(timeline.isCanceled)
        assertFalse(timeline.isPlaying)

        time.now = 1_000_000_000L; timeline.update()
        assertEquals("取消后后续段不得执行", listOf(0), ticks)
    }

    @Test
    fun `update - 0 时长段链在同一 tick 内即时推进不额外耗时`() {
        val time = FakeTimeSource()
        val ticks = mutableListOf<Pair<Long, Int>>()
        val timeline = Timeline(time)
            .step(0L) { ticks += time.now / 1_000_000L to 0 }    // 瞬时段
            .step(300L) { ticks += time.now / 1_000_000L to 1 }
            .step(0L) { ticks += time.now / 1_000_000L to 2 }    // 瞬时段
            .step(200L) { ticks += time.now / 1_000_000L to 3 }
        timeline.play()

        time.now = 0L; timeline.update()   // 段0 瞬时完成，段1 开始
        assertEquals("瞬时段0 于 0ms 完成", listOf(0L to 0), ticks)

        time.now = 300_000_000L; timeline.update()   // 段1 完成 → 段2 瞬时完成 → 段3 开始
        assertEquals(listOf(0L to 0, 300L to 1, 300L to 2), ticks)

        time.now = 500_000_000L; timeline.update()
        assertTrue("整条总时长 = 300+200 = 500ms", timeline.isFinished)
        assertEquals(listOf(0L to 0, 300L to 1, 300L to 2, 500L to 3), ticks)
    }

    @Test
    fun `update - 帧率无关：不同 tick 间隔总时长一致`() {
        // 420 = 3 × 140：段长必须整除（整数除法不可产生 133ms 段）
        val totalMs = 420L
        val elapsedA = driveWithTick(tickMs = 7L, totalMs = totalMs, stepCount = 3)
        val elapsedB = driveWithTick(tickMs = 33L, totalMs = totalMs, stepCount = 3)

        // 总时长恒 420ms；观测完成时刻 ∈ [420, 420 + tick×段数)（每段结束各多一个 tick 粒度）
        assertTrue("7ms 节拍完成时刻 >= 420", elapsedA >= totalMs)
        assertTrue("7ms 节拍完成时刻 < 420+7×3", elapsedA < totalMs + 7L * 3)
        assertTrue("33ms 节拍完成时刻 >= 420", elapsedB >= totalMs)
        assertTrue("33ms 节拍完成时刻 < 420+33×3", elapsedB < totalMs + 33L * 3)
    }

    @Test
    fun `play - 空时间轴立即完成`() {
        val time = FakeTimeSource()
        val timeline = Timeline(time).play()
        assertTrue("空时间轴 play 即完成", timeline.isFinished)
        assertFalse(timeline.isPlaying)
    }

    @Test
    fun `awaitCompletion - 协程驱动至完成`() = runTest {
        val time = FakeTimeSource()
        val ticks = mutableListOf<Long>()
        val timeline = Timeline(time).step(100L) { ticks += 100L }.step(200L) { ticks += 300L }
        timeline.play()

        val driver = launch { timeline.awaitCompletion() }
        var elapsedMs = 0L
        while (driver.isActive) {
            elapsedMs += 16L
            time.now = elapsedMs * 1_000_000L
            advanceTimeBy(16L)
        }
        assertTrue(timeline.isFinished)
        assertEquals(listOf(100L, 300L), ticks)
    }

    /** 以固定 tick 间隔推进直到完成，返回观测完成时刻（墙钟 ms） */
    private fun driveUntilFinished(time: FakeTimeSource, timeline: Timeline, tickMs: Long) {
        var elapsedMs = 0L
        while (true) {
            time.now = elapsedMs * 1_000_000L
            timeline.update()
            if (timeline.isFinished) break
            elapsedMs += tickMs
        }
    }

    /** 以固定 tick 间隔驱动三段等长时间轴直到完成，返回观测完成时刻 */
    private fun driveWithTick(tickMs: Long, totalMs: Long, stepCount: Int): Long {
        val time = FakeTimeSource()
        val timeline = Timeline(time)
        repeat(stepCount) { timeline.step(totalMs / stepCount) }
        timeline.play()
        var elapsedMs = 0L
        while (true) {
            time.now = elapsedMs * 1_000_000L
            timeline.update()
            if (timeline.isFinished) break
            elapsedMs += tickMs
        }
        return elapsedMs
    }
}
