package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RuleContextTest {

    private val emptySaveData = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1),
        disciples = emptyList(), pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList(), teams = emptyList()
    )

    @After fun teardown() { SaveValidationRuleRegistry.clear() }

    @Test fun `allEquipmentIds includes stacks and instances`() {
        val data = emptySaveData.copy(
            equipmentStacks = listOf(EquipmentStack(id = "s1", name = "堆叠", rarity = 1, description = "")),
            equipmentInstances = listOf(EquipmentInstance(id = "i1", name = "单件", rarity = 1, description = ""))
        )
        val ctx = RuleContext(data)
        assertEquals(setOf("s1", "i1"), ctx.allEquipmentIds)
    }

    @Test fun `allEquipmentIds empty when no equipment`() {
        val ctx = RuleContext(emptySaveData)
        assertTrue(ctx.allEquipmentIds.isEmpty())
    }

    @Test fun `buildingInstanceIds includes non-empty instanceIds`() {
        val gd = GameData(sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = listOf(
                GridBuildingData(buildingId = "hall", instanceId = "b-1", gridX = 0, gridY = 0),
                GridBuildingData(buildingId = "hall", instanceId = "", gridX = 2, gridY = 0)))
        val data = emptySaveData.copy(gameData = gd)
        val ctx = RuleContext(data)
        assertEquals(setOf("b-1"), ctx.buildingInstanceIds)
    }

    @Test fun `removedDiscipleIds starts empty`() {
        val ctx = RuleContext(emptySaveData)
        assertTrue(ctx.removedDiscipleIds.isEmpty())
    }

    @Test fun `removedDiscipleIds is writable`() {
        val ctx = RuleContext(emptySaveData)
        ctx.removedDiscipleIds.add("d-1")
        assertTrue("d-1" in ctx.removedDiscipleIds)
    }
}
