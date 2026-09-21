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
import com.xianxia.sect.core.nativebridge.NativeEngineFlag as NativeEngineFlagX
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
 *
 * 弟子持久化走生产口径（B20a 偏置消除，[dispatchAssemble]）：无弟子写入零组装、
 * 有写入走增量/patch 组装（GameStateStoreImpl.dispatchAssemble 同款）——
 * 旧面每事务 assembleAll() 全表组装是 Bench 登记的 O(D) 观测偏置，已消除。
 *
 * 稳态零写入断言（w3-13 防复发，handover §2.82）：[nonMirrorWriteCount] 计数
 * "非镜像入口（update/updateAndReturn/modifyState）提交的持久状态变更"且仅在
 * AUTHORITATIVE 模式下计数（flag-OFF 写入即真相，不是违规）。对拍用例据此断言
 * AUTHORITATIVE 稳态管线的 Kotlin 侧游戏写入为零。
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

    /**
     * 非镜像入口的持久状态变更计数（AUTHORITATIVE 下即"稳态违规写入"观测面）。
     * 镜像入口（[updateMirror]）不计——镜像写入源自 C++ 真相源，属合法投影。
     */
    var nonMirrorWriteCount = 0
        private set

    // ── 嵌套事务重入────────────────────────
    // 对齐生产 GameStateStoreImpl 的 ReentrantLock 重入语义（:913-1015）：
    // 最外层 update 创建事务 buffer（activeTransaction）；嵌套 update 检测到
    // 活跃事务时**复用同一 buffer** 执行 block（不 persist、不计数、
    // 不递增 updateCallCount）——内层写入进入外层事务，最外层结束时统一
    // persistFrom 与计数，内层写入不被外层提交覆盖。
    private var activeTransaction: MutableGameState? = null

    override fun update(block: MutableGameState.() -> Unit) {
        updateInternal(block, mirror = false)
    }

    /**
     * 镜像专用事务更新（对齐生产 GameStateStoreImpl.updateMirror）：
     * C++ → Kotlin 前向镜像的投影写入入口——镜像只读契约的命名约定。
     */
    override fun updateMirror(block: MutableGameState.() -> Unit) {
        updateInternal(block, mirror = true)
    }

    private fun updateInternal(block: MutableGameState.() -> Unit, mirror: Boolean) {
        val active = activeTransaction
        if (active != null) {
            active.block()
            return
        }
        updateCallCount++
        val mgs = mutableState()
        activeTransaction = mgs
        try {
            val baseline = CaptureBaseline(gameDataValue, collectionValues())
            mgs.block()
            persistCollections(mgs)
            if (!mirror) countNonMirrorWrite(baseline, mgs)
            // 生产口径：dispatchAssemble 在提交判定（countNonMirrorWrite 的
            // isDirty 检查）之后消费 trackers——顺序对齐 GameStateStoreImpl
            dispatchAssemble(mgs)
            // 事务表晋升为提交基线（生产 _discipleTables = 提交表同语义，
            // 出厂即锁写）；COW 共享存储下下一事务副本零列写（见 [mutableState]）
            committedTables = mgs.discipleTables.also { it.writeAllowed = false }
            committedSource = disciplesValue
        } finally {
            activeTransaction = null
        }
    }

    override fun <R> updateAndReturn(block: MutableGameState.() -> R): R {
        val active = activeTransaction
        if (active != null) {
            return active.block()
        }
        updateCallCount++
        val mgs = mutableState()
        activeTransaction = mgs
        try {
            val baseline = CaptureBaseline(gameDataValue, collectionValues())
            val result = mgs.block()
            persistCollections(mgs)
            countNonMirrorWrite(baseline, mgs)
            dispatchAssemble(mgs)
            committedTables = mgs.discipleTables.also { it.writeAllowed = false }
            committedSource = disciplesValue
            return result
        } finally {
            activeTransaction = null
        }
    }

    private data class CaptureBaseline(
        val gameData: GameData,
        val collections: List<List<*>>
    )

    private fun collectionValues(): List<List<*>> = listOf(
        equipmentStacksValue, equipmentInstancesValue, manualStacksValue,
        manualInstancesValue, pillsValue, materialsValue, herbsValue,
        seedsValue, storageBagsValue
    )

    private fun collectionNames(): List<String> = listOf(
        "equipmentStacks", "equipmentInstances", "manualStacks",
        "manualInstances", "pills", "materials", "herbs",
        "seeds", "storageBags"
    )

    /** 非镜像入口提交了持久状态变更 ⇒ AUTHORITATIVE 下计数（稳态零写入断言观测面）。 */
    private fun countNonMirrorWrite(baseline: CaptureBaseline, mgs: MutableGameState) {
        if (!NativeEngineFlagX.authoritative) return
        val gameDataChanged = mgs.gameData !== baseline.gameData
        val current = listOf(
            mgs.equipmentStacks.items, mgs.equipmentInstances.items, mgs.manualStacks.items,
            mgs.manualInstances.items, mgs.pills.items, mgs.materials.items,
            mgs.herbs.items, mgs.seeds.items, mgs.storageBags.items
        )
        val collectionsChanged = collectionNames().indices.any { i ->
            baseline.collections[i] !== current[i]
        }
        val disciplesChanged = mgs.discipleTables.dirtyTracker.isDirty
        if (gameDataChanged || collectionsChanged || disciplesChanged) {
            nonMirrorWriteCount++
        }
    }

    /**
     * 事务态构建（B20c COW 保真）：优先从提交基线表 [deepCopy]——COW 路径
     * 每列 O(1) 存储共享、零列写（生产 GameStateStoreImpl 每事务
     * deepCopy 同语义）；仅当无有效基线（初次 / 测试直改 [disciplesValue]
     * 使源列表引用失效）时才走 replaceAll 全列写重建（ trackers 消费 =
     * 模拟生产"提交态 trackers 空"，构造期记录不流入本事务）。
     */
    private fun mutableState(): MutableGameState {
        val committed = committedTables
        val tables = if (committed != null && committedSource === disciplesValue) {
            committed.deepCopy().also { it.writeAllowed = true }
        } else {
            DiscipleTables().also {
                it.writeAllowed = true
                it.replaceAll(disciplesValue)
                it.changedIdTracker.consumeChangedIds()
                it.dirtyTracker.consumeDirtyColumns()
            }
        }
        return MutableGameState(
            gameData = gameDataValue,
            discipleTables = tables,
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
    }

    /** 事务表晋升为提交基线（COW 源）；committedSource 恒与其配套失效检测。 */
    private var committedTables: DiscipleTables? = null
    private var committedSource: List<Disciple>? = null

    /** 事务结束后回读 gameData 与实体集合到 Fake 字段（弟子面走 [dispatchAssemble]）。 */
    private fun persistCollections(mgs: MutableGameState) {
        gameDataValue = mgs.gameData
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

    /**
     * 生产口径的弟子组装（B20a 偏置消除，对齐 GameStateStoreImpl.dispatchAssemble）。
     *
     * 旧面 persistFrom 每事务 `assembleAll()` 全表组装 = MirrorSegmentProjection
     * BenchTest 登记的消费侧 O(D) 观测偏置（两臂各背同一常数，且在 D=1000 档
     * 掩盖列级臂真实成本）。本方法对齐生产三判据：
     * ① 无弟子写入（dirtyTracker 空）→ 零组装（生产 commitUpdateState 同判据）；
     * ② changedIds ≳ 半表 → 子对象级 patch 组装（assembleAllPatched，脏组外
     *    复用 prev 子对象引用）；
     * ③ 稀疏变更 → 双指针增量归并（assembleAllIncremental，未变弟子复用旧引用）；
     * 容量拒绝（rejectedRecord）强制全量兜底，与生产同序。组装产物与 assembleAll
     * 逐字段全等由 DiscipleTables 增量族内部守卫 + Bench 两臂落库全等断言看护。
     */
    private fun dispatchAssemble(mgs: MutableGameState) {
        val tables = mgs.discipleTables
        if (!tables.dirtyTracker.isDirty) return
        val changedIds = tables.changedIdTracker.consumeChangedIds()
        val forceFullAssemble = tables.changedIdTracker.consumeRejectedRecord()
        val dirtyColumns = tables.dirtyTracker.consumeDirtyColumns()
        disciplesValue = if (!forceFullAssemble && changedIds.isNotEmpty()) {
            if (changedIds.size >= disciplesValue.size / 2) {
                tables.assembleAllPatched(disciplesValue, changedIds, dirtyColumns)
            } else {
                tables.assembleAllIncremental(disciplesValue, changedIds)
            }
        } else {
            tables.assembleAll()
        }
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
    // 对齐生产 GameStateStoreImpl 共享语义——生产 discipleTables 为
    // 事务内共享可变实例（钩子标记/状态变更对同事务后序读取可见；
    // 每次访问重建副本会丢失事务内前序写入，如教化之道钩子
    // 的 lastTheftJudgementYears 标记 → 子事件 3 兜底误判 hasCandidate 重复
    // 判定，与 C++ 当前态行为漂移）。事务内返回 activeTransaction 共享表，
    // 事务外仍返回 committed 副本（只读桩语义）。
    override val discipleTables: DiscipleTables
        get() = activeTransaction?.discipleTables ?: DiscipleTables().also {
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
