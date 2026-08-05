@file:Suppress("WildcardImport", "EmptyFunctionBlock")

package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 测试用 [GameStateStore] 假实现（共享）。
 *
 * 使用持久化 [DiscipleTables] 实例，确保跨 update 调用持久化。
 * 原为 GameEngineAtomicAssignTest 私有副本，抽取为共享供多测试文件复用。
 */
internal class FakeAtomicStateStore : GameStateStore {

    // 持久化 DiscipleTables 实例（newMutable 复用同一实例，确保跨 update 持久化）
    val persistentDiscipleTables = DiscipleTables()

    // 持久化材料实例（血炼等用例需要跨 update 保留插入的材料）
    val persistentMaterials = EntityStore<Material>()

    // ── 启动/运行状态 ──
    override val lifecycleState = MutableStateFlow(GameStateStore.LifecycleState())
    override val bootPhase = MutableStateFlow(BootPhase.UNINITIALIZED)
    override val runState = MutableStateFlow(RunState.IDLE)

    // ── StateFlow 观察 ──
    private val _gameData = MutableStateFlow(GameData())
    override val gameData: StateFlow<GameData> get() = _gameData
    override val disciples = MutableStateFlow<List<Disciple>>(emptyList())
    override val discipleTables: DiscipleTables get() = persistentDiscipleTables
    override val equipmentStacks = MutableStateFlow<List<EquipmentStack>>(emptyList())
    override val equipmentInstances = MutableStateFlow<List<EquipmentInstance>>(emptyList())
    override val manualStacks = MutableStateFlow<List<ManualStack>>(emptyList())
    override val manualInstances = MutableStateFlow<List<ManualInstance>>(emptyList())
    override val pills = MutableStateFlow<List<Pill>>(emptyList())
    override val materials = MutableStateFlow<List<Material>>(emptyList())
    override val herbs = MutableStateFlow<List<Herb>>(emptyList())
    override val seeds = MutableStateFlow<List<Seed>>(emptyList())
    override val storageBags = MutableStateFlow<List<StorageBag>>(emptyList())
    override val teams = MutableStateFlow<List<ExplorationTeam>>(emptyList())
    override val battleLogs = MutableStateFlow<List<BattleLog>>(emptyList())
    override val isPaused = MutableStateFlow(false)
    override val isLoading = MutableStateFlow(false)
    override val isSaving = MutableStateFlow(false)
    override val pendingBattleResult = MutableStateFlow<BattleResultUIData?>(null)
    override val pendingNotification = MutableStateFlow<GameNotification?>(null)
    override val rewardCardQueue = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val pendingBeastAttacks = MutableStateFlow<List<PendingBeastAttack>>(emptyList())
    override val pendingMarriageProposals = MutableStateFlow<List<PendingMarriageProposal>>(emptyList())
    override val pendingBattleRewardCards = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val sectCombatPower = MutableStateFlow(0L)
    override val aiSectCombatPowers = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val discipleAggregates = MutableStateFlow<List<DiscipleAggregate>>(emptyList())
    override val highFreqState = MutableStateFlow(GameStateStore.HighFreqState())
    override val entityState = MutableStateFlow(GameStateStore.EntityState())
    override val configState = MutableStateFlow(GameStateStore.ConfigState())

    // ── 快照 ──
    override val discipleAggregatesSnapshot: List<DiscipleAggregate> get() = discipleAggregates.value
    override val gameDataSnapshot: GameData get() = _gameData.value
    override val disciplesSnapshot: List<Disciple> get() = disciples.value
    override val equipmentStacksSnapshot: List<EquipmentStack> get() = equipmentStacks.value
    override val equipmentInstancesSnapshot: List<EquipmentInstance> get() = equipmentInstances.value
    override val manualStacksSnapshot: List<ManualStack> get() = manualStacks.value
    override val manualInstancesSnapshot: List<ManualInstance> get() = manualInstances.value
    override val pillsSnapshot: List<Pill> get() = pills.value
    override val materialsSnapshot: List<Material> get() = materials.value
    override val herbsSnapshot: List<Herb> get() = herbs.value
    override val seedsSnapshot: List<Seed> get() = seeds.value
    override val storageBagsSnapshot: List<StorageBag> get() = storageBags.value
    override val teamsSnapshot: List<ExplorationTeam> get() = teams.value
    override val battleLogsSnapshot: List<BattleLog> get() = battleLogs.value

