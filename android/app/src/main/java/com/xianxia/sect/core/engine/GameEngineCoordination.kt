package com.xianxia.sect.core.engine

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.engine.service.RedeemCodeService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.domain.building.BuildingService
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.exploration.MissionSystem
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.engine.domain.building.HerbGardenSystem
import com.xianxia.sect.core.engine.system.FocusDomain
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.util.addManualInstanceToDiscipleBag
import com.xianxia.sect.core.util.manualBagStackIds
import android.util.Log

// ── Focus / UI state ────────────────────────────────────────────────

fun GameEngine.setFocusedDiscipleId(id: String?) {
    stateStore.focusedDiscipleId = id
    if (id != null) { gameEngineCore.catchUpDomain(FocusDomain.DISCIPLES); gameEngineCore.onUserInteraction() }
}

fun GameEngine.setActiveTab(tab: String) {
    val oldTab = stateStore.activeTab
    stateStore.activeTab = tab
    if (oldTab != tab) gameEngineCore.catchUpDomain(domainForTab(tab))
    gameEngineCore.onUserInteraction()
}

fun GameEngine.setActiveDialog(route: com.xianxia.sect.ui.navigation.DialogRoute?) {
    if (route == null || route == com.xianxia.sect.ui.navigation.DialogRoute.None) {
        stateStore.activeDialog = null
    } else {
        stateStore.activeDialog = route.toString()
        gameEngineCore.catchUpDomain(domainForDialog(route))
    }
    gameEngineCore.onUserInteraction()
}

fun GameEngine.notifyUserInteraction() = gameEngineCore.onUserInteraction()

private fun domainForTab(tab: String): FocusDomain = when (tab) {
    "DISCIPLES" -> FocusDomain.DISCIPLES; "BUILDINGS" -> FocusDomain.BUILDINGS; "WAREHOUSE" -> FocusDomain.WAREHOUSE; else -> FocusDomain.ALWAYS
}

private fun domainForDialog(route: com.xianxia.sect.ui.navigation.DialogRoute): FocusDomain = when (route) {
    is com.xianxia.sect.ui.navigation.DialogRoute.Alchemy,
    is com.xianxia.sect.ui.navigation.DialogRoute.Forge,
    is com.xianxia.sect.ui.navigation.DialogRoute.HerbGarden,
    is com.xianxia.sect.ui.navigation.DialogRoute.SpiritMine,
    is com.xianxia.sect.ui.navigation.DialogRoute.Residence,
    is com.xianxia.sect.ui.navigation.DialogRoute.WarehouseBuilding -> FocusDomain.BUILDINGS
    is com.xianxia.sect.ui.navigation.DialogRoute.WorldMap -> FocusDomain.WORLD_MAP
    is com.xianxia.sect.ui.navigation.DialogRoute.Diplomacy -> FocusDomain.DIPLOMACY
    is com.xianxia.sect.ui.navigation.DialogRoute.MissionHall,
    is com.xianxia.sect.ui.navigation.DialogRoute.PatrolTower -> FocusDomain.EXPLORATION
    is com.xianxia.sect.ui.navigation.DialogRoute.Warehouse,
    is com.xianxia.sect.ui.navigation.DialogRoute.Merchant -> FocusDomain.WAREHOUSE
    is com.xianxia.sect.ui.navigation.DialogRoute.Disciples -> FocusDomain.DISCIPLES
    is com.xianxia.sect.ui.navigation.DialogRoute.Buildings -> FocusDomain.BUILDINGS
    is com.xianxia.sect.ui.navigation.DialogRoute.BattleLog -> FocusDomain.EXPLORATION
    is com.xianxia.sect.ui.navigation.DialogRoute.Mail -> FocusDomain.BACKGROUND
    else -> FocusDomain.ALWAYS
}

// ── Game lifecycle ──────────────────────────────────────────────────

suspend fun GameEngine.initializeNewGameSuspend(gameData: GameData) {
    stateStore.update { this.gameData = gameData }
}

suspend fun GameEngine.ensureHeavyDataLoaded() {
    if (heavyDataLoaded) return
    val slot = stateStore.gameDataSnapshot.currentSlot
    val keys = try { database.gameHeavyDataDao().getLoadedKeys(slot) } catch (e: Exception) {
        Log.w("GameEngine", "Failed to load heavy data keys, heavy data will be regenerated", e); heavyDataLoaded = true; return
    }
    if (keys.isEmpty()) { heavyDataLoaded = true; return }
    val heavyRows = mutableListOf<GameHeavyData>()
    for (key in keys) {
        try {
            val row = database.gameHeavyDataDao().getByKey(slot, key)
            if (row != null) heavyRows.add(row)
        } catch (e: Exception) {
            Log.w("GameEngine", "Heavy data key '$key' too large for CursorWindow, will be regenerated on next save", e)
            try { database.gameHeavyDataDao().deleteByKey(slot, key) } catch (_: Exception) {}
        }
    }
    if (heavyRows.isEmpty()) { heavyDataLoaded = true; return }
    val converters = com.xianxia.sect.data.local.ProtobufConverters
    stateStore.update {
        val current = this.gameData
        val needsUpdate = current.aiSectDisciples.isEmpty() || current.sectDetails.isEmpty() ||
            current.exploredSects.isEmpty() || current.scoutInfo.isEmpty() || current.manualProficiencies.isEmpty() ||
            current.recruitList.isEmpty() || current.worldMapSects.isEmpty()
        if (needsUpdate) {
            this.gameData = current.copy(
                aiSectDisciples = if (current.aiSectDisciples.isEmpty()) converters.decodeDiscipleListMapFromRows(heavyRows, GameHeavyData.KEY_AI_SECT_DISCIPLES) else current.aiSectDisciples,
                sectDetails = if (current.sectDetails.isEmpty()) converters.decodeSectDetailMapFromRows(heavyRows, GameHeavyData.KEY_SECT_DETAILS) else current.sectDetails,
                exploredSects = if (current.exploredSects.isEmpty()) converters.decodeExploredSectInfoMapFromRows(heavyRows, GameHeavyData.KEY_EXPLORED_SECTS) else current.exploredSects,
                scoutInfo = if (current.scoutInfo.isEmpty()) converters.decodeSectScoutInfoMapFromRows(heavyRows, GameHeavyData.KEY_SCOUT_INFO) else current.scoutInfo,
                manualProficiencies = if (current.manualProficiencies.isEmpty()) converters.decodeManualProficiencyMapFromRows(heavyRows, GameHeavyData.KEY_MANUAL_PROFICIENCIES) else current.manualProficiencies,
                recruitList = if (current.recruitList.isEmpty()) converters.decodeDiscipleListFromRows(heavyRows, GameHeavyData.KEY_RECRUIT_LIST) else current.recruitList,
                worldMapSects = if (current.worldMapSects.isEmpty()) converters.decodeWorldSectListFromRows(heavyRows, GameHeavyData.KEY_WORLD_MAP_SECTS) else current.worldMapSects
            )
        }
    }
    heavyDataLoaded = true
    Log.d("GameEngine", "ensureHeavyDataLoaded: loaded ${heavyRows.size} heavy data rows for slot $slot")
}

