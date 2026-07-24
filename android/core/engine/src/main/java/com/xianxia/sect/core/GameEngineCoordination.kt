package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.TimeProgressUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.guide.GuideCounterKeys
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
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.util.addManualInstanceToDiscipleBag
import com.xianxia.sect.core.util.manualBagStackIds
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult

// ── Focus / UI state ────────────────────────────────────────────────

fun GameEngine.setFocusedDiscipleId(id: String?) {
    // 焦点域已覆盖焦点弟子功能，不再需要独立焦点弟子跟踪
}

fun GameEngine.setActiveTab(tab: String) {
    stateStore.activeTab = tab
}

fun GameEngine.setActiveDialog(dialogName: String?) {
    stateStore.activeDialog = dialogName
}

fun GameEngine.pushSubDialogDomain(domainName: String) {
    stateStore.activeSubDialogs = stateStore.activeSubDialogs + domainName
}

fun GameEngine.popSubDialogDomain(domainName: String) {
    stateStore.activeSubDialogs = stateStore.activeSubDialogs - domainName
}

fun GameEngine.notifyUserInteraction() = gameEngineCore.onUserInteraction()

// ── Game lifecycle ──────────────────────────────────────────────────

suspend fun GameEngine.initializeNewGameSuspend(gameData: GameData) {
    stateStore.update { this.gameData = gameData }
}

