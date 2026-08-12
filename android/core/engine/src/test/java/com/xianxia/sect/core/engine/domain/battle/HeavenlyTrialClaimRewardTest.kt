package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.HeavenlyTrialSaveData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.ManualDatabase.ManualTemplate
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.BootPhase
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 天道试炼通关奖励领取链路测试。
 *
 * 历史 bug（2026-08-05）：奖励装备/功法直接写 equipmentInstances/manualInstances
 * （实例轨道），而仓库 UI 只渲染 equipmentStacks/manualStacks（堆叠轨道），
 * 玩家领取后物品不可见。修复后统一委托 InventorySystem.addXxx。
 *
 * 使用支持 COW 副本 + 重入缓冲的 [TrialTestStore]（模拟 GameStateStoreImpl 事务语义），
 * 验证物品落堆叠轨道、来源追踪、容量不足时事务整体回滚（凭据保留可重试）。
 */
class HeavenlyTrialClaimRewardTest {

    private lateinit var store: TrialTestStore
    private lateinit var service: HeavenlyTrialService

    @Before
    fun setUp() {
        store = TrialTestStore()
        ManualDatabase.initializeWithManuals(mapOf(
            "trial_manual_5" to ManualTemplate(
                id = "trial_manual_5",
                name = "试炼功法",
                type = ManualType.ATTACK,
                rarity = 5,
                description = "测试用功法",
                stats = emptyMap(),
                skillName = "普通攻击",
                skillDamageType = "physical",
                skillDamageMultiplier = 1.0,
                skillCooldown = 0,
                skillMpCost = 0,
                skillHits = 1,
                minRealm = 9
            )
        ))
        service = buildService()
    }

    /**
     * 构造 service。
     * 注意：StackableItemStore 槽位上限由 GameConfig.Warehouse 常量决定，
     * 与 gameConfigProvider mock 无关（容量不足用例用预置占满方式构造）。
     */
    private fun buildService(): HeavenlyTrialService {
        val inventoryConfig = mock<InventoryConfig>()
        whenever(inventoryConfig.getMaxStackSize(org.mockito.kotlin.any())).thenReturn(99)
        val provider = mock<GameConfigProvider>()
        val warehouseConfig = mock<GameConfigProvider.WarehouseConfig>()
        whenever(warehouseConfig.baseCapacity).thenReturn(50)
        whenever(warehouseConfig.capacityPerBuilding).thenReturn(75)
        whenever(provider.warehouse).thenReturn(warehouseConfig)
        val inventorySystem = InventorySystem(
            stateStore = store,
            inventoryConfig = inventoryConfig,
            spiritStoneWallet = mock(),
            gameConfigProvider = provider
        )
        return HeavenlyTrialService(
            stateStore = store,
            inventoryConfig = inventoryConfig,
            spiritStoneWallet = mock(),
            inventorySystem = inventorySystem
        )
    }

    /** 预设某关双阶段通关状态（领取前置条件） */
    private fun markLevelFullyCleared(levelIndex: Int) {
        store.gameDataValue = store.gameDataValue.copy(
            heavenlyTrialState = HeavenlyTrialSaveData(
                phase1ClearedLevels = listOf(levelIndex),
                phase2ClearedLevels = listOf(levelIndex)
            )
        )
    }

    @Test
    fun `claimClearReward - 第六关领取后装备进入 equipmentStacks 而非 instances`() = runTest {
        markLevelFullyCleared(5)

        val result = service.claimClearReward(5)

        assertTrue("应返回 Success", result is ClaimClearRewardResult.Success)
        val stacks = store.equipmentStacks.value
        assertEquals("装备堆叠总数量应为 5", 5, stacks.sumOf { it.quantity })
        assertTrue("装备稀有度应为地品（5）", stacks.all { it.rarity == 5 })
        assertTrue(
            "实例表应为空（修复前装备写入 equipmentInstances，仓库 UI 不渲染导致不可见）",
            store.equipmentInstances.value.isEmpty()
        )
        assertEquals(
            "储物袋奖励应正常入仓（10 个玄品储物袋）",
            10,
            store.storageBags.value.sumOf { it.quantity }
        )
        assertEquals("应记录领取凭据", listOf(5), store.gameDataValue.heavenlyTrialState.claimedRewardLevels)
        val cards = (result as ClaimClearRewardResult.Success).cards
        assertEquals(
            "奖励卡片装备数量合计应为 5",
            5,
            cards.filter { it.itemType == "equipment" }.sumOf { it.quantity }
        )
    }