suspend fun GameEngine.loadData(
    gameData: GameData, disciples: List<Disciple>, equipmentStacks: List<EquipmentStack>,
    equipmentInstances: List<EquipmentInstance>, manualStacks: List<ManualStack>,
    manualInstances: List<ManualInstance>, pills: List<Pill>, materials: List<Material> = emptyList(),
    herbs: List<Herb> = emptyList(), seeds: List<Seed> = emptyList(), teams: List<ExplorationTeam>,
    battleLogs: List<BattleLog> = emptyList(), alliances: List<Alliance> = emptyList(),
    productionSlots: List<ProductionSlot> = emptyList(),
    storageBags: List<StorageBag> = emptyList()
) {
    heavyDataLoaded = false
    val (migratedGameData, migratedDisciples) = migratePatrolSlotsIfNeeded(gameData, disciples)
    stateStore.loadFromSnapshot(
        gameData = migratedGameData.copy(isGameStarted = true), disciples = migratedDisciples,
        equipmentStacks = equipmentStacks, equipmentInstances = equipmentInstances,
        manualStacks = manualStacks, manualInstances = manualInstances, pills = pills,
        materials = materials, herbs = herbs, seeds = seeds, storageBags = storageBags,
        teams = teams, battleLogs = battleLogs
    )
    Log.d("GameEngine", "loadData: restored game year=${gameData.gameYear}, ${disciples.size} disciples, recruitList=${gameData.recruitList.size} unrecruited disciples")
    val alchemyCount = gameData.placedBuildings.count { it.displayName == "炼丹炉" }
    val forgeCount = gameData.placedBuildings.count { it.displayName == "锻造坊" }
    val fixedProductionSlots = fixAlchemyForgeSlotCount(productionSlots, alchemyCount, forgeCount)
    if (fixedProductionSlots.isNotEmpty()) {
        productionCoordinator.repository.restoreSlots(fixedProductionSlots, gameData.currentSlot)
    } else {
        productionCoordinator.repository.initializeAllSlots(gameData.currentSlot)
    }
    checkAndCollectCompletedSlots()
    val currentData = stateStore.gameDataSnapshot
    if (currentData.travelingMerchantItems.isEmpty() || currentData.recruitList.isEmpty()) {
        Log.w("GameEngine", "loadData: detected empty merchant items or recruit list after load, refreshing...")
        stateStore.update {
            if (this.gameData.travelingMerchantItems.isEmpty()) cultivationService.refreshTravelingMerchant(this.gameData.gameYear, this.gameData.gameMonth)
            if (this.gameData.recruitList.isEmpty()) cultivationService.refreshRecruitList(this.gameData.gameYear)
        }
    }
    discipleService.syncAllDiscipleStatuses()
    val loadedData = stateStore.gameData.value
    if (loadedData.aiSectDisciples.isEmpty() && loadedData.worldMapSects.isNotEmpty()) {
        Log.i("GameEngine", "loadData: aiSectDisciples empty, regenerating for ${loadedData.worldMapSects.size} sects")
        val regenerated = mutableMapOf<String, List<Disciple>>()
        for (sect in loadedData.worldMapSects) {
            if (!sect.isPlayerSect) { val (d, _) = AISectDiscipleManager.initializeSectDisciples(sect.name, sect.level); regenerated[sect.id] = d }
        }
        stateStore.update { this.gameData = this.gameData.copy(aiSectDisciples = regenerated) }
    }
    try { mailService.resetAndInitSlot(gameData.slotId) } catch (e: Exception) { Log.e("GameEngine", "Failed to initialize mail for slot ${gameData.slotId}", e) }
}

