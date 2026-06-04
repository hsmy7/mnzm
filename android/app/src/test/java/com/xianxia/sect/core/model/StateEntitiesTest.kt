package com.xianxia.sect.core.model

import org.junit.Assert.*
import org.junit.Test

class StateEntitiesTest {

    // ---- SectOrganizationState ----

    @Test
    fun sectOrganizationState_defaultConstruction() {
        val state = SectOrganizationState()
        assertEquals(ElderSlots(), state.elderSlots)
        assertEquals(emptyList<Alliance>(), state.alliances)
        assertEquals(emptyList<BattleTeam>(), state.battleTeams)
        assertEquals(emptyList<AIBattleTeam>(), state.aiBattleTeams)
        assertEquals(SectPolicies(), state.sectPolicies)
        assertEquals(emptyList<ActiveMission>(), state.activeMissions)
        assertEquals(emptyList<Mission>(), state.availableMissions)
        assertEquals(emptyList<String>(), state.usedRedeemCodes)
    }

    @Test
    fun sectOrganizationState_copy() {
        val original = SectOrganizationState()
        val copied = original.copy(usedRedeemCodes = listOf("CODE1"))
        assertEquals(listOf("CODE1"), copied.usedRedeemCodes)
    }

    // ---- SectPolicyState ----

    @Test
    fun sectPolicyState_defaultConstruction() {
        val state = SectPolicyState()
        assertEquals(1, state.slotId)
        assertEquals(SectPolicies(), state.sectPolicies)
        assertEquals(emptySet<Int>(), state.autoRecruitSpiritRootFilter)
        assertEquals(emptySet<Int>(), state.daoCompanionBannedRootCounts)
        assertFalse(state.daoCompanionConsentRequired)
        assertFalse(state.breakthroughAutoPillFocused)
        assertEquals(emptySet<Int>(), state.breakthroughAutoPillRootCounts)
        assertFalse(state.autoEquipFromWarehouseFocused)
        assertEquals(emptySet<Int>(), state.autoEquipFromWarehouseRootCounts)
        assertFalse(state.autoLearnFromWarehouseFocused)
        assertEquals(emptySet<Int>(), state.autoLearnFromWarehouseRootCounts)
        assertEquals(3, state.autoSaveIntervalMonths)
    }

    @Test
    fun sectPolicyState_monthlySalaryDefaults() {
        val state = SectPolicyState()
        val salary = state.monthlySalary
        assertEquals(20, salary[9])
        assertEquals(60, salary[8])
        assertEquals(100, salary[7])
        assertEquals(160, salary[6])
        assertEquals(220, salary[5])
        assertEquals(360, salary[4])
        assertEquals(440, salary[3])
        assertEquals(560, salary[2])
        assertEquals(720, salary[1])
        assertEquals(1000, salary[0])
    }

    @Test
    fun sectPolicyState_monthlySalaryEnabledDefaults() {
        val state = SectPolicyState()
        val enabled = state.monthlySalaryEnabled
        for (realm in 0..9) {
            assertTrue("Realm $realm should be enabled", enabled[realm] == true)
        }
    }

    @Test
    fun sectPolicyState_copy() {
        val original = SectPolicyState()
        val copied = original.copy(autoSaveIntervalMonths = 6)
        assertEquals(6, copied.autoSaveIntervalMonths)
    }

    // ---- ProductionState ----

    @Test
    fun productionState_defaultConstruction() {
        val state = ProductionState()
        assertEquals(1, state.slotId)
        assertEquals(emptyList<SpiritFieldPlant>(), state.spiritFieldPlants)
        assertEquals(emptyList<String>(), state.unlockedRecipes)
        assertEquals(emptyList<String>(), state.unlockedManuals)
        assertEquals(emptyMap<String, List<ManualProficiencyData>>(), state.manualProficiencies)
    }

    @Test
    fun productionState_copy() {
        val original = ProductionState()
        val copied = original.copy(unlockedRecipes = listOf("recipe1"))
        assertEquals(listOf("recipe1"), copied.unlockedRecipes)
    }

    // ---- DiplomacyState ----

    @Test
    fun diplomacyState_defaultConstruction() {
        val state = DiplomacyState()
        assertEquals(1, state.slotId)
        assertEquals(emptyList<SectRelation>(), state.sectRelations)
        assertEquals(emptyList<Alliance>(), state.alliances)
        assertEquals(3, state.playerAllianceSlots)
        assertTrue(state.playerProtectionEnabled)
        assertEquals(1, state.playerProtectionStartYear)
        assertFalse(state.playerHasAttackedAI)
        assertEquals(emptyMap<String, SectDetail>(), state.sectDetails)
        assertEquals(emptyMap<String, ExploredSectInfo>(), state.exploredSects)
        assertEquals(emptyMap<String, SectScoutInfo>(), state.scoutInfo)
    }

    @Test
    fun diplomacyState_copy() {
        val original = DiplomacyState()
        val copied = original.copy(playerAllianceSlots = 5)
        assertEquals(5, copied.playerAllianceSlots)
    }

    @Test
    fun diplomacyState_playerProtectionDefaultIsTrue() {
        val state = DiplomacyState()
        assertTrue(state.playerProtectionEnabled)
    }

    @Test
    fun diplomacyState_playerHasAttackedAIDefaultIsFalse() {
        val state = DiplomacyState()
        assertFalse(state.playerHasAttackedAI)
    }

