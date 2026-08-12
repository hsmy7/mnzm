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

class JadeSymbolNonNegativeRuleTest {
    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(JadeSymbolNonNegativeRule)
    }

    @After
    fun teardown() { SaveValidationRuleRegistry.clear() }

    @Test
    fun `positive jade fields pass`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            jadeSymbols = 12, jadeSymbolsToday = 5, jadeAccumMs = 123_456L, jadeDayAnchorMs = 1_000_000L
        )
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(saveData(gd)))
    }

    @Test
    fun `zero jade fields pass`() {
        val gd = GameData(sectName = "宗", gameYear = 1, gameMonth = 1)
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(saveData(gd)))
    }

    @Test
    fun `negative counts repaired to 0`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            jadeSymbols = -3, jadeSymbolsToday = -1
        )
        val result = SaveValidator.validate(saveData(gd))
        assertTrue(result is IntegrityResult.Repaired)
        val r = result as IntegrityResult.Repaired
        assertEquals(0, r.data.gameData.jadeSymbols)
        assertEquals(0, r.data.gameData.jadeSymbolsToday)
    }

    @Test
    fun `accum ms above interval clamped`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            jadeAccumMs = GameConfig.Jade.INTERVAL_MS + 5_000L
        )
        val result = SaveValidator.validate(saveData(gd))
        assertTrue(result is IntegrityResult.Repaired)
        val r = result as IntegrityResult.Repaired
        assertEquals(GameConfig.Jade.INTERVAL_MS - 1, r.data.gameData.jadeAccumMs)
    }

    @Test
    fun `negative anchor repaired to 0`() {
        val gd = GameData(sectName = "宗", gameYear = 1, gameMonth = 1, jadeDayAnchorMs = -999L)
        val result = SaveValidator.validate(saveData(gd))
        assertTrue(result is IntegrityResult.Repaired)
        val r = result as IntegrityResult.Repaired
        assertEquals(0L, r.data.gameData.jadeDayAnchorMs)
    }

    @Test
    fun `accum ms just below interval passes`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            jadeAccumMs = GameConfig.Jade.INTERVAL_MS - 1
        )
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(saveData(gd)))
    }

    @Test
    fun `accum ms exactly at interval clamped below threshold`() {
        // 钳制上限必须严格小于发放阈值：恰等于 20 分钟的存档读档首帧即免费 +1 玉符
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            jadeAccumMs = GameConfig.Jade.INTERVAL_MS
        )
        val result = SaveValidator.validate(saveData(gd))
        assertTrue(result is IntegrityResult.Repaired)
        val r = result as IntegrityResult.Repaired
        assertEquals(GameConfig.Jade.INTERVAL_MS - 1, r.data.gameData.jadeAccumMs)
    }

    @Test
    fun `today above daily cap clamped`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            jadeSymbolsToday = 999
        )
        val result = SaveValidator.validate(saveData(gd))
        assertTrue(result is IntegrityResult.Repaired)
        val r = result as IntegrityResult.Repaired
        assertEquals(GameConfig.Jade.DAILY_CAP, r.data.gameData.jadeSymbolsToday)
    }

    @Test
    fun `jadeSymbols at Int max clamped below overflow line`() {
        // 持有 Int.MAX_VALUE 时首次发放 +1 会回绕为负（自愈清空），钳至安全上限
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            jadeSymbols = Int.MAX_VALUE
        )
        val result = SaveValidator.validate(saveData(gd))
        assertTrue(result is IntegrityResult.Repaired)
        val r = result as IntegrityResult.Repaired
        assertEquals(Int.MAX_VALUE - GameConfig.Jade.DAILY_CAP, r.data.gameData.jadeSymbols)
    }

    @Test
    fun `unrelated fields untouched`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            spiritStones = 500L, jadeSymbols = -2
        )
        val result = SaveValidator.validate(saveData(gd))
        assertTrue(result is IntegrityResult.Repaired)
        val r = result as IntegrityResult.Repaired
        assertEquals(500L, r.data.gameData.spiritStones)
        assertEquals(0, r.data.gameData.jadeSymbols)
    }

    private fun saveData(gd: GameData) = SaveData(
        gameData = gd, disciples = emptyList(), pills = emptyList(),
        materials = emptyList(), herbs = emptyList(), seeds = emptyList(),
            )
}
