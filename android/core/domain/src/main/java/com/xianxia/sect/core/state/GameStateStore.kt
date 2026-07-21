package com.xianxia.sect.core.state

import androidx.compose.runtime.Immutable
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
    val pendingBeastAttacks: StateFlow<List<PendingBeastAttack>>

    // === 三层 StateFlow 架构 ===
    @Immutable
    data class HighFreqState(
        val lowGradeSpiritStones: Long = 0L,
        val midGradeSpiritStones: Long = 0L,
        val highGradeSpiritStones: Long = 0L,
        val gameYear: Int = 1,
        val gameMonth: Int = 1,
        val gamePhase: Int = 1,
        val isPaused: Boolean = true
    )

    @Immutable
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

    @Immutable
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
    /** 通知队列（v3+，替代单值 [pendingNotification]） */
    val notifications: StateFlow<List<GameNotification>>
    fun enqueueNotification(notification: GameNotification)
    fun consumeNotification(): GameNotification?

    /** @deprecated 使用 [enqueueNotification] 替代 */
    @Deprecated("Use enqueueNotification() instead", ReplaceWith("enqueueNotification(notification)"))
    fun setPendingNotification(notification: GameNotification)
    /** @deprecated 通知系统已改为队列，UI 侧通过 [consumeNotification] 消费 */
    @Deprecated("Notifications are now queued. Use consumeNotification() instead.")
    fun clearPendingNotification()
    fun setPendingBattleResult(result: BattleResultUIData)
    fun clearPendingBattleResult()

    // === 战斗奖励卡片（延迟入队，先展示小屏界面） ===
    val pendingBattleRewardCards: StateFlow<List<RewardCardItem>>
    fun setPendingBeastAttacks(attacks: List<PendingBeastAttack>)
    fun clearPendingBeastAttacks()
    fun setPendingBattleRewardCards(cards: List<RewardCardItem>)
    fun clearPendingBattleRewardCards()

    // === 奖励卡片 ===
    fun enqueueRewardCards(items: List<RewardCardItem>)
    fun clearRewardCardQueue(count: Int = Int.MAX_VALUE)

    // === 交互状态 ===
    var activeTab: String
    var activeDialog: String?

    /**
     * 当前激活的子界面域名称集合（不经过导航系统的子界面，
     * 如 [DiscipleSelectorDialog]）。引擎在 [resolveDomainsFromView]
     * 中将其解析为对应的 [FocusDomain]。
     */
    var activeSubDialogs: Set<String>

    // === 生命周期状态（新 API，优先使用） ===

    /**
     * 原子化生命周期状态 — 同时包含启动阶段和运行时状态。
     * [bootPhase] 和 [runState] 由此派生，确保两字段的更新是原子性的。
     */
    @Immutable
    data class LifecycleState(
        val bootPhase: BootPhase = BootPhase.UNINITIALIZED,
        val runState: RunState = RunState.IDLE
    )

    /** 原子化的生命周期状态（单一真相源） */
    val lifecycleState: StateFlow<LifecycleState>

    /**
     * 启动序列阶段。
     *
     * 仅由 [BootSequenceController] 内部推进，外部只读。
     * ★ 由 [lifecycleState] 派生，读取旧值可能有中间窗口。
     */
    val bootPhase: StateFlow<BootPhase>

    /** 运行时状态：IDLE / LOADING / PLAYING / RELOADING */
    val runState: StateFlow<RunState>

    /**
     * 推进启动序列到下一步。
     * 只能在 [BootPhase] 内逐步前进（ordinal +1），到达 BOOT_COMPLETE 后不可再调用。
     */
    fun advanceBootPhase()

    /**
     * 重置启动序列到 UNINITIALIZED。
     * 在 RELOADING 入口或错误恢复时调用。
     */
    fun resetBootPhase()

    /**
     * 设置运行状态为 PLAYING。
     * 在启动序列完成后调用。
     */
    fun setPlaying()

    /** 设置运行状态为 RELOADING。 */
    fun setReloading()

    /** 设置运行状态为 IDLE（取消后恢复）。 */
    fun setIdle() {}

    /** 设置运行状态为 LOADING（启动任务开始）。 */
    fun setLoading() {}

    // === 生命周期状态（旧 API，兼容层） ===
    @Deprecated("Use bootPhase/runState instead. Will be removed in next major version.")
    @Suppress("DEPRECATION")
    val gameLifecycle: StateFlow<GameLifecycle>

    // === 核心写入 API ===
    fun update(block: MutableGameState.() -> Unit)

    /**
     * 带返回值的事务更新。替代 `update {}` + `var result = false` 闭包捕获反模式。
     *
     * @param block 在 [MutableGameState] 上下文中执行的 lambda，返回类型为 [R]
     * @return block 的返回值
     */
    fun <R> updateAndReturn(block: MutableGameState.() -> R): R

    /**
     * 在事务内原地修改状态，避免 [update] 重入。
     *
     * - 若当前已在 [update] 事务内 → 直接执行 block
     * - 否则 → 新开 [update] 事务执行 block
     */
    fun modifyState(block: MutableGameState.() -> Unit)

    // === 批量发射模式（结算时抑制个体 StateFlow 发射，减少重组雪崩） ===
    /** 进入批量发射模式：个体 Field StateFlow 暂不发射，仅累积 _updateVersion */
    fun enterBatchEmissionMode() {}
    /** 退出批量发射模式：发射 _updateVersion 触发一次统一状态重建 */
    fun exitBatchEmissionMode() {}

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

    /**
     * 重置为新槽位的游戏状态。
     * 清除所有内存状态并同步槽位上下文。
     * 默认行为与 [reset] 一致，具体实现可附加 repository 同步逻辑。
     */
    suspend fun resetForSlot(slotId: Int) {
        reset()
    }
}
