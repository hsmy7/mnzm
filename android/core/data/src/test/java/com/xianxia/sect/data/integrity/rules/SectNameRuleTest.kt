package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test



class SectNameRuleTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(SectNameRule)
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    @Test
    fun `blank name returns Repaired with default name`() {
        val data = SaveData(
            gameData = GameData(sectName = "", gameYear = 1, gameMonth = 1),
            disciples = emptyList(), pills = emptyList(), materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(), teams = emptyList()
        )
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals("青云宗", (result as IntegrityResult.Repaired).data.gameData.sectName)
    }

    @Test
    fun `whitespace only name returns Repaired`() {
        val data = SaveData(
            gameData = GameData(sectName = "   ", gameYear = 1, gameMonth = 1),
            disciples = emptyList(), pills = emptyList(), materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(), teams = emptyList()
        )
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals("青云宗", (result as IntegrityResult.Repaired).data.gameData.sectName)
    }

    @Test
    fun `valid name returns Passed`() {
        val data = SaveData(
            gameData = GameData(sectName = "青云宗", gameYear = 1, gameMonth = 1),
            disciples = emptyList(), pills = emptyList(), materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(), teams = emptyList()
        )
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test
    fun `very long name does not crash`() {
        val data = SaveData(
            gameData = GameData(sectName = "A".repeat(10000), gameYear = 1, gameMonth = 1),
            disciples = emptyList(), pills = emptyList(), materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(), teams = emptyList()
        )
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Passed || result is IntegrityResult.Repaired)
    }
}