suspend fun GameEngine.ensureHeavyDataLoaded() {
    if (heavyDataLoaded) return
    val slot = stateStore.gameDataSnapshot.currentSlot
    heavyDataLoaded = true
    val currentSects = stateStore.gameDataSnapshot.worldMapSects
    DomainLog.d("GameEngine", "ensureHeavyDataLoaded: 完成 slot=$slot worldMapSects=${currentSects.size}")
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
    // 防御性幽灵过滤：读档时清除 name 为空的幽灵弟子（补充 SaveValidator 的保护）
    val cleanedDisciples = migratedDisciples.filter { it.name.isNotBlank() }
    if (cleanedDisciples.size != migratedDisciples.size) {
        val count = migratedDisciples.size - cleanedDisciples.size
        DomainLog.w("GameEngine", "loadData: 过滤了 $count 个幽灵弟子（name为空）")
    }
    stateStore.loadFromSnapshot(
        gameData = migratedGameData, disciples = cleanedDisciples,
        equipmentStacks = equipmentStacks, equipmentInstances = equipmentInstances,
        manualStacks = manualStacks, manualInstances = manualInstances, pills = pills,
        materials = materials, herbs = herbs, seeds = seeds, storageBags = storageBags,
        teams = teams, battleLogs = battleLogs
    )
    // 丹药追踪字段迁移（必须在 stateStore.update 内执行，确保字段守卫通过）
    stateStore.update {
        val tables = discipleTables
        for (id in tables.ids) {
            val oldFunctionalTypes = tables.usedFunctionalPillTypes.getOrNull(id) ?: emptyList()
            val currentPermanentKeys = tables.usedPermanentPillKeys.getOrNull(id) ?: emptySet()
            if (currentPermanentKeys.isEmpty() && oldFunctionalTypes.isNotEmpty()) {
                tables.usedPermanentPillKeys[id] = oldFunctionalTypes.flatMap { pillType ->
                    (1..6).map { tier -> "$tier#$pillType" }
                }.toSet()
            }
            val oldExtendLifeIds = tables.usedExtendLifePillIds.getOrNull(id) ?: emptyList()
            val currentExtendLifeTypes = tables.usedExtendLifePillTypes.getOrNull(id) ?: emptySet()
            if (currentExtendLifeTypes.isEmpty() && oldExtendLifeIds.isNotEmpty()) {
                tables.usedExtendLifePillTypes[id] = oldExtendLifeIds.toSet()
            }
            val oldActiveCategory = tables.activePillCategories.getOrNull(id) ?: ""
            val currentActiveTypes = tables.activePillTypes.getOrNull(id) ?: emptySet()
            if (currentActiveTypes.isEmpty() && oldActiveCategory.isNotEmpty()) {
                tables.activePillTypes[id] = setOf(oldActiveCategory)
            }
        }
    }
    DomainLog.d("GameEngine", "loadData: restored game year=${gameData.gameYear}, ${disciples.size} disciples, recruitList=${gameData.recruitList.size} unrecruited disciples")
    val alchemyCount = BuildingFeatureRegistry.countByType(gameData, BuildingType.ALCHEMY)
    val forgeCount = BuildingFeatureRegistry.countByType(gameData, BuildingType.FORGE)
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
        if (currentData.recruitList.isEmpty() && currentData.gameYear - currentData.lastRecruitYear >= 3) {
            cultivationService.refreshRecruitList(currentData.gameYear)
        }
    }
    // 旧存档兼容：merchantRefreshChances=0（该字段加入前的存档）初始化为1
    // 同时设置 lastGrantYear 防止下一年度事件双倍发放
    if (currentData.merchantRefreshChances == 0 && currentData.merchantLastRefreshChanceGrantYear == 0) {
        stateStore.update {
            this.gameData = this.gameData.copy(
                merchantRefreshChances = 1,
                merchantLastRefreshChanceGrantYear = currentData.gameYear
            )
        }
        DomainLog.w("GameEngine", "loadData: merchantRefreshChances was 0, initialized to 1, lastGrantYear=${currentData.gameYear}")
    }
    discipleService.syncAllDiscipleStatuses()

    // 旧存档兼容：spiritMineLastSettledMonth=0（该字段加入前的存档）会导致首月灵矿产出暴增
    // 修复 P1-1：检测到 0 且游戏已有进度时，初始化为当前月份
    stateStore.update {
        val data = this.gameData
        if (data.spiritMineLastSettledMonth == 0) {
            val currentMonth = data.gameYear * 12 + data.gameMonth
            if (currentMonth > 1) {
                this.gameData = data.copy(spiritMineLastSettledMonth = currentMonth)
                DomainLog.w("GameEngine", "loadData: spiritMineLastSettledMonth was 0, initialized to $currentMonth")
            }
        }
    }

    val loadedData = stateStore.gameData.value
    if (loadedData.aiSectDisciples.isEmpty() && loadedData.worldMapSects.isNotEmpty()) {
        DomainLog.i("GameEngine", "loadData: aiSectDisciples empty, regenerating for ${loadedData.worldMapSects.size} sects")
        val regenerated = mutableMapOf<String, List<Disciple>>()
        for (sect in loadedData.worldMapSects) {
            if (!sect.isPlayerSect) { val (d, _) = AISectDiscipleManager.initializeSectDisciples(sect.name, sect.level); regenerated[sect.id] = d }
        }
        stateStore.update { this.gameData = this.gameData.copy(aiSectDisciples = regenerated) }
    }
    // 旧存档兼容：AI 宗门弟子数不足 50 人时补充至 50 人
    val currentDisciples = stateStore.gameData.value.aiSectDisciples
    val worldSects = stateStore.gameData.value.worldMapSects
    if (currentDisciples.isNotEmpty() && worldSects.isNotEmpty()) {
        val filled = currentDisciples.toMutableMap()
        var filledCount = 0
        for (sect in worldSects) {
            if (sect.isPlayerSect) continue
            val existing = filled[sect.id] ?: continue
            if (existing.size < 50) {
                filled[sect.id] = AISectDiscipleManager.fillDisciplesToTarget(
                    sect.name, existing, 50, sect.level
                )
                filledCount++
            }
        }
        if (filledCount > 0) {
            DomainLog.i("GameEngine", "loadData: filled $filledCount AI sects to 50 disciples")
            stateStore.update { this.gameData = this.gameData.copy(aiSectDisciples = filled) }
        }
    }
    try { mailService.resetAndInitSlot(gameData.slotId) } catch (e: Exception) { DomainLog.e("GameEngine", "Failed to initialize mail for slot ${gameData.slotId}", e) }
    // 运营补偿：读档时自动注入补偿邮件
    try {
        injectCompensationOnLoad()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.e("GameEngine", "注入补偿邮件失败", e)
    }
}