    @Test
    fun `claimClearReward - 第七关领取后功法进入 manualStacks`() = runTest {
        markLevelFullyCleared(6)

        val result = service.claimClearReward(6)

        assertTrue("应返回 Success", result is ClaimClearRewardResult.Success)
        val stacks = store.manualStacks.value
        assertEquals("功法堆叠总数量应为 5", 5, stacks.sumOf { it.quantity })
        assertTrue(
            "实例表应为空（修复前功法写入 manualInstances，仓库 UI 不渲染导致不可见）",
            store.manualInstances.value.isEmpty()
        )
        assertEquals("应记录领取凭据", listOf(6), store.gameDataValue.heavenlyTrialState.claimedRewardLevels)
    }

    @Test
    fun `claimClearReward - 发放记录年度来源 trial`() = runTest {
        markLevelFullyCleared(5)

        service.claimClearReward(5)

        val annual = store.gameDataValue.annualEquipmentBySource
        assertEquals("年度装备来源应记录 trial:5 共 5 件", 5, annual["trial:5"])
    }

    @Test
    fun `claimClearReward - 重复领取返回 AlreadyClaimed`() = runTest {
        markLevelFullyCleared(5)
        service.claimClearReward(5)

        val second = service.claimClearReward(5)

        assertEquals(ClaimClearRewardResult.AlreadyClaimed, second)
        assertEquals(1, store.gameDataValue.heavenlyTrialState.claimedRewardLevels.size)
    }

    @Test
    fun `claimClearReward - 未通关返回 LevelNotCleared`() = runTest {
        val result = service.claimClearReward(5)

        assertEquals(ClaimClearRewardResult.LevelNotCleared, result)
        assertTrue(store.gameDataValue.heavenlyTrialState.claimedRewardLevels.isEmpty())
    }

    @Test
    fun `claimClearReward - 仓库容量满时返回 CapacityInsufficient 且事务整体回滚`() = runTest {
        // 预置占满全部仓库槽位的装备堆叠（无仓库建筑时容量 = BASE_CAPACITY）：
        // 第 1 件奖励装备必然无槽可建（名字不匹配预置堆叠）→ addXxx Failure →
        // 抛异常整体回滚，前序/后续发放（储物袋）均不落库，凭据保留可重试
        val baseCapacity = GameConfig.Warehouse.BASE_CAPACITY
        store.update {
            equipmentStacks.replaceAll(
                (0 until baseCapacity).map { i ->
                    EquipmentStack(
                        id = "pre_fill_$i",
                        name = "预置装备$i",
                        rarity = 1,
                        slot = EquipmentSlot.WEAPON,
                        quantity = 1
                    )
                }
            )
        }
        markLevelFullyCleared(5)

        val result = service.claimClearReward(5)

        assertTrue("应返回 CapacityInsufficient", result is ClaimClearRewardResult.CapacityInsufficient)
        assertEquals("预置堆叠不得被部分发放污染", baseCapacity, store.equipmentStacks.value.size)
        assertTrue("储物袋不得发放（同事务回滚）", store.storageBags.value.isEmpty())
        assertTrue(
            "凭据不得写入，玩家清理仓库后可重试",
            store.gameDataValue.heavenlyTrialState.claimedRewardLevels.isEmpty()
        )
    }
}

/**
 * 测试用 GameStateStore：模拟 GameStateStoreImpl 的事务语义——
 * - update 事务基于 COW 快照副本（EntityStore 独立拷贝），提交时写回
 * - 嵌套 update（重入）复用当前事务缓冲（与 reentrantBuffer 语义一致）
 * - block 抛异常 → 不提交 → 整体回滚
 */
private class TrialTestStore : GameStateStore {

    var gameDataValue: GameData = GameData()

    private val _gameDataFlow = MutableStateFlow(GameData())
    override val gameData: StateFlow<GameData> get() = _gameDataFlow
    override val gameDataSnapshot: GameData get() = gameDataValue

    private val _tables = DiscipleTables()
    override val discipleTables: DiscipleTables get() = _tables

    // 持久 EntityStore（事务提交时 replaceAll 写回）
    private val eqStacks = EntityStore<EquipmentStack>()
    private val eqInstances = EntityStore<EquipmentInstance>()
    private val mnStacks = EntityStore<ManualStack>()
    private val mnInstances = EntityStore<ManualInstance>()
    private val pils = EntityStore<Pill>()
    private val mats = EntityStore<Material>()
    private val hrbs = EntityStore<Herb>()
    private val sds = EntityStore<Seed>()
    private val stBags = EntityStore<StorageBag>()

