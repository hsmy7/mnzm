package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator

import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test



class DuplicateDiscipleIdRuleTest {
    @Before fun setup() { SaveValidationRuleRegistry.clear(); SaveValidationRuleRegistry.register(DuplicateDiscipleIdRule) }
    @After fun teardown() { SaveValidationRuleRegistry.clear() }

    @Test fun `unique disciple IDs passes`() {
        val data = saveData(listOf(d("d-1"), d("d-2")))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test fun `duplicate IDs removes second`() {
        val data = saveData(listOf(d("d-dup"), d("d-dup").copy(name = "乙")))
        val resultr = SaveValidator.validate(data)
        assertTrue(resultr is IntegrityResult.Repaired)
        val r = resultr as IntegrityResult.Repaired
        assertEquals(1, r.data.disciples.size)
        assertEquals("甲", r.data.disciples.first().name)
    }

    @Test fun `triple duplicate keeps first only`() {
        val data = saveData(listOf(d("d-x"), d("d-x").copy(name = "乙"), d("d-x").copy(name = "丙")))
        val resultr = SaveValidator.validate(data)
        assertTrue(resultr is IntegrityResult.Repaired)
        val r = resultr as IntegrityResult.Repaired
        assertEquals(1, r.data.disciples.size)
    }

    @Test fun `no disciples passes`() {
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(saveData(emptyList())))
    }

    private fun d(id: String) = Disciple(id = id, name = "甲", realm = 9, realmLayer = 1,
        cultivation = 10.0, age = 20, lifespan = 80, isAlive = true, equipment = EquipmentSet())
    private fun saveData(dd: List<Disciple>) = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1),
        disciples = dd, pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList(), teams = emptyList()
    )
}