suspend fun GameEngine.createNewGame(sectName: String, currentSlot: Int = 1) {
    stateStore.resetForSlot(currentSlot); cultivationService.resetHighFrequencyData()
    // 1. 先初始化世界和游戏状态（邮件依赖 gameData 就绪）
    initializeWorldAndServices(sectName, currentSlot)
    val gridCells = GameConfig.SectMap.WORLD_WIDTH_CELLS
    val centerGrid = gridCells / 2 - 1  // 2x2 building centered on grid
    val mapSeed = java.util.Random().nextInt()
    stateStore.update {
        val initialMine = GridBuildingData(buildingId = "灵矿场", displayName = "灵矿场", gridX = centerGrid, gridY = centerGrid, width = 2, height = 2, instanceId = java.util.UUID.randomUUID().toString(), sectId = "")
        gameData = gameData.copy(
            slotId = currentSlot,
            currentSlot = currentSlot,
            mapSeed = mapSeed,
            placedBuildings = listOf(initialMine),
            spiritMineSlots = (0..2).map { SpiritMineSlot(index = it, sectId = "") },
            spiritMineLastSettledMonth = 1 * 12 + 1,  // gameYear=1, gameMonth=1
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
        repeat(3) { discipleService.recruitDisciple(realm = 9) }
    }
    addInitialStorageBags()
    // 2. 世界初始化完成后才加载邮件（此时 mailRecords/slotId 等状态已就绪）
    // Note: isGameStarted is set to true later in SaveLoadViewModel.startNewGame()
    // after startGameLoop() succeeds, ensuring UI doesn't appear without a running game loop
    try { mailService.resetAndInitSlot(currentSlot) } catch (e: Exception) { DomainLog.e("GameEngine", "Failed to init mail for new game slot $currentSlot", e) }
}

suspend fun GameEngine.restartGameSuspend(sectName: String = "", currentSlot: Int = 1) = restartGameInternal(sectName, currentSlot)

private suspend fun GameEngine.restartGameInternal(sectName: String, currentSlot: Int) {
    stateStore.resetForSlot(currentSlot); cultivationService.resetHighFrequencyData()
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
            spiritMineLastSettledMonth = 1 * 12 + 1,  // gameYear=1, gameMonth=1
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
            repeat(3) { discipleService.recruitDisciple(realm = 9) }
        }
        addInitialStorageBags()
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

    // 为每个 AI 宗门分配唯一弟子头像
    val aiSects = generationResult.sects.filter { !it.isPlayerSect }
    val allPortraitNames = com.xianxia.sect.core.util.PortraitPool.allPortraitNames()
    val sectDetailsMap = aiSects.mapIndexed { index, sect ->
        val portraitRes = allPortraitNames[index % allPortraitNames.size]
        sect.id to com.xianxia.sect.core.model.SectDetail(sectId = sect.id, portraitRes = portraitRes)
    }.toMap()

    stateStore.update {
        gameData = gameData.copy(
            sectName = sectName,
            worldMapSects = generationResult.sects,
            sectRelations = sectRelations,
            aiSectDisciples = generationResult.aiSectDisciples,
            availableMissions = emptyList(),
            sectDetails = sectDetailsMap
        )
    }
}

private suspend fun GameEngine.addInitialStorageBags() {
    stateStore.update {
        storageBags = storageBags + listOf(
            StorageBag(name = "凡品储物袋", rarity = 1, quantity = 1),
            StorageBag(name = "凡品储物袋", rarity = 1, quantity = 1)
        )
    }
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
    }
    discipleFacade.syncAllDiscipleStatuses()
}

suspend fun GameEngine.updateGameDataAndSync(update: (GameData) -> GameData) {
    stateStore.update { gameData = update(gameData) }
    discipleFacade.syncAllDiscipleStatuses()
}

// ── Cross-domain: Sect / Map ────────────────────────────────────────

suspend fun GameEngine.enterSect(sectId: String) { stateStore.update { gameData = gameData.copy(activeSectId = sectId) } }
fun GameEngine.currentActiveSectId(): String = stateStore.gameDataSnapshot.activeSectId

// ── Cross-domain: Use pill ──────────────────────────────────────────

fun GameEngine.usePill(discipleId: String, pillId: String) {
    giveItemToDisciple(discipleId, pillId, "pill")
}

// ── Cross-domain: Manual operations ─────────────────────────────────

suspend fun GameEngine.equipItem(discipleId: String, equipmentId: String): DomainResult<Unit> {
    return discipleService.equipEquipment(discipleId, equipmentId)
}