suspend fun GameEngine.createNewGame(sectName: String, currentSlot: Int = 1) {
    stateStore.reset(); cultivationService.resetHighFrequencyData()
    try { mailService.resetAndInitSlot(currentSlot) } catch (e: Exception) { Log.e("GameEngine", "Failed to init mail for new game slot $currentSlot", e) }
    initializeWorldAndServices(sectName, currentSlot)
    stateStore.update {
        val initialMine = GridBuildingData(buildingId = "灵矿场", displayName = "灵矿场", gridX = 23, gridY = 23, width = 2, height = 2, instanceId = java.util.UUID.randomUUID().toString(), sectId = "")
        gameData = gameData.copy(isGameStarted = true, currentSlot = currentSlot, placedBuildings = listOf(initialMine), spiritMineSlots = (0..2).map { SpiritMineSlot(index = it, sectId = "") })
        repeat(3) { discipleService.recruitDisciple() }
    }
    addInitialManual()
}

suspend fun GameEngine.restartGameSuspend(sectName: String = "", currentSlot: Int = 1) = restartGameInternal(sectName, currentSlot)

private suspend fun GameEngine.restartGameInternal(sectName: String, currentSlot: Int) {
    stateStore.reset(); cultivationService.resetHighFrequencyData()
    try { mailService.resetAndInitSlot(currentSlot) } catch (e: Exception) { Log.e("GameEngine", "Failed to init mail for restarted game slot $currentSlot", e) }
    if (sectName.isNotBlank()) {
        initializeWorldAndServices(sectName, currentSlot)
        stateStore.update {
            val initialMine = GridBuildingData(buildingId = "灵矿场", displayName = "灵矿场", gridX = 23, gridY = 23, width = 2, height = 2, instanceId = java.util.UUID.randomUUID().toString(), sectId = "")
            gameData = gameData.copy(isGameStarted = true, currentSlot = currentSlot, placedBuildings = listOf(initialMine), spiritMineSlots = (0..2).map { SpiritMineSlot(index = it, sectId = "") })
            repeat(3) { discipleService.recruitDisciple() }
        }
        addInitialManual()
    } else {
        stateStore.update { gameData = GameData(isGameStarted = true).copy(currentSlot = currentSlot) }
    }
}

private suspend fun GameEngine.initializeWorldAndServices(sectName: String, currentSlot: Int = 1) {
    val generationResult = WorldMapGenerator.generateWorldSects(sectName)
    val sectRelations = WorldMapGenerator.initializeSectRelations(generationResult.sects)
    productionCoordinator.repository.initializeAllSlots(currentSlot)
    stateStore.update {
        cultivationService.refreshTravelingMerchant(1, 1); cultivationService.refreshRecruitList(1)
        gameData = gameData.copy(sectName = sectName, worldMapSects = generationResult.sects, sectRelations = sectRelations, aiSectDisciples = generationResult.aiSectDisciples, availableMissions = emptyList())
    }
}

private fun GameEngine.addInitialManual() {
    inventorySystem.addManualStack(ManualStack(id = java.util.UUID.randomUUID().toString(), name = "基础心法", rarity = 1, description = "一门基础的心法功法", type = ManualType.MIND, stats = mapOf("hp" to 10, "mp" to 10), minRealm = 9))
}

// ── Data update helpers ─────────────────────────────────────────────

suspend fun GameEngine.updateGameData(update: (GameData) -> GameData) { stateStore.update { gameData = update(gameData) } }

internal fun GameEngine.updateGameDataSync(update: (GameData) -> GameData) {
    gameEngineCore.launchInScope { stateStore.update { gameData = update(gameData) } }
}

suspend fun GameEngine.updateDisciple(discipleId: String, update: (Disciple) -> Disciple) {
    stateStore.update {
        val list = disciples.toMutableList()
        val index = list.indexOfFirst { it.id == discipleId }
        if (index >= 0) { list[index] = update(list[index]); disciples = list }
    }
}

suspend fun GameEngine.changeDiscipleTypeAtomic(discipleId: String, newType: String) {
    stateStore.update {
        val list = disciples.toMutableList()
        val index = list.indexOfFirst { it.id == discipleId }
        if (index >= 0) { list[index] = list[index].copy(discipleType = newType); disciples = list }
        discipleFacade.syncAllDiscipleStatuses()
    }
}

suspend fun GameEngine.updateGameDataAndSync(update: (GameData) -> GameData) {
    stateStore.update { gameData = update(gameData); discipleFacade.syncAllDiscipleStatuses() }
}

// ── Cross-domain: Sect / Map ────────────────────────────────────────

suspend fun GameEngine.enterSect(sectId: String) { stateStore.update { gameData = gameData.copy(activeSectId = sectId) } }
fun GameEngine.currentActiveSectId(): String = stateStore.gameDataSnapshot.activeSectId

// ── Cross-domain: Use pill ──────────────────────────────────────────