    // 仓库 StateFlow（事务提交时同步）
    private val _equipmentStacks = MutableStateFlow<List<EquipmentStack>>(emptyList())
    private val _equipmentInstances = MutableStateFlow<List<EquipmentInstance>>(emptyList())
    private val _manualStacks = MutableStateFlow<List<ManualStack>>(emptyList())
    private val _manualInstances = MutableStateFlow<List<ManualInstance>>(emptyList())
    private val _pills = MutableStateFlow<List<Pill>>(emptyList())
    private val _materials = MutableStateFlow<List<Material>>(emptyList())
    private val _herbs = MutableStateFlow<List<Herb>>(emptyList())
    private val _seeds = MutableStateFlow<List<Seed>>(emptyList())
    private val _storageBags = MutableStateFlow<List<StorageBag>>(emptyList())
    override val equipmentStacks: StateFlow<List<EquipmentStack>> get() = _equipmentStacks
    override val equipmentInstances: StateFlow<List<EquipmentInstance>> get() = _equipmentInstances
    override val manualStacks: StateFlow<List<ManualStack>> get() = _manualStacks
    override val manualInstances: StateFlow<List<ManualInstance>> get() = _manualInstances
    override val pills: StateFlow<List<Pill>> get() = _pills
    override val materials: StateFlow<List<Material>> get() = _materials
    override val herbs: StateFlow<List<Herb>> get() = _herbs
    override val seeds: StateFlow<List<Seed>> get() = _seeds
    override val storageBags: StateFlow<List<StorageBag>> get() = _storageBags

    // 当前事务缓冲（重入时复用）
    private var txMutable: MutableGameState? = null

