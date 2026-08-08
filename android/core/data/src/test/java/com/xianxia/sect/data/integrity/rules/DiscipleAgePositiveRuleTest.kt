package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test





class DiscipleAgePositiveRuleTest {
    @Before fun setup() { SaveValidationRuleRegistry.clear(); SaveValidationRuleRegistry.register(DiscipleAgePositiveRule) }
    @After fun teardown() { SaveValidationRuleRegistry.clear() }

    @Test fun `positive age passes`() {
        val data = saveData(listOf(d(age = 20)))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test fun `zero age passes`() {
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(saveData(listOf(d(age = 0)))))
    }

    @Test fun `negative age repaired to 16`() {
        val data = saveData(listOf(d(age = -5)))
        val resultr = SaveValidator.validate(data)
        assertTrue(resultr is IntegrityResult.Repaired)
        val r = resultr as IntegrityResult.Repaired
        assertEquals(16, r.data.disciples.first().age)
    }

    @Test fun `very negative age repaired`() {
        val data = saveData(listOf(d(age = -999)))
        val resultr = SaveValidator.validate(data)
        assertTrue(resultr is IntegrityResult.Repaired)
        val r = resultr as IntegrityResult.Repaired
        assertEquals(16, r.data.disciples.first().age)
    }

    private fun d(age: Int) = Disciple(id = "d-1", name = "甲", age = age, realm = 9, realmLayer = 1,
        cultivation = 10.0, lifespan = 80, isAlive = true, equipment = EquipmentSet())
    private fun saveData(dd: List<Disciple>) = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1),
        disciples = dd, pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList(), teams = emptyList()
    )
}
