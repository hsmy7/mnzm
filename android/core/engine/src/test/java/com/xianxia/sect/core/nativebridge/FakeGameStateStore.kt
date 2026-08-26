package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.state.BootPhase
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.PendingBeastAttack
import com.xianxia.sect.core.state.PendingMarriageProposal
import com.xianxia.sect.core.state.RunState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * FakeGameStateStore — 引擎测试共用 Fake GameStateStore（CLAUDE.md 9.4 手写 Fake）。
 *
 * 仅实现镜像同步/增量应用测试所需的最小面（update/updateAndReturn/
 * takeAtomicSnapshot/实体列表/原子快照）；未用接口成员为合法 no-op 桩。
 * 从 DiffStateSyncTest 内部类提取为顶级类——StateSyncService、增量镜像
 * （applyDirty）、TimeSystem 对拍等测试复用同一实现，避免多份拷贝漂移。
 */
@Suppress("EmptyFunctionBlock")  // 未用接口成员为合法 no-op 桩（测试只验证被测服务自身逻辑）
open class FakeGameStateStore : GameStateStore {
    var gameDataValue = GameData()
    var disciplesValue: List<Disciple> = emptyList()
    var equipmentStacksValue: List<EquipmentStack> = emptyList()
    var equipmentInstancesValue: List<com.xianxia.sect.core.model.EquipmentInstance> = emptyList()
    var manualStacksValue: List<com.xianxia.sect.core.model.ManualStack> = emptyList()
    var manualInstancesValue: List<com.xianxia.sect.core.model.ManualInstance> = emptyList()
    var pillsValue: List<Pill> = emptyList()
    var materialsValue: List<Material> = emptyList()
    var herbsValue: List<Herb> = emptyList()
    var seedsValue: List<Seed> = emptyList()
    var storageBagsValue: List<com.xianxia.sect.core.model.StorageBag> = emptyList()

    // 记录 update 调用（验证单事务）
    var updateCallCount = 0

    override fun update(block: MutableGameState.() -> Unit) {
        updateCallCount++
        val mgs = mutableState()
        mgs.block()
        persistFrom(mgs)
    }

    override fun <R> updateAndReturn(block: MutableGameState.() -> R): R {
        updateCallCount++
        val mgs = mutableState()
        val result = mgs.block()
        persistFrom(mgs)
        return result
    }

    /** 构建可写事务态（与生产 store 相同的字段面）。 */
    private fun mutableState(): MutableGameState = MutableGameState(
        gameData = gameDataValue,
        discipleTables = DiscipleTables().also {
            it.writeAllowed = true
            it.replaceAll(disciplesValue)
        },
        equipmentStacks = EntityStore(equipmentStacksValue),
        equipmentInstances = EntityStore(equipmentInstancesValue),
        manualStacks = EntityStore(manualStacksValue),
        manualInstances = EntityStore(manualInstancesValue),
        pills = EntityStore(pillsValue),
        materials = EntityStore(materialsValue),
        herbs = EntityStore(herbsValue),
        seeds = EntityStore(seedsValue),
        storageBags = EntityStore(storageBagsValue),
        battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )

    /** 事务结束后回读各存储到 Fake 字段。 */
    private fun persistFrom(mgs: MutableGameState) {
        gameDataValue = mgs.gameData
        disciplesValue = mgs.discipleTables.assembleAll()
        equipmentStacksValue = mgs.equipmentStacks.all()
        equipmentInstancesValue = mgs.equipmentInstances.all()
        manualStacksValue = mgs.manualStacks.all()
        manualInstancesValue = mgs.manualInstances.all()
        pillsValue = mgs.pills.all()
        materialsValue = mgs.materials.all()
        herbsValue = mgs.herbs.all()
        seedsValue = mgs.seeds.all()
        storageBagsValue = mgs.storageBags.all()
    }

    override fun takeAtomicSnapshot(): GameStateStore.GameSnapshot = GameStateStore.GameSnapshot(
        gameData = gameDataValue,
        disciples = disciplesValue,
        equipmentStacks = equipmentStacksValue,
        equipmentInstances = equipmentInstancesValue,
        manualStacks = manualStacksValue,
        manualInstances = manualInstancesValue,
        pills = pillsValue,
        materials = materialsValue,
        herbs = herbsValue,
        seeds = seedsValue,
        storageBags = storageBagsValue
    )

