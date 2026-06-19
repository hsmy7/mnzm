package com.xianxia.sect.core.model

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.*
import org.junit.Test

class GameDataTest {

    // ==================== 默认构造 ====================

    @Test
    fun gameData_defaultConstruction_keyDefaults() {
        val data = GameData()
        assertEquals("", data.id)
        assertEquals(0, data.slotId)
        assertEquals("青云宗", data.sectName)
        assertEquals(1, data.currentSlot)
        assertEquals(1, data.gameYear)
        assertEquals(1, data.gameMonth)
        assertEquals(0, data.gamePhase)
        assertFalse(data.isGameStarted)
        assertEquals(1, data.gameSpeed)
        assertEquals(1000L, data.spiritStones)
        assertEquals(0, data.spiritHerbs)
        assertEquals(0.0, data.sectCultivation, 0.001)
        assertEquals(3, data.autoSaveIntervalMonths)
        assertFalse(data.isGameOver)
    }

    @Test
    fun gameData_defaultConstruction_listFieldsEmpty() {
        val data = GameData()
        assertTrue(data.worldMapSects.isEmpty())
        assertTrue(data.sectDetails.isEmpty())
        assertTrue(data.aiSectDisciples.isEmpty())
        assertTrue(data.exploredSects.isEmpty())
        assertTrue(data.scoutInfo.isEmpty())
        assertTrue(data.manualProficiencies.isEmpty())
        assertTrue(data.travelingMerchantItems.isEmpty())
        assertTrue(data.playerListedItems.isEmpty())
        assertTrue(data.recruitList.isEmpty())
        assertTrue(data.worldLevels.isEmpty())
        assertTrue(data.cultivatorCaves.isEmpty())
        assertTrue(data.caveExplorationTeams.isEmpty())
        assertTrue(data.aiCaveTeams.isEmpty())
        assertTrue(data.unlockedRecipes.isEmpty())
        assertTrue(data.unlockedManuals.isEmpty())
        assertTrue(data.placedBuildings.isEmpty())
        assertTrue(data.spiritFieldPlants.isEmpty())
        assertTrue(data.residenceSlots.isEmpty())
        assertTrue(data.warehouseGarrisons.isEmpty())
        assertTrue(data.patrolSlots.isEmpty())
        assertTrue(data.alliances.isEmpty())
        assertTrue(data.sectRelations.isEmpty())
        assertTrue(data.activeMissions.isEmpty())
        assertTrue(data.availableMissions.isEmpty())
        assertTrue(data.usedRedeemCodes.isEmpty())
        assertTrue(data.mailRecords.isEmpty())
    }

    @Test
    fun gameData_defaultConstruction_booleanFields() {
        val data = GameData()
        assertFalse(data.isGameStarted)
        assertTrue(data.playerProtectionEnabled)
        assertFalse(data.playerHasAttackedAI)
        assertFalse(data.daoCompanionConsentRequired)
        assertFalse(data.patrolBattleResultPopup)
        assertFalse(data.breakthroughAutoPillFocused)
        assertFalse(data.autoEquipFromWarehouseFocused)
        assertFalse(data.autoLearnFromWarehouseFocused)
        assertFalse(data.isGameOver)
    }

    @Test
    fun gameData_defaultConstruction_intFields() {
        val data = GameData()
        assertEquals(0, data.spiritMineExpansions)
        assertEquals(0, data.merchantLastRefreshYear)
        assertEquals(0, data.merchantRefreshCount)
        assertEquals(0, data.lastRecruitYear)
        assertEquals(3, data.playerAllianceSlots)
        assertEquals(1, data.playerProtectionStartYear)
    }

    @Test
    fun gameData_defaultConstruction_yearlySalary() {
        val data = GameData()
        assertEquals(10, data.yearlySalary.size)
        assertEquals(240, data.yearlySalary[9])
        assertEquals(720, data.yearlySalary[8])
        assertEquals(1200, data.yearlySalary[7])
        assertEquals(1920, data.yearlySalary[6])
        assertEquals(2640, data.yearlySalary[5])
        assertEquals(4320, data.yearlySalary[4])
        assertEquals(5280, data.yearlySalary[3])
        assertEquals(6720, data.yearlySalary[2])
        assertEquals(8640, data.yearlySalary[1])
        assertEquals(12000, data.yearlySalary[0])
    }

