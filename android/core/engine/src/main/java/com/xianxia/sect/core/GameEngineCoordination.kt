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
import com.xianxia.sect.core.util.DomainLog

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

fun GameEngine.setActiveDialog(dialogName: String?) {
    if (dialogName == null) {
        stateStore.activeDialog = null
    } else {
        stateStore.activeDialog = dialogName
        gameEngineCore.catchUpDomain(domainForDialog(dialogName))
    }
    gameEngineCore.onUserInteraction()
}

fun GameEngine.notifyUserInteraction() = gameEngineCore.onUserInteraction()

private fun domainForTab(tab: String): FocusDomain = when (tab) {
    "DISCIPLES" -> FocusDomain.DISCIPLES; "BUILDINGS" -> FocusDomain.BUILDINGS; "WAREHOUSE" -> FocusDomain.WAREHOUSE; else -> FocusDomain.ALWAYS
}

private fun domainForDialog(dialogName: String): FocusDomain = when (dialogName) {
    "Alchemy", "Forge", "HerbGarden", "SpiritMine", "Residence", "WarehouseBuilding" -> FocusDomain.BUILDINGS
    "WorldMap" -> FocusDomain.WORLD_MAP
    "Diplomacy" -> FocusDomain.DIPLOMACY
    "MissionHall", "PatrolTower" -> FocusDomain.EXPLORATION
    "Warehouse", "Merchant" -> FocusDomain.WAREHOUSE
    "Disciples" -> FocusDomain.DISCIPLES
    "Buildings" -> FocusDomain.BUILDINGS
    "BattleLog" -> FocusDomain.EXPLORATION
    "Mail" -> FocusDomain.BACKGROUND
    else -> FocusDomain.ALWAYS
}

// ── Game lifecycle ──────────────────────────────────────────────────

suspend fun GameEngine.initializeNewGameSuspend(gameData: GameData) {
    stateStore.update { this.gameData = gameData }
}

suspend fun GameEngine.ensureHeavyDataLoaded() {
    if (heavyDataLoaded) return
    val slot = stateStore.gameDataSnapshot.currentSlot

    // 第一层：尝试从 game_heavy_data 表恢复
    val restoredFromHeavy = tryRestoreFromHeavyData(slot)

    // 第二层：回退到 world_map_state 表
    if (!restoredFromHeavy) {
        DomainLog.w("GameEngine", "worldMapSects 重型数据恢复失败，尝试 world_map_state 表回退 slot=$slot")
        val restoredFromState = tryRestoreFromWorldMapState(slot)
        if (!restoredFromState) {
            // 第三层：从 FixedSectPositions 配置表重生
            DomainLog.e("GameEngine", "worldMapSects 所有回退源失败，从 FixedSectPositions 重生 slot=$slot")
            regenerateWorldFromFixedPositions(slot)
        }
    }

    // 校验恢复后的数据一致性
    val currentSects = stateStore.gameDataSnapshot.worldMapSects
    if (currentSects.isEmpty()) {
        DomainLog.e("GameEngine", "ensureHeavyDataLoaded: worldMapSects 仍为空，世界地图将无宗门显示 slot=$slot")
    } else if (currentSects.size < 29) {
        DomainLog.w("GameEngine", "ensureHeavyDataLoaded: worldMapSects 仅 ${currentSects.size} 个（预期 29），可能已被裁剪 slot=$slot")
    }

    heavyDataLoaded = true
    DomainLog.d("GameEngine", "ensureHeavyDataLoaded: 完成 slot=$slot worldMapSects=${currentSects.size}")
}

/**
 * 第一层恢复：从 game_heavy_data 表读取 Protobuf 编码的重型数据。
 * @return true 如果 worldMapSects 成功恢复
 */