    // ---- WorldMapStateEntity ----

    @Test
    fun worldMapStateEntity_defaultConstruction() {
        val state = WorldMapStateEntity()
        assertEquals(1, state.slotId)
        assertEquals(emptyList<WorldSect>(), state.worldMapSects)
        assertEquals(emptyMap<String, List<Disciple>>(), state.aiSectDisciples)
        assertEquals(emptyList<CultivatorCave>(), state.cultivatorCaves)
        assertEquals(emptyList<CaveExplorationTeam>(), state.caveExplorationTeams)
        assertEquals(emptyList<AICaveTeam>(), state.aiCaveTeams)
        assertEquals(emptyList<WorldLevel>(), state.worldLevels)
    }

    @Test
    fun worldMapStateEntity_copy() {
        val original = WorldMapStateEntity()
        val copied = original.copy(slotId = 2)
        assertEquals(2, copied.slotId)
    }

    // ---- WorldMapState (non-entity) ----

    @Test
    fun worldMapState_defaultConstruction() {
        val state = WorldMapState()
        assertEquals(emptyList<WorldSect>(), state.worldMapSects)
        assertEquals(emptyMap<String, ExploredSectInfo>(), state.exploredSects)
        assertEquals(emptyMap<String, SectScoutInfo>(), state.scoutInfo)
        assertEquals(emptyList<SectRelation>(), state.sectRelations)
    }

    @Test
    fun worldMapState_copy() {
        val original = WorldMapState()
        val copied = original.copy(worldMapSects = listOf(WorldSect(id = "ws1")))
        assertEquals(1, copied.worldMapSects.size)
        assertEquals("ws1", copied.worldMapSects[0].id)
    }

    // ---- EconomicState ----

    @Test
    fun economicState_defaultConstruction() {
        val state = EconomicState()
        assertEquals(emptyList<MerchantItem>(), state.travelingMerchantItems)
        assertEquals(0, state.merchantLastRefreshYear)
        assertEquals(0, state.merchantRefreshCount)
        assertEquals(emptyList<MerchantItem>(), state.playerListedItems)
    }

    @Test
    fun economicState_copy() {
        val original = EconomicState()
        val copied = original.copy(merchantLastRefreshYear = 10)
        assertEquals(10, copied.merchantLastRefreshYear)
    }

    // ---- ExplorationState ----

    @Test
    fun explorationState_defaultConstruction() {
        val state = ExplorationState()
        assertEquals(emptyList<Disciple>(), state.recruitList)
        assertEquals(0, state.lastRecruitYear)
        assertEquals(emptyList<CultivatorCave>(), state.cultivatorCaves)
        assertEquals(emptyList<CaveExplorationTeam>(), state.caveExplorationTeams)
        assertEquals(emptyList<AICaveTeam>(), state.aiCaveTeams)
        assertEquals(emptyList<String>(), state.unlockedRecipes)
        assertEquals(emptyList<String>(), state.unlockedManuals)
        assertEquals(emptyMap<String, List<ManualProficiencyData>>(), state.manualProficiencies)
        assertEquals(emptyList<WorldLevel>(), state.worldLevels)
    }

    @Test
    fun explorationState_copy() {
        val original = ExplorationState()
        val copied = original.copy(lastRecruitYear = 5)
        assertEquals(5, copied.lastRecruitYear)
    }

    // ---- PatrolSlot ----

    @Test
    fun patrolSlot_defaultConstruction() {
        val slot = PatrolSlot()
        assertEquals(0, slot.index)
        assertEquals("", slot.discipleId)
        assertEquals("", slot.discipleName)
        assertEquals("", slot.discipleRealm)
        assertEquals("", slot.portraitRes)
    }

    @Test
    fun patrolSlot_isActive_whenEmpty() {
        val slot = PatrolSlot()
        assertFalse(slot.isActive)
    }

    @Test
    fun patrolSlot_isActive_whenFilled() {
        val slot = PatrolSlot(discipleId = "d1")
        assertTrue(slot.isActive)
    }

    // ---- PatrolConfig ----

    @Test
    fun patrolConfig_defaultConstruction() {
        val config = PatrolConfig()
        assertEquals(setOf(9), config.targetRealms)
        assertEquals(1, config.maxBeastCount)
        assertTrue(config.requireFullStatus)
    }

    @Test
    fun patrolConfig_customConstruction() {
        val config = PatrolConfig(
            targetRealms = setOf(7, 8, 9),
            maxBeastCount = 3,
            requireFullStatus = false
        )
        assertEquals(setOf(7, 8, 9), config.targetRealms)
        assertEquals(3, config.maxBeastCount)
        assertFalse(config.requireFullStatus)
    }

    // ---- PatrolStateEntity ----

    @Test
    fun patrolStateEntity_defaultConstruction() {
        val state = PatrolStateEntity()
        assertEquals(1, state.slotId)
        assertEquals(emptyList<PatrolSlot>(), state.patrolSlots)
        assertEquals(PatrolConfig(), state.patrolConfig)
        assertEquals(emptyList<PatrolConfig>(), state.patrolConfigs)
        assertFalse(state.patrolBattleResultPopup)
    }

    @Test
    fun patrolStateEntity_copy() {
        val original = PatrolStateEntity()
        val copied = original.copy(patrolBattleResultPopup = true)
        assertTrue(copied.patrolBattleResultPopup)
    }
}
