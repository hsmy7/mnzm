package com.xianxia.sect.core.engine.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GameTimeClock 单元测试（2026-08-01 重写）。
 *
 * 历史假绿：旧测试用 `setLastWallMsForTest(SystemClock.elapsedRealtime() - elapsedMs)`
 * 回拨时钟——在 `returnDefaultValues = true` 的纯 JVM 下 SystemClock.elapsedRealtime()
 * 恒返回 0，测试靠"双读同为 0 相减"的算术恒等式通过，实现被改写也会照样绿。
 * 现改为注入 FakeTimeSource 手工推进，测试验证真实时间语义。
 *
 * 2026-08-01 语义变更：单 tick 追补上限 MAX_PHASES_PER_TICK = 3
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

    // 3. 2x 速度下 1000ms 真实时间 = 2000ms 游戏时间 → 2 旬
    @Test
    fun speed2x_1000ms_advances2Phases() {
        clock.setSpeed(2)
        clock.start()  // 重置 accumulator，避免 setSpeed 期间的杂散累积
        val result = simulateTick(1000L)
        assertEquals(2, result.phasesToAdvance)
    }

    // 4. 2x 速度下 3000ms 真实时间 = 6000ms 游戏时间 = 6 旬 = 缩放后上限（3×2）→ 恰好不截断
    @Test
    fun speed2x_3000ms_atScaledCap() {
        clock.setSpeed(2)
        clock.start()
        val result = simulateTick(3000L)
        assertEquals(GameTimeClock.MAX_PHASES_PER_TICK * 2, result.phasesToAdvance)
    }

    // 5. 暂停(speed=0) → 不推进任何旬
    @Test
    fun speed0_doesNotAdvance() {
        clock.setSpeed(0)
        val result = simulateTick(5000L)
        assertEquals(0, result.phasesToAdvance)
    }

    // 6. 速度切换中保存累积量：1x 下过 1500ms，切 2x，再过 500ms → 累积正确
    @Test
    fun speedSwitch_preservesAccumulation() {
        // 1x 下累积 1500ms 游戏时间（不够 1 旬，accumulator 剩余 1500ms）
        simulateTick(1500L)
        assertEquals(0, clock.tick(false).phasesToAdvance)  // 1500 < 2000 → 0 旬

        // 切到 2x：setSpeed 保留累积的 1500ms game time
        clock.setSpeed(2)

        // 2x 下再过 500ms 真实时间 → 新增 1000ms 游戏时间
        // 总计 = 1500 + 1000 = 2500ms / 1000 = 2 旬，余 500ms
        val result = simulateTick(500L)
        assertEquals(2, result.phasesToAdvance)
    }

    // 7. phaseProgress 在 0~1 之间平滑变化
    @Test
    fun phaseProgress_between0and1() {
        clock.start()
        assertEquals(0f, clock.phaseProgress, 0.01f)

        simulateTick(1000L)
        val progress = clock.phaseProgress
        assertTrue("progress should be >= 0, was $progress", progress >= 0f)
        assertTrue("progress should be <= 1, was $progress", progress <= 1f)
    }

    // 8. remainingPhaseMs 计算正确
    @Test
    fun remainingPhaseMs_correct() {
        clock.start()
        assertEquals(GameTimeClock.MS_PER_PHASE_1X, clock.remainingPhaseMs)

        simulateTick(500L)
        val remaining = clock.remainingPhaseMs
        // 500ms * 1 = 500ms game time accumulated, remaining = 2000 - 500 = 1500
        assertEquals(1500L, remaining)
    }

    // 9. isSettlementPending=true 时仍然返回正确的 phasesToAdvance
    @Test
    fun settlementPending_stillReturnsPhases() {
        val result = simulateTick(2000L, isSettlementPending = true)
        assertEquals(1, result.phasesToAdvance)
        assertTrue(result.isSettlementPending)
    }

    // 10. isSettlementPending=false 时正常返回
    @Test
    fun noSettlement_normalReturn() {
        val result = simulateTick(2000L, isSettlementPending = false)
        assertEquals(1, result.phasesToAdvance)
        assertFalse(result.isSettlementPending)
    }

    // 11. 一次 tick 内累积多旬（从暂停恢复/卡顿后追赶）——4 旬超上限截断为 3
    @Test
    fun multiPhaseInOneTick_cappedAt3() {
        val result = simulateTick(8000L)
        assertEquals(GameTimeClock.MAX_PHASES_PER_TICK, result.phasesToAdvance)
    }

    // 12. 超大 delta 被 MAX_CATCHUP_MS 截断后再被 MAX_PHASES_PER_TICK 上限约束
    @Test
    fun largeDelta_cappedByMaxCatchupThenMaxPhases() {
        clock.setSpeed(2)
        val result = simulateTick(100_000L)
        // 截断后: 30000 * 2 = 60000 game ms / 1000 = 60 phases → 再被缩放上限（3×2）截为 6
        assertEquals(GameTimeClock.MAX_PHASES_PER_TICK * 2, result.phasesToAdvance)
    }

    // 13. 暂停后恢复：speed=0 暂停 → speed=1 恢复，累积量不丢
    @Test
    fun pauseResume_preservesState() {
        simulateTick(1000L) // 1x: 1000ms game time
        clock.setSpeed(0)
        simulateTick(5000L) // paused: no accumulation
        clock.setSpeed(1)
        val result = simulateTick(1000L) // 1x: 1000ms more game time
        assertEquals(1, result.phasesToAdvance)
    }

    // 14. 2x 下 10 秒 → 20 旬 → 超缩放上限（6）截断为 6
    @Test
    fun speed2x_10seconds_cappedAtScaled() {
        clock.setSpeed(2)
        val result = simulateTick(10_000L)
        assertEquals(GameTimeClock.MAX_PHASES_PER_TICK * 2, result.phasesToAdvance)
    }

    // 15. forceConsumeOnePhase 正确扣除
    @Test
    fun forceConsumeOnePhase_deducts() {
        clock.start()
        simulateTick(2500L) // 1 旬被消费，accumulator 剩余 500ms
        assertEquals(1500L, clock.remainingPhaseMs)

        clock.forceConsumeOnePhase()
        // forceConsume: max(0, 500 - 2000) = 0 → accumulator 归零
        assertEquals(2000L, clock.remainingPhaseMs)
    }

    // 16. 冻结 20s 后恢复 → 2x 下 40 旬 → 被缩放上限（6）截断
    @Test
    fun freeze20s_cappedAtScaledMaxPhases() {
        clock.setSpeed(2)
        clock.start()
        val result = simulateTick(20_000L)
        assertEquals(GameTimeClock.MAX_PHASES_PER_TICK * 2, result.phasesToAdvance)
    }

    // 17. 冻结 60s → MAX_CATCHUP_MS(30s) 截断 → 15 旬 → 再被追补上限截为 3
    @Test
    fun freeze60s_cappedTo3() {
        val result = simulateTick(60_000L)
        assertEquals(GameTimeClock.MAX_PHASES_PER_TICK, result.phasesToAdvance)
    }

    // 18. 追补上限触发后余量被丢弃：下一 tick 从零累积（不残留爆炸余量）
    @Test
    fun catchUpCap_discardsRemainder() {
        clock.setSpeed(2)
        val result = simulateTick(10_000L)  // 20 旬 → 截断为 6（缩放上限），余量丢弃
        assertEquals(GameTimeClock.MAX_PHASES_PER_TICK * 2, result.phasesToAdvance)

        // 紧接 100ms 后 tick：只推进 0 旬（accumulatedGameMs 已清零）
        val next = simulateTick(100L)
        assertEquals(0, next.phasesToAdvance)
    }

    // 19. 未触发上限时余量保留：8000ms→3 旬 + 余 2000ms → 再 100ms 后仍 0 旬
    //     （区别于 18：此场景 phases==3 未超上限，但余量 2000ms 恰好凑不出下一旬）
    @Test
    fun underCap_preservesRemainder() {
        // 7000ms / 2000 = 3 旬（≤3 上限），余 1000ms 保留
        val result = simulateTick(7000L)
        assertEquals(3, result.phasesToAdvance)
        // 再 1000ms → 累积 2000ms → 1 旬
        val next = simulateTick(1000L)
        assertEquals(1, next.phasesToAdvance)
    }
}
