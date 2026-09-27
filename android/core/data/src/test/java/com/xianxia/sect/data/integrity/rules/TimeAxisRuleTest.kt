package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 双轨时间权威轴规则测试（结算改造 2026-09-27 B3，方案 §4.3）：
 * 负值钳制 / 旧档归一化回填（与 C++ ensureBaselineTimeAxis 同公式）/
 * 投影一致性以权威轴为准（INV-1 防双真相源）/ 积分基准越界钳制 / 合法档 Passed。
 */
class TimeAxisRuleTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(TimeAxisRule)
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    private fun dataWith(
        year: Int = 1,
        month: Int = 1,
        phase: Int = 0,
        elapsed: Long = 0L,
        lastSettle: Long = 0L
    ) = SaveData(
        gameData = GameData(
            id = "t1",
            gameYear = year,
            gameMonth = month,
            gamePhase = phase,
            elapsedGameMs = elapsed,
            lastSettleGameMs = lastSettle
        ),
        disciples = emptyList(), pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList()
    )

    @Test
    fun `consistent state passes`() {
        val elapsed = GameConfig.Time.calendarToGameMs(3, 5, 2)
        val result = SaveValidator.validate(
            dataWith(year = 3, month = 5, phase = 2, elapsed = elapsed, lastSettle = elapsed)
        )
        assertEquals(IntegrityResult.Passed, result)
    }

    @Test
    fun `new save at calendar origin passes`() {
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(dataWith()))
    }

    @Test
    fun `negative axis is clamped to zero`() {
        val result = SaveValidator.validate(dataWith(elapsed = -5_000L, lastSettle = -1L))
        assertTrue(result is IntegrityResult.Repaired)
        val gd = (result as IntegrityResult.Repaired).data.gameData
        assertEquals(0L, gd.elapsedGameMs)
        assertEquals(0L, gd.lastSettleGameMs)
    }

    @Test
    fun `legacy save with calendar only backfills axis`() {
        val result = SaveValidator.validate(dataWith(year = 12, month = 7, phase = 1))
        assertTrue(result is IntegrityResult.Repaired)
        val gd = (result as IntegrityResult.Repaired).data.gameData
        val expected = GameConfig.Time.calendarToGameMs(12, 7, 1)
        assertEquals("回填值 = calendarToGameMs（与 C++ 同公式）", expected, gd.elapsedGameMs)
        assertEquals("回填时积分基准与权威轴同刻", expected, gd.lastSettleGameMs)
        assertEquals("日历本身合法不变", 12, gd.gameYear)
    }

    @Test
    fun `calendar inconsistent with axis is recomputed from axis`() {
        // 权威轴对应 2 年 1 月上旬；日历被手改为 5 年 3 月 → 以权威轴为准重算
        val elapsed = GameConfig.Time.calendarToGameMs(2, 1, 0)
        val result = SaveValidator.validate(
            dataWith(year = 5, month = 3, phase = 2, elapsed = elapsed, lastSettle = elapsed)
        )
        assertTrue(result is IntegrityResult.Repaired)
        val gd = (result as IntegrityResult.Repaired).data.gameData
        assertEquals(2, gd.gameYear)
        assertEquals(1, gd.gameMonth)
        assertEquals(0, gd.gamePhase)
        assertEquals("权威轴本身不被改写", elapsed, gd.elapsedGameMs)
    }

    @Test
    fun `lastSettle beyond axis is clamped to axis`() {
        val elapsed = GameConfig.Time.calendarToGameMs(4, 2, 1)
        val result = SaveValidator.validate(
            dataWith(year = 4, month = 2, phase = 1, elapsed = elapsed, lastSettle = elapsed + 999)
        )
        assertTrue(result is IntegrityResult.Repaired)
        val gd = (result as IntegrityResult.Repaired).data.gameData
        assertEquals(elapsed, gd.lastSettleGameMs)
    }
}