    // ── 兼容层 ──
    override val warehouseFullEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)

    // ── UI 交互状态 ──
    override var activeTab: String = ""
    override var activeDialog: String? = ""
    override var activeSubDialogs: Set<String> = emptySet()

    // ── 通知 / 战斗结果 ──
    override fun getCurrentSeeds(): List<Seed> = seeds.value
    override fun getCurrentHerbs(): List<Herb> = herbs.value
    override fun getCurrentMaterials(): List<Material> = materials.value
    override fun clearPendingNotification() { pendingNotification.value = null }
    override val notifications = MutableStateFlow<List<GameNotification>>(emptyList())
    override fun enqueueNotification(n: GameNotification) { notifications.value = notifications.value + n }
    override fun consumeNotification(): GameNotification? {
        val item = notifications.value.firstOrNull()
        if (item != null) notifications.value = notifications.value.drop(1)
        return item
    }
    override fun setPendingBattleResult(r: BattleResultUIData) { pendingBattleResult.value = r }
    override fun clearPendingBattleResult() { pendingBattleResult.value = null }
    override fun setPendingBeastAttacks(a: List<PendingBeastAttack>) { pendingBeastAttacks.value = a }
    override fun clearPendingBeastAttacks() { pendingBeastAttacks.value = emptyList() }
    override fun removePendingBeastAttack(beastLevelId: String) {
        pendingBeastAttacks.value =
            pendingBeastAttacks.value.filter { it.beastLevel.id != beastLevelId }
    }
    override fun clearPendingMarriageProposals() { pendingMarriageProposals.value = emptyList() }
    override fun setPendingBattleRewardCards(c: List<RewardCardItem>) { pendingBattleRewardCards.value = c }
    override fun clearPendingBattleRewardCards() { pendingBattleRewardCards.value = emptyList() }
    override fun enqueueRewardCards(items: List<RewardCardItem>) {}
    override fun clearRewardCardQueue(count: Int) {}
    override fun setPausedDirect(paused: Boolean) { isPaused.value = paused }
    override fun setLoadingDirect(loading: Boolean) { isLoading.value = loading }
    override fun setSavingDirect(saving: Boolean) { isSaving.value = saving }
    override suspend fun loadFromSnapshot(
        gameData: GameData,
        disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack>,
        equipmentInstances: List<EquipmentInstance>,
        manualStacks: List<ManualStack>,
        manualInstances: List<ManualInstance>,
        pills: List<Pill>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>,
        storageBags: List<StorageBag>,
        teams: List<ExplorationTeam>,
        battleLogs: List<BattleLog>,
        isPaused: Boolean,
        isLoading: Boolean,
        isSaving: Boolean
    ) {
        this._gameData.value = gameData
        this.disciples.value = disciples
        this.teams.value = teams
        this.battleLogs.value = battleLogs
        this.isPaused.value = isPaused
        this.isLoading.value = isLoading
        this.isSaving.value = isSaving
    }
    override suspend fun reset() {
        _gameData.value = GameData()
        disciples.value = emptyList()
        bootPhase.value = BootPhase.UNINITIALIZED
        runState.value = RunState.IDLE
    }

    // ── 生命周期 ──
    override fun advanceBootPhase() {}
    override fun resetBootPhase() {}
    override fun setPlaying() { runState.value = RunState.PLAYING }
    override fun setReloading() { runState.value = RunState.RELOADING }
    override fun setLoading() { runState.value = RunState.LOADING }
    override fun setIdle() { runState.value = RunState.IDLE }

    /** 最后一次 update 后的 GameData 快照 */
    var latestGameData: GameData = GameData()
        private set

    override fun update(block: MutableGameState.() -> Unit) {
        val m = newMutable()
        block(m)
        _gameData.value = m.gameData
        latestGameData = m.gameData
        teams.value = m.teams
        battleLogs.value = m.battleLogs
        isPaused.value = m.isPaused
        isLoading.value = m.isLoading
        isSaving.value = m.isSaving
    }

    override fun <R> updateAndReturn(block: MutableGameState.() -> R): R {
        val m = newMutable()
        val result = block(m)
        _gameData.value = m.gameData
        latestGameData = m.gameData
        teams.value = m.teams; battleLogs.value = m.battleLogs
        isPaused.value = m.isPaused; isLoading.value = m.isLoading; isSaving.value = m.isSaving
        return result
    }

    override fun modifyState(block: MutableGameState.() -> Unit) { update(block) }
    override fun enterBatchEmissionMode() {}
    override fun exitBatchEmissionMode() {}
    override fun takeAtomicSnapshot(): GameStateStore.GameSnapshot = GameStateStore.GameSnapshot()

    /** 使用持久化 DiscipleTables 实例，确保跨 update 调用持久化 */
    private fun newMutable() = MutableGameState(
        gameData = _gameData.value,
        discipleTables = persistentDiscipleTables,
        equipmentStacks = EntityStore(),
        equipmentInstances = EntityStore(),
        manualStacks = EntityStore(),
        manualInstances = EntityStore(),
        pills = EntityStore(),
        materials = persistentMaterials,
        herbs = EntityStore(),
        seeds = EntityStore(),
        storageBags = EntityStore(),
        teams = teams.value,
        battleLogs = battleLogs.value,
        isPaused = isPaused.value,
        isLoading = isLoading.value,
        isSaving = isSaving.value
    )
}
