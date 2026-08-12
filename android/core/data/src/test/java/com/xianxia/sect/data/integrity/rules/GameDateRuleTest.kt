package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test



class GameDateRuleTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(GameDateRule)
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    @Test
    fun `valid date returns Passed`() {
        val data = saveDataWith(GameData(sectName = "宗", gameYear = 5, gameMonth = 6))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test
    fun `year zero repairs to 1`() {
        val data = saveDataWith(GameData(sectName = "宗", gameYear = 0, gameMonth = 6))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(1, (result as IntegrityResult.Repaired).data.gameData.gameYear)
    }

    @Test
    fun `month zero repairs to 1`() {
        val data = saveDataWith(GameData(sectName = "宗", gameYear = 3, gameMonth = 0))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(1, (result as IntegrityResult.Repaired).data.gameData.gameMonth)
    }

    @Test
    fun `month 13 repairs to 12`() {
        val data = saveDataWith(GameData(sectName = "宗", gameYear = 3, gameMonth = 13))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(12, (result as IntegrityResult.Repaired).data.gameData.gameMonth)
    }

    @Test
    fun `negative month repairs to 1`() {
        val data = saveDataWith(GameData(sectName = "宗", gameYear = 3, gameMonth = -5))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(1, (result as IntegrityResult.Repaired).data.gameData.gameMonth)
    }

    @Test
    fun `negative year repairs to 1`() {
        val data = saveDataWith(GameData(sectName = "宗", gameYear = -100, gameMonth = 6))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(1, (result as IntegrityResult.Repaired).data.gameData.gameYear)
    }

    private fun saveDataWith(gd: GameData) = SaveData(
        gameData = gd, disciples = emptyList(), pills = emptyList(),
        materials = emptyList(), herbs = emptyList(), seeds = emptyList(),
            )
}