    override fun <R> updateAndReturn(block: MutableGameState.() -> R): R {
        val active = txMutable
        if (active != null) {
            // 重入：直接在当前事务缓冲上执行（与 GameStateStoreImpl reentrantBuffer 语义一致）
            return block(active)
        }
        val mutable = MutableGameState(
            gameData = gameDataValue,
            discipleTables = _tables,
            equipmentStacks = EntityStore(eqStacks.all()),
            equipmentInstances = EntityStore(eqInstances.all()),
            manualStacks = EntityStore(mnStacks.all()),
            manualInstances = EntityStore(mnInstances.all()),
            pills = EntityStore(pils.all()),
            materials = EntityStore(mats.all()),
            herbs = EntityStore(hrbs.all()),
            seeds = EntityStore(sds.all()),
            storageBags = EntityStore(stBags.all()),
                        battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
        txMutable = mutable
        try {
            val result = block(mutable)
            // 提交：写回持久 EntityStore + gameData + StateFlow
            gameDataValue = mutable.gameData
            _gameDataFlow.value = mutable.gameData
            eqStacks.replaceAll(mutable.equipmentStacks.all())
            eqInstances.replaceAll(mutable.equipmentInstances.all())
            mnStacks.replaceAll(mutable.manualStacks.all())
            mnInstances.replaceAll(mutable.manualInstances.all())
            pils.replaceAll(mutable.pills.all())
            mats.replaceAll(mutable.materials.all())
            hrbs.replaceAll(mutable.herbs.all())
            sds.replaceAll(mutable.seeds.all())
            stBags.replaceAll(mutable.storageBags.all())
            _equipmentStacks.value = mutable.equipmentStacks.all()
            _equipmentInstances.value = mutable.equipmentInstances.all()
            _manualStacks.value = mutable.manualStacks.all()
            _manualInstances.value = mutable.manualInstances.all()
            _pills.value = mutable.pills.all()
            _materials.value = mutable.materials.all()
            _herbs.value = mutable.herbs.all()
            _seeds.value = mutable.seeds.all()
            _storageBags.value = mutable.storageBags.all()
            return result
        } finally {
            txMutable = null
        }
    }

    override fun update(block: MutableGameState.() -> Unit) {
        updateAndReturn {
            block()
            Unit
        }
    }

    override fun modifyState(block: MutableGameState.() -> Unit) = update(block)

    // === 以下为测试用空实现/透传（参照 GameEngineCoordinationTest.SimpleStore） ===

    override val disciples = MutableStateFlow<List<Disciple>>(emptyList())
    override val discipleAggregates = MutableStateFlow<List<DiscipleAggregate>>(emptyList())
    override val sectCombatPower = MutableStateFlow(0L)
    override val aiSectCombatPowers = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val highFreqState = MutableStateFlow(GameStateStore.HighFreqState())
    override val entityState = MutableStateFlow(GameStateStore.EntityState())
    override val configState = MutableStateFlow(GameStateStore.ConfigState())
    override val disciplesSnapshot: List<Disciple> get() = emptyList()
    override val equipmentStacksSnapshot: List<EquipmentStack> get() = eqStacks.all()
    override val equipmentInstancesSnapshot: List<EquipmentInstance> get() = eqInstances.all()
    override val manualStacksSnapshot: List<ManualStack> get() = mnStacks.all()
    override val manualInstancesSnapshot: List<ManualInstance> get() = mnInstances.all()
    override val pillsSnapshot: List<Pill> get() = pils.all()
    override val materialsSnapshot: List<Material> get() = mats.all()
    override val herbsSnapshot: List<Herb> get() = hrbs.all()
    override val seedsSnapshot: List<Seed> get() = sds.all()
    override val storageBagsSnapshot: List<StorageBag> get() = stBags.all()
    override val battleLogsSnapshot: List<BattleLog> get() = emptyList()
    override val discipleAggregatesSnapshot: List<DiscipleAggregate> get() = emptyList()
    override val notifications = MutableStateFlow<List<GameNotification>>(emptyList())
    override val warehouseFullEvent = MutableSharedFlow<String>()
    override var activeTab: String = ""
    override var activeDialog: String? = null
    override var activeSubDialogs: Set<String> = emptySet()
    override fun getCurrentSeeds(): List<Seed> = sds.all()
    override fun getCurrentHerbs(): List<Herb> = hrbs.all()
    override fun getCurrentMaterials(): List<Material> = mats.all()
    override fun enqueueNotification(notification: GameNotification) = Unit
    override fun consumeNotification(): GameNotification? = null
    @Suppress("OVERRIDE_DEPRECATION")
    override fun clearPendingNotification() = Unit
    override fun setPendingBattleResult(result: BattleResultUIData) = Unit
    override fun clearPendingBattleResult() = Unit
    override fun setPendingBeastAttacks(attacks: List<PendingBeastAttack>) = Unit
    override fun clearPendingBeastAttacks() = Unit
    override fun removePendingBeastAttack(beastLevelId: String) = Unit
    override fun clearPendingMarriageProposals() = Unit
    override fun setPendingBattleRewardCards(cards: List<RewardCardItem>) = Unit
    override fun clearPendingBattleRewardCards() = Unit
    override fun enqueueRewardCards(items: List<RewardCardItem>) = Unit
    override fun clearRewardCardQueue(count: Int) = Unit
    override val pendingBattleResult = MutableStateFlow<BattleResultUIData?>(null)
    override val pendingNotification = MutableStateFlow<GameNotification?>(null)
    override val rewardCardQueue = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val pendingBeastAttacks = MutableStateFlow<List<PendingBeastAttack>>(emptyList())
    override val pendingMarriageProposals = MutableStateFlow<List<PendingMarriageProposal>>(emptyList())
    override val pendingBattleRewardCards = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val battleLogs = MutableStateFlow<List<BattleLog>>(emptyList())
    override val isPaused = MutableStateFlow(false)
    override val isLoading = MutableStateFlow(false)
    override val isSaving = MutableStateFlow(false)
    override val lifecycleState = MutableStateFlow(GameStateStore.LifecycleState())
    override val bootPhase = MutableStateFlow(BootPhase.UNINITIALIZED)
    override val runState = MutableStateFlow(RunState.IDLE)
    override fun advanceBootPhase() = Unit
    override fun resetBootPhase() = Unit
    override fun setPlaying() = Unit
    override fun setReloading() = Unit
    override fun setLoading() = Unit
    override fun setIdle() = Unit
    override fun enterBatchEmissionMode() = Unit
    override fun exitBatchEmissionMode() = Unit
    override fun setPausedDirect(paused: Boolean) = Unit
    override fun setLoadingDirect(loading: Boolean) = Unit
    override fun setSavingDirect(saving: Boolean) = Unit
    override fun takeAtomicSnapshot(): GameStateStore.GameSnapshot = GameStateStore.GameSnapshot()
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
                battleLogs: List<BattleLog>,
        isPaused: Boolean,
        isLoading: Boolean,
        isSaving: Boolean
    ) {
        this.gameDataValue = gameData
    }

    override suspend fun reset() {
        gameDataValue = GameData()
    }
}