    @Test
    fun gameData_defaultConstruction_yearlySalaryEnabled() {
        val data = GameData()
        assertEquals(10, data.yearlySalaryEnabled.size)
        data.yearlySalaryEnabled.values.forEach {
            assertTrue(it)
        }
    }

    // ==================== displayTime ====================

    @Test
    fun gameData_displayTime_default() {
        val data = GameData()
        assertEquals("第1年1月上旬", data.displayTime)
    }

    @Test
    fun gameData_displayTime_customPhase() {
        val data = GameData(gameYear = 5, gameMonth = 8, gamePhase = 1)
        assertEquals("第5年8月中旬", data.displayTime)
    }

    @Test
    fun gameData_displayTime_latePhase() {
        val data = GameData(gameYear = 10, gameMonth = 12, gamePhase = 2)
        assertEquals("第10年12月下旬", data.displayTime)
    }

    // ==================== isPlayerProtected ====================

    @Test
    fun gameData_isPlayerProtected_default() {
        val data = GameData()
        assertTrue(data.isPlayerProtected)
    }

    @Test
    fun gameData_isPlayerProtected_protectionDisabled() {
        val data = GameData(playerProtectionEnabled = false)
        assertFalse(data.isPlayerProtected)
    }

    @Test
    fun gameData_isPlayerProtected_playerAttackedAI() {
        val data = GameData(playerProtectionEnabled = true, playerHasAttackedAI = true)
        assertFalse(data.isPlayerProtected)
    }

    @Test
    fun gameData_isPlayerProtected_protectionExpired() {
        val data = GameData(
            playerProtectionEnabled = true,
            playerHasAttackedAI = false,
            playerProtectionStartYear = 1,
            gameYear = 101
        )
        assertFalse(data.isPlayerProtected)
    }

    @Test
    fun gameData_isPlayerProtected_protectionStillActive() {
        val data = GameData(
            playerProtectionEnabled = true,
            playerHasAttackedAI = false,
            playerProtectionStartYear = 1,
            gameYear = 50
        )
        assertTrue(data.isPlayerProtected)
    }

    // ==================== playerProtectionRemainingYears ====================

    @Test
    fun gameData_playerProtectionRemainingYears_default() {
        val data = GameData()
        assertEquals(GameConfig.PlayerProtection.PROTECTION_YEARS - 0, data.playerProtectionRemainingYears)
    }

    @Test
    fun gameData_playerProtectionRemainingYears_partial() {
        val data = GameData(playerProtectionStartYear = 1, gameYear = 30)
        assertEquals(GameConfig.PlayerProtection.PROTECTION_YEARS - 29, data.playerProtectionRemainingYears)
    }

    @Test
    fun gameData_playerProtectionRemainingYears_expired() {
        val data = GameData(playerProtectionStartYear = 1, gameYear = 200)
        assertEquals(0, data.playerProtectionRemainingYears)
    }

    @Test
    fun gameData_playerProtectionRemainingYears_disabled() {
        val data = GameData(playerProtectionEnabled = false)
        assertEquals(0, data.playerProtectionRemainingYears)
    }

    @Test
    fun gameData_playerProtectionRemainingYears_attackedAI() {
        val data = GameData(playerHasAttackedAI = true)
        assertEquals(0, data.playerProtectionRemainingYears)
    }

    // ==================== copy ====================

    @Test
    fun gameData_copy_changesField() {
        val data = GameData()
        val copied = data.copy(spiritStones = 5000L)
        assertEquals(1000L, data.spiritStones)
        assertEquals(5000L, copied.spiritStones)
    }

    @Test
    fun gameData_copy_preservesOtherFields() {
        val data = GameData(sectName = "青云宗", gameYear = 5)
        val copied = data.copy(gameYear = 10)
        assertEquals("青云宗", copied.sectName)
        assertEquals(10, copied.gameYear)
        assertEquals(5, data.gameYear)
    }

    // ==================== 聚合属性 ====================

    @Test
    fun gameData_buildings_aggregation() {
        val data = GameData()
        val buildings = data.buildings
        assertTrue(buildings.productionSlots.isEmpty())
        assertTrue(buildings.spiritMineSlots.isEmpty())
        assertTrue(buildings.librarySlots.isEmpty())
    }

    @Test
    fun gameData_economy_aggregation() {
        val data = GameData()
        val economy = data.economy
        assertTrue(economy.travelingMerchantItems.isEmpty())
        assertEquals(0, economy.merchantLastRefreshYear)
        assertEquals(0, economy.merchantRefreshCount)
        assertTrue(economy.playerListedItems.isEmpty())
    }

