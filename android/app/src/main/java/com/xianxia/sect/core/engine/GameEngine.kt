package com.xianxia.sect.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.engine.service.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.BattleSystem
import com.xianxia.sect.core.engine.production.ProductionCoordinator
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject
import android.util.Log

/** GiftResult 类型别名，保持向后兼容 */
typealias GiftResult = DiplomacyService.GiftResult

/** ElderBonusData 类型别名，保持向后兼容 */
typealias ElderBonusData = FormulaService.ElderBonusData

data class GameStateSnapshot(
    val gameData: GameData,
    val disciples: List<Disciple>,
    val equipment: List<Equipment>,
    val manuals: List<Manual>,
    val pills: List<Pill>,
    val materials: List<Material>,
    val herbs: List<Herb>,
    val seeds: List<Seed>,
    val teams: List<ExplorationTeam>,
    val events: List<GameEvent>,
    val battleLogs: List<BattleLog>,
    val alliances: List<Alliance>
)

/**
 * 游戏引擎主类
 *
 * 作为各个 Service 的统一协调层，负责：
 * - 组合所有 Service 并提供统一的访问入口
 * - 管理 transactionMutex 确保跨 Service 操作的原子性
 * - 暴露 StateFlow 给 UI 层
 *
 * 业务逻辑拆分到以下 Service：
 * - CultivationService: 时间推进、修炼系统、AI宗门逻辑
 * - InventoryService: 物品管理、仓库操作
 * - DiscipleService: 弟子 CRUD、状态机、培养
 * - CombatService: 战斗执行、战斗日志、结果处理
 * - ExplorationService: 探索队伍、洞府探索、探查任务
 * - EventService: 游戏事件、商人系统
 * - BuildingService: 建筑管理、生产操作
 * - SaveService: 存档快照、状态验证
 * - DiplomacyService: 外交系统（送礼、结盟）
 * - RedeemCodeService: 兑换码系统
 * - FormulaService: 公式计算（成功率、工作时间等）
 */
