package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.*
import org.junit.Assert.*
import org.junit.Test

class DiscipleSlotCleanupTest {

    private val testDiscipleId = "disciple_to_remove"

    private fun createGameDataWithDiscipleInSlots(discipleId: String): GameData {
        val discipleSlot = DirectDiscipleSlot(index = 0, discipleId = discipleId, discipleName = "Test")
        val spiritMineSlot = SpiritMineSlot(index = 0, discipleId = discipleId, discipleName = "Test")
        val librarySlot = LibrarySlot(index = 0, discipleId = discipleId, discipleName = "Test")
        val residenceSlot = ResidenceSlot(discipleId = discipleId, discipleName = "Test")
        val warehouseGarrison = WarehouseGarrisonSlot(discipleId = discipleId, discipleName = "Test")
        val patrolSlot = PatrolSlot(index = 0, discipleId = discipleId, discipleName = "Test")
        val garrisonSlot = GarrisonSlot(index = 0, discipleId = discipleId, discipleName = "Test")
        val battleTeamSlot = BattleTeamSlot(index = 0, discipleId = discipleId, discipleName = "Test")

        val elderSlots = ElderSlots(
            viceSectMaster = discipleId,
            herbGardenElder = discipleId,
            alchemyElder = discipleId,
            forgeElder = discipleId,
            outerElder = discipleId,
            preachingElder = discipleId,
            lawEnforcementElder = discipleId,
            innerElder = discipleId,
            qingyunPreachingElder = discipleId,
            preachingMasters = listOf(discipleSlot),
            lawEnforcementDisciples = listOf(discipleSlot),
            qingyunPreachingMasters = listOf(discipleSlot),
            herbGardenDisciples = listOf(discipleSlot),
            alchemyDisciples = listOf(discipleSlot),
            forgeDisciples = listOf(discipleSlot),
            lawEnforcementReserveDisciples = listOf(discipleSlot),
            herbGardenReserveDisciples = listOf(discipleSlot),
            alchemyReserveDisciples = listOf(discipleSlot),
            forgeReserveDisciples = listOf(discipleSlot),
            spiritMineDeaconDisciples = listOf(discipleSlot)
        )

        val bloodRefinement = BloodRefinementProgress(
            discipleId = discipleId,
            discipleName = "Test"
        )

        val battleTeam = BattleTeam(
            id = "team1",
            slots = listOf(battleTeamSlot)
        )

        val playerSect = WorldSect(
            id = "player_sect",
            isPlayerSect = true,
            garrisonSlots = listOf(garrisonSlot)
        )

        return GameData(
            spiritMineSlots = listOf(spiritMineSlot),
            librarySlots = listOf(librarySlot),
            elderSlots = elderSlots,
            residenceSlots = listOf(residenceSlot),
            activeBloodRefinements = mapOf("br1" to bloodRefinement),
            patrolSlots = listOf(patrolSlot),
            warehouseGarrisons = listOf(warehouseGarrison),
            battleTeams = listOf(battleTeam),
            worldMapSects = listOf(playerSect)
        )
    }

    // ---- clearAllSlots ----

    @Test
    fun clearAllSlots_clearsSpiritMineSlots() {
        val data = createGameDataWithDiscipleInSlots(testDiscipleId)
        val result = DiscipleSlotCleanup.clearAllSlots(data, testDiscipleId)
        for (slot in result.spiritMineSlots) {
            assertNotEquals(testDiscipleId, slot.discipleId)
        }
    }

    @Test
    fun clearAllSlots_clearsLibrarySlots() {
        val data = createGameDataWithDiscipleInSlots(testDiscipleId)
        val result = DiscipleSlotCleanup.clearAllSlots(data, testDiscipleId)
        for (slot in result.librarySlots) {
            assertNotEquals(testDiscipleId, slot.discipleId)
        }
    }

    @Test
    fun clearAllSlots_clearsResidenceSlots() {
        val data = createGameDataWithDiscipleInSlots(testDiscipleId)
        val result = DiscipleSlotCleanup.clearAllSlots(data, testDiscipleId)
        for (slot in result.residenceSlots) {
            assertNotEquals(testDiscipleId, slot.discipleId)
        }
    }

    @Test
    fun clearAllSlots_clearsPatrolSlots() {
        val data = createGameDataWithDiscipleInSlots(testDiscipleId)
        val result = DiscipleSlotCleanup.clearAllSlots(data, testDiscipleId)
        for (slot in result.patrolSlots) {
            assertNotEquals(testDiscipleId, slot.discipleId)
        }
    }

    @Test
    fun clearAllSlots_clearsWarehouseGarrisons() {
        val data = createGameDataWithDiscipleInSlots(testDiscipleId)
        val result = DiscipleSlotCleanup.clearAllSlots(data, testDiscipleId)
        for (slot in result.warehouseGarrisons) {
            assertNotEquals(testDiscipleId, slot.discipleId)
        }
    }

