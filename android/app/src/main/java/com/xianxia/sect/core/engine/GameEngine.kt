package com.xianxia.sect.core.engine

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.engine.service.*
import com.xianxia.sect.core.engine.system.AddResult
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.system.MerchantItemConverter
import com.xianxia.sect.core.engine.BattleSystem
import com.xianxia.sect.core.engine.DisciplePillManager
import com.xianxia.sect.core.engine.production.ProductionCoordinator
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.engine.HerbGardenSystem
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.StorageBagUtils
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.addEquipmentInstanceToDiscipleBag
import com.xianxia.sect.core.state.addManualInstanceToDiscipleBag
import com.xianxia.sect.core.state.equipmentBagStackIds
import com.xianxia.sect.core.state.manualBagStackIds
import javax.inject.Inject
import javax.inject.Singleton
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
    val battleLogs: List<BattleLog>,
    val alliances: List<Alliance>,
    val productionSlots: List<com.xianxia.sect.core.model.production.ProductionSlot> = emptyList()
)

@Singleton
class GameEngine @Inject constructor(
    private val gameEngineCore: GameEngineCore,
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig,
    private val battleSystem: BattleSystem,
    private val productionCoordinator: ProductionCoordinator,
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
    val gameDataSnapshot: GameData get() = stateStore.gameDataSnapshot
    val discipleAggregatesSnapshot: List<DiscipleAggregate> get() = stateStore.discipleAggregatesSnapshot
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
    val battleLogs: StateFlow<List<BattleLog>> get() = stateStore.battleLogs
    val pendingBattleResult: StateFlow<BattleResultUIData?> get() = stateStore.pendingBattleResult
    fun clearPendingBattleResult() = stateStore.clearPendingBattleResult()
    val pendingNotification: StateFlow<GameNotification?> get() = stateStore.pendingNotification
    val warehouseFullEvent get() = stateStore.warehouseFullEvent
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
                worldLevels = data.worldLevels ?: emptyList()
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
        battleLogs: List<BattleLog> = emptyList(),
        alliances: List<Alliance> = emptyList(),
        productionSlots: List<com.xianxia.sect.core.model.production.ProductionSlot> = emptyList()
    ) {
        val (migratedGameData, migratedDisciples) = migratePatrolSlotsIfNeeded(gameData, disciples)

        stateStore.loadFromSnapshot(
            gameData = migratedGameData.copy(isGameStarted = true),
            disciples = migratedDisciples,
            equipmentStacks = equipmentStacks,
            equipmentInstances = equipmentInstances,
            manualStacks = manualStacks,
            manualInstances = manualInstances,
            pills = pills,
            materials = materials,
            herbs = herbs,
            seeds = seeds,
            teams = teams,
            battleLogs = battleLogs
        )

        Log.d(TAG, "loadData: restored game year=${gameData.gameYear}, ${disciples.size} disciples, recruitList=${gameData.recruitList.size} unrecruited disciples")

        // Validate and fix alchemy/forge slot count to match placed buildings
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

        discipleService.syncAllDiscipleStatuses()

        // Regenerate AI sect disciples if missing (fix for saves migrated from DB v3)
        val loadedData = stateStore.gameData.value
        if (loadedData.aiSectDisciples.isEmpty() && loadedData.worldMapSects.isNotEmpty()) {
            Log.i(TAG, "loadData: aiSectDisciples empty, regenerating for ${loadedData.worldMapSects.size} sects")
            val regenerated = mutableMapOf<String, List<Disciple>>()
            for (sect in loadedData.worldMapSects) {
                if (!sect.isPlayerSect) {
                    val (disciples, _) = AISectDiscipleManager.initializeSectDisciples(sect.name, sect.level)
                    regenerated[sect.id] = disciples
                }
            }
            stateStore.update {
                this.gameData = this.gameData.copy(aiSectDisciples = regenerated)
            }
        }
    }

    suspend fun createNewGame(sectName: String, currentSlot: Int = 1) {
        stateStore.reset()
        cultivationService.resetHighFrequencyData()
        initializeWorldAndServices(sectName, currentSlot)
        // 在事务中创建初始弟子，避免 addDisciple 异步写入竞态导致弟子丢失
        // 保留 initializeWorldAndServices 已设置的 gameData（含商人商品、招募弟子等），
        // 仅更新必要字段，避免用全新 GameData 覆盖导致首月数据丢失
        stateStore.update {
            gameData = gameData.copy(
                isGameStarted = true,
                currentSlot = currentSlot,
                placedBuildings = emptyList()
            )
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
            initializeWorldAndServices(sectName, currentSlot)
            // 在事务中创建初始弟子，避免 addDisciple 异步写入竞态导致弟子丢失
            // 保留 initializeWorldAndServices 已设置的 gameData（含商人商品、招募弟子等），
            // 仅更新必要字段，避免用全新 GameData 覆盖导致首月数据丢失
            stateStore.update {
                gameData = gameData.copy(
                    isGameStarted = true,
                    currentSlot = currentSlot,
                    placedBuildings = emptyList()
                )
                repeat(3) { discipleService.recruitDisciple() }
            }
            addInitialManual()
        } else {
            stateStore.update {
                gameData = GameData(isGameStarted = true).copy(currentSlot = currentSlot)
            }
        }
    }

    private suspend fun initializeWorldAndServices(sectName: String, currentSlot: Int = 1) {
        val generationResult = WorldMapGenerator.generateWorldSects(sectName)
        val sectRelations = WorldMapGenerator.initializeSectRelations(generationResult.sects)
        productionCoordinator.repository.initializeAllSlots(currentSlot)
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

    suspend fun placeBuilding(building: GridBuildingData) {
        val sectId = currentActiveSectId()
        updateGameData { gd ->
            gd.copy(placedBuildings = gd.placedBuildings + building.copy(sectId = sectId))
        }
    }

    /** 直接更新建筑位置，绕过 transactionMutex 避免与游戏 tick 竞争锁导致延迟 */
    fun moveBuildingDirect(instanceId: String, newGridX: Int, newGridY: Int) {
        val sectId = currentActiveSectId()
        stateStore.updateGameDataDirect { data ->
            data.copy(
                placedBuildings = data.placedBuildings.map {
                    if (it.instanceId == instanceId && it.sectId == sectId) it.copy(gridX = newGridX, gridY = newGridY)
                    else it
                }
            )
        }
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

    suspend fun confiscateStorageBagItem(discipleId: String, item: StorageBagItem) {
        stateStore.update {
            val disciple = disciples.find { it.id == discipleId } ?: return@update
            var updatedDisciple = disciple

            // 从储物袋移除1个
            val updatedItems = StorageBagUtils.decreaseItemQuantity(
                disciple.equipment.storageBagItems, item.itemId, 1
            )
            updatedDisciple = updatedDisciple.copy(
                equipment = updatedDisciple.equipment.copy(storageBagItems = updatedItems)
            )

            // 加入宗门仓库
            when (item.itemType.lowercase(java.util.Locale.getDefault())) {
                "equipment" -> {
                    val template = EquipmentDatabase.getTemplateByName(item.name)
                    if (template != null) {
                        val stack = com.xianxia.sect.core.model.EquipmentStack(
                            name = template.name,
                            slot = template.slot,
                            rarity = template.rarity,
                            physicalAttack = template.physicalAttack,
                            magicAttack = template.magicAttack,
                            physicalDefense = template.physicalDefense,
                            magicDefense = template.magicDefense,
                            speed = template.speed,
                            hp = template.hp,
                            mp = template.mp,
                            description = template.description,
                            minRealm = GameConfig.Realm.getMinRealmForRarity(template.rarity)
                        )
                        equipmentStacks = equipmentStacks.toMutableList().apply {
                            val existing = find { it.name == stack.name && it.rarity == stack.rarity }
                            if (existing != null) {
                                val idx = indexOf(existing)
                                set(idx, existing.copy(quantity = existing.quantity + 1))
                            } else {
                                add(stack.copy(quantity = 1))
                            }
                        }
                    }
                }
                "manual" -> {
                    val template = ManualDatabase.getByName(item.name)
                    if (template != null) {
                        manualStacks = manualStacks.toMutableList().apply {
                            val existing = find { it.name == template.name && it.rarity == template.rarity }
                            if (existing != null) {
                                val idx = indexOf(existing)
                                set(idx, existing.copy(quantity = existing.quantity + 1))
                            } else {
                                add(ManualDatabase.createFromTemplate(template).copy(quantity = 1))
                            }
                        }
                    }
                }
                "pill" -> {
                    val template = ItemDatabase.getPillById(item.itemId)
                        ?: ItemDatabase.getPillByName(item.name)
                    if (template != null) {
                        val pill = ItemDatabase.createPillFromTemplate(template, quantity = 1)
                        pills = pills.toMutableList().apply {
                            val existing = find { it.name == pill.name && it.rarity == pill.rarity && it.grade == pill.grade }
                            if (existing != null) {
                                val idx = indexOf(existing)
                                set(idx, existing.copy(quantity = existing.quantity + 1))
                            } else {
                                add(pill)
                            }
                        }
                    }
                }
                "herb" -> {
                    val herbTemplate = HerbDatabase.getHerbByName(item.name)
                    herbs = herbs.toMutableList().apply {
                        val existing = find { it.name == item.name && it.rarity == item.rarity }
                        if (existing != null) {
                            val idx = indexOf(existing)
                            set(idx, existing.copy(quantity = existing.quantity + 1))
                        } else {
                            add(Herb(
                                name = item.name,
                                rarity = item.rarity,
                                description = herbTemplate?.description ?: "",
                                category = herbTemplate?.category ?: "",
                                quantity = 1
                            ))
                        }
                    }
                }
                "seed" -> {
                    seeds = seeds.toMutableList().apply {
                        val existing = find { it.name == item.name && it.rarity == item.rarity }
                        if (existing != null) {
                            val idx = indexOf(existing)
                            set(idx, existing.copy(quantity = existing.quantity + 1))
                        } else {
                            add(Seed(
                                name = item.name,
                                rarity = item.rarity,
                                description = "",
                                growTime = 0,
                                quantity = 1
                            ))
                        }
                    }
                }
                "material" -> {
                    val matTemplate = com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialByName(item.name)
                    materials = materials.toMutableList().apply {
                        val existing = find { it.name == item.name && it.rarity == item.rarity }
                        if (existing != null) {
                            val idx = indexOf(existing)
                            set(idx, existing.copy(quantity = existing.quantity + 1))
                        } else {
                            add(Material(
                                name = item.name,
                                rarity = item.rarity,
                                description = matTemplate?.description ?: "",
                                quantity = 1
                            ))
                        }
                    }
                }
            }

            disciples = disciples.map { if (it.id == discipleId) updatedDisciple else it }
        }
    }

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

    suspend fun expelTheftDisciple(discipleId: String): Boolean {
        return discipleService.expelDisciple(discipleId)
    }

    fun imprisonTheftDisciple(discipleId: String, currentYear: Int) {
        stateStore.updateDisciplesDirect { disciples ->
            disciples.map {
                if (it.id == discipleId) it.copy(
                    status = DiscipleStatus.REFLECTING,
                    statusData = it.statusData + mapOf(
                        "reflectionStartYear" to currentYear.toString(),
                        "reflectionEndYear" to (currentYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS).toString()
                    )
                ) else it
            }
        }
    }

    fun releaseTheftDisciple(discipleId: String): Int {
        val loyaltyChange = (1..10).random()
        stateStore.updateDisciplesDirect { disciples ->
            disciples.map {
                if (it.id == discipleId) {
                    val baseStats = DiscipleStatCalculator.getBaseStats(it)
                    it.copy(
                        status = DiscipleStatus.IDLE,
                        statusData = it.statusData - setOf("reflectionStartYear", "reflectionEndYear"),
                        skills = it.skills.copy(
                            loyalty = (baseStats.loyalty + loyaltyChange).coerceAtLeast(0)
                        )
                    )
                } else it
            }
        }
        return loyaltyChange
    }

    fun clearPendingNotification() {
        stateStore.clearPendingNotification()
    }

    suspend fun approveMarriage(maleId: String, femaleId: String) {
        updateDisciple(maleId) { it.copyWith(partnerId = femaleId) }
        updateDisciple(femaleId) { it.copyWith(partnerId = maleId) }
    }

    fun enterSect(sectId: String) {
        stateStore.updateGameDataDirect { gameData ->
            gameData.activeSectId = sectId
            gameData
        }
    }

    fun currentActiveSectId(): String = stateStore.gameDataSnapshot.activeSectId

    suspend fun equipEquipment(discipleId: String, equipmentId: String): Boolean =
        discipleService.equipEquipment(discipleId, equipmentId)

    suspend fun unequipEquipment(discipleId: String, equipmentId: String): Boolean =
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

    fun generateSectTradeItems(year: Int): List<MerchantItem> =
        diplomacyService.generateSectTradeItems(year)

    fun getOrRefreshSectTradeItems(sectId: String): List<MerchantItem> =
        diplomacyService.getOrRefreshSectTradeItems(sectId)

    fun buyFromSectTrade(sectId: String, itemId: String, quantity: Int = 1) =
        diplomacyService.buyFromSectTrade(sectId, itemId, quantity)

    suspend fun buyFromSectTradeSync(sectId: String, itemId: String, quantity: Int = 1) =
        diplomacyService.buyFromSectTradeSync(sectId, itemId, quantity)

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

    suspend fun addProductionSlot(slot: ProductionSlot) {
        productionCoordinator.repository.addSlot(slot)
    }

    fun assignDiscipleToProductionSlot(
        buildingType: BuildingType,
        slotIndex: Int,
        discipleId: String,
        discipleName: String
    ) {
        gameEngineCore.launchInScope {
            // 写入 repository（UI 读取来源 + 持久化）
            productionCoordinator.repository.updateSlot(buildingType, slotIndex) { slot ->
                slot.copy(
                    assignedDiscipleId = discipleId,
                    assignedDiscipleName = discipleName
                )
            }
            // 同步写入 stateStore（getAvailableWorkers 等读取来源，后续迁移完成后移除）
            stateStore.update { gameData = gameData.copy(
                productionSlots = gameData.productionSlots.map { slot ->
                    if (slot.buildingType == buildingType && slot.slotIndex == slotIndex) {
                        slot.copy(
                            assignedDiscipleId = discipleId,
                            assignedDiscipleName = discipleName
                        )
                    } else slot
                }
            )}
        }
    }

    fun removeDiscipleFromProductionSlot(
        buildingType: BuildingType,
        slotIndex: Int
    ) {
        gameEngineCore.launchInScope {
            val data = stateStore.gameDataSnapshot
            val currentYear = data.gameYear
            val currentMonth = data.gameMonth
            val slot = data.productionSlots.find {
                it.buildingType == buildingType && it.slotIndex == slotIndex
            }
            val discipleId = slot?.assignedDiscipleId

            // 写入 repository（UI 读取来源 + 持久化）
            productionCoordinator.repository.updateSlot(buildingType, slotIndex) { s ->
                if (s.isWorking && !s.assignedDiscipleId.isNullOrEmpty()) {
                    val remaining = s.remainingTime(currentYear, currentMonth)
                    s.copy(
                        assignedDiscipleId = null,
                        assignedDiscipleName = "",
                        startYear = currentYear,
                        startMonth = currentMonth,
                        duration = remaining.coerceAtLeast(1)
                    )
                } else {
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
            // 同步写入 stateStore（后续迁移完成后移除）
            stateStore.update { gameData = gameData.copy(
                productionSlots = gameData.productionSlots.map { s ->
                    if (s.buildingType == buildingType && s.slotIndex == slotIndex) {
                        if (s.isWorking && !s.assignedDiscipleId.isNullOrEmpty()) {
                            val remaining = s.remainingTime(currentYear, currentMonth)
                            s.copy(
                                assignedDiscipleId = null,
                                assignedDiscipleName = "",
                                startYear = currentYear,
                                startMonth = currentMonth,
                                duration = remaining.coerceAtLeast(1)
                            )
                        } else {
                            s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                        }
                    } else s
                }
            )}
            if (discipleId != null) {
                stateStore.update {
                    disciples = disciples.map { d ->
                        if (d.id == discipleId) d.copy(status = DiscipleStatus.IDLE) else d
                    }
                }
            }
        }
    }

    suspend fun toggleAutoRestart(buildingType: BuildingType, slotIndex: Int) {
        productionCoordinator.repository.updateSlot(buildingType, slotIndex) { slot ->
            slot.copy(autoRestartEnabled = !slot.autoRestartEnabled)
        }
        stateStore.update { gameData = gameData.copy(
            productionSlots = gameData.productionSlots.map { slot ->
                if (slot.buildingType == buildingType && slot.slotIndex == slotIndex)
                    slot.copy(autoRestartEnabled = !slot.autoRestartEnabled)
                else slot
            }
        )}
    }

    fun getAssignedDiscipleForSlot(buildingType: BuildingType, slotIndex: Int): Pair<String, String>? {
        val slot = productionCoordinator.repository.getSlotByIndex(buildingType, slotIndex)
        val id = slot?.assignedDiscipleId
        return if (id.isNullOrEmpty()) null else Pair(id, slot.assignedDiscipleName)
    }

    fun getAlchemyFurnaceCount(): Int {
        val activeSectId = stateStore.gameDataSnapshot.activeSectId
        return stateStore.gameDataSnapshot.placedBuildings.count { it.displayName == "炼丹炉" && it.sectId == activeSectId }
    }

    fun getForgeWorkshopCount(): Int {
        val activeSectId = stateStore.gameDataSnapshot.activeSectId
        return stateStore.gameDataSnapshot.placedBuildings.count { it.displayName == "锻造坊" && it.sectId == activeSectId }
    }

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
    }

    fun clearPlantSlot(slotIndex: Int) = buildingService.clearPlantSlot(slotIndex)

    fun getForgeSlots(): List<BuildingSlot> = buildingService.getBuildingSlots()

    fun getStateSnapshotSync(): GameStateSnapshot {
        return GameStateSnapshot(
            gameData = stateStore.gameDataSnapshot,
            disciples = stateStore.disciplesSnapshot,
            equipmentStacks = stateStore.equipmentStacksSnapshot,
            equipmentInstances = stateStore.equipmentInstancesSnapshot,
            manualStacks = stateStore.manualStacksSnapshot,
            manualInstances = stateStore.manualInstancesSnapshot,
            pills = stateStore.pillsSnapshot,
            materials = stateStore.materialsSnapshot,
            herbs = stateStore.herbsSnapshot,
            seeds = stateStore.seedsSnapshot,
            teams = stateStore.teamsSnapshot,
            battleLogs = stateStore.battleLogsSnapshot,
            alliances = stateStore.gameDataSnapshot.alliances,
            productionSlots = productionCoordinator.repository.getSlots()
        )
    }

    suspend fun getStateSnapshot(): GameStateSnapshot = getStateSnapshotSuspend()

    suspend fun getStateSnapshotSuspend(): GameStateSnapshot {
        val gd = stateStore.gameDataSnapshot
        return GameStateSnapshot(
            gameData = gd,
            disciples = stateStore.disciplesSnapshot,
            equipmentStacks = stateStore.equipmentStacksSnapshot,
            equipmentInstances = stateStore.equipmentInstancesSnapshot,
            manualStacks = stateStore.manualStacksSnapshot,
            manualInstances = stateStore.manualInstancesSnapshot,
            pills = stateStore.pillsSnapshot,
            materials = stateStore.materialsSnapshot,
            herbs = stateStore.herbsSnapshot,
            seeds = stateStore.seedsSnapshot,
            teams = stateStore.teamsSnapshot,
            battleLogs = stateStore.battleLogsSnapshot,
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
        battleLogs: List<BattleLog>,
        teams: List<ExplorationTeam>
    ) = saveService.loadFromSave(
        loadedGameData, disciples, equipmentStacks, equipmentInstances, manualStacks, manualInstances, pills,
        materials, herbs, seeds, battleLogs, teams
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

        val logsToKeep = if (normalizedLevel == 1) 20 else 10
        val levelName = if (normalizedLevel == 1) "MODERATE" else "CRITICAL"

        gameEngineCore.launchInScope {
            stateStore.update {
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
        stateStore.updateGameDataDirect { it.copy(elderSlots = newElderSlots) }
        gameEngineCore.launchInScope {
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
            discipleSpiritRootColor = discipleSpiritRootColor,
            sectId = data.activeSectId
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
        stateStore.updateGameDataDirect { it.copy(elderSlots = updatedSlots) }
        gameEngineCore.launchInScope {
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
        stateStore.updateGameDataDirect { it.copy(elderSlots = updatedSlots) }
        gameEngineCore.launchInScope {
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

        // Rebuild slot list in global mine order, stamping sectId from each mine
        val globalMines = data.placedBuildings.filter { it.displayName == "灵矿场" }
        val rebuiltSlots = mutableListOf<SpiritMineSlot>()
        var slotIdx = 0
        for (mine in globalMines) {
            for (offset in 0 until 3) {
                val existing = data.spiritMineSlots.getOrNull(slotIdx + offset)
                val slot = if (existing != null) {
                    if (existing.discipleId.isNotEmpty() && (existing.discipleId !in discipleMap || discipleMap[existing.discipleId]?.discipleType != "outer")) {
                        existing.copy(discipleId = "", discipleName = "", index = rebuiltSlots.size)
                    } else existing.copy(index = rebuiltSlots.size)
                } else {
                    SpiritMineSlot(index = rebuiltSlots.size, sectId = mine.sectId)
                }
                rebuiltSlots.add(slot)
            }
            slotIdx += 3
        }
        val finalSlots = rebuiltSlots.toList()

        if (finalSlots != data.spiritMineSlots) {
            val keptIds = finalSlots.mapNotNull { it.discipleId }.toSet()
            val orphanedIds = data.spiritMineSlots
                .filter { it.discipleId.isNotEmpty() && it.discipleId !in keptIds }
                .mapNotNull { it.discipleId }
            orphanedIds.forEach { discipleId ->
                gameEngineCore.launchInScope {
                    stateStore.update {
                        disciples = disciples.map { d ->
                            if (d.id == discipleId && d.status == DiscipleStatus.MINING)
                                d.copy(status = DiscipleStatus.IDLE) else d
                        }
                    }
                }
            }
            updateGameDataSync { it.copy(spiritMineSlots = finalSlots) }
        }
    }

    /**
     * Ensure alchemy/forge slot counts match placed building counts.
     * Called synchronously in loadData before restoreSlots.
     */
    private fun fixAlchemyForgeSlotCount(
        slots: List<ProductionSlot>,
        alchemyCount: Int,
        forgeCount: Int
    ): List<ProductionSlot> {
        val result = slots.toMutableList()

        // Fix alchemy slots: keep first N (sorted by slotIndex), fill missing
        val alchemySlots = result.filter { it.buildingType == BuildingType.ALCHEMY }
        result.removeAll { it.buildingType == BuildingType.ALCHEMY }
        val fixedAlchemy = mutableListOf<ProductionSlot>()
        alchemySlots.sortedBy { it.slotIndex }.take(alchemyCount).forEach { fixedAlchemy.add(it) }
        if (fixedAlchemy.size < alchemyCount) {
            val existingIndices = fixedAlchemy.map { it.slotIndex }.toSet()
            var nextIdx = 0
            while (fixedAlchemy.size < alchemyCount) {
                if (nextIdx !in existingIndices) {
                    fixedAlchemy.add(ProductionSlot.createIdle(
                        slotIndex = nextIdx, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy"
                    ))
                }
                nextIdx++
            }
        }
        result.addAll(fixedAlchemy)

        // Fix forge slots: keep first N (sorted by slotIndex), fill missing
        val forgeSlots = result.filter { it.buildingType == BuildingType.FORGE }
        result.removeAll { it.buildingType == BuildingType.FORGE }
        val fixedForge = mutableListOf<ProductionSlot>()
        forgeSlots.sortedBy { it.slotIndex }.take(forgeCount).forEach { fixedForge.add(it) }
        if (fixedForge.size < forgeCount) {
            val existingIndices = fixedForge.map { it.slotIndex }.toSet()
            var nextIdx = 0
            while (fixedForge.size < forgeCount) {
                if (nextIdx !in existingIndices) {
                    fixedForge.add(ProductionSlot.createIdle(
                        slotIndex = nextIdx, buildingType = BuildingType.FORGE, buildingId = "forge"
                    ))
                }
                nextIdx++
            }
        }
        result.addAll(fixedForge)

        return result
    }

    fun updateSpiritMineSlots(slots: List<SpiritMineSlot>) {
        updateGameDataSync { it.copy(spiritMineSlots = slots) }
    }

    fun updatePatrolSlots(slots: List<PatrolSlot>) {
        updateGameDataSync { it.copy(patrolSlots = slots) }
    }

    fun updatePatrolConfig(config: PatrolConfig) {
        updateGameDataSync { it.copy(patrolConfig = config) }
    }

    fun updatePatrolConfigs(configs: List<PatrolConfig>) {
        updateGameDataSync { it.copy(patrolConfigs = configs) }
    }

    fun assignDiscipleToLibrarySlot(slotIndex: Int, discipleId: String, discipleName: String) {
        val data = stateStore.gameData.value
        val slots = data.librarySlots.toMutableList()
        // Prevent assigning same disciple to multiple library slots
        if (slots.any { it.discipleId == discipleId && it.index != slotIndex }) return
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

    suspend fun plantOnSpiritField(buildingInstanceId: String, seedId: String, sectId: String) {
        val seed = inventorySystem.getSeedById(seedId) ?: return
        if (seed.quantity <= 0) return

        updateGameData { data ->
            val idx = data.spiritFieldPlants.indexOfFirst { it.buildingInstanceId == buildingInstanceId && it.seedId.isEmpty() }
            if (idx < 0) return@updateGameData data

            val currentYear = data.gameYear
            val currentMonth = data.gameMonth
            val updatedPlants = data.spiritFieldPlants.toMutableList()
            updatedPlants[idx] = updatedPlants[idx].copy(
                seedId = seedId,
                seedName = seed.name,
                growTime = seed.growTime,
                expectedYield = seed.yield,
                plantYear = currentYear,
                plantMonth = currentMonth,
                sectId = sectId
            )
            data.copy(spiritFieldPlants = updatedPlants)
        }

        inventorySystem.removeSeedSync(seedId, 1)
    }

    /** 批量种植：依次占用空灵田，只消耗一颗种子数量（按quantity扣除） */
    suspend fun plantOnSpiritFields(instanceIds: List<String>, seedId: String, sectId: String) {
        if (instanceIds.isEmpty()) return
        val seed = inventorySystem.getSeedById(seedId) ?: return
        if (seed.quantity <= 0) return

        var planted = 0
        updateGameData { data ->
            val currentYear = data.gameYear
            val currentMonth = data.gameMonth
            val updatedPlants = data.spiritFieldPlants.toMutableList()
            for (i in updatedPlants.indices) {
                if (planted >= instanceIds.size) break
                val p = updatedPlants[i]
                if (p.buildingInstanceId in instanceIds && p.seedId.isEmpty()) {
                    updatedPlants[i] = p.copy(
                        seedId = seedId, seedName = seed.name,
                        growTime = seed.growTime, expectedYield = seed.yield,
                        plantYear = currentYear, plantMonth = currentMonth, sectId = sectId
                    )
                    planted++
                }
            }
            data.copy(spiritFieldPlants = updatedPlants)
        }

        if (planted > 0) {
            inventorySystem.removeSeedSync(seedId, planted)
        }
    }

    suspend fun removePlantFromSpiritField(buildingInstanceId: String) {
        updateGameData { data ->
            val idx = data.spiritFieldPlants.indexOfFirst { it.buildingInstanceId == buildingInstanceId }
            if (idx < 0) return@updateGameData data

            val updatedPlants = data.spiritFieldPlants.toMutableList()
            updatedPlants[idx] = updatedPlants[idx].copy(
                seedId = "",
                seedName = "",
                growTime = 0,
                expectedYield = 0,
                plantYear = 0,
                plantMonth = 0
            )
            data.copy(spiritFieldPlants = updatedPlants)
        }
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

                        val canEquip = GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm)

                        if (canEquip) {
                            val slot = stack.slot
                            val oldEquipId = when (slot) {
                                EquipmentSlot.WEAPON -> disciple.equipment.weaponId
                                EquipmentSlot.ARMOR -> disciple.equipment.armorId
                                EquipmentSlot.BOOTS -> disciple.equipment.bootsId
                                EquipmentSlot.ACCESSORY -> disciple.equipment.accessoryId
                                else -> ""
                            }

                            var updatedDisciple = disciple

                            if (oldEquipId.isNotEmpty()) {
                                val oldInstance = equipmentInstances.find { it.id == oldEquipId }
                                if (oldInstance != null) {
                                    val bagStackIds = updatedDisciple.equipmentBagStackIds()
                                    val result = addEquipmentInstanceToDiscipleBag(
                                        disciple = updatedDisciple,
                                        instance = oldInstance,
                                        bagStackIds = bagStackIds,
                                        excludeStackId = stack.id,
                                        gameYear = gameData.gameYear,
                                        gameMonth = gameData.gameMonth,
                                        gameDay = gameData.gameDay,
                                        maxStackSize = inventoryConfig.getMaxStackSize("equipment_stack")
                                    )
                                    updatedDisciple = result.updatedDisciple
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

                        } else {
                            if (stack.quantity > 1) {
                                equipmentStacks = equipmentStacks.map { s ->
                                    if (s.id == item.id) s.copy(quantity = s.quantity - 1) else s
                                }
                            } else {
                                equipmentStacks = equipmentStacks.filter { it.id != item.id }
                            }

                            val bagStackIds = disciple.equipmentBagStackIds()
                            val bagStackId: String
                            val existingBagStack = equipmentStacks.find {
                                it.name == stack.name && it.rarity == stack.rarity && it.slot == stack.slot && it.id != item.id && it.id in bagStackIds && it.quantity < inventoryConfig.getMaxStackSize("equipment_stack")
                            }
                            if (existingBagStack != null) {
                                equipmentStacks = equipmentStacks.map { s ->
                                    if (s.id == existingBagStack.id) s.copy(quantity = existingBagStack.quantity + 1) else s
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
                                            d.equipment.storageBagItems,
                                            StorageBagItem(
                                                itemId = bagStackId,
                                                itemType = "equipment_stack",
                                                name = stack.name,
                                                rarity = stack.rarity,
                                                quantity = 1,
                                                obtainedYear = gameData.gameYear,
                                                obtainedMonth = gameData.gameMonth,
                                                forgetYear = gameData.gameYear,
                                                forgetMonth = gameData.gameMonth,
                                                forgetDay = gameData.gameDay
                                            ),
                                            inventoryConfig.getMaxStackSize("equipment_stack")
                                        )
                                    )
                                } else d
                            }

                        }
                    }
                }
                "manual" -> {
                    stateStore.update {
                        val stack = manualStacks.find { it.id == item.id }
                        if (stack == null || stack.quantity < 1) return@update

                        val disciple = disciples.find { it.id == discipleId }
                        if (disciple == null) return@update

                        val canLearn = GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm) &&
                            disciple.manualIds.size < DiscipleStatCalculator.getMaxManualSlots(disciple) &&
                            !(stack.type == ManualType.MIND && disciple.manualIds.any { mid ->
                                manualInstances.find { m -> m.id == mid }?.type == ManualType.MIND
                            }) &&
                            !disciple.manualIds.any { mid ->
                                manualInstances.find { m -> m.id == mid }?.name == stack.name
                            }

                        if (canLearn) {
                            val newQty = stack.quantity - 1
                            if (newQty <= 0) {
                                manualStacks = manualStacks.filter { it.id != item.id }
                            } else {
                                manualStacks = manualStacks.map {
                                    if (it.id == item.id) it.copy(quantity = newQty) else it
                                }
                            }

                            val instanceId = java.util.UUID.randomUUID().toString()
                            val instance = stack.toInstance(id = instanceId, ownerId = discipleId, isLearned = true)
                            manualInstances = manualInstances + instance

                            disciples = disciples.map {
                                if (it.id == discipleId) {
                                    it.copy(manualIds = it.manualIds + instanceId)
                                } else it
                            }

                        } else {
                            val newQty = stack.quantity - 1
                            if (newQty <= 0) {
                                manualStacks = manualStacks.filter { it.id != item.id }
                            } else {
                                manualStacks = manualStacks.map {
                                    if (it.id == item.id) it.copy(quantity = newQty) else it
                                }
                            }

                            val bagStackIds = disciple.manualBagStackIds()

                            val existingBagStack = manualStacks.find {
                                it.name == stack.name && it.rarity == stack.rarity && it.type == stack.type && it.id != item.id && it.id in bagStackIds && it.quantity < inventoryConfig.getMaxStackSize("manual_stack")
                            }

                            val storageItemId: String
                            if (existingBagStack != null) {
                                manualStacks = manualStacks.map {
                                    if (it.id == existingBagStack.id) it.copy(quantity = it.quantity + 1) else it
                                }
                                storageItemId = existingBagStack.id
                            } else {
                                val newStack = stack.copy(id = java.util.UUID.randomUUID().toString(), quantity = 1)
                                manualStacks = manualStacks + newStack
                                storageItemId = newStack.id
                            }

                            disciples = disciples.map {
                                if (it.id == discipleId) {
                                    it.copyWith(
                                        storageBagItems = StorageBagUtils.increaseItemQuantity(
                                            it.equipment.storageBagItems,
                                            StorageBagItem(
                                                itemId = storageItemId,
                                                itemType = "manual_stack",
                                                name = stack.name,
                                                rarity = stack.rarity,
                                                quantity = 1,
                                                obtainedYear = gameData.gameYear,
                                                obtainedMonth = gameData.gameMonth,
                                                forgetYear = gameData.gameYear,
                                                forgetMonth = gameData.gameMonth,
                                                forgetDay = gameData.gameDay
                                            ),
                                            inventoryConfig.getMaxStackSize("manual_stack")
                                        )
                                    )
                                } else it
                            }
                        }
                    }
                }
                "pill" -> {
                    stateStore.update {
                        val pill = pills.find { it.id == item.id }
                        if (pill != null && pill.quantity >= quantity) {
                            val disciple = disciples.find { it.id == discipleId }
                            if (disciple == null) return@update
                            val pillItem = StorageBagItem(
                                itemId = item.id,
                                itemType = "pill",
                                name = pill.name,
                                rarity = pill.rarity,
                                quantity = quantity,
                                obtainedYear = gameData.gameYear,
                                obtainedMonth = gameData.gameMonth,
                                effect = DisciplePillManager.pillToItemEffect(pill),
                                grade = pill.grade.displayName
                            )
                            val canUse = DisciplePillManager.canUsePill(disciple, pillItem).canUse

                            pills = pills.mapNotNull { p ->
                                if (p.id == item.id) {
                                    val newQty = p.quantity - quantity
                                    if (newQty == 0) null else p.copy(quantity = newQty)
                                } else p
                            }

                            if (canUse) {
                                var updatedDisciple = disciple
                                val effect = pill.effects
                                if (effect.cultivationAdd > 0) {
                                    updatedDisciple = updatedDisciple.copy(cultivation = (updatedDisciple.cultivation + effect.cultivationAdd).coerceAtLeast(0.0))
                                }
                                if (effect.skillExpAdd > 0) {
                                    updatedDisciple = updatedDisciple.copy(manualMasteries = updatedDisciple.manualMasteries.mapValues { (_, v) -> (v + effect.skillExpAdd).coerceAtMost(10000) })
                                }
                                if (effect.cultivationSpeedPercent > 0) {
                                    updatedDisciple = updatedDisciple.copy(
                                        cultivationSpeedBonus = effect.cultivationSpeedPercent,
                                        cultivationSpeedDuration = if (effect.duration > 0) effect.duration * 30 else updatedDisciple.cultivationSpeedDuration
                                    )
                                }
                                if (effect.extendLife > 0) {
                                    updatedDisciple = updatedDisciple.copy(lifespan = updatedDisciple.lifespan + effect.extendLife)
                                    if (!updatedDisciple.usage.usedExtendLifePillIds.contains(pill.pillType)) {
                                        updatedDisciple = updatedDisciple.copy(usage = updatedDisciple.usage.copy(usedExtendLifePillIds = updatedDisciple.usage.usedExtendLifePillIds + pill.pillType))
                                    }
                                }
                                if (effect.intelligenceAdd > 0 || effect.charmAdd > 0 || effect.loyaltyAdd > 0 ||
                                    effect.comprehensionAdd > 0 || effect.artifactRefiningAdd > 0 || effect.pillRefiningAdd > 0 ||
                                    effect.spiritPlantingAdd > 0 || effect.teachingAdd > 0 || effect.moralityAdd > 0 ||
                                    effect.miningAdd > 0
                                ) {
                                    updatedDisciple = updatedDisciple.copy(
                                        skills = updatedDisciple.skills.copy(
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
                                        )
                                    )
                                }
                                if (pill.category == PillCategory.FUNCTIONAL && pill.pillType.isNotEmpty()) {
                                    updatedDisciple = updatedDisciple.copy(usage = updatedDisciple.usage.copy(usedFunctionalPillTypes = updatedDisciple.usage.usedFunctionalPillTypes + pill.pillType))
                                }
                                if (effect.physicalAttackAdd > 0 || effect.magicAttackAdd > 0 ||
                                    effect.physicalDefenseAdd > 0 || effect.magicDefenseAdd > 0 ||
                                    effect.hpAdd > 0 || effect.mpAdd > 0 || effect.speedAdd > 0 ||
                                    effect.critRateAdd > 0 || effect.critEffectAdd > 0 ||
                                    effect.cultivationSpeedPercent > 0 || effect.skillExpSpeedPercent > 0 || effect.nurtureSpeedPercent > 0
                                ) {
                                    updatedDisciple = updatedDisciple.copy(pillEffects = updatedDisciple.pillEffects.copy(
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
                                        pillEffectDuration = if (effect.duration > 0) effect.duration * 30 else updatedDisciple.pillEffects.pillEffectDuration,
                                        activePillCategory = if (effect.cannotStack) pill.category.name else updatedDisciple.pillEffects.activePillCategory
                                    ))
                                }
                                if (effect.healMaxHpPercent > 0) {
                                    updatedDisciple = updatedDisciple.copy(combat = updatedDisciple.combat.copy(hpVariance = 0))
                                }
                                if (effect.clearAll) {
                                    updatedDisciple = updatedDisciple.copy(pillEffects = PillEffects())
                                }
                                disciples = disciples.map { if (it.id == discipleId) updatedDisciple else it }
                            } else {
                                disciples = disciples.map { d ->
                                    if (d.id == discipleId) {
                                        d.copyWith(
                                            storageBagItems = StorageBagUtils.increaseItemQuantity(
                                                d.equipment.storageBagItems,
                                                pillItem,
                                                inventoryConfig.getMaxStackSize("pill")
                                            )
                                        )
                                    } else d
                                }
                            }
                        }
                    }
                }
                "material" -> {
                    stateStore.update {
                        val material = materials.find { it.id == item.id }
                        if (material != null && !material.isLocked && quantity in 1..material.quantity) {
                            materials = materials.mapNotNull { m ->
                                if (m.id == item.id) {
                                    val newQty = m.quantity - quantity
                                    if (newQty == 0) null else m.copy(quantity = newQty)
                                } else m
                            }
                            disciples = disciples.map { d ->
                                if (d.id == discipleId) {
                                    d.copyWith(
                                        storageBagItems = StorageBagUtils.increaseItemQuantity(
                                            d.equipment.storageBagItems,
                                            StorageBagItem(
                                                itemId = item.id,
                                                itemType = "material",
                                                name = item.name,
                                                rarity = item.rarity,
                                                quantity = quantity,
                                                obtainedYear = gameData.gameYear,
                                                obtainedMonth = gameData.gameMonth
                                            ),
                                            inventoryConfig.getMaxStackSize("material")
                                        )
                                    )
                                } else d
                            }
                        }
                    }
                }
                "herb" -> {
                    stateStore.update {
                        val herb = herbs.find { it.id == item.id }
                        if (herb != null && !herb.isLocked && quantity in 1..herb.quantity) {
                            herbs = herbs.mapNotNull { h ->
                                if (h.id == item.id) {
                                    val newQty = h.quantity - quantity
                                    if (newQty == 0) null else h.copy(quantity = newQty)
                                } else h
                            }
                            disciples = disciples.map { d ->
                                if (d.id == discipleId) {
                                    d.copyWith(
                                        storageBagItems = StorageBagUtils.increaseItemQuantity(
                                            d.equipment.storageBagItems,
                                            StorageBagItem(
                                                itemId = item.id,
                                                itemType = "herb",
                                                name = item.name,
                                                rarity = item.rarity,
                                                quantity = quantity,
                                                obtainedYear = gameData.gameYear,
                                                obtainedMonth = gameData.gameMonth
                                            ),
                                            inventoryConfig.getMaxStackSize("herb")
                                        )
                                    )
                                } else d
                            }
                        }
                    }
                }
                "seed" -> {
                    stateStore.update {
                        val seed = seeds.find { it.id == item.id }
                        if (seed != null && !seed.isLocked && quantity in 1..seed.quantity) {
                            seeds = seeds.mapNotNull { s ->
                                if (s.id == item.id) {
                                    val newQty = s.quantity - quantity
                                    if (newQty == 0) null else s.copy(quantity = newQty)
                                } else s
                            }
                            disciples = disciples.map { d ->
                                if (d.id == discipleId) {
                                    d.copyWith(
                                        storageBagItems = StorageBagUtils.increaseItemQuantity(
                                            d.equipment.storageBagItems,
                                            StorageBagItem(
                                                itemId = item.id,
                                                itemType = "seed",
                                                name = item.name,
                                                rarity = item.rarity,
                                                quantity = quantity,
                                                obtainedYear = gameData.gameYear,
                                                obtainedMonth = gameData.gameMonth
                                            ),
                                            inventoryConfig.getMaxStackSize("seed")
                                        )
                                    )
                                } else d
                            }
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

    suspend fun equipItem(discipleId: String, equipmentId: String) {
        discipleService.equipEquipment(discipleId, equipmentId)
    }

    suspend fun unequipItem(discipleId: String, slot: EquipmentSlot) {
        val disciple = getDiscipleById(discipleId) ?: return
        val equipId = when (slot) {
            EquipmentSlot.WEAPON -> disciple.equipment.weaponId
            EquipmentSlot.ARMOR -> disciple.equipment.armorId
            EquipmentSlot.BOOTS -> disciple.equipment.bootsId
            EquipmentSlot.ACCESSORY -> disciple.equipment.accessoryId
        }
        if (equipId.isNotEmpty()) {
            discipleService.unequipEquipment(discipleId, equipId)
        }
    }

    suspend fun unequipItemById(discipleId: String, equipmentId: String) {
        discipleService.unequipEquipment(discipleId, equipmentId)
    }

    suspend fun forgetManual(discipleId: String, instanceId: String) {
        stateStore.update {
            val instance = manualInstances.find { it.id == instanceId } ?: return@update
            val currentDisciple = disciples.find { it.id == discipleId } ?: return@update
            val bagStackIds = currentDisciple.manualBagStackIds()

            val result = addManualInstanceToDiscipleBag(
                disciple = currentDisciple,
                instance = instance,
                bagStackIds = bagStackIds,
                gameYear = gameData.gameYear,
                gameMonth = gameData.gameMonth,
                gameDay = gameData.gameDay,
                maxStackSize = inventoryConfig.getMaxStackSize("manual_stack")
            )
            disciples = disciples.map {
                if (it.id == discipleId) {
                    result.updatedDisciple.copy(manualIds = it.manualIds.filter { mid -> mid != instanceId })
                } else it
            }

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

    suspend fun replaceManual(discipleId: String, oldInstanceId: String, newStackId: String) {
        stateStore.update {
            val oldInstance = manualInstances.find { it.id == oldInstanceId } ?: return@update
            val newStack = manualStacks.find { it.id == newStackId } ?: return@update
            val disciple = disciples.find { it.id == discipleId } ?: return@update

            if (newStack.quantity < 1) return@update
            if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, newStack.minRealm)) return@update

            val blocked = newStack.type == ManualType.MIND && oldInstance.type != ManualType.MIND && disciple.manualIds
                .filter { it != oldInstanceId }
                .any { mid -> manualInstances.find { m -> m.id == mid }?.type == ManualType.MIND }
            if (blocked) return@update

            val hasSameName = disciple.manualIds
                .filter { it != oldInstanceId }
                .any { mid -> manualInstances.find { m -> m.id == mid }?.name == newStack.name }
            if (hasSameName) return@update

            val bagStackIds = disciple.manualBagStackIds()
            val data = gameData

            val result = addManualInstanceToDiscipleBag(
                disciple = disciple,
                instance = oldInstance,
                bagStackIds = bagStackIds,
                gameYear = data.gameYear,
                gameMonth = data.gameMonth,
                gameDay = data.gameDay,
                maxStackSize = inventoryConfig.getMaxStackSize("manual_stack")
            )

            val currentNewStack = manualStacks.find { it.id == newStackId }
            if (currentNewStack == null) return@update
            val newQty = currentNewStack.quantity - 1
            if (newQty <= 0) {
                manualStacks = manualStacks.filter { it.id != newStackId }
            } else {
                manualStacks = manualStacks.map {
                    if (it.id == newStackId) it.copy(quantity = newQty) else it
                }
            }

            val newInstance = newStack.toInstance(id = java.util.UUID.randomUUID().toString(), ownerId = discipleId, isLearned = true)
            manualInstances = manualInstances + newInstance

            val updatedProficiencies = gameData.manualProficiencies.toMutableMap()
            updatedProficiencies[discipleId]?.let { profList ->
                val filtered = profList.filter { it.manualId != oldInstanceId }
                if (filtered.isEmpty()) {
                    updatedProficiencies.remove(discipleId)
                } else {
                    updatedProficiencies[discipleId] = filtered
                }
            }

            disciples = disciples.map {
                if (it.id == discipleId) {
                    result.updatedDisciple.copy(
                        manualIds = (it.manualIds.filter { mid -> mid != oldInstanceId }) + newInstance.id
                    )
                } else it
            }

            gameData = gameData.copy(manualProficiencies = updatedProficiencies)
        }
    }

    suspend fun learnManual(discipleId: String, stackId: String) {
        stateStore.update {
            val stack = manualStacks.find { it.id == stackId } ?: return@update
            val disciple = disciples.find { it.id == discipleId } ?: return@update

            if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm)) return@update

            val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
            if (disciple.manualIds.size >= maxSlots) return@update

            if (stack.type == ManualType.MIND) {
                val hasMind = disciple.manualIds.any { mid ->
                    manualInstances.find { it.id == mid }?.type == ManualType.MIND
                }
                if (hasMind) return@update
            }

            val hasSameName = disciple.manualIds.any { mid ->
                manualInstances.find { it.id == mid }?.name == stack.name
            }
            if (hasSameName) return@update

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
                    val hpDelta = stack.stats["hp"] ?: stack.stats["maxHp"] ?: 0
                    val mpDelta = stack.stats["mp"] ?: stack.stats["maxMp"] ?: 0
                    val rawHp = it.combat.currentHp
                    val rawMp = it.combat.currentMp
                    val newHp = if (rawHp >= 0 && hpDelta > 0) rawHp + hpDelta else rawHp
                    val newMp = if (rawMp >= 0 && mpDelta > 0) rawMp + mpDelta else rawMp
                    it.copy(
                        manualIds = it.manualIds + instanceId,
                        combat = it.combat.copy(currentHp = newHp, currentMp = newMp)
                    )
                } else it
            }
        }
    }

    suspend fun buyMerchantItem(itemId: String, quantity: Int) {
        val merchantItem = stateStore.gameData.value.travelingMerchantItems.find { it.id == itemId } ?: return
        val cost = merchantItem.price * quantity
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
                if (!inventorySystem.canAddPill(p.name, p.rarity, p.category, p.grade)) return
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
                    val existing = seeds.find { it.name == s.name && it.rarity == s.rarity && it.growTime == s.growTime }
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
        val newItems = mutableListOf<MerchantItem>()
        stateStore.update {
            items.forEach { (itemId, quantity) ->
                val eqStack = equipmentStacks.find { it.id == itemId }
                if (eqStack != null && !eqStack.isLocked && quantity in 1..eqStack.quantity) {
                    equipmentStacks = equipmentStacks.mapNotNull { s ->
                        if (s.id == itemId) {
                            val newQty = s.quantity - quantity
                            if (newQty == 0) null else s.copy(quantity = newQty)
                        } else s
                    }
                    newItems.add(MerchantItem(
                        id = java.util.UUID.randomUUID().toString(),
                        name = eqStack.name,
                        type = "equipment",
                        itemId = itemId,
                        rarity = eqStack.rarity,
                        price = GameConfig.Rarity.calculateSellPrice(eqStack.basePrice, 1),
                        quantity = quantity
                    ))
                    return@forEach
                }
                val manualStack = manualStacks.find { it.id == itemId }
                if (manualStack != null && !manualStack.isLocked && quantity in 1..manualStack.quantity) {
                    manualStacks = manualStacks.mapNotNull { s ->
                        if (s.id == itemId) {
                            val newQty = s.quantity - quantity
                            if (newQty == 0) null else s.copy(quantity = newQty)
                        } else s
                    }
                    newItems.add(MerchantItem(
                        id = java.util.UUID.randomUUID().toString(),
                        name = manualStack.name,
                        type = "manual",
                        itemId = itemId,
                        rarity = manualStack.rarity,
                        price = GameConfig.Rarity.calculateSellPrice(manualStack.basePrice, 1),
                        quantity = quantity
                    ))
                    return@forEach
                }
                val pill = pills.find { it.id == itemId }
                if (pill != null && !pill.isLocked && quantity in 1..pill.quantity) {
                    pills = pills.mapNotNull { p ->
                        if (p.id == itemId) {
                            val newQty = p.quantity - quantity
                            if (newQty == 0) null else p.copy(quantity = newQty)
                        } else p
                    }
                    newItems.add(MerchantItem(
                        id = java.util.UUID.randomUUID().toString(),
                        name = pill.name,
                        type = "pill",
                        itemId = itemId,
                        rarity = pill.rarity,
                        price = GameConfig.Rarity.calculateSellPrice(pill.basePrice, 1),
                        quantity = quantity,
                        grade = pill.grade.displayName
                    ))
                    return@forEach
                }
                val material = materials.find { it.id == itemId }
                if (material != null && !material.isLocked && quantity in 1..material.quantity) {
                    materials = materials.mapNotNull { m ->
                        if (m.id == itemId) {
                            val newQty = m.quantity - quantity
                            if (newQty == 0) null else m.copy(quantity = newQty)
                        } else m
                    }
                    newItems.add(MerchantItem(
                        id = java.util.UUID.randomUUID().toString(),
                        name = material.name,
                        type = "material",
                        itemId = itemId,
                        rarity = material.rarity,
                        price = GameConfig.Rarity.calculateSellPrice(material.basePrice, 1),
                        quantity = quantity
                    ))
                    return@forEach
                }
                val herb = herbs.find { it.id == itemId }
                if (herb != null && !herb.isLocked && quantity in 1..herb.quantity) {
                    herbs = herbs.mapNotNull { h ->
                        if (h.id == itemId) {
                            val newQty = h.quantity - quantity
                            if (newQty == 0) null else h.copy(quantity = newQty)
                        } else h
                    }
                    newItems.add(MerchantItem(
                        id = java.util.UUID.randomUUID().toString(),
                        name = herb.name,
                        type = "herb",
                        itemId = itemId,
                        rarity = herb.rarity,
                        price = GameConfig.Rarity.calculateSellPrice(herb.basePrice, 1),
                        quantity = quantity
                    ))
                    return@forEach
                }
                val seed = seeds.find { it.id == itemId }
                if (seed != null && !seed.isLocked && quantity in 1..seed.quantity) {
                    seeds = seeds.mapNotNull { s ->
                        if (s.id == itemId) {
                            val newQty = s.quantity - quantity
                            if (newQty == 0) null else s.copy(quantity = newQty)
                        } else s
                    }
                    newItems.add(MerchantItem(
                        id = java.util.UUID.randomUUID().toString(),
                        name = seed.name,
                        type = "seed",
                        itemId = itemId,
                        rarity = seed.rarity,
                        price = GameConfig.Rarity.calculateSellPrice(seed.basePrice, 1),
                        quantity = quantity
                    ))
                    return@forEach
                }
            }
            if (newItems.isNotEmpty()) {
                gameData = gameData.copy(playerListedItems = gameData.playerListedItems + newItems)
            }
        }
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

                if (pill.effects.cannotStack && disciple.pillEffects.activePillCategory == pill.category.name) {
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

                val effect = pill.effects
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
                        cultivationSpeedDuration = if (effect.duration > 0) effect.duration * 30 else updatedDisciple.cultivationSpeedDuration
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
                    effect.spiritPlantingAdd > 0 || effect.teachingAdd > 0 || effect.moralityAdd > 0 ||
                    effect.miningAdd > 0
                ) {
                    updatedDisciple = updatedDisciple.copy(
                        skills = updatedDisciple.skills.copy(
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
                        pillEffectDuration = if (effect.duration > 0) effect.duration * 30 else updatedDisciple.pillEffects.pillEffectDuration,
                        activePillCategory = if (effect.cannotStack) pill.category.name else updatedDisciple.pillEffects.activePillCategory
                    )
                    updatedDisciple = updatedDisciple.copy(pillEffects = newEffects)
                }

                if (effect.healMaxHpPercent > 0) {
                    updatedDisciple = updatedDisciple.copy(combat = updatedDisciple.combat.copy(hpVariance = 0))
                }

                if (effect.clearAll) {
                    updatedDisciple = updatedDisciple.copy(pillEffects = PillEffects())
                }

                disciples = disciples.map {
                    if (it.id == discipleId) updatedDisciple else it
                }

                successMsg = "弟子${disciple.name}使用了${pill.name}"
            }

        }
    }

    suspend fun sellEquipment(equipmentId: String, quantity: Int = 1): Boolean {
        var success = false
        stateStore.update {
            val stack = equipmentStacks.find { it.id == equipmentId }
            if (stack != null && !stack.isLocked && quantity in 1..stack.quantity) {
                equipmentStacks = equipmentStacks.mapNotNull { s ->
                    if (s.id == equipmentId) {
                        val newQty = s.quantity - quantity
                        if (newQty == 0) null else s.copy(quantity = newQty)
                    } else s
                }
                gameData = gameData.copy(spiritStones = gameData.spiritStones + GameConfig.Rarity.calculateSellPrice(stack.basePrice, quantity))
                success = true
            }
        }
        return success
    }

    suspend fun sellManual(manualId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update {
            val stack = manualStacks.find { it.id == manualId }
            if (stack != null && !stack.isLocked && quantity in 1..stack.quantity) {
                manualStacks = manualStacks.mapNotNull { s ->
                    if (s.id == manualId) {
                        val newQty = s.quantity - quantity
                        if (newQty == 0) null else s.copy(quantity = newQty)
                    } else s
                }
                gameData = gameData.copy(spiritStones = gameData.spiritStones + GameConfig.Rarity.calculateSellPrice(stack.basePrice, quantity))
                success = true
            }
        }
        return success
    }

    suspend fun sellPill(pillId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update {
            val pill = pills.find { it.id == pillId }
            if (pill != null && !pill.isLocked && quantity in 1..pill.quantity) {
                pills = pills.mapNotNull { p ->
                    if (p.id == pillId) {
                        val newQty = p.quantity - quantity
                        if (newQty == 0) null else p.copy(quantity = newQty)
                    } else p
                }
                gameData = gameData.copy(spiritStones = gameData.spiritStones + GameConfig.Rarity.calculateSellPrice(pill.basePrice, quantity))
                success = true
            }
        }
        return success
    }

    suspend fun sellMaterial(materialId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update {
            val material = materials.find { it.id == materialId }
            if (material != null && !material.isLocked && quantity in 1..material.quantity) {
                materials = materials.mapNotNull { m ->
                    if (m.id == materialId) {
                        val newQty = m.quantity - quantity
                        if (newQty == 0) null else m.copy(quantity = newQty)
                    } else m
                }
                gameData = gameData.copy(spiritStones = gameData.spiritStones + GameConfig.Rarity.calculateSellPrice(material.basePrice, quantity))
                success = true
            }
        }
        return success
    }

    suspend fun sellHerb(herbId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update {
            val herb = herbs.find { it.id == herbId }
            if (herb != null && !herb.isLocked && quantity in 1..herb.quantity) {
                herbs = herbs.mapNotNull { h ->
                    if (h.id == herbId) {
                        val newQty = h.quantity - quantity
                        if (newQty == 0) null else h.copy(quantity = newQty)
                    } else h
                }
                gameData = gameData.copy(spiritStones = gameData.spiritStones + GameConfig.Rarity.calculateSellPrice(herb.basePrice, quantity))
                success = true
            }
        }
        return success
    }

    suspend fun sellSeed(seedId: String, quantity: Int): Boolean {
        var success = false
        stateStore.update {
            val seed = seeds.find { it.id == seedId }
            if (seed != null && !seed.isLocked && quantity in 1..seed.quantity) {
                seeds = seeds.mapNotNull { s ->
                    if (s.id == seedId) {
                        val newQty = s.quantity - quantity
                        if (newQty == 0) null else s.copy(quantity = newQty)
                    } else s
                }
                gameData = gameData.copy(spiritStones = gameData.spiritStones + GameConfig.Rarity.calculateSellPrice(seed.basePrice, quantity))
                success = true
            }
        }
        return success
    }

    data class BulkSellOperation(
        val id: String,
        val name: String,
        val quantity: Int,
        val itemType: String
    )

    data class BulkSellResult(
        val soldCount: Int,
        val totalEarned: Long,
        val soldItemNames: List<String>,
        val failedItemNames: List<String>
    )

    suspend fun bulkSellItems(operations: List<BulkSellOperation>): BulkSellResult {
        var totalEarned = 0L
        var soldCount = 0
        val soldItemNames = mutableListOf<String>()
        val failedItemNames = mutableListOf<String>()

        stateStore.update {
            for (op in operations) {
                var sold = false
                when (op.itemType) {
                    "equipment" -> {
                        val stack = equipmentStacks.find { it.id == op.id }
                        if (stack != null && !stack.isLocked && op.quantity in 1..stack.quantity) {
                            equipmentStacks = equipmentStacks.mapNotNull { s ->
                                if (s.id == op.id) {
                                    val newQty = s.quantity - op.quantity
                                    if (newQty == 0) null else s.copy(quantity = newQty)
                                } else s
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(stack.basePrice, op.quantity)
                            soldCount++
                            sold = true
                        }
                    }
                    "manual" -> {
                        val stack = manualStacks.find { it.id == op.id }
                        if (stack != null && !stack.isLocked && op.quantity in 1..stack.quantity) {
                            manualStacks = manualStacks.mapNotNull { s ->
                                if (s.id == op.id) {
                                    val newQty = s.quantity - op.quantity
                                    if (newQty == 0) null else s.copy(quantity = newQty)
                                } else s
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(stack.basePrice, op.quantity)
                            soldCount++
                            sold = true
                        }
                    }
                    "pill" -> {
                        val pill = pills.find { it.id == op.id }
                        if (pill != null && !pill.isLocked && op.quantity in 1..pill.quantity) {
                            pills = pills.mapNotNull { p ->
                                if (p.id == op.id) {
                                    val newQty = p.quantity - op.quantity
                                    if (newQty == 0) null else p.copy(quantity = newQty)
                                } else p
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(pill.basePrice, op.quantity)
                            soldCount++
                            sold = true
                        }
                    }
                    "material" -> {
                        val material = materials.find { it.id == op.id }
                        if (material != null && !material.isLocked && op.quantity in 1..material.quantity) {
                            materials = materials.mapNotNull { m ->
                                if (m.id == op.id) {
                                    val newQty = m.quantity - op.quantity
                                    if (newQty == 0) null else m.copy(quantity = newQty)
                                } else m
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(material.basePrice, op.quantity)
                            soldCount++
                            sold = true
                        }
                    }
                    "herb" -> {
                        val herb = herbs.find { it.id == op.id }
                        if (herb != null && !herb.isLocked && op.quantity in 1..herb.quantity) {
                            herbs = herbs.mapNotNull { h ->
                                if (h.id == op.id) {
                                    val newQty = h.quantity - op.quantity
                                    if (newQty == 0) null else h.copy(quantity = newQty)
                                } else h
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(herb.basePrice, op.quantity)
                            soldCount++
                            sold = true
                        }
                    }
                    "seed" -> {
                        val seed = seeds.find { it.id == op.id }
                        if (seed != null && !seed.isLocked && op.quantity in 1..seed.quantity) {
                            seeds = seeds.mapNotNull { s ->
                                if (s.id == op.id) {
                                    val newQty = s.quantity - op.quantity
                                    if (newQty == 0) null else s.copy(quantity = newQty)
                                } else s
                            }
                            totalEarned += GameConfig.Rarity.calculateSellPrice(seed.basePrice, op.quantity)
                            soldCount++
                            sold = true
                        }
                    }
                }
                if (sold) {
                    soldItemNames.add("${op.name} ${op.quantity}")
                } else {
                    failedItemNames.add(op.name)
                }
            }
            if (totalEarned > 0) {
                gameData = gameData.copy(spiritStones = gameData.spiritStones + totalEarned)
            }
        }

        return BulkSellResult(soldCount, totalEarned, soldItemNames, failedItemNames)
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
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.gameData = ts.gameData.copy(spiritStones = ts.gameData.spiritStones + amount)
        } else {
            updateGameDataSync { it.copy(spiritStones = it.spiritStones + amount) }
        }
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
                } else {
                    val equipMap = stateStore.equipmentInstances.value.associateBy { it.id }
                    val manualMap = stateStore.manualInstances.value.associateBy { it.id }
                    val proficiencies = data.manualProficiencies.mapValues { (_, list) ->
                        list.associateBy { it.manualId }
                    }
                    val result = MissionSystem.processMissionCompletion(
                        activeMission, aliveDisciples, equipMap, manualMap, proficiencies, battleSystem
                    )
                    applyMissionResult(result)
                    if (result.victory) {
                    } else {
                    }
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
        result.pills.forEach { pill -> inventorySystem.addPill(pill) }
        result.equipmentStacks.forEach { equip -> inventorySystem.addEquipmentStack(equip) }
        result.manualStacks.forEach { manual -> inventorySystem.addManualStack(manual) }
    }

    /**
     * Execute an immediate attack on a target sect.
     * attackSlots are (slotIndex, disciple) pairs selected from the player's disciples.
     * This replaces the old movement+battle flow with immediate resolution.
     */
    suspend fun attackSect(sectId: String, attackSlots: List<Pair<Int, com.xianxia.sect.core.model.DiscipleAggregate>>) {
        val data = stateStore.gameData.value
        val targetSect = data.worldMapSects.find { it.id == sectId } ?: return
        val allDisciples = stateStore.disciples.value

        val attackers = attackSlots.mapNotNull { (_, agg) ->
            allDisciples.find { it.id == agg.id && it.isAlive }
        }
        if (attackers.isEmpty()) return

        val playerSect = data.worldMapSects.find { it.isPlayerSect }

        // Get defender disciples: follow decideAttacks() logic
        // If occupied by another AI sect, defenders come from occupier's garrison
        // Otherwise, defenders come from the sect's own disciple pool
        val isAiOccupied = targetSect.occupierSectId.isNotEmpty() && targetSect.occupierSectId != playerSect?.id
        val defenderDisciples = if (isAiOccupied) {
            val occupierDisciples = data.aiSectDisciples[targetSect.occupierSectId] ?: emptyList()
            targetSect.garrisonSlots
                .filter { it.discipleId.isNotEmpty() }
                .mapNotNull { slot -> occupierDisciples.find { d -> d.id == slot.discipleId && d.isAlive } }
        } else {
            val sectDisciplePool = data.aiSectDisciples[sectId] ?: emptyList()
            sectDisciplePool.filter { it.isAlive }.sortedBy { it.realm }.take(AISectAttackManager.TEAM_SIZE)
        }

        // 清理阵亡守军用的池：从占领者池中移除（被占领宗门时）
        val defenderPoolSectId = if (isAiOccupied) targetSect.occupierSectId else sectId
        // 占领判定用的池：正常宗门需全池无化神+才占领；被占领宗门击败守军即占领
        val occupationPoolSectId = sectId
        val fullDefenderPool = data.aiSectDisciples[occupationPoolSectId] ?: emptyList()

        val battleResult = AISectAttackManager.executeSectBattle(attackers, targetSect, defenderDisciples, fullDefenderPool)

        // Apply player casualties and survivor HP/MP
        val deadPlayerIds = battleResult.deadAttackerIds.toSet()
        combatService.processBattleCasualties(
            deadMemberIds = deadPlayerIds,
            survivorHpMap = battleResult.survivorHpMap,
            survivorMpMap = battleResult.survivorMpMap,
            isOutsideSect = true
        )

        // Update AI defender disciples: remove dead defenders from aiSectDisciples
        val deadDefenderIds = battleResult.deadDefenderIds.toSet()
        stateStore.update {
            gameData = gameData.copy(
                aiSectDisciples = gameData.aiSectDisciples.mapValues { (sId, disciples) ->
                    if (sId == defenderPoolSectId) {
                        disciples.filter { it.id !in deadDefenderIds }
                    } else disciples
                },
                worldMapSects = gameData.worldMapSects.map { sect ->
                    if (sect.id == sectId) {
                        sect.copy(
                            garrisonSlots = sect.garrisonSlots.map { slot ->
                                if (slot.discipleId in deadDefenderIds) {
                                    com.xianxia.sect.core.model.GarrisonSlot(index = slot.index)
                                } else slot
                            }
                        )
                    } else sect
                }
            )
        }

        // Build battle log
        val teamMembers = attackers.map { d ->
            BattleLogMember(
                id = d.id,
                name = d.name,
                realm = d.realm,
                realmName = d.realmName,
                hp = battleResult.survivorHpMap[d.id] ?: 0,
                maxHp = d.maxHp,
                mp = battleResult.survivorMpMap[d.id] ?: 0,
                maxMp = d.maxMp,
                isAlive = d.id !in deadPlayerIds,
                portraitRes = d.portraitRes
            )
        }
        val enemyMembers = defenderDisciples.map { d ->
            BattleLogEnemy(name = d.name, realm = d.realm, realmName = d.realmName, portraitRes = d.portraitRes)
        }
        val winResult = when (battleResult.winner) {
            AIBattleWinner.ATTACKER -> BattleResult.WIN
            AIBattleWinner.DEFENDER -> BattleResult.LOSE
            AIBattleWinner.DRAW -> BattleResult.DRAW
        }
        val log = BattleLog(
            year = data.gameYear,
            month = data.gameMonth,
            type = BattleType.SECT_WAR,
            attackerName = "玩家队伍",
            defenderName = targetSect.name,
            result = winResult,
            teamMembers = teamMembers,
            enemies = enemyMembers,
            turns = battleResult.turns,
            teamCasualties = deadPlayerIds.size,
            details = when (battleResult.winner) {
                AIBattleWinner.ATTACKER -> if (battleResult.canOccupy) "攻占了${targetSect.name}" else "击溃了${targetSect.name}的守军"
                AIBattleWinner.DEFENDER -> "进攻${targetSect.name}失败"
                AIBattleWinner.DRAW -> "与${targetSect.name}打成平手"
            }
        )
        val existingLogs = stateStore.battleLogs.value
        val updatedLogs = (existingLogs + log).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
        stateStore.update { battleLogs = updatedLogs }

        // If win, generate rewards and handle occupation
        if (battleResult.winner == AIBattleWinner.ATTACKER) {
            val sectSurvivorIds = attackers.filter { it.id !in deadPlayerIds }.map { it.id }.toSet()
            stateStore.updateDisciplesDirect { disciples ->
                disciples.map { d ->
                    if (d.id in sectSurvivorIds && d.isAlive) d.copyWith(soulPower = d.soulPower + 1)
                    else d
                }
            }

            val warRewards: WarRewards
            if (battleResult.canOccupy) {
                val survivors = attackers.filter { it.id !in deadPlayerIds }
                val garrisonSlots = targetSect.garrisonSlots.mapIndexed { index, _ ->
                    if (index < survivors.size) {
                        val d = survivors[index]
                        com.xianxia.sect.core.model.GarrisonSlot(
                            index = index,
                            discipleId = d.id,
                            discipleName = d.name,
                            discipleRealm = d.realmName,
                            discipleSpiritRootColor = d.spiritRoot.countColor,
                            portraitRes = d.portraitRes
                        )
                    } else {
                        com.xianxia.sect.core.model.GarrisonSlot(index = index)
                    }
                }

                val rewardCount = (80..130).random()
                warRewards = AISectAttackManager.generateWarRewards(targetSect.level, rewardCount)

                // 被占领宗门的存活弟子移入招募列表
                val capturedDisciples = data.aiSectDisciples[sectId]
                    ?.filter { it.isAlive } ?: emptyList()

                stateStore.update {
                    gameData = gameData.copy(
                        worldMapSects = gameData.worldMapSects.map { sect ->
                            if (sect.id == sectId) {
                                sect.copy(
                                    isPlayerOccupied = true,
                                    occupierSectId = playerSect?.id ?: "",
                                    garrisonSlots = garrisonSlots
                                )
                            } else sect
                        },
                        recruitList = gameData.recruitList + capturedDisciples,
                        aiSectDisciples = gameData.aiSectDisciples.toMutableMap().apply {
                            this[sectId] = emptyList()
                        }
                    )
                    addSpiritStones(warRewards.spiritStones)
                    warRewards.equipmentStacks.forEach { inventorySystem.addEquipmentStack(it) }
                    warRewards.manualStacks.forEach { inventorySystem.addManualStack(it) }
                    warRewards.pills.forEach { inventorySystem.addPill(it) }
                    warRewards.materials.forEach { inventorySystem.addMaterial(it) }
                    warRewards.herbs.forEach { inventorySystem.addHerb(it) }
                    warRewards.seeds.forEach { inventorySystem.addSeed(it) }
                }
            } else {
                val rewardCount = (20..60).random()
                warRewards = AISectAttackManager.generateWarRewards(targetSect.level, rewardCount)
                stateStore.update {
                    addSpiritStones(warRewards.spiritStones)
                    warRewards.equipmentStacks.forEach { inventorySystem.addEquipmentStack(it) }
                    warRewards.manualStacks.forEach { inventorySystem.addManualStack(it) }
                    warRewards.pills.forEach { inventorySystem.addPill(it) }
                    warRewards.materials.forEach { inventorySystem.addMaterial(it) }
                    warRewards.herbs.forEach { inventorySystem.addHerb(it) }
                    warRewards.seeds.forEach { inventorySystem.addSeed(it) }
                }
            }
            stateStore.setPendingBattleResult(BattleResultUIData(
                battleLogId = log.id,
                victory = true,
                teamMembers = teamMembers,
                rewards = warRewardsToBattleRewardItems(warRewards)
            ))
        } else {
            stateStore.setPendingBattleResult(BattleResultUIData(
                battleLogId = log.id,
                victory = false,
                teamMembers = teamMembers,
                rewards = emptyList()
            ))
        }
    }

    private fun warRewardsToBattleRewardItems(rewards: WarRewards): List<BattleRewardItem> {
        val items = mutableListOf<BattleRewardItem>()
        if (rewards.spiritStones > 0) {
            items.add(BattleRewardItem(name = "灵石", quantity = rewards.spiritStones.toInt(), rarity = 1, type = "spiritStones"))
        }
        rewards.equipmentStacks.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "equipment")) }
        rewards.manualStacks.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "manual")) }
        rewards.pills.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "pill")) }
        rewards.materials.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "material")) }
        rewards.herbs.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "herb")) }
        rewards.seeds.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "seed")) }
        return items
    }



    suspend fun assignGarrisonDisciple(sectId: String, slotIndex: Int, discipleId: String) {
        val data = stateStore.gameData.value
        val disciple = stateStore.disciples.value.find { it.id == discipleId && it.isAlive } ?: return

        // Prevent assigning same disciple to multiple garrison slots
        val targetSect = data.worldMapSects.find { it.id == sectId } ?: return
        val alreadyGarrisoned = targetSect.garrisonSlots.any { it.discipleId == discipleId }
        if (alreadyGarrisoned) return

        stateStore.update {
            gameData = gameData.copy(
                worldMapSects = gameData.worldMapSects.map { sect ->
                    if (sect.id == sectId) {
                        val slots = sect.garrisonSlots.map { slot ->
                            if (slot.index == slotIndex) {
                                com.xianxia.sect.core.model.GarrisonSlot(
                                    index = slotIndex,
                                    discipleId = disciple.id,
                                    discipleName = disciple.name,
                                    discipleRealm = disciple.realmName,
                                    discipleSpiritRootColor = disciple.spiritRoot.countColor,
                                    portraitRes = disciple.portraitRes
                                )
                            } else slot
                        }
                        sect.copy(garrisonSlots = slots)
                    } else sect
                }
            )
        }
    }

    suspend fun removeGarrisonDisciple(sectId: String, slotIndex: Int) {
        stateStore.update {
            gameData = gameData.copy(
                worldMapSects = gameData.worldMapSects.map { sect ->
                    if (sect.id == sectId) {
                        val slots = sect.garrisonSlots.map { slot ->
                            if (slot.index == slotIndex) {
                                com.xianxia.sect.core.model.GarrisonSlot(index = slotIndex)
                            } else slot
                        }
                        sect.copy(garrisonSlots = slots)
                    } else sect
                }
            )
        }
    }

    suspend fun attackWorldLevel(levelId: String, discipleIds: List<String?>) {
        val data = stateStore.gameData.value
        val level = data.worldLevels.find { it.id == levelId } ?: return
        if (level.defeated) return

        val validIds = discipleIds.filterNotNull()
        if (validIds.isEmpty()) return

        val allDisciples = stateStore.disciples.value
        val combatDisciples = validIds.mapNotNull { id -> allDisciples.find { it.id == id && it.isAlive } }
        if (combatDisciples.isEmpty()) return

        val equipmentMap = stateStore.equipmentInstances.value.associateBy { it.id }
        val manualMap = stateStore.manualInstances.value.associateBy { it.id }

        val beastTypeName = if (level.isBeast) {
            GameConfig.Beast.getType(level.beastType ?: 0).name
        } else null

        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        val battle = battleSystem.createBattle(
            disciples = combatDisciples,
            equipmentMap = equipmentMap,
            manualMap = manualMap,
            beastLevel = level.realm,
            beastCount = level.count,
            beastType = beastTypeName,
            manualProficiencies = allProficiencies
        )

        val result = battleSystem.executeBattle(battle)

        // Update disciple HP/MP after battle
        val hpMap = result.battle.team.associate { it.id to (it.hp to it.mp) }
        val survivorIds = result.battle.team.filter { !it.isDead }.map { it.id }.toSet()
        val updatedDisciples = stateStore.disciples.value.map { d ->
            val (hp, mp) = hpMap[d.id] ?: return@map d
            if (d.id !in survivorIds) {
                d.copy(isAlive = false, status = DiscipleStatus.DEAD)
            } else {
                d.copy(combat = d.combat.copy(
                    currentHp = hp.coerceIn(0, d.maxHp),
                    currentMp = mp.coerceIn(0, d.maxMp)
                ))
            }
        }
        stateStore.update { disciples = updatedDisciples }

        // Clean up dead disciples' positions and equipment
        val combatDiscipleIds = combatDisciples.map { it.id }.toSet()
        val deadIds = updatedDisciples
            .filter { it.id in combatDiscipleIds && !it.isAlive }
            .map { it.id }.toSet()
        if (deadIds.isNotEmpty()) {
            combatService.processBattleCasualties(deadIds, emptyMap(), emptyMap(), isOutsideSect = true)
        }

        // Build battle log
        val teamMembers = result.battle.team.map { m ->
            BattleLogMember(
                id = m.id, name = m.name, realm = m.realm, realmName = m.realmName,
                hp = m.hp, maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp,
                isAlive = !m.isDead, portraitRes = m.portraitRes
            )
        }
        val enemies = result.battle.beasts.map { b ->
            BattleLogEnemy(name = b.name, realm = b.realm, realmName = b.realmName, portraitRes = b.portraitRes)
        }
        val rounds = result.log.rounds.map { r ->
            BattleLogRound(
                roundNumber = r.roundNumber,
                actions = r.actions.map { a ->
                    BattleLogAction(
                        type = a.type,
                        attacker = a.attacker,
                        attackerType = a.attackerType,
                        target = a.target,
                        damage = a.damage,
                        damageType = a.damageType,
                        isCrit = a.isCrit,
                        isKill = a.isKill,
                        message = a.message
                    )
                }
            )
        }
        val log = BattleLog(
            year = data.gameYear,
            month = data.gameMonth,
            type = BattleType.PVE,
            attackerName = "玩家队伍",
            defenderName = if (level.isBeast) level.beastName else level.guardianName,
            result = if (result.victory) BattleResult.WIN else BattleResult.LOSE,
            teamMembers = teamMembers,
            enemies = enemies,
            rounds = rounds,
            turns = result.turnCount,
            teamCasualties = teamMembers.count { !survivorIds.contains(it.name) },
            beastsDefeated = if (result.victory) level.count else result.battle.beasts.count { it.isDead },
            details = if (result.victory) "击败了${if (level.isBeast) level.beastName else level.guardianName}" else "被${if (level.isBeast) level.beastName else level.guardianName}击败"
        )

        val existingLogs = stateStore.battleLogs.value
        val updatedLogs = (existingLogs + log).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)

        if (result.victory) {
            stateStore.updateDisciplesDirect { disciples ->
                disciples.map { d ->
                    if (d.id in survivorIds && d.isAlive) {
                        var modified = d.copyWith(soulPower = d.soulPower + 1)
                        if (modified.talentIds.any { id ->
                            TalentDatabase.getById(id)?.effects?.containsKey("winBattleRandomAttrPlus") == true
                        }) {
                            val r = kotlin.random.Random.nextInt(17)
                            val s = modified.skills
                            val c = modified.combat
                            when (r) {
                                0 -> s.intelligence++
                                1 -> s.comprehension++
                                2 -> s.charm++
                                3 -> s.loyalty++
                                4 -> s.artifactRefining++
                                5 -> s.pillRefining++
                                6 -> s.spiritPlanting++
                                7 -> s.mining++
                                8 -> s.teaching++
                                9 -> s.morality++
                                10 -> c.baseHp++
                                11 -> c.baseMp++
                                12 -> c.basePhysicalAttack++
                                13 -> c.baseMagicAttack++
                                14 -> c.basePhysicalDefense++
                                15 -> c.baseMagicDefense++
                                16 -> c.baseSpeed++
                            }
                        }
                        modified
                    } else d
                }
            }

            val allRewards = mutableListOf<BattleRewardItem>()
            stateStore.update {
                if (level.isBeast) {
                    allRewards.addAll(handleBeastLevelVictory(level))
                    val engineSsRewards = result.rewards["spiritStones"] ?: 0
                    if (engineSsRewards > 0) {
                        addSpiritStones(engineSsRewards.toLong())
                        allRewards.add(BattleRewardItem(
                            name = "灵石", quantity = engineSsRewards, rarity = 1, type = "spiritStones"
                        ))
                    }
                } else {
                    allRewards.addAll(handleCaveLevelVictory(level))
                }
                gameData = gameData.copy(
                    worldLevels = gameData.worldLevels.map { l ->
                        if (l.id == levelId) l.copy(defeated = true) else l
                    }
                )
                battleLogs = updatedLogs
            }

            stateStore.setPendingBattleResult(BattleResultUIData(
                battleLogId = log.id,
                victory = true,
                teamMembers = teamMembers,
                rewards = allRewards
            ))
        } else {
            stateStore.setPendingBattleResult(BattleResultUIData(
                battleLogId = log.id,
                victory = false,
                teamMembers = teamMembers,
                rewards = emptyList()
            ))
            stateStore.update { battleLogs = updatedLogs }
        }
    }

    /**
     * Execute an instant scout mission against a target sect.
     * Resolves immediately like attackWorldLevel — no travel time.
     */
    suspend fun scoutSect(sectId: String, memberIds: List<String>) {
        val data = stateStore.gameData.value
        val targetSect = data.worldMapSects.find { it.id == sectId } ?: return

        val allDisciples = stateStore.disciples.value
        val combatDisciples = memberIds.mapNotNull { id -> allDisciples.find { it.id == id && it.isAlive } }
        if (combatDisciples.isEmpty()) return

        val aiDefenders = (data.aiSectDisciples[sectId] ?: emptyList())
            .filter { it.isAlive && it.realm in 7..9 }
            .shuffled()
            .take(kotlin.random.Random.nextInt(5, 11))

        val equipmentMap = stateStore.equipmentInstances.value.associateBy { it.id }
        val manualMap = stateStore.manualInstances.value.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        // Build player Combatants (full stats, skills)
        val playerCombatants = combatDisciples.map { disciple ->
            val discipleEquipment = buildMap {
                disciple.equipment.weaponId?.let { id -> equipmentMap[id]?.let { put(id, it) } }
                disciple.equipment.armorId?.let { id -> equipmentMap[id]?.let { put(id, it) } }
                disciple.equipment.bootsId?.let { id -> equipmentMap[id]?.let { put(id, it) } }
                disciple.equipment.accessoryId?.let { id -> equipmentMap[id]?.let { put(id, it) } }
            }
            val discipleManuals = disciple.manualIds.mapNotNull { id -> manualMap[id]?.let { id to it } }.toMap()
            val discipleProficiencies = allProficiencies[disciple.id] ?: emptyMap()
            val stats = disciple.getFinalStats(discipleEquipment, discipleManuals, discipleProficiencies)
            val effectiveHp = if (disciple.combat.currentHp < 0) stats.maxHp else disciple.combat.currentHp.coerceAtMost(stats.maxHp)
            val effectiveMp = if (disciple.combat.currentMp < 0) stats.maxMp else disciple.combat.currentMp.coerceAtMost(stats.maxMp)
            val skills = disciple.manualIds.mapNotNull { manualId ->
                val manual = discipleManuals[manualId] ?: return@mapNotNull null
                val proficiencyData = discipleProficiencies[manualId]
                val masteryLevel = proficiencyData?.masteryLevel ?: 0
                val baseSkill = manual.skill ?: return@mapNotNull null
                val multiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(baseSkill.damageMultiplier, masteryLevel)
                baseSkill.copy(damageMultiplier = multiplier).toCombatSkill(manualName = manual.name)
            }
            Combatant(
                id = disciple.id, name = disciple.name,
                side = CombatantSide.DEFENDER,
                hp = effectiveHp, maxHp = stats.maxHp,
                mp = effectiveMp, maxMp = stats.maxMp,
                physicalAttack = stats.physicalAttack, magicAttack = stats.magicAttack,
                physicalDefense = stats.physicalDefense, magicDefense = stats.magicDefense,
                speed = stats.speed, critRate = stats.critRate,
                skills = skills, realm = disciple.realm, realmName = disciple.realmName,
                element = disciple.spiritRoot.types.firstOrNull()?.trim() ?: "metal",
                portraitRes = disciple.portraitRes
            )
        }

        // Build AI defender Combatants with full stats and skills
        val aiCombatants = aiDefenders.map { d ->
            AISectAttackManager.convertToCombatant(d, CombatantSide.ATTACKER)
        }

        val battle = Battle(
            team = playerCombatants,
            beasts = aiCombatants,
            turn = 0, isFinished = false, winner = null,
            maxTurns = Int.MAX_VALUE
        )

        val result = battleSystem.executeBattle(battle)

        // Update disciple HP/MP
        val hpMap = result.battle.team.associate { it.id to (it.hp to it.mp) }
        val survivorIds = result.battle.team.filter { !it.isDead }.map { it.id }.toSet()
        val updatedDisciples = stateStore.disciples.value.map { d ->
            val (hp, mp) = hpMap[d.id] ?: return@map d
            if (d.id !in survivorIds) {
                d.copy(isAlive = false, status = DiscipleStatus.DEAD)
            } else {
                d.copy(combat = d.combat.copy(
                    currentHp = hp.coerceIn(0, d.maxHp),
                    currentMp = mp.coerceIn(0, d.maxMp)
                ))
            }
        }
        // Set scouting disciples back to IDLE
        val scoutIds = memberIds.toSet()
        val finalDisciples = updatedDisciples.map { d ->
            if (d.id in scoutIds && d.isAlive && d.status != DiscipleStatus.IDLE) d.copy(status = DiscipleStatus.IDLE) else d
        }
        stateStore.update { disciples = finalDisciples }

        // Clean up dead disciples' positions and equipment
        val scoutDiscipleIds = combatDisciples.map { it.id }.toSet()
        val scoutDeadIds = finalDisciples
            .filter { it.id in scoutDiscipleIds && !it.isAlive }
            .map { it.id }.toSet()
        if (scoutDeadIds.isNotEmpty()) {
            combatService.processBattleCasualties(scoutDeadIds, emptyMap(), emptyMap(), isOutsideSect = true)
        }

        // Build BattleLog
        val teamMembers = result.battle.team.map { m ->
            BattleLogMember(
                id = m.id, name = m.name, realm = m.realm, realmName = m.realmName,
                hp = m.hp, maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp,
                isAlive = !m.isDead, portraitRes = m.portraitRes
            )
        }
        val enemies = aiCombatants.map { b ->
            BattleLogEnemy(name = b.name, realm = b.realm, realmName = b.realmName, portraitRes = b.portraitRes)
        }
        val rounds = result.log.rounds.map { r ->
            BattleLogRound(
                roundNumber = r.roundNumber,
                actions = r.actions.map { a ->
                    BattleLogAction(
                        type = a.type,
                        attacker = a.attacker,
                        attackerType = a.attackerType,
                        target = a.target,
                        damage = a.damage,
                        damageType = a.damageType,
                        isCrit = a.isCrit,
                        isKill = a.isKill,
                        message = a.message
                    )
                }
            )
        }
        val victory = result.victory
        val log = BattleLog(
            year = data.gameYear,
            month = data.gameMonth,
            type = BattleType.SCOUT,
            attackerName = "探查队伍",
            defenderName = targetSect.name,
            result = if (victory) BattleResult.WIN else BattleResult.LOSE,
            teamMembers = teamMembers,
            enemies = enemies,
            rounds = rounds,
            turns = result.turnCount,
            teamCasualties = teamMembers.size - survivorIds.size,
            beastsDefeated = if (victory) aiCombatants.count { result.battle.beasts.any { b -> b.id == it.id && b.isDead } } else 0,
            details = if (victory) "成功探查了${targetSect.name}" else "探查${targetSect.name}失败"
        )

        val existingLogs = stateStore.battleLogs.value
        val updatedLogs = (existingLogs + log).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
        stateStore.update { battleLogs = updatedLogs }
        stateStore.setPendingBattleResult(BattleResultUIData(
            battleLogId = log.id,
            victory = victory,
            teamMembers = teamMembers,
            rewards = emptyList()
        ))

        // On victory: generate SectScoutInfo
        if (victory) {
            val allSectDisciples = data.aiSectDisciples[sectId] ?: emptyList()
            val realmDistribution = allSectDisciples.groupingBy { it.realm }.eachCount()
            val scoutInfo = SectScoutInfo(
                sectId = sectId,
                sectName = targetSect.name,
                scoutYear = data.gameYear,
                scoutMonth = data.gameMonth,
                discipleCount = allSectDisciples.size,
                maxRealm = allSectDisciples.maxOfOrNull { it.realm } ?: 9,
                disciples = realmDistribution,
                isKnown = true,
                expiryYear = data.gameYear + 1,
                expiryMonth = data.gameMonth
            )
            updateGameDataSync {
                val existingDetail = it.sectDetails[sectId] ?: SectDetail(sectId = sectId)
                it.copy(sectDetails = it.sectDetails + (sectId to existingDetail.copy(scoutInfo = scoutInfo)))
            }
        }
    }

    private fun handleBeastLevelVictory(level: WorldLevel): List<BattleRewardItem> {
        val rewards = mutableListOf<BattleRewardItem>()
        val beastConfig = GameConfig.Beast.getType(level.beastType ?: 0)
        val tier = GameConfig.Realm.getMaxRarity(level.realm)
        for (i in 0 until level.count) {
            val materialCount = kotlin.random.Random.nextInt(1, 4)
            repeat(materialCount) {
                val beastMaterial = com.xianxia.sect.core.registry.BeastMaterialDatabase.getRandomMaterialByBeastType(
                    beastConfig.name, tier
                )
                if (beastMaterial != null) {
                    val material = Material(
                        id = java.util.UUID.randomUUID().toString(),
                        name = beastMaterial.name,
                        rarity = beastMaterial.rarity,
                        description = beastMaterial.description,
                        category = beastMaterial.materialCategory,
                        quantity = 1
                    )
                    val result = inventorySystem.addMaterial(material)
                    if (result == AddResult.SUCCESS || result == AddResult.PARTIAL_SUCCESS) {
                        rewards.add(BattleRewardItem(
                            itemId = material.id,
                            name = material.name,
                            quantity = 1,
                            rarity = material.rarity,
                            type = "material"
                        ))
                    }
                }
            }
        }
        return rewards
    }

    private fun handleCaveLevelVictory(level: WorldLevel): List<BattleRewardItem> {
        val rewards = mutableListOf<BattleRewardItem>()
        val config = LevelGenerator.getCaveReward(level.realm)
        val spiritStones = (config.baseSpiritStones * (0.8 + kotlin.random.Random.nextDouble() * 0.4)).toLong()
        addSpiritStones(spiritStones)
        if (spiritStones > 0) {
            rewards.add(BattleRewardItem(
                name = "灵石",
                quantity = spiritStones.toInt(),
                rarity = 1,
                type = "spiritStones"
            ))
        }

        val itemCount = kotlin.random.Random.nextInt(1, 7)
        val (minRarity, maxRarity) = config.rarityRange
        repeat(itemCount) {
            val rarity = kotlin.random.Random.nextInt(minRarity, maxRarity + 1)
            val typeRoll = kotlin.random.Random.nextDouble()
            when {
                typeRoll < 0.33 -> {
                    val manual = com.xianxia.sect.core.registry.ManualDatabase.generateRandom(rarity)
                    if (manual != null) {
                        val result = inventorySystem.addManualStack(manual)
                        if (result == AddResult.SUCCESS || result == AddResult.PARTIAL_SUCCESS) {
                            rewards.add(BattleRewardItem(
                                itemId = manual.id,
                                name = manual.name,
                                quantity = 1,
                                rarity = manual.rarity,
                                type = "manual"
                            ))
                        }
                    }
                }
                typeRoll < 0.66 -> {
                    val equip = com.xianxia.sect.core.registry.EquipmentDatabase.generateRandom(rarity)
                    if (equip != null) {
                        val result = inventorySystem.addEquipmentStack(equip)
                        if (result == AddResult.SUCCESS || result == AddResult.PARTIAL_SUCCESS) {
                            rewards.add(BattleRewardItem(
                                itemId = equip.id,
                                name = equip.name,
                                quantity = 1,
                                rarity = equip.rarity,
                                type = "equipment"
                            ))
                        }
                    }
                }
                else -> {
                    val pill = com.xianxia.sect.core.registry.ItemDatabase.generateRandomPill(rarity)
                    if (pill != null) {
                        val result = inventorySystem.addPill(pill)
                        if (result == AddResult.SUCCESS || result == AddResult.PARTIAL_SUCCESS) {
                            rewards.add(BattleRewardItem(
                                itemId = pill.id,
                                name = pill.name,
                                quantity = 1,
                                rarity = pill.rarity,
                                type = "pill"
                            ))
                        }
                    }
                }
            }
        }
        return rewards
    }

    private fun migratePatrolSlotsIfNeeded(
        gameData: GameData,
        disciples: List<Disciple>
    ): Pair<GameData, List<Disciple>> {
        val numTowers = gameData.placedBuildings.count { it.displayName == "巡视楼" }
        if (numTowers == 0) return gameData to disciples

        val oldSlots = gameData.patrolSlots
        val expectedSize = numTowers * 8
        if (oldSlots.size <= expectedSize) return gameData to disciples

        Log.w(TAG, "迁移巡逻槽位: ${oldSlots.size}槽/${numTowers}塔 → ${expectedSize}槽")

        var updatedDisciples = disciples.toMutableList()
        val newSlots = mutableListOf<PatrolSlot>()
        var newGlobalIndex = 0

        for (towerIdx in 0 until numTowers) {
            val oldStart = towerIdx * 10
            for (localIdx in 0 until 8) {
                val globalIdx = oldStart + localIdx
                if (globalIdx < oldSlots.size) {
                    newSlots.add(oldSlots[globalIdx].copy(index = newGlobalIndex))
                } else {
                    newSlots.add(PatrolSlot(index = newGlobalIndex))
                }
                newGlobalIndex++
            }
            // 释放被裁掉的槽位（local 8-9）中的弟子
            for (discardLocalIdx in 8 until 10) {
                val discardGlobalIdx = oldStart + discardLocalIdx
                if (discardGlobalIdx < oldSlots.size) {
                    val discardedSlot = oldSlots[discardGlobalIdx]
                    if (discardedSlot.discipleId.isNotEmpty()) {
                        updatedDisciples = updatedDisciples.map {
                            if (it.id == discardedSlot.discipleId) it.copy(status = DiscipleStatus.IDLE) else it
                        }.toMutableList()
                    }
                }
            }
        }

        // 释放超出 numTowers * 10 的孤立槽位中的弟子
        val orphanedStart = numTowers * 10
        for (i in orphanedStart until oldSlots.size) {
            val slot = oldSlots[i]
            if (slot.discipleId.isNotEmpty()) {
                updatedDisciples = updatedDisciples.map {
                    if (it.id == slot.discipleId) it.copy(status = DiscipleStatus.IDLE) else it
                }.toMutableList()
            }
        }

        val newConfigs = gameData.patrolConfigs.take(numTowers)

        Log.i(TAG, "巡逻槽位迁移完成: ${oldSlots.size} → ${newSlots.size}")
        return gameData.copy(patrolSlots = newSlots, patrolConfigs = newConfigs) to updatedDisciples
    }
}