fun GameEngine.usePill(discipleId: String, pillId: String) {
    gameEngineCore.launchInScope {
        var errorMsg: String? = null; var successMsg: String? = null
        stateStore.update {
            val pill = pills.find { it.id == pillId } ?: return@update
            if (pill.quantity <= 0) return@update
            val disciple = disciples.find { it.id == discipleId } ?: return@update
            if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, pill.minRealm)) { errorMsg = "弟子${disciple.name}境界不足，无法使用${pill.name}（需要${GameConfig.Realm.getName(pill.minRealm)}及以上）"; return@update }
            if (pill.effects.cannotStack && disciple.pillEffects.activePillCategory == pill.category.name) { errorMsg = "弟子${disciple.name}已有同类型丹药生效中，无法使用${pill.name}"; return@update }
            if (pill.category == PillCategory.FUNCTIONAL && pill.pillType.isNotEmpty()) {
                if (disciple.usage.usedFunctionalPillTypes.contains(pill.pillType)) { errorMsg = "弟子${disciple.name}已使用过${pill.name}，同类功能丹药每人只能使用一次"; return@update }
            }
            pills = pills.map { if (it.id == pillId && it.quantity > 0) it.copy(quantity = it.quantity - 1) else it }.filter { it.quantity > 0 }
            var updatedDisciple = disciple
            val effect = pill.effects
            if (effect.cultivationAdd > 0) updatedDisciple = updatedDisciple.copy(cultivation = (updatedDisciple.cultivation + effect.cultivationAdd).coerceAtLeast(0.0))
            if (effect.skillExpAdd > 0) updatedDisciple = updatedDisciple.copy(manualMasteries = updatedDisciple.manualMasteries.mapValues { (_, v) -> (v + effect.skillExpAdd).coerceAtMost(10000) })
            if (effect.cultivationSpeedPercent > 0) updatedDisciple = updatedDisciple.copy(cultivationSpeedBonus = effect.cultivationSpeedPercent, cultivationSpeedDuration = if (effect.duration > 0) effect.duration * 30 else updatedDisciple.cultivationSpeedDuration)
            if (effect.extendLife > 0) {
                updatedDisciple = updatedDisciple.copy(lifespan = updatedDisciple.lifespan + effect.extendLife)
                if (!updatedDisciple.usage.usedExtendLifePillIds.contains(pill.pillType)) updatedDisciple = updatedDisciple.copy(usage = updatedDisciple.usage.copy(usedExtendLifePillIds = updatedDisciple.usage.usedExtendLifePillIds + pill.pillType))
            }
            if (effect.intelligenceAdd > 0 || effect.charmAdd > 0 || effect.loyaltyAdd > 0 || effect.comprehensionAdd > 0 || effect.artifactRefiningAdd > 0 || effect.pillRefiningAdd > 0 || effect.spiritPlantingAdd > 0 || effect.teachingAdd > 0 || effect.moralityAdd > 0 || effect.miningAdd > 0) {
                updatedDisciple = updatedDisciple.copy(skills = updatedDisciple.skills.copy(
                    intelligence = (updatedDisciple.skills.intelligence + effect.intelligenceAdd).coerceAtLeast(0),
                    charm = (updatedDisciple.skills.charm + effect.charmAdd).coerceAtLeast(0),
                    loyalty = (updatedDisciple.skills.loyalty + effect.loyaltyAdd).coerceAtLeast(0),
                    comprehension = (updatedDisciple.skills.comprehension + effect.comprehensionAdd).coerceAtLeast(0),
                    artifactRefining = (updatedDisciple.skills.artifactRefining + effect.artifactRefiningAdd).coerceAtLeast(0),
                    pillRefining = (updatedDisciple.skills.pillRefining + effect.pillRefiningAdd).coerceAtLeast(0),
                    spiritPlanting = (updatedDisciple.skills.spiritPlanting + effect.spiritPlantingAdd).coerceAtLeast(0),
                    teaching = (updatedDisciple.skills.teaching + effect.teachingAdd).coerceAtLeast(0),
                    morality = (updatedDisciple.skills.morality + effect.moralityAdd).coerceAtLeast(0),
                    mining = (updatedDisciple.skills.mining + effect.miningAdd).coerceAtLeast(0)
                ))
            }
            if (pill.category == PillCategory.FUNCTIONAL && pill.pillType.isNotEmpty()) updatedDisciple = updatedDisciple.copy(usage = updatedDisciple.usage.copy(usedFunctionalPillTypes = updatedDisciple.usage.usedFunctionalPillTypes + pill.pillType))
            if (effect.physicalAttackAdd > 0 || effect.magicAttackAdd > 0 || effect.physicalDefenseAdd > 0 || effect.magicDefenseAdd > 0 || effect.hpAdd > 0 || effect.mpAdd > 0 || effect.speedAdd > 0 || effect.critRateAdd > 0 || effect.critEffectAdd > 0 || effect.cultivationSpeedPercent > 0 || effect.skillExpSpeedPercent > 0 || effect.nurtureSpeedPercent > 0) {
                updatedDisciple = updatedDisciple.copy(pillEffects = updatedDisciple.pillEffects.copy(
                    pillPhysicalAttackBonus = effect.physicalAttackAdd, pillMagicAttackBonus = effect.magicAttackAdd,
                    pillPhysicalDefenseBonus = effect.physicalDefenseAdd, pillMagicDefenseBonus = effect.magicDefenseAdd,
                    pillHpBonus = effect.hpAdd, pillMpBonus = effect.mpAdd, pillSpeedBonus = effect.speedAdd,
                    pillCritRateBonus = effect.critRateAdd, pillCritEffectBonus = effect.critEffectAdd,
                    pillCultivationSpeedBonus = effect.cultivationSpeedPercent, pillSkillExpSpeedBonus = effect.skillExpSpeedPercent,
                    pillNurtureSpeedBonus = effect.nurtureSpeedPercent,
                    pillEffectDuration = if (effect.duration > 0) effect.duration * 30 else updatedDisciple.pillEffects.pillEffectDuration,
                    activePillCategory = if (effect.cannotStack) pill.category.name else updatedDisciple.pillEffects.activePillCategory
                ))
            }
            if (effect.healMaxHpPercent > 0) updatedDisciple = updatedDisciple.copy(combat = updatedDisciple.combat.copy(hpVariance = 0))
            if (effect.clearAll) updatedDisciple = updatedDisciple.copy(pillEffects = PillEffects())
            disciples = disciples.map { if (it.id == discipleId) updatedDisciple else it }
            successMsg = "弟子${disciple.name}使用了${pill.name}"
        }
    }
}

// ── Cross-domain: Manual operations ─────────────────────────────────

suspend fun GameEngine.equipItem(discipleId: String, equipmentId: String) { discipleService.equipEquipment(discipleId, equipmentId) }

