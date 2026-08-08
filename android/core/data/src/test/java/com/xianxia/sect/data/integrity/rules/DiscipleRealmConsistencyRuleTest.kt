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





class DiscipleRealmConsistencyRuleTest {
    @Before fun setup() { SaveValidationRuleRegistry.clear(); SaveValidationRuleRegistry.register(DiscipleRealmConsistencyRule) }
    @After fun teardown() { SaveValidationRuleRegistry.clear() }

    @Test fun `valid realm and layer passes`() {
        val data = saveData(listOf(disciple(realm = 9, layer = 5)))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test fun `realm out of range clamped`() {
        val data = saveData(listOf(disciple(realm = 99, layer = 1)))
        val resultr = SaveValidator.validate(data)
        assertTrue(resultr is IntegrityResult.Repaired)
        val r = resultr as IntegrityResult.Repaired
        assertEquals(9, r.data.disciples.first().realm)
    }

    @Test fun `negative realm clamped to 0`() {
        val data = saveData(listOf(disciple(realm = -5, layer = 1)))
        val resultr = SaveValidator.validate(data)
        assertTrue(resultr is IntegrityResult.Repaired)
        val r = resultr as IntegrityResult.Repaired
        assertEquals(0, r.data.disciples.first().realm)
    }

    @Test fun `layer exceeding max clamped`() {
        val data = saveData(listOf(disciple(realm = 9, layer = 99)))
        val resultr = SaveValidator.validate(data)
        assertTrue(resultr is IntegrityResult.Repaired)
        val r = resultr as IntegrityResult.Repaired
        assertEquals("炼气最多9层, 99→9", 9, r.data.disciples.first().realmLayer)
    }

    private fun disciple(realm: Int = 9, layer: Int = 1) = Disciple(
        id = "d-1", name = "甲", realm = realm, realmLayer = layer, cultivation = 10.0,
        age = 20, lifespan = 80, isAlive = true, equipment = EquipmentSet()
    )
    private fun saveData(d: List<Disciple>) = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1),
        disciples = d, pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList(), teams = emptyList()
    )
}