suspend fun GameEngine.unequipItem(discipleId: String, slot: EquipmentSlot): DomainResult<Unit>? {
    val disciple = getDiscipleById(discipleId) ?: return null
    val equipId = when (slot) { EquipmentSlot.WEAPON -> disciple.equipment.weaponId; EquipmentSlot.ARMOR -> disciple.equipment.armorId; EquipmentSlot.BOOTS -> disciple.equipment.bootsId; EquipmentSlot.ACCESSORY -> disciple.equipment.accessoryId }
    if (equipId.isEmpty()) return null
    val result = discipleService.unequipEquipment(discipleId, equipId)
    cultivationService.markAutoEquipDirty(discipleId)
    return result
}

suspend fun GameEngine.unequipItemById(discipleId: String, equipmentId: String): DomainResult<Unit> {
    val result = discipleService.unequipEquipment(discipleId, equipmentId)
    cultivationService.markAutoEquipDirty(discipleId)
    return result
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

        // 记录功法替换日志
        val replaceAge = discipleTables.ages[id]
        val replaceEvents = discipleTables.lifeEvents.getOrDefault(id, emptyList())
        discipleTables.lifeEvents[id] = replaceEvents +
            "${replaceAge}岁：将功法${oldInstance.name}替换为${newStack.name}"

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

suspend fun GameEngine.recruitAllFromList(): Int {
    var recruited = 0
    stateStore.update {
        val validRecruits = gameData.recruitList.filter { d ->
            d.name.isNotBlank() && d.age > 0 && d.realm > 0
        }
        if (validRecruits.isEmpty()) {
            if (gameData.recruitList.isNotEmpty()) {
                DomainLog.w("GameEngine", "recruitAllFromList: all ${gameData.recruitList.size} recruits are corrupted, skipped")
            }
            return@update
        }
        val droppedCount = gameData.recruitList.size - validRecruits.size
        val currentMonth = gameData.gameYear * 12 + gameData.gameMonth
        validRecruits.forEach { disciple ->
            discipleTables.allocateAndInsert(
                disciple.copy(usage = disciple.usage.copy(recruitedMonth = currentMonth))
            )
        }
        recruited = validRecruits.size
        gameData = gameData.copy(recruitList = emptyList())
        if (droppedCount > 0) {
            DomainLog.w("GameEngine", "recruitAllFromList: dropped $droppedCount corrupted recruits, recruited $recruited")
        } else {
            DomainLog.i("GameEngine", "recruitAllFromList: recruited $recruited disciples")
        }
    }
    return recruited
}

fun GameEngine.removeFromRecruitList(discipleId: String) {
    updateGameDataSync { it.copy(recruitList = it.recruitList.toList().filter { it.id != discipleId }) }
}

// ── Cross-domain: Spirit mine / patrol / salary ─────────────────────

fun GameEngine.validateAndFixSpiritMineData() {
    val unified = stateStore.unifiedState.value
    val data = unified.gameData
    val discipleMap = unified.disciples.associateBy { it.id }
    val globalMines = data.placedBuildings.filter {
        BuildingFeatureRegistry.findByDisplayName(it.displayName)?.buildingType == BuildingType.MINING
    }
    val rebuiltSlots = mutableListOf<SpiritMineSlot>()
    var slotIdx = 0
    for (mine in globalMines) {
        for (offset in 0 until 3) {
            val existing = data.spiritMineSlots.getOrNull(slotIdx + offset)
            val slot = if (existing != null) {
                if (existing.discipleId.isNotEmpty() && existing.discipleId !in discipleMap) existing.copy(discipleId = "", discipleName = "", index = rebuiltSlots.size, buildingInstanceId = mine.instanceId) else existing.copy(index = rebuiltSlots.size, buildingInstanceId = mine.instanceId)
            } else SpiritMineSlot(index = rebuiltSlots.size, sectId = mine.sectId, buildingInstanceId = mine.instanceId)
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
    gameEngineCore.launchInScope {
        stateStore.modifyState { spiritStoneWallet.add(this, amount, com.xianxia.sect.core.model.SpiritStoneGrade.LOW,
            com.xianxia.sect.core.wallet.SpiritStoneSource.Internal) }
    }
}

fun GameEngine.updateYearlySalary(newSalary: Map<Int, Int>) { updateGameDataSync { it.copy(yearlySalary = newSalary) } }

// ── Cross-domain: Missions ──────────────────────────────────────────

fun GameEngine.startMission(mission: Mission, selectedDisciples: List<Disciple>) {
    val data = stateStore.gameDataSnapshot
    val activeMission = MissionSystem.createActiveMission(mission, selectedDisciples, data.gameYear, data.gameMonth)
    updateGameDataSync { it.copy(activeMissions = it.activeMissions + activeMission) }
    selectedDisciples.forEach { disciple -> gameEngineCore.launchInScope { updateDiscipleStatus(disciple.id, DiscipleStatus.ON_MISSION) } }
}

suspend fun GameEngine.checkAndProcessCompletedMissions(): List<String> {
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
                applyMissionResult(result, activeMission, data.gameYear, data.gameMonth, aliveDisciples)
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

private suspend fun GameEngine.applyMissionResult(
    result: MissionSystem.MissionResult,
    activeMission: ActiveMission,
    year: Int,
    month: Int,
    aliveDisciples: List<Disciple>
) {
    // 引导系统：累计完成任务
    incrementGuideCounter(GuideCounterKeys.MISSIONS_COMPLETED)
    if (result.spiritStones > 0) addSpiritStones(result.spiritStones.toLong())
    result.materials.forEach { material ->
        when (val r = inventorySystem.addMaterial(material)) {
            is DomainResult.Success -> {}
            is DomainResult.Partial -> DomainLog.w("GameEngine", "材料 ${material.name} 溢出 ${r.overflow} 个")
            is DomainResult.Failure -> DomainLog.w("GameEngine", "添加材料失败: ${r.error}")
        }
    }
    inventorySystem.withTrackingSource("sect_level") {
        result.pills.forEach { pill ->
            when (val r = inventorySystem.addPill(pill)) {
                is DomainResult.Success -> {}
                is DomainResult.Partial -> DomainLog.w("GameEngine", "丹药 ${pill.name} 溢出 ${r.overflow} 个")
                is DomainResult.Failure -> DomainLog.w("GameEngine", "添加丹药失败: ${r.error}")
            }
        }
        result.equipmentStacks.forEach { equipment ->
            when (val r = inventorySystem.addEquipmentStack(equipment)) {
                is DomainResult.Success -> {}
                is DomainResult.Partial -> DomainLog.w("GameEngine", "装备 ${equipment.name} 溢出 ${r.overflow} 个")
                is DomainResult.Failure -> DomainLog.w("GameEngine", "添加装备失败: ${r.error}")
            }
        }
    }
    result.manualStacks.forEach { manual ->
        when (val r = inventorySystem.addManualStack(manual)) {
            is DomainResult.Success -> {}
            is DomainResult.Partial -> DomainLog.w("GameEngine", "功法 ${manual.name} 溢出 ${r.overflow} 个")
            is DomainResult.Failure -> DomainLog.w("GameEngine", "添加功法失败: ${r.error}")
        }
    }

    // 有战斗则写入战斗日志
    if (result.combatTriggered && result.battleResult != null) {
        val bsr = result.battleResult
        val logData = bsr.log
        val teamMembers = logData.teamMembers.map { m ->
            BattleLogMember(
                id = m.id, name = m.name, realm = m.realm, realmName = m.realmName,
                hp = m.hp, maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp,
                isAlive = m.isAlive, portraitRes = m.portraitRes
            )
        }
        val enemies = logData.enemies.map { e ->
            BattleLogEnemy(
                id = e.id, name = "敌人", realm = e.realm, realmName = e.realmName,
                hp = e.hp, maxHp = e.maxHp, isAlive = e.isAlive, portraitRes = e.portraitRes
            )
        }
        val rounds = logData.rounds.map { r ->
            BattleLogRound(
                roundNumber = r.roundNumber,
                actions = r.actions.map { a ->
                    BattleLogAction(
                        type = a.type, attacker = a.attacker, attackerType = a.attackerType,
                        target = a.target, damage = a.damage, damageType = a.damageType,
                        isCrit = a.isCrit, isKill = a.isKill, message = a.message,
                        skillName = a.skillName
                    )
                }
            )
        }
        val drops = mutableListOf<String>()
        if (result.spiritStones > 0) drops.add("灵石 ×${result.spiritStones}")
        result.materials.forEach { drops.add("${it.name} ×${it.quantity}") }
        result.pills.forEach { drops.add("${it.name} ×${it.quantity}") }
        result.equipmentStacks.forEach { drops.add("${it.name} ×${it.quantity}") }
        result.manualStacks.forEach { drops.add("${it.name} ×${it.quantity}") }

        gameEngineCore.launchInScope {
            stateStore.update {
                recordPlayerBattle(
                    year = year,
                    month = month,
                    type = BattleType.PVE,
                    attackerName = "玩家队伍",
                    defenderName = activeMission.missionName,
                    result = if (result.victory) BattleResult.WIN else BattleResult.LOSE,
                    teamMembers = teamMembers,
                    enemies = enemies,
                    rounds = rounds,
                    turns = bsr.turnCount,
                    details = "执行任务「${activeMission.missionName}」，" +
                        if (result.victory) "战斗胜利" else "战斗失利",
                    drops = drops
                )
            }
        }
    }
}

// ── Service delegates ───────────────────────────────────────────────

suspend fun GameEngine.completeExploration(teamId: String, success: Boolean, survivorIds: List<String>) = explorationService.completeExploration(teamId, success, survivorIds)
suspend fun GameEngine.redeemCode(code: String, usedCodes: List<String>, currentYear: Int, currentMonth: Int): RedeemResult = redeemCodeService.redeemCode(code, usedCodes, currentYear, currentMonth)
fun GameEngine.resetCultivationTimer() { cultivationService.resetHighFrequencyData() }
suspend fun GameEngine.checkpointAllProduction() { cultivationService.checkpointAllProduction() }
fun GameEngine.checkpointAllDisciples() { stateStore.update { cultivationService.checkpointAllDisciples(this) } }

// ── Private: Production slot fix ────────────────────────────────────

private suspend fun GameEngine.checkAndCollectCompletedSlots() {
    autoHarvestCompletedAlchemySlots()
    val forgeSlots = productionCoordinator.repository.getSlotsByBuildingId("forge")
    forgeSlots.forEach { slot -> if (slot.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.COMPLETED) buildingService.autoHarvestForgeSlot(slot) }
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
    val numTowers = gameData.placedBuildings.count {
        BuildingFeatureRegistry.findByDisplayName(it.displayName)?.buildingType == BuildingType.PATROL
    }
    if (numTowers == 0) return gameData to disciples
    val oldSlots = gameData.patrolSlots
    val expectedSize = numTowers * 8
    if (oldSlots.size <= expectedSize) {
        // 即使数量正确，也需回填 buildingInstanceId（旧存档可能为空）
        val towers = gameData.placedBuildings.filter {
            BuildingFeatureRegistry.findByDisplayName(it.displayName)?.buildingType == BuildingType.PATROL
        }
        val needsBackfill = oldSlots.any { it.buildingInstanceId.isEmpty() }
        if (!needsBackfill) return gameData to disciples
        val backfilledSlots = mutableListOf<PatrolSlot>()
        var globalIdx = 0
        for (towerIdx in towers.indices) {
            val tower = towers[towerIdx]
            for (localIdx in 0 until 8) {
                if (globalIdx < oldSlots.size) {
                    backfilledSlots.add(oldSlots[globalIdx].copy(buildingInstanceId = tower.instanceId))
                }
                globalIdx++
            }
        }
        return gameData.copy(patrolSlots = backfilledSlots) to disciples
    }
    DomainLog.w("GameEngine", "迁移巡逻槽位: ${oldSlots.size}槽/${numTowers}塔 → ${expectedSize}槽")
    var updatedDisciples = disciples.toMutableList()
    val towers = gameData.placedBuildings.filter { it.displayName == "巡视楼" }
    val newSlots = mutableListOf<PatrolSlot>()
    var newGlobalIndex = 0
    for (towerIdx in 0 until numTowers) {
        val tower = towers[towerIdx]
        val oldStart = towerIdx * 10
        for (localIdx in 0 until 8) {
            val globalIdx = oldStart + localIdx
            if (globalIdx < oldSlots.size) newSlots.add(oldSlots[globalIdx].copy(index = newGlobalIndex, buildingInstanceId = tower.instanceId)) else newSlots.add(PatrolSlot(index = newGlobalIndex, buildingInstanceId = tower.instanceId))
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

// ── 血炼原子操作 ────────────────────────────────────────────────────

/** 血炼启动结果 */
sealed interface BloodRefinementStartResult {
    data object Success : BloodRefinementStartResult
    data class InsufficientStones(
        val required: Long, val current: Long
    ) : BloodRefinementStartResult
    data class InsufficientMaterials(
        val materialName: String, val missing: Int
    ) : BloodRefinementStartResult
    data class Error(val message: String) : BloodRefinementStartResult
}

/**
 * 原子化启动血炼：灵石扣除、材料消耗、进度写入、弟子状态更新
 * 在同一 [stateStore.update] 事务中完成，失败时整体回滚。
 */
suspend fun GameEngine.startBloodRefinementAtomic(
    materialName: String,
    materialRarity: Int,
    materialCount: Int,
    buildingInstanceId: String,
    requiredSpiritStones: Long,
    progress: BloodRefinementProgress
): BloodRefinementStartResult {
    if (requiredSpiritStones <= 0) {
        return BloodRefinementStartResult.Error("灵石消耗必须为正数")
    }
    if (materialCount <= 0) {
        return BloodRefinementStartResult.Error("材料消耗必须为正数")
    }
    if (progress.durationMonths <= 0 || progress.bonusPercent <= 0.0) {
        return BloodRefinementStartResult.Error("血炼配置异常（duration/bonus）")
    }
    progress.discipleId.toIntOrNull() ?: return BloodRefinementStartResult.Error("非法弟子ID")
    try {
        stateStore.update {
            checkStones(requiredSpiritStones)
            consumeMaterial(materialName, materialRarity, materialCount)
            commitBloodRefinement(buildingInstanceId, requiredSpiritStones, progress)
        }
        return BloodRefinementStartResult.Success
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.e("GameEngine", "血炼原子启动失败: ${e.message}", e)
        return BloodRefinementStartResult.Error(e.message ?: "未知错误")
    }
}

/** 原子化取消血炼：移除进度 + 恢复弟子状态为空闲 */
suspend fun GameEngine.cancelBloodRefinement(
    buildingInstanceId: String,
    discipleId: String
) {
    stateStore.update {
        cancelBloodRefinement(buildingInstanceId, discipleId)
    }
}

/** 月度结算 — 处理所有到期血炼 */
suspend fun GameEngine.processBloodRefinementCompletions() {
    stateStore.update { processBloodRefinementCompletions() }
}

/** 校验灵石是否足够 */
private fun MutableGameState.checkStones(required: Long) {
    if (gameData.spiritStones < required) {
        error("灵石不足: 需要 $required, 当前 ${gameData.spiritStones}")
    }
}

/** 跨堆叠扣除材料 */
private fun MutableGameState.consumeMaterial(
    name: String, rarity: Int, count: Int
) {
    var remaining = count
    val matching = materials.all().filter {
        it.name == name && it.rarity == rarity && !it.isLocked
    }
    for (mat in matching) {
        if (remaining <= 0) break
        val take = minOf(remaining, mat.quantity)
        val newQty = mat.quantity - take
        if (newQty <= 0) materials.remove(mat.id)
        else materials.update(mat.id) { it.copy(quantity = newQty) }
        remaining -= take
    }
    if (remaining > 0) error("兽血材料不足: 缺少 $remaining 份 $name")
}

/** 扣除灵石并写入血炼进度 */
private fun MutableGameState.commitBloodRefinement(
    buildingInstanceId: String,
    requiredSpiritStones: Long,
    progress: BloodRefinementProgress
) {
    // 排他性检查：该建筑已有血炼
    if (buildingInstanceId in gameData.activeBloodRefinements) {
        error("该血炼池已有进行中的血炼")
    }
    // 排他性检查：该弟子已在其他血炼池中
    if (gameData.activeBloodRefinements.values.any {
        it.discipleId == progress.discipleId
    }) {
        error("该弟子已在其他血炼池中")
    }
    val filledProgress = progress.copy(
        startYear = gameData.gameYear, startMonth = gameData.gameMonth
    )
    gameData = gameData.copy(
        spiritStones = gameData.spiritStones - requiredSpiritStones,
        activeBloodRefinements = gameData.activeBloodRefinements +
            (buildingInstanceId to filledProgress)
    )
    val dId = progress.discipleId.toIntOrNull()
    if (dId != null && dId in discipleTables.ids) {
        discipleTables.statuses[dId] = DiscipleStatus.REFINING
        discipleTables.statusData[dId] = mapOf(
            "buildingId" to buildingInstanceId
        )
    }
}

/** 血炼属性 key → 显示名映射 */
private val STAT_DISPLAY_NAMES = mapOf(
    "hp" to "生命",
    "physicalAttack" to "物攻",
    "magicAttack" to "法攻",
    "physicalDefense" to "物防",
    "magicDefense" to "法防",
    "speed" to "速度"
)

/**
 * 月度结算 — 检查并处理所有到期血炼。
 * 遍历 [activeBloodRefinements]，对已到期的条目逐条结算。
 */
fun MutableGameState.processBloodRefinementCompletions() {
    if (gameData.activeBloodRefinements.isEmpty()) return
    val remaining = mutableMapOf<String, BloodRefinementProgress>()
    val originals = gameData.activeBloodRefinements
    for ((buildingId, progress) in originals) {
        val elapsed = TimeProgressUtil.calculateElapsedMonths(
            progress.startYear, progress.startMonth,
            gameData.gameYear, gameData.gameMonth
        )
        if (elapsed < progress.durationMonths) {
            remaining[buildingId] = progress
        } else {
            settleSingleRefinement(buildingId, progress)
        }
    }
    if (remaining.size != originals.size) {
        gameData = gameData.copy(activeBloodRefinements = remaining)
    }
}

/**
 * 结算单条到期血炼：累加百分比乘区、记录完成、
 * 重置弟子状态、发送通知。
 *
 * 新系统使用乘区百分比，直接累加材料百分比到累计记录，
 * 不再写入 DiscipleTables.base* 列。
 */
private fun MutableGameState.settleSingleRefinement(
    buildingId: String,
    progress: BloodRefinementProgress
) {
    val dId = progress.discipleId.toIntOrNull()
    if (dId == null || dId !in discipleTables.ids) return
    val statKey = progress.selectedStat
    if (statKey.isEmpty()) return

    // 防御：血炼期间弟子可能因其他系统死亡
    if (discipleTables.isAlive[dId] == 0) return

    // 百分比累加：只传增量（addPctToTotal 内部做 total + pct）
    val existingTotal = gameData.bloodRefinementPctTotals[progress.discipleId]
    val safeBonusPct = progress.bonusPercent.coerceAtLeast(0.0)

    val updatedTotal = if (existingTotal != null) {
        DiscipleStatCalculator.addPctToTotal(existingTotal, statKey, safeBonusPct)
    } else {
        DiscipleStatCalculator.addPctToTotal(
            BloodRefinementPctTotal(discipleId = progress.discipleId),
            statKey, safeBonusPct
        )
    }

    // ❌ 不再写入 DiscipleTables.base* 列（血炼改为乘法乘区，计算时动态应用）
    // ❌ 不再需要 calculateSimpleInterestBonus

    val existingRefinements =
        gameData.bloodRefinements[progress.discipleId] ?: emptyList()
    val updatedRefinements = existingRefinements + progress.materialId
    gameData = gameData.copy(
        bloodRefinements = gameData.bloodRefinements +
            (progress.discipleId to updatedRefinements),
        bloodRefinementPctTotals = gameData.bloodRefinementPctTotals +
            (progress.discipleId to updatedTotal)
    )

    discipleTables.statuses[dId] = DiscipleStatus.IDLE
    discipleTables.clearBloodRefinementStatusData(dId)

    val statName = STAT_DISPLAY_NAMES[statKey] ?: statKey
    recordGameEvent(
        GameEventCategory.SECT, GameEventType.BLOOD_REFINEMENT,
        "${progress.discipleName}的血练已完成！属性「$statName」获得提升。",
        relatedEntityName = progress.discipleName
    )
}
private fun MutableGameState.cancelBloodRefinement(
    buildingInstanceId: String,
    discipleId: String
) {
    gameData = gameData.copy(
        activeBloodRefinements = gameData.activeBloodRefinements - buildingInstanceId
    )
    val dId = discipleId.toIntOrNull()
    if (dId != null && dId in discipleTables.ids) {
        discipleTables.statuses[dId] = DiscipleStatus.IDLE
        discipleTables.clearBloodRefinementStatusData(dId)
    }
}

/** 仅清除血炼相关的 statusData key，不擦除其他系统写入的数据 */
private fun DiscipleTables.clearBloodRefinementStatusData(dId: Int) {
    val current = statusData.getOrDefault(dId, emptyMap())
    statusData[dId] = current - "buildingId"
}