suspend fun GameEngine.unequipItem(discipleId: String, slot: EquipmentSlot) {
    val disciple = getDiscipleById(discipleId) ?: return
    val equipId = when (slot) { EquipmentSlot.WEAPON -> disciple.equipment.weaponId; EquipmentSlot.ARMOR -> disciple.equipment.armorId; EquipmentSlot.BOOTS -> disciple.equipment.bootsId; EquipmentSlot.ACCESSORY -> disciple.equipment.accessoryId }
    if (equipId.isNotEmpty()) { discipleService.unequipEquipment(discipleId, equipId); cultivationService.markAutoEquipDirty(discipleId) }
}

suspend fun GameEngine.unequipItemById(discipleId: String, equipmentId: String) {
    discipleService.unequipEquipment(discipleId, equipmentId); cultivationService.markAutoEquipDirty(discipleId)
}

suspend fun GameEngine.forgetManual(discipleId: String, instanceId: String) {
    cultivationService.markAutoLearnDirty(discipleId)
    stateStore.update {
        val instance = manualInstances.find { it.id == instanceId } ?: return@update
        val currentDisciple = disciples.find { it.id == discipleId } ?: return@update
        val bagStackIds = currentDisciple.manualBagStackIds()
        val result = addManualInstanceToDiscipleBag(disciple = currentDisciple, instance = instance, bagStackIds = bagStackIds, gameYear = gameData.gameYear, gameMonth = gameData.gameMonth, gamePhase = gameData.gamePhase, maxStackSize = inventoryConfig.getMaxStackSize("manual_stack"))
        disciples = disciples.map { if (it.id == discipleId) result.updatedDisciple.copy(manualIds = it.manualIds.filter { mid -> mid != instanceId }) else it }
        val updatedProficiencies = gameData.manualProficiencies.toMutableMap()
        updatedProficiencies[discipleId]?.let { profList ->
            val filtered = profList.filter { it.manualId != instanceId }
            if (filtered.isEmpty()) updatedProficiencies.remove(discipleId) else updatedProficiencies[discipleId] = filtered
        }
        gameData = gameData.copy(manualProficiencies = updatedProficiencies)
    }
}

suspend fun GameEngine.replaceManual(discipleId: String, oldInstanceId: String, newStackId: String) {
    stateStore.update {
        val oldInstance = manualInstances.find { it.id == oldInstanceId } ?: return@update
        val newStack = manualStacks.find { it.id == newStackId } ?: return@update
        val disciple = disciples.find { it.id == discipleId } ?: return@update
        if (newStack.quantity < 1) return@update
        if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, newStack.minRealm)) return@update
        val blocked = newStack.type == ManualType.MIND && oldInstance.type != ManualType.MIND && disciple.manualIds.filter { it != oldInstanceId }.any { mid -> manualInstances.find { m -> m.id == mid }?.type == ManualType.MIND }
        if (blocked) return@update
        val hasSameName = disciple.manualIds.filter { it != oldInstanceId }.any { mid -> manualInstances.find { m -> m.id == mid }?.name == newStack.name }
        if (hasSameName) return@update
        val bagStackIds = disciple.manualBagStackIds()
        val result = addManualInstanceToDiscipleBag(disciple = disciple, instance = oldInstance, bagStackIds = bagStackIds, gameYear = gameData.gameYear, gameMonth = gameData.gameMonth, gamePhase = gameData.gamePhase, maxStackSize = inventoryConfig.getMaxStackSize("manual_stack"))
        val currentNewStack = manualStacks.find { it.id == newStackId } ?: return@update
        val newQty = currentNewStack.quantity - 1
        if (newQty <= 0) manualStacks = manualStacks.filter { it.id != newStackId } else manualStacks = manualStacks.map { if (it.id == newStackId) it.copy(quantity = newQty) else it }
        val newInstance = newStack.toInstance(id = java.util.UUID.randomUUID().toString(), ownerId = discipleId, isLearned = true)
        manualInstances = manualInstances + newInstance
        val updatedProficiencies = gameData.manualProficiencies.toMutableMap()
        updatedProficiencies[discipleId]?.let { profList ->
            val filtered = profList.filter { it.manualId != oldInstanceId }
            if (filtered.isEmpty()) updatedProficiencies.remove(discipleId) else updatedProficiencies[discipleId] = filtered
        }
        disciples = disciples.map { if (it.id == discipleId) result.updatedDisciple.copy(manualIds = (it.manualIds.filter { mid -> mid != oldInstanceId }) + newInstance.id) else it }
        gameData = gameData.copy(manualProficiencies = updatedProficiencies)
    }
}

suspend fun GameEngine.learnManual(discipleId: String, stackId: String) {
    stateStore.update {
        val stack = manualStacks.find { it.id == stackId } ?: return@update
        val disciple = disciples.find { it.id == discipleId } ?: return@update
        if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm)) return@update
        val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
        if (disciple.manualIds.size >= maxSlots) return@update
        if (stack.type == ManualType.MIND && disciple.manualIds.any { mid -> manualInstances.find { it.id == mid }?.type == ManualType.MIND }) return@update
        if (disciple.manualIds.any { mid -> manualInstances.find { it.id == mid }?.name == stack.name }) return@update
        val newQty = stack.quantity - 1
        if (newQty <= 0) manualStacks = manualStacks.filter { it.id != stackId } else manualStacks = manualStacks.map { if (it.id == stackId) it.copy(quantity = newQty) else it }
        val instanceId = java.util.UUID.randomUUID().toString()
        val instance = stack.toInstance(id = instanceId, ownerId = discipleId, isLearned = true)
        manualInstances = manualInstances + instance
        disciples = disciples.map {
            if (it.id == discipleId && !it.manualIds.contains(instanceId)) {
                val hpDelta = stack.stats["hp"] ?: stack.stats["maxHp"] ?: 0
                val mpDelta = stack.stats["mp"] ?: stack.stats["maxMp"] ?: 0
                val rawHp = it.combat.currentHp; val rawMp = it.combat.currentMp
                it.copy(manualIds = it.manualIds + instanceId, combat = it.combat.copy(currentHp = if (rawHp >= 0 && hpDelta > 0) rawHp + hpDelta else rawHp, currentMp = if (rawMp >= 0 && mpDelta > 0) rawMp + mpDelta else rawMp))
            } else it
        }
    }
}

