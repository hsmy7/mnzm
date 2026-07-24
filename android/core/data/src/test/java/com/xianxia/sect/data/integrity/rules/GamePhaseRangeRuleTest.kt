package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test



class GamePhaseRangeRuleTest {
    @Before fun setup() { SaveValidationRuleRegistry.clear(); SaveValidationRuleRegistry.register(GamePhaseRangeRule) }
    @After fun teardown() { SaveValidationRuleRegistry.clear() }

    @Test fun `phase 0 passes`() {
        assertEquals(IntegrityResult.Passed, validate(0))
    }

    @Test fun `phase 1 passes`() {
        assertEquals(IntegrityResult.Passed, validate(1))
    }

    @Test fun `phase 2 passes`() {
        assertEquals(IntegrityResult.Passed, validate(2))
    }

    @Test fun `phase 3 repaired to 0`() {
        val result = validate(3)
        assertTrue(result is IntegrityResult.Repaired)
        val r = result as IntegrityResult.Repaired
        assertEquals(0, r.data.gameData.gamePhase)
    }

    @Test fun `phase negative repaired to 0`() {
        val result = validate(-1)
        assertTrue(result is IntegrityResult.Repaired)
        val r = result as IntegrityResult.Repaired
        assertEquals(0, r.data.gameData.gamePhase)
    }

    @Test fun `phase 15 old gameDay mapped`() {
        val result = validate(15)
        assertTrue(result is IntegrityResult.Repaired)
        val r = result as IntegrityResult.Repaired
        assertTrue(r.data.gameData.gamePhase in 0..2)
    }

    private fun validate(phase: Int) = SaveValidator.validate(SaveData(
        gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 6, gamePhase = phase),
        disciples = emptyList(), pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList(), teams = emptyList()
    ))
}