    @Test
    fun gameData_worldMap_aggregation() {
        val data = GameData()
        val worldMap = data.worldMap
        assertTrue(worldMap.worldMapSects.isEmpty())
        assertTrue(worldMap.exploredSects.isEmpty())
        assertTrue(worldMap.scoutInfo.isEmpty())
        assertTrue(worldMap.sectRelations.isEmpty())
    }

    // ==================== with* 方法 ====================

    @Test
    fun gameData_withBuildings() {
        val data = GameData()
        val newBuildings = BuildingState(
            spiritMineSlots = listOf(SpiritMineSlot(index = 0, discipleId = "d1"))
        )
        val updated = data.withBuildings(newBuildings)
        assertEquals(1, updated.spiritMineSlots.size)
        assertEquals("d1", updated.spiritMineSlots[0].discipleId)
        assertTrue(data.spiritMineSlots.isEmpty())
    }

    @Test
    fun gameData_withEconomy() {
        val data = GameData()
        val newEconomy = EconomicState(merchantRefreshCount = 5)
        val updated = data.withEconomy(newEconomy)
        assertEquals(5, updated.merchantRefreshCount)
        assertEquals(0, data.merchantRefreshCount)
    }

    @Test
    fun gameData_withWorldMap() {
        val data = GameData()
        val newWorldMap = WorldMapState(
            worldMapSects = listOf(WorldSect(id = "s1", name = "宗门1"))
        )
        val updated = data.withWorldMap(newWorldMap)
        assertEquals(1, updated.worldMapSects.size)
        assertEquals("宗门1", updated.worldMapSects[0].name)
        assertTrue(data.worldMapSects.isEmpty())
    }

    // ==================== Companion 常量 ====================

    @Test
    fun gameData_maxRedeemCodes() {
        assertEquals(500, GameData.MAX_REDEEM_CODES)
    }

    // ==================== SectPolicies ====================

    @Test
    fun sectPolicies_defaultConstruction() {
        val policies = SectPolicies()
        assertFalse(policies.spiritMineBoost)
        assertFalse(policies.enhancedSecurity)
        assertFalse(policies.alchemyIncentive)
        assertFalse(policies.forgeIncentive)
        assertFalse(policies.herbCultivation)
        assertFalse(policies.cultivationSubsidy)
        assertFalse(policies.manualResearch)
        assertFalse(policies.autoPlant)
        assertFalse(policies.autoAlchemy)
        assertFalse(policies.autoForge)
        assertFalse(policies.autoMineFocused)
        assertTrue(policies.autoMineRootCounts.isEmpty())
        assertEquals(1, policies.autoMineThreshold)
    }

    @Test
    fun sectPolicies_copy() {
        val policies = SectPolicies()
        val copied = policies.copy(spiritMineBoost = true)
        assertFalse(policies.spiritMineBoost)
        assertTrue(copied.spiritMineBoost)
    }

    // ==================== ElderSlots ====================

    @Test
    fun elderSlots_defaultConstruction() {
        val slots = ElderSlots()
        assertEquals("", slots.viceSectMaster)
        assertEquals("", slots.herbGardenElder)
        assertEquals("", slots.alchemyElder)
        assertEquals("", slots.forgeElder)
        assertEquals("", slots.outerElder)
        assertEquals("", slots.preachingElder)
        assertTrue(slots.preachingMasters.isEmpty())
        assertEquals("", slots.lawEnforcementElder)
        assertTrue(slots.lawEnforcementDisciples.isEmpty())
        assertEquals("", slots.innerElder)
    }

    @Test
    fun elderSlots_isDiscipleInAnyPosition_empty() {
        val slots = ElderSlots()
        assertFalse(slots.isDiscipleInAnyPosition("d1"))
    }

    @Test
    fun elderSlots_isDiscipleInAnyPosition_viceSectMaster() {
        val slots = ElderSlots(viceSectMaster = "d1")
        assertTrue(slots.isDiscipleInAnyPosition("d1"))
        assertFalse(slots.isDiscipleInAnyPosition("d2"))
    }

    @Test
    fun elderSlots_isDiscipleInAnyPosition_elder() {
        val slots = ElderSlots(alchemyElder = "d1")
        assertTrue(slots.isDiscipleInAnyPosition("d1"))
    }

