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





class GhostDiscipleCleanupRuleTest {
    @Before fun setup() { SaveValidationRuleRegistry.clear(); SaveValidationRuleRegistry.register(GhostDiscipleCleanupRule) }
    @After fun teardown() { SaveValidationRuleRegistry.clear() }

    @Test fun `no ghost disciples passes`() {
        val data = saveData(listOf(makeDisciple("d-1", "正常")))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test fun `ghost disciple is removed`() {
        val data = saveData(listOf(makeDisciple("d-1", ""), makeDisciple("d-2", "正常")))
        val resultr = SaveValidator.validate(data)
        assertTrue(resultr is IntegrityResult.Repaired)
        val r = resultr as IntegrityResult.Repaired
        assertEquals(1, r.data.disciples.size)
        assertEquals("d-2", r.data.disciples.first().id)
    }

    @Test fun `whitespace name treated as ghost`() {
        val data = saveData(listOf(makeDisciple("d-1", "   ")))
        val resultr = SaveValidator.validate(data)
        assertTrue(resultr is IntegrityResult.Repaired)
        val r = resultr as IntegrityResult.Repaired
        assertTrue(r.data.disciples.isEmpty())
    }

    @Test fun `all ghost disciples removed`() {
        val data = saveData(listOf(makeDisciple("d-1", ""), makeDisciple("d-2", "")))
        val resultr = SaveValidator.validate(data)
        assertTrue(resultr is IntegrityResult.Repaired)
        val r = resultr as IntegrityResult.Repaired
        assertTrue(r.data.disciples.isEmpty())
    }

    private fun makeDisciple(id: String, name: String) = Disciple(
        id = id, name = name, realm = 9, realmLayer = 1, cultivation = 10.0,
        age = 20, lifespan = 80, isAlive = true, equipment = EquipmentSet()
    )
    private fun saveData(d: List<Disciple>) = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1),
        disciples = d, pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList()
    )
}
