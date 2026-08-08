package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.DomainResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner



/**
 * GameEngineAtomicAssign 原子扩展方法的单元测试。
 *
 * 验证 6 个原子方法在 [GameStateStore.update] 单事务内的状态变更正确性。
 */
@RunWith(RobolectricTestRunner::class)
class GameEngineAtomicAssignTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var engine: GameEngine
    private lateinit var discipleFacade: com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade

    private val DISCIPLE_A = "1"
    private val DISCIPLE_B = "2"
    private val BUILDING_ID = "residence_b1"
    private val SLOT_0 = 0

    @Before
    fun setUp() {
        gate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        store = FakeAtomicStateStore()

        // 创建测试弟子（在事务内初始化 DiscipleTables，tables 实例被 store 持久化）
        store.update {
            discipleTables.writeAllowed = true
            val a = DISCIPLE_A.toInt()
            discipleTables.addId(a)
            discipleTables.names[a] = "弟子A"
            discipleTables.statuses[a] = DiscipleStatus.IDLE
            discipleTables.isAlive[a] = 1
            discipleTables.realms[a] = 9
            discipleTables.realmLayers[a] = 1
            discipleTables.portraitRes[a] = "portrait_a"

            val b = DISCIPLE_B.toInt()
            discipleTables.addId(b)
            discipleTables.names[b] = "弟子B"
            discipleTables.statuses[b] = DiscipleStatus.IDLE
            discipleTables.isAlive[b] = 1
            discipleTables.realms[b] = 9
            discipleTables.realmLayers[b] = 1
            discipleTables.portraitRes[b] = "portrait_b"
            discipleTables.writeAllowed = false
        }

        // 创建测试槽位
        store.update {
            gameData = gameData.copy(
                placedBuildings = listOf(
                    GridBuildingData(instanceId = BUILDING_ID, displayName = "单人住所")
                ),
                residenceSlots = listOf(
                    ResidenceSlot(buildingInstanceId = BUILDING_ID, slotIndex = SLOT_0)
                ),
                patrolSlots = listOf(
                    PatrolSlot(index = 0),
                    PatrolSlot(index = 1)
                )
            )
        }

        // 使用 mock() 创建 GameEngine（D1 后 8 构造参数），仅 stateStore + assignmentGate 为真实实现
        discipleFacade = mock()
        val mockBattleFacade = mock<BattleFacade>()
        org.mockito.kotlin.whenever(mockBattleFacade.assignmentGate).thenReturn(gate)

        // D1：构造时 highFrequencyData/productionSlots 经 Facade 访问器求值——stub 链防 NPE
        val mockProductionFacade = mock<ProductionFacade>()
        org.mockito.kotlin.whenever(mockProductionFacade.productionSlots)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        val mockCultivationFacade = mock<CultivationFacade>()
        org.mockito.kotlin.whenever(mockCultivationFacade.cultivationService).thenReturn(mock())
        org.mockito.kotlin.whenever(mockCultivationFacade.discipleService).thenReturn(mock())
        org.mockito.kotlin.whenever(mockCultivationFacade.discipleFacade).thenReturn(discipleFacade)
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
            battleFacade = mockBattleFacade
        )
    }

    // ── 住所分配 ──

    @Test
    fun `assignToResidenceAtomic 空槽位分配成功`() = runTest {
        val result = engine.assignToResidenceAtomic(BUILDING_ID, SLOT_0, DISCIPLE_A)

        assertTrue("应为 Success", result.isSuccess)
        val slot = store.latestGameData.residenceSlots[SLOT_0]
        assertEquals("槽位应写入弟子 A", DISCIPLE_A, slot.discipleId)
        assertEquals("槽位名应正确", "弟子A", slot.discipleName)
        assertFalse("住所不注册 gate", gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `assignToResidenceAtomic 覆盖原住户时释放旧弟子`() = runTest {
        engine.assignToResidenceAtomic(BUILDING_ID, SLOT_0, DISCIPLE_A)

        val result = engine.assignToResidenceAtomic(BUILDING_ID, SLOT_0, DISCIPLE_B)

        assertTrue("覆盖应成功", result.isSuccess)
        val slot = store.latestGameData.residenceSlots[SLOT_0]
        assertEquals("槽位应写入弟子 B", DISCIPLE_B, slot.discipleId)
        assertFalse("住所不在 gate 中", gate.isAssigned(DISCIPLE_A))
        assertFalse("住所不在 gate 中", gate.isAssigned(DISCIPLE_B))
    }

    @Test
    fun `assignToResidenceAtomic 不存在的弟子返回 Failure`() = runTest {
        val result = engine.assignToResidenceAtomic(BUILDING_ID, SLOT_0, "999")
        assertTrue("应为 Failure", result.isFailure)
    }

    @Test
    fun `assignToResidenceAtomic 入住不改变弟子状态`() = runTest {
        val prevStatus = store.latestGameData.let {
            engine.assignToResidenceAtomic(BUILDING_ID, SLOT_0, DISCIPLE_A)
            // 分配后检查状态不变（初始为 IDLE）
            val tables = store.discipleTables
            tables.statuses[DISCIPLE_A.toInt()]
        }
        assertEquals("状态应保持 IDLE", DiscipleStatus.IDLE, prevStatus)
    }

    // ── 住所移除 ──

    @Test
    fun `removeFromResidenceAtomic 移除后槽位清空`() = runTest {
        engine.assignToResidenceAtomic(BUILDING_ID, SLOT_0, DISCIPLE_A)

        val result = engine.removeFromResidenceAtomic(BUILDING_ID, SLOT_0)

        assertTrue("移除应成功", result.isSuccess)
        assertEquals("槽位应清空", "", store.latestGameData.residenceSlots[SLOT_0].discipleId)
        assertFalse("gate 不受影响", gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `removeFromResidenceAtomic 空槽位不做操作`() = runTest {
        val result = engine.removeFromResidenceAtomic(BUILDING_ID, SLOT_0)
        assertTrue("空槽位移除应 Success", result.isSuccess)
    }

    @Test
    fun `assignToResidenceAtomic 入住不清除巡视楼槽位`() = runTest {
        // 模拟 A 在巡视楼中
        store.update {
            val slots = gameData.patrolSlots.toMutableList()
            slots[0] = PatrolSlot(index = 0, discipleId = DISCIPLE_A, discipleName = "弟子A")
            gameData = gameData.copy(patrolSlots = slots)
        }

        engine.assignToResidenceAtomic(BUILDING_ID, SLOT_0, DISCIPLE_A)

        assertEquals("巡视楼槽位不应被清除", DISCIPLE_A, store.latestGameData.patrolSlots[0].discipleId)
    }

    // ── 巡视楼分配 ──

    @Test
    fun `assignPatrolAtomic 分配成功设状态 PATROLLING`() = runTest {
        val result = engine.assignPatrolAtomic(DISCIPLE_A, globalIndex = 0)

        assertTrue("分配应成功", result.isSuccess)
        assertEquals("槽位应写入弟子 A", DISCIPLE_A, store.latestGameData.patrolSlots[0].discipleId)
        assertTrue("gate 应注册", gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `assignPatrolAtomic 使用塔索引重载`() = runTest {
        val result = engine.assignPatrolAtomic(DISCIPLE_A, towerIndex = 0, slotOffset = 0, slotsPerTower = 2)
        assertTrue("便利重载应成功", result.isSuccess)
        assertEquals("槽位 0 应写入弟子 A", DISCIPLE_A, store.latestGameData.patrolSlots[0].discipleId)
    }

    @Test
    fun `assignPatrolAtomic 更换时同步旧 occupant 状态`() = runTest {
        // A 在槽 0，B 在槽 1
        engine.assignPatrolAtomic(DISCIPLE_A, globalIndex = 0)
        engine.assignPatrolAtomic(DISCIPLE_B, globalIndex = 1)

        // 更换：B 顶替 A 的槽位 0
        val result = engine.assignPatrolAtomic(DISCIPLE_B, globalIndex = 0)

        assertTrue("更换应成功", result.isSuccess)
        assertEquals("槽位 0 应为弟子 B", DISCIPLE_B, store.latestGameData.patrolSlots[0].discipleId)
        assertTrue("新弟子 gate 应注册", gate.isAssigned(DISCIPLE_B))
        assertFalse("旧弟子 gate 应释放", gate.isAssigned(DISCIPLE_A))
        // 回归守卫：旧 occupant 必须被同步状态（修复前从不 sync，
        // statuses 残留 PATROLLING 从选择弹窗消失）。A 的 sync 调用 = 第 1 次分配(新弟子) + 本次更换(旧 occupant) = 2 次
        verify(discipleFacade, times(2)).syncSingleDiscipleStatus(DISCIPLE_A)
        verify(discipleFacade, times(2)).syncSingleDiscipleStatus(DISCIPLE_B)
    }

    // ── 巡视楼移除 ──

    @Test
    fun `removePatrolAtomic 移除后槽位清空 gate 释放`() = runTest {
        engine.assignPatrolAtomic(DISCIPLE_A, globalIndex = 0)

        val result = engine.removePatrolAtomic(globalIndex = 0)

        assertTrue("移除应成功", result.isSuccess)
        assertEquals("槽位应清空", "", store.latestGameData.patrolSlots[0].discipleId)
        assertFalse("gate 应释放", gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `removePatrolAtomic 空槽位不做操作`() = runTest {
        val result = engine.removePatrolAtomic(globalIndex = 0)
        assertTrue("空槽位移除应 Success", result.isSuccess)
    }

    // ── 巡视楼交换 ──

    @Test
    fun `swapPatrolAtomic 交换两个槽位`() = runTest {
        engine.assignPatrolAtomic(DISCIPLE_A, globalIndex = 0)
        engine.assignPatrolAtomic(DISCIPLE_B, globalIndex = 1)

        val result = engine.swapPatrolAtomic(fromGlobalIndex = 0, toGlobalIndex = 1)

        assertTrue("交换应成功", result.isSuccess)
        val d = store.latestGameData
        assertEquals("槽位 0 应为弟子 B", DISCIPLE_B, d.patrolSlots[0].discipleId)
        assertEquals("槽位 1 应为弟子 A", DISCIPLE_A, d.patrolSlots[1].discipleId)
    }

    @Test
    fun `swapPatrolAtomic 相同索引不做操作`() = runTest {
        engine.assignPatrolAtomic(DISCIPLE_A, globalIndex = 0)
        val result = engine.swapPatrolAtomic(fromGlobalIndex = 0, toGlobalIndex = 0)
        assertTrue("同索引交换应 Success", result.isSuccess)
        assertEquals("槽位不变", DISCIPLE_A, store.latestGameData.patrolSlots[0].discipleId)
    }

    // ── 批量分配 ──

    @Test
    fun `autoAssignPatrolAtomic 批量分配成功`() = runTest {
        val result = engine.autoAssignPatrolAtomic(listOf(0 to DISCIPLE_A, 1 to DISCIPLE_B))

        assertTrue("批量分配应成功", result.isSuccess)
        val d = store.latestGameData
        assertEquals("槽位 0 为 A", DISCIPLE_A, d.patrolSlots[0].discipleId)
        assertEquals("槽位 1 为 B", DISCIPLE_B, d.patrolSlots[1].discipleId)
        assertTrue("A gate 注册", gate.isAssigned(DISCIPLE_A))
        assertTrue("B gate 注册", gate.isAssigned(DISCIPLE_B))
    }

    // ── CancellationException ──

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun `CancellationException 不被吞入 Failure`() = runTest {
        DomainResult.catching<Unit> {
            throw kotlinx.coroutines.CancellationException("测试取消")
        }
    }

    // ── gate 与 residenceSlots 一致性 ──

    @Test
    fun `已入住弟子不在 gate 注册中`() = runTest {
        engine.assignToResidenceAtomic(BUILDING_ID, SLOT_0, DISCIPLE_A)

        // 住所不注册 gate（住所与工作槽位共存）
        val residentSlotsWithDisciple = store.latestGameData.residenceSlots.filter { it.discipleId.isNotEmpty() }
        assertTrue("应有已入住的住所槽位", residentSlotsWithDisciple.isNotEmpty())
        for (slot in residentSlotsWithDisciple) {
            assertFalse("住所不注册 gate", gate.isAssigned(slot.discipleId))
        }
    }
}
