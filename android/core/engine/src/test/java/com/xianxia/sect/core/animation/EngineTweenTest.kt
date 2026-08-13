package com.xianxia.sect.core.animation

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EngineTween] 时序测试——虚拟时间推进 / 暂停恢复 / 完成回调 / 帧率无关性。
 *
 * 帧率无关性断言方式：不同 tick 间隔下，动画在**同一墙钟时刻**产出相同的缓动值，
 * 完成时刻有界于 [duration, duration + tick)（tick 只影响观测粒度，不影响动画时长）。
 */
class EngineTweenTest {

    /** 可控假时间源（确定性推进的关键） */
    private class FakeTimeSource : TimeSource {
        var now = 0L
        override fun nanoTime(): Long = now
    }

    private companion object {
        const val DURATION_MS = 400L
        const val EPS = 0.001f
    }

    @Test
    fun `update - 虚拟时间推进按墙钟时长完成`() {
        val time = FakeTimeSource()
        val values = mutableListOf<Float>()
        val tween = EngineTween(time, DURATION_MS, onUpdate = { values += it })

        tween.play()
        time.now = 100_000_000L; tween.update()
        assertEquals("100ms 处应为 EaseOutCubic(0.25)=0.578125", 0.578125f, values.last(), EPS)
        time.now = 200_000_000L; tween.update()
        assertEquals("200ms 处应为 EaseOutCubic(0.5)=0.875", 0.875f, values.last(), EPS)
        time.now = 400_000_000L; tween.update()
        assertTrue("400ms 完成", tween.isFinished)
        assertEquals("完成帧终值 = EaseOutCubic(1) = 1", 1f, values.last(), EPS)
    }

    @Test
    fun `update - 帧率无关：不同 tick 间隔总时长与中间值一致`() {
        val queryTimes = listOf(100L, 200L, 300L, 400L)
        val valuesA = sampleAt(queryTimes, tickMs = 16L)
        val valuesB = sampleAt(queryTimes, tickMs = 50L)

        // 不同 tick 节拍下，同一墙钟时刻的进度完全一致（update 仅由时钟决定，与历史节拍无关）
        for (i in queryTimes.indices) {
            assertEquals("t=${queryTimes[i]}ms 处两帧率进度应一致", valuesA[i], valuesB[i], EPS)
        }
        // 值序列 = 线性进度（EaseOutCubic 缓动由 onUpdate 消费，progress 为未缓动值）
        assertEquals("t=100 处线性进度 0.25", 0.25f, valuesA[0], EPS)
        assertEquals("t=200 处线性进度 0.5", 0.5f, valuesA[1], EPS)
        assertEquals("t=300 处线性进度 0.75", 0.75f, valuesA[2], EPS)
        assertEquals("t=400 完成", 1f, valuesA[3], EPS)

        // 完成时刻有界：动画时长恒 400ms，观测完成不早于 400ms、不晚于 400+tick
        val elapsedA = driveWithTick(tickMs = 16L).last().first
        val elapsedB = driveWithTick(tickMs = 50L).last().first
        assertTrue("16ms 节拍完成时刻 >= 400", elapsedA >= DURATION_MS)
        assertTrue("16ms 节拍完成时刻 < 400+16", elapsedA < DURATION_MS + 16L)
        assertTrue("50ms 节拍完成时刻 >= 400", elapsedB >= DURATION_MS)
        assertTrue("50ms 节拍完成时刻 < 400+50", elapsedB < DURATION_MS + 50L)
    }

    @Test
    fun `pause - 暂停期间墙钟不推进进度，恢复后无缝续播`() {
        val time = FakeTimeSource()
        val values = mutableListOf<Float>()
        val tween = EngineTween(time, DURATION_MS, onUpdate = { values += it })

        tween.play()
        time.now = 100_000_000L; tween.update()   // 进度 0.25
        tween.pause()
        assertTrue(tween.isPaused)

        // 暂停期间墙钟流逝 500ms：进度冻结
        time.now = 600_000_000L; tween.update()
        assertTrue("暂停期间 update 不推进", tween.isPaused)
        assertEquals("暂停时进度冻结在 0.25", 0.25f, tween.progress, EPS)

        tween.resume()
        time.now = 700_000_000L; tween.update()   // 恢复后 100ms → 累计 200ms
        assertEquals("恢复后进度 0.5", 0.5f, tween.progress, EPS)

        time.now = 900_000_000L; tween.update()   // 恢复后 300ms → 累计 400ms
        assertTrue("恢复后按剩余时长完成", tween.isFinished)
        assertEquals(1f, values.last(), EPS)
    }

    @Test
    fun `pause - 暂停不触发完成回调`() {
        val time = FakeTimeSource()
        var completed = 0
        val tween = EngineTween(time, DURATION_MS, onComplete = { completed++ })

        tween.play()
        time.now = 300_000_000L; tween.update()
        tween.pause()
        time.now = 10_000_000_000L; tween.update()   // 墙钟远过时长
        assertEquals("暂停期间不得完成", 0, completed)
        tween.resume()
        time.now = 10_100_000_000L; tween.update()
        assertEquals("恢复后按剩余时长完成一次", 1, completed)
    }

