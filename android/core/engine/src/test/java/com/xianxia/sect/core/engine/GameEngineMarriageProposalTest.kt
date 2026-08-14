package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
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
import com.xianxia.sect.core.state.WriteGuardRule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner



/**
 * 婚姻提议审批（approve/reject）的单元测试。
 *
 * 测试 [GameEngine.approveMarriageProposal] 和 [GameEngine.rejectMarriageProposal]
 * 的状态变更正确性。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class GameEngineMarriageProposalTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: MarriageProposalTestStore
    private lateinit var engine: GameEngine

    private val MALE_ID = "1"
    private val FEMALE_ID = "2"

    @Before
    fun setUp() {
        store = MarriageProposalTestStore()
        // 初始化弟子
        store.update {
            discipleTables.writeAllowed = true
            val m = MALE_ID.toInt()
            discipleTables.addId(m)
            discipleTables.names[m] = "男A"
            discipleTables.statuses[m] = DiscipleStatus.IDLE
            discipleTables.isAlive[m] = 1
            discipleTables.realms[m] = 9
            discipleTables.realmLayers[m] = 1
            discipleTables.portraitRes[m] = "portrait_a"
            discipleTables.genders[m] = "male"

            val f = FEMALE_ID.toInt()
            discipleTables.addId(f)
            discipleTables.names[f] = "女A"
            discipleTables.statuses[f] = DiscipleStatus.IDLE
            discipleTables.isAlive[f] = 1
            discipleTables.realms[f] = 9
            discipleTables.realmLayers[f] = 1
            discipleTables.portraitRes[f] = "portrait_b"
            discipleTables.genders[f] = "female"
            discipleTables.writeAllowed = false
        }

        // 创建一个婚姻提议
        store.update {
            gameData = gameData.copy(daoCompanionConsentRequired = true)
            pendingMarriageProposals = listOf(
                PendingMarriageProposal(MALE_ID, "男A", FEMALE_ID, "女A")
            )
        }


        // D1：构造时 highFrequencyData/productionSlots 经 Facade 访问器求值——stub 链防 NPE
        val mockProductionFacade = mock<ProductionFacade>()
        org.mockito.kotlin.whenever(mockProductionFacade.productionSlots)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        val mockCultivationFacade = mock<CultivationFacade>()
        org.mockito.kotlin.whenever(mockCultivationFacade.cultivationService).thenReturn(mock())
        org.mockito.kotlin.whenever(mockCultivationFacade.discipleService).thenReturn(mock())
        org.mockito.kotlin.whenever(mockCultivationFacade.productionFacade).thenReturn(mockProductionFacade)
        val mockPC = mock<ProductionCoordinator>()
        org.mockito.kotlin.whenever(mockPC.repository).thenReturn(mock())
        org.mockito.kotlin.whenever(mockCultivationFacade.productionCoordinator).thenReturn(mockPC)
        val mockInventoryFacade = mock<InventoryFacade>()
        org.mockito.kotlin.whenever(mockInventoryFacade.inventorySystem).thenReturn(mock())
        val mockEconomyFacade = mock<EconomyFacade>()
        org.mockito.kotlin.whenever(mockEconomyFacade.inventoryFacade).thenReturn(mockInventoryFacade)
        org.mockito.kotlin.whenever(mockEconomyFacade.mailService).thenReturn(mock())

        engine = GameEngine(
            gameEngineCore = mock(),
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = mock(),
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade,
            economyFacade = mockEconomyFacade,
            battleFacade = mock()
        )
    }

    // ── approve ─────────────────────────────────────────────────

    @Test
    fun `approve - proposers become partners`() {
        engine.approveMarriageProposal(MALE_ID, FEMALE_ID)
        // 检查 companion 设定
        assertEquals(FEMALE_ID, store._discipleTables.partnerIds.getOrNull(MALE_ID.toInt()))
        assertEquals(MALE_ID, store._discipleTables.partnerIds.getOrNull(FEMALE_ID.toInt()))
        // 提议应被清除
        assertTrue("approve 后提议应被移除", store.pendingMarriageProposalsValue.isEmpty())
    }

    @Test
    fun `approve - invalid male ID does nothing`() {
        engine.approveMarriageProposal("999", FEMALE_ID)
        assertNull(store._discipleTables.partnerIds.getOrNull(999))
        assertEquals(1, store.pendingMarriageProposalsValue.size)
    }

    @Test
    fun `approve - non-existent proposal does nothing`() {
        engine.approveMarriageProposal(MALE_ID, "999")
        assertNull(store._discipleTables.partnerIds.getOrNull(FEMALE_ID.toInt()))
    }

    @Test
    fun `approve - already partnered male skips pairing and removes proposal`() {
        store.update {
            discipleTables.partnerIds[MALE_ID.toInt()] = "99"
        }
        engine.approveMarriageProposal(MALE_ID, FEMALE_ID)
        // 原有配套不应被覆盖
        assertEquals("99", store._discipleTables.partnerIds.getOrNull(MALE_ID.toInt()))
        // 提议应被清理
        assertTrue("已有道侣时提议应被移除", store.pendingMarriageProposalsValue.isEmpty())
    }

    @Test
    fun `approve - already partnered female skips pairing`() {
        store.update {
            discipleTables.partnerIds[FEMALE_ID.toInt()] = "99"
        }
        engine.approveMarriageProposal(MALE_ID, FEMALE_ID)
        assertNull("女性已有道侣，男性不应被配对", store._discipleTables.partnerIds.getOrNull(MALE_ID.toInt()))
    }

    // ── reject ──────────────────────────────────────────────────

    @Test
    fun `reject - proposal is removed`() {
        engine.rejectMarriageProposal(MALE_ID, FEMALE_ID)
        assertTrue("reject 后提议应被移除", store.pendingMarriageProposalsValue.isEmpty())
        // 不应产生配对
        assertNull(store._discipleTables.partnerIds.getOrNull(MALE_ID.toInt()))
        assertNull(store._discipleTables.partnerIds.getOrNull(FEMALE_ID.toInt()))
    }

    @Test
    fun `reject - non-existent proposal is no-op`() {
        engine.rejectMarriageProposal(MALE_ID, "999")
        assertEquals("无关提议应保留", 1, store.pendingMarriageProposalsValue.size)
    }
}

// ── Fake State Store ──

private class MarriageProposalTestStore : GameStateStore {
    // 持久化 DiscipleTables（跨 update 持久化）
    val _discipleTables = DiscipleTables()
    private val _gameData = MutableStateFlow(GameData())
    private val _pendingProposals = MutableStateFlow<List<PendingMarriageProposal>>(emptyList())
    private val _updateVersion = MutableStateFlow(0L)

    val pendingMarriageProposalsValue: List<PendingMarriageProposal>
        get() = _pendingProposals.value

    override val pendingMarriageProposals: StateFlow<List<PendingMarriageProposal>> = _pendingProposals.asStateFlow()

    override fun clearPendingMarriageProposals() {
        _pendingProposals.value = emptyList()
    }

    // GameStateStore 必需实现（最小化存根）
    override val gameData: StateFlow<GameData> get() = _gameData
    override val disciples = MutableStateFlow<List<Disciple>>(emptyList())
    override val discipleTables: DiscipleTables get() = _discipleTables
    override val equipmentStacks = MutableStateFlow<List<EquipmentStack>>(emptyList())
    override val equipmentInstances = MutableStateFlow<List<EquipmentInstance>>(emptyList())
    override val manualStacks = MutableStateFlow<List<ManualStack>>(emptyList())
    override val manualInstances = MutableStateFlow<List<ManualInstance>>(emptyList())
    override val pills = MutableStateFlow<List<Pill>>(emptyList())
    override val materials = MutableStateFlow<List<Material>>(emptyList())
    override val herbs = MutableStateFlow<List<Herb>>(emptyList())
    override val seeds = MutableStateFlow<List<Seed>>(emptyList())
    override val storageBags = MutableStateFlow<List<StorageBag>>(emptyList())
    override val battleLogs = MutableStateFlow<List<BattleLog>>(emptyList())
    override val isPaused = MutableStateFlow(false)
    override val isLoading = MutableStateFlow(false)
    override val isSaving = MutableStateFlow(false)
    override val pendingBattleResult = MutableStateFlow<BattleResultUIData?>(null)
    override val pendingNotification = MutableStateFlow<GameNotification?>(null)
    override val rewardCardQueue = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val pendingBeastAttacks = MutableStateFlow<List<PendingBeastAttack>>(emptyList())
    override val pendingBattleRewardCards = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val highFreqState = MutableStateFlow(GameStateStore.HighFreqState())
    override val entityState = MutableStateFlow(GameStateStore.EntityState())
    override val configState = MutableStateFlow(GameStateStore.ConfigState())
    override val sectCombatPower = MutableStateFlow(0L)
    override val aiSectCombatPowers = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val discipleAggregates = MutableStateFlow<List<DiscipleAggregate>>(emptyList())
    override val discipleAggregatesSnapshot: List<DiscipleAggregate> get() = emptyList()
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
    override val battleLogsSnapshot: List<BattleLog> get() = battleLogs.value
    override val warehouseFullEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val notifications = MutableStateFlow<List<GameNotification>>(emptyList())
    override val lifecycleState = MutableStateFlow(GameStateStore.LifecycleState())
    override val bootPhase = MutableStateFlow(com.xianxia.sect.core.state.BootPhase.UNINITIALIZED)
    override val runState = MutableStateFlow(com.xianxia.sect.core.state.RunState.IDLE)
    @Deprecated("Use bootPhase/runState instead")
    override val gameDataSnapshot: GameData get() = _gameData.value
    override var activeTab: String = ""
    override var activeDialog: String? = null
    override var activeSubDialogs: Set<String> = emptySet()
    override fun getCurrentSeeds(): List<Seed> = emptyList()
    override fun getCurrentHerbs(): List<Herb> = emptyList()
    override fun getCurrentMaterials(): List<Material> = emptyList()
    override fun enqueueNotification(notification: GameNotification) {}
    override fun consumeNotification(): GameNotification? = null
    @Deprecated("Notifications are now queued. Use consumeNotification() instead.")
    override fun clearPendingNotification() {}
    override fun setPendingBattleResult(result: BattleResultUIData) {}
    override fun clearPendingBattleResult() {}
    override fun setPendingBeastAttacks(attacks: List<PendingBeastAttack>) {}
    override fun clearPendingBeastAttacks() {}
    override fun removePendingBeastAttack(beastLevelId: String) {}
    override fun setPendingBattleRewardCards(cards: List<RewardCardItem>) {}
    override fun clearPendingBattleRewardCards() {}
    override fun enqueueRewardCards(items: List<RewardCardItem>) {}
    override fun clearRewardCardQueue(count: Int) {}
    override fun advanceBootPhase() {}
    override fun resetBootPhase() {}
    override fun setPlaying() {}
    override fun setReloading() {}
    override fun setIdle() {}
    override fun setLoading() {}
    override fun setPausedDirect(paused: Boolean) {}
    override fun setLoadingDirect(loading: Boolean) {}
    override fun setSavingDirect(saving: Boolean) {}
    override fun takeAtomicSnapshot() = GameStateStore.GameSnapshot()
    override fun enterBatchEmissionMode() {}
    override fun exitBatchEmissionMode() {}

    // update() 实现：允许通过闭包修改持久化状态
    private var inUpdate = false
    private val reusableMutableState = MutableGameState(
        gameData = GameData(),
        discipleTables = DiscipleTables(),
        equipmentStacks = EntityStore(),
        equipmentInstances = EntityStore(),
        manualStacks = EntityStore(),
        manualInstances = EntityStore(),
        pills = EntityStore(),
        materials = EntityStore(),
        herbs = EntityStore(),
        seeds = EntityStore(),
        storageBags = EntityStore(),
        battleLogs = emptyList(),
                isPaused = false,
        isLoading = false,
        isSaving = false,
        pendingNotification = null,
        pendingMarriageProposals = emptyList()
    )

    override fun update(block: MutableGameState.() -> Unit) {
        if (inUpdate) {
            reusableMutableState.block()
            return
        }
        inUpdate = true
        try {
            // 当前值
            val curGame = _gameData.value
            val curTables = _discipleTables
            val curProposals = _pendingProposals.value

            reusableMutableState.apply {
                gameData = curGame
                discipleTables = curTables.also { }
                pendingMarriageProposals = curProposals
            }
            // 注：此 Fake 直接操作持久化实例 _discipleTables，
            // 但 block 中使用的是 reusableMutableState.discipleTables
            // 在近似实现中我们仅关注 gameData + pendingMarriageProposals
            val gameBefore = reusableMutableState.gameData
            val proposalsBefore = reusableMutableState.pendingMarriageProposals
            reusableMutableState.block()
            val gameChanged = reusableMutableState.gameData !== gameBefore
            val proposalsChanged = reusableMutableState.pendingMarriageProposals !== proposalsBefore
            if (gameChanged) _gameData.value = reusableMutableState.gameData
            if (proposalsChanged) _pendingProposals.value = reusableMutableState.pendingMarriageProposals
            // 特殊处理 discipleTables 的 partnerIds 变更
            if (reusableMutableState.discipleTables.partnerIds !== _discipleTables.partnerIds) {
                // 如果 block 创建了新的 tables，只复制 partnerIds
                for (id in reusableMutableState.discipleTables.ids) {
                    _discipleTables.partnerIds[id] = reusableMutableState.discipleTables.partnerIds.getOrNull(id)
                }
            }
            if (gameChanged || proposalsChanged) _updateVersion.value++
        } finally {
            inUpdate = false
        }
    }

    override fun <R> updateAndReturn(block: MutableGameState.() -> R): R {
        var result: R? = null
        update { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as R
    }

    override fun modifyState(block: MutableGameState.() -> Unit) { update(block) }

    override suspend fun loadFromSnapshot(
        gameData: GameData, disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack>, equipmentInstances: List<EquipmentInstance>,
        manualStacks: List<ManualStack>, manualInstances: List<ManualInstance>,
        pills: List<Pill>, materials: List<Material>, herbs: List<Herb>,
        seeds: List<Seed>, storageBags: List<StorageBag>,
        battleLogs: List<BattleLog>,
        isPaused: Boolean, isLoading: Boolean, isSaving: Boolean
    ) {}

    override suspend fun reset() {}
}
