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
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.WriteGuardRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 弟子管理事务 native 转发门控测试（batch-08 第一子批）。
 *
 * 守护 GameEngineManualOps（装备穿脱/功法学忘）与 GameEngineDiscipleSlotOps
 * （亲传/藏经阁任命卸任）native 臂的三级降级契约——镜像服务缺失 /
 * native 桥不可用（JVM 无 .so 恒未加载）/ flag OFF 均回退 Kotlin 原路径
 * （discipleService/discipleFacade 委托被调用，弟子状态零变更）。
 *
 * C++ 事务本体的语义逐位守护在桌面 GTest disciple_tx_test.cpp
 * （JVM 层无法加载 native 桥，成功臂不属本文件职责面）。
 */
@Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class GameEngineDiscipleTxForwardTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var engine: GameEngine
    private lateinit var discipleFacade: DiscipleFacade
    private lateinit var discipleService: com.xianxia.sect.core.engine.domain.disciple.DiscipleService

    @Before
    fun setUp() {
        gate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        store = FakeAtomicStateStore()
        discipleFacade = mock()
        val mockBattleFacade = mock<BattleFacade>()
        whenever(mockBattleFacade.assignmentGate).thenReturn(gate)

        val mockProductionFacade = mock<ProductionFacade>()
        whenever(mockProductionFacade.productionSlots)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        discipleService = mock()
        val mockCultivationFacade = mock<CultivationFacade>()
        whenever(mockCultivationFacade.cultivationService).thenReturn(mock())
        whenever(mockCultivationFacade.discipleService).thenReturn(discipleService)
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
            gameEngineCore = mock(),
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = GameRngManager(),
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade,
            economyFacade = mockEconomyFacade,
            battleFacade = mockBattleFacade
        )
    }

    @After
    fun restoreFlag() {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.AUTHORITATIVE
    }

    // ── 装备穿脱（GameEngineManualOps）──────────────────────────────

    @Test
    fun `equipItem flag OFF falls back to discipleService`() = runTest {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.OFF

        engine.equipItem("1", "eq-1")

        verify(discipleService).equipEquipment("1", "eq-1")
    }

    @Test
    fun `equipItem authoritative without native bridge falls back`() = runTest {
        // AUTHORITATIVE（生产默认）+ JVM 无 .so → GameCoreBridge.isLoaded=false → 回退
        engine.equipItem("1", "eq-1")

        verify(discipleService).equipEquipment("1", "eq-1")
    }

    @Test
    fun `unequipItemById authoritative without native bridge falls back`() = runTest {
        engine.unequipItemById("1", "eq-1")

        verify(discipleService).unequipEquipment("1", "eq-1")
    }

    @Test
    fun `unequipItem resolves slot then falls back`() = runTest {
        // 弟子不存在（store 空）→ getDiscipleById null → 原方法契约返回 null，
        // 不触碰 discipleService（预读早退先于转发）
        val result = engine.unequipItem("1", EquipmentSlot.WEAPON)

        assertEquals(null, result)
        verify(discipleService, org.mockito.kotlin.never()).unequipEquipment(
            org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    // ── 功法学忘（GameEngineManualOps）──────────────────────────────

    @Test
    fun `learnManual flag OFF falls back to kotlin transaction`() = runTest {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.OFF

        // 空仓静默守卫（Kotlin 原路径语义：stack 缺失 silent return）
        engine.learnManual("1", "m1")

        assertEquals(0, store.manualStacks.value.size)
    }

    @Test
    fun `learnManual authoritative without native bridge falls back`() = runTest {
        engine.learnManual("1", "m1")

        assertEquals(0, store.manualStacks.value.size)
    }

    @Test
    fun `forgetManual authoritative without native bridge falls back`() = runTest {
        engine.forgetManual("1", "inst-1")

        // 实例缺失静默守卫：无异常 + 状态零变更
        assertTrue(store.manualInstances.value.isEmpty())
    }

    // ── 任命/卸任（GameEngineDiscipleSlotOps）────────────────────────

    @Test
    fun `assignDirectDisciple flag OFF falls back to discipleFacade`() {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.OFF

        engine.assignDirectDisciple("herbGarden", 0, "1", "弟子", "炼气", "#FFFFFF")

        verify(discipleFacade).assignDirectDisciple("herbGarden", 0, "1", "弟子", "炼气", "#FFFFFF")
    }

    @Test
    fun `assignDirectDisciple without native bridge falls back`() {
        engine.assignDirectDisciple("herbGarden", 0, "1", "弟子", "炼气", "#FFFFFF")

        verify(discipleFacade).assignDirectDisciple("herbGarden", 0, "1", "弟子", "炼气", "#FFFFFF")
    }

    @Test
    fun `removeDirectDisciple without native bridge falls back`() {
        engine.removeDirectDisciple("herbGarden", 0)

        verify(discipleFacade).removeDirectDisciple("herbGarden", 0)
    }

    @Test
    fun `library assign and remove fall back without native bridge`() {
        engine.assignDiscipleToLibrarySlot(0, "1", "弟子")
        verify(discipleFacade).assignDiscipleToLibrarySlot(0, "1", "弟子")

        engine.removeDiscipleFromLibrarySlot(0)
        verify(discipleFacade).removeDiscipleFromLibrarySlot(0)
    }

    @Test
    fun `slot fallback leaves gate registry untouched`() {
        // 降级臂不做任何 gate 操作（Gate 语义归 Kotlin 原路径内部）
        engine.removeDirectDisciple("forge", 0)

        assertEquals(0, gate.size())
    }
}
