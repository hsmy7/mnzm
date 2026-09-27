package com.xianxia.sect.core.engine.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GameTimeClock 单元测试。
 *
 * 时钟注入：使用 FakeTimeSource 手工推进（纯 JVM 下 SystemClock.elapsedRealtime()
 * 恒返回 0，墙钟差值恒等式会造成假绿），测试验证真实时间语义。
 *
 * 单 tick 追补上限 MAX_PHASES_PER_TICK = 3
 * （防止 OEM 挂起恢复后 60 旬连跑卡死），超限丢弃余量。
 */
class GameTimeClockTest {

    private lateinit var fakeTime: FakeTimeSource
    private lateinit var clock: GameTimeClock

    /** 可手工推进的单调时钟 */
    private class FakeTimeSource(var now: Long = 0L) : TimeSource {
        override fun elapsedRealtime(): Long = now
        fun advanceBy(ms: Long) { now += ms }
    }

    @Before
    fun setUp() {
        fakeTime = FakeTimeSource()
        clock = GameTimeClock(fakeTime)
        clock.start()
    }

    /** 推进真实时间并 tick */
    private fun simulateTick(elapsedMs: Long, isSettlementPending: Boolean = false): GameTimeClock.TickResult {
        fakeTime.advanceBy(elapsedMs)
        return clock.tick(isSettlementPending)
    }

    // 1. 1x 速度下 2000ms → 恰好 1 旬
    @Test
    fun speed1x_2000ms_advances1Phase() {
        val result = simulateTick(2000L)
        assertEquals(1, result.phasesToAdvance)
    }

    // 2. 1x 速度下 6000ms → 恰好 3 旬（1 月）
    @Test
    fun speed1x_6000ms_advances3Phases() {
        val result = simulateTick(6000L)
        assertEquals(3, result.phasesToAdvance)
    }

    // 3. phaseProgress 在 0~1 之间平滑变化
    @Test
    fun phaseProgress_between0and1() {
        clock.start()
        assertEquals(0f, clock.phaseProgress, 0.01f)

        simulateTick(1000L)
        val progress = clock.phaseProgress
        assertTrue("progress should be >= 0, was $progress", progress >= 0f)
        assertTrue("progress should be <= 1, was $progress", progress <= 1f)
    }

    // 4. remainingPhaseMs 计算正确
    @Test
    fun remainingPhaseMs_correct() {
        clock.start()
        assertEquals(GameTimeClock.MS_PER_PHASE, clock.remainingPhaseMs)

        simulateTick(500L)
        val remaining = clock.remainingPhaseMs
        // 500ms 累积，remaining = 2000 - 500 = 1500
        assertEquals(1500L, remaining)
    }

    // 5. isSettlementPending=true 时仍然返回正确的 phasesToAdvance
    @Test
    fun settlementPending_stillReturnsPhases() {
        val result = simulateTick(2000L, isSettlementPending = true)
        assertEquals(1, result.phasesToAdvance)
        assertTrue(result.isSettlementPending)
    }

    // 6. isSettlementPending=false 时正常返回
    @Test
    fun noSettlement_normalReturn() {
        val result = simulateTick(2000L, isSettlementPending = false)
        assertEquals(1, result.phasesToAdvance)
        assertFalse(result.isSettlementPending)
    }

    // 7. 一次 tick 内累积多旬（从暂停恢复/卡顿后追赶）——4 旬超上限截断为 3
    @Test
    fun multiPhaseInOneTick_cappedAt3() {
        val result = simulateTick(8000L)
        assertEquals(GameTimeClock.MAX_PHASES_PER_TICK, result.phasesToAdvance)
    }

    // 8. forceConsumeOnePhase 正确扣除
    @Test
    fun forceConsumeOnePhase_deducts() {
        clock.start()
        simulateTick(2500L) // 1 旬被消费，accumulator 剩余 500ms
        assertEquals(1500L, clock.remainingPhaseMs)

        clock.forceConsumeOnePhase()
        // forceConsume: max(0, 500 - 2000) = 0 → accumulator 归零
        assertEquals(2000L, clock.remainingPhaseMs)
    }

    // 9. 冻结 60s → 60 旬 → 被追补上限截为 3
    @Test
    fun freeze60s_cappedTo3() {
        val result = simulateTick(60_000L)
        assertEquals(GameTimeClock.MAX_PHASES_PER_TICK, result.phasesToAdvance)
    }

    // 10. 追补上限触发后余量被丢弃：下一 tick 从零累积（不残留爆炸余量）
    @Test
    fun catchUpCap_discardsRemainder() {
        val result = simulateTick(10_000L)  // 5 旬 → 截断为 3（追补上限），余量丢弃
        assertEquals(GameTimeClock.MAX_PHASES_PER_TICK, result.phasesToAdvance)

        // 紧接 100ms 后 tick：只推进 0 旬（accumulatedGameMs 已清零）
        val next = simulateTick(100L)
        assertEquals(0, next.phasesToAdvance)
    }

    // 11. 未触发上限时余量保留：7000ms→3 旬 + 余 1000ms → 再 1000ms 后仍 1 旬
    @Test
    fun underCap_preservesRemainder() {
        // 7000ms / 2000 = 3 旬（≤3 上限），余 1000ms 保留
        val result = simulateTick(7000L)
        assertEquals(3, result.phasesToAdvance)
        // 再 1000ms → 累积 2000ms → 1 旬
        val next = simulateTick(1000L)
        assertEquals(1, next.phasesToAdvance)
    }

    // 12. accumulatedGameMs 暴露：单调增长（进度监控快照输入）
    @Test
    fun accumulatedGameMs_growsWhileRunning() {
        clock.start()
        assertEquals(0L, clock.accumulatedGameMs)

        simulateTick(500L)
        assertEquals(500L, clock.accumulatedGameMs)

        simulateTick(500L)
        assertEquals(1000L, clock.accumulatedGameMs)
    }

    // 13. accumulatedGameMs：旬消费后回绕（相位推进时归零重累积）
    @Test
    fun accumulatedGameMs_wrapsAfterPhaseConsumption() {
        clock.start()
        simulateTick(2000L)  // 恰好 1 旬：2000ms 全被消费
        assertEquals(0L, clock.accumulatedGameMs)

        simulateTick(3000L)  // 1 旬 + 余 1000ms
        assertEquals(1000L, clock.accumulatedGameMs)
    }

    // 14. nowMs 暴露单调时钟（租约与快照时间基准）
    @Test
    fun nowMs_tracksTimeSource() {
        val before = clock.nowMs()
        fakeTime.advanceBy(1234L)
        assertEquals(before + 1234L, clock.nowMs())
    }
}
