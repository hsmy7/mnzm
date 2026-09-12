package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.PatrolConfig
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.ResidenceSlot
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
 * GameEnginePatrolNativeTxGateTest — 巡逻/住所/矿场/年俸域 native 臂门控降级守卫（batch-12）。
 *
 * 守护契约：
 * - 降级契约：flag OFF / AUTHORITATIVE 下 JVM 无生产 .so（GameCoreBridge 未加载）
 *   → PatrolNativeForward.tryForward 均返回 null → 调用方回退 Kotlin 原实现，
 *   回退臂语义与下沉前逐字一致（双实现并行契约）
 * - 镜像守卫：测试 mock（未 stub stateSyncServiceRef）返回 null sync → 先赋可空
 *   局部再判空（handover findings 13），不得 NPE
 * - 回退臂语义：住所分配/移除；巡逻分配（释放原 occupant + 清新弟子其它槽位）/
 *   移除/交换/批量；配置整表覆写；矿场槽位覆写与自愈；年俸覆写——下沉前后
 *   状态面逐项不变
 *
 * C++ 事务语义（AUTHORITATIVE + 生产桥）由 GTest patrol_tx_test.cpp（31 用例）
 * 与真机对拍框架逐位守护。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class GameEnginePatrolNativeTxGateTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var engine: GameEngine
    private lateinit var discipleFacade: DiscipleFacade
    private lateinit var mockCore: GameEngineCore
    private lateinit var mockPC: ProductionCoordinator

    companion object {
        private const val DISCIPLE_A = "1"
        private const val DISCIPLE_B = "2"
        private const val RESIDENCE_ID = "res_b1"
        private const val TOWER_ID = "tower_1"
    }

    @Before
    fun setUp() {
        gate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        store = FakeAtomicStateStore()
        store.update {
            discipleTables.writeAllowed = true
            for (i in 1..2) {
                val id = i
                discipleTables.addId(id)
                discipleTables.names[id] = "弟子$i"
                discipleTables.statuses[id] = DiscipleStatus.IDLE
                discipleTables.isAlive[id] = 1
                discipleTables.realms[id] = 9
                discipleTables.realmLayers[id] = 1
                discipleTables.portraitRes[id] = "portrait_$id"
            }
            discipleTables.writeAllowed = false
        }
        store.update {
            gameData = gameData.copy(
                placedBuildings = listOf(
                    GridBuildingData(
                        buildingId = "residence", displayName = "居所", instanceId = RESIDENCE_ID
                    ),
                    GridBuildingData(
                        buildingId = "patrol_tower", displayName = "巡视楼", instanceId = TOWER_ID
                    )
                ),
                residenceSlots = listOf(ResidenceSlot(buildingInstanceId = RESIDENCE_ID, slotIndex = 0)),
                patrolSlots = listOf(
                    PatrolSlot(index = 0, buildingInstanceId = TOWER_ID),
                    PatrolSlot(index = 1, buildingInstanceId = TOWER_ID)
                )
            )
        }
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
        mockPC = mock<ProductionCoordinator>()
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
            gameRngManager = mock(),
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade,
            economyFacade = mockEconomyFacade,
            battleFacade = mockBattleFacade
        )
    }

    private fun patrolSlot(index: Int) =
        store.gameDataSnapshot.patrolSlots.first { it.index == index }

    private fun residenceSlot() = store.gameDataSnapshot.residenceSlots[0]

    // ── 转发器门控（flag / 镜像守卫）──────────────────────────────

    @Test
    fun `tryForward returns null when flag is OFF`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertNull(PatrolNativeForward.tryForward(engine, 1552) { })
        }
    }

    @Test
    fun `tryForward returns null when sync service missing under AUTHORITATIVE`() = runTest {
        // JVM 环境：mock core 的 stateSyncServiceRef 未 stub → null sync →
        // 可空局部守卫返回 null（不得 NPE），桥未加载也走不到 native
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            assertNull(PatrolNativeForward.tryForward(engine, 1552) { })
        }
    }

    // ── 回退臂语义（flag OFF；与下沉前逐项一致）──────────────────

    @Test
    fun `assign residence falls back to kotlin arm when flag OFF`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertTrue(engine.assignToResidenceAtomic(RESIDENCE_ID, 0, DISCIPLE_A).isSuccess)
        }
        assertEquals(DISCIPLE_A, residenceSlot().discipleId)
        assertEquals("弟子1", residenceSlot().discipleName)
    }

    @Test
    fun `remove residence falls back to kotlin arm when flag OFF`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.assignToResidenceAtomic(RESIDENCE_ID, 0, DISCIPLE_A)
            assertTrue(engine.removeFromResidenceAtomic(RESIDENCE_ID, 0).isSuccess)
        }
        assertTrue(residenceSlot().discipleId.isEmpty())
    }

    @Test
    fun `assign patrol falls back to kotlin arm and clears other slots`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertTrue(engine.assignPatrolAtomic(DISCIPLE_A, 0).isSuccess)
        }
        assertEquals(DISCIPLE_A, patrolSlot(0).discipleId)
        assertEquals("弟子1", patrolSlot(0).discipleName)
        assertEquals("炼气1层", patrolSlot(0).discipleRealm)
        assertEquals(TOWER_ID, patrolSlot(0).buildingInstanceId)
        // gate 已登记（事务外残差在回退臂同序执行）
        assertTrue(gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `assign patrol replaces occupant and releases gate`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.assignPatrolAtomic(DISCIPLE_A, 0)
            assertTrue(engine.assignPatrolAtomic(DISCIPLE_B, 0).isSuccess)
        }
        assertEquals(DISCIPLE_B, patrolSlot(0).discipleId)
        assertFalse(gate.isAssigned(DISCIPLE_A))
        assertTrue(gate.isAssigned(DISCIPLE_B))
    }

    @Test
    fun `remove patrol falls back to kotlin arm and releases gate`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.assignPatrolAtomic(DISCIPLE_A, 0)
            assertTrue(engine.removePatrolAtomic(0).isSuccess)
        }
        assertTrue(patrolSlot(0).discipleId.isEmpty())
        assertEquals(TOWER_ID, patrolSlot(0).buildingInstanceId)
        assertFalse(gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `swap patrol falls back to kotlin arm`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.assignPatrolAtomic(DISCIPLE_A, 0)
            engine.assignPatrolAtomic(DISCIPLE_B, 1)
            assertTrue(engine.swapPatrolAtomic(0, 1).isSuccess)
        }
        assertEquals(DISCIPLE_B, patrolSlot(0).discipleId)
        assertEquals(DISCIPLE_A, patrolSlot(1).discipleId)
    }

    @Test
    fun `auto assign patrol falls back to kotlin arm`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertTrue(
                engine.autoAssignPatrolAtomic(listOf(0 to DISCIPLE_A, 1 to DISCIPLE_B)).isSuccess
            )
        }
        assertEquals(DISCIPLE_A, patrolSlot(0).discipleId)
        assertEquals(DISCIPLE_B, patrolSlot(1).discipleId)
        assertTrue(gate.isAssigned(DISCIPLE_A))
        assertTrue(gate.isAssigned(DISCIPLE_B))
    }

    @Test
    fun `auto assign empty list is silent success`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertTrue(engine.autoAssignPatrolAtomic(emptyList()).isSuccess)
        }
    }

    @Test
    fun `update patrol configs falls back to kotlin arm`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.updatePatrolConfigs(
                listOf(PatrolConfig(requireFullStatus = false, maxBeastCount = 3))
            )
        }
        val configs = store.gameDataSnapshot.patrolConfigs
        assertEquals(1, configs.size)
        assertFalse(configs[0].requireFullStatus)
        assertEquals(3, configs[0].maxBeastCount)
    }

    @Test
    fun `update spirit mine slots falls back to kotlin arm`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.updateSpiritMineSlots(emptyList())
        }
        assertTrue(store.gameDataSnapshot.spiritMineSlots.isEmpty())
    }

    @Test
    fun `update yearly salary falls back to kotlin arm`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.updateYearlySalary(mapOf(9 to 120, 8 to 300))
        }
        val salary = store.gameDataSnapshot.yearlySalary
        assertEquals(120, salary[9])
        assertEquals(300, salary[8])
    }

    @Test
    fun `validate and fix spirit mine falls back to kotlin arm`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.validateAndFixSpiritMineData()
        }
        // 无灵矿场建筑 → 重建结果为空表（Kotlin 原语义）
        assertTrue(store.gameDataSnapshot.spiritMineSlots.isEmpty())
    }

    // ── AUTHORITATIVE 但桥未加载：双模式降级等价 ──────────────────

    @Test
    fun `patrol assign behaves identically under AUTHORITATIVE without native bridge`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            assertTrue(engine.assignPatrolAtomic(DISCIPLE_A, 0).isSuccess)
        }
        assertEquals(DISCIPLE_A, patrolSlot(0).discipleId)
        assertEquals("弟子1", patrolSlot(0).discipleName)
        assertEquals(TOWER_ID, patrolSlot(0).buildingInstanceId)
        assertTrue(gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `residence assign behaves identically under AUTHORITATIVE without native bridge`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            assertTrue(engine.assignToResidenceAtomic(RESIDENCE_ID, 0, DISCIPLE_B).isSuccess)
        }
        assertEquals(DISCIPLE_B, residenceSlot().discipleId)
        assertEquals("弟子2", residenceSlot().discipleName)
    }

    @Test
    fun `yearly salary behaves identically under AUTHORITATIVE without native bridge`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            engine.updateYearlySalary(mapOf(7 to 55))
        }
        assertEquals(55, store.gameDataSnapshot.yearlySalary[7])
    }

    // ── 校验失败臂语义（回退臂 catch 后返回 Failure）──────────────

    @Test
    fun `assign patrol unknown disciple returns failure when flag OFF`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertTrue(engine.assignPatrolAtomic("404", 0).isFailure)
            assertTrue(engine.assignPatrolAtomic(DISCIPLE_A, 99).isFailure)
        }
        assertTrue(patrolSlot(0).discipleId.isEmpty())
    }

    @Test
    fun `assign residence unknown building returns failure when flag OFF`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertTrue(engine.assignToResidenceAtomic("no_such", 0, DISCIPLE_A).isFailure)
        }
        assertTrue(residenceSlot().discipleId.isEmpty())
    }
}