    // ── 只读流（最小桩）──
    override val gameData: StateFlow<GameData> get() = MutableStateFlow(gameDataValue)
    override val disciples: StateFlow<List<Disciple>> get() = MutableStateFlow(disciplesValue)
    override val discipleTables: DiscipleTables
        get() = DiscipleTables().also {
            it.writeAllowed = true
            it.replaceAll(disciplesValue)
        }
    override val equipmentStacks: StateFlow<List<EquipmentStack>> get() = MutableStateFlow(equipmentStacksValue)
    override val equipmentInstances: StateFlow<List<com.xianxia.sect.core.model.EquipmentInstance>>
        get() = MutableStateFlow(equipmentInstancesValue)
    override val manualStacks: StateFlow<List<com.xianxia.sect.core.model.ManualStack>>
        get() = MutableStateFlow(manualStacksValue)
    override val manualInstances: StateFlow<List<com.xianxia.sect.core.model.ManualInstance>>
        get() = MutableStateFlow(manualInstancesValue)
    override val pills: StateFlow<List<Pill>> get() = MutableStateFlow(pillsValue)
    override val materials: StateFlow<List<Material>> get() = MutableStateFlow(materialsValue)
    override val herbs: StateFlow<List<Herb>> get() = MutableStateFlow(herbsValue)
    override val seeds: StateFlow<List<Seed>> get() = MutableStateFlow(seedsValue)
    override val storageBags: StateFlow<List<com.xianxia.sect.core.model.StorageBag>>
        get() = MutableStateFlow(storageBagsValue)
    override val battleLogs: StateFlow<List<BattleLog>>
        get() = MutableStateFlow(emptyList())
    override val isPaused: StateFlow<Boolean> get() = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> get() = MutableStateFlow(false)
    override val isSaving: StateFlow<Boolean> get() = MutableStateFlow(false)
    override val pendingBattleResult: StateFlow<BattleResultUIData?>
        get() = MutableStateFlow(null)
    override val pendingNotification: StateFlow<GameNotification?>
        get() = MutableStateFlow(null)
    override val rewardCardQueue: StateFlow<List<RewardCardItem>>
        get() = MutableStateFlow(emptyList())
    override val pendingBattleRewardCards: StateFlow<List<RewardCardItem>>
        get() = MutableStateFlow(emptyList())
    override val pendingBeastAttacks: StateFlow<List<PendingBeastAttack>>
        get() = MutableStateFlow(emptyList())
    override val pendingMarriageProposals: StateFlow<List<PendingMarriageProposal>>
        get() = MutableStateFlow(emptyList())
    override val highFreqState: StateFlow<GameStateStore.HighFreqState>
        get() = MutableStateFlow(GameStateStore.HighFreqState())
    override val entityState: StateFlow<GameStateStore.EntityState>
        get() = MutableStateFlow(GameStateStore.EntityState())
    override val configState: StateFlow<GameStateStore.ConfigState>
        get() = MutableStateFlow(GameStateStore.ConfigState())
    override val sectCombatPower: StateFlow<Long> get() = MutableStateFlow(0L)
    override val aiSectCombatPowers: StateFlow<Map<String, Long>> get() = MutableStateFlow(emptyMap())
    override val discipleAggregates: StateFlow<List<com.xianxia.sect.core.model.DiscipleAggregate>>
        get() = MutableStateFlow(emptyList())
    override val discipleAggregatesSnapshot: List<com.xianxia.sect.core.model.DiscipleAggregate> get() = emptyList()
    override val warehouseFullEvent: MutableSharedFlow<String>
        get() = MutableSharedFlow()
    override val notifications: StateFlow<List<GameNotification>>
        get() = MutableStateFlow(emptyList())