    @Test
    fun elderSlots_isDiscipleInAnyPosition_directDisciple() {
        val slots = ElderSlots(
            alchemyDisciples = listOf(DirectDiscipleSlot(discipleId = "d1", discipleName = "弟子1"))
        )
        assertTrue(slots.isDiscipleInAnyPosition("d1"))
    }

    // ==================== DirectDiscipleSlot ====================

    @Test
    fun directDiscipleSlot_isActive_emptyId() {
        val slot = DirectDiscipleSlot(discipleId = "")
        assertFalse(slot.isActive)
    }

    @Test
    fun directDiscipleSlot_isActive_nonEmptyId() {
        val slot = DirectDiscipleSlot(discipleId = "d1")
        assertTrue(slot.isActive)
    }

    // ==================== GarrisonSlot ====================

    @Test
    fun garrisonSlot_isActive_emptyId() {
        assertFalse(GarrisonSlot(discipleId = "").isActive)
    }

    @Test
    fun garrisonSlot_isActive_nonEmptyId() {
        assertTrue(GarrisonSlot(discipleId = "d1").isActive)
    }

    // ==================== BattleTeam ====================

    @Test
    fun battleTeam_defaultConstruction() {
        val team = BattleTeam()
        assertNotNull(team.id)
        assertEquals("战斗队伍", team.name)
        assertEquals(0, team.teamNumber)
        assertEquals(10, team.slots.size)
        assertTrue(team.isAtSect)
        assertEquals("idle", team.status)
    }

    @Test
    fun battleTeam_slotsComposition() {
        val team = BattleTeam()
        val elderSlots = team.slots.filter { it.slotType == BattleSlotType.ELDER }
        val discipleSlots = team.slots.filter { it.slotType == BattleSlotType.DISCIPLE }
        assertEquals(2, elderSlots.size)
        assertEquals(8, discipleSlots.size)
    }

    // ==================== BattleTeamSlot ====================

    @Test
    fun battleTeamSlot_defaultConstruction() {
        val slot = BattleTeamSlot()
        assertEquals(0, slot.index)
        assertEquals("", slot.discipleId)
        assertEquals(BattleSlotType.DISCIPLE, slot.slotType)
        assertTrue(slot.isAlive)
    }

    // ==================== BloodRefinementProgress ====================

    @Test
    fun bloodRefinementProgress_defaultConstruction() {
        val progress = BloodRefinementProgress()
        assertEquals("", progress.discipleId)
        assertEquals("", progress.materialId)
        assertEquals(0, progress.startYear)
        assertEquals(0, progress.durationMonths)
        assertEquals(0.0, progress.bonusPercent, 0.001)
    }

    // ==================== SpiritFieldPlant ====================

    @Test
    fun spiritFieldPlant_defaultConstruction() {
        val plant = SpiritFieldPlant(buildingInstanceId = "bi1")
        assertEquals("bi1", plant.buildingInstanceId)
        assertEquals("", plant.seedId)
        assertEquals(0, plant.growTime)
        assertEquals(0, plant.expectedYield)
    }

    // ==================== MerchantItem ====================

    @Test
    fun merchantItem_defaultConstruction() {
        val item = MerchantItem()
        assertEquals("", item.id)
        assertEquals("", item.name)
        assertEquals("", item.type)
        assertEquals(1, item.rarity)
        assertEquals(0L, item.price)
        assertEquals(1, item.quantity)
    }

    // ==================== GameSettingsData ====================

    @Test
    fun gameSettingsData_defaultConstruction() {
        val settings = GameSettingsData()
        assertTrue(settings.soundEnabled)
        assertTrue(settings.musicEnabled)
        assertTrue(settings.vibrationEnabled)
        assertTrue(settings.autoSave)
        assertEquals("zh", settings.language)
    }

    // ==================== ManualProficiencyData ====================

    @Test
    fun manualProficiencyData_defaultConstruction() {
        val data = ManualProficiencyData()
        assertEquals("", data.manualId)
        assertEquals(0.0, data.proficiency, 0.001)
        assertEquals(100, data.maxProficiency)
        assertEquals(1, data.level)
        assertEquals(0, data.masteryLevel)
    }

    // ==================== MineSlot ====================

    @Test
    fun mineSlot_defaultConstruction() {
        val slot = MineSlot()
        assertEquals(0, slot.index)
        assertEquals("", slot.discipleId)
        assertEquals(0, slot.output)
        assertEquals(1.0, slot.efficiency, 0.001)
        assertFalse(slot.isActive)
    }