    @Test
    fun `resume - 未暂停时忽略`() {
        val time = FakeTimeSource()
        val tween = EngineTween(time, DURATION_MS).play()
        tween.resume()
        assertTrue("未暂停时 resume 应保持播放", tween.isPlaying)
    }

    @Test
    fun `cancel - 停止后不再回调且不触发完成`() {
        val time = FakeTimeSource()
        val values = mutableListOf<Float>()
        var completed = 0
        val tween = EngineTween(time, DURATION_MS, onUpdate = { values += it }, onComplete = { completed++ })

        tween.play()
        time.now = 100_000_000L; tween.update()
        tween.cancel()
        assertTrue(tween.isCanceled)
        assertFalse(tween.isPlaying)

        time.now = 1_000_000_000L; tween.update()
        assertEquals("cancel 后不得再回调", 1, values.size)
        assertEquals("cancel 后不得完成", 0, completed)
    }

    @Test
    fun `play - 已完成后可重新开始`() {
        val time = FakeTimeSource()
        val values = mutableListOf<Float>()
        val tween = EngineTween(time, DURATION_MS, onUpdate = { values += it })

        tween.play()
        time.now = 400_000_000L; tween.update()
        assertTrue(tween.isFinished)

        tween.play()
        assertTrue("重新播放", tween.isPlaying)
        time.now = 400_000_000L + 200_000_000L; tween.update()
        assertEquals("重播中点值 = EaseOutCubic(0.5)=0.875", 0.875f, values.last(), EPS)
        time.now = 800_000_000L + 400_000_000L; tween.update()
        assertTrue(tween.isFinished)
    }

    @Test
    fun `play - 播放中重复调用幂等`() {
        val time = FakeTimeSource()
        val tween = EngineTween(time, DURATION_MS)
        tween.play()
        tween.play()
        tween.play()
        assertTrue(tween.isPlaying)
        time.now = 200_000_000L; tween.update()
        assertEquals("重复 play 不得重置起点", 0.5f, tween.progress, EPS)
    }

    @Test
    fun `update - 非正时长首次推进即瞬时完成`() {
        val time = FakeTimeSource()
        var completed = 0
        val tween = EngineTween(time, 0L, onComplete = { completed++ })
        tween.play()
        assertTrue("play 后尚未推进", tween.isPlaying)
        tween.update()
        assertTrue("0 时长首次 update 即完成", tween.isFinished)
        assertEquals(1, completed)
    }

    @Test
    fun `update - 未 play 时推进为无操作`() {
        val time = FakeTimeSource()
        val values = mutableListOf<Float>()
        val tween = EngineTween(time, DURATION_MS, onUpdate = { values += it })
        time.now = 1_000_000_000L
        tween.update()
        assertTrue("未 play 不回调", values.isEmpty())
        assertFalse(tween.isPlaying)
    }

    @Test
    fun `awaitCompletion - 协程驱动至完成且回调齐全`() = runTest {
        val time = FakeTimeSource()
        val values = mutableListOf<Float>()
        var completed = 0
        val tween = EngineTween(time, DURATION_MS, onUpdate = { values += it }, onComplete = { completed++ })

        tween.play()
        val driver = launch { tween.awaitCompletion() }
        var elapsedMs = 0L
        while (driver.isActive) {
            elapsedMs += 16L
            time.now = elapsedMs * 1_000_000L
            advanceTimeBy(16L)
        }
        assertTrue("awaitCompletion 结束后应完成", tween.isFinished)
        assertEquals("完成回调一次", 1, completed)
        assertEquals("末帧值 = 1", 1f, values.last(), EPS)
        assertEquals("驱动墙钟时长 400ms", 400L, elapsedMs)
    }

    /**
     * 以固定 tick 间隔驱动 400ms EaseOutCubic tween 直到完成，返回 (墙钟ms, 缓动值) 序列。
     */
    private fun driveWithTick(tickMs: Long): List<Pair<Long, Float>> {
        val time = FakeTimeSource()
        val samples = mutableListOf<Pair<Long, Float>>()
        val tween = EngineTween(time, DURATION_MS, onUpdate = { samples += time.now / 1_000_000L to it })
        tween.play()
        var elapsedMs = 0L
        while (true) {
            time.now = elapsedMs * 1_000_000L
            tween.update()
            if (tween.isFinished) break
            elapsedMs += tickMs
        }
        samples += elapsedMs to 1f
        return samples
    }

    /**
     * 以固定 tick 节拍推进，但在每个查询时刻显式置钟更新一次采样进度。
     * 帧率无关性的本质：任意历史节拍下，同一墙钟时刻的进度恒等。
     */
    private fun sampleAt(queryTimes: List<Long>, tickMs: Long): List<Float> {
        val time = FakeTimeSource()
        val tween = EngineTween(time, DURATION_MS)
        tween.play()
        val result = mutableListOf<Float>()
        for (query in queryTimes) {
            var t = 0L
            while (t + tickMs <= query) {
                t += tickMs
                time.now = t * 1_000_000L
                tween.update()
            }
            if (t < query) {
                time.now = query * 1_000_000L
                tween.update()
            }
            result += tween.progress
        }
        return result
    }
}
