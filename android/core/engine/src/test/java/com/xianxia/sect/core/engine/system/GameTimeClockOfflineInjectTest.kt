package com.xianxia.sect.core.engine.system

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * GameTimeClock 离线注入端口测试（结算改造 2026-09-27 §10 B7）。
 *
 * 回退臂语义：[GameTimeClock.addOfflineGameMs] 把折算好的离线游戏毫秒
 * 一次性加进当旬累积器——后续 [GameTimeClock.tick] 按追补上限消化
 * （超限丢弃余量为既有"追补超限丢弃"语义）。AUTHORITATIVE 生产路径
 * 不消费本端口（由 GameEngineCoreOfflineOps 分流 native），此处只锁
 * 回退臂累积行为。
 */
class GameTimeClockOfflineInjectTest {

    private class FakeTimeSource(var now: Long = 0L) : TimeSource {
        override fun elapsedRealtime(): Long = now
        fun advanceBy(ms: Long) { now += ms }
    }

    private lateinit var fakeTime: FakeTimeSource
    private lateinit var clock: GameTimeClock

    @Before
    fun setUp() {
        fakeTime = FakeTimeSource()
        clock = GameTimeClock(fakeTime)
        clock.start()
    }

    @Test
    fun `离线注入进入累积器并在后续 tick 消化`() {
        clock.addOfflineGameMs(6000L)   // 3 旬（1 月）
        assertEquals(6000L, clock.accumulatedGameMs)
        fakeTime.advanceBy(100L)
        val result = clock.tick(isSettlementPending = false)
        // 1x 追补上限 3 旬 → 本 tick 一次消化完（残留 100ms = 本 tick 墙钟增量）
        assertEquals(3, result.phasesToAdvance)
        assertEquals(100L, clock.accumulatedGameMs)
    }

    @Test
    fun `离线注入超追补上限时按既有语义丢弃余量`() {
        // 18h 游戏时间 = 32_400 旬：远超单 tick 上限 3 旬
        clock.addOfflineGameMs(18L * 3_600_000L)
        fakeTime.advanceBy(100L)
        val result = clock.tick(isSettlementPending = false)
        assertEquals(GameTimeClock.MAX_PHASES_PER_TICK, result.phasesToAdvance)
        assertEquals(0L, clock.accumulatedGameMs)
    }

    @Test
    fun `非正注入为零副作用`() {
        clock.addOfflineGameMs(0L)
        clock.addOfflineGameMs(-4000L)
        assertEquals(0L, clock.accumulatedGameMs)
        fakeTime.advanceBy(100L)
        assertEquals(0, clock.tick(false).phasesToAdvance)
    }
}
