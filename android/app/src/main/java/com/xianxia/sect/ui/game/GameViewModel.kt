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
    val dialogStateManager: DialogStateManager,
    @ApplicationContext private val appContext: Context,
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
            .flatMap { it.equipment.storageBagItems }
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
            .flatMap { it.equipment.storageBagItems }
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

    fun forgetManual(discipleId: String, instanceId: String) {
        viewModelScope.launch {
            try {
                gameEngine.forgetManual(discipleId, instanceId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "遗忘功法失败"
            }
        }
    }

    fun replaceManual(discipleId: String, oldInstanceId: String, newStackId: String) {
        viewModelScope.launch {
            try {
                gameEngine.replaceManual(discipleId, oldInstanceId, newStackId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "功法替换失败"
            }
        }
    }

    fun learnManual(discipleId: String, stackId: String) {
        viewModelScope.launch {
            try {
                gameEngine.learnManual(discipleId, stackId)
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

    fun sellItem(itemId: String, itemType: String, quantity: Int) {
        viewModelScope.launch {
            val result = when (itemType) {
                "equipment" -> gameEngine.sellEquipment(itemId, quantity)
                "manual" -> gameEngine.sellManual(itemId, quantity)
                "pill" -> gameEngine.sellPill(itemId, quantity)
                "material" -> gameEngine.sellMaterial(itemId, quantity)
                "herb" -> gameEngine.sellHerb(itemId, quantity)
                "seed" -> gameEngine.sellSeed(itemId, quantity)
                else -> false
            }
            if (!result) {
                _errorMessage.value = "售卖失败，物品可能已被锁定或不存在"
            }
        }
    }
    fun bulkSellItems(
        selectedRarities: Set<Int>,
        selectedTypes: Set<String>
    ) {
        viewModelScope.launch {
            try {
                val operations = mutableListOf<GameEngine.BulkSellOperation>()
                
                if (selectedTypes.contains("EQUIPMENT")) {
                    equipmentStacks.value.filter { 
                        selectedRarities.contains(it.rarity) && 
                        !it.isLocked
                    }.forEach { item ->
                        operations.add(GameEngine.BulkSellOperation(item.id, item.name, item.quantity, "equipment"))
                    }
                }

                if (selectedTypes.contains("MANUAL")) {
                    manualStacks.value.filter { 
                        selectedRarities.contains(it.rarity) && 
                        !it.isLocked
                    }.forEach { item ->
                        operations.add(GameEngine.BulkSellOperation(item.id, item.name, item.quantity, "manual"))
                    }
                }

                if (selectedTypes.contains("PILL")) {
                    pills.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        operations.add(GameEngine.BulkSellOperation(item.id, item.name, item.quantity, "pill"))
                    }
                }

                if (selectedTypes.contains("MATERIAL")) {
                    materials.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        operations.add(GameEngine.BulkSellOperation(item.id, item.name, item.quantity, "material"))
                    }
                }

                if (selectedTypes.contains("HERB")) {
                    herbs.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        operations.add(GameEngine.BulkSellOperation(item.id, item.name, item.quantity, "herb"))
                    }
                }

                if (selectedTypes.contains("SEED")) {
                    seeds.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        operations.add(GameEngine.BulkSellOperation(item.id, item.name, item.quantity, "seed"))
                    }
                }

                if (operations.isEmpty()) {
                    _errorMessage.value = "没有符合条件的物品可出售（已排除锁定物品）"
                    return@launch
                }

                try {
                    val result = gameEngine.bulkSellItems(operations)
                    if (result.soldCount > 0) {
                        val msg = buildString {
                            append("成功出售 ${result.soldCount} 件物品，获得 ${result.totalEarned} 灵石")
                            if (result.failedItemNames.isNotEmpty()) {
                                append("\n以下物品出售失败：${result.failedItemNames.joinToString("、")}")
                            }
                        }
                        _successMessage.value = msg
                    } else {
                        _errorMessage.value = "出售失败，物品可能已被锁定或不存在"
                    }
                } catch (e: Exception) {
                    _errorMessage.value = "出售过程中发生错误: ${e.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "一键出售失败"
            }
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
        Log.i(TAG, "GameViewModel cleared, stopping game loop and releasing resources")

        // 停止游戏循环并释放资源
        // 保存逻辑由 SaveLoadViewModel.onCleared() 统一负责，避免重复保存导致竞态条件
        clearResources()

        super.onCleared()
    }


}


