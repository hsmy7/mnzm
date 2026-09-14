package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.WriteGuardRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * GameEngineResidualNativeTxGateTest — 残余域 native 臂门控降级守卫（batch-23）。
 *
 * 守护契约：
 * - 降级契约：flag OFF / AUTHORITATIVE 下 JVM 无生产 .so（GameCoreBridge 未加载）
 *   → ResidualNativeForward.tryForward 均返回 null → 调用方回退 Kotlin 原实现，
 *   回退臂语义与下沉前逐字一致（双实现并行契约）
 * - 镜像守卫：测试 mock（未 stub stateSyncServiceRef）返回 null sync → 先赋可空
 *   局部再判空（handover findings 13），不得 NPE
 * - 回退臂语义：妖兽视图锁定/解锁（含空 id 早退）；设置项 17 字段（bool 开关
 *   与 Int 集过滤）经域入口写入——下沉前后状态面逐项不变
 *
 * C++ 事务语义（AUTHORITATIVE + 生产桥）由 GTest lock_beast_tx_test.cpp（17 用例）
 * 与真机对拍框架逐位守护。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class GameEngineResidualNativeTxGateTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var engine: GameEngine
    private lateinit var discipleFacade: DiscipleFacade
    private lateinit var mockCore: GameEngineCore

    companion object {
        private const val BEAST_ID = "beast_1"
    }

    @Before
    fun setUp() {
        gate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        store = FakeAtomicStateStore()
        setupEngine()
    }

    /** mock GameEngine（8 构造参数），仅 stateStore + assignmentGate 为真实实现 */
    private fun setupEngine() {
        discipleFacade = mock()
        mockCore = mock<GameEngineCore>()
        whenever(mockCore.launchInScope(any())).thenAnswer { invocation ->
            val block = invocation.getArgument<suspend CoroutineScope.() -> Unit>(0)
            runBlocking { block(CoroutineScope(Dispatchers.Unconfined)) }
            mock<kotlinx.coroutines.Job>()
        }
        whenever(mockCore.scopeForStateIn()).thenReturn(CoroutineScope(Dispatchers.Unconfined))
        val mockBattleFacade = mock<BattleFacade>()
        whenever(mockBattleFacade.assignmentGate).thenReturn(gate)
        val mockProductionFacade = mock<ProductionFacade>()
        whenever(mockProductionFacade.productionSlots).thenReturn(MutableStateFlow(emptyList()))
        val mockCultivationFacade = mock<CultivationFacade>()
        whenever(mockCultivationFacade.cultivationService).thenReturn(mock())
        whenever(mockCultivationFacade.discipleService).thenReturn(mock())
        whenever(mockCultivationFacade.discipleFacade).thenReturn(discipleFacade)
        whenever(mockCultivationFacade.productionFacade).thenReturn(mockProductionFacade)
        val mockPC = mock<ProductionCoordinator>()
        whenever(mockPC.repository).thenReturn(mock())
        whenever(mockCultivationFacade.productionCoordinator).thenReturn(mockPC)
        val mockInventoryFacade = mock<InventoryFacade>()
        whenever(mockInventoryFacade.inventorySystem).thenReturn(mock())
        val mockEconomyFacade = mock<EconomyFacade>()
        whenever(mockEconomyFacade.inventoryFacade).thenReturn(mockInventoryFacade)
        whenever(mockEconomyFacade.mailService).thenReturn(mock())

        engine = GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = GameRngManager(),
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade,
            economyFacade = mockEconomyFacade,
            battleFacade = mockBattleFacade
        )
    }

    private fun lockedIds(): Set<String> = store.gameDataSnapshot.lockedBeastIds

    // ── 转发器门控（flag / 镜像守卫）──────────────────────────────

    @Test
    fun `tryForward returns null when flag is OFF`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertNull(ResidualNativeForward.tryForward(engine, 1730) { })
        }
    }

    @Test
    fun `tryForward returns null when sync service missing under AUTHORITATIVE`() = runTest {
        // JVM 环境：mock core 的 stateSyncServiceRef 未 stub → null sync →
        // 可空局部守卫返回 null（不得 NPE），桥未加载也走不到 native
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            assertNull(ResidualNativeForward.tryForward(engine, 1730) { })
        }
    }

    // ── 回退臂语义：妖兽视图锁定（flag OFF；与下沉前逐项一致）────

    @Test
    fun `lock beast falls back to kotlin arm when flag OFF`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.lockBeastView(BEAST_ID)
        }
        assertEquals(setOf(BEAST_ID), lockedIds())
    }

    @Test
    fun `lock beast duplicate keeps single entry`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.lockBeastView(BEAST_ID)
            engine.lockBeastView(BEAST_ID)
        }
        assertEquals(setOf(BEAST_ID), lockedIds())
    }

    @Test
    fun `unlock beast removes entry`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.lockBeastView(BEAST_ID)
            engine.unlockBeastView(BEAST_ID)
        }
        assertTrue(lockedIds().isEmpty())
    }

    @Test
    fun `unlock beast with empty id is noop`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.lockBeastView(BEAST_ID)
            engine.unlockBeastView("")
        }
        assertEquals(setOf(BEAST_ID), lockedIds())
    }

    // ── 回退臂语义：设置项字段（bool 开关 + Int 集）──────────────

    @Test
    fun `audio toggles fall back to kotlin arm when flag OFF`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.setSoundEnabled(false)
            engine.setMusicEnabled(false)
        }
        assertFalse(store.gameDataSnapshot.soundEnabled)
        assertFalse(store.gameDataSnapshot.musicEnabled)
    }

    @Test
    fun `settings toggles fall back to kotlin arm when flag OFF`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.setPatrolBattleResultPopup(true)
            engine.setAutoSellMidGradeForPurchase(true)
            engine.setAutoSellHighGradeForPurchase(true)
            engine.setShowAllAvailableDisciples(true)
        }
        val gd = store.gameDataSnapshot
        assertTrue(gd.patrolBattleResultPopup)
        assertTrue(gd.autoSellMidGradeForPurchase)
        assertTrue(gd.autoSellHighGradeForPurchase)
        assertTrue(gd.showAllAvailableDisciples)
    }

    @Test
    fun `auto assign settings fall back to kotlin arm when flag OFF`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.setBreakthroughAutoPillSettings(true, setOf(1, 2))
            engine.setAutoEquipSettings(true, setOf(3))
            engine.setAutoLearnSettings(true, setOf(2))
            engine.setDaoCompanionBannedRootCounts(setOf(1))
            engine.setPrisonerSpiritRootFilter(setOf(2, 3))
        }
        val gd = store.gameDataSnapshot
        assertTrue(gd.breakthroughAutoPillFocused)
        assertEquals(setOf(1, 2), gd.breakthroughAutoPillRootCounts)
        assertTrue(gd.autoEquipFromWarehouseFocused)
        assertEquals(setOf(3), gd.autoEquipFromWarehouseRootCounts)
        assertTrue(gd.autoLearnFromWarehouseFocused)
        assertEquals(setOf(2), gd.autoLearnFromWarehouseRootCounts)
        assertEquals(setOf(1), gd.daoCompanionBannedRootCounts)
        assertEquals(setOf(2, 3), gd.prisonerSpiritRootFilter)
    }

    @Test
    fun `recruit filters fall back to kotlin arm when flag OFF and keep 1to5 validation`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.setAutoRecruitFilterValidated(setOf(1, 3, 7))
            engine.setAutoRejectFilterValidated(setOf(2, 9))
        }
        assertEquals(setOf(1, 3), store.gameDataSnapshot.autoRecruitSpiritRootFilter)
        assertEquals(setOf(2), store.gameDataSnapshot.autoRejectSpiritRootFilter)
    }

    @Test
    fun `dao companion consent off clears pending proposals after write`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.setDaoCompanionConsentRequired(true)
            assertTrue(store.gameDataSnapshot.daoCompanionConsentRequired)
            engine.setDaoCompanionConsentRequired(false)
        }
        // 平台残差：关闭同意模式后待处理提议清空（Kotlin 运行态，不入 C++ 状态）
        assertFalse(store.gameDataSnapshot.daoCompanionConsentRequired)
        assertTrue(store.pendingMarriageProposals.value.isEmpty())
    }

    // ── 补丁数组构造（协议面形状守卫）────────────────────────────

    @Test
    fun `patch array encodes bool scalars and sorted int arrays`() {
        val array = buildPatchArray(
            listOf(
                "soundEnabled" to SettingPatchValue.Flag(false),
                "prisonerSpiritRootFilter" to SettingPatchValue.IntSet(setOf(3, 1, 2))
            )
        )
        val text = array.toString()
        assertTrue(text.contains("\"field\":\"soundEnabled\""))
        assertTrue(text.contains("\"value\":false"))
        assertTrue(text.contains("\"field\":\"prisonerSpiritRootFilter\""))
        // Int 集升序数组（确定性协议面——C++ 侧再做集合语义归一化）
        assertTrue(text.contains("\"value\":[1,2,3]"))
    }
}