@ViewModelScoped
class GameEngine @Inject constructor(
    // 可通过 DI 注入的依赖（System 层组件）
    private val inventorySystem: InventorySystem,
    private val battleSystem: BattleSystem,
    private val productionCoordinator: ProductionCoordinator
) {
    companion object {
        private const val TAG = "GameEngine"
    }

    // 核心互斥锁 - 用于跨 Service 的原子性操作（使用协程 Mutex，与 UnifiedGameStateManager 兼容）
    private val transactionMutex = Mutex()

    private var _isPaused = false

    // ==================== 核心状态 (MutableStateFlow) ====================

    private val _gameData = MutableStateFlow(GameData())
    private val _disciples = MutableStateFlow<List<Disciple>>(emptyList())
    private val _equipment = MutableStateFlow<List<Equipment>>(emptyList())
    private val _manuals = MutableStateFlow<List<Manual>>(emptyList())
    private val _pills = MutableStateFlow<List<Pill>>(emptyList())
    private val _materials = MutableStateFlow<List<Material>>(emptyList())
    private val _herbs = MutableStateFlow<List<Herb>>(emptyList())
    private val _seeds = MutableStateFlow<List<Seed>>(emptyList())
    private val _events = MutableStateFlow<List<GameEvent>>(emptyList())
    private val _battleLogs = MutableStateFlow<List<BattleLog>>(emptyList())
    private val _teams = MutableStateFlow<List<ExplorationTeam>>(emptyList())

    // ==================== 核心服务实例 (by lazy，在 StateFlow 声明之后初始化) ====================
    //
    // 以下 8 个 Service 不通过 DI 注入，因为它们的构造函数需要 MutableStateFlow 引用。
    // 根据 CoreModule.kt 的设计说明，它们在此手动创建，生命周期跟随 GameEngine (@ViewModelScoped)。
    //

    /** 库存服务 */
    private val inventoryService by lazy {
        InventoryService(
            _equipment = _equipment,
            _manuals = _manuals,
            _pills = _pills,
            _materials = _materials,
            _herbs = _herbs,
            _seeds = _seeds,
            inventorySystem = inventorySystem,
            addEvent = { message, type -> eventService.addGameEvent(message, type) }
        )
    }

    /** 事件服务（优先创建，被其他 Service 引用） */
    private val eventService by lazy {
        EventService(
            _gameData = _gameData,
            _events = _events,
            addEvent = { message, type -> /* 直接记录 */ Unit },
            transactionMutex = transactionMutex
        )
    }

    /** 弟子服务 */
    private val discipleService by lazy {
        DiscipleService(
            _gameData = _gameData,
            _disciples = _disciples,
            _equipment = _equipment,
            addEvent = { message, type -> eventService.addGameEvent(message, type) },
            transactionMutex = transactionMutex
        )
    }

    /** 战斗服务 */
    private val combatService by lazy {
        CombatService(
            _gameData = _gameData,
            _disciples = _disciples,
            _equipment = _equipment,
            _manuals = _manuals,
            _battleLogs = _battleLogs,
            battleSystem = battleSystem,
            addEvent = { message, type -> eventService.addGameEvent(message, type) },
            transactionMutex = transactionMutex
        )
    }

    /** 探索服务 */
    private val explorationService by lazy {
        ExplorationService(
            _gameData = _gameData,
            _disciples = _disciples,
            _teams = _teams,
            addEvent = { message, type -> eventService.addGameEvent(message, type) },
            transactionMutex = transactionMutex
        )
    }

    /** 建筑服务 */
    private val buildingService by lazy {
        BuildingService(
            _gameData = _gameData,
            _disciples = _disciples,
            _herbs = _herbs,
            _equipment = _equipment,
            _pills = _pills,
            productionCoordinator = productionCoordinator,
            addEvent = { message, type -> eventService.addGameEvent(message, type) },
            transactionMutex = transactionMutex
        )
    }

    /** 存档服务 */
    private val saveService by lazy {
        SaveService(
            _gameData = _gameData,
            _disciples = _disciples,
            _equipment = _equipment,
            _manuals = _manuals,
            _pills = _pills,
            _materials = _materials,
            _herbs = _herbs,
            _seeds = _seeds,
            _events = _events,
            _battleLogs = _battleLogs,
            _teams = _teams
        )
    }

    /** 修炼服务（最后创建，依赖最多） */
    private val cultivationService by lazy {
        CultivationService(
            _gameData = _gameData,
            _disciples = _disciples,
            _equipment = _equipment,
            _manuals = _manuals,
            _pills = _pills,
            _materials = _materials,
            _herbs = _herbs,
            _seeds = _seeds,
            _events = _events,
            _battleLogs = _battleLogs,
            _teams = _teams,
            inventorySystem = inventorySystem,
            battleSystem = battleSystem,
            productionCoordinator = productionCoordinator,
            addEvent = { message, type -> eventService.addGameEvent(message, type) },
            transactionMutex = transactionMutex
        )
    }

    // ==================== 派生服务实例（依赖上述核心服务）====================

    /** 外交服务 */
    private val diplomacyService by lazy {
        DiplomacyService(
            _gameData = _gameData,
            _manuals = _manuals,
            _equipment = _equipment,
            _pills = _pills,
            addEvent = { message, type -> eventService.addGameEvent(message, type) },
            transactionMutex = transactionMutex
        ).also { it._disciples = _disciples }
    }

    /** 兑换码服务 */
    private val redeemCodeService by lazy {
        RedeemCodeService(
            _gameData = _gameData,
            _disciples = _disciples,
            inventoryService = inventoryService,
            addEvent = { message, type -> eventService.addGameEvent(message, type) }
        )
    }

    /** 公式计算服务 */
    private val formulaService by lazy {
        FormulaService(
            _gameData = _gameData,
            _disciples = _disciples
        )
    }

    // ==================== 公开 StateFlow 访问器 ====================

    val gameData: StateFlow<GameData> = _gameData
    val disciples: StateFlow<List<Disciple>> = _disciples
    val equipment: StateFlow<List<Equipment>> = _equipment
    val manuals: StateFlow<List<Manual>> = _manuals
    val pills: StateFlow<List<Pill>> = _pills
    val materials: StateFlow<List<Material>> = _materials
    val herbs: StateFlow<List<Herb>> = _herbs
    val seeds: StateFlow<List<Seed>> = _seeds
    val events: StateFlow<List<GameEvent>> = _events
    val battleLogs: StateFlow<List<BattleLog>> = _battleLogs
    val teams: StateFlow<List<ExplorationTeam>> = _teams

    // ==================== 高频数据 StateFlow (从 CultivationService 获取) ====================

    /**
     * 高频更新数据 - 用于实时修炼进度、装备培养、功法熟练度等
     *
     * 数据结构来自 CultivationService 的 HighFrequencyData，
     * 通过 map 转换为 ViewModel 需要的格式
     */
    val highFrequencyData: StateFlow<HighFrequencyData> = cultivationService.getHighFrequencyData()

    /**
     * 实时修炼数据 - 每个弟子的当前修炼值
     */
    val realtimeCultivation: StateFlow<Map<String, Double>> =
        cultivationService.getHighFrequencyData()
            .map { it.realtimeCultivation ?: emptyMap() }
            .stateIn(
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
                emptyMap()
            )

    // ==================== 建筑/炼丹相关 StateFlow ====================

    /** 建筑槽位列表 (锻造槽位) */
    val buildingSlots: StateFlow<List<BuildingSlot>> by lazy {
        MutableStateFlow(buildingService.getBuildingSlots())
    }

    /** 炼丹槽位列表 */
    val alchemySlots: StateFlow<List<AlchemySlot>> by lazy {
        MutableStateFlow(buildingService.getAlchemySlots())
    }

    // ==================== 初始化方法 ====================

    /**
     * Initialize game engine with new game data
     */
    fun initializeNewGame(gameData: GameData) {
        runBlocking { transactionMutex.withLock {
            _gameData.value = gameData
        } }
    }

    /**
     * Load game from save data
     */
    fun loadGame(
        gameData: GameData,
        disciples: List<Disciple>,
        equipment: List<Equipment>,
        manuals: List<Manual>,
        pills: List<Pill>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>
    ) {
        runBlocking { transactionMutex.withLock {
            _gameData.value = gameData
            _disciples.value = disciples
            _equipment.value = equipment
            _manuals.value = manuals
            _pills.value = pills
            _materials.value = materials
            _herbs.value = herbs
            _seeds.value = seeds

            saveService.restoreCollections(
                disciples = disciples,
                equipment = equipment,
                manuals = manuals,
                pills = pills,
                materials = materials,
                herbs = herbs,
                seeds = seeds,
                events = emptyList(),
                battleLogs = emptyList(),
                teams = emptyList()
            )
            checkAndCollectCompletedAlchemySlots()
        } }
    }

    // ==================== 游戏生命周期方法 (从 GameEngineAdapter 迁移) ====================

    /**
     * 加载完整游戏数据（包含所有域）
     *
     * 从 GameEngineAdapter 迁移而来，
     * 支持加载 teams、events、battleLogs、alliances 等完整数据
     */
    suspend fun loadData(
        gameData: GameData,
        disciples: List<Disciple>,
        equipment: List<Equipment>,
        manuals: List<Manual>,
        pills: List<Pill>,
        materials: List<Material> = emptyList(),
        herbs: List<Herb> = emptyList(),
        seeds: List<Seed> = emptyList(),
        teams: List<ExplorationTeam>,
        events: List<GameEvent>,
        battleLogs: List<BattleLog> = emptyList(),
        alliances: List<Alliance> = emptyList()
    ) {
        loadGame(gameData, disciples, equipment, manuals, pills, materials, herbs, seeds)
        // 加载额外的数据到对应的 StateFlow
        runBlocking { transactionMutex.withLock {
            _teams.value = teams
            _events.value = events
            _battleLogs.value = battleLogs
        } }
    }

    /**
     * 创建新游戏
     *
     * 从 GameEngineAdapter 迁移而来
     */
    suspend fun createNewGame(sectName: String, currentSlot: Int = 1) {
        initializeNewGame(GameData(sectName = sectName))
        updateGameData { it.copy(currentSlot = currentSlot) }

        val worldSects = WorldMapGenerator.generateWorldSects(sectName)
        val sectRelations = WorldMapGenerator.initializeSectRelations(worldSects)
        updateGameData { it.copy(worldMapSects = worldSects, sectRelations = sectRelations) }

        repeat(3) {
            discipleService.recruitDisciple()
        }

        val initialManual = Manual(
            id = java.util.UUID.randomUUID().toString(),
            name = "基础心法",
            rarity = 1,
            description = "一门基础的心法功法",
            type = ManualType.MIND,
            stats = mapOf("hp" to 10, "mp" to 10),
            minRealm = 9
        )
        inventoryService.addManualToWarehouse(initialManual)

        updateGameData { it.copy(
            herbGardenPlantSlots = (0 until HerbGardenSystem.MAX_PLANT_SLOTS).map {
                PlantSlotData(index = it)
            },
            availableMissions = MissionSystem.generatePermanentSectMissions()
        )}
    }

    /**
     * 重新开始游戏
     *
     * 从 GameEngineAdapter 迁移而来
     */
    fun restartGame() {
        runBlocking { transactionMutex.withLock {
            _gameData.value = GameData()
            _disciples.value = emptyList()
            _equipment.value = emptyList()
            _manuals.value = emptyList()
            _pills.value = emptyList()
            _materials.value = emptyList()
            _herbs.value = emptyList()
            _seeds.value = emptyList()
            _events.value = emptyList()
            _battleLogs.value = emptyList()
            _teams.value = emptyList()
            cultivationService.resetHighFrequencyData()
        } }
    }

    // ==================== 原子更新工具方法 (从 GameEngineAdapter 迁移) ====================

    /**
     * 原子性更新 GameData (协程版本)
     *
     * 从 GameEngineAdapter 迁移而来，
     * 使用 transactionMutex 确保线程安全
     */
    suspend fun updateGameData(update: (GameData) -> GameData) {
        runBlocking { transactionMutex.withLock {
            _gameData.value = update(_gameData.value)
        } }
    }

    /**
     * 原子性更新 GameData (同步版本)
     *
     * 从 GameEngineAdapter 迁移而来
     */
    fun updateGameDataSync(update: (GameData) -> GameData) {
        runBlocking { transactionMutex.withLock {
            _gameData.value = update(_gameData.value)
        } }
    }

    /**
     * 原子性更新指定弟子 (通过 ID 和更新函数)
     *
     * 从 GameEngineAdapter 迁移而来，
     * 比现有的 updateDisciple(disciple: Disciple) 更灵活
     */
    suspend fun updateDisciple(discipleId: String, update: (Disciple) -> Disciple) {
        runBlocking { transactionMutex.withLock {
            val disciples = _disciples.value.toMutableList()
            val index = disciples.indexOfFirst { it.id == discipleId }
            if (index >= 0) {
                disciples[index] = update(disciples[index])
                _disciples.value = disciples
            }
        } }
    }

    // ==================== 时间推进 (委托给 CultivationService) ====================

    suspend fun advanceDay() = cultivationService.advanceDay()

    suspend fun advanceMonth() {
        cultivationService.advanceMonth()
        checkAndCollectCompletedAlchemySlots()
        checkAndProcessCompletedMissions()
        processMonthlyMissionRefresh()
    }

    suspend fun advanceYear() = cultivationService.advanceYear()

    fun updateRealtimeCultivation(currentTimeMillis: Long) =
        cultivationService.updateRealtimeCultivation(currentTimeMillis)

    // ==================== 库存/物品管理 (委托给 InventoryService) ====================

    fun addEquipment(equipment: Equipment) = inventoryService.addEquipment(equipment)

    fun removeEquipment(equipmentId: String): Boolean = inventoryService.removeEquipment(equipmentId)

    fun addManualToWarehouse(manual: Manual) = inventoryService.addManualToWarehouse(manual)

    fun addPillToWarehouse(pill: Pill) = inventoryService.addPillToWarehouse(pill)

    fun addMaterialToWarehouse(material: Material) = inventoryService.addMaterialToWarehouse(material)

    fun addHerbToWarehouse(herb: Herb) = inventoryService.addHerbToWarehouse(herb)

    fun addSeedToWarehouse(seed: Seed) = inventoryService.addSeedToWarehouse(seed)

    fun sortWarehouse() = inventoryService.sortWarehouse()

    fun createEquipmentFromRecipe(recipe: ForgeRecipe): Equipment =
        inventoryService.createEquipmentFromRecipe(recipe)

    fun createEquipmentFromMerchantItem(item: MerchantItem): Equipment =
        inventoryService.createEquipmentFromMerchantItem(item)

    fun createManualFromMerchantItem(item: MerchantItem): Manual =
        inventoryService.createManualFromMerchantItem(item)

    fun createPillFromMerchantItem(item: MerchantItem): Pill =
        inventoryService.createPillFromMerchantItem(item)

    fun createMaterialFromMerchantItem(item: MerchantItem): Material =
        inventoryService.createMaterialFromMerchantItem(item)

    fun createHerbFromMerchantItem(item: MerchantItem): Herb =
        inventoryService.createHerbFromMerchantItem(item)

    fun createSeedFromMerchantItem(item: MerchantItem): Seed =
        inventoryService.createSeedFromMerchantItem(item)

    // ==================== 弟子管理 (委托给 DiscipleService) ====================

    fun addDisciple(disciple: Disciple) = discipleService.addDisciple(disciple)

    fun removeDisciple(discipleId: String): Boolean = discipleService.removeDisciple(discipleId)

    fun getDiscipleById(discipleId: String): Disciple? = discipleService.getDiscipleById(discipleId)

    fun updateDisciple(disciple: Disciple) = discipleService.updateDisciple(disciple)

    fun getDiscipleStatus(discipleId: String): DiscipleStatus =
        discipleService.getDiscipleStatus(discipleId)

    fun syncAllDiscipleStatuses() = discipleService.syncAllDiscipleStatuses()

    fun resetAllDisciplesStatus() = discipleService.resetAllDisciplesStatus()

    fun recruitDisciple(): Disciple = discipleService.recruitDisciple()

    fun expelDisciple(discipleId: String): Boolean = discipleService.expelDisciple(discipleId)

    fun sendToReflection(discipleId: String): Boolean = discipleService.sendToReflection(discipleId)

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

    /** 执法堂自动补位：从储备弟子池填补空缺的执法弟子槽位 */
    fun autoFillLawEnforcementSlots(): Int = discipleService.autoFillLawEnforcementSlots()

    // ==================== DiscipleAggregate 查询接口（渐进式迁移支持）====================

    /**
     * 获取单个弟子的聚合数据
     *
     * 此方法为 [DiscipleAggregate] 多表架构的迁移桥梁。
     * 内部实现：从现有 [Disciple] 单表实体转换而来。
     *
     * @param discipleId 弟子 ID
     * @return 完整的 DiscipleAggregate 实例，如果弟子不存在则返回 null
     */
    fun getDiscipleAggregate(discipleId: String): DiscipleAggregate? {
        val disciple = discipleService.getDiscipleById(discipleId) ?: return null
        return disciple.toAggregate()
    }

    /**
     * 获取所有弟子的聚合数据列表
     *
     * 此方法为 [DiscipleAggregate] 多表架构的迁移桥梁。
     * 内部实现：从现有 [Disciple] 列表批量转换而来。
     *
     * @return 所有弟子的 DiscipleAggregate 列表
     */
    fun getAllDiscipleAggregates(): List<DiscipleAggregate> {
        return discipleService.getDisciples().value.map { it.toAggregate() }
    }

    // ==================== 战斗系统 (委托给 CombatService) ====================

    fun executeExplorationBattle(teamMembers: List<Disciple>, dungeon: Dungeon): BattleSystemResult =
        combatService.executeExplorationBattle(teamMembers, dungeon)

    fun applyBattleVictoryBonuses(memberIds: List<String>, addSoulPower: Boolean) =
        combatService.applyBattleVictoryBonuses(memberIds, addSoulPower)

    fun processBattleCasualties(deadMemberIds: Set<String>, survivorHpMap: Map<String, Int>) =
        combatService.processBattleCasualties(deadMemberIds, survivorHpMap)

    fun clearBattlePillEffects(teamMembers: List<Disciple>) =
        combatService.clearBattlePillEffects(teamMembers)

    fun getTotalBattlesCount(): Int = combatService.getTotalBattlesCount()

    fun getRecentBattles(count: Int = 10): List<BattleLog> = combatService.getRecentBattles(count)

    fun getWinRate(lastNBattles: Int = 50): Double = combatService.getWinRate(lastNBattles)

    // ==================== 探索系统 (委托给 ExplorationService) ====================

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

    fun startCaveExploration(cave: CultivatorCave, selectedDisciples: List<Disciple>): Boolean =
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

    // ==================== 事件系统 (委托给 EventService) ====================

    fun addEvent(message: String, type: EventType = EventType.INFO) = eventService.addGameEvent(message, type)

    fun clearEvents() = eventService.clearEvents()

    fun getRecentEvents(count: Int = 20): List<GameEvent> = eventService.getRecentEvents(count)

    fun generateSectTradeItems(year: Int): List<MerchantItem> =
        eventService.generateSectTradeItems(year)

    fun getOrRefreshSectTradeItems(sectId: String): List<MerchantItem> =
        eventService.getOrRefreshSectTradeItems(sectId)

    fun buyFromSectTrade(sectId: String, itemId: String, quantity: Int = 1) =
        eventService.buyFromSectTrade(
            sectId, itemId, quantity,
            onAddEquipment = { inventoryService.addEquipment(it) },
            onAddManual = { inventoryService.addManualToWarehouse(it) },
            onAddPill = { inventoryService.addPillToWarehouse(it) },
            onAddMaterial = { inventoryService.addMaterialToWarehouse(it) },
            onAddHerb = { inventoryService.addHerbToWarehouse(it) },
            onAddSeed = { inventoryService.addSeedToWarehouse(it) }
        )

    // ==================== 建筑系统 (委托给 BuildingService) ====================

    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) =
        buildingService.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)

    fun removeDiscipleFromBuilding(buildingId: String, slotIndex: Int) =
        buildingService.removeDiscipleFromBuilding(buildingId, slotIndex)

    fun getBuildingSlots(buildingId: String): List<BuildingSlot> =
        buildingService.getBuildingSlotsForBuilding(buildingId)

    suspend fun startAlchemy(slotIndex: Int, recipeId: String): Boolean =
        buildingService.startAlchemy(slotIndex, recipeId)

    suspend fun startForging(slotIndex: Int, recipeId: String): Boolean =
        buildingService.startForging(slotIndex, recipeId)

    fun startBuildingWork(buildingId: String, slotIndex: Int, recipeId: String, baseDuration: Int) =
        buildingService.startBuildingWork(buildingId, slotIndex, recipeId, baseDuration)

    fun collectBuildingResult(slotId: String) = buildingService.collectBuildingResult(slotId)

    fun collectForgeResult(slotIndex: Int) {
        val data = _gameData.value
        val slot = data.forgeSlots.find { it.slotIndex == slotIndex && it.status == SlotStatus.COMPLETED }
            ?: return
        collectBuildingResult(slot.id)
    }

    fun collectAlchemyResult(slotIndex: Int): AlchemyResult? =
        buildingService.collectAlchemyResult(slotIndex)

    fun checkAndCollectCompletedAlchemySlots(): List<AlchemyResult> =
        buildingService.checkAndCollectCompletedAlchemySlots()

    fun harvestHerb(slotIndex: Int) = buildingService.harvestHerb(slotIndex)

    fun clearPlantSlot(slotIndex: Int) = buildingService.clearPlantSlot(slotIndex)

    fun getAlchemySlots(): List<AlchemySlot> = buildingService.getAlchemySlots()

    fun getForgeSlots(): List<BuildingSlot> = buildingService.getBuildingSlots()

    // ==================== 存档系统 (委托给 SaveService) ====================

    fun getStateSnapshotSync(): GameStateSnapshot {
        return GameStateSnapshot(
            gameData = _gameData.value,
            disciples = _disciples.value,
            equipment = _equipment.value,
            manuals = _manuals.value,
            pills = _pills.value,
            materials = _materials.value,
            herbs = _herbs.value,
            seeds = _seeds.value,
            teams = _teams.value,
            events = _events.value,
            battleLogs = _battleLogs.value,
            alliances = _gameData.value.alliances
        )
    }

    suspend fun getStateSnapshot(): GameStateSnapshot = getStateSnapshotSync()

    fun prepareForLoad() = saveService.prepareForLoad()

    fun restoreFromLoad(loadedGameData: GameData) = saveService.restoreFromLoad(loadedGameData)

    fun validateState(): List<String> = saveService.validateState()

    fun getStateStatistics(): Map<String, Any> = saveService.getStateStatistics()

    fun isGameStarted(): Boolean = saveService.isGameStarted()

    fun getFormattedGameTime(): String = saveService.getFormattedGameTime()

    // ==================== 内存管理和调试 ====================

    /**
     * Get memory usage information for debugging
     */
    fun getMemoryUsageInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 内存使用情况 ===")
        sb.appendLine("弟子数量: ${_disciples.value.size}")
        sb.appendLine("装备数量: ${_equipment.value.size}")
        sb.appendLine("功法数量: ${_manuals.value.size}")
        sb.appendLine("丹药数量: ${_pills.value.size}")
        sb.appendLine("材料数量: ${_materials.value.size}")
        sb.appendLine("灵草数量: ${_herbs.value.size}")
        sb.appendLine("种子数量: ${_seeds.value.size}")
        sb.appendLine("探索队伍: ${_teams.value.size}")
        sb.appendLine("事件记录: ${_events.value.size}/50")
        sb.appendLine("战斗日志: ${_battleLogs.value.size}/50")
        return sb.toString()
    }

    /**
     * Release memory based on pressure level
     */
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

        runBlocking { transactionMutex.withLock {
            if (_events.value.size > eventsToKeep) {
                _events.value = _events.value.take(eventsToKeep)
                Log.d(TAG, "内存释放($levelName): 事件列表已清理，保留最近$eventsToKeep 条")
            }
            if (_battleLogs.value.size > logsToKeep) {
                _battleLogs.value = _battleLogs.value.take(logsToKeep)
                Log.d(TAG, "内存释放($levelName): 战斗日志已清理，保留最近$logsToKeep 条")
            }
        } }
    }

    // ==================== 游戏循环控制 (从 GameEngineAdapter 迁移) ====================

    /**
     * 处理每秒 tick
     *
     * 从 GameEngineAdapter 迁移而来，
     * 由 GameEngineCore.gameLoop() 调用
     */
    fun processSecondTick() {
        cultivationService.updateRealtimeCultivation(System.currentTimeMillis())
    }

    /**
     * 重置修炼计时器（用于后台暂停时）
     *
     * 当应用进入后台时调用，重置修炼时间戳，
     * 避免恢复前台后补偿后台期间的修为
     */
    fun resetCultivationTimer() {
        cultivationService.resetHighFrequencyData()
    }

    /**
     * 检查游戏是否暂停
     *
     * 从 GameEngineAdapter 迁移而来
     */
    fun isPaused(): Boolean = _isPaused

    /**
     * 设置游戏暂停状态
     *
     * 从 GameEngineAdapter 迁移而来
     */
    suspend fun setPaused(paused: Boolean) {
        _isPaused = paused
    }

    // ==================== 弟子便捷方法 (从 GameEngineAdapter 迁移) ====================

    /**
     * 开除弟子（dismissDisciple 的别名）
     *
     * 从 GameEngineAdapter 迁移而来，
     * 内部调用 expelDisciple
     */
    fun dismissDisciple(discipleId: String) {
        expelDisciple(discipleId)
    }

    /**
     * 给弟子使用物品
     *
     * 从 GameEngineAdapter 迁移而来，
     * 目前仅支持丹药类型
     */
    fun giveItemToDisciple(discipleId: String, itemId: String, itemType: String) {
        when (itemType) {
            "pill" -> usePill(discipleId, itemId)
            // 未来可扩展支持其他物品类型
        }
    }

    /**
     * 分配功法给弟子（assignManual 的别名）
     *
     * 从 GameEngineAdapter 迁移而来，
     * 内部调用 learnManual
     */
    fun assignManual(discipleId: String, manualId: String) {
        learnManual(discipleId, manualId)
    }

    /**
     * 收回弟子的功法（removeManual 的别名）
     *
     * 从 GameEngineAdapter 迁移而来，
     * 内部调用 forgetManual
     */
    fun removeManual(discipleId: String, manualId: String) {
        forgetManual(discipleId, manualId)
    }

    // ==================== 原子更新工具方法 ====================

    /**
     * 原子性更新 MutableStateFlow，确保读写一致性
     * 用于替代直接 .value = 赋值，防止并发场景下的丢失更新
     */
    private inline fun <T> atomicUpdate(flow: MutableStateFlow<T>, crossinline updater: (T) -> T) {
        runBlocking { transactionMutex.withLock { flow.value = updater(flow.value) } }
    }

    // ==================== 复杂业务逻辑（委托给专业服务）====================

    // ---------- 外交系统 (委托给 DiplomacyService) ----------

    /**
     * Gift spirit stones to sect
     * (委托给 DiplomacyService)
     */
    fun giftSpiritStones(sectId: String, tier: Int): DiplomacyService.GiftResult =
        diplomacyService.giftSpiritStones(sectId, tier)

    /**
     * Gift item to sect
     * (委托给 DiplomacyService)
     */
    fun giftItem(sectId: String, itemId: String, itemType: String, quantity: Int): DiplomacyService.GiftResult =
        diplomacyService.giftItem(sectId, itemId, itemType, quantity)

    /**
     * Request alliance with sect
     * (委托给 DiplomacyService)
     */
    fun requestAlliance(sectId: String, envoyDiscipleId: String): Pair<Boolean, String> =
        diplomacyService.requestAlliance(sectId, envoyDiscipleId)

    /**
     * Dissolve alliance
     * (委托给 DiplomacyService)
     */
    fun dissolveAlliance(sectId: String): Pair<Boolean, String> =
        diplomacyService.dissolveAlliance(sectId)

    /**
     * 获取拒绝概率 (委托给 DiplomacyService)
     */
    fun getRejectProbability(sectLevel: Int, rarity: Int): Int =
        diplomacyService.getRejectProbability(sectLevel, rarity)

    /**
     * 检查结盟条件 (委托给 DiplomacyService)
     */
    fun checkAllianceConditions(sectId: String, envoyDiscipleId: String): Triple<Boolean, String, Int> =
        diplomacyService.checkAllianceConditions(sectId, envoyDiscipleId)

    /**
     * 计算游说成功率 (委托给 DiplomacyService)
     */
    fun calculatePersuasionSuccessRate(favorability: Int, intelligence: Int, charm: Int): Double =
        diplomacyService.calculatePersuasionSuccessRate(favorability, intelligence, charm)

    /**
     * 获取游说弟子境界要求 (委托给 DiplomacyService)
     */
    fun getEnvoyRealmRequirement(sectLevel: Int): Int =
        diplomacyService.getEnvoyRealmRequirement(sectLevel)

    /**
     * 获取结盟费用 (委托给 DiplomacyService)
     */
    fun getAllianceCost(sectLevel: Int): Long =
        diplomacyService.getAllianceCost(sectLevel)

    // ---------- 兑换码系统 (委托给 RedeemCodeService) ----------

    /**
     * Redeem code
     * (委托给 RedeemCodeService)
     */
    suspend fun redeemCode(
        code: String,
        usedCodes: List<String>,
        currentYear: Int,
        currentMonth: Int
    ): RedeemResult = redeemCodeService.redeemCode(code, usedCodes, currentYear, currentMonth)

    // ---------- 公式计算 (委托给 FormulaService) ----------

    /**
     * Calculate success rate bonus for production
     * (委托给 FormulaService)
     */
    fun calculateSuccessRateBonus(disciple: Disciple?, buildingId: String): Double =
        formulaService.calculateSuccessRateBonus(disciple, buildingId)

    /**
     * Calculate work duration with all disciple bonuses
     * (委托给 FormulaService)
     */
    fun calculateWorkDurationWithAllDisciples(baseDuration: Int, buildingId: String): Int =
        formulaService.calculateWorkDurationWithAllDisciples(baseDuration, buildingId)

    /**
     * Calculate elder and disciples bonus
     * (委托给 FormulaService)
     */
    fun calculateElderAndDisciplesBonus(buildingType: String): FormulaService.ElderBonusData =
        formulaService.calculateElderAndDisciplesBonus(buildingType)

    // ==================== 从 GameEngineAdapter 迁移的额外方法 ====================
    //
    // 以下方法原来定义在 GameEngineAdapter 中，作为纯代理转发到 GameEngine。
    // 由于 GameEngine 中缺少这些方法的直接实现，现在在此补充。
    // 大部分方法委托给对应的 Service 或 System。

    // ---------- 宗门交互 (委托给 SectSystem) ----------

    /**
     * 与宗门交互
     * (从 GameEngineAdapter 迁移)
     */
    fun interactWithSect(sectId: String, action: String) {
        Log.w(TAG, "interactWithSect 尚未实现: sectId=$sectId, action=$action")
    }

    // ---------- 外交查询方法 (委托给 DiplomacyService) ----------

    /** 检查是否为盟友 */
    fun isAlly(sectId: String): Boolean {
        val data = _gameData.value
        return data.alliances.any { it.sectIds.contains("player") && it.sectIds.contains(sectId) }
    }

    /** 获取结盟剩余年数 */
    fun getAllianceRemainingYears(sectId: String): Int {
        val data = _gameData.value
        val alliance = data.alliances.find { it.sectIds.contains("player") && it.sectIds.contains(sectId) } ?: return 0
        val sect = data.worldMapSects.find { it.id == sectId }
        if (sect?.allianceStartYear != null && sect.allianceStartYear > 0) {
            return (data.gameYear - sect.allianceStartYear).coerceAtLeast(0)
        }
        return (data.gameYear - alliance.startYear).coerceAtLeast(0)
    }

    /** 获取玩家所有盟友 */
    fun getPlayerAllies(): List<String> {
        val data = _gameData.value
        val playerAlliance = data.alliances.find { it.sectIds.contains("player") } ?: return emptyList()
        return playerAlliance.sectIds.filter { it != "player" }
    }

    // ---------- 长老/弟子管理 (委托给 SectSystem 或 DiscipleService) ----------

    /** 更新长老槽位 */
    fun updateElderSlots(newElderSlots: ElderSlots) {
        updateGameDataSync { it.copy(elderSlots = newElderSlots) }
    }

    /** 分配亲传弟子 */
    fun assignDirectDisciple(
        elderSlotType: String,
        slotIndex: Int,
        discipleId: String,
        discipleName: String,
        discipleRealm: String,
        discipleSpiritRootColor: String
    ) {
        val data = _gameData.value
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
        updateGameDataSync { it.copy(elderSlots = updatedSlots) }

        // 执法弟子任命后触发自动补位（在事务锁内保证原子性）
        if (elderSlotType == "lawEnforcement") {
            runBlocking { transactionMutex.withLock {
                discipleService.autoFillLawEnforcementSlots()
            } }
        }
    }

    /** 移除亲传弟子 */
    fun removeDirectDisciple(elderSlotType: String, slotIndex: Int) {
        val slots = _gameData.value.elderSlots
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
        updateGameDataSync { it.copy(elderSlots = updatedSlots) }

        // 执法弟子离任后触发自动补位
        if (elderSlotType == "lawEnforcement") {
            discipleService.autoFillLawEnforcementSlots()
        }
    }

    /** 更新弟子状态 */
    fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        _disciples.value = _disciples.value.map {
            if (it.id == discipleId) it.copy(status = status) else it
        }
    }

    // ---------- 灵矿/建筑相关 ----------

    /** 验证并修复灵矿数据 */
    fun validateAndFixSpiritMineData() {
        val data = _gameData.value
        val discipleIds = _disciples.value.map { it.id }.toSet()
        val fixedSlots = data.spiritMineSlots.map { slot ->
            if (slot.discipleId != null && slot.discipleId !in discipleIds) {
                slot.copy(discipleId = null, discipleName = "")
            } else slot
        }
        if (fixedSlots != data.spiritMineSlots) {
            updateGameDataSync { it.copy(spiritMineSlots = fixedSlots) }
        }
    }

    /** 更新灵矿槽位 */
    fun updateSpiritMineSlots(slots: List<SpiritMineSlot>) {
        // TODO: 委托给 SectSystem 的具体实现
        updateGameDataSync { it.copy(spiritMineSlots = slots) }
    }

    /** 分配弟子到藏经阁槽位 */
    fun assignDiscipleToLibrarySlot(slotIndex: Int, discipleId: String, discipleName: String) {
        val data = _gameData.value
        val slots = data.librarySlots.toMutableList()
        while (slots.size <= slotIndex) {
            slots.add(LibrarySlot(index = slots.size))
        }
        slots[slotIndex] = LibrarySlot(
            index = slotIndex,
            discipleId = discipleId,
            discipleName = discipleName
        )
        updateGameDataSync { it.copy(librarySlots = slots) }
    }

    fun removeDiscipleFromLibrarySlot(slotIndex: Int) {
        val data = _gameData.value
        if (slotIndex < 0 || slotIndex >= data.librarySlots.size) return
        val slots = data.librarySlots.toMutableList()
        slots[slotIndex] = LibrarySlot(index = slotIndex)
        updateGameDataSync { it.copy(librarySlots = slots) }
    }

    // ---------- 建筑/生产操作 ----------

    /** 清空炼丹槽位 */
    fun clearAlchemySlot(slotIndex: Int) {
        val data = _gameData.value
        val currentSlots = data.alchemySlots
        if (slotIndex >= 0 && slotIndex < currentSlots.size) {
            val updatedSlots = currentSlots.toMutableList()
            updatedSlots[slotIndex] = AlchemySlot(slotIndex = slotIndex)
            updateGameDataSync { it.copy(alchemySlots = updatedSlots) }
        }
    }

    /** 清空锻造槽位 */
    fun clearForgeSlot(slotIndex: Int) {
        val data = _gameData.value
        val slot = data.forgeSlots.find { it.slotIndex == slotIndex } ?: return
        if (slot.status != SlotStatus.WORKING) {
            slot.discipleId?.let { discipleId ->
                updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
            }
            updateGameDataSync { it.copy(forgeSlots = data.forgeSlots.filter { s -> s.slotIndex != slotIndex || s.buildingId != slot.buildingId }) }
        }
    }

    /** 手动种植 */
    fun startManualPlanting(slotIndex: Int, seedId: String) {
        runBlocking { transactionMutex.withLock {
            val data = _gameData.value
            val currentSlots = data.herbGardenPlantSlots.toMutableList()
            if (slotIndex < 0 || slotIndex >= currentSlots.size) return@withLock
            val seed = _seeds.value.find { it.id == seedId } ?: return@withLock
            if (seed.quantity <= 0) return@withLock

            currentSlots[slotIndex] = PlantSlotData(
                index = slotIndex,
                seedId = seedId,
                seedName = seed.name,
                status = "growing",
                startYear = data.gameYear,
                startMonth = data.gameMonth,
                growTime = seed.growTime,
                harvestAmount = seed.yield
            )
            _gameData.value = data.copy(herbGardenPlantSlots = currentSlots)
            inventoryService.removeSeed(seedId = seedId, quantity = 1)
        }}
    }

    // ---------- 弟子招募/管理 ----------

    /** 从招募列表招募弟子 */
    fun recruitDiscipleFromList(disciple: Disciple) {
        addDisciple(disciple)
        val data = _gameData.value
        updateGameDataSync { it.copy(recruitList = data.recruitList.filter { it.id != disciple.id }) }
    }

    /** 给予奖励物品到弟子 */
    suspend fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>) {
        items.forEach { item ->
            when (item.type.lowercase()) {
                "equipment" -> {
                    val eq = _equipment.value.find { it.id == item.id }
                    if (eq != null) { equipEquipment(discipleId, item.id) }
                }
                "manual" -> { learnManual(discipleId, item.id) }
                "pill" -> { usePill(discipleId, item.id) }
            }
        }
    }

    /** 从招募列表全部招募 */
    fun recruitAllFromList(): Boolean {
        val data = _gameData.value
        if (data.recruitList.isEmpty()) return false
        data.recruitList.forEach { addDisciple(it) }
        updateGameDataSync { it.copy(recruitList = emptyList()) }
        return true
    }

    /** 从招募列表移除 */
    fun removeFromRecruitList(disciple: Disciple) {
        val data = _gameData.value
        updateGameDataSync { it.copy(recruitList = data.recruitList.filter { it.id != disciple.id }) }
    }

    // ---------- 装备/物品操作 (委托给 InventoryService) ----------

    /** 装备物品 */
    fun equipItem(discipleId: String, equipmentId: String) {
        discipleService.equipEquipment(discipleId, equipmentId)
    }

    /** 卸下装备 (按槽位) */
    fun unequipItem(discipleId: String, slot: EquipmentSlot) {
        discipleService.unequipEquipment(discipleId, equipmentId = slot.name)  // 需要确认参数
    }

    /** 卸下装备 (按装备ID) */
    fun unequipItemById(discipleId: String, equipmentId: String) {
        discipleService.unequipEquipment(discipleId, equipmentId)
    }

    /** 忘记功法 */
    fun forgetManual(discipleId: String, manualId: String) {
        val disciple = getDiscipleById(discipleId) ?: return
        _disciples.value = _disciples.value.map {
            if (it.id == discipleId) it.copy(manualIds = it.manualIds.filter { mid -> mid != manualId }) else it
        }
    }

    /** 替换功法 */
    fun replaceManual(discipleId: String, oldManualId: String, newManualId: String) {
        forgetManual(discipleId, oldManualId)
        learnManual(discipleId, newManualId)
    }

    /** 学习功法 */
    fun learnManual(discipleId: String, manualId: String) {
        _disciples.value = _disciples.value.map {
            if (it.id == discipleId && !it.manualIds.contains(manualId)) {
                it.copy(manualIds = it.manualIds + manualId)
            } else it
        }
    }

    // ---------- 商人/交易系统 (委托给 InventoryService 和 EventService) ----------

    /** 从商人购买物品 */
    suspend fun buyMerchantItem(itemId: String, quantity: Int) {
        val data = _gameData.value
        val merchantItem = data.travelingMerchantItems.find { it.itemId == itemId } ?: return
        val cost = merchantItem.price * quantity
        if (data.spiritStones < cost) return
        updateGameDataSync { it.copy(spiritStones = it.spiritStones - cost) }
        when (merchantItem.type.lowercase()) {
            "equipment" -> { addEquipment(createEquipmentFromMerchantItem(merchantItem)) }
            "manual" -> { addManualToWarehouse(createManualFromMerchantItem(merchantItem)) }
            "pill" -> { addPillToWarehouse(createPillFromMerchantItem(merchantItem)) }
            "material" -> { addMaterialToWarehouse(createMaterialFromMerchantItem(merchantItem)) }
            "herb" -> { addHerbToWarehouse(createHerbFromMerchantItem(merchantItem)) }
            "seed" -> { addSeedToWarehouse(createSeedFromMerchantItem(merchantItem)) }
        }
    }

    /** 挂物品到商人 */
    suspend fun listItemsToMerchant(items: List<Pair<String, Int>>) {
        val data = _gameData.value
        val newItems = mutableListOf<MerchantItem>()
        items.forEach { (itemId, quantity) ->
            val eq = _equipment.value.find { it.id == itemId }
            if (eq != null && !eq.isEquipped) {
                inventoryService.removeEquipment(itemId)
                newItems.add(MerchantItem(
                    id = java.util.UUID.randomUUID().toString(),
                    name = eq.name,
                    type = "equipment",
                    itemId = itemId,
                    rarity = eq.rarity,
                    price = (eq.basePrice * 0.8).toInt(),
                    quantity = quantity
                ))
                return@forEach
            }
            val manual = _manuals.value.find { it.id == itemId }
            if (manual != null) {
                inventoryService.removeManual(manualId = itemId, quantity = quantity)
                newItems.add(MerchantItem(
                    id = java.util.UUID.randomUUID().toString(),
                    name = manual.name,
                    type = "manual",
                    itemId = itemId,
                    rarity = manual.rarity,
                    price = (manual.basePrice * 0.8).toInt(),
                    quantity = quantity
                ))
                return@forEach
            }
            val pill = _pills.value.find { it.id == itemId }
            if (pill != null) {
                inventoryService.removePill(pillId = itemId, quantity = quantity)
                newItems.add(MerchantItem(
                    id = java.util.UUID.randomUUID().toString(),
                    name = pill.name,
                    type = "pill",
                    itemId = itemId,
                    rarity = pill.rarity,
                    price = (pill.basePrice * 0.8).toInt(),
                    quantity = quantity
                ))
                return@forEach
            }
            val material = _materials.value.find { it.id == itemId }
            if (material != null) {
                inventoryService.removeMaterial(materialId = itemId, quantity = quantity)
                newItems.add(MerchantItem(
                    id = java.util.UUID.randomUUID().toString(),
                    name = material.name,
                    type = "material",
                    itemId = itemId,
                    rarity = material.rarity,
                    price = (material.basePrice * 0.8).toInt(),
                    quantity = quantity
                ))
                return@forEach
            }
            val herb = _herbs.value.find { it.id == itemId }
            if (herb != null) {
                inventoryService.removeHerb(herbId = itemId, quantity = quantity)
                newItems.add(MerchantItem(
                    id = java.util.UUID.randomUUID().toString(),
                    name = herb.name,
                    type = "herb",
                    itemId = itemId,
                    rarity = herb.rarity,
                    price = (herb.basePrice * 0.8).toInt(),
                    quantity = quantity
                ))
                return@forEach
            }
            val seed = _seeds.value.find { it.id == itemId }
            if (seed != null) {
                inventoryService.removeSeed(seedId = itemId, quantity = quantity)
                newItems.add(MerchantItem(
                    id = java.util.UUID.randomUUID().toString(),
                    name = seed.name,
                    type = "seed",
                    itemId = itemId,
                    rarity = seed.rarity,
                    price = (seed.basePrice * 0.8).toInt(),
                    quantity = quantity
                ))
                return@forEach
            }
        }
        updateGameData { it.copy(playerListedItems = it.playerListedItems + newItems) }
    }

    suspend fun removePlayerListedItem(itemId: String) {
        val data = _gameData.value
        val item = data.playerListedItems.find { it.id == itemId } ?: return
        when (item.type.lowercase()) {
            "equipment" -> _equipment.value.find { it.id == item.itemId }?.let {
                _equipment.value = _equipment.value + it.copy(quantity = (it.quantity + item.quantity).coerceAtMost(999))
            }
            "manual" -> _manuals.value.find { it.id == item.itemId }?.let {
                inventoryService.addManualToWarehouse(it.copy(quantity = (it.quantity + item.quantity).coerceAtMost(999)))
            }
            "pill" -> _pills.value.find { it.id == item.itemId }?.let {
                _pills.value = _pills.value.map { p ->
                    if (p.id == item.itemId) p.copy(quantity = (p.quantity + item.quantity).coerceAtMost(999)) else p
                }
            }
            "material" -> _materials.value.find { it.id == item.itemId }?.let {
                _materials.value = _materials.value.map { m ->
                    if (m.id == item.itemId) m.copy(quantity = (m.quantity + item.quantity).coerceAtMost(999)) else m
                }
            }
            "herb" -> _herbs.value.find { it.id == item.itemId }?.let {
                _herbs.value = _herbs.value.map { h ->
                    if (h.id == item.itemId) h.copy(quantity = (h.quantity + item.quantity).coerceAtMost(999)) else h
                }
            }
            "seed" -> _seeds.value.find { it.id == item.itemId }?.let {
                _seeds.value = _seeds.value.map { s ->
                    if (s.id == item.itemId) s.copy(quantity = (s.quantity + item.quantity).coerceAtMost(999)) else s
                }
            }
        }
        updateGameData { it.copy(playerListedItems = it.playerListedItems.filter { it.id != itemId }) }
    }

    }

    // ---------- 出售操作 (委托给 InventoryService) ----------

    /** 使用丹药 */
    fun usePill(discipleId: String, pillId: String) {
        val pill = _pills.value.find { it.id == pillId } ?: return
        if (pill.quantity <= 0) return
        val disciple = getDiscipleById(discipleId) ?: return

        val updatedPills = _pills.value.map {
            if (it.id == pillId && it.quantity > 0) it.copy(quantity = it.quantity - 1) else it
        }.filter { it.quantity > 0 }
        _pills.value = updatedPills

        val effect = pill.effect
        var updatedDisciple = disciple

        if (effect.cultivationSpeed > 1.0) {
            updatedDisciple = updatedDisciple.copy(cultivationSpeedBonus = effect.cultivationSpeed)
        }

        if (effect.cultivationPercent > 0 || effect.skillExpPercent > 0) {
            val newCultivation = (updatedDisciple.totalCultivation + (updatedDisciple.totalCultivation * effect.cultivationPercent).toLong()).coerceAtLeast(0)
            updatedDisciple = updatedDisciple.copy(combat = updatedDisciple.combat.copy(totalCultivation = newCultivation))
        }

        val newEffects = updatedDisciple.pillEffects.copy(
            pillPhysicalAttackBonus = if (effect.physicalAttackPercent > 0) effect.physicalAttackPercent else updatedDisciple.pillEffects.pillPhysicalAttackBonus,
            pillMagicAttackBonus = if (effect.magicAttackPercent > 0) effect.magicAttackPercent else updatedDisciple.pillEffects.pillMagicAttackBonus,
            pillPhysicalDefenseBonus = if (effect.physicalDefensePercent > 0) effect.physicalDefensePercent else updatedDisciple.pillEffects.pillPhysicalDefenseBonus,
            pillMagicDefenseBonus = if (effect.magicDefensePercent > 0) effect.magicDefensePercent else updatedDisciple.pillEffects.pillMagicDefenseBonus,
            pillHpBonus = if (effect.hpPercent > 0) effect.hpPercent else updatedDisciple.pillEffects.pillHpBonus,
            pillMpBonus = if (effect.mpPercent > 0) effect.mpPercent else updatedDisciple.pillEffects.pillMpBonus,
            pillSpeedBonus = if (effect.speedPercent > 0) effect.speedPercent else updatedDisciple.pillEffects.pillSpeedBonus,
            pillEffectDuration = if (pill.duration > 0) pill.duration else updatedDisciple.pillEffects.pillEffectDuration
        )
        updatedDisciple = updatedDisciple.copy(pillEffects = newEffects)

        if (effect.heal > 0 || effect.healPercent > 0 || effect.healMaxHpPercent > 0) {
            updatedDisciple = updatedDisciple.copy(combat = updatedDisciple.combat.copy(hpVariance = 0))
        }

        if (effect.revive && !disciple.isAlive) {
            updatedDisciple = updatedDisciple.copy(isAlive = true, combat = updatedDisciple.combat.copy(hpVariance = 0))
        }

        if (effect.clearAll) {
            updatedDisciple = updatedDisciple.copy(pillEffects = PillEffects())
        }

        _disciples.value = _disciples.value.map {
            if (it.id == discipleId) updatedDisciple else it
        }
    }

    /** 出售装备 */
    fun sellEquipment(equipmentId: String): Boolean {
        val eq = _equipment.value.find { it.id == equipmentId } ?: return false
        if (eq.isEquipped) return false
        removeEquipment(equipmentId)
        addSpiritStones((eq.basePrice * 0.8).toInt())
        return true
    }

    /** 出售功法 */
    fun sellManual(manualId: String, quantity: Int): Boolean {
        val manual = _manuals.value.find { it.id == manualId } ?: return false
        if (!inventoryService.removeManual(manualId, quantity)) return false
        addSpiritStones((manual.basePrice * quantity * 0.8).toInt())
        return true
    }

    /** 出售丹药 */
    fun sellPill(pillId: String, quantity: Int): Boolean {
        val pill = _pills.value.find { it.id == pillId } ?: return false
        if (!inventoryService.removePill(pillId, quantity)) return false
        addSpiritStones((pill.basePrice * quantity * 0.8).toInt())
        return true
    }

    /** 出售材料 */
    fun sellMaterial(materialId: String, quantity: Int): Boolean {
        val material = _materials.value.find { it.id == materialId } ?: return false
        if (!inventoryService.removeMaterial(materialId, quantity)) return false
        addSpiritStones((material.basePrice * quantity * 0.05).toInt())
        return true
    }

    /** 出售灵草 */
    fun sellHerb(herbId: String, quantity: Int): Boolean {
        val herb = _herbs.value.find { it.id == herbId } ?: return false
        if (!inventoryService.removeHerb(herbId, quantity)) return false
        addSpiritStones((herb.basePrice * quantity * 0.05).toInt())
        return true
    }

    /** 出售种子 */
    fun sellSeed(seedId: String, quantity: Int): Boolean {
        val seed = _seeds.value.find { it.id == seedId } ?: return false
        if (!inventoryService.removeSeed(seedId, quantity)) return false
        addSpiritStones((seed.basePrice * quantity * 0.05).toInt())
        return true
    }

    /** 添加灵石 */
    fun addSpiritStones(amount: Int) {
        updateGameDataSync { it.copy(spiritStones = it.spiritStones + amount) }
    }

    /** 更新月俸 */
    fun updateMonthlySalary(newSalary: Map<Int, Int>) {
        updateGameDataSync { it.copy(monthlySalary = newSalary) }
    }

    // ---------- 任务系统 ----------

    /** 开始任务 */
    fun startMission(mission: Mission, selectedDisciples: List<Disciple>) {
        val data = _gameData.value
        val activeMission = MissionSystem.createActiveMission(mission, selectedDisciples, data.gameYear, data.gameMonth)
        updateGameDataSync { it.copy(activeMissions = it.activeMissions + activeMission) }
        selectedDisciples.forEach { disciple ->
            updateDiscipleStatus(disciple.id, DiscipleStatus.ON_MISSION)
        }
    }

    /** 检查并处理已完成的任务，返回完成的任务ID列表 */
    fun checkAndProcessCompletedMissions(): List<String> {
        val data = _gameData.value
        val currentYear = data.gameYear
        val currentMonth = data.gameMonth
        val completedIds = mutableListOf<String>()
        val remainingActive = mutableListOf<ActiveMission>()

        for (activeMission in data.activeMissions) {
            if (activeMission.isComplete(currentYear, currentMonth)) {
                completedIds.add(activeMission.id)

                val aliveDisciples = activeMission.discipleIds.mapNotNull { did ->
                    _disciples.value.find { it.id == did && it.isAlive }
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
                    val disciple = _disciples.value.find { it.id == did }
                    if (disciple != null && disciple.isAlive) {
                        updateDiscipleStatus(did, DiscipleStatus.IDLE)
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

    /** 月度任务刷新：外派任务3%几率生成 + 过期清理 */
    fun processMonthlyMissionRefresh() {
        val data = _gameData.value
        val result = MissionSystem.processMonthlyDispatchRefresh(
            data.availableMissions,
            data.gameYear,
            data.gameMonth
        )
        val permanentMissions = MissionSystem.generatePermanentSectMissions()
        updateGameDataSync { it.copy(availableMissions = permanentMissions + result.remainingAndNewDispatchMissions) }
    }

    private fun applyMissionResult(result: MissionSystem.MissionResult) {
        if (result.spiritStones > 0) addSpiritStones(result.spiritStones)
        result.materials.forEach { inventoryService.addMaterialToWarehouse(it) }
        result.herbs.forEach { inventoryService.addHerbToWarehouse(it) }
        result.seeds.forEach { inventoryService.addSeedToWarehouse(it) }
        result.pills.forEach { inventoryService.addPillToWarehouse(it) }
        result.equipment.forEach { inventoryService.addEquipment(it) }
        result.manuals.forEach { inventoryService.addManualToWarehouse(it) }
    }

    /** 开始探索（简化接口） */
    fun startExploration(name: String, memberIds: List<String>, location: String, duration: Int) {
        val data = _gameData.value
        createExplorationTeam(name, memberIds, location, location, duration, data.gameYear, data.gameMonth, data.gameDay)
    }

    /** 开始战斗队伍移动 */
    fun startBattleTeamMove(targetSectId: String) {
        val data = _gameData.value
        val team = data.battleTeam ?: return
        val targetSect = data.worldMapSects.find { it.id == targetSectId } ?: return

        val updatedTeam = team.copy(
            targetSectId = targetSectId,
            status = "moving",
            targetX = targetSect.x,
            targetY = targetSect.y,
            originSectId = data.worldMapSects.find { it.isPlayerSect }?.id,
            route = emptyList(),
            currentRouteIndex = 0,
            moveProgress = 0f,
            isReturning = false
        )
        updateGameDataSync { it.copy(battleTeam = updatedTeam) }
    }
}