private suspend fun GameEngine.tryRestoreFromHeavyData(slot: Int): Boolean {
    val keys = try {
        heavyDataPort.getLoadedKeys(slot)
    } catch (e: Exception) {
        DomainLog.w("GameEngine", "加载重型数据键失败 slot=$slot", e)
        return false
    }
    if (keys.isEmpty()) {
        DomainLog.w("GameEngine", "重型数据键为空 slot=$slot，数据库可能未包含重型数据")
        return false
    }

    val heavyRows = mutableListOf<GameHeavyData>()
    for (key in keys) {
        try {
            val row = heavyDataPort.getByKey(slot, key)
            if (row != null) heavyRows.add(row)
        } catch (e: Exception) {
            DomainLog.w("GameEngine", "重型数据键 '$key' 过大无法加载，将在下次存档时重生", e)
            try { heavyDataPort.deleteByKey(slot, key) } catch (_: Exception) {}
        }
    }
    if (heavyRows.isEmpty()) {
        DomainLog.w("GameEngine", "所有重型数据行加载失败 slot=$slot")
        return false
    }

    stateStore.update {
        val current = this.gameData
        val needsUpdate = current.aiSectDisciples.isEmpty() || current.sectDetails.isEmpty() ||
            current.exploredSects.isEmpty() || current.scoutInfo.isEmpty() || current.manualProficiencies.isEmpty() ||
            current.recruitList.isEmpty() || current.worldMapSects.isEmpty()
        if (needsUpdate) {
            this.gameData = current.copy(
                aiSectDisciples = if (current.aiSectDisciples.isEmpty()) heavyDataDecoder.decodeDiscipleListMapFromRows(heavyRows, GameHeavyData.KEY_AI_SECT_DISCIPLES) else current.aiSectDisciples,
                sectDetails = if (current.sectDetails.isEmpty()) heavyDataDecoder.decodeSectDetailMapFromRows(heavyRows, GameHeavyData.KEY_SECT_DETAILS) else current.sectDetails,
                exploredSects = if (current.exploredSects.isEmpty()) heavyDataDecoder.decodeExploredSectInfoMapFromRows(heavyRows, GameHeavyData.KEY_EXPLORED_SECTS) else current.exploredSects,
                scoutInfo = if (current.scoutInfo.isEmpty()) heavyDataDecoder.decodeSectScoutInfoMapFromRows(heavyRows, GameHeavyData.KEY_SCOUT_INFO) else current.scoutInfo,
                manualProficiencies = if (current.manualProficiencies.isEmpty()) heavyDataDecoder.decodeManualProficiencyMapFromRows(heavyRows, GameHeavyData.KEY_MANUAL_PROFICIENCIES) else current.manualProficiencies,
                recruitList = if (current.recruitList.isEmpty()) heavyDataDecoder.decodeDiscipleListFromRows(heavyRows, GameHeavyData.KEY_RECRUIT_LIST) else current.recruitList,
                worldMapSects = if (current.worldMapSects.isEmpty()) heavyDataDecoder.decodeWorldSectListFromRows(heavyRows, GameHeavyData.KEY_WORLD_MAP_SECTS) else current.worldMapSects
            )
        }
    }

    val restored = stateStore.gameDataSnapshot.worldMapSects.isNotEmpty()
    if (restored) {
        DomainLog.d("GameEngine", "ensureHeavyDataLoaded: 从 game_heavy_data 加载 ${heavyRows.size} 行重型数据 slot=$slot")
    }
    return restored
}

/**
 * 第二层恢复：从 world_map_state 表读取（Room 直接存储，非 Protobuf 分块）。
 * 该表在每次存档时与 game_heavy_data 同步写入，作为独立冗余副本。
 * @return true 如果 worldMapSects 成功恢复
 */
private suspend fun GameEngine.tryRestoreFromWorldMapState(slot: Int): Boolean {
    return try {
        val entity = worldMapStatePort.getBySlot(slot) ?: return false
        if (entity.worldMapSects.isEmpty()) return false

        stateStore.update {
            val current = this.gameData
            this.gameData = current.copy(
                worldMapSects = entity.worldMapSects,
                aiSectDisciples = if (current.aiSectDisciples.isEmpty()) entity.aiSectDisciples else current.aiSectDisciples,
                worldLevels = if (current.worldLevels.isNullOrEmpty()) entity.worldLevels else current.worldLevels,
                cultivatorCaves = if (current.cultivatorCaves.isNullOrEmpty()) entity.cultivatorCaves else current.cultivatorCaves,
                caveExplorationTeams = if (current.caveExplorationTeams.isEmpty()) entity.caveExplorationTeams else current.caveExplorationTeams,
                aiCaveTeams = if (current.aiCaveTeams.isEmpty()) entity.aiCaveTeams else current.aiCaveTeams
            )
        }
        DomainLog.w("GameEngine", "worldMapSects 已从 world_map_state 表恢复 slot=$slot sects=${entity.worldMapSects.size}")
        true
    } catch (e: Exception) {
        DomainLog.w("GameEngine", "从 world_map_state 表恢复失败 slot=$slot", e)
        false
    }
}

/**
 * 第三层恢复（最后手段）：从 FixedSectPositions 配置表重生宗门数据。
 * 重生后的宗门状态为默认（关系中立、无详情），但保证世界地图和外交功能可用。
 */
