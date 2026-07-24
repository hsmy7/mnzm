package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator

import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test



class DiscipleDeadStatusRuleTest {
    @Before fun setup() { SaveValidationRuleRegistry.clear(); SaveValidationRuleRegistry.register(DiscipleDeadStatusRule) }
    @After fun teardown() { SaveValidationRuleRegistry.clear() }

    @Test fun `alive disciple with equipment passes`() {
        val d = Disciple(id = "d-1", name = "甲", realm = 9, realmLayer = 1, cultivation = 10.0,
            age = 20, lifespan = 80, isAlive = true,
            equipment = EquipmentSet(weaponId = "sword-1"))
        val data = saveData(listOf(d))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test fun `dead disciple equipment cleared`() {
        val d = Disciple(id = "d-1", name = "死者", realm = 9, realmLayer = 1, cultivation = 10.0,
            age = 80, lifespan = 80, isAlive = false,
            equipment = EquipmentSet(weaponId = "sword-1", armorId = "armor-1"))
        val data = saveData(listOf(d))
        val resultr = SaveValidator.validate(data)
        assertTrue(resultr is IntegrityResult.Repaired)
        val r = resultr as IntegrityResult.Repaired
        val equip = r.data.disciples.first().equipment
        assertEquals("", equip.weaponId)
        assertEquals("", equip.armorId)
    }

    @Test fun `dead disciple without equipment passes`() {
        val d = Disciple(id = "d-1", name = "死者", realm = 9, realmLayer = 1, cultivation = 10.0,
            age = 80, lifespan = 80, isAlive = false, equipment = EquipmentSet())
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(saveData(listOf(d))))
    }

    @Test fun `no disciples passes`() {
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(saveData(emptyList())))
    }

    private fun saveData(dd: List<Disciple>) = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1),
        disciples = dd, pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList(), teams = emptyList()
    )
}
