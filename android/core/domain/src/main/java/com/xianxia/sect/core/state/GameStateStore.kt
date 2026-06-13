package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 游戏状态存储接口
 *
 * 在 :core:domain 中定义，由 :app 中的 GameStateStore 实现。
 * 提供 engine 和 UI 模块所需的状态读写 API。
 */
interface GameStateStore : GameStateSnapshotProvider {

    // === StateFlow 观察 ===
    val gameData: StateFlow<GameData>
    val disciples: StateFlow<List<Disciple>>
    val discipleTables: DiscipleTables  // Engine/Service 层直接操作组件表
    val equipmentStacks: StateFlow<List<EquipmentStack>>
    val equipmentInstances: StateFlow<List<EquipmentInstance>>
    val manualStacks: StateFlow<List<ManualStack>>
    val manualInstances: StateFlow<List<ManualInstance>>
    val pills: StateFlow<List<Pill>>
    val materials: StateFlow<List<Material>>
    val herbs: StateFlow<List<Herb>>
    val seeds: StateFlow<List<Seed>>
    val storageBags: StateFlow<List<StorageBag>>
    val battleLogs: StateFlow<List<BattleLog>>
    val teams: StateFlow<List<ExplorationTeam>>
    val isPaused: StateFlow<Boolean>
    val isLoading: StateFlow<Boolean>
    val isSaving: StateFlow<Boolean>
    val pendingBattleResult: StateFlow<BattleResultUIData?>
    val pendingNotification: StateFlow<GameNotification?>
    val rewardCardQueue: StateFlow<List<RewardCardItem>>

    // === 三层 StateFlow 架构 ===
    data class HighFreqState(
        val spiritStones: Long = 0L,
        val gameYear: Int = 1,
        val gameMonth: Int = 1,
        val gamePhase: Int = 1,
        val isPaused: Boolean = true
    )

    data class EntityState(
        val disciples: List<Disciple> = emptyList(),
        val equipmentStacks: List<EquipmentStack> = emptyList(),
        val equipmentInstances: List<EquipmentInstance> = emptyList(),
        val manualStacks: List<ManualStack> = emptyList(),
        val manualInstances: List<ManualInstance> = emptyList(),
        val pills: List<Pill> = emptyList(),
        val materials: List<Material> = emptyList(),
        val herbs: List<Herb> = emptyList(),
        val seeds: List<Seed> = emptyList(),
        val storageBags: List<StorageBag> = emptyList(),
        val teams: List<ExplorationTeam> = emptyList(),
        val battleLogs: List<BattleLog> = emptyList()
    )

    data class ConfigState(
        val sectPolicies: SectPolicies = SectPolicies(),
        val yearlySalary: Map<Int, Int> = emptyMap(),
        val yearlySalaryEnabled: Map<Int, Boolean> = emptyMap(),
        val elderSlots: ElderSlots? = null,
        val placedBuildings: List<GridBuildingData> = emptyList(),
        val autoRecruitSpiritRootFilter: Set<Int> = emptySet(),
        val gameSpeed: Int = 1,
        val autoSaveIntervalMonths: Int = 3
    )

    val highFreqState: StateFlow<HighFreqState>
    val entityState: StateFlow<EntityState>
    val configState: StateFlow<ConfigState>
    val unifiedState: StateFlow<UnifiedGameState>

    // === 聚合状态 ===
    val sectCombatPower: StateFlow<Long>
    val aiSectCombatPowers: StateFlow<Map<String, Long>>
    val discipleAggregates: StateFlow<List<DiscipleAggregate>>
    val discipleAggregatesSnapshot: List<DiscipleAggregate>

    // === 事件 ===
    val warehouseFullEvent: MutableSharedFlow<Unit>

    // === 快照读取（绕过 stateIn 调度延迟） ===
    fun getCurrentSeeds(): List<Seed>
    fun getCurrentHerbs(): List<Herb>
    fun getCurrentMaterials(): List<Material>

    // === 通知 API ===
    fun setPendingNotification(notification: GameNotification)
    fun clearPendingNotification()
    fun setPendingBattleResult(result: BattleResultUIData)
    fun clearPendingBattleResult()

    // === 战斗奖励卡片（延迟入队，先展示小屏界面） ===
    val pendingBattleRewardCards: StateFlow<List<RewardCardItem>>
    fun setPendingBattleRewardCards(cards: List<RewardCardItem>)
    fun clearPendingBattleRewardCards()

    // === 奖励卡片 ===
    fun enqueueRewardCards(items: List<RewardCardItem>)
    fun clearRewardCardQueue(count: Int = Int.MAX_VALUE)

    // === 交互状态 ===
    var focusedDiscipleId: String?
    var activeTab: String
    var activeDialog: String?

    // === 核心写入 API ===
    suspend fun update(block: suspend MutableGameState.() -> Unit)

    // === Shadow/Transaction API ===
    fun createShadow(): MutableGameState
    fun createSettlementShadow(): MutableGameState
    suspend fun swapFromShadow(shadow: MutableGameState)
    fun beginShadowTransaction(shadow: MutableGameState)
    fun endShadowTransaction()
    fun currentTransactionMutableState(): MutableGameState?
    fun isInTransaction(): Boolean

    // === 直接状态设置 ===
    fun setPausedDirect(paused: Boolean)
    fun setLoadingDirect(loading: Boolean)
    fun setSavingDirect(saving: Boolean)

    // === 生命周期 ===
    suspend fun loadFromSnapshot(
        gameData: GameData,
        disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack> = emptyList(),
        equipmentInstances: List<EquipmentInstance> = emptyList(),
        manualStacks: List<ManualStack> = emptyList(),
        manualInstances: List<ManualInstance> = emptyList(),
        pills: List<Pill>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>,
        storageBags: List<StorageBag> = emptyList(),
        teams: List<ExplorationTeam>,
        battleLogs: List<BattleLog>,
        isPaused: Boolean = true,
        isLoading: Boolean = false,
        isSaving: Boolean = false
    )

    suspend fun reset()
}
