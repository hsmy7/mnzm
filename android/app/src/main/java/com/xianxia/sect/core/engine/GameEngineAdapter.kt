package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.subsystem.*
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.RedeemResult
import com.xianxia.sect.core.state.UnifiedGameStateManager
import com.xianxia.sect.core.state.StateChangeRequestBus
import com.xianxia.sect.core.state.UnifiedGameState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameEngineAdapter @Inject constructor(
    private val gameEngine: GameEngine,
    private val stateManager: UnifiedGameStateManager,
    private val eventBus: EventBus
) {
    
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transactionMutex = Mutex()
    
    private val _gameDataFlow: StateFlow<GameData> by lazy {
        stateManager.state
            .map { it.gameData }
            .stateIn(adapterScope, SharingStarted.WhileSubscribed(5000), GameData())
    }
    
    private val _disciplesFlow: StateFlow<List<Disciple>> by lazy {
        stateManager.state
            .map { it.disciples }
            .stateIn(adapterScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
    
    private val _equipmentFlow: StateFlow<List<Equipment>> by lazy {
        stateManager.state
            .map { it.equipment }
            .stateIn(adapterScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
    
    private val _teamsFlow: StateFlow<List<ExplorationTeam>> by lazy {
        stateManager.state
            .map { it.teams }
            .stateIn(adapterScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
    
    private val _buildingSlotsFlow: StateFlow<List<BuildingSlot>> by lazy {
        stateManager.state
            .map { it.gameData.forgeSlots }
            .stateIn(adapterScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
    
    private val _eventsFlow: StateFlow<List<GameEvent>> by lazy {
        stateManager.state
            .map { it.events }
            .stateIn(adapterScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
    
    private val _battleLogsFlow: StateFlow<List<BattleLog>> by lazy {
        stateManager.state
            .map { it.battleLogs }
            .stateIn(adapterScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
    
    private val _alchemySlotsFlow: StateFlow<List<AlchemySlot>> by lazy {
        stateManager.state
            .map { it.gameData.alchemySlots }
            .stateIn(adapterScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
    
    val gameData: StateFlow<GameData> get() = _gameDataFlow
    
    val disciples: StateFlow<List<Disciple>> get() = _disciplesFlow
    
    val equipment: StateFlow<List<Equipment>> get() = _equipmentFlow
    
    val manuals: StateFlow<List<Manual>> = gameEngine.manuals
    val pills: StateFlow<List<Pill>> = gameEngine.pills
    val materials: StateFlow<List<Material>> = gameEngine.materials
    val herbs: StateFlow<List<Herb>> = gameEngine.herbs
    val seeds: StateFlow<List<Seed>> = gameEngine.seeds
    
    val teams: StateFlow<List<ExplorationTeam>> get() = _teamsFlow
    
    val buildingSlots: StateFlow<List<BuildingSlot>> get() = _buildingSlotsFlow
    
    val events: StateFlow<List<GameEvent>> get() = _eventsFlow
    
    val battleLogs: StateFlow<List<BattleLog>> get() = _battleLogsFlow
    
    val alchemySlots: StateFlow<List<AlchemySlot>> get() = _alchemySlotsFlow
    
    val highFrequencyData: StateFlow<HighFrequencyData> = gameEngine.highFrequencyData
        .map { engineData ->
            HighFrequencyData(
                cultivationUpdates = engineData.cultivationUpdates,
                equipmentNurtureUpdates = engineData.equipmentNurtureUpdates,
                manualProficiencyUpdates = engineData.manualProficiencyUpdates
            )
        }
        .stateIn(adapterScope, SharingStarted.WhileSubscribed(5000), HighFrequencyData())
    
    val realtimeCultivation: StateFlow<Map<String, Double>> = gameEngine.realtimeCultivation
    
    val unifiedState: StateFlow<UnifiedGameState> get() = stateManager.state
    
    data class HighFrequencyData(
        val cultivationUpdates: Map<String, Double> = emptyMap(),
        val equipmentNurtureUpdates: Map<String, Double> = emptyMap(),
        val manualProficiencyUpdates: Map<String, Double> = emptyMap()
    )
    
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
    
    suspend fun getStateSnapshot(): GameStateSnapshot {
        val state = stateManager.createSnapshot()
        return GameStateSnapshot(
            gameData = state.gameData,
            disciples = state.disciples,
            equipment = state.equipment,
            manuals = state.manuals,
            pills = state.pills,
            materials = state.materials,
            herbs = state.herbs,
            seeds = state.seeds,
            teams = state.teams,
            events = state.events,
            battleLogs = state.battleLogs,
            alliances = state.alliances
        )
    }
    
    fun getStateSnapshotSync(): GameStateSnapshot {
        val state = stateManager.currentState
        return GameStateSnapshot(
            gameData = state.gameData,
            disciples = state.disciples,
            equipment = state.equipment,
            manuals = state.manuals,
            pills = state.pills,
            materials = state.materials,
            herbs = state.herbs,
            seeds = state.seeds,
            teams = state.teams,
            events = state.events,
            battleLogs = state.battleLogs,
            alliances = state.alliances
        )
    }
    
    suspend fun updateGameData(update: (GameData) -> GameData) {
        stateManager.updateGameData(update)
    }
    
    fun updateGameDataSync(update: (GameData) -> GameData) {
        stateManager.updateGameDataSync(update)
    }
    
    suspend fun updateGameDataSuspend(update: (GameData) -> GameData) {
        stateManager.updateGameData(update)
    }
    
    suspend fun updateDisciple(discipleId: String, update: (Disciple) -> Disciple) {
        stateManager.updateDisciple(discipleId, update)
    }
    
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
        stateManager.loadState(UnifiedGameState(
            gameData = gameData,
            disciples = disciples,
            equipment = equipment,
            manuals = manuals,
            pills = pills,
            materials = materials,
            herbs = herbs,
            seeds = seeds,
            teams = teams,
            events = events,
            battleLogs = battleLogs,
            alliances = alliances
        ))
    }
    
    fun createNewGame(sectName: String) = gameEngine.createNewGame(sectName)
    
    fun addEvent(message: String, type: EventType) = gameEngine.addEvent(message, type)
    
    fun isPaused(): Boolean = stateManager.currentState.isPaused
    
    suspend fun setPaused(paused: Boolean) {
        stateManager.setPaused(paused)
    }
    
    fun processSecondTick() = gameEngine.processSecondTick()
    
    fun advanceDay() = gameEngine.advanceDay()
    
    fun restartGame() = gameEngine.restartGame()
    
    fun resetAllDisciplesStatus() = gameEngine.resetAllDisciplesStatus()
    
    fun interactWithSect(sectId: String, action: String) = gameEngine.interactWithSect(sectId, action)
    
    fun getOrRefreshSectTradeItems(sectId: String) = gameEngine.getOrRefreshSectTradeItems(sectId)
    
    fun startScoutMission(memberIds: List<String>, targetSect: WorldSect, year: Int, month: Int, day: Int) =
        gameEngine.startScoutMission(memberIds, targetSect, year, month, day)
    
    fun giftSpiritStones(sectId: String, tier: Int) = gameEngine.giftSpiritStones(sectId, tier)
    
    fun giftItem(sectId: String, itemId: String, itemType: String, quantity: Int) =
        gameEngine.giftItem(sectId, itemId, itemType, quantity)
    
    fun requestAlliance(sectId: String, envoyDiscipleId: String): Pair<Boolean, String> =
        gameEngine.requestAlliance(sectId, envoyDiscipleId)
    
    fun dissolveAlliance(sectId: String): Pair<Boolean, String> = gameEngine.dissolveAlliance(sectId)

    fun getEnvoyRealmRequirement(sectLevel: Int) = gameEngine.getEnvoyRealmRequirement(sectLevel)
    
    fun getAllianceCost(sectLevel: Int) = gameEngine.getAllianceCost(sectLevel)
    
    fun isAlly(sectId: String) = gameEngine.isAlly(sectId)
    
    fun getAllianceRemainingYears(sectId: String) = gameEngine.getAllianceRemainingYears(sectId)
    
    fun getPlayerAllies() = gameEngine.getPlayerAllies()
    
    fun buyFromSectTrade(sectId: String, itemId: String, quantity: Int) =
        gameEngine.buyFromSectTrade(sectId, itemId, quantity)
    
    fun redeemCode(code: String, usedCodes: List<String>, currentYear: Int, currentMonth: Int): RedeemResult =
        gameEngine.redeemCode(code, usedCodes, currentYear, currentMonth)
    
    fun updateElderSlots(newElderSlots: ElderSlots) = gameEngine.updateElderSlots(newElderSlots)
    
    fun syncAllDiscipleStatuses() = gameEngine.syncAllDiscipleStatuses()
    
    fun assignDirectDisciple(elderSlotType: String, slotIndex: Int, discipleId: String, discipleName: String, discipleRealm: String, discipleSpiritRootColor: String) =
        gameEngine.assignDirectDisciple(elderSlotType, slotIndex, discipleId, discipleName, discipleRealm, discipleSpiritRootColor)
    
    fun removeDirectDisciple(elderSlotType: String, slotIndex: Int) =
        gameEngine.removeDirectDisciple(elderSlotType, slotIndex)
    
    fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) =
        gameEngine.updateDiscipleStatus(discipleId, status)
    
    fun validateAndFixSpiritMineData() = gameEngine.validateAndFixSpiritMineData()
    
    fun startExploration(teamName: String, discipleIds: List<String>, dungeonId: String, duration: Int) =
        gameEngine.startExploration(teamName, discipleIds, dungeonId, duration)
    
    fun recruitDisciple() = gameEngine.recruitDisciple()
    
    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) =
        gameEngine.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)
    
    fun isDiscipleAssignedToSpiritMine(discipleId: String) = gameEngine.isDiscipleAssignedToSpiritMine(discipleId)
    
    fun updateSpiritMineSlots(slots: List<SpiritMineSlot>) = gameEngine.updateSpiritMineSlots(slots)
    
    fun assignDiscipleToLibrarySlot(slotIndex: Int, discipleId: String, discipleName: String) =
        gameEngine.assignDiscipleToLibrarySlot(slotIndex, discipleId, discipleName)
    
    fun removeDiscipleFromLibrarySlot(slotIndex: Int) = gameEngine.removeDiscipleFromLibrarySlot(slotIndex)
    
    suspend fun startAlchemy(slotIndex: Int, recipeId: String) = gameEngine.startAlchemy(slotIndex, recipeId)
    
    fun collectAlchemyResult(slotIndex: Int) = gameEngine.collectAlchemyResult(slotIndex)
    
    fun clearAlchemySlot(slotIndex: Int) = gameEngine.clearAlchemySlot(slotIndex)
    
    suspend fun startForging(slotIndex: Int, recipeId: String) = gameEngine.startForging(slotIndex, recipeId)
    
    fun collectForgeResult(slotIndex: Int) = gameEngine.collectForgeResult(slotIndex)
    
    fun clearForgeSlot(slotIndex: Int) = gameEngine.clearForgeSlot(slotIndex)
    
    fun startManualPlanting(slotIndex: Int, seedId: String) = gameEngine.startManualPlanting(slotIndex, seedId)
    
    fun clearPlantSlot(slotIndex: Int) = gameEngine.clearPlantSlot(slotIndex)
    
    fun recruitDiscipleFromList(disciple: Disciple) = gameEngine.recruitDiscipleFromList(disciple)
    
    fun expelDisciple(discipleId: String) = gameEngine.expelDisciple(discipleId)
    
    suspend fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>) =
        gameEngine.rewardItemsToDisciple(discipleId, items)
    
    fun recruitAllFromList() = gameEngine.recruitAllFromList()
    
    fun removeFromRecruitList(disciple: Disciple) = gameEngine.removeFromRecruitList(disciple)
    
    fun equipItem(discipleId: String, equipmentId: String) = gameEngine.equipItem(discipleId, equipmentId)
    
    fun unequipItem(discipleId: String, slot: EquipmentSlot) = gameEngine.unequipItem(discipleId, slot)
    
    fun unequipItemById(discipleId: String, equipmentId: String) = gameEngine.unequipItemById(discipleId, equipmentId)
    
    fun forgetManual(discipleId: String, manualId: String) = gameEngine.forgetManual(discipleId, manualId)
    
    fun replaceManual(discipleId: String, oldManualId: String, newManualId: String) =
        gameEngine.replaceManual(discipleId, oldManualId, newManualId)
    
    fun learnManual(discipleId: String, manualId: String) = gameEngine.learnManual(discipleId, manualId)
    
    fun recallTeam(teamId: String) = gameEngine.recallTeam(teamId)
    
    fun startCaveExploration(cave: CultivatorCave, selectedDisciples: List<Disciple>) =
        gameEngine.startCaveExploration(cave, selectedDisciples)
    
    suspend fun buyMerchantItem(itemId: String, quantity: Int) = gameEngine.buyMerchantItem(itemId, quantity)
    
    suspend fun listItemsToMerchant(items: List<Pair<String, Int>>) = gameEngine.listItemsToMerchant(items)
    
    suspend fun removePlayerListedItem(itemId: String) = gameEngine.removePlayerListedItem(itemId)
    
    suspend fun buyFromTradingHall(itemId: String) = gameEngine.buyFromTradingHall(itemId)
    
    suspend fun sellToTradingHall(itemId: String) = gameEngine.sellToTradingHall(itemId)
    
    fun harvestHerb(slotIndex: Int) = gameEngine.harvestHerb(slotIndex)
    
    fun updateMonthlySalary(newSalary: Map<Int, Int>) = gameEngine.updateMonthlySalary(newSalary)
    
    fun updateMonthlySalaryEnabled(realm: Int, enabled: Boolean) =
        gameEngine.updateMonthlySalaryEnabled(realm, enabled)
    
    fun usePill(discipleId: String, pillId: String) = gameEngine.usePill(discipleId, pillId)
    
    fun sellEquipment(equipmentId: String) = gameEngine.sellEquipment(equipmentId)
    
    fun sellManual(manualId: String, quantity: Int) = gameEngine.sellManual(manualId, quantity)
    
    fun sellPill(pillId: String, quantity: Int) = gameEngine.sellPill(pillId, quantity)
    
    fun sellMaterial(materialId: String, quantity: Int) = gameEngine.sellMaterial(materialId, quantity)
    
    fun sellHerb(herbId: String, quantity: Int) = gameEngine.sellHerb(herbId, quantity)
    
    fun sellSeed(seedId: String, quantity: Int) = gameEngine.sellSeed(seedId, quantity)
    
    fun addSpiritStones(amount: Int) = gameEngine.addSpiritStones(amount)
    
    fun syncListedItemsWithInventory() = gameEngine.syncListedItemsWithInventory()
    
    fun startMission(mission: Mission, selectedDisciples: List<Disciple>) =
        gameEngine.startMission(mission, selectedDisciples)
    
    fun releaseMemory(level: Int) = gameEngine.releaseMemory(level)
    
    fun startBattleTeamMove(targetSectId: String) = gameEngine.startBattleTeamMove(targetSectId)
    
    fun dismissDisciple(discipleId: String) = gameEngine.expelDisciple(discipleId)
    
    fun giveItemToDisciple(discipleId: String, itemId: String, itemType: String) {
        when (itemType) {
            "pill" -> usePill(discipleId, itemId)
        }
    }
    
    fun assignManual(discipleId: String, manualId: String) = gameEngine.learnManual(discipleId, manualId)
    
    fun removeManual(discipleId: String, manualId: String) = gameEngine.forgetManual(discipleId, manualId)
    
    fun dispose() {
        adapterScope.cancel()
    }
}