// ── Cross-domain: Recruit ───────────────────────────────────────────

fun GameEngine.recruitAllFromList(): Boolean {
    val data = stateStore.gameData.value
    if (data.recruitList.isEmpty()) return false
    val currentMonthValue = data.gameYear * 12 + data.gameMonth
    val recruitedDisciples = data.recruitList.map { it.copyWith(recruitedMonth = currentMonthValue) }
    gameEngineCore.launchInScope { stateStore.update { disciples = disciples + recruitedDisciples; gameData = gameData.copy(recruitList = emptyList()) } }
    return true
}

fun GameEngine.removeFromRecruitList(discipleId: String) {
    val data = stateStore.gameData.value
    updateGameDataSync { it.copy(recruitList = data.recruitList.filter { it.id != discipleId }) }
}

// ── Cross-domain: Spirit mine / patrol / salary ─────────────────────

fun GameEngine.validateAndFixSpiritMineData() {
    val unified = stateStore.unifiedState.value
    val data = unified.gameData
    val discipleMap = unified.disciples.associateBy { it.id }
    val globalMines = data.placedBuildings.filter { it.displayName == "灵矿场" }
    val rebuiltSlots = mutableListOf<SpiritMineSlot>()
    var slotIdx = 0
    for (mine in globalMines) {
        for (offset in 0 until 3) {
            val existing = data.spiritMineSlots.getOrNull(slotIdx + offset)
            val slot = if (existing != null) {
                if (existing.discipleId.isNotEmpty() && (existing.discipleId !in discipleMap || discipleMap[existing.discipleId]?.discipleType != "outer")) existing.copy(discipleId = "", discipleName = "", index = rebuiltSlots.size) else existing.copy(index = rebuiltSlots.size)
            } else SpiritMineSlot(index = rebuiltSlots.size, sectId = mine.sectId)
            rebuiltSlots.add(slot)
        }
        slotIdx += 3
    }
    val finalSlots = rebuiltSlots.toList()
    if (finalSlots != data.spiritMineSlots) {
        val keptIds = finalSlots.mapNotNull { it.discipleId }.toSet()
        val orphanedIds = data.spiritMineSlots.filter { it.discipleId.isNotEmpty() && it.discipleId !in keptIds }.mapNotNull { it.discipleId }
        orphanedIds.forEach { discipleId ->
            gameEngineCore.launchInScope { stateStore.update { disciples = disciples.map { d -> if (d.id == discipleId && d.status == DiscipleStatus.MINING) d.copy(status = DiscipleStatus.IDLE) else d } } }
        }
        updateGameDataSync { it.copy(spiritMineSlots = finalSlots) }
    }
}

fun GameEngine.updateSpiritMineSlots(slots: List<SpiritMineSlot>) { updateGameDataSync { it.copy(spiritMineSlots = slots) } }
fun GameEngine.updatePatrolSlots(slots: List<PatrolSlot>) { updateGameDataSync { it.copy(patrolSlots = slots) } }
fun GameEngine.updatePatrolConfig(config: PatrolConfig) { updateGameDataSync { it.copy(patrolConfig = config) } }
fun GameEngine.updatePatrolConfigs(configs: List<PatrolConfig>) { updateGameDataSync { it.copy(patrolConfigs = configs) } }

fun GameEngine.addSpiritStones(amount: Long) {
    val ts = stateStore.currentTransactionMutableState()
    if (ts != null) ts.gameData = ts.gameData.copy(spiritStones = ts.gameData.spiritStones + amount) else updateGameDataSync { it.copy(spiritStones = it.spiritStones + amount) }
}

fun GameEngine.updateMonthlySalary(newSalary: Map<Int, Int>) { updateGameDataSync { it.copy(monthlySalary = newSalary) } }

// ── Cross-domain: Missions ──────────────────────────────────────────

fun GameEngine.startMission(mission: Mission, selectedDisciples: List<Disciple>) {
    val data = stateStore.gameDataSnapshot
    val activeMission = MissionSystem.createActiveMission(mission, selectedDisciples, data.gameYear, data.gameMonth)
    updateGameDataSync { it.copy(activeMissions = it.activeMissions + activeMission) }
    selectedDisciples.forEach { disciple -> gameEngineCore.launchInScope { updateDiscipleStatus(disciple.id, DiscipleStatus.ON_MISSION) } }
}

