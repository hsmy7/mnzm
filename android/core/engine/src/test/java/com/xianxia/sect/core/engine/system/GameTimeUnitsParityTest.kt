package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间单位常量栈双端对拍（结算改造方案 2026-09-27 §2.2/§5.1）：
 * 锁定 Kotlin 侧常量与换算公式（GameConfig.Time 单一来源）；C++ 侧对应
 * time_units_test.cpp（time_units.h 同名常量同值同公式）——两测互为锚点，
 * 改值须双端同步（沿 GameTimeClockPhaseCapParityTest 先例）。
 *
 * 语义：连续积分轨全部速率以「每游戏秒」为单位（每旬 ÷2 / 每月 ÷6 / 每年 ÷72）；
 * 游戏日历是权威时间轴 elapsedGameMs 的派生投影（INV-1）。
 */
class GameTimeUnitsParityTest {

    // ── 常量族（C++ time_units.h 同名锚点）────────────────────────────

    @Test
    fun `constants match the documented values`() {
        assertEquals(1000, GameConfig.Time.MS_PER_GAME_SECOND)
        assertEquals(2.0, GameConfig.Time.GAME_SECONDS_PER_PHASE, 0.0)
        assertEquals(6.0, GameConfig.Time.GAME_SECONDS_PER_MONTH, 0.0)
        assertEquals(72.0, GameConfig.Time.GAME_SECONDS_PER_YEAR, 0.0)
        // 派生一致性：1 旬 = 2 游戏秒 = 2000 游戏毫秒（与 GameTimeClock.MS_PER_PHASE_1X 同值）
        assertEquals(2000L, GameConfig.Time.GAME_MS_PER_PHASE)
        assertEquals(
            (GameConfig.Time.GAME_SECONDS_PER_PHASE * GameConfig.Time.MS_PER_GAME_SECOND).toLong(),
            GameConfig.Time.GAME_MS_PER_PHASE
        )
        // 既有换算锚点：旬/月/年秒数与旬/月、月/年整除关系一致
        assertEquals(
            GameConfig.Time.PHASES_PER_MONTH * GameConfig.Time.GAME_SECONDS_PER_PHASE,
            GameConfig.Time.GAME_SECONDS_PER_MONTH,
            0.0
        )
        assertEquals(
            GameConfig.Time.MONTHS_PER_YEAR * GameConfig.Time.GAME_SECONDS_PER_MONTH,
            GameConfig.Time.GAME_SECONDS_PER_YEAR,
            0.0
        )
        // 1x 下游戏毫秒与现实毫秒恒等（GameTimeClock.MS_PER_PHASE_1X 同值锚）
        assertEquals(GameTimeClock.MS_PER_PHASE_1X, GameConfig.Time.GAME_MS_PER_PHASE)
    }

    // ── 换算公式（C++ time_units.h 同名锚点）──────────────────────────

    @Test
    fun `conversion formulas divide by the period length`() {
        // 每旬 19.0 修为 → 每秒 9.5（REALM_SPEED_PER_PHASE 首行值锚）
        assertEquals(9.5, GameConfig.Time.perPhaseToPerGameSecond(19.0), 0.0)
        // 每月 3000 灵石 → 每秒 500
        assertEquals(500.0, GameConfig.Time.perMonthToPerGameSecond(3000.0), 0.0)
        // 每年 720 → 每秒 10
        assertEquals(10.0, GameConfig.Time.perYearToPerGameSecond(720.0), 0.0)
        // 恒等锚：1.0 每秒 = 2.0 每旬 = 6.0 每月 = 72.0 每年
        assertEquals(
            1.0, GameConfig.Time.perPhaseToPerGameSecond(2.0), 1e-12
        )
        assertEquals(1.0, GameConfig.Time.perMonthToPerGameSecond(6.0), 1e-12)
        assertEquals(1.0, GameConfig.Time.perYearToPerGameSecond(72.0), 1e-12)
    }

    // ── 日历投影（C++ time_system.h 同名锚点）────────────────────────

    @Test
    fun `calendar origin projects to zero game ms`() {
        assertEquals(0L, GameConfig.Time.calendarToGameMs(year = 1, month = 1, phase = 0))
        val origin = GameConfig.Time.projectCalendar(0L)
        assertEquals(1, origin.year)
        assertEquals(1, origin.month)
        assertEquals(0, origin.phase)
    }

    @Test
    fun `calendar and projection are exact inverses`() {
        for (year in 1..5) {
            for (month in 1..GameConfig.Time.MONTHS_PER_YEAR) {
                for (phase in 0 until GameConfig.Time.PHASES_PER_MONTH) {
                    val gameMs = GameConfig.Time.calendarToGameMs(year, month, phase)
                    val projected = GameConfig.Time.projectCalendar(gameMs)
                    assertEquals("y$year m$month p$phase", year, projected.year)
                    assertEquals("y$year m$month p$phase", month, projected.month)
                    assertEquals("y$year m$month p$phase", phase, projected.phase)
                }
            }
        }
    }

    @Test
    fun `one phase of game ms advances exactly one phase in projection`() {
        val next = GameConfig.Time.projectCalendar(GameConfig.Time.GAME_MS_PER_PHASE)
        assertEquals(1, next.year)
        assertEquals(1, next.month)
        assertEquals(1, next.phase)
        // 一年 = 72 游戏秒 = 36 旬 = 72000 游戏毫秒
        val yearMs = GameConfig.Time.calendarToGameMs(year = 2, month = 1, phase = 0)
        assertEquals(72_000L, yearMs)
    }

    @Test
    fun `negative input is clamped to calendar origin`() {
        val projected = GameConfig.Time.projectCalendar(-12345L)
        assertEquals(1, projected.year)
        assertEquals(1, projected.month)
        assertEquals(0, projected.phase)
        assertTrue(GameConfig.Time.calendarToGameMs(1, 1, 0) >= 0)
    }
}
