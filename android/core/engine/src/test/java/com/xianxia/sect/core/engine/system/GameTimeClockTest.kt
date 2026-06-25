package com.xianxia.sect.core.engine.system

import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GameTimeClockTest {

    private lateinit var clock: GameTimeClock

    @Before
    fun setUp() {
        clock = GameTimeClock()
        clock.start()
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
        // 1000ms 真实时间 × 2(speed) = 2000ms 游戏时间 / 1000(msPerPhase) = 2 旬
        assertEquals(2, result.phasesToAdvance)
    }

    // 4. 2x 速度下 3000ms 真实时间 = 6000ms 游戏时间 → 6 旬（= 2 个月）
    @Test
    fun speed2x_3000ms_advances6Phases() {
        clock.setSpeed(2)
        clock.start()  // 重置 accumulator
        val result = simulateTick(3000L)
        // 3000ms 真实时间 × 2(speed) = 6000ms 游戏时间 / 1000(msPerPhase) = 6 旬
        assertEquals(6, result.phasesToAdvance)
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
        // 注意：不调 start()，否则会丢失累积的 1500ms

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

    // 11. 一次 tick 内累积多旬（从暂停恢复/卡顿后追赶）
    @Test
    fun multiPhaseInOneTick() {
        // 8000ms < MAX_CATCHUP_MS(30000)，不被截断
        val result = simulateTick(8000L)
        assertEquals(4, result.phasesToAdvance)
    }

    // 12. 超大 delta 被安全上限截断，防止挂起恢复后时间爆炸
    @Test
    fun largeDelta_cappedByMaxCatchup() {
        clock.setSpeed(2)
        // 100000ms 真实时间 >> MAX_CATCHUP_MS(30000)，应被截断
        // 截断后: 30000 * 2 = 60000 game ms / 1000 = 60 phases
        val result = simulateTick(100_000L)
        assertEquals(60, result.phasesToAdvance)
    }

    // 13. 暂停后恢复：speed=0 暂停 → speed=1 恢复，累积量不丢
    @Test
    fun pauseResume_preservesState() {
        simulateTick(1000L) // 1x: 1000ms game time
        clock.setSpeed(0)
        simulateTick(5000L) // paused: no accumulation
        clock.setSpeed(1)
        // Now 1000ms game time already accumulated, need another 1000ms for 1 phase
        val result = simulateTick(1000L) // 1x: 1000ms more game time
        assertEquals(1, result.phasesToAdvance)
    }

    // 14. 极端：2x 下 10 秒 → 10 旬（3 月 + 1 旬）
    @Test
    fun speed2x_10seconds() {
        clock.setSpeed(2)
        val result = simulateTick(10_000L)
        // 10000 * 2 = 20000 game ms, / 1000 = 20 phases
        assertEquals(20, result.phasesToAdvance)
    }

    // 15. forceConsumeOnePhase 正确扣除
    @Test
    fun forceConsumeOnePhase_deducts() {
        clock.start()
        simulateTick(2500L) // 1 旬被消费，accumulator 剩余 500ms
        assertEquals(1500L, clock.remainingPhaseMs) // 2000 - 500 = 1500ms 到下一旬

        clock.forceConsumeOnePhase()
        // forceConsume: max(0, 500 - 2000) = 0 → accumulator 归零
        // 下一旬需要完整的 2000ms
        assertEquals(2000L, clock.remainingPhaseMs)
    }

    // 16. 协程冻结 20s 后恢复 tick() → 游戏时间被补算（20s × speed）
    //     场景：OEM 省电策略冻结协程 20s，恢复后 tick() 基于 now - lastWallMs 补算
    @Test
    fun freeze20s_compensatesFully() {
        // 2x 速度：20s 真实时间 × 2 = 40s 游戏时间 = 40000ms / 1000(msPerPhase@2x) = 40 旬
        // 20000ms < MAX_CATCHUP_MS(30000)，不被截断，全额补算
        clock.setSpeed(2)
        clock.start()  // 重置 accumulator，避免 setSpeed 期间的杂散累积
        val result = simulateTick(20_000L)
        assertEquals(40, result.phasesToAdvance)
    }

    // 17. 协程冻结 60s → 被 MAX_CATCHUP_MS(30s) 截断
    //     场景：长时间冻结恢复，单次 delta 被安全上限截断防止时间爆炸
    @Test
    fun freeze60s_cappedTo30s() {
        // 1x 速度：60s 真实时间被截断为 30s = 30000ms / 2000(msPerPhase@1x) = 15 旬
        val result = simulateTick(60_000L)
        assertEquals(15, result.phasesToAdvance)
    }

    // ── 辅助方法 ──

    /**
     * 模拟一次 tick：通过 setLastWallMsForTest 回拨时钟来模拟时间流逝。
     */
    private fun simulateTick(elapsedMs: Long, isSettlementPending: Boolean = false): GameTimeClock.TickResult {
        clock.setLastWallMsForTest(SystemClock.elapsedRealtime() - elapsedMs)
        return clock.tick(isSettlementPending)
    }
}