fun GameEngine.checkAndProcessCompletedMissions(): List<String> {
    val data = stateStore.gameDataSnapshot
    val completedIds = mutableListOf<String>()
    val remainingActive = mutableListOf<ActiveMission>()
    for (activeMission in data.activeMissions) {
        if (activeMission.isComplete(data.gameYear, data.gameMonth)) {
            completedIds.add(activeMission.id)
            val aliveDisciples = activeMission.discipleIds.mapNotNull { did -> stateStore.disciplesSnapshot.find { it.id == did && it.isAlive } }
            if (aliveDisciples.isNotEmpty()) {
                val equipMap = stateStore.equipmentInstancesSnapshot.associateBy { it.id }
                val manualMap = stateStore.manualInstancesSnapshot.associateBy { it.id }
                val proficiencies = data.manualProficiencies.mapValues { (_, list) -> list.associateBy { it.manualId } }
                val result = MissionSystem.processMissionCompletion(activeMission, aliveDisciples, equipMap, manualMap, proficiencies, battleSystem)
                applyMissionResult(result)
            }
            for (did in activeMission.discipleIds) {
                val disciple = stateStore.disciplesSnapshot.find { it.id == did }
                if (disciple != null && disciple.isAlive) gameEngineCore.launchInScope { updateDiscipleStatus(did, DiscipleStatus.IDLE) }
            }
        } else remainingActive.add(activeMission)
    }
    if (completedIds.isNotEmpty()) updateGameDataSync { it.copy(activeMissions = remainingActive) }
    return completedIds
}

fun GameEngine.processMonthlyMissionRefresh() {
    val data = stateStore.gameDataSnapshot
    val result = MissionSystem.processMonthlyRefresh(data.availableMissions, data.gameYear, data.gameMonth)
    updateGameDataSync { it.copy(availableMissions = result.cleanedMissions) }
}

private fun GameEngine.applyMissionResult(result: MissionSystem.MissionResult) {
    if (result.spiritStones > 0) addSpiritStones(result.spiritStones.toLong())
    result.materials.forEach { inventorySystem.addMaterial(it) }
    result.pills.forEach { inventorySystem.addPill(it) }
    result.equipmentStacks.forEach { inventorySystem.addEquipmentStack(it) }
    result.manualStacks.forEach { inventorySystem.addManualStack(it) }
}

// ── Service delegates ───────────────────────────────────────────────

fun GameEngine.completeExploration(teamId: String, success: Boolean, survivorIds: List<String>) = explorationService.completeExploration(teamId, success, survivorIds)
suspend fun GameEngine.redeemCode(code: String, usedCodes: List<String>, currentYear: Int, currentMonth: Int): RedeemResult = redeemCodeService.redeemCode(code, usedCodes, currentYear, currentMonth)
fun GameEngine.resetCultivationTimer() { cultivationService.resetHighFrequencyData() }

// ── Private: Production slot fix ────────────────────────────────────

private suspend fun GameEngine.checkAndCollectCompletedSlots() {
    autoHarvestCompletedAlchemySlots()
    val forgeSlots = productionCoordinator.repository.getSlotsByBuildingId("forge")
    forgeSlots.forEach { slot -> if (slot.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.COMPLETED) buildingService.autoHarvestForgeSlot(slot) }
    val herbSlots = productionCoordinator.repository.getSlotsByType(BuildingType.HERB_GARDEN)
    herbSlots.forEach { slot ->
        if (slot.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.COMPLETED) {
            harvestHerbFromCompletedSlot(slot); buildingService.clearPlantSlot(slot.slotIndex)
        }
    }
}

private fun GameEngine.harvestHerbFromCompletedSlot(slot: ProductionSlot) {
    val herb = com.xianxia.sect.core.registry.HerbDatabase.getHerbFromSeedName(slot.recipeName) ?: slot.recipeId?.let { com.xianxia.sect.core.registry.HerbDatabase.getHerbFromSeed(it) } ?: return
    val herbGrowthBonus = if (stateStore.gameData.value.sectPolicies.herbCultivation) GameConfig.PolicyConfig.HERB_CULTIVATION_BASE_EFFECT else 0.0
    val actualYield = HerbGardenSystem.calculateIncreasedYield(slot.expectedYield, herbGrowthBonus)
    inventorySystem.addHerb(Herb(name = herb.name, rarity = herb.rarity, description = herb.description, category = herb.category, quantity = actualYield))
}