private suspend fun GameEngine.regenerateWorldFromFixedPositions(slot: Int) {
    val currentSectName = stateStore.gameDataSnapshot.sectName
    if (currentSectName.isBlank()) {
        DomainLog.e("GameEngine", "无法重生宗门：当前宗门名为空 slot=$slot")
        return
    }

    val generationResult = WorldMapGenerator.generateWorldSects(currentSectName)
    val sectRelations = WorldMapGenerator.initializeSectRelations(generationResult.sects)

    stateStore.update {
        val current = this.gameData
        this.gameData = current.copy(
            worldMapSects = generationResult.sects,
            sectRelations = sectRelations,
            aiSectDisciples = if (current.aiSectDisciples.isEmpty()) generationResult.aiSectDisciples else current.aiSectDisciples
        )
    }
    DomainLog.e("GameEngine", "宗门数据已从 FixedSectPositions 重生 slot=$slot " +
        "sectName=$currentSectName sects=${generationResult.sects.size} — 存档数据已丢失，宗门关系和详情已重置为默认")
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
    DomainLog.d("GameEngine", "loadData: restored game year=${gameData.gameYear}, ${disciples.size} disciples, recruitList=${gameData.recruitList.size} unrecruited disciples")
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
        DomainLog.w("GameEngine", "loadData: detected empty merchant items or recruit list after load, refreshing...")
        if (currentData.travelingMerchantItems.isEmpty()) {
            cultivationService.refreshTravelingMerchant(currentData.gameYear, currentData.gameMonth)
        }
        if (currentData.recruitList.isEmpty()) {
            cultivationService.refreshRecruitList(currentData.gameYear)
        }
    }
    discipleService.syncAllDiscipleStatuses()
    val loadedData = stateStore.gameData.value
    if (loadedData.aiSectDisciples.isEmpty() && loadedData.worldMapSects.isNotEmpty()) {
        DomainLog.i("GameEngine", "loadData: aiSectDisciples empty, regenerating for ${loadedData.worldMapSects.size} sects")
        val regenerated = mutableMapOf<String, List<Disciple>>()
        for (sect in loadedData.worldMapSects) {
            if (!sect.isPlayerSect) { val (d, _) = AISectDiscipleManager.initializeSectDisciples(sect.name, sect.level); regenerated[sect.id] = d }
        }
        stateStore.update { this.gameData = this.gameData.copy(aiSectDisciples = regenerated) }
    }
    try { mailService.resetAndInitSlot(gameData.slotId) } catch (e: Exception) { DomainLog.e("GameEngine", "Failed to initialize mail for slot ${gameData.slotId}", e) }
}

suspend fun GameEngine.createNewGame(sectName: String, currentSlot: Int = 1) {
    stateStore.reset(); cultivationService.resetHighFrequencyData()
    // 1. 先初始化世界和游戏状态（邮件依赖 gameData 就绪）
    initializeWorldAndServices(sectName, currentSlot)
    val gridCells = GameConfig.SectMap.WORLD_WIDTH_CELLS
    val centerGrid = gridCells / 2 - 1  // 2x2 building centered on grid
    stateStore.update {
        val initialMine = GridBuildingData(buildingId = "灵矿场", displayName = "灵矿场", gridX = centerGrid, gridY = centerGrid, width = 2, height = 2, instanceId = java.util.UUID.randomUUID().toString(), sectId = "")
        gameData = gameData.copy(
            slotId = currentSlot,
            currentSlot = currentSlot,
            placedBuildings = listOf(initialMine),
            spiritMineSlots = (0..2).map { SpiritMineSlot(index = it, sectId = "") },
            // 显式清零所有建筑/槽位相关字段，防止旧存档数据残留
            productionSlots = emptyList(),
            residenceSlots = emptyList(),
            warehouseGarrisons = emptyList(),
            patrolSlots = emptyList(),
            patrolConfig = PatrolConfig(),
            patrolConfigs = emptyList(),
            librarySlots = emptyList(),
            spiritFieldPlants = emptyList()
        )
        repeat(3) { discipleService.recruitDisciple() }
    }
    addInitialManual()
    // 2. 世界初始化完成后才加载邮件（此时 mailRecords/slotId 等状态已就绪）
    // Note: isGameStarted is set to true later in SaveLoadViewModel.startNewGame()
    // after startGameLoop() succeeds, ensuring UI doesn't appear without a running game loop
    try { mailService.resetAndInitSlot(currentSlot) } catch (e: Exception) { DomainLog.e("GameEngine", "Failed to init mail for new game slot $currentSlot", e) }
}

