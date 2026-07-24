package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator

import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test



class AgeLifespanRuleTest {
    @Before fun setup() { SaveValidationRuleRegistry.clear(); SaveValidationRuleRegistry.register(AgeLifespanRule) }
    @After fun teardown() { SaveValidationRuleRegistry.clear() }

    @Test fun `age within lifespan passes`() {
        val data = saveData(listOf(disciple(age = 50, lifespan = 120)))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test fun `age exceeding lifespan clamps`() {
        val data = saveData(listOf(disciple(age = 100, lifespan = 80)))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val repaired = result as IntegrityResult.Repaired
        assertEquals(80, repaired.data.disciples.first().age)
    }

    @Test fun `dead disciple age exceeding lifespan unchanged`() {
        val data = saveData(listOf(disciple(age = 200, lifespan = 80, isAlive = false)))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test fun `age equals lifespan passes`() {
        val data = saveData(listOf(disciple(age = 80, lifespan = 80)))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    private fun disciple(age: Int = 20, lifespan: Int = 80, isAlive: Boolean = true) = Disciple(
        id = "d-1", name = "甲", realm = 9, realmLayer = 1, cultivation = 10.0,
        age = age, lifespan = lifespan, isAlive = isAlive,
        equipment = EquipmentSet()
    )
    private fun saveData(d: List<Disciple>) = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1),
        disciples = d, pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList(), teams = emptyList()
    )
}
