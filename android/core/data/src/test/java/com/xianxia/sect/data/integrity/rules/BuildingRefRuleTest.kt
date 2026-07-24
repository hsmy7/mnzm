package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator

import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test



class BuildingRefRuleTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(BuildingRefRule)
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    @Test
    fun `valid building ref returns Passed`() {
        val gd = GameData(sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = listOf(gridBuilding("bld-001")),
            residenceSlots = listOf(ResidenceSlot(buildingInstanceId = "bld-001", slotIndex = 0)))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(saveData(gd)))
    }

    @Test
    fun `orphan building ref removes slot`() {
        val gd = GameData(sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = emptyList(),
            residenceSlots = listOf(ResidenceSlot(buildingInstanceId = "ghost-bld", slotIndex = 0)))
        val result = SaveValidator.validate(saveData(gd))
        assertTrue(result is IntegrityResult.Repaired)
        assertTrue((result as IntegrityResult.Repaired).data.gameData.residenceSlots.isEmpty())
    }

    @Test
    fun `empty buildingInstanceId is valid`() {
        val gd = GameData(sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = emptyList(),
            residenceSlots = listOf(ResidenceSlot(buildingInstanceId = "", slotIndex = 0)))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(saveData(gd)))
    }

    @Test
    fun `mixed valid and orphan slots removes only orphan`() {
        val gd = GameData(sectName = "宗", gameYear = 1, gameMonth = 1,
            placedBuildings = listOf(gridBuilding("bld-001")),
            residenceSlots = listOf(
                ResidenceSlot(buildingInstanceId = "bld-001", slotIndex = 0),
                ResidenceSlot(buildingInstanceId = "ghost-bld", slotIndex = 1)))
        val result = SaveValidator.validate(saveData(gd))
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(1, (result as IntegrityResult.Repaired).data.gameData.residenceSlots.size)
        assertEquals("bld-001", (result as IntegrityResult.Repaired).data.gameData.residenceSlots.first().buildingInstanceId)
    }

    private fun gridBuilding(instanceId: String) = GridBuildingData(
        buildingId = "hall", instanceId = instanceId, gridX = 0, gridY = 0, width = 2, height = 2)

    private fun saveData(gd: GameData) = SaveData(
        gameData = gd, disciples = emptyList(), pills = emptyList(),
        materials = emptyList(), herbs = emptyList(), seeds = emptyList(),
        teams = emptyList()
    )
}
