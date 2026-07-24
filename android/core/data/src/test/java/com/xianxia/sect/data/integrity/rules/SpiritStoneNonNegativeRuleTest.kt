package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test



class SpiritStoneNonNegativeRuleTest {
    @Before fun setup() { SaveValidationRuleRegistry.clear(); SaveValidationRuleRegistry.register(SpiritStoneNonNegativeRule) }
    @After fun teardown() { SaveValidationRuleRegistry.clear() }

    @Test fun `positive stones passes`() {
        val gd = GameData(sectName = "宗", gameYear = 1, gameMonth = 1, spiritStones = 100)
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(saveData(gd)))
    }

    @Test fun `zero stones passes`() {
        val gd = GameData(sectName = "宗", gameYear = 1, gameMonth = 1)
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(saveData(gd)))
    }

    @Test fun `negative low grade repaired to 0`() {
        val gd = GameData(sectName = "宗", gameYear = 1, gameMonth = 1, spiritStones = -500)
        val resultr = SaveValidator.validate(saveData(gd))
        assertTrue(resultr is IntegrityResult.Repaired)
        val r = resultr as IntegrityResult.Repaired
        assertEquals(0L, r.data.gameData.spiritStones)
    }

    @Test fun `all grades negative repaired`() {
        val gd = GameData(sectName = "宗", gameYear = 1, gameMonth = 1,
            spiritStones = -1, midGradeSpiritStones = -2, highGradeSpiritStones = -3)
        val resultr = SaveValidator.validate(saveData(gd))
        assertTrue(resultr is IntegrityResult.Repaired)
        val r = resultr as IntegrityResult.Repaired
        assertEquals(0L, r.data.gameData.spiritStones)
        assertEquals(0L, r.data.gameData.midGradeSpiritStones)
        assertEquals(0L, r.data.gameData.highGradeSpiritStones)
    }

    private fun saveData(gd: GameData) = SaveData(
        gameData = gd, disciples = emptyList(), pills = emptyList(),
        materials = emptyList(), herbs = emptyList(), seeds = emptyList(),
        teams = emptyList()
    )
}