suspend fun GameEngine.restartGameSuspend(sectName: String = "", currentSlot: Int = 1) = restartGameInternal(sectName, currentSlot)

private suspend fun GameEngine.restartGameInternal(sectName: String, currentSlot: Int) {
    stateStore.reset(); cultivationService.resetHighFrequencyData()
    if (sectName.isNotBlank()) {
        // 1. 先初始化世界和游戏状态
        initializeWorldAndServices(sectName, currentSlot)
        val gridCells = GameConfig.SectMap.WORLD_WIDTH_CELLS
        val centerGrid = gridCells / 2 - 1
        stateStore.update {
            val initialMine = GridBuildingData(buildingId = "灵矿场", displayName = "灵矿场", gridX = centerGrid, gridY = centerGrid, width = 2, height = 2, instanceId = java.util.UUID.randomUUID().toString(), sectId = "")
            gameData = gameData.copy(
                slotId = currentSlot,
                currentSlot = currentSlot,
                placedBuildings = listOf(initialMine),
                spiritMineSlots = (0..2).map { SpiritMineSlot(index = it, sectId = "") },
                // 显式清零所有建筑/槽位相关字段，防止旧存档数据残留
                productionSlots = emptyList(),
                residenceSlots = emptyList(),
                warehouseGarrisons = emptyList(),
                patrolSlots = emptyList(),
                patrolConfig = PatrolConfig(),
                patrolConfigs = emptyList(),
                librarySlots = emptyList(),
                spiritFieldPlants = emptyList()
            )
            repeat(3) { discipleService.recruitDisciple() }
        }
        addInitialManual()
        // 2. 世界初始化完成后才加载邮件
        // Note: isGameStarted is set to true later in SaveLoadViewModel.restartGame()
        // after startGameLoop() succeeds
        try { mailService.resetAndInitSlot(currentSlot) } catch (e: Exception) { DomainLog.e("GameEngine", "Failed to init mail for restarted game slot $currentSlot", e) }
    } else {
        stateStore.update { gameData = GameData().copy(currentSlot = currentSlot) }
    }
}

private suspend fun GameEngine.initializeWorldAndServices(sectName: String, currentSlot: Int = 1) {
    val generationResult = WorldMapGenerator.generateWorldSects(sectName)
    val sectRelations = WorldMapGenerator.initializeSectRelations(generationResult.sects)
    productionCoordinator.repository.initializeAllSlots(currentSlot)
    cultivationService.refreshTravelingMerchant(1, 1)
    cultivationService.refreshRecruitList(1)
    cultivationService.refreshMerchantAcquisition(1, 1)
    stateStore.update {
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
        val id = discipleId.toInt()
        if (id !in discipleTables.ids) return@update
        val current = discipleTables.assemble(id)
        val updated = update(current)
        discipleTables.remove(id)
        discipleTables.insert(updated)
    }
}

