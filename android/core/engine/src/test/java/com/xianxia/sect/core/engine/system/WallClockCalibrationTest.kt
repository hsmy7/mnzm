package com.xianxia.sect.core.engine.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SR-5 C2：云校正墙钟的偏移算式与红线。
 *
 * 承重断言是**偏移 0 时与系统钟逐位一致**——LEGACY 模式不上传 ⇒ 永不采样 ⇒
 * 默认模式下全部游戏语义判据（日额/周冷却/兑换码限流/邮件过期）读到的毫秒数
 * 与本批之前完全相同（"LEGACY 零行为变化"红线的锚）。
 */
class WallClockCalibrationTest {

    @Test
    fun `未采样时偏移为 0 且读数即系统钟`() {
        val clock = CalibratedWallClock()
        assertEquals(0L, clock.currentOffsetMs)
        assertFalse(clock.isCalibrated)

        val before = System.currentTimeMillis()
        val read = clock.currentTimeMillis()
        val after = System.currentTimeMillis()
        assertTrue(
            "偏移 0 时读数必须落在系统钟前后采样区间内：read=$read before=$before after=$after",
            read in before..after
        )
    }

    @Test
    fun `可信样本生效后读数含偏移`() {
        val clock = CalibratedWallClock()
        val localNow = System.currentTimeMillis()
        val drift = 30_000L

        assertEquals(drift, clock.applyDriftSample(localNow + drift, localNow))
        assertTrue(clock.isCalibrated)
        assertEquals(drift, clock.currentOffsetMs)

        val before = System.currentTimeMillis() + drift
        val read = clock.currentTimeMillis()
        val after = System.currentTimeMillis() + drift
        assertTrue(
            "校正后读数应等于系统钟 + drift：read=$read 期望区间=$before..$after",
            read in before..after
        )
    }

    @Test
    fun `超出阈值的样本丢弃且不占单次额度`() {
        val clock = CalibratedWallClock()
        val localNow = System.currentTimeMillis()

        // 冷启动列表语义（上次归档写入时刻，小时/天量级）——必须判为不可信
        assertNull(clock.applyDriftSample(localNow - 6L * 60L * 60L * 1000L, localNow))
        assertNull(clock.applyDriftSample(localNow + CalibratedWallClock.MAX_DRIFT_MS + 1L, localNow))
        assertFalse(clock.isCalibrated)
        assertEquals(0L, clock.currentOffsetMs)

        // 丢弃不消耗额度：随后可信样本仍可生效
        assertEquals(5_000L, clock.applyDriftSample(localNow + 5_000L, localNow))
        assertTrue(clock.isCalibrated)
    }

    @Test
    fun `阈值边界样本接受`() {
        val clock = CalibratedWallClock()
        val localNow = System.currentTimeMillis()
        assertEquals(
            -CalibratedWallClock.MAX_DRIFT_MS,
            clock.applyDriftSample(localNow - CalibratedWallClock.MAX_DRIFT_MS, localNow)
        )
    }

    @Test
    fun `单次约束_已校正后不再改偏移`() {
        val clock = CalibratedWallClock()
        val localNow = System.currentTimeMillis()
        assertEquals(10_000L, clock.applyDriftSample(localNow + 10_000L, localNow))
        assertNull(clock.applyDriftSample(localNow + 20_000L, localNow))
        assertEquals(10_000L, clock.currentOffsetMs)
    }

    @Test
    fun `非法样本参数丢弃`() {
        val clock = CalibratedWallClock()
        assertNull(clock.applyDriftSample(0L, System.currentTimeMillis()))
        assertNull(clock.applyDriftSample(System.currentTimeMillis(), 0L))
        assertNull(clock.applyDriftSample(-1L, -1L))
        assertFalse(clock.isCalibrated)
    }

    @Test
    fun `fun interface 可直接构造固定钟`() {
        val fixed = WallClock { 1_700_000_000_000L }
        assertEquals(1_700_000_000_000L, fixed.currentTimeMillis())

        val before = System.currentTimeMillis()
        val system = SystemWallClock.currentTimeMillis()
        val after = System.currentTimeMillis()
        assertTrue(
            "SystemWallClock 必须就是系统钟：system=$system 区间=$before..$after",
            system in before..after
        )
    }
}
