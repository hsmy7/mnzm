package com.xianxia.sect.core.engine

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.engine.service.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.system.MerchantItemConverter
import com.xianxia.sect.core.engine.BattleSystem
import com.xianxia.sect.core.engine.DisciplePillManager
import com.xianxia.sect.core.engine.production.ProductionCoordinator
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.engine.HerbGardenSystem
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.StorageBagUtils
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.event.EventBus
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject
import android.util.Log

typealias GiftResult = DiplomacyService.GiftResult

typealias ElderBonusData = FormulaService.ElderBonusData

data class GameStateSnapshot(
    val gameData: GameData,
    val disciples: List<Disciple>,
    val equipmentStacks: List<EquipmentStack>,
    val equipmentInstances: List<EquipmentInstance>,
    val manualStacks: List<ManualStack>,
    val manualInstances: List<ManualInstance>,
    val pills: List<Pill>,
    val materials: List<Material>,
    val herbs: List<Herb>,
    val seeds: List<Seed>,
    val teams: List<ExplorationTeam>,
    val events: List<GameEvent>,
    val battleLogs: List<BattleLog>,
    val alliances: List<Alliance>,
    val productionSlots: List<com.xianxia.sect.core.model.production.ProductionSlot> = emptyList()
)

@ViewModelScoped
class GameEngine @Inject constructor(
    private val gameEngineCore: GameEngineCore,
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig,
    private val battleSystem: BattleSystem,
    private val productionCoordinator: ProductionCoordinator,
    private val eventService: EventService,
    private val discipleService: DiscipleService,
    private val combatService: CombatService,
    private val explorationService: ExplorationService,
    private val buildingService: BuildingService,
    private val saveService: SaveService,
    private val cultivationService: CultivationService,
    private val diplomacyService: DiplomacyService,
    private val redeemCodeService: RedeemCodeService,
    private val formulaService: FormulaService
) {
    companion object {
        private const val TAG = "GameEngine"
    }

    val gameData: StateFlow<GameData> get() = stateStore.gameData
    val disciples: StateFlow<List<Disciple>> get() = stateStore.disciples
    val equipmentStacks: StateFlow<List<EquipmentStack>> get() = stateStore.equipmentStacks
    val equipmentInstances: StateFlow<List<EquipmentInstance>> get() = stateStore.equipmentInstances
    val manualStacks: StateFlow<List<ManualStack>> get() = stateStore.manualStacks
    val manualInstances: StateFlow<List<ManualInstance>> get() = stateStore.manualInstances
    val pills: StateFlow<List<Pill>> get() = stateStore.pills
    val materials: StateFlow<List<Material>> get() = stateStore.materials
    val herbs: StateFlow<List<Herb>> get() = stateStore.herbs
    val seeds: StateFlow<List<Seed>> get() = stateStore.seeds
    fun getCurrentSeeds(): List<Seed> = stateStore.getCurrentSeeds()
    fun getCurrentHerbs(): List<Herb> = stateStore.getCurrentHerbs()
    fun getCurrentMaterials(): List<Material> = stateStore.getCurrentMaterials()
    val events: StateFlow<List<GameEvent>> get() = stateStore.events
    val battleLogs: StateFlow<List<BattleLog>> get() = stateStore.battleLogs
    val teams: StateFlow<List<ExplorationTeam>> get() = stateStore.teams

    val discipleAggregates: StateFlow<List<DiscipleAggregate>> get() = stateStore.discipleAggregates

    val highFrequencyData: StateFlow<HighFrequencyData> = cultivationService.getHighFrequencyData()

    val realtimeCultivation: StateFlow<Map<String, Double>> by lazy {
        cultivationService.getHighFrequencyData()
            .map { it.realtimeCultivation ?: emptyMap() }
            .stateIn(
                gameEngineCore.scopeForStateIn(),
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
                emptyMap()
            )
    }

    val productionSlots: StateFlow<List<ProductionSlot>> = productionCoordinator.slots

    val worldMapRenderData: StateFlow<WorldMapRenderData> by lazy {
        stateStore.gameData.map { data ->
            WorldMapRenderData(
                worldMapSects = data.worldMapSects,
                cultivatorCaves = data.cultivatorCaves ?: emptyList(),
                caveExplorationTeams = data.caveExplorationTeams ?: emptyList(),
                battleTeam = data.battleTeam,
                aiBattleTeams = data.aiBattleTeams ?: emptyList()
            )
        }.distinctUntilChanged()
            .stateIn(
                gameEngineCore.scopeForStateIn(),
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
                WorldMapRenderData()
            )
    }

    suspend fun initializeNewGameSuspend(gameData: GameData) {
        stateStore.update { this.gameData = gameData }
    }

    suspend fun loadData(
        gameData: GameData,
        disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack>,
        equipmentInstances: List<EquipmentInstance>,
        manualStacks: List<ManualStack>,
        manualInstances: List<ManualInstance>,
        pills: List<Pill>,
        materials: List<Material> = emptyList(),
        herbs: List<Herb> = emptyList(),
        seeds: List<Seed> = emptyList(),
        teams: List<ExplorationTeam>,
        events: List<GameEvent>,
        battleLogs: List<BattleLog> = emptyList(),
        alliances: List<Alliance> = emptyList(),
        productionSlots: List<com.xianxia.sect.core.model.production.ProductionSlot> = emptyList()
    ) {
        stateStore.loadFromSnapshot(
            gameData = gameData,
            disciples = disciples,
            equipmentStacks = equipmentStacks,
            equipmentInstances = equipmentInstances,
            manualStacks = manualStacks,
            manualInstances = manualInstances,
            pills = pills,
            materials = materials,
            herbs = herbs,
            seeds = seeds,
            teams = teams,
            events = events,
            battleLogs = battleLogs
        )

        Log.d(TAG, "loadData: restored game year=${gameData.gameYear}, ${disciples.size} disciples, recruitList=${gameData.recruitList.size} unrecruited disciples")

        if (productionSlots.isNotEmpty()) {
            productionCoordinator.repository.restoreSlots(productionSlots)
        }
        checkAndCollectCompletedSlots()

        val currentData = stateStore.gameData.value
        if (currentData.travelingMerchantItems.isEmpty() || currentData.recruitList.isEmpty()) {
            Log.w(TAG, "loadData: detected empty merchant items or recruit list after load, refreshing...")
            stateStore.update {
                if (this.gameData.travelingMerchantItems.isEmpty()) {
                    cultivationService.refreshTravelingMerchant(this.gameData.gameYear, this.gameData.gameMonth)
                }
                if (this.gameData.recruitList.isEmpty()) {
                    cultivationService.refreshRecruitList(this.gameData.gameYear)
                }
            }
        }
    }

    suspend fun createNewGame(sectName: String, currentSlot: Int = 1) {
        stateStore.reset()
        cultivationService.resetHighFrequencyData()
        initializeWorldAndServices(sectName)
        // 在事务中创建初始弟子，避免 addDisciple 异步写入竞态导致弟子丢失
        // 保留 initializeWorldAndServices 已设置的 gameData（含商人商品、招募弟子等），
        // 仅更新必要字段，避免用全新 GameData 覆盖导致首月数据丢失
        stateStore.update {
            gameData = gameData.copy(isGameStarted = true, currentSlot = currentSlot)
            repeat(3) { discipleService.recruitDisciple() }
        }
        addInitialManual()
    }

    suspend fun restartGameSuspend(sectName: String = "", currentSlot: Int = 1) {
        restartGameInternal(sectName, currentSlot)
    }

    private suspend fun restartGameInternal(sectName: String, currentSlot: Int) {
        stateStore.reset()
        cultivationService.resetHighFrequencyData()

        if (sectName.isNotBlank()) {
            initializeWorldAndServices(sectName)
            // 在事务中创建初始弟子，避免 addDisciple 异步写入竞态导致弟子丢失
            // 保留 initializeWorldAndServices 已设置的 gameData（含商人商品、招募弟子等），
            // 仅更新必要字段，避免用全新 GameData 覆盖导致首月数据丢失
            stateStore.update {
                gameData = gameData.copy(isGameStarted = true, currentSlot = currentSlot)
                repeat(3) { discipleService.recruitDisciple() }
            }
            addInitialManual()
        } else {
            stateStore.update {
                gameData = GameData(isGameStarted = true).copy(currentSlot = currentSlot)
            }
        }
    }

    private suspend fun initializeWorldAndServices(sectName: String) {
        val generationResult = WorldMapGenerator.generateWorldSects(sectName)
        val sectRelations = WorldMapGenerator.initializeSectRelations(generationResult.sects)
        productionCoordinator.repository.initializeAllSlots()
        // 在事务中刷新商人商品和招募弟子列表，确保刷新结果写入 stateStore 而不被后续覆盖
        stateStore.update {
            cultivationService.refreshTravelingMerchant(1, 1)
            cultivationService.refreshRecruitList(1)
            gameData = gameData.copy(
                sectName = sectName,
                worldMapSects = generationResult.sects,
                sectRelations = sectRelations,
                aiSectDisciples = generationResult.aiSectDisciples,
                availableMissions = emptyList()
            )
        }
    }

    private fun addInitialManual() {
        val initialStack = ManualStack(
            id = java.util.UUID.randomUUID().toString(),
            name = "基础心法",
            rarity = 1,
            description = "一门基础的心法功法",
            type = ManualType.MIND,
            stats = mapOf("hp" to 10, "mp" to 10),
            minRealm = 9
        )
        inventorySystem.addManualStack(initialStack)
    }

    suspend fun updateGameData(update: (GameData) -> GameData) {
        stateStore.update { gameData = update(gameData) }
    }

    private fun updateGameDataSync(update: (GameData) -> GameData) {
        gameEngineCore.launchInScope {
            stateStore.update { gameData = update(gameData) }
        }
    }

    suspend fun updateDisciple(discipleId: String, update: (Disciple) -> Disciple) {
        stateStore.update {
            val list = disciples.toMutableList()
            val index = list.indexOfFirst { it.id == discipleId }
            if (index >= 0) {
                list[index] = update(list[index])
                disciples = list
            }
        }
    }

    suspend fun updateRealtimeCultivation(currentTimeMillis: Long) =
        cultivationService.updateRealtimeCultivation(currentTimeMillis)

    fun addEquipmentStack(stack: EquipmentStack) = inventorySystem.addEquipmentStack(stack)

    fun removeEquipment(equipmentId: String): Boolean = inventorySystem.removeEquipment(equipmentId)

    fun addManualStackToWarehouse(stack: ManualStack) = inventorySystem.addManualStack(stack)

    fun addPillToWarehouse(pill: Pill) = inventorySystem.addPill(pill)

    fun addMaterialToWarehouse(material: Material) = inventorySystem.addMaterial(material)

    fun addHerbToWarehouse(herb: Herb) = inventorySystem.addHerb(herb)

    fun addSeedToWarehouse(seed: Seed) = inventorySystem.addSeed(seed)

    fun sortWarehouse() = inventorySystem.sortWarehouse()

    fun createEquipmentStackFromRecipe(recipe: ForgeRecipeDatabase.ForgeRecipe): EquipmentStack =
        inventorySystem.createEquipmentFromRecipe(recipe)

    fun createEquipmentStackFromMerchantItem(item: MerchantItem): EquipmentStack =
        inventorySystem.createEquipmentFromMerchantItem(item)

    fun createManualStackFromMerchantItem(item: MerchantItem): ManualStack =
        inventorySystem.createManualFromMerchantItem(item)

    fun createPillFromMerchantItem(item: MerchantItem): Pill =
        inventorySystem.createPillFromMerchantItem(item)

    fun createMaterialFromMerchantItem(item: MerchantItem): Material =
        inventorySystem.createMaterialFromMerchantItem(item)

    fun createHerbFromMerchantItem(item: MerchantItem): Herb =
        inventorySystem.createHerbFromMerchantItem(item)

    fun createSeedFromMerchantItem(item: MerchantItem): Seed =
        inventorySystem.createSeedFromMerchantItem(item)

    fun addDisciple(disciple: Disciple) = discipleService.addDisciple(disciple)

    fun removeDisciple(discipleId: String): Boolean = discipleService.removeDisciple(discipleId)

    fun getDiscipleById(discipleId: String): Disciple? = discipleService.getDiscipleById(discipleId)

    fun updateDisciple(disciple: Disciple) = discipleService.updateDisciple(disciple)

    fun getDiscipleStatus(discipleId: String): DiscipleStatus =
        discipleService.getDiscipleStatus(discipleId)

    fun syncAllDiscipleStatuses() = discipleService.syncAllDiscipleStatuses()

    suspend fun resetAllDisciplesStatus() = discipleService.resetAllDisciplesStatus()

    fun recruitDisciple(): Disciple = discipleService.recruitDisciple()

    suspend fun expelDisciple(discipleId: String): Boolean {
        return discipleService.expelDisciple(discipleId)
    }

    fun equipEquipment(discipleId: String, equipmentId: String): Boolean =
        discipleService.equipEquipment(discipleId, equipmentId)

    fun unequipEquipment(discipleId: String, equipmentId: String): Boolean =
        discipleService.unequipEquipment(discipleId, equipmentId)

    fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean =
        discipleService.isDiscipleAssignedToSpiritMine(discipleId)

    fun updateMonthlySalaryEnabled(realm: Int, enabled: Boolean) =
        discipleService.updateMonthlySalaryEnabled(realm, enabled)

    fun getAliveDisciplesCount(): Int = discipleService.getAliveDisciplesCount()

    fun getIdleDisciples(): List<Disciple> = discipleService.getIdleDisciples()

    suspend fun autoFillLawEnforcementSlots(): Int = discipleService.autoFillLawEnforcementSlots()

    fun getDiscipleAggregate(discipleId: String): DiscipleAggregate? {
        val disciple = discipleService.getDiscipleById(discipleId) ?: return null
        return disciple.toAggregate()
    }

    fun getAllDiscipleAggregates(): List<DiscipleAggregate> {
        return discipleService.getDisciples().value.map { it.toAggregate() }
    }

    suspend fun processBattleCasualties(deadMemberIds: Set<String>, survivorHpMap: Map<String, Int>, survivorMpMap: Map<String, Int> = emptyMap()) =
        combatService.processBattleCasualties(deadMemberIds, survivorHpMap, survivorMpMap)

    fun getTotalBattlesCount(): Int = combatService.getTotalBattlesCount()

    fun getRecentBattles(count: Int = 10): List<BattleLog> = combatService.getRecentBattles(count)

    fun getWinRate(lastNBattles: Int = 50): Double = combatService.getWinRate(lastNBattles)

    fun createExplorationTeam(
        name: String,
        memberIds: List<String>,
        dungeonId: String,
        dungeonName: String,
        duration: Int,
        currentYear: Int,
        currentMonth: Int,
        currentDay: Int
    ): ExplorationTeam = explorationService.createExplorationTeam(
        name, memberIds, dungeonId, dungeonName, duration,
        currentYear, currentMonth, currentDay
    )

    fun recallTeam(teamId: String): Boolean = explorationService.recallTeam(teamId)

    fun completeExploration(teamId: String, success: Boolean, survivorIds: List<String>) =
        explorationService.completeExploration(teamId, success, survivorIds)

    suspend fun startCaveExploration(cave: CultivatorCave, selectedDisciples: List<Disciple>): Boolean =
        explorationService.startCaveExploration(cave, selectedDisciples)

    fun recallCaveExplorationTeam(teamId: String): Boolean =
        explorationService.recallCaveExplorationTeam(teamId)

    fun startScoutMission(
        memberIds: List<String>,
        targetSect: WorldSect,
        currentYear: Int,
        currentMonth: Int,
        currentDay: Int
    ) = explorationService.startScoutMission(memberIds, targetSect, currentYear, currentMonth, currentDay)

    fun getActiveExplorationTeamsCount(): Int = explorationService.getActiveExplorationTeamsCount()

    fun getActiveCaveExplorationTeamsCount(): Int = explorationService.getActiveCaveExplorationTeamsCount()

    fun addEvent(message: String, type: EventType = EventType.INFO) = eventService.addGameEvent(message, type)

    fun clearEvents() = eventService.clearEvents()

    fun getRecentEvents(count: Int = 20): List<GameEvent> = eventService.getRecentEvents(count)

    fun generateSectTradeItems(year: Int): List<MerchantItem> =
        eventService.generateSectTradeItems(year)

    fun getOrRefreshSectTradeItems(sectId: String): List<MerchantItem> =
        eventService.getOrRefreshSectTradeItems(sectId)

    fun buyFromSectTrade(sectId: String, itemId: String, quantity: Int = 1) =
        eventService.buyFromSectTrade(sectId, itemId, quantity)

    suspend fun buyFromSectTradeSync(sectId: String, itemId: String, quantity: Int = 1) =
        eventService.buyFromSectTradeSync(sectId, itemId, quantity)

    suspend fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) =
        buildingService.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)

    suspend fun removeDiscipleFromBuilding(buildingId: String, slotIndex: Int) =
        buildingService.removeDiscipleFromBuilding(buildingId, slotIndex)

    fun getBuildingSlots(buildingId: String): List<BuildingSlot> =
        buildingService.getBuildingSlotsForBuilding(buildingId)

    suspend fun startAlchemy(slotIndex: Int, recipeId: String): Boolean =
        buildingService.startAlchemy(slotIndex, recipeId)

    suspend fun startForging(slotIndex: Int, recipeId: String): Boolean =
        buildingService.startForging(slotIndex, recipeId)

    suspend fun autoHarvestCompletedAlchemySlots(): List<AlchemyResult> =
        buildingService.autoHarvestCompletedAlchemySlots()

    /**
     * Auto-harvest all completed production slots (alchemy, forge, herb garden).
     * This handles slots left in COMPLETED state from old saves.
     */
    private suspend fun checkAndCollectCompletedSlots() {
        // Auto-harvest completed alchemy slots
        autoHarvestCompletedAlchemySlots()

        // Auto-harvest completed forge slots
        val forgeSlots = productionCoordinator.repository.getSlotsByBuildingId("forge")
        forgeSlots.forEach { slot ->
            if (slot.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.COMPLETED) {
                buildingService.autoHarvestForgeSlot(slot)
            }
        }

        // Auto-harvest completed herb garden slots - harvest herbs then reset
        val herbSlots = productionCoordinator.repository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN)
        herbSlots.forEach { slot ->
            if (slot.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.COMPLETED) {
                // Harvest herbs from completed slot (in case auto-harvest was missed)
                harvestHerbFromCompletedSlot(slot)
                buildingService.clearPlantSlot(slot.slotIndex)
            }
        }
    }

    /**
     * Harvest herbs from a completed herb garden slot.
     * This handles the case where auto-harvest in processHerbGardenGrowth was missed
     * (e.g., slot was in COMPLETED state from an old save).
     */
    private fun harvestHerbFromCompletedSlot(slot: com.xianxia.sect.core.model.production.ProductionSlot) {
        val herb = HerbDatabase.getHerbFromSeedName(slot.recipeName)
            ?: slot.recipeId?.let { HerbDatabase.getHerbFromSeed(it) }
            ?: return

        val herbGrowthBonus = if (stateStore.gameData.value.sectPolicies.herbCultivation) GameConfig.PolicyConfig.HERB_CULTIVATION_BASE_EFFECT else 0.0
        val actualYield = HerbGardenSystem.calculateIncreasedYield(slot.expectedYield, herbGrowthBonus)

        val herbItem = Herb(
            name = herb.name,
            rarity = herb.rarity,
            description = herb.description,
            category = herb.category,
            quantity = actualYield
        )
        inventorySystem.addHerb(herbItem)
        eventService.addGameEvent("${herb.name}已成熟，收获${actualYield}个", EventType.SUCCESS)
    }

    fun clearPlantSlot(slotIndex: Int) = buildingService.clearPlantSlot(slotIndex)

    fun getForgeSlots(): List<BuildingSlot> = buildingService.getBuildingSlots()

    fun getStateSnapshotSync(): GameStateSnapshot {
        return GameStateSnapshot(
            gameData = stateStore.gameData.value,
            disciples = stateStore.disciples.value,
            equipmentStacks = stateStore.equipmentStacks.value,
            equipmentInstances = stateStore.equipmentInstances.value,
            manualStacks = stateStore.manualStacks.value,
            manualInstances = stateStore.manualInstances.value,
            pills = stateStore.pills.value,
            materials = stateStore.materials.value,
            herbs = stateStore.herbs.value,
            seeds = stateStore.seeds.value,
            teams = stateStore.teams.value,
            events = stateStore.events.value,
            battleLogs = stateStore.battleLogs.value,
            alliances = stateStore.gameData.value.alliances,
            productionSlots = productionCoordinator.repository.getSlots()
        )
    }

    suspend fun getStateSnapshot(): GameStateSnapshot = getStateSnapshotSuspend()

    suspend fun getStateSnapshotSuspend(): GameStateSnapshot {
        val gd = stateStore.gameData.value
        return GameStateSnapshot(
            gameData = gd,
            disciples = stateStore.disciples.value,
            equipmentStacks = stateStore.equipmentStacks.value,
            equipmentInstances = stateStore.equipmentInstances.value,
            manualStacks = stateStore.manualStacks.value,
            manualInstances = stateStore.manualInstances.value,
            pills = stateStore.pills.value,
            materials = stateStore.materials.value,
            herbs = stateStore.herbs.value,
            seeds = stateStore.seeds.value,
            teams = stateStore.teams.value,
            events = stateStore.events.value,
            battleLogs = stateStore.battleLogs.value,
            alliances = gd.alliances,
            productionSlots = productionCoordinator.repository.getSlots()
        )
    }

    suspend fun loadFromSave(
        loadedGameData: GameData,
        disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack>,
        equipmentInstances: List<EquipmentInstance>,
        manualStacks: List<ManualStack>,
        manualInstances: List<ManualInstance>,
        pills: List<Pill>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>,
        events: List<GameEvent>,
        battleLogs: List<BattleLog>,
        teams: List<ExplorationTeam>
    ) = saveService.loadFromSave(
        loadedGameData, disciples, equipmentStacks, equipmentInstances, manualStacks, manualInstances, pills,
        materials, herbs, seeds, events, battleLogs, teams
    )

    fun validateState(): List<String> = saveService.validateState()

    fun getStateStatistics(): Map<String, Any> = saveService.getStateStatistics()

    fun isGameStarted(): Boolean = saveService.isGameStarted()

    fun getFormattedGameTime(): String = saveService.getFormattedGameTime()

    fun getMemoryUsageInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 内存使用情况 ===")
        sb.appendLine("弟子数量: ${stateStore.disciples.value.size}")
        sb.appendLine("装备栈数量: ${stateStore.equipmentStacks.value.size}")
        sb.appendLine("装备实例数量: ${stateStore.equipmentInstances.value.size}")
        sb.appendLine("功法栈数量: ${stateStore.manualStacks.value.size}")
        sb.appendLine("功法实例数量: ${stateStore.manualInstances.value.size}")
        sb.appendLine("丹药数量: ${stateStore.pills.value.size}")
        sb.appendLine("材料数量: ${stateStore.materials.value.size}")
        sb.appendLine("灵草数量: ${stateStore.herbs.value.size}")
        sb.appendLine("种子数量: ${stateStore.seeds.value.size}")
        sb.appendLine("探索队伍: ${stateStore.teams.value.size}")
        sb.appendLine("事件记录: ${stateStore.events.value.size}/50")
        sb.appendLine("战斗日志: ${stateStore.battleLogs.value.size}/50")
        return sb.toString()
    }

    @Suppress("DEPRECATION")
    fun releaseMemory(level: Int) {
        val normalizedLevel = when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            1 -> 1
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            2 -> 2
            else -> {
                Log.w(TAG, "未知的内存压力级别: $level，忽略")
                return
            }
        }

        val eventsToKeep = if (normalizedLevel == 1) 20 else 10
        val logsToKeep = if (normalizedLevel == 1) 20 else 10
        val levelName = if (normalizedLevel == 1) "MODERATE" else "CRITICAL"

        gameEngineCore.launchInScope {
            stateStore.update {
                if (events.size > eventsToKeep) {
                    events = events.take(eventsToKeep)
                    Log.d(TAG, "内存释放($levelName): 事件列表已清理，保留最近$eventsToKeep 条")
                }
                if (battleLogs.size > logsToKeep) {
                    battleLogs = battleLogs.take(logsToKeep)
                    Log.d(TAG, "内存释放($levelName): 战斗日志已清理，保留最近$logsToKeep 条")
                }

                if (normalizedLevel == 2) {
                    var trimmed = false

                    val worldSects = gameData.worldMapSects
                    if (worldSects.size > 30) {
                        val playerSect = worldSects.find { it.isPlayerSect }
                        val otherSects = worldSects.filter { !it.isPlayerSect }.sortedByDescending { s -> s.relation }.take(if (playerSect != null) 29 else 30)
                        val trimmedSects = if (playerSect != null) listOf(playerSect) + otherSects else otherSects
                        gameData = gameData.copy(worldMapSects = trimmedSects)
                        trimmed = true
                        Log.d(TAG, "内存释放($levelName): worldMapSects 裁剪至 ${trimmedSects.size} 个")
                    }

                    val aiTeams = gameData.aiBattleTeams
                    if (aiTeams.size > 20) {
                        gameData = gameData.copy(aiBattleTeams = aiTeams.take(20))
                        trimmed = true
                        Log.d(TAG, "内存释放($levelName): aiBattleTeams 裁剪至 20 个")
                    }

                    val caveTeams = gameData.caveExplorationTeams
                    if (caveTeams.size > 15) {
                        gameData = gameData.copy(caveExplorationTeams = caveTeams.take(15))
                        trimmed = true
                        Log.d(TAG, "内存释放($levelName): caveExplorationTeams 裁剪至 15 个")
                    }

                    val aiCaveTeams = gameData.aiCaveTeams
                    if (aiCaveTeams.size > 15) {
                        gameData = gameData.copy(aiCaveTeams = aiCaveTeams.take(15))
                        trimmed = true
                        Log.d(TAG, "内存释放($levelName): aiCaveTeams 裁剪至 15 个")
                    }

                    if (!trimmed) {
                        Log.d(TAG, "内存释放($levelName): 无需裁剪其他列表")
                    }
                }
            }
        }
    }

    suspend fun processSecondTick() {
        cultivationService.updateRealtimeCultivation(System.currentTimeMillis())
    }

    fun resetCultivationTimer() {
        cultivationService.resetHighFrequencyData()
    }

    suspend fun dismissDisciple(discipleId: String) {
        expelDisciple(discipleId)
    }

    fun giveItemToDisciple(discipleId: String, itemId: String, itemType: String) {
        when (itemType) {
            "pill" -> usePill(discipleId, itemId)
        }
    }

    fun assignManual(discipleId: String, stackId: String) {
        gameEngineCore.launchInScope { learnManual(discipleId, stackId) }
    }

    fun removeManual(discipleId: String, instanceId: String) {
        gameEngineCore.launchInScope { forgetManual(discipleId, instanceId) }
    }

    fun giftSpiritStones(sectId: String, tier: Int): DiplomacyService.GiftResult =
        diplomacyService.giftSpiritStones(sectId, tier)

    fun giftItem(sectId: String, itemId: String, itemType: String, quantity: Int): DiplomacyService.GiftResult =
        diplomacyService.giftItem(sectId, itemId, itemType, quantity)

    fun requestAlliance(sectId: String, envoyDiscipleId: String): Pair<Boolean, String> =
        diplomacyService.requestAlliance(sectId, envoyDiscipleId)

    fun dissolveAlliance(sectId: String): Pair<Boolean, String> =
        diplomacyService.dissolveAlliance(sectId)

    fun getRejectProbability(sectLevel: Int, rarity: Int): Int =
        diplomacyService.getRejectProbability(sectLevel, rarity)

    fun checkAllianceConditions(sectId: String, envoyDiscipleId: String): Triple<Boolean, String, Int> =
        diplomacyService.checkAllianceConditions(sectId, envoyDiscipleId)

    fun calculatePersuasionSuccessRate(favorability: Int, intelligence: Int, charm: Int): Double =
        diplomacyService.calculatePersuasionSuccessRate(favorability, intelligence, charm)

    fun getEnvoyRealmRequirement(sectLevel: Int): Int =
        diplomacyService.getEnvoyRealmRequirement(sectLevel)

    fun getAllianceCost(sectLevel: Int): Long =
        diplomacyService.getAllianceCost(sectLevel)

    suspend fun redeemCode(
        code: String,
        usedCodes: List<String>,
        currentYear: Int,
        currentMonth: Int
    ): RedeemResult = redeemCodeService.redeemCode(code, usedCodes, currentYear, currentMonth)

    fun calculateSuccessRateBonus(disciple: Disciple?, buildingId: String): Double =
        formulaService.calculateSuccessRateBonus(disciple, buildingId)

    fun calculateWorkDurationWithAllDisciples(baseDuration: Int, buildingId: String): Int =
        formulaService.calculateWorkDurationWithAllDisciples(baseDuration, buildingId)

    fun calculateElderAndDisciplesBonus(buildingType: String): FormulaService.ElderBonusData =
        formulaService.calculateElderAndDisciplesBonus(buildingType)

    fun interactWithSect(sectId: String, action: String) {
        Log.w(TAG, "interactWithSect 尚未实现: sectId=$sectId, action=$action")
    }

    fun isAlly(sectId: String): Boolean {
        val data = stateStore.gameData.value
        return data.alliances.any { it.sectIds.contains("player") && it.sectIds.contains(sectId) }
    }

    fun getAllianceRemainingYears(sectId: String): Int {
        val data = stateStore.gameData.value
        val alliance = data.alliances.find { it.sectIds.contains("player") && it.sectIds.contains(sectId) } ?: return 0
        val sect = data.worldMapSects.find { it.id == sectId }
        val startYear = if (sect?.allianceStartYear != null && sect.allianceStartYear > 0) {
            sect.allianceStartYear
        } else {
            alliance.startYear
        }
        val elapsed = data.gameYear - startYear
        return (GameConfig.Diplomacy.ALLIANCE_DURATION_YEARS - elapsed).coerceAtLeast(0)
    }

    fun getPlayerAllies(): List<String> {
        val data = stateStore.gameData.value
        val playerAlliance = data.alliances.find { it.sectIds.contains("player") } ?: return emptyList()
        return playerAlliance.sectIds.filter { it != "player" }
    }

    fun updateElderSlots(newElderSlots: ElderSlots) {
        gameEngineCore.launchInScope {
            stateStore.update { gameData = gameData.copy(elderSlots = newElderSlots) }
            discipleService.syncAllDiscipleStatuses()
        }
    }

    fun assignDirectDisciple(
        elderSlotType: String,
        slotIndex: Int,
        discipleId: String,
        discipleName: String,
        discipleRealm: String,
        discipleSpiritRootColor: String
    ) {
        val data = stateStore.gameData.value
        val slots = data.elderSlots
        val newSlot = DirectDiscipleSlot(
            index = slotIndex,
            discipleId = discipleId,
            discipleName = discipleName,
            discipleRealm = discipleRealm,
            discipleSpiritRootColor = discipleSpiritRootColor
        )
        val updatedSlots = when (elderSlotType) {
            "herbGarden" -> {
                val list = slots.herbGardenDisciples.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(herbGardenDisciples = list)
            }
            "alchemy" -> {
                val list = slots.alchemyDisciples.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(alchemyDisciples = list)
            }
            "forge" -> {
                val list = slots.forgeDisciples.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(forgeDisciples = list)
            }
            "preaching" -> {
                val list = slots.preachingMasters.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(preachingMasters = list)
            }
            "lawEnforcement" -> {
                val list = slots.lawEnforcementDisciples.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(lawEnforcementDisciples = list)
            }
            "lawEnforcementReserve" -> {
                val list = slots.lawEnforcementReserveDisciples.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(lawEnforcementReserveDisciples = list)
            }
            "qingyunPreaching" -> {
                val list = slots.qingyunPreachingMasters.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(qingyunPreachingMasters = list)
            }
            "spiritMineDeacon" -> {
                val list = slots.spiritMineDeaconDisciples.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(spiritMineDeaconDisciples = list)
            }
            else -> slots
        }
        gameEngineCore.launchInScope {
            stateStore.update { gameData = gameData.copy(elderSlots = updatedSlots) }
            discipleService.syncAllDiscipleStatuses()
            if (elderSlotType == "lawEnforcement") {
                discipleService.autoFillLawEnforcementSlots()
            }
        }
    }

    fun removeDirectDisciple(elderSlotType: String, slotIndex: Int) {
        val slots = stateStore.gameData.value.elderSlots
        val updatedSlots = when (elderSlotType) {
            "herbGarden" -> {
                val list = slots.herbGardenDisciples.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(herbGardenDisciples = list)
            }
            "alchemy" -> {
                val list = slots.alchemyDisciples.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(alchemyDisciples = list)
            }
            "forge" -> {
                val list = slots.forgeDisciples.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(forgeDisciples = list)
            }
            "preaching" -> {
                val list = slots.preachingMasters.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(preachingMasters = list)
            }
            "lawEnforcement" -> {
                val list = slots.lawEnforcementDisciples.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(lawEnforcementDisciples = list)
            }
            "lawEnforcementReserve" -> {
                val list = slots.lawEnforcementReserveDisciples.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(lawEnforcementReserveDisciples = list)
            }
            "qingyunPreaching" -> {
                val list = slots.qingyunPreachingMasters.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(qingyunPreachingMasters = list)
            }
            "spiritMineDeacon" -> {
                val list = slots.spiritMineDeaconDisciples.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(spiritMineDeaconDisciples = list)
            }
            else -> slots
        }
        gameEngineCore.launchInScope {
            stateStore.update { gameData = gameData.copy(elderSlots = updatedSlots) }
            discipleService.syncAllDiscipleStatuses()
            if (elderSlotType == "lawEnforcement") {
                discipleService.autoFillLawEnforcementSlots()
            }
        }
    }

    suspend fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        stateStore.update {
            disciples = disciples.map {
                if (it.id == discipleId) it.copy(status = status) else it
            }
        }
    }

    fun validateAndFixSpiritMineData() {
        val unified = stateStore.unifiedState.value
        val data = unified.gameData
        val discipleMap = unified.disciples.associateBy { it.id }
        val fixedSlots = data.spiritMineSlots.map { slot ->
            if (slot.discipleId.isNotEmpty() && (slot.discipleId !in discipleMap || discipleMap[slot.discipleId]?.discipleType != "outer")) {
                slot.copy(discipleId = "", discipleName = "")
            } else slot
        }
        if (fixedSlots != data.spiritMineSlots) {
            updateGameDataSync { it.copy(spiritMineSlots = fixedSlots) }
        }
    }

    fun updateSpiritMineSlots(slots: List<SpiritMineSlot>) {
        updateGameDataSync { it.copy(spiritMineSlots = slots) }
    }

    fun assignDiscipleToLibrarySlot(slotIndex: Int, discipleId: String, discipleName: String) {
        val data = stateStore.gameData.value
        val slots = data.librarySlots.toMutableList()
        while (slots.size <= slotIndex) {
            slots.add(LibrarySlot(index = slots.size))
        }
        slots[slotIndex] = LibrarySlot(
            index = slotIndex,
            discipleId = discipleId,
            discipleName = discipleName
        )
        gameEngineCore.launchInScope {
            stateStore.update { gameData = gameData.copy(librarySlots = slots) }
            discipleService.syncAllDiscipleStatuses()
        }
    }

    fun removeDiscipleFromLibrarySlot(slotIndex: Int) {
        val data = stateStore.gameData.value
        if (slotIndex < 0 || slotIndex >= data.librarySlots.size) return
        val slots = data.librarySlots.toMutableList()
        slots[slotIndex] = LibrarySlot(index = slotIndex)
        gameEngineCore.launchInScope {
            stateStore.update { gameData = gameData.copy(librarySlots = slots) }
            discipleService.syncAllDiscipleStatuses()
        }
    }

    fun clearAlchemySlot(slotIndex: Int) {
        if (slotIndex < 0) return
        gameEngineCore.launchInScope {
            productionCoordinator.resetSlotByBuildingIdAtomic("alchemy", slotIndex)
        }
    }

    fun clearForgeSlot(slotIndex: Int) {
        gameEngineCore.launchInScope {
            val slot = productionCoordinator.repository.getSlotByBuildingId("forge", slotIndex)
            if (slot != null && !slot.isWorking) {
                slot.assignedDiscipleId?.let { discipleId ->
                    updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
                }
            }
            productionCoordinator.resetSlotByBuildingIdAtomic("forge", slotIndex)
        }
    }

    suspend fun startManualPlanting(slotIndex: Int, seedId: String) {
        val seed = stateStore.getCurrentSeeds().find { it.id == seedId } ?: return
        if (seed.quantity <= 0) return

        val data = stateStore.gameData.value
        val existingSlot = productionCoordinator.repository.getSlotByBuildingId("herbGarden", slotIndex)

        // If existing slot is COMPLETED (e.g., from old save where auto-harvest was missed),
        // harvest it first before planting new seed to prevent losing yield
        if (existingSlot != null && existingSlot.isCompleted) {
            harvestHerbFromCompletedSlot(existingSlot)
        }

        val herbDbSeedId = HerbDatabase.getSeedByName(seed.name)?.id
        val herbId = herbDbSeedId?.let { HerbDatabase.getHerbIdFromSeedId(it) }
        val newSlot = com.xianxia.sect.core.model.production.ProductionSlot(
            id = existingSlot?.id ?: java.util.UUID.randomUUID().toString(),
            slotIndex = slotIndex,
            buildingType = com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN,
            buildingId = "herbGarden",
            status = com.xianxia.sect.core.model.production.ProductionSlotStatus.WORKING,
            recipeId = herbDbSeedId ?: seedId,
            recipeName = seed.name,
            startYear = data.gameYear,
            startMonth = data.gameMonth,
            duration = seed.growTime,
            outputItemId = herbId ?: "",
            outputItemName = seed.name,
            expectedYield = seed.yield
        )

        if (existingSlot != null) {
            productionCoordinator.repository.updateSlotByBuildingId("herbGarden", slotIndex) { newSlot }
        } else {
            productionCoordinator.repository.addSlot(newSlot)
        }

        inventorySystem.removeSeedSync(seedId, 1)
    }

    fun recruitDiscipleFromList(discipleId: String) {
        val data = stateStore.gameData.value
        val disciple = data.recruitList.find { it.id == discipleId } ?: return
        val currentMonthValue = data.gameYear * 12 + data.gameMonth
        val recruitedDisciple = disciple.copyWith(recruitedMonth = currentMonthValue)
        gameEngineCore.launchInScope {
            stateStore.update {
                disciples = disciples + recruitedDisciple
                gameData = gameData.copy(recruitList = gameData.recruitList.filter { it.id != discipleId })
            }
        }
    }

    suspend fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>) {
        val data = stateStore.gameData.value
        items.forEach { item ->
            val quantity = item.quantity.coerceAtLeast(1)
            when (item.type.lowercase(java.util.Locale.getDefault())) {
                "equipment" -> {
                    stateStore.update {
                        val stack = equipmentStacks.find { it.id == item.id }
                        if (stack == null || stack.quantity < 1) return@update

                        val disciple = disciples.find { it.id == discipleId }
                        if (disciple == null) return@update

                        val bagStackIds = disciples.flatMap { it.storageBagItems }
                            .filter { it.itemType == "equipment_stack" }
                            .map { it.itemId }
                            .toSet()

                        val canEquip = GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm)

                        if (canEquip) {
                            val slot = stack.slot
                            val oldEquipId = when (slot) {
                                EquipmentSlot.WEAPON -> disciple.weaponId
                                EquipmentSlot.ARMOR -> disciple.armorId
                                EquipmentSlot.BOOTS -> disciple.bootsId
                                EquipmentSlot.ACCESSORY -> disciple.accessoryId
                                else -> ""
                            }

                            var updatedDisciple = disciple

                            if (oldEquipId.isNotEmpty()) {
                                val oldInstance = equipmentInstances.find { it.id == oldEquipId }
                                if (oldInstance != null) {
                                    val existingStack = equipmentStacks.find {
                                        it.name == oldInstance.name && it.rarity == oldInstance.rarity && it.slot == oldInstance.slot && it.id != stack.id && it.id in bagStackIds
                                    }
                                    val storageItemId: String
                                    if (existingStack != null) {
                                        val maxStack = inventoryConfig.getMaxStackSize("equipment_stack")
                                        val newQty = (existingStack.quantity + 1).coerceAtMost(maxStack)
                                        equipmentStacks = equipmentStacks.map { s ->
                                            if (s.id == existingStack.id) s.copy(quantity = newQty) else s
                                        }
                                        storageItemId = existingStack.id
                                    } else {
                                        val newStack = oldInstance.toStack(quantity = 1)
                                        equipmentStacks = equipmentStacks + newStack
                                        storageItemId = newStack.id
                                    }
                                    equipmentInstances = equipmentInstances.filter { it.id != oldEquipId }

                                    updatedDisciple = updatedDisciple.copyWith(
                                        storageBagItems = StorageBagUtils.increaseItemQuantity(
                                            updatedDisciple.storageBagItems,
                                            StorageBagItem(
                                                itemId = storageItemId,
                                                itemType = "equipment_stack",
                                                name = oldInstance.name,
                                                rarity = oldInstance.rarity,
                                                quantity = 1,
                                                obtainedYear = gameData.gameYear,
                                                obtainedMonth = gameData.gameMonth
                                            )
                                        )
                                    )
                                }

                                updatedDisciple = when (slot) {
                                    EquipmentSlot.WEAPON -> updatedDisciple.copyWith(weaponId = "")
                                    EquipmentSlot.ARMOR -> updatedDisciple.copyWith(armorId = "")
                                    EquipmentSlot.BOOTS -> updatedDisciple.copyWith(bootsId = "")
                                    EquipmentSlot.ACCESSORY -> updatedDisciple.copyWith(accessoryId = "")
                                    else -> updatedDisciple
                                }
                            }

                            if (stack.quantity > 1) {
                                equipmentStacks = equipmentStacks.map { s ->
                                    if (s.id == item.id) s.copy(quantity = s.quantity - 1) else s
                                }
                            } else {
                                equipmentStacks = equipmentStacks.filter { it.id != item.id }
                            }

                            val instanceId = java.util.UUID.randomUUID().toString()
                            val instance = stack.toInstance(id = instanceId, ownerId = discipleId, isEquipped = true)
                            equipmentInstances = equipmentInstances + instance

                            updatedDisciple = when (slot) {
                                EquipmentSlot.WEAPON -> updatedDisciple.copyWith(weaponId = instanceId)
                                EquipmentSlot.ARMOR -> updatedDisciple.copyWith(armorId = instanceId)
                                EquipmentSlot.BOOTS -> updatedDisciple.copyWith(bootsId = instanceId)
                                EquipmentSlot.ACCESSORY -> updatedDisciple.copyWith(accessoryId = instanceId)
                                else -> updatedDisciple
                            }

                            disciples = disciples.map { if (it.id == discipleId) updatedDisciple else it }

                            eventService.addGameEvent("${disciple.name} 装备了 ${stack.name}", EventType.INFO)
                        } else {
                            if (stack.quantity > 1) {
                                equipmentStacks = equipmentStacks.map { s ->
                                    if (s.id == item.id) s.copy(quantity = s.quantity - 1) else s
                                }
                            } else {
                                equipmentStacks = equipmentStacks.filter { it.id != item.id }
                            }

                            val bagStackId: String
                            val existingBagStack = equipmentStacks.find {
                                it.name == stack.name && it.rarity == stack.rarity && it.slot == stack.slot && it.id != item.id && it.id in bagStackIds
                            }
                            if (existingBagStack != null) {
                                val maxStack = inventoryConfig.getMaxStackSize("equipment_stack")
                                val newQty = (existingBagStack.quantity + 1).coerceAtMost(maxStack)
                                equipmentStacks = equipmentStacks.map { s ->
                                    if (s.id == existingBagStack.id) s.copy(quantity = newQty) else s
                                }
                                bagStackId = existingBagStack.id
                            } else {
                                val newStack = stack.copy(id = java.util.UUID.randomUUID().toString(), quantity = 1)
                                equipmentStacks = equipmentStacks + newStack
                                bagStackId = newStack.id
                            }

                            disciples = disciples.map { d ->
                                if (d.id == discipleId) {
                                    d.copyWith(
                                        storageBagItems = StorageBagUtils.increaseItemQuantity(
                                            d.storageBagItems,
                                            StorageBagItem(
                                                itemId = bagStackId,
                                                itemType = "equipment_stack",
                                                name = stack.name,
                                                rarity = stack.rarity,
                                                quantity = 1,
                                                obtainedYear = gameData.gameYear,
                                                obtainedMonth = gameData.gameMonth
                                            )
                                        )
                                    )
                                } else d
                            }

                            eventService.addGameEvent("${disciple.name} 境界不足，${stack.name}已放入储物袋", EventType.WARNING)
                        }
                    }
                }
                "manual" -> {
                    val manualStack = stateStore.manualStacks.value.find { it.id == item.id }
                    if (manualStack != null) {
                        learnManual(discipleId, item.id)
                        val updatedDisciple = stateStore.disciples.value.find { it.id == discipleId }
                        val wasLearned = updatedDisciple?.manualIds?.any { mid ->
                            stateStore.manualInstances.value.find { m -> m.id == mid }?.name == manualStack.name
                        } == true
                        if (!wasLearned) {
                            updateDisciple(discipleId) { disciple ->
                                disciple.copyWith(
                                    storageBagItems = StorageBagUtils.increaseItemQuantity(
                                        disciple.storageBagItems,
                                        StorageBagItem(
                                            itemId = item.id,
                                            itemType = "manual_stack",
                                            name = manualStack.name,
                                            rarity = manualStack.rarity,
                                            quantity = 1,
                                            obtainedYear = data.gameYear,
                                            obtainedMonth = data.gameMonth
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
                "pill" -> {
                    val pill = stateStore.pills.value.find { it.id == item.id }
                    if (pill != null && pill.quantity >= quantity) {
                        val disciple = stateStore.disciples.value.find { it.id == discipleId }
                        val pillItem = StorageBagItem(
                            itemId = item.id,
                            itemType = "pill",
                            name = pill.name,
                            rarity = pill.rarity,
                            quantity = quantity,
                            obtainedYear = data.gameYear,
                            obtainedMonth = data.gameMonth,
                            effect = DisciplePillManager.pillToItemEffect(pill),
                            grade = pill.grade.displayName
                        )
                        val canUse = disciple != null && DisciplePillManager.canUsePill(disciple, pillItem).canUse

                        if (canUse) {
                            repeat(quantity) { usePill(discipleId, item.id) }
                        } else {
                            if (inventorySystem.removePill(item.id, quantity, bypassLock = true)) {
                                updateDisciple(discipleId) { d ->
                                    d.copyWith(
                                        storageBagItems = StorageBagUtils.increaseItemQuantity(
                                            d.storageBagItems,
                                            pillItem
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                "material" -> {
                    if (inventorySystem.removeMaterial(item.id, quantity, bypassLock = true)) {
                        updateDisciple(discipleId) { disciple ->
                            disciple.copyWith(
                                storageBagItems = StorageBagUtils.increaseItemQuantity(
                                    disciple.storageBagItems,
                                    StorageBagItem(
                                        itemId = item.id,
                                        itemType = "material",
                                        name = item.name,
                                        rarity = item.rarity,
                                        quantity = quantity,
                                        obtainedYear = data.gameYear,
                                        obtainedMonth = data.gameMonth
                                    )
                                )
                            )
                        }
                    }
                }
                "herb" -> {
                    if (inventorySystem.removeHerb(item.id, quantity, bypassLock = true)) {
                        updateDisciple(discipleId) { disciple ->
                            disciple.copyWith(
                                storageBagItems = StorageBagUtils.increaseItemQuantity(
                                    disciple.storageBagItems,
                                    StorageBagItem(
                                        itemId = item.id,
                                        itemType = "herb",
                                        name = item.name,
                                        rarity = item.rarity,
                                        quantity = quantity,
                                        obtainedYear = data.gameYear,
                                        obtainedMonth = data.gameMonth
                                    )
                                )
                            )
                        }
                    }
                }
                "seed" -> {
                    if (inventorySystem.removeSeed(item.id, quantity, bypassLock = true)) {
                        updateDisciple(discipleId) { disciple ->
                            disciple.copyWith(
                                storageBagItems = StorageBagUtils.increaseItemQuantity(
                                    disciple.storageBagItems,
                                    StorageBagItem(
                                        itemId = item.id,
                                        itemType = "seed",
                                        name = item.name,
                                        rarity = item.rarity,
                                        quantity = quantity,
                                        obtainedYear = data.gameYear,
                                        obtainedMonth = data.gameMonth
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun recruitAllFromList(): Boolean {
        val data = stateStore.gameData.value
        if (data.recruitList.isEmpty()) return false
        val currentMonthValue = data.gameYear * 12 + data.gameMonth
        val recruitedDisciples = data.recruitList.map { it.copyWith(recruitedMonth = currentMonthValue) }
        gameEngineCore.launchInScope {
            stateStore.update {
                disciples = disciples + recruitedDisciples
                gameData = gameData.copy(recruitList = emptyList())
            }
        }
        return true
    }

    fun removeFromRecruitList(discipleId: String) {
        val data = stateStore.gameData.value
        updateGameDataSync { it.copy(recruitList = data.recruitList.filter { it.id != discipleId }) }
    }

    fun equipItem(discipleId: String, equipmentId: String) {
        discipleService.equipEquipment(discipleId, equipmentId)
    }

    fun unequipItem(discipleId: String, slot: EquipmentSlot) {
        discipleService.unequipEquipment(discipleId, equipmentId = slot.name)
    }

    fun unequipItemById(discipleId: String, equipmentId: String) {
        discipleService.unequipEquipment(discipleId, equipmentId)
    }

    suspend fun forgetManual(discipleId: String, instanceId: String) {
        val disciple = getDiscipleById(discipleId) ?: return
        val instance = stateStore.manualInstances.value.find { it.id == instanceId } ?: return
        val data = stateStore.gameData.value
        stateStore.update {
            val existingStack = manualStacks.find {
                it.name == instance.name && it.rarity == instance.rarity && it.type == instance.type
            }
            if (existingStack != null) {
                manualStacks = manualStacks.map {
                    if (it.id == existingStack.id) it.copy(quantity = it.quantity + 1) else it
                }
                disciples = disciples.map {
                    if (it.id == discipleId) {
                        val storageItem = StorageBagItem(
                            itemId = existingStack.id,
                            itemType = "manual_stack",
                            name = instance.name,
                            rarity = instance.rarity,
                            quantity = 1,
                            obtainedYear = data.gameYear,
                            obtainedMonth = data.gameMonth
                        )
                        it.copyWith(
                            manualIds = it.manualIds.filter { mid -> mid != instanceId },
                            storageBagItems = StorageBagUtils.increaseItemQuantity(it.storageBagItems, storageItem)
                        )
                    } else it
                }
            } else {
                val newStack = instance.toStack(quantity = 1)
                manualStacks = manualStacks + newStack
                disciples = disciples.map {
                    if (it.id == discipleId) {
                        val storageItem = StorageBagItem(
                            itemId = newStack.id,
                            itemType = "manual_stack",
                            name = instance.name,
                            rarity = instance.rarity,
                            quantity = 1,
                            obtainedYear = data.gameYear,
                            obtainedMonth = data.gameMonth
                        )
                        it.copyWith(
                            manualIds = it.manualIds.filter { mid -> mid != instanceId },
                            storageBagItems = StorageBagUtils.increaseItemQuantity(it.storageBagItems, storageItem)
                        )
                    } else it
                }
            }
            manualInstances = manualInstances.filter { it.id != instanceId }
            val updatedProficiencies = gameData.manualProficiencies.toMutableMap()
            updatedProficiencies[discipleId]?.let { profList ->
                val filtered = profList.filter { it.manualId != instanceId }
                if (filtered.isEmpty()) {
                    updatedProficiencies.remove(discipleId)
                } else {
                    updatedProficiencies[discipleId] = filtered
                }
            }
            gameData = gameData.copy(manualProficiencies = updatedProficiencies)
        }
    }

    fun replaceManual(discipleId: String, oldInstanceId: String, newStackId: String) {
        gameEngineCore.launchInScope {
            val oldInstance = stateStore.manualInstances.value.find { it.id == oldInstanceId }
            val newStack = stateStore.manualStacks.value.find { it.id == newStackId }
            val disciple = stateStore.disciples.value.find { it.id == discipleId }

            val blocked = newStack?.type == ManualType.MIND && oldInstance?.type != ManualType.MIND && disciple != null && disciple.manualIds
                .filter { it != oldInstanceId }
                .any { mid -> stateStore.manualInstances.value.find { m -> m.id == mid }?.type == ManualType.MIND }

            if (!blocked) {
                forgetManual(discipleId, oldInstanceId)
                learnManual(discipleId, newStackId)
            }
        }
    }

    suspend fun learnManual(discipleId: String, stackId: String) {
        stateStore.update {
            val stack = manualStacks.find { it.id == stackId } ?: return@update
            val disciple = disciples.find { it.id == discipleId } ?: return@update

            if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm)) return@update

            if (stack.type == ManualType.MIND) {
                val hasMind = disciple.manualIds.any { mid ->
                    manualInstances.find { it.id == mid }?.type == ManualType.MIND
                }
                if (hasMind) return@update
            }

            val newQty = stack.quantity - 1
            if (newQty <= 0) {
                manualStacks = manualStacks.filter { it.id != stackId }
            } else {
                manualStacks = manualStacks.map {
                    if (it.id == stackId) it.copy(quantity = newQty) else it
                }
            }

            val instanceId = java.util.UUID.randomUUID().toString()
            val instance = stack.toInstance(id = instanceId, ownerId = discipleId, isLearned = true)
            manualInstances = manualInstances + instance

            disciples = disciples.map {
                if (it.id == discipleId && !it.manualIds.contains(instanceId)) {
                    it.copy(manualIds = it.manualIds + instanceId)
                } else it
            }
        }
    }

    suspend fun buyMerchantItem(itemId: String, quantity: Int) {
        val merchantItem = stateStore.gameData.value.travelingMerchantItems.find { it.id == itemId } ?: return
        val cost = merchantItem.price.toLong() * quantity
        if (stateStore.gameData.value.spiritStones < cost || quantity > merchantItem.quantity) return

        when (merchantItem.type.lowercase(java.util.Locale.getDefault())) {
            "equipment" -> {
                val eq = MerchantItemConverter.toEquipment(merchantItem)
                if (!inventorySystem.canAddEquipment(eq.name, eq.rarity, eq.slot)) return
            }
            "manual" -> {
                val m = MerchantItemConverter.toManual(merchantItem)
                if (!inventorySystem.canAddManual(m.name, m.rarity, m.type)) return
            }
            "pill" -> {
                val p = MerchantItemConverter.toPill(merchantItem)
                if (!inventorySystem.canAddPill(p.name, p.rarity, p.category)) return
            }
            "material" -> {
                val m = MerchantItemConverter.toMaterial(merchantItem)
                if (!inventorySystem.canAddMaterial(m.name, m.rarity, m.category)) return
            }
            "herb" -> {
                val h = MerchantItemConverter.toHerb(merchantItem)
                if (!inventorySystem.canAddHerb(h.name, h.rarity, h.category)) return
            }
            "seed" -> {
                val s = MerchantItemConverter.toSeed(merchantItem)
                if (!inventorySystem.canAddSeed(s.name, s.rarity, s.growTime)) return
            }
        }

        stateStore.update {
            gameData = gameData.copy(
                spiritStones = gameData.spiritStones - cost,
                travelingMerchantItems = gameData.travelingMerchantItems.map { item ->
                    if (item.id == itemId) {
                        if (quantity >= item.quantity) null else item.copy(quantity = item.quantity - quantity)
                    } else item
                }.filterNotNull()
            )

            when (merchantItem.type.lowercase(java.util.Locale.getDefault())) {
                "equipment" -> {
                    val stack = MerchantItemConverter.toEquipment(merchantItem).copy(quantity = quantity)
                    val existing = equipmentStacks.find { it.name == stack.name && it.rarity == stack.rarity && it.slot == stack.slot }
                    if (existing != null) {
                        equipmentStacks = equipmentStacks.map { if (it.id == existing.id) it.copy(quantity = it.quantity + stack.quantity) else it }
                    } else {
                        equipmentStacks = equipmentStacks + stack
                    }
                }
                "manual" -> {
                    val stack = MerchantItemConverter.toManual(merchantItem).copy(quantity = quantity)
                    val existing = manualStacks.find { it.name == stack.name && it.rarity == stack.rarity && it.type == stack.type }
                    if (existing != null) {
                        manualStacks = manualStacks.map { if (it.id == existing.id) it.copy(quantity = it.quantity + stack.quantity) else it }
                    } else {
                        manualStacks = manualStacks + stack
                    }
                }
                "pill" -> {
                    val p = MerchantItemConverter.toPill(merchantItem).copy(quantity = quantity)
                    val existing = pills.find { it.name == p.name && it.rarity == p.rarity && it.category == p.category && it.grade == p.grade }
                    if (existing != null) {
                        val newQty = (existing.quantity + p.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("pill"))
                        pills = pills.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                    } else {
                        pills = pills + p
                    }
                }
                "material" -> {
                    val m = MerchantItemConverter.toMaterial(merchantItem).copy(quantity = quantity)
                    val existing = materials.find { it.name == m.name && it.rarity == m.rarity && it.category == m.category }
                    if (existing != null) {
                        val newQty = (existing.quantity + m.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("material"))
                        materials = materials.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                    } else {
                        materials = materials + m
                    }
                }
                "herb" -> {
                    val h = MerchantItemConverter.toHerb(merchantItem).copy(quantity = quantity)
                    val existing = herbs.find { it.name == h.name && it.rarity == h.rarity && it.category == h.category }
                    if (existing != null) {
                        val newQty = (existing.quantity + h.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("herb"))
                        herbs = herbs.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                    } else {
                        herbs = herbs + h
                    }
                }
                "seed" -> {
                    val s = MerchantItemConverter.toSeed(merchantItem).copy(quantity = quantity)
                    val existing = seeds.find { it.name == s.name && it.rarity == s.rarity && s.growTime == s.growTime }
                    if (existing != null) {
                        val newQty = (existing.quantity + s.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("seed"))
                        seeds = seeds.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                    } else {
                        seeds = seeds + s
                    }
                }
            }
        }
    }

    suspend fun listItemsToMerchant(items: List<Pair<String, Int>>) {
        val data = stateStore.gameData.value
        val newItems = mutableListOf<MerchantItem>()
        items.forEach { (itemId, quantity) ->
            val eqStack = stateStore.equipmentStacks.value.find { it.id == itemId }
            if (eqStack != null) {
                if (!inventorySystem.removeEquipment(itemId, quantity)) return@forEach
                newItems.add(MerchantItem(
                    id = java.util.UUID.randomUUID().toString(),
                    name = eqStack.name,
                    type = "equipment",
                    itemId = itemId,
                    rarity = eqStack.rarity,
                    price = (eqStack.basePrice * GameConfig.Rarity.SELL_PRICE_MULTIPLIER).toInt(),
                    quantity = quantity
                ))
                return@forEach
            }
            val manualStack = stateStore.manualStacks.value.find { it.id == itemId }
            if (manualStack != null) {
                if (!inventorySystem.removeManual(itemId, quantity)) return@forEach
                newItems.add(MerchantItem(
                    id = java.util.UUID.randomUUID().toString(),
                    name = manualStack.name,
                    type = "manual",
                    itemId = itemId,
                    rarity = manualStack.rarity,
                    price = (manualStack.basePrice * GameConfig.Rarity.SELL_PRICE_MULTIPLIER).toInt(),
                    quantity = quantity
                ))
                return@forEach
            }
            val pill = stateStore.pills.value.find { it.id == itemId }
            if (pill != null) {
                if (!inventorySystem.removePill(itemId, quantity)) return@forEach
                newItems.add(MerchantItem(
                    id = java.util.UUID.randomUUID().toString(),
                    name = pill.name,
                    type = "pill",
                    itemId = itemId,
                    rarity = pill.rarity,
                    price = (pill.basePrice * GameConfig.Rarity.SELL_PRICE_MULTIPLIER).toInt(),
                    quantity = quantity
                ))
                return@forEach
            }
            val material = stateStore.materials.value.find { it.id == itemId }
            if (material != null) {
                if (!inventorySystem.removeMaterial(itemId, quantity)) return@forEach
                newItems.add(MerchantItem(
                    id = java.util.UUID.randomUUID().toString(),
                    name = material.name,
                    type = "material",
                    itemId = itemId,
                    rarity = material.rarity,
                    price = (material.basePrice * GameConfig.Rarity.SELL_PRICE_MULTIPLIER).toInt(),
                    quantity = quantity
                ))
                return@forEach
            }
            val herb = stateStore.herbs.value.find { it.id == itemId }
            if (herb != null) {
                if (!inventorySystem.removeHerb(itemId, quantity)) return@forEach
                newItems.add(MerchantItem(
                    id = java.util.UUID.randomUUID().toString(),
                    name = herb.name,
                    type = "herb",
                    itemId = itemId,
                    rarity = herb.rarity,
                    price = (herb.basePrice * GameConfig.Rarity.SELL_PRICE_MULTIPLIER).toInt(),
                    quantity = quantity
                ))
                return@forEach
            }
            val seed = stateStore.seeds.value.find { it.id == itemId }
            if (seed != null) {
                if (!inventorySystem.removeSeed(itemId, quantity)) return@forEach
                newItems.add(MerchantItem(
                    id = java.util.UUID.randomUUID().toString(),
                    name = seed.name,
                    type = "seed",
                    itemId = itemId,
                    rarity = seed.rarity,
                    price = (seed.basePrice * GameConfig.Rarity.SELL_PRICE_MULTIPLIER).toInt(),
                    quantity = quantity
                ))
                return@forEach
            }
        }
        updateGameData { it.copy(playerListedItems = it.playerListedItems + newItems) }
    }

    suspend fun removePlayerListedItem(itemId: String) {
        val data = stateStore.gameData.value
        val item = data.playerListedItems.find { it.id == itemId } ?: return

        when (item.type.lowercase(java.util.Locale.getDefault())) {
            "equipment" -> stateStore.equipmentStacks.value.find { it.id == item.itemId }?.let {
                inventorySystem.addEquipmentStack(it.copy(quantity = (it.quantity + item.quantity)))
            }
            "manual" -> stateStore.manualStacks.value.find { it.id == item.itemId }?.let {
                inventorySystem.addManualStack(it.copy(quantity = (it.quantity + item.quantity)))
            }
            "pill" -> stateStore.pills.value.find { it.id == item.itemId }?.let {
                stateStore.update { pills = pills.map { p -> if (p.id == item.itemId) p.copy(quantity = (p.quantity + item.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("pill"))) else p } }
            }
            "material" -> stateStore.materials.value.find { it.id == item.itemId }?.let {
                stateStore.update { materials = materials.map { m -> if (m.id == item.itemId) m.copy(quantity = (m.quantity + item.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("material"))) else m } }
            }
            "herb" -> stateStore.herbs.value.find { it.id == item.itemId }?.let {
                stateStore.update { herbs = herbs.map { h -> if (h.id == item.itemId) h.copy(quantity = (h.quantity + item.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("herb"))) else h } }
            }
            "seed" -> stateStore.seeds.value.find { it.id == item.itemId }?.let {
                stateStore.update { seeds = seeds.map { s -> if (s.id == item.itemId) s.copy(quantity = (s.quantity + item.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("seed"))) else s } }
            }
        }
        updateGameData { it.copy(playerListedItems = it.playerListedItems.filter { it.id != itemId }) }
    }

    fun usePill(discipleId: String, pillId: String) {
        gameEngineCore.launchInScope {
            var errorMsg: String? = null
            var successMsg: String? = null
            stateStore.update {
                val pill = pills.find { it.id == pillId } ?: return@update
                if (pill.quantity <= 0) return@update
                val disciple = disciples.find { it.id == discipleId } ?: return@update

                if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, pill.minRealm)) {
                    errorMsg = "弟子${disciple.name}境界不足，无法使用${pill.name}（需要${GameConfig.Realm.getName(pill.minRealm)}及以上）"
                    return@update
                }

                if (pill.cannotStack && disciple.pillEffects.activePillCategory == pill.category.name) {
                    errorMsg = "弟子${disciple.name}已有同类型丹药生效中，无法使用${pill.name}"
                    return@update
                }

                if (pill.category == PillCategory.FUNCTIONAL && pill.pillType.isNotEmpty()) {
                    if (disciple.usage.usedFunctionalPillTypes.contains(pill.pillType)) {
                        errorMsg = "弟子${disciple.name}已使用过${pill.name}，同类功能丹药每人只能使用一次"
                        return@update
                    }
                }

                val updatedPills = pills.map {
                    if (it.id == pillId && it.quantity > 0) it.copy(quantity = it.quantity - 1) else it
                }.filter { it.quantity > 0 }
                pills = updatedPills

                val effect = pill.effect
                var updatedDisciple = disciple

                if (effect.cultivationAdd > 0) {
                    val newCultivation = (updatedDisciple.cultivation + effect.cultivationAdd).coerceAtLeast(0.0)
                    updatedDisciple = updatedDisciple.copy(cultivation = newCultivation)
                }

                if (effect.skillExpAdd > 0) {
                    val updatedMasteries = updatedDisciple.manualMasteries.mapValues { (_, v) ->
                        (v + effect.skillExpAdd).coerceAtMost(10000)
                    }
                    updatedDisciple = updatedDisciple.copy(manualMasteries = updatedMasteries)
                }

                if (effect.cultivationSpeedPercent > 0) {
                    updatedDisciple = updatedDisciple.copy(
                        cultivationSpeedBonus = effect.cultivationSpeedPercent,
                        cultivationSpeedDuration = if (pill.duration > 0) pill.duration else updatedDisciple.cultivationSpeedDuration
                    )
                }

                if (effect.extendLife > 0) {
                    updatedDisciple = updatedDisciple.copy(lifespan = updatedDisciple.lifespan + effect.extendLife)
                    if (!updatedDisciple.usage.usedExtendLifePillIds.contains(pill.pillType)) {
                        updatedDisciple = updatedDisciple.copy(
                            usage = updatedDisciple.usage.copy(
                                usedExtendLifePillIds = updatedDisciple.usage.usedExtendLifePillIds + pill.pillType
                            )
                        )
                    }
                }

                if (effect.intelligenceAdd > 0 || effect.charmAdd > 0 || effect.loyaltyAdd > 0 ||
                    effect.comprehensionAdd > 0 || effect.artifactRefiningAdd > 0 || effect.pillRefiningAdd > 0 ||
                    effect.spiritPlantingAdd > 0 || effect.teachingAdd > 0 || effect.moralityAdd > 0
                ) {
                    updatedDisciple = updatedDisciple.copy(
                        skills = updatedDisciple.skills.copy(
                            intelligence = (updatedDisciple.skills.intelligence + effect.intelligenceAdd).coerceIn(1, 100),
                            charm = (updatedDisciple.skills.charm + effect.charmAdd).coerceIn(1, 100),
                            loyalty = (updatedDisciple.skills.loyalty + effect.loyaltyAdd).coerceIn(1, 100),
                            comprehension = (updatedDisciple.skills.comprehension + effect.comprehensionAdd).coerceIn(1, 100),
                            artifactRefining = (updatedDisciple.skills.artifactRefining + effect.artifactRefiningAdd).coerceIn(1, 100),
                            pillRefining = (updatedDisciple.skills.pillRefining + effect.pillRefiningAdd).coerceIn(1, 100),
                            spiritPlanting = (updatedDisciple.skills.spiritPlanting + effect.spiritPlantingAdd).coerceIn(1, 100),
                            teaching = (updatedDisciple.skills.teaching + effect.teachingAdd).coerceIn(1, 100),
                            morality = (updatedDisciple.skills.morality + effect.moralityAdd).coerceIn(1, 100)
                        )
                    )
                }

                if (pill.category == PillCategory.FUNCTIONAL && pill.pillType.isNotEmpty()) {
                    updatedDisciple = updatedDisciple.copy(
                        usage = updatedDisciple.usage.copy(
                            usedFunctionalPillTypes = updatedDisciple.usage.usedFunctionalPillTypes + pill.pillType
                        )
                    )
                }

                if (effect.physicalAttackAdd > 0 || effect.magicAttackAdd > 0 ||
                    effect.physicalDefenseAdd > 0 || effect.magicDefenseAdd > 0 ||
                    effect.hpAdd > 0 || effect.mpAdd > 0 || effect.speedAdd > 0 ||
                    effect.critRateAdd > 0 || effect.critEffectAdd > 0 ||
                    effect.cultivationSpeedPercent > 0 || effect.skillExpSpeedPercent > 0 || effect.nurtureSpeedPercent > 0
                ) {
                    val newEffects = updatedDisciple.pillEffects.copy(
                        pillPhysicalAttackBonus = effect.physicalAttackAdd,
                        pillMagicAttackBonus = effect.magicAttackAdd,
                        pillPhysicalDefenseBonus = effect.physicalDefenseAdd,
                        pillMagicDefenseBonus = effect.magicDefenseAdd,
                        pillHpBonus = effect.hpAdd,
                        pillMpBonus = effect.mpAdd,
                        pillSpeedBonus = effect.speedAdd,
                        pillCritRateBonus = effect.critRateAdd,
                        pillCritEffectBonus = effect.critEffectAdd,
                        pillCultivationSpeedBonus = effect.cultivationSpeedPercent,
                        pillSkillExpSpeedBonus = effect.skillExpSpeedPercent,
                        pillNurtureSpeedBonus = effect.nurtureSpeedPercent,
                        pillEffectDuration = if (pill.duration > 0) pill.duration else updatedDisciple.pillEffects.pillEffectDuration,
                        activePillCategory = if (pill.cannotStack) pill.category.name else updatedDisciple.pillEffects.activePillCategory
                    )
                    updatedDisciple = updatedDisciple.copy(pillEffects = newEffects)
                }

                if (effect.healMaxHpPercent > 0) {
                    updatedDisciple = updatedDisciple.copy(combat = updatedDisciple.combat.copy(hpVariance = 0))
                }

                if (effect.revive && !disciple.isAlive) {
                    updatedDisciple = updatedDisciple.copy(isAlive = true, combat = updatedDisciple.combat.copy(hpVariance = 0))
                }

                if (effect.clearAll) {
                    updatedDisciple = updatedDisciple.copy(pillEffects = PillEffects())
                }

                disciples = disciples.map {
                    if (it.id == discipleId) updatedDisciple else it
                }

                successMsg = "弟子${disciple.name}使用了${pill.name}"
            }

            errorMsg?.let { addEvent(it, EventType.WARNING) }
            successMsg?.let { addEvent(it, EventType.SUCCESS) }
        }
    }

    private fun calculateSellPrice(basePrice: Int, quantity: Int): Long {
        return (basePrice.toLong() * quantity * GameConfig.Rarity.SELL_PRICE_MULTIPLIER).toLong()
    }

    fun sellEquipment(equipmentId: String, quantity: Int = 1): Boolean {
        val stack = stateStore.equipmentStacks.value.find { it.id == equipmentId } ?: return false
        if (stack.isLocked) return false
        if (quantity < 1 || quantity > stack.quantity) return false
        if (!inventorySystem.removeEquipment(equipmentId, quantity)) return false
        addSpiritStones(calculateSellPrice(stack.basePrice, quantity))
        return true
    }

    fun sellManual(manualId: String, quantity: Int): Boolean {
        val stack = stateStore.manualStacks.value.find { it.id == manualId } ?: return false
        if (stack.isLocked) return false
        if (quantity < 1 || quantity > stack.quantity) return false
        if (!inventorySystem.removeManual(manualId, quantity)) return false
        addSpiritStones(calculateSellPrice(stack.basePrice, quantity))
        return true
    }

    fun sellPill(pillId: String, quantity: Int): Boolean {
        val pill = stateStore.pills.value.find { it.id == pillId } ?: return false
        if (pill.isLocked) return false
        if (quantity < 1 || quantity > pill.quantity) return false
        if (!inventorySystem.removePill(pillId, quantity)) return false
        addSpiritStones(calculateSellPrice(pill.basePrice, quantity))
        return true
    }

    fun sellMaterial(materialId: String, quantity: Int): Boolean {
        val material = stateStore.materials.value.find { it.id == materialId } ?: return false
        if (material.isLocked) return false
        if (quantity < 1 || quantity > material.quantity) return false
        if (!inventorySystem.removeMaterial(materialId, quantity)) return false
        addSpiritStones(calculateSellPrice(material.basePrice, quantity))
        return true
    }

    fun sellHerb(herbId: String, quantity: Int): Boolean {
        val herb = stateStore.herbs.value.find { it.id == herbId } ?: return false
        if (herb.isLocked) return false
        if (quantity < 1 || quantity > herb.quantity) return false
        if (!inventorySystem.removeHerb(herbId, quantity)) return false
        addSpiritStones(calculateSellPrice(herb.basePrice, quantity))
        return true
    }

    fun sellSeed(seedId: String, quantity: Int): Boolean {
        val seed = stateStore.seeds.value.find { it.id == seedId } ?: return false
        if (seed.isLocked) return false
        if (quantity < 1 || quantity > seed.quantity) return false
        if (!inventorySystem.removeSeed(seedId, quantity)) return false
        addSpiritStones(calculateSellPrice(seed.basePrice, quantity))
        return true
    }

    fun toggleItemLock(itemId: String, itemType: String) {
        gameEngineCore.launchInScope {
            stateStore.update {
                when (itemType) {
                    "equipment" -> {
                        equipmentStacks = equipmentStacks.map { if (it.id == itemId) it.copy(isLocked = !it.isLocked) else it }
                    }
                    "manual" -> {
                        manualStacks = manualStacks.map { if (it.id == itemId) it.copy(isLocked = !it.isLocked) else it }
                    }
                    "pill" -> {
                        pills = pills.map { if (it.id == itemId) it.copy(isLocked = !it.isLocked) else it }
                    }
                    "material" -> {
                        materials = materials.map { if (it.id == itemId) it.copy(isLocked = !it.isLocked) else it }
                    }
                    "herb" -> {
                        herbs = herbs.map { if (it.id == itemId) it.copy(isLocked = !it.isLocked) else it }
                    }
                    "seed" -> {
                        seeds = seeds.map { if (it.id == itemId) it.copy(isLocked = !it.isLocked) else it }
                    }
                }
            }
        }
    }

    fun addSpiritStones(amount: Long) {
        updateGameDataSync { it.copy(spiritStones = it.spiritStones + amount) }
    }

    fun updateMonthlySalary(newSalary: Map<Int, Int>) {
        updateGameDataSync { it.copy(monthlySalary = newSalary) }
    }

    fun startMission(mission: Mission, selectedDisciples: List<Disciple>) {
        val data = stateStore.gameData.value
        val activeMission = MissionSystem.createActiveMission(mission, selectedDisciples, data.gameYear, data.gameMonth)
        updateGameDataSync { it.copy(activeMissions = it.activeMissions + activeMission) }
        selectedDisciples.forEach { disciple ->
            gameEngineCore.launchInScope { updateDiscipleStatus(disciple.id, DiscipleStatus.ON_MISSION) }
        }
    }

    fun checkAndProcessCompletedMissions(): List<String> {
        val data = stateStore.gameData.value
        val currentYear = data.gameYear
        val currentMonth = data.gameMonth
        val completedIds = mutableListOf<String>()
        val remainingActive = mutableListOf<ActiveMission>()

        for (activeMission in data.activeMissions) {
            if (activeMission.isComplete(currentYear, currentMonth)) {
                completedIds.add(activeMission.id)

                val aliveDisciples = activeMission.discipleIds.mapNotNull { did ->
                    stateStore.disciples.value.find { it.id == did && it.isAlive }
                }
                val allDead = aliveDisciples.isEmpty()

                if (allDead) {
                    addEvent("任务「${activeMission.missionName}」失败，执行弟子全部阵亡", EventType.WARNING)
                } else {
                    val result = MissionSystem.processMissionCompletion(activeMission, aliveDisciples)
                    applyMissionResult(result)
                    addEvent("任务「${activeMission.missionName}」已完成，获得奖励", EventType.SUCCESS)
                }

                for (did in activeMission.discipleIds) {
                    val disciple = stateStore.disciples.value.find { it.id == did }
                    if (disciple != null && disciple.isAlive) {
                        gameEngineCore.launchInScope { updateDiscipleStatus(did, DiscipleStatus.IDLE) }
                    }
                }
            } else {
                remainingActive.add(activeMission)
            }
        }

        if (completedIds.isNotEmpty()) {
            updateGameDataSync { it.copy(activeMissions = remainingActive) }
        }

        return completedIds
    }

    fun processMonthlyMissionRefresh() {
        val data = stateStore.gameData.value
        val result = MissionSystem.processMonthlyRefresh(
            data.availableMissions,
            data.gameYear,
            data.gameMonth
        )
        updateGameDataSync { it.copy(availableMissions = result.cleanedMissions) }
    }

    private fun applyMissionResult(result: MissionSystem.MissionResult) {
        if (result.spiritStones > 0) addSpiritStones(result.spiritStones.toLong())
        result.materials.forEach { inventorySystem.addMaterial(it) }
    }

    fun startExploration(name: String, memberIds: List<String>, location: String, duration: Int) {
        val data = stateStore.gameData.value
        val dungeonName = GameConfig.Dungeons.get(location)?.name ?: location
        createExplorationTeam(name, memberIds, location, dungeonName, duration, data.gameYear, data.gameMonth, data.gameDay)
    }

    fun startBattleTeamMove(targetSectId: String) {
        val data = stateStore.gameData.value
        val team = data.battleTeam ?: return
        val targetSect = data.worldMapSects.find { it.id == targetSectId } ?: return

        val updatedTeam = team.copy(
            targetSectId = targetSectId,
            status = "moving",
            targetX = targetSect.x,
            targetY = targetSect.y,
            originSectId = data.worldMapSects.find { it.isPlayerSect }?.id ?: "",
            route = emptyList(),
            currentRouteIndex = 0,
            moveProgress = 0f,
            isReturning = false
        )
        updateGameDataSync { it.copy(battleTeam = updatedTeam) }
    }

    fun toggleSmartBattle() {
        updateGameDataSync { it.copy(smartBattleEnabled = !it.smartBattleEnabled) }
    }

    fun isSmartBattleEnabled(): Boolean = stateStore.gameData.value.smartBattleEnabled
}