    @Test
    fun clearAllSlots_clearsElderSlots() {
        val data = createGameDataWithDiscipleInSlots(testDiscipleId)
        val result = DiscipleSlotCleanup.clearAllSlots(data, testDiscipleId)
        assertEquals("", result.elderSlots.viceSectMaster)
        assertEquals("", result.elderSlots.herbGardenElder)
        assertEquals("", result.elderSlots.alchemyElder)
        assertEquals("", result.elderSlots.forgeElder)
        assertEquals("", result.elderSlots.outerElder)
        assertEquals("", result.elderSlots.preachingElder)
        assertEquals("", result.elderSlots.lawEnforcementElder)
        assertEquals("", result.elderSlots.innerElder)
        assertEquals("", result.elderSlots.qingyunPreachingElder)
    }

    @Test
    fun clearAllSlots_clearsElderDiscipleSlots() {
        val data = createGameDataWithDiscipleInSlots(testDiscipleId)
        val result = DiscipleSlotCleanup.clearAllSlots(data, testDiscipleId)
        for (slot in result.elderSlots.preachingMasters) {
            assertNotEquals(testDiscipleId, slot.discipleId)
        }
        for (slot in result.elderSlots.herbGardenDisciples) {
            assertNotEquals(testDiscipleId, slot.discipleId)
        }
        for (slot in result.elderSlots.alchemyDisciples) {
            assertNotEquals(testDiscipleId, slot.discipleId)
        }
        for (slot in result.elderSlots.forgeDisciples) {
            assertNotEquals(testDiscipleId, slot.discipleId)
        }
    }

    @Test
    fun clearAllSlots_clearsBloodRefinements() {
        val data = createGameDataWithDiscipleInSlots(testDiscipleId)
        val result = DiscipleSlotCleanup.clearAllSlots(data, testDiscipleId)
        for ((_, progress) in result.activeBloodRefinements) {
            assertNotEquals(testDiscipleId, progress.discipleId)
        }
    }

    @Test
    fun clearAllSlots_clearsBattleTeamSlots() {
        val data = createGameDataWithDiscipleInSlots(testDiscipleId)
        val result = DiscipleSlotCleanup.clearAllSlots(data, testDiscipleId)
        for (team in result.battleTeams) {
            for (slot in team.slots) {
                assertNotEquals(testDiscipleId, slot.discipleId)
            }
        }
    }

    @Test
    fun clearAllSlots_clearsGarrisonSlots() {
        val data = createGameDataWithDiscipleInSlots(testDiscipleId)
        val result = DiscipleSlotCleanup.clearAllSlots(data, testDiscipleId)
        val playerSect = result.worldMapSects.find { it.isPlayerSect }
        assertNotNull(playerSect)
        for (slot in playerSect!!.garrisonSlots) {
            assertNotEquals(testDiscipleId, slot.discipleId)
        }
    }

    @Test
    fun clearAllSlots_doesNotAffectOtherDisciples() {
        val otherId = "other_disciple"
        val data = createGameDataWithDiscipleInSlots(testDiscipleId).copy(
            spiritMineSlots = listOf(
                SpiritMineSlot(index = 0, discipleId = testDiscipleId, discipleName = "Test"),
                SpiritMineSlot(index = 1, discipleId = otherId, discipleName = "Other")
            )
        )
        val result = DiscipleSlotCleanup.clearAllSlots(data, testDiscipleId)
        val otherSlot = result.spiritMineSlots.find { it.index == 1 }
        assertNotNull(otherSlot)
        assertEquals(otherId, otherSlot!!.discipleId)
    }

    @Test
    fun clearAllSlots_nonExistentDisciple_noChange() {
        val data = createGameDataWithDiscipleInSlots(testDiscipleId)
        val result = DiscipleSlotCleanup.clearAllSlots(data, "nonexistent_id")
        assertEquals(testDiscipleId, result.spiritMineSlots[0].discipleId)
    }

    @Test
    fun clearAllSlots_emptyGameData_noException() {
        val data = GameData()
        val result = DiscipleSlotCleanup.clearAllSlots(data, testDiscipleId)
        assertNotNull(result)
        assertEquals(0, result.spiritMineSlots.size)
    }

    @Test
    fun clearAllSlots_doesNotAffectNonPlayerSects() {
        val aiSect = WorldSect(
            id = "ai_sect",
            isPlayerSect = false,
            garrisonSlots = listOf(GarrisonSlot(index = 0, discipleId = testDiscipleId, discipleName = "Test"))
        )
        val data = GameData(worldMapSects = listOf(aiSect))
        val result = DiscipleSlotCleanup.clearAllSlots(data, testDiscipleId)
        val resultAiSect = result.worldMapSects.find { it.id == "ai_sect" }
        assertNotNull(resultAiSect)
        // Non-player sects should not be modified
        assertEquals(testDiscipleId, resultAiSect!!.garrisonSlots[0].discipleId)
    }
}
