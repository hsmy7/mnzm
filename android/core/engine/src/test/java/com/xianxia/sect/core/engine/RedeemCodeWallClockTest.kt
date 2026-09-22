package com.xianxia.sect.core.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * SR-5 C4：兑换码限流的墙钟改为**入参**。
 *
 * 收敛前五处裸读 `System.currentTimeMillis()`（基础冷却/分钟/小时/日四层各读一次 +
 * 过期清理起算 + 使用记录落账），一次兑换内多个时刻互相错位，且无法用固定时刻复现边界。
 * 现由 [com.xianxia.sect.core.engine.service.RedeemCodeService] 一次采样后下传，
 * 本测试用固定钟逐层钉住判据。
 */
class RedeemCodeWallClockTest {

    private val playerId = "wall_clock_test_player"

    @Before
    fun setUp() {
        RedeemCodeManager.clearAllCaches()
    }

    private fun history(stamps: List<Long>): MutableList<Long> =
        RedeemCodeManager.deviceAttemptHistory.getOrPut(playerId) { mutableListOf() }.apply { addAll(stamps) }

    @Test
    fun `基础冷却以入参时刻计算_同秒内拒_过窗后放`() = runBlocking {
        RedeemCodeManager.lastRedeemTime = BASE
        assertNotNull(
            "入参时刻距上次兑换仅 2s，必须仍在 3s 基础冷却内",
            RedeemCodeManager.checkRateLimit(playerId, BASE + 2_000L)
        )

        RedeemCodeManager.lastRedeemTime = BASE
        assertNull(
            "入参时刻已过 4s，基础冷却应放行",
            RedeemCodeManager.checkRateLimit(playerId, BASE + 4_000L)
        )
    }

    @Test
    fun `分钟层用入参时刻_窗口滑出即放`() = runBlocking {
        history(listOf(BASE, BASE + 1_000L, BASE + 2_000L, BASE + 3_000L, BASE + 4_000L))

        RedeemCodeManager.lastRedeemTime = 0L
        assertNotNull(
            "5 条一分钟内的记录即达上限（MAX_ATTEMPTS_PER_MINUTE=5）",
            RedeemCodeManager.checkRateLimit(playerId, BASE + 5_000L)
        )

        RedeemCodeManager.lastRedeemTime = 0L
        assertNull(
            "起算时刻推到 65s 后（一分钟窗口起点 = BASE+5s），上述 5 条全部滑出，应放行",
            RedeemCodeManager.checkRateLimit(playerId, BASE + 65_000L)
        )
    }

    @Test
    fun `小时层用入参时刻`() = runBlocking {
        // 每条间隔 70s：分钟层恒为空，只暴露小时层
        history((0 until RedeemCodeManager.MAX_ATTEMPTS_PER_HOUR).map { BASE + it * 70_000L })

        RedeemCodeManager.lastRedeemTime = 0L
        assertNotNull(
            "20 条一小时内的记录应触发小时层",
            RedeemCodeManager.checkRateLimit(playerId, BASE + 30L * 60_000L)
        )
    }

    @Test
    fun `过期清理以入参时刻起算_不再自取系统钟`() {
        val stale = BASE - 25L * 60L * 60L * 1000L
        val fresh = BASE - 1L * 60L * 60L * 1000L
        assertEquals(2, history(listOf(stale, fresh)).size)

        RedeemCodeManager.cleanupExpiredAttempts(BASE)

        assertEquals(
            "25h 前的记录必须按入参时刻清掉",
            listOf(fresh),
            RedeemCodeManager.deviceAttemptHistory[playerId]
        )
    }

    @Test
    fun `统计接口同样按入参时刻开窗`() {
        // 一条落在分钟窗内（30s 前），一条只在小时窗内（30min 前）
        history(listOf(BASE - 30_000L, BASE - 30L * 60_000L))

        val stats = RedeemCodeManager.getRateLimitStats(playerId, BASE)
        assertEquals(1, stats.attemptsInLastMinute)
        assertEquals(2, stats.attemptsInLastHour)
        assertEquals(2, stats.attemptsToday)
    }

    private companion object {
        /** 固定基准时刻（整秒，便于心算窗口） */
        const val BASE = 1_700_000_000_000L
    }
}
