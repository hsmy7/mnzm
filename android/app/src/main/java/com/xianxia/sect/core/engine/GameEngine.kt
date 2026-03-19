package com.xianxia.sect.core.engine

import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.GiftConfig
import com.xianxia.sect.core.config.SectResponseTexts
import com.xianxia.sect.core.data.BeastMaterialDatabase
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.data.ItemDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.data.TalentDatabase
import com.xianxia.sect.core.engine.RedeemCodeManager
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.GameUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.random.Random

class GameEngine {
    
    private val _gameData = MutableStateFlow(GameData())
    val gameData: StateFlow<GameData> = _gameData.asStateFlow()
    
    private val _disciples = MutableStateFlow<List<Disciple>>(emptyList())
    val disciples: StateFlow<List<Disciple>> = _disciples.asStateFlow()
    
    private val _equipment = MutableStateFlow<List<Equipment>>(emptyList())
    val equipment: StateFlow<List<Equipment>> = _equipment.asStateFlow()
    
    private val _manuals = MutableStateFlow<List<Manual>>(emptyList())
    val manuals: StateFlow<List<Manual>> = _manuals.asStateFlow()
    
    private val _pills = MutableStateFlow<List<Pill>>(emptyList())
    val pills: StateFlow<List<Pill>> = _pills.asStateFlow()

    private val _materials = MutableStateFlow<List<Material>>(emptyList())
    val materials: StateFlow<List<Material>> = _materials.asStateFlow()

    private val _herbs = MutableStateFlow<List<Herb>>(emptyList())
    val herbs: StateFlow<List<Herb>> = _herbs.asStateFlow()

    private val _seeds = MutableStateFlow<List<Seed>>(emptyList())
    val seeds: StateFlow<List<Seed>> = _seeds.asStateFlow()

    private val _teams = MutableStateFlow<List<ExplorationTeam>>(emptyList())
    val teams: StateFlow<List<ExplorationTeam>> = _teams.asStateFlow()
    
    private val _events = MutableStateFlow<List<GameEvent>>(emptyList())
    val events: StateFlow<List<GameEvent>> = _events.asStateFlow()
    
    private val _battleLogs = MutableStateFlow<List<BattleLog>>(emptyList())
    val battleLogs: StateFlow<List<BattleLog>> = _battleLogs.asStateFlow()
    
    private val _buildingSlots = MutableStateFlow<List<BuildingSlot>>(emptyList())
    val buildingSlots: StateFlow<List<BuildingSlot>> = _buildingSlots.asStateFlow()
    
    private val _alchemySlots = MutableStateFlow<List<AlchemySlot>>(emptyList())
    val alchemySlots: StateFlow<List<AlchemySlot>> = _alchemySlots.asStateFlow()
    
    private val _highFrequencyData = MutableStateFlow(HighFrequencyData())
    val highFrequencyData: StateFlow<HighFrequencyData> = _highFrequencyData.asStateFlow()
    
    private val _realtimeCultivation = MutableStateFlow<Map<String, Double>>(emptyMap())
    val realtimeCultivation: StateFlow<Map<String, Double>> = _realtimeCultivation.asStateFlow()
    
    data class HighFrequencyData(
        val timestamp: Long = System.currentTimeMillis(),
        val cultivationUpdates: Map<String, Double> = emptyMap(),
        val equipmentNurtureUpdates: Map<String, Double> = emptyMap(),
        val manualProficiencyUpdates: Map<String, Double> = emptyMap()
    )
    
    private val transactionMutex = Mutex()
    
    private var tickCounter = 0
    
    companion object {
        private const val TAG = "GameEngine"
    }
    
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
        val buildingSlots: List<BuildingSlot>,
        val events: List<GameEvent>,
        val battleLogs: List<BattleLog>,
        val alliances: List<Alliance>,
        val supportTeams: List<SupportTeam>,
        val alchemySlots: List<AlchemySlot>
    )
    
    /**
     * 获取游戏状态快照（带锁）
     * 用于安全地获取游戏数据进行保存
     */
    suspend fun getStateSnapshot(): GameStateSnapshot {
        return transactionMutex.withLock {
            GameStateSnapshot(
                gameData = _gameData.value,
                disciples = _disciples.value,
                equipment = _equipment.value,
                manuals = _manuals.value,
                pills = _pills.value,
                materials = _materials.value,
                herbs = _herbs.value,
                seeds = _seeds.value,
                teams = _teams.value,
                buildingSlots = _buildingSlots.value,
                events = _events.value,
                battleLogs = _battleLogs.value,
                alliances = _gameData.value.alliances,
                supportTeams = _gameData.value.supportTeams,
                alchemySlots = _alchemySlots.value
            )
        }
    }
    
    /**
     * 获取游戏状态快照（同步版本，用于崩溃处理等紧急场景）
     * 注意：此方法直接读取 StateFlow 当前值，不使用锁，可能在数据更新过程中读取到不一致的状态
     * 仅在紧急保存时使用，正常保存应使用 getStateSnapshot()
     */
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
            buildingSlots = _buildingSlots.value,
            events = _events.value,
            battleLogs = _battleLogs.value,
            alliances = _gameData.value.alliances,
            supportTeams = _gameData.value.supportTeams,
            alchemySlots = _alchemySlots.value
        )
    }
    
    private inner class GameTransaction {
        private var gameDataSnapshot: GameData = _gameData.value
        private var disciplesSnapshot: List<Disciple> = _disciples.value
        private var equipmentSnapshot: List<Equipment> = _equipment.value
        private var manualsSnapshot: List<Manual> = _manuals.value
        private var pillsSnapshot: List<Pill> = _pills.value
        private var materialsSnapshot: List<Material> = _materials.value
        private var herbsSnapshot: List<Herb> = _herbs.value
        private var seedsSnapshot: List<Seed> = _seeds.value
        private var teamsSnapshot: List<ExplorationTeam> = _teams.value
        private var buildingSlotsSnapshot: List<BuildingSlot> = _buildingSlots.value
        private var battleLogsSnapshot: List<BattleLog> = _battleLogs.value
        private var eventsSnapshot: List<GameEvent> = _events.value
        private val pendingEvents = mutableListOf<Pair<String, EventType>>()
        
        val gameData: GameData get() = gameDataSnapshot
        val disciples: List<Disciple> get() = disciplesSnapshot
        val equipment: List<Equipment> get() = equipmentSnapshot
        val manuals: List<Manual> get() = manualsSnapshot
        val pills: List<Pill> get() = pillsSnapshot
        val materials: List<Material> get() = materialsSnapshot
        val herbs: List<Herb> get() = herbsSnapshot
        val seeds: List<Seed> get() = seedsSnapshot
        val teams: List<ExplorationTeam> get() = teamsSnapshot
        val buildingSlots: List<BuildingSlot> get() = buildingSlotsSnapshot
        val battleLogs: List<BattleLog> get() = battleLogsSnapshot
        
        fun updateGameData(update: (GameData) -> GameData) {
            gameDataSnapshot = update(gameDataSnapshot)
        }
        
        fun updateDisciples(update: (List<Disciple>) -> List<Disciple>) {
            disciplesSnapshot = update(disciplesSnapshot)
        }
        
        fun updateEquipment(update: (List<Equipment>) -> List<Equipment>) {
            equipmentSnapshot = update(equipmentSnapshot)
        }
        
        fun updateManuals(update: (List<Manual>) -> List<Manual>) {
            manualsSnapshot = update(manualsSnapshot)
        }
        
        fun updatePills(update: (List<Pill>) -> List<Pill>) {
            pillsSnapshot = update(pillsSnapshot)
        }
        
        fun updateMaterials(update: (List<Material>) -> List<Material>) {
            materialsSnapshot = update(materialsSnapshot)
        }
        
        fun updateHerbs(update: (List<Herb>) -> List<Herb>) {
            herbsSnapshot = update(herbsSnapshot)
        }
        
        fun updateSeeds(update: (List<Seed>) -> List<Seed>) {
            seedsSnapshot = update(seedsSnapshot)
        }
        
        fun updateTeams(update: (List<ExplorationTeam>) -> List<ExplorationTeam>) {
            teamsSnapshot = update(teamsSnapshot)
        }
        
        fun updateBuildingSlots(update: (List<BuildingSlot>) -> List<BuildingSlot>) {
            buildingSlotsSnapshot = update(buildingSlotsSnapshot)
        }
        
        fun updateBattleLogs(update: (List<BattleLog>) -> List<BattleLog>) {
            battleLogsSnapshot = update(battleLogsSnapshot)
        }
        
        fun addEvent(message: String, type: EventType) {
            pendingEvents.add(message to type)
        }
        
        fun commit(): List<Pair<String, EventType>> {
            _gameData.value = gameDataSnapshot
            _disciples.value = disciplesSnapshot
            _equipment.value = equipmentSnapshot
            _manuals.value = manualsSnapshot
            _pills.value = pillsSnapshot
            _materials.value = materialsSnapshot
            _herbs.value = herbsSnapshot
            _seeds.value = seedsSnapshot
            _teams.value = teamsSnapshot
            _buildingSlots.value = buildingSlotsSnapshot
            _battleLogs.value = battleLogsSnapshot
            
            return pendingEvents.toList()
        }
    }
    
    private suspend inline fun <T> withTransaction(crossinline block: suspend GameTransaction.() -> T): T {
        return transactionMutex.withLock {
            val transaction = GameTransaction()
            val result = transaction.block()
            val events = transaction.commit()
            events.forEach { (message, type) ->
                addEvent(message, type)
            }
            result
        }
    }

    private inline fun <T> withTransactionSync(block: GameTransaction.() -> T): T {
        val transaction = GameTransaction()
        val result = transaction.block()
        val events = transaction.commit()
        events.forEach { (message, type) ->
            addEvent(message, type)
        }
        return result
    }
    
    fun initializeWorldMap() {
        val data = _gameData.value
        if (data.worldMapSects.isEmpty()) {
            val worldSects = WorldMapGenerator.generateWorldSects(data.sectName)
            val initialYear = data.gameYear
            val sectsWithTradeItems = worldSects.map { sect ->
                if (!sect.isPlayerSect) {
                    sect.copy(
                        tradeItems = generateSectTradeItems(initialYear),
                        tradeLastRefreshYear = initialYear
                    )
                } else {
                    sect
                }
            }
            _gameData.value = data.copy(worldMapSects = sectsWithTradeItems)
        }
    }
    
    fun createNewGame(sectName: String) {
        _gameData.value = GameData(
            sectName = sectName,
            gameYear = 1,
            gameMonth = 1,
            spiritStones = 1000,
            recruitList = emptyList(),
            lastRecruitYear = 0
        )

        val initialMonth = 1 * 12 + 1
        val initialDisciples = generateInitialDisciples(3).map { 
            it.copy(recruitedMonth = initialMonth)
        }
        _disciples.value = initialDisciples
        
        _equipment.value = emptyList()
        _pills.value = emptyList()
        _materials.value = emptyList()
        _herbs.value = emptyList()
        _seeds.value = emptyList()
        _teams.value = emptyList()
        _buildingSlots.value = emptyList()
        _events.value = emptyList()
        _battleLogs.value = emptyList()
        _alchemySlots.value = (0 until 3).map { index ->
            AlchemySlot(slotIndex = index)
        }
        
        val initialManual = ManualDatabase.getByType(ManualType.MIND)
            .filter { it.rarity == 1 }
            .randomOrNull()
            ?.let { ManualDatabase.createFromTemplate(it) }
        _manuals.value = if (initialManual != null) listOf(initialManual) else emptyList()
        
        initializeWorldMap()
        
        // 初始化 AI 宗门间关系
        val sectRelations = WorldMapGenerator.initializeSectRelations(_gameData.value.worldMapSects)
        _gameData.value = _gameData.value.copy(sectRelations = sectRelations)
        
        initializeHerbGardenSlots()
        
        refreshRecruitList(1)
        
        checkAndInitMerchant(1)
        
        refreshMissions(1, 1)
        
        addEvent("欢迎来到 $sectName！宗门初立，请好好经营。", EventType.SUCCESS)
    }
    
    fun updateGameData(update: (GameData) -> GameData) {
        _gameData.value = update(_gameData.value)
    }
    
    fun loadData(
        gameData: GameData,
        disciples: List<Disciple>,
        equipment: List<Equipment>,
        manuals: List<Manual>,
        pills: List<Pill>,
        materials: List<Material> = emptyList(),
        herbs: List<Herb> = emptyList(),
        seeds: List<Seed> = emptyList(),
        teams: List<ExplorationTeam>,
        slots: List<BuildingSlot>,
        events: List<GameEvent>,
        battleLogs: List<BattleLog> = emptyList(),
        alliances: List<Alliance> = emptyList(),
        supportTeams: List<SupportTeam> = emptyList(),
        alchemySlots: List<AlchemySlot> = emptyList()
    ) {
        val resolvedAlliances = alliances.ifEmpty { gameData.alliances }
        val resolvedSupportTeams = supportTeams.ifEmpty { gameData.supportTeams }
        
        val fixedRecruitList = gameData.recruitList.map { disciple ->
            if (disciple.baseHp != 100) {
                disciple
            } else {
                val variance = if (disciple.combatStatsVariance == 0) {
                    Random.nextInt(-30, 31)
                } else {
                    disciple.combatStatsVariance
                }
                val varianceMultiplier = 1.0 + variance / 100.0
                disciple.copy(
                    combatStatsVariance = variance,
                    baseHp = (100 * varianceMultiplier).toInt(),
                    baseMp = (50 * varianceMultiplier).toInt(),
                    basePhysicalAttack = (10 * varianceMultiplier).toInt(),
                    baseMagicAttack = (5 * varianceMultiplier).toInt(),
                    basePhysicalDefense = (5 * varianceMultiplier).toInt(),
                    baseMagicDefense = (3 * varianceMultiplier).toInt(),
                    baseSpeed = (10 * varianceMultiplier).toInt()
                )
            }
        }
        
        val finalGameData = gameData.copy(
            alliances = resolvedAlliances,
            supportTeams = resolvedSupportTeams,
            recruitList = fixedRecruitList
        )
        
        _gameData.value = finalGameData
        _disciples.value = disciples.map { disciple ->
            if (disciple.baseHp != 100) {
                disciple
            } else {
                val variance = if (disciple.combatStatsVariance == 0) {
                    Random.nextInt(-30, 31)
                } else {
                    disciple.combatStatsVariance
                }
                val varianceMultiplier = 1.0 + variance / 100.0
                disciple.copy(
                    combatStatsVariance = variance,
                    baseHp = (100 * varianceMultiplier).toInt(),
                    baseMp = (50 * varianceMultiplier).toInt(),
                    basePhysicalAttack = (10 * varianceMultiplier).toInt(),
                    baseMagicAttack = (5 * varianceMultiplier).toInt(),
                    basePhysicalDefense = (5 * varianceMultiplier).toInt(),
                    baseMagicDefense = (3 * varianceMultiplier).toInt(),
                    baseSpeed = (10 * varianceMultiplier).toInt()
                )
            }
        }
        _equipment.value = equipment
        _manuals.value = manuals
        _pills.value = pills
        _materials.value = materials
        _herbs.value = herbs
        _seeds.value = seeds
        _teams.value = teams
        _buildingSlots.value = slots
        _events.value = events
        _battleLogs.value = battleLogs
        _alchemySlots.value = alchemySlots.ifEmpty {
            (0 until 3).map { index -> AlchemySlot(slotIndex = index) }
        }

        initializeWorldMap()

        val playerSect = _gameData.value.worldMapSects.find { it.isPlayerSect }
        if (playerSect != null) {
            val existingPlayerRelations = _gameData.value.sectRelations.filter {
                it.sectId1 == playerSect.id || it.sectId2 == playerSect.id
            }
            val missingSects = _gameData.value.worldMapSects.filter { sect ->
                !sect.isPlayerSect && existingPlayerRelations.none { r ->
                    r.sectId1 == sect.id || r.sectId2 == sect.id
                }
            }
            if (missingSects.isNotEmpty()) {
                val newRelations = missingSects.map { sect ->
                    SectRelation(
                        sectId1 = minOf(playerSect.id, sect.id),
                        sectId2 = maxOf(playerSect.id, sect.id),
                        favor = sect.relation.coerceIn(0, 100),
                        lastInteractionYear = 0
                    )
                }
                _gameData.value = _gameData.value.copy(
                    sectRelations = _gameData.value.sectRelations + newRelations
                )
            }
        }

        initializeHerbGardenSlots()

        initializeLibrarySlots()

        checkAndInitMerchant(gameData.gameYear)

        validateAndFixSpiritMineData()
    }
    
    private fun checkAndInitMerchant(currentYear: Int) {
        val data = _gameData.value
        // 只在初始化时（从未刷新过）刷新商品
        if (data.merchantLastRefreshYear == 0) {
            val (items, newCount) = generateMerchantItems(
                currentYear,
                data.merchantRefreshCount
            )
            _gameData.value = data.copy(
                travelingMerchantItems = items,
                merchantLastRefreshYear = currentYear,
                merchantRefreshCount = newCount
            )
            addEvent("云游商人到访！带来了${items.size}件商品", EventType.SUCCESS)
        }
    }

    /**
     * 初始化灵药园种植槽位
     * 确保旧存档升级后也有正确的槽位数据结构
     */
    private fun initializeHerbGardenSlots() {
        val data = _gameData.value
        val currentSlots = data.herbGardenPlantSlots

        // 检查是否需要初始化槽位
        val needsInitialization = currentSlots.isEmpty() ||
                currentSlots.size < 3 ||
                (0..2).any { index -> currentSlots.none { it.index == index } }

        if (needsInitialization) {
            // 创建完整的3个槽位，保留已有数据
            val initializedSlots = (0..2).map { index ->
                currentSlots.find { it.index == index } ?: PlantSlotData(index = index)
            }
            _gameData.value = data.copy(herbGardenPlantSlots = initializedSlots)
        }
    }
    
    private fun initializeLibrarySlots() {
        val data = _gameData.value
        val currentSlots = data.librarySlots

        val needsInitialization = currentSlots.isEmpty() ||
                currentSlots.size < 3 ||
                (0..2).any { index -> currentSlots.none { it.index == index } }

        if (needsInitialization) {
            val initializedSlots = (0..2).map { index ->
                currentSlots.find { it.index == index } ?: LibrarySlot(index = index)
            }
            _gameData.value = data.copy(librarySlots = initializedSlots)
        }
    }

    /**
     * 校验并修复灵矿槽位与弟子状态的数据一致性
     * 解决弟子状态为MINING但槽位无记录的问题
     * 同时处理：无效弟子ID、重复分配、死亡弟子等边界情况
     */
    fun validateAndFixSpiritMineData() {
        val existingDiscipleIds = _disciples.value.map { it.id }.toSet()
        val deadDiscipleIds = _disciples.value.filter { !it.isAlive }.map { it.id }.toSet()

        val data = _gameData.value
        val spiritMineSlots = data.spiritMineSlots
        val spiritMineDeaconDisciples = data.elderSlots.spiritMineDeaconDisciples

        val validMiningDiscipleIds = spiritMineSlots
            .mapNotNull { it.discipleId }
            .filter { it in existingDiscipleIds && it !in deadDiscipleIds }
            .toSet()

        val validDeaconDiscipleIds = spiritMineDeaconDisciples
            .mapNotNull { it.discipleId }
            .filter { it in existingDiscipleIds && it !in deadDiscipleIds }
            .toSet()

        val duplicateIds = (validMiningDiscipleIds + validDeaconDiscipleIds)
            .groupingBy { it }
            .eachCount()
            .filter { it.value > 1 }
            .keys

        val allValidMiningDiscipleIds = (validMiningDiscipleIds + validDeaconDiscipleIds) - duplicateIds

        val updatedDisciples = _disciples.value.map { disciple ->
            when {
                disciple.status == DiscipleStatus.MINING && disciple.id !in allValidMiningDiscipleIds -> {
                    disciple.copy(status = DiscipleStatus.IDLE)
                }
                disciple.status != DiscipleStatus.MINING && disciple.id in allValidMiningDiscipleIds -> {
                    disciple.copy(status = DiscipleStatus.MINING)
                }
                else -> disciple
            }
        }

        val updatedSpiritMineSlots = spiritMineSlots.mapIndexed { index, slot ->
            when {
                slot.discipleId == null -> slot
                slot.discipleId !in existingDiscipleIds -> slot.copy(discipleId = null, discipleName = "")
                slot.discipleId in deadDiscipleIds -> slot.copy(discipleId = null, discipleName = "")
                slot.discipleId in duplicateIds && index > spiritMineSlots.indexOfFirst { it.discipleId == slot.discipleId } -> {
                    slot.copy(discipleId = null, discipleName = "")
                }
                else -> slot
            }
        }

        val updatedDeaconDisciples = spiritMineDeaconDisciples.mapIndexed { index, slot ->
            when {
                slot.discipleId == null -> slot
                slot.discipleId !in existingDiscipleIds -> slot.copy(discipleId = null, discipleName = "", discipleRealm = "", discipleSpiritRootColor = "#E0E0E0")
                slot.discipleId in deadDiscipleIds -> slot.copy(discipleId = null, discipleName = "", discipleRealm = "", discipleSpiritRootColor = "#E0E0E0")
                slot.discipleId in duplicateIds && index > spiritMineDeaconDisciples.indexOfFirst { it.discipleId == slot.discipleId } -> {
                    slot.copy(discipleId = null, discipleName = "", discipleRealm = "", discipleSpiritRootColor = "#E0E0E0")
                }
                else -> slot
            }
        }

        _disciples.value = updatedDisciples

        val latestData = _gameData.value
        if (updatedSpiritMineSlots != spiritMineSlots || updatedDeaconDisciples != spiritMineDeaconDisciples) {
            val updatedElderSlots = latestData.elderSlots.copy(spiritMineDeaconDisciples = updatedDeaconDisciples)
            _gameData.value = latestData.copy(
                spiritMineSlots = updatedSpiritMineSlots,
                elderSlots = updatedElderSlots
            )
        }
    }
    
    fun assignDiscipleToLibrarySlot(slotIndex: Int, discipleId: String, discipleName: String) {
        val data = _gameData.value
        val currentSlots = data.librarySlots.toMutableList()
        
        val existingIndex = currentSlots.indexOfFirst { it.discipleId == discipleId }
        if (existingIndex >= 0) {
            currentSlots[existingIndex] = currentSlots[existingIndex].copy(
                discipleId = null,
                discipleName = ""
            )
        }
        
        val slotIndexInList = currentSlots.indexOfFirst { it.index == slotIndex }
        if (slotIndexInList >= 0) {
            currentSlots[slotIndexInList] = currentSlots[slotIndexInList].copy(
                discipleId = discipleId,
                discipleName = discipleName
            )
        } else {
            currentSlots.add(LibrarySlot(index = slotIndex, discipleId = discipleId, discipleName = discipleName))
        }
        
        _gameData.value = data.copy(librarySlots = currentSlots)
        syncAllDiscipleStatuses()
    }
    
    fun removeDiscipleFromLibrarySlot(slotIndex: Int) {
        val data = _gameData.value
        val currentSlots = data.librarySlots.toMutableList()
        
        val slotIndexInList = currentSlots.indexOfFirst { it.index == slotIndex }
        if (slotIndexInList >= 0) {
            currentSlots[slotIndexInList] = currentSlots[slotIndexInList].copy(
                discipleId = null,
                discipleName = ""
            )
            _gameData.value = data.copy(librarySlots = currentSlots)
            syncAllDiscipleStatuses()
        }
    }
    
    fun advanceDay() {
        val startTime = System.currentTimeMillis()
        try {
            val data = _gameData.value

            var newDay = data.gameDay + 1
            var newMonth = data.gameMonth
            var newYear = data.gameYear
            var monthChanged = false
            
            if (newDay > 30) {
                newDay = 1
                newMonth++
                monthChanged = true
                if (newMonth > GameConfig.Time.MONTHS_PER_YEAR) {
                    newMonth = 1
                    newYear++
                    Log.i(TAG, "Year advanced: $newYear")
                }
            }

            _gameData.value = data.copy(
                gameYear = newYear,
                gameMonth = newMonth,
                gameDay = newDay
            )

            try {
                processDailyEvents(newYear, newMonth, newDay)
            } catch (e: Exception) {
                Log.e(TAG, "Error in processDailyEvents - " +
                    "Game: Year=$newYear, Month=$newMonth, Day=$newDay, " +
                    "Sect=${data.sectName}", e)
            }
            
            if (monthChanged) {
                Log.d(TAG, "Month changed to $newMonth, processing monthly events")
                try {
                    processMonthlyEvents(newYear, newMonth)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in processMonthlyEvents - " +
                        "Game: Year=$newYear, Month=$newMonth, " +
                        "Sect=${data.sectName}, Disciples=${_disciples.value.size}, " +
                        "Teams=${_teams.value.size}, BuildingSlots=${_buildingSlots.value.size}", e)
                }
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > 100) {
                Log.w(TAG, "advanceDay took ${elapsed}ms (year=$newYear, month=$newMonth, day=$newDay)")
            }
        } catch (e: Exception) {
            val data = _gameData.value
            Log.e(TAG, "Critical error in advanceDay - " +
                "Game: Year=${data.gameYear}, Month=${data.gameMonth}, Day=${data.gameDay}, " +
                "Sect=${data.sectName}, SpiritStones=${data.spiritStones}", e)
        }
    }
    
    private fun processDailyEvents(year: Int, month: Int, day: Int) {
        val data = _gameData.value
        
        try {
            processScoutTeamMovement()
        } catch (e: Exception) {
            Log.e(TAG, "Error in processScoutTeamMovement - " +
                "Game: Year=$year, Month=$month, Day=$day, Teams=${_teams.value.size}", e)
        }
        
        try {
            processCaveExplorationTeamMovement()
        } catch (e: Exception) {
            Log.e(TAG, "Error in processCaveExplorationTeamMovement - " +
                "Game: Year=$year, Month=$month, Day=$day, CaveTeams=${data.caveExplorationTeams.size}", e)
        }
        
        try {
            processBattleTeamMovement()
        } catch (e: Exception) {
            Log.e(TAG, "Error in processBattleTeamMovement - " +
                "Game: Year=$year, Month=$month, Day=$day, BattleTeam=${data.battleTeam?.id}", e)
        }
        
        try {
            processSupportTeamMovement()
        } catch (e: Exception) {
            Log.e(TAG, "Error in processSupportTeamMovement - " +
                "Game: Year=$year, Month=$month, Day=$day, SupportTeams=${data.supportTeams.size}", e)
        }
        
        try {
            processAIBattleTeamMovement()
        } catch (e: Exception) {
            Log.e(TAG, "Error in processAIBattleTeamMovement - " +
                "Game: Year=$year, Month=$month, Day=$day, WorldSects=${data.worldMapSects.size}", e)
        }
    }
    
    private fun processScoutTeamMovement() {
        val data = _gameData.value
        val updatedTeams = _teams.value.map { team ->
            if (team.status == ExplorationStatus.SCOUTING && team.moveProgress < 1f) {
                val progressIncrement = 1f / team.duration.coerceAtLeast(1) * 1.5f
                val newProgress = (team.moveProgress + progressIncrement).coerceAtMost(1f)
                
                val targetSect = data.worldMapSects.find { it.id == team.scoutTargetSectId }
                val playerSect = data.worldMapSects.find { it.isPlayerSect }
                
                val (newX, newY) = if (targetSect != null && playerSect != null) {
                    val bezierPos = calculateBezierPosition(
                        fromX = playerSect.x,
                        fromY = playerSect.y,
                        toX = targetSect.x,
                        toY = targetSect.y,
                        fromId = playerSect.id,
                        toId = targetSect.id,
                        t = newProgress
                    )
                    Pair(bezierPos.first, bezierPos.second)
                } else {
                    val x = team.currentX + (team.targetX - team.currentX) * newProgress
                    val y = team.currentY + (team.targetY - team.currentY) * newProgress
                    Pair(x, y)
                }
                
                team.copy(
                    moveProgress = newProgress,
                    currentX = if (newProgress >= 1f) team.targetX else newX,
                    currentY = if (newProgress >= 1f) team.targetY else newY
                )
            } else {
                team
            }
        }
        _teams.value = updatedTeams
        
        updatedTeams.filter { it.status == ExplorationStatus.SCOUTING && it.moveProgress >= 1f }.forEach { team ->
            triggerScoutBattleOnArrival(team)
        }
    }
    
    private fun calculateBezierPosition(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        fromId: String,
        toId: String,
        t: Float
    ): Pair<Float, Float> {
        val distance = kotlin.math.sqrt((toX - fromX) * (toX - fromX) + (toY - fromY) * (toY - fromY))
        if (distance < 1f) return Pair(fromX, fromY)
        
        val midX = (fromX + toX) / 2
        val midY = (fromY + toY) / 2
        
        val dx = toX - fromX
        val dy = toY - fromY
        val normalX = -dy / distance
        val normalY = dx / distance
        
        val (sortedId1, sortedId2) = if (fromId < toId) fromId to toId else toId to fromId
        val randomOffset = ((sortedId1.hashCode() + sortedId2.hashCode()) % 100 - 50) / 100f
        val curveStrength = distance * 0.2f * randomOffset
        
        val controlX = midX + normalX * curveStrength
        val controlY = midY + normalY * curveStrength
        
        val oneMinusT = 1 - t
        val posX = oneMinusT * oneMinusT * fromX + 2 * oneMinusT * t * controlX + t * t * toX
        val posY = oneMinusT * oneMinusT * fromY + 2 * oneMinusT * t * controlY + t * t * toY
        
        return Pair(posX.toFloat(), posY.toFloat())
    }
    
    private fun processCaveExplorationTeamMovement() {
        try {
            val data = _gameData.value
            
            val updatedTeams = data.caveExplorationTeams.map { team ->
                if (team.status == CaveExplorationStatus.TRAVELING && team.moveProgress < 1f) {
                    val progressIncrement = 1f / team.duration.coerceAtLeast(1) * 1.5f
                    val newProgress = (team.moveProgress + progressIncrement).coerceAtMost(1f)
                    
                    val newX = team.startX + (team.targetX - team.startX) * newProgress
                    val newY = team.startY + (team.targetY - team.startY) * newProgress
                    
                    team.copy(
                        moveProgress = newProgress,
                        currentX = if (newProgress >= 1f) team.targetX else newX,
                        currentY = if (newProgress >= 1f) team.targetY else newY
                    )
                } else {
                    team
                }
            }
            
            _gameData.value = data.copy(caveExplorationTeams = updatedTeams)
            
            val teamsWithMissingCave = mutableListOf<CaveExplorationTeam>()
            updatedTeams.filter { it.status == CaveExplorationStatus.TRAVELING && it.moveProgress >= 1f }.forEach { team ->
                val cave = data.cultivatorCaves.find { it.id == team.caveId }
                if (cave != null) {
                    startCaveExplorationPhase(team, cave)
                } else {
                    teamsWithMissingCave.add(team)
                }
            }
            
            teamsWithMissingCave.forEach { team ->
                addEvent("探索队伍前往的${team.caveName}已消失，队伍返回宗门", EventType.WARNING)
                resetCaveExplorationTeamMembersStatus(team)
                val currentData = _gameData.value
                _gameData.value = currentData.copy(
                    caveExplorationTeams = currentData.caveExplorationTeams.filter { it.id != team.id }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in processCaveExplorationTeamMovement", e)
            addEvent("探索队伍移动处理发生错误，请检查队伍状态", EventType.WARNING)
        }
    }
    
    private fun resetCaveExplorationTeamMembersStatus(team: CaveExplorationTeam) {
        team.memberIds.forEach { memberId ->
            val disciple = _disciples.value.find { it.id == memberId }
            if (disciple != null && disciple.status == DiscipleStatus.EXPLORING) {
                _disciples.value = _disciples.value.map { 
                    if (it.id == memberId) it.copy(status = DiscipleStatus.IDLE) else it 
                }
            }
        }
    }
    
    private fun startCaveExplorationPhase(team: CaveExplorationTeam, cave: CultivatorCave) {
        val data = _gameData.value
        val updatedTeam = team.copy(status = CaveExplorationStatus.EXPLORING)
        
        _gameData.value = data.copy(
            caveExplorationTeams = data.caveExplorationTeams.map { 
                if (it.id == team.id) updatedTeam else it 
            }
        )
        
        addEvent("探索队伍已抵达${cave.name}，开始探索", EventType.INFO)
    }
    
    private fun processBattleTeamMovement() {
        val data = _gameData.value
        val battleTeam = data.battleTeam ?: return
        
        if (battleTeam.status != "moving" || battleTeam.moveProgress >= 1f) return
        
        val route = battleTeam.route
        if (route.isEmpty()) return
        
        val currentSectId = route.getOrNull(battleTeam.currentRouteIndex) ?: return
        val nextSectId = route.getOrNull(battleTeam.currentRouteIndex + 1)
        
        val currentSect = data.worldMapSects.find { it.id == currentSectId } ?: return
        
        val targetSect = if (nextSectId != null) {
            data.worldMapSects.find { it.id == nextSectId } ?: return
        } else {
            val targetId = battleTeam.targetSectId ?: return
            data.worldMapSects.find { it.id == targetId } ?: return
        }
        
        val distance = kotlin.math.sqrt(
            (targetSect.x - currentSect.x) * (targetSect.x - currentSect.x) +
            (targetSect.y - currentSect.y) * (targetSect.y - currentSect.y)
        )
        
        val duration = (distance / 100f).coerceAtLeast(1f).toInt()
        val progressIncrement = 1f / duration.coerceAtLeast(1) * 1.5f
        val newProgress = (battleTeam.moveProgress + progressIncrement).coerceAtMost(1f)
        
        val newX = currentSect.x + (targetSect.x - currentSect.x) * newProgress
        val newY = currentSect.y + (targetSect.y - currentSect.y) * newProgress
        
        if (newProgress >= 1f) {
            if (nextSectId != null) {
                val updatedTeam = battleTeam.copy(
                    currentRouteIndex = battleTeam.currentRouteIndex + 1,
                    moveProgress = 0f,
                    currentX = targetSect.x,
                    currentY = targetSect.y
                )
                _gameData.value = data.copy(battleTeam = updatedTeam)
            } else {
                if (battleTeam.isReturning) {
                    val playerSect = data.worldMapSects.find { it.isPlayerSect }
                    val originSectId = battleTeam.originSectId
                    val isReturningToPlayerSect = originSectId == null || originSectId == playerSect?.id
                    
                    if (isReturningToPlayerSect) {
                        val updatedTeam = battleTeam.copy(
                            status = "idle",
                            isAtSect = true,
                            moveProgress = 0f,
                            currentX = targetSect.x,
                            currentY = targetSect.y,
                            targetSectId = null,
                            originSectId = null,
                            route = emptyList(),
                            currentRouteIndex = 0,
                            isReturning = false,
                            isOccupying = false,
                            occupiedSectId = null
                        )
                        _gameData.value = data.copy(battleTeam = updatedTeam)
                    } else {
                        val updatedSects = data.worldMapSects.map { sect ->
                            if (sect.id == originSectId) {
                                sect.copy(occupierBattleTeamId = battleTeam.id)
                            } else {
                                sect
                            }
                        }
                        val updatedTeam = battleTeam.copy(
                            status = "stationed",
                            isAtSect = false,
                            moveProgress = 0f,
                            currentX = targetSect.x,
                            currentY = targetSect.y,
                            targetSectId = null,
                            route = emptyList(),
                            currentRouteIndex = 0,
                            isReturning = false,
                            isOccupying = true,
                            occupiedSectId = originSectId
                        )
                        _gameData.value = data.copy(
                            battleTeam = updatedTeam,
                            worldMapSects = updatedSects
                        )
                    }
                } else {
                    val updatedTeam = battleTeam.copy(
                        status = "battle",
                        moveProgress = 1f,
                        currentX = targetSect.x,
                        currentY = targetSect.y
                    )
                    _gameData.value = data.copy(battleTeam = updatedTeam)
                    
                    if (updatedTeam.status == "battle" && !updatedTeam.isReturning) {
                        triggerBattleTeamCombat(updatedTeam)
                    }
                }
            }
        } else {
            val updatedTeam = battleTeam.copy(
                moveProgress = newProgress,
                currentX = newX,
                currentY = newY
            )
            _gameData.value = data.copy(battleTeam = updatedTeam)
        }
    }
    
    fun startBattleTeamMove(targetSectId: String) {
        val data = _gameData.value
        val battleTeam = data.battleTeam ?: return
        
        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return
        val targetSect = data.worldMapSects.find { it.id == targetSectId } ?: return
        
        val startSectId = if (battleTeam.isOccupying && battleTeam.occupiedSectId != null) {
            val occupiedSectId = battleTeam.occupiedSectId!!
            val updatedSects = data.worldMapSects.map { sect ->
                if (sect.id == occupiedSectId) {
                    sect.copy(occupierBattleTeamId = null)
                } else {
                    sect
                }
            }
            _gameData.value = data.copy(worldMapSects = updatedSects)
            occupiedSectId
        } else {
            playerSect.id
        }
        
        val startSect = _gameData.value.worldMapSects.find { it.id == startSectId } ?: playerSect
        
        if (!AISectAttackManager.isRouteConnected(startSect, targetSect, _gameData.value)) {
            addEvent("目标宗门不在可达路线上", EventType.DANGER)
            return
        }
        
        val route = calculateRoute(startSectId, targetSectId, _gameData.value.worldMapSects)
        
        val updatedTeam = battleTeam.copy(
            status = "moving",
            isAtSect = false,
            targetSectId = targetSectId,
            originSectId = startSectId,
            route = route,
            currentRouteIndex = 0,
            moveProgress = 0f,
            currentX = startSect.x,
            currentY = startSect.y,
            targetX = targetSect.x,
            targetY = targetSect.y,
            isReturning = false,
            isOccupying = false,
            occupiedSectId = null
        )
        
        val updatedData = if (!targetSect.isPlayerSect && data.isPlayerProtected) {
            _gameData.value.copy(
                battleTeam = updatedTeam,
                playerHasAttackedAI = true
            )
        } else {
            _gameData.value.copy(battleTeam = updatedTeam)
        }
        
        _gameData.value = updatedData
        addEvent("战斗队伍开始向${targetSect.name}进发", EventType.INFO)
    }
    
    private fun calculateRoute(fromId: String, toId: String, sects: List<WorldSect>): List<String> {
        val route = mutableListOf<String>()
        route.add(fromId)
        
        val fromSect = sects.find { it.id == fromId } ?: return route
        if (fromSect.connectedSectIds.contains(toId)) {
            route.add(toId)
            return route
        }
        
        val visited = mutableSetOf(fromId)
        val queue = ArrayDeque<List<String>>()
        queue.add(listOf(fromId))
        
        while (queue.isNotEmpty()) {
            val currentRoute = queue.removeFirst()
            val currentId = currentRoute.last()
            val currentSect = sects.find { it.id == currentId } ?: continue
            
            for (connectedId in currentSect.connectedSectIds) {
                if (connectedId == toId) {
                    return currentRoute + connectedId
                }
                if (connectedId !in visited) {
                    visited.add(connectedId)
                    queue.add(currentRoute + connectedId)
                }
            }
        }
        
        return route
    }
    
    private fun triggerBattleTeamCombat(battleTeam: BattleTeam) {
        val data = _gameData.value
        val targetSectId = battleTeam.targetSectId ?: return
        val targetSect = data.worldMapSects.find { it.id == targetSectId } ?: return
        
        val teamMembers = battleTeam.slots.filter { it.discipleId != null && it.isAlive }.mapNotNull { slot ->
            _disciples.value.find { it.id == slot.discipleId }
        }.filter { it.isAlive }
        
        if (teamMembers.isEmpty()) {
            addEvent("战斗队伍无可用弟子，自动返回", EventType.DANGER)
            returnBattleTeam(battleTeam)
            return
        }
        
        val hasHighRealmDisciple = targetSect.aiDisciples.any { it.isAlive && it.realm <= 5 }
        if (!hasHighRealmDisciple) {
            occupySect(battleTeam, targetSect)
            return
        }
        
        val defenseTeam = generateAISectDefenseTeam(targetSect)
        
        if (defenseTeam.isEmpty()) {
            occupySect(battleTeam, targetSect)
            return
        }
        
        val battleResult = executeBattleTeamCombat(teamMembers, defenseTeam, targetSect)
        
        val updatedTargetSect = _gameData.value.worldMapSects.find { it.id == targetSectId } ?: targetSect
        
        if (battleResult.teamWins) {
            val hasRemainingHighRealm = updatedTargetSect.aiDisciples.any { it.isAlive && it.realm <= 5 }
            if (!hasRemainingHighRealm) {
                occupySect(battleTeam, updatedTargetSect)
            } else {
                addEvent("战斗队伍在${updatedTargetSect.name}战斗胜利，开始返回", EventType.SUCCESS)
                returnBattleTeam(battleTeam)
            }
        } else {
            addEvent("战斗队伍在${updatedTargetSect.name}战斗失败，开始返回", EventType.DANGER)
            returnBattleTeam(battleTeam)
        }
        
        removeDeadDisciplesFromBattleTeam()
    }
    
    private fun generateAISectDefenseTeam(targetSect: WorldSect): List<Disciple> {
        val sortedDisciples = targetSect.aiDisciples
            .filter { it.isAlive }
            .sortedBy { it.realm }
        
        return sortedDisciples.take(10)
    }
    
    private fun executeBattleTeamCombat(
        teamMembers: List<Disciple>,
        defenseTeam: List<Disciple>,
        targetSect: WorldSect
    ): BattleTeamCombatResult {
        val teamCombatants = teamMembers.map { disciple ->
            val stats = disciple.getBaseStats()
            Combatant(
                id = disciple.id,
                name = disciple.name,
                type = CombatantType.DISCIPLE,
                hp = stats.maxHp,
                maxHp = stats.maxHp,
                mp = stats.maxMp,
                maxMp = stats.maxMp,
                physicalAttack = stats.physicalAttack,
                magicAttack = stats.magicAttack,
                physicalDefense = stats.physicalDefense,
                magicDefense = stats.magicDefense,
                speed = stats.speed,
                critRate = stats.critRate,
                skills = emptyList(),
                realm = disciple.realm,
                realmName = disciple.realmName,
                realmLayer = disciple.realmLayer
            )
        }
        
        val enemyCombatants = defenseTeam.mapIndexed { index, aiDisciple ->
            val stats = aiDisciple.getBaseStats()
            Combatant(
                id = aiDisciple.id,
                name = aiDisciple.name,
                type = CombatantType.BEAST,
                hp = stats.maxHp,
                maxHp = stats.maxHp,
                mp = stats.maxMp,
                maxMp = stats.maxMp,
                physicalAttack = stats.physicalAttack,
                magicAttack = stats.magicAttack,
                physicalDefense = stats.physicalDefense,
                magicDefense = stats.magicDefense,
                speed = stats.speed,
                critRate = 0.05 + aiDisciple.realm * 0.01,
                skills = emptyList(),
                realm = aiDisciple.realm,
                realmName = GameConfig.Realm.getName(aiDisciple.realm),
                realmLayer = aiDisciple.realmLayer
            )
        }
        
        val battle = Battle(
            team = teamCombatants,
            beasts = enemyCombatants,
            turn = 0,
            isFinished = false,
            winner = null
        )
        
        val battleResult = BattleSystem.executeBattle(battle)
        
        val teamDeadIds = mutableSetOf<String>()
        val enemyDeadIds = mutableSetOf<String>()
        
        battleResult.battle.team.forEach { combatant ->
            if (combatant.isDead) {
                teamDeadIds.add(combatant.id)
            }
        }
        
        battleResult.battle.beasts.forEach { combatant ->
            if (combatant.isDead) {
                enemyDeadIds.add(combatant.id)
            }
        }
        
        teamDeadIds.forEach { discipleId ->
            _disciples.value = _disciples.value.map { 
                if (it.id == discipleId) it.copy(isAlive = false) else it 
            }
        }
        
        val data = _gameData.value
        val updatedSects = data.worldMapSects.map { sect ->
            if (sect.id == targetSect.id) {
                val aliveAiDisciples = sect.aiDisciples.filter { it.id !in enemyDeadIds }
                sect.copy(aiDisciples = aliveAiDisciples)
            } else {
                sect
            }
        }
        _gameData.value = data.copy(worldMapSects = updatedSects)
        
        return BattleTeamCombatResult(
            teamWins = battleResult.victory,
            teamCasualties = teamDeadIds.size,
            enemyCasualties = enemyDeadIds.size
        )
    }
    
    private data class BattleTeamCombatResult(
        val teamWins: Boolean,
        val teamCasualties: Int,
        val enemyCasualties: Int
    )
    
    private fun returnBattleTeam(battleTeam: BattleTeam) {
        val data = _gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return
        
        val originSectId = battleTeam.originSectId ?: playerSect.id
        val originSect = data.worldMapSects.find { it.id == originSectId } ?: playerSect
        
        val currentPositionId = when {
            battleTeam.status == "battle" && battleTeam.targetSectId != null -> {
                battleTeam.targetSectId!!
            }
            battleTeam.status == "moving" && battleTeam.route.isNotEmpty() -> {
                battleTeam.route.getOrNull(battleTeam.currentRouteIndex) ?: originSectId
            }
            battleTeam.isOccupying && battleTeam.occupiedSectId != null -> {
                battleTeam.occupiedSectId
            }
            else -> originSectId
        }
        
        val returnRoute = calculateRoute(currentPositionId, originSectId, data.worldMapSects)
        
        val updatedTeam = battleTeam.copy(
            status = "moving",
            targetSectId = originSectId,
            route = returnRoute,
            currentRouteIndex = 0,
            moveProgress = 0f,
            targetX = originSect.x,
            targetY = originSect.y,
            isReturning = true
        )
        
        _gameData.value = data.copy(battleTeam = updatedTeam)
    }
    
    private fun removeDeadDisciplesFromBattleTeam() {
        val data = _gameData.value
        val battleTeam = data.battleTeam ?: return
        
        val updatedSlots = battleTeam.slots.map { slot ->
            if (slot.discipleId != null) {
                val disciple = _disciples.value.find { it.id == slot.discipleId }
                if (disciple != null && !disciple.isAlive) {
                    slot.copy(isAlive = false)
                } else {
                    slot
                }
            } else {
                slot
            }
        }
        
        val updatedTeam = battleTeam.copy(slots = updatedSlots)
        _gameData.value = data.copy(battleTeam = updatedTeam)
    }
    
    private fun occupySect(battleTeam: BattleTeam, targetSect: WorldSect) {
        val data = _gameData.value
        val currentTargetSect = data.worldMapSects.find { it.id == targetSect.id } ?: targetSect
        
        val aliveAiDisciples = currentTargetSect.aiDisciples.filter { it.isAlive }
        
        val newPlayerDisciples = aliveAiDisciples.map { aiDisciple ->
            Disciple(
                id = java.util.UUID.randomUUID().toString(),
                name = aiDisciple.name,
                realm = aiDisciple.realm,
                realmLayer = aiDisciple.realmLayer,
                cultivation = aiDisciple.cultivation,
                spiritRootType = aiDisciple.spiritRootType,
                age = aiDisciple.age,
                lifespan = aiDisciple.lifespan,
                status = DiscipleStatus.IDLE,
                discipleType = "outer",
                recruitedMonth = data.gameYear * 12 + data.gameMonth
            )
        }
        
        _disciples.value = _disciples.value + newPlayerDisciples
        
        val sectWarehouse = currentTargetSect.warehouse
        var warehouseItemText = ""
        if (sectWarehouse.items.isNotEmpty()) {
            _gameData.value = _gameData.value.copy(
                spiritStones = _gameData.value.spiritStones + sectWarehouse.spiritStones
            )
            
            sectWarehouse.items.forEach { item ->
                when (item.itemType) {
                    "pill" -> {
                        val recipe = PillRecipeDatabase.getRecipeById(item.itemId)
                        if (recipe != null) {
                            repeat(item.quantity) {
                                val pill = Pill(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = recipe.name,
                                    rarity = recipe.rarity,
                                    quantity = 1,
                                    description = recipe.description,
                                    category = recipe.category,
                                    breakthroughChance = recipe.breakthroughChance,
                                    targetRealm = recipe.targetRealm,
                                    cultivationSpeed = recipe.cultivationSpeed,
                                    duration = recipe.effectDuration,
                                    cultivationPercent = recipe.cultivationPercent,
                                    skillExpPercent = recipe.skillExpPercent,
                                    extendLife = recipe.extendLife,
                                    physicalAttackPercent = recipe.physicalAttackPercent,
                                    magicAttackPercent = recipe.magicAttackPercent,
                                    physicalDefensePercent = recipe.physicalDefensePercent,
                                    magicDefensePercent = recipe.magicDefensePercent,
                                    hpPercent = recipe.hpPercent,
                                    mpPercent = recipe.mpPercent,
                                    speedPercent = recipe.speedPercent,
                                    healPercent = recipe.healPercent,
                                    healMaxHpPercent = recipe.healMaxHpPercent,
                                    heal = recipe.heal,
                                    battleCount = recipe.battleCount,
                                    mpRecoverMaxMpPercent = recipe.mpRecoverMaxMpPercent
                                )
                                addPillToWarehouse(pill)
                            }
                        } else {
                            Log.w(TAG, "无法找到丹药配方: ${item.itemId}, 物品: ${item.itemName}")
                        }
                    }
                    "equipment" -> {
                        val template = EquipmentDatabase.getById(item.itemId)
                        if (template != null) {
                            repeat(item.quantity) {
                                val equipment = Equipment(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = template.name,
                                    slot = template.slot,
                                    rarity = item.rarity,
                                    physicalAttack = template.physicalAttack,
                                    magicAttack = template.magicAttack,
                                    physicalDefense = template.physicalDefense,
                                    magicDefense = template.magicDefense,
                                    speed = template.speed,
                                    hp = template.hp,
                                    mp = template.mp,
                                    description = template.description,
                                    minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity)
                                )
                                _equipment.value = _equipment.value + equipment
                            }
                        } else {
                            Log.w(TAG, "无法找到装备模板: ${item.itemId}, 物品: ${item.itemName}")
                        }
                    }
                    "manual" -> {
                        val template = ManualDatabase.getById(item.itemId)
                        if (template != null) {
                            repeat(item.quantity) {
                                val manual = Manual(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = template.name,
                                    rarity = item.rarity,
                                    description = template.description,
                                    type = template.type,
                                    stats = template.stats,
                                    minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity)
                                )
                                if (_manuals.value.none { it.name == manual.name }) {
                                    _manuals.value = _manuals.value + manual
                                }
                            }
                        } else {
                            Log.w(TAG, "无法找到功法模板: ${item.itemId}, 物品: ${item.itemName}")
                        }
                    }
                    "material" -> {
                        val material = Material(
                            id = java.util.UUID.randomUUID().toString(),
                            name = item.itemName,
                            rarity = item.rarity,
                            quantity = item.quantity
                        )
                        addMaterialToWarehouse(material)
                    }
                    "herb" -> {
                        val herb = Herb(
                            id = java.util.UUID.randomUUID().toString(),
                            name = item.itemName,
                            rarity = item.rarity,
                            quantity = item.quantity
                        )
                        addHerbToWarehouse(herb)
                    }
                    "seed" -> {
                        val seed = Seed(
                            id = java.util.UUID.randomUUID().toString(),
                            name = item.itemName,
                            rarity = item.rarity,
                            quantity = item.quantity
                        )
                        addSeedToWarehouse(seed)
                    }
                }
            }
            
            val totalItems = sectWarehouse.items.sumOf { it.quantity }
            warehouseItemText = "，获得仓库内${totalItems}件物品和${sectWarehouse.spiritStones}灵石"
        }
        
        val updatedSects = data.worldMapSects.map { sect ->
            if (sect.id == currentTargetSect.id) {
                sect.copy(
                    isPlayerOccupied = true,
                    occupierBattleTeamId = battleTeam.id,
                    aiDisciples = emptyList(),
                    warehouse = SectWarehouse()
                )
            } else {
                sect
            }
        }
        
        when (currentTargetSect.levelName) {
            "小型宗门" -> {
                val currentSize = data.spiritMineSlots.size
                val newSlots = (0 until 3).map { index ->
                    SpiritMineSlot(index = currentSize + index)
                }
                _gameData.value = data.copy(spiritMineSlots = data.spiritMineSlots + newSlots)
                addEvent("占领小型宗门${currentTargetSect.name}，灵矿槽位+3", EventType.SUCCESS)
            }
            "中型宗门" -> {
                val currentSize = _alchemySlots.value.size
                val newSlots = (0 until 3).map { index ->
                    AlchemySlot(slotIndex = currentSize + index)
                }
                _alchemySlots.value = _alchemySlots.value + newSlots
                addEvent("占领中型宗门${currentTargetSect.name}，炼丹槽位+3", EventType.SUCCESS)
            }
            "大型宗门" -> {
                val currentSize = _buildingSlots.value.size
                val newSlots = (0 until 3).map { index ->
                    BuildingSlot(slotIndex = currentSize + index, type = SlotType.FORGING)
                }
                _buildingSlots.value = _buildingSlots.value + newSlots
                addEvent("占领大型宗门${currentTargetSect.name}，炼器槽位+3", EventType.SUCCESS)
            }
            "顶级宗门" -> {
                val currentSize = data.librarySlots.size
                val newSlots = (0 until 3).map { index ->
                    LibrarySlot(index = currentSize + index)
                }
                _gameData.value = _gameData.value.copy(librarySlots = data.librarySlots + newSlots)
                addEvent("占领顶级宗门${currentTargetSect.name}，藏经阁槽位+3", EventType.SUCCESS)
            }
        }
        
        val updatedTeam = battleTeam.copy(
            status = "stationed",
            isAtSect = false,
            isOccupying = true,
            occupiedSectId = currentTargetSect.id,
            targetSectId = null,
            route = emptyList(),
            currentRouteIndex = 0,
            moveProgress = 0f,
            isReturning = false,
            currentX = currentTargetSect.x,
            currentY = currentTargetSect.y
        )
        
        _gameData.value = _gameData.value.copy(
            worldMapSects = updatedSects,
            battleTeam = updatedTeam
        )
        
        addEvent("成功占领${targetSect.name}，获得${aliveAiDisciples.size}名弟子${warehouseItemText}", EventType.SUCCESS)
    }
    
    private fun triggerScoutBattleOnArrival(team: ExplorationTeam) {
        val currentTeam = _teams.value.find { it.id == team.id }
        if (currentTeam?.status != ExplorationStatus.SCOUTING) {
            return
        }
        
        val data = _gameData.value
        val targetSect = data.worldMapSects.find { it.id == team.scoutTargetSectId }
        
        if (targetSect != null) {
            triggerScoutBattle(team, targetSect, data.gameYear, data.gameMonth)
        }
    }
    
    fun processSecondTick() {
        val startTime = System.currentTimeMillis()
        tickCounter++
        
        val isSecondTick = tickCounter >= GameConfig.Time.TICKS_PER_SECOND
        if (isSecondTick) {
            tickCounter = 0
        }
        
        val data = _gameData.value
        
        try {
            processDiscipleCultivation(isSecondTick)
        } catch (e: Exception) {
            Log.e(TAG, "Error in processDiscipleCultivation - " +
                "Game: Year=${data.gameYear}, Month=${data.gameMonth}, Day=${data.gameDay}, " +
                "Disciples count=${_disciples.value.size}", e)
        }
        
        try {
            processManualProficiencyPerSecond(isSecondTick)
        } catch (e: Exception) {
            Log.e(TAG, "Error in processManualProficiencyPerSecond - " +
                "Game: Year=${data.gameYear}, Month=${data.gameMonth}, Day=${data.gameDay}, " +
                "Manuals count=${_manuals.value.size}", e)
        }
        
        try {
            processEquipmentAutoNurture(isSecondTick)
        } catch (e: Exception) {
            Log.e(TAG, "Error in processEquipmentAutoNurture - " +
                "Game: Year=${data.gameYear}, Month=${data.gameMonth}, Day=${data.gameDay}, " +
                "Equipment count=${_equipment.value.size}", e)
        }
        
        if (isSecondTick) {
            try {
                processRealTimeUpdates()
            } catch (e: Exception) {
                Log.e(TAG, "Error in processRealTimeUpdates - " +
                    "Game: Year=${data.gameYear}, Month=${data.gameMonth}, Day=${data.gameDay}", e)
            }
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed > 50) {
            Log.w(TAG, "processSecondTick took ${elapsed}ms (isSecondTick=$isSecondTick)")
        }
    }
    
    private fun processRealTimeUpdates() {
        val cultivationUpdates = mutableMapOf<String, Double>()
        val equipmentNurtureUpdates = mutableMapOf<String, Double>()
        val manualProficiencyUpdates = mutableMapOf<String, Double>()
        
        _disciples.value.forEach { disciple ->
            cultivationUpdates[disciple.id] = disciple.cultivation
        }
        
        _equipment.value.forEach { equipment ->
            equipmentNurtureUpdates[equipment.id] = equipment.nurtureProgress.toDouble()
        }
        
        val data = _gameData.value
        data.manualProficiencies.forEach { (discipleId, proficiencies) ->
            proficiencies.forEach { proficiency ->
                manualProficiencyUpdates["${discipleId}_${proficiency.manualId}"] = proficiency.proficiency
            }
        }
        
        _highFrequencyData.value = HighFrequencyData(
            timestamp = System.currentTimeMillis(),
            cultivationUpdates = cultivationUpdates,
            equipmentNurtureUpdates = equipmentNurtureUpdates,
            manualProficiencyUpdates = manualProficiencyUpdates
        )
        
        _realtimeCultivation.value = cultivationUpdates
    }
    
    private fun processMonthlyEvents(year: Int, month: Int) {
        val data = _gameData.value
        
        val operations = listOf(
            "payMonthlySalary" to { payMonthlySalary() },
            "processBuildingSlots" to { processBuildingSlots(year, month) },
            "processExplorationTeams" to { processExplorationTeams(year, month) },
            "processPartnerMatching" to { processPartnerMatching(year, month) },
            "processChildGeneration" to { processChildGeneration(year) },
            "checkMerchantRefresh" to { checkMerchantRefresh(year) },
            "processHerbGarden" to { processHerbGarden(year, month) },
            "processAlchemySlots" to { processAlchemySlots(year, month) },
            "processManualProficiency" to { processManualProficiency() },
            "processDiscipleAutoEquip" to { processDiscipleAutoEquip() },
            "processPillEffectDuration" to { processPillEffectDuration() },
            "processCaveLifecycle" to { processCaveLifecycle(year, month) },
            "processLoyaltyDesertion" to { processLoyaltyDesertion() },
            "processPolicyCosts" to { processPolicyCosts() },
            "processMoralityTheft" to { processMoralityTheft() },
            "processLawEnforcementReserveAutoFill" to { processLawEnforcementReserveAutoFill() },
            "processReflectionEnd" to { processReflectionEnd(year) },
            "checkAllianceExpiry" to { checkAllianceExpiry(year) },
            "checkAllianceFavorDrop" to { checkAllianceFavorDrop() },
            "processAIAlliances" to { processAIAlliances(year) },
            "processCrossSectPartnerMatching" to { processCrossSectPartnerMatching(year, month) },
            "processCrossSectChildGeneration" to { processCrossSectChildGeneration(year) },
            "processDiplomaticEvents" to { processDiplomaticEvents(year) },
            "processScoutInfoExpiry" to { processScoutInfoExpiry(year, month) },
            "processAISectAttackDecisions" to { processAISectAttackDecisions() },
            "resetMonthlyUsedPills" to { resetMonthlyUsedPills() },
            "processDiscipleAutoBuyFromListing" to { processDiscipleAutoBuyFromListing() },
            "processMissions" to { processMissions(year, month) }
        )

        operations.forEach { (name, operation) ->
            try {
                operation()
            } catch (e: Exception) {
                Log.e(TAG, "Error in $name - " +
                    "Game: Year=$year, Month=$month, Sect=${data.sectName}, " +
                    "Disciples=${_disciples.value.size}, SpiritStones=${data.spiritStones}", e)
            }
        }

        // 每年 1 月才增加年龄（弟子每年长一岁）
        // 招募弟子列表每年一月刷新
        // 重置所有宗门的送礼限制
        if (month == 1) {
            val yearlyOperations = listOf(
                "processAging" to { processAging(year) },
                "refreshRecruitList" to { refreshRecruitList(year) },
                "resetSectGiftYear" to { resetSectGiftYear() },
                "processSectRelationNaturalChange" to { processSectRelationNaturalChange(year) },
                "processSectDisciplesRecruitment" to { processSectDisciplesRecruitment(year) },
                "processSectDisciplesAging" to { processSectDisciplesAging(year) },
                "generateYearlyItemsForAISects" to { generateYearlyItemsForAISects() },
                "processOuterTournament" to { processOuterTournament(year) }
            )
            
            yearlyOperations.forEach { (name, operation) ->
                try {
                    operation()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in yearly operation $name - " +
                        "Game: Year=$year, Sect=${data.sectName}", e)
                }
            }
            
            refreshMissions(year, month)
        }
        
        try {
            processSectDisciplesCultivation()
        } catch (e: Exception) {
            Log.e(TAG, "Error in processSectDisciplesCultivation - " +
                "Game: Year=$year, Month=$month, Sect=${data.sectName}", e)
        }
    }

    /**
     * 重置所有宗门的送礼年份限制
     * 每年1月调用，允许玩家向各宗门送礼
     * 同时检查未收到礼物的AI宗门，好感度减一
     */
    private fun resetSectGiftYear() {
        val data = _gameData.value
        val previousYear = data.gameYear - 1
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        
        var updatedRelations = data.sectRelations.toMutableList()
        if (playerSect != null) {
            for (i in updatedRelations.indices) {
                val relation = updatedRelations[i]
                if (relation.sectId1 == playerSect.id || relation.sectId2 == playerSect.id) {
                    val otherSectId = if (relation.sectId1 == playerSect.id) relation.sectId2 else relation.sectId1
                    val targetSect = data.worldMapSects.find { it.id == otherSectId }
                    if (targetSect != null) {
                        val giftedLastYear = targetSect.lastGiftYear == previousYear
                        if (giftedLastYear) {
                            updatedRelations[i] = relation.copy(noGiftYears = 0)
                        } else {
                            val newNoGiftYears = relation.noGiftYears + 1
                            if (newNoGiftYears >= 3) {
                                val newFavor = (relation.favor - 1).coerceAtLeast(0)
                                updatedRelations[i] = relation.copy(favor = newFavor, noGiftYears = 0, lastInteractionYear = data.gameYear)
                            } else {
                                updatedRelations[i] = relation.copy(noGiftYears = newNoGiftYears)
                            }
                        }
                    }
                }
            }
        }

        val updatedSects = data.worldMapSects.map { sect ->
            sect.copy(lastGiftYear = 0)
        }

        _gameData.value = data.copy(worldMapSects = updatedSects, sectRelations = updatedRelations)
    }
    
    /**
     * AI宗门之间好感度年度自然变化
     * 每年1月调用
     */
    private fun processSectRelationNaturalChange(year: Int) {
        val data = _gameData.value
        val updatedRelations = data.sectRelations.toMutableList()
        val aiSects = data.worldMapSects.filter { !it.isPlayerSect }
        val processedPairs = mutableSetOf<Pair<String, String>>()
        
        aiSects.forEach { sect1 ->
            aiSects.filter { it.id != sect1.id }.forEach { sect2 ->
                val id1 = minOf(sect1.id, sect2.id)
                val id2 = maxOf(sect1.id, sect2.id)
                val pairKey = id1 to id2
                
                if (processedPairs.contains(pairKey)) return@forEach
                processedPairs.add(pairKey)
                
                val index = updatedRelations.indexOfFirst { it.sectId1 == id1 && it.sectId2 == id2 }
                val currentFavor = if (index >= 0) updatedRelations[index].favor else 30
                
                val naturalChange = if (Random.nextBoolean()) Random.nextInt(1, 6) else -Random.nextInt(1, 6)
                
                val newFavor = (currentFavor + naturalChange).coerceIn(0, 100)
                
                if (index >= 0) {
                    updatedRelations[index] = updatedRelations[index].copy(
                        favor = newFavor,
                        lastInteractionYear = year
                    )
                } else if (newFavor != 30) {
                    updatedRelations.add(SectRelation(
                        sectId1 = id1,
                        sectId2 = id2,
                        favor = newFavor,
                        lastInteractionYear = year
                    ))
                }
            }
        }
        
        _gameData.value = data.copy(sectRelations = updatedRelations)
    }
    
    /**
     * AI宗门外交随机事件
     * 每月有12%概率触发
     */
    private fun processDiplomaticEvents(year: Int) {
        if (Random.nextDouble() > 0.12) return
        
        val data = _gameData.value
        val aiSects = data.worldMapSects.filter { !it.isPlayerSect }
        
        if (aiSects.size < 2) return
        
        val sect1 = aiSects.random()
        val sect2 = aiSects.filter { it.id != sect1.id }.random()
        
        val id1 = minOf(sect1.id, sect2.id)
        val id2 = maxOf(sect1.id, sect2.id)
        
        val updatedRelations = data.sectRelations.toMutableList()
        val index = updatedRelations.indexOfFirst { it.sectId1 == id1 && it.sectId2 == id2 }
        val currentFavor = if (index >= 0) updatedRelations[index].favor else 30
        
        val eventRoll = Random.nextDouble()
        val (favorDelta, eventText) = when {
            eventRoll < 0.12 -> {
                val delta = Random.nextInt(8, 16)
                Pair(delta, "${sect1.name}与${sect2.name}互赠礼物，关系改善")
            }
            eventRoll < 0.22 -> {
                val delta = Random.nextInt(5, 11)
                Pair(delta, "${sect1.name}与${sect2.name}进行友好交流，关系增进")
            }
            eventRoll < 0.30 -> {
                val delta = Random.nextInt(3, 8)
                Pair(delta, "${sect1.name}与${sect2.name}达成资源互换协议")
            }
            eventRoll < 0.36 -> {
                val delta = Random.nextInt(10, 21)
                Pair(delta, "${sect1.name}与${sect2.name}因共同危机而关系升温")
            }
            eventRoll < 0.43 -> {
                val delta = Random.nextInt(6, 13)
                Pair(delta, "${sect1.name}与${sect2.name}联合探索秘境，共享收获")
            }
            eventRoll < 0.48 -> {
                val delta = Random.nextInt(4, 9)
                Pair(delta, "${sect1.name}与${sect2.name}交换弟子进行交流学习")
            }
            eventRoll < 0.52 -> {
                val delta = Random.nextInt(5, 11)
                Pair(delta, "${sect1.name}与${sect2.name}联合举办比武大会")
            }
            eventRoll < 0.55 -> {
                val delta = Random.nextInt(12, 19)
                Pair(delta, "${sect1.name}与${sect2.name}缔结姻亲之约")
            }
            eventRoll < 0.65 -> {
                val delta = -Random.nextInt(5, 11)
                Pair(delta, "${sect1.name}与${sect2.name}发生边界争端")
            }
            eventRoll < 0.75 -> {
                val delta = -Random.nextInt(8, 16)
                Pair(delta, "${sect1.name}与${sect2.name}产生误会，关系恶化")
            }
            eventRoll < 0.81 -> {
                val delta = -Random.nextInt(12, 21)
                Pair(delta, "${sect1.name}背后诋毁${sect2.name}，关系急剧恶化")
            }
            eventRoll < 0.87 -> {
                val delta = -Random.nextInt(6, 13)
                Pair(delta, "${sect1.name}与${sect2.name}争夺灵矿资源发生冲突")
            }
            eventRoll < 0.92 -> {
                val delta = -Random.nextInt(8, 15)
                Pair(delta, "${sect1.name}挖角${sect2.name}核心弟子")
            }
            eventRoll < 0.96 -> {
                val delta = -Random.nextInt(15, 26)
                Pair(delta, "${sect1.name}发现${sect2.name}派遣间谍")
            }
            else -> {
                val delta = -Random.nextInt(5, 11)
                Pair(delta, "${sect1.name}拒绝${sect2.name}紧急援助请求")
            }
        }
        
        val newFavor = (currentFavor + favorDelta).coerceIn(0, 100)
        
        if (index >= 0) {
            updatedRelations[index] = updatedRelations[index].copy(
                favor = newFavor,
                lastInteractionYear = year
            )
        } else {
            updatedRelations.add(SectRelation(
                sectId1 = id1,
                sectId2 = id2,
                favor = newFavor,
                lastInteractionYear = year
            ))
        }
        
        _gameData.value = data.copy(sectRelations = updatedRelations)
        addEvent(eventText, EventType.INFO)
    }
    
    /**
     * 实时更新共同敌人好感度
     * 当AI宗门被攻击时，其盟友之间好感度增加
     */
    private fun updateCommonEnemyFavorRealtime(attackedSectId: String, attackerIsPlayer: Boolean) {
        val data = _gameData.value
        val attackedSect = data.worldMapSects.find { it.id == attackedSectId } ?: return
        
        if (attackedSect.isPlayerSect) return
        
        val alliance = attackedSect.allianceId?.let { aid -> 
            data.alliances.find { it.id == aid } 
        } ?: return
        
        val allyIds = alliance.sectIds.filter { it != attackedSectId && it != "player" && it != "player_sect" }
        if (allyIds.isEmpty()) return
        
        val updatedRelations = data.sectRelations.toMutableList()
        
        allyIds.forEach { allyId ->
            val id1 = minOf(attackedSectId, allyId)
            val id2 = maxOf(attackedSectId, allyId)
            
            val index = updatedRelations.indexOfFirst { it.sectId1 == id1 && it.sectId2 == id2 }
            val currentFavor = if (index >= 0) updatedRelations[index].favor else 30
            val favorIncrease = if (attackerIsPlayer) Random.nextInt(8, 16) else Random.nextInt(5, 12)
            val newFavor = (currentFavor + favorIncrease).coerceIn(0, 100)
            
            if (index >= 0) {
                updatedRelations[index] = updatedRelations[index].copy(favor = newFavor)
            } else {
                updatedRelations.add(SectRelation(
                    sectId1 = id1,
                    sectId2 = id2,
                    favor = newFavor,
                    lastInteractionYear = data.gameYear
                ))
            }
            
            val allySect = data.worldMapSects.find { it.id == allyId }
            if (allySect != null) {
                addEvent("${attackedSect.name}与${allySect.name}因共同敌人关系升温", EventType.INFO)
            }
        }
        
        _gameData.value = data.copy(sectRelations = updatedRelations)
    }
    
    private fun processPillEffectDuration() {
        _disciples.value = _disciples.value.map { disciple ->
            var updated = disciple
            var effectExpired = false
            
            // 减少修炼速度加成持续时间（按月计算）
            if (disciple.cultivationSpeedDuration > 0) {
                val newDuration = disciple.cultivationSpeedDuration - 1
                updated = updated.copy(
                    cultivationSpeedDuration = newDuration,
                    cultivationSpeedBonus = if (newDuration <= 0) 1.0 else disciple.cultivationSpeedBonus
                )
                if (newDuration <= 0) effectExpired = true
            }
            
            // 战斗丹效果(pillEffectDuration)在战斗后减少，不在此处处理
            
            if (effectExpired) {
                addEvent("${disciple.name}的丹药效果已过期", EventType.INFO)
            }
            
            updated
        }
    }
    
    private fun resetMonthlyUsedPills() {
        _disciples.value = _disciples.value.map { disciple ->
            disciple.copy(monthlyUsedPillIds = emptyList())
        }
    }
    
    private fun processMissions(year: Int, month: Int) {
        val data = _gameData.value
        val activeMissions = data.activeMissions
        
        if (activeMissions.isEmpty()) return
        
        val completedMissions = activeMissions.filter { it.isComplete(year, month) }
        
        completedMissions.forEach { activeMission ->
            val missionDisciples = _disciples.value.filter { it.id in activeMission.discipleIds && it.isAlive }
            val result = MissionSystem.processMissionCompletion(activeMission, missionDisciples)
            
            missionDisciples.filter { it.isAlive }.forEach { disciple ->
                _disciples.value = _disciples.value.map {
                    if (it.id == disciple.id) {
                        it.copy(status = DiscipleStatus.IDLE)
                    } else it
                }
            }
            
            addEvent("${activeMission.missionName}完成！", EventType.SUCCESS)
            
            if (result.spiritStones > 0) {
                _gameData.value = _gameData.value.copy(
                    spiritStones = _gameData.value.spiritStones + result.spiritStones
                )
                addEvent("获得${result.spiritStones}灵石", EventType.SUCCESS)
            }
            
            if (result.pills.isNotEmpty()) {
                result.pills.forEach { addPillToWarehouse(it) }
                result.pills.forEach { addEvent("获得丹药：${it.name}", EventType.SUCCESS) }
            }
            
            if (result.materials.isNotEmpty()) {
                result.materials.forEach { addMaterialToWarehouse(it) }
                result.materials.forEach { addEvent("获得材料：${it.name}", EventType.SUCCESS) }
            }
            
            if (result.herbs.isNotEmpty()) {
                result.herbs.forEach { addHerbToWarehouse(it) }
                result.herbs.forEach { addEvent("获得草药：${it.name}", EventType.SUCCESS) }
            }
            
            if (result.seeds.isNotEmpty()) {
                result.seeds.forEach { addSeedToWarehouse(it) }
                result.seeds.forEach { addEvent("获得种子：${it.name}", EventType.SUCCESS) }
            }
            
            if (result.equipment.isNotEmpty()) {
                _equipment.value = _equipment.value + result.equipment
                result.equipment.forEach { addEvent("获得装备：${it.name}", EventType.SUCCESS) }
            }
            
            if (result.manuals.isNotEmpty()) {
                _manuals.value = _manuals.value + result.manuals
                result.manuals.forEach { addEvent("获得功法：${it.name}", EventType.SUCCESS) }
            }
            
            result.investigateOutcome?.let { outcome ->
                when (outcome) {
                    InvestigateOutcome.BEAST_RIOT -> addEvent("调查发现：妖兽作乱", EventType.WARNING)
                    InvestigateOutcome.SECT_CONFLICT -> addEvent("调查发现：门派争斗", EventType.WARNING)
                    InvestigateOutcome.DESTINED_CHILD -> {
                        addEvent("调查发现：天命之子！", EventType.SUCCESS)
                        val destinedChild = generateRandomDisciple().copy(
                            discipleType = "outer"
                        )
                        _gameData.value = _gameData.value.copy(
                            recruitList = _gameData.value.recruitList + destinedChild
                        )
                        addEvent("发现天命之子：${destinedChild.name}，灵根：${destinedChild.spiritRootName}，已加入招募列表", EventType.SUCCESS)
                    }
                    InvestigateOutcome.NOTHING_FOUND -> addEvent("调查结果：一无所获", EventType.INFO)
                }
            }
        }
        
        val remainingMissions = activeMissions.filter { !it.isComplete(year, month) }
        _gameData.value = _gameData.value.copy(activeMissions = remainingMissions)
    }
    
    private fun refreshMissions(year: Int, month: Int) {
        val data = _gameData.value
        
        if (!MissionSystem.shouldRefreshMissions(data.lastMissionRefreshYear, year)) return
        
        val currentMissions = MissionSystem.cleanExpiredMissions(
            data.availableMissions,
            year,
            month
        )
        
        val newMissions = MissionSystem.generateMissions(year, month)
        
        _gameData.value = data.copy(
            availableMissions = currentMissions + newMissions,
            lastMissionRefreshYear = year
        )
        
        addEvent("任务阁刷新了新的任务", EventType.INFO)
    }
    
    fun startMission(mission: Mission, selectedDisciples: List<Disciple>): Boolean {
        val validation = MissionSystem.validateDisciplesForMission(mission, selectedDisciples)
        if (!validation.valid) {
            addEvent(validation.errorMessage ?: "无法开始任务", EventType.WARNING)
            return false
        }
        
        val data = _gameData.value
        
        val activeMission = MissionSystem.createActiveMission(
            mission,
            selectedDisciples,
            data.gameYear,
            data.gameMonth
        )
        
        selectedDisciples.forEach { disciple ->
            _disciples.value = _disciples.value.map {
                if (it.id == disciple.id) {
                    it.copy(status = DiscipleStatus.ON_MISSION)
                } else it
            }
        }
        
        val updatedAvailableMissions = data.availableMissions.filter { it.id != mission.id }
        
        _gameData.value = data.copy(
            activeMissions = data.activeMissions + activeMission,
            availableMissions = updatedAvailableMissions
        )
        
        addEvent("开始执行${mission.name}", EventType.INFO)
        return true
    }
    
    private fun clearBattlePillEffects(disciples: List<Disciple>) {
        disciples.forEach { disciple ->
            if (disciple.pillEffectDuration > 0 || disciple.hasReviveEffect || disciple.hasClearAllEffect) {
                _disciples.value = _disciples.value.map {
                    if (it.id == disciple.id) {
                        it.copy(
                            pillEffectDuration = 0,
                            pillPhysicalAttackBonus = 0.0,
                            pillMagicAttackBonus = 0.0,
                            pillPhysicalDefenseBonus = 0.0,
                            pillMagicDefenseBonus = 0.0,
                            pillHpBonus = 0.0,
                            pillMpBonus = 0.0,
                            pillSpeedBonus = 0.0,
                            hasReviveEffect = false,
                            hasClearAllEffect = false
                        )
                    } else it
                }
                if (disciple.pillEffectDuration > 0) {
                    addEvent("${disciple.name}的战斗丹效果已消失", EventType.INFO)
                }
            }
        }
    }

    private fun applyBattleVictoryBonuses(memberIds: List<String>, addSoulPower: Boolean) {
        memberIds.distinct().forEach { memberId ->
            _disciples.value = _disciples.value.map { disciple ->
                if (disciple.id != memberId || !disciple.isAlive) return@map disciple

                var updated = disciple
                if (addSoulPower) {
                    updated = updated.copy(soulPower = updated.soulPower + 1)
                }

                applyBattleWinGrowth(updated)
            }
        }
    }

    private fun applyBattleWinGrowth(disciple: Disciple): Disciple {
        val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
        val growthTimes = (talentEffects["winBattleRandomAttrPlus"] ?: 0.0).toInt().coerceAtLeast(0)
        if (growthTimes <= 0) return disciple

        var updated = disciple
        repeat(growthTimes) {
            updated = when (Random.nextInt(16)) {
                0 -> updated.copy(intelligence = updated.intelligence + 1)
                1 -> updated.copy(charm = updated.charm + 1)
                2 -> updated.copy(loyalty = updated.loyalty + 1)
                3 -> updated.copy(comprehension = updated.comprehension + 1)
                4 -> updated.copy(artifactRefining = updated.artifactRefining + 1)
                5 -> updated.copy(pillRefining = updated.pillRefining + 1)
                6 -> updated.copy(spiritPlanting = updated.spiritPlanting + 1)
                7 -> updated.copy(teaching = updated.teaching + 1)
                8 -> updated.copy(morality = updated.morality + 1)
                9 -> updated.copy(statusData = increaseStatusInt(updated.statusData, "winGrowth.maxHp", 1))
                10 -> updated.copy(statusData = increaseStatusInt(updated.statusData, "winGrowth.maxMp", 1))
                11 -> updated.copy(statusData = increaseStatusInt(updated.statusData, "winGrowth.physicalAttack", 1))
                12 -> updated.copy(statusData = increaseStatusInt(updated.statusData, "winGrowth.magicAttack", 1))
                13 -> updated.copy(statusData = increaseStatusInt(updated.statusData, "winGrowth.physicalDefense", 1))
                14 -> updated.copy(statusData = increaseStatusInt(updated.statusData, "winGrowth.magicDefense", 1))
                else -> updated.copy(statusData = increaseStatusInt(updated.statusData, "winGrowth.speed", 1))
            }
        }
        return updated
    }

    private fun increaseStatusInt(statusData: Map<String, String>, key: String, delta: Int): Map<String, String> {
        val current = statusData[key]?.toIntOrNull() ?: 0
        return statusData + (key to (current + delta).toString())
    }
    
    /**
     * 灵药园事务处理结果
     * 包含所有需要更新的数据，确保原子性
     */
    private data class HerbGardenTransaction(
        val updatedSlots: List<PlantSlotData>,
        val updatedHerbs: List<Herb>,
        val updatedSeeds: List<Seed>,
        val events: List<Pair<String, EventType>>
    )

    private fun processHerbGarden(year: Int, month: Int) {
        initializeHerbGardenSlots()

        val data = _gameData.value
        val currentHerbs = _herbs.value
        val currentSeeds = _seeds.value

        val transaction = executeHerbGardenTransaction(
            slots = data.herbGardenPlantSlots,
            herbs = currentHerbs,
            seeds = currentSeeds,
            year = year,
            month = month,
            data = data
        )

        _herbs.value = transaction.updatedHerbs
        _seeds.value = transaction.updatedSeeds
        _gameData.value = data.copy(herbGardenPlantSlots = transaction.updatedSlots)

        transaction.events.forEach { (message, type) ->
            addEvent(message, type)
        }
    }

    /**
     * 执行灵药园事务（原子性计算所有变更）
     * 所有计算在副本上进行，返回最终结果
     */
    private fun executeHerbGardenTransaction(
        slots: List<PlantSlotData>,
        herbs: List<Herb>,
        seeds: List<Seed>,
        year: Int,
        month: Int,
        data: GameData
    ): HerbGardenTransaction {
        val events = mutableListOf<Pair<String, EventType>>()
        val mutableHerbs = herbs.toMutableList()
        val mutableSeeds = seeds.toMutableList()

        // 计算长老和亲传弟子的加成
        val elderBonus = calculateElderAndDisciplesBonus("herbGarden")

        val herbGardenSystem = HerbGardenSystem
        val herbGardenDiscipleSlots = _buildingSlots.value.filter { 
            it.buildingId == "herbGarden" && it.slotIndex in 10..12 
        }
        val discipleSlots = herbGardenDiscipleSlots.map { buildingSlot ->
            HerbGardenSystem.DiscipleSlot(
                index = buildingSlot.slotIndex - 10,
                discipleId = buildingSlot.discipleId,
                discipleName = buildingSlot.discipleId?.let { id ->
                    _disciples.value.find { it.id == id }?.name
                }
            )
        }

        val updatedSlots = slots.map { slot ->
            if (!slot.isGrowing) {
                return@map slot
            }
            
            if (!slot.isFinished(year, month)) {
                return@map slot
            }
            
            val seedId = slot.seedId
            if (seedId == null) {
                return@map PlantSlotData(index = slot.index)
            }
            
            val seed = HerbDatabase.getSeedById(seedId)
            if (seed == null) {
                return@map PlantSlotData(index = slot.index)
            }
            
            val herbId = seedId.removeSuffix("Seed")
            val herb = HerbDatabase.getHerbById(herbId)
            if (herb == null) {
                return@map PlantSlotData(index = slot.index)
            }
            
            val discipleYieldBonus = herbGardenSystem.calculateYieldBonus(
                discipleSlots = discipleSlots,
                disciples = _disciples.value,
                manualProficiencies = data.manualProficiencies,
                manuals = _manuals.value
            )
            val totalYieldBonus = discipleYieldBonus + elderBonus.yieldBonus
            val actualYield = herbGardenSystem.calculateIncreasedYield(slot.harvestAmount, totalYieldBonus)
            
            val existingHerbIndex = mutableHerbs.indexOfFirst { 
                it.name == herb.name && it.rarity == herb.rarity 
            }
            if (existingHerbIndex >= 0) {
                mutableHerbs[existingHerbIndex] = mutableHerbs[existingHerbIndex].copy(
                    quantity = mutableHerbs[existingHerbIndex].quantity + actualYield
                )
            } else {
                mutableHerbs.add(Herb(
                    id = UUID.randomUUID().toString(),
                    name = herb.name,
                    rarity = herb.rarity,
                    description = herb.description,
                    category = herb.category,
                    quantity = actualYield
                ))
            }
            
            val bonusText = if (elderBonus.yieldBonus != 0.0) {
                " (产量+${GameUtils.formatPercent(elderBonus.yieldBonus)})"
            } else ""
            events.add("${herb.name}已成熟，收获${actualYield}个放入仓库${bonusText}" to EventType.SUCCESS)
            
            // 自动续种：检查是否还有相同种子
            val autoPlantResult = autoRestartPlanting(
                slot = slot,
                seed = seed,
                herb = herb,
                mutableSeeds = mutableSeeds,
                year = year,
                month = month,
                elderBonus = elderBonus
            )
            
            if (autoPlantResult != null) {
                events.add("自动续种：开始种植${herb.name}" to EventType.INFO)
                autoPlantResult
            } else {
                events.add("种子不足，无法自动续种${herb.name}" to EventType.WARNING)
                PlantSlotData(index = slot.index)
            }
        }

        return HerbGardenTransaction(
            updatedSlots = updatedSlots,
            updatedHerbs = mutableHerbs.toList(),
            updatedSeeds = mutableSeeds.toList(),
            events = events.toList()
        )
    }

    /**
     * 自动续种
     * 检查是否有相同种子，有则自动开始种植
     */
    private fun autoRestartPlanting(
        slot: PlantSlotData,
        seed: HerbDatabase.Seed,
        herb: HerbDatabase.Herb,
        mutableSeeds: MutableList<Seed>,
        year: Int,
        month: Int,
        elderBonus: ElderBonusResult
    ): PlantSlotData? {
        // 在仓库中查找相同类型的种子（通过名称和品阶匹配）
        val seedIndex = mutableSeeds.indexOfFirst { 
            it.name == seed.name && it.rarity == seed.rarity && it.quantity > 0 
        }
        
        if (seedIndex < 0) {
            return null
        }
        
        // 扣除种子
        val currentSeed = mutableSeeds[seedIndex]
        mutableSeeds[seedIndex] = currentSeed.copy(quantity = currentSeed.quantity - 1)
        if (mutableSeeds[seedIndex].quantity <= 0) {
            mutableSeeds.removeAt(seedIndex)
        }
        
        // 计算实际耗时（考虑速度加成 + 长老/亲传弟子加成 + 灵药培育政策加成）
        val baseDuration = seed.growTime
        var speedBonus = elderBonus.speedBonus
        
        // 灵药培育政策加成
        if (_gameData.value.sectPolicies.herbCultivation) {
            val basePolicyBonus = 0.20
            val viceSectMasterBonus = calculateViceSectMasterPolicyBonus()
            speedBonus += basePolicyBonus * (1 + viceSectMasterBonus)
        }
        
        val actualDuration = (baseDuration * (1.0 - speedBonus)).toInt().coerceAtLeast(1)
        
        // 计算实际产量（考虑产量加成）
        val baseYield = seed.yield
        val actualYield = (baseYield * (1.0 + elderBonus.yieldBonus)).toInt().coerceAtLeast(1)
        
        // 创建新的种植槽位数据
        return PlantSlotData(
            index = slot.index,
            status = "growing",
            seedId = seed.id,
            seedName = seed.name,
            startYear = year,
            startMonth = month,
            growTime = actualDuration,
            expectedYield = actualYield,
            harvestAmount = actualYield,
            harvestHerbId = herb.id
        )
    }
    
    private fun processAlchemySlots(year: Int, month: Int) {
        val currentSlots = _alchemySlots.value
        val data = _gameData.value
        
        val updatedSlots = currentSlots.mapIndexed { index, slot ->
            if (slot.status == AlchemySlotStatus.WORKING && slot.isFinished(year, month)) {
                val recipeId = slot.recipeId
                if (recipeId != null) {
                    val recipe = PillRecipeDatabase.getRecipeById(recipeId)
                    if (recipe != null) {
                        val elderBonus = calculateElderAndDisciplesBonus("alchemy")
                        val totalSuccessBonus = elderBonus.successBonus
                        val finalSuccessRate = recipe.successRate + totalSuccessBonus
                        
                        val success = Random.nextDouble() < finalSuccessRate
                        
                        if (success) {
                            val existingPill = _pills.value.find { 
                                it.name == recipe.name && 
                                it.rarity == recipe.rarity &&
                                it.category == recipe.category
                            }
                            
                            if (existingPill != null) {
                                _pills.value = _pills.value.map {
                                    if (it.id == existingPill.id) it.copy(quantity = it.quantity + 1) else it
                                }
                            } else {
                                val pill = Pill(
                                    id = UUID.randomUUID().toString(),
                                    name = recipe.name,
                                    rarity = recipe.rarity,
                                    description = recipe.description,
                                    category = recipe.category,
                                    breakthroughChance = recipe.breakthroughChance,
                                    targetRealm = recipe.targetRealm,
                                    cultivationSpeed = recipe.cultivationSpeed,
                                    duration = recipe.effectDuration,
                                    cultivationPercent = recipe.cultivationPercent,
                                    skillExpPercent = recipe.skillExpPercent,
                                    physicalAttackPercent = recipe.physicalAttackPercent,
                                    physicalDefensePercent = recipe.physicalDefensePercent,
                                    magicAttackPercent = recipe.magicAttackPercent,
                                    magicDefensePercent = recipe.magicDefensePercent,
                                    hpPercent = recipe.hpPercent,
                                    mpPercent = recipe.mpPercent,
                                    speedPercent = recipe.speedPercent,
                                    healPercent = recipe.healPercent,
                                    healMaxHpPercent = recipe.healMaxHpPercent,
                                    heal = recipe.heal,
                                    battleCount = recipe.battleCount,
                                    extendLife = recipe.extendLife,
                                    mpRecoverMaxMpPercent = recipe.mpRecoverMaxMpPercent,
                                    quantity = 1
                                )
                                addPillToWarehouse(pill)
                            }
                            
                            val bonusText = if (totalSuccessBonus > 0) " (成功率+${GameUtils.formatPercent(totalSuccessBonus)})" else ""
                            addEvent("炼制成功！获得${recipe.name}${bonusText}", EventType.SUCCESS)
                        } else {
                            addEvent("炼制失败，${recipe.name}化为灰烬", EventType.WARNING)
                        }
                    }
                }
                
                // 同步更新 _buildingSlots
                _buildingSlots.value = _buildingSlots.value.filter {
                    !(it.buildingId == "alchemyRoom" && it.slotIndex == slot.slotIndex)
                }

                AlchemySlot(
                    id = slot.id,
                    slotIndex = slot.slotIndex,
                    status = AlchemySlotStatus.IDLE
                )
            } else {
                slot
            }
        }
        
        _alchemySlots.value = updatedSlots
    }

    /**
     * 手动种植事务结果
     */
    private data class ManualPlantingTransaction(
        val success: Boolean,
        val updatedSlots: List<PlantSlotData>,
        val updatedSeeds: List<Seed>,
        val eventMessage: String?,
        val eventType: EventType
    )

    /**
     * 手动开始种植（或更换种子）- 事务模式
     * @param slotIndex 种植槽位索引（0-2）
     * @param seedId 种子ID（仓库中的UUID）
     * @return 是否成功
     */
    fun startManualPlanting(slotIndex: Int, seedId: String): Boolean {
        initializeHerbGardenSlots()

        val data = _gameData.value
        val currentSeeds = _seeds.value

        val transaction = executeManualPlantingTransaction(
            slotIndex = slotIndex,
            seedId = seedId,
            slots = data.herbGardenPlantSlots,
            seeds = currentSeeds,
            gameYear = data.gameYear,
            gameMonth = data.gameMonth,
            data = data
        )

        if (transaction.success) {
            _seeds.value = transaction.updatedSeeds
            _gameData.value = data.copy(herbGardenPlantSlots = transaction.updatedSlots)
            transaction.eventMessage?.let { addEvent(it, transaction.eventType) }
            syncListedItemsWithInventory()
            return true
        } else {
            transaction.eventMessage?.let { addEvent(it, transaction.eventType) }
            return false
        }
    }

    /**
     * 执行手动种植事务（原子性计算）
     */
    private fun executeManualPlantingTransaction(
        slotIndex: Int,
        seedId: String,
        slots: List<PlantSlotData>,
        seeds: List<Seed>,
        gameYear: Int,
        gameMonth: Int,
        data: GameData
    ): ManualPlantingTransaction {
        val seedItemIndex = seeds.indexOfFirst { it.id == seedId }
        if (seedItemIndex < 0 || seeds[seedItemIndex].quantity <= 0) {
            return ManualPlantingTransaction(
                success = false,
                updatedSlots = slots,
                updatedSeeds = seeds,
                eventMessage = "仓库中没有该种子",
                eventType = EventType.WARNING
            )
        }

        val seedItem = seeds[seedItemIndex]
        val seed = HerbDatabase.getSeedByName(seedItem.name)
            ?: return ManualPlantingTransaction(
                success = false,
                updatedSlots = slots,
                updatedSeeds = seeds,
                eventMessage = "种子数据错误",
                eventType = EventType.WARNING
            )

        val herbId = seed.id.removeSuffix("Seed")
        val herb = HerbDatabase.getHerbById(herbId)
            ?: return ManualPlantingTransaction(
                success = false,
                updatedSlots = slots,
                updatedSeeds = seeds,
                eventMessage = "草药数据错误",
                eventType = EventType.WARNING
            )

        val mutableSeeds = seeds.toMutableList()
        if (seedItem.quantity > 1) {
            mutableSeeds[seedItemIndex] = seedItem.copy(quantity = seedItem.quantity - 1)
        } else {
            mutableSeeds.removeAt(seedItemIndex)
        }

        // 计算长老和亲传弟子的加成
        val elderBonus = calculateElderAndDisciplesBonus("herbGarden")

        val herbGardenSystem = HerbGardenSystem
        val herbGardenDiscipleSlots = _buildingSlots.value.filter {
            it.buildingId == "herbGarden" && it.slotIndex in 10..12
        }
        val discipleSlots = herbGardenDiscipleSlots.map { buildingSlot ->
            HerbGardenSystem.DiscipleSlot(
                index = buildingSlot.slotIndex - 10,
                discipleId = buildingSlot.discipleId,
                discipleName = buildingSlot.discipleId?.let { id ->
                    _disciples.value.find { it.id == id }?.name
                }
            )
        }
        val discipleSpeedBonus = herbGardenSystem.calculatePlantingSpeedBonus(
            discipleSlots = discipleSlots,
            disciples = _disciples.value,
            manualProficiencies = data.manualProficiencies,
            manuals = _manuals.value
        )
        val totalSpeedBonus = discipleSpeedBonus + elderBonus.speedBonus

        val baseGrowTime = ForgeRecipeDatabase.getDurationByTier(seed.tier)
        // 应用长老/亲传弟子的成熟时间减少
        val afterElderBonus = (baseGrowTime * (1.0 - elderBonus.growTimeReduction)).toInt().coerceAtLeast(1)
        val actualGrowTime = herbGardenSystem.calculateReducedDuration(afterElderBonus, totalSpeedBonus)

        val newSlot = PlantSlotData(
            index = slotIndex,
            status = "growing",
            seedId = seed.id,
            seedName = seed.name,
            startYear = gameYear,
            startMonth = gameMonth,
            growTime = actualGrowTime,
            harvestAmount = seed.yield,
            harvestHerbId = herbId
        )

        val updatedSlots = slots.toMutableList()
        val existingIndex = updatedSlots.indexOfFirst { it.index == slotIndex }
        if (existingIndex >= 0) {
            updatedSlots[existingIndex] = newSlot
        } else {
            updatedSlots.add(newSlot)
        }

        return ManualPlantingTransaction(
            success = true,
            updatedSlots = updatedSlots.toList(),
            updatedSeeds = mutableSeeds.toList(),
            eventMessage = "${seed.name}种植成功，预计${actualGrowTime}个月后成熟",
            eventType = EventType.SUCCESS
        )
    }

    /**
     * 更换种子（原种子直接移除，无产出）
     * @param slotIndex 种植槽位索引（0-2）
     * @param newSeedId 新种子ID
     * @return 是否成功
     */
    fun changeSeed(slotIndex: Int, newSeedId: String): Boolean {
        val data = _gameData.value
        val slot = data.herbGardenPlantSlots.find { it.index == slotIndex }

        // 如果槽位正在种植，移除原种子
        if (slot != null && slot.isGrowing) {
            addEvent("${slot.seedName}已移除", EventType.INFO)
        }

        // 直接调用开始种植
        return startManualPlanting(slotIndex, newSeedId)
    }

    /**
     * 获取种植槽剩余月份
     */
    fun getRemainingMonths(slot: PlantSlotData, currentYear: Int, currentMonth: Int): Int {
        if (!slot.isGrowing) return 0
        val yearDiff = (currentYear - slot.startYear).toLong()
        val monthDiff = (currentMonth - slot.startMonth).toLong()
        val elapsed = yearDiff * 12 + monthDiff
        return (slot.growTime - elapsed.toInt()).coerceAtLeast(0)
    }

    private fun processManualProficiency() {
        val data = _gameData.value
        val updatedProficiencies = data.manualProficiencies.toMutableMap()
        
        _disciples.value.filter { it.isAlive && it.status == DiscipleStatus.IDLE }.forEach { disciple ->
            val proficiencies = updatedProficiencies[disciple.id]?.toMutableList() ?: mutableListOf()
            
            disciple.manualIds.forEach { manualId ->
                val manual = _manuals.value.find { it.id == manualId } ?: return@forEach
                val existingIndex = proficiencies.indexOfFirst { it.manualId == manualId }
                
                val gain = ManualProficiencySystem.calculateProficiencyGain(
                    baseGain = ManualProficiencySystem.BASE_PROFICIENCY_GAIN,
                    discipleRealm = disciple.realm,
                    manualRarity = manual.rarity
                )
                
                if (existingIndex >= 0) {
                    val existing = proficiencies[existingIndex]
                    val updated = ManualProficiencySystem.updateProficiency(
                        ManualProficiencySystem.ManualProficiency(
                            manualId = existing.manualId,
                            manualName = existing.manualName,
                            proficiency = existing.proficiency,
                            masteryLevel = existing.masteryLevel
                        ),
                        gain
                    )
                    proficiencies[existingIndex] = ManualProficiencyData(
                        manualId = updated.manualId,
                        manualName = updated.manualName,
                        proficiency = updated.proficiency,
                        masteryLevel = updated.masteryLevel
                    )
                } else {
                    val newProficiency = ManualProficiencySystem.ManualProficiency(
                        manualId = manualId,
                        manualName = manual.name
                    )
                    val updated = ManualProficiencySystem.updateProficiency(newProficiency, gain)
                    proficiencies.add(ManualProficiencyData(
                        manualId = updated.manualId,
                        manualName = updated.manualName,
                        proficiency = updated.proficiency,
                        masteryLevel = updated.masteryLevel
                    ))
                }
            }
            
            updatedProficiencies[disciple.id] = proficiencies
        }

        _gameData.value = data.copy(manualProficiencies = updatedProficiencies)
    }

    // 弟子自动装备和替换装备/功法
    // 1. 优先装备空槽位
    // 2. 高品阶替换低品阶（同品阶不替换）
    private fun processDiscipleAutoEquip() {
        val currentDisciples = _disciples.value.toMutableList()
        val currentEquipment = _equipment.value.toMutableList()
        val eventsToAdd = mutableListOf<Pair<String, EventType>>()
        val data = _gameData.value
        
        currentDisciples.forEachIndexed { index, disciple ->
            if (!disciple.isAlive || disciple.status == DiscipleStatus.REFLECTING) return@forEachIndexed
            
            val (updatedDisciple, equipmentUpdates, events) = processDiscipleEquipmentAndManuals(
                disciple, 
                currentEquipment, 
                data.gameYear, 
                data.gameMonth
            )
            
            if (updatedDisciple != disciple) {
                currentDisciples[index] = updatedDisciple
            }
            
            equipmentUpdates.forEach { update ->
                val equipIndex = currentEquipment.indexOfFirst { it.id == update.id }
                if (equipIndex >= 0) {
                    currentEquipment[equipIndex] = update
                }
            }
            
            eventsToAdd.addAll(events)
        }
        
        _disciples.value = currentDisciples
        _equipment.value = currentEquipment
        
        eventsToAdd.forEach { (message, type) ->
            addEvent(message, type)
        }
    }

    private data class DiscipleEquipResult(
        val disciple: Disciple,
        val equipmentUpdates: List<Equipment>,
        val events: List<Pair<String, EventType>>
    )

    private fun processDiscipleEquipmentAndManuals(
        disciple: Disciple,
        currentEquipment: List<Equipment>,
        gameYear: Int,
        gameMonth: Int
    ): DiscipleEquipResult {
        val equipmentUpdates = mutableListOf<Equipment>()
        val events = mutableListOf<Pair<String, EventType>>()
        var updatedDisciple = disciple
        val equipmentMap = currentEquipment.associateBy { it.id }
        
        val equipmentSlots = listOf(
            EquipmentSlot.WEAPON to disciple.weaponId,
            EquipmentSlot.ARMOR to disciple.armorId,
            EquipmentSlot.BOOTS to disciple.bootsId,
            EquipmentSlot.ACCESSORY to disciple.accessoryId
        )

        equipmentSlots.forEach { (slot, currentEquipId) ->
            val bagEquipments = disciple.storageBagItems.filter { item ->
                item.itemType == "equipment"
            }.mapNotNull { item ->
                equipmentMap[item.itemId]
            }.filter { it.slot == slot }

            if (bagEquipments.isEmpty()) return@forEach

            val currentEquip = currentEquipId?.let { equipmentMap[it] }
            val currentRarity = currentEquip?.rarity ?: 0

            val betterEquip = bagEquipments.filter { it.rarity > currentRarity }.maxByOrNull { it.rarity }

            if (betterEquip != null) {
                currentEquipId?.let { oldId ->
                    val oldEquip = equipmentMap[oldId]
                    if (oldEquip != null) {
                        equipmentUpdates.add(oldEquip.copy(ownerId = null, isEquipped = false))
                    }
                }

                updatedDisciple = when (slot) {
                    EquipmentSlot.WEAPON -> updatedDisciple.copy(weaponId = betterEquip.id)
                    EquipmentSlot.ARMOR -> updatedDisciple.copy(armorId = betterEquip.id)
                    EquipmentSlot.BOOTS -> updatedDisciple.copy(bootsId = betterEquip.id)
                    EquipmentSlot.ACCESSORY -> updatedDisciple.copy(accessoryId = betterEquip.id)
                }

                equipmentUpdates.add(betterEquip.copy(ownerId = disciple.id, isEquipped = true))

                val updatedItems = updatedDisciple.storageBagItems.toMutableList()
                val bagIndex = updatedItems.indexOfFirst { it.itemId == betterEquip.id }
                if (bagIndex >= 0) {
                    val item = updatedItems[bagIndex]
                    if (item.quantity > 1) {
                        updatedItems[bagIndex] = item.copy(quantity = item.quantity - 1)
                    } else {
                        updatedItems.removeAt(bagIndex)
                    }
                }
                updatedDisciple = updatedDisciple.copy(storageBagItems = updatedItems)

                currentEquip?.let { oldEquip ->
                    val oldItem = StorageBagItem(
                        itemId = oldEquip.id,
                        itemType = "equipment",
                        name = oldEquip.name,
                        rarity = oldEquip.rarity,
                        quantity = 1,
                        obtainedYear = gameYear,
                        obtainedMonth = gameMonth
                    )
                    updatedDisciple = updatedDisciple.copy(
                        storageBagItems = addStorageItemToBag(updatedDisciple.storageBagItems, oldItem)
                    )
                }

                events.add("${disciple.name} 自动装备了 ${betterEquip.name}（${slot.name}）" to EventType.INFO)
            }
        }

        val manualResult = processAutoLearnManual(updatedDisciple, equipmentMap, gameYear, gameMonth)
        updatedDisciple = manualResult.disciple
        events.addAll(manualResult.events)

        val functionalPillResult = processAutoUseFunctionalPills(updatedDisciple, gameYear, gameMonth)
        updatedDisciple = functionalPillResult.disciple
        events.addAll(functionalPillResult.events)

        return DiscipleEquipResult(updatedDisciple, equipmentUpdates, events)
    }

    private data class FunctionalPillResult(
        val disciple: Disciple,
        val events: List<Pair<String, EventType>>
    )

    private fun processAutoUseFunctionalPills(
        disciple: Disciple,
        gameYear: Int,
        gameMonth: Int
    ): FunctionalPillResult {
        val events = mutableListOf<Pair<String, EventType>>()
        
        if (disciple.storageBagItems.isEmpty()) {
            return FunctionalPillResult(disciple, events)
        }

        var updatedDisciple = disciple
        val usedPillIds = disciple.monthlyUsedPillIds.toMutableList()

        val functionalPills = disciple.storageBagItems.filter { item ->
            item.itemType == "pill" && item.effect?.let { effect ->
                effect.extendLife > 0 || effect.revive || effect.clearAll
            } == true
        }.filter { item ->
            !usedPillIds.contains(item.itemId)
        }

        functionalPills.forEach { pillItem ->
            if (usedPillIds.contains(pillItem.itemId)) return@forEach
            
            val effect = pillItem.effect ?: return@forEach
            var used = false
            var effectDescription = ""

            if (effect.extendLife > 0) {
                updatedDisciple = updatedDisciple.copy(
                    lifespan = updatedDisciple.lifespan + effect.extendLife,
                    usedExtendLifePillIds = updatedDisciple.usedExtendLifePillIds + pillItem.itemId
                )
                effectDescription = "寿命延长${effect.extendLife}年"
                used = true
            }

            if (effect.revive) {
                updatedDisciple = updatedDisciple.copy(hasReviveEffect = true)
                effectDescription = if (effectDescription.isNotEmpty()) "$effectDescription, 获得复活效果" else "获得复活效果"
                used = true
            }

            if (effect.clearAll) {
                updatedDisciple = updatedDisciple.copy(hasClearAllEffect = true)
                effectDescription = if (effectDescription.isNotEmpty()) "$effectDescription, 清除所有负面状态" else "清除所有负面状态"
                used = true
            }

            if (used) {
                val updatedItems = updatedDisciple.storageBagItems.toMutableList()
                val index = updatedItems.indexOfFirst { it.itemId == pillItem.itemId }
                if (index >= 0) {
                    val item = updatedItems[index]
                    if (item.quantity > 1) {
                        updatedItems[index] = item.copy(quantity = item.quantity - 1)
                    } else {
                        updatedItems.removeAt(index)
                    }
                }
                updatedDisciple = updatedDisciple.copy(storageBagItems = updatedItems)
                usedPillIds.add(pillItem.itemId)
                
                events.add("${disciple.name} 自动使用了 ${pillItem.name}，$effectDescription" to EventType.INFO)
            }
        }

        updatedDisciple = updatedDisciple.copy(monthlyUsedPillIds = usedPillIds)

        return FunctionalPillResult(updatedDisciple, events)
    }

    private data class ManualLearnResult(
        val disciple: Disciple,
        val events: List<Pair<String, EventType>>
    )

    private fun processAutoLearnManual(
        disciple: Disciple,
        equipmentMap: Map<String, Equipment>,
        gameYear: Int,
        gameMonth: Int
    ): ManualLearnResult {
        val events = mutableListOf<Pair<String, EventType>>()
        
        if (disciple.storageBagItems.isEmpty()) {
            return ManualLearnResult(disciple, events)
        }

        var updatedDisciple = disciple
        // 注意：功法存入储物袋时会从 _manuals 列表中移除，所以需要从储物袋道具重建功法对象
        val manuals = _manuals.value.associateBy { it.id }

        // 检查是否已有心法
        val hasMindManual = disciple.manualIds.any { manualId ->
            manuals[manualId]?.type == ManualType.MIND
        }

        // 从储物袋中筛选功法道具，使用 createManualFromStorageItem 重建功法对象
        val bagManuals = disciple.storageBagItems.filter { item ->
            item.itemType == "manual" && !disciple.manualIds.contains(item.itemId)
        }.mapNotNull { item ->
            // 优先从 _manuals 列表查找（可能存在），否则从储物袋道具重建
            manuals[item.itemId] ?: createManualFromStorageItem(item)
        }.filter { manual ->
            // 如果已有心法，过滤掉心法类型；否则可以学习所有类型
            // 同时过滤掉境界不足的功法
            val canLearnByRealm = disciple.realm <= manual.minRealm
            if (hasMindManual) manual.type != ManualType.MIND && canLearnByRealm else canLearnByRealm
        }

        if (bagManuals.isEmpty()) {
            return ManualLearnResult(updatedDisciple, events)
        }

        val learnedManuals = disciple.manualIds.mapNotNull { manualId ->
            manuals[manualId]
        }

        val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
        val manualSlotBonus = talentEffects["manualSlot"]?.toInt() ?: 0
        val maxManualSlots = 5 + manualSlotBonus

        if (learnedManuals.size < maxManualSlots) {
            val bestManual = bagManuals.maxByOrNull { it.rarity }

            if (bestManual != null) {
                // 功法被学习后不再加回仓库，只保存在弟子的 manualIds 中
                updatedDisciple = updatedDisciple.copy(
                    manualIds = updatedDisciple.manualIds + bestManual.id
                )

                val updatedItems = updatedDisciple.storageBagItems.toMutableList()
                val index = updatedItems.indexOfFirst { it.itemId == bestManual.id }
                if (index >= 0) {
                    val item = updatedItems[index]
                    if (item.quantity > 1) {
                        updatedItems[index] = item.copy(quantity = item.quantity - 1)
                    } else {
                        updatedItems.removeAt(index)
                    }
                }
                updatedDisciple = updatedDisciple.copy(storageBagItems = updatedItems)

                events.add("${disciple.name} 自动学习了功法 ${bestManual.name}" to EventType.INFO)
            }
        } else {
            val lowestLearned = learnedManuals.minByOrNull { it.rarity }
            val highestBag = bagManuals.maxByOrNull { it.rarity }

            if (lowestLearned != null && highestBag != null && highestBag.rarity > lowestLearned.rarity) {
                if (lowestLearned.type == ManualType.MIND) {
                    return ManualLearnResult(updatedDisciple, events)
                }
                if (hasMindManual && highestBag.type == ManualType.MIND) {
                    return ManualLearnResult(updatedDisciple, events)
                }

                // 功法被学习后不再加回仓库，只保存在弟子的 manualIds 中
                val updatedManualIds = disciple.manualIds.toMutableList()
                updatedManualIds.remove(lowestLearned.id)
                updatedManualIds.add(highestBag.id)

                updatedDisciple = updatedDisciple.copy(manualIds = updatedManualIds)

                val updatedItems = updatedDisciple.storageBagItems.toMutableList()
                val index = updatedItems.indexOfFirst { it.itemId == highestBag.id }
                if (index >= 0) {
                    val item = updatedItems[index]
                    if (item.quantity > 1) {
                        updatedItems[index] = item.copy(quantity = item.quantity - 1)
                    } else {
                        updatedItems.removeAt(index)
                    }
                }

                val oldItem = StorageBagItem(
                    itemId = lowestLearned.id,
                    itemType = "manual",
                    name = lowestLearned.name,
                    rarity = lowestLearned.rarity,
                    quantity = 1,
                    obtainedYear = gameYear,
                    obtainedMonth = gameMonth
                )
                updatedItems.add(oldItem)

                updatedDisciple = updatedDisciple.copy(storageBagItems = updatedItems)

                events.add("${disciple.name} 自动替换功法：${lowestLearned.name} → ${highestBag.name}" to EventType.INFO)
            }
        }

        return ManualLearnResult(updatedDisciple, events)
    }

    /**
     * 公共接口：处理弟子自动使用道具（带锁）
     * 供外部调用，会自动获取 transactionMutex 锁
     */
    suspend fun processDiscipleItemInstantly(
        discipleId: String,
        itemType: String
    ) {
        transactionMutex.withLock {
            processDiscipleItemInstantlyInternal(discipleId, itemType)
        }
    }

    /**
     * 内部实现：处理弟子自动使用道具（无锁）
     * 必须在已经持有 transactionMutex 锁的情况下调用
     * 供 rewardItemsToDisciple 在锁内部调用，避免递归锁问题
     */
    private fun processDiscipleItemInstantlyInternal(
        discipleId: String,
        itemType: String
    ) {
        val disciple = _disciples.value.find { it.id == discipleId } ?: return
        val data = _gameData.value
        val equipmentMap = _equipment.value.associateBy { it.id }

        when (itemType) {
            "pill" -> {
                val updatedDisciple = autoUseCultivationPillsInstant(disciple)
                if (updatedDisciple != disciple) {
                    _disciples.value = _disciples.value.map {
                        if (it.id == discipleId) updatedDisciple else it
                    }
                }
            }
            "equipment" -> {
                val result = processDiscipleEquipmentInstantly(disciple, equipmentMap, data.gameYear, data.gameMonth)
                if (result.disciple != disciple || result.equipmentUpdates.isNotEmpty()) {
                    _disciples.value = _disciples.value.map {
                        if (it.id == discipleId) result.disciple else it
                    }
                    // 批量更新装备 - 使用 distinctBy 确保没有重复 id
                    if (result.equipmentUpdates.isNotEmpty()) {
                        val updateMap = result.equipmentUpdates.associateBy { it.id }
                        _equipment.value = (_equipment.value.map { equipment ->
                            updateMap[equipment.id] ?: equipment
                        }).distinctBy { it.id }
                    }
                }
                result.events.forEach { (message, type) -> addEvent(message, type) }
            }
            "manual" -> {
                val result = processAutoLearnManualInstant(disciple, data.gameYear, data.gameMonth)
                if (result.disciple != disciple) {
                    _disciples.value = _disciples.value.map {
                        if (it.id == discipleId) result.disciple else it
                    }
                }
                result.events.forEach { (message, type) -> addEvent(message, type) }
            }
        }
    }

    private fun autoUseCultivationPillsInstant(disciple: Disciple): Disciple {
        if (disciple.storageBagItems.isEmpty()) return disciple
        
        val cultivationPill = disciple.storageBagItems
            .filter { item ->
                item.itemType == "pill" && item.effect?.let { effect ->
                    val isSpeedPill = effect.cultivationSpeed > 1.0 && effect.duration > 0
                    val isInstantPill = effect.cultivationPercent > 0 && effect.duration == 0
                    val canUseSpeedPill = isSpeedPill && disciple.cultivationSpeedDuration <= 0
                    val canUseInstantPill = isInstantPill
                    canUseSpeedPill || canUseInstantPill
                } == true
            }
            .maxByOrNull { item ->
                val effect = item.effect
                val speedBonus = (effect?.cultivationSpeed ?: 1.0) - 1.0
                val percentBonus = effect?.cultivationPercent ?: 0.0
                speedBonus + percentBonus
            }
        
        if (cultivationPill != null) {
            val effect = cultivationPill.effect
            val cultivationGain = (disciple.maxCultivation * (effect?.cultivationPercent ?: 0.0)).toInt()
            val speedBonus = (effect?.cultivationSpeed ?: 1.0)
            val duration = effect?.duration ?: 0
            
            val newCultivation = minOf(disciple.cultivation + cultivationGain, disciple.maxCultivation)
            val actualGain = (newCultivation - disciple.cultivation).toLong()
            
            val updatedItems = disciple.storageBagItems.toMutableList()
            val index = updatedItems.indexOf(cultivationPill)
            if (index >= 0) {
                if (cultivationPill.quantity > 1) {
                    updatedItems[index] = cultivationPill.copy(quantity = cultivationPill.quantity - 1)
                } else {
                    updatedItems.removeAt(index)
                }
            }
            
            val shouldUpdateSpeedEffect = speedBonus > 1.0 && duration > 0
            
            val message = if (actualGain > 0 && shouldUpdateSpeedEffect) {
                "${disciple.name} 立即使用了 ${cultivationPill.name}，修为增加${actualGain}，修炼速度提升${GameUtils.formatPercent(speedBonus - 1)}"
            } else if (actualGain > 0) {
                "${disciple.name} 立即使用了 ${cultivationPill.name}，修为增加${actualGain}"
            } else if (shouldUpdateSpeedEffect) {
                "${disciple.name} 立即使用了 ${cultivationPill.name}，修炼速度提升${GameUtils.formatPercent(speedBonus - 1)}"
            } else {
                "${disciple.name} 立即使用了 ${cultivationPill.name}"
            }
            addEvent(message, EventType.INFO)
            
            return disciple.copy(
                storageBagItems = updatedItems,
                cultivation = newCultivation,
                totalCultivation = disciple.totalCultivation + actualGain,
                cultivationSpeedBonus = if (shouldUpdateSpeedEffect) speedBonus else disciple.cultivationSpeedBonus,
                cultivationSpeedDuration = if (shouldUpdateSpeedEffect) duration else disciple.cultivationSpeedDuration
            )
        }
        
        return disciple
    }

    private data class InstantEquipResult(
        val disciple: Disciple,
        val equipmentUpdates: List<Equipment>,
        val events: List<Pair<String, EventType>>
    )

    private fun processDiscipleEquipmentInstantly(
        disciple: Disciple,
        equipmentMap: Map<String, Equipment>,
        gameYear: Int,
        gameMonth: Int
    ): InstantEquipResult {
        val equipmentUpdates = mutableListOf<Equipment>()
        val events = mutableListOf<Pair<String, EventType>>()
        var updatedDisciple = disciple

        val equipmentSlots = listOf(
            EquipmentSlot.WEAPON to disciple.weaponId,
            EquipmentSlot.ARMOR to disciple.armorId,
            EquipmentSlot.BOOTS to disciple.bootsId,
            EquipmentSlot.ACCESSORY to disciple.accessoryId
        )

        equipmentSlots.forEach { (slot, currentEquipId) ->
            val bagEquipments = disciple.storageBagItems.filter { item ->
                item.itemType == "equipment"
            }.mapNotNull { item ->
                equipmentMap[item.itemId] ?: createEquipmentFromStorageItem(item)
            }.filter { it.slot == slot }

            if (bagEquipments.isEmpty()) return@forEach

            val currentEquip = currentEquipId?.let { equipmentMap[it] }
            val currentRarity = currentEquip?.rarity ?: 0

            val betterEquip = bagEquipments.filter { it.rarity > currentRarity }.maxByOrNull { it.rarity }

            if (betterEquip != null) {
                currentEquipId?.let { oldId ->
                    val oldEquip = equipmentMap[oldId]
                    if (oldEquip != null) {
                        equipmentUpdates.add(oldEquip.copy(ownerId = null, isEquipped = false))
                    }
                }

                updatedDisciple = when (slot) {
                    EquipmentSlot.WEAPON -> updatedDisciple.copy(weaponId = betterEquip.id)
                    EquipmentSlot.ARMOR -> updatedDisciple.copy(armorId = betterEquip.id)
                    EquipmentSlot.BOOTS -> updatedDisciple.copy(bootsId = betterEquip.id)
                    EquipmentSlot.ACCESSORY -> updatedDisciple.copy(accessoryId = betterEquip.id)
                }

                equipmentUpdates.add(betterEquip.copy(ownerId = disciple.id, isEquipped = true))

                val updatedItems = updatedDisciple.storageBagItems.toMutableList()
                val bagIndex = updatedItems.indexOfFirst { it.itemId == betterEquip.id }
                if (bagIndex >= 0) {
                    val item = updatedItems[bagIndex]
                    if (item.quantity > 1) {
                        updatedItems[bagIndex] = item.copy(quantity = item.quantity - 1)
                    } else {
                        updatedItems.removeAt(bagIndex)
                    }
                }
                updatedDisciple = updatedDisciple.copy(storageBagItems = updatedItems)

                currentEquip?.let { oldEquip ->
                    val oldItem = StorageBagItem(
                        itemId = oldEquip.id,
                        itemType = "equipment",
                        name = oldEquip.name,
                        rarity = oldEquip.rarity,
                        quantity = 1,
                        obtainedYear = gameYear,
                        obtainedMonth = gameMonth
                    )
                    updatedDisciple = updatedDisciple.copy(
                        storageBagItems = addStorageItemToBag(updatedDisciple.storageBagItems, oldItem)
                    )
                }

                events.add("${disciple.name} 立即装备了 ${betterEquip.name}（${slot.name}）" to EventType.INFO)
            }
        }

        return InstantEquipResult(updatedDisciple, equipmentUpdates, events)
    }

    private data class InstantManualResult(
        val disciple: Disciple,
        val events: List<Pair<String, EventType>>,
        val learnedManual: Manual? = null,
        val replacedManual: Manual? = null
    )

    private fun processAutoLearnManualInstant(
        disciple: Disciple,
        gameYear: Int,
        gameMonth: Int
    ): InstantManualResult {
        val events = mutableListOf<Pair<String, EventType>>()

        if (disciple.storageBagItems.isEmpty()) {
            return InstantManualResult(disciple, events)
        }

        var updatedDisciple = disciple
        val manuals = _manuals.value.associateBy { it.id }

        val hasMindManual = disciple.manualIds.any { manualId ->
            manuals[manualId]?.type == ManualType.MIND
        }

        val bagManuals = disciple.storageBagItems.filter { item ->
            item.itemType == "manual" && !disciple.manualIds.contains(item.itemId)
        }.mapNotNull { item ->
            manuals[item.itemId] ?: createManualFromStorageItem(item)
        }.filter { manual ->
            val canLearnByRealm = disciple.realm <= manual.minRealm
            if (hasMindManual) manual.type != ManualType.MIND && canLearnByRealm else canLearnByRealm
        }

        if (bagManuals.isEmpty()) {
            return InstantManualResult(updatedDisciple, events)
        }

        val learnedManuals = disciple.manualIds.mapNotNull { manualId ->
            manuals[manualId]
        }

        val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
        val manualSlotBonus = talentEffects["manualSlot"]?.toInt() ?: 0
        val maxManualSlots = 5 + manualSlotBonus

        if (learnedManuals.size < maxManualSlots) {
            val bestManual = bagManuals.maxByOrNull { it.rarity }

            if (bestManual != null) {
                // 功法被学习后加回仓库，标记为已学习
                val learnedManual = bestManual.copy(isLearned = true, ownerId = disciple.id)
                // 先移除可能存在的旧功法，再添加新的
                _manuals.value = _manuals.value.filter { it.id != bestManual.id } + learnedManual

                updatedDisciple = updatedDisciple.copy(
                    manualIds = updatedDisciple.manualIds + bestManual.id
                )

                val updatedItems = updatedDisciple.storageBagItems.toMutableList()
                val index = updatedItems.indexOfFirst { it.itemId == bestManual.id }
                if (index >= 0) {
                    val item = updatedItems[index]
                    if (item.quantity > 1) {
                        updatedItems[index] = item.copy(quantity = item.quantity - 1)
                    } else {
                        updatedItems.removeAt(index)
                    }
                }
                updatedDisciple = updatedDisciple.copy(storageBagItems = updatedItems)

                events.add("${disciple.name} 立即学习了功法 ${bestManual.name}" to EventType.INFO)
                return InstantManualResult(updatedDisciple, events, learnedManual)
            }
        } else {
            val lowestLearned = learnedManuals.minByOrNull { it.rarity }
            val highestBag = bagManuals.maxByOrNull { it.rarity }

            if (lowestLearned != null && highestBag != null && highestBag.rarity > lowestLearned.rarity) {
                if (hasMindManual && highestBag.type == ManualType.MIND) {
                    return InstantManualResult(updatedDisciple, events)
                }

                // 新功法被学习后加回仓库，标记为已学习
                val learnedManual = highestBag.copy(isLearned = true, ownerId = disciple.id)
                // 旧功法标记为未学习
                val unlearnedManual = lowestLearned.copy(isLearned = false, ownerId = null)

                _manuals.value = _manuals.value.filter { it.id != lowestLearned.id && it.id != highestBag.id } + learnedManual + unlearnedManual

                val updatedManualIds = disciple.manualIds.toMutableList()
                updatedManualIds.remove(lowestLearned.id)
                updatedManualIds.add(highestBag.id)

                updatedDisciple = updatedDisciple.copy(manualIds = updatedManualIds)

                val updatedItems = updatedDisciple.storageBagItems.toMutableList()
                val index = updatedItems.indexOfFirst { it.itemId == highestBag.id }
                if (index >= 0) {
                    val item = updatedItems[index]
                    if (item.quantity > 1) {
                        updatedItems[index] = item.copy(quantity = item.quantity - 1)
                    } else {
                        updatedItems.removeAt(index)
                    }
                }

                val oldItem = StorageBagItem(
                    itemId = lowestLearned.id,
                    itemType = "manual",
                    name = lowestLearned.name,
                    rarity = lowestLearned.rarity,
                    quantity = 1,
                    obtainedYear = gameYear,
                    obtainedMonth = gameMonth
                )
                updatedItems.add(oldItem)

                updatedDisciple = updatedDisciple.copy(storageBagItems = updatedItems)

                events.add("${disciple.name} 立即替换功法：${lowestLearned.name} → ${highestBag.name}" to EventType.INFO)
                return InstantManualResult(updatedDisciple, events, learnedManual, unlearnedManual)
            }
        }

        return InstantManualResult(updatedDisciple, events)
    }

    private fun checkMerchantRefresh(year: Int) {
        val data = _gameData.value
        // 每五年刷新一次（年份是5的倍数时刷新）
        if (year % 5 == 0 && data.merchantLastRefreshYear < year) {
            val (items, newCount) = generateMerchantItems(
                year,
                data.merchantRefreshCount
            )
            _gameData.value = _gameData.value.copy(
                travelingMerchantItems = items,
                merchantLastRefreshYear = year,
                merchantRefreshCount = newCount
            )
            addEvent("云游商人到访！带来了${items.size}件商品", EventType.SUCCESS)
        }
    }
    
    private fun payMonthlySalary() {
        val data = _gameData.value
        var totalPaid = 0L
        
        // 计算总月俸（只计算开启开关的境界）
        _disciples.value.filter { it.isAlive && it.status != DiscipleStatus.REFLECTING }.forEach { disciple ->
            val isEnabled = data.monthlySalaryEnabled[disciple.realm] ?: true
            if (isEnabled) {
                val salary = data.monthlySalary[disciple.realm] ?: 0
                totalPaid += salary
            }
        }
        
        val canPay = totalPaid > 0 && data.spiritStones >= totalPaid
        
        if (canPay) {
            // 扣除宗门灵石
            _gameData.value = _gameData.value.copy(
                spiritStones = _gameData.value.spiritStones - totalPaid
            )
        }
        
        // 处理每个弟子的月俸和忠诚变化
        _disciples.value = _disciples.value.map { disciple ->
            if (!disciple.isAlive || disciple.status == DiscipleStatus.REFLECTING) return@map disciple
            
            val isEnabled = data.monthlySalaryEnabled[disciple.realm] ?: true
            val shouldReceive = isEnabled && (data.monthlySalary[disciple.realm] ?: 0) > 0
            
            var updatedDisciple = disciple
            // 使用两个独立字段：累计发放次数和累计少发次数
            var paidCount = disciple.salaryPaidCount
            var missedCount = disciple.salaryMissedCount
            
            if (shouldReceive) {
                if (canPay) {
                    // 发放月俸成功
                    val salary = data.monthlySalary[disciple.realm] ?: 0
                    updatedDisciple = updatedDisciple.copy(
                        storageBagSpiritStones = updatedDisciple.storageBagSpiritStones + salary
                    )
                    // 累计发放次数+1
                    paidCount += 1
                } else {
                    // 应该发但没发成（灵石不足）
                    missedCount += 1
                }
            }
            
            // 检查是否达到3次阈值，调整忠诚
            var newLoyalty = updatedDisciple.loyalty
            var loyaltyChanged = false
            
            // 累计发放3次，忠诚+1，重置发放计数
            while (paidCount >= 3) {
                newLoyalty = (newLoyalty + 1).coerceAtMost(100)
                paidCount -= 3
                loyaltyChanged = true
            }
            
            // 累计少发3次，忠诚-1，重置少发计数
            while (missedCount >= 3) {
                newLoyalty = (newLoyalty - 1).coerceAtLeast(0)
                missedCount -= 3
                loyaltyChanged = true
            }
            
            if (loyaltyChanged) {
                // 移除弟子领取月俸提升忠诚度的消息，只保留下降的消息
                if (newLoyalty < updatedDisciple.loyalty) {
                    addEvent("${updatedDisciple.name} 因累计未领到月俸，忠诚度下降", EventType.WARNING)
                }
            }
            
            updatedDisciple.copy(
                loyalty = newLoyalty,
                salaryPaidCount = paidCount,
                salaryMissedCount = missedCount
            )
        }
        
        if (canPay) {
            addEvent("发放月俸：$totalPaid 灵石", EventType.INFO)
        } else if (totalPaid > data.spiritStones) {
            addEvent("灵石不足，无法发放月俸！", EventType.WARNING)
        }
    }
    
    private fun processDiscipleCultivation(isSecondTick: Boolean = true) {
        val wenDaoTeachingBonus = calculateWenDaoPeakTeachingBonus()
        val qingyunTeachingBonus = calculateQingyunPeakTeachingBonus()
        
        val updatedDisciples = _disciples.value.map { disciple ->
            if (!disciple.isAlive || !disciple.canCultivate) {
                return@map disciple
            }

            var currentDisciple = autoUseCultivationPills(disciple)

            var additionalBonus = 0.0
            
            if (_gameData.value.sectPolicies.cultivationSubsidy && disciple.realm < 5) {
                val basePolicyBonus = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_BASE_EFFECT
                val viceSectMasterBonus = calculateViceSectMasterPolicyBonus()
                additionalBonus += basePolicyBonus * (1 + viceSectMasterBonus)
            }

            if (disciple.discipleType == "outer" && wenDaoTeachingBonus > 0) {
                additionalBonus += wenDaoTeachingBonus
            }

            if (disciple.discipleType == "inner" && qingyunTeachingBonus > 0) {
                additionalBonus += qingyunTeachingBonus
            }

            val speed = currentDisciple.calculateCultivationSpeedPerSecond(
                manuals = _manuals.value.associateBy { it.id },
                manualProficiencies = _gameData.value.manualProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap(),
                additionalBonus = additionalBonus
            )

            val tickMultiplier = 1.0 / GameConfig.Time.TICKS_PER_SECOND
            val totalGain = speed * tickMultiplier
            val newCultivation = currentDisciple.cultivation + totalGain

            val cultivationReq = currentDisciple.maxCultivation

            if (currentDisciple.realm == 0) {
                currentDisciple
            } else if (newCultivation >= cultivationReq && currentDisciple.realm > 0 && isSecondTick) {
                currentDisciple = currentDisciple.copy(cultivation = newCultivation)
                val (updatedDisciple, pillBonus) = autoUseBreakthroughPills(currentDisciple)
                currentDisciple = attemptBreakthrough(updatedDisciple, pillBonus)
                currentDisciple
            } else {
                currentDisciple.copy(
                    cultivation = minOf(newCultivation, cultivationReq),
                    totalCultivation = currentDisciple.totalCultivation + totalGain.toLong()
                )
            }
        }

        _disciples.value = updatedDisciples
    }
    
    private fun calculateManualCultivationBonus(disciple: Disciple): Double {
        var bonus = 0.0
        val proficiencies = _gameData.value.manualProficiencies[disciple.id]
        val learnedManuals = _manuals.value.filter { it.id in disciple.manualIds }
        learnedManuals.forEach { manual ->
            val proficiencyData = proficiencies?.find { it.manualId == manual.id }
            val masteryLevel = proficiencyData?.masteryLevel ?: 0
            val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus
            bonus += manual.cultivationSpeedPercent * masteryBonus / 100.0
        }
        return bonus
    }
    
    private fun calculateTalentCultivationBonus(disciple: Disciple): Double {
        var bonus = 0.0
        // 从天赋中获取修炼速度加成
        val talents = TalentDatabase.getTalentsByIds(disciple.talentIds)
        talents.forEach { talent ->
            bonus += talent.effects["cultivationSpeed"] ?: 0.0
        }
        return bonus
    }

    private fun processManualProficiencyPerSecond(isSecondTick: Boolean = true) {
        if (!isSecondTick) return
        
        val data = _gameData.value
        val updatedProficiencies = data.manualProficiencies.toMutableMap()

        _disciples.value.filter { it.isAlive }.forEach { disciple ->
            val proficiencies = updatedProficiencies[disciple.id]?.toMutableList() ?: mutableListOf()

            val isInLibrary = _buildingSlots.value.any {
                it.buildingId == "library" && it.discipleId == disciple.id
            }
            
            val libraryTeachingBonus = calculateLibraryTeachingBonus()
            
            val libraryBonus = if (isInLibrary) 0.5 + libraryTeachingBonus else 0.0

            val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
            val manualLearnSpeedBonus = talentEffects["manualLearnSpeed"] ?: 0.0
            
            /**
             * 功法研习政策效果计算
             * 
             * 基础效果：功法修炼速度+20%
             * 副宗主加成：智力50为基准，每多5点+1%
             * 消耗：每月4000灵石
             * 
             * 计算公式：
             * 实际功法修炼速度加成 = 20% × (1 + 副宗主加成)
             * 
             * 示例（副宗主智力75）：
             * 副宗主加成 = (75-50)/5 × 1% = 5%
             * 实际功法修炼速度加成 = 20% × 1.05 = 21%
             */
            var policyManualBonus = 0.0
            if (_gameData.value.sectPolicies.manualResearch) {
                val basePolicyBonus = GameConfig.PolicyConfig.MANUAL_RESEARCH_BASE_EFFECT
                val viceSectMasterBonus = calculateViceSectMasterPolicyBonus()
                policyManualBonus = basePolicyBonus * (1 + viceSectMasterBonus)
            }
            
            val totalManualBonus = manualLearnSpeedBonus + policyManualBonus

            disciple.manualIds.forEach { manualId ->
                val manual = _manuals.value.find { it.id == manualId } ?: return@forEach
                val existingIndex = proficiencies.indexOfFirst { it.manualId == manualId }

                val gain = ManualProficiencySystem.calculateProficiencyGain(
                    baseGain = ManualProficiencySystem.BASE_PROFICIENCY_GAIN / 5.0,
                    discipleRealm = disciple.realm,
                    manualRarity = manual.rarity,
                    libraryBonus = libraryBonus,
                    talentBonus = totalManualBonus
                )

                if (existingIndex >= 0) {
                    val existing = proficiencies[existingIndex]
                    val updated = ManualProficiencySystem.updateProficiency(
                        ManualProficiencySystem.ManualProficiency(
                            manualId = existing.manualId,
                            manualName = existing.manualName,
                            proficiency = existing.proficiency,
                            masteryLevel = existing.masteryLevel
                        ),
                        gain,
                        manual.rarity
                    )
                    proficiencies[existingIndex] = ManualProficiencyData(
                        manualId = updated.manualId,
                        manualName = updated.manualName,
                        proficiency = updated.proficiency,
                        masteryLevel = updated.masteryLevel
                    )
                } else {
                    val newProficiency = ManualProficiencySystem.ManualProficiency(
                        manualId = manualId,
                        manualName = manual.name
                    )
                    val updated = ManualProficiencySystem.updateProficiency(newProficiency, gain, manual.rarity)
                    proficiencies.add(ManualProficiencyData(
                        manualId = updated.manualId,
                        manualName = updated.manualName,
                        proficiency = updated.proficiency,
                        masteryLevel = updated.masteryLevel
                    ))
                }
            }

            updatedProficiencies[disciple.id] = proficiencies
        }

        _gameData.value = data.copy(manualProficiencies = updatedProficiencies)
    }

    private fun processEquipmentAutoNurture(isSecondTick: Boolean = true) {
        val currentEquipments = _equipment.value
        val updatedEquipments = mutableListOf<Equipment>()

        currentEquipments.forEach { equipment ->
            val autoExpGain = EquipmentNurtureSystem.calculateAutoExpGain(equipment.rarity)
            val tickMultiplier = 1.0 / GameConfig.Time.TICKS_PER_SECOND
            val adjustedExpGain = autoExpGain * tickMultiplier

            val nurtureResult = EquipmentNurtureSystem.updateNurtureExp(equipment, adjustedExpGain)
            updatedEquipments.add(nurtureResult.equipment)
        }

        if (updatedEquipments.isNotEmpty()) {
            _equipment.value = updatedEquipments
        }
    }

    private fun autoUseCultivationPills(disciple: Disciple): Disciple {
        if (disciple.storageBagItems.isEmpty()) return disciple
        
        val cultivationPill = disciple.storageBagItems
            .filter { item ->
                item.itemType == "pill" && item.effect?.let { effect ->
                    val isSpeedPill = effect.cultivationSpeed > 1.0 && effect.duration > 0
                    val isInstantPill = effect.cultivationPercent > 0 && effect.duration == 0
                    val canUseSpeedPill = isSpeedPill && disciple.cultivationSpeedDuration <= 0
                    val canUseInstantPill = isInstantPill
                    canUseSpeedPill || canUseInstantPill
                } == true
            }
            .maxByOrNull { item ->
                val effect = item.effect
                val speedBonus = (effect?.cultivationSpeed ?: 1.0) - 1.0
                val percentBonus = effect?.cultivationPercent ?: 0.0
                speedBonus + percentBonus
            }
        
        if (cultivationPill != null) {
            val effect = cultivationPill.effect
            val cultivationGain = (disciple.maxCultivation * (effect?.cultivationPercent ?: 0.0)).toInt()
            val speedBonus = (effect?.cultivationSpeed ?: 1.0)
            val duration = effect?.duration ?: 0
            
            val newCultivation = minOf(disciple.cultivation + cultivationGain, disciple.maxCultivation)
            val actualGain = (newCultivation - disciple.cultivation).toLong()
            
            val updatedItems = disciple.storageBagItems.toMutableList()
            val index = updatedItems.indexOf(cultivationPill)
            if (index >= 0) {
                if (cultivationPill.quantity > 1) {
                    updatedItems[index] = cultivationPill.copy(quantity = cultivationPill.quantity - 1)
                } else {
                    updatedItems.removeAt(index)
                }
            }
            
            val shouldUpdateSpeedEffect = speedBonus > 1.0 && duration > 0
            
            val message = if (actualGain > 0 && shouldUpdateSpeedEffect) {
                "${disciple.name} 自动使用了 ${cultivationPill.name}，修为增加${actualGain}，修炼速度提升${GameUtils.formatPercent(speedBonus - 1)}"
            } else if (actualGain > 0) {
                "${disciple.name} 自动使用了 ${cultivationPill.name}，修为增加${actualGain}"
            } else if (shouldUpdateSpeedEffect) {
                "${disciple.name} 自动使用了 ${cultivationPill.name}，修炼速度提升${GameUtils.formatPercent(speedBonus - 1)}"
            } else {
                "${disciple.name} 自动使用了 ${cultivationPill.name}"
            }
            addEvent(message, EventType.INFO)
            
            return disciple.copy(
                storageBagItems = updatedItems,
                cultivation = newCultivation,
                totalCultivation = disciple.totalCultivation + actualGain,
                cultivationSpeedBonus = if (shouldUpdateSpeedEffect) speedBonus else disciple.cultivationSpeedBonus,
                cultivationSpeedDuration = if (shouldUpdateSpeedEffect) duration else disciple.cultivationSpeedDuration
            )
        }
        
        return disciple
    }
    
    private fun autoUseBreakthroughPills(disciple: Disciple): Pair<Disciple, Double> {
        if (disciple.storageBagItems.isEmpty()) return Pair(disciple, 0.0)
        
        // 只有大境界突破才使用突破丹药
        if (!TribulationSystem.isBigBreakthrough(disciple)) {
            return Pair(disciple, 0.0)
        }
        
        val breakthroughPill = disciple.storageBagItems.find { item ->
            item.itemType == "pill" && item.effect?.breakthroughChance != null && item.effect.breakthroughChance > 0
        }
        
        if (breakthroughPill != null) {
            // 检查丹药是否适合当前境界突破（targetRealm是突破后的目标境界，应为当前境界-1）
            val targetRealm = breakthroughPill.effect?.targetRealm ?: 0
            if (targetRealm != disciple.realm - 1) {
                // 丹药不适合当前境界突破，不使用
                return Pair(disciple, 0.0)
            }
            
            val pillBonus = breakthroughPill.effect?.breakthroughChance ?: 0.0
            val updatedItems = disciple.storageBagItems.toMutableList()
            val index = updatedItems.indexOf(breakthroughPill)
            if (index >= 0) {
                if (breakthroughPill.quantity > 1) {
                    updatedItems[index] = breakthroughPill.copy(quantity = breakthroughPill.quantity - 1)
                } else {
                    updatedItems.removeAt(index)
                }
            }
            addEvent("${disciple.name} 自动使用了 ${breakthroughPill.name}，突破概率提升${GameUtils.formatPercent(pillBonus)}", EventType.INFO)
            return Pair(disciple.copy(storageBagItems = updatedItems), pillBonus)
        }

        return Pair(disciple, 0.0)
    }

    // 战斗前自动使用战斗类丹药（有就直接用）
    // 战斗丹药：增加 battleCount 等战斗属性
    private fun autoUseBattlePills(disciple: Disciple): Disciple {
        if (disciple.storageBagItems.isEmpty()) return disciple

        // 查找具有战斗属性的丹药（battleCount > 0）
        val battlePill = disciple.storageBagItems.find { item ->
            item.itemType == "pill" && item.effect?.battleCount != null && item.effect.battleCount > 0
        }

        if (battlePill != null) {
            val updatedItems = disciple.storageBagItems.toMutableList()
            val index = updatedItems.indexOf(battlePill)
            if (index >= 0) {
                if (battlePill.quantity > 1) {
                    updatedItems[index] = battlePill.copy(quantity = battlePill.quantity - 1)
                } else {
                    updatedItems.removeAt(index)
                }
            }

            // 应用战斗属性加成
            val effect = battlePill.effect
            val updatedDisciple = disciple.copy(
                storageBagItems = updatedItems,
                pillEffectDuration = effect?.battleCount ?: 1,
                pillPhysicalAttackBonus = disciple.pillPhysicalAttackBonus + (effect?.physicalAttackPercent ?: 0.0),
                pillMagicAttackBonus = disciple.pillMagicAttackBonus + (effect?.magicAttackPercent ?: 0.0),
                pillPhysicalDefenseBonus = disciple.pillPhysicalDefenseBonus + (effect?.physicalDefensePercent ?: 0.0),
                pillMagicDefenseBonus = disciple.pillMagicDefenseBonus + (effect?.magicDefensePercent ?: 0.0),
                pillHpBonus = disciple.pillHpBonus + (effect?.hpPercent ?: 0.0),
                pillMpBonus = disciple.pillMpBonus + (effect?.mpPercent ?: 0.0),
                pillSpeedBonus = disciple.pillSpeedBonus + (effect?.speedPercent ?: 0.0)
            )

            addEvent("${disciple.name} 自动使用了 ${battlePill.name}，战斗力提升", EventType.INFO)
            return updatedDisciple
        }

        return disciple
    }

    // 状态不佳时自动使用恢复类丹药（有就直接用）
    // 恢复丹药：具有 heal、healPercent、hpPercent、mpPercent 等恢复属性
    private fun autoUseHealingPills(disciple: Disciple): Disciple {
        if (disciple.storageBagItems.isEmpty()) return disciple

        val healingPill = disciple.storageBagItems.find { item ->
            item.itemType == "pill" && item.effect?.let { effect ->
                effect.heal > 0 || effect.healPercent > 0 || 
                effect.hpPercent > 0 || effect.mpPercent > 0 || effect.mpRecoverPercent > 0
            } == true
        }

        if (healingPill != null) {
            val updatedItems = disciple.storageBagItems.toMutableList()
            val index = updatedItems.indexOf(healingPill)
            if (index >= 0) {
                if (healingPill.quantity > 1) {
                    updatedItems[index] = healingPill.copy(quantity = healingPill.quantity - 1)
                } else {
                    updatedItems.removeAt(index)
                }
            }

            // 应用恢复效果
            val effect = healingPill.effect
            // 注意：由于Disciple类中没有直接的hp和mp字段，这里只处理属性加成
            val updatedDisciple = disciple.copy(
                storageBagItems = updatedItems,
                pillEffectDuration = 1, // 恢复类丹药效果持续1个月
                pillHpBonus = disciple.pillHpBonus + (effect?.hpPercent ?: 0.0),
                pillMpBonus = disciple.pillMpBonus + (effect?.mpPercent ?: 0.0)
            )

            addEvent("${disciple.name} 自动使用了 ${healingPill.name}，恢复状态", EventType.INFO)
            return updatedDisciple
        }

        return disciple
    }

    private fun attemptBreakthrough(disciple: Disciple, pillBonus: Double = 0.0): Disciple {
        // 战斗中不能突破
        if (disciple.status == DiscipleStatus.BATTLE) {
            return disciple
        }

        // 获取内门长老悟性加成（仅对内门弟子生效）
        var innerElderComprehension = 0
        if (disciple.discipleType == "inner") {
            val innerElderId = _gameData.value.elderSlots.innerElder
            if (innerElderId != null) {
                val innerElder = _disciples.value.find { it.id == innerElderId }
                if (innerElder != null && innerElder.isAlive) {
                    innerElderComprehension = innerElder.comprehension
                }
            }
        }

        // 获取外门长老悟性加成（仅对外门弟子生效）
        var outerElderComprehensionBonus = 0.0
        if (disciple.discipleType == "outer") {
            val outerElderId = _gameData.value.elderSlots.outerElder
            if (outerElderId != null) {
                val outerElder = _disciples.value.find { it.id == outerElderId }
                if (outerElder != null && outerElder.isAlive) {
                    // 外门长老悟性加成：以50为基准，每高5点增加1%，每低5点减少1%
                    outerElderComprehensionBonus = (outerElder.comprehension - 50) / 5.0 * 0.01
                }
            }
        }

        val chance = disciple.getBreakthroughChance(pillBonus, innerElderComprehension, outerElderComprehensionBonus)
        val isBigBreakthrough = TribulationSystem.isBigBreakthrough(disciple)

        if (isBigBreakthrough) {
            addEvent("${disciple.name} 开始尝试突破至${GameConfig.Realm.get(disciple.realm - 1).name}...", EventType.INFO)

            if (Random.nextDouble() >= chance) {
                addEvent("${disciple.name} 突破失败，修为不足或机缘未到", EventType.INFO)
                return disciple.copy(
                    cultivation = 0.0,
                    breakthroughFailCount = disciple.breakthroughFailCount + 1
                )
            }

            if (disciple.realm > 0) {
                val needsHeartDemon = TribulationSystem.needsHeartDemon(disciple)
                
                if (needsHeartDemon) {
                    val heartResult = TribulationSystem.trialHeartDemon(disciple)
                    if (!heartResult.success) {
                        addEvent("${disciple.name} 突破失败，未能战胜心魔（${heartResult.message}）", EventType.INFO)
                        return disciple.copy(
                            cultivation = 0.0,
                            breakthroughFailCount = disciple.breakthroughFailCount + 1
                        )
                    }
                }

                val thunderResult = TribulationSystem.trialThunderTribulation(disciple)
                if (!thunderResult.success) {
                    addEvent("${disciple.name} 突破失败，未能渡过雷劫", EventType.INFO)
                    return disciple.copy(
                        cultivation = 0.0,
                        breakthroughFailCount = disciple.breakthroughFailCount + 1
                    )
                }

                val newRealm = disciple.realm - 1
                val newRealmName = GameConfig.Realm.getName(newRealm)
                addEvent("${disciple.name} 成功突破至${newRealmName}！", EventType.SUCCESS)

                return disciple.copy(
                    realm = newRealm,
                    realmLayer = 1,
                    cultivation = 0.0,
                    breakthroughCount = disciple.breakthroughCount + 1,
                    breakthroughFailCount = 0,
                    lifespan = GameConfig.Realm.get(newRealm).maxAge
                )
            }
        } else {
            if (Random.nextDouble() >= chance) {
                addEvent("${disciple.name} 突破失败，修为不足或机缘未到", EventType.INFO)
                return disciple.copy(
                    cultivation = 0.0,
                    breakthroughFailCount = disciple.breakthroughFailCount + 1
                )
            }
        }
        
        val newLayer = disciple.realmLayer + 1
        return disciple.copy(
            realmLayer = newLayer,
            cultivation = 0.0,
            breakthroughCount = disciple.breakthroughCount + 1,
            breakthroughFailCount = 0
        )
    }
    
    private fun processBuildingSlots(year: Int, month: Int) {
        val data = _gameData.value
        
        // 灵矿场汇总处理
        processSpiritMineMonthly()
        
        val slotsToRemove = mutableListOf<String>()
        
        val updatedSlots = _buildingSlots.value.map { slot ->
            when {
                slot.buildingId == "spiritMine" -> slot
                slot.status == SlotStatus.WORKING && slot.isFinished(year, month) -> {
                    completeBuildingTask(slot)
                    val currentSlot = _buildingSlots.value.find { it.id == slot.id }
                    if (currentSlot?.status == SlotStatus.WORKING) {
                        // 自动续炼成功，保留WORKING状态
                        currentSlot
                    } else {
                        // 没有自动续炼，锻造槽位需要移除
                        if (slot.buildingId == "forge") {
                            slotsToRemove.add(slot.id)
                        }
                        slot.copy(status = SlotStatus.IDLE)
                    }
                }
                else -> slot
            }
        }
        
        _buildingSlots.value = if (slotsToRemove.isNotEmpty()) {
            updatedSlots.filter { it.id !in slotsToRemove }
        } else {
            updatedSlots
        }
    }
    
    private fun completeBuildingTask(slot: BuildingSlot) {
        when (slot.buildingId) {
            "alchemyRoom" -> completeAlchemy(slot)
            "forge" -> completeForging(slot)
            // 灵药园使用新的PlantSlotData系统，通过processHerbGarden处理
            // 灵矿场在processBuildingSlots中汇总处理
        }
    }
    
    /**
     * 灵矿场月度汇总产出
     * 统计所有入驻弟子的产出，合并为一条事件显示
     */
    private fun processSpiritMineMonthly() {
        val data = _gameData.value
        val mineSlots = data.spiritMineSlots.filter { it.discipleId != null }
        
        if (mineSlots.isEmpty()) return
        
        // 计算执事道德加成
        val deaconBonus = calculateSpiritMineDeaconBonus()
        
        var yieldMultiplier = 1.0 + deaconBonus
        
        /**
         * 灵矿增产政策效果计算
         * 
         * 基础效果：灵石产出+20%
         * 副宗主加成：智力50为基准，每多5点+1%
         * 副作用：采矿弟子忠诚度每月-1
         * 
         * 计算公式：
         * 实际产出倍数 = 1.0 + 20% × (1 + 副宗主加成)
         * 
         * 示例（副宗主智力70）：
         * 副宗主加成 = (70-50)/5 × 1% = 4%
         * 实际产出倍数 = 1.0 + 20% × 1.04 = 1.208 (即+20.8%)
         */
        val spiritMineBoostEnabled = data.sectPolicies.spiritMineBoost
        if (spiritMineBoostEnabled) {
            val baseBonus = GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_BASE_EFFECT
            val viceSectMasterBonus = calculateViceSectMasterPolicyBonus()
            yieldMultiplier += baseBonus * (1 + viceSectMasterBonus)
        }
        
        var totalOutput = 0L
        var workingDiscipleCount = 0
        val disciplesToUpdate = mutableListOf<Disciple>()
        
        mineSlots.forEach { slot ->
            val disciple = _disciples.value.find { it.id == slot.discipleId } ?: return@forEach
            workingDiscipleCount++
            
            val baseOutput = when (disciple.realm) {
                0 -> 250000L  // 仙人
                1 -> 90000L   // 渡劫
                2 -> 35000L   // 大乘
                3 -> 13000L   // 合体
                4 -> 5000L    // 炼虚
                5 -> 2000L    // 化神
                6 -> 800L     // 元婴
                7 -> 300L     // 金丹
                8 -> 120L     // 筑基
                9 -> 50L      // 炼气
                else -> 50L
            }
            
            // 天赋影响已移除
            val output = (baseOutput * yieldMultiplier).toLong()
            totalOutput += output
            
            // 灵矿增产政策：采矿弟子忠诚度每月-1
            if (spiritMineBoostEnabled) {
                disciplesToUpdate.add(disciple.copy(loyalty = (disciple.loyalty - 1).coerceAtLeast(0)))
            }
        }
        
        // 更新弟子忠诚度
        if (disciplesToUpdate.isNotEmpty()) {
            _disciples.value = _disciples.value.map { d ->
                disciplesToUpdate.find { it.id == d.id } ?: d
            }
        }
        
        if (totalOutput > 0) {
            _gameData.value = _gameData.value.copy(spiritStones = _gameData.value.spiritStones + totalOutput)
            val deaconBonusText = if (deaconBonus > 0) {
                " (执事加成+${GameUtils.formatPercent(deaconBonus)})"
            } else ""
            val policyText = if (spiritMineBoostEnabled) " (增产令+20%)" else ""
            addEvent("灵矿场${workingDiscipleCount}名弟子开采获得${totalOutput}灵石${deaconBonusText}${policyText}", EventType.SUCCESS)
        }
    }
    
    /**
     * 计算灵矿执事的道德加成
     * 以道德50为基准，每高5点增加2%产量
     */
    private fun calculateSpiritMineDeaconBonus(): Double {
        val data = _gameData.value
        val deaconSlots = data.elderSlots.spiritMineDeaconDisciples
        
        return deaconSlots.mapNotNull { slot ->
            slot.discipleId?.let { id -> _disciples.value.find { it.id == id } }
        }.sumOf { disciple ->
            // 以道德50为基准，每高5点增加2%产量
            val moralityDiff = disciple.morality - 50
            (moralityDiff / 5.0) * 0.02
        }
    }
    
    private fun completeAlchemy(slot: BuildingSlot) {
        val recipeId = slot.recipeId ?: return
        val recipe = PillRecipeDatabase.getRecipeById(recipeId) ?: return

        // 计算长老和亲传弟子的加成
        val elderBonus = calculateElderAndDisciplesBonus("alchemy")

        // 计算成功率加成
        val disciple = _disciples.value.find { it.id == slot.discipleId }
        val discipleSuccessBonus = calculateSuccessRateBonus(disciple, "alchemyRoom")
        
        /**
         * 丹道激励政策效果计算
         * 
         * 基础效果：炼丹成功率+10%
         * 副宗主加成：智力50为基准，每多5点+1%
         * 消耗：每月3000灵石
         * 
         * 计算公式：
         * 实际成功率加成 = 10% × (1 + 副宗主加成)
         * 
         * 示例（副宗主智力60）：
         * 副宗主加成 = (60-50)/5 × 1% = 2%
         * 实际成功率加成 = 10% × 1.02 = 10.2%
         */
        var policySuccessBonus = 0.0
        if (_gameData.value.sectPolicies.alchemyIncentive) {
            val baseBonus = GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_BASE_EFFECT
            val viceSectMasterBonus = calculateViceSectMasterPolicyBonus()
            policySuccessBonus = baseBonus * (1 + viceSectMasterBonus)
        }
        
        val totalSuccessBonus = discipleSuccessBonus + elderBonus.successBonus + policySuccessBonus
        val finalSuccessRate = recipe.successRate + totalSuccessBonus

        val success = Random.nextDouble() < finalSuccessRate

        if (success) {
            // 检查是否已有相同丹药，有则堆叠
            val existingPill = _pills.value.find { 
                it.name == recipe.name && 
                it.rarity == recipe.rarity &&
                it.category == recipe.category
            }
            
            if (existingPill != null) {
                // 堆叠到已有丹药
                _pills.value = _pills.value.map {
                    if (it.id == existingPill.id) it.copy(quantity = it.quantity + 1) else it
                }
            } else {
                // 创建新丹药
                val pill = Pill(
                    id = UUID.randomUUID().toString(),
                    name = recipe.name,
                    rarity = recipe.rarity,
                    description = recipe.description,
                    category = recipe.category,
                    breakthroughChance = recipe.breakthroughChance,
                    targetRealm = recipe.targetRealm,
                    cultivationSpeed = recipe.cultivationSpeed,
                    duration = recipe.effectDuration,
                    cultivationPercent = recipe.cultivationPercent,
                    skillExpPercent = recipe.skillExpPercent,
                    physicalAttackPercent = recipe.physicalAttackPercent,
                    magicAttackPercent = recipe.magicAttackPercent,
                    physicalDefensePercent = recipe.physicalDefensePercent,
                    magicDefensePercent = recipe.magicDefensePercent,
                    hpPercent = recipe.hpPercent,
                    mpPercent = recipe.mpPercent,
                    speedPercent = recipe.speedPercent,
                    healMaxHpPercent = recipe.healMaxHpPercent,
                    healPercent = recipe.healPercent,
                    heal = recipe.heal,
                    battleCount = recipe.battleCount,
                    extendLife = recipe.extendLife,
                    mpRecoverMaxMpPercent = recipe.mpRecoverMaxMpPercent
                )
                addPillToWarehouse(pill)
            }

            // 显示成功率加成信息
            val bonusText = if (totalSuccessBonus > 0) " (成功率+${GameUtils.formatPercent(totalSuccessBonus)})" else ""
            addEvent("炼丹成功！获得${recipe.name}${bonusText}", EventType.SUCCESS)
        } else {
            addEvent("炼丹失败，${recipe.name}化为灰烬", EventType.WARNING)
        }

        // 自动续炼：检查材料是否足够
        autoRestartAlchemy(slot, recipe, elderBonus)
    }

    /**
     * 自动续炼丹药
     * 炼丹使用草药（herbs）作为材料
     */
    private fun autoRestartAlchemy(slot: BuildingSlot, recipe: PillRecipeDatabase.PillRecipe, elderBonus: ElderBonusResult) {
        // 检查草药是否足够（同时匹配名称和品阶）
        val hasHerbs = recipe.materials.all { (herbId, requiredAmount) ->
            val herbData = HerbDatabase.getHerbById(herbId)
            val herbName = herbData?.name
            val herbRarity = herbData?.rarity ?: 1
            val herb = _herbs.value.find { it.name == herbName && it.rarity == herbRarity }
            herb != null && herb.quantity >= requiredAmount
        }

        if (hasHerbs) {
            // 扣除草药（同时匹配名称和品阶）
            recipe.materials.forEach { (herbId, requiredAmount) ->
                val herbData = HerbDatabase.getHerbById(herbId)
                val herbName = herbData?.name
                val herbRarity = herbData?.rarity ?: 1
                _herbs.value = _herbs.value.map { herb ->
                    if (herb.name == herbName && herb.rarity == herbRarity) {
                        herb.copy(quantity = herb.quantity - requiredAmount)
                    } else {
                        herb
                    }
                }.filter { it.quantity > 0 }
            }

            // 计算实际耗时（已在 calculateWorkDurationWithAllDisciples 中包含长老/亲传弟子速度加成）
            val actualDuration = calculateWorkDurationWithAllDisciples(recipe.duration, "alchemyRoom")

            // 更新槽位状态，继续炼制同一种丹药
            val data = _gameData.value
            val updatedSlot = slot.copy(
                status = SlotStatus.WORKING,
                startYear = data.gameYear,
                startMonth = data.gameMonth,
                duration = actualDuration
            )

            _buildingSlots.value = _buildingSlots.value.map {
                if (it.id == slot.id) updatedSlot else it
            }

            // 同步更新 _alchemySlots
            val newAlchemySlot = AlchemySlot(
                slotIndex = slot.slotIndex,
                recipeId = recipe.id,
                recipeName = recipe.name,
                pillName = recipe.name,
                pillRarity = recipe.rarity,
                startYear = data.gameYear,
                startMonth = data.gameMonth,
                duration = actualDuration,
                status = AlchemySlotStatus.WORKING,
                successRate = recipe.successRate,
                requiredMaterials = recipe.materials
            )
            val currentAlchemySlots = _alchemySlots.value
            if (slot.slotIndex < currentAlchemySlots.size) {
                _alchemySlots.value = currentAlchemySlots.mapIndexed { index, s -> if (index == slot.slotIndex) newAlchemySlot else s }
            } else {
                val filledSlots = currentAlchemySlots.toMutableList()
                while (filledSlots.size < slot.slotIndex) {
                    filledSlots.add(AlchemySlot(slotIndex = filledSlots.size))
                }
                filledSlots.add(newAlchemySlot)
                _alchemySlots.value = filledSlots
            }

            val bonusPercent = ((recipe.duration - actualDuration) * 100 / recipe.duration)
            val speedText = if (bonusPercent > 0) "(加速${bonusPercent}%)" else ""
            addEvent("自动续炼：开始炼制${recipe.name}${speedText}", EventType.INFO)
            syncListedItemsWithInventory()
        } else {
            // 材料不足，将槽位重置为空闲状态
            val idleSlot = slot.copy(
                status = SlotStatus.IDLE,
                recipeId = null,
                startYear = 0,
                startMonth = 0,
                duration = 0
            )
            _buildingSlots.value = _buildingSlots.value.map {
                if (it.id == slot.id) idleSlot else it
            }

            // 同步更新 _alchemySlots 为空闲状态
            val currentAlchemySlots = _alchemySlots.value
            if (slot.slotIndex < currentAlchemySlots.size) {
                _alchemySlots.value = currentAlchemySlots.mapIndexed { index, s ->
                    if (index == slot.slotIndex) {
                        AlchemySlot(
                            slotIndex = slot.slotIndex,
                            status = AlchemySlotStatus.IDLE
                        )
                    } else {
                        s
                    }
                }
            } else {
                val filledSlots = currentAlchemySlots.toMutableList()
                while (filledSlots.size < slot.slotIndex) {
                    filledSlots.add(AlchemySlot(slotIndex = filledSlots.size))
                }
                filledSlots.add(AlchemySlot(
                    slotIndex = slot.slotIndex,
                    status = AlchemySlotStatus.IDLE
                ))
                _alchemySlots.value = filledSlots
            }

            addEvent("草药不足，无法自动续炼${recipe.name}", EventType.WARNING)
        }
    }
    
    private fun completeForging(slot: BuildingSlot) {
        val recipeId = slot.recipeId ?: return
        val recipe = ForgeRecipeDatabase.getRecipeById(recipeId) ?: return

        // 计算长老和亲传弟子的加成
        val elderBonus = calculateElderAndDisciplesBonus("forge")

        // 计算成功率加成
        val disciple = _disciples.value.find { it.id == slot.discipleId }
        val discipleSuccessBonus = calculateSuccessRateBonus(disciple, "forge")
        
        /**
         * 锻造激励政策效果计算
         * 
         * 基础效果：锻造成功率+10%
         * 副宗主加成：智力50为基准，每多5点+1%
         * 消耗：每月3000灵石
         * 
         * 计算公式：
         * 实际成功率加成 = 10% × (1 + 副宗主加成)
         * 
         * 示例（副宗主智力55）：
         * 副宗主加成 = (55-50)/5 × 1% = 1%
         * 实际成功率加成 = 10% × 1.01 = 10.1%
         */
        var policySuccessBonus = 0.0
        if (_gameData.value.sectPolicies.forgeIncentive) {
            val baseBonus = GameConfig.PolicyConfig.FORGE_INCENTIVE_BASE_EFFECT
            val viceSectMasterBonus = calculateViceSectMasterPolicyBonus()
            policySuccessBonus = baseBonus * (1 + viceSectMasterBonus)
        }
        
        val totalSuccessBonus = discipleSuccessBonus + elderBonus.successBonus + policySuccessBonus
        val finalSuccessRate = recipe.successRate + totalSuccessBonus

        val success = Random.nextDouble() < finalSuccessRate

        if (success) {
            val equipment = createEquipmentFromRecipe(recipe)
            // 检查是否已存在相同 id 的装备，避免重复
            if (_equipment.value.none { it.id == equipment.id }) {
                _equipment.value = _equipment.value + equipment
            }

            // 显示成功率加成信息
            val bonusText = if (totalSuccessBonus > 0) " (成功率+${GameUtils.formatPercent(totalSuccessBonus)})" else ""
            addEvent("锻造成功！获得${recipe.name}${bonusText}", EventType.SUCCESS)
        } else {
            addEvent("锻造失败，${recipe.name}化为灰烬", EventType.WARNING)
        }

        // 自动续炼：检查材料是否足够
        autoRestartForging(slot, recipe, elderBonus)
    }

    /**
     * 自动续炼装备
     * 炼器使用妖兽材料（materials）
     */
    private fun autoRestartForging(slot: BuildingSlot, recipe: ForgeRecipeDatabase.ForgeRecipe, elderBonus: ElderBonusResult) {
        // 检查材料是否足够（同时匹配名称和品阶）
        val hasMaterials = recipe.materials.all { (materialId, requiredAmount) ->
            val beastMaterial = BeastMaterialDatabase.getMaterialById(materialId)
            val materialName = beastMaterial?.name
            val materialRarity = beastMaterial?.rarity ?: 1
            val material = _materials.value.find { it.name == materialName && it.rarity == materialRarity }
            material != null && material.quantity >= requiredAmount
        }

        if (hasMaterials) {
            // 扣除材料（同时匹配名称和品阶）
            recipe.materials.forEach { (materialId, requiredAmount) ->
                val beastMaterial = BeastMaterialDatabase.getMaterialById(materialId)
                val materialName = beastMaterial?.name
                val materialRarity = beastMaterial?.rarity ?: 1
                _materials.value = _materials.value.map { material ->
                    if (material.name == materialName && material.rarity == materialRarity) {
                        material.copy(quantity = material.quantity - requiredAmount)
                    } else {
                        material
                    }
                }.filter { it.quantity > 0 }
            }

            // 计算实际耗时（已在 calculateWorkDurationWithAllDisciples 中包含长老/亲传弟子速度加成）
            val actualDuration = calculateWorkDurationWithAllDisciples(recipe.duration, "forge")

            // 更新槽位状态，继续炼制同一种装备
            val data = _gameData.value
            val updatedSlot = slot.copy(
                status = SlotStatus.WORKING,
                startYear = data.gameYear,
                startMonth = data.gameMonth,
                duration = actualDuration
            )

            _buildingSlots.value = _buildingSlots.value.map {
                if (it.id == slot.id) updatedSlot else it
            }

            val bonusPercent = ((recipe.duration - actualDuration) * 100 / recipe.duration)
            val speedText = if (bonusPercent > 0) "(加速${bonusPercent}%)" else ""
            addEvent("自动续炼：开始锻造${recipe.name}${speedText}", EventType.INFO)
            syncListedItemsWithInventory()
        } else {
            // 材料不足，将槽位重置为空闲状态
            val idleSlot = slot.copy(
                status = SlotStatus.IDLE,
                recipeId = null,
                startYear = 0,
                startMonth = 0,
                duration = 0
            )
            _buildingSlots.value = _buildingSlots.value.map {
                if (it.id == slot.id) idleSlot else it
            }
            addEvent("材料不足，无法自动续炼${recipe.name}", EventType.WARNING)
        }
    }
    
    private fun createEquipmentFromRecipe(recipe: ForgeRecipeDatabase.ForgeRecipe): Equipment {
        val template = EquipmentDatabase.getTemplateByName(recipe.name)
        return if (template != null) {
            EquipmentDatabase.createFromTemplate(template)
        } else {
            Equipment(
                id = UUID.randomUUID().toString(),
                name = recipe.name,
                rarity = recipe.rarity,
                description = recipe.description,
                slot = recipe.type,
                minRealm = GameConfig.Realm.getMinRealmForRarity(recipe.rarity)
            )
        }
    }
    
    private fun createEquipmentFromMerchantItem(item: MerchantItem): Equipment {
        val template = EquipmentDatabase.getTemplateByName(item.name)
        return if (template != null) {
            EquipmentDatabase.createFromTemplate(template)
        } else {
            Equipment(
                id = UUID.randomUUID().toString(),
                name = item.name,
                rarity = item.rarity,
                description = item.description,
                slot = EquipmentSlot.values().random(),
                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity)
            )
        }
    }

    private fun createEquipmentFromStorageItem(item: StorageBagItem): Equipment {
        val template = EquipmentDatabase.getTemplateByName(item.name)
        return if (template != null) {
            EquipmentDatabase.createFromTemplate(template).copy(id = item.itemId)
        } else {
            Equipment(
                id = item.itemId,
                name = item.name,
                rarity = item.rarity,
                description = "",
                slot = EquipmentSlot.values().random(),
                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity)
            )
        }
    }

    private fun createManualFromMerchantItem(item: MerchantItem): Manual {
        val template = ManualDatabase.getByName(item.name)
        return if (template != null) {
            ManualDatabase.createFromTemplate(template)
        } else {
            Manual(
                id = UUID.randomUUID().toString(),
                name = item.name,
                rarity = item.rarity,
                description = item.description,
                type = ManualType.values().random(),
                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity)
            )
        }
    }

    private fun createPillFromMerchantItem(item: MerchantItem): Pill {
        val template = ItemDatabase.getPillByName(item.name)
        return if (template != null) {
            ItemDatabase.createPillFromTemplate(template)
        } else {
            Pill(
                id = UUID.randomUUID().toString(),
                name = item.name,
                rarity = item.rarity,
                description = item.description,
                category = PillCategory.CULTIVATION
            )
        }
    }

    private fun createManualFromStorageItem(item: StorageBagItem): Manual {
        val template = ManualDatabase.getByName(item.name)
        return if (template != null) {
            ManualDatabase.createFromTemplate(template).copy(id = item.itemId)
        } else {
            Manual(
                id = item.itemId,
                name = item.name,
                rarity = item.rarity,
                description = "",
                type = ManualType.values().random(),
                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity)
            )
        }
    }

    private fun createMaterialFromMerchantItem(item: MerchantItem): Material {
        val beastMaterial = BeastMaterialDatabase.getMaterialByName(item.name)
        return Material(
            id = UUID.randomUUID().toString(),
            name = item.name,
            category = beastMaterial?.materialCategory ?: MaterialCategory.BEAST_HIDE,
            rarity = item.rarity,
            quantity = 1,
            description = item.description
        )
    }

    private fun createHerbFromMerchantItem(item: MerchantItem): Herb {
        val herbData = HerbDatabase.getHerbByName(item.name)
        return Herb(
            id = UUID.randomUUID().toString(),
            name = item.name,
            rarity = item.rarity,
            quantity = 1,
            description = item.description,
            category = herbData?.category ?: "grass"
        )
    }

    private fun createSeedFromMerchantItem(item: MerchantItem): Seed {
        val herbData = HerbDatabase.getSeedByName(item.name)
        return Seed(
            id = UUID.randomUUID().toString(),
            name = item.name,
            rarity = item.rarity,
            quantity = 1,
            description = item.description,
            growTime = herbData?.growTime ?: 30,
            yield = herbData?.yield ?: 1
        )
    }

    private fun addMaterialToWarehouse(material: Material) {
        val existingMaterial = _materials.value.find { it.name == material.name && it.rarity == material.rarity }
        if (existingMaterial != null) {
            _materials.value = _materials.value.map {
                if (it.name == material.name && it.rarity == material.rarity) {
                    it.copy(quantity = it.quantity + material.quantity)
                } else {
                    it
                }
            }
        } else {
            _materials.value = _materials.value + material
        }
    }

    private fun addHerbToWarehouse(herb: Herb) {
        val existingHerb = _herbs.value.find { it.name == herb.name && it.rarity == herb.rarity }
        if (existingHerb != null) {
            _herbs.value = _herbs.value.map {
                if (it.name == herb.name && it.rarity == herb.rarity) {
                    it.copy(quantity = it.quantity + herb.quantity)
                } else {
                    it
                }
            }
        } else {
            _herbs.value = _herbs.value + herb
        }
    }

    private fun addPillToWarehouse(pill: Pill) {
        val existingPill = _pills.value.find { 
            it.name == pill.name && it.rarity == pill.rarity && it.category == pill.category 
        }
        if (existingPill != null) {
            _pills.value = _pills.value.map {
                if (it.id == existingPill.id) {
                    it.copy(quantity = it.quantity + pill.quantity)
                } else {
                    it
                }
            }
        } else {
            _pills.value = _pills.value + pill
        }
    }

    private fun addSeedToWarehouse(seed: Seed) {
        val existingSeed = _seeds.value.find { it.name == seed.name && it.rarity == seed.rarity }
        if (existingSeed != null) {
            _seeds.value = _seeds.value.map {
                if (it.name == seed.name && it.rarity == seed.rarity) {
                    it.copy(quantity = it.quantity + seed.quantity)
                } else {
                    it
                }
            }
        } else {
            _seeds.value = _seeds.value + seed
        }
    }

    private fun createPillFromStorageItem(item: StorageBagItem, quantity: Int = 1): Pill {
        val template = ItemDatabase.getPillByName(item.name)
        return if (template != null) {
            ItemDatabase.createPillFromTemplate(template, quantity).copy(id = item.itemId)
        } else {
            Pill(
                id = item.itemId,
                name = item.name,
                rarity = item.rarity,
                description = "",
                category = PillCategory.values().random(),
                quantity = quantity
            )
        }
    }

    private fun createEquipmentFromTradingItem(item: TradingItem): Equipment {
        val template = EquipmentDatabase.getTemplateByName(item.name)
        return if (template != null) {
            EquipmentDatabase.createFromTemplate(template).copy(id = item.itemId)
        } else {
            Equipment(
                id = item.itemId,
                name = item.name,
                rarity = item.rarity,
                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity)
            )
        }
    }

    private fun createManualFromTradingItem(item: TradingItem): Manual {
        val template = ManualDatabase.getByName(item.name)
        return if (template != null) {
            Manual(
                id = item.itemId,
                name = item.name,
                rarity = item.rarity,
                description = template.description,
                type = template.type,
                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity)
            )
        } else {
            Manual(
                id = item.itemId,
                name = item.name,
                rarity = item.rarity,
                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity)
            )
        }
    }

    private fun createPillFromTradingItem(item: TradingItem): Pill {
        val template = ItemDatabase.getPillByName(item.name)
        return if (template != null) {
            ItemDatabase.createPillFromTemplate(template, 1).copy(id = item.itemId)
        } else {
            Pill(
                id = item.itemId,
                name = item.name,
                rarity = item.rarity,
                description = "",
                category = PillCategory.values().random(),
                quantity = 1
            )
        }
    }

    private fun processExplorationTeams(year: Int, month: Int) {
        val data = _gameData.value
        val currentMonth = year * 12 + month

        val teamsToComplete = mutableListOf<ExplorationTeam>()
        val teamsToRemove = mutableListOf<ExplorationTeam>()

        _teams.value.forEach { team ->
            if (team.status == ExplorationStatus.EXPLORING) {
                // 检查队伍中是否有存活弟子
                val aliveMembers = team.memberIds.mapNotNull { id ->
                    _disciples.value.find { it.id == id }
                }.filter { it.isAlive }

                if (aliveMembers.isEmpty()) {
                    // 无存活弟子，移除队伍
                    teamsToRemove.add(team)
                    addEvent("探索队伍【${team.name}】全员阵亡，队伍已解散", EventType.DANGER)
                } else {
                    val startMonth = team.startYear * 12 + team.startMonth
                    val exploredMonths = currentMonth - startMonth

                    if (exploredMonths >= team.duration) {
                        teamsToComplete.add(team)
                    } else {
                        val isNewMonth = exploredMonths > (team.progress)
                        if (isNewMonth) {
                            val updatedTeam = team.copy(progress = exploredMonths)
                            _teams.value = _teams.value.map { if (it.id == team.id) updatedTeam else it }

                            grantMonthlyExplorationDrop(team, year, month)

                            if (Random.nextDouble() < 0.50) {
                                triggerBattleEvent(updatedTeam)
                            }
                        }
                    }
                }
            }
        }

        // 移除无存活弟子的队伍，并移除死亡弟子
        teamsToRemove.forEach { team ->
            // 收集死亡弟子ID
            val deadDiscipleIds = team.memberIds.filter { memberId ->
                val disciple = _disciples.value.find { it.id == memberId }
                disciple != null && !disciple.isAlive
            }
            // 清理死亡弟子的关联数据并移除
            deadDiscipleIds.forEach { discipleId ->
                clearDiscipleFromAllSlots(discipleId)
            }
            _disciples.value = _disciples.value.filter { it.id !in deadDiscipleIds }
            _teams.value = _teams.value.filter { it.id != team.id }
        }

        teamsToComplete.forEach { team ->
            completeExploration(team)
        }
    }

    /**
     * 计算队伍平均境界（包含小境界层数）
     * 使用通用工具函数
     */
    private fun calculateTeamAvgRealm(disciples: List<Disciple>): Double {
        return GameUtils.calculateTeamAverageRealm(
            disciples,
            realmExtractor = { it.realm },
            layerExtractor = { it.realmLayer }
        )
    }

    /**
     * 根据队伍平均境界获取奖励品阶范围
     * 炼气筑基：凡品
     * 金丹元婴：凡品 - 灵品
     * 化神：灵品 - 宝品
     * 炼虚：宝品 - 地品
     * 合体：宝品 - 地品
     * 大乘：宝品 - 地品
     * 渡劫：地品 - 天品
     * 仙人：地品 - 天品
     */
    private fun getRarityRangeByRealm(avgRealm: Double): Pair<Int, Int> {
        // 数值越小表示境界越高
        // 9=炼气期, 8=筑基期, 7=金丹期, 6=元婴期, 5=化神期
        // 4=炼虚期, 3=合体期, 2=大乘期, 1=渡劫期, 0=仙人
        return when {
            avgRealm >= 9.0 -> Pair(1, 1)   // 炼气期：凡品
            avgRealm >= 8.0 -> Pair(1, 1)   // 筑基期：凡品
            avgRealm >= 7.0 -> Pair(1, 2)   // 金丹期：凡品-灵品
            avgRealm >= 6.0 -> Pair(1, 2)   // 元婴期：凡品-灵品
            avgRealm >= 5.0 -> Pair(2, 3)   // 化神期：灵品-宝品
            avgRealm >= 4.0 -> Pair(3, 5)   // 炼虚期：宝品-地品
            avgRealm >= 3.0 -> Pair(3, 5)   // 合体期：宝品-地品
            avgRealm >= 2.0 -> Pair(3, 5)   // 大乘期：宝品-地品
            avgRealm >= 1.0 -> Pair(5, 6)   // 渡劫期：地品-天品
            else -> Pair(5, 6)              // 仙人境界：地品-天品
        }
    }

    private data class DropQuantityConfig(
        val itemTypeMin: Int,
        val itemTypeMax: Int,
        val materialMin: Int,
        val materialMax: Int
    )

    private fun getDropQuantityByRarity(rarity: Int): DropQuantityConfig {
        return when (rarity) {
            1 -> DropQuantityConfig(1, 3, 5, 15)   // 凡品：功法/装备/丹药1-3种，草药/种子/材料5-15种
            2 -> DropQuantityConfig(1, 2, 5, 10)   // 灵品：功法/装备/丹药1-2种，草药/种子/材料5-10种
            3 -> DropQuantityConfig(1, 1, 1, 8)    // 宝品：功法/装备/丹药1种，草药/种子/材料1-8种
            4 -> DropQuantityConfig(1, 1, 1, 4)    // 玄品：功法/装备/丹药1种，草药/种子/材料1-4种
            5 -> DropQuantityConfig(1, 1, 1, 2)    // 地品：功法/装备/丹药1种，草药/种子/材料1-2种
            6 -> DropQuantityConfig(1, 1, 1, 1)    // 天品：功法/装备/丹药1种，草药/种子/材料1种
            else -> DropQuantityConfig(1, 1, 1, 1)
        }
    }

    private data class BeastMaterialDropConfig(
        val typeMin: Int,
        val typeMax: Int,
        val quantityMin: Int,
        val quantityMax: Int
    )

    private fun getBeastMaterialDropQuantityByRarity(rarity: Int): BeastMaterialDropConfig {
        return when (rarity) {
            1 -> BeastMaterialDropConfig(1, 3, 1, 7)   // 凡品：掉落1-3种，每种数量1-7
            2 -> BeastMaterialDropConfig(1, 3, 1, 5)   // 灵品：掉落1-3种，每种数量1-5
            3 -> BeastMaterialDropConfig(1, 3, 1, 3)   // 宝品：掉落1-3种，每种数量1-3
            4 -> BeastMaterialDropConfig(1, 3, 1, 2)   // 玄品：掉落1-3种，每种数量1-2
            5 -> BeastMaterialDropConfig(1, 3, 1, 1)   // 地品：掉落1-3种，每种数量1
            6 -> BeastMaterialDropConfig(1, 3, 1, 1)   // 天品：掉落1-3种，每种数量1
            else -> BeastMaterialDropConfig(1, 3, 1, 1)
        }
    }

    private fun grantMonthlyExplorationDrop(team: ExplorationTeam, year: Int, month: Int) {
        val aliveMembers = team.memberIds.mapNotNull { id ->
            _disciples.value.find { it.id == id }
        }.filter { it.isAlive }
        
        if (aliveMembers.isEmpty()) {
            addEvent("探索队伍【${team.name}】无存活弟子，无法获得奖励", EventType.WARNING)
            return
        }
        
        // 根据队伍平均境界确定奖励品阶范围
        val avgRealm = calculateTeamAvgRealm(aliveMembers)
        val (minRarity, maxRarity) = getRarityRangeByRealm(avgRealm)

        val data = _gameData.value
        val dungeonConfig = GameConfig.Dungeons.get(team.dungeon)
        val dungeonName = dungeonConfig?.name ?: team.dungeon
        val rewards = dungeonConfig?.rewards

        // 计算队伍幸运值加成（根据所有成员的rareDropRate天赋）
        val luckBonus = aliveMembers.sumOf { member ->
            val talentEffects = TalentDatabase.calculateTalentEffects(member.talentIds)
            (talentEffects["rareDropRate"] ?: 0.0) / aliveMembers.size
        }

        val drops = mutableListOf<String>()

        // 灵石掉落（使用数据库配置的范围）
        val spiritStoneRange = rewards?.spiritStones ?: listOf(100, 300)
        val spiritStones = Random.nextLong(spiritStoneRange[0].toLong(), spiritStoneRange[1].toLong() + 1)
        _gameData.value = _gameData.value.copy(spiritStones = _gameData.value.spiritStones + spiritStones)
        drops.add("灵石+$spiritStones")

        val actualRarity = Random.nextInt(minRarity, maxRarity + 1)
        val quantityConfig = getDropQuantityByRarity(actualRarity)

        // 装备掉落（使用数据库配置的概率）
        val equipmentChance = rewards?.equipmentChance ?: 0.07
        val equipmentDrops = mutableListOf<String>()
        if (Random.nextDouble() < (equipmentChance + luckBonus).coerceAtMost(0.5)) {
            val count = Random.nextInt(quantityConfig.itemTypeMin, quantityConfig.itemTypeMax + 1)
            repeat(count) {
                val equipment = generateExplorationEquipmentDropSilent(dungeonName, actualRarity, actualRarity)
                equipmentDrops.add(equipment.name)
            }
        }

        // 丹药掉落 18%（受幸运影响）
        val pillDrops = mutableListOf<String>()
        if (Random.nextDouble() < (0.18 + luckBonus).coerceAtMost(0.5)) {
            val count = Random.nextInt(quantityConfig.itemTypeMin, quantityConfig.itemTypeMax + 1)
            repeat(count) {
                val pill = generateExplorationPillDropSilent(dungeonName, actualRarity, actualRarity)
                pillDrops.add(pill.name)
            }
        }

        // 功法掉落（使用数据库配置的概率）
        val manualChance = rewards?.manualChance ?: 0.07
        val manualDrops = mutableListOf<String>()
        if (Random.nextDouble() < (manualChance + luckBonus).coerceAtMost(0.5)) {
            val count = Random.nextInt(quantityConfig.itemTypeMin, quantityConfig.itemTypeMax + 1)
            repeat(count) {
                val manual = generateExplorationManualDropSilent(dungeonName, actualRarity, actualRarity)
                manualDrops.add(manual.name)
            }
        }

        // 草药掉落 45%（受幸运影响）
        val herbDrops = mutableMapOf<String, Int>()
        if (Random.nextDouble() < (0.45 + luckBonus).coerceAtMost(0.8)) {
            val count = Random.nextInt(quantityConfig.materialMin, quantityConfig.materialMax + 1)
            repeat(count) {
                val herbName = generateExplorationHerbDropSilent(dungeonName, actualRarity, actualRarity)
                herbDrops[herbName] = herbDrops.getOrDefault(herbName, 0) + 1
            }
        }

        // 种子掉落 70%（受幸运影响）
        val seedDrops = mutableMapOf<String, Int>()
        if (Random.nextDouble() < (0.70 + luckBonus).coerceAtMost(0.95)) {
            val count = Random.nextInt(quantityConfig.materialMin, quantityConfig.materialMax + 1)
            repeat(count) {
                val seedName = generateExplorationSeedDropSilent(dungeonName, actualRarity, actualRarity)
                seedDrops[seedName] = seedDrops.getOrDefault(seedName, 0) + 1
            }
        }

        if (equipmentDrops.isNotEmpty()) {
            drops.add("装备:${equipmentDrops.joinToString(",")}")
        }
        if (pillDrops.isNotEmpty()) {
            drops.add("丹药:${pillDrops.joinToString(",")}")
        }
        if (manualDrops.isNotEmpty()) {
            drops.add("功法:${manualDrops.joinToString(",")}")
        }
        if (herbDrops.isNotEmpty()) {
            val herbStr = herbDrops.entries.joinToString(",") { "${it.key}x${it.value}" }
            drops.add("草药:$herbStr")
        }
        if (seedDrops.isNotEmpty()) {
            val seedStr = seedDrops.entries.joinToString(",") { "${it.key}x${it.value}" }
            drops.add("种子:$seedStr")
        }

        if (drops.isNotEmpty()) {
            addEvent("探索$dungeonName 获得：${drops.joinToString(" ")}", EventType.SUCCESS)
        }
    }

    private fun generateExplorationEquipmentDropSilent(dungeonName: String, minRarity: Int, maxRarity: Int): Equipment {
        // 从数据库模板生成装备，根据品阶范围
        val equipment = EquipmentDatabase.generateRandom(minRarity, maxRarity)
        // 检查是否已存在相同 id 的装备，避免重复
        if (_equipment.value.none { it.id == equipment.id }) {
            _equipment.value = _equipment.value + equipment
        }
        return equipment
    }

    private fun generateExplorationPillDropSilent(dungeonName: String, minRarity: Int, maxRarity: Int): Pill {
        val newPill = ItemDatabase.generateRandomPill(minRarity, maxRarity)
        addPillToWarehouse(newPill)
        return newPill
    }

    private fun generateExplorationManualDropSilent(dungeonName: String, minRarity: Int, maxRarity: Int): Manual {
        // 从数据库模板生成功法，根据品阶范围
        val manual = ManualDatabase.generateRandom(minRarity, maxRarity)
        // 检查是否已存在相同 id 的功法，避免重复
        if (_manuals.value.none { it.id == manual.id }) {
            _manuals.value = _manuals.value + manual
        }
        return manual
    }

    private fun generateExplorationHerbDropSilent(dungeonName: String, minRarity: Int, maxRarity: Int): String {
        val herbTemplate = HerbDatabase.generateRandomHerb(minRarity, maxRarity)
        val newHerb = Herb(
            id = UUID.randomUUID().toString(),
            name = herbTemplate.name,
            rarity = herbTemplate.rarity,
            quantity = 1,
            category = herbTemplate.category
        )
        addHerbToWarehouse(newHerb)
        return herbTemplate.name
    }

    private fun generateExplorationSeedDropSilent(dungeonName: String, minRarity: Int, maxRarity: Int): String {
        val seedTemplate = HerbDatabase.generateRandomSeed(minRarity, maxRarity)
        val newSeed = Seed(
            id = UUID.randomUUID().toString(),
            name = seedTemplate.name,
            rarity = seedTemplate.rarity,
            quantity = 1,
            growTime = seedTemplate.growTime,
            yield = seedTemplate.yield
        )
        addSeedToWarehouse(newSeed)
        return seedTemplate.name
    }

    private fun triggerBattleEvent(team: ExplorationTeam) {
        val currentDisciples = _disciples.value
        val currentEquipment = _equipment.value
        val currentManuals = _manuals.value
        val currentPills = _pills.value
        val currentMaterials = _materials.value
        val currentBattleLogs = _battleLogs.value
        val currentGameData = _gameData.value
        
        var disciples = team.memberIds.mapNotNull { id ->
            currentDisciples.find { it.id == id }
        }.filter { it.isAlive }

        if (disciples.isEmpty()) return

        val pillUpdates = mutableListOf<Pill>()
        val disciplesAfterPills = disciples.map { disciple ->
            var updated = disciple
            val battlePillResult = autoUseBattlePillsInTransaction(disciple, currentPills, pillUpdates)
            updated = battlePillResult.disciple
            pillUpdates.clear()
            pillUpdates.addAll(battlePillResult.pillUpdates)
            val healingPillResult = autoUseHealingPillsInTransaction(updated, currentPills, pillUpdates)
            updated = healingPillResult.disciple
            pillUpdates.clear()
            pillUpdates.addAll(healingPillResult.pillUpdates)
            updated
        }
        disciples = disciplesAfterPills

        val equipmentMap = currentEquipment.associateBy { it.id }
        val manualMap = currentManuals.associateBy { it.id }

        val beastCount = Random.nextInt(1, 11)

        val dungeonConfig = GameConfig.Dungeons.get(team.dungeon)
        val beastType = dungeonConfig?.beastType

        val battle = BattleSystem.createBattle(
            disciples, 
            equipmentMap, 
            manualMap, 
            0, 
            beastCount, 
            beastType,
            currentGameData.manualProficiencies.mapValues { (_, list) -> 
                list.associateBy { it.manualId } 
            }
        )
        val battleResult = BattleSystem.executeBattle(battle)
        
        val battleLog = BattleLog(
            id = UUID.randomUUID().toString(),
            year = currentGameData.gameYear,
            month = currentGameData.gameMonth,
            dungeonName = team.dungeonName,
            teamId = team.id,
            teamMembers = battleResult.log.teamMembers.map { member ->
                val disciple = disciples.find { it.id == member.id }
                BattleLogMember(
                    id = member.id,
                    name = member.name,
                    realm = disciple?.realm ?: 9,
                    realmName = GameConfig.Realm.getName(disciple?.realm ?: 9),
                    realmLayer = disciple?.realmLayer ?: 0,
                    hp = member.hp,
                    maxHp = member.maxHp,
                    isAlive = member.isAlive
                )
            },
            enemies = battleResult.log.enemies.map { enemy ->
                BattleLogEnemy(
                    name = enemy.name,
                    realm = enemy.realm,
                    realmName = enemy.realmName,
                    realmLayer = enemy.realmLayer
                )
            },
            rounds = battleResult.log.rounds.map { round ->
                BattleLogRound(
                    roundNumber = round.roundNumber,
                    actions = round.actions.map { action ->
                        BattleLogAction(
                            type = action.type,
                            attacker = action.attacker,
                            attackerType = action.attackerType,
                            target = action.target,
                            damage = action.damage,
                            damageType = action.damageType,
                            isCrit = action.isCrit,
                            isKill = action.isKill,
                            message = action.message
                        )
                    }
                )
            },
            turns = battleResult.battle.turn,
            result = if (battleResult.battle.winner?.name?.lowercase() == "team") BattleResult.WIN else BattleResult.LOSE,
            battleResult = BattleLogResult(
                winner = battleResult.battle.winner?.name?.lowercase() ?: "",
                turns = battleResult.battle.turn,
                teamCasualties = battleResult.log.teamMembers.count { !it.isAlive },
                beastsDefeated = battleResult.battle.beasts.count { it.isDead }
            )
        )
        
        val deadMemberIds = battleResult.log.teamMembers.filter { !it.isAlive }.map { it.id }.toSet()
        val teamMemberHpMap = battleResult.log.teamMembers.associateBy { it.id }.mapValues { it.value.hp }
        val updatedDisciples = currentDisciples.map { disciple ->
            if (deadMemberIds.contains(disciple.id)) {
                disciple.copy(isAlive = false)
            } else {
                val finalHp = teamMemberHpMap[disciple.id] ?: disciple.maxHp
                val updatedStatusData = disciple.statusData + ("currentHp" to finalHp.toString())
                disciple.copy(statusData = updatedStatusData)
            }
        }
        
        val equipmentUpdates = processEquipmentNurtureAfterBattleInTransaction(
            disciples, 
            currentEquipment, 
            if (battleResult.victory) BattleResult.WIN else BattleResult.LOSE, 
            beastCount
        )
        
        val disciplesAfterClear = clearBattlePillEffectsInTransaction(updatedDisciples, disciples)

        val eventsToAdd = mutableListOf<Pair<String, EventType>>()
        deadMemberIds.forEach { memberId ->
            val disciple = currentDisciples.find { it.id == memberId }
            if (disciple != null) {
                eventsToAdd.add("${disciple.name}在探索中阵亡" to EventType.DANGER)
            }
        }

        var newSpiritStones = currentGameData.spiritStones
        var newMaterials = currentMaterials.toMutableList()
        
        if (battleResult.victory) {
            val spiritStones = Random.nextLong(200, 800)
            newSpiritStones += spiritStones
            
            val aliveMembers = battleResult.log.teamMembers.filter { it.isAlive }
            val disciplesWithBonus = applyBattleVictoryBonusesInTransaction(
                disciplesAfterClear,
                aliveMembers.map { it.id },
                addSoulPower = true
            )
            
            val materialDrops = mutableMapOf<String, Int>()
            if (beastType != null) {
                val avgRealm = if (aliveMembers.isNotEmpty()) aliveMembers.map { it.realm }.average() else 9.0
                val (minRarity, maxRarity) = getRarityRangeByRealm(avgRealm)
                val tier = Random.nextInt(minRarity, maxRarity + 1)
                
                val luckBonus = aliveMembers.sumOf { member ->
                    val disciple = disciplesWithBonus.find { it.id == member.id } ?: return@sumOf 0.0
                    val effects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
                    (effects["rareDropRate"] ?: 0.0) +
                        (effects["cultivationSpeed"] ?: 0.0) * 0.20 +
                        (effects["comprehensionFlat"] ?: 0.0) * 0.001
                }
                val luckMultiplier = 1.0 + luckBonus
                
                val materialConfig = getBeastMaterialDropQuantityByRarity(tier)
                val materialCount = Random.nextInt(materialConfig.typeMin, materialConfig.typeMax + 1)
                repeat(materialCount) {
                    val beastMaterial = BeastMaterialDatabase.getRandomMaterialByBeastType(beastType, tier, luckMultiplier)
                    if (beastMaterial != null) {
                        val quantity = Random.nextInt(materialConfig.quantityMin, materialConfig.quantityMax + 1)
                        val material = Material(
                            id = UUID.randomUUID().toString(),
                            name = beastMaterial.name,
                            category = beastMaterial.materialCategory,
                            rarity = beastMaterial.rarity,
                            quantity = quantity,
                            description = beastMaterial.description
                        )
                        newMaterials = addMaterialToWarehouseInTransaction(newMaterials, material)
                        materialDrops[beastMaterial.name] = materialDrops.getOrDefault(beastMaterial.name, 0) + quantity
                    }
                }
            }
            
            val dropMsg = if (materialDrops.isNotEmpty()) "，获得材料:${materialDrops.entries.joinToString(",") { "${it.key}x${it.value}" }}" else ""
            eventsToAdd.add("探索队伍【${team.name}】战斗胜利，获得灵石+$spiritStones，存活弟子神魂+1$dropMsg" to EventType.SUCCESS)
            
            _disciples.value = disciplesWithBonus
        } else {
            _disciples.value = disciplesAfterClear
            eventsToAdd.add("探索队伍【${team.name}】战斗失败" to EventType.WARNING)
        }
        
        _gameData.value = currentGameData.copy(spiritStones = newSpiritStones)
        _battleLogs.value = listOf(battleLog) + currentBattleLogs.take(49)
        _equipment.value = currentEquipment.map { equip ->
            equipmentUpdates.find { it.id == equip.id } ?: equip
        }
        _materials.value = newMaterials
        _pills.value = currentPills.map { pill ->
            pillUpdates.find { it.id == pill.id } ?: pill
        }.filter { it.quantity > 0 }
        
        eventsToAdd.forEach { (message, type) ->
            addEvent(message, type)
        }
    }

    private data class PillUseResult(val disciple: Disciple, val pillUpdates: List<Pill>)

    private fun autoUseBattlePillsInTransaction(
        disciple: Disciple, 
        currentPills: List<Pill>, 
        existingUpdates: List<Pill>
    ): PillUseResult {
        if (disciple.storageBagItems.isEmpty()) return PillUseResult(disciple, existingUpdates)

        // 查找具有战斗属性的丹药
        val battlePillItem = disciple.storageBagItems.find { item ->
            item.itemType == "pill" && item.effect?.let { effect ->
                effect.battleCount > 0 ||
                effect.physicalAttackPercent > 0 ||
                effect.magicAttackPercent > 0 ||
                effect.physicalDefensePercent > 0 ||
                effect.magicDefensePercent > 0 ||
                effect.speedPercent > 0
            } == true
        }

        if (battlePillItem != null) {
            val effect = battlePillItem.effect ?: return PillUseResult(disciple, existingUpdates)
            
            // 更新弟子战斗属性加成
            val updatedDisciple = disciple.copy(
                pillPhysicalAttackBonus = disciple.pillPhysicalAttackBonus + effect.physicalAttackPercent,
                pillMagicAttackBonus = disciple.pillMagicAttackBonus + effect.magicAttackPercent,
                pillPhysicalDefenseBonus = disciple.pillPhysicalDefenseBonus + effect.physicalDefensePercent,
                pillMagicDefenseBonus = disciple.pillMagicDefenseBonus + effect.magicDefensePercent,
                pillSpeedBonus = disciple.pillSpeedBonus + effect.speedPercent,
                pillEffectDuration = disciple.pillEffectDuration + effect.battleCount
            )

            // 更新储物袋道具数量或移除道具
            val updatedItems = updatedDisciple.storageBagItems.toMutableList()
            val index = updatedItems.indexOf(battlePillItem)
            if (index >= 0) {
                if (battlePillItem.quantity > 1) {
                    updatedItems[index] = battlePillItem.copy(quantity = battlePillItem.quantity - 1)
                } else {
                    updatedItems.removeAt(index)
                }
            }

            // 更新宗门丹药库存
            val pillInStock = currentPills.find { it.id == battlePillItem.itemId }
            val newPillUpdates = if (pillInStock != null) {
                val existingUpdate = existingUpdates.find { it.id == pillInStock.id }
                if (existingUpdate != null) {
                    // 已有更新记录，减少数量
                    existingUpdates.map { 
                        if (it.id == pillInStock.id) it.copy(quantity = it.quantity - 1) else it 
                    }
                } else {
                    // 新增更新记录
                    existingUpdates + pillInStock.copy(quantity = pillInStock.quantity - 1)
                }
            } else {
                existingUpdates
            }

            addEvent("${disciple.name} 自动使用了 ${battlePillItem.name}，战斗力提升", EventType.INFO)
            return PillUseResult(updatedDisciple.copy(storageBagItems = updatedItems), newPillUpdates)
        }

        return PillUseResult(disciple, existingUpdates)
    }

    private fun autoUseHealingPillsInTransaction(
        disciple: Disciple, 
        currentPills: List<Pill>, 
        existingUpdates: List<Pill>
    ): PillUseResult {
        // 检查弟子储物袋是否为空
        if (disciple.storageBagItems.isEmpty()) {
            return PillUseResult(disciple, existingUpdates)
        }

        // 从储物袋中查找恢复类丹药
        val healingPillItem = disciple.storageBagItems.find { item ->
            item.itemType == "pill" && item.effect?.let { effect ->
                effect.heal > 0 || effect.healPercent > 0 || 
                effect.hpPercent > 0 || effect.mpPercent > 0 || effect.mpRecoverPercent > 0
            } == true
        }

        // 如果没有找到恢复丹药，直接返回
        if (healingPillItem == null) {
            return PillUseResult(disciple, existingUpdates)
        }

        // 从 currentPills 中查找对应的 Pill 对象
        val pill = currentPills.find { it.id == healingPillItem.itemId }
            ?: existingUpdates.find { it.id == healingPillItem.itemId }

        if (pill == null) {
            return PillUseResult(disciple, existingUpdates)
        }

        // 更新储物袋道具数量
        val updatedItems = disciple.storageBagItems.toMutableList()
        val index = updatedItems.indexOf(healingPillItem)
        if (index >= 0) {
            if (healingPillItem.quantity > 1) {
                updatedItems[index] = healingPillItem.copy(quantity = healingPillItem.quantity - 1)
            } else {
                updatedItems.removeAt(index)
            }
        }

        // 更新丹药列表
        val updatedPills = existingUpdates.toMutableList()
        val existingPillIndex = updatedPills.indexOfFirst { it.id == pill.id }
        if (existingPillIndex >= 0) {
            val existingPill = updatedPills[existingPillIndex]
            if (existingPill.quantity > 1) {
                updatedPills[existingPillIndex] = existingPill.copy(quantity = existingPill.quantity - 1)
            } else {
                updatedPills.removeAt(existingPillIndex)
            }
        } else {
            // 从 currentPills 中查找并添加到更新列表
            val currentPill = currentPills.find { it.id == pill.id }
            if (currentPill != null) {
                if (currentPill.quantity > 1) {
                    updatedPills.add(currentPill.copy(quantity = currentPill.quantity - 1))
                }
                // 如果数量为1，不添加到更新列表，意味着该丹药将被删除
            }
        }

        // 添加事件日志
        addEvent("${disciple.name} 自动使用了 ${healingPillItem.name}，恢复状态", EventType.INFO)

        // 返回更新后的结果
        return PillUseResult(
            disciple = disciple.copy(storageBagItems = updatedItems),
            pillUpdates = updatedPills
        )
    }

    private fun processEquipmentNurtureAfterBattleInTransaction(
        disciples: List<Disciple>,
        currentEquipment: List<Equipment>,
        result: BattleResult,
        enemyCount: Int
    ): List<Equipment> {
        val equipmentMap = currentEquipment.associateBy { it.id }
        val updatedEquipments = mutableListOf<Equipment>()

        disciples.forEach { disciple ->
            val equippedIds = listOfNotNull(
                disciple.weaponId,
                disciple.armorId,
                disciple.bootsId,
                disciple.accessoryId
            )

            equippedIds.forEach { equipId ->
                val equipment = equipmentMap[equipId] ?: return@forEach
                val expGain = EquipmentNurtureSystem.calculateExpGain(equipment, result == BattleResult.WIN)
                val nurtureResult = EquipmentNurtureSystem.updateNurtureExp(equipment, expGain)
                updatedEquipments.add(nurtureResult.equipment)
            }
        }

        return updatedEquipments
    }

    private fun clearBattlePillEffectsInTransaction(
        allDisciples: List<Disciple>,
        battleDisciples: List<Disciple>
    ): List<Disciple> {
        return allDisciples.map { disciple ->
            if (battleDisciples.any { it.id == disciple.id } && disciple.pillEffectDuration > 0) {
                disciple.copy(
                    pillEffectDuration = 0,
                    pillPhysicalAttackBonus = 0.0,
                    pillMagicAttackBonus = 0.0,
                    pillPhysicalDefenseBonus = 0.0,
                    pillMagicDefenseBonus = 0.0,
                    pillHpBonus = 0.0,
                    pillMpBonus = 0.0,
                    pillSpeedBonus = 0.0
                )
            } else {
                disciple
            }
        }
    }

    private fun applyBattleVictoryBonusesInTransaction(
        allDisciples: List<Disciple>,
        memberIds: List<String>,
        addSoulPower: Boolean
    ): List<Disciple> {
        return allDisciples.map { disciple ->
            if (!memberIds.contains(disciple.id) || !disciple.isAlive) {
                disciple
            } else {
                var updated = disciple
                if (addSoulPower) {
                    updated = updated.copy(soulPower = updated.soulPower + 1)
                }
                updated = applyBattleWinGrowth(updated)
                updated
            }
        }
    }

    private fun addMaterialToWarehouseInTransaction(materials: MutableList<Material>, newMaterial: Material): MutableList<Material> {
        val existingIndex = materials.indexOfFirst { 
            it.name == newMaterial.name && it.rarity == newMaterial.rarity 
        }
        if (existingIndex >= 0) {
            materials[existingIndex] = materials[existingIndex].copy(
                quantity = materials[existingIndex].quantity + newMaterial.quantity
            )
        } else {
            materials.add(newMaterial)
        }
        return materials
    }
    
    private fun completeExploration(team: ExplorationTeam) {
        val currentDisciples = _disciples.value
        val currentTeams = _teams.value
        val currentGameData = _gameData.value
        
        val aliveMembers = team.memberIds.mapNotNull { id ->
            currentDisciples.find { it.id == id }
        }.filter { it.isAlive }
        
        // 收集死亡弟子ID
        val deadDiscipleIds = team.memberIds.filter { memberId ->
            val disciple = currentDisciples.find { it.id == memberId }
            disciple != null && !disciple.isAlive
        }
        
        // 清理死亡弟子的关联数据
        deadDiscipleIds.forEach { discipleId ->
            clearDiscipleFromAllSlots(discipleId)
        }

        val updatedDisciples = currentDisciples
            .map { disciple ->
                if (team.memberIds.contains(disciple.id) && disciple.isAlive) {
                    disciple.copy(status = DiscipleStatus.IDLE)
                } else {
                    disciple
                }
            }
            .filter { it.id !in deadDiscipleIds }

        val eventsToAdd = mutableListOf<Pair<String, EventType>>()
        
        if (aliveMembers.isNotEmpty()) {
            val dropsResult = grantExplorationCompletionDropsInTransaction(
                team, 
                aliveMembers, 
                currentGameData,
                updatedDisciples
            )
            _disciples.value = dropsResult.updatedDisciples.filter { it.id !in deadDiscipleIds }
            _equipment.value = dropsResult.updatedEquipment
            _manuals.value = dropsResult.updatedManuals
            _pills.value = dropsResult.updatedPills
            _herbs.value = dropsResult.updatedHerbs
            _seeds.value = dropsResult.updatedSeeds
            _gameData.value = dropsResult.updatedGameData
            eventsToAdd.addAll(dropsResult.events)
            eventsToAdd.add("探索队伍【${team.name}】返回宗门" to EventType.SUCCESS)
        } else {
            _disciples.value = updatedDisciples
            eventsToAdd.add("探索队伍【${team.name}】全军覆没，无人生还" to EventType.DANGER)
        }

        _teams.value = currentTeams.filter { it.id != team.id }
        
        eventsToAdd.forEach { (message, type) ->
            addEvent(message, type)
        }
    }

    private data class ExplorationDropsResult(
        val updatedDisciples: List<Disciple>,
        val updatedEquipment: List<Equipment>,
        val updatedManuals: List<Manual>,
        val updatedPills: List<Pill>,
        val updatedHerbs: List<Herb>,
        val updatedSeeds: List<Seed>,
        val updatedGameData: GameData,
        val events: List<Pair<String, EventType>>
    )

    private fun grantExplorationCompletionDropsInTransaction(
        team: ExplorationTeam,
        aliveMembers: List<Disciple>,
        currentGameData: GameData,
        currentDisciples: List<Disciple>
    ): ExplorationDropsResult {
        val events = mutableListOf<Pair<String, EventType>>()
        var updatedGameData = currentGameData
        var updatedEquipment = _equipment.value.toMutableList()
        var updatedManuals = _manuals.value.toMutableList()
        var updatedPills = _pills.value.toMutableList()
        var updatedHerbs = _herbs.value.toMutableList()
        var updatedSeeds = _seeds.value.toMutableList()
        
        val avgRealm = calculateTeamAvgRealm(aliveMembers)
        val (minRarity, maxRarity) = getRarityRangeByRealm(avgRealm)
        
        val dungeonConfig = GameConfig.Dungeons.get(team.dungeon)
        val dungeonName = dungeonConfig?.name ?: team.dungeon

        val drops = mutableListOf<String>()

        if (Random.nextDouble() < 0.85) {
            val spiritStones = Random.nextLong(1000, 3001)
            updatedGameData = updatedGameData.copy(spiritStones = updatedGameData.spiritStones + spiritStones)
            drops.add("灵石+$spiritStones")
        }

        val actualRarity = Random.nextInt(minRarity, maxRarity + 1)
        val quantityConfig = getDropQuantityByRarity(actualRarity)

        val equipmentDrops = mutableListOf<String>()
        if (Random.nextDouble() < 0.13) {
            val count = Random.nextInt(quantityConfig.itemTypeMin, quantityConfig.itemTypeMax + 1)
            repeat(count) {
                val equipment = generateExplorationEquipmentDropInTransaction(updatedEquipment, dungeonName, actualRarity, actualRarity)
                updatedEquipment.add(equipment)
                equipmentDrops.add(equipment.name)
            }
        }

        val pillDrops = mutableListOf<String>()
        if (Random.nextDouble() < 0.18) {
            val count = Random.nextInt(quantityConfig.itemTypeMin, quantityConfig.itemTypeMax + 1)
            repeat(count) {
                val pill = generateExplorationPillDropInTransaction(updatedPills, dungeonName, actualRarity, actualRarity)
                updatedPills = pill.first
                pillDrops.add(pill.second.name)
            }
        }

        val manualDrops = mutableListOf<String>()
        if (Random.nextDouble() < 0.13) {
            val count = Random.nextInt(quantityConfig.itemTypeMin, quantityConfig.itemTypeMax + 1)
            repeat(count) {
                val manual = generateExplorationManualDropInTransaction(updatedManuals, dungeonName, actualRarity, actualRarity)
                updatedManuals.add(manual)
                manualDrops.add(manual.name)
            }
        }

        val herbDrops = mutableMapOf<String, Int>()
        if (Random.nextDouble() < 0.45) {
            val count = Random.nextInt(quantityConfig.materialMin, quantityConfig.materialMax + 1)
            repeat(count) {
                val result = generateExplorationHerbDropInTransaction(updatedHerbs, dungeonName, actualRarity, actualRarity)
                updatedHerbs = result.first
                herbDrops[result.second] = herbDrops.getOrDefault(result.second, 0) + 1
            }
        }

        val seedDrops = mutableMapOf<String, Int>()
        if (Random.nextDouble() < 0.70) {
            val count = Random.nextInt(quantityConfig.materialMin, quantityConfig.materialMax + 1)
            repeat(count) {
                val result = generateExplorationSeedDropInTransaction(updatedSeeds, dungeonName, actualRarity, actualRarity)
                updatedSeeds = result.first
                seedDrops[result.second] = seedDrops.getOrDefault(result.second, 0) + 1
            }
        }

        if (equipmentDrops.isNotEmpty()) {
            drops.add("装备:${equipmentDrops.joinToString(",")}")
        }
        if (pillDrops.isNotEmpty()) {
            drops.add("丹药:${pillDrops.joinToString(",")}")
        }
        if (manualDrops.isNotEmpty()) {
            drops.add("功法:${manualDrops.joinToString(",")}")
        }
        if (herbDrops.isNotEmpty()) {
            val herbStr = herbDrops.entries.joinToString(",") { "${it.key}x${it.value}" }
            drops.add("草药:$herbStr")
        }
        if (seedDrops.isNotEmpty()) {
            val seedStr = seedDrops.entries.joinToString(",") { "${it.key}x${it.value}" }
            drops.add("种子:$seedStr")
        }

        if (drops.isNotEmpty()) {
            events.add("探索$dungeonName 完成，获得：${drops.joinToString(" ")}" to EventType.SUCCESS)
        }

        return ExplorationDropsResult(
            updatedDisciples = currentDisciples,
            updatedEquipment = updatedEquipment,
            updatedManuals = updatedManuals,
            updatedPills = updatedPills,
            updatedHerbs = updatedHerbs,
            updatedSeeds = updatedSeeds,
            updatedGameData = updatedGameData,
            events = events
        )
    }

    private fun generateExplorationEquipmentDropInTransaction(
        currentEquipment: MutableList<Equipment>,
        dungeonName: String,
        minRarity: Int,
        maxRarity: Int
    ): Equipment {
        val equipment = EquipmentDatabase.generateRandom(minRarity, maxRarity)
        return equipment
    }

    private fun generateExplorationPillDropInTransaction(
        currentPills: MutableList<Pill>,
        dungeonName: String,
        minRarity: Int,
        maxRarity: Int
    ): Pair<MutableList<Pill>, Pill> {
        val newPill = ItemDatabase.generateRandomPill(minRarity, maxRarity)
        
        val existingPill = currentPills.find { 
            it.name == newPill.name && 
            it.rarity == newPill.rarity &&
            it.category == newPill.category
        }
        
        return if (existingPill != null) {
            currentPills[currentPills.indexOf(existingPill)] = existingPill.copy(quantity = existingPill.quantity + 1)
            currentPills to existingPill
        } else {
            currentPills.add(newPill)
            currentPills to newPill
        }
    }

    private fun generateExplorationManualDropInTransaction(
        currentManuals: MutableList<Manual>,
        dungeonName: String,
        minRarity: Int,
        maxRarity: Int
    ): Manual {
        return ManualDatabase.generateRandom(minRarity, maxRarity)
    }

    private fun generateExplorationHerbDropInTransaction(
        currentHerbs: MutableList<Herb>,
        dungeonName: String,
        minRarity: Int,
        maxRarity: Int
    ): Pair<MutableList<Herb>, String> {
        val herbTemplate = HerbDatabase.generateRandomHerb(minRarity, maxRarity)
        val existingHerb = currentHerbs.find { it.name == herbTemplate.name && it.rarity == herbTemplate.rarity }
        
        return if (existingHerb != null) {
            currentHerbs[currentHerbs.indexOf(existingHerb)] = existingHerb.copy(quantity = existingHerb.quantity + 1)
            currentHerbs to herbTemplate.name
        } else {
            val newHerb = Herb(
                id = UUID.randomUUID().toString(),
                name = herbTemplate.name,
                rarity = herbTemplate.rarity,
                quantity = 1,
                category = herbTemplate.category
            )
            currentHerbs.add(newHerb)
            currentHerbs to herbTemplate.name
        }
    }

    private fun generateExplorationSeedDropInTransaction(
        currentSeeds: MutableList<Seed>,
        dungeonName: String,
        minRarity: Int,
        maxRarity: Int
    ): Pair<MutableList<Seed>, String> {
        val seedTemplate = HerbDatabase.generateRandomSeed(minRarity, maxRarity)
        val existingSeed = currentSeeds.find { it.name == seedTemplate.name && it.rarity == seedTemplate.rarity }
        
        return if (existingSeed != null) {
            currentSeeds[currentSeeds.indexOf(existingSeed)] = existingSeed.copy(quantity = existingSeed.quantity + 1)
            currentSeeds to seedTemplate.name
        } else {
            val newSeed = Seed(
                id = UUID.randomUUID().toString(),
                name = seedTemplate.name,
                rarity = seedTemplate.rarity,
                quantity = 1,
                growTime = seedTemplate.growTime,
                yield = seedTemplate.yield
            )
            currentSeeds.add(newSeed)
            currentSeeds to seedTemplate.name
        }
    }
    
    private fun processAging(year: Int) {
        val deadDisciples = mutableListOf<Disciple>()
        
        val updatedDisciples = _disciples.value.map { disciple ->
            val newAge = disciple.age + 1
            
            // 所有弟子 5 岁时自动变成炼气一层（如果之前不是）
            if (newAge == 5 && disciple.realmLayer == 0) {
                addEvent("${disciple.name} 年满 5 岁，开始正式修炼，达到炼气一层！", EventType.SUCCESS)
            }
            
            val griefEndYear = disciple.griefEndYear
            val updatedDisciple = if (newAge == 5 && disciple.realmLayer == 0) {
                disciple.copy(
                    age = newAge,
                    realm = 9,
                    realmLayer = 1,
                    griefEndYear = if (griefEndYear != null && year >= griefEndYear) null else griefEndYear
                )
            } else if (griefEndYear != null && year >= griefEndYear) {
                disciple.copy(age = newAge, griefEndYear = null)
            } else {
                disciple.copy(age = newAge)
            }
            
            if (newAge >= disciple.lifespan) {
                addEvent("${disciple.name} 寿元已尽", EventType.DANGER)
                applyGriefToChildren(disciple, year)
                deadDisciples.add(updatedDisciple)
                null
            } else {
                updatedDisciple
            }
        }.filterNotNull()
        
        // 清理死亡弟子的工作槽位
        deadDisciples.forEach { disciple ->
            clearDiscipleFromAllSlots(disciple.id)
        }
        
        _disciples.value = updatedDisciples
    }

    /**
     * 处理弟子忠诚脱离判定
     * 忠诚低于50点时，每月有概率脱离宗门
     * 忠诚分段脱离概率：
     * - 忠诚40-49：每月1%脱离
     * - 忠诚30-39：每月5%脱离
     * - 忠诚20-29：每月15%脱离
     * - 忠诚10-19：每月30%脱离
     * - 忠诚0-9：每月50%脱离
     * 新招募弟子与初始弟子一年内不会脱离宗门
     * 子嗣7岁前不会脱离宗门
     */
    private fun processLoyaltyDesertion() {
        val deserters = mutableListOf<Disciple>()
        val currentMonth = _gameData.value.gameYear * 12 + _gameData.value.gameMonth

        _disciples.value = _disciples.value.filter { disciple ->
            if (!disciple.isAlive || disciple.loyalty >= 50) {
                return@filter true
            }

            // 思过崖弟子不能脱离宗门
            if (disciple.status == DiscipleStatus.REFLECTING) {
                return@filter true
            }

            // 子嗣保护期：7岁前不会脱离宗门
            if (disciple.age < 7) {
                return@filter true
            }

            // 新弟子保护期：一年内不会脱离宗门
            val monthsSinceRecruitment = currentMonth - disciple.recruitedMonth
            if (monthsSinceRecruitment < 12) {
                return@filter true
            }

            // 根据忠诚分段计算脱离概率
            val desertionChance = when (disciple.loyalty) {
                in 40..49 -> 0.01
                in 30..39 -> 0.05
                in 20..29 -> 0.15
                in 10..19 -> 0.30
                in 0..9 -> 0.50
                else -> 0.0
            }
            val roll = Random.nextDouble()

            if (roll < desertionChance) {
                // 弟子脱离宗门
                deserters.add(disciple)
                false
            } else {
                true
            }
        }

        // 清理脱离弟子的所有工作槽位
        deserters.forEach { deserter ->
            clearDiscipleFromAllSlots(deserter.id)
        }

        // 发送脱离事件通知
        deserters.forEach { deserter ->
            addEvent(
                "${deserter.name} 因忠诚度过低，已脱离宗门！",
                EventType.DANGER
            )
        }
    }

    /**
     * 处理弟子道德偷盗判定
     * 弟子道德分段偷盗概率：
     * - 道德40-49：每月1%偷盗
     * - 道德30-39：每月5%偷盗
     * - 道德20-29：每月15%偷盗
     * - 道德10-19：每月30%偷盗
     * - 道德0-9：每月50%偷盗
     * 偷盗每月判定一次
     * 弟子偷盗后会脱离宗门
     * 偷盗物品：随机偷盗1到8种，每种数量范围为1到10（不超过宗门所拥有的上限）
     * 执法长老和执法弟子可以抓捕偷盗弟子
     */
    private fun processMoralityTheft() {
        val thieves = mutableListOf<Disciple>()
        val currentMonth = _gameData.value.gameYear * 12 + _gameData.value.gameMonth

        _disciples.value.forEach { disciple ->
            if (!disciple.isAlive || disciple.morality >= 50) {
                return@forEach
            }

            // 思过崖弟子不能偷盗
            if (disciple.status == DiscipleStatus.REFLECTING) {
                return@forEach
            }

            // 子嗣保护期：7岁前不会偷盗
            if (disciple.age < 7) {
                return@forEach
            }

            // 新弟子保护期：一年内不会偷盗（仅对招募弟子生效，子嗣不适用）
            val isChild = disciple.parentId1 != null || disciple.parentId2 != null
            if (!isChild) {
                val monthsSinceRecruitment = currentMonth - disciple.recruitedMonth
                if (monthsSinceRecruitment < 12) {
                    return@forEach
                }
            }

            // 根据道德分段计算偷盗概率
            val theftChance = when (disciple.morality) {
                in 40..49 -> 0.01
                in 30..39 -> 0.05
                in 20..29 -> 0.15
                in 10..19 -> 0.30
                in 0..9 -> 0.50
                else -> 0.0
            }
            val roll = Random.nextDouble()

            if (roll < theftChance) {
                thieves.add(disciple)
            }
        }

        // 获取执法长老和执法弟子
        val lawElderId = _gameData.value.elderSlots.lawEnforcementElder
        val lawElder = _disciples.value.find { it.id == lawElderId }
        val lawDiscipleSlots = _gameData.value.elderSlots.lawEnforcementDisciples
        val lawDisciples = lawDiscipleSlots.mapNotNull { slot -> 
            _disciples.value.find { it.id == slot.discipleId } 
        }

        // 计算抓捕概率（累计计算，智力低于50有负面效果）
        // 执法长老：智力以50为基准，每高1点增加0.5%抓捕概率，低于50则减少
        // 执法弟子：智力以50为基准，每高5点增加0.5%抓捕概率，低于50则减少
        // 增强治安政策：基础+20%抓捕率，副宗主智力以50为基准，每高5点增加1%
        fun calculateArrestChance(): Double {
            var totalChance = 0.0
            
            /**
             * 增强治安政策效果计算
             * 
             * 基础效果：执法堂抓捕率+20%
             * 副宗主加成：智力50为基准，每多5点+1%
             * 消耗：每月3000灵石
             * 
             * 计算公式：
             * 实际抓捕率加成 = 20% × (1 + 副宗主加成)
             * 
             * 示例（副宗主智力65）：
             * 副宗主加成 = (65-50)/5 × 1% = 3%
             * 实际抓捕率加成 = 20% × 1.03 = 20.6%
             */
            val enhancedSecurityEnabled = _gameData.value.sectPolicies.enhancedSecurity
            if (enhancedSecurityEnabled) {
                val baseBonus = GameConfig.PolicyConfig.ENHANCED_SECURITY_BASE_EFFECT
                val viceSectMasterBonus = calculateViceSectMasterPolicyBonus()
                totalChance += baseBonus * (1 + viceSectMasterBonus)
            }
            
            // 执法长老的抓捕概率
            lawElder?.let { elder ->
                val elderBonus = (elder.intelligence - 50) * 0.005
                totalChance += elderBonus
            }
            
            // 执法弟子的抓捕概率
            lawDisciples.forEach { disciple ->
                val discipleBonus = (disciple.intelligence - 50) / 5.0 * 0.005
                totalChance += discipleBonus
            }
            
            return totalChance.coerceIn(0.0, 1.0)
        }

        // 处理每个偷盗者
        thieves.forEach { thief ->
            // 先进行抓捕判定
            val arrestChance = calculateArrestChance()
            val arrestRoll = Random.nextDouble()
            
            if (arrestRoll < arrestChance) {
                val currentYear = _gameData.value.gameYear
                val reflectionEndYear = currentYear + 10
                
                _disciples.value = _disciples.value.map { d ->
                    if (d.id == thief.id) {
                        d.copy(
                            status = DiscipleStatus.REFLECTING,
                            statusData = d.statusData + 
                                ("reflectionStartYear" to currentYear.toString()) + 
                                ("reflectionEndYear" to reflectionEndYear.toString())
                        )
                    } else {
                        d
                    }
                }
                clearDiscipleFromAllSlots(thief.id)
                
                val arresterName = if (lawElder != null && Random.nextBoolean()) {
                    "执法长老${lawElder.name}"
                } else if (lawDisciples.isNotEmpty()) {
                    val arrester = lawDisciples.random()
                    "执法弟子${arrester.name}"
                } else {
                    "执法长老${lawElder?.name ?: ""}"
                }
                
                addEvent(
                    "$arresterName 发现 ${thief.name} 企图偷盗宗门财物，已将其关入思过崖思过十年！",
                    EventType.WARNING
                )
                return@forEach
            }

            // 收集可偷盗的物品
            data class StealableItem(
                val id: String,
                val name: String,
                val rarity: Int,
                val type: String,
                val maxQuantity: Int,
                val effect: ItemEffect? = null
            )

            val stealableItems = mutableListOf<StealableItem>()

            // 装备（未装备且无拥有者的）
            _equipment.value.filter { !it.isEquipped && it.ownerId == null }.forEach { equip ->
                stealableItems.add(StealableItem(
                    id = equip.id,
                    name = equip.name,
                    rarity = equip.rarity,
                    type = "equipment",
                    maxQuantity = 1
                ))
            }

            // 功法（未学习的）
            _manuals.value.filter { !it.isLearned && it.ownerId == null }.forEach { manual ->
                stealableItems.add(StealableItem(
                    id = manual.id,
                    name = manual.name,
                    rarity = manual.rarity,
                    type = "manual",
                    maxQuantity = 1
                ))
            }

            // 丹药
            _pills.value.filter { it.quantity > 0 }.forEach { pill ->
                val pillEffect = pill.effect
                val itemEffect = ItemEffect(
                    cultivationSpeed = pillEffect.cultivationSpeed,
                    cultivationPercent = pillEffect.cultivationPercent,
                    skillExpPercent = pillEffect.skillExpPercent,
                    breakthroughChance = pillEffect.breakthroughChance,
                    targetRealm = pillEffect.targetRealm,
                    heal = pillEffect.heal,
                    healPercent = pillEffect.healPercent,
                    hpPercent = pillEffect.hpPercent,
                    mpPercent = pillEffect.mpPercent,
                    mpRecoverPercent = pillEffect.mpRecoverMaxMpPercent,
                    extendLife = pillEffect.extendLife,
                    battleCount = pillEffect.battleCount,
                    physicalAttackPercent = pillEffect.physicalAttackPercent,
                    magicAttackPercent = pillEffect.magicAttackPercent,
                    physicalDefensePercent = pillEffect.physicalDefensePercent,
                    magicDefensePercent = pillEffect.magicDefensePercent,
                    speedPercent = pillEffect.speedPercent,
                    revive = pillEffect.revive,
                    clearAll = pillEffect.clearAll,
                    duration = pillEffect.duration
                )
                stealableItems.add(StealableItem(
                    id = pill.id,
                    name = pill.name,
                    rarity = pill.rarity,
                    type = "pill",
                    maxQuantity = pill.quantity,
                    effect = itemEffect
                ))
            }

            // 材料
            _materials.value.filter { it.quantity > 0 }.forEach { material ->
                stealableItems.add(StealableItem(
                    id = material.id,
                    name = material.name,
                    rarity = material.rarity,
                    type = "material",
                    maxQuantity = material.quantity
                ))
            }

            // 灵药
            _herbs.value.filter { it.quantity > 0 }.forEach { herb ->
                stealableItems.add(StealableItem(
                    id = herb.id,
                    name = herb.name,
                    rarity = herb.rarity,
                    type = "herb",
                    maxQuantity = herb.quantity
                ))
            }

            // 种子
            _seeds.value.filter { it.quantity > 0 }.forEach { seed ->
                stealableItems.add(StealableItem(
                    id = seed.id,
                    name = seed.name,
                    rarity = seed.rarity,
                    type = "seed",
                    maxQuantity = seed.quantity
                ))
            }

            if (stealableItems.isEmpty()) {
                _disciples.value = _disciples.value.filter { it.id != thief.id }
                clearDiscipleFromAllSlots(thief.id)
                addEvent("${thief.name} 因道德败坏企图偷盗宗门财物，但仓库空无一物，已逃离宗门！", EventType.WARNING)
                return@forEach
            }

            val itemTypesToSteal = Random.nextInt(1, minOf(9, stealableItems.size + 1))
            val shuffledItems = stealableItems.shuffled().take(itemTypesToSteal)

            val stolenItems = mutableListOf<StorageBagItem>()

            shuffledItems.forEach { item ->
                val quantityToSteal = Random.nextInt(1, minOf(11, item.maxQuantity + 1))

                when (item.type) {
                    "equipment" -> {
                        _equipment.value = _equipment.value.filter { it.id != item.id }
                    }
                    "manual" -> {
                        _manuals.value = _manuals.value.filter { it.id != item.id }
                    }
                    "pill" -> {
                        _pills.value = _pills.value.map { pill ->
                            if (pill.id == item.id) {
                                pill.copy(quantity = pill.quantity - quantityToSteal)
                            } else {
                                pill
                            }
                        }.filter { it.quantity > 0 }
                    }
                    "material" -> {
                        _materials.value = _materials.value.map { material ->
                            if (material.id == item.id) {
                                material.copy(quantity = material.quantity - quantityToSteal)
                            } else {
                                material
                            }
                        }.filter { it.quantity > 0 }
                    }
                    "herb" -> {
                        _herbs.value = _herbs.value.map { herb ->
                            if (herb.id == item.id) {
                                herb.copy(quantity = herb.quantity - quantityToSteal)
                            } else {
                                herb
                            }
                        }.filter { it.quantity > 0 }
                    }
                    "seed" -> {
                        _seeds.value = _seeds.value.map { seed ->
                            if (seed.id == item.id) {
                                seed.copy(quantity = seed.quantity - quantityToSteal)
                            } else {
                                seed
                            }
                        }.filter { it.quantity > 0 }
                    }
                }

                stolenItems.add(StorageBagItem(
                    itemId = item.id,
                    itemType = item.type,
                    name = item.name,
                    rarity = item.rarity,
                    quantity = quantityToSteal,
                    obtainedYear = _gameData.value.gameYear,
                    obtainedMonth = _gameData.value.gameMonth,
                    effect = item.effect
                ))
            }

            _disciples.value = _disciples.value.filter { it.id != thief.id }

            clearDiscipleFromAllSlots(thief.id)

            val stolenItemsDesc = stolenItems.map { "${it.name}×${it.quantity}" }.joinToString("、")
            addEvent(
                "${thief.name} 因道德败坏，偷盗了宗门财物（$stolenItemsDesc）后逃离宗门！",
                EventType.DANGER
            )
        }
    }

    /**
     * 统一处理所有政策的月度灵石消耗
     * 包括：增强治安、丹道激励、锻造激励、灵药培育、修行津贴、功法研习
     * 当灵石不足时，按优先级顺序自动关闭政策
     */
    private fun processPolicyCosts() {
        val policies = _gameData.value.sectPolicies
        var totalCost = 0L
        val policiesToDisable = mutableListOf<String>()
        
        // 计算所有启用政策的总消耗
        if (policies.enhancedSecurity) totalCost += GameConfig.PolicyConfig.ENHANCED_SECURITY_COST
        if (policies.alchemyIncentive) totalCost += GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_COST
        if (policies.forgeIncentive) totalCost += GameConfig.PolicyConfig.FORGE_INCENTIVE_COST
        if (policies.herbCultivation) totalCost += GameConfig.PolicyConfig.HERB_CULTIVATION_COST
        if (policies.cultivationSubsidy) totalCost += GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_COST
        if (policies.manualResearch) totalCost += GameConfig.PolicyConfig.MANUAL_RESEARCH_COST
        
        val currentStones = _gameData.value.spiritStones
        
        if (currentStones >= totalCost) {
            // 灵石充足，扣除总消耗
            _gameData.value = _gameData.value.copy(spiritStones = currentStones - totalCost)
            if (totalCost > 0) {
                addEvent("政策消耗${totalCost}灵石", EventType.INFO)
            }
        } else {
            // 灵石不足，按优先级顺序关闭政策（消耗低的先关闭）
            var newPolicies = policies
            var remainingStones = currentStones
            
            // 政策列表按消耗金额排序（低到高）
            val policyCosts = listOf(
                Triple(GameConfig.PolicyConfig.ENHANCED_SECURITY_NAME, policies.enhancedSecurity, GameConfig.PolicyConfig.ENHANCED_SECURITY_COST) to 
                    { p: SectPolicies -> p.copy(enhancedSecurity = false) },
                Triple(GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_NAME, policies.alchemyIncentive, GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_COST) to 
                    { p: SectPolicies -> p.copy(alchemyIncentive = false) },
                Triple(GameConfig.PolicyConfig.FORGE_INCENTIVE_NAME, policies.forgeIncentive, GameConfig.PolicyConfig.FORGE_INCENTIVE_COST) to 
                    { p: SectPolicies -> p.copy(forgeIncentive = false) },
                Triple(GameConfig.PolicyConfig.HERB_CULTIVATION_NAME, policies.herbCultivation, GameConfig.PolicyConfig.HERB_CULTIVATION_COST) to 
                    { p: SectPolicies -> p.copy(herbCultivation = false) },
                Triple(GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_NAME, policies.cultivationSubsidy, GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_COST) to 
                    { p: SectPolicies -> p.copy(cultivationSubsidy = false) },
                Triple(GameConfig.PolicyConfig.MANUAL_RESEARCH_NAME, policies.manualResearch, GameConfig.PolicyConfig.MANUAL_RESEARCH_COST) to 
                    { p: SectPolicies -> p.copy(manualResearch = false) }
            )
            
            for ((info, updater) in policyCosts) {
                val (name, enabled, cost) = info
                if (enabled && remainingStones < cost) {
                    newPolicies = updater(newPolicies)
                    policiesToDisable.add(name)
                } else if (enabled) {
                    remainingStones -= cost
                }
            }
            
            _gameData.value = _gameData.value.copy(sectPolicies = newPolicies)
            
            if (policiesToDisable.isNotEmpty()) {
                addEvent("灵石不足，${policiesToDisable.joinToString("、")}政策已自动关闭", EventType.WARNING)
            }
        }
    }

    private fun processLawEnforcementReserveAutoFill() {
        val data = _gameData.value
        if (data.elderSlots.lawEnforcementElder == null) return
        
        val lawDisciples = data.elderSlots.lawEnforcementDisciples
        val reserveDisciples = data.elderSlots.lawEnforcementReserveDisciples
        
        val emptySlots = (0..7).filter { index -> 
            lawDisciples.none { it.index == index && it.discipleId != null }
        }
        
        if (emptySlots.isEmpty() || reserveDisciples.isEmpty()) return
        
        val aliveReserveDisciples = reserveDisciples.filter { slot ->
            val disciple = _disciples.value.find { it.id == slot.discipleId }
            disciple != null && disciple.isAlive && disciple.status != DiscipleStatus.REFLECTING
        }.sortedByDescending { slot ->
            _disciples.value.find { it.id == slot.discipleId }?.intelligence ?: 0
        }.toMutableList()
        
        if (aliveReserveDisciples.isEmpty()) return
        
        val updatedLawDisciples = lawDisciples.toMutableList()
        val updatedReserveDisciples = reserveDisciples.toMutableList()
        var filledCount = 0
        
        for (emptyIndex in emptySlots) {
            if (aliveReserveDisciples.isEmpty()) break
            
            val reserveToPromote = aliveReserveDisciples.first()
            val reserveIndex = reserveToPromote.index
            
            val existingSlotIndex = updatedLawDisciples.indexOfFirst { it.index == emptyIndex }
            if (existingSlotIndex >= 0) {
                updatedLawDisciples[existingSlotIndex] = reserveToPromote.copy(index = emptyIndex)
            } else {
                updatedLawDisciples.add(reserveToPromote.copy(index = emptyIndex))
            }
            
            updatedReserveDisciples.removeAll(predicate = { it.index == reserveIndex })
            aliveReserveDisciples.removeAll(predicate = { it.index == reserveIndex })
            
            addEvent(
                "储备弟子 ${reserveToPromote.discipleName} 自动补位为执法弟子",
                EventType.SUCCESS
            )
            filledCount++
        }
        
        if (filledCount > 0) {
            val updatedElderSlots = data.elderSlots.copy(
                lawEnforcementDisciples = updatedLawDisciples,
                lawEnforcementReserveDisciples = updatedReserveDisciples
            )
            _gameData.value = data.copy(elderSlots = updatedElderSlots)
            syncAllDiscipleStatuses()
        }
    }

    private fun processReflectionEnd(year: Int) {
        val reflectingDisciples = _disciples.value.filter { 
            it.status == DiscipleStatus.REFLECTING 
        }
        
        if (reflectingDisciples.isEmpty()) return
        
        val updatedDisciples = _disciples.value.map { disciple ->
            if (disciple.status == DiscipleStatus.REFLECTING) {
                val endYear = disciple.statusData["reflectionEndYear"]?.toIntOrNull() ?: 0
                if (year >= endYear) {
                    val newMorality = (disciple.morality + 10).coerceAtMost(100)
                    addEvent(
                        "${disciple.name} 在思过崖思过期满，已悔过自新，道德提升10点",
                        EventType.SUCCESS
                    )
                    disciple.copy(
                        status = DiscipleStatus.IDLE,
                        morality = newMorality,
                        statusData = disciple.statusData - "reflectionStartYear" - "reflectionEndYear"
                    )
                } else {
                    disciple
                }
            } else {
                disciple
            }
        }
        
        _disciples.value = updatedDisciples
        syncAllDiscipleStatuses()
    }

    private fun applyGriefToChildren(deadDisciple: Disciple, currentYear: Int) {
        val children = _disciples.value.filter { 
            it.parentId1 == deadDisciple.id || it.parentId2 == deadDisciple.id 
        }
        
        if (children.isNotEmpty()) {
            val updatedDisciples = _disciples.value.map { disciple ->
                if (children.contains(disciple)) {
                    addEvent("${disciple.name} 因${deadDisciple.name}逝去而伤心过度，修炼速度降低", EventType.WARNING)
                    disciple.copy(griefEndYear = currentYear + 1)
                } else {
                    disciple
                }
            }
            _disciples.value = updatedDisciples
        }
    }
    
    private fun processPartnerMatching(year: Int, month: Int) {
        val updatedDisciples = _disciples.value.toMutableList()
        val matched = mutableSetOf<String>()
        
        _disciples.value.filter {
            it.isAlive && it.age >= 18 && it.partnerId == null && !matched.contains(it.id) && it.status != DiscipleStatus.REFLECTING
        }.forEach { disciple ->
            val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
            val partnerChanceBonus = talentEffects["partnerChance"] ?: 0.0
            val baseChance = 0.012
            val finalChance = (baseChance + partnerChanceBonus).coerceIn(0.01, 1.0)

            if (Random.nextDouble() < finalChance) {
                val potentialPartners = _disciples.value.filter { d ->
                    d.isAlive &&
                    d.age >= 18 &&
                    d.partnerId == null &&
                    d.id != disciple.id &&
                    d.gender != disciple.gender &&
                    !matched.contains(d.id) &&
                    !areSiblings(disciple, d) &&
                    d.status != DiscipleStatus.REFLECTING
                }

                if (potentialPartners.isNotEmpty()) {
                    val partner = potentialPartners.random()
                    matched.add(disciple.id)
                    matched.add(partner.id)

                    val discipleIndex = updatedDisciples.indexOfFirst { it.id == disciple.id }
                    val partnerIndex = updatedDisciples.indexOfFirst { it.id == partner.id }

                    if (discipleIndex >= 0) {
                        updatedDisciples[discipleIndex] = updatedDisciples[discipleIndex].copy(partnerId = partner.id)
                    }
                    if (partnerIndex >= 0) {
                        updatedDisciples[partnerIndex] = updatedDisciples[partnerIndex].copy(partnerId = disciple.id)
                    }

                    addEvent("${disciple.name} 与 ${partner.name} 结为道侣，共修大道", EventType.SUCCESS)
                }
            }
        }
        
        _disciples.value = updatedDisciples
    }
    
    private fun processChildGeneration(year: Int) {
        val updatedDisciples = _disciples.value.toMutableList()
        val newChildren = mutableListOf<Disciple>()
        
        _disciples.value.filter { 
            it.isAlive && it.partnerId != null && it.age >= 18 && it.gender == "female" && it.status != DiscipleStatus.REFLECTING
        }.forEach { disciple ->
            val partner = _disciples.value.find { it.id == disciple.partnerId }
            
            if (partner == null || !partner.isAlive || partner.status == DiscipleStatus.REFLECTING) {
                if (partner == null || !partner.isAlive) {
                    val discipleIndex = updatedDisciples.indexOfFirst { it.id == disciple.id }
                    if (discipleIndex >= 0) {
                        updatedDisciples[discipleIndex] = updatedDisciples[discipleIndex].copy(partnerId = null)
                    }
                }
                return@forEach
            }
            
            if (year - disciple.lastChildYear >= 1 && Random.nextDouble() < 0.006) {
                val discipleIndex = updatedDisciples.indexOfFirst { it.id == disciple.id }
                val partnerIndex = updatedDisciples.indexOfFirst { it.id == partner.id }
                
                if (discipleIndex >= 0) {
                    updatedDisciples[discipleIndex] = updatedDisciples[discipleIndex].copy(lastChildYear = year)
                }
                if (partnerIndex >= 0) {
                    updatedDisciples[partnerIndex] = updatedDisciples[partnerIndex].copy(lastChildYear = year)
                }
                
                val child = createChild(disciple, partner, year)
                newChildren.add(child)
                addEvent("${disciple.name} 与 ${partner.name} 喜得${if (child.gender == "male") "子" else "女"} ${child.name}，灵根：${child.spiritRootName}", EventType.SUCCESS)
            }
        }
        
        _disciples.value = updatedDisciples + newChildren
    }
    
    private fun createChild(parent1: Disciple, parent2: Disciple, year: Int): Disciple {
        // 获取父姓（男性为父）
        val father = if (parent1.gender == "male") parent1 else parent2
        val fatherSurname = father.name.take(1) // 取姓氏（第一个字）

        val names = listOf(
            // 逍遥问道类
            "逍遥", "无忌", "长生", "问道", "求道", "修真", "清风", "明月", "云飞", "天行",
            // 子字辈
            "子轩", "子涵", "子墨", "子瑜", "子琪", "子萱", "子晴", "子怡", "子欣", "子悦",
            // 仙道意境类
            "凌霄", "御风", "踏云", "惊鸿", "逐月", "追星", "揽月", "摘星", "乘云", "驾雾",
            // 修炼境界类
            "悟道", "通玄", "归真", "化神", "凝神", "聚气", "炼虚", "合道", "渡劫", "飞升",
            // 自然意象类
            "青松", "翠竹", "寒梅", "幽兰", "紫霞", "晨曦", "暮雪", "晨露", "晚风", "秋霜",
            // 剑修风格类
            "剑心", "剑尘", "剑歌", "剑舞", "剑影", "剑魄", "剑魂", "剑鸣", "剑啸", "剑寒",
            // 丹修风格类
            "丹辰", "丹华", "丹心", "丹青", "丹枫", "丹霞", "丹墨", "丹羽", "丹翎", "丹曦",
            // 器修风格类
            "器宇", "器灵", "器心", "器魂", "器鸣", "器韵", "器华", "器尘", "器玄", "器真",
            // 阵修风格类
            "阵玄", "阵灵", "阵心", "阵尘", "阵华", "阵羽", "阵霄", "阵辰", "阵曦", "阵影",
            // 符修风格类
            "符玄", "符灵", "符心", "符尘", "符华", "符羽", "符霄", "符辰", "符曦", "符影"
        )
        val childName = fatherSurname + names.random()
        
        // 子嗣灵根概率分布（与普通弟子一致）
        val spiritRootTypes = listOf("metal", "wood", "water", "fire", "earth")
        val rootCount = when (Random.nextDouble()) {
            in 0.0..0.06 -> 1  // 6% 单灵根
            in 0.06..0.20 -> 2 // 14% 双灵根
            in 0.20..0.50 -> 3 // 30% 三灵根
            in 0.50..0.75 -> 4 // 25% 四灵根
            else -> 5          // 25% 五灵根
        }
        val spiritRootType = spiritRootTypes.shuffled().take(rootCount).joinToString(",")
        
        // 随机性别
        val gender = if (Random.nextBoolean()) "male" else "female"
        
        // 生成天赋
        val talents = TalentDatabase.generateTalentsForDisciple()
        
        // 计算天赋对寿命的影响
        var lifespanModifier = 1.0
        talents.forEach { talent ->
            talent.effects["lifespan"]?.let { lifespanModifier += it }
        }
        // 基础寿命根据境界计算（炼气期100年）
        val baseLifespan = GameConfig.Realm.get(9).maxAge
        val modifiedLifespan = (baseLifespan * lifespanModifier).toInt().coerceAtLeast(1)
        
        val data = _gameData.value
        val currentMonth = data.gameYear * 12 + data.gameMonth

        val baseChild = Disciple(
            id = UUID.randomUUID().toString(),
            name = childName,
            realm = 9,
            realmLayer = 0,
            cultivation = 0.0,
            spiritRootType = spiritRootType,
            age = 0,
            lifespan = modifiedLifespan,
            gender = gender,
            talentIds = talents.map { it.id },
            parentId1 = parent1.id,
            parentId2 = parent2.id,
            intelligence = Random.nextInt(1, 101),
            charm = Random.nextInt(1, 101),
            loyalty = Random.nextInt(70, 101),
            comprehension = Random.nextInt(1, 101),
            artifactRefining = Random.nextInt(1, 101),
            pillRefining = Random.nextInt(1, 101),
            spiritPlanting = Random.nextInt(1, 101),
            teaching = Random.nextInt(1, 101),
            morality = Random.nextInt(1, 101),
            recruitedMonth = currentMonth,
            combatStatsVariance = Random.nextInt(-30, 31)
        )
        return applyTalentBaseFlatBonuses(baseChild, talents).let { disciple ->
            val varianceMultiplier = 1.0 + disciple.combatStatsVariance / 100.0
            disciple.copy(
                baseHp = (100 * varianceMultiplier).toInt(),
                baseMp = (50 * varianceMultiplier).toInt(),
                basePhysicalAttack = (10 * varianceMultiplier).toInt(),
                baseMagicAttack = (5 * varianceMultiplier).toInt(),
                basePhysicalDefense = (5 * varianceMultiplier).toInt(),
                baseMagicDefense = (3 * varianceMultiplier).toInt(),
                baseSpeed = (10 * varianceMultiplier).toInt()
            )
        }
    }
    
    fun recruitDisciple() {
        val data = _gameData.value
        val currentMonth = data.gameYear * 12 + data.gameMonth

        val disciple = generateRandomDisciple().copy(
            recruitedMonth = currentMonth
        )
        _disciples.value = _disciples.value + disciple
        _gameData.value = data.copy()

        addEvent("招募到新弟子：${disciple.name}，灵根：${disciple.spiritRootName}", EventType.SUCCESS)
    }

    fun recruitDiscipleFromList(disciple: Disciple): Boolean {
        val data = _gameData.value

        // 检查弟子是否已经在弟子列表中，防止重复添加
        if (_disciples.value.any { it.id == disciple.id }) {
            addEvent("该弟子已经被招募了", EventType.WARNING)
            // 从招募列表中移除
            val updatedList = data.recruitList.filter { it.id != disciple.id }
            _gameData.value = data.copy(recruitList = updatedList)
            return false
        }

        val currentMonth = data.gameYear * 12 + data.gameMonth

        val discipleWithRecruitTime = disciple.copy(
            recruitedMonth = currentMonth,
            discipleType = "outer"
        )
        _disciples.value = _disciples.value + discipleWithRecruitTime
        val updatedList = data.recruitList.filter { it.id != disciple.id }
        _gameData.value = data.copy(recruitList = updatedList)
        return true
    }
    
    fun expelDisciple(discipleId: String) {
        val disciple = _disciples.value.find { it.id == discipleId } ?: return
        
        clearDiscipleFromAllSlots(discipleId)
        
        _disciples.value = _disciples.value.filter { it.id != discipleId }
        
        addEvent("驱逐了弟子：${disciple.name}", EventType.WARNING)
    }
    
    private fun clearDiscipleFromAllSlots(discipleId: String) {
        val data = _gameData.value
        
        _buildingSlots.value = _buildingSlots.value.map { slot ->
            if (slot.discipleId == discipleId) {
                slot.copy(
                    discipleId = null,
                    discipleName = "",
                    status = SlotStatus.IDLE,
                    type = SlotType.IDLE
                )
            } else {
                slot
            }
        }
        
        _teams.value = _teams.value.map { team ->
            if (discipleId in team.memberIds) {
                val newMemberIds = team.memberIds.filter { it != discipleId }
                val newMemberNames = team.memberNames.filterIndexed { index, _ -> 
                    team.memberIds[index] != discipleId 
                }
                team.copy(
                    memberIds = newMemberIds,
                    memberNames = newMemberNames
                )
            } else {
                team
            }
        }
        
        val updatedSpiritMineSlots = data.spiritMineSlots.map { slot ->
            if (slot.discipleId == discipleId) {
                slot.copy(discipleId = null, discipleName = "")
            } else {
                slot
            }
        }
        
        val updatedLibrarySlots = data.librarySlots.map { slot ->
            if (slot.discipleId == discipleId) {
                slot.copy(discipleId = null, discipleName = "")
            } else {
                slot
            }
        }
        
        val updatedElderSlots = data.elderSlots.let { elders ->
            var updated = elders
            if (elders.herbGardenElder == discipleId) {
                updated = updated.copy(herbGardenElder = null)
            }
            if (elders.alchemyElder == discipleId) {
                updated = updated.copy(alchemyElder = null)
            }
            if (elders.forgeElder == discipleId) {
                updated = updated.copy(forgeElder = null)
            }
            if (elders.libraryElder == discipleId) {
                updated = updated.copy(libraryElder = null)
            }
            updated.copy(
                herbGardenDisciples = elders.herbGardenDisciples.map { if (it.discipleId == discipleId) it.copy(discipleId = null, discipleName = "", discipleRealm = "") else it },
                alchemyDisciples = elders.alchemyDisciples.map { if (it.discipleId == discipleId) it.copy(discipleId = null, discipleName = "", discipleRealm = "") else it },
                forgeDisciples = elders.forgeDisciples.map { if (it.discipleId == discipleId) it.copy(discipleId = null, discipleName = "", discipleRealm = "") else it },
                libraryDisciples = elders.libraryDisciples.map { if (it.discipleId == discipleId) it.copy(discipleId = null, discipleName = "", discipleRealm = "") else it }
            )
        }
        
        val updatedWorldMapSects = data.worldMapSects.map { sect ->
            val updatedMineSlots = sect.mineSlots.map { slot ->
                if (slot.discipleId == discipleId) {
                    slot.copy(discipleId = null, discipleName = "", isActive = false)
                } else {
                    slot
                }
            }
            sect.copy(mineSlots = updatedMineSlots)
        }
        
        _gameData.value = data.copy(
            spiritMineSlots = updatedSpiritMineSlots,
            librarySlots = updatedLibrarySlots,
            elderSlots = updatedElderSlots,
            worldMapSects = updatedWorldMapSects
        )
    }
    
    /**
     * 辅助函数：将新道具添加到储物袋，合并相同道具
     */
    private fun addStorageItemToBag(currentItems: List<StorageBagItem>, newItem: StorageBagItem): List<StorageBagItem> {
        val items = currentItems.toMutableList()
        val existingIndex = items.indexOfFirst { 
            it.itemId == newItem.itemId && it.itemType == newItem.itemType 
        }
        if (existingIndex >= 0) {
            // 已存在相同道具，合并数量
            val existing = items[existingIndex]
            items[existingIndex] = existing.copy(
                quantity = existing.quantity + newItem.quantity
            )
        } else {
            // 不存在，添加新道具
            items.add(newItem)
        }
        return items
    }
    
    suspend fun rewardItemToDisciple(discipleId: String, itemId: String, itemType: String) {
        val disciple = _disciples.value.find { it.id == discipleId } ?: return
        val data = _gameData.value
        
        when (itemType) {
            "pill" -> {
                val pill = _pills.value.find { it.id == itemId } ?: return
                val pillEffect = pill.effect
                val itemEffect = ItemEffect(
                    cultivationSpeed = pillEffect.cultivationSpeed,
                    cultivationPercent = pillEffect.cultivationPercent,
                    skillExpPercent = pillEffect.skillExpPercent,
                    breakthroughChance = pillEffect.breakthroughChance,
                    targetRealm = pillEffect.targetRealm,
                    heal = pillEffect.heal,
                    healPercent = pillEffect.healPercent,
                    hpPercent = pillEffect.hpPercent,
                    mpPercent = pillEffect.mpPercent,
                    mpRecoverPercent = pillEffect.mpRecoverMaxMpPercent,
                    extendLife = pillEffect.extendLife,
                    battleCount = pillEffect.battleCount,
                    physicalAttackPercent = pillEffect.physicalAttackPercent,
                    magicAttackPercent = pillEffect.magicAttackPercent,
                    physicalDefensePercent = pillEffect.physicalDefensePercent,
                    magicDefensePercent = pillEffect.magicDefensePercent,
                    speedPercent = pillEffect.speedPercent,
                    revive = pillEffect.revive,
                    clearAll = pillEffect.clearAll,
                    duration = pillEffect.duration
                )
                val storageItem = StorageBagItem(
                    itemId = pill.id,
                    itemType = "pill",
                    name = pill.name,
                    rarity = pill.rarity,
                    quantity = 1,
                    obtainedYear = data.gameYear,
                    obtainedMonth = data.gameMonth,
                    effect = itemEffect
                )
                val updatedBag = addStorageItemToBag(disciple.storageBagItems, storageItem)
                _disciples.value = _disciples.value.map { 
                    if (it.id == discipleId) it.copy(storageBagItems = updatedBag) else it 
                }
                _pills.value = _pills.value.filter { it.id != itemId }
                addEvent("赏赐 ${pill.name} 给 ${disciple.name}", EventType.SUCCESS)
                try {
                    processDiscipleItemInstantly(discipleId, "pill")
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing pill instantly", e)
                }
            }
            "equipment" -> {
                val equipment = _equipment.value.find { it.id == itemId } ?: return
                val storageItem = StorageBagItem(
                    itemId = equipment.id,
                    itemType = "equipment",
                    name = equipment.name,
                    rarity = equipment.rarity,
                    quantity = 1,
                    obtainedYear = data.gameYear,
                    obtainedMonth = data.gameMonth
                )
                val updatedBag = addStorageItemToBag(disciple.storageBagItems, storageItem)
                _disciples.value = _disciples.value.map { 
                    if (it.id == discipleId) it.copy(storageBagItems = updatedBag) else it 
                }
                _equipment.value = _equipment.value.filter { it.id != itemId }
                addEvent("赏赐 ${equipment.name} 给 ${disciple.name}", EventType.SUCCESS)
                try {
                    processDiscipleItemInstantly(discipleId, "equipment")
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing equipment instantly", e)
                }
            }
            "manual" -> {
                val manual = _manuals.value.find { it.id == itemId } ?: return
                val storageItem = StorageBagItem(
                    itemId = manual.id,
                    itemType = "manual",
                    name = manual.name,
                    rarity = manual.rarity,
                    quantity = 1,
                    obtainedYear = data.gameYear,
                    obtainedMonth = data.gameMonth
                )
                val updatedBag = addStorageItemToBag(disciple.storageBagItems, storageItem)
                _disciples.value = _disciples.value.map { 
                    if (it.id == discipleId) it.copy(storageBagItems = updatedBag) else it 
                }
                _manuals.value = _manuals.value.filter { it.id != itemId }
                addEvent("赏赐 ${manual.name} 给 ${disciple.name}", EventType.SUCCESS)
                try {
                    processDiscipleItemInstantly(discipleId, "manual")
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing manual instantly", e)
                }
            }
        }
    }
    
    fun rewardSpiritStonesToDisciple(discipleId: String, amount: Long) {
        val data = _gameData.value
        if (data.spiritStones < amount) {
            addEvent("灵石不足！", EventType.WARNING)
            return
        }
        
        val disciple = _disciples.value.find { it.id == discipleId } ?: return
        _disciples.value = _disciples.value.map { 
            if (it.id == discipleId) it.copy(storageBagSpiritStones = it.storageBagSpiritStones + amount) else it 
        }
        _gameData.value = _gameData.value.copy(spiritStones = _gameData.value.spiritStones - amount)
        addEvent("赏赐 $amount 灵石给 ${disciple.name}", EventType.SUCCESS)
    }
    
    suspend fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>) {
        transactionMutex.withLock {
            val disciple = _disciples.value.find { it.id == discipleId } ?: return@withLock
            
            if (disciple.status == DiscipleStatus.REFLECTING) {
                addEvent("思过中的弟子无法接受赏赐！", EventType.WARNING)
                return@withLock
            }
            
            val data = _gameData.value
            var updatedDisciple = disciple
            
            // 收集所有变更，最后批量更新
            val equipmentToRemove = mutableListOf<String>()
            val manualsToRemove = mutableListOf<String>()
            val pillsToUpdate = mutableListOf<Pair<String, Int>>() // id to new quantity (0 means remove)
            val materialsToUpdate = mutableListOf<Pair<String, Int>>()
            val herbsToUpdate = mutableListOf<Pair<String, Int>>()
            val seedsToUpdate = mutableListOf<Pair<String, Int>>()
            val newStorageItems = mutableListOf<StorageBagItem>()
            
            items.forEach { item ->
                when (item.type) {
                    "equipment" -> {
                        val equipment = _equipment.value.find { it.id == item.id } ?: return@forEach
                        val storageItem = StorageBagItem(
                            itemId = equipment.id,
                            itemType = "equipment",
                            name = equipment.name,
                            rarity = equipment.rarity,
                            quantity = 1,
                            obtainedYear = data.gameYear,
                            obtainedMonth = data.gameMonth
                        )
                        newStorageItems.add(storageItem)
                        equipmentToRemove.add(item.id)
                    }
                    "manual" -> {
                        val manual = _manuals.value.find { it.id == item.id } ?: return@forEach
                        val storageItem = StorageBagItem(
                            itemId = manual.id,
                            itemType = "manual",
                            name = manual.name,
                            rarity = manual.rarity,
                            quantity = 1,
                            obtainedYear = data.gameYear,
                            obtainedMonth = data.gameMonth
                        )
                        newStorageItems.add(storageItem)
                        manualsToRemove.add(item.id)
                    }
                    "pill" -> {
                        val pill = _pills.value.find { it.id == item.id } ?: return@forEach
                        val transferQty = minOf(item.quantity, pill.quantity)
                        val pillEffect = pill.effect
                        val itemEffect = ItemEffect(
                            cultivationSpeed = pillEffect.cultivationSpeed,
                            cultivationPercent = pillEffect.cultivationPercent,
                            skillExpPercent = pillEffect.skillExpPercent,
                            breakthroughChance = pillEffect.breakthroughChance,
                            targetRealm = pillEffect.targetRealm,
                            heal = pillEffect.heal,
                            healPercent = pillEffect.healPercent,
                            hpPercent = pillEffect.hpPercent,
                            mpPercent = pillEffect.mpPercent,
                            mpRecoverPercent = pillEffect.mpRecoverMaxMpPercent,
                            extendLife = pillEffect.extendLife,
                            battleCount = pillEffect.battleCount,
                            physicalAttackPercent = pillEffect.physicalAttackPercent,
                            magicAttackPercent = pillEffect.magicAttackPercent,
                            physicalDefensePercent = pillEffect.physicalDefensePercent,
                            magicDefensePercent = pillEffect.magicDefensePercent,
                            speedPercent = pillEffect.speedPercent,
                            revive = pillEffect.revive,
                            clearAll = pillEffect.clearAll,
                            duration = pillEffect.duration
                        )
                        val storageItem = StorageBagItem(
                            itemId = pill.id,
                            itemType = "pill",
                            name = pill.name,
                            rarity = pill.rarity,
                            quantity = transferQty,
                            obtainedYear = data.gameYear,
                            obtainedMonth = data.gameMonth,
                            effect = itemEffect
                        )
                        newStorageItems.add(storageItem)
                        val newQty = pill.quantity - transferQty
                        pillsToUpdate.add(item.id to newQty)
                    }
                    "material" -> {
                        val material = _materials.value.find { it.id == item.id } ?: return@forEach
                        val transferQty = minOf(item.quantity, material.quantity)
                        val storageItem = StorageBagItem(
                            itemId = material.id,
                            itemType = "material",
                            name = material.name,
                            rarity = material.rarity,
                            quantity = transferQty,
                            obtainedYear = data.gameYear,
                            obtainedMonth = data.gameMonth
                        )
                        newStorageItems.add(storageItem)
                        val newQty = material.quantity - transferQty
                        materialsToUpdate.add(item.id to newQty)
                    }
                    "herb" -> {
                        val herb = _herbs.value.find { it.id == item.id } ?: return@forEach
                        val transferQty = minOf(item.quantity, herb.quantity)
                        val storageItem = StorageBagItem(
                            itemId = herb.id,
                            itemType = "herb",
                            name = herb.name,
                            rarity = herb.rarity,
                            quantity = transferQty,
                            obtainedYear = data.gameYear,
                            obtainedMonth = data.gameMonth
                        )
                        newStorageItems.add(storageItem)
                        val newQty = herb.quantity - transferQty
                        herbsToUpdate.add(item.id to newQty)
                    }
                    "seed" -> {
                        val seed = _seeds.value.find { it.id == item.id } ?: return@forEach
                        val transferQty = minOf(item.quantity, seed.quantity)
                        val storageItem = StorageBagItem(
                            itemId = seed.id,
                            itemType = "seed",
                            name = seed.name,
                            rarity = seed.rarity,
                            quantity = transferQty,
                            obtainedYear = data.gameYear,
                            obtainedMonth = data.gameMonth
                        )
                        newStorageItems.add(storageItem)
                        val newQty = seed.quantity - transferQty
                        seedsToUpdate.add(item.id to newQty)
                    }
                }
            }
            
            // 更新弟子数据 - 合并相同道具
            var currentItems = updatedDisciple.storageBagItems
            newStorageItems.forEach { newItem ->
                currentItems = addStorageItemToBag(currentItems, newItem)
            }
            updatedDisciple = updatedDisciple.copy(storageBagItems = currentItems)
            
            // 批量更新 StateFlow
            _disciples.value = _disciples.value.map { 
                if (it.id == discipleId) updatedDisciple else it 
            }
            
            // 批量更新装备
            if (equipmentToRemove.isNotEmpty()) {
                _equipment.value = _equipment.value.filter { it.id !in equipmentToRemove }
            }
            
            // 批量更新功法
            if (manualsToRemove.isNotEmpty()) {
                _manuals.value = _manuals.value.filter { it.id !in manualsToRemove }
            }
            
            // 批量更新丹药
            if (pillsToUpdate.isNotEmpty()) {
                val pillsMap = pillsToUpdate.toMap()
                _pills.value = _pills.value.mapNotNull { pill ->
                    val newQty = pillsMap[pill.id]
                    when {
                        newQty == null -> pill
                        newQty <= 0 -> null
                        else -> pill.copy(quantity = newQty)
                    }
                }
            }
            
            // 批量更新材料
            if (materialsToUpdate.isNotEmpty()) {
                val materialsMap = materialsToUpdate.toMap()
                _materials.value = _materials.value.mapNotNull { material ->
                    val newQty = materialsMap[material.id]
                    when {
                        newQty == null -> material
                        newQty <= 0 -> null
                        else -> material.copy(quantity = newQty)
                    }
                }
            }
            
            // 批量更新草药
            if (herbsToUpdate.isNotEmpty()) {
                val herbsMap = herbsToUpdate.toMap()
                _herbs.value = _herbs.value.mapNotNull { herb ->
                    val newQty = herbsMap[herb.id]
                    when {
                        newQty == null -> herb
                        newQty <= 0 -> null
                        else -> herb.copy(quantity = newQty)
                    }
                }
            }
            
            // 批量更新种子
            if (seedsToUpdate.isNotEmpty()) {
                val seedsMap = seedsToUpdate.toMap()
                _seeds.value = _seeds.value.mapNotNull { seed ->
                    val newQty = seedsMap[seed.id]
                    when {
                        newQty == null -> seed
                        newQty <= 0 -> null
                        else -> seed.copy(quantity = newQty)
                    }
                }
            }
            
            addEvent("赏赐 ${items.size} 件道具给 ${disciple.name}", EventType.SUCCESS)

            val hasPills = items.any { it.type == "pill" }
            val hasEquipment = items.any { it.type == "equipment" }
            val hasManuals = items.any { it.type == "manual" }

            // 在锁内部直接调用无锁版本，避免递归锁问题
            // 此时已经在 transactionMutex.withLock 块内
            if (hasPills) {
                try {
                    processDiscipleItemInstantlyInternal(discipleId, "pill")
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing pills instantly", e)
                }
            }
            if (hasEquipment) {
                try {
                    processDiscipleItemInstantlyInternal(discipleId, "equipment")
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing equipment instantly", e)
                }
            }
            if (hasManuals) {
                try {
                    processDiscipleItemInstantlyInternal(discipleId, "manual")
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing manuals instantly", e)
                }
            }
        }
    }
    
    fun restartGame() {
        val previous = _gameData.value
        val sectName = previous.sectName.ifBlank { "青云宗" }
        val currentSlot = previous.currentSlot

        createNewGame(sectName)
        _gameData.value = _gameData.value.copy(currentSlot = currentSlot)

        addEvent("游戏已重置", EventType.INFO)
    }
    
    private fun generateRandomDisciple(isInitial: Boolean = false): Disciple {
        val name = GameUtils.generateRandomName(GameUtils.NameStyle.XIANXIA)
        val spiritRootTypes = listOf("metal", "wood", "water", "fire", "earth")
        val rootCount = when (Random.nextDouble()) {
            in 0.0..0.06 -> 1  // 6% 单灵根
            in 0.06..0.20 -> 2 // 14% 双灵根
            in 0.20..0.50 -> 3 // 30% 三灵根
            in 0.50..0.75 -> 4 // 25% 四灵根
            else -> 5          // 25% 五灵根
        }
        val spiritRootType = spiritRootTypes.shuffled().take(rootCount).joinToString(",")
        
        val gender = if (Random.nextBoolean()) "male" else "female"

        val talents = TalentDatabase.generateTalentsForDisciple()

        // 计算天赋对寿命的影响
        var lifespanModifier = 1.0
        talents.forEach { talent ->
            talent.effects["lifespan"]?.let { lifespanModifier += it }
        }
        // 基础寿命根据境界计算（炼气期100年）
        val baseLifespan = GameConfig.Realm.get(9).maxAge
        val modifiedLifespan = (baseLifespan * lifespanModifier).toInt().coerceAtLeast(1)

        val baseDisciple = Disciple(
            id = UUID.randomUUID().toString(),
            name = name,
            realm = 9,
            realmLayer = 1,
            cultivation = 0.0,
            spiritRootType = spiritRootType,
            age = Random.nextInt(5, 32),
            lifespan = modifiedLifespan,
            gender = gender,
            talentIds = talents.map { it.id },
            intelligence = Random.nextInt(1, 101),
            charm = Random.nextInt(1, 101),
            loyalty = Random.nextInt(30, 61),
            comprehension = Random.nextInt(1, 101),
            artifactRefining = Random.nextInt(1, 101),
            pillRefining = Random.nextInt(1, 101),
            spiritPlanting = Random.nextInt(1, 101),
            teaching = Random.nextInt(1, 101),
            morality = Random.nextInt(1, 101),
            combatStatsVariance = Random.nextInt(-30, 31)
        )
        return applyTalentBaseFlatBonuses(baseDisciple, talents).let { disciple ->
            val varianceMultiplier = 1.0 + disciple.combatStatsVariance / 100.0
            disciple.copy(
                baseHp = (100 * varianceMultiplier).toInt(),
                baseMp = (50 * varianceMultiplier).toInt(),
                basePhysicalAttack = (10 * varianceMultiplier).toInt(),
                baseMagicAttack = (5 * varianceMultiplier).toInt(),
                basePhysicalDefense = (5 * varianceMultiplier).toInt(),
                baseMagicDefense = (3 * varianceMultiplier).toInt(),
                baseSpeed = (10 * varianceMultiplier).toInt()
            )
        }
    }

    private fun applyTalentBaseFlatBonuses(disciple: Disciple, talents: List<Talent>): Disciple {
        val effects = mutableMapOf<String, Double>()
        talents.forEach { talent ->
            talent.effects.forEach { (key, value) ->
                effects[key] = (effects[key] ?: 0.0) + value
            }
        }

        fun flat(key: String): Int = (effects[key] ?: 0.0).toInt()

        return disciple.copy(
            intelligence = (disciple.intelligence + flat("intelligenceFlat")).coerceAtLeast(1),
            charm = (disciple.charm + flat("charmFlat")).coerceAtLeast(1),
            loyalty = (disciple.loyalty + flat("loyaltyFlat")).coerceAtLeast(1),
            comprehension = (disciple.comprehension + flat("comprehensionFlat")).coerceAtLeast(1),
            artifactRefining = (disciple.artifactRefining + flat("artifactRefiningFlat")).coerceAtLeast(1),
            pillRefining = (disciple.pillRefining + flat("pillRefiningFlat")).coerceAtLeast(1),
            spiritPlanting = (disciple.spiritPlanting + flat("spiritPlantingFlat")).coerceAtLeast(1),
            teaching = (disciple.teaching + flat("teachingFlat")).coerceAtLeast(1),
            morality = (disciple.morality + flat("moralityFlat")).coerceAtLeast(1)
        )
    }
    
    fun generateInitialDisciples(count: Int): List<Disciple> {
        return (1..count).map { generateRandomDisciple(isInitial = true) }
    }
    
    fun generateInitialDisciplesWithRealm(count: Int, realm: Int): List<Disciple> {
        return (1..count).map { 
            generateRandomDisciple(isInitial = true).copy(
                realm = realm,
                realmLayer = 9,
                cultivation = GameConfig.Realm.get(realm).cultivationBase.toDouble() * 9,
                lifespan = GameConfig.Realm.get(realm).maxAge
            )
        }
    }
    
    /**
     * 每年一月自动刷新弟子招募列表
     * 此方法由processMonthlyEvents在每年一月自动调用
     */
    private fun refreshRecruitList(year: Int) {
        val data = _gameData.value
        
        // 检查是否需要刷新（避免重复刷新）
        if (data.lastRecruitYear >= year) {
            return
        }
        
        // 生成新弟子：基础 10-30 名
        val baseMin = 10
        val baseMax = 30
        val recruitCount = (baseMin..baseMax).random()
        val newRecruitList = (1..recruitCount).map { generateRandomDisciple() }
        
        // 保存到 GameData
        _gameData.value = data.copy(
            recruitList = newRecruitList,
            lastRecruitYear = year
        )
        
        // 添加事件通知
        addEvent("新年伊始，有${recruitCount}名求道者前来拜师", EventType.SUCCESS)
    }
    
    /**
     * 获取招募弟子列表
     * 每年一月刷新一次，如果当前年份已经刷新过，则返回已保存的列表
     */
    fun getRecruitList(): List<Disciple> {
        val data = _gameData.value
        return data.recruitList
    }
    
    /**
     * 从招募列表中移除指定弟子
     */
    fun removeFromRecruitList(disciple: Disciple) {
        val data = _gameData.value
        val updatedList = data.recruitList.filter { it.id != disciple.id }
        _gameData.value = data.copy(recruitList = updatedList)
    }
    
    /**
     * 招募所有剩余弟子
     */
    fun recruitAllFromList(): Boolean {
        val data = _gameData.value
        if (data.recruitList.isEmpty()) {
            return false
        }

        val currentMonth = data.gameYear * 12 + data.gameMonth
        var recruitCount = 0
        data.recruitList.forEach { disciple ->
            // 检查弟子是否已经在弟子列表中，防止重复添加
            if (!_disciples.value.any { it.id == disciple.id }) {
                val discipleWithRecruitTime = disciple.copy(
                    recruitedMonth = currentMonth
                )
                _disciples.value = _disciples.value + discipleWithRecruitTime
                recruitCount++
            }
        }
        _gameData.value = data.copy(recruitList = emptyList())
        return recruitCount > 0
    }

    fun getGameData(): GameData = _gameData.value

    fun addDisciple(disciple: Disciple, cost: Long) {
        val data = _gameData.value

        // 检查弟子是否已经在弟子列表中，防止重复添加
        if (_disciples.value.any { it.id == disciple.id }) {
            addEvent("该弟子已经在宗门中了", EventType.WARNING)
            return
        }

        val currentMonth = data.gameYear * 12 + data.gameMonth
        val discipleWithRecruitTime = disciple.copy(
            recruitedMonth = currentMonth
        )
        _disciples.value = _disciples.value + discipleWithRecruitTime
        _gameData.value = _gameData.value.copy(spiritStones = _gameData.value.spiritStones - cost)
    }
    
    fun startExploration(teamName: String, memberIds: List<String>, dungeon: String, duration: Int) {
        val data = _gameData.value
        val dungeonName = GameConfig.Dungeons.get(dungeon)?.name ?: dungeon

        // 检查该秘境是否已有活跃队伍
        val activeTeamInDungeon = _teams.value.find {
            it.dungeon == dungeon &&
            (it.status == ExplorationStatus.TRAVELING || it.status == ExplorationStatus.EXPLORING)
        }
        if (activeTeamInDungeon != null) {
            addEvent("该秘境已有队伍正在探索，无法派遣新队伍", EventType.WARNING)
            return
        }

        // 检查是否有弟子已经在其他队伍中探索
        val exploringMemberIds = _teams.value
            .filter { it.status == ExplorationStatus.TRAVELING || it.status == ExplorationStatus.EXPLORING }
            .flatMap { it.memberIds }
            .toSet()
        val alreadyExploring = memberIds.filter { it in exploringMemberIds }
        if (alreadyExploring.isNotEmpty()) {
            val names = alreadyExploring.mapNotNull { id ->
                _disciples.value.find { it.id == id }?.name
            }.joinToString(", ")
            addEvent("以下弟子已在探索中，无法重复派遣：$names", EventType.WARNING)
            return
        }

        // 检查是否有五岁以下弟子
        val underageDisciples = memberIds.mapNotNull { memberId ->
            _disciples.value.find { it.id == memberId && it.age < 5 }
        }
        if (underageDisciples.isNotEmpty()) {
            val names = underageDisciples.joinToString(", ") { it.name }
            addEvent("以下弟子年龄太小，无法参加探索：$names", EventType.WARNING)
            return
        }

        val team = ExplorationTeam(
            id = UUID.randomUUID().toString(),
            name = teamName,
            memberIds = memberIds,
            dungeon = dungeon,
            dungeonName = dungeonName,
            startYear = data.gameYear,
            startMonth = data.gameMonth,
            duration = duration,
            status = ExplorationStatus.EXPLORING
        )
        
        _teams.value = _teams.value + team
        syncAllDiscipleStatuses()
        
        addEvent("探索队伍【$teamName】出发前往$dungeonName", EventType.INFO)
    }
    
    fun recallTeam(teamId: String) {
        val team = _teams.value.find { it.id == teamId } ?: return
        
        _teams.value = _teams.value.filter { it.id != teamId }
        syncAllDiscipleStatuses()
        addEvent("探索队伍【${team.name}】已召回", EventType.INFO)
    }
    
    fun equipItem(discipleId: String, equipmentId: String) {
        val equip = _equipment.value.find { it.id == equipmentId } ?: return
        val disciple = _disciples.value.find { it.id == discipleId } ?: return
        
        if (disciple.realm > equip.minRealm) {
            val realmName = GameConfig.Realm.getName(equip.minRealm)
            addEvent("${disciple.name} 境界不足，需要达到${realmName}才能装备${equip.name}", EventType.WARNING)
            return
        }
        
        // 检查装备是否已被其他弟子穿戴
        if (equip.isEquipped && equip.ownerId != null && equip.ownerId != discipleId) {
            val currentOwner = _disciples.value.find { it.id == equip.ownerId }
            addEvent("${equip.name} 已被 ${currentOwner?.name ?: "其他弟子"} 装备，请先卸下", EventType.WARNING)
            return
        }
        
        val oldEquipId = when (equip.slot) {
            EquipmentSlot.WEAPON -> disciple.weaponId
            EquipmentSlot.ARMOR -> disciple.armorId
            EquipmentSlot.BOOTS -> disciple.bootsId
            EquipmentSlot.ACCESSORY -> disciple.accessoryId
        }
        
        val updatedDisciple = when (equip.slot) {
            EquipmentSlot.WEAPON -> disciple.copy(weaponId = equipmentId)
            EquipmentSlot.ARMOR -> disciple.copy(armorId = equipmentId)
            EquipmentSlot.BOOTS -> disciple.copy(bootsId = equipmentId)
            EquipmentSlot.ACCESSORY -> disciple.copy(accessoryId = equipmentId)
        }
        
        _disciples.value = _disciples.value.map { if (it.id == discipleId) updatedDisciple else it }
        
        val updatedEquip = equip.copy(ownerId = discipleId, isEquipped = true)
        _equipment.value = _equipment.value.map { if (it.id == equipmentId) updatedEquip else it }
        
        oldEquipId?.let { oldId ->
            val oldEquip = _equipment.value.find { it.id == oldId }
            if (oldEquip != null) {
                _equipment.value = _equipment.value.map {
                    if (it.id == oldId) it.copy(ownerId = null, isEquipped = false) else it
                }
            }
        }
        
        addEvent("${disciple.name} 装备了 ${equip.name}", EventType.INFO)
    }
    
    fun unequipItem(discipleId: String, slot: EquipmentSlot) {
        val disciple = _disciples.value.find { it.id == discipleId } ?: return
        
        val equipId = when (slot) {
            EquipmentSlot.WEAPON -> disciple.weaponId
            EquipmentSlot.ARMOR -> disciple.armorId
            EquipmentSlot.BOOTS -> disciple.bootsId
            EquipmentSlot.ACCESSORY -> disciple.accessoryId
        } ?: return
        
        val updatedDisciple = when (slot) {
            EquipmentSlot.WEAPON -> disciple.copy(weaponId = null)
            EquipmentSlot.ARMOR -> disciple.copy(armorId = null)
            EquipmentSlot.BOOTS -> disciple.copy(bootsId = null)
            EquipmentSlot.ACCESSORY -> disciple.copy(accessoryId = null)
        }
        
        _disciples.value = _disciples.value.map { if (it.id == discipleId) updatedDisciple else it }
        
        _equipment.value = _equipment.value.map {
            if (it.id == equipId) it.copy(ownerId = null, isEquipped = false) else it
        }
        
        val equip = _equipment.value.find { it.id == equipId }
        addEvent("${disciple.name} 卸下了 ${equip?.name}", EventType.INFO)
    }

    fun unequipItemById(discipleId: String, equipmentId: String) {
        val equipment = _equipment.value.find { it.id == equipmentId } ?: return
        unequipItem(discipleId, equipment.slot)
    }
    
    fun learnManual(discipleId: String, manualId: String) {
        val manual = _manuals.value.find { it.id == manualId } ?: return
        val disciple = _disciples.value.find { it.id == discipleId } ?: return
        
        if (disciple.manualIds.contains(manualId)) {
            addEvent("弟子不能学习相同的功法", EventType.WARNING)
            return
        }
        
        if (disciple.realm > manual.minRealm) {
            val realmName = GameConfig.Realm.getName(manual.minRealm)
            addEvent("${disciple.name} 境界不足，需要达到${realmName}才能学习${manual.name}", EventType.WARNING)
            return
        }
        
        val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
        val manualSlotBonus = talentEffects["manualSlot"]?.toInt() ?: 0
        val maxManualSlots = 5 + manualSlotBonus
        
        if (disciple.manualIds.size >= maxManualSlots) {
            addEvent("${disciple.name} 功法槽位已满，无法学习更多功法", EventType.WARNING)
            return
        }
        
        if (manual.type == ManualType.MIND) {
            val hasMindManual = _manuals.value.any { 
                it.id in disciple.manualIds && it.type == ManualType.MIND 
            }
            if (hasMindManual) {
                addEvent("${disciple.name} 已有心法，无法再学习心法", EventType.WARNING)
                return
            }
        }
        
        val updatedDisciple = disciple.copy(manualIds = disciple.manualIds + manualId)
        _disciples.value = _disciples.value.map { if (it.id == discipleId) updatedDisciple else it }
        
        val updatedManual = manual.copy(ownerId = discipleId, isLearned = true)
        _manuals.value = _manuals.value.map { if (it.id == manualId) updatedManual else it }
        
        val data = _gameData.value
        val updatedProficiencies = data.manualProficiencies.toMutableMap()
        val discipleProficiencies = updatedProficiencies[discipleId]?.toMutableList() ?: mutableListOf()
        
        val existingProficiency = discipleProficiencies.find { it.manualId == manualId }
        if (existingProficiency == null) {
            val maxProficiency = ManualProficiencySystem.getMaxProficiency(manual.rarity).toInt()
            discipleProficiencies.add(ManualProficiencyData(
                manualId = manualId,
                manualName = manual.name,
                proficiency = 0.0,
                maxProficiency = maxProficiency,
                level = 1,
                masteryLevel = 0
            ))
            updatedProficiencies[discipleId] = discipleProficiencies
            _gameData.value = data.copy(manualProficiencies = updatedProficiencies)
        }
        
        addEvent("${disciple.name} 学习了 ${manual.name}", EventType.SUCCESS)
    }
    
    fun replaceManual(discipleId: String, oldManualId: String, newManualId: String) {
        val oldManual = _manuals.value.find { it.id == oldManualId } ?: return
        val newManual = _manuals.value.find { it.id == newManualId } ?: return
        val disciple = _disciples.value.find { it.id == discipleId } ?: return
        
        if (!disciple.manualIds.contains(oldManualId)) {
            addEvent("${disciple.name} 没有学习 ${oldManual.name}", EventType.WARNING)
            return
        }
        
        if (disciple.manualIds.contains(newManualId)) {
            addEvent("弟子不能学习相同的功法", EventType.WARNING)
            return
        }
        
        if (disciple.realm > newManual.minRealm) {
            val realmName = GameConfig.Realm.getName(newManual.minRealm)
            addEvent("${disciple.name} 境界不足，需要达到${realmName}才能学习${newManual.name}", EventType.WARNING)
            return
        }
        
        if (newManual.type == ManualType.MIND && oldManual.type != ManualType.MIND) {
            val hasMindManual = _manuals.value.any { 
                it.id in disciple.manualIds && it.type == ManualType.MIND 
            }
            if (hasMindManual) {
                addEvent("${disciple.name} 已有心法，无法再学习心法", EventType.WARNING)
                return
            }
        }
        
        val updatedManualIds = disciple.manualIds.filter { it != oldManualId } + newManualId
        val updatedDisciple = disciple.copy(manualIds = updatedManualIds)
        _disciples.value = _disciples.value.map { if (it.id == discipleId) updatedDisciple else it }
        
        _manuals.value = _manuals.value.map { 
            when (it.id) {
                oldManualId -> it.copy(ownerId = null, isLearned = false)
                newManualId -> it.copy(ownerId = discipleId, isLearned = true)
                else -> it
            }
        }
        
        val data = _gameData.value
        val updatedProficiencies = data.manualProficiencies.toMutableMap()
        val discipleProficiencies = updatedProficiencies[discipleId]?.toMutableList() ?: mutableListOf()
        
        discipleProficiencies.removeAll { it.manualId == oldManualId }
        
        val existingNewProficiency = discipleProficiencies.find { it.manualId == newManualId }
        if (existingNewProficiency == null) {
            val maxProficiency = ManualProficiencySystem.getMaxProficiency(newManual.rarity).toInt()
            discipleProficiencies.add(ManualProficiencyData(
                manualId = newManualId,
                manualName = newManual.name,
                proficiency = 0.0,
                maxProficiency = maxProficiency,
                level = 1,
                masteryLevel = 0
            ))
        }
        
        updatedProficiencies[discipleId] = discipleProficiencies
        _gameData.value = data.copy(manualProficiencies = updatedProficiencies)
        
        addEvent("${disciple.name} 将 ${oldManual.name} 更换为 ${newManual.name}", EventType.SUCCESS)
    }

    fun forgetManual(discipleId: String, manualId: String) {
        val manual = _manuals.value.find { it.id == manualId } ?: return
        val disciple = _disciples.value.find { it.id == discipleId } ?: return

        if (!disciple.manualIds.contains(manualId)) {
            addEvent("${disciple.name} 没有学习 ${manual.name}", EventType.WARNING)
            return
        }

        val updatedDisciple = disciple.copy(
            manualIds = disciple.manualIds.filter { it != manualId }
        )
        _disciples.value = _disciples.value.map { if (it.id == discipleId) updatedDisciple else it }

        val updatedManual = manual.copy(ownerId = null, isLearned = false)
        _manuals.value = _manuals.value.map { if (it.id == manualId) updatedManual else it }

        val data = _gameData.value
        val updatedProficiencies = data.manualProficiencies.toMutableMap()
        val discipleProficiencies = updatedProficiencies[discipleId]?.toMutableList()
        if (discipleProficiencies != null) {
            discipleProficiencies.removeAll { it.manualId == manualId }
            updatedProficiencies[discipleId] = discipleProficiencies
            _gameData.value = data.copy(manualProficiencies = updatedProficiencies)
        }

        addEvent("${disciple.name} 遗忘了 ${manual.name}", EventType.INFO)
    }

    fun usePill(discipleId: String, pillId: String) {
        val pill = _pills.value.find { it.id == pillId } ?: return
        val disciple = _disciples.value.find { it.id == discipleId } ?: return

        if (disciple.status == DiscipleStatus.REFLECTING) {
            addEvent("思过中的弟子无法使用丹药！", EventType.WARNING)
            return
        }

        if (pill.quantity <= 0) {
            addEvent("丹药数量不足！", EventType.WARNING)
            return
        }

        var updatedDisciple = disciple

        when (pill.category) {
            PillCategory.BREAKTHROUGH -> {
                if (disciple.canBreakthrough()) {
                    val chance = disciple.getBreakthroughChance() + pill.breakthroughChance
                    if (Random.nextDouble() < chance) {
                        val newRealm = disciple.realm - 1
                        updatedDisciple = disciple.copy(
                            realm = newRealm,
                            cultivation = 0.0,
                            breakthroughCount = disciple.breakthroughCount + 1
                        )
                        addEvent("${disciple.name} 服用${pill.name}突破成功！", EventType.SUCCESS)
                    } else {
                        addEvent("${disciple.name} 服用${pill.name}突破失败，修为不足或机缘未到", EventType.INFO)
                    }
                } else {
                    addEvent("${disciple.name} 修为不足，无法突破", EventType.WARNING)
                    return  // 不消耗丹药
                }
            }
            PillCategory.CULTIVATION -> {
                // 修炼丹：增加修为和修炼速度
                val cultivationGain = (disciple.maxCultivation * pill.cultivationPercent).toInt()
                val shouldUpdateSpeedEffect = pill.cultivationSpeed > 1.0 && pill.duration > 0
                updatedDisciple = disciple.copy(
                    cultivation = disciple.cultivation + cultivationGain,
                    totalCultivation = disciple.totalCultivation + cultivationGain,
                    cultivationSpeedBonus = if (shouldUpdateSpeedEffect) pill.cultivationSpeed else disciple.cultivationSpeedBonus,
                    cultivationSpeedDuration = if (shouldUpdateSpeedEffect) pill.duration else disciple.cultivationSpeedDuration
                )
                val msg = buildList {
                    if (cultivationGain > 0) add("修为增加${cultivationGain}")
                    if (shouldUpdateSpeedEffect) add("修炼速度提升${GameUtils.formatPercent(pill.cultivationSpeed - 1)}")
                }.joinToString("，")
                addEvent("${disciple.name} 服用${pill.name}，$msg", EventType.SUCCESS)
            }
            PillCategory.BATTLE_PHYSICAL, PillCategory.BATTLE_MAGIC, PillCategory.BATTLE_STATUS -> {
                // 战斗丹：应用临时属性加成
                updatedDisciple = disciple.copy(
                    pillPhysicalAttackBonus = pill.physicalAttackPercent,
                    pillMagicAttackBonus = pill.magicAttackPercent,
                    pillPhysicalDefenseBonus = pill.physicalDefensePercent,
                    pillMagicDefenseBonus = pill.magicDefensePercent,
                    pillHpBonus = pill.hpPercent,
                    pillMpBonus = pill.mpPercent,
                    pillSpeedBonus = pill.speedPercent,
                    pillEffectDuration = pill.battleCount
                )
                val bonusDesc = buildList {
                    if (pill.physicalAttackPercent > 0) add("物攻+${GameUtils.formatPercent(pill.physicalAttackPercent)}")
                    if (pill.magicAttackPercent > 0) add("法攻+${GameUtils.formatPercent(pill.magicAttackPercent)}")
                    if (pill.physicalDefensePercent > 0) add("物防+${GameUtils.formatPercent(pill.physicalDefensePercent)}")
                    if (pill.magicDefensePercent > 0) add("法防+${GameUtils.formatPercent(pill.magicDefensePercent)}")
                    if (pill.hpPercent > 0) add("生命+${GameUtils.formatPercent(pill.hpPercent)}")
                    if (pill.mpPercent > 0) add("法力+${GameUtils.formatPercent(pill.mpPercent)}")
                    if (pill.speedPercent > 0) add("速度+${GameUtils.formatPercent(pill.speedPercent)}")
                }.joinToString(", ")
                addEvent("${disciple.name} 服用${pill.name}，$bonusDesc，持续${pill.battleCount}场战斗", EventType.SUCCESS)
            }
            PillCategory.HEALING -> {
                // 治疗丹：恢复生命值和法力值
                val currentStats = disciple.getBaseStats()
                val healAmount = if (pill.healMaxHpPercent > 0) {
                    (currentStats.maxHp * pill.healMaxHpPercent).toInt()
                } else pill.heal
                val mpRecoverAmount = if (pill.mpRecoverMaxMpPercent > 0) {
                    (currentStats.maxMp * pill.mpRecoverMaxMpPercent).toInt()
                } else 0

                // 注意：这里只是记录治疗效果，实际HP/MP需要在战斗系统中处理
                updatedDisciple = disciple.copy(
                    lifespan = disciple.lifespan + pill.extendLife
                )

                val effects = buildList {
                    if (healAmount > 0) add("恢复${healAmount}点生命")
                    if (mpRecoverAmount > 0) add("恢复${mpRecoverAmount}点法力")
                    if (pill.extendLife > 0) add("寿命延长${pill.extendLife}年")
                    if (pill.revive) add("复活效果")
                    if (pill.clearAll) add("清除所有负面状态")
                }.joinToString(", ")
                addEvent("${disciple.name} 服用${pill.name}，$effects", EventType.SUCCESS)
            }
        }

        _disciples.value = _disciples.value.map { if (it.id == discipleId) updatedDisciple else it }

        val updatedPill = pill.copy(quantity = pill.quantity - 1)
        if (updatedPill.quantity <= 0) {
            _pills.value = _pills.value.filter { it.id != pillId }
        } else {
            _pills.value = _pills.value.map { if (it.id == pillId) updatedPill else it }
        }
        
        syncListedItemsWithInventory()
    }
    
    fun exploreSect(sectId: String) {
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId } ?: return
        
        if (sect.isPlayerSect) {
            addEvent("这是你的宗门", EventType.INFO)
            return
        }
        
        if (sect.discovered) {
            addEvent("已经探索过${sect.name}", EventType.INFO)
            return
        }
        
        val cost = 500L
        if (data.spiritStones < cost) {
            addEvent("灵石不足，无法探索！", EventType.WARNING)
            return
        }
        
        _gameData.value = _gameData.value.copy(
            spiritStones = _gameData.value.spiritStones - cost,
            worldMapSects = data.worldMapSects.map { 
                if (it.id == sectId) it.copy(discovered = true) else it 
            },
            exploredSects = data.exploredSects + (sectId to ExploredSectInfo(
                year = data.gameYear,
                month = data.gameMonth
            ))
        )
        
        addEvent("探索${sect.name}成功！${sect.levelName}，最高境界弟子${sect.disciples.filter { it.key <= (listOf(5, 3, 1, 0)[sect.level]) }.values.sum()}人", EventType.SUCCESS)
    }
    
    fun interactWithSect(sectId: String, action: String) {
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId } ?: return
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val currentFavor = if (playerSect != null) {
            data.sectRelations.find { 
                (it.sectId1 == playerSect.id && it.sectId2 == sectId) ||
                (it.sectId1 == sectId && it.sectId2 == playerSect.id)
            }?.favor ?: 0
        } else 0
        
        when (action) {
            "gift" -> {
                val giftCost = 1000L
                if (data.spiritStones >= giftCost) {
                    val newFavor = minOf(100, currentFavor + 10)
                    val updatedRelations = if (playerSect != null) {
                        updateSectRelationFavor(data.sectRelations, playerSect.id, sectId, newFavor, data.gameYear)
                    } else {
                        data.sectRelations
                    }
                    _gameData.value = _gameData.value.copy(
                        spiritStones = _gameData.value.spiritStones - giftCost,
                        sectRelations = updatedRelations
                    )
                    addEvent("向${sect.name}送礼，好感度+10", EventType.SUCCESS)
                } else {
                    addEvent("灵石不足，无法送礼", EventType.WARNING)
                }
            }
            "ally" -> {
                if (currentFavor >= 90) {
                    addEvent("与${sect.name}结为盟友", EventType.SUCCESS)
                    _gameData.value = data.copy(
                        worldMapSects = data.worldMapSects.map { 
                            if (it.id == sectId) it.copy(allianceId = "player_alliance_${System.currentTimeMillis()}", allianceStartYear = _gameData.value.gameYear) else it 
                        }
                    )
                } else {
                    addEvent("${sect.name}好感度不足90，拒绝结盟", EventType.WARNING)
                }
            }
        }
    }
    
    private fun generateMerchantItems(year: Int, refreshCount: Int): Pair<List<MerchantItem>, Int> {
        val items = mutableListOf<MerchantItem>()
        val random = Random(System.currentTimeMillis() + refreshCount)
        
        // 固定生成 40 件商品
        val itemCount = 40
        
        // 计算品阶概率调整（每100年调整一次）
        val centuryCount = year / 100
        val adjustment = centuryCount * 3 // 每百年调整3%
        
        // 基础概率：1阶75%, 2阶60%, 3阶22.6%, 4阶7%, 5阶2.8%, 6阶0.6%
        // 每100年：1/2/3阶减少3%，4/5/6阶增加3%
        val baseProbabilities = listOf(75.0, 60.0, 22.6, 7.0, 2.8, 0.6)
        val adjustedProbabilities = baseProbabilities.mapIndexed { index, prob ->
            when {
                index < 3 -> maxOf(0.0, prob - adjustment) // 1/2/3阶减少
                else -> minOf(100.0, prob + adjustment) // 4/5/6阶增加
            }
        }
        
        // 用于记录已生成的商品名称，防止重复
        val generatedNames = mutableSetOf<String>()
        
        // 每第十次刷新必出一个天品道具
        val isTenthRefresh = (refreshCount + 1) % 10 == 0
        var guaranteedItemAdded = false
        
        // 最多尝试生成 itemCount * 3 次，防止无限循环
        var attempts = 0
        val maxAttempts = itemCount * 3
        
        while (items.size < itemCount && attempts < maxAttempts) {
            attempts++
            
            // 如果是第十次刷新且还没添加天品，强制生成一个天品
            val rarity = if (isTenthRefresh && !guaranteedItemAdded) {
                6 // 天品
            } else {
                generateRarityByProbability(adjustedProbabilities, random)
            }
            
            val type = listOf("equipment", "manual", "pill", "material", "herb", "seed").random(random)
            
            val item = when (type) {
                "equipment" -> {
                    val equipment = EquipmentDatabase.generateRandom(rarity, rarity)
                    val basePrice = GameConfig.Rarity.get(rarity).basePrice
                    val price = (basePrice.toDouble() * random.nextInt(700, 1301) / 1000.0).toInt()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = equipment.name,
                        type = "equipment",
                        itemId = equipment.id,
                        rarity = equipment.rarity,
                        price = price,
                        quantity = 1,
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "manual" -> {
                    val manual = ManualDatabase.generateRandom(rarity, rarity)
                    val basePrice = GameConfig.Rarity.get(rarity).basePrice
                    val price = (basePrice.toDouble() * random.nextInt(700, 1301) / 1000.0).toInt()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = manual.name,
                        type = "manual",
                        itemId = manual.id,
                        rarity = manual.rarity,
                        price = price,
                        quantity = 1,
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "pill" -> {
                    val pillTemplates = ItemDatabase.getPillsByRarity(rarity)
                    if (pillTemplates.isEmpty()) continue
                    val template = pillTemplates.random(random)
                    val pill = ItemDatabase.createPillFromTemplate(template)
                    val basePrice = (GameConfig.Rarity.get(rarity).basePrice * 0.8).toInt()
                    val price = (basePrice.toDouble() * random.nextInt(700, 1301) / 1000.0).toInt()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = pill.name,
                        type = "pill",
                        itemId = pill.id,
                        rarity = pill.rarity,
                        price = price,
                        quantity = random.nextInt(1, 4),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "material" -> {
                    val materials = BeastMaterialDatabase.getMaterialsByRarity(rarity)
                    if (materials.isEmpty()) continue
                    val material = materials.random(random)
                    val basePrice = (GameConfig.Rarity.get(rarity).basePrice * 0.05).toInt()
                    val price = (basePrice.toDouble() * random.nextInt(700, 1301) / 1000.0).toInt()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = material.name,
                        type = "material",
                        itemId = material.id,
                        rarity = material.rarity,
                        price = price,
                        quantity = random.nextInt(1, 16),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "herb" -> {
                    val herbs = HerbDatabase.getByRarity(rarity)
                    if (herbs.isEmpty()) continue
                    val herb = herbs.random(random)
                    val basePrice = (GameConfig.Rarity.get(rarity).basePrice * 0.05).toInt()
                    val price = (basePrice.toDouble() * random.nextInt(700, 1301) / 1000.0).toInt()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = herb.name,
                        type = "herb",
                        itemId = herb.id,
                        rarity = herb.rarity,
                        price = price,
                        quantity = random.nextInt(1, 16),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "seed" -> {
                    val seeds = HerbDatabase.getSeedsByRarity(rarity)
                    if (seeds.isEmpty()) continue
                    val seed = seeds.random(random)
                    val basePrice = (GameConfig.Rarity.get(rarity).basePrice * 0.05).toInt()
                    val price = (basePrice.toDouble() * random.nextInt(700, 1301) / 1000.0).toInt()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = seed.name,
                        type = "seed",
                        itemId = seed.id,
                        rarity = seed.rarity,
                        price = price,
                        quantity = random.nextInt(1, 16),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                else -> continue
            }
            
            // 检查是否已存在相同名称的商品，避免重复
            if (!generatedNames.contains(item.name)) {
                generatedNames.add(item.name)
                items.add(item)
                
                // 如果是第十次刷新且添加的是天品，标记已添加
                if (isTenthRefresh && !guaranteedItemAdded && item.rarity == 6) {
                    guaranteedItemAdded = true
                }
            }
        }
        
        // 按品阶排序（高品阶在前）
        items.sortByDescending { it.rarity }
        
        return Pair(items, refreshCount + 1)
    }
    
    private fun generateRarityByProbability(probabilities: List<Double>, random: Random): Int {
        val totalProbability = probabilities.sum().toInt()
        if (totalProbability <= 0) return 1 // 防止总概率为 0 或负数
        
        var randomValue = random.nextInt(totalProbability)
        
        for ((index, prob) in probabilities.withIndex()) {
            randomValue -= prob.toInt()
            if (randomValue < 0) {
                return index + 1 // 返回品阶（1-6）
            }
        }
        
        return 1 // 默认返回 1 阶
    }
    
    fun refreshMerchant() {
        val data = _gameData.value
        if (data.gameYear == data.merchantLastRefreshYear) {
            addEvent("云游商人今年已经来过了", EventType.INFO)
            return
        }
        
        val (items, newCount) = generateMerchantItems(
            data.gameYear,
            data.merchantRefreshCount
        )
        _gameData.value = data.copy(
            travelingMerchantItems = items,
            merchantLastRefreshYear = data.gameYear,
            merchantRefreshCount = newCount
        )
        addEvent("云游商人到访！带来了${items.size}件商品", EventType.SUCCESS)
    }
    
    private fun generateEquipmentName(rarity: Int, slot: EquipmentSlot): String {
        val prefixes = listOf("凡品", "灵品", "宝品", "玄品", "地品", "天品")
        val suffixes = when(slot) {
            EquipmentSlot.WEAPON -> listOf("剑", "刀", "杖", "枪", "戟")
            EquipmentSlot.ARMOR -> listOf("甲", "铠", "袍", "衣", "衫")
            EquipmentSlot.BOOTS -> listOf("靴", "鞋", "履", "屐", "屣")
            EquipmentSlot.ACCESSORY -> listOf("戒", "环", "链", "坠", "符")
        }
        return "${prefixes.getOrElse(rarity - 1) { "凡品" }}${suffixes.random()}"
    }
    
    private fun generateManualName(rarity: Int): String {
        val prefixes = listOf("凡品", "灵品", "宝品", "玄品", "地品", "天品")
        val suffixes = listOf("心法", "剑诀", "真诀", "秘典", "宝鉴")
        return "${prefixes.getOrElse(rarity - 1) { "入门" }}${suffixes.random()}"
    }
    
    private fun generateMaterialName(rarity: Int): String {
        val names = listOf("灵草", "矿石", "兽骨", "灵石", "灵木")
        val suffixes = listOf("碎片", "精华", "结晶", "粉末", "块")
        return "${names.random()}${suffixes.random()}"
    }
    
    private fun generatePillName(rarity: Int): String {
        val prefixes = listOf("凡品", "灵品", "宝品", "玄品", "地品", "天品")
        val suffixes = listOf("丹", "丸", "散", "膏", "露")
        return "${prefixes.getOrElse(rarity - 1) { "凡品" }}${suffixes.random()}"
    }

    private fun generateEquipmentName(type: String, rarity: Int): String {
        val prefixes = listOf("凡品", "灵品", "宝品", "玄品", "地品", "天品")
        val suffixes = when(type) {
            "weapon" -> listOf("剑", "刀", "杖", "枪", "戟")
            "armor" -> listOf("甲", "铠", "袍", "衣", "衫")
            "boots" -> listOf("靴", "鞋", "履", "屐", "屣")
            "ring" -> listOf("戒", "环", "链", "坠", "符")
            else -> listOf("器", "物", "宝")
        }
        return "${prefixes.getOrElse(rarity - 1) { "凡品" }}${suffixes.random()}"
    }

    private fun generatePillName(type: String, rarity: Int): String {
        val prefixes = listOf("凡品", "灵品", "宝品", "玄品", "地品", "天品")
        val suffixes = when(type) {
            "cultivation" -> listOf("聚气丹", "筑基丹", "金丹", "元婴丹", "化神丹")
            "healing" -> listOf("回春丹", "疗伤丹", "续命丹", "还魂丹", "重生丹")
            "strength" -> listOf("力王丹", "金刚丹", "霸体丹", "神力丹", "破天丹")
            "agility" -> listOf("轻身丹", "疾风丹", "闪电丹", "幻影丹", "瞬移丹")
            else -> listOf("丹", "丸", "散")
        }
        return "${prefixes.getOrElse(rarity - 1) { "凡品" }}${suffixes.random()}"
    }

    private fun generateManualName(type: String, rarity: Int): String {
        val prefixes = listOf("基础", "进阶", "玄妙", "天机", "无上", "混沌")
        val typeNames = when(type) {
            "attack" -> listOf("剑诀", "刀法", "拳谱", "枪术", "戟法")
            "defense" -> listOf("盾术", "护体", "金钟", "铁布", "金刚")
            "support" -> listOf("心法", "真诀", "秘典", "宝鉴", "真经")
            "movement" -> listOf("身法", "步法", "轻功", "遁术", "瞬移")
            "production" -> listOf("炼器", "炼丹", "阵法", "符箓", "灵植")
            else -> listOf("功法", "秘籍", "典籍")
        }
        return "${prefixes.getOrElse(rarity - 1) { "入门" }}${typeNames.random()}"
    }

    fun buyMerchantItem(itemId: String, quantity: Int = 1) {
        val data = _gameData.value
        val item = data.travelingMerchantItems.find { it.id == itemId } ?: return
        
        val actualQuantity = minOf(quantity, item.quantity)
        val totalPrice = item.price * actualQuantity
        
        if (data.spiritStones < totalPrice) {
            addEvent("灵石不足，无法购买${item.name}", EventType.WARNING)
            return
        }
        
        val updatedItems = if (item.quantity > actualQuantity) {
            data.travelingMerchantItems.map { 
                if (it.id == itemId) it.copy(quantity = it.quantity - actualQuantity)
                else it
            }
        } else {
            data.travelingMerchantItems.filter { it.id != itemId }
        }
        
        _gameData.value = _gameData.value.copy(
            spiritStones = _gameData.value.spiritStones - totalPrice,
            travelingMerchantItems = updatedItems
        )
        
        for (i in 0 until actualQuantity) {
            when (item.type) {
                "equipment" -> {
                    val equipment = createEquipmentFromMerchantItem(item)
                    // 检查是否已存在相同 id 的装备，避免重复
                    if (_equipment.value.none { it.id == equipment.id }) {
                        _equipment.value = _equipment.value + equipment
                    }
                }
                "manual" -> {
                    val manual = createManualFromMerchantItem(item)
                    // 检查是否已存在相同 id 的功法，避免重复
                    if (_manuals.value.none { it.id == manual.id }) {
                        _manuals.value = _manuals.value + manual
                    }
                }
                "pill" -> {
                    val pill = createPillFromMerchantItem(item)
                    addPillToWarehouse(pill)
                }
                "material" -> {
                    val material = createMaterialFromMerchantItem(item)
                    addMaterialToWarehouse(material)
                }
                "herb" -> {
                    val herb = createHerbFromMerchantItem(item)
                    addHerbToWarehouse(herb)
                }
                "seed" -> {
                    val seed = createSeedFromMerchantItem(item)
                    addSeedToWarehouse(seed)
                }
            }
        }
        
        addEvent("购买了${item.name} x$actualQuantity", EventType.SUCCESS)
    }
    
    fun addToSellList(itemId: String, itemType: String, price: Int) {
        val data = _gameData.value
        
        val itemName: String
        val rarity: Int
        
        when (itemType) {
            "equipment" -> {
                val equip = _equipment.value.find { it.id == itemId } ?: return
                itemName = equip.name
                rarity = equip.rarity
                _equipment.value = _equipment.value.filter { it.id != itemId }
            }
            "manual" -> {
                val manual = _manuals.value.find { it.id == itemId } ?: return
                itemName = manual.name
                rarity = manual.rarity
                _manuals.value = _manuals.value.filter { it.id != itemId }
            }
            "pill" -> {
                val pill = _pills.value.find { it.id == itemId } ?: return
                itemName = pill.name
                rarity = pill.rarity
                _pills.value = _pills.value.filter { it.id != itemId }
            }
            else -> return
        }
        
        val tradingItem = TradingItem(
            id = UUID.randomUUID().toString(),
            itemId = itemId,
            name = itemName,
            type = itemType,
            rarity = rarity,
            price = price,
            quantity = 1
        )
        
        _gameData.value = data.copy(
            tradingSellList = data.tradingSellList + tradingItem
        )
        
        addEvent("已将${itemName}上架出售，价格${price}灵石", EventType.INFO)
    }

    fun sellItems(itemIds: List<String>, totalPrice: Int) {
        val data = _gameData.value

        // Remove equipment
        _equipment.value = _equipment.value.filter { it.id !in itemIds }

        // Remove manuals
        _manuals.value = _manuals.value.filter { it.id !in itemIds }

        // Remove pills
        _pills.value = _pills.value.filter { it.id !in itemIds }

        // Remove materials
        _materials.value = _materials.value.filter { it.id !in itemIds }

        // Remove herbs
        _herbs.value = _herbs.value.filter { it.id !in itemIds }

        // Remove seeds
        _seeds.value = _seeds.value.filter { it.id !in itemIds }

        // Add spirit stones
        _gameData.value = _gameData.value.copy(
            spiritStones = _gameData.value.spiritStones + totalPrice
        )

        addEvent("成功出售 ${itemIds.size} 件道具，获得 $totalPrice 灵石", EventType.SUCCESS)
        
        syncListedItemsWithInventory()
    }

    // 出售装备
    fun sellEquipment(equipmentId: String) {
        _equipment.value = _equipment.value.filter { it.id != equipmentId }
    }

    // 出售功法
    fun sellManual(manualId: String) {
        _manuals.value = _manuals.value.filter { it.id != manualId }
    }

    // 出售丹药
    fun sellPill(pillId: String, quantity: Int) {
        val pill = _pills.value.find { it.id == pillId } ?: return
        if (pill.quantity <= quantity) {
            _pills.value = _pills.value.filter { it.id != pillId }
        } else {
            _pills.value = _pills.value.map {
                if (it.id == pillId) it.copy(quantity = it.quantity - quantity) else it
            }
        }
    }

    // 出售材料
    fun sellMaterial(materialId: String, quantity: Int) {
        val material = _materials.value.find { it.id == materialId } ?: return
        if (material.quantity <= quantity) {
            _materials.value = _materials.value.filter { it.id != materialId }
        } else {
            _materials.value = _materials.value.map {
                if (it.id == materialId) it.copy(quantity = it.quantity - quantity) else it
            }
        }
    }

    // 出售灵药
    fun sellHerb(herbId: String, quantity: Int) {
        val herb = _herbs.value.find { it.id == herbId } ?: return
        if (herb.quantity <= quantity) {
            _herbs.value = _herbs.value.filter { it.id != herbId }
        } else {
            _herbs.value = _herbs.value.map {
                if (it.id == herbId) it.copy(quantity = it.quantity - quantity) else it
            }
        }
    }

    // 出售种子
    fun sellSeed(seedId: String, quantity: Int) {
        val seed = _seeds.value.find { it.id == seedId } ?: return
        if (seed.quantity <= quantity) {
            _seeds.value = _seeds.value.filter { it.id != seedId }
        } else {
            _seeds.value = _seeds.value.map {
                if (it.id == seedId) it.copy(quantity = it.quantity - quantity) else it
            }
        }
    }

    // 增加灵石
    fun addSpiritStones(amount: Int) {
        val currentData = _gameData.value
        _gameData.value = currentData.copy(
            spiritStones = currentData.spiritStones + amount
        )
    }

    fun removeFromSellList(tradingItemId: String) {
        val data = _gameData.value
        val item = data.tradingSellList.find { it.id == tradingItemId } ?: return

        when (item.type) {
            "equipment" -> {
                val equipment = createEquipmentFromTradingItem(item)
                // 检查是否已存在相同 id 的装备，避免重复
                if (_equipment.value.none { it.id == equipment.id }) {
                    _equipment.value = _equipment.value + equipment
                }
            }
            "manual" -> {
                val manual = createManualFromTradingItem(item)
                // 检查是否已存在相同 id 的功法，避免重复
                if (_manuals.value.none { it.id == manual.id }) {
                    _manuals.value = _manuals.value + manual
                }
            }
            "pill" -> {
                val pill = createPillFromTradingItem(item)
                addPillToWarehouse(pill)
            }
        }
        
        _gameData.value = data.copy(
            tradingSellList = data.tradingSellList.filter { it.id != tradingItemId }
        )
        
        addEvent("已下架${item.name}", EventType.INFO)
    }
    
    fun addToBuyList(itemId: String, itemName: String, itemType: String, rarity: Int, price: Int) {
        val data = _gameData.value
        
        val existingItem = data.tradingBuyList.find { it.itemId == itemId }
        if (existingItem != null) {
            addEvent("${itemName}已在收购列表中", EventType.WARNING)
            return
        }
        
        val tradingItem = TradingItem(
            id = UUID.randomUUID().toString(),
            itemId = itemId,
            name = itemName,
            type = itemType,
            rarity = rarity,
            price = price,
            quantity = 1
        )
        
        _gameData.value = data.copy(
            tradingBuyList = data.tradingBuyList + tradingItem
        )
        
        addEvent("开始收购${itemName}，价格${price}灵石", EventType.INFO)
    }
    
    fun removeFromBuyList(tradingItemId: String) {
        val data = _gameData.value
        val item = data.tradingBuyList.find { it.id == tradingItemId } ?: return
        
        _gameData.value = data.copy(
            tradingBuyList = data.tradingBuyList.filter { it.id != tradingItemId }
        )
        
        addEvent("取消了对${item.name}的收购", EventType.INFO)
    }
    
    fun processAutoTrading() {
        val data = _gameData.value
        if (data.tradingBuyList.isEmpty()) return
        
        val buyList = data.tradingBuyList
        
        for (buyItem in buyList) {
            for (disciple in _disciples.value.filter { it.isAlive }) {
                if (disciple.storageBagItems.isEmpty()) continue
                
                val itemIndex = disciple.storageBagItems.indexOfFirst { 
                    it.itemId == buyItem.itemId
                }
                
                if (itemIndex >= 0) {
                    val storageItem = disciple.storageBagItems[itemIndex]
                    val quantity = storageItem.quantity
                    val totalPrice = buyItem.price * quantity
                    
                    if (data.spiritStones >= totalPrice) {
                        _gameData.value = _gameData.value.copy(
                            spiritStones = _gameData.value.spiritStones - totalPrice
                        )
                        
                        val updatedItems = disciple.storageBagItems.toMutableList()
                        updatedItems.removeAt(itemIndex)
                        _disciples.value = _disciples.value.map { 
                            if (it.id == disciple.id) it.copy(storageBagItems = updatedItems) else it 
                        }
                        
                        when (storageItem.itemType) {
                            "equipment" -> {
                                val equipment = createEquipmentFromStorageItem(storageItem)
                                // 检查是否已存在相同 id 的装备，避免重复
                                if (_equipment.value.none { it.id == equipment.id }) {
                                    _equipment.value = _equipment.value + equipment
                                }
                            }
                            "manual" -> {
                                val manual = createManualFromStorageItem(storageItem)
                                // 检查是否已存在相同 id 的功法，避免重复
                                if (_manuals.value.none { it.id == manual.id }) {
                                    _manuals.value = _manuals.value + manual
                                }
                            }
                            "pill" -> {
                                val pill = createPillFromStorageItem(storageItem, quantity)
                                addPillToWarehouse(pill)
                            }
                        }
                        
                        addEvent("交易堂从${disciple.name}处收购了${quantity}个${storageItem.name}", EventType.SUCCESS)
                    }
                }
            }
        }
    }
    
    suspend fun listItemsToMerchant(items: List<Pair<String, Int>>) {
        transactionMutex.withLock {
            val data = _gameData.value
            val newPlayerListedItems = data.playerListedItems.toMutableList()
            val existingItemIds = data.playerListedItems.map { it.itemId }.toSet()
            var successCount = 0
            
            items.forEach { (itemId, quantity) ->
                if (itemId in existingItemIds) {
                    addEvent("物品已上架，跳过", EventType.WARNING)
                    return@forEach
                }
                
                val equipment = _equipment.value.find { it.id == itemId }
                val manual = _manuals.value.find { it.id == itemId }
                val pill = _pills.value.find { it.id == itemId }
                val material = _materials.value.find { it.id == itemId }
                val herb = _herbs.value.find { it.id == itemId }
                val seed = _seeds.value.find { it.id == itemId }
                
                val itemData = equipment ?: manual ?: pill ?: material ?: herb ?: seed
                if (itemData == null) {
                    addEvent("找不到物品: ${itemData?.name ?: itemId}", EventType.WARNING)
                    return@forEach
                }
                
                val actualQuantity = when (itemData) {
                    is Pill -> minOf(quantity, itemData.quantity)
                    is Material -> minOf(quantity, itemData.quantity)
                    is Herb -> minOf(quantity, itemData.quantity)
                    is Seed -> minOf(quantity, itemData.quantity)
                    else -> 1
                }
                
                val basePrice = when (itemData) {
                    is Equipment -> itemData.basePrice
                    is Manual -> itemData.basePrice
                    is Pill -> itemData.basePrice
                    is Material -> itemData.basePrice
                    is Herb -> itemData.basePrice
                    is Seed -> itemData.basePrice
                    else -> 0
                }
                val sellPrice = (basePrice * 0.8).toInt().coerceAtLeast(1)
                
                val merchantItem = MerchantItem(
                    id = UUID.randomUUID().toString(),
                    name = itemData.name,
                    type = when (itemData) {
                        is Equipment -> "equipment"
                        is Manual -> "manual"
                        is Pill -> "pill"
                        is Material -> "material"
                        is Herb -> "herb"
                        is Seed -> "seed"
                        else -> "material"
                    },
                    itemId = itemData.id,
                    rarity = itemData.rarity,
                    price = sellPrice,
                    quantity = actualQuantity,
                    description = itemData.description,
                    data = itemData,
                    obtainedYear = data.gameYear,
                    obtainedMonth = data.gameMonth
                )
                
                newPlayerListedItems.add(merchantItem)
                successCount++
            }
            
            _gameData.value = data.copy(
                playerListedItems = newPlayerListedItems
            )

            if (successCount > 0) {
                addEvent("成功上架${successCount}件物品", EventType.SUCCESS)
            }

            syncListedItemsWithInventory()
        }
    }
    
    suspend fun removePlayerListedItem(itemId: String) {
        transactionMutex.withLock {
            val data = _gameData.value
            val item = data.playerListedItems.find { it.id == itemId } ?: return@withLock
            
            val updatedItems = data.playerListedItems.filter { it.id != itemId }
            
            _gameData.value = data.copy(
                playerListedItems = updatedItems
            )
            
            addEvent("下架了${item.name}", EventType.INFO)
        }
    }
    
    private fun removeItemFromInventory(item: MerchantItem) {
        when (item.type) {
            "equipment" -> {
                _equipment.value = _equipment.value.filter { it.id != item.itemId }
            }
            "manual" -> {
                _manuals.value = _manuals.value.filter { it.id != item.itemId }
            }
            "pill" -> {
                val existingPill = _pills.value.find { it.id == item.itemId }
                if (existingPill != null) {
                    if (existingPill.quantity <= 1) {
                        _pills.value = _pills.value.filter { it.id != item.itemId }
                    } else {
                        _pills.value = _pills.value.map {
                            if (it.id == item.itemId) it.copy(quantity = it.quantity - 1)
                            else it
                        }
                    }
                }
            }
            "material" -> {
                val existingMaterial = _materials.value.find { it.id == item.itemId }
                if (existingMaterial != null) {
                    if (existingMaterial.quantity <= 1) {
                        _materials.value = _materials.value.filter { it.id != item.itemId }
                    } else {
                        _materials.value = _materials.value.map {
                            if (it.id == item.itemId) it.copy(quantity = it.quantity - 1)
                            else it
                        }
                    }
                }
            }
            "herb" -> {
                val existingHerb = _herbs.value.find { it.id == item.itemId }
                if (existingHerb != null) {
                    if (existingHerb.quantity <= 1) {
                        _herbs.value = _herbs.value.filter { it.id != item.itemId }
                    } else {
                        _herbs.value = _herbs.value.map {
                            if (it.id == item.itemId) it.copy(quantity = it.quantity - 1)
                            else it
                        }
                    }
                }
            }
            "seed" -> {
                val existingSeed = _seeds.value.find { it.id == item.itemId }
                if (existingSeed != null) {
                    if (existingSeed.quantity <= 1) {
                        _seeds.value = _seeds.value.filter { it.id != item.itemId }
                    } else {
                        _seeds.value = _seeds.value.map {
                            if (it.id == item.itemId) it.copy(quantity = it.quantity - 1)
                            else it
                        }
                    }
                }
            }
        }
    }
    
    internal fun syncListedItemsWithInventory() {
        val data = _gameData.value
        val currentListedItems = data.playerListedItems.toMutableList()
        val itemsToRemove = mutableListOf<String>()
        val itemsToUpdate = mutableListOf<Pair<String, Int>>()
        
        currentListedItems.forEach { item ->
            val inventoryQuantity = when (item.type) {
                "equipment" -> _equipment.value.count { it.id == item.itemId }
                "manual" -> _manuals.value.count { it.id == item.itemId }
                "pill" -> _pills.value.find { it.id == item.itemId }?.quantity ?: 0
                "material" -> _materials.value.find { it.id == item.itemId }?.quantity ?: 0
                "herb" -> _herbs.value.find { it.id == item.itemId }?.quantity ?: 0
                "seed" -> _seeds.value.find { it.id == item.itemId }?.quantity ?: 0
                else -> 0
            }
            
            when {
                inventoryQuantity == 0 -> {
                    itemsToRemove.add(item.id)
                }
                inventoryQuantity != item.quantity -> {
                    itemsToUpdate.add(item.id to inventoryQuantity)
                }
            }
        }
        
        if (itemsToRemove.isNotEmpty() || itemsToUpdate.isNotEmpty()) {
            val updatedList = currentListedItems
                .filterNot { it.id in itemsToRemove }
                .map { item ->
                    val update = itemsToUpdate.find { it.first == item.id }
                    if (update != null) {
                        item.copy(quantity = update.second)
                    } else {
                        item
                    }
                }
            
            _gameData.value = data.copy(playerListedItems = updatedList)
            
            if (itemsToRemove.isNotEmpty()) {
                addEvent("${itemsToRemove.size}件上架物品因库存不足已自动下架", EventType.WARNING)
            }
            if (itemsToUpdate.isNotEmpty()) {
                addEvent("${itemsToUpdate.size}件上架物品数量已根据库存调整", EventType.WARNING)
            }
        }
    }
    
    private fun returnItemToInventory(item: MerchantItem) {
        val savedData = item.data
        when (item.type) {
            "equipment" -> {
                val existingEquipment = _equipment.value.find { it.id == item.itemId }
                if (existingEquipment != null) {
                    _equipment.value = _equipment.value.map {
                        if (it.id == item.itemId) it.copy(ownerId = null, isEquipped = false)
                        else it
                    }
                } else if (savedData is Equipment) {
                    _equipment.value = _equipment.value + savedData.copy(ownerId = null, isEquipped = false)
                } else {
                    val template = EquipmentDatabase.getTemplateByName(item.name)
                    if (template != null) {
                        val equipment = EquipmentDatabase.createFromTemplate(template).copy(
                            id = item.itemId,
                            ownerId = null,
                            isEquipped = false
                        )
                        _equipment.value = _equipment.value + equipment
                    } else {
                        addEvent("无法恢复装备: ${item.name}", EventType.WARNING)
                    }
                }
            }
            "manual" -> {
                val existingManual = _manuals.value.find { it.id == item.itemId }
                if (existingManual != null) {
                    _manuals.value = _manuals.value.map {
                        if (it.id == item.itemId) it.copy(ownerId = null, isLearned = false)
                        else it
                    }
                } else if (savedData is Manual) {
                    _manuals.value = _manuals.value + savedData.copy(ownerId = null, isLearned = false)
                } else {
                    val template = ManualDatabase.getByName(item.name)
                    if (template != null) {
                        val manual = ManualDatabase.createFromTemplate(template).copy(
                            id = item.itemId,
                            ownerId = null,
                            isLearned = false
                        )
                        _manuals.value = _manuals.value + manual
                    } else {
                        addEvent("无法恢复功法: ${item.name}", EventType.WARNING)
                    }
                }
            }
            "pill" -> {
                val recipe = PillRecipeDatabase.getRecipeByName(item.name)
                if (recipe != null) {
                    val pill = Pill(
                        id = java.util.UUID.randomUUID().toString(),
                        name = item.name,
                        description = item.description,
                        rarity = item.rarity,
                        quantity = item.quantity,
                        category = recipe.category
                    )
                    addPillToWarehouse(pill)
                } else {
                    addEvent("无法恢复丹药: ${item.name}", EventType.WARNING)
                }
            }
            "material" -> {
                val materialTemplate = BeastMaterialDatabase.getMaterialByName(item.name)
                if (materialTemplate != null) {
                    val material = Material(
                        id = java.util.UUID.randomUUID().toString(),
                        name = item.name,
                        description = item.description,
                        rarity = item.rarity,
                        quantity = item.quantity,
                        category = materialTemplate.materialCategory
                    )
                    addMaterialToWarehouse(material)
                } else {
                    addEvent("无法恢复材料: ${item.name}", EventType.WARNING)
                }
            }
            "herb" -> {
                val herbTemplate = HerbDatabase.getHerbByName(item.name)
                if (herbTemplate != null) {
                    val herb = Herb(
                        id = java.util.UUID.randomUUID().toString(),
                        name = item.name,
                        description = item.description,
                        rarity = item.rarity,
                        quantity = item.quantity
                    )
                    addHerbToWarehouse(herb)
                } else {
                    addEvent("无法恢复灵药: ${item.name}", EventType.WARNING)
                }
            }
            "seed" -> {
                val seedTemplate = HerbDatabase.getSeedByName(item.name)
                if (seedTemplate != null) {
                    val seed = Seed(
                        id = java.util.UUID.randomUUID().toString(),
                        name = item.name,
                        description = item.description,
                        rarity = item.rarity,
                        quantity = item.quantity,
                        growTime = seedTemplate.growTime,
                        yield = seedTemplate.yield
                    )
                    addSeedToWarehouse(seed)
                } else {
                    addEvent("无法恢复种子: ${item.name}", EventType.WARNING)
                }
            }
        }
    }

    private fun processDiscipleAutoBuyFromListing() {
        val data = _gameData.value
        val listedItems = data.playerListedItems
        if (listedItems.isEmpty()) return

        val currentDisciples = _disciples.value.toMutableList()
        val updatedListedItems = listedItems.toMutableList()
        val eventsToAdd = mutableListOf<Pair<String, EventType>>()
        var totalSpiritStonesEarned = 0
        val itemsToRemoveFromInventory = mutableListOf<MerchantItem>()

        currentDisciples.forEachIndexed { index, disciple ->
            if (!disciple.isAlive || disciple.status == DiscipleStatus.REFLECTING) return@forEachIndexed

            val isOuterDisciple = disciple.discipleType == "outer"
            val maxRarity = if (isOuterDisciple) 2 else 6

            var currentDisciple = disciple
            var purchased = false

            val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
            val manualSlotBonus = talentEffects["manualSlot"]?.toInt() ?: 0
            val maxManualSlots = 5 + manualSlotBonus
            val manualSlotEmpty = currentDisciple.manualIds.size < maxManualSlots

            fun getAffordableItems() = updatedListedItems.filter { 
                it.price <= currentDisciple.spiritStones && it.rarity <= maxRarity 
            }

            fun tryPurchase(item: MerchantItem): Boolean {
                val result = discipleBuyItem(currentDisciple, item, data.gameYear, data.gameMonth) ?: return false
                currentDisciple = result.first
                updateListedItemsAfterPurchase(updatedListedItems, item)
                itemsToRemoveFromInventory.add(item)
                eventsToAdd.add(result.second to EventType.INFO)
                totalSpiritStonesEarned += item.price
                return true
            }

            if (getAffordableItems().isEmpty()) return@forEachIndexed

            if (!purchased && manualSlotEmpty) {
                val manualItem = getAffordableItems()
                    .filter { it.type == "manual" && !currentDisciple.manualIds.contains(it.itemId) }
                    .maxByOrNull { it.rarity }
                if (manualItem != null) {
                    purchased = tryPurchase(manualItem)
                }
            }

            if (!purchased) {
                val equipmentSlots = listOf(
                    EquipmentSlot.WEAPON to currentDisciple.weaponId,
                    EquipmentSlot.ARMOR to currentDisciple.armorId,
                    EquipmentSlot.BOOTS to currentDisciple.bootsId,
                    EquipmentSlot.ACCESSORY to currentDisciple.accessoryId
                )
                val emptySlot = equipmentSlots.firstOrNull { it.second == null }
                if (emptySlot != null) {
                    val slot = emptySlot.first
                    val equipmentItem = getAffordableItems()
                        .filter { it.type == "equipment" }
                        .firstOrNull { item ->
                            val equipment = _equipment.value.find { it.id == item.itemId }
                            equipment?.slot == slot
                        }
                    if (equipmentItem != null) {
                        purchased = tryPurchase(equipmentItem)
                    }
                }
            }

            if (!purchased) {
                // 检查是否已有适合当前境界突破的丹药（targetRealm是突破后的目标境界，应为当前境界-1）
                val breakthroughPillCount = currentDisciple.storageBagItems.count { item ->
                    item.itemType == "pill" && item.effect?.targetRealm == disciple.realm - 1 && 
                    item.effect?.breakthroughChance?.let { c -> c > 0 } == true
                }
                
                if (breakthroughPillCount == 0) {
                    // 自动购买适合当前境界突破的丹药（targetRealm是突破后的目标境界，应为当前境界-1）
                    val breakthroughPill = getAffordableItems()
                        .filter { it.type == "pill" }
                        .firstOrNull { item ->
                            val pill = _pills.value.find { it.id == item.itemId }
                            pill?.category == PillCategory.BREAKTHROUGH && pill.targetRealm == disciple.realm - 1
                        }
                    if (breakthroughPill != null) {
                        purchased = tryPurchase(breakthroughPill)
                    }
                }
            }

            if (!purchased) {
                val cultivationPillCount = currentDisciple.storageBagItems.count { item ->
                    item.itemType == "pill" && (
                        item.effect?.cultivationSpeed?.let { s -> s > 1.0 } == true ||
                        item.effect?.cultivationPercent?.let { p -> p > 0 } == true
                    )
                }
                
                if (cultivationPillCount == 0) {
                    val cultivationPill = getAffordableItems()
                        .filter { it.type == "pill" }
                        .firstOrNull { item ->
                            val pill = _pills.value.find { it.id == item.itemId }
                            pill?.category == PillCategory.CULTIVATION
                        }
                    if (cultivationPill != null) {
                        purchased = tryPurchase(cultivationPill)
                    }
                }
            }

            if (!purchased) {
                val isPhysicalType = currentDisciple.physicalAttack >= currentDisciple.magicAttack
                val battlePhysicalCount = currentDisciple.storageBagItems.count { 
                    it.itemType == "pill" && it.effect?.physicalAttackPercent?.let { p -> p > 0 } == true
                }
                val battleMagicCount = currentDisciple.storageBagItems.count { 
                    it.itemType == "pill" && it.effect?.magicAttackPercent?.let { p -> p > 0 } == true
                }
                val battleStatusCount = currentDisciple.storageBagItems.count { 
                    it.itemType == "pill" && it.effect?.speedPercent?.let { p -> p > 0 } == true
                }

                if (isPhysicalType && battlePhysicalCount == 0) {
                    val battlePhysicalPill = getAffordableItems()
                        .filter { it.type == "pill" }
                        .firstOrNull { item ->
                            val pill = _pills.value.find { it.id == item.itemId }
                            pill?.category == PillCategory.BATTLE_PHYSICAL
                        }
                    if (battlePhysicalPill != null) {
                        purchased = tryPurchase(battlePhysicalPill)
                    }
                } else if (!isPhysicalType && battleMagicCount == 0) {
                    val battleMagicPill = getAffordableItems()
                        .filter { it.type == "pill" }
                        .firstOrNull { item ->
                            val pill = _pills.value.find { it.id == item.itemId }
                            pill?.category == PillCategory.BATTLE_MAGIC
                        }
                    if (battleMagicPill != null) {
                        purchased = tryPurchase(battleMagicPill)
                    }
                }

                if (!purchased && battleStatusCount == 0) {
                    val battleStatusPill = getAffordableItems()
                        .filter { it.type == "pill" }
                        .firstOrNull { item ->
                            val pill = _pills.value.find { it.id == item.itemId }
                            pill?.category == PillCategory.BATTLE_STATUS
                        }
                    if (battleStatusPill != null) {
                        purchased = tryPurchase(battleStatusPill)
                    }
                }
            }

            if (!purchased) {
                val healPillCount = currentDisciple.storageBagItems.count { 
                    it.itemType == "pill" && it.effect?.heal?.let { h -> h > 0 } == true
                }
                val healPercentPillCount = currentDisciple.storageBagItems.count { 
                    it.itemType == "pill" && it.effect?.healPercent?.let { h -> h > 0 } == true
                }

                if (healPillCount == 0) {
                    val healPill = getAffordableItems()
                        .filter { it.type == "pill" }
                        .firstOrNull { item ->
                            val pill = _pills.value.find { it.id == item.itemId }
                            pill?.category == PillCategory.HEALING && (pill.heal > 0)
                        }
                    if (healPill != null) {
                        purchased = tryPurchase(healPill)
                    }
                }

                if (!purchased && healPercentPillCount == 0) {
                    val healPercentPill = getAffordableItems()
                        .filter { it.type == "pill" }
                        .firstOrNull { item ->
                            val pill = _pills.value.find { it.id == item.itemId }
                            pill?.category == PillCategory.HEALING && (pill.healPercent > 0)
                        }
                    if (healPercentPill != null) {
                        purchased = tryPurchase(healPercentPill)
                    }
                }
            }

            if (!purchased) {
                val extendLifePillCount = currentDisciple.storageBagItems.count { 
                    it.itemType == "pill" && it.effect?.extendLife?.let { e -> e > 0 } == true
                }
                val revivePillCount = currentDisciple.storageBagItems.count { 
                    it.itemType == "pill" && it.effect?.revive == true
                }
                val clearAllPillCount = currentDisciple.storageBagItems.count { 
                    it.itemType == "pill" && it.effect?.clearAll == true
                }

                if (extendLifePillCount == 0) {
                    val extendLifePill = getAffordableItems()
                        .filter { it.type == "pill" }
                        .firstOrNull { item ->
                            val pill = _pills.value.find { it.id == item.itemId }
                            pill?.extendLife?.let { e -> e > 0 } == true && 
                            !currentDisciple.usedExtendLifePillIds.contains(item.itemId)
                        }
                    if (extendLifePill != null) {
                        purchased = tryPurchase(extendLifePill)
                    }
                }

                if (!purchased && revivePillCount == 0) {
                    val revivePill = getAffordableItems()
                        .filter { it.type == "pill" }
                        .firstOrNull { item ->
                            val pill = _pills.value.find { it.id == item.itemId }
                            pill?.revive == true
                        }
                    if (revivePill != null) {
                        purchased = tryPurchase(revivePill)
                    }
                }

                if (!purchased && clearAllPillCount == 0) {
                    val clearAllPill = getAffordableItems()
                        .filter { it.type == "pill" }
                        .firstOrNull { item ->
                            val pill = _pills.value.find { it.id == item.itemId }
                            pill?.clearAll == true
                        }
                    if (clearAllPill != null) {
                        purchased = tryPurchase(clearAllPill)
                    }
                }
            }

            currentDisciples[index] = currentDisciple
        }

        itemsToRemoveFromInventory.forEach { item ->
            removeItemFromInventory(item)
        }

        _disciples.value = currentDisciples
        _gameData.value = data.copy(
            playerListedItems = updatedListedItems,
            spiritStones = data.spiritStones + totalSpiritStonesEarned
        )

        eventsToAdd.forEach { (message, type) ->
            addEvent(message, type)
        }
        
        if (totalSpiritStonesEarned > 0) {
            addEvent("弟子购买物品，获得${totalSpiritStonesEarned}灵石", EventType.SUCCESS)
        }
    }

    private fun updateListedItemsAfterPurchase(
        listedItems: MutableList<MerchantItem>,
        item: MerchantItem
    ) {
        if (item.quantity > 1) {
            val itemIndex = listedItems.indexOfFirst { it.id == item.id }
            if (itemIndex >= 0) {
                listedItems[itemIndex] = item.copy(quantity = item.quantity - 1)
            }
        } else {
            listedItems.removeAll { it.id == item.id }
        }
    }

    private fun shouldDiscipleBuyEquipment(disciple: Disciple, item: MerchantItem): Boolean {
        val equipment = _equipment.value.find { it.id == item.itemId } ?: return false

        val currentEquipId = when (equipment.slot) {
            EquipmentSlot.WEAPON -> disciple.weaponId
            EquipmentSlot.ARMOR -> disciple.armorId
            EquipmentSlot.BOOTS -> disciple.bootsId
            EquipmentSlot.ACCESSORY -> disciple.accessoryId
        }

        val currentEquip = currentEquipId?.let { _equipment.value.find { it.id == currentEquipId } }
        val currentRarity = currentEquip?.rarity ?: 0

        return equipment.rarity > currentRarity
    }

    private fun shouldDiscipleBuyManual(disciple: Disciple, item: MerchantItem): Boolean {
        if (disciple.manualIds.contains(item.itemId)) return false

        val manual = _manuals.value.find { it.id == item.itemId } ?: return false
        val currentManualCount = disciple.manualIds.size

        return currentManualCount < 3 && manual.rarity >= 2
    }

    private fun discipleBuyItem(
        disciple: Disciple,
        item: MerchantItem,
        gameYear: Int,
        gameMonth: Int
    ): Pair<Disciple, String>? {
        if (disciple.spiritStones < item.price) {
            return null
        }
        
        val updatedDisciple = disciple.copy(
            spiritStones = disciple.spiritStones - item.price
        )

        val effect = if (item.type == "pill") {
            val pill = _pills.value.find { it.id == item.itemId }
            pill?.let {
                ItemEffect(
                    cultivationSpeed = it.cultivationSpeed,
                    cultivationPercent = it.cultivationPercent,
                    skillExpPercent = it.skillExpPercent,
                    breakthroughChance = it.breakthroughChance,
                    targetRealm = it.targetRealm,
                    heal = it.heal,
                    healPercent = it.healPercent,
                    hpPercent = it.hpPercent,
                    mpPercent = it.mpPercent,
                    extendLife = it.extendLife,
                    battleCount = it.battleCount,
                    physicalAttackPercent = it.physicalAttackPercent,
                    magicAttackPercent = it.magicAttackPercent,
                    physicalDefensePercent = it.physicalDefensePercent,
                    magicDefensePercent = it.magicDefensePercent,
                    speedPercent = it.speedPercent,
                    revive = it.revive,
                    clearAll = it.clearAll,
                    duration = it.duration
                )
            }
        } else null

        val storageItem = StorageBagItem(
            itemId = item.itemId,
            itemType = item.type,
            name = item.name,
            rarity = item.rarity,
            quantity = 1,
            obtainedYear = gameYear,
            obtainedMonth = gameMonth,
            effect = effect
        )

        val updatedBag = addStorageItemToBag(updatedDisciple.storageBagItems, storageItem)
        val finalDisciple = updatedDisciple.copy(storageBagItems = updatedBag)

        return finalDisciple to "${disciple.name} 购买了 ${item.name}（${item.price}灵石）"
    }
    
    fun updateMonthlySalary(salary: Map<Int, Int>) {
        val data = _gameData.value
        _gameData.value = data.copy(monthlySalary = salary)
    }
    
    fun updateElderSlots(elderSlots: ElderSlots) {
        val data = _gameData.value
        _gameData.value = data.copy(elderSlots = elderSlots)
    }

    /**
     * 卸任执法长老，同时清空所有执法弟子的状态
     */
    fun removeLawEnforcementElderAndDisciples() {
        val data = _gameData.value
        val lawEnforcementDiscipleIds = data.elderSlots.lawEnforcementDisciples.mapNotNull { it.discipleId }

        // 清空执法长老和执法弟子槽位
        _gameData.value = data.copy(
            elderSlots = data.elderSlots.copy(
                lawEnforcementElder = null,
                lawEnforcementDisciples = emptyList()
            )
        )

        // 恢复所有执法弟子的空闲状态
        if (lawEnforcementDiscipleIds.isNotEmpty()) {
            _disciples.value = _disciples.value.map { disciple ->
                if (lawEnforcementDiscipleIds.contains(disciple.id)) {
                    disciple.copy(status = DiscipleStatus.IDLE)
                } else {
                    disciple
                }
            }
        }
    }

    /**
     * 卸任传道长老，同时清空所有传道师的状态
     */
    fun removePreachingElderAndMasters() {
        val data = _gameData.value
        val preachingMasterIds = data.elderSlots.preachingMasters.mapNotNull { it.discipleId }

        // 清空传道长老和传道师槽位
        _gameData.value = data.copy(
            elderSlots = data.elderSlots.copy(
                preachingElder = null,
                preachingMasters = emptyList()
            )
        )

        // 恢复所有传道师的空闲状态
        if (preachingMasterIds.isNotEmpty()) {
            _disciples.value = _disciples.value.map { disciple ->
                if (preachingMasterIds.contains(disciple.id)) {
                    disciple.copy(status = DiscipleStatus.IDLE)
                } else {
                    disciple
                }
            }
        }
    }

    /**
     * 卸任青云传道长老，同时清空所有青云传道师的状态
     */
    fun removeQingyunPreachingElderAndMasters() {
        val data = _gameData.value
        val qingyunPreachingMasterIds = data.elderSlots.qingyunPreachingMasters.mapNotNull { it.discipleId }

        // 清空青云传道长老和青云传道师槽位
        _gameData.value = data.copy(
            elderSlots = data.elderSlots.copy(
                qingyunPreachingElder = null,
                qingyunPreachingMasters = emptyList()
            )
        )

        // 恢复所有青云传道师的空闲状态
        if (qingyunPreachingMasterIds.isNotEmpty()) {
            _disciples.value = _disciples.value.map { disciple ->
                if (qingyunPreachingMasterIds.contains(disciple.id)) {
                    disciple.copy(status = DiscipleStatus.IDLE)
                } else {
                    disciple
                }
            }
        }
    }

    /**
     * 计算长老和亲传弟子对建筑的加成
     * @param buildingType 建筑类型: spiritMine, herbGarden, alchemy, forge, library
     * @return Pair(产量加成百分比, 速度加成百分比, 成功率加成百分比, 成熟时间减少百分比)
     */
    private fun calculateElderAndDisciplesBonus(buildingType: String): ElderBonusResult {
        val data = _gameData.value
        val (elderId, discipleSlots) = when (buildingType) {
            "spiritMine" -> return ElderBonusResult()
            "herbGarden" -> data.elderSlots.herbGardenElder to data.elderSlots.herbGardenDisciples
            "alchemy" -> data.elderSlots.alchemyElder to data.elderSlots.alchemyDisciples
            "forge" -> data.elderSlots.forgeElder to data.elderSlots.forgeDisciples
            "library" -> data.elderSlots.libraryElder to data.elderSlots.libraryDisciples
            else -> return ElderBonusResult()
        }

        val elder = elderId?.let { _disciples.value.find { d -> d.id == it } }
        val disciples = discipleSlots.mapNotNull { slot -> 
            slot.discipleId?.let { id -> _disciples.value.find { d -> d.id == id } }
        }

        var yieldBonus = 0.0
        var speedBonus = 0.0
        var successBonus = 0.0
        var growTimeReduction = 0.0

        when (buildingType) {
            "herbGarden" -> {
                // 灵药园：受灵植属性影响
                // 长老：以50为基准，每高5点增加1%成熟速度，每低5点减少1%成熟速度
                elder?.let { e ->
                    val spiritPlantingDiff = e.spiritPlanting - 50
                    growTimeReduction += (spiritPlantingDiff / 5.0) * 0.01
                }
                // 亲传弟子：以50为基准，每高10点增加1%成熟速度，每低10点减少1%成熟速度
                disciples.forEach { d ->
                    val spiritPlantingDiff = d.spiritPlanting - 50
                    growTimeReduction += (spiritPlantingDiff / 10.0) * 0.01
                }
            }
            "alchemy" -> {
                // 炼丹房：受炼丹属性影响
                // 长老：以50为基准，每高5点增加1%炼制速度，每低5点减少1%炼制速度；每多5点增加1%成功率
                elder?.let { e ->
                    val pillRefiningDiff = e.pillRefining - 50
                    speedBonus += (pillRefiningDiff / 5.0) * 0.01
                    successBonus += (pillRefiningDiff / 5.0) * 0.01
                }
                // 亲传弟子：以50为基准，每高10点增加1%炼制速度，每低10点减少1%炼制速度；每多10点增加1%成功率
                disciples.forEach { d ->
                    val pillRefiningDiff = d.pillRefining - 50
                    speedBonus += (pillRefiningDiff / 10.0) * 0.01
                    successBonus += (pillRefiningDiff / 10.0) * 0.01
                }
            }
            "forge" -> {
                // 天工峰：受炼器属性影响
                // 长老：每多5点增加1%炼制速度，每多5点增加1%成功率
                elder?.let { e ->
                    val artifactRefiningDiff = e.artifactRefining - 50
                    speedBonus += (artifactRefiningDiff / 5.0) * 0.01
                    successBonus += (artifactRefiningDiff / 5.0) * 0.01
                }
                // 亲传弟子：每多10点增加1%炼制速度，每多10点增加1%成功率
                disciples.forEach { d ->
                    val artifactRefiningDiff = d.artifactRefining - 50
                    speedBonus += (artifactRefiningDiff / 10.0) * 0.01
                    successBonus += (artifactRefiningDiff / 10.0) * 0.01
                }
            }
            "library" -> {
                // 藏经阁：受传道属性影响
                // 长老：每多1点增加1%修炼功法速度
                elder?.let { e ->
                    val teachingDiff = e.teaching - 50
                    speedBonus += teachingDiff * 0.01
                }
                // 亲传弟子：每多5点增加1%修炼功法速度
                disciples.forEach { d ->
                    val teachingDiff = d.teaching - 50
                    speedBonus += (teachingDiff / 5.0) * 0.01
                }
            }
        }

        return ElderBonusResult(
            yieldBonus = yieldBonus,
            speedBonus = speedBonus,
            successBonus = successBonus,
            growTimeReduction = growTimeReduction
        )
    }

    private data class ElderBonusResult(
        val yieldBonus: Double = 0.0,
        val speedBonus: Double = 0.0,
        val successBonus: Double = 0.0,
        val growTimeReduction: Double = 0.0
    )

    /**
     * 计算副宗主智力对政策效果的加成
     * 
     * 计算规则：
     * - 基准智力值为50点，此时无加成
     * - 每超过基准值5点智力，政策效果增加1%
     * - 计算公式：加成比例 = ((智力 - 50) / 5) * 1%
     * 
     * 示例：
     * - 智力50：加成0% (基准值)
     * - 智力55：加成1%
     * - 智力60：加成2%
     * - 智力70：加成4%
     * 
     * 政策效果计算公式：
     * 实际效果 = 基础效果 × (1 + 副宗主加成)
     * 
     * @return 副宗主智力加成比例，范围[0.0, +∞)
     */
    private fun calculateViceSectMasterPolicyBonus(): Double {
        val viceSectMasterId = _gameData.value.elderSlots.viceSectMaster ?: return 0.0
        val viceSectMaster = _disciples.value.find { it.id == viceSectMasterId } ?: return 0.0
        val intelligence = viceSectMaster.intelligence
        
        // 使用GameConfig中的常量进行计算
        val baseIntelligence = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE
        val step = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_STEP
        val bonusPerStep = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP
        
        return ((intelligence - baseIntelligence) / step.toDouble() * bonusPerStep).coerceAtLeast(0.0)
    }
    
    fun assignDirectDisciple(elderSlotType: String, slotIndex: Int, discipleId: String, discipleName: String, discipleRealm: String, discipleSpiritRootColor: String = "#E0E0E0") {
        val data = _gameData.value
        
        val hasElder = when (elderSlotType) {
            "herbGarden" -> data.elderSlots.herbGardenElder != null
            "alchemy" -> data.elderSlots.alchemyElder != null
            "forge" -> data.elderSlots.forgeElder != null
            "library" -> data.elderSlots.libraryElder != null
            "spiritMine" -> false
            "preachingMasters" -> data.elderSlots.preachingElder != null
            "lawEnforcementDisciples" -> data.elderSlots.lawEnforcementElder != null
            "lawEnforcementReserveDisciples" -> data.elderSlots.lawEnforcementElder != null
            "qingyunPreachingMasters" -> data.elderSlots.qingyunPreachingElder != null
            else -> false
        }
        
        if (!hasElder) {
            addEvent("该槽位没有长老，无法任命", EventType.WARNING)
            return
        }
        
        val currentSlots = when (elderSlotType) {
            "herbGarden" -> data.elderSlots.herbGardenDisciples
            "alchemy" -> data.elderSlots.alchemyDisciples
            "forge" -> data.elderSlots.forgeDisciples
            "library" -> data.elderSlots.libraryDisciples
            "preachingMasters" -> data.elderSlots.preachingMasters
            "lawEnforcementDisciples" -> data.elderSlots.lawEnforcementDisciples
            "lawEnforcementReserveDisciples" -> data.elderSlots.lawEnforcementReserveDisciples
            "qingyunPreachingMasters" -> data.elderSlots.qingyunPreachingMasters
            else -> emptyList()
        }.toMutableList()
        
        val allDiscipleSlots = listOf(
            data.elderSlots.herbGardenDisciples,
            data.elderSlots.alchemyDisciples,
            data.elderSlots.forgeDisciples,
            data.elderSlots.libraryDisciples,
            data.elderSlots.preachingMasters,
            data.elderSlots.lawEnforcementDisciples,
            data.elderSlots.lawEnforcementReserveDisciples,
            data.elderSlots.qingyunPreachingMasters
        ).flatten()
        
        if (allDiscipleSlots.any { it.discipleId == discipleId }) {
            addEvent("该弟子已在其他职位任职", EventType.WARNING)
            return
        }
        
        val allElderIds = listOf(
            data.elderSlots.herbGardenElder,
            data.elderSlots.alchemyElder,
            data.elderSlots.forgeElder,
            data.elderSlots.libraryElder,
            data.elderSlots.outerElder,
            data.elderSlots.preachingElder,
            data.elderSlots.lawEnforcementElder,
            data.elderSlots.innerElder,
            data.elderSlots.qingyunPreachingElder
        )
        
        if (allElderIds.contains(discipleId)) {
            addEvent("该弟子已担任长老职位", EventType.WARNING)
            return
        }
        
        val existingIndex = currentSlots.indexOfFirst { it.index == slotIndex }
        val newSlot = DirectDiscipleSlot(
            index = slotIndex,
            discipleId = discipleId,
            discipleName = discipleName,
            discipleRealm = discipleRealm,
            discipleSpiritRootColor = discipleSpiritRootColor
        )
        
        if (existingIndex >= 0) {
            currentSlots[existingIndex] = newSlot
        } else {
            currentSlots.add(newSlot)
        }
        
        val updatedElderSlots = when (elderSlotType) {
            "herbGarden" -> data.elderSlots.copy(herbGardenDisciples = currentSlots)
            "alchemy" -> data.elderSlots.copy(alchemyDisciples = currentSlots)
            "forge" -> data.elderSlots.copy(forgeDisciples = currentSlots)
            "library" -> data.elderSlots.copy(libraryDisciples = currentSlots)
            "preachingMasters" -> data.elderSlots.copy(preachingMasters = currentSlots)
            "lawEnforcementDisciples" -> data.elderSlots.copy(lawEnforcementDisciples = currentSlots)
            "lawEnforcementReserveDisciples" -> data.elderSlots.copy(lawEnforcementReserveDisciples = currentSlots)
            "qingyunPreachingMasters" -> data.elderSlots.copy(qingyunPreachingMasters = currentSlots)
            else -> data.elderSlots
        }
        
        _gameData.value = data.copy(elderSlots = updatedElderSlots)
        
        syncAllDiscipleStatuses()

        val positionName = when (elderSlotType) {
            "lawEnforcementDisciples" -> "执法弟子"
            "lawEnforcementReserveDisciples" -> "储备弟子"
            "preachingMasters" -> "问道峰传道师"
            "qingyunPreachingMasters" -> "青云峰传道师"
            else -> "亲传弟子"
        }
        addEvent("$discipleName 已成为$positionName", EventType.SUCCESS)
    }
    
    fun removeDirectDisciple(elderSlotType: String, slotIndex: Int) {
        val data = _gameData.value
        
        val currentSlots = when (elderSlotType) {
            "herbGarden" -> data.elderSlots.herbGardenDisciples
            "alchemy" -> data.elderSlots.alchemyDisciples
            "forge" -> data.elderSlots.forgeDisciples
            "library" -> data.elderSlots.libraryDisciples
            "preachingMasters" -> data.elderSlots.preachingMasters
            "lawEnforcementDisciples" -> data.elderSlots.lawEnforcementDisciples
            "lawEnforcementReserveDisciples" -> data.elderSlots.lawEnforcementReserveDisciples
            "qingyunPreachingMasters" -> data.elderSlots.qingyunPreachingMasters
            "spiritMineDeacon" -> data.elderSlots.spiritMineDeaconDisciples
            else -> emptyList()
        }.filter { it.index != slotIndex }
        
        val updatedElderSlots = when (elderSlotType) {
            "herbGarden" -> data.elderSlots.copy(herbGardenDisciples = currentSlots)
            "alchemy" -> data.elderSlots.copy(alchemyDisciples = currentSlots)
            "forge" -> data.elderSlots.copy(forgeDisciples = currentSlots)
            "library" -> data.elderSlots.copy(libraryDisciples = currentSlots)
            "preachingMasters" -> data.elderSlots.copy(preachingMasters = currentSlots)
            "lawEnforcementDisciples" -> data.elderSlots.copy(lawEnforcementDisciples = currentSlots)
            "lawEnforcementReserveDisciples" -> data.elderSlots.copy(lawEnforcementReserveDisciples = currentSlots)
            "qingyunPreachingMasters" -> data.elderSlots.copy(qingyunPreachingMasters = currentSlots)
            "spiritMineDeacon" -> data.elderSlots.copy(spiritMineDeaconDisciples = currentSlots)
            else -> data.elderSlots
        }
        
        _gameData.value = data.copy(elderSlots = updatedElderSlots)
        syncAllDiscipleStatuses()
    }
    
    fun updateSpiritMineSlots(slots: List<SpiritMineSlot>) {
        val data = _gameData.value
        _gameData.value = data.copy(spiritMineSlots = slots)
        syncAllDiscipleStatuses()
    }

    fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        _disciples.value = _disciples.value.map {
            if (it.id == discipleId) it.copy(status = status) else it
        }
    }

    /**
     * 根据弟子所在槽位计算其应有的状态
     * 状态优先级（从高到低）：
     * 1. REFLECTING - 思过崖思过中
     * 2. BATTLE - 战斗队伍中
     * 3. EXPLORING - 探索队伍中
     * 4. LAW_ENFORCING - 执法长老/执法弟子
     * 5. PREACHING - 传道长老/传道师
     * 6. MANAGING - 副宗主/外门长老/内门长老/藏经阁长老/灵矿执事
     * 7. FORGING - 天工长老
     * 8. ALCHEMY - 炼丹长老
     * 9. FARMING - 灵植长老
     * 10. STUDYING - 藏经阁弟子
     * 11. MINING - 矿工
     * 12. GROWING - 无境界弟子成长中
     * 13. IDLE - 空闲
     */
    fun calculateDiscipleStatus(discipleId: String): DiscipleStatus {
        val data = _gameData.value
        val elderSlots = data.elderSlots
        val disciple = _disciples.value.find { it.id == discipleId } ?: return DiscipleStatus.IDLE

        if (disciple.status == DiscipleStatus.REFLECTING) {
            return DiscipleStatus.REFLECTING
        }

        if (data.activeMissions.any { discipleId in it.discipleIds }) {
            return DiscipleStatus.ON_MISSION
        }

        val battleTeam = data.battleTeam
        if (battleTeam != null && battleTeam.status != "idle") {
            if (battleTeam.slots.any { it.discipleId == discipleId }) {
                return DiscipleStatus.BATTLE
            }
        }

        val exploringTeam = _teams.value.find { team ->
            team.memberIds.contains(discipleId) &&
            (team.status == ExplorationStatus.TRAVELING || team.status == ExplorationStatus.EXPLORING)
        }
        if (exploringTeam != null) {
            return DiscipleStatus.EXPLORING
        }

        val caveExplorationTeam = data.caveExplorationTeams.find { team ->
            team.memberIds.contains(discipleId) &&
            (team.status == CaveExplorationStatus.TRAVELING || team.status == CaveExplorationStatus.EXPLORING)
        }
        if (caveExplorationTeam != null) {
            return DiscipleStatus.EXPLORING
        }

        if (elderSlots.lawEnforcementElder == discipleId) {
            return DiscipleStatus.LAW_ENFORCING
        }
        if (elderSlots.lawEnforcementDisciples.any { it.discipleId == discipleId }) {
            return DiscipleStatus.LAW_ENFORCING
        }

        if (elderSlots.preachingElder == discipleId) {
            return DiscipleStatus.PREACHING
        }
        if (elderSlots.qingyunPreachingElder == discipleId) {
            return DiscipleStatus.PREACHING
        }
        if (elderSlots.preachingMasters.any { it.discipleId == discipleId }) {
            return DiscipleStatus.PREACHING
        }
        if (elderSlots.qingyunPreachingMasters.any { it.discipleId == discipleId }) {
            return DiscipleStatus.PREACHING
        }

        if (elderSlots.viceSectMaster == discipleId) {
            return DiscipleStatus.MANAGING
        }
        if (elderSlots.outerElder == discipleId) {
            return DiscipleStatus.MANAGING
        }
        if (elderSlots.innerElder == discipleId) {
            return DiscipleStatus.MANAGING
        }
        if (elderSlots.libraryElder == discipleId) {
            return DiscipleStatus.MANAGING
        }
        if (elderSlots.spiritMineDeaconDisciples.any { it.discipleId == discipleId }) {
            return DiscipleStatus.MANAGING
        }

        if (elderSlots.forgeElder == discipleId) {
            return DiscipleStatus.FORGING
        }

        if (elderSlots.alchemyElder == discipleId) {
            return DiscipleStatus.ALCHEMY
        }

        if (elderSlots.herbGardenElder == discipleId) {
            return DiscipleStatus.FARMING
        }

        if (data.librarySlots.any { it.discipleId == discipleId }) {
            return DiscipleStatus.STUDYING
        }

        if (data.spiritMineSlots.any { it.discipleId == discipleId }) {
            return DiscipleStatus.MINING
        }

        if (disciple.realmLayer == 0) {
            return DiscipleStatus.GROWING
        }

        return DiscipleStatus.IDLE
    }

    /**
     * 同步所有弟子的状态
     * 根据槽位位置重新计算每个弟子的状态
     */
    fun syncAllDiscipleStatuses() {
        val data = _gameData.value
        val elderSlots = data.elderSlots

        val lawEnforcerIds = mutableSetOf<String>()
        elderSlots.lawEnforcementElder?.let { lawEnforcerIds.add(it) }
        elderSlots.lawEnforcementDisciples.mapNotNull { it.discipleId }.forEach { lawEnforcerIds.add(it) }

        val preachingIds = mutableSetOf<String>()
        elderSlots.preachingElder?.let { preachingIds.add(it) }
        elderSlots.qingyunPreachingElder?.let { preachingIds.add(it) }
        elderSlots.preachingMasters.mapNotNull { it.discipleId }.forEach { preachingIds.add(it) }
        elderSlots.qingyunPreachingMasters.mapNotNull { it.discipleId }.forEach { preachingIds.add(it) }

        val managingIds = mutableSetOf<String>()
        elderSlots.viceSectMaster?.let { managingIds.add(it) }
        elderSlots.outerElder?.let { managingIds.add(it) }
        elderSlots.innerElder?.let { managingIds.add(it) }
        elderSlots.libraryElder?.let { managingIds.add(it) }
        elderSlots.spiritMineDeaconDisciples.mapNotNull { it.discipleId }.forEach { managingIds.add(it) }

        val forgingIds = mutableSetOf<String>()
        elderSlots.forgeElder?.let { forgingIds.add(it) }

        val alchemyIds = mutableSetOf<String>()
        elderSlots.alchemyElder?.let { alchemyIds.add(it) }

        val farmingIds = mutableSetOf<String>()
        elderSlots.herbGardenElder?.let { farmingIds.add(it) }

        val studyingIds = data.librarySlots.mapNotNull { it.discipleId }.toMutableSet()

        val miningIds = data.spiritMineSlots.mapNotNull { it.discipleId }.toMutableSet()

        val battleIds = mutableSetOf<String>()
        val battleTeam = data.battleTeam
        if (battleTeam != null && battleTeam.status != "idle") {
            battleTeam.slots.mapNotNull { it.discipleId }.forEach { battleIds.add(it) }
        }

        val exploringIds = mutableSetOf<String>()
        _teams.value.filter { 
            it.status == ExplorationStatus.TRAVELING || it.status == ExplorationStatus.EXPLORING 
        }.forEach { team ->
            team.memberIds.forEach { exploringIds.add(it) }
        }
        data.caveExplorationTeams.filter {
            it.status == CaveExplorationStatus.TRAVELING || it.status == CaveExplorationStatus.EXPLORING
        }.forEach { team ->
            team.memberIds.forEach { exploringIds.add(it) }
        }

        _disciples.value = _disciples.value.map { disciple ->
            if (!disciple.isAlive) return@map disciple

            if (disciple.status == DiscipleStatus.REFLECTING) return@map disciple

            val newStatus = when {
                battleIds.contains(disciple.id) -> DiscipleStatus.BATTLE
                exploringIds.contains(disciple.id) -> DiscipleStatus.EXPLORING
                lawEnforcerIds.contains(disciple.id) -> DiscipleStatus.LAW_ENFORCING
                preachingIds.contains(disciple.id) -> DiscipleStatus.PREACHING
                managingIds.contains(disciple.id) -> DiscipleStatus.MANAGING
                forgingIds.contains(disciple.id) -> DiscipleStatus.FORGING
                alchemyIds.contains(disciple.id) -> DiscipleStatus.ALCHEMY
                farmingIds.contains(disciple.id) -> DiscipleStatus.FARMING
                studyingIds.contains(disciple.id) -> DiscipleStatus.STUDYING
                miningIds.contains(disciple.id) -> DiscipleStatus.MINING
                disciple.realmLayer == 0 -> DiscipleStatus.GROWING
                else -> DiscipleStatus.IDLE
            }

            if (disciple.status != newStatus) {
                disciple.copy(status = newStatus)
            } else {
                disciple
            }
        }
    }

    fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean {
        val data = _gameData.value
        val inMinerSlots = data.spiritMineSlots.any { it.discipleId == discipleId }
        val inDeaconSlots = data.elderSlots.spiritMineDeaconDisciples.any { it.discipleId == discipleId }
        return inMinerSlots || inDeaconSlots
    }
    
    fun updateMonthlySalaryEnabled(realm: Int, enabled: Boolean) {
        val data = _gameData.value
        val newEnabled = data.monthlySalaryEnabled.toMutableMap()
        newEnabled[realm] = enabled
        _gameData.value = data.copy(monthlySalaryEnabled = newEnabled)
    }
    
    fun addEvent(message: String, type: EventType = EventType.INFO) {
        val data = _gameData.value
        val event = GameEvent(
            year = data.gameYear,
            month = data.gameMonth,
            message = message,
            type = type
        )
        _events.value = listOf(event) + _events.value.take(49)
    }
    
    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) {
        // 处理移除弟子的情况（空字符串表示移除）
        if (discipleId.isEmpty()) {
            val existingSlot = _buildingSlots.value.find {
                it.buildingId == buildingId && it.slotIndex == slotIndex
            }

            if (existingSlot != null) {
                // 如果槽位正在工作中，不能移除
                if (existingSlot.status == SlotStatus.WORKING) {
                    addEvent("该槽位正在工作中，无法移除弟子", EventType.WARNING)
                    return
                }

                // 将弟子状态恢复为空闲
                existingSlot.discipleId?.let { oldDiscipleId ->
                    _disciples.value = _disciples.value.map {
                        if (it.id == oldDiscipleId) it.copy(status = DiscipleStatus.IDLE) else it
                    }
                }

                existingSlot.discipleId?.let { oldDiscipleId ->
                    val disciple = _disciples.value.find { it.id == oldDiscipleId }
                    disciple?.let {
                        addEvent("${it.name}从${getBuildingName(buildingId)}移除", EventType.INFO)
                    }
                }

                // 移除槽位中的弟子
                _buildingSlots.value = _buildingSlots.value
                    .filter { !(it.buildingId == buildingId && it.slotIndex == slotIndex) }
            }
            return
        }

        val disciple = _disciples.value.find { it.id == discipleId } ?: return
        if (!disciple.isAlive || disciple.status != DiscipleStatus.IDLE) {
            addEvent("${disciple.name}无法被安排工作", EventType.WARNING)
            return
        }

        // 五岁以下弟子不能工作
        if (disciple.age < 5) {
            addEvent("${disciple.name}年龄太小，无法工作", EventType.WARNING)
            return
        }

        val existingSlot = _buildingSlots.value.find {
            it.buildingId == buildingId && it.slotIndex == slotIndex
        }

        // 如果槽位正在工作中，不能更换弟子
        if (existingSlot != null && existingSlot.status == SlotStatus.WORKING) {
            addEvent("该槽位正在工作中，无法更换弟子", EventType.WARNING)
            return
        }

        // 如果槽位已有弟子，先将原弟子状态恢复为空闲
        existingSlot?.discipleId?.let { oldDiscipleId ->
            _disciples.value = _disciples.value.map {
                if (it.id == oldDiscipleId) it.copy(status = DiscipleStatus.IDLE) else it
            }
        }

        val newSlot = existingSlot?.copy(discipleId = discipleId) ?: BuildingSlot(
            buildingId = buildingId,
            slotIndex = slotIndex,
            discipleId = discipleId,
            status = SlotStatus.IDLE
        )

        _buildingSlots.value = _buildingSlots.value
            .filter { !(it.buildingId == buildingId && it.slotIndex == slotIndex) } + newSlot

        // 所有建筑都设为 WORKING 状态
        _disciples.value = _disciples.value.map {
            if (it.id == discipleId) it.copy(status = DiscipleStatus.WORKING) else it
        }
    }
    
    /**
     * 获取指定建筑的所有槽位
     */
    fun getBuildingSlots(buildingId: String): List<BuildingSlot> {
        return _buildingSlots.value.filter { it.buildingId == buildingId }
    }
    
    /**
     * 从建筑槽位移除弟子
     */
    fun removeDiscipleFromBuilding(buildingId: String, slotIndex: Int) {
        val existingSlot = _buildingSlots.value.find {
            it.buildingId == buildingId && it.slotIndex == slotIndex
        }

        if (existingSlot != null) {
            // 如果槽位正在工作中，不能移除
            if (existingSlot.status == SlotStatus.WORKING) {
                addEvent("该槽位正在工作中，无法移除弟子", EventType.WARNING)
                return
            }

            // 将弟子状态恢复为空闲
            existingSlot.discipleId?.let { oldDiscipleId ->
                _disciples.value = _disciples.value.map {
                    if (it.id == oldDiscipleId) it.copy(status = DiscipleStatus.IDLE) else it
                }
            }

            existingSlot.discipleId?.let { oldDiscipleId ->
                val disciple = _disciples.value.find { it.id == oldDiscipleId }
                disciple?.let {
                    addEvent("${it.name}从${getBuildingName(buildingId)}移除", EventType.INFO)
                }
            }

            // 移除槽位中的弟子
            _buildingSlots.value = _buildingSlots.value
                .filter { !(it.buildingId == buildingId && it.slotIndex == slotIndex) }
        }
    }
    
    /**
     * 分配弟子到建筑槽位（通用方法）
     */
    fun assignDiscipleToBuildingSlot(buildingId: String, slotIndex: Int, discipleId: String, slotType: SlotType) {
        val disciple = _disciples.value.find { it.id == discipleId } ?: return
        if (!disciple.isAlive || disciple.status != DiscipleStatus.IDLE) {
            addEvent("${disciple.name}无法被安排工作", EventType.WARNING)
            return
        }

        // 五岁以下弟子不能工作
        if (disciple.age < 5) {
            addEvent("${disciple.name}年龄太小，无法工作", EventType.WARNING)
            return
        }

        val existingSlot = _buildingSlots.value.find {
            it.buildingId == buildingId && it.slotIndex == slotIndex
        }

        // 如果槽位正在工作中，不能更换弟子
        if (existingSlot != null && existingSlot.status == SlotStatus.WORKING) {
            addEvent("该槽位正在工作中，无法更换弟子", EventType.WARNING)
            return
        }

        // 如果槽位已有弟子，先将原弟子状态恢复为空闲
        existingSlot?.discipleId?.let { oldDiscipleId ->
            _disciples.value = _disciples.value.map {
                if (it.id == oldDiscipleId) it.copy(status = DiscipleStatus.IDLE) else it
            }
        }

        val data = _gameData.value
        val newSlot = existingSlot?.copy(
            discipleId = discipleId,
            discipleName = disciple.name,
            type = slotType,
            status = SlotStatus.WORKING,
            startYear = data.gameYear,
            startMonth = data.gameMonth,
            duration = 1 // 灵矿场默认工作 1 个月
        ) ?: BuildingSlot(
            buildingId = buildingId,
            slotIndex = slotIndex,
            type = slotType,
            discipleId = discipleId,
            discipleName = disciple.name,
            status = SlotStatus.WORKING,
            startYear = data.gameYear,
            startMonth = data.gameMonth,
            duration = 1
        )

        _buildingSlots.value = _buildingSlots.value
            .filter { !(it.buildingId == buildingId && it.slotIndex == slotIndex) } + newSlot

        // 更新弟子状态
        _disciples.value = _disciples.value.map {
            if (it.id == discipleId) it.copy(status = DiscipleStatus.WORKING) else it
        }
        
        addEvent("${disciple.name}开始${slotType.displayName}", EventType.INFO)
    }
    
    /**
     * 从建筑槽位移除弟子（通用方法）
     */
    fun removeDiscipleFromBuildingSlot(buildingId: String, slotIndex: Int) {
        val existingSlot = _buildingSlots.value.find {
            it.buildingId == buildingId && it.slotIndex == slotIndex
        }

        if (existingSlot != null) {
            // 如果槽位正在工作中，不能移除
            if (existingSlot.status == SlotStatus.WORKING) {
                addEvent("该槽位正在工作中，无法移除弟子", EventType.WARNING)
                return
            }

            // 将弟子状态恢复为空闲
            existingSlot.discipleId?.let { oldDiscipleId ->
                _disciples.value = _disciples.value.map {
                    if (it.id == oldDiscipleId) it.copy(status = DiscipleStatus.IDLE) else it
                }
            }

            existingSlot.discipleId?.let { oldDiscipleId ->
                val disciple = _disciples.value.find { it.id == oldDiscipleId }
                disciple?.let {
                    addEvent("${it.name}从${getBuildingName(buildingId)}移除", EventType.INFO)
                }
            }

            // 移除槽位中的弟子
            _buildingSlots.value = _buildingSlots.value
                .filter { !(it.buildingId == buildingId && it.slotIndex == slotIndex) }
        }
    }
    
    fun startBuildingWork(buildingId: String, slotIndex: Int, recipeId: String, baseDuration: Int) {
        val data = _gameData.value
        val existingSlot = _buildingSlots.value.find {
            it.buildingId == buildingId && it.slotIndex == slotIndex
        }

        // 如果槽位正在工作中，不能开始新工作
        if (existingSlot?.status == SlotStatus.WORKING) {
            addEvent("该槽位已经在工作中", EventType.WARNING)
            return
        }

        // 计算所有入驻弟子的总加成后的实际耗时（新系统 - 多弟子叠加）
        val actualDuration = calculateWorkDurationWithAllDisciples(baseDuration, buildingId)

        // 如果槽位存在则更新，否则创建新槽位
        val updatedSlot = existingSlot?.copy(
            recipeId = recipeId,
            startYear = data.gameYear,
            startMonth = data.gameMonth,
            duration = actualDuration,
            status = SlotStatus.WORKING
        ) ?: BuildingSlot(
            buildingId = buildingId,
            slotIndex = slotIndex,
            recipeId = recipeId,
            startYear = data.gameYear,
            startMonth = data.gameMonth,
            duration = actualDuration,
            status = SlotStatus.WORKING
        )

        // 更新或添加槽位
        _buildingSlots.value = if (existingSlot != null) {
            _buildingSlots.value.map {
                if (it.id == existingSlot.id) updatedSlot else it
            }
        } else {
            _buildingSlots.value + updatedSlot
        }

        val workName = getBuildingName(buildingId)
        val bonusPercent = ((baseDuration - actualDuration) * 100 / baseDuration)
        if (bonusPercent > 0) {
            addEvent("开始在${workName}工作(加速${bonusPercent}%)", EventType.INFO)
        } else {
            addEvent("开始在${workName}工作", EventType.INFO)
        }
    }

    /**
     * 计算工作耗时，受长老和亲传弟子影响
     * @param baseDuration 基础耗时
     * @param buildingId 建筑类型
     * @return 实际耗时
     */
    private fun calculateWorkDurationWithAllDisciples(baseDuration: Int, buildingId: String): Int {
        var totalSpeedBonus = 0.0

        when (buildingId) {
            "alchemyRoom" -> {
                val elderBonus = calculateElderAndDisciplesBonus("alchemy")
                totalSpeedBonus += elderBonus.speedBonus
            }
            "forge" -> {
                val elderBonus = calculateElderAndDisciplesBonus("forge")
                totalSpeedBonus += elderBonus.speedBonus
            }
            else -> {
                val buildingSlots = _buildingSlots.value.filter { it.buildingId == buildingId }
                val assignedDiscipleIds = buildingSlots.mapNotNull { it.discipleId }
                if (assignedDiscipleIds.isNotEmpty()) {
                    totalSpeedBonus += getElderPositionBonus(buildingId)
                }
            }
        }

        return calculateReducedDuration(baseDuration, totalSpeedBonus)
    }

    /**
     * 根据境界获取速度加成（新数值 - 适配多弟子叠加）
     */
    private fun getRealmSpeedBonus(realm: Int): Double {
        return when (realm) {
            0 -> 0.50  // 仙人 +50%
            1 -> 0.40  // 渡劫 +40%
            2 -> 0.35  // 大乘 +35%
            3 -> 0.30  // 合体 +30%
            4 -> 0.25  // 炼虚 +25%
            5 -> 0.20  // 化神 +20%
            6 -> 0.15  // 元婴 +15%
            7 -> 0.10  // 金丹 +10%
            8 -> 0.05  // 筑基 +5%
            else -> 0.0 // 炼气 0%
        }
    }

    /**
     * 根据建筑类型获取属性速度加成
     * @param disciple 弟子对象
     * @param buildingId 建筑类型
     * @return 属性速度加成
     */
    private fun getAttributeSpeedBonus(disciple: Disciple, buildingId: String): Double {
        return when (buildingId) {
            "forge" -> {
                // 炼器建筑使用炼器属性
                val diff = disciple.artifactRefining - 50
                diff * 0.02
            }
            "alchemyRoom" -> {
                // 炼丹建筑使用炼丹属性
                val diff = disciple.pillRefining - 50
                diff * 0.02
            }
            "herbGarden" -> {
                // 灵药园使用种植属性
                val diff = disciple.spiritPlanting - 50
                diff * 0.02
            }
            else -> 0.0
        }
    }

    /**
     * 获取长老职位对建筑的速度加成
     * 长老的对应属性决定加成比例（每点偏离 50 影响 2%）
     * - 天工长老：使用炼器属性
     * - 炼丹长老：使用炼丹属性
     * - 灵药长老：使用种植属性
     * - 藏经阁长老：使用传道属性（单独计算）
     * @param buildingId 建筑类型
     * @return 长老职位速度加成
     */
    private fun getElderPositionBonus(buildingId: String): Double {
        val (elderBuildingId, attributeSelector) = when (buildingId) {
            "forge" -> "forge" to { disciple: Disciple -> disciple.artifactRefining }
            "alchemyRoom" -> "alchemyRoom" to { disciple: Disciple -> disciple.pillRefining }
            "herbGarden" -> "herbGarden" to { disciple: Disciple -> disciple.spiritPlanting }
            else -> return 0.0
        }
        
        // 检查该建筑是否有长老任职
        val elderSlot = _buildingSlots.value.find { 
            it.buildingId == elderBuildingId && it.discipleId != null 
        }
        
        // 如果没有长老任职，返回 0 加成
        val elderDiscipleId = elderSlot?.discipleId ?: return 0.0
        
        // 获取长老弟子对象
        val elderDisciple = _disciples.value.find { it.id == elderDiscipleId } ?: return 0.0
        
        // 计算长老属性加成（每点偏离 50 影响 2%）
        val attributeValue = attributeSelector(elderDisciple)
        val attributeDiff = attributeValue - 50
        val attributeBonus = attributeDiff * 0.02
        
        // 加成最低为 0（属性低于 50 时不产生负加成）
        return attributeBonus.coerceAtLeast(0.0)
    }

    /**
     * 计算藏经阁长老传道属性对弟子功法修炼速度的加成
     * 长老传道属性以 50 为基准，每点偏离影响 1% 的加成
     * 亲传弟子传道属性每 5 点偏离 50 影响 1% 的加成
     * @return 长老和亲传弟子传道属性加成
     */
    private fun calculateLibraryTeachingBonus(): Double {
        // 使用新的长老槽位系统计算加成
        val elderBonus = calculateElderAndDisciplesBonus("library")
        return elderBonus.speedBonus.coerceAtLeast(0.0)
    }

    /**
     * 计算问道峰传道长老和传道师的传道属性对外门弟子修炼速度的加成
     * 传道长老：以50为基准，每高5点增加2%修炼速度
     * 传道师：以50为基准，每高5点增加1%修炼速度
     * @return 传道属性加成（百分比，如0.1表示+10%）
     */
    private fun calculateWenDaoPeakTeachingBonus(): Double {
        val data = _gameData.value
        val elderSlots = data.elderSlots
        
        var totalBonus = 0.0
        
        // 获取传道长老
        val preachingElderId = elderSlots.preachingElder
        if (preachingElderId != null) {
            val preachingElder = _disciples.value.find { it.id == preachingElderId }
            if (preachingElder != null && preachingElder.isAlive) {
                // 传道长老：以50为基准，每高5点增加2%
                val teachingDiff = preachingElder.teaching - 50
                if (teachingDiff > 0) {
                    totalBonus += (teachingDiff / 5.0) * 0.02
                }
            }
        }
        
        // 获取传道师的传道属性加成
        val preachingMasters = elderSlots.preachingMasters
        preachingMasters.filter { it.isActive }.forEach { slot ->
            val master = _disciples.value.find { it.id == slot.discipleId }
            if (master != null && master.isAlive) {
                // 传道师：以50为基准，每高5点增加1%
                val teachingDiff = master.teaching - 50
                if (teachingDiff > 0) {
                    totalBonus += (teachingDiff / 5.0) * 0.01
                }
            }
        }
        
        return totalBonus
    }

    /**
     * 计算青云峰传道长老和传道师的传道属性对内门弟子修炼速度的加成
     * 传道长老：以50为基准，每高5点增加3%修炼速度
     * 传道师：以50为基准，每高5点增加1%修炼速度
     * @return 传道属性加成（百分比，如0.1表示+10%）
     */
    private fun calculateQingyunPeakTeachingBonus(): Double {
        val data = _gameData.value
        val elderSlots = data.elderSlots
        
        var totalBonus = 0.0
        
        // 获取青云峰传道长老
        val qingyunPreachingElderId = elderSlots.qingyunPreachingElder
        if (qingyunPreachingElderId != null) {
            val qingyunPreachingElder = _disciples.value.find { it.id == qingyunPreachingElderId }
            if (qingyunPreachingElder != null && qingyunPreachingElder.isAlive) {
                // 传道长老：以50为基准，每高5点增加3%
                val teachingDiff = qingyunPreachingElder.teaching - 50
                if (teachingDiff > 0) {
                    totalBonus += (teachingDiff / 5.0) * 0.03
                }
            }
        }
        
        // 获取青云峰传道师的传道属性加成
        val qingyunPreachingMasters = elderSlots.qingyunPreachingMasters
        qingyunPreachingMasters.filter { it.isActive }.forEach { slot ->
            val master = _disciples.value.find { it.id == slot.discipleId }
            if (master != null && master.isAlive) {
                // 传道师：以50为基准，每高5点增加1%
                val teachingDiff = master.teaching - 50
                if (teachingDiff > 0) {
                    totalBonus += (teachingDiff / 5.0) * 0.01
                }
            }
        }
        
        return totalBonus
    }

    /**
     * 计算天赋速度加成（新数值 - 适配多弟子叠加）
     */
    private fun calculateTalentSpeedBonus(disciple: Disciple, buildingId: String): Double {
        val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
        val cultivationSpeedBonus = talentEffects["cultivationSpeed"] ?: 0.0
        val craftFlatBonus = getBuildingCraftFlatBonus(talentEffects, buildingId) * 0.010
        return cultivationSpeedBonus + craftFlatBonus
    }

    /**
     * 计算功法速度加成（新数值 - 适配多弟子叠加）
     * 使用实际存在的生产类功法ID，并加入熟练度加成
     */
    private fun calculateManualSpeedBonus(disciple: Disciple, buildingId: String): Double {
        return 0.0
    }
    
    /**
     * 计算减少后的时间
     * 确保高加成能明显减少月份
     */
    private fun calculateReducedDuration(baseDuration: Int, speedBonus: Double): Int {
        if (speedBonus <= 0) return baseDuration

        val reductionPercent = speedBonus / 4.0

        val reducedMonths = (baseDuration * reductionPercent).toInt()
        return (baseDuration - reducedMonths).coerceAtLeast(1)
    }

    /**
     * 计算天赋加成（新数值）
     */
    private fun calculateTalentBonus(disciple: Disciple, buildingId: String): Double {
        val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
        val cultivationSpeedBonus = (talentEffects["cultivationSpeed"] ?: 0.0) * 0.60
        val craftFlatBonus = getBuildingCraftFlatBonus(talentEffects, buildingId) * 0.015
        return cultivationSpeedBonus + craftFlatBonus
    }

    /**
     * 计算功法加成（新数值）
     */
    private fun calculateManualBonus(disciple: Disciple, buildingId: String): Double {
        return 0.0
    }

    /**
     * 计算成功率加成（新系统）
     * 包含：境界加成 + 天赋加成 + 功法加成
     */
    fun calculateSuccessRateBonus(disciple: Disciple?, buildingId: String): Double {
        if (disciple == null) return 0.0

        var bonus = 0.0

        // 1. 境界加成
        bonus += getRealmSuccessRateBonus(disciple.realm)

        // 2. 天赋加成
        bonus += getSuccessRateTalentBonus(disciple, buildingId)

        // 3. 功法加成
        bonus += getSuccessRateManualBonus(disciple, buildingId)

        return bonus
    }

    /**
     * 根据境界获取成功率加成
     */
    private fun getRealmSuccessRateBonus(realm: Int): Double {
        return when (realm) {
            0 -> 0.30  // 仙人 +30%
            1 -> 0.25  // 渡劫 +25%
            2 -> 0.22  // 大乘 +22%
            3 -> 0.19  // 合体 +19%
            4 -> 0.16  // 炼虚 +16%
            5 -> 0.13  // 化神 +13%
            6 -> 0.10  // 元婴 +10%
            7 -> 0.07  // 金丹 +7%
            8 -> 0.04  // 筑基 +4%
            else -> 0.0 // 炼气 0%
        }
    }

    /**
     * 获取成功率相关天赋加成
     */
    private fun getSuccessRateTalentBonus(disciple: Disciple, buildingId: String): Double {
        val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
        val breakthroughBonus = (talentEffects["breakthroughChance"] ?: 0.0) * 0.80
        val craftFlatBonus = getBuildingCraftFlatBonus(talentEffects, buildingId) * 0.006
        return breakthroughBonus + craftFlatBonus
    }

    private fun getBuildingCraftFlatBonus(talentEffects: Map<String, Double>, buildingId: String): Double {
        return when (buildingId) {
            "alchemyRoom" -> talentEffects["pillRefiningFlat"] ?: 0.0
            "forge" -> talentEffects["artifactRefiningFlat"] ?: 0.0
            "herbGarden" -> talentEffects["spiritPlantingFlat"] ?: 0.0
            else -> 0.0
        }
    }

    /**
     * 获取成功率相关功法加成
     */
    private fun getSuccessRateManualBonus(disciple: Disciple, buildingId: String): Double {
        return 0.0
    }

    fun startAlchemy(slotIndex: Int, recipeId: String): Boolean {
        val recipe = PillRecipeDatabase.getRecipeById(recipeId) ?: return false

        // 先检查槽位是否正在工作中（防止重复点击）
        val existingSlot = _buildingSlots.value.find {
            it.buildingId == "alchemyRoom" && it.slotIndex == slotIndex
        }
        if (existingSlot?.status == SlotStatus.WORKING) {
            addEvent("该炼丹槽位正在工作中", EventType.WARNING)
            return false
        }

        // 检查草药材料是否足够（同时匹配名称和品阶）
        val hasMaterials = recipe.materials.all { (herbId, requiredAmount) ->
            val herbData = HerbDatabase.getHerbById(herbId)
            val herbName = herbData?.name
            val herbRarity = herbData?.rarity ?: 1
            val herb = _herbs.value.find { it.name == herbName && it.rarity == herbRarity }
            herb != null && herb.quantity >= requiredAmount
        }

        if (!hasMaterials) {
            addEvent("草药材料不足，无法开始炼丹", EventType.WARNING)
            return false
        }

        // 扣除草药材料（同时匹配名称和品阶）
        recipe.materials.forEach { (herbId, requiredAmount) ->
            val herbData = HerbDatabase.getHerbById(herbId)
            val herbName = herbData?.name
            val herbRarity = herbData?.rarity ?: 1
            _herbs.value = _herbs.value.map { herb ->
                if (herb.name == herbName && herb.rarity == herbRarity) {
                    herb.copy(quantity = herb.quantity - requiredAmount)
                } else {
                    herb
                }
            }.filter { it.quantity > 0 }
        }

        // 使用配方中定义的炼丹时间
        val duration = recipe.duration
        startBuildingWork("alchemyRoom", slotIndex, recipeId, duration)
        
        // 同步更新 _alchemySlots 以便 UI 显示
        val data = _gameData.value
        val actualSlot = _buildingSlots.value.find {
            it.buildingId == "alchemyRoom" && it.slotIndex == slotIndex
        }
        if (actualSlot != null) {
            val newAlchemySlot = AlchemySlot(
                slotIndex = slotIndex,
                recipeId = recipeId,
                recipeName = recipe.name,
                pillName = recipe.name,
                pillRarity = recipe.rarity,
                startYear = data.gameYear,
                startMonth = data.gameMonth,
                duration = actualSlot.duration,
                status = AlchemySlotStatus.WORKING,
                successRate = recipe.successRate,
                requiredMaterials = recipe.materials
            )
            
            val currentAlchemySlots = _alchemySlots.value
            if (slotIndex < currentAlchemySlots.size) {
                _alchemySlots.value = currentAlchemySlots.mapIndexed { index, s -> 
                    if (index == slotIndex) newAlchemySlot else s 
                }
            } else {
                val filledSlots = currentAlchemySlots.toMutableList()
                while (filledSlots.size < slotIndex) {
                    filledSlots.add(AlchemySlot(slotIndex = filledSlots.size))
                }
                filledSlots.add(newAlchemySlot)
                _alchemySlots.value = filledSlots
            }
        }
        
        return true
    }

    fun startForging(slotIndex: Int, recipeId: String): Boolean {
        val recipe = ForgeRecipeDatabase.getRecipeById(recipeId) ?: return false

        // 先检查槽位是否正在工作中（防止重复点击）
        val existingSlot = _buildingSlots.value.find {
            it.buildingId == "forge" && it.slotIndex == slotIndex
        }
        if (existingSlot?.status == SlotStatus.WORKING) {
            addEvent("该锻造槽位正在工作中", EventType.WARNING)
            return false
        }

        // 检查材料是否足够（同时匹配名称和品阶）
        val hasMaterials = recipe.materials.all { (materialId, requiredAmount) ->
            val beastMaterial = BeastMaterialDatabase.getMaterialById(materialId)
            val materialName = beastMaterial?.name
            val materialRarity = beastMaterial?.rarity ?: 1
            val material = _materials.value.find { it.name == materialName && it.rarity == materialRarity }
            material != null && material.quantity >= requiredAmount
        }

        if (!hasMaterials) {
            addEvent("材料不足，无法开始锻造", EventType.WARNING)
            return false
        }

        // 扣除材料（同时匹配名称和品阶）
        recipe.materials.forEach { (materialId, requiredAmount) ->
            val beastMaterial = BeastMaterialDatabase.getMaterialById(materialId)
            val materialName = beastMaterial?.name
            val materialRarity = beastMaterial?.rarity ?: 1
            _materials.value = _materials.value.map { material ->
                if (material.name == materialName && material.rarity == materialRarity) {
                    material.copy(quantity = material.quantity - requiredAmount)
                } else {
                    material
                }
            }.filter { it.quantity > 0 }
        }

        val duration = ForgeRecipeDatabase.getDurationByTier(recipe.tier)
        startBuildingWork("forge", slotIndex, recipeId, duration)
        return true
    }
    
    fun collectBuildingResult(slotId: String) {
        val slot = _buildingSlots.value.find { it.id == slotId } ?: return
        
        if (slot.status != SlotStatus.COMPLETED) {
            addEvent("工作尚未完成", EventType.WARNING)
            return
        }
        
        completeBuildingTask(slot)
        
        val disciple = _disciples.value.find { it.id == slot.discipleId }
        if (disciple != null) {
            _disciples.value = _disciples.value.map { 
                if (it.id == disciple.id) it.copy(status = DiscipleStatus.IDLE) else it 
            }
        }
        
        _buildingSlots.value = _buildingSlots.value.filter { it.id != slotId }
    }

    fun getAlchemySlots(): List<AlchemySlot> {
        return _alchemySlots.value
    }

    fun startAlchemy(slotIndex: Int, recipe: AlchemyRecipe): Boolean {
        val currentSlots = _alchemySlots.value
        
        if (slotIndex < 0 || slotIndex >= 3) {
            addEvent("无效的炼丹槽位", EventType.ERROR)
            return false
        }
        
        val slot = currentSlots.getOrNull(slotIndex)
        if (slot != null && slot.status == AlchemySlotStatus.WORKING) {
            addEvent("该槽位正在炼制中", EventType.WARNING)
            return false
        }
        
        val materials = _materials.value
        if (!recipe.hasEnoughMaterials(materials)) {
            val missing = recipe.getMissingMaterials(materials)
            addEvent("材料不足：${missing.joinToString { "${it.first} x${it.second}" }}", EventType.WARNING)
            return false
        }
        
        val consumedMaterials = materials.map { mat ->
            val required = recipe.materials[mat.id] ?: 0
            if (required > 0) {
                mat.copy(quantity = (mat.quantity - required).coerceAtLeast(0))
            } else {
                mat
            }
        }.filter { it.quantity > 0 }
        
        _materials.value = consumedMaterials
        
        val data = _gameData.value
        val newSlot = AlchemySlot(
            slotIndex = slotIndex,
            recipeId = recipe.id,
            recipeName = recipe.name,
            pillName = recipe.pillName,
            pillRarity = recipe.pillRarity,
            startYear = data.gameYear,
            startMonth = data.gameMonth,
            duration = recipe.duration,
            status = AlchemySlotStatus.WORKING,
            successRate = recipe.successRate,
            requiredMaterials = recipe.materials
        )
        
        if (slotIndex < currentSlots.size) {
            _alchemySlots.value = currentSlots.mapIndexed { index, s -> if (index == slotIndex) newSlot else s }
        } else {
            val filledSlots = currentSlots.toMutableList()
            while (filledSlots.size < slotIndex) {
                filledSlots.add(AlchemySlot(slotIndex = filledSlots.size))
            }
            filledSlots.add(newSlot)
            _alchemySlots.value = filledSlots
        }

        // 同步更新 _buildingSlots
        val newBuildingSlot = BuildingSlot(
            buildingId = "alchemyRoom",
            slotIndex = slotIndex,
            recipeId = recipe.id,
            recipeName = recipe.name,
            startYear = data.gameYear,
            startMonth = data.gameMonth,
            duration = recipe.duration,
            status = SlotStatus.WORKING
        )
        val existingBuildingSlot = _buildingSlots.value.find { 
            it.buildingId == "alchemyRoom" && it.slotIndex == slotIndex 
        }
        _buildingSlots.value = if (existingBuildingSlot != null) {
            _buildingSlots.value.map { if (it.id == existingBuildingSlot.id) newBuildingSlot else it }
        } else {
            _buildingSlots.value + newBuildingSlot
        }
        
        addEvent("开始炼制${recipe.name}", EventType.INFO)
        return true
    }

    fun collectAlchemyResult(slotIndex: Int): AlchemyResult? {
        val currentSlots = _alchemySlots.value
        val slot = currentSlots.getOrNull(slotIndex) ?: return null
        
        if (slot.status != AlchemySlotStatus.WORKING) {
            addEvent("该槽位没有正在炼制的丹药", EventType.WARNING)
            return null
        }
        
        val data = _gameData.value
        if (!slot.isFinished(data.gameYear, data.gameMonth)) {
            addEvent("炼制尚未完成", EventType.WARNING)
            return null
        }
        
        val success = Random.nextDouble() <= slot.successRate
        
        var pill: Pill? = null
        if (success) {
            pill = Pill(
                name = slot.pillName,
                rarity = slot.pillRarity,
                description = "通过炼丹炉炼制而成",
                quantity = 1
            )
            addPillToWarehouse(pill)
            addEvent("炼制成功！获得${slot.pillName}", EventType.INFO)
        } else {
            addEvent("炼制失败，材料损毁", EventType.ERROR)
        }
        
        _alchemySlots.value = currentSlots.mapIndexed { index, s ->
            if (index == slotIndex) {
                s.copy(
                    status = AlchemySlotStatus.IDLE,
                    recipeId = null,
                    recipeName = "",
                    pillName = "",
                    startYear = 0,
                    startMonth = 0,
                    duration = 0
                )
            } else {
                s
            }
        }

        // 同步更新 _buildingSlots
        _buildingSlots.value = _buildingSlots.value.filter {
            !(it.buildingId == "alchemyRoom" && it.slotIndex == slotIndex)
        }
        
        return AlchemyResult(
            success = success,
            pill = pill,
            message = if (success) "成功" else "失败"
        )
    }
    
    fun clearAlchemySlot(slotIndex: Int) {
        val currentSlots = _alchemySlots.value
        val slot = currentSlots.getOrNull(slotIndex) ?: return
        
        _alchemySlots.value = currentSlots.mapIndexed { index, s ->
            if (index == slotIndex) {
                s.copy(
                    status = AlchemySlotStatus.IDLE,
                    recipeId = null,
                    recipeName = "",
                    pillName = "",
                    startYear = 0,
                    startMonth = 0,
                    duration = 0
                )
            } else {
                s
            }
        }

        // 同步更新 _buildingSlots
        _buildingSlots.value = _buildingSlots.value.filter {
            !(it.buildingId == "alchemyRoom" && it.slotIndex == slotIndex)
        }

        addEvent("已取消炼丹任务", EventType.INFO)
    }
    
    fun harvestHerb(slotIndex: Int) {
        val data = _gameData.value
        val currentSlots = data.herbGardenPlantSlots
        
        if (slotIndex < 0 || slotIndex >= currentSlots.size) {
            addEvent("无效的种植槽位", EventType.WARNING)
            return
        }
        
        val slot = currentSlots[slotIndex]
        
        if (slot.status != "mature") {
            addEvent("该槽位没有成熟的草药", EventType.WARNING)
            return
        }
        
        val herbId = slot.harvestHerbId ?: slot.seedId?.removeSuffix("Seed")
        if (herbId == null) {
            addEvent("草药数据错误", EventType.ERROR)
            return
        }
        
        val herb = HerbDatabase.getHerbById(herbId)
        if (herb == null) {
            addEvent("草药数据错误", EventType.ERROR)
            return
        }
        
        val elderBonus = calculateElderAndDisciplesBonus("herbGarden")
        val herbGardenSystem = HerbGardenSystem
        val herbGardenDiscipleSlots = _buildingSlots.value.filter { 
            it.buildingId == "herbGarden" && it.slotIndex in 10..12 
        }
        val discipleSlots = herbGardenDiscipleSlots.map { buildingSlot ->
            HerbGardenSystem.DiscipleSlot(
                index = buildingSlot.slotIndex - 10,
                discipleId = buildingSlot.discipleId,
                discipleName = buildingSlot.discipleId?.let { id ->
                    _disciples.value.find { it.id == id }?.name
                }
            )
        }
        val discipleYieldBonus = herbGardenSystem.calculateYieldBonus(
            discipleSlots = discipleSlots,
            disciples = _disciples.value,
            manualProficiencies = data.manualProficiencies,
            manuals = _manuals.value
        )
        val totalYieldBonus = discipleYieldBonus + elderBonus.yieldBonus
        val actualYield = herbGardenSystem.calculateIncreasedYield(slot.harvestAmount, totalYieldBonus)
        
        val currentHerbs = _herbs.value.toMutableList()
        val existingHerbIndex = currentHerbs.indexOfFirst { 
            it.name == herb.name && it.rarity == herb.rarity 
        }
        
        if (existingHerbIndex >= 0) {
            currentHerbs[existingHerbIndex] = currentHerbs[existingHerbIndex].copy(
                quantity = currentHerbs[existingHerbIndex].quantity + actualYield
            )
        } else {
            currentHerbs.add(Herb(
                id = UUID.randomUUID().toString(),
                name = herb.name,
                rarity = herb.rarity,
                description = herb.description,
                category = herb.category,
                quantity = actualYield
            ))
        }
        
        _herbs.value = currentHerbs.toList()
        
        val updatedSlots = currentSlots.toMutableList()
        updatedSlots[slotIndex] = PlantSlotData(index = slotIndex)
        _gameData.value = data.copy(herbGardenPlantSlots = updatedSlots)
        
        addEvent("收获${herb.name} x${actualYield}", EventType.SUCCESS)
    }
    
    fun clearPlantSlot(slotIndex: Int) {
        val data = _gameData.value
        val currentSlots = data.herbGardenPlantSlots.toMutableList()
        
        if (slotIndex < 0 || slotIndex >= currentSlots.size) {
            return
        }
        
        currentSlots[slotIndex] = PlantSlotData(index = slotIndex)
        _gameData.value = data.copy(herbGardenPlantSlots = currentSlots)
        addEvent("已移除种植任务", EventType.INFO)
    }
    
    fun clearForgeSlot(slotIndex: Int) {
        val existingSlot = _buildingSlots.value.find {
            it.buildingId == "forge" && it.slotIndex == slotIndex
        }
        
        if (existingSlot != null && existingSlot.status == SlotStatus.WORKING) {
            addEvent("该槽位正在工作中，无法移除", EventType.WARNING)
            return
        }
        
        _buildingSlots.value = _buildingSlots.value.filter {
            !(it.buildingId == "forge" && it.slotIndex == slotIndex)
        }
        addEvent("已取消锻造任务", EventType.INFO)
    }
    
    fun collectForgeResult(slotIndex: Int): ForgeResult? {
        val existingSlot = _buildingSlots.value.find {
            it.buildingId == "forge" && it.slotIndex == slotIndex
        }
        
        if (existingSlot == null) {
            addEvent("无效的炼器槽位", EventType.WARNING)
            return null
        }
        
        if (existingSlot.status != SlotStatus.COMPLETED) {
            addEvent("锻造尚未完成", EventType.WARNING)
            return null
        }
        
        val recipeId = existingSlot.recipeId
        if (recipeId == null) {
            addEvent("配方数据错误", EventType.ERROR)
            _buildingSlots.value = _buildingSlots.value.filter { it.id != existingSlot.id }
            return null
        }
        
        val recipe = ForgeRecipeDatabase.getRecipeById(recipeId)
        if (recipe == null) {
            addEvent("配方数据错误", EventType.ERROR)
            _buildingSlots.value = _buildingSlots.value.filter { it.id != existingSlot.id }
            return null
        }
        
        val elderBonus = calculateElderAndDisciplesBonus("forge")
        val disciple = _disciples.value.find { it.id == existingSlot.discipleId }
        val discipleSuccessBonus = calculateSuccessRateBonus(disciple, "forge")
        val totalSuccessBonus = discipleSuccessBonus + elderBonus.successBonus
        val finalSuccessRate = recipe.successRate + totalSuccessBonus
        
        val success = Random.nextDouble() < finalSuccessRate
        
        var equipment: Equipment? = null
        if (success) {
            equipment = createEquipmentFromRecipe(recipe)
            _equipment.value = _equipment.value + equipment
            val bonusText = if (totalSuccessBonus > 0) " (成功率+${GameUtils.formatPercent(totalSuccessBonus)})" else ""
            addEvent("锻造成功！获得${recipe.name}${bonusText}", EventType.SUCCESS)
        } else {
            addEvent("锻造失败，${recipe.name}化为灰烬", EventType.WARNING)
        }
        
        _buildingSlots.value = _buildingSlots.value.filter { it.id != existingSlot.id }
        
        return ForgeResult(
            success = success,
            equipment = equipment,
            message = if (success) "成功" else "失败"
        )
    }
    
    private fun getBuildingName(buildingId: String): String {
        return when (buildingId) {
            "herbGarden" -> "灵药园"
            "alchemyRoom" -> "炼丹房"
            "forge" -> "天工峰"
            "library" -> "藏经阁"
            "spiritMine" -> "灵矿"
            "tianShuHall" -> "天枢殿"
            else -> "建筑"
        }
    }
    
    fun sortWarehouse() {
        _equipment.value = _equipment.value.sortedWith(compareByDescending<Equipment> { it.rarity }.thenBy { it.name })
        _manuals.value = _manuals.value.sortedWith(compareByDescending<Manual> { it.rarity }.thenBy { it.name })
        _pills.value = _pills.value.sortedWith(compareByDescending<Pill> { it.rarity }.thenBy { it.name })
        _materials.value = _materials.value.sortedWith(compareByDescending<Material> { it.rarity }.thenBy { it.name })
        _herbs.value = _herbs.value.sortedWith(compareByDescending<Herb> { it.rarity }.thenBy { it.name })
        _seeds.value = _seeds.value.sortedWith(compareByDescending<Seed> { it.rarity }.thenBy { it.name })
        
        addEvent("仓库整理完成", EventType.INFO)
    }

    /**
     * 开始探查任务
     * 探查队伍将前往目标宗门进行探查，期间 100% 发生一次战斗
     * 胜利后目标宗门的弟子信息会显示，有效期 20 年
     */
    fun startScoutMission(memberIds: List<String>, targetSect: WorldSect, currentYear: Int, currentMonth: Int, currentDay: Int) {
        val data = _gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        
        if (playerSect == null) {
            addEvent("无法找到玩家宗门，探查失败", EventType.WARNING)
            return
        }
        
        // 计算距离
        val distance = kotlin.math.sqrt(
            (targetSect.x - playerSect.x) * (targetSect.x - playerSect.x) + 
            (targetSect.y - playerSect.y) * (targetSect.y - playerSect.y)
        )
        
        val travelDays = (distance / 200 * 30).toInt().coerceIn(1, 360)
        
        var arrivalDay = currentDay + travelDays
        var arrivalMonth = currentMonth
        var arrivalYear = currentYear
        
        while (arrivalDay > 30) {
            arrivalDay -= 30
            arrivalMonth++
            if (arrivalMonth > 12) {
                arrivalMonth = 1
                arrivalYear++
            }
        }
        
        // 创建临时探查队伍
        val team = ExplorationTeam(
            id = UUID.randomUUID().toString(),
            name = "探查队伍",
            memberIds = memberIds,
            memberNames = memberIds.mapNotNull { id -> _disciples.value.find { it.id == id }?.name },
            dungeon = "scout",
            dungeonName = "探查-${targetSect.name}",
            startYear = currentYear,
            startMonth = currentMonth,
            startDay = currentDay,
            duration = travelDays,
            status = ExplorationStatus.SCOUTING,
            scoutTargetSectId = targetSect.id,
            scoutTargetSectName = targetSect.name,
            currentX = playerSect.x,
            currentY = playerSect.y,
            targetX = targetSect.x,
            targetY = targetSect.y,
            moveProgress = 0f,
            arrivalYear = arrivalYear,
            arrivalMonth = arrivalMonth,
            arrivalDay = arrivalDay,
            route = listOf(playerSect.id, targetSect.id),
            currentRouteIndex = 0,
            currentSegmentProgress = 0f
        )
        _teams.value = _teams.value + team

        // 更新弟子状态
        memberIds.forEach { memberId ->
            val disciple = _disciples.value.find { it.id == memberId }
            if (disciple != null) {
                val updated = disciple.copy(status = DiscipleStatus.SCOUTING)
                _disciples.value = _disciples.value.map { if (it.id == memberId) updated else it }
            }
        }

        addEvent("探查队伍出发前往${targetSect.name}进行探查，预计${travelDays}天后到达", EventType.INFO)
    }

    /**
     * 触发探查战斗
     * 使用AI宗门的真实弟子进行战斗
     */
    private fun triggerScoutBattle(team: ExplorationTeam, targetSect: WorldSect, currentYear: Int, currentMonth: Int) {
        // 获取队伍成员
        val teamMembers = team.memberIds.mapNotNull { id ->
            _disciples.value.find { it.id == id }
        }.filter { it.isAlive }

        if (teamMembers.isEmpty()) {
            addEvent("探查队伍【${team.name}】全员阵亡，探查失败", EventType.DANGER)
            completeScoutMission(team, false, targetSect, currentYear, currentMonth)
            return
        }

        // 从目标宗门的真实AI弟子中选择战斗敌人
        // 筛选筑基（8）和炼气（9）境界的存活弟子
        val eligibleDisciples = targetSect.aiDisciples
            .filter { it.isAlive && (it.realm == 8 || it.realm == 9) }
            .shuffled()
            .take(20)

        // 如果没有符合条件的弟子，探查自动成功
        if (eligibleDisciples.isEmpty()) {
            addEvent("探查队伍【${team.name}】发现${targetSect.name}没有筑基及以下弟子，探查成功", EventType.SUCCESS)
            completeScoutMission(team, true, targetSect, currentYear, currentMonth)
            return
        }

        // 将AI弟子转换为战斗敌人格式
        val enemies = generateScoutEnemiesFromAIDisciples(eligibleDisciples)

        // 简化战斗：计算双方总战力
        val teamPower = teamMembers.sumOf { member ->
            val stats = member.getBaseStats()
            stats.physicalAttack + stats.physicalDefense + stats.maxHp / 10
        }.toDouble()

        val enemyPower = enemies.sumOf { enemy ->
            enemy.attack + enemy.defense + enemy.hp / 10
        }.toDouble()

        // 战斗结果判定（加入随机因素）
        val teamAdvantage = teamPower / (teamPower + enemyPower)
        val randomFactor = Random.nextDouble() * 0.4 - 0.2 // -0.2 到 0.2 的随机波动
        val finalAdvantage = teamAdvantage + randomFactor

        val teamWins = finalAdvantage > 0.5

        // 计算伤亡
        val teamCasualties = if (teamWins) {
            (teamMembers.size * (1 - finalAdvantage) * Random.nextDouble() * 0.5).toInt()
        } else {
            teamMembers.size
        }

        val enemiesDefeated = if (teamWins) {
            enemies.size
        } else {
            (enemies.size * finalAdvantage * Random.nextDouble()).toInt()
        }

        // 提前确定伤亡弟子列表（确保BattleLog和状态更新一致）
        val casualties = if (teamWins) {
            teamMembers.shuffled().take(teamCasualties)
        } else {
            teamMembers
        }

        // 确定被击败的AI弟子列表
        val defeatedEnemyIds = if (teamWins) {
            enemies.map { it.id }.toSet()
        } else {
            enemies.shuffled().take(enemiesDefeated).map { it.id }.toSet()
        }

        // 生成战斗回合记录
        val rounds = generateScoutBattleRounds(teamMembers, enemies, teamWins, teamCasualties, enemiesDefeated)

        // 记录战斗日志
        val battleLog = BattleLog(
            id = UUID.randomUUID().toString(),
            dungeonName = "探查-${targetSect.name}",
            teamMembers = teamMembers.map { member ->
                BattleLogMember(
                    id = member.id,
                    name = member.name,
                    realm = member.realm,
                    realmName = member.realmName,
                    isAlive = !casualties.any { it.id == member.id }
                )
            },
            enemies = enemies.map { enemy ->
                BattleLogEnemy(
                    name = enemy.name,
                    realm = enemy.realm,
                    realmName = enemy.realmName
                )
            },
            rounds = rounds,
            result = if (teamWins) BattleResult.WIN else BattleResult.LOSE,
            battleResult = BattleLogResult(
                winner = if (teamWins) "team" else "enemy",
                turns = rounds.size,
                teamCasualties = teamCasualties,
                beastsDefeated = enemiesDefeated
            ),
            year = currentYear,
            month = currentMonth
        )
        _battleLogs.value = listOf(battleLog) + _battleLogs.value.take(49)

        // 处理战斗结果
        if (teamWins) {
            addEvent("探查队伍【${team.name}】在${targetSect.name}探查成功，击败敌军", EventType.SUCCESS)
            completeScoutMission(team, true, targetSect, currentYear, currentMonth)
        } else {
            addEvent("探查队伍【${team.name}】在${targetSect.name}探查失败，全军覆没", EventType.DANGER)
            completeScoutMission(team, false, targetSect, currentYear, currentMonth)
        }

        // 更新玩家弟子状态（根据战斗结果）
        casualties.forEach { casualty ->
            val updatedDisciple = casualty.copy(isAlive = false)
            _disciples.value = _disciples.value.map { if (it.id == casualty.id) updatedDisciple else it }
        }

        // 更新AI宗门弟子状态（被击败的弟子标记为死亡）
        if (defeatedEnemyIds.isNotEmpty()) {
            val updatedSects = _gameData.value.worldMapSects.map { sect ->
                if (sect.id == targetSect.id) {
                    val updatedAiDisciples = sect.aiDisciples.map { disciple ->
                        if (defeatedEnemyIds.contains(disciple.id)) {
                            disciple.copy(isAlive = false)
                        } else {
                            disciple
                        }
                    }
                    sect.copy(aiDisciples = updatedAiDisciples)
                } else {
                    sect
                }
            }
            _gameData.value = _gameData.value.copy(worldMapSects = updatedSects)
        }
    }

    /**
     * 从AI宗门真实弟子生成探查敌人
     * 使用AI弟子的真实属性
     */
    private fun generateScoutEnemiesFromAIDisciples(aiDisciples: List<Disciple>): List<BattleEnemy> {
        return aiDisciples.map { disciple ->
            val stats = disciple.getBaseStats()
            BattleEnemy(
                id = disciple.id,
                name = disciple.name,
                realm = disciple.realm,
                realmName = disciple.realmNameOnly,
                realmLayer = disciple.realmLayer,
                hp = stats.maxHp,
                maxHp = stats.maxHp,
                attack = stats.physicalAttack,
                defense = stats.physicalDefense,
                speed = stats.speed
            )
        }
    }

    /**
     * 生成探查战斗回合记录
     * 模拟战斗过程，生成回合信息
     */
    private fun generateScoutBattleRounds(
        teamMembers: List<Disciple>,
        enemies: List<BattleEnemy>,
        teamWins: Boolean,
        teamCasualties: Int,
        enemiesDefeated: Int
    ): List<BattleLogRound> {
        val rounds = mutableListOf<BattleLogRound>()
        val maxRounds = Random.nextInt(3, 8) // 3-7回合

        // 创建可变的战斗单位列表
        val aliveTeamMembers = teamMembers.toMutableList()
        val aliveEnemies = enemies.toMutableList()

        for (roundNum in 1..maxRounds) {
            val actions = mutableListOf<BattleLogAction>()

            // 队伍成员行动
            aliveTeamMembers.shuffled().take(3).forEach { member ->
                if (aliveEnemies.isNotEmpty()) {
                    val target = aliveEnemies.random()
                    val damage = member.getBaseStats().physicalAttack
                    actions.add(BattleLogAction(
                        type = "attack",
                        attacker = member.name,
                        attackerType = "disciple",
                        target = target.name,
                        damage = damage,
                        damageType = "physical",
                        isCrit = Random.nextDouble() < 0.1
                    ))
                }
            }

            // 敌人行动
            aliveEnemies.shuffled().take(3).forEach { enemy ->
                if (aliveTeamMembers.isNotEmpty()) {
                    val target = aliveTeamMembers.random()
                    actions.add(BattleLogAction(
                        type = "attack",
                        attacker = enemy.name,
                        attackerType = "beast",
                        target = target.name,
                        damage = enemy.attack,
                        damageType = "physical",
                        isCrit = Random.nextDouble() < 0.1
                    ))
                }
            }

            // 模拟伤亡
            if (roundNum == maxRounds) {
                // 最后一回合，根据战斗结果处理
                if (teamWins) {
                    // 敌人全部阵亡
                    aliveEnemies.clear()
                } else {
                    // 队伍全部阵亡
                    aliveTeamMembers.clear()
                }
            } else {
                // 中间回合随机伤亡
                if (aliveEnemies.isNotEmpty() && Random.nextDouble() < 0.3) {
                    aliveEnemies.removeAt(Random.nextInt(aliveEnemies.size))
                }
                if (aliveTeamMembers.isNotEmpty() && Random.nextDouble() < 0.2) {
                    aliveTeamMembers.removeAt(Random.nextInt(aliveTeamMembers.size))
                }
            }

            rounds.add(BattleLogRound(
                roundNumber = roundNum,
                actions = actions
            ))

            // 如果一方全灭，结束战斗
            if (aliveTeamMembers.isEmpty() || aliveEnemies.isEmpty()) {
                break
            }
        }

        return rounds
    }

    /**
     * 获取战斗用境界名称
     * 使用GameConfig标准定义：0=仙人, 1=渡劫, 2=大乘, 3=合体, 4=炼虚, 5=化神, 6=元婴, 7=金丹, 8=筑基, 9=炼气
     */
    private fun getRealmNameByIndexForBattle(index: Int): String {
        return GameConfig.Realm.getName(index)
    }

    /**
     * 完成探查任务
     */
    private fun completeScoutMission(team: ExplorationTeam, success: Boolean, targetSect: WorldSect, currentYear: Int, currentMonth: Int) {
        // 恢复队伍状态
        val updatedTeam = team.copy(
            status = ExplorationStatus.COMPLETED,
            scoutTargetSectId = null,
            scoutTargetSectName = ""
        )
        _teams.value = _teams.value.map { if (it.id == team.id) updatedTeam else it }

        // 收集死亡弟子ID
        val deadDiscipleIds = mutableListOf<String>()
        
        // 恢复存活弟子状态，记录死亡弟子
        team.memberIds.forEach { memberId ->
            val disciple = _disciples.value.find { it.id == memberId }
            if (disciple != null) {
                if (disciple.isAlive) {
                    val updated = disciple.copy(status = DiscipleStatus.IDLE)
                    _disciples.value = _disciples.value.map { if (it.id == memberId) updated else it }
                } else {
                    deadDiscipleIds.add(memberId)
                }
            }
        }
        
        // 清理死亡弟子的关联数据并移除
        if (deadDiscipleIds.isNotEmpty()) {
            deadDiscipleIds.forEach { discipleId ->
                clearDiscipleFromAllSlots(discipleId)
            }
            _disciples.value = _disciples.value.filter { it.id !in deadDiscipleIds }
        }

        // 如果探查成功，更新宗门信息
        if (success) {
            val expiryYear = currentYear + 20
            val expiryMonth = currentMonth

            val disciples = targetSect.discipleCountByRealm

            updateSectInfo(targetSect.id, disciples, expiryYear, expiryMonth)

            addEvent("探查成功！${targetSect.name}的弟子信息已获取，有效期至${expiryYear}年${expiryMonth}月", EventType.SUCCESS)
        }
    }

    /**
     * 生成AI宗门弟子分布
     * 小型宗门(0): 最高化神(5)，1-5名化神弟子，以下各境界10-30名
     * 中型宗门(1): 最高炼虚(4)，1-5名炼虚弟子，以下各境界10-30名
     * 大型宗门(2): 最高大乘(2)，1-5名大乘弟子，以下各境界10-30名
     * 顶级宗门(3): 最高渡劫(1)，1-5名渡劫弟子，以下各境界10-30名
     */
    private fun generateSectDisciples(sectLevel: Int): Map<Int, Int> {
        val disciples = mutableMapOf<Int, Int>()

        when (sectLevel) {
            0 -> {
                // 小型宗门：最高化神(5)，1-5名化神弟子，以下各境界10-30名
                disciples[5] = Random.nextInt(1, 6) // 化神 1-5名
                disciples[6] = Random.nextInt(10, 31) // 元婴 10-30名
                disciples[7] = Random.nextInt(10, 31) // 金丹 10-30名
                disciples[8] = Random.nextInt(10, 31) // 筑基 10-30名
                disciples[9] = Random.nextInt(10, 31) // 炼气 10-30名
            }
            1 -> {
                // 中型宗门：最高炼虚(4)，1-5名炼虚弟子，以下各境界10-30名
                disciples[4] = Random.nextInt(1, 6) // 炼虚 1-5名
                disciples[5] = Random.nextInt(10, 31) // 化神 10-30名
                disciples[6] = Random.nextInt(10, 31) // 元婴 10-30名
                disciples[7] = Random.nextInt(10, 31) // 金丹 10-30名
                disciples[8] = Random.nextInt(10, 31) // 筑基 10-30名
                disciples[9] = Random.nextInt(10, 31) // 炼气 10-30名
            }
            2 -> {
                // 大型宗门：最高大乘(2)，1-5名大乘弟子，以下各境界10-30名
                disciples[2] = Random.nextInt(1, 6) // 大乘 1-5名
                disciples[3] = Random.nextInt(10, 31) // 合体 10-30名
                disciples[4] = Random.nextInt(10, 31) // 炼虚 10-30名
                disciples[5] = Random.nextInt(10, 31) // 化神 10-30名
                disciples[6] = Random.nextInt(10, 31) // 元婴 10-30名
                disciples[7] = Random.nextInt(10, 31) // 金丹 10-30名
                disciples[8] = Random.nextInt(10, 31) // 筑基 10-30名
                disciples[9] = Random.nextInt(10, 31) // 炼气 10-30名
            }
            3 -> {
                // 顶级宗门：最高渡劫(1)，1-5名渡劫弟子，以下各境界10-30名
                disciples[1] = Random.nextInt(1, 6) // 渡劫 1-5名
                disciples[2] = Random.nextInt(10, 31) // 大乘 10-30名
                disciples[3] = Random.nextInt(10, 31) // 合体 10-30名
                disciples[4] = Random.nextInt(10, 31) // 炼虚 10-30名
                disciples[5] = Random.nextInt(10, 31) // 化神 10-30名
                disciples[6] = Random.nextInt(10, 31) // 元婴 10-30名
                disciples[7] = Random.nextInt(10, 31) // 金丹 10-30名
                disciples[8] = Random.nextInt(10, 31) // 筑基 10-30名
                disciples[9] = Random.nextInt(10, 31) // 炼气 10-30名
            }
            else -> {
                // 默认按小型宗门处理
                disciples[5] = Random.nextInt(1, 6) // 化神 1-5名
                disciples[6] = Random.nextInt(10, 31) // 元婴 10-30名
                disciples[7] = Random.nextInt(10, 31) // 金丹 10-30名
                disciples[8] = Random.nextInt(10, 31) // 筑基 10-30名
                disciples[9] = Random.nextInt(10, 31) // 炼气 10-30名
            }
        }

        return disciples
    }

    /**
     * 更新宗门信息
     */
    private fun updateSectInfo(sectId: String, disciples: Map<Int, Int>, expiryYear: Int, expiryMonth: Int) {
        val data = _gameData.value
        
        val newScoutInfo = SectScoutInfo(
            sectId = sectId,
            isKnown = true,
            disciples = disciples,
            expiryYear = expiryYear,
            expiryMonth = expiryMonth
        )
        
        val updatedScoutInfo = data.scoutInfo.toMutableMap()
        updatedScoutInfo[sectId] = newScoutInfo
        
        val updatedWorldMapSects = data.worldMapSects.map { sect ->
            if (sect.id == sectId) {
                sect.copy(
                    scoutInfo = newScoutInfo,
                    expiryYear = expiryYear,
                    expiryMonth = expiryMonth,
                    isKnown = true
                )
            } else {
                sect
            }
        }
        
        _gameData.value = data.copy(
            scoutInfo = updatedScoutInfo,
            worldMapSects = updatedWorldMapSects
        )
    }

    /**
     * 处理探查信息过期
     * 每月检查并清除过期的探查信息
     */
    private fun processScoutInfoExpiry(year: Int, month: Int) {
        val data = _gameData.value
        var hasExpired = false
        
        val updatedScoutInfo = data.scoutInfo.filter { (_, info) ->
            val isExpired = year > info.expiryYear || 
                (year == info.expiryYear && month > info.expiryMonth)
            if (isExpired) {
                hasExpired = true
                addEvent("${info.sectId}的探查信息已过期", EventType.INFO)
            }
            !isExpired
        }
        
        if (hasExpired) {
            val updatedWorldMapSects = data.worldMapSects.map { sect ->
                val sectScoutInfo = updatedScoutInfo[sect.id]
                if (sectScoutInfo == null && sect.scoutInfo != null) {
                    sect.copy(
                        scoutInfo = null,
                        isKnown = false,
                        expiryYear = 0,
                        expiryMonth = 0
                    )
                } else {
                    sect
                }
            }
            
            _gameData.value = data.copy(
                scoutInfo = updatedScoutInfo,
                worldMapSects = updatedWorldMapSects
            )
        }
    }

    /**
     * 四元组数据类
     */
    data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    // ==================== 洞府系统 ====================

    /**
     * 处理洞府生命周期
     */
    private fun processCaveLifecycle(year: Int, month: Int) {
        val data = _gameData.value

        val activeCaves = data.cultivatorCaves.filter { cave ->
            !cave.isExpired(year, month) && cave.status != CaveStatus.EXPLORED
        }

        val connectionEdges = buildConnectionEdges(data.worldMapSects)

        val newCaves = CaveGenerator.generateCaves(
            existingSects = data.worldMapSects,
            connectionEdges = connectionEdges,
            currentYear = year,
            currentMonth = month,
            existingCaves = activeCaves,
            maxNewCaves = 2
        )

        val allAITeams = data.aiCaveTeams.toMutableList()
        activeCaves.forEach { cave ->
            val currentTeams = allAITeams.count { 
                it.caveId == cave.id && it.status == AITeamStatus.EXPLORING 
            }
            
            if (currentTeams < 3 && Random.nextDouble() < 0.7) {
                val nearbySects = findNearbySects(cave, 400f)
                val existingTeamForCave = allAITeams.filter { it.caveId == cave.id }
                
                val aiTeam = AICaveTeamGenerator.generateAITeam(cave, nearbySects, existingTeamForCave)
                if (aiTeam != null) {
                    allAITeams.add(aiTeam)
                }
            }
        }
        
        var updatedSectsForAI = _gameData.value.worldMapSects.toMutableList()
        val aiTeamsToRemove = mutableListOf<String>()
        
        allAITeams.filter { it.status == AITeamStatus.EXPLORING }.forEach { aiTeam ->
            val cave = activeCaves.find { it.id == aiTeam.caveId } ?: return@forEach
            
            if (Random.nextDouble() < 0.3) {
                val rewards = CaveExplorationSystem.generateVictoryRewards(cave)
                val warehouseItems = SectWarehouseManager.convertCaveRewardsToWarehouseItems(rewards)
                
                val sectIndex = updatedSectsForAI.indexOfFirst { it.id == aiTeam.sectId }
                if (sectIndex >= 0) {
                    val sect = updatedSectsForAI[sectIndex]
                    val updatedWarehouse = SectWarehouseManager.addItemsToWarehouse(sect.warehouse, warehouseItems)
                    val warehouseWithStones = SectWarehouseManager.addSpiritStonesToWarehouse(
                        updatedWarehouse,
                        rewards.items.filter { it.type == "spiritStones" }.sumOf { it.quantity.toLong() }
                    )
                    updatedSectsForAI[sectIndex] = sect.copy(warehouse = warehouseWithStones)
                    addEvent("${aiTeam.sectName}成功探索了${cave.name}，获得丰厚奖励", EventType.INFO)
                }
                
                aiTeamsToRemove.add(aiTeam.id)
            }
        }
        
        val filteredAITeams = allAITeams.filter { it.id !in aiTeamsToRemove }

        val teamsToComplete = data.caveExplorationTeams.filter { 
            it.status == CaveExplorationStatus.EXPLORING 
        }
        
        var finalCaves = activeCaves.toMutableList()
        var finalAITeams = filteredAITeams.toList()
        var finalExplorationTeams = data.caveExplorationTeams.toMutableList()
        val teamsWithMissingCave = mutableListOf<CaveExplorationTeam>()
        val teamsWithError = mutableListOf<CaveExplorationTeam>()

        teamsToComplete.forEach { team ->
            val cave = finalCaves.find { it.id == team.caveId }
            if (cave == null) {
                teamsWithMissingCave.add(team)
                return@forEach
            }
            try {
                val result = executeCaveExploration(team, cave, finalAITeams)
                
                finalCaves = result.first.toMutableList()
                finalAITeams = result.second
                if (result.third) {
                    finalExplorationTeams.removeAll { it.id == team.id }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing cave exploration for team ${team.id}", e)
                teamsWithError.add(team)
            }
        }

        teamsWithMissingCave.forEach { team ->
            addEvent("探索队伍前往的${team.caveName}已消失，队伍返回宗门", EventType.WARNING)
            resetCaveExplorationTeamMembersStatus(team)
            finalExplorationTeams.removeAll { it.id == team.id }
        }

        teamsWithError.forEach { team ->
            addEvent("探索队伍${team.caveName}发生错误，队伍返回宗门", EventType.WARNING)
            resetCaveExplorationTeamMembersStatus(team)
            finalCaves = finalCaves.map { cave ->
                if (cave.id == team.caveId && cave.status == CaveStatus.EXPLORING) {
                    cave.copy(status = CaveStatus.AVAILABLE)
                } else {
                    cave
                }
            }.toMutableList()
            finalExplorationTeams.removeAll { it.id == team.id }
        }

        val currentData = _gameData.value
        _gameData.value = currentData.copy(
            cultivatorCaves = finalCaves + newCaves,
            aiCaveTeams = finalAITeams,
            caveExplorationTeams = finalExplorationTeams,
            worldMapSects = updatedSectsForAI
        )

        newCaves.forEach { cave ->
            addEvent("发现${cave.ownerRealmName}洞府：${cave.name}", EventType.SUCCESS)
        }
    }

    /**
     * 处理洞府探索失败后的弟子状态更新
     */
    private fun handleCaveExplorationDefeat(team: CaveExplorationTeam, battleResult: BattleSystemResult?) {
        if (battleResult == null) {
            resetCaveExplorationTeamMembersStatus(team)
            return
        }
        
        val deadMemberIds = battleResult.log.teamMembers.filter { !it.isAlive }.map { it.id }.toSet()
        val teamMemberHpMap = battleResult.log.teamMembers.associateBy { it.id }.mapValues { it.value.hp }
        
        team.memberIds.forEach { memberId ->
            val disciple = _disciples.value.find { it.id == memberId }
            if (disciple != null) {
                _disciples.value = _disciples.value.map { d ->
                    if (d.id != memberId) d
                    else if (deadMemberIds.contains(memberId)) {
                        d.copy(isAlive = false, status = DiscipleStatus.IDLE)
                    } else {
                        val finalHp = teamMemberHpMap[memberId] ?: d.maxHp
                        val updatedStatusData = d.statusData + ("currentHp" to finalHp.toString())
                        d.copy(status = DiscipleStatus.IDLE, statusData = updatedStatusData)
                    }
                }
            }
        }
        
        deadMemberIds.forEach { memberId ->
            val disciple = _disciples.value.find { it.id == memberId }
            if (disciple != null) {
                addEvent("${disciple.name}在洞府探索中阵亡", EventType.DANGER)
            }
        }
    }
    
    /**
     * 处理洞府探索失败后的弟子状态更新（使用累积的战斗数据）
     */
    private fun handleCaveExplorationDefeatWithAllData(
        team: CaveExplorationTeam, 
        allDeadMemberIds: Set<String>, 
        allMemberHpMap: Map<String, Int>
    ) {
        team.memberIds.forEach { memberId ->
            val disciple = _disciples.value.find { it.id == memberId }
            if (disciple != null) {
                _disciples.value = _disciples.value.map { d ->
                    if (d.id != memberId) d
                    else if (allDeadMemberIds.contains(memberId)) {
                        d.copy(isAlive = false, status = DiscipleStatus.IDLE)
                    } else {
                        val finalHp = allMemberHpMap[memberId] ?: d.maxHp
                        val updatedStatusData = d.statusData + ("currentHp" to finalHp.toString())
                        d.copy(status = DiscipleStatus.IDLE, statusData = updatedStatusData)
                    }
                }
            }
        }
        
        allDeadMemberIds.forEach { memberId ->
            val disciple = _disciples.value.find { it.id == memberId }
            if (disciple != null) {
                addEvent("${disciple.name}在洞府探索中阵亡", EventType.DANGER)
            }
        }
    }

    /**
     * 执行洞府探索战斗和奖励
     * @return Triple<更新后的洞府列表, 更新后的AI队伍列表, 是否移除探索队伍>
     */
    private fun executeCaveExploration(
        team: CaveExplorationTeam,
        cave: CultivatorCave,
        currentAITeams: List<AICaveTeam>
    ): Triple<List<CultivatorCave>, List<AICaveTeam>, Boolean> {
        val allDeadMemberIds = mutableSetOf<String>()
        val allMemberHpMap = mutableMapOf<String, Int>()
        
        try {
            val equipmentMap = _equipment.value.associateBy { it.id }
            
            var aiTeams = currentAITeams.toMutableList()
            var allVictories = true

            val caveAITeams = aiTeams.filter { 
                it.caveId == cave.id && it.status == AITeamStatus.EXPLORING 
            }
            
            var currentAliveDisciples = _disciples.value.filter { it.id in team.memberIds && it.isAlive }
            
            for (aiTeam in caveAITeams) {
                val battle = CaveExplorationSystem.createAIBattle(currentAliveDisciples, equipmentMap, aiTeam)
                val battleResult = BattleSystem.executeBattle(battle)
                
                clearBattlePillEffects(currentAliveDisciples)

                if (battleResult.victory) {
                    battleResult.log.teamMembers.forEach { member ->
                        if (!member.isAlive) {
                            allDeadMemberIds.add(member.id)
                        }
                        allMemberHpMap[member.id] = member.hp
                    }
                    
                    currentAliveDisciples = currentAliveDisciples.filter { it.id !in allDeadMemberIds }
                    
                    applyBattleVictoryBonuses(
                        memberIds = battleResult.log.teamMembers.filter { it.isAlive }.map { it.id },
                        addSoulPower = false
                    )
                    aiTeams = aiTeams.map { 
                        if (it.id == aiTeam.id) it.copy(status = AITeamStatus.DEFEATED) else it 
                    }.toMutableList()
                    
                    addEvent("击败${aiTeam.sectName}的探索队伍", EventType.SUCCESS)
                } else {
                    allVictories = false
                    battleResult.log.teamMembers.forEach { member ->
                        if (!member.isAlive) {
                            allDeadMemberIds.add(member.id)
                        }
                        allMemberHpMap[member.id] = member.hp
                    }
                    addEvent("被${aiTeam.sectName}的探索队伍击败", EventType.DANGER)
                    break
                }
            }

            if (!allVictories) {
                handleCaveExplorationDefeatWithAllData(team, allDeadMemberIds, allMemberHpMap)
                val updatedCaves = _gameData.value.cultivatorCaves.map { 
                    if (it.id == cave.id) it.copy(status = CaveStatus.AVAILABLE) else it 
                }
                return Triple(updatedCaves, aiTeams.toList(), true)
            }

            if (currentAliveDisciples.isEmpty()) {
                addEvent("探索队伍全员阵亡，探索失败", EventType.DANGER)
                handleCaveExplorationDefeatWithAllData(team, allDeadMemberIds, allMemberHpMap)
                val updatedCaves = _gameData.value.cultivatorCaves.map { 
                    if (it.id == cave.id) it.copy(status = CaveStatus.AVAILABLE) else it 
                }
                return Triple(updatedCaves, aiTeams.toList(), true)
            }

            val guardianBattle = CaveExplorationSystem.createGuardianBattle(currentAliveDisciples, equipmentMap, cave)
            val guardianResult = BattleSystem.executeBattle(guardianBattle)
            
            clearBattlePillEffects(currentAliveDisciples)

            if (!guardianResult.victory) {
                guardianResult.log.teamMembers.forEach { member ->
                    if (!member.isAlive) {
                        allDeadMemberIds.add(member.id)
                    }
                    allMemberHpMap[member.id] = member.hp
                }
                addEvent("被${cave.name}的守护兽击败", EventType.DANGER)
                handleCaveExplorationDefeatWithAllData(team, allDeadMemberIds, allMemberHpMap)
                val updatedCaves = _gameData.value.cultivatorCaves.map { 
                    if (it.id == cave.id) it.copy(status = CaveStatus.AVAILABLE) else it 
                }
                return Triple(updatedCaves, aiTeams.toList(), true)
            }

            guardianResult.log.teamMembers.forEach { member ->
                if (!member.isAlive) {
                    allDeadMemberIds.add(member.id)
                }
                allMemberHpMap[member.id] = member.hp
            }

            applyBattleVictoryBonuses(
                memberIds = guardianResult.log.teamMembers.filter { it.isAlive }.map { it.id },
                addSoulPower = false
            )

            val rewards = CaveExplorationSystem.generateVictoryRewards(cave)
            rewards.items.forEach { item ->
                when (item.type) {
                    "spiritStones" -> {
                        val currentStones = _gameData.value.spiritStones
                        _gameData.value = _gameData.value.copy(
                            spiritStones = currentStones + item.quantity
                        )
                    }
                    "equipment" -> {
                        val template = EquipmentDatabase.getTemplateByName(item.name)
                        if (template != null) {
                            val equipment = Equipment(
                                id = UUID.randomUUID().toString(),
                                name = template.name,
                                slot = template.slot,
                                rarity = item.rarity,
                                physicalAttack = template.physicalAttack,
                                magicAttack = template.magicAttack,
                                physicalDefense = template.physicalDefense,
                                magicDefense = template.magicDefense,
                                speed = template.speed,
                                hp = template.hp,
                                mp = template.mp,
                                description = template.description,
                                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity)
                            )
                            _equipment.value = _equipment.value + equipment
                        }
                    }
                    "pill" -> {
                        val recipe = PillRecipeDatabase.getRecipeById(item.itemId)
                        if (recipe != null) {
                            val pill = Pill(
                                id = UUID.randomUUID().toString(),
                                name = recipe.name,
                                rarity = recipe.rarity,
                                quantity = item.quantity,
                                description = recipe.description,
                                category = recipe.category,
                                breakthroughChance = recipe.breakthroughChance,
                                targetRealm = recipe.targetRealm,
                                cultivationSpeed = recipe.cultivationSpeed,
                                duration = recipe.effectDuration,
                                cultivationPercent = recipe.cultivationPercent,
                                skillExpPercent = recipe.skillExpPercent,
                                extendLife = recipe.extendLife,
                                physicalAttackPercent = recipe.physicalAttackPercent,
                                magicAttackPercent = recipe.magicAttackPercent,
                                physicalDefensePercent = recipe.physicalDefensePercent,
                                magicDefensePercent = recipe.magicDefensePercent,
                                hpPercent = recipe.hpPercent,
                                mpPercent = recipe.mpPercent,
                                speedPercent = recipe.speedPercent,
                                healPercent = recipe.healPercent,
                                healMaxHpPercent = recipe.healMaxHpPercent,
                                heal = recipe.heal,
                                battleCount = recipe.battleCount,
                                mpRecoverMaxMpPercent = recipe.mpRecoverMaxMpPercent
                            )
                            addPillToWarehouse(pill)
                        }
                    }
                    "manual" -> {
                        val template = ManualDatabase.getByName(item.name)
                        if (template != null) {
                            val manual = Manual(
                                id = UUID.randomUUID().toString(),
                                name = template.name,
                                rarity = item.rarity,
                                description = template.description,
                                type = template.type,
                                stats = template.stats,
                                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity)
                            )
                            if (_manuals.value.none { it.id == manual.id }) {
                                _manuals.value = _manuals.value + manual
                            }
                        }
                    }
                }
            }

            val finalCaves = _gameData.value.cultivatorCaves.filter { it.id != cave.id }
            val finalAITeams = aiTeams.filter { it.caveId != cave.id }

            team.memberIds.forEach { memberId ->
                val disciple = _disciples.value.find { it.id == memberId }
                if (disciple != null) {
                    _disciples.value = _disciples.value.map { d ->
                        if (d.id != memberId) d
                        else if (allDeadMemberIds.contains(memberId)) {
                            d.copy(isAlive = false, status = DiscipleStatus.IDLE)
                        } else {
                            val finalHp = allMemberHpMap[memberId] ?: d.maxHp
                            val updatedStatusData = d.statusData + ("currentHp" to finalHp.toString())
                            d.copy(status = DiscipleStatus.IDLE, statusData = updatedStatusData, soulPower = d.soulPower + 1)
                        }
                    }
                }
            }

            allDeadMemberIds.forEach { memberId ->
                val disciple = _disciples.value.find { it.id == memberId }
                if (disciple != null) {
                    addEvent("${disciple.name}在洞府探索中阵亡", EventType.DANGER)
                }
            }

            addEvent("成功探索${cave.name}，获得丰厚奖励！存活弟子神魂+1", EventType.SUCCESS)
            return Triple(finalCaves, finalAITeams, true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in executeCaveExploration for cave ${cave.name}", e)
            addEvent("洞府探索过程中发生错误，队伍返回宗门: ${e.message}", EventType.WARNING)
            team.memberIds.forEach { memberId ->
                val disciple = _disciples.value.find { it.id == memberId }
                if (disciple != null && disciple.status == DiscipleStatus.EXPLORING) {
                    _disciples.value = _disciples.value.map { 
                        if (it.id == memberId) it.copy(status = DiscipleStatus.IDLE) else it 
                    }
                }
            }
            val updatedCaves = _gameData.value.cultivatorCaves.map { 
                if (it.id == cave.id && it.status == CaveStatus.EXPLORING) {
                    it.copy(status = CaveStatus.AVAILABLE)
                } else {
                    it
                }
            }
            return Triple(updatedCaves, currentAITeams, true)
        }
    }

    private fun buildConnectionEdges(sects: List<WorldSect>): List<MSTEdge> {
        val edges = mutableListOf<MSTEdge>()
        val processedPairs = mutableSetOf<Pair<String, String>>()
        
        for (sect in sects) {
            for (connectedId in sect.connectedSectIds) {
                val pairKey = if (sect.id < connectedId) {
                    Pair(sect.id, connectedId)
                } else {
                    Pair(connectedId, sect.id)
                }
                
                if (pairKey !in processedPairs) {
                    processedPairs.add(pairKey)
                    val connectedSect = sects.find { it.id == connectedId }
                    if (connectedSect != null) {
                        val distance = kotlin.math.sqrt(
                            (sect.x - connectedSect.x) * (sect.x - connectedSect.x) +
                            (sect.y - connectedSect.y) * (sect.y - connectedSect.y)
                        ).toDouble()
                        edges.add(MSTEdge(sect, connectedSect, distance))
                    }
                }
            }
        }
        
        return edges
    }

    /**
     * 查找洞府附近的宗门
     */
    private fun findNearbySects(cave: CultivatorCave, range: Float): List<WorldSect> {
        val data = _gameData.value
        return data.worldMapSects.filter { sect ->
            !sect.isPlayerSect &&
            kotlin.math.sqrt(
                (cave.x - sect.x) * (cave.x - sect.x) +
                (cave.y - sect.y) * (cave.y - sect.y)
            ) <= range
        }
    }

    /**
     * 开始洞府探索
     */
    fun startCaveExploration(cave: CultivatorCave, selectedDisciples: List<Disciple>) {
        val data = _gameData.value

        val underageDisciples = selectedDisciples.filter { it.age < 5 }
        if (underageDisciples.isNotEmpty()) {
            val names = underageDisciples.joinToString(", ") { it.name }
            addEvent("以下弟子年龄太小，无法参加洞府探索：$names", EventType.WARNING)
            return
        }

        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val sectX = playerSect?.x ?: 2000f
        val sectY = playerSect?.y ?: 1750f

        val distance = kotlin.math.sqrt(
            (cave.x - sectX) * (cave.x - sectX) +
            (cave.y - sectY) * (cave.y - sectY)
        )
        val travelDuration = (distance / 200 * 30).toInt().coerceIn(1, 360)

        val team = CaveExplorationSystem.createCaveExplorationTeam(
            cave = cave,
            disciples = selectedDisciples,
            currentYear = data.gameYear,
            currentMonth = data.gameMonth,
            sectX = sectX,
            sectY = sectY,
            travelDuration = travelDuration
        )

        val updatedCave = cave.copy(status = CaveStatus.EXPLORING)
        val updatedCaves = data.cultivatorCaves.map { 
            if (it.id == cave.id) updatedCave else it 
        }

        selectedDisciples.forEach { disciple ->
            val updated = disciple.copy(status = DiscipleStatus.EXPLORING)
            _disciples.value = _disciples.value.map { if (it.id == disciple.id) updated else it }
        }

        _gameData.value = data.copy(
            cultivatorCaves = updatedCaves,
            caveExplorationTeams = data.caveExplorationTeams + team
        )

        addEvent("派遣队伍前往${cave.name}探索，预计${travelDuration}天后到达", EventType.INFO)
    }

    /**
     * 获取洞府的AI队伍
     */
    fun getAITeamsForCave(caveId: String): List<AICaveTeam> {
        val data = _gameData.value
        return data.aiCaveTeams.filter { 
            it.caveId == caveId && it.status == AITeamStatus.EXPLORING 
        }
    }

    /**
     * 召回洞府探索队伍
     */
    fun recallCaveExplorationTeam(teamId: String) {
        val data = _gameData.value
        val team = data.caveExplorationTeams.find { it.id == teamId } ?: return

        val updatedTeams = data.caveExplorationTeams.filter { it.id != teamId }

        team.memberIds.forEach { memberId ->
            val disciple = _disciples.value.find { it.id == memberId }
            if (disciple != null) {
                _disciples.value = _disciples.value.map { 
                    if (it.id == memberId) it.copy(status = DiscipleStatus.IDLE) else it 
                }
            }
        }

        val updatedCaves = data.cultivatorCaves.map { cave ->
            if (cave.id == team.caveId && cave.status == CaveStatus.EXPLORING) {
                cave.copy(status = CaveStatus.AVAILABLE)
            } else {
                cave
            }
        }

        _gameData.value = data.copy(
            caveExplorationTeams = updatedTeams,
            cultivatorCaves = updatedCaves
        )

        addEvent("${team.caveName}的探索队伍已召回", EventType.INFO)
    }

    /**
     * 击败AI队伍
     */
    fun defeatAITeam(aiTeamId: String) {
        val data = _gameData.value
        val updatedTeams = data.aiCaveTeams.map { team ->
            if (team.id == aiTeamId) {
                team.copy(status = AITeamStatus.DEFEATED)
            } else {
                team
            }
        }
        _gameData.value = data.copy(aiCaveTeams = updatedTeams)
    }

    /**
     * 检查两个弟子是否是兄弟姐妹关系
     * 只要他们共享至少一个父母，就算是兄弟姐妹
     */
    private fun areSiblings(d1: Disciple, d2: Disciple): Boolean {
        if (d1.parentId1 == null || d2.parentId1 == null) {
            return false
        }
        
        val d1Parents = listOfNotNull(d1.parentId1, d1.parentId2)
        val d2Parents = listOfNotNull(d2.parentId1, d2.parentId2)
        
        return d1Parents.any { p1 -> d2Parents.any { p2 -> p1 == p2 } }
    }

    private fun generateSectTradeItems(year: Int): List<MerchantItem> {
        val items = mutableListOf<MerchantItem>()
        val random = Random(System.currentTimeMillis() + year)
        
        val itemCount = 20
        
        val centuryCount = year / 100
        val adjustment = centuryCount * 3
        
        val baseProbabilities = listOf(75.0, 60.0, 22.6, 7.0, 2.8, 0.6)
        val adjustedProbabilities = baseProbabilities.mapIndexed { index, prob ->
            when {
                index < 3 -> maxOf(1.0, prob - adjustment)  // 至少保留 1% 概率
                else -> minOf(100.0, prob + adjustment)
            }
        }
        
        val generatedNames = mutableSetOf<String>()
        var attempts = 0
        val maxAttempts = itemCount * 3
        
        while (items.size < itemCount && attempts < maxAttempts) {
            attempts++
            val type = listOf("equipment", "manual", "pill", "material", "herb", "seed").random(random)
            val rarity = generateRarityByProbability(adjustedProbabilities, random)
            
            val item = when (type) {
                "equipment" -> {
                    val equipment = EquipmentDatabase.generateRandom(rarity, rarity)
                    val basePrice = GameConfig.Rarity.get(rarity).basePrice
                    val price = (basePrice.toDouble() * random.nextInt(700, 1301) / 1000.0).toInt()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = equipment.name,
                        type = "equipment",
                        itemId = equipment.id,
                        rarity = equipment.rarity,
                        price = price,
                        quantity = 1,
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "manual" -> {
                    val manual = ManualDatabase.generateRandom(rarity, rarity)
                    val basePrice = GameConfig.Rarity.get(rarity).basePrice
                    val price = (basePrice.toDouble() * random.nextInt(700, 1301) / 1000.0).toInt()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = manual.name,
                        type = "manual",
                        itemId = manual.id,
                        rarity = manual.rarity,
                        price = price,
                        quantity = 1,
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "pill" -> {
                    val pillTemplates = ItemDatabase.getPillsByRarity(rarity)
                    if (pillTemplates.isEmpty()) continue
                    val template = pillTemplates.random(random)
                    val pill = ItemDatabase.createPillFromTemplate(template)
                    val basePrice = (GameConfig.Rarity.get(rarity).basePrice * 0.8).toInt()
                    val price = (basePrice.toDouble() * random.nextInt(700, 1301) / 1000.0).toInt()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = pill.name,
                        type = "pill",
                        itemId = pill.id,
                        rarity = pill.rarity,
                        price = price,
                        quantity = random.nextInt(1, 4),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "material" -> {
                    val materials = BeastMaterialDatabase.getMaterialsByRarity(rarity)
                    if (materials.isEmpty()) continue
                    val material = materials.random(random)
                    val basePrice = (GameConfig.Rarity.get(rarity).basePrice * 0.05).toInt()
                    val price = (basePrice.toDouble() * random.nextInt(700, 1301) / 1000.0).toInt()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = material.name,
                        type = "material",
                        itemId = material.id,
                        rarity = material.rarity,
                        price = price,
                        quantity = random.nextInt(1, 16),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "herb" -> {
                    val herbs = HerbDatabase.getByRarity(rarity)
                    if (herbs.isEmpty()) continue
                    val herb = herbs.random(random)
                    val basePrice = (GameConfig.Rarity.get(rarity).basePrice * 0.05).toInt()
                    val price = (basePrice.toDouble() * random.nextInt(700, 1301) / 1000.0).toInt()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = herb.name,
                        type = "herb",
                        itemId = herb.id,
                        rarity = herb.rarity,
                        price = price,
                        quantity = random.nextInt(1, 16),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "seed" -> {
                    val seeds = HerbDatabase.getSeedsByRarity(rarity)
                    if (seeds.isEmpty()) continue
                    val seed = seeds.random(random)
                    val basePrice = (GameConfig.Rarity.get(rarity).basePrice * 0.05).toInt()
                    val price = (basePrice.toDouble() * random.nextInt(700, 1301) / 1000.0).toInt()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = seed.name,
                        type = "seed",
                        itemId = seed.id,
                        rarity = seed.rarity,
                        price = price,
                        quantity = random.nextInt(1, 16),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                else -> continue
            }
            
            if (!generatedNames.contains(item.name)) {
                generatedNames.add(item.name)
                items.add(item)
            }
        }
        
        items.sortByDescending { it.rarity }
        
        return items
    }

    fun getOrRefreshSectTradeItems(sectId: String): List<MerchantItem> {
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId } ?: return emptyList()
        
        val currentYear = data.gameYear
        val shouldRefresh = currentYear - sect.tradeLastRefreshYear >= 3 || sect.tradeItems.isEmpty()
        
        if (shouldRefresh) {
            val newItems = generateSectTradeItems(currentYear)
            val updatedSects = data.worldMapSects.map { 
                if (it.id == sectId) it.copy(
                    tradeItems = newItems,
                    tradeLastRefreshYear = currentYear
                ) else it 
            }
            _gameData.value = data.copy(worldMapSects = updatedSects)
            return newItems
        }
        
        return sect.tradeItems
    }

    fun buyFromSectTrade(sectId: String, itemId: String, quantity: Int = 1) {
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId } ?: return
        val item = sect.tradeItems.find { it.id == itemId } ?: return
        
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val relation = if (playerSect != null) {
            data.sectRelations.find { 
                (it.sectId1 == playerSect.id && it.sectId2 == sectId) ||
                (it.sectId1 == sectId && it.sectId2 == playerSect.id)
            }?.favor ?: 0
        } else 0
        
        if (relation < 40) {
            addEvent("好感度太低，无法与${sect.name}交易", EventType.WARNING)
            return
        }
        
        val maxAllowedRarity = when {
            relation >= 90 -> 6
            relation >= 80 -> 5
            relation >= 70 -> 4
            relation >= 60 -> 3
            relation >= 50 -> 2
            relation >= 40 -> 1
            else -> 0
        }
        
        if (item.rarity > maxAllowedRarity) {
            addEvent("好感度不足，无法购买${item.rarity}阶商品", EventType.WARNING)
            return
        }
        
        // 计算价格折扣：好感度折扣 + 结盟折扣
        val relationDiscount = when {
            relation >= 70 -> (relation - 70) * 0.01
            else -> 0.0
        }
        
        // 结盟额外10%折扣
        val allianceDiscount = if (isAlly(sectId)) 0.10 else 0.0
        
        // 限制最低价格为原价的30%，避免负数价格
        val priceMultiplier = (1.0 - relationDiscount - allianceDiscount).coerceAtLeast(0.3)
        
        val actualQuantity = minOf(quantity, item.quantity)
        val totalPrice = (item.price * priceMultiplier * actualQuantity).toInt()
        
        if (data.spiritStones < totalPrice) {
            addEvent("灵石不足，无法购买${item.name}", EventType.WARNING)
            return
        }
        
        val updatedTradeItems = if (item.quantity > actualQuantity) {
            sect.tradeItems.map { 
                if (it.id == itemId) it.copy(quantity = it.quantity - actualQuantity)
                else it
            }
        } else {
            sect.tradeItems.filter { it.id != itemId }
        }
        
        val updatedSects = data.worldMapSects.map { 
            if (it.id == sectId) it.copy(tradeItems = updatedTradeItems) else it 
        }
        
        _gameData.value = data.copy(
            spiritStones = data.spiritStones - totalPrice,
            worldMapSects = updatedSects
        )
        
        for (i in 0 until actualQuantity) {
            when (item.type) {
                "equipment" -> {
                    val equipment = createEquipmentFromMerchantItem(item)
                    // 检查是否已存在相同 id 的装备，避免重复
                    if (_equipment.value.none { it.id == equipment.id }) {
                        _equipment.value = _equipment.value + equipment
                    }
                }
                "manual" -> {
                    val manual = createManualFromMerchantItem(item)
                    // 检查是否已存在相同 id 的功法，避免重复
                    if (_manuals.value.none { it.id == manual.id }) {
                        _manuals.value = _manuals.value + manual
                    }
                }
                "pill" -> {
                    val pill = createPillFromMerchantItem(item)
                    addPillToWarehouse(pill)
                }
                "material" -> {
                    val material = createMaterialFromMerchantItem(item)
                    addMaterialToWarehouse(material)
                }
                "herb" -> {
                    val herb = createHerbFromMerchantItem(item)
                    addHerbToWarehouse(herb)
                }
                "seed" -> {
                    val seed = createSeedFromMerchantItem(item)
                    addSeedToWarehouse(seed)
                }
            }
        }
        
        addEvent("从${sect.name}购买了${item.name} x$actualQuantity", EventType.SUCCESS)
    }

    // ==================== 送礼系统 ====================

    /**
     * 送礼结果
     */
    data class GiftResult(
        val success: Boolean,
        val rejected: Boolean = false,
        val favorChange: Int = 0,
        val newFavor: Int = 0,
        val message: String = "",
        val responseType: String = "" // accept, reject, already_gifted, insufficient_resources, item_not_found
    )

    /**
     * 灵石送礼
     * @param sectId 宗门ID
     * @param tier 档位 (1=薄礼, 2=厚礼, 3=重礼, 4=大礼)
     * @return 送礼结果
     */
    fun giftSpiritStones(sectId: String, tier: Int): GiftResult {
        val data = _gameData.value
        val currentYear = data.gameYear

        // 查找目标宗门
        val sect = data.worldMapSects.find { it.id == sectId }
        if (sect == null) {
            return GiftResult(
                success = false,
                responseType = "sect_not_found",
                message = "未找到目标宗门"
            )
        }

        // 检查是否为玩家宗门
        if (sect.isPlayerSect) {
            return GiftResult(
                success = false,
                responseType = "invalid_target",
                message = "不能向自己的宗门送礼"
            )
        }

        // 检查每年一次限制
        if (sect.lastGiftYear == currentYear) {
            return GiftResult(
                success = false,
                rejected = false,
                responseType = "already_gifted",
                message = "今年已经向${sect.name}送过礼了，请明年再来"
            )
        }

        // 获取档位配置
        val tierConfig = GiftConfig.SpiritStoneGiftConfig.getTier(tier)
        if (tierConfig == null) {
            return GiftResult(
                success = false,
                responseType = "invalid_tier",
                message = "无效的送礼档位"
            )
        }

        // 检查灵石是否足够
        if (data.spiritStones < tierConfig.spiritStones) {
            return GiftResult(
                success = false,
                responseType = "insufficient_resources",
                message = "灵石不足，需要${tierConfig.spiritStones}灵石"
            )
        }

        // 计算拒绝概率（灵石送礼使用档位对应的虚拟稀有度）
        // 薄礼=灵品(2), 厚礼=宝品(3), 重礼=玄品(4), 大礼=地品(5)
        val virtualRarity = (tier + 1).coerceIn(2, 5)
        val rejectProbability = getRejectProbability(sect.level, virtualRarity)

        // 判断是否被拒绝
        val isRejected = Random.nextInt(100) < rejectProbability

        if (isRejected) {
            // 被拒绝：不扣除灵石，不标记已送礼（拒绝算没有送礼）
            val responseText = SectResponseTexts.getRejectResponse(sect.level, "spirit_stones", tierConfig.name)
            addEvent("向${sect.name}送礼${tierConfig.name}被拒绝：$responseText", EventType.WARNING)

            return GiftResult(
                success = false,
                rejected = true,
                responseType = "rejected",
                message = responseText
            )
        }

        // 送礼成功：扣除灵石、标记已送礼、增加好感度（使用百分比计算）
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val currentFavor = if (playerSect != null) {
            data.sectRelations.find { 
                (it.sectId1 == playerSect.id && it.sectId2 == sectId) ||
                (it.sectId1 == sectId && it.sectId2 == playerSect.id)
            }?.favor ?: 0
        } else 0
        
        val percentage = GiftConfig.FavorPercentageConfig.getFavorPercentage(sect.level, tier)
        val favorIncrease = if (percentage != null) {
            val baseIncrease = currentFavor * percentage / 100
            if (baseIncrease == 0) 1 else baseIncrease
        } else {
            1
        }
        val newFavor = (currentFavor + favorIncrease).coerceAtMost(100)

        val updatedSects = data.worldMapSects.map { currentSect ->
            if (currentSect.id == sectId) {
                currentSect.copy(lastGiftYear = currentYear)
            } else {
                currentSect
            }
        }

        val updatedRelations = if (playerSect != null) {
            updateSectRelationFavor(data.sectRelations, playerSect.id, sectId, newFavor, currentYear)
        } else {
            data.sectRelations
        }

        _gameData.value = data.copy(
            spiritStones = data.spiritStones - tierConfig.spiritStones,
            worldMapSects = updatedSects,
            sectRelations = updatedRelations
        )

        val responseText = SectResponseTexts.getAcceptResponse(sect.level, "spirit_stones", tierConfig.name, favorIncrease)
        addEvent("向${sect.name}送礼${tierConfig.name}成功，好感度+${favorIncrease}", EventType.SUCCESS)

        return GiftResult(
            success = true,
            rejected = false,
            favorChange = favorIncrease,
            newFavor = newFavor,
            responseType = "accept",
            message = responseText
        )
    }

    /**
     * 物品送礼
     * @param sectId 宗门ID
     * @param itemId 物品ID
     * @param itemType 物品类型 (manual, equipment, pill)
     * @param quantity 数量
     * @return 送礼结果
     */
    fun giftItem(sectId: String, itemId: String, itemType: String, quantity: Int): GiftResult {
        val data = _gameData.value
        val currentYear = data.gameYear

        // 查找目标宗门
        val sect = data.worldMapSects.find { it.id == sectId }
        if (sect == null) {
            return GiftResult(
                success = false,
                responseType = "sect_not_found",
                message = "未找到目标宗门"
            )
        }

        // 检查是否为玩家宗门
        if (sect.isPlayerSect) {
            return GiftResult(
                success = false,
                responseType = "invalid_target",
                message = "不能向自己的宗门送礼"
            )
        }

        // 检查每年一次限制
        if (sect.lastGiftYear == currentYear) {
            return GiftResult(
                success = false,
                rejected = false,
                responseType = "already_gifted",
                message = "今年已经向${sect.name}送过礼了，请明年再来"
            )
        }

        // 查找物品并获取稀有度
        val (itemRarity, itemName, actualQuantity) = when (itemType) {
            GiftConfig.ItemType.MANUAL -> {
                val manual = _manuals.value.find { it.id == itemId }
                if (manual == null) {
                    return GiftResult(
                        success = false,
                        responseType = "item_not_found",
                        message = "未找到该功法"
                    )
                }
                Triple(manual.rarity, manual.name, 1)
            }
            GiftConfig.ItemType.EQUIPMENT -> {
                val equipment = _equipment.value.find { it.id == itemId }
                if (equipment == null) {
                    return GiftResult(
                        success = false,
                        responseType = "item_not_found",
                        message = "未找到该装备"
                    )
                }
                // 已装备的装备不能送礼
                if (equipment.isEquipped) {
                    return GiftResult(
                        success = false,
                        responseType = "item_equipped",
                        message = "该装备已被装备，无法送礼"
                    )
                }
                Triple(equipment.rarity, equipment.name, 1)
            }
            GiftConfig.ItemType.PILL -> {
                val pill = _pills.value.find { it.id == itemId }
                if (pill == null) {
                    return GiftResult(
                        success = false,
                        responseType = "item_not_found",
                        message = "未找到该丹药"
                    )
                }
                if (pill.quantity < quantity) {
                    return GiftResult(
                        success = false,
                        responseType = "insufficient_quantity",
                        message = "丹药数量不足，当前有${pill.quantity}个"
                    )
                }
                Triple(pill.rarity, pill.name, quantity.coerceAtLeast(1))
            }
            else -> {
                return GiftResult(
                    success = false,
                    responseType = "invalid_item_type",
                    message = "不支持的物品类型"
                )
            }
        }

        // 计算好感度（使用百分比增长）
        val favorPercentage = GiftConfig.ItemFavorPercentageConfig.getFavorPercentage(sect.level, itemRarity)
        
        val playerSectForFavor = _gameData.value.worldMapSects.find { it.isPlayerSect }
        val currentFavorValue = if (playerSectForFavor != null) {
            _gameData.value.sectRelations.find { 
                (it.sectId1 == playerSectForFavor.id && it.sectId2 == sectId) ||
                (it.sectId1 == sectId && it.sectId2 == playerSectForFavor.id)
            }?.favor ?: 0
        } else 0
        
        val baseFavor = if (favorPercentage == null) {
            1
        } else {
            if (currentFavorValue == 0) {
                1
            } else {
                (currentFavorValue * favorPercentage / 100).coerceAtLeast(1)
            }
        }
        val totalFavor = baseFavor * actualQuantity

        // 计算拒绝概率
        val rejectProbability = getRejectProbability(sect.level, itemRarity)

        // 判断是否被拒绝
        val isRejected = Random.nextInt(100) < rejectProbability

        if (isRejected) {
            // 被拒绝：不移除物品，不标记已送礼（拒绝算没有送礼）
            val responseText = SectResponseTexts.getRejectResponse(sect.level, itemType, itemName)
            addEvent("向${sect.name}送礼${itemName}被拒绝：$responseText", EventType.WARNING)

            return GiftResult(
                success = false,
                rejected = true,
                responseType = "rejected",
                message = responseText
            )
        }

        // 送礼成功：移除物品、标记已送礼、增加好感度
        removeGiftItem(itemType, itemId, actualQuantity)

        val newFavor = (currentFavorValue + totalFavor).coerceAtMost(100)

        val finalSects = _gameData.value.worldMapSects.map {
            if (it.id == sectId) {
                it.copy(lastGiftYear = currentYear)
            } else it
        }

        val updatedRelations = if (playerSectForFavor != null) {
            updateSectRelationFavor(_gameData.value.sectRelations, playerSectForFavor.id, sectId, newFavor, currentYear)
        } else {
            _gameData.value.sectRelations
        }

        _gameData.value = _gameData.value.copy(worldMapSects = finalSects, sectRelations = updatedRelations)

        val responseText = SectResponseTexts.getAcceptResponse(sect.level, itemType, itemName, totalFavor)
        val quantityText = if (actualQuantity > 1) "x$actualQuantity " else ""
        addEvent("向${sect.name}送礼${quantityText}${itemName}成功，好感度+$totalFavor", EventType.SUCCESS)

        return GiftResult(
            success = true,
            rejected = false,
            favorChange = totalFavor,
            newFavor = newFavor,
            responseType = "accept",
            message = responseText
        )
    }

    /**
     * 移除送礼物品
     */
    private fun removeGiftItem(itemType: String, itemId: String, quantity: Int) {
        when (itemType) {
            GiftConfig.ItemType.MANUAL -> {
                _manuals.value = _manuals.value.filter { it.id != itemId }
            }
            GiftConfig.ItemType.EQUIPMENT -> {
                _equipment.value = _equipment.value.filter { it.id != itemId }
            }
            GiftConfig.ItemType.PILL -> {
                _pills.value = _pills.value.map { pill ->
                    if (pill.id == itemId) {
                        pill.copy(quantity = (pill.quantity - quantity).coerceAtLeast(0))
                    } else pill
                }.filter { it.quantity > 0 }
            }
        }
    }

    /**
     * 获取拒绝概率
     * @param sectLevel 宗门等级 (0-3)
     * @param rarity 物品稀有度 (1-6)
     * @return 拒绝概率 (0-100)
     */
    fun getRejectProbability(sectLevel: Int, rarity: Int): Int {
        return GiftConfig.SectRejectConfig.getRejectProbability(sectLevel, rarity)
    }

    /**
     * 获取稀有度对应的好感度
     * @param rarity 稀有度 (1-6)
     * @return 好感度
     * @deprecated 此方法已废弃，物品送礼好感度计算已改为百分比增长方式。
     *             请使用 GiftConfig.ItemFavorPercentageConfig.getFavorPercentage() 获取百分比配置。
     *             此方法仅保留用于兼容性，后续可能会被移除。
     */
    @Deprecated(
        message = "物品送礼好感度已改为百分比计算，请使用 ItemFavorPercentageConfig",
        replaceWith = ReplaceWith("GiftConfig.ItemFavorPercentageConfig.getFavorPercentage(sectLevel, rarity)")
    )
    fun getRarityFavor(rarity: Int): Int {
        return GiftConfig.RarityFavorConfig.getFavor(rarity)
    }

    /**
     * 获取灵石送礼档位好感度增长百分比
     * @param sectLevel 宗门等级 (0-3)
     * @param tier 档位 (1-4)
     * @return 好感度增长百分比，如果档位不可用则返回null
     */
    fun getSpiritStoneFavorPercentage(sectLevel: Int, tier: Int): Int? {
        return GiftConfig.FavorPercentageConfig.getFavorPercentage(sectLevel, tier)
    }

    /**
     * 计算灵石送礼实际好感度增长
     * @param currentFavor 当前好感度
     * @param percentage 增长百分比
     * @return 实际好感度增长值
     */
    fun calculateFavorIncrease(currentFavor: Int, percentage: Int): Int {
        val baseIncrease = currentFavor * percentage / 100
        return if (baseIncrease == 0) 1 else baseIncrease
    }

    /**
     * 检查是否可以向指定宗门送礼
     */
    fun canGiftToSect(sectId: String): Pair<Boolean, String> {
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId }

        if (sect == null) {
            return Pair(false, "未找到目标宗门")
        }

        if (sect.isPlayerSect) {
            return Pair(false, "不能向自己的宗门送礼")
        }

        if (sect.lastGiftYear == data.gameYear) {
            return Pair(false, "今年已经送过礼了")
        }

        return Pair(true, "可以送礼")
    }

    // ==================== 结盟系统 ====================

    /**
     * 计算游说成功率
     * 成功率 = 好感度加成 + 智力加成 + 魅力加成
     * - 好感度加成：(好感度 - 90) × 5%，好感度<90时为0%
     * - 智力加成：(智力 - 50) / 5 × 1%
     * - 魅力加成：(魅力 - 50) / 5 × 1%
     */
    fun calculatePersuasionSuccessRate(favorability: Int, intelligence: Int, charm: Int): Double {
        val favorBonus = if (favorability >= 90) (favorability - 90) * 0.05 else 0.0
        val intBonus = (intelligence - 50) / 5.0 * 0.01
        val charmBonus = (charm - 50) / 5.0 * 0.01
        return (favorBonus + intBonus + charmBonus).coerceIn(0.0, 1.0)
    }

    /**
     * 获取游说弟子境界要求
     * 小型宗门(0): 金丹(7)
     * 中型宗门(1): 化神(5)
     * 大型宗门(2): 炼虚(4)
     * 顶级宗门(3): 合体(3)
     */
    fun getEnvoyRealmRequirement(sectLevel: Int): Int {
        return when (sectLevel) {
            0 -> 7  // 金丹
            1 -> 5  // 化神
            2 -> 4  // 炼虚
            3 -> 3  // 合体
            else -> 7
        }
    }

    /**
     * 获取结盟费用
     */
    fun getAllianceCost(sectLevel: Int): Long {
        return when (sectLevel) {
            0 -> 50_000L
            1 -> 200_000L
            2 -> 800_000L
            3 -> 2_000_000L
            else -> 50_000L
        }
    }

    /**
     * 检查结盟条件
     */
    fun checkAllianceConditions(sectId: String, envoyDiscipleId: String): Triple<Boolean, String, Int> {
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId }
        val envoy = _disciples.value.find { it.id == envoyDiscipleId }

        if (sect == null) {
            return Triple(false, "未找到目标宗门", 0)
        }

        if (sect.isPlayerSect) {
            return Triple(false, "不能与自己的宗门结盟", 0)
        }

        if (sect.allianceId != null) {
            return Triple(false, "该宗门已有结盟", 0)
        }

        if (envoy == null) {
            return Triple(false, "未找到游说弟子", 0)
        }

        if (!envoy.isAlive || envoy.status != DiscipleStatus.IDLE) {
            return Triple(false, "游说弟子必须处于空闲状态", 0)
        }

        val requiredRealm = getEnvoyRealmRequirement(sect.level)
        if (envoy.realm > requiredRealm) {
            val realmName = GameConfig.Realm.get(requiredRealm)?.name ?: "未知"
            return Triple(false, "游说弟子境界需要达到${realmName}及以上", 0)
        }

        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val favor = if (playerSect != null) {
            data.sectRelations.find { 
                (it.sectId1 == playerSect.id && it.sectId2 == sectId) ||
                (it.sectId1 == sectId && it.sectId2 == playerSect.id)
            }?.favor ?: 0
        } else 0

        if (favor < 90) {
            return Triple(false, "好感度需要达到90以上", 0)
        }

        val cost = getAllianceCost(sect.level)
        if (data.spiritStones < cost) {
            return Triple(false, "灵石不足，需要${cost}灵石", 0)
        }

        val playerAllianceCount = data.alliances.count { it.sectIds.contains("player") }
        if (playerAllianceCount >= data.playerAllianceSlots) {
            return Triple(false, "结盟数量已达上限", 0)
        }

        return Triple(true, "可以结盟", cost.toInt())
    }

    /**
     * 请求结盟
     */
    fun requestAlliance(sectId: String, envoyDiscipleId: String): Pair<Boolean, String> {
        val (canAlliance, message, cost) = checkAllianceConditions(sectId, envoyDiscipleId)
        
        if (!canAlliance) {
            return Pair(false, message)
        }

        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId }!!
        val envoy = _disciples.value.find { it.id == envoyDiscipleId }!!

        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val favor = if (playerSect != null) {
            data.sectRelations.find { 
                (it.sectId1 == playerSect.id && it.sectId2 == sectId) ||
                (it.sectId1 == sectId && it.sectId2 == playerSect.id)
            }?.favor ?: 0
        } else 0

        val successRate = calculatePersuasionSuccessRate(favor, envoy.intelligence, envoy.charm)
        val roll = Random.nextDouble()

        if (roll < successRate) {
            val alliance = Alliance(
                sectIds = listOf("player", sectId),
                startYear = data.gameYear,
                initiatorId = "player",
                envoyDiscipleId = envoyDiscipleId
            )

            val updatedSects = data.worldMapSects.map { s ->
                if (s.id == sectId) s.copy(allianceId = alliance.id, allianceStartYear = data.gameYear)
                else s
            }

            _gameData.value = data.copy(
                spiritStones = data.spiritStones - cost,
                alliances = data.alliances + alliance,
                worldMapSects = updatedSects
            )

            addEvent("与${sect.name}成功结盟！", EventType.SUCCESS)
            return Pair(true, "结盟成功！")
        } else {
            _gameData.value = data.copy(spiritStones = data.spiritStones - cost / 2)
            addEvent("游说${sect.name}失败，结盟未成", EventType.WARNING)
            return Pair(false, "游说失败，好感度不足以达成结盟")
        }
    }

    /**
     * 解除结盟
     */
    fun dissolveAlliance(sectId: String): Pair<Boolean, String> {
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId }

        if (sect == null) {
            return Pair(false, "未找到目标宗门")
        }

        if (sect.allianceId == null) {
            return Pair(false, "该宗门未与您结盟")
        }

        val alliance = data.alliances.find { it.id == sect.allianceId }
        if (alliance == null) {
            return Pair(false, "未找到结盟记录")
        }

        val updatedSects = data.worldMapSects.map { s ->
            if (s.id == sectId) s.copy(allianceId = null, allianceStartYear = 0)
            else s
        }

        val updatedAlliances = data.alliances.filter { it.id != alliance.id }

        _gameData.value = data.copy(
            worldMapSects = updatedSects,
            alliances = updatedAlliances
        )

        addEvent("与${sect.name}解除结盟", EventType.WARNING)
        return Pair(true, "已解除结盟")
    }

    /**
     * 检查盟约到期
     */
    private fun checkAllianceExpiry(year: Int) {
        val data = _gameData.value
        val expiredAlliances = data.alliances.filter { year - it.startYear >= 5 }

        if (expiredAlliances.isEmpty()) return

        val updatedAlliances = data.alliances.filter { year - it.startYear < 5 }
        val updatedSects = data.worldMapSects.map { sect ->
            if (expiredAlliances.any { it.sectIds.contains(sect.id) }) {
                sect.copy(allianceId = null, allianceStartYear = 0)
            } else sect
        }

        _gameData.value = data.copy(
            alliances = updatedAlliances,
            worldMapSects = updatedSects
        )

        expiredAlliances.forEach { alliance ->
            val sect = data.worldMapSects.find { it.id != "player" && alliance.sectIds.contains(it.id) }
            if (sect != null) {
                addEvent("与${sect.name}的盟约已到期自动解散", EventType.INFO)
            }
        }
    }

    /**
     * 检查好感度低于90自动解除
     */
    private fun checkAllianceFavorDrop() {
        val data = _gameData.value
        val dissolvedAlliances = mutableListOf<Alliance>()
        val playerSect = data.worldMapSects.find { it.isPlayerSect }

        data.alliances.forEach { alliance ->
            if (!alliance.sectIds.contains("player")) return@forEach
            
            val sectId = alliance.sectIds.find { it != "player" }
            if (sectId != null && playerSect != null) {
                val sect = data.worldMapSects.find { it.id == sectId }
                val relation = data.sectRelations.find { 
                    (it.sectId1 == playerSect.id && it.sectId2 == sectId) ||
                    (it.sectId1 == sectId && it.sectId2 == playerSect.id)
                }
                val favor = relation?.favor ?: 0
                if (favor < 90) {
                    dissolvedAlliances.add(alliance)
                    addEvent("与${sect?.name ?: "宗门"}的好感度过低，盟约自动解除", EventType.WARNING)
                }
            }
        }

        if (dissolvedAlliances.isNotEmpty()) {
            val updatedAlliances = data.alliances.filter { it !in dissolvedAlliances }
            val updatedSects = data.worldMapSects.map { sect ->
                if (dissolvedAlliances.any { it.sectIds.contains(sect.id) }) {
                    sect.copy(allianceId = null, allianceStartYear = 0)
                } else sect
            }
            _gameData.value = data.copy(
                alliances = updatedAlliances,
                worldMapSects = updatedSects
            )
        }
    }

    /**
     * AI宗门结盟检查
     */
    private fun processAIAlliances(year: Int) {
        val data = _gameData.value
        val newAlliances = mutableListOf<Alliance>()
        val updatedSects = data.worldMapSects.toMutableList()
        val updatedRelations = data.sectRelations.toMutableList()
        val processedPairs = mutableSetOf<Pair<String, String>>()

        data.worldMapSects.filter { !it.isPlayerSect }.forEach { sect1 ->
            val allianceCount1 = data.alliances.count { it.sectIds.contains(sect1.id) } +
                                 newAlliances.count { it.sectIds.contains(sect1.id) }
            if (allianceCount1 >= 2) return@forEach
            
            data.worldMapSects.filter { 
                !it.isPlayerSect && 
                it.id != sect1.id
            }.forEach { sect2 ->
                val pairKey = if (sect1.id < sect2.id) sect1.id to sect2.id else sect2.id to sect1.id
                if (processedPairs.contains(pairKey)) return@forEach
                
                val allianceCount2 = data.alliances.count { it.sectIds.contains(sect2.id) } +
                                     newAlliances.count { it.sectIds.contains(sect2.id) }
                if (allianceCount2 >= 2) return@forEach
                
                if (sect1.allianceId == sect2.allianceId && sect1.allianceId != null) return@forEach

                val existingAllies1 = data.alliances.flatMap { 
                    if (it.sectIds.contains(sect1.id)) it.sectIds.filter { id -> id != sect1.id } 
                    else emptyList() 
                }.toSet()
                
                if (existingAllies1.contains(sect2.id)) return@forEach
                
                val existingAllies2 = data.alliances.flatMap { 
                    if (it.sectIds.contains(sect2.id)) it.sectIds.filter { id -> id != sect2.id } 
                    else emptyList() 
                }.toSet()
                
                if (existingAllies2.contains(sect1.id)) return@forEach

                val relation = getSectRelation(sect1.id, sect2.id, data.sectRelations)
                val favor = relation?.favor ?: 30
                
                if (favor < 90) return@forEach

                val baseScore = calculateAllianceScore(sect1, sect2, data.sectRelations)
                
                // 添加随机波动 (-2 到 +3)
                val randomBonus = Random.nextInt(-2, 4)
                val score = (baseScore + randomBonus).coerceIn(0, 100)
                
                // 结盟阈值：评分 > 80
                if (score > 80) {
                    // 基础概率 = 评分 / 200，最高 50%
                    val baseProbability = score / 200.0
                    
                    if (Random.nextDouble() < baseProbability) {
                        val alliance = Alliance(
                            sectIds = listOf(sect1.id, sect2.id),
                            startYear = year,
                            initiatorId = sect1.id
                        )
                        newAlliances.add(alliance)

                        val index1 = updatedSects.indexOfFirst { it.id == sect1.id }
                        val index2 = updatedSects.indexOfFirst { it.id == sect2.id }
                        if (index1 >= 0) updatedSects[index1] = updatedSects[index1].copy(allianceId = alliance.id, allianceStartYear = year)
                        if (index2 >= 0) updatedSects[index2] = updatedSects[index2].copy(allianceId = alliance.id, allianceStartYear = year)

                        addEvent("${sect1.name}与${sect2.name}基于共同利益结为盟友", EventType.INFO)
                        processedPairs.add(pairKey)
                    }
                }
            }
        }

        if (newAlliances.isNotEmpty()) {
            _gameData.value = data.copy(
                alliances = data.alliances + newAlliances,
                worldMapSects = updatedSects,
                sectRelations = updatedRelations
            )
        }
    }
    
    /**
     * 计算结盟评分
     * 综合考虑多个因素
     */
    private fun calculateAllianceScore(
        sect1: WorldSect, 
        sect2: WorldSect, 
        relations: List<SectRelation>
    ): Int {
        var score = 0
        
        // 1. 宗门等级相似度 (10%)
        val levelDiff = kotlin.math.abs(sect1.level - sect2.level)
        val levelScore = when {
            levelDiff == 0 -> 100
            levelDiff == 1 -> 80
            levelDiff == 2 -> 60
            else -> 40
        }
        score += levelScore * 10 / 100
        
        // 2. 正道/魔道匹配 (25%)
        val alignmentScore = when {
            sect1.isRighteous && sect2.isRighteous -> 90   // 同为正道
            !sect1.isRighteous && !sect2.isRighteous -> 80  // 同为魔道
            else -> 30  // 正邪对立
        }
        score += alignmentScore * 25 / 100
        
        // 3. 地理位置关系 (20%)
        val isAdjacent = sect1.connectedSectIds.contains(sect2.id)
        val geoScore = if (isAdjacent) 70 else 50
        score += geoScore * 20 / 100
        
        // 4. 实力对比 (15%)
        val power1 = sect1.disciples.values.sum() * (10 - sect1.maxRealm)
        val power2 = sect2.disciples.values.sum() * (10 - sect2.maxRealm)
        val powerDiff = kotlin.math.abs(power1 - power2)
        val powerScore = when {
            powerDiff < 100 -> 80
            powerDiff < 500 -> 60
            else -> 40
        }
        score += powerScore * 15 / 100
        
        // 5. 好感度 (30%)
        val relation = getSectRelation(sect1.id, sect2.id, relations)
        val favorScore = relation?.favor ?: 30
        score += favorScore * 30 / 100
        
        return score.coerceIn(0, 100)
    }
    
    /**
     * 获取宗门关系
     */
    private fun getSectRelation(
        sectId1: String, 
        sectId2: String, 
        relations: List<SectRelation>
    ): SectRelation? {
        val id1 = minOf(sectId1, sectId2)
        val id2 = maxOf(sectId1, sectId2)
        return relations.find { it.sectId1 == id1 && it.sectId2 == id2 }
    }
    
    /**
     * 获取玩家宗门与指定宗门的好感度
     * @param targetSectId 目标宗门ID
     * @return 好感度值，如果不存在关系则返回0
     */
    fun getPlayerSectFavor(targetSectId: String): Int {
        val data = _gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return 0
        val relation = getSectRelation(playerSect.id, targetSectId, data.sectRelations)
        return relation?.favor ?: 0
    }
    
    /**
     * 批量获取玩家宗门与多个宗门的好感度
     * @param targetSectIds 目标宗门ID列表
     * @return Map<宗门ID, 好感度值>
     */
    fun getPlayerSectFavors(targetSectIds: List<String>): Map<String, Int> {
        val data = _gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return emptyMap()
        return targetSectIds.associateWith { targetId ->
            val relation = getSectRelation(playerSect.id, targetId, data.sectRelations)
            relation?.favor ?: 0
        }
    }
    
    /**
     * 更新宗门关系
     */
    private fun updateSectRelation(
        sectId1: String, 
        sectId2: String, 
        favorDelta: Int = 0,
        relations: MutableList<SectRelation>,
        year: Int = 0
    ) {
        val id1 = minOf(sectId1, sectId2)
        val id2 = maxOf(sectId1, sectId2)
        
        val index = relations.indexOfFirst { it.sectId1 == id1 && it.sectId2 == id2 }
        
        if (index >= 0) {
            val relation = relations[index]
            relations[index] = relation.copy(
                favor = (relation.favor + favorDelta).coerceIn(0, 100),
                lastInteractionYear = if (year > 0) year else relation.lastInteractionYear,
                noGiftYears = if (favorDelta > 0) 0 else relation.noGiftYears
            )
        } else {
            relations.add(SectRelation(
                sectId1 = id1,
                sectId2 = id2,
                favor = (50 + favorDelta).coerceIn(0, 100),
                lastInteractionYear = year
            ))
        }
    }


    /**
     * 直接设置宗门关系的好感度值
     */
    private fun updateSectRelationFavor(
        relations: List<SectRelation>,
        sectId1: String,
        sectId2: String,
        newFavor: Int,
        year: Int = 0
    ): List<SectRelation> {
        val id1 = minOf(sectId1, sectId2)
        val id2 = maxOf(sectId1, sectId2)
        
        val index = relations.indexOfFirst { it.sectId1 == id1 && it.sectId2 == id2 }
        
        return if (index >= 0) {
            relations.mapIndexed { i, relation ->
                if (i == index) {
                    relation.copy(favor = newFavor.coerceIn(0, 100), lastInteractionYear = year, noGiftYears = 0)
                } else {
                    relation
                }
            }
        } else {
            relations + SectRelation(
                sectId1 = id1,
                sectId2 = id2,
                favor = newFavor.coerceIn(0, 100),
                lastInteractionYear = year,
                noGiftYears = 0
            )
        }
    }
    /**
     * 计算支援队伍规模
     * 支援弟子数量 = 好感度 - 90（以90为基准，每提升1点好感度增加1人，保底1人）
     */
    fun calculateSupportTeamSize(favorability: Int): Int {
        return (favorability - 90).coerceAtLeast(1)
    }

    /**
     * 检查求援条件
     */
    fun checkRequestSupportConditions(allySectId: String, envoyDiscipleId: String): Pair<Boolean, String> {
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == allySectId }
        val envoy = _disciples.value.find { it.id == envoyDiscipleId }

        if (sect == null) {
            return Pair(false, "未找到目标宗门")
        }

        if (sect.allianceId == null) {
            return Pair(false, "该宗门不是您的盟友")
        }

        if (envoy == null) {
            return Pair(false, "未找到求援弟子")
        }

        if (!envoy.isAlive || envoy.status != DiscipleStatus.IDLE) {
            return Pair(false, "求援弟子必须处于空闲状态")
        }

        if (envoy.realm > 7) {
            return Pair(false, "求援弟子境界需要达到金丹及以上")
        }

        val hasExistingSupportTeam = data.supportTeams.any { it.sourceSectId == allySectId && (it.isMoving || it.isStationed) }
        if (hasExistingSupportTeam) {
            return Pair(false, "该宗门已有支援队伍，无法再次派遣")
        }

        return Pair(true, "可以求援")
    }

    /**
     * 请求支援
     */
    /**
     * 计算两点之间的距离
     */
    private fun calculateDistance(x1: Float, y1: Float, x2: Float, y2: Float): Int {
        return kotlin.math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)).toInt()
    }

    /**
     * 使用BFS查找从起点到终点的最短路径
     * @return 路径上的宗门ID列表（包含起点和终点），如果找不到返回null
     */
    private fun findShortestPath(
        startSectId: String,
        targetSectId: String,
        sects: List<WorldSect>
    ): List<String>? {
        if (startSectId == targetSectId) return listOf(startSectId)
        
        val sectMap = sects.associateBy { it.id }
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        val parent = mutableMapOf<String, String>()
        
        queue.add(startSectId)
        visited.add(startSectId)
        
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val currentSect = sectMap[current] ?: continue
            
            for (neighborId in currentSect.connectedSectIds) {
                if (neighborId !in visited) {
                    visited.add(neighborId)
                    parent[neighborId] = current
                    
                    if (neighborId == targetSectId) {
                        val path = mutableListOf<String>()
                        var node = targetSectId
                        while (node != startSectId) {
                            path.add(node)
                            node = parent[node] ?: break
                        }
                        path.add(startSectId)
                        return path.reversed()
                    }
                    
                    queue.add(neighborId)
                }
            }
        }
        
        return null
    }

    fun requestSupport(allySectId: String, envoyDiscipleId: String): Pair<Boolean, String> {
        val (canRequest, message) = checkRequestSupportConditions(allySectId, envoyDiscipleId)
        
        if (!canRequest) {
            return Pair(false, message)
        }

        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == allySectId }!!
        val envoy = _disciples.value.find { it.id == envoyDiscipleId }!!

        val playerSectForFavor = data.worldMapSects.find { it.isPlayerSect }
        val favor = if (playerSectForFavor != null) {
            data.sectRelations.find { 
                (it.sectId1 == playerSectForFavor.id && it.sectId2 == allySectId) ||
                (it.sectId1 == allySectId && it.sectId2 == playerSectForFavor.id)
            }?.favor ?: 0
        } else 0

        val successRate = calculatePersuasionSuccessRate(favor, envoy.intelligence, envoy.charm)
        val roll = Random.nextDouble()

        if (roll < successRate) {
            val supportSize = calculateSupportTeamSize(favor)
            val supportRealm = getSupportTeamRealm(sect.maxRealm)
            val realmName = GameConfig.Realm.getName(supportRealm)
            
            val (selectedAiDisciples, updatedSect) = selectRealSupportDisciples(sect, supportSize, supportRealm)
            
            if (selectedAiDisciples.isEmpty()) {
                addEvent("${sect.name}没有符合条件的弟子可派遣", EventType.WARNING)
                return Pair(false, "求援失败，对方没有符合条件的弟子")
            }
            
            val playerSect = data.worldMapSects.find { it.isPlayerSect }
            if (playerSect == null) {
                return Pair(false, "找不到玩家宗门")
            }
            
            val route = findShortestPath(sect.id, playerSect.id, data.worldMapSects)
            if (route == null || route.size < 2) {
                addEvent("无法找到从${sect.name}到玩家宗门的路线", EventType.WARNING)
                return Pair(false, "求援失败，无法到达玩家宗门")
            }
            
            val updatedSects = data.worldMapSects.map { s ->
                if (s.id == sect.id) updatedSect else s
            }
            
            val totalDistance = route.windowed(2).sumOf { (from, to) ->
                val fromSect = data.worldMapSects.find { it.id == from }
                val toSect = data.worldMapSects.find { it.id == to }
                if (fromSect != null && toSect != null) {
                    calculateDistance(fromSect.x, fromSect.y, toSect.x, toSect.y)
                } else 0
            }
            
            val travelDays = (totalDistance / 200 * 30 * 2 / 3).toInt().coerceIn(1, 360)
            
            var arrivalDay = data.gameDay + travelDays
            var arrivalMonth = data.gameMonth
            var arrivalYear = data.gameYear
            
            while (arrivalDay > 30) {
                arrivalDay -= 30
                arrivalMonth++
                if (arrivalMonth > 12) {
                    arrivalMonth = 1
                    arrivalYear++
                }
            }
            
            val supportTeam = SupportTeam(
                id = java.util.UUID.randomUUID().toString(),
                name = "${sect.name}支援队",
                sourceSectId = sect.id,
                sourceSectName = sect.name,
                targetSectId = "player",
                disciples = selectedAiDisciples.map { it.id },
                aiDisciples = selectedAiDisciples,
                size = selectedAiDisciples.size,
                currentX = sect.x,
                currentY = sect.y,
                targetX = playerSect.x,
                targetY = playerSect.y,
                moveProgress = 0f,
                status = "moving",
                startYear = data.gameYear,
                startMonth = data.gameMonth,
                startDay = data.gameDay,
                duration = travelDays,
                arrivalYear = arrivalYear,
                arrivalMonth = arrivalMonth,
                arrivalDay = arrivalDay,
                route = route,
                currentRouteIndex = 0,
                currentSegmentProgress = 0f
            )
            
            _gameData.value = data.copy(
                supportTeams = data.supportTeams + supportTeam,
                worldMapSects = updatedSects
            )
            
            addEvent("${sect.name}派遣${selectedAiDisciples.size}名${realmName}及以下境界弟子前来支援", EventType.SUCCESS)
            return Pair(true, "求援成功，${sect.name}将派遣${selectedAiDisciples.size}名${realmName}及以下境界弟子支援")
        } else {
            addEvent("${sect.name}拒绝了支援请求", EventType.WARNING)
            return Pair(false, "求援失败")
        }
    }

    /**
     * 从AI宗门的真实弟子中选择支援弟子
     * @return Pair<选中的Disciple列表, 更新后的WorldSect>
     */
    private fun selectRealSupportDisciples(
        sect: WorldSect,
        supportSize: Int,
        maxRealm: Int
    ): Pair<List<Disciple>, WorldSect> {
        val eligibleDisciples = sect.aiDisciples
            .filter { it.isAlive && it.realm <= maxRealm }
            .sortedByDescending { it.realm }
        
        if (eligibleDisciples.isEmpty()) {
            return Pair(emptyList(), sect)
        }
        
        val selectedAiDisciples = eligibleDisciples.take(supportSize)
        val selectedIds = selectedAiDisciples.map { it.id }.toSet()
        
        val updatedAiDisciples = sect.aiDisciples.map { disciple ->
            if (disciple.id in selectedIds) {
                disciple.copy(isAlive = false)
            } else {
                disciple
            }
        }
        
        val updatedSect = sect.copy(aiDisciples = updatedAiDisciples)
        
        return Pair(selectedAiDisciples, updatedSect)
    }

    /**
     * 生成支援队伍弟子
     */
    private fun generateSupportDisciples(
        sect: WorldSect,
        supportSize: Int,
        maxRealm: Int,
        year: Int
    ): List<Disciple> {
        val disciples = mutableListOf<Disciple>()
        val realmDistribution = generateSupportTeamRealmDistribution(
            sect.maxRealm,
            supportSize,
            sect.disciples
        )

        realmDistribution.forEach { (realm, count) ->
            repeat(count) {
                val disciple = createSupportDisciple(sect, realm, year)
                disciples.add(disciple)
            }
        }

        return disciples
    }

    /**
     * 创建单个支援弟子
     */
    private fun createSupportDisciple(sect: WorldSect, realm: Int, year: Int): Disciple {
        val id = java.util.UUID.randomUUID().toString()
        val gender = if (Random.nextBoolean()) "male" else "female"
        val age = when (realm) {
            in 0..2 -> Random.nextInt(500, 2000)
            in 3..4 -> Random.nextInt(200, 800)
            in 5..6 -> Random.nextInt(100, 400)
            else -> Random.nextInt(20, 100)
        }

        val names = if (gender == "male") {
            listOf("云飞", "天行", "清风", "玄真", "道尘", "剑心", "凌霄", "破军", "天罡", "玄机")
        } else {
            listOf("月华", "紫烟", "灵芸", "清音", "玉瑶", "雪晴", "碧云", "青鸾", "素心", "梦璃")
        }

        val realmLayer = Random.nextInt(1, 10)
        val baseStats = when (realm) {
            in 0..2 -> Random.nextInt(80, 100)
            in 3..4 -> Random.nextInt(60, 85)
            in 5..6 -> Random.nextInt(40, 70)
            else -> Random.nextInt(20, 50)
        }

        val spiritRootType = listOf("metal", "wood", "water", "fire", "earth").random()
        val combatStatsVariance = Random.nextInt(-30, 31)
        val varianceMultiplier = 1.0 + combatStatsVariance / 100.0

        return Disciple(
            id = id,
            name = "${sect.name.take(1)}${names.random()}",
            gender = gender,
            age = age,
            realm = realm,
            realmLayer = realmLayer,
            spiritRootType = spiritRootType,
            intelligence = baseStats + Random.nextInt(-10, 11),
            charm = baseStats + Random.nextInt(-10, 11),
            morality = Random.nextInt(30, 80),
            status = DiscipleStatus.IDLE,
            loyalty = Random.nextInt(60, 80),
            combatStatsVariance = combatStatsVariance,
            baseHp = (100 * varianceMultiplier).toInt(),
            baseMp = (50 * varianceMultiplier).toInt(),
            basePhysicalAttack = (10 * varianceMultiplier).toInt(),
            baseMagicAttack = (5 * varianceMultiplier).toInt(),
            basePhysicalDefense = (5 * varianceMultiplier).toInt(),
            baseMagicDefense = (3 * varianceMultiplier).toInt(),
            baseSpeed = (10 * varianceMultiplier).toInt()
        )
    }

    /**
     * 获取支援队伍的最高境界
     * 规则：
     * - 如果宗门有渡劫境弟子(1-2)，派出合体境及以下(3)
     * - 如果宗门有大乘境弟子(3)，派出炼虚境及以下(4)
     * - 否则派出比宗门最高境界低一级的弟子
     */
    private fun getSupportTeamRealm(sectMaxRealm: Int): Int {
        return when (sectMaxRealm) {
            1, 2 -> 3  // 渡劫境宗门，派出合体境及以下
            3 -> 4  // 大乘境宗门，派出炼虚境及以下
            4 -> 5  // 炼虚境宗门，派出化神境及以下
            5 -> 6  // 化神境宗门，派出金丹境及以下
            6 -> 7  // 金丹境宗门，派出筑基境及以下
            else -> (sectMaxRealm + 1).coerceIn(7, 9)  // 其他情况，比最高境界低一级
        }
    }

    /**
     * 生成支援队伍的境界分布
     * 规则：
     * - 优先派遣中间力量（比宗门最高境界低一级的弟子）
     * - 弟子不够则向上补充（派遣更高境界的弟子）
     * 
     * @param sectMaxRealm 宗门最高弟子境界
     * @param supportSize 支援队伍规模
     * @param sectDisciples 宗门弟子分布 Map<境界, 数量>
     * @return 境界分布 Map<境界, 数量>
     */
    fun generateSupportTeamRealmDistribution(
        sectMaxRealm: Int,
        supportSize: Int,
        sectDisciples: Map<Int, Int>
    ): Map<Int, Int> {
        val baseRealm = getSupportTeamRealm(sectMaxRealm)
        val result = mutableMapOf<Int, Int>()
        var remaining = supportSize
        
        // 从基础境界开始，向上补充
        for (realm in baseRealm downTo 7) {
            if (remaining <= 0) break
            val available = sectDisciples[realm] ?: 0
            if (available > 0) {
                val take = minOf(available, remaining)
                result[realm] = take
                remaining -= take
            }
        }
        
        // 如果还不够，从更高境界补充
        if (remaining > 0) {
            for (realm in baseRealm - 1 downTo 1) {
                if (remaining <= 0) break
                val available = sectDisciples[realm] ?: 0
                if (available > 0) {
                    val take = minOf(available, remaining)
                    result[realm] = (result[realm] ?: 0) + take
                    remaining -= take
                }
            }
        }
        
        return result
    }

    /**
     * 攻击盟友处理
     */
    fun handleAttackAlly(sectId: String): Boolean {
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId } ?: return false

        if (sect.allianceId == null) return false

        val alliance = data.alliances.find { it.id == sect.allianceId } ?: return false

        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val updatedRelations = if (playerSect != null) {
            updateSectRelationFavor(data.sectRelations, playerSect.id, sectId, 0, data.gameYear)
        } else {
            data.sectRelations
        }

        val updatedSects = data.worldMapSects.map { s ->
            if (s.id == sectId) s.copy(allianceId = null, allianceStartYear = 0)
            else s
        }

        val updatedAlliances = data.alliances.filter { it.id != alliance.id }

        _gameData.value = data.copy(
            worldMapSects = updatedSects,
            alliances = updatedAlliances,
            sectRelations = updatedRelations
        )

        addEvent("背叛盟友${sect.name}，好感度降为0", EventType.DANGER)
        return true
    }

    /**
     * 检查是否为盟友
     */
    fun isAlly(sectId: String): Boolean {
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId } ?: return false
        return sect.allianceId != null && data.alliances.any { it.sectIds.contains("player") && it.sectIds.contains(sectId) }
    }

    /**
     * 获取玩家的盟友列表
     */
    fun getPlayerAllies(): List<WorldSect> {
        val data = _gameData.value
        val playerAllianceIds = data.alliances.filter { it.sectIds.contains("player") }.flatMap { it.sectIds }.filter { it != "player" }
        return data.worldMapSects.filter { it.id in playerAllianceIds }
    }

    /**
     * 获取盟约剩余年限
     */
    fun getAllianceRemainingYears(sectId: String): Int {
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId } ?: return 0
        val alliance = data.alliances.find { it.id == sect.allianceId } ?: return 0
        return (5 - (data.gameYear - alliance.startYear)).coerceAtLeast(0)
    }

    /**
     * 跨宗门道侣匹配
     * 概率与门内一致（1.2%）
     */
    private fun processCrossSectPartnerMatching(year: Int, month: Int) {
        val data = _gameData.value
        val updatedDisciples = _disciples.value.toMutableList()
        val matched = mutableSetOf<String>()

        // 获取玩家所有盟友宗门
        val allyIds = data.alliances
            .filter { it.sectIds.contains("player") }
            .flatMap { it.sectIds }
            .filter { it != "player" }

        if (allyIds.isEmpty()) return

        // 玩家弟子
        val playerDisciples = _disciples.value.filter {
            it.isAlive && it.age >= 18 && it.partnerId == null && !matched.contains(it.id) && it.status != DiscipleStatus.REFLECTING
        }

        playerDisciples.forEach { playerDisciple ->
            val talentEffects = TalentDatabase.calculateTalentEffects(playerDisciple.talentIds)
            val partnerChanceBonus = talentEffects["partnerChance"] ?: 0.0
            val baseChance = 0.012 // 1.2%
            val finalChance = (baseChance + partnerChanceBonus).coerceIn(0.01, 1.0)

            if (Random.nextDouble() < finalChance) {
                // 随机选择一个盟友宗门
                val allyId = allyIds.random()
                val allySect = data.worldMapSects.find { it.id == allyId } ?: return@forEach

                
                // 为该弟子创建一个虚拟的盟友宗门弟子作为伴侣
                val partnerId = "cross_sect_${java.util.UUID.randomUUID()}"
                val partnerGender = if (playerDisciple.gender == "male") "female" else "male"
                val partnerName = "${allySect.name.take(1)}${if (partnerGender == "male") listOf("云飞", "天行", "清风", "玄真").random() else listOf("月华", "紫烟", "灵芸", "清音").random()}"

                matched.add(playerDisciple.id)
                matched.add(partnerId)

                val discipleIndex = updatedDisciples.indexOfFirst { it.id == playerDisciple.id }
                if (discipleIndex >= 0) {
                    updatedDisciples[discipleIndex] = updatedDisciples[discipleIndex].copy(
                        partnerId = partnerId,
                        partnerSectId = allyId
                    )
                }

                addEvent("${playerDisciple.name} 与 ${partnerName}（${allySect.name}) 结为道侣，共修大道", EventType.SUCCESS)
            }
        }

        _disciples.value = updatedDisciples
    }

    /**
     * 跨宗门生育
     * 概率与门内一致（0.6%）
     * 子嗣归属男方宗门
     */
    private fun processCrossSectChildGeneration(year: Int) {
        val updatedDisciples = _disciples.value.toMutableList()
        val newChildren = mutableListOf<Disciple>()

        // 只处理女性玩家弟子（跨宗门道侣）
        _disciples.value.filter {
            it.isAlive && it.partnerId != null && it.age >= 18 && it.gender == "female" && it.partnerSectId != null && it.status != DiscipleStatus.REFLECTING
        }.forEach { disciple ->
            // 跨宗门伴侣，检查partnerId是否是跨宗门生成的ID
            if (!disciple.partnerId?.startsWith("cross_sect_")!!) return@forEach

            if (year - disciple.lastChildYear >= 1 && Random.nextDouble() < 0.006) {
                val discipleIndex = updatedDisciples.indexOfFirst { it.id == disciple.id }
                if (discipleIndex >= 0) {
                    updatedDisciples[discipleIndex] = updatedDisciples[discipleIndex].copy(lastChildYear = year)
                }

                // 子嗣归属玩家宗门（因为母亲是玩家弟子）
                val child = createCrossSectChild(disciple, year)
                newChildren.add(child)
                addEvent("${disciple.name} 喜得${if (child.gender == "male") "子" else "女"} ${child.name}，灵根: ${child.spiritRootName}", EventType.SUCCESS)
            }
        }

        _disciples.value = updatedDisciples + newChildren
    }

    /**
     * 创建跨宗门子嗣
     */
    private fun createCrossSectChild(mother: Disciple, year: Int): Disciple {
        val id = java.util.UUID.randomUUID().toString()
        val gender = if (Random.nextBoolean()) "male" else "female"
        val fatherSurname = mother.partnerId?.take(5) ?: ""

        val names = if (gender == "male") {
            listOf("逍遥", "无忌", "长生", "问道", "清风", "明月", "玄真", "道尘")
        } else {
            listOf("月华", "紫烟", "灵芸", "清音", "玉瑶", "雪晴", "碧云", "青鸾")
        }

        val spiritRootType = listOf("metal", "wood", "water", "fire", "earth").random()
        val combatStatsVariance = Random.nextInt(-30, 31)
        val varianceMultiplier = 1.0 + combatStatsVariance / 100.0

        return Disciple(
            id = id,
            name = "${mother.name.take(1)}${names.random()}",
            gender = gender,
            age = 0,
            realm = 9,
            realmLayer = 1,
            spiritRootType = spiritRootType,
            intelligence = mother.intelligence + Random.nextInt(-10, 11),
            charm = mother.charm + Random.nextInt(-10, 11),
            morality = Random.nextInt(30, 80),
            status = DiscipleStatus.IDLE,
            loyalty = Random.nextInt(70, 101),
            parentId1 = mother.id,
            parentId2 = mother.partnerId,
            combatStatsVariance = combatStatsVariance,
            baseHp = (100 * varianceMultiplier).toInt(),
            baseMp = (50 * varianceMultiplier).toInt(),
            basePhysicalAttack = (10 * varianceMultiplier).toInt(),
            baseMagicAttack = (5 * varianceMultiplier).toInt(),
            basePhysicalDefense = (5 * varianceMultiplier).toInt(),
            baseMagicDefense = (3 * varianceMultiplier).toInt(),
            baseSpeed = (10 * varianceMultiplier).toInt()
        )
    }

    /**
     * 处理支援队伍日度移动（沿路线移动）
     */
    private fun processSupportTeamMovement() {
        val data = _gameData.value
        val updatedTeams = mutableListOf<SupportTeam>()
        
        data.supportTeams.forEach { team ->
            if (team.status == "moving" && team.moveProgress < 1f) {
                val route = team.route
                if (route.isEmpty() || route.size < 2) {
                    updatedTeams.add(team)
                    return@forEach
                }
                
                val progressIncrement = 1f / team.duration.coerceAtLeast(1) * 1.5f
                val newProgress = (team.moveProgress + progressIncrement).coerceAtMost(1f)
                
                if (newProgress >= 1f) {
                    val playerSect = data.worldMapSects.find { it.isPlayerSect }
                    addEvent("${team.sourceSectName}的支援队伍已到达并驻扎，共${team.size}名弟子", EventType.SUCCESS)
                    updatedTeams.add(team.copy(
                        moveProgress = 1f,
                        status = "stationed",
                        currentX = playerSect?.x ?: team.targetX,
                        currentY = playerSect?.y ?: team.targetY,
                        currentRouteIndex = route.size - 1,
                        currentSegmentProgress = 1f
                    ))
                } else {
                    val totalSegments = route.size - 1
                    val segmentProgress = newProgress * totalSegments
                    val currentSegment = segmentProgress.toInt().coerceIn(0, totalSegments - 1)
                    val segmentLocalProgress = segmentProgress - currentSegment
                    
                    val fromSectId = route[currentSegment]
                    val toSectId = route[currentSegment + 1]
                    val fromSect = data.worldMapSects.find { it.id == fromSectId }
                    val toSect = data.worldMapSects.find { it.id == toSectId }
                    
                    val currentX = if (fromSect != null && toSect != null) {
                        fromSect.x + (toSect.x - fromSect.x) * segmentLocalProgress
                    } else team.currentX
                    
                    val currentY = if (fromSect != null && toSect != null) {
                        fromSect.y + (toSect.y - fromSect.y) * segmentLocalProgress
                    } else team.currentY
                    
                    updatedTeams.add(team.copy(
                        moveProgress = newProgress,
                        currentX = currentX,
                        currentY = currentY,
                        currentRouteIndex = currentSegment,
                        currentSegmentProgress = segmentLocalProgress
                    ))
                }
            } else {
                updatedTeams.add(team)
            }
        }
        
        _gameData.value = data.copy(supportTeams = updatedTeams)
    }
    
    private fun processAIBattleTeamMovement() {
        val data = _gameData.value
        val updatedTeams = data.aiBattleTeams.map { team ->
            if (team.status == "moving") {
                AISectAttackManager.updateAIBattleTeamMovement(team, data)
            } else {
                team
            }
        }
        
        _gameData.value = data.copy(aiBattleTeams = updatedTeams)
        
        updatedTeams.filter { AISectAttackManager.isTeamArrived(it) }.forEach { team ->
            triggerAISectBattle(team)
        }
    }
    
    private fun triggerAISectBattle(team: AIBattleTeam) {
        val data = _gameData.value
        val defenderSect = data.worldMapSects.find { it.id == team.defenderSectId } ?: return
        val attackerSect = data.worldMapSects.find { it.id == team.attackerSectId }
        
        if (team.isPlayerDefender) {
            triggerPlayerSectBattle(team, defenderSect, attackerSect)
            return
        }
        
        val result = AISectAttackManager.executeAISectBattle(team, defenderSect)
        
        if (attackerSect != null) {
            val updatedAttackerDisciples = attackerSect.aiDisciples.filter { it.id !in result.deadAttackerIds }
            _gameData.value = _gameData.value.copy(
                worldMapSects = _gameData.value.worldMapSects.map { sect ->
                    if (sect.id == team.attackerSectId) {
                        sect.copy(aiDisciples = updatedAttackerDisciples)
                    } else {
                        sect
                    }
                }
            )
        }
        
        val updatedDefenderDisciples = defenderSect.aiDisciples.filter { it.id !in result.deadDefenderIds }
        _gameData.value = _gameData.value.copy(
            worldMapSects = _gameData.value.worldMapSects.map { sect ->
                if (sect.id == team.defenderSectId) {
                    sect.copy(aiDisciples = updatedDefenderDisciples)
                } else {
                    sect
                }
            }
        )
        
        if (result.canOccupy) {
            val currentData = _gameData.value
            val defenderWarehouse = currentData.worldMapSects.find { it.id == team.defenderSectId }?.warehouse ?: SectWarehouse()
            
            _gameData.value = currentData.copy(
                worldMapSects = currentData.worldMapSects.map { sect ->
                    if (sect.id == team.defenderSectId) {
                        sect.copy(
                            occupierSectId = team.attackerSectId,
                            warehouse = SectWarehouse()
                        )
                    } else if (sect.id == team.attackerSectId) {
                        val mergedWarehouse = SectWarehouseManager.addItemsToWarehouse(sect.warehouse, defenderWarehouse.items)
                        val finalWarehouse = SectWarehouseManager.addSpiritStonesToWarehouse(mergedWarehouse, defenderWarehouse.spiritStones)
                        sect.copy(warehouse = finalWarehouse)
                    } else {
                        sect
                    }
                }
            )
            addEvent(AISectAttackManager.generateSectDestroyedEvent(team.attackerSectName, team.defenderSectName), EventType.DANGER)
        }
        
        val updatedRelations = _gameData.value.sectRelations.map { relation ->
            val isRelevantRelation = (relation.sectId1 == team.attackerSectId && relation.sectId2 == team.defenderSectId) ||
                                     (relation.sectId1 == team.defenderSectId && relation.sectId2 == team.attackerSectId)
            if (isRelevantRelation) {
                relation.copy(favor = (relation.favor - 10).coerceIn(-100, 100))
            } else {
                relation
            }
        }
        _gameData.value = _gameData.value.copy(sectRelations = updatedRelations)
        
        val aliveDisciples = team.disciples.filter { it.id !in result.deadAttackerIds }
        _gameData.value = _gameData.value.copy(
            aiBattleTeams = _gameData.value.aiBattleTeams.map { 
                if (it.id == team.id) {
                    it.copy(
                        status = "returning",
                        disciples = aliveDisciples,
                        currentX = defenderSect.x,
                        currentY = defenderSect.y,
                        targetX = team.attackerStartX,
                        targetY = team.attackerStartY,
                        moveProgress = 0f
                    )
                } else it 
            }
        )
    }
    
    private fun triggerPlayerSectBattle(team: AIBattleTeam, playerSect: WorldSect, attackerSect: WorldSect?) {
        val data = _gameData.value
        val playerDefenseTeam = AISectAttackManager.createPlayerDefenseTeam(_disciples.value)
        
        if (playerDefenseTeam.isEmpty()) {
            addEvent("${team.attackerSectName}进攻我宗，但我宗无可用弟子防守！", EventType.DANGER)
            processPlayerDefeat(team, attackerSect)
            return
        }
        
        val result = AISectAttackManager.executePlayerSectBattle(team, playerDefenseTeam)
        
        val deadPlayerDiscipleIds = result.deadDefenderIds
        _disciples.value = _disciples.value.map { disciple ->
            if (disciple.id in deadPlayerDiscipleIds) {
                disciple.copy(isAlive = false)
            } else {
                disciple
            }
        }
        
        if (attackerSect != null) {
            val updatedAttackerDisciples = attackerSect.aiDisciples.filter { it.id !in result.deadAttackerIds }
            _gameData.value = _gameData.value.copy(
                worldMapSects = _gameData.value.worldMapSects.map { sect ->
                    if (sect.id == team.attackerSectId) {
                        sect.copy(aiDisciples = updatedAttackerDisciples)
                    } else {
                        sect
                    }
                }
            )
        }
        
        val updatedPlayerRelations = _gameData.value.sectRelations.map { relation ->
            val isRelevantRelation = (relation.sectId1 == team.attackerSectId && relation.sectId2 == playerSect.id) ||
                                     (relation.sectId1 == playerSect.id && relation.sectId2 == team.attackerSectId)
            if (isRelevantRelation) {
                relation.copy(favor = (relation.favor - 15).coerceIn(0, 100))
            } else {
                relation
            }
        }

        _gameData.value = _gameData.value.copy(sectRelations = updatedPlayerRelations)

        if (result.winner == AIBattleWinner.ATTACKER) {
            processPlayerDefeat(team, attackerSect)
        } else {
            addEvent("我宗成功击退${team.attackerSectName}的进攻！", EventType.SUCCESS)
            val aliveDisciples = team.disciples.filter { it.id !in result.deadAttackerIds }
            _gameData.value = _gameData.value.copy(
                aiBattleTeams = _gameData.value.aiBattleTeams.map { 
                    if (it.id == team.id) {
                        it.copy(
                            status = "returning",
                            disciples = aliveDisciples,
                            currentX = playerSect.x,
                            currentY = playerSect.y,
                            targetX = team.attackerStartX,
                            targetY = team.attackerStartY,
                            moveProgress = 0f
                        )
                    } else {
                        it
                    }
                }
            )
        }
    }
    
    private fun processPlayerDefeat(team: AIBattleTeam, attackerSect: WorldSect?) {
        val data = _gameData.value
        val lootResult = AISectAttackManager.calculatePlayerLootLoss(
            data.spiritStones,
            _materials.value,
            _herbs.value,
            _seeds.value,
            _pills.value
        )
        
        _gameData.value = data.copy(
            spiritStones = data.spiritStones - lootResult.lostSpiritStones
        )
        
        lootResult.lostMaterials.forEach { (itemName, itemCount) ->
            when {
                _materials.value.any { it.name == itemName } -> {
                    _materials.value = _materials.value.map { material ->
                        if (material.name == itemName && material.quantity > 0) {
                            material.copy(quantity = (material.quantity - itemCount).coerceAtLeast(0))
                        } else material
                    }
                }
                _herbs.value.any { it.name == itemName } -> {
                    _herbs.value = _herbs.value.map { herb ->
                        if (herb.name == itemName && herb.quantity > 0) {
                            herb.copy(quantity = (herb.quantity - itemCount).coerceAtLeast(0))
                        } else herb
                    }
                }
                _seeds.value.any { it.name == itemName } -> {
                    _seeds.value = _seeds.value.map { seed ->
                        if (seed.name == itemName && seed.quantity > 0) {
                            seed.copy(quantity = (seed.quantity - itemCount).coerceAtLeast(0))
                        } else seed
                    }
                }
                _pills.value.any { it.name == itemName } -> {
                    _pills.value = _pills.value.map { pill ->
                        if (pill.name == itemName && pill.quantity > 0) {
                            pill.copy(quantity = (pill.quantity - itemCount).coerceAtLeast(0))
                        } else pill
                    }
                }
            }
        }
        
        if (attackerSect != null) {
            val warehouseItems = SectWarehouseManager.convertLootLossToWarehouseItems(
                lootResult.lostMaterials,
                _materials.value,
                _herbs.value,
                _seeds.value,
                _pills.value
            )
            val updatedWarehouse = SectWarehouseManager.addItemsToWarehouse(attackerSect.warehouse, warehouseItems)
            val finalWarehouse = SectWarehouseManager.addSpiritStonesToWarehouse(updatedWarehouse, lootResult.lostSpiritStones)
            
            _gameData.value = _gameData.value.copy(
                worldMapSects = _gameData.value.worldMapSects.map { sect ->
                    if (sect.id == attackerSect.id) {
                        sect.copy(warehouse = finalWarehouse)
                    } else {
                        sect
                    }
                }
            )
        }
        
        addEvent("我宗防守失败，被${team.attackerSectName}掠夺了${lootResult.lostSpiritStones}灵石和${lootResult.lostMaterials.size}种道具！", EventType.DANGER)
        
        _gameData.value = _gameData.value.copy(
            aiBattleTeams = _gameData.value.aiBattleTeams.map { 
                if (it.id == team.id) it.copy(status = "completed") else it 
            }
        )
    }
    
    private fun processAISectAttackDecisions() {
        val data = _gameData.value
        
        val newBattles = AISectAttackManager.decideAttacks(data)
        val supportBattles = AISectAttackManager.checkAllianceSupport(data)
        val playerAttack = AISectAttackManager.decidePlayerAttack(data)
        
        val allNewBattles = newBattles + supportBattles + listOfNotNull(playerAttack)
        
        if (allNewBattles.isNotEmpty()) {
            _gameData.value = data.copy(
                aiBattleTeams = data.aiBattleTeams + allNewBattles
            )
        }
    }
    
    private fun processSectDisciplesCultivation() {
        val data = _gameData.value
        var updatedSects = data.worldMapSects.map { sect ->
            AISectDiscipleManager.processMonthlyCultivation(sect)
        }
        updatedSects = updatedSects.map { sect ->
            AISectDiscipleManager.processManualMasteryGrowth(sect)
        }
        updatedSects = updatedSects.map { sect ->
            AISectDiscipleManager.processEquipmentNurture(sect)
        }
        _gameData.value = data.copy(worldMapSects = updatedSects)
    }
    
    private fun processSectDisciplesRecruitment(year: Int) {
        val data = _gameData.value
        val updatedSects = data.worldMapSects.map { sect ->
            AISectDiscipleManager.recruitDisciplesForSect(sect, year)
        }
        _gameData.value = data.copy(worldMapSects = updatedSects)
    }
    
    private fun processSectDisciplesAging(year: Int) {
        val data = _gameData.value
        val updatedSects = data.worldMapSects.map { sect ->
            AISectDiscipleManager.processAging(sect)
        }
        _gameData.value = data.copy(worldMapSects = updatedSects)
    }
    
    private fun generateYearlyItemsForAISects() {
        val data = _gameData.value
        val updatedSects = data.worldMapSects.map { sect ->
            if (!sect.isPlayerSect && !sect.isPlayerOccupied) {
                val newWarehouse = SectWarehouseManager.generateYearlyItemsForAISect(sect)
                val existingWarehouse = sect.warehouse
                val mergedWarehouse = SectWarehouseManager.addItemsToWarehouse(
                    existingWarehouse,
                    newWarehouse.items
                )
                val finalWarehouse = SectWarehouseManager.addSpiritStonesToWarehouse(
                    mergedWarehouse,
                    newWarehouse.spiritStones
                )
                sect.copy(warehouse = finalWarehouse)
            } else {
                sect
            }
        }
        _gameData.value = data.copy(worldMapSects = updatedSects)
    }

    private fun processOuterTournament(year: Int) {
        if (year % 3 != 0) {
            return
        }
        
        try {
            val outerDisciples = _disciples.value.filter { 
                it.isAlive && 
                it.discipleType == "outer" && 
                it.status != DiscipleStatus.REFLECTING
            }
            
            if (outerDisciples.isEmpty()) {
                addEvent("第${year}年外门大比：无符合条件的弟子参与", EventType.INFO)
                return
            }
            
            addEvent("第${year}年外门大比开始，共有${outerDisciples.size}名外门弟子参与", EventType.INFO)
            
            val sortedDisciples = outerDisciples.sortedWith(
                compareByDescending<Disciple> { it.realm }
                    .thenByDescending { it.realmLayer }
                    .thenByDescending { it.cultivation }
                    .thenByDescending { it.comprehension }
            )
            
            val promotionCount = minOf(20, sortedDisciples.size)
            val promotedDisciples = sortedDisciples.take(promotionCount)
            val promotedIds = promotedDisciples.map { it.id }.toSet()
            
            _disciples.value = _disciples.value.map { disciple ->
                if (disciple.id in promotedIds) {
                    disciple.copy(discipleType = "inner")
                } else {
                    disciple
                }
            }
            
            val promotedNames = promotedDisciples.take(5).map { it.name }
            val namesDisplay = if (promotedDisciples.size <= 5) {
                promotedNames.joinToString("、")
            } else {
                "${promotedNames.joinToString("、")} 等${promotedDisciples.size}人"
            }
            
            addEvent("第${year}年外门大比圆满结束，$namesDisplay 晋升为内门弟子", EventType.SUCCESS)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in processOuterTournament", e)
            addEvent("第${year}年外门大比处理出错", EventType.DANGER)
        }
    }

    /**
     * 重置所有弟子状态为空闲
     * - 探索队伍解散，弟子变空闲
     * - 战斗队伍解散，弟子变空闲
     * - 工作槽位清空，弟子变空闲
     * - 职务槽位清空，弟子变空闲
     * - 思过崖弟子不受影响
     */
    fun resetAllDisciplesStatus() {
        val data = _gameData.value
        val discipleIdsToReset = mutableSetOf<String>()

        // 1. 收集探索队伍中的弟子ID
        _teams.value.forEach { team ->
            discipleIdsToReset.addAll(team.memberIds)
        }
        _teams.value = emptyList()

        // 2. 收集洞府探索队伍中的弟子ID
        data.caveExplorationTeams.forEach { team ->
            discipleIdsToReset.addAll(team.memberIds)
        }

        // 3. 收集战斗队伍中的弟子ID
        data.battleTeam?.slots?.forEach { slot ->
            slot.discipleId?.let { discipleIdsToReset.add(it) }
        }

        // 4. 收集工作槽位中的弟子ID (BuildingSlot)
        _buildingSlots.value.forEach { slot ->
            slot.discipleId?.let { discipleIdsToReset.add(it) }
        }
        _buildingSlots.value = emptyList()

        // 5. 炼丹槽位没有discipleId，不需要重置弟子状态

        // 6. 收集灵矿槽位中的弟子ID
        data.spiritMineSlots.forEach { slot ->
            slot.discipleId?.let { discipleIdsToReset.add(it) }
        }

        // 7. 收集藏经阁槽位中的弟子ID
        data.librarySlots.forEach { slot ->
            slot.discipleId?.let { discipleIdsToReset.add(it) }
        }

        // 8. 收集职务槽位中的弟子ID (ElderSlots)
        val elderSlots = data.elderSlots
        elderSlots.viceSectMaster?.let { discipleIdsToReset.add(it) }
        elderSlots.herbGardenElder?.let { discipleIdsToReset.add(it) }
        elderSlots.alchemyElder?.let { discipleIdsToReset.add(it) }
        elderSlots.forgeElder?.let { discipleIdsToReset.add(it) }
        elderSlots.libraryElder?.let { discipleIdsToReset.add(it) }
        elderSlots.outerElder?.let { discipleIdsToReset.add(it) }
        elderSlots.preachingElder?.let { discipleIdsToReset.add(it) }
        elderSlots.lawEnforcementElder?.let { discipleIdsToReset.add(it) }
        elderSlots.innerElder?.let { discipleIdsToReset.add(it) }
        elderSlots.qingyunPreachingElder?.let { discipleIdsToReset.add(it) }

        // 收集亲传弟子槽位
        elderSlots.preachingMasters.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.lawEnforcementDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.lawEnforcementReserveDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.qingyunPreachingMasters.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.herbGardenDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.alchemyDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.forgeDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.libraryDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.herbGardenReserveDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.alchemyReserveDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.forgeReserveDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.spiritMineDeaconDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }

        // 更新弟子状态为空闲（排除思过崖弟子）
        _disciples.value = _disciples.value.map { disciple ->
            if (discipleIdsToReset.contains(disciple.id) && disciple.status != DiscipleStatus.REFLECTING) {
                disciple.copy(status = DiscipleStatus.IDLE)
            } else {
                disciple
            }
        }

        // 清空GameData中的相关数据
        _gameData.value = data.copy(
            caveExplorationTeams = emptyList(),
            battleTeam = null,
            spiritMineSlots = data.spiritMineSlots.map { it.copy(discipleId = null, discipleName = "") },
            librarySlots = data.librarySlots.map { it.copy(discipleId = null, discipleName = "") },
            elderSlots = ElderSlots()
        )

        addEvent("已重置所有弟子状态，探索/战斗队伍已解散，工作/职务槽位已清空", EventType.INFO)
    }

    fun redeemCode(
        code: String,
        usedCodes: List<String>,
        currentYear: Int,
        currentMonth: Int
    ): RedeemResult {
        val validationResult = RedeemCodeManager.validateCode(
            code = code,
            usedCodes = usedCodes,
            currentYear = currentYear,
            currentMonth = currentMonth
        )

        if (!validationResult.success) {
            return validationResult
        }

        val redeemCode = RedeemCodeManager.getRedeemCode(code) ?: return RedeemResult(
            success = false,
            message = "兑换码不存在"
        )

        val result = RedeemCodeManager.generateReward(redeemCode)

        if (!result.success) {
            return result
        }

        val data = _gameData.value

        result.rewards.forEach { reward ->
            when (reward.type) {
                "spiritStones" -> {
                    _gameData.value = _gameData.value.copy(
                        spiritStones = _gameData.value.spiritStones + reward.quantity
                    )
                }
                "equipment" -> {
                    val equipment = EquipmentDatabase.generateRandom(
                        minRarity = redeemCode.rarity,
                        maxRarity = redeemCode.rarity
                    )
                    if (_equipment.value.none { it.id == equipment.id }) {
                        _equipment.value = _equipment.value + equipment
                    }
                }
                "manual" -> {
                    val manual = ManualDatabase.generateRandom(
                        minRarity = redeemCode.rarity,
                        maxRarity = redeemCode.rarity
                    )
                    if (_manuals.value.none { it.id == manual.id }) {
                        _manuals.value = _manuals.value + manual
                    }
                }
                "pill" -> {
                    val pill = ItemDatabase.generateRandomPill(
                        minRarity = redeemCode.rarity,
                        maxRarity = redeemCode.rarity
                    )
                    addPillToWarehouse(pill)
                }
                "material" -> {
                    val material = ItemDatabase.generateRandomMaterial(
                        minRarity = redeemCode.rarity,
                        maxRarity = redeemCode.rarity
                    )
                    addMaterialToWarehouse(material)
                }
                "herb" -> {
                    val herbTemplate = HerbDatabase.generateRandomHerb(
                        minRarity = redeemCode.rarity,
                        maxRarity = redeemCode.rarity
                    )
                    val herb = Herb(
                        id = java.util.UUID.randomUUID().toString(),
                        name = herbTemplate.name,
                        rarity = herbTemplate.rarity,
                        description = herbTemplate.description,
                        category = herbTemplate.category,
                        quantity = 1
                    )
                    addHerbToWarehouse(herb)
                }
                "seed" -> {
                    val seedTemplate = HerbDatabase.generateRandomSeed(
                        minRarity = redeemCode.rarity,
                        maxRarity = redeemCode.rarity
                    )
                    val seed = Seed(
                        id = java.util.UUID.randomUUID().toString(),
                        name = seedTemplate.name,
                        rarity = seedTemplate.rarity,
                        description = seedTemplate.description,
                        growTime = seedTemplate.growTime,
                        yield = seedTemplate.yield,
                        quantity = 1
                    )
                    addSeedToWarehouse(seed)
                }
                "disciple" -> {
                    result.disciple?.let { disciple ->
                        val currentMonthValue = data.gameYear * 12 + data.gameMonth
                        val discipleWithRecruitTime = disciple.copy(
                            recruitedMonth = currentMonthValue
                        )
                        _disciples.value = _disciples.value + discipleWithRecruitTime
                    }
                }
            }
        }

        result.disciples.forEach { disciple ->
            val currentMonthValue = data.gameYear * 12 + data.gameMonth
            val discipleWithRecruitTime = disciple.copy(
                recruitedMonth = currentMonthValue
            )
            _disciples.value = _disciples.value + discipleWithRecruitTime
        }

        _gameData.value = _gameData.value.copy(
            usedRedeemCodes = _gameData.value.usedRedeemCodes + code.uppercase()
        )

        val rewardDescription = result.rewards.joinToString("、") { reward ->
            when (reward.type) {
                "spiritStones" -> "${reward.quantity}灵石"
                "disciple" -> "弟子${reward.name}"
                else -> "${reward.name}×${reward.quantity}"
            }
        }
        addEvent("兑换成功！获得：$rewardDescription", EventType.SUCCESS)

        return result.copy(message = "兑换成功！获得：$rewardDescription")
    }

    // ==================== 内存管理 ====================

    /**
     * 获取当前内存使用情况的字符串描述
     * 返回各类数据对象的数量统计
     */
    fun getMemoryUsageInfo(): String {
        val data = _gameData.value
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
        sb.appendLine("建筑槽位: ${_buildingSlots.value.size}")
        sb.appendLine("炼丹槽位: ${_alchemySlots.value.size}")
        sb.appendLine("事件记录: ${_events.value.size}/50")
        sb.appendLine("战斗日志: ${_battleLogs.value.size}/50")
        sb.appendLine("世界宗门: ${data.worldMapSects.size}")
        sb.appendLine("宗门关系: ${data.sectRelations.size}")
        sb.appendLine("洞府数量: ${data.cultivatorCaves.size}")
        sb.appendLine("洞府探索队伍: ${data.caveExplorationTeams.size}")
        sb.appendLine("AI洞府队伍: ${data.aiCaveTeams.size}")
        sb.appendLine("结盟信息: ${data.alliances.size}")
        sb.appendLine("支援队伍: ${data.supportTeams.size}")
        sb.appendLine("探查信息: ${data.scoutInfo.size}")
        sb.appendLine("商人商品: ${data.travelingMerchantItems.size}")
        sb.appendLine("兑换码使用: ${data.usedRedeemCodes.size}")

        return sb.toString()
    }

    /**
     * 根据内存压力级别释放资源
     * @param level 内存压力级别，支持 ComponentCallbacks2 常量或简化级别
     *   - TRIM_MEMORY_MODERATE (60) 或 1: 中等压力，清理旧事件（保留最近20条）
     *   - TRIM_MEMORY_RUNNING_CRITICAL (15) / TRIM_MEMORY_COMPLETE (80) 或 2: 严重压力，保留最近10条
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
        
        synchronized(this) {
            if (_events.value.size > eventsToKeep) {
                _events.value = _events.value.take(eventsToKeep)
                Log.d(TAG, "内存释放($levelName): 事件列表已清理，保留最近$eventsToKeep 条")
            }
            if (_battleLogs.value.size > logsToKeep) {
                _battleLogs.value = _battleLogs.value.take(logsToKeep)
                Log.d(TAG, "内存释放($levelName): 战斗日志已清理，保留最近$logsToKeep 条")
            }
        }
    }
}
