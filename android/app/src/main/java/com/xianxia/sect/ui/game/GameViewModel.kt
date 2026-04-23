package com.xianxia.sect.ui.game

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.engine.system.SystemError
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.UnifiedGameStateManager
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.ui.state.DialogStateManager
import com.xianxia.sect.ui.state.DialogStateManager.DialogType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class GameViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    private val storageFacade: StorageFacade,
    val dialogStateManager: DialogStateManager,
    @ApplicationContext private val appContext: Context,
    private val stateManager: UnifiedGameStateManager,
    private val systemManager: SystemManager
) : ViewModel() {

    companion object {
        private const val TAG = "GameViewModel"
    }

    init {
        viewModelScope.launch {
            systemManager.errors.collect { error ->
                Log.e(TAG, "System error in ${error.systemName} (${error.tickType}): ${error.error.message}")
                _errorMessage.value = "系统异常：${error.systemName}"
            }
        }
    }

    private fun openDialog(dialogType: DialogType, params: Map<String, Any?> = emptyMap()) {
        dialogStateManager.openDialog(dialogType, params)
    }
    
    /**
     * 关闭当前对话框的统一方法
     */
    fun closeCurrentDialog() {
        dialogStateManager.closeDialog()
    }

    fun openRecruitDialog() {
        openDialog(DialogType.Recruit)
    }

    fun openMerchantDialog() {
        openDialog(DialogType.Merchant)
    }

    fun openDiplomacyDialog() {
        openDialog(DialogType.Diplomacy)
    }

    fun openSpiritMineDialog() {
        openDialog(DialogType.SpiritMine)
    }

    fun openHerbGardenDialog() {
        openDialog(DialogType.HerbGarden)
    }

    fun openAlchemyDialog() {
        openDialog(DialogType.Alchemy)
    }

    fun openForgeDialog() {
        openDialog(DialogType.Forge)
    }

    fun openLibraryDialog() {
        openDialog(DialogType.Library)
    }

    fun openWenDaoPeakDialog() {
        openDialog(DialogType.WenDaoPeak)
    }

    fun openQingyunPeakDialog() {
        openDialog(DialogType.QingyunPeak)
    }

    fun openTianshuHallDialog() {
        openDialog(DialogType.TianshuHall)
    }

    fun openLawEnforcementHallDialog() {
        openDialog(DialogType.LawEnforcementHall)
    }

    fun openMissionHallDialog() {
        openDialog(DialogType.MissionHall)
    }

    fun openReflectionCliffDialog() {
        openDialog(DialogType.ReflectionCliff)
    }

    fun openInventoryDialog() {
        openDialog(DialogType.Inventory)
    }

    fun openEventLogDialog() {
        openDialog(DialogType.EventLog)
    }

    fun openWorldMapDialog() {
        openDialog(DialogType.WorldMap)
    }

    fun closeWorldMapDialog() {
        closeCurrentDialog()
    }

    fun openSecretRealmDialog() {
        openDialog(DialogType.SecretRealm)
    }

    fun closeSecretRealmDialog() {
        closeCurrentDialog()
    }

    fun openBattleLogDialog() {
        openDialog(DialogType.BattleLog)
    }

    fun closeBattleLogDialog() {
        closeCurrentDialog()
    }

    fun closeRecruitDialog() {
        closeCurrentDialog()
    }

    fun closeInventoryDialog() {
        closeCurrentDialog()
    }

    fun closeDiplomacyDialog() {
        closeCurrentDialog()
    }

    fun closeMerchantDialog() {
        closeCurrentDialog()
    }

    fun closeEventLogDialog() {
        closeCurrentDialog()
    }

    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), gameEngine.gameData.value ?: GameData())

    /**
     * 弟子聚合数据 - 用于 UI 层显示（推荐使用）
     *
     * 此属性将底层的 List<Disciple> 自动转换为 List<DiscipleAggregate>，
     * 确保 UI 层统一使用新的多表架构类型。
     */
    val discipleAggregates: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val disciples: StateFlow<List<DiscipleAggregate>> = discipleAggregates

    /**
     * 可招募弟子聚合数据 - 响应式数据流
     *
     * 根据 gameData.recruitList 中的 ID 从全量弟子中筛选出可招募弟子。
     * 当招募列表或弟子数据变化时自动更新，确保 RecruitDialog 显示最新数据。
     */
    val recruitListAggregates: StateFlow<List<DiscipleAggregate>> = gameData
        .map { data -> data.recruitList.map { it.toAggregate() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val equipment: StateFlow<List<EquipmentInstance>> = gameEngine.equipmentInstances
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val manuals: StateFlow<List<ManualInstance>> = gameEngine.manualInstances
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val equipmentStacks: StateFlow<List<EquipmentStack>> = combine(
        gameEngine.equipmentStacks,
        gameEngine.disciples
    ) { stacks, disciples ->
        val bagStackIds = disciples.filter { it.isAlive }
            .flatMap { it.storageBagItems }
            .filter { it.itemType == "equipment_stack" }
            .map { it.itemId }
            .toSet()
        stacks.filter { it.id !in bagStackIds }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val equipmentInstances: StateFlow<List<EquipmentInstance>> = gameEngine.equipmentInstances
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val manualStacks: StateFlow<List<ManualStack>> = combine(
        gameEngine.manualStacks,
        gameEngine.disciples
    ) { stacks, disciples ->
        val bagStackIds = disciples.filter { it.isAlive }
            .flatMap { it.storageBagItems }
            .filter { it.itemType == "manual_stack" }
            .map { it.itemId }
            .toSet()
        stacks.filter { it.id !in bagStackIds }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val manualInstances: StateFlow<List<ManualInstance>> = gameEngine.manualInstances
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pills: StateFlow<List<Pill>> = gameEngine.pills
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val materials: StateFlow<List<Material>> = gameEngine.materials
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val herbs: StateFlow<List<Herb>> = gameEngine.herbs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val seeds: StateFlow<List<Seed>> = gameEngine.seeds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val teams: StateFlow<List<ExplorationTeam>> = gameEngine.teams
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val events: StateFlow<List<GameEvent>> = gameEngine.events
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val battleLogs: StateFlow<List<BattleLog>> = gameEngine.battleLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val alliances: StateFlow<List<Alliance>> = gameEngine.gameData
        .map { it.alliances }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val productionSlots: StateFlow<List<com.xianxia.sect.core.model.production.ProductionSlot>> = gameEngine.productionSlots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val worldMapRenderData: StateFlow<WorldMapRenderData> = gameEngine.worldMapRenderData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WorldMapRenderData())

    val alchemySlots: StateFlow<List<AlchemySlot>> = productionSlots
        .map { slots ->
            slots.filter { it.buildingType == com.xianxia.sect.core.model.production.BuildingType.ALCHEMY }.map { slot ->
                AlchemySlot(
                    id = slot.id,
                    slotIndex = slot.slotIndex,
                    recipeId = slot.recipeId,
                    recipeName = slot.recipeName,
                    pillName = slot.outputItemName,
                    pillRarity = slot.outputItemRarity,
                    startYear = slot.startYear,
                    startMonth = slot.startMonth,
                    duration = slot.duration,
                    status = when (slot.status) {
                        com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE -> AlchemySlotStatus.IDLE
                        com.xianxia.sect.core.model.production.ProductionSlotStatus.WORKING -> AlchemySlotStatus.WORKING
                        com.xianxia.sect.core.model.production.ProductionSlotStatus.COMPLETED -> AlchemySlotStatus.FINISHED
                    },
                    successRate = slot.successRate,
                    requiredMaterials = slot.requiredMaterials
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val highFrequencyData: StateFlow<HighFrequencyData> = gameEngine.highFrequencyData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HighFrequencyData())

    val realtimeCultivation: StateFlow<Map<String, Double>> = gameEngine.realtimeCultivation
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    fun clearSuccessMessage() {
        _successMessage.value = null
    }

    fun hasDisciplePosition(discipleId: String): Boolean {
        return DisciplePositionHelper.hasDisciplePosition(discipleId, gameEngine.gameData.value)
    }

    fun getDisciplePosition(discipleId: String): String? {
        return DisciplePositionHelper.getDisciplePosition(discipleId, gameEngine.gameData.value)
    }

    fun isReserveDisciple(discipleId: String): Boolean {
        val elderSlots = gameEngine.gameData.value.elderSlots
        return elderSlots.lawEnforcementReserveDisciples.any { it.discipleId == discipleId } ||
               elderSlots.herbGardenReserveDisciples.any { it.discipleId == discipleId } ||
               elderSlots.alchemyReserveDisciples.any { it.discipleId == discipleId } ||
               elderSlots.forgeReserveDisciples.any { it.discipleId == discipleId }
    }

    fun isPositionWorkStatus(discipleId: String): Boolean {
        return DisciplePositionHelper.isPositionWorkStatus(discipleId, gameEngine.gameData.value)
    }

    // 防止重复点击标志


    private val _selectedBuildingId = MutableStateFlow<String?>(null)
    val selectedBuildingId: StateFlow<String?> = _selectedBuildingId.asStateFlow()

    private val _selectedPlantSlotIndex = MutableStateFlow<Int?>(null)
    val selectedPlantSlotIndex: StateFlow<Int?> = _selectedPlantSlotIndex.asStateFlow()

    val forgeSlots: StateFlow<List<ForgeSlot>> = productionSlots
        .map { slots ->
            slots.filter { it.buildingType == com.xianxia.sect.core.model.production.BuildingType.FORGE }.map { slot ->
                val recipe = slot.recipeId?.let {
                    com.xianxia.sect.core.data.ForgeRecipeDatabase.getRecipeById(it)
                }
                ForgeSlot(
                    id = slot.id,
                    slotIndex = slot.slotIndex,
                    recipeId = slot.recipeId,
                    recipeName = slot.recipeName,
                    equipmentName = recipe?.name ?: "",
                    equipmentRarity = recipe?.rarity ?: 1,
                    startYear = slot.startYear,
                    startMonth = slot.startMonth,
                    duration = slot.duration,
                    status = when (slot.status) {
                        com.xianxia.sect.core.model.production.ProductionSlotStatus.WORKING -> ForgeSlotStatus.WORKING
                        com.xianxia.sect.core.model.production.ProductionSlotStatus.COMPLETED -> ForgeSlotStatus.FINISHED
                        else -> ForgeSlotStatus.IDLE
                    }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allForgeRecipes: StateFlow<List<ForgeRecipeDatabase.ForgeRecipe>> = flow {
        emit(ForgeRecipeDatabase.getAllRecipes())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())


    private val _gameState = MutableStateFlow("LOADING")
    val gameState: StateFlow<String> = _gameState.asStateFlow()

    val isPaused: StateFlow<Boolean> = gameEngineCore.state
        .map { it.isPaused }
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    private val _gameLog = MutableStateFlow<List<String>>(emptyList())
    val gameLog: StateFlow<List<String>> = _gameLog.asStateFlow()

    private val _notifications = MutableStateFlow<List<String>>(emptyList())
    val notifications: StateFlow<List<String>> = _notifications.asStateFlow()




    // 建筑详情对话框 (带参数)
    fun showBuildingDetailDialog(buildingId: String) {
        _selectedBuildingId.value = buildingId
        openDialog(DialogType.BuildingDetail, mapOf("buildingId" to buildingId))
    }

    fun openBuildingDetailDialog(buildingId: String) {
        _selectedBuildingId.value = buildingId
        openDialog(DialogType.BuildingDetail, mapOf("buildingId" to buildingId))
    }

    fun closeBuildingDetailDialog() {
        closeCurrentDialog()
    }

    fun dispatchTeamToDungeon(dungeonId: String, discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val dungeonConfig = GameConfig.Dungeons.get(dungeonId)
                if (dungeonConfig == null) {
                    _errorMessage.value = "秘境不存在"
                    return@launch
                }

                val teamName = "${dungeonConfig.name}探索队"
                val duration = 24

                gameEngine.startExploration(teamName, discipleIds, dungeonId, duration)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "派遣失败"
            }
        }
    }

    // 招募弟子
    fun recruitDisciple() {
        viewModelScope.launch {
            try {
                gameEngine.recruitDisciple()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "招募弟子失败"
            }
        }
    }

    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            try {
                gameEngine.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "分配失败"
            }
        }
    }


    // 弟子招募相关
    fun recruitDiscipleFromList(discipleId: String) {
        if (recruitingDiscipleIds.contains(discipleId)) {
            return
        }
        recruitingDiscipleIds.add(discipleId)

        viewModelScope.launch {
            try {
                gameEngine.recruitDiscipleFromList(discipleId)
            } finally {
                recruitingDiscipleIds.remove(discipleId)
            }
        }
    }

    fun expelDisciple(discipleId: String) {
        viewModelScope.launch {
            gameEngine.expelDisciple(discipleId)
        }
    }

    fun toggleFollowDisciple(discipleId: String) {
        viewModelScope.launch {
            gameEngine.updateDisciple(discipleId) { disciple ->
                val currentFollowed = disciple.statusData["followed"] == "true"
                val newStatusData = disciple.statusData.toMutableMap().apply {
                    if (currentFollowed) remove("followed") else this["followed"] = "true"
                }
                disciple.copy(statusData = newStatusData)
            }
        }
    }

    suspend fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>) {
        gameEngine.rewardItemsToDisciple(discipleId, items)
    }

    fun recruitAllDisciples() {
        viewModelScope.launch {
            val success = gameEngine.recruitAllFromList()
            if (!success) {
                _errorMessage.value = "无弟子可招募"
            }
        }
    }

    fun rejectDiscipleFromList(discipleId: String) {
        viewModelScope.launch {
            gameEngine.removeFromRecruitList(discipleId)
        }
    }

    fun equipItem(discipleId: String, equipmentId: String) {
        viewModelScope.launch {
            try {
                gameEngine.equipItem(discipleId, equipmentId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "装备失败"
            }
        }
    }

    fun unequipItem(discipleId: String, slot: EquipmentSlot) {
        viewModelScope.launch {
            try {
                gameEngine.unequipItem(discipleId, slot)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸下装备失败"
            }
        }
    }

    fun unequipItem(discipleId: String, equipmentId: String) {
        viewModelScope.launch {
            try {
                gameEngine.unequipItemById(discipleId, equipmentId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸下装备失败"
            }
        }
    }

    fun forgetManual(discipleId: String, manualId: String) {
        viewModelScope.launch {
            try {
                gameEngine.forgetManual(discipleId, manualId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "遗忘功法失败"
            }
        }
    }

    fun replaceManual(discipleId: String, oldManualId: String, newManualId: String) {
        viewModelScope.launch {
            try {
                gameEngine.replaceManual(discipleId, oldManualId, newManualId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "功法替换失败"
            }
        }
    }

    fun learnManual(discipleId: String, manualId: String) {
        viewModelScope.launch {
            try {
                gameEngine.learnManual(discipleId, manualId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "学习功法失败"
            }
        }
    }

    fun recallTeam(teamId: String) {
        viewModelScope.launch {
            try {
                gameEngine.recallTeam(teamId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "召回失败"
            }
        }
    }

    fun startCaveExploration(cave: CultivatorCave, selectedDisciples: List<DiscipleAggregate>) {
        viewModelScope.launch {
            gameEngine.startCaveExploration(cave, selectedDisciples.map { it.toDisciple() })
        }
    }

    fun buyFromMerchant(itemId: String, quantity: Int = 1) {
        viewModelScope.launch {
            try {
                gameEngine.buyMerchantItem(itemId, quantity)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "购买失败"
            }
        }
    }

    fun listItemsToMerchant(items: List<Pair<String, Int>>) {
        viewModelScope.launch {
            try {
                gameEngine.listItemsToMerchant(items)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "上架失败"
            }
        }
    }

    fun removePlayerListedItem(itemId: String) {
        viewModelScope.launch {
            try {
                gameEngine.removePlayerListedItem(itemId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "下架失败"
            }
        }
    }

    // 招募相关
    private val recruitingDiscipleIds = mutableSetOf<String>()

    fun recruitDisciple(disciple: DiscipleAggregate) {
        if (recruitingDiscipleIds.contains(disciple.id)) {
            return
        }
        recruitingDiscipleIds.add(disciple.id)

        viewModelScope.launch {
            try {
                gameEngine.recruitDiscipleFromList(disciple.id)
            } finally {
                recruitingDiscipleIds.remove(disciple.id)
            }
        }
    }

    fun plantSeed(slotIndex: Int, seed: com.xianxia.sect.core.model.Seed) {
        viewModelScope.launch {
            try {
                gameEngine.startManualPlanting(slotIndex, seed.id)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "种植失败"
            }
        }
    }

    fun setMonthlySalary(realm: Int, amount: Int) {
        viewModelScope.launch {
            val data = gameEngine.gameData.value
            val newSalary = data.monthlySalary.toMutableMap()
            newSalary[realm] = amount
            gameEngine.updateMonthlySalary(newSalary)
        }
    }

    fun setMonthlySalaryEnabled(realm: Int, enabled: Boolean) {
        viewModelScope.launch {
            gameEngine.updateMonthlySalaryEnabled(realm, enabled)
        }
    }

    fun toggleSmartBattle() {
        viewModelScope.launch {
            gameEngine.toggleSmartBattle()
        }
    }

    fun isSmartBattleEnabled(): Boolean = gameEngine.isSmartBattleEnabled()

    fun usePill(discipleId: String, pillId: String) {
        viewModelScope.launch {
            try {
                gameEngine.usePill(discipleId, pillId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "使用丹药失败"
            }
        }
    }

    fun usePill(discipleId: String, pill: Pill) {
        viewModelScope.launch {
            try {
                gameEngine.usePill(discipleId, pill.id)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "使用丹药失败"
            }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun getDiscipleById(id: String): DiscipleAggregate? {
        return discipleAggregates.value.find { it.id == id }
    }

    @Suppress("DEPRECATION")
    fun getManualById(id: String): ManualInstance? {
        return manuals.value.find { it.id == id }
    }

    fun getManualInstanceById(id: String): ManualInstance? {
        return manualInstances.value.find { it.id == id }
    }

    fun getEquipmentInstanceById(id: String): EquipmentInstance? {
        return equipmentInstances.value.find { it.id == id }
    }

    fun toggleItemLock(itemId: String, itemType: String) {
        viewModelScope.launch {
            gameEngine.toggleItemLock(itemId, itemType)
        }
    }

    // 一键出售物品（原子性操作，排除锁定物品）
    fun bulkSellItems(
        selectedRarities: Set<Int>,
        selectedTypes: Set<String>
    ) {
        viewModelScope.launch {
            try {
                val learnedManualIds = mutableSetOf<String>()
                disciples.value.filter { it.isAlive }.forEach { disciple ->
                    learnedManualIds.addAll(disciple.manualIds)
                }
                
                val sellOperations = mutableListOf<SuspendableSellOperation>()
                
                if (selectedTypes.contains("EQUIPMENT")) {
                    equipmentStacks.value.filter { 
                        selectedRarities.contains(it.rarity) && 
                        !it.isLocked
                    }.forEach { item ->
                        sellOperations.add(SuspendableSellOperation.Equipment(item.id, item.name, (item.basePrice * item.quantity * 0.8).toInt()))
                    }
                }

                if (selectedTypes.contains("MANUAL")) {
                    manualStacks.value.filter { 
                        selectedRarities.contains(it.rarity) && 
                        !it.isLocked
                    }.forEach { item ->
                        sellOperations.add(SuspendableSellOperation.Manual(item.id, item.name, item.quantity, (item.basePrice * item.quantity * 0.8).toInt()))
                    }
                }

                if (selectedTypes.contains("PILL")) {
                    pills.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        sellOperations.add(SuspendableSellOperation.Pill(item.id, item.name, item.quantity, (item.basePrice * item.quantity * 0.8).toInt()))
                    }
                }

                if (selectedTypes.contains("MATERIAL")) {
                    materials.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        sellOperations.add(SuspendableSellOperation.Material(item.id, item.name, item.quantity, (item.basePrice * item.quantity * 0.8).toInt()))
                    }
                }

                if (selectedTypes.contains("HERB")) {
                    herbs.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        sellOperations.add(SuspendableSellOperation.Herb(item.id, item.name, item.quantity, (item.basePrice * item.quantity * 0.8).toInt()))
                    }
                }

                if (selectedTypes.contains("SEED")) {
                    seeds.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        sellOperations.add(SuspendableSellOperation.Seed(item.id, item.name, item.quantity, (item.basePrice * item.quantity * 0.8).toInt()))
                    }
                }

                if (sellOperations.isEmpty()) {
                    _errorMessage.value = "没有符合条件的物品可出售（已排除锁定物品）"
                    return@launch
                }

                var totalValue = 0
                val soldItems = mutableListOf<String>()
                val executedOperations = mutableListOf<SuspendableSellOperation>()
                
                try {
                    for (op in sellOperations) {
                        val success = when (op) {
                            is SuspendableSellOperation.Equipment -> {
                                gameEngine.sellEquipment(op.id)
                            }
                            is SuspendableSellOperation.Manual -> {
                                gameEngine.sellManual(op.id, op.quantity)
                            }
                            is SuspendableSellOperation.Pill -> {
                                gameEngine.sellPill(op.id, op.quantity)
                            }
                            is SuspendableSellOperation.Material -> {
                                gameEngine.sellMaterial(op.id, op.quantity)
                            }
                            is SuspendableSellOperation.Herb -> {
                                gameEngine.sellHerb(op.id, op.quantity)
                            }
                            is SuspendableSellOperation.Seed -> {
                                gameEngine.sellSeed(op.id, op.quantity)
                            }
                        }
                        
                        if (success) {
                            totalValue += op.price
                            soldItems.add(op.displayName)
                            executedOperations.add(op)
                        }
                    }
                    
                    if (totalValue > 0) {
                        gameEngine.addSpiritStones(totalValue)
                    }
                } catch (e: Exception) {
                    _errorMessage.value = "出售过程中发生错误: ${e.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "一键出售失败"
            }
        }
    }
    
    private sealed class SuspendableSellOperation {
        abstract val id: String
        abstract val displayName: String
        abstract val price: Int
        
        data class Equipment(override val id: String, val name: String, override val price: Int) : SuspendableSellOperation() {
            override val displayName: String = name
        }
        data class Manual(override val id: String, val name: String, val quantity: Int, override val price: Int) : SuspendableSellOperation() {
            override val displayName: String = "$name x$quantity"
        }
        data class Pill(override val id: String, val name: String, val quantity: Int, override val price: Int) : SuspendableSellOperation() {
            override val displayName: String = "$name x$quantity"
        }
        data class Material(override val id: String, val name: String, val quantity: Int, override val price: Int) : SuspendableSellOperation() {
            override val displayName: String = "$name x$quantity"
        }
        data class Herb(override val id: String, val name: String, val quantity: Int, override val price: Int) : SuspendableSellOperation() {
            override val displayName: String = "$name x$quantity"
        }
        data class Seed(override val id: String, val name: String, val quantity: Int, override val price: Int) : SuspendableSellOperation() {
            override val displayName: String = "$name x$quantity"
        }
    }

    fun getEquipmentById(id: String): EquipmentInstance? {
        return equipment.value.find { it.id == id }
    }

    fun getPillById(id: String): Pill? {
        return pills.value.find { it.id == id }
    }

    fun getMaterialById(id: String): Material? {
        return materials.value.find { it.id == id }
    }

    fun startMission(mission: com.xianxia.sect.core.model.Mission, selectedDisciples: List<DiscipleAggregate>) {
        viewModelScope.launch {
            try {
                gameEngine.startMission(mission, selectedDisciples.map { it.toDisciple() })
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "开始任务失败"
            }
        }
    }

    /**
     * 处理内存压力回调
     * @param level 内存压力级别，来自 ComponentCallbacks2
     * 注意：保存操作由 GameActivity 统一处理（带防抖），这里只负责释放内存
     */
    @Suppress("DEPRECATION")
    fun onMemoryPressure(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.w(TAG, "内存压力: $level，释放内存资源")
                gameEngine.releaseMemory(level)
            }
        }
    }

    /**
     * 清理资源
     * 用于释放内存和清理状态
     */
    fun clearResources() {
        Log.i(TAG, "清理 GameViewModel 资源")
        try {
            gameEngineCore.stopGameLoop()
        } catch (e: Exception) {
            Log.w(TAG, "stopGameLoop failed: ${e.message}")
        }
        _errorMessage.value = null
        _successMessage.value = null
    }

    fun openSalaryConfigDialog() {
        openDialog(DialogType.SalaryConfig)
    }

    fun closeSalaryConfigDialog() {
        closeCurrentDialog()
    }

    private val _showRedeemCodeDialog = MutableStateFlow(false)
    val showRedeemCodeDialog: StateFlow<Boolean> = _showRedeemCodeDialog.asStateFlow()

    private val _redeemResult = MutableStateFlow<RedeemResult?>(null)
    val redeemResult: StateFlow<RedeemResult?> = _redeemResult.asStateFlow()

    fun openRedeemCodeDialog() {
        _showRedeemCodeDialog.value = true
        _redeemResult.value = null
    }

    fun closeRedeemCodeDialog() {
        _showRedeemCodeDialog.value = false
        _redeemResult.value = null
    }

    fun redeemCode(code: String) {
        viewModelScope.launch {
            try {
                val currentGameData = gameEngine.gameData.value
                val result = gameEngine.redeemCode(
                    code = code,
                    usedCodes = currentGameData.usedRedeemCodes,
                    currentYear = currentGameData.gameYear,
                    currentMonth = currentGameData.gameMonth
                )
                _redeemResult.value = result
                if (result.success) {
                    _successMessage.value = result.message
                } else {
                    _errorMessage.value = result.message
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error redeeming code", e)
                _errorMessage.value = "兑换失败: ${e.message}"
            }
        }
    }

    fun clearRedeemResult() {
        _redeemResult.value = null
    }

    override fun onCleared() {
        Log.i(TAG, "GameViewModel cleared, performing auto save before stopping game loop")

        // 关键修复：先获取快照再停循环，避免停循环后状态被重置导致保存空数据
        var snapshotToSave: com.xianxia.sect.core.engine.GameStateSnapshot? = null
        try {
            val snapshot = gameEngine.getStateSnapshotSync()
            if (snapshot.gameData.sectName.isNotBlank()) {
                snapshotToSave = snapshot
                Log.d(TAG, "Captured game snapshot for exit save: sectName=${snapshot.gameData.sectName}, " +
                    "year=${snapshot.gameData.gameYear}, disciples=${snapshot.disciples.size}")
            } else {
                Log.w(TAG, "Game data not initialized, skipping exit save")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture snapshot for exit save: ${e.message}")
        }

        // 等待正在进行的保存操作完成
        if (stateManager.state.value.isSaving) {
            Log.i(TAG, "Waiting for current save operation to complete")
            val waitStartTime = System.currentTimeMillis()
            val maxWaitTime = 3000L
            while (stateManager.state.value.isSaving && System.currentTimeMillis() - waitStartTime < maxWaitTime) {
                Thread.sleep(100)
            }
            if (stateManager.state.value.isSaving) {
                Log.w(TAG, "Save operation still in progress after timeout, proceeding with exit save")
            }
        }

        // 停止游戏循环
        clearResources()

        // 使用之前捕获的快照进行保存
        if (snapshotToSave != null) {
            try {
                val autoSaveSlot = com.xianxia.sect.data.StorageConstants.AUTO_SAVE_SLOT
                val saveData = SaveData(
                    gameData = snapshotToSave.gameData,
                    disciples = snapshotToSave.disciples,
                    equipmentStacks = snapshotToSave.equipmentStacks,
                    equipmentInstances = snapshotToSave.equipmentInstances,
                    manualStacks = snapshotToSave.manualStacks,
                    manualInstances = snapshotToSave.manualInstances,
                    pills = snapshotToSave.pills,
                    materials = snapshotToSave.materials,
                    herbs = snapshotToSave.herbs,
                    seeds = snapshotToSave.seeds,
                    teams = snapshotToSave.teams,
                    events = snapshotToSave.events,
                    battleLogs = snapshotToSave.battleLogs,
                    alliances = snapshotToSave.alliances,
                    productionSlots = snapshotToSave.productionSlots
                )
                val result = runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(5_000L) {
                        storageFacade.save(autoSaveSlot, saveData)
                    } ?: SaveResult.failure(SaveError.TIMEOUT, "Save timeout on exit")
                }
                if (result.isSuccess) {
                    Log.i(TAG, "Auto save on exit completed, slot: $autoSaveSlot")
                } else {
                    Log.w(TAG, "Auto save on exit failed, slot: $autoSaveSlot")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto save on exit failed: ${e.message}")
            }
        }

        super.onCleared()
    }


}


