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
    
    private val _warTeams = MutableStateFlow<List<WarTeam>>(emptyList())
    val warTeams: StateFlow<List<WarTeam>> = _warTeams.asStateFlow()
    
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
        private const val TICKS_PER_SECOND = 5
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
        val warTeams: List<WarTeam>,
        val alliances: List<Alliance>,
        val supportTeams: List<SupportTeam>
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
                warTeams = _warTeams.value,
                alliances = _gameData.value.alliances,
                supportTeams = _gameData.value.supportTeams
            )
        }
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
        private var warTeamsSnapshot: List<WarTeam> = _warTeams.value
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
        val warTeams: List<WarTeam> get() = warTeamsSnapshot
        
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
        
        fun updateWarTeams(update: (List<WarTeam>) -> List<WarTeam>) {
            warTeamsSnapshot = update(warTeamsSnapshot)
            gameDataSnapshot = gameDataSnapshot.copy(warTeams = warTeamsSnapshot)
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
            _warTeams.value = warTeamsSnapshot
            
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
        _warTeams.value = emptyList()
        _alchemySlots.value = emptyList()
        
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
        
        addEvent("欢迎来到 $sectName！宗门初立，请好好经营。", EventType.SUCCESS)
    }
    
    private fun updateWarTeamsSync(newTeams: List<WarTeam>) {
        _warTeams.value = newTeams
        val currentData = _gameData.value
        _gameData.value = currentData.copy(warTeams = newTeams)
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
        warTeams: List<WarTeam> = emptyList(),
        alliances: List<Alliance> = emptyList(),
        supportTeams: List<SupportTeam> = emptyList()
    ) {
        val resolvedWarTeams = warTeams.ifEmpty { gameData.warTeams }
        val resolvedAlliances = alliances.ifEmpty { gameData.alliances }
        val resolvedSupportTeams = supportTeams.ifEmpty { gameData.supportTeams }
        val finalGameData = gameData.copy(
            warTeams = resolvedWarTeams,
            alliances = resolvedAlliances,
            supportTeams = resolvedSupportTeams
        )
        
        _gameData.value = finalGameData
        _disciples.value = disciples
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
        _warTeams.value = resolvedWarTeams

        initializeWorldMap()

        initializeHerbGardenSlots()

        initializeLibrarySlots()

        checkAndInitMerchant(gameData.gameYear)
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
        }
    }
    
    fun advanceDay() {
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
                }
            }

            _gameData.value = data.copy(
                gameYear = newYear,
                gameMonth = newMonth,
                gameDay = newDay
            )

            processDailyEvents(newYear, newMonth, newDay)
            
            if (monthChanged) {
                processMonthlyEvents(newYear, newMonth)
            }
        } catch (e: Exception) {
            Log.e("GameEngine", "Error in advanceDay", e)
        }
    }
    
    private fun processDailyEvents(year: Int, month: Int, day: Int) {
        processScoutTeamMovement()
    }
    
    private fun processScoutTeamMovement() {
        val data = _gameData.value
        val updatedTeams = _teams.value.map { team ->
            if (team.status == ExplorationStatus.SCOUTING && team.moveProgress < 1f) {
                val progressIncrement = 1f / team.duration.coerceAtLeast(1)
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
                    val x = team.currentX + (team.targetX - team.currentX) * progressIncrement
                    val y = team.currentY + (team.targetY - team.currentY) * progressIncrement
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
        tickCounter++
        
        val isSecondTick = tickCounter >= TICKS_PER_SECOND
        if (isSecondTick) {
            tickCounter = 0
        }
        
        try {
            processDiscipleCultivation(isSecondTick)
        } catch (e: Exception) {
            Log.e("GameEngine", "Error in processDiscipleCultivation", e)
        }
        try {
            processManualProficiencyPerSecond(isSecondTick)
        } catch (e: Exception) {
            Log.e("GameEngine", "Error in processManualProficiencyPerSecond", e)
        }
        try {
            processEquipmentAutoNurture(isSecondTick)
        } catch (e: Exception) {
            Log.e("GameEngine", "Error in processEquipmentAutoNurture", e)
        }
        try {
            processWarTeamTravel()
        } catch (e: Exception) {
            Log.e("GameEngine", "Error in processWarTeamTravel", e)
        }
        
        if (isSecondTick) {
            processRealTimeUpdates()
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
            "checkTournament" to { checkTournament(year) },
            "checkOuterDiscipleTournament" to { checkOuterDiscipleTournament(year) },
            "processPillEffectDuration" to { processPillEffectDuration() },
            "processCaveLifecycle" to { processCaveLifecycle(year, month) },
            "processWarTeamsMonthly" to { processWarTeamsMonthly(year, month) },
            "processLoyaltyDesertion" to { processLoyaltyDesertion() },
            "processEnhancedSecurity" to { processEnhancedSecurity() },
            "processMoralityTheft" to { processMoralityTheft() },
            "processLawEnforcementReserveAutoFill" to { processLawEnforcementReserveAutoFill() },
            "processReflectionEnd" to { processReflectionEnd(year) },
            "checkAllianceExpiry" to { checkAllianceExpiry(year) },
            "checkAllianceFavorDrop" to { checkAllianceFavorDrop() },
            "processAIAlliances" to { processAIAlliances(year) },
            "processCrossSectPartnerMatching" to { processCrossSectPartnerMatching(year, month) },
            "processCrossSectChildGeneration" to { processCrossSectChildGeneration(year) },
            "processSupportTeamsMonthly" to { processSupportTeamsMonthly(year, month) },
            "processDiplomaticEvents" to { processDiplomaticEvents(year) },
            "processScoutInfoExpiry" to { processScoutInfoExpiry(year, month) },
            "resetMonthlyUsedPills" to { resetMonthlyUsedPills() }
        )

        operations.forEach { (name, operation) ->
            try {
                operation()
            } catch (e: Exception) {
                Log.e("GameEngine", "Error in $name", e)
            }
        }

        // 每年 1 月才增加年龄（弟子每年长一岁）
        // 招募弟子列表每年一月刷新
        // 重置所有宗门的送礼限制
        if (month == 1) {
            try {
                processAging(year)
                refreshRecruitList(year)
                resetSectGiftYear()
                processSectRelationNaturalChange(year)
            } catch (e: Exception) {
                Log.e("GameEngine", "Error in yearly operations", e)
            }
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
        
        val updatedSects = data.worldMapSects.map { sect ->
            // 只对AI宗门（非玩家宗门）进行处理
            if (sect.id != "player") {
                // 检查是否在上一年收到过礼物（lastGiftYear == previousYear表示收到过）
                if (sect.lastGiftYear != previousYear) {
                    // 未收到礼物，好感度减一（最低为0）
                    val newRelation = (sect.relation - 1).coerceAtLeast(0)
                    sect.copy(
                        lastGiftYear = 0,
                        relation = newRelation
                    )
                } else {
                    // 收到过礼物，只重置lastGiftYear
                    sect.copy(lastGiftYear = 0)
                }
            } else {
                // 玩家宗门只重置lastGiftYear
                sect.copy(lastGiftYear = 0)
            }
        }
        _gameData.value = data.copy(worldMapSects = updatedSects)
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
                
                var naturalChange = Random.nextInt(-3, 4)
                
                if (sect1.isRighteous == sect2.isRighteous) {
                    naturalChange += 1
                }
                
                val isAdjacent = sect1.connectedSectIds.contains(sect2.id)
                if (isAdjacent) {
                    naturalChange += Random.nextInt(-1, 2)
                }
                
                val levelDiff = kotlin.math.abs(sect1.level - sect2.level)
                if (levelDiff > 2) {
                    naturalChange -= 1
                }
                
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
            eventRoll < 0.2 -> {
                val delta = Random.nextInt(8, 16)
                Pair(delta, "${sect1.name}与${sect2.name}互赠礼物，关系改善")
            }
            eventRoll < 0.35 -> {
                val delta = Random.nextInt(5, 11)
                Pair(delta, "${sect1.name}与${sect2.name}进行友好交流，关系增进")
            }
            eventRoll < 0.5 -> {
                val delta = Random.nextInt(3, 8)
                Pair(delta, "${sect1.name}与${sect2.name}达成资源互换协议")
            }
            eventRoll < 0.65 -> {
                val delta = -Random.nextInt(5, 11)
                Pair(delta, "${sect1.name}与${sect2.name}发生边界争端")
            }
            eventRoll < 0.8 -> {
                val delta = -Random.nextInt(8, 16)
                Pair(delta, "${sect1.name}与${sect2.name}产生误会，关系恶化")
            }
            eventRoll < 0.9 -> {
                val delta = -Random.nextInt(12, 21)
                Pair(delta, "${sect1.name}背后诋毁${sect2.name}，关系急剧恶化")
            }
            else -> {
                val delta = Random.nextInt(10, 21)
                Pair(delta, "${sect1.name}与${sect2.name}因共同危机而关系升温")
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
        
        // 计算实际耗时（考虑速度加成 + 长老/亲传弟子加成）
        val baseDuration = seed.growTime
        val actualDuration = (baseDuration * (1.0 - elderBonus.speedBonus)).toInt().coerceAtLeast(1)
        
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
                        val finalSuccessRate = (recipe.successRate + totalSuccessBonus).coerceAtMost(0.95)
                        
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
                                    physicalAttackPercent = recipe.physicalAttackPercent,
                                    physicalDefensePercent = recipe.physicalDefensePercent,
                                    magicAttackPercent = recipe.magicAttackPercent,
                                    magicDefensePercent = recipe.magicDefensePercent,
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
        val elapsed = (currentYear - slot.startYear) * 12 + (currentMonth - slot.startMonth)
        return (slot.growTime - elapsed).coerceAtLeast(0)
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
                    lifespan = updatedDisciple.lifespan + effect.extendLife
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
            if (hasMindManual) manual.type != ManualType.MIND else true
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
        
        if (disciple.cultivationSpeedDuration > 0) {
            return disciple
        }
        
        val cultivationPill = disciple.storageBagItems
            .filter { item ->
                item.itemType == "pill" && item.effect?.let { effect ->
                    effect.cultivationSpeed > 1.0 || effect.cultivationPercent > 0
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
            
            val message = if (actualGain > 0 && speedBonus > 1.0) {
                "${disciple.name} 立即使用了 ${cultivationPill.name}，修为增加${actualGain}，修炼速度提升${GameUtils.formatPercent(speedBonus - 1)}"
            } else if (actualGain > 0) {
                "${disciple.name} 立即使用了 ${cultivationPill.name}，修为增加${actualGain}"
            } else {
                "${disciple.name} 立即使用了 ${cultivationPill.name}，修炼速度提升${GameUtils.formatPercent(speedBonus - 1)}"
            }
            addEvent(message, EventType.INFO)
            
            return disciple.copy(
                storageBagItems = updatedItems,
                cultivation = newCultivation,
                totalCultivation = disciple.totalCultivation + actualGain,
                cultivationSpeedBonus = speedBonus,
                cultivationSpeedDuration = duration
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
            if (hasMindManual) manual.type != ManualType.MIND else true
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

    private fun checkTournament(year: Int) {
        autoHoldTournamentsIfNeeded()
    }
    
    private fun checkOuterDiscipleTournament(year: Int) {
        val data = _gameData.value
        if (year - data.outerTournamentLastYear < 3) return
        
        val outerDisciples = _disciples.value.filter { 
            it.isAlive && it.discipleType == "outer" 
        }
        
        if (outerDisciples.size < 2) {
            _gameData.value = data.copy(outerTournamentLastYear = year)
            return
        }
        
        val equipmentMap = _equipment.value.associateBy { it.id }
        val manualMap = _manuals.value.associateBy { it.id }
        val allManualProficiencies = data.manualProficiencies
        
        val sortedDisciples = outerDisciples.sortedByDescending { disciple ->
            val proficienciesMap = (allManualProficiencies[disciple.id] ?: emptyList())
                .associateBy { it.manualId }
            val stats = disciple.getFinalStats(equipmentMap, manualMap, proficienciesMap)
            stats.physicalAttack + stats.magicAttack + stats.physicalDefense + stats.magicDefense
        }
        
        val top20 = sortedDisciples.take(20)
        val promotedCount = top20.size
        
        if (promotedCount > 0) {
            val promotedIds = top20.map { it.id }.toSet()
            
            _disciples.value = _disciples.value.map { disciple ->
                if (disciple.id in promotedIds) {
                    disciple.copy(discipleType = "inner")
                } else {
                    disciple
                }
            }
            
            _gameData.value = data.copy(outerTournamentLastYear = year)
            
            val promotedNames = top20.take(5).map { it.name }
            val nameList = if (top20.size <= 5) {
                promotedNames.joinToString("、")
            } else {
                "${promotedNames.joinToString("、")}等${promotedCount}人"
            }
            
            addEvent("外门弟子大比结束！$nameList 晋升为内门弟子，可在青云峰分配任务", EventType.SUCCESS)
        }
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
        val librarySlots = _gameData.value.librarySlots
        
        val updatedDisciples = _disciples.value.map { disciple ->
            if (!disciple.isAlive || !disciple.canCultivate) {
                return@map disciple
            }

            var currentDisciple = autoUseCultivationPills(disciple)

            var speed = GameConfig.Cultivation.BASE_SPEED

            speed *= currentDisciple.spiritRoot.cultivationBonus

            speed *= currentDisciple.comprehensionSpeedBonus

            speed *= currentDisciple.cultivationSpeedBonus

            val manualBonus = calculateManualCultivationBonus(currentDisciple)
            speed *= (1 + manualBonus)

            val talentBonus = calculateTalentCultivationBonus(currentDisciple)
            speed *= (1 + talentBonus)

            val tickMultiplier = 1.0 / TICKS_PER_SECOND
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

            disciple.manualIds.forEach { manualId ->
                val manual = _manuals.value.find { it.id == manualId } ?: return@forEach
                val existingIndex = proficiencies.indexOfFirst { it.manualId == manualId }

                val gain = ManualProficiencySystem.calculateProficiencyGain(
                    baseGain = ManualProficiencySystem.BASE_PROFICIENCY_GAIN / 5.0,
                    discipleRealm = disciple.realm,
                    manualRarity = manual.rarity,
                    libraryBonus = libraryBonus,
                    talentBonus = manualLearnSpeedBonus
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
            val tickMultiplier = 1.0 / TICKS_PER_SECOND
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
        
        if (disciple.cultivationSpeedDuration > 0) {
            return disciple
        }
        
        val cultivationPill = disciple.storageBagItems
            .filter { item ->
                item.itemType == "pill" && item.effect?.let { effect ->
                    effect.cultivationSpeed > 1.0 || effect.cultivationPercent > 0
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
            
            val message = if (actualGain > 0 && speedBonus > 1.0) {
                "${disciple.name} 自动使用了 ${cultivationPill.name}，修为增加${actualGain}，修炼速度提升${GameUtils.formatPercent(speedBonus - 1)}"
            } else if (actualGain > 0) {
                "${disciple.name} 自动使用了 ${cultivationPill.name}，修为增加${actualGain}"
            } else {
                "${disciple.name} 自动使用了 ${cultivationPill.name}，修炼速度提升${GameUtils.formatPercent(speedBonus - 1)}"
            }
            addEvent(message, EventType.INFO)
            
            return disciple.copy(
                storageBagItems = updatedItems,
                cultivation = newCultivation,
                totalCultivation = disciple.totalCultivation + actualGain,
                cultivationSpeedBonus = speedBonus,
                cultivationSpeedDuration = duration
            )
        }
        
        return disciple
    }
    
    private fun autoUseBreakthroughPills(disciple: Disciple): Pair<Disciple, Double> {
        if (disciple.storageBagItems.isEmpty()) return Pair(disciple, 0.0)
        
        val breakthroughPill = disciple.storageBagItems.find { item ->
            item.itemType == "pill" && item.effect?.breakthroughChance != null && item.effect.breakthroughChance > 0
        }
        
        if (breakthroughPill != null) {
            // 检查丹药是否适合当前境界
            val targetRealm = breakthroughPill.effect?.targetRealm ?: 0
            if (targetRealm != disciple.realm) {
                // 丹药不适合当前境界，不使用
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

        val chance = disciple.getBreakthroughChance(pillBonus)
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
                val needsHeartDemon = disciple.realm <= 5
                
                if (needsHeartDemon) {
                    val heartResult = TribulationSystem.trialHeartDemon(disciple)
                    if (!heartResult.success) {
                        addEvent("${disciple.name} 突破失败，未能战胜心魔", EventType.INFO)
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
        
        // 计算长老和亲传弟子的产量加成
        val elderBonus = calculateElderAndDisciplesBonus("spiritMine")
        var yieldMultiplier = 1.0 + elderBonus.yieldBonus
        
        // 灵矿增产政策：产出+20%
        val spiritMineBoostEnabled = data.sectPolicies.spiritMineBoost
        if (spiritMineBoostEnabled) {
            yieldMultiplier += 0.2
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
            val bonusText = if (elderBonus.yieldBonus != 0.0) {
                " (长老加成${if (elderBonus.yieldBonus > 0) "+" else ""}${GameUtils.formatPercent(elderBonus.yieldBonus)})"
            } else ""
            val policyText = if (spiritMineBoostEnabled) " (增产令+20%)" else ""
            addEvent("灵矿场${workingDiscipleCount}名弟子开采获得${totalOutput}灵石${bonusText}${policyText}", EventType.SUCCESS)
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
        val totalSuccessBonus = discipleSuccessBonus + elderBonus.successBonus
        val finalSuccessRate = (recipe.successRate + totalSuccessBonus).coerceAtMost(0.95)

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
                    physicalAttackPercent = recipe.physicalAttackPercent,
                    magicAttackPercent = recipe.magicAttackPercent,
                    physicalDefensePercent = recipe.physicalDefensePercent,
                    magicDefensePercent = recipe.magicDefensePercent,
                    hpPercent = recipe.hpPercent,
                    mpPercent = recipe.mpPercent,
                    speedPercent = recipe.speedPercent,
                    healMaxHpPercent = recipe.healMaxHpPercent,
                    healPercent = recipe.healPercent,
                    battleCount = recipe.battleCount
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

            // 计算实际耗时（考虑速度加成 + 长老/亲传弟子加成）
            val baseDuration = calculateWorkDurationWithAllDisciples(recipe.duration, "alchemyRoom")
            val actualDuration = (baseDuration * (1.0 - elderBonus.speedBonus)).toInt().coerceAtLeast(1)

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

            val bonusPercent = ((recipe.duration - actualDuration) * 100 / recipe.duration)
            val speedText = if (bonusPercent > 0) "(加速${bonusPercent}%)" else ""
            addEvent("自动续炼：开始炼制${recipe.name}${speedText}", EventType.INFO)
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
        val totalSuccessBonus = discipleSuccessBonus + elderBonus.successBonus
        val finalSuccessRate = (recipe.successRate + totalSuccessBonus).coerceAtMost(0.95)

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

            // 计算实际耗时（考虑速度加成 + 长老/亲传弟子加成）
            val baseDuration = calculateWorkDurationWithAllDisciples(recipe.duration, "forge")
            val actualDuration = (baseDuration * (1.0 - elderBonus.speedBonus)).toInt().coerceAtLeast(1)

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
                slot = recipe.type
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
                slot = EquipmentSlot.values().random()
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
                slot = EquipmentSlot.values().random()
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
                type = ManualType.values().random()
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
                category = PillCategory.values().random()
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
                type = ManualType.values().random()
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
        if (pill.cannotStack) {
            _pills.value = _pills.value + pill
            return
        }
        
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
                rarity = item.rarity
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
                minRealm = template.minRealm
            )
        } else {
            Manual(
                id = item.itemId,
                name = item.name,
                rarity = item.rarity
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
     * 化神：凡品 - 宝品
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
            avgRealm >= 8.0 -> Pair(1, 1)   // 炼气期：凡品
            avgRealm >= 7.0 -> Pair(1, 1)   // 筑基期：凡品
            avgRealm >= 6.0 -> Pair(1, 2)   // 金丹期：凡品-灵品
            avgRealm >= 5.0 -> Pair(1, 2)   // 元婴期：凡品-灵品
            avgRealm >= 4.0 -> Pair(1, 3)   // 化神期：凡品-宝品
            avgRealm >= 3.0 -> Pair(3, 5)   // 炼虚期：宝品-地品
            avgRealm >= 2.0 -> Pair(3, 5)   // 合体期：宝品-地品
            avgRealm >= 1.0 -> Pair(3, 5)   // 大乘期：宝品-地品
            avgRealm >= 0.5 -> Pair(5, 6)   // 渡劫期：地品-天品
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
        // 从HerbDatabase生成真正的草药，根据品阶范围
        val herbTemplate = HerbDatabase.generateRandomHerb(minRarity, maxRarity)
        // 添加到仓库（_herbs）
        val existingHerb = _herbs.value.find { it.name == herbTemplate.name && it.rarity == herbTemplate.rarity }
        if (existingHerb != null) {
            _herbs.value = _herbs.value.map {
                if (it.name == herbTemplate.name && it.rarity == herbTemplate.rarity) it.copy(quantity = it.quantity + 1)
                else it
            }
        } else {
            val newHerb = Herb(
                id = UUID.randomUUID().toString(),
                name = herbTemplate.name,
                rarity = herbTemplate.rarity,
                quantity = 1,
                category = herbTemplate.category
            )
            _herbs.value = _herbs.value + newHerb
        }
        return herbTemplate.name
    }

    private fun generateExplorationSeedDropSilent(dungeonName: String, minRarity: Int, maxRarity: Int): String {
        // 从数据库模板生成种子，根据品阶范围
        val seedTemplate = HerbDatabase.generateRandomSeed(minRarity, maxRarity)
        // 添加到仓库（_seeds）
        val existingSeed = _seeds.value.find { it.name == seedTemplate.name && it.rarity == seedTemplate.rarity }
        if (existingSeed != null) {
            _seeds.value = _seeds.value.map {
                if (it.name == seedTemplate.name && it.rarity == seedTemplate.rarity) it.copy(quantity = it.quantity + 1)
                else it
            }
        } else {
            val newSeed = Seed(
                id = UUID.randomUUID().toString(),
                name = seedTemplate.name,
                rarity = seedTemplate.rarity,
                quantity = 1,
                growTime = seedTemplate.growTime,
                yield = seedTemplate.yield
            )
            _seeds.value = _seeds.value + newSeed
        }
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
        
        if (newPill.cannotStack) {
            currentPills.add(newPill)
            return currentPills to newPill
        }
        
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
     * 以50点为准，每少一点增加2%脱离概率
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

            // 子嗣保护期：7岁前不会脱离宗门
            if (disciple.age < 7) {
                return@filter true
            }

            // 新弟子保护期：一年内不会脱离宗门
            val monthsSinceRecruitment = currentMonth - disciple.recruitedMonth
            if (monthsSinceRecruitment < 12) {
                return@filter true
            }

            // 计算脱离概率：(50 - 忠诚) * 2%
            val desertionChance = (50 - disciple.loyalty) * 0.02
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
        // 增强治安政策：基础+30%抓捕率，副宗主智力以50为基准，每高5点增加1%
        fun calculateArrestChance(): Double {
            var totalChance = 0.0
            
            // 增强治安政策效果
            val enhancedSecurityEnabled = _gameData.value.sectPolicies.enhancedSecurity
            if (enhancedSecurityEnabled) {
                // 基础30%抓捕率
                totalChance += 0.30
                
                // 副宗主智力影响：以50为基准，每高5点增加1%
                val viceSectMasterId = _gameData.value.elderSlots.viceSectMaster
                val viceSectMaster = _disciples.value.find { it.id == viceSectMasterId }
                viceSectMaster?.let { vice ->
                    val viceBonus = (vice.intelligence - 50) / 5.0 * 0.01
                    totalChance += viceBonus
                }
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

    private fun processEnhancedSecurity() {
        val enhancedSecurityEnabled = _gameData.value.sectPolicies.enhancedSecurity
        if (!enhancedSecurityEnabled) return
        
        val cost = 5000L
        val currentStones = _gameData.value.spiritStones
        
        if (currentStones >= cost) {
            _gameData.value = _gameData.value.copy(spiritStones = currentStones - cost)
            addEvent("增强治安政策消耗5000灵石", EventType.INFO)
        } else {
            val currentPolicies = _gameData.value.sectPolicies
            _gameData.value = _gameData.value.copy(
                sectPolicies = currentPolicies.copy(enhancedSecurity = false)
            )
            addEvent("灵石不足（当前${currentStones}），增强治安政策已自动关闭", EventType.WARNING)
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
            disciple != null && disciple.isAlive
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
            loyalty = Random.nextInt(30, 61),
            comprehension = Random.nextInt(1, 101),
            artifactRefining = Random.nextInt(1, 101),
            pillRefining = Random.nextInt(1, 101),
            spiritPlanting = Random.nextInt(1, 101),
            teaching = Random.nextInt(1, 101),
            morality = Random.nextInt(1, 101),
            recruitedMonth = currentMonth,
            combatStatsVariance = Random.nextInt(-30, 31)
        )
        return applyTalentBaseFlatBonuses(baseChild, talents)
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
        
        _warTeams.value = _warTeams.value.map { team ->
            val newMembers = team.members.filter { it.discipleId != discipleId }
            if (newMembers.size != team.members.size) {
                team.copy(members = newMembers)
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
            if (elders.spiritMineElder == discipleId) {
                updated = updated.copy(spiritMineElder = null)
            }
            if (elders.recruitElder == discipleId) {
                updated = updated.copy(recruitElder = null)
            }
            updated.copy(
                herbGardenDisciples = elders.herbGardenDisciples.map { if (it.discipleId == discipleId) it.copy(discipleId = null, discipleName = "", discipleRealm = "") else it },
                alchemyDisciples = elders.alchemyDisciples.map { if (it.discipleId == discipleId) it.copy(discipleId = null, discipleName = "", discipleRealm = "") else it },
                forgeDisciples = elders.forgeDisciples.map { if (it.discipleId == discipleId) it.copy(discipleId = null, discipleName = "", discipleRealm = "") else it },
                libraryDisciples = elders.libraryDisciples.map { if (it.discipleId == discipleId) it.copy(discipleId = null, discipleName = "", discipleRealm = "") else it },
                spiritMineDisciples = elders.spiritMineDisciples.map { if (it.discipleId == discipleId) it.copy(discipleId = null, discipleName = "", discipleRealm = "") else it },
                recruitDisciples = elders.recruitDisciples.map { if (it.discipleId == discipleId) it.copy(discipleId = null, discipleName = "", discipleRealm = "") else it }
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
                    Log.e("GameEngine", "Error processing pill instantly", e)
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
                    Log.e("GameEngine", "Error processing equipment instantly", e)
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
                    Log.e("GameEngine", "Error processing manual instantly", e)
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
                    Log.e("GameEngine", "Error processing pills instantly", e)
                }
            }
            if (hasEquipment) {
                try {
                    processDiscipleItemInstantlyInternal(discipleId, "equipment")
                } catch (e: Exception) {
                    Log.e("GameEngine", "Error processing equipment instantly", e)
                }
            }
            if (hasManuals) {
                try {
                    processDiscipleItemInstantlyInternal(discipleId, "manual")
                } catch (e: Exception) {
                    Log.e("GameEngine", "Error processing manuals instantly", e)
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
        return applyTalentBaseFlatBonuses(baseDisciple, talents)
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
                cultivation = GameConfig.Realm.get(realm).cultivationBase.toDouble() * 9
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
        
        // 计算纳徒长老及亲传弟子魅力加成
        val charmBonus = calculateDiplomacyElderCharmBonus()
        
        // 生成新弟子：基础 10-30 名 + 魅力加成
        // 魅力 50 为基准，超过增加，低于减少
        // 确保最小值为 10，最大值为 30+ 加成
        val baseMin = 10
        val baseMax = 30
        val adjustedMax = (baseMax + charmBonus).coerceAtLeast(baseMin)
        val recruitCount = (baseMin..adjustedMax).random()
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
        
        memberIds.forEach { memberId ->
            val disciple = _disciples.value.find { it.id == memberId }
            if (disciple != null) {
                val updated = disciple.copy(status = DiscipleStatus.EXPLORING)
                _disciples.value = _disciples.value.map { if (it.id == memberId) updated else it }
            }
        }
        
        addEvent("探索队伍【$teamName】出发前往$dungeonName", EventType.INFO)
    }
    
    fun recallTeam(teamId: String) {
        val team = _teams.value.find { it.id == teamId } ?: return
        
        team.memberIds.forEach { memberId ->
            val disciple = _disciples.value.find { it.id == memberId }
            if (disciple != null) {
                val updated = disciple.copy(status = DiscipleStatus.IDLE)
                _disciples.value = _disciples.value.map { if (it.id == memberId) updated else it }
            }
        }
        
        _teams.value = _teams.value.filter { it.id != teamId }
        addEvent("探索队伍【${team.name}】已召回", EventType.INFO)
    }
    
    fun equipItem(discipleId: String, equipmentId: String) {
        val equip = _equipment.value.find { it.id == equipmentId } ?: return
        val disciple = _disciples.value.find { it.id == discipleId } ?: return
        
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
                updatedDisciple = disciple.copy(
                    cultivation = disciple.cultivation + cultivationGain,
                    totalCultivation = disciple.totalCultivation + cultivationGain,
                    cultivationSpeedBonus = pill.cultivationSpeed,
                    cultivationSpeedDuration = pill.duration
                )
                addEvent("${disciple.name} 服用${pill.name}，修为增加${cultivationGain}，修炼速度提升${GameUtils.formatPercent(pill.cultivationSpeed - 1)}", EventType.SUCCESS)
            }
            PillCategory.BATTLE -> {
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
        
        when (action) {
            "gift" -> {
                val giftCost = 1000L
                if (data.spiritStones >= giftCost) {
                    val newRelation = minOf(100, sect.relation + 10)
                    _gameData.value = _gameData.value.copy(
                        spiritStones = _gameData.value.spiritStones - giftCost,
                        worldMapSects = data.worldMapSects.map { 
                            if (it.id == sectId) it.copy(relation = newRelation) else it 
                        }
                    )
                    addEvent("向${sect.name}送礼，好感度+10", EventType.SUCCESS)
                } else {
                    addEvent("灵石不足，无法送礼", EventType.WARNING)
                }
            }
            "ally" -> {
                if (sect.relation >= 50) {
                    addEvent("与${sect.name}结为盟友", EventType.SUCCESS)
                    _gameData.value = data.copy(
                        worldMapSects = data.worldMapSects.map { 
                            if (it.id == sectId) it.copy(relation = 100) else it 
                        }
                    )
                } else {
                    addEvent("${sect.name}关系不足，拒绝结盟", EventType.WARNING)
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
    
    fun listItemsToMerchant(items: List<Pair<String, Int>>) {
        val data = _gameData.value
        val newPlayerListedItems = data.playerListedItems.toMutableList()
        
        items.forEach { (itemId, quantity) ->
            // 从仓库中查找物品
            val equipment = _equipment.value.find { it.id == itemId }
            val manual = _manuals.value.find { it.id == itemId }
            val pill = _pills.value.find { it.id == itemId }
            val material = _materials.value.find { it.id == itemId }
            val herb = _herbs.value.find { it.id == itemId }
            val seed = _seeds.value.find { it.id == itemId }
            
            val itemData = equipment ?: manual ?: pill ?: material ?: herb ?: seed ?: return@forEach
            
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
            
            // 创建商人物品（添加到玩家上架列表）
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
                quantity = quantity,
                description = itemData.description,
                obtainedYear = data.gameYear,
                obtainedMonth = data.gameMonth
            )
            
            newPlayerListedItems.add(merchantItem)
            
            // 从仓库中移除物品
            when (itemData) {
                is Equipment -> _equipment.value = _equipment.value.filter { it.id != itemId }
                is Manual -> _manuals.value = _manuals.value.filter { it.id != itemId }
                is Pill -> _pills.value = _pills.value.filter { it.id != itemId }
                is Material -> _materials.value = _materials.value.filter { it.id != itemId }
                is Herb -> _herbs.value = _herbs.value.filter { it.id != itemId }
                is Seed -> _seeds.value = _seeds.value.filter { it.id != itemId }
            }
        }
        
        _gameData.value = data.copy(
            playerListedItems = newPlayerListedItems
        )
        
        addEvent("成功上架${items.size}件物品", EventType.SUCCESS)
    }
    
    fun removePlayerListedItem(itemId: String) {
        val data = _gameData.value
        val item = data.playerListedItems.find { it.id == itemId } ?: return
        
        // 从玩家上架列表中移除
        val updatedItems = data.playerListedItems.filter { it.id != itemId }
        
        _gameData.value = data.copy(
            playerListedItems = updatedItems
        )
        
        addEvent("下架了${item.name}", EventType.INFO)
    }
    
    fun startTournament(realm: Int) {
        val data = _gameData.value
        val realmName = GameConfig.Realm.getName(realm)
        
        val disciples = _disciples.value.filter { it.isAlive && it.realm == realm }
        
        if (disciples.size < 2) {
            addEvent("${realmName}境界弟子人数不足，无法举办宗门大比", EventType.WARNING)
            return
        }
        
        val currentYear = data.gameYear
        if (currentYear - data.tournamentLastYear < 3) {
            addEvent("距离上次大比不足三年，无法举办", EventType.WARNING)
            return
        }
        
        val reward = data.tournamentRewards[realm]
        val participants = disciples.sortedByDescending { it.physicalAttack + it.magicAttack }
        
        participants.forEachIndexed { index, disciple ->
            when (index) {
                0 -> {
                    addEvent("${realmName}大比圆满结束！${disciple.name}获得冠军！", EventType.SUCCESS)
                    reward?.let { r ->
                        if (r.championSpiritStones > 0) {
                            _gameData.value = _gameData.value.copy(
                                spiritStones = _gameData.value.spiritStones + r.championSpiritStones
                            )
                            addEvent("${disciple.name}获得冠军奖励 ${r.championSpiritStones} 灵石", EventType.SUCCESS)
                        }
                    }
                }
                1 -> {
                    addEvent("${disciple.name}获得${realmName}大比亚军", EventType.INFO)
                    reward?.let { r ->
                        if (r.runnerUpSpiritStones > 0) {
                            _gameData.value = _gameData.value.copy(
                                spiritStones = _gameData.value.spiritStones + r.runnerUpSpiritStones
                            )
                            addEvent("${disciple.name}获得亚军奖励 ${r.runnerUpSpiritStones} 灵石", EventType.SUCCESS)
                        }
                    }
                }
                2, 3 -> {
                    addEvent("${disciple.name}进入${realmName}大比四强", EventType.INFO)
                    reward?.let { r ->
                        if (r.semifinalSpiritStones > 0) {
                            _gameData.value = _gameData.value.copy(
                                spiritStones = _gameData.value.spiritStones + r.semifinalSpiritStones
                            )
                            addEvent("${disciple.name}获得四强奖励 ${r.semifinalSpiritStones} 灵石", EventType.SUCCESS)
                        }
                    }
                }
                else -> {
                    reward?.let { r ->
                        if (r.participantSpiritStones > 0) {
                            _gameData.value = _gameData.value.copy(
                                spiritStones = _gameData.value.spiritStones + r.participantSpiritStones
                            )
                        }
                    }
                }
            }
        }
        
        _gameData.value = data.copy(
            tournamentLastYear = currentYear
        )
        
        addEvent("${realmName}大比已举办，共${participants.size}名弟子参加", EventType.SUCCESS)
    }
    
    fun toggleTournamentRealm(realm: Int, enabled: Boolean) {
        val data = _gameData.value
        val newEnabled = data.tournamentRealmEnabled.toMutableMap()
        newEnabled[realm] = enabled
        _gameData.value = data.copy(tournamentRealmEnabled = newEnabled)
    }
    
    fun toggleTournamentAutoHold(enabled: Boolean) {
        val data = _gameData.value
        _gameData.value = data.copy(tournamentAutoHold = enabled)
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
     * 计算长老和亲传弟子对建筑的加成
     * @param buildingType 建筑类型: spiritMine, herbGarden, alchemy, forge, library
     * @return Pair(产量加成百分比, 速度加成百分比, 成功率加成百分比, 成熟时间减少百分比)
     */
    private fun calculateElderAndDisciplesBonus(buildingType: String): ElderBonusResult {
        val data = _gameData.value
        val (elderId, discipleSlots) = when (buildingType) {
            "spiritMine" -> data.elderSlots.spiritMineElder to data.elderSlots.spiritMineDisciples
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
            "spiritMine" -> {
                // 灵矿场：受道德属性影响
                // 长老：以50为基础，每多1点增加2%产量，每少1点减少2%
                elder?.let { e ->
                    val moralityDiff = e.morality - 50
                    yieldBonus += moralityDiff * 0.02
                }
                // 亲传弟子：以50为基础，每多5点增加2%产量，每少5点减少2%
                disciples.forEach { d ->
                    val moralityDiff = d.morality - 50
                    yieldBonus += (moralityDiff / 5.0) * 0.02
                }
            }
            "herbGarden" -> {
                // 灵药园：受灵植属性影响
                // 长老：每多1点增加1%产量，每多5点减少1%成熟时间
                elder?.let { e ->
                    val spiritPlantingDiff = e.spiritPlanting - 50
                    yieldBonus += spiritPlantingDiff * 0.01
                    growTimeReduction += (spiritPlantingDiff / 5.0) * 0.01
                }
                // 亲传弟子：每多5点增加1%产量，每多10点减少1%成熟时间
                disciples.forEach { d ->
                    val spiritPlantingDiff = d.spiritPlanting - 50
                    yieldBonus += (spiritPlantingDiff / 5.0) * 0.01
                    growTimeReduction += (spiritPlantingDiff / 10.0) * 0.01
                }
            }
            "alchemy" -> {
                // 炼丹房：受炼丹属性影响
                // 长老：每多1点增加1%炼制速度，每多5点增加1%成功率
                elder?.let { e ->
                    val pillRefiningDiff = e.pillRefining - 50
                    speedBonus += pillRefiningDiff * 0.01
                    successBonus += (pillRefiningDiff / 5.0) * 0.01
                }
                // 亲传弟子：每多5点增加1%炼制速度，每多10点增加1%成功率
                disciples.forEach { d ->
                    val pillRefiningDiff = d.pillRefining - 50
                    speedBonus += (pillRefiningDiff / 5.0) * 0.01
                    successBonus += (pillRefiningDiff / 10.0) * 0.01
                }
            }
            "forge" -> {
                // 天工峰：受炼器属性影响
                // 长老：每多1点增加1%炼制速度，每多5点增加1%成功率
                elder?.let { e ->
                    val artifactRefiningDiff = e.artifactRefining - 50
                    speedBonus += artifactRefiningDiff * 0.01
                    successBonus += (artifactRefiningDiff / 5.0) * 0.01
                }
                // 亲传弟子：每多5点增加1%炼制速度，每多10点增加1%成功率
                disciples.forEach { d ->
                    val artifactRefiningDiff = d.artifactRefining - 50
                    speedBonus += (artifactRefiningDiff / 5.0) * 0.01
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
            yieldBonus = yieldBonus.coerceAtLeast(-0.5),
            speedBonus = speedBonus.coerceAtLeast(-0.5),
            successBonus = successBonus.coerceAtLeast(-0.3).coerceAtMost(0.3),
            growTimeReduction = growTimeReduction.coerceAtLeast(-0.3).coerceAtMost(0.5)
        )
    }

    private data class ElderBonusResult(
        val yieldBonus: Double = 0.0,
        val speedBonus: Double = 0.0,
        val successBonus: Double = 0.0,
        val growTimeReduction: Double = 0.0
    )
    
    fun assignDirectDisciple(elderSlotType: String, slotIndex: Int, discipleId: String, discipleName: String, discipleRealm: String, discipleSpiritRootColor: String = "#E0E0E0") {
        val data = _gameData.value
        
        val hasElder = when (elderSlotType) {
            "herbGarden" -> data.elderSlots.herbGardenElder != null
            "alchemy" -> data.elderSlots.alchemyElder != null
            "forge" -> data.elderSlots.forgeElder != null
            "library" -> data.elderSlots.libraryElder != null
            "spiritMine" -> data.elderSlots.spiritMineElder != null
            "recruit" -> data.elderSlots.recruitElder != null
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
            "spiritMine" -> data.elderSlots.spiritMineDisciples
            "recruit" -> data.elderSlots.recruitDisciples
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
            data.elderSlots.spiritMineDisciples,
            data.elderSlots.recruitDisciples,
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
            data.elderSlots.spiritMineElder,
            data.elderSlots.recruitElder,
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
            "spiritMine" -> data.elderSlots.copy(spiritMineDisciples = currentSlots)
            "recruit" -> data.elderSlots.copy(recruitDisciples = currentSlots)
            "preachingMasters" -> data.elderSlots.copy(preachingMasters = currentSlots)
            "lawEnforcementDisciples" -> data.elderSlots.copy(lawEnforcementDisciples = currentSlots)
            "lawEnforcementReserveDisciples" -> data.elderSlots.copy(lawEnforcementReserveDisciples = currentSlots)
            "qingyunPreachingMasters" -> data.elderSlots.copy(qingyunPreachingMasters = currentSlots)
            else -> data.elderSlots
        }
        
        _gameData.value = data.copy(elderSlots = updatedElderSlots)
        val positionName = when (elderSlotType) {
            "lawEnforcementDisciples" -> "执法弟子"
            "lawEnforcementReserveDisciples" -> "储备弟子"
            "qingyunPreachingMasters" -> "传道师"
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
            "spiritMine" -> data.elderSlots.spiritMineDisciples
            "recruit" -> data.elderSlots.recruitDisciples
            "preachingMasters" -> data.elderSlots.preachingMasters
            "lawEnforcementDisciples" -> data.elderSlots.lawEnforcementDisciples
            "lawEnforcementReserveDisciples" -> data.elderSlots.lawEnforcementReserveDisciples
            "qingyunPreachingMasters" -> data.elderSlots.qingyunPreachingMasters
            else -> emptyList()
        }.filter { it.index != slotIndex }
        
        val updatedElderSlots = when (elderSlotType) {
            "herbGarden" -> data.elderSlots.copy(herbGardenDisciples = currentSlots)
            "alchemy" -> data.elderSlots.copy(alchemyDisciples = currentSlots)
            "forge" -> data.elderSlots.copy(forgeDisciples = currentSlots)
            "library" -> data.elderSlots.copy(libraryDisciples = currentSlots)
            "spiritMine" -> data.elderSlots.copy(spiritMineDisciples = currentSlots)
            "recruit" -> data.elderSlots.copy(recruitDisciples = currentSlots)
            "preachingMasters" -> data.elderSlots.copy(preachingMasters = currentSlots)
            "lawEnforcementDisciples" -> data.elderSlots.copy(lawEnforcementDisciples = currentSlots)
            "lawEnforcementReserveDisciples" -> data.elderSlots.copy(lawEnforcementReserveDisciples = currentSlots)
            "qingyunPreachingMasters" -> data.elderSlots.copy(qingyunPreachingMasters = currentSlots)
            else -> data.elderSlots
        }
        
        _gameData.value = data.copy(elderSlots = updatedElderSlots)
    }
    
    fun assignInnerDisciple(elderSlotType: String, slotIndex: Int, discipleId: String, discipleName: String, discipleRealm: String, discipleSpiritRootColor: String = "#E0E0E0") {
        val data = _gameData.value
        
        val currentSlots = when (elderSlotType) {
            "forge" -> data.elderSlots.forgeInnerDisciples
            "alchemy" -> data.elderSlots.alchemyInnerDisciples
            "herbGarden" -> data.elderSlots.herbGardenInnerDisciples
            else -> emptyList()
        }.toMutableList()
        
        val allInnerDiscipleSlots = listOf(
            data.elderSlots.forgeInnerDisciples,
            data.elderSlots.alchemyInnerDisciples,
            data.elderSlots.herbGardenInnerDisciples
        ).flatten()
        
        if (allInnerDiscipleSlots.any { it.discipleId == discipleId }) {
            addEvent("该弟子已在其他内门弟子职位任职", EventType.WARNING)
            return
        }
        
        val allDirectDiscipleSlots = listOf(
            data.elderSlots.herbGardenDisciples,
            data.elderSlots.alchemyDisciples,
            data.elderSlots.forgeDisciples,
            data.elderSlots.libraryDisciples,
            data.elderSlots.spiritMineDisciples,
            data.elderSlots.recruitDisciples,
            data.elderSlots.preachingMasters,
            data.elderSlots.lawEnforcementDisciples,
            data.elderSlots.lawEnforcementReserveDisciples
        ).flatten()
        
        if (allDirectDiscipleSlots.any { it.discipleId == discipleId }) {
            addEvent("该弟子已在亲传弟子职位任职", EventType.WARNING)
            return
        }
        
        val allElderIds = listOf(
            data.elderSlots.herbGardenElder,
            data.elderSlots.alchemyElder,
            data.elderSlots.forgeElder,
            data.elderSlots.libraryElder,
            data.elderSlots.spiritMineElder,
            data.elderSlots.recruitElder,
            data.elderSlots.outerElder,
            data.elderSlots.preachingElder,
            data.elderSlots.lawEnforcementElder
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
            "forge" -> data.elderSlots.copy(forgeInnerDisciples = currentSlots)
            "alchemy" -> data.elderSlots.copy(alchemyInnerDisciples = currentSlots)
            "herbGarden" -> data.elderSlots.copy(herbGardenInnerDisciples = currentSlots)
            else -> data.elderSlots
        }
        
        _gameData.value = data.copy(elderSlots = updatedElderSlots)
        addEvent("$discipleName 已成为内门弟子", EventType.SUCCESS)
    }
    
    fun removeInnerDisciple(elderSlotType: String, slotIndex: Int) {
        val data = _gameData.value
        val currentSlots = when (elderSlotType) {
            "forge" -> data.elderSlots.forgeInnerDisciples
            "alchemy" -> data.elderSlots.alchemyInnerDisciples
            "herbGarden" -> data.elderSlots.herbGardenInnerDisciples
            else -> emptyList()
        }.filter { it.index != slotIndex }
        
        val updatedElderSlots = when (elderSlotType) {
            "forge" -> data.elderSlots.copy(forgeInnerDisciples = currentSlots)
            "alchemy" -> data.elderSlots.copy(alchemyInnerDisciples = currentSlots)
            "herbGarden" -> data.elderSlots.copy(herbGardenInnerDisciples = currentSlots)
            else -> data.elderSlots
        }
        
        _gameData.value = data.copy(elderSlots = updatedElderSlots)
    }
    
    fun updateSpiritMineSlots(slots: List<SpiritMineSlot>) {
        val data = _gameData.value
        _gameData.value = data.copy(spiritMineSlots = slots)
    }

    fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        _disciples.value = _disciples.value.map {
            if (it.id == discipleId) it.copy(status = status) else it
        }
    }

    fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean {
        return _gameData.value.spiritMineSlots.any { it.discipleId == discipleId }
    }
    
    fun updateMonthlySalaryEnabled(realm: Int, enabled: Boolean) {
        val data = _gameData.value
        val newEnabled = data.monthlySalaryEnabled.toMutableMap()
        newEnabled[realm] = enabled
        _gameData.value = data.copy(monthlySalaryEnabled = newEnabled)
    }
    
    fun updateTournamentRewards(realm: Int, reward: TournamentReward) {
        val data = _gameData.value
        val newRewards = data.tournamentRewards.toMutableMap()
        newRewards[realm] = reward
        _gameData.value = data.copy(tournamentRewards = newRewards)
    }
    
    fun autoHoldTournamentsIfNeeded() {
        val data = _gameData.value
        if (!data.tournamentAutoHold) return
        
        val currentYear = data.gameYear
        if (currentYear - data.tournamentLastYear < 5) return
        
        data.tournamentRealmEnabled.forEach { (realm, enabled) ->
            if (enabled) {
                val realmDisciples = _disciples.value.filter { it.isAlive && it.realm == realm }
                if (realmDisciples.size >= 2) {
                    startTournament(realm)
                    return
                }
            }
        }
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
     * 计算工作耗时，只受长老影响（新系统 - 移除弟子个人属性）
     * @param baseDuration 基础耗时
     * @param buildingId 建筑类型
     * @return 实际耗时
     */
    private fun calculateWorkDurationWithAllDisciples(baseDuration: Int, buildingId: String): Int {
        // 获取该建筑所有入驻的弟子（用于检查是否有人工作）
        val buildingSlots = _buildingSlots.value.filter { it.buildingId == buildingId }
        val assignedDiscipleIds = buildingSlots.mapNotNull { it.discipleId }
        
        // 如果没有弟子入驻，返回基础耗时
        if (assignedDiscipleIds.isEmpty()) return baseDuration

        var totalSpeedBonus = 0.0

        // 只计算长老职位加成（移除弟子个人属性、境界、天赋、功法加成）
        totalSpeedBonus += getElderPositionBonus(buildingId)

        // 总加成上限 300%
        totalSpeedBonus = totalSpeedBonus.coerceAtMost(3.0)

        // 使用新的时间计算公式
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
     * 计算纳徒长老及其亲传弟子魅力对招募弟子人数上限的加成
     * 50点为基准值，超过50点增加最大弟子数，低于50点减少最大弟子数
     * 长老每偏离50点15点影响1名，亲传弟子每偏离50点25点影响1名
     * 例如：长老魅力65 → (65-50)/15=+1人，长老魅力35 → (35-50)/15=-1人
     * @return 魅力加成的人数（可为负数）
     */
    private fun calculateDiplomacyElderCharmBonus(): Int {
        val data = _gameData.value
        val elderSlots = data.elderSlots
        
        var totalBonus = 0
        
        // 获取纳徒长老
        val elderId = elderSlots.recruitElder
        if (elderId != null) {
            val elderDisciple = _disciples.value.find { it.id == elderId }
            if (elderDisciple != null) {
                // 长老以50为基准，每偏离15点影响1名
                val elderDiff = elderDisciple.charm - 50
                totalBonus += elderDiff / 15
            }
        }
        
        // 获取纳徒亲传弟子的魅力加成
        val directDisciples = elderSlots.recruitDisciples
        directDisciples.filter { it.isActive }.forEach { slot ->
            val disciple = _disciples.value.find { it.id == slot.discipleId }
            if (disciple != null) {
                // 亲传弟子以50为基准，每偏离25点影响1名
                val discipleDiff = disciple.charm - 50
                totalBonus += discipleDiff / 25
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
     * 加成上限300%，最大可减少75%的时间
     */
    private fun calculateReducedDuration(baseDuration: Int, speedBonus: Double): Int {
        if (speedBonus <= 0) return baseDuration

        // 最大减少75%的时间（当加成达到300%时）
        val effectiveBonus = speedBonus.coerceAtMost(3.0)
        val reductionPercent = (effectiveBonus / 4.0).coerceAtMost(0.75)

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

        return bonus.coerceAtMost(0.30) // 成功率加成上限30%
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
            _alchemySlots.value = if (slotIndex < currentAlchemySlots.size) {
                currentAlchemySlots.mapIndexed { index, s -> if (index == slotIndex) newAlchemySlot else s }
            } else {
                currentAlchemySlots + newAlchemySlot
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
        
        val newSlot = AlchemySlot(
            slotIndex = slotIndex,
            recipeId = recipe.id,
            recipeName = recipe.name,
            pillName = recipe.pillName,
            pillRarity = recipe.pillRarity,
            startYear = _gameData.value.gameYear,
            startMonth = _gameData.value.gameMonth,
            duration = recipe.duration,
            status = AlchemySlotStatus.WORKING,
            successRate = recipe.successRate,
            requiredMaterials = recipe.materials
        )
        
        _alchemySlots.value = if (slotIndex < currentSlots.size) {
            currentSlots.mapIndexed { index, s -> if (index == slotIndex) newSlot else s }
        } else {
            currentSlots + newSlot
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
        val finalSuccessRate = (recipe.successRate + totalSuccessBonus).coerceAtMost(0.95)
        
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
        _equipment.value = _equipment.value.sortedByDescending { it.rarity }
        _manuals.value = _manuals.value.sortedByDescending { it.rarity }
        _pills.value = _pills.value.sortedByDescending { it.rarity }
        _materials.value = _materials.value.sortedByDescending { it.rarity }
        _herbs.value = _herbs.value.sortedByDescending { it.rarity }
        _seeds.value = _seeds.value.sortedByDescending { it.rarity }
        
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

        // 计算队伍平均境界
        val avgRealm = calculateTeamAvgRealm(teamMembers)

        // 生成AI宗门弟子（5-10人，境界为探查队伍平均境界）
        val enemyCount = Random.nextInt(5, 11) // 5-10人
        val enemies = generateScoutEnemies(enemyCount, avgRealm)

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
                    isAlive = !teamMembers.shuffled().take(teamCasualties).any { it.id == member.id }
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

        // 更新弟子状态（根据战斗结果）
        if (teamWins) {
            // 随机选择阵亡弟子
            val casualties = teamMembers.shuffled().take(teamCasualties)
            casualties.forEach { casualty ->
                val updatedDisciple = casualty.copy(isAlive = false)
                _disciples.value = _disciples.value.map { if (it.id == casualty.id) updatedDisciple else it }
            }
        } else {
            // 全员阵亡
            teamMembers.forEach { member ->
                val updatedDisciple = member.copy(isAlive = false)
                _disciples.value = _disciples.value.map { if (it.id == member.id) updatedDisciple else it }
            }
        }
    }

    /**
     * 生成探查敌人
     * 敌人数量为5-10人，境界严格根据探查队伍平均境界生成
     */
    private fun generateScoutEnemies(count: Int, avgRealm: Double): List<BattleEnemy> {
        val enemies = mutableListOf<BattleEnemy>()

        // 根据平均境界确定敌人境界（严格按平均境界，只允许±1的浮动）
        val baseRealm = avgRealm.toInt()
        // 浮动范围：70%与平均境界相同，15%低一级，15%高一级
        val realmVariations = listOf(-1, 0, 0, 0, 0, 0, 0, 1)

        repeat(count) { index ->
            val realmVariation = realmVariations.random()
            val enemyRealm = (baseRealm + realmVariation).coerceIn(0, 9) // 限制在0-9范围内（仙人以下）
            val realmName = getRealmNameByIndexForBattle(enemyRealm)

            // 根据境界计算属性
            val (hp, attack, defense, speed) = calculateEnemyStatsByRealm(enemyRealm)

            enemies.add(BattleEnemy(
                id = "scout_enemy_$index",
                name = "${realmName}弟子",
                realm = enemyRealm,
                realmName = realmName,
                hp = hp,
                maxHp = hp,
                attack = attack,
                defense = defense,
                speed = speed
            ))
        }

        return enemies
    }

    /**
     * 根据境界计算敌人属性
     * AI宗门弟子基础属性与普通弟子一致
     * 使用与普通弟子相同的计算方式：基础值 × 境界系数
     */
    private fun calculateEnemyStatsByRealm(realm: Int): Tuple4<Int, Int, Int, Int> {
        // 获取境界配置（使用与普通弟子相同的配置）
        val realmConfig = GameConfig.Realm.get(realm)
        val realmMultiplier = realmConfig.multiplier
        val layerBonus = 1.0  // AI弟子默认为第1层

        // 基础属性计算（与普通弟子一致）
        // 普通弟子：hp = (100 * spiritRootMultiplier * realmMultiplier * layerBonus)
        // AI弟子默认使用双灵根加成（1.5倍）
        val spiritRootMultiplier = 1.5

        val hp = (100 * spiritRootMultiplier * realmMultiplier * layerBonus).toInt()
        val attack = (10 * spiritRootMultiplier * realmMultiplier * layerBonus).toInt()
        val defense = (5 * spiritRootMultiplier * realmMultiplier * layerBonus).toInt()
        val speed = (10 * spiritRootMultiplier * realmMultiplier * layerBonus).toInt()

        // 添加小幅随机波动（±5%）
        val variance = 0.95 + Random.nextDouble() * 0.1

        return Tuple4(
            (hp * variance).toInt(),
            (attack * variance).toInt(),
            (defense * variance).toInt(),
            (speed * variance).toInt()
        )
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

            val disciples = generateAISectDisciples(targetSect.level)

            updateSectInfo(targetSect.id, disciples, expiryYear, expiryMonth)

            addEvent("探查成功！${targetSect.name}的弟子信息已获取，有效期至${expiryYear}年${expiryMonth}月", EventType.SUCCESS)
        }
    }

    /**
     * 生成AI宗门弟子分布
     */
    private fun generateAISectDisciples(sectLevel: Int): Map<Int, Int> {
        val disciples = mutableMapOf<Int, Int>()

        // 根据宗门等级确定弟子数量和境界分布
        val totalDisciples = when (sectLevel) {
            0 -> Random.nextInt(15, 26) // 小型宗门 15-25人
            1 -> Random.nextInt(25, 41) // 中型宗门 25-40人
            2 -> Random.nextInt(40, 61) // 大型宗门 40-60人
            3 -> Random.nextInt(60, 101) // 顶级宗门 60-100人
            else -> Random.nextInt(15, 26)
        }

        // 根据宗门等级确定最高境界
        val maxRealm = when (sectLevel) {
            0 -> 5 // 小型宗门最高元婴
            1 -> 3 // 中型宗门最高炼虚
            2 -> 1 // 大型宗门最高大乘
            3 -> 0 // 顶级宗门最高渡劫/仙人
            else -> 5
        }

        // 分配弟子到各境界
        var remaining = totalDisciples

        // 高境界弟子较少
        for (realm in 0..maxRealm) {
            if (remaining <= 0) break
            val count = when (realm) {
                0 -> if (sectLevel == 3) Random.nextInt(1, 4) else 0 // 渡劫期
                1 -> if (sectLevel >= 2) Random.nextInt(1, 4) else 0 // 大乘期
                2 -> if (sectLevel >= 2) Random.nextInt(2, 6) else 0 // 合体期
                3 -> if (sectLevel >= 1) Random.nextInt(3, 8) else 0 // 炼虚期
                4 -> Random.nextInt(5, 11) // 化神期
                5 -> Random.nextInt(8, 16) // 元婴期
                else -> 0
            }
            if (count > 0) {
                disciples[realm] = count.coerceAtMost(remaining)
                remaining -= count
            }
        }

        // 中等境界
        val midRealmCount = (remaining * 0.4).toInt()
        if (midRealmCount > 0) {
            disciples[6] = midRealmCount // 金丹期
            remaining -= midRealmCount
        }

        val lowMidCount = (remaining * 0.5).toInt()
        if (lowMidCount > 0) {
            disciples[7] = lowMidCount // 筑基期
            remaining -= lowMidCount
        }

        // 低境界占大部分
        if (remaining > 0) {
            disciples[9] = remaining // 炼气期
        }

        // 仙人境界（顶级宗门有几率有）
        if (sectLevel == 3 && Random.nextDouble() < 0.3) {
            disciples[0] = Random.nextInt(1, 3)
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

        // 1. 移除过期的洞府
        val activeCaves = data.cultivatorCaves.filter { cave ->
            !cave.isExpired(year, month) && cave.status != CaveStatus.EXPLORED
        }

        // 2. 生成新的洞府（每月最多2座）
        val newCaves = CaveGenerator.generateCaves(
            existingSects = data.worldMapSects,
            connectionEdges = emptyList(),
            currentYear = year,
            currentMonth = month,
            existingCaves = activeCaves,
            maxNewCaves = 2
        )

        // 3. 为每个洞府生成AI队伍（最多3个）
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

        // 4. 处理玩家探索队伍状态转换
        val updatedExplorationTeams = data.caveExplorationTeams.map { team ->
            if (team.status == CaveExplorationStatus.TRAVELING) {
                val elapsed = (year - team.startYear) * 12 + (month - team.startMonth)
                if (elapsed >= team.duration) {
                    team.copy(status = CaveExplorationStatus.EXPLORING)
                } else {
                    team
                }
            } else {
                team
            }
        }

        // 5. 处理到达洞府的探索队伍（自动开始战斗）
        val teamsToComplete = updatedExplorationTeams.filter { 
            it.status == CaveExplorationStatus.EXPLORING 
        }
        
        var finalCaves = activeCaves.toMutableList()
        var finalAITeams = allAITeams.toList()
        var finalExplorationTeams = updatedExplorationTeams.toMutableList()
        val teamsWithMissingCave = mutableListOf<CaveExplorationTeam>()

        teamsToComplete.forEach { team ->
            val cave = finalCaves.find { it.id == team.caveId }
            if (cave == null) {
                teamsWithMissingCave.add(team)
                return@forEach
            }
            val result = executeCaveExploration(team, cave, finalAITeams)
            
            finalCaves = result.first.toMutableList()
            finalAITeams = result.second
            if (result.third) {
                finalExplorationTeams.removeAll { it.id == team.id }
            }
        }

        teamsWithMissingCave.forEach { team ->
            addEvent("探索队伍前往的${team.caveName}已消失，队伍返回宗门", EventType.WARNING)
            team.memberIds.forEach { memberId ->
                val disciple = _disciples.value.find { it.id == memberId }
                if (disciple != null) {
                    _disciples.value = _disciples.value.map { 
                        if (it.id == memberId) it.copy(status = DiscipleStatus.IDLE) else it 
                    }
                }
            }
            finalExplorationTeams.removeAll { it.id == team.id }
        }

        _gameData.value = data.copy(
            cultivatorCaves = finalCaves + newCaves,
            aiCaveTeams = finalAITeams,
            caveExplorationTeams = finalExplorationTeams
        )

        // 通知新洞府生成
        newCaves.forEach { cave ->
            addEvent("发现${cave.ownerRealmName}洞府：${cave.name}", EventType.SUCCESS)
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
        val aliveDisciples = _disciples.value.filter { it.id in team.memberIds && it.isAlive }
        val equipmentMap = _equipment.value.associateBy { it.id }
        
        var aiTeams = currentAITeams.toMutableList()
        var allVictories = true

        // 1. 与AI队伍战斗
        val caveAITeams = aiTeams.filter { 
            it.caveId == cave.id && it.status == AITeamStatus.EXPLORING 
        }
        
        for (aiTeam in caveAITeams) {
            val battle = CaveExplorationSystem.createAIBattle(aliveDisciples, equipmentMap, aiTeam)
            val battleResult = BattleSystem.executeBattle(battle)
            
            // 战斗后清除战斗丹效果
            clearBattlePillEffects(aliveDisciples)

            if (battleResult.victory) {
                applyBattleVictoryBonuses(
                    memberIds = battleResult.log.teamMembers.filter { it.isAlive }.map { it.id },
                    addSoulPower = false
                )
                aiTeams = aiTeams.map { 
                    if (it.id == aiTeam.id) it.copy(status = AITeamStatus.DEFEATED) else it 
                }.toMutableList()
                
                val sect = _gameData.value.worldMapSects.find { it.id == aiTeam.sectId }
                if (sect != null) {
                    val updatedSects = _gameData.value.worldMapSects.map { s ->
                        if (s.id == aiTeam.sectId) s else s
                    }
                    _gameData.value = _gameData.value.copy(worldMapSects = updatedSects)
                }
                addEvent("击败${aiTeam.sectName}的探索队伍", EventType.SUCCESS)
            } else {
                allVictories = false
                addEvent("被${aiTeam.sectName}的探索队伍击败", EventType.DANGER)
                break
            }
        }

        if (!allVictories) {
            team.memberIds.forEach { memberId ->
                val disciple = _disciples.value.find { it.id == memberId }
                if (disciple != null) {
                    _disciples.value = _disciples.value.map { 
                        if (it.id == memberId) it.copy(status = DiscipleStatus.IDLE) else it 
                    }
                }
            }
            val updatedCaves = _gameData.value.cultivatorCaves.map { 
                if (it.id == cave.id) it.copy(status = CaveStatus.AVAILABLE) else it 
            }
            return Triple(updatedCaves, aiTeams.toList(), true)
        }

        // 2. 与守护兽战斗
        val guardianBattle = CaveExplorationSystem.createGuardianBattle(aliveDisciples, equipmentMap, cave)
        val guardianResult = BattleSystem.executeBattle(guardianBattle)
        
        // 战斗后清除战斗丹效果
        clearBattlePillEffects(aliveDisciples)

        if (!guardianResult.victory) {
            addEvent("被${cave.name}的守护兽击败", EventType.DANGER)
            team.memberIds.forEach { memberId ->
                val disciple = _disciples.value.find { it.id == memberId }
                if (disciple != null) {
                    _disciples.value = _disciples.value.map { 
                        if (it.id == memberId) it.copy(status = DiscipleStatus.IDLE) else it 
                    }
                }
            }
            val updatedCaves = _gameData.value.cultivatorCaves.map { 
                if (it.id == cave.id) it.copy(status = CaveStatus.AVAILABLE) else it 
            }
            return Triple(updatedCaves, aiTeams.toList(), true)
        }

        applyBattleVictoryBonuses(
            memberIds = guardianResult.log.teamMembers.filter { it.isAlive }.map { it.id },
            addSoulPower = false
        )

        // 3. 生成并发放奖励
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
                            description = template.description
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
                            battleCount = recipe.battleCount
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
                            minRealm = template.minRealm
                        )
                        // 检查是否已存在相同 id 的功法，避免重复
                        if (_manuals.value.none { it.id == manual.id }) {
                            _manuals.value = _manuals.value + manual
                        }
                    }
                }
            }
        }

        // 4. 移除洞府和相关的AI队伍
        val finalCaves = _gameData.value.cultivatorCaves.filter { it.id != cave.id }
        val finalAITeams = aiTeams.filter { it.caveId != cave.id }

        // 5. 战斗胜利后给存活的弟子增加神魂
        val aliveMembers = guardianResult.log.teamMembers.filter { it.isAlive }
        aliveMembers.forEach { member ->
            val disciple = _disciples.value.find { it.id == member.id }
            if (disciple != null) {
                _disciples.value = _disciples.value.map {
                    if (it.id == member.id) it.copy(soulPower = it.soulPower + 1) else it
                }
            }
        }

        // 6. 恢复弟子状态
        team.memberIds.forEach { memberId ->
            val disciple = _disciples.value.find { it.id == memberId }
            if (disciple != null) {
                _disciples.value = _disciples.value.map { 
                    if (it.id == memberId) it.copy(status = DiscipleStatus.IDLE) else it 
                }
            }
        }

        addEvent("成功探索${cave.name}，获得丰厚奖励！存活弟子神魂+1", EventType.SUCCESS)
        return Triple(finalCaves, finalAITeams, true)
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

        val team = CaveExplorationSystem.createCaveExplorationTeam(
            cave = cave,
            disciples = selectedDisciples,
            currentYear = data.gameYear,
            currentMonth = data.gameMonth
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

        addEvent("派遣队伍前往${cave.name}探索", EventType.INFO)
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

    // ==================== 军团系统 ====================

    /**
     * 创建军团
     * @param name 军团名称
     * @param memberIds 成员ID列表（必须10人）
     * @return 是否成功
     */
    fun createWarTeam(name: String, memberIds: List<String>): Boolean {
        if (memberIds.size != 10) {
            addEvent("军团必须由10名弟子组成", EventType.WARNING)
            return false
        }

        val data = _gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        if (playerSect == null) {
            addEvent("找不到玩家宗门", EventType.WARNING)
            return false
        }

        // 检查玩家宗门是否已有驻扎军团
        val existingTeam = _warTeams.value.find { it.stationedSectId == playerSect.id }
        if (existingTeam != null) {
            addEvent("玩家宗门已有驻扎军团，无法创建新军团", EventType.WARNING)
            return false
        }

        // 验证弟子状态
        val disciples = _disciples.value.filter { it.id in memberIds }
        if (disciples.size != 10) {
            addEvent("部分弟子不存在", EventType.WARNING)
            return false
        }

        for (disciple in disciples) {
            if (!disciple.isAlive) {
                addEvent("${disciple.name}已死亡，无法加入军团", EventType.WARNING)
                return false
            }
            if (disciple.status != DiscipleStatus.IDLE) {
                addEvent("${disciple.name}当前状态为${disciple.status.displayName}，无法加入军团", EventType.WARNING)
                return false
            }
        }

        // 更新弟子状态为WAR
        _disciples.value = _disciples.value.map {
            if (it.id in memberIds) it.copy(status = DiscipleStatus.BATTLE) else it
        }

        // 创建军团
        val team = WarTeam(
            id = UUID.randomUUID().toString(),
            name = name,
            memberIds = memberIds,
            status = WarTeamStatus.IDLE,
            stationedSectId = playerSect.id,
            stationedSectName = playerSect.name
        )

        updateWarTeamsSync(_warTeams.value + team)

        addEvent("军团【$name】创建成功，驻扎于${playerSect.name}", EventType.SUCCESS)
        return true
    }

    /**
     * 从存档加载军团数据
     */
    fun loadWarTeam(team: WarTeam) {
        if (_warTeams.value.none { it.id == team.id }) {
            updateWarTeamsSync(_warTeams.value + team)
        }
    }

    /**
     * 解散军团
     */
    fun disbandWarTeam(teamId: String): Boolean {
        val team = _warTeams.value.find { it.id == teamId } ?: return false
        val data = _gameData.value

        val deadMemberIds = team.deadMemberIds.toSet()
        val aliveMemberIds = team.memberIds.filter { it !in deadMemberIds }

        _disciples.value = _disciples.value.map {
            if (it.id in aliveMemberIds) it.copy(status = DiscipleStatus.IDLE) else it
        }

        if (deadMemberIds.isNotEmpty()) {
            _equipment.value = _equipment.value.filter { equip ->
                equip.ownerId !in deadMemberIds
            }
            _manuals.value = _manuals.value.filter { manual ->
                manual.ownerId !in deadMemberIds
            }
            addEvent("军团【${team.name}】中${deadMemberIds.size}名阵亡弟子的装备与功法已随弟子消逝", EventType.INFO)
        }

        if (team.stationedSectId != null) {
            val updatedSects = data.worldMapSects.map { sect ->
                if (sect.occupierTeamId == team.id) {
                    sect.copy(
                        isOccupied = false,
                        occupierTeamId = null,
                        occupierTeamName = "",
                        mineSlots = listOf(MineSlot(0), MineSlot(1), MineSlot(2))
                    )
                } else sect
            }
            _gameData.value = data.copy(worldMapSects = updatedSects)
        }

        updateWarTeamsSync(_warTeams.value.filter { it.id != teamId })

        addEvent("军团【${team.name}】已解散", EventType.WARNING)
        return true
    }

    /**
     * 移动军团到己方宗门
     */
    fun moveWarTeam(teamId: String, targetSectId: String): Boolean {
        val team = _warTeams.value.find { it.id == teamId } ?: return false
        if (!team.canOperate) {
            addEvent("军团当前无法移动", EventType.WARNING)
            return false
        }

        val data = _gameData.value
        val targetSect = data.worldMapSects.find { it.id == targetSectId }
        if (targetSect == null || !targetSect.isOwned) {
            addEvent("目标宗门不可用", EventType.WARNING)
            return false
        }

        // 检查目标宗门是否已有其他军团驻扎
        val existingTeam = _warTeams.value.find { 
            it.stationedSectId == targetSectId && it.id != teamId 
        }
        if (existingTeam != null) {
            addEvent("${targetSect.name}已有军团驻扎", EventType.WARNING)
            return false
        }

        // 计算路径
        val path = calculatePath(team.stationedSectId ?: "", targetSectId)
        if (path.isEmpty()) {
            addEvent("无法找到到达${targetSect.name}的路径", EventType.WARNING)
            return false
        }

        // 开始行军
        val currentTime = System.currentTimeMillis()
        val travelTimePerNode = 5000L // 5秒/节点
        val totalTravelTime = (path.size - 1) * travelTimePerNode

        val updatedTeam = team.copy(
            status = WarTeamStatus.TRAVELING,
            targetSectId = targetSectId,
            targetSectName = targetSect.name,
            pathSectIds = path,
            currentPathIndex = 0,
            travelStartTime = currentTime,
            travelEndTime = currentTime + totalTravelTime
        )

        updateWarTeamsSync(_warTeams.value.map { if (it.id == teamId) updatedTeam else it })

        addEvent("军团【${team.name}】开始行军前往${targetSect.name}", EventType.INFO)
        return true
    }

    /**
     * 进攻宗门
     */
    fun attackSect(teamId: String, targetSectId: String): Boolean {
        val team = _warTeams.value.find { it.id == teamId } ?: return false
        if (!team.canOperate) {
            addEvent("军团当前无法进攻", EventType.WARNING)
            return false
        }

        val data = _gameData.value
        val targetSect = data.worldMapSects.find { it.id == targetSectId }
        if (targetSect == null || targetSect.isOwned) {
            addEvent("目标宗门不可进攻", EventType.WARNING)
            return false
        }

        // 检查是否攻击盟友
        if (targetSect.allianceId != null) {
            val alliance = data.alliances.find { it.id == targetSect.allianceId }
            if (alliance != null && alliance.sectIds.contains("player")) {
                handleAttackAlly(targetSectId)
            }
        }

        // 检查是否相邻
        val stationedSect = data.worldMapSects.find { it.id == team.stationedSectId }
        if (stationedSect == null || !(stationedSect.connectedSectIds ?: emptyList()).contains(targetSectId)) {
            addEvent("只能进攻相邻的敌方宗门", EventType.WARNING)
            return false
        }

        // 计算路径
        val path = calculatePath(team.stationedSectId ?: "", targetSectId)
        if (path.isEmpty()) {
            addEvent("无法找到进攻路径", EventType.WARNING)
            return false
        }

        // 开始行军
        val currentTime = System.currentTimeMillis()
        val travelTimePerNode = 5000L
        val totalTravelTime = (path.size - 1) * travelTimePerNode

        val updatedTeam = team.copy(
            status = WarTeamStatus.TRAVELING,
            targetSectId = targetSectId,
            targetSectName = targetSect.name,
            pathSectIds = path,
            currentPathIndex = 0,
            travelStartTime = currentTime,
            travelEndTime = currentTime + totalTravelTime
        )

        updateWarTeamsSync(_warTeams.value.map { if (it.id == teamId) updatedTeam else it })

        addEvent("军团【${team.name}】开始进攻${targetSect.name}", EventType.INFO)
        return true
    }

    /**
     * 召回军团到玩家宗门
     */
    fun recallWarTeam(teamId: String): Boolean {
        val team = _warTeams.value.find { it.id == teamId } ?: return false
        if (team.status == WarTeamStatus.RETURNING) {
            addEvent("军团正在返回中", EventType.WARNING)
            return false
        }

        val data = _gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        if (playerSect == null) return false

        // 计算返回路径
        val path = calculatePath(team.stationedSectId ?: "", playerSect.id)
        if (path.isEmpty()) {
            // 直接传送回玩家宗门
            val updatedTeam = team.copy(
                status = WarTeamStatus.IDLE,
                stationedSectId = playerSect.id,
                stationedSectName = playerSect.name,
                targetSectId = null,
                targetSectName = "",
                pathSectIds = emptyList()
            )
            updateWarTeamsSync(_warTeams.value.map { if (it.id == teamId) updatedTeam else it })
            addEvent("军团【${team.name}】已返回${playerSect.name}", EventType.INFO)
            return true
        }

        val currentTime = System.currentTimeMillis()
        val travelTimePerNode = 5000L
        val totalTravelTime = (path.size - 1) * travelTimePerNode

        val updatedTeam = team.copy(
            status = WarTeamStatus.RETURNING,
            targetSectId = playerSect.id,
            targetSectName = playerSect.name,
            pathSectIds = path,
            currentPathIndex = 0,
            travelStartTime = currentTime,
            travelEndTime = currentTime + totalTravelTime
        )

        updateWarTeamsSync(_warTeams.value.map { if (it.id == teamId) updatedTeam else it })

        addEvent("军团【${team.name}】开始返回${playerSect.name}", EventType.INFO)
        return true
    }

    /**
     * 处理军团月度事件（维护费、行军进度等）
     */
    private fun processWarTeamsMonthly(year: Int, month: Int) {
        val data = _gameData.value
        var totalCost = 0L
        val teamsToRemove = mutableListOf<String>()

        // 处理每个军团
        _warTeams.value.forEach { team ->
            totalCost += team.monthlyCost

            // 检查灵石是否充足
            if (data.spiritStones < totalCost) {
                addEvent("灵石不足，军团【${team.name}】解散！", EventType.DANGER)
                teamsToRemove.add(team.id)
            }
        }

        // 解散灵石不足的军团
        teamsToRemove.forEach { teamId ->
            disbandWarTeam(teamId)
        }

        // 扣除维护费
        if (totalCost > 0 && teamsToRemove.isEmpty()) {
            _gameData.value = _gameData.value.copy(
                spiritStones = _gameData.value.spiritStones - totalCost
            )
            addEvent("军团月度维护消耗：$totalCost 灵石", EventType.INFO)
        }

        // 结算占领地灵矿产出
        processOccupiedMineOutput()
    }

    /**
     * 处理行军进度（每秒调用）
     */
    fun processWarTeamTravel() {
        val currentTime = System.currentTimeMillis()
        val currentTeams = _warTeams.value.toMutableList()
        val teamsToUpdate = mutableListOf<WarTeam>()
        val eventsToAdd = mutableListOf<Pair<String, EventType>>()
        var hasUpdates = false

        currentTeams.forEachIndexed { index, team ->
            if (team.status == WarTeamStatus.TRAVELING || team.status == WarTeamStatus.RETURNING) {
                if (currentTime >= team.travelEndTime) {
                    if (team.status == WarTeamStatus.TRAVELING) {
                        executeWarBattle(team)
                        hasUpdates = true
                    } else {
                        val data = _gameData.value
                        val playerSect = data.worldMapSects.find { it.isPlayerSect }
                        if (playerSect != null) {
                            val updatedTeam = team.copy(
                                status = WarTeamStatus.IDLE,
                                stationedSectId = playerSect.id,
                                stationedSectName = playerSect.name,
                                targetSectId = null,
                                targetSectName = "",
                                pathSectIds = emptyList()
                            )
                            teamsToUpdate.add(updatedTeam)
                            eventsToAdd.add("军团【${team.name}】已返回${playerSect.name}" to EventType.INFO)
                            hasUpdates = true
                        }
                    }
                } else {
                    val progress = ((currentTime - team.travelStartTime).toFloat() /
                        (team.travelEndTime - team.travelStartTime)).coerceIn(0f, 1f)
                    val pathSize = team.pathSectIds.size
                    val newIndex = if (pathSize > 1) {
                        (progress * (pathSize - 1)).toInt()
                    } else {
                        0
                    }
                    if (newIndex != team.currentPathIndex) {
                        val updatedTeam = team.copy(currentPathIndex = newIndex)
                        teamsToUpdate.add(updatedTeam)
                        hasUpdates = true
                    } else {
                        teamsToUpdate.add(team)
                    }
                }
            } else {
                teamsToUpdate.add(team)
            }
        }

        if (hasUpdates && teamsToUpdate.isNotEmpty()) {
            val newTeams = currentTeams.map { existingTeam ->
                teamsToUpdate.find { it.id == existingTeam.id } ?: existingTeam
            }
            _warTeams.value = newTeams
            _gameData.value = _gameData.value.copy(warTeams = newTeams)
            eventsToAdd.forEach { (message, type) ->
                addEvent(message, type)
            }
        }
    }

    /**
     * 执行战斗
     */
    private fun executeWarBattle(team: WarTeam) {
        val data = _gameData.value
        val targetSect = data.worldMapSects.find { it.id == team.targetSectId }
        if (targetSect == null) {
            // 目标不存在，返回
            returnTeamToStation(team)
            return
        }

        // 获取进攻方弟子
        val attackers = _disciples.value.filter { it.id in team.memberIds && it.isAlive }
        if (attackers.size < 10) {
            addEvent("军团【${team.name}】兵力不足，进攻失败", EventType.WARNING)
            returnTeamToStation(team)
            return
        }

        // 生成防守方
        var defenders: List<DefenseDisciple> = if (targetSect.isOccupied && targetSect.occupierTeamId != null) {
            // 已占领宗门：使用驻扎军团
            val defenseTeam = _warTeams.value.find { it.id == targetSect.occupierTeamId }
            if (defenseTeam != null) {
                // 将弟子转换为DefenseDisciple
                _disciples.value.filter { it.id in defenseTeam.memberIds && it.isAlive }.map { disciple ->
                    val discipleProficiencies = data.manualProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
                    val stats = disciple.getFinalStats(
                        _equipment.value.associateBy { it.id },
                        _manuals.value.associateBy { it.id },
                        discipleProficiencies
                    )
                    DefenseDisciple(
                        id = disciple.id,
                        name = disciple.name,
                        realm = disciple.realm,
                        realmLayer = disciple.realmLayer,
                        realmName = GameConfig.Realm.getName(disciple.realm),
                        maxHp = stats.maxHp,
                        maxMp = stats.maxMp,
                        physicalAttack = stats.physicalAttack,
                        magicAttack = stats.magicAttack,
                        physicalDefense = stats.physicalDefense,
                        magicDefense = stats.magicDefense,
                        speed = stats.speed,
                        critRate = stats.critRate
                    )
                }
            } else {
                generateAIDefenseTeam(targetSect)
            }
        } else {
            // 未占领宗门：生成AI防守队伍
            generateAIDefenseTeam(targetSect)
        }
        
        // AI 宗门盟友支援逻辑
        if (!targetSect.isPlayerSect && targetSect.allianceId != null) {
            val allySupport = generateAllySupportForAISect(targetSect, data)
            if (allySupport.isNotEmpty()) {
                defenders = defenders + allySupport
                addEvent("${targetSect.name}的盟友派出${allySupport.size}名弟子支援防守", EventType.INFO)
            }
            
            // 实时更新共同敌人好感度
            updateCommonEnemyFavorRealtime(targetSect.id, attackerIsPlayer = true)
        }

        // 执行战斗
        val result = SectWarBattleSystem.executeBattle(
            attackers = attackers,
            defenders = defenders,
            equipmentMap = _equipment.value.associateBy { it.id },
            manuals = _manuals.value.associateBy { it.id },
            manualProficiencies = data.manualProficiencies.mapValues { (_, list) -> 
                list.associateBy { it.manualId } 
            }
        )
        
        // 战斗后清除战斗丹效果
        clearBattlePillEffects(attackers)

        if (result.victory) {
            val deadIds = result.playerCasualties
                .filter { it.injuryType == InjuryType.DEAD }
                .map { it.discipleId }
                .toSet()
            applyBattleVictoryBonuses(
                memberIds = team.memberIds.filter { it !in deadIds },
                addSoulPower = false
            )
            // 胜利：占领宗门
            occupySect(team, targetSect, result)
        } else {
            addEvent("军团【${team.name}】进攻${targetSect.name}失败，正在返回...", EventType.WARNING)
            handleDefeatCasualties(result.playerCasualties, team.id)
            returnTeamToStation(team)
        }
    }
    
    /**
     * 为 AI 宗门生成盟友支援队伍
     * 根据好感度决定是否派遣支援
     */
    private fun generateAllySupportForAISect(targetSect: WorldSect, data: GameData): List<DefenseDisciple> {
        val alliance = data.alliances.find { it.id == targetSect.allianceId } ?: return emptyList()
        
        // 找到盟友宗门（排除目标宗门自己和玩家宗门）
        val allySectId = alliance.sectIds.find { it != targetSect.id && it != "player" && it != "player_sect" } 
            ?: return emptyList()
        
        val allySect = data.worldMapSects.find { it.id == allySectId } ?: return emptyList()
        
        // 获取盟友间的好感度
        val relation = getSectRelation(targetSect.id, allySectId, data.sectRelations)
        val favor = relation?.favor ?: 50
        
        // 好感度越高，派遣支援的概率越高
        // 好感度 50-100 对应概率 0%-50%
        val supportProbability = (favor - 50).coerceIn(0, 50) / 100.0
        
        if (Random.nextDouble() >= supportProbability) {
            return emptyList()
        }
        
        // 计算支援队伍规模
        // 好感度越高，支援规模越大
        val supportSize = when {
            favor >= 90 -> (15..25).random()
            favor >= 80 -> (10..20).random()
            favor >= 70 -> (5..15).random()
            favor >= 60 -> (3..10).random()
            else -> (1..5).random()
        }
        
        // 生成支援弟子
        return generateAllySupportDisciples(allySect, supportSize)
    }
    
    /**
     * 生成盟友支援弟子
     */
    private fun generateAllySupportDisciples(allySect: WorldSect, size: Int): List<DefenseDisciple> {
        val disciples = mutableListOf<DefenseDisciple>()
        val maxRealm = allySect.maxRealm
        
        repeat(size) {
            // 境界分布：最高境界较少，低境界较多
            // maxRealm 值越小境界越高（0=仙人，9=炼气）
            // 支援弟子境界不能高于宗门最高境界
            val realm = when {
                Random.nextDouble() < 0.1 -> maxRealm  // 10% 最高境界
                Random.nextDouble() < 0.3 -> (maxRealm + 1).coerceAtMost(9)  // 20% 次高境界
                Random.nextDouble() < 0.6 -> (maxRealm + 2).coerceAtMost(9)  // 30% 再次境界
                else -> {
                    val minRealm = (maxRealm + 3).coerceAtMost(9)
                    if (minRealm <= 9) (minRealm..9).random() else 9
                }
            }
            
            val realmName = GameConfig.Realm.getName(realm)
            val baseHp = 500 + (9 - realm) * 500
            val baseAttack = 50 + (9 - realm) * 50
            val baseDefense = 30 + (9 - realm) * 30
            
            disciples.add(DefenseDisciple(
                id = "ally_support_${allySect.id}_${System.nanoTime()}_$it",
                name = "${allySect.name}弟子${it + 1}",
                realm = realm,
                realmLayer = (1..9).random(),
                realmName = realmName,
                maxHp = baseHp,
                hp = baseHp,
                maxMp = baseHp / 2,
                physicalAttack = baseAttack,
                magicAttack = (baseAttack * 0.8).toInt(),
                physicalDefense = baseDefense,
                magicDefense = (baseDefense * 0.8).toInt(),
                speed = 80 + (9 - realm) * 10,
                critRate = 0.05 + (9 - realm) * 0.01
            ))
        }
        
        return disciples
    }

    /**
     * 占领宗门
     */
    private fun occupySect(team: WarTeam, sect: WorldSect, result: WarBattleResult) {
        val data = _gameData.value

        // 处理原防守军团（如果存在）
        if (sect.isOccupied && sect.occupierTeamId != null) {
            val defeatedTeam = _warTeams.value.find { it.id == sect.occupierTeamId }
            if (defeatedTeam != null) {
                addEvent("军团【${team.name}】击败了${defeatedTeam.name}", EventType.SUCCESS)
                // 被击败的军团返回玩家宗门
                val playerSect = data.worldMapSects.find { it.isPlayerSect }
                if (playerSect != null) {
                    _warTeams.value = _warTeams.value.map {
                        if (it.id == defeatedTeam.id) {
                            it.copy(
                                status = WarTeamStatus.IDLE,
                                stationedSectId = playerSect.id,
                                stationedSectName = playerSect.name
                            )
                        } else it
                    }
                }
            }
        }

        // 更新宗门状态
        val updatedSects = data.worldMapSects.map { s ->
            if (s.id == sect.id) {
                s.copy(
                    isOccupied = true,
                    occupierTeamId = team.id,
                    occupierTeamName = team.name,
                    mineSlots = listOf(MineSlot(0), MineSlot(1), MineSlot(2)),
                    occupationTime = System.currentTimeMillis()
                )
            } else s
        }

        // 更新军团状态
        val updatedTeam = team.copy(
            status = WarTeamStatus.IDLE,
            stationedSectId = sect.id,
            stationedSectName = sect.name,
            targetSectId = null,
            targetSectName = "",
            pathSectIds = emptyList()
        )

        val newWarTeams = _warTeams.value.map { if (it.id == team.id) updatedTeam else it }
        _gameData.value = data.copy(worldMapSects = updatedSects)
        updateWarTeamsSync(newWarTeams)

        handleDefeatCasualties(result.playerCasualties, team.id)

        addEvent("军团【${team.name}】成功占领${sect.name}！", EventType.SUCCESS)
    }

    /**
     * 处理伤亡
     */
    private fun handleDefeatCasualties(casualties: List<Casualty>, teamId: String? = null) {
        val deadDiscipleIds = mutableListOf<String>()
        
        casualties.forEach { casualty ->
            val disciple = _disciples.value.find { it.id == casualty.discipleId }
            if (disciple != null) {
                when (casualty.injuryType) {
                    InjuryType.DEAD -> {
                        deadDiscipleIds.add(casualty.discipleId)
                        _disciples.value = _disciples.value.filter { it.id != casualty.discipleId }
                        addEvent("${casualty.name}在战斗中阵亡", EventType.DANGER)
                    }
                    InjuryType.SEVERE -> {
                        addEvent("${casualty.name}重伤，需要${casualty.recoveryMonths}个月恢复", EventType.WARNING)
                    }
                    InjuryType.MODERATE -> {
                        addEvent("${casualty.name}中伤，需要${casualty.recoveryMonths}个月恢复", EventType.WARNING)
                    }
                    InjuryType.LIGHT -> {
                        addEvent("${casualty.name}轻伤", EventType.INFO)
                    }
                    else -> {}
                }
            }
        }
        
        if (deadDiscipleIds.isNotEmpty() && teamId != null) {
            val team = _warTeams.value.find { it.id == teamId }
            if (team != null) {
                val updatedTeam = team.copy(
                    deadMemberIds = team.deadMemberIds + deadDiscipleIds
                )
                updateWarTeamsSync(_warTeams.value.map { if (it.id == teamId) updatedTeam else it })
            }
        }
    }

    /**
     * 军团返回驻扎地
     */
    private fun returnTeamToStation(team: WarTeam) {
        val data = _gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        if (playerSect != null) {
            val updatedTeam = team.copy(
                status = WarTeamStatus.IDLE,
                stationedSectId = playerSect.id,
                stationedSectName = playerSect.name,
                targetSectId = null,
                targetSectName = "",
                pathSectIds = emptyList()
            )
            updateWarTeamsSync(_warTeams.value.map { if (it.id == team.id) updatedTeam else it })
        }
    }

    /**
     * 生成AI防守队伍
     */
    private fun generateAIDefenseTeam(sect: WorldSect): List<DefenseDisciple> {
        return SectWarBattleSystem.generateAIDefenseTeam(sect)
    }

    /**
     * 计算行军路径（BFS）
     */
    private fun calculatePath(fromSectId: String, toSectId: String): List<String> {
        if (fromSectId.isEmpty() || toSectId.isEmpty()) return emptyList()
        if (fromSectId == toSectId) return listOf(fromSectId)

        val data = _gameData.value
        val sectMap = data.worldMapSects.associateBy { it.id }
        
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, List<String>>>()
        queue.add(fromSectId to listOf(fromSectId))
        visited.add(fromSectId)

        while (queue.isNotEmpty()) {
            val (current, path) = queue.removeFirst()
            val sect = sectMap[current] ?: continue

            for (neighborId in (sect.connectedSectIds ?: emptyList())) {
                if (neighborId == toSectId) {
                    return path + neighborId
                }
                if (neighborId !in visited) {
                    visited.add(neighborId)
                    queue.add(neighborId to path + neighborId)
                }
            }
        }

        return emptyList()
    }

    /**
     * 处理占领地灵矿产出
     */
    private fun processOccupiedMineOutput() {
        val data = _gameData.value
        var totalOutput = 0L

        data.worldMapSects.filter { it.isOccupied }.forEach { sect ->
            val activeSlots = sect.mineSlots.count { it.isActive }
            if (activeSlots > 0) {
                val output = calculateOccupiedMineOutput(sect.level, activeSlots)
                totalOutput += output
            }
        }

        if (totalOutput > 0) {
            _gameData.value = _gameData.value.copy(
                spiritStones = _gameData.value.spiritStones + totalOutput
            )
            addEvent("占领地灵矿产出：$totalOutput 灵石", EventType.SUCCESS)
        }
    }

    /**
     * 计算占领地灵矿产出
     */
    private fun calculateOccupiedMineOutput(sectLevel: Int, activeSlots: Int): Long {
        val baseOutput = when (sectLevel) {
            0 -> 100L
            1 -> 200L
            2 -> 350L
            3 -> 500L
            else -> 100L
        }
        return baseOutput * activeSlots / 3
    }

    /**
     * 分配弟子到占领地灵矿
     */
    fun assignDiscipleToMineSlot(sectId: String, slotIndex: Int, discipleId: String?): Boolean {
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId }
        if (sect == null || !sect.isOccupied) {
            addEvent("该宗门未被占领", EventType.WARNING)
            return false
        }

        val updatedSlots = sect.mineSlots.mapIndexed { index, slot ->
            if (index == slotIndex) {
                val disciple = discipleId?.let { _disciples.value.find { d -> d.id == it } }
                MineSlot(
                    index = index,
                    discipleId = discipleId,
                    discipleName = disciple?.name ?: "",
                    isActive = discipleId != null
                )
            } else slot
        }

        val updatedSects = data.worldMapSects.map { 
            if (it.id == sectId) it.copy(mineSlots = updatedSlots) else it 
        }

        _gameData.value = data.copy(worldMapSects = updatedSects)

        if (discipleId != null) {
            val disciple = _disciples.value.find { it.id == discipleId }
            addEvent("${disciple?.name ?: "弟子"}被分配到${sect.name}灵矿", EventType.INFO)
        }

        return true
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
        
        if (sect.relation < 40) {
            addEvent("好感度太低，无法与${sect.name}交易", EventType.WARNING)
            return
        }
        
        val maxAllowedRarity = when {
            sect.relation >= 90 -> 6
            sect.relation >= 80 -> 5
            sect.relation >= 70 -> 4
            sect.relation >= 60 -> 3
            sect.relation >= 50 -> 2
            sect.relation >= 40 -> 1
            else -> 0
        }
        
        if (item.rarity > maxAllowedRarity) {
            addEvent("好感度不足，无法购买${item.rarity}阶商品", EventType.WARNING)
            return
        }
        
        // 计算价格折扣：好感度折扣 + 结盟折扣
        val relationDiscount = when {
            sect.relation >= 70 -> (sect.relation - 70) * 0.01
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

        // 送礼成功：扣除灵石、标记已送礼、增加好感度
        val newFavor = (sect.relation + tierConfig.favor).coerceAtMost(100)

        val updatedSects = data.worldMapSects.map { currentSect ->
            if (currentSect.id == sectId) {
                currentSect.copy(
                    relation = newFavor,
                    lastGiftYear = currentYear
                )
            } else {
                currentSect
            }
        }

        _gameData.value = data.copy(
            spiritStones = data.spiritStones - tierConfig.spiritStones,
            worldMapSects = updatedSects
        )

        val responseText = SectResponseTexts.getAcceptResponse(sect.level, "spirit_stones", tierConfig.name, tierConfig.favor)
        addEvent("向${sect.name}送礼${tierConfig.name}成功，好感度+${tierConfig.favor}", EventType.SUCCESS)

        return GiftResult(
            success = true,
            rejected = false,
            favorChange = tierConfig.favor,
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

        // 计算好感度
        val baseFavor = getRarityFavor(itemRarity)
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

        val newFavor = (sect.relation + totalFavor).coerceAtMost(100)

        val finalSects = _gameData.value.worldMapSects.map {
            if (it.id == sectId) {
                it.copy(relation = newFavor, lastGiftYear = currentYear)
            } else it
        }

        _gameData.value = _gameData.value.copy(worldMapSects = finalSects)

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
     */
    fun getRarityFavor(rarity: Int): Int {
        return GiftConfig.RarityFavorConfig.getFavor(rarity)
    }

    /**
     * 获取灵石送礼档位好感度
     * @param tier 档位 (1-4)
     * @return 好感度
     */
    fun getSpiritStoneFavor(tier: Int): Int {
        return GiftConfig.SpiritStoneGiftConfig.getTier(tier)?.favor ?: 0
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

        if (sect.relation < 90) {
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

        val successRate = calculatePersuasionSuccessRate(sect.relation, envoy.intelligence, envoy.charm)
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
            if (s.id == sectId) s.copy(allianceId = null, allianceStartYear = 0, relation = (s.relation - 30).coerceAtLeast(0))
            else s
        }

        val updatedAlliances = data.alliances.filter { it.id != alliance.id }

        _gameData.value = data.copy(
            worldMapSects = updatedSects,
            alliances = updatedAlliances
        )

        addEvent("与${sect.name}解除结盟，好感度下降", EventType.WARNING)
        return Pair(true, "已解除结盟")
    }

    /**
     * 检查盟约到期
     */
    private fun checkAllianceExpiry(year: Int) {
        val data = _gameData.value
        val expiredAlliances = data.alliances.filter { year - it.startYear >= 3 }

        if (expiredAlliances.isEmpty()) return

        val updatedAlliances = data.alliances.filter { year - it.startYear < 3 }
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

        data.alliances.forEach { alliance ->
            if (!alliance.sectIds.contains("player")) return@forEach
            
            val sectId = alliance.sectIds.find { it != "player" }
            if (sectId != null) {
                val sect = data.worldMapSects.find { it.id == sectId }
                if (sect != null && sect.relation < 90) {
                    dissolvedAlliances.add(alliance)
                    addEvent("与${sect.name}的好感度过低，盟约自动解除", EventType.WARNING)
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

                // 计算结盟评分
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
                lastInteractionYear = if (year > 0) year else relation.lastInteractionYear
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
     * 计算支援队伍规模
     * 支援弟子数量 = 5 + (好感度 - 90) × 5，最大55
     */
    fun calculateSupportTeamSize(favorability: Int): Int {
        return (5 + (favorability - 90) * 5).coerceIn(5, 55)
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

        return Pair(true, "可以求援")
    }

    /**
     * 请求支援
     */
    fun requestSupport(allySectId: String, envoyDiscipleId: String): Pair<Boolean, String> {
        val (canRequest, message) = checkRequestSupportConditions(allySectId, envoyDiscipleId)
        
        if (!canRequest) {
            return Pair(false, message)
        }

        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == allySectId }!!
        val envoy = _disciples.value.find { it.id == envoyDiscipleId }!!

        val successRate = calculatePersuasionSuccessRate(sect.relation, envoy.intelligence, envoy.charm)
        val roll = Random.nextDouble()

        if (roll < successRate) {
            val supportSize = calculateSupportTeamSize(sect.relation)
            val supportRealm = getSupportTeamRealm(sect.maxRealm)
            val realmName = GameConfig.Realm.getName(supportRealm)
            
            // 生成支援队伍弟子
            val newSupportDisciples = generateSupportDisciples(sect, supportSize, supportRealm, data.gameYear)
            _disciples.value = _disciples.value + newSupportDisciples
            
            // 创建支援队伍（在世界地图上显示）
            val supportTeam = SupportTeam(
                id = java.util.UUID.randomUUID().toString(),
                name = "${sect.name}支援队",
                sourceSectId = sect.id,
                sourceSectName = sect.name,
                targetSectId = "player",
                disciples = newSupportDisciples.map { it.id },
                size = supportSize,
                currentX = sect.x,
                currentY = sect.y,
                targetX = 0.5f,
                targetY = 0.5f,
                progress = 0f,
                status = "moving",
                startYear = data.gameYear,
                startMonth = data.gameMonth,
                arrivalYear = data.gameYear + (sect.distance / 12),
                arrivalMonth = (data.gameMonth + (sect.distance % 12)).let { if (it > 12) it - 12 else it }
            )
            
            _gameData.value = data.copy(
                supportTeams = data.supportTeams + supportTeam
            )
            
            addEvent("${sect.name}派遣${supportSize}名${realmName}及以下境界弟子前来支援", EventType.SUCCESS)
            return Pair(true, "求援成功，${sect.name}将派遣${supportSize}名${realmName}及以下境界弟子支援")
        } else {
            addEvent("${sect.name}拒绝了支援请求", EventType.WARNING)
            return Pair(false, "求援失败")
        }
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
            loyalty = Random.nextInt(60, 90),
            combatStatsVariance = Random.nextInt(-30, 31)
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

        val updatedSects = data.worldMapSects.map { s ->
            if (s.id == sectId) s.copy(allianceId = null, allianceStartYear = 0, relation = 0)
            else s
        }

        val updatedAlliances = data.alliances.filter { it.id != alliance.id }

        _gameData.value = data.copy(
            worldMapSects = updatedSects,
            alliances = updatedAlliances
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
        return (3 - (data.gameYear - alliance.startYear)).coerceAtLeast(0)
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
            loyalty = Random.nextInt(60, 90),
            parentId1 = mother.id,
            parentId2 = mother.partnerId,
            combatStatsVariance = Random.nextInt(-30, 31)
        )
    }

    /**
     * 处理支援队伍月度移动
     */
    private fun processSupportTeamsMonthly(year: Int, month: Int) {
        val data = _gameData.value
        val updatedTeams = mutableListOf<SupportTeam>()
        val teamsToRemove = mutableListOf<String>()
        
        data.supportTeams.forEach { team ->
            if (team.status == "moving") {
                val currentYear = data.gameYear
                val currentMonth = data.gameMonth
                val arrivalYear = team.arrivalYear
                val arrivalMonth = team.arrivalMonth
                
                // 计算当前时间（从开始时间到当前时间的毫秒数）
                val elapsedMonths = (currentYear - team.startYear) * 12 + (currentMonth - team.startMonth)
                val elapsedSeconds = elapsedMonths * 30 * 24 * 60 * 60L
                
                // 计算总移动时间（毫秒）
                val totalDurationMs = ((arrivalYear - team.startYear) * 12 + (arrivalMonth - team.startMonth)) * 30 * 24 * 60 * 60L
                
                val progress = if (totalDurationMs > 0) {
                    (elapsedSeconds.toFloat() / totalDurationMs).coerceIn(0f, 1f)
                } else {
                    1f
                }
                
                if (progress >= 1f) {
                    // 支援弟子加入玩家宗门
                    team.disciples.forEach { discipleId ->
                        val disciple = _disciples.value.find { it.id == discipleId }
                        if (disciple != null) {
                            // 支援弟子已经存在于玩家宗门，无需额外处理
                        }
                    }
                    
                    addEvent("${team.sourceSectName}的支援队伍已到达，${team.size}名弟子加入宗门", EventType.SUCCESS)
                    teamsToRemove.add(team.id)
                } else {
                    updatedTeams.add(team.copy(progress = progress))
                }
            } else {
                updatedTeams.add(team)
            }
        }
        
        _gameData.value = data.copy(
            supportTeams = updatedTeams.filter { it.id !in teamsToRemove }
        )
    }
}