suspend fun GameEngine.changeDiscipleTypeAtomic(discipleId: String, newType: String) {
    stateStore.update {
        val id = discipleId.toInt()
        if (id in discipleTables.ids) discipleTables.discipleTypes[id] = newType
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
            val pill = pills.get(pillId) ?: return@update
            if (pill.quantity <= 0) return@update
            val id = discipleId.toInt()
            if (id !in discipleTables.ids) return@update
            val disciple = discipleTables.assemble(id)
            if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, pill.minRealm)) { errorMsg = "弟子${disciple.name}境界不足，无法使用${pill.name}（需要${GameConfig.Realm.getName(pill.minRealm)}及以上）"; return@update }
            if (pill.effects.cannotStack && disciple.pillEffects.activePillCategory == pill.category.name) { errorMsg = "弟子${disciple.name}已有同类型丹药生效中，无法使用${pill.name}"; return@update }
            if (pill.category == PillCategory.FUNCTIONAL && pill.pillType.isNotEmpty()) {
                if (disciple.usage.usedFunctionalPillTypes.contains(pill.pillType)) { errorMsg = "弟子${disciple.name}已使用过${pill.name}，同类功能丹药每人只能使用一次"; return@update }
            }
            val newQty = pill.quantity - 1
            if (newQty <= 0) pills.remove(pillId) else pills.update(pillId) { it.copy(quantity = newQty) }
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
            discipleTables.remove(id)
            discipleTables.insert(updatedDisciple)
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
        val instance = manualInstances.get(instanceId) ?: return@update
        val id = discipleId.toInt()
        if (id !in discipleTables.ids) return@update
        val currentDisciple = discipleTables.assemble(id)
        val bagStackIds = currentDisciple.manualBagStackIds()
        val result = addManualInstanceToDiscipleBag(disciple = currentDisciple, instance = instance, bagStackIds = bagStackIds, gameYear = gameData.gameYear, gameMonth = gameData.gameMonth, gamePhase = gameData.gamePhase, maxStackSize = inventoryConfig.getMaxStackSize("manual_stack"))
        val updatedManualIds = currentDisciple.manualIds.filter { mid -> mid != instanceId }
        val updatedDisciple = result.updatedDisciple.copy(manualIds = updatedManualIds)
        discipleTables.remove(id)
        discipleTables.insert(updatedDisciple)
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
        val oldInstance = manualInstances.get(oldInstanceId) ?: return@update
        val newStack = manualStacks.get(newStackId) ?: return@update
        val id = discipleId.toInt()
        if (id !in discipleTables.ids) return@update
        val disciple = discipleTables.assemble(id)
        if (newStack.quantity < 1) return@update
        if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, newStack.minRealm)) return@update
        val blocked = newStack.type == ManualType.MIND && oldInstance.type != ManualType.MIND && disciple.manualIds.filter { it != oldInstanceId }.any { mid -> manualInstances.get(mid)?.type == ManualType.MIND }
        if (blocked) return@update
        val hasSameName = disciple.manualIds.filter { it != oldInstanceId }.any { mid -> manualInstances.get(mid)?.name == newStack.name }
        if (hasSameName) return@update
        val bagStackIds = disciple.manualBagStackIds()
        val result = addManualInstanceToDiscipleBag(disciple = disciple, instance = oldInstance, bagStackIds = bagStackIds, gameYear = gameData.gameYear, gameMonth = gameData.gameMonth, gamePhase = gameData.gamePhase, maxStackSize = inventoryConfig.getMaxStackSize("manual_stack"))
        val currentNewStack = manualStacks.get(newStackId) ?: return@update
        val newQty = currentNewStack.quantity - 1
        if (newQty <= 0) manualStacks.remove(newStackId) else manualStacks.update(newStackId) { it.copy(quantity = newQty) }
        val newInstance = newStack.toInstance(id = java.util.UUID.randomUUID().toString(), ownerId = discipleId, isLearned = true)
        manualInstances.add(newInstance)
        val updatedProficiencies = gameData.manualProficiencies.toMutableMap()
        updatedProficiencies[discipleId]?.let { profList ->
            val filtered = profList.filter { it.manualId != oldInstanceId }
            if (filtered.isEmpty()) updatedProficiencies.remove(discipleId) else updatedProficiencies[discipleId] = filtered
        }
        val updatedManualIds = (disciple.manualIds.filter { mid -> mid != oldInstanceId }) + newInstance.id
        val updatedDisciple = result.updatedDisciple.copy(manualIds = updatedManualIds)
        discipleTables.remove(id)
        discipleTables.insert(updatedDisciple)
        gameData = gameData.copy(manualProficiencies = updatedProficiencies)
    }
}

suspend fun GameEngine.learnManual(discipleId: String, stackId: String) {
    stateStore.update {
        val stack = manualStacks.get(stackId) ?: return@update
        val id = discipleId.toInt()
        if (id !in discipleTables.ids) return@update
        val disciple = discipleTables.assemble(id)
        if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm)) return@update
        val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
        if (disciple.manualIds.size >= maxSlots) return@update
        if (stack.type == ManualType.MIND && disciple.manualIds.any { mid -> manualInstances.get(mid)?.type == ManualType.MIND }) return@update
        if (disciple.manualIds.any { mid -> manualInstances.get(mid)?.name == stack.name }) return@update
        val newQty = stack.quantity - 1
        if (newQty <= 0) manualStacks.remove(stackId) else manualStacks.update(stackId) { it.copy(quantity = newQty) }
        val instanceId = java.util.UUID.randomUUID().toString()
        val instance = stack.toInstance(id = instanceId, ownerId = discipleId, isLearned = true)
        manualInstances.add(instance)
        if (!disciple.manualIds.contains(instanceId)) {
            val hpDelta = stack.stats["hp"] ?: stack.stats["maxHp"] ?: 0
            val mpDelta = stack.stats["mp"] ?: stack.stats["maxMp"] ?: 0
            val rawHp = disciple.combat.currentHp; val rawMp = disciple.combat.currentMp
            val updatedDisciple = disciple.copy(manualIds = disciple.manualIds + instanceId, combat = disciple.combat.copy(currentHp = if (rawHp >= 0 && hpDelta > 0) rawHp + hpDelta else rawHp, currentMp = if (rawMp >= 0 && mpDelta > 0) rawMp + mpDelta else rawMp))
            discipleTables.remove(id)
            discipleTables.insert(updatedDisciple)
        }
    }
}

