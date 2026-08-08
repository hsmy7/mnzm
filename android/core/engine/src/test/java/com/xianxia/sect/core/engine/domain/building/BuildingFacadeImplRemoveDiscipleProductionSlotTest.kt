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
 * 生产槽卸任/自动重启双写一致性测试（S1/S5，2026-08-08）。
 *
 * S1：removeDiscipleFromProductionSlot——repo 先写、成功才清镜像（失败两端皆未变）；
 *     镜像残留会让状态推导仍 WORKING、自动重启按镜像判定继续生产（"卸不掉"链路）。
 * S5：toggleAutoRestart——repo 更新成功后事务内同步镜像 autoRestartEnabled。
 *
 * 4.00.91 背景：UI 读 repo 真源、状态推导读镜像，分叉入口只清一端 → "任命/卸任不生效"。
 */
@RunWith(RobolectricTestRunner::class)
class BuildingFacadeImplRemoveDiscipleProductionSlotTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var repository: ProductionSlotRepository
    private lateinit var discipleStatusService: DiscipleStatusService
    private lateinit var facade: BuildingFacadeImpl

    companion object {
        private const val DISCIPLE_A = "1"
    }

    private val alchemySlot0 = ProductionSlot(
        id = "alchemy_0", buildingType = BuildingType.ALCHEMY, buildingId = "alchemy",
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
            discipleTables.writeAllowed = false
            gameData = gameData.copy(productionSlots = listOf(alchemySlot0))
        }

        discipleStatusService = mock()
        val scopeProvider = mock<com.xianxia.sect.core.util.CoroutineScopeProvider>()
        whenever(scopeProvider.scope).thenReturn(CoroutineScope(Dispatchers.Unconfined))
        repository = ProductionSlotRepository(
            dao = mock(), configService = mock(), scopeProvider = scopeProvider
        )
        runBlocking { repository.loadSlots(listOf(alchemySlot0)) }
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

    /** A 入炼丹槽 0（repo + 镜像 + gate 三方一致） */
    private fun placeAInAlchemy() {
        runBlocking {
            repository.updateSlot(BuildingType.ALCHEMY, 0) { slot ->
                slot.copy(assignedDiscipleId = DISCIPLE_A, assignedDiscipleName = "弟子A")
            }
        }
        store.update {
            gameData = gameData.copy(
                productionSlots = gameData.productionSlots.map {
                    if (it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0) {
                        it.copy(assignedDiscipleId = DISCIPLE_A, assignedDiscipleName = "弟子A")
                    } else it
                }
            )
        }
        gate.confirmAssign(
            DISCIPLE_A,
            SlotRef(SlotCategory.PRODUCTION_SLOT, "ALCHEMY:0", "production_ALCHEMY_0")
        )
    }

    // ── S1：removeDiscipleFromProductionSlot 双写 ──

    @Test
    fun `卸任成功后 repo 与镜像同时清空且 gate 释放`() = runTest {
        placeAInAlchemy()

        facade.removeDiscipleFromProductionSlot(BuildingType.ALCHEMY, 0)

        val repoSlot = repository.getSlotByIndex(BuildingType.ALCHEMY, 0)
        assertEquals("repo 槽位应清空", null, repoSlot?.assignedDiscipleId)
        val gdSlot = store.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0 }
        assertEquals("镜像槽位应清空", null, gdSlot?.assignedDiscipleId)
        assertFalse("gate 应释放", gate.isAssigned(DISCIPLE_A))
        verify(discipleStatusService).syncSingleDiscipleStatus(DISCIPLE_A)
    }

    @Test
    fun `repo 写失败时镜像与 gate 均不动`() = runTest {
        // 镜像存在 HERB_GARDEN[0] 但 repo 无该槽 → updateSlot 必然 Failure
        store.update {
            gameData = gameData.copy(
                productionSlots = gameData.productionSlots + ProductionSlot(
                    id = "herb_0", buildingType = BuildingType.HERB_GARDEN, buildingId = "herbGarden",
                    slotIndex = 0, status = ProductionSlotStatus.IDLE,
                    assignedDiscipleId = DISCIPLE_A, assignedDiscipleName = "弟子A"
                )
            )
        }
        gate.confirmAssign(
            DISCIPLE_A,
            SlotRef(SlotCategory.PRODUCTION_SLOT, "HERB_GARDEN:0", "production_HERB_GARDEN_0")
        )

        facade.removeDiscipleFromProductionSlot(BuildingType.HERB_GARDEN, 0)

        val gdSlot = store.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.HERB_GARDEN && it.slotIndex == 0 }
        assertEquals("repo 写失败 → 镜像应保持占用", DISCIPLE_A, gdSlot?.assignedDiscipleId)
        assertTrue("repo 写失败 → gate 不应释放", gate.isAssigned(DISCIPLE_A))
    }

    // ── S5：toggleAutoRestart 镜像同步 ──

    @Test
    fun `切换自动重启后镜像 autoRestartEnabled 同步`() = runTest {
        runBlocking {
            facade.toggleAutoRestart(BuildingType.ALCHEMY, 0)
        }

        val repoSlot = repository.getSlotByIndex(BuildingType.ALCHEMY, 0)
        assertEquals("repo 应开启自动重启", true, repoSlot?.autoRestartEnabled)
        val gdSlot = store.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0 }
        assertEquals("镜像应同步开启", true, gdSlot?.autoRestartEnabled)

        // 再次切换 → 关闭
        runBlocking {
            facade.toggleAutoRestart(BuildingType.ALCHEMY, 0)
        }
        assertEquals("repo 应关闭自动重启", false,
            repository.getSlotByIndex(BuildingType.ALCHEMY, 0)?.autoRestartEnabled)
        assertEquals("镜像应同步关闭", false,
            store.latestGameData.productionSlots
                .find { it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0 }?.autoRestartEnabled)
    }

    @Test
    fun `repo 无该槽时切换自动重启失败且镜像不变`() = runTest {
        runBlocking {
            facade.toggleAutoRestart(BuildingType.HERB_GARDEN, 0)
        }

        // 镜像无 HERB_GARDEN 槽 → 无断言对象；确认不崩溃且 repo 无该槽
        assertEquals("repo 不应有该槽", null,
            repository.getSlotByIndex(BuildingType.HERB_GARDEN, 0))
    }
}
