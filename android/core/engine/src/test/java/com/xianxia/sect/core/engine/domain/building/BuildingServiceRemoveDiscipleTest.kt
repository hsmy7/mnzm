package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.service.FormulaService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.GameRngManager
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * BuildingService.removeDiscipleFromBuilding 双写一致性测试（S2，2026-08-08）。
 *
 * 同 S1 链路（repo 先写、成功才清镜像 + gate 释放）；isWorking 早退保留（双端皆不动）。
 * 4.00.91 背景：镜像残留让状态推导仍 WORKING、自动重启按镜像判定继续生产。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class BuildingServiceRemoveDiscipleTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var repository: ProductionSlotRepository
    private lateinit var service: BuildingService

    companion object {
        private const val DISCIPLE_A = "1"
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
            discipleTables.writeAllowed = false
            gameData = gameData.copy(productionSlots = emptyList())
        }

        val scopeProvider = mock<com.xianxia.sect.core.util.CoroutineScopeProvider>()
        whenever(scopeProvider.scope).thenReturn(CoroutineScope(Dispatchers.Unconfined))
        repository = ProductionSlotRepository(
            dao = mock(), configService = mock(), scopeProvider = scopeProvider
        )
        val coordinator = mock<ProductionCoordinator>()
        whenever(coordinator.repository).thenReturn(repository)

        service = BuildingService(
            stateStore = store,
            productionCoordinator = coordinator,
            productionSlotRepository = repository,
            inventorySystem = mock<InventorySystem>(),
            formulaService = mock<FormulaService>(),
            rngManager = mock<GameRngManager>(),
            assignmentGate = gate,
            ioDispatcher = IoDispatcher(Dispatchers.Unconfined)
        )
    }

    private val alchemySlot0 = ProductionSlot(
        id = "alchemy_0", buildingType = BuildingType.ALCHEMY, buildingId = "alchemy",
        slotIndex = 0, status = ProductionSlotStatus.IDLE
    )

    /** A 入炼丹槽（repo + 镜像 + gate 三方一致） */
    private fun placeAInAlchemy() {
        runBlocking {
            repository.loadSlots(listOf(alchemySlot0))
            repository.updateSlot(BuildingType.ALCHEMY, 0) { slot ->
                slot.copy(assignedDiscipleId = DISCIPLE_A, assignedDiscipleName = "弟子A")
            }
        }
        store.update {
            gameData = gameData.copy(
                productionSlots = listOf(
                    alchemySlot0.copy(assignedDiscipleId = DISCIPLE_A, assignedDiscipleName = "弟子A")
                )
            )
        }
        gate.confirmAssign(
            DISCIPLE_A,
            SlotRef(SlotCategory.PRODUCTION_SLOT, "alchemy:0", "production_alchemy_0")
        )
    }

    @Test
    fun `卸任成功后 repo 与镜像同时清空且 gate 释放`() = runTest {
        placeAInAlchemy()

        service.removeDiscipleFromBuilding("alchemy", 0)

        val repoSlot = repository.getSlotByBuildingId("alchemy", 0)
        assertEquals("repo 槽位应清空", null, repoSlot?.assignedDiscipleId)
        val gdSlot = store.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0 }
        assertEquals("镜像槽位应清空", null, gdSlot?.assignedDiscipleId)
        assertFalse("gate 应释放", gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `槽位 WORKING 时早退不卸任`() = runTest {
        val workingSlot = alchemySlot0.copy(status = ProductionSlotStatus.WORKING)
        runBlocking {
            repository.loadSlots(listOf(workingSlot))
            repository.updateSlot(BuildingType.ALCHEMY, 0) { slot ->
                slot.copy(assignedDiscipleId = DISCIPLE_A, assignedDiscipleName = "弟子A")
            }
        }
        store.update {
            gameData = gameData.copy(productionSlots = listOf(workingSlot))
        }
        gate.confirmAssign(
            DISCIPLE_A,
            SlotRef(SlotCategory.PRODUCTION_SLOT, "alchemy:0", "production_alchemy_0")
        )

        service.removeDiscipleFromBuilding("alchemy", 0)

        val repoSlot = repository.getSlotByBuildingId("alchemy", 0)
        assertEquals("WORKING 槽位不应被卸任", DISCIPLE_A, repoSlot?.assignedDiscipleId)
        assertTrue("WORKING 槽位 gate 应保持", gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `repo 无该槽时直接返回不报错`() = runTest {
        service.removeDiscipleFromBuilding("herbGarden", 0)
        assertEquals("repo 不应有该槽", null, repository.getSlotByBuildingId("herbGarden", 0))
    }
}