    // ── GameStateSnapshotProvider 快照属性 ──
    override val gameDataSnapshot: GameData get() = gameDataValue
    override val disciplesSnapshot: List<Disciple> get() = disciplesValue
    override val equipmentStacksSnapshot: List<EquipmentStack> get() = equipmentStacksValue
    override val equipmentInstancesSnapshot: List<com.xianxia.sect.core.model.EquipmentInstance>
        get() = equipmentInstancesValue
    override val manualStacksSnapshot: List<com.xianxia.sect.core.model.ManualStack> get() = manualStacksValue
    override val manualInstancesSnapshot: List<com.xianxia.sect.core.model.ManualInstance>
        get() = manualInstancesValue
    override val pillsSnapshot: List<Pill> get() = pillsValue
    override val materialsSnapshot: List<Material> get() = materialsValue
    override val herbsSnapshot: List<Herb> get() = herbsValue
    override val seedsSnapshot: List<Seed> get() = seedsValue
    override val storageBagsSnapshot: List<com.xianxia.sect.core.model.StorageBag> get() = storageBagsValue
    override val battleLogsSnapshot: List<BattleLog> get() = emptyList()

    override fun enqueueNotification(notification: GameNotification) {}
    override fun consumeNotification(): GameNotification? = null
    override fun clearPendingNotification() {}
    override fun setPendingBattleResult(result: BattleResultUIData) {}
    override fun clearPendingBattleResult() {}
    override fun setPendingBeastAttacks(attacks: List<PendingBeastAttack>) {}
    override fun clearPendingBeastAttacks() {}
    override fun removePendingBeastAttack(beastLevelId: String) {}
    override fun clearPendingMarriageProposals() {}
    override fun setPendingBattleRewardCards(cards: List<RewardCardItem>) {}
    override fun clearPendingBattleRewardCards() {}
    override fun enqueueRewardCards(items: List<RewardCardItem>) {}
    override fun clearRewardCardQueue(count: Int) {}
    override fun getCurrentSeeds(): List<Seed> = seedsValue
    override fun getCurrentHerbs(): List<Herb> = herbsValue
    override fun getCurrentMaterials(): List<Material> = materialsValue

    // === 交互状态与生命周期 ===
    override var activeTab: String = "main"
    override var activeDialog: String? = null
    override var activeSubDialogs: Set<String> = emptySet()
    override val lifecycleState: StateFlow<GameStateStore.LifecycleState>
        get() = MutableStateFlow(GameStateStore.LifecycleState())
    override val bootPhase: StateFlow<BootPhase> get() = MutableStateFlow(BootPhase.UNINITIALIZED)
    override val runState: StateFlow<RunState> get() = MutableStateFlow(RunState.IDLE)
    override fun advanceBootPhase() {}
    override fun resetBootPhase() {}
    override fun setPlaying() {}
    override fun setReloading() {}
    override fun modifyState(block: MutableGameState.() -> Unit) = update(block)
    override fun setLoading() {}
    override fun setPausedDirect(paused: Boolean) {}
    override fun setLoadingDirect(loading: Boolean) {}
    override fun setSavingDirect(saving: Boolean) {}

    override suspend fun loadFromSnapshot(
        gameData: GameData,
        disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack>,
        equipmentInstances: List<com.xianxia.sect.core.model.EquipmentInstance>,
        manualStacks: List<com.xianxia.sect.core.model.ManualStack>,
        manualInstances: List<com.xianxia.sect.core.model.ManualInstance>,
        pills: List<Pill>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>,
        storageBags: List<com.xianxia.sect.core.model.StorageBag>,
        battleLogs: List<BattleLog>,
        isPaused: Boolean,
        isLoading: Boolean,
        isSaving: Boolean
    ) {
        gameDataValue = gameData
        disciplesValue = disciples
        equipmentStacksValue = equipmentStacks
        equipmentInstancesValue = equipmentInstances
        manualStacksValue = manualStacks
        manualInstancesValue = manualInstances
        pillsValue = pills
        materialsValue = materials
        herbsValue = herbs
        seedsValue = seeds
        storageBagsValue = storageBags
    }

    override suspend fun reset() {}
    override fun registerTransactionObserver(observer: GameStateStore.TransactionObserver) {}
}
