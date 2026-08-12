package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test





class GhostRefCleanupRuleTest {
    @Before fun setup() {
        SaveValidationRuleRegistry.clear()
        // 必须注册两条规则：先清理幽灵弟子，再清理引用
        SaveValidationRuleRegistry.register(GhostDiscipleCleanupRule)
        SaveValidationRuleRegistry.register(GhostRefCleanupRule)
    }
    @After fun teardown() { SaveValidationRuleRegistry.clear() }

    @Test fun `ghost disciple ref in residence slot is cleared`() {
        val bld = GridBuildingData(buildingId = "hall", instanceId = "bld-001", gridX = 0, gridY = 0)
        val ghost = makeDisciple("ghost-1", "")
        val slot = ResidenceSlot(buildingInstanceId = "bld-001", slotIndex = 0,
            discipleId = "ghost-1", discipleName = "幽灵")
        val gd = GameData(sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = listOf(bld), residenceSlots = listOf(slot))
        val data = SaveData(gameData = gd, disciples = listOf(ghost),
            pills = emptyList(), materials = emptyList(), herbs = emptyList(),
            seeds = emptyList())
        val resultr = SaveValidator.validate(data)
        assertTrue(resultr is IntegrityResult.Repaired)
        val r = resultr as IntegrityResult.Repaired
        // 幽灵弟子被移除
        assertTrue(r.data.disciples.isEmpty())
        // 槽位引用被清除
        val cleaned = r.data.gameData.residenceSlots.first()
        assertEquals("", cleaned.discipleId)
        assertEquals("", cleaned.discipleName)
    }

    @Test fun `valid disciple ref preserved after ghost cleanup`() {
        val bld = GridBuildingData(buildingId = "hall", instanceId = "bld-001", gridX = 0, gridY = 0)
        val ghost = makeDisciple("ghost-1", "")
        val valid = makeDisciple("d-1", "幸存")
        val orphanSlot = ResidenceSlot(buildingInstanceId = "bld-001", slotIndex = 0,
            discipleId = "ghost-1", discipleName = "幽灵")
        val validSlot = ResidenceSlot(buildingInstanceId = "bld-001", slotIndex = 1,
            discipleId = "d-1", discipleName = "幸存")
        val gd = GameData(sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = listOf(bld), residenceSlots = listOf(orphanSlot, validSlot))
        val data = SaveData(gameData = gd, disciples = listOf(ghost, valid),
            pills = emptyList(), materials = emptyList(), herbs = emptyList(),
            seeds = emptyList())
        val resultr = SaveValidator.validate(data)
        assertTrue(resultr is IntegrityResult.Repaired)
        val r = resultr as IntegrityResult.Repaired
        assertEquals(1, r.data.disciples.size)
        val slots = r.data.gameData.residenceSlots
        assertEquals(2, slots.size)
        assertEquals("", slots.find { it.slotIndex == 0 }!!.discipleId) // orphan cleared
        assertEquals("d-1", slots.find { it.slotIndex == 1 }!!.discipleId) // valid preserved
    }

    private fun makeDisciple(id: String, name: String) = Disciple(
        id = id, name = name, realm = 9, realmLayer = 1, cultivation = 10.0,
        age = 20, lifespan = 80, isAlive = true, equipment = EquipmentSet()
    )
}
