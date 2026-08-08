package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.wallet.SpiritStoneWallet
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
 * 生产槽分配（BuildingFacadeImpl.assignDiscipleToProductionSlot）互斥守卫测试。
 *
 * 回归：此前只清 Repository 其他生产槽，巡逻/长老/藏经阁等 GameData 槽位残留，
 * 且 GameData.productionSlots 镜像不同步——勾选"显示所有弟子"分配后双槽位。
 */
@RunWith(RobolectricTestRunner::class)
class BuildingFacadeImplAssignProductionSlotTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var repository: ProductionSlotRepository
    private lateinit var discipleStatusService: DiscipleStatusService
    private lateinit var facade: BuildingFacadeImpl

    /** Repository 槽位列表（stub 闭包引用，测试种子可修改） */
    private lateinit var repoSlots: MutableList<ProductionSlot>

    companion object {
        private const val DISCIPLE_A = "1"
        private const val DISCIPLE_B = "2"
    }

    private val alchemySlot0 = ProductionSlot(
        id = "alchemy_0", buildingType = BuildingType.ALCHEMY, buildingId = "alchemy",
        slotIndex = 0, status = ProductionSlotStatus.IDLE
    )
    private val forgeSlot0 = ProductionSlot(
        id = "forge_0", buildingType = BuildingType.FORGE, buildingId = "forge",
        slotIndex = 0, status = ProductionSlotStatus.IDLE
    )

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
                patrolSlots = listOf(PatrolSlot(index = 0), PatrolSlot(index = 1)),
                productionSlots = listOf(alchemySlot0, forgeSlot0)
            )
        }

        discipleStatusService = mock()
        // 使用真实 ProductionSlotRepository（dao/configService 全 mock）：
        // getSlots() 函数与属性 slots 的 JVM getter 同名，Mockito 按名匹配会抛
        // WrongTypeOfReturnValue（见 BootSequenceControllerTest T15 注释），绕开 mock；
        // workingSlots 等 stateIn 在构造时立即启动共享协程，scope 必须非 null
        val scopeProvider = mock<com.xianxia.sect.core.util.CoroutineScopeProvider>()
        whenever(scopeProvider.scope).thenReturn(CoroutineScope(Dispatchers.Unconfined))
        repository = ProductionSlotRepository(
            dao = mock(), configService = mock(), scopeProvider = scopeProvider
        )
        repoSlots = mutableListOf(alchemySlot0, forgeSlot0)
        runBlocking { repository.loadSlots(repoSlots) }
        val coordinator = mock<ProductionCoordinator>()
        whenever(coordinator.repository).thenReturn(repository)

        val mockCore = mock<GameEngineCore>()
        whenever(mockCore.launchInScope(any())).thenAnswer { invocation ->
            val block = invocation.getArgument<suspend CoroutineScope.() -> Unit>(0)
            runBlocking { block(CoroutineScope(Dispatchers.Unconfined)) }
            mock<kotlinx.coroutines.Job>()
        }
        facade = BuildingFacadeImpl(
            buildingService = mock(),
            stateStore = store,
            gameEngineCore = mockCore,
            productionCoordinator = coordinator,
            inventorySystem = mock<InventorySystem>(),
            spiritStoneWallet = mock<SpiritStoneWallet>(),
            assignmentGate = gate,
            discipleStatusService = discipleStatusService,
            ioDispatcher = IoDispatcher(Dispatchers.Unconfined)
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

    @Test
    fun `生产槽分配时已在其他槽位的弟子旧槽位被清空`() = runTest {
        placeInPatrol(DISCIPLE_A)

        facade.assignDiscipleToProductionSlot(BuildingType.ALCHEMY, 0, DISCIPLE_A, "弟子A")

        assertEquals("巡逻槽位应清空", "", store.latestGameData.patrolSlots[0].discipleId)
        val gdSlot = store.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0 }
        assertEquals("GameData 生产槽应写入 A", DISCIPLE_A, gdSlot?.assignedDiscipleId)
        val assignment = gate.getAssignment(DISCIPLE_A)
        assertEquals("gate 应唯一登记生产槽", SlotCategory.PRODUCTION_SLOT, assignment?.slotRef?.category)
    }

    @Test
    fun `生产槽分配时该弟子在其他生产槽的 repo 记录被清空`() = runTest {
        // A 已在锻造槽（Repository + GameData 双存储）
        store.update {
            gameData = gameData.copy(
                productionSlots = gameData.productionSlots.map {
                    if (it.buildingType == BuildingType.FORGE) {
                        it.copy(assignedDiscipleId = DISCIPLE_A, assignedDiscipleName = "弟子A")
                    } else it
                }
            )
        }

        facade.assignDiscipleToProductionSlot(BuildingType.ALCHEMY, 0, DISCIPLE_A, "弟子A")

        val forgeSlot = store.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.FORGE && it.slotIndex == 0 }
        assertEquals("GameData 锻造槽应清空", null, forgeSlot?.assignedDiscipleId)
        val alchemySlot = store.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0 }
        assertEquals("GameData 炼丹槽应写入 A", DISCIPLE_A, alchemySlot?.assignedDiscipleId)
    }

    @Test
    fun `生产槽分配顶替旧工人时旧工人 gate 释放且状态回归`() = runTest {
        // B 已在炼丹槽 0（Repository + GameData 双存储 + gate 登记）
        store.update {
            gameData = gameData.copy(
                productionSlots = gameData.productionSlots.map {
                    if (it.buildingType == BuildingType.ALCHEMY) {
                        it.copy(assignedDiscipleId = DISCIPLE_B, assignedDiscipleName = "弟子B")
                    } else it
                }
            )
        }
        runBlocking {
            repository.updateSlot(BuildingType.ALCHEMY, 0) { slot ->
                slot.copy(assignedDiscipleId = DISCIPLE_B, assignedDiscipleName = "弟子B")
            }
        }
        gate.confirmAssign(
            DISCIPLE_B,
            SlotRef(SlotCategory.PRODUCTION_SLOT, "ALCHEMY:0", "production_ALCHEMY_0")
        )

        facade.assignDiscipleToProductionSlot(BuildingType.ALCHEMY, 0, DISCIPLE_A, "弟子A")

        assertFalse("旧工人 B 的 gate 应释放", gate.isAssigned(DISCIPLE_B))
        verify(discipleStatusService).syncSingleDiscipleStatus(DISCIPLE_B)
    }

    @Test
    fun `生产槽分配后 GameData 生产槽与新槽位同步`() = runTest {
        facade.assignDiscipleToProductionSlot(BuildingType.ALCHEMY, 0, DISCIPLE_A, "弟子A")

        val gdSlot = store.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0 }
        assertEquals("GameData 炼丹槽应写入 A", DISCIPLE_A, gdSlot?.assignedDiscipleId)
        assertEquals("GameData 炼丹槽名字应正确", "弟子A", gdSlot?.assignedDiscipleName)
        assertTrue("gate 应注册", gate.isAssigned(DISCIPLE_A))
    }
}