// ── Cross-domain: Recruit ───────────────────────────────────────────

fun GameEngine.recruitAllFromList(): Boolean {
    val data = stateStore.gameData.value
    if (data.recruitList.isEmpty()) return false
    val currentMonthValue = data.gameYear * 12 + data.gameMonth
    gameEngineCore.launchInScope {
        stateStore.update {
            var nextId = (discipleTables.ids.maxOrNull() ?: 0) + 1
            val recruitedDisciples = gameData.recruitList.map {
                it.copy(
                    id = (nextId++).toString(),
                    usage = it.usage.copy(recruitedMonth = currentMonthValue)
                )
            }
            recruitedDisciples.forEach { discipleTables.insert(it) }
            gameData = gameData.copy(recruitList = emptyList())
        }
    }
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
            gameEngineCore.launchInScope { stateStore.update { val id = discipleId.toInt(); if (id in discipleTables.ids && discipleTables.statuses[id] == DiscipleStatus.MINING) discipleTables.statuses[id] = DiscipleStatus.IDLE } }
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

fun GameEngine.updateYearlySalary(newSalary: Map<Int, Int>) { updateGameDataSync { it.copy(yearlySalary = newSalary) } }

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
    DomainLog.w("GameEngine", "迁移巡逻槽位: ${oldSlots.size}槽/${numTowers}塔 → ${expectedSize}槽")
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
    DomainLog.i("GameEngine", "巡逻槽位迁移完成: ${oldSlots.size} → ${newSlots.size}")
    return gameData.copy(patrolSlots = newSlots, patrolConfigs = newConfigs) to updatedDisciples
}

// ── Memory ──────────────────────────────────────────────────────────

fun GameEngine.getMemoryUsageInfo(): String {
    val sb = StringBuilder()
    sb.appendLine("=== 内存使用情况 ===")
    sb.appendLine("弟子数量: ${stateStore.discipleTables.count}"); sb.appendLine("装备栈数量: ${stateStore.equipmentStacks.value.size}")
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
        else -> { DomainLog.w("GameEngine", "未知的内存压力级别: $level，忽略"); return }
    }
    val logsToKeep = if (normalizedLevel == 1) 20 else 10
    val levelName = if (normalizedLevel == 1) "MODERATE" else "CRITICAL"
    gameEngineCore.launchInScope {
        stateStore.update {
            if (battleLogs.size > logsToKeep) { battleLogs = battleLogs.take(logsToKeep); DomainLog.d("GameEngine", "内存释放($levelName): 战斗日志已清理，保留最近$logsToKeep 条") }
            if (normalizedLevel == 2) {
                var trimmed = false
                val worldSects = gameData.worldMapSects
                if (worldSects.size > 30) {
                    val playerSect = worldSects.find { it.isPlayerSect }
                    val otherSects = worldSects.filter { !it.isPlayerSect }.sortedByDescending { s -> s.relation }.take(if (playerSect != null) 29 else 30)
                    val trimmedSects = if (playerSect != null) listOf(playerSect) + otherSects else otherSects
                    gameData = gameData.copy(worldMapSects = trimmedSects); trimmed = true; DomainLog.d("GameEngine", "内存释放($levelName): worldMapSects 裁剪至 ${trimmedSects.size} 个")
                }
                val caveTeams = gameData.caveExplorationTeams
                if (caveTeams.size > 15) { gameData = gameData.copy(caveExplorationTeams = caveTeams.take(15)); trimmed = true; DomainLog.d("GameEngine", "内存释放($levelName): caveExplorationTeams 裁剪至 15 个") }
                val aiCaveTeams = gameData.aiCaveTeams
                if (aiCaveTeams.size > 15) { gameData = gameData.copy(aiCaveTeams = aiCaveTeams.take(15)); trimmed = true; DomainLog.d("GameEngine", "内存释放($levelName): aiCaveTeams 裁剪至 15 个") }
                if (!trimmed) DomainLog.d("GameEngine", "内存释放($levelName): 无需裁剪其他列表")
            }
        }
    }
}