private fun fixAlchemyForgeSlotCount(slots: List<ProductionSlot>, alchemyCount: Int, forgeCount: Int): List<ProductionSlot> {
    val result = slots.toMutableList()
    val alchemySlots = result.filter { it.buildingType == BuildingType.ALCHEMY }; result.removeAll { it.buildingType == BuildingType.ALCHEMY }
    val fixedAlchemy = mutableListOf<ProductionSlot>()
    alchemySlots.sortedBy { it.slotIndex }.take(alchemyCount).forEach { fixedAlchemy.add(it) }
    if (fixedAlchemy.size < alchemyCount) { val existingIndices = fixedAlchemy.map { it.slotIndex }.toSet(); var nextIdx = 0; while (fixedAlchemy.size < alchemyCount) { if (nextIdx !in existingIndices) fixedAlchemy.add(ProductionSlot.createIdle(slotIndex = nextIdx, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")); nextIdx++ } }
    result.addAll(fixedAlchemy)
    val forgeSlots = result.filter { it.buildingType == BuildingType.FORGE }; result.removeAll { it.buildingType == BuildingType.FORGE }
    val fixedForge = mutableListOf<ProductionSlot>()
    forgeSlots.sortedBy { it.slotIndex }.take(forgeCount).forEach { fixedForge.add(it) }
    if (fixedForge.size < forgeCount) { val existingIndices = fixedForge.map { it.slotIndex }.toSet(); var nextIdx = 0; while (fixedForge.size < forgeCount) { if (nextIdx !in existingIndices) fixedForge.add(ProductionSlot.createIdle(slotIndex = nextIdx, buildingType = BuildingType.FORGE, buildingId = "forge")); nextIdx++ } }
    result.addAll(fixedForge)
    return result
}

// ── Private: Migration ──────────────────────────────────────────────

private fun migratePatrolSlotsIfNeeded(gameData: GameData, disciples: List<Disciple>): Pair<GameData, List<Disciple>> {
    val numTowers = gameData.placedBuildings.count { it.displayName == "巡视楼" }
    if (numTowers == 0) return gameData to disciples
    val oldSlots = gameData.patrolSlots
    val expectedSize = numTowers * 8
    if (oldSlots.size <= expectedSize) return gameData to disciples
    Log.w("GameEngine", "迁移巡逻槽位: ${oldSlots.size}槽/${numTowers}塔 → ${expectedSize}槽")
    var updatedDisciples = disciples.toMutableList()
    val newSlots = mutableListOf<PatrolSlot>()
    var newGlobalIndex = 0
    for (towerIdx in 0 until numTowers) {
        val oldStart = towerIdx * 10
        for (localIdx in 0 until 8) {
            val globalIdx = oldStart + localIdx
            if (globalIdx < oldSlots.size) newSlots.add(oldSlots[globalIdx].copy(index = newGlobalIndex)) else newSlots.add(PatrolSlot(index = newGlobalIndex))
            newGlobalIndex++
        }
        for (discardLocalIdx in 8 until 10) {
            val discardGlobalIdx = oldStart + discardLocalIdx
            if (discardGlobalIdx < oldSlots.size) { val discardedSlot = oldSlots[discardGlobalIdx]; if (discardedSlot.discipleId.isNotEmpty()) updatedDisciples = updatedDisciples.map { if (it.id == discardedSlot.discipleId) it.copy(status = DiscipleStatus.IDLE) else it }.toMutableList() }
        }
    }
    val orphanedStart = numTowers * 10
    for (i in orphanedStart until oldSlots.size) { val slot = oldSlots[i]; if (slot.discipleId.isNotEmpty()) updatedDisciples = updatedDisciples.map { if (it.id == slot.discipleId) it.copy(status = DiscipleStatus.IDLE) else it }.toMutableList() }
    val newConfigs = gameData.patrolConfigs.take(numTowers)
    Log.i("GameEngine", "巡逻槽位迁移完成: ${oldSlots.size} → ${newSlots.size}")
    return gameData.copy(patrolSlots = newSlots, patrolConfigs = newConfigs) to updatedDisciples
}

// ── Memory ──────────────────────────────────────────────────────────

fun GameEngine.getMemoryUsageInfo(): String {
    val sb = StringBuilder()
    sb.appendLine("=== 内存使用情况 ===")
    sb.appendLine("弟子数量: ${stateStore.disciples.value.size}"); sb.appendLine("装备栈数量: ${stateStore.equipmentStacks.value.size}")
    sb.appendLine("装备实例数量: ${stateStore.equipmentInstances.value.size}"); sb.appendLine("功法栈数量: ${stateStore.manualStacks.value.size}")
    sb.appendLine("功法实例数量: ${stateStore.manualInstances.value.size}"); sb.appendLine("丹药数量: ${stateStore.pills.value.size}")
    sb.appendLine("材料数量: ${stateStore.materials.value.size}"); sb.appendLine("灵草数量: ${stateStore.herbs.value.size}")
    sb.appendLine("种子数量: ${stateStore.seeds.value.size}"); sb.appendLine("探索队伍: ${stateStore.teams.value.size}")
    sb.appendLine("战斗日志: ${stateStore.battleLogs.value.size}/50")
    return sb.toString()
}

@Suppress("DEPRECATION")
fun GameEngine.releaseMemory(level: Int) {
    val normalizedLevel = when (level) {
        android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE, android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, 1 -> 1
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE, 2 -> 2
        else -> { Log.w("GameEngine", "未知的内存压力级别: $level，忽略"); return }
    }
    val logsToKeep = if (normalizedLevel == 1) 20 else 10
    val levelName = if (normalizedLevel == 1) "MODERATE" else "CRITICAL"
    gameEngineCore.launchInScope {
        stateStore.update {
            if (battleLogs.size > logsToKeep) { battleLogs = battleLogs.take(logsToKeep); Log.d("GameEngine", "内存释放($levelName): 战斗日志已清理，保留最近$logsToKeep 条") }
            if (normalizedLevel == 2) {
                var trimmed = false
                val worldSects = gameData.worldMapSects
                if (worldSects.size > 30) {
                    val playerSect = worldSects.find { it.isPlayerSect }
                    val otherSects = worldSects.filter { !it.isPlayerSect }.sortedByDescending { s -> s.relation }.take(if (playerSect != null) 29 else 30)
                    val trimmedSects = if (playerSect != null) listOf(playerSect) + otherSects else otherSects
                    gameData = gameData.copy(worldMapSects = trimmedSects); trimmed = true; Log.d("GameEngine", "内存释放($levelName): worldMapSects 裁剪至 ${trimmedSects.size} 个")
                }
                val caveTeams = gameData.caveExplorationTeams
                if (caveTeams.size > 15) { gameData = gameData.copy(caveExplorationTeams = caveTeams.take(15)); trimmed = true; Log.d("GameEngine", "内存释放($levelName): caveExplorationTeams 裁剪至 15 个") }
                val aiCaveTeams = gameData.aiCaveTeams
                if (aiCaveTeams.size > 15) { gameData = gameData.copy(aiCaveTeams = aiCaveTeams.take(15)); trimmed = true; Log.d("GameEngine", "内存释放($levelName): aiCaveTeams 裁剪至 15 个") }
                if (!trimmed) Log.d("GameEngine", "内存释放($levelName): 无需裁剪其他列表")
            }
        }
    }
}