    @Test
    fun mineSlot_isActive() {
        assertTrue(MineSlot(discipleId = "d1", isActive = true).isActive)
        assertFalse(MineSlot(discipleId = "d1", isActive = false).isActive)
        assertFalse(MineSlot(discipleId = "").isActive)
    }

    // ==================== ResidenceSlot ====================

    @Test
    fun residenceSlot_isActive() {
        assertTrue(ResidenceSlot(discipleId = "d1").isActive)
        assertFalse(ResidenceSlot(discipleId = "").isActive)
    }

    // ==================== WarehouseGarrisonSlot ====================

    @Test
    fun warehouseGarrisonSlot_isActive() {
        assertTrue(WarehouseGarrisonSlot(discipleId = "d1").isActive)
        assertFalse(WarehouseGarrisonSlot(discipleId = "").isActive)
    }

    // ==================== Alliance ====================

    @Test
    fun alliance_defaultConstruction() {
        val alliance = Alliance()
        assertNotNull(alliance.id)
        assertTrue(alliance.sectIds.isEmpty())
        assertEquals(0, alliance.startYear)
        assertEquals("", alliance.initiatorId)
        assertEquals("", alliance.envoyDiscipleId)
    }

    // ==================== SectRelation ====================

    @Test
    fun sectRelation_defaultConstruction() {
        val relation = SectRelation(sectId1 = "s1", sectId2 = "s2")
        assertEquals("s1", relation.sectId1)
        assertEquals("s2", relation.sectId2)
        assertEquals(GameConfig.WorldMap.INITIAL_SECT_FAVOR, relation.favor)
        assertEquals(0, relation.lastInteractionYear)
        assertEquals(0, relation.noGiftYears)
    }

    // ==================== PlantSlotData ====================

    @Test
    fun plantSlotData_defaultConstruction() {
        val slot = PlantSlotData()
        assertEquals(0, slot.index)
        assertEquals("idle", slot.status)
        assertEquals("", slot.seedId)
        assertEquals(0, slot.growTime)
    }

    @Test
    fun plantSlotData_isGrowing() {
        assertTrue(PlantSlotData(status = "growing").isGrowing)
        assertFalse(PlantSlotData(status = "idle").isGrowing)
    }

    @Test
    fun plantSlotData_isIdle() {
        assertTrue(PlantSlotData(status = "idle").isIdle)
        assertFalse(PlantSlotData(status = "growing").isIdle)
    }

    @Test
    fun plantSlotData_maxAiDisciplesPerSect() {
        assertEquals(1000, PlantSlotData.MAX_AI_DISCIPLES_PER_SECT)
    }

    // ==================== SectWarehouse ====================

    @Test
    fun sectWarehouse_defaultConstruction() {
        val warehouse = SectWarehouse()
        assertTrue(warehouse.items.isEmpty())
        assertEquals(0L, warehouse.spiritStones)
    }

    // ==================== WarehouseItem ====================

    @Test
    fun warehouseItem_defaultConstruction() {
        val item = WarehouseItem()
        assertEquals("", item.itemId)
        assertEquals(1, item.rarity)
        assertEquals(1, item.quantity)
    }

    // ==================== ExploredSectInfo ====================

    @Test
    fun exploredSectInfo_defaultConstruction() {
        val info = ExploredSectInfo()
        assertEquals("", info.sectId)
        assertEquals(0, info.year)
        assertEquals(0, info.battleCount)
        assertEquals(0, info.casualties)
        assertEquals(9, info.maxRealm)
    }

    // ==================== SectScoutInfo ====================

    @Test
    fun sectScoutInfo_defaultConstruction() {
        val info = SectScoutInfo()
        assertEquals("", info.sectId)
        assertEquals(9, info.maxRealm)
        assertFalse(info.isKnown)
    }

    // ==================== WorldSect ====================

    @Test
    fun worldSect_defaultConstruction() {
        val sect = WorldSect()
        assertEquals("", sect.id)
        assertEquals("", sect.name)
        assertEquals(0, sect.level)
        assertFalse(sect.isPlayerSect)
        assertFalse(sect.discovered)
        assertEquals(10, sect.garrisonSlots.size)
    }

    // ==================== SectDetail ====================

    @Test
    fun sectDetail_defaultConstruction() {
        val detail = SectDetail()
        assertEquals("", detail.sectId)
        assertTrue(detail.mineSlots.isEmpty())
        assertFalse(detail.isOwned)
        assertEquals(GiftPreferenceType.NONE, detail.giftPreference)
    }
}
