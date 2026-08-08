package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.service.LawEnforcementProcessor
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.state.WriteGuardRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner



/**
 * 亲传弟子分配（DiscipleFacadeImpl.assignDirectDisciple）互斥守卫测试。
 *
 * 回归：分配前从不清理旧槽位——勾选"显示所有弟子"后把在岗弟子任命为
 * 灵植/炼丹/锻造/传道/执法/青云/灵矿执事亲传，旧岗位槽位残留（双槽位 Bug）。
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleFacadeAssignDirectDiscipleTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var discipleService: DiscipleService
    private lateinit var facade: DiscipleFacadeImpl

    companion object {
        private const val DISCIPLE_A = "1"
        private const val DISCIPLE_B = "2"
    }

    @Before
    fun setUp() {
        gate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        store = FakeAtomicStateStore()
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
            gameData = gameData.copy(
                patrolSlots = listOf(PatrolSlot(index = 0), PatrolSlot(index = 1))
            )
        }

        discipleService = mock()
        val mockCore = mock<GameEngineCore>()
        whenever(mockCore.launchInScope(any())).thenAnswer { invocation ->
            val block = invocation.getArgument<suspend CoroutineScope.() -> Unit>(0)
            runBlocking { block(CoroutineScope(Dispatchers.Unconfined)) }
            mock<kotlinx.coroutines.Job>()
        }
        facade = DiscipleFacadeImpl(
            discipleService = discipleService,
            stateStore = store,
            cultivationService = mock(),
            gameEngineCore = mockCore,
            inventorySystem = mock<InventorySystem>(),
            pillManager = mock(),
            assignmentGate = gate,
            discipleSlotCleanup = DiscipleSlotCleanup(gate),
            lawEnforcementProcessor = mock<LawEnforcementProcessor>(),
            productionCoordinator = mock<ProductionCoordinator>()
        )
    }

    /** 把弟子放入巡逻槽位 0（模拟"已在岗"）并登记 gate */
    private fun placeInPatrol(discipleId: String) {
        store.update {
            val slots = gameData.patrolSlots.toMutableList()
            slots[0] = PatrolSlot(index = 0, discipleId = discipleId, discipleName = "弟子$discipleId")
            gameData = gameData.copy(patrolSlots = slots)
        }
        gate.confirmAssign(discipleId, SlotRef(SlotCategory.PATROL_SLOT, "patrol", "patrol_0"))
    }

    private fun assignHerbGarden(discipleId: String) {
        facade.assignDirectDisciple(
            elderSlotType = SLOT_TYPE_HERB_GARDEN,
            slotIndex = 0,
            discipleId = discipleId,
            discipleName = "弟子$discipleId",
            discipleRealm = "炼气1层",
            discipleSpiritRootColor = "#000000"
        )
    }

    @Test
    fun `亲传弟子分配时已在其他槽位的弟子旧槽位被清空`() = runTest {
        placeInPatrol(DISCIPLE_A)

        assignHerbGarden(DISCIPLE_A)

        assertEquals("巡逻槽位应清空", "", store.latestGameData.patrolSlots[0].discipleId)
        val list = store.latestGameData.elderSlots.herbGardenDisciples
        assertEquals("灵植亲传槽应为 A", DISCIPLE_A, list[0].discipleId)
        val assignment = gate.getAssignment(DISCIPLE_A)
        assertEquals("gate 应唯一登记亲传", SlotCategory.ELDER_POSITION, assignment?.slotRef?.category)
    }

    @Test
    fun `亲传弟子顶替同槽旧弟子时旧弟子 gate 释放且状态回归`() = runTest {
        // B 已在灵植亲传槽 0
        store.update {
            gameData = gameData.copy(
                elderSlots = ElderSlots(
                    herbGardenDisciples = listOf(
                        DirectDiscipleSlot(index = 0, discipleId = DISCIPLE_B, discipleName = "弟子B")
                    )
                )
            )
        }
        gate.confirmAssign(DISCIPLE_B, SlotRef(SlotCategory.ELDER_POSITION, "herbGarden:0", "elder_herbGarden_0"))

        assignHerbGarden(DISCIPLE_A)

        assertFalse("旧亲传 B 的 gate 应释放", gate.isAssigned(DISCIPLE_B))
        verify(discipleService).syncSingleDiscipleStatus(DISCIPLE_B)
        val list = store.latestGameData.elderSlots.herbGardenDisciples
        assertEquals("灵植亲传槽应为 A", DISCIPLE_A, list[0].discipleId)
    }

    @Test
    fun `亲传弟子分配后 gate 仅登记亲传槽位`() = runTest {
        assignHerbGarden(DISCIPLE_A)

        val assignment = gate.getAssignment(DISCIPLE_A)
        assertEquals("gate 应登记亲传槽", SlotCategory.ELDER_POSITION, assignment?.slotRef?.category)
        assertEquals("slotType 应为 herbGarden:0", "herbGarden:0", assignment?.slotRef?.slotType)
        assertTrue("弟子 B 不应注册", !gate.isAssigned(DISCIPLE_B))
    }
}
