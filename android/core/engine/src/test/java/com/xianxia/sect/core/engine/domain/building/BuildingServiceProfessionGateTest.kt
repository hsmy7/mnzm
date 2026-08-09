package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.production.MaterialUpdate
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.production.ProductionStartData
import com.xianxia.sect.core.engine.service.FormulaService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 炼丹/锻造职业品阶门禁测试（2026-08-09 职业系统）。
 *
 * 覆盖 BuildingService.startAlchemy/startForging 的反绕过拦截：
 * 无职业/职业等级不足时返回 RecipeTierLocked（材料未扣、槽位不变），
 * 达标配方正常放行并计算职业化成功率。
 */
@RunWith(RobolectricTestRunner::class)
class BuildingServiceProfessionGateTest {

    private fun newService(
        store: FakeAtomicStateStore,
        repository: ProductionSlotRepository = mock(),
        coordinator: ProductionCoordinator = mock(),
        formulaService: FormulaService = mock()
    ): BuildingService {
        return BuildingService(
            stateStore = store,
            productionCoordinator = coordinator,
            productionSlotRepository = repository,
            inventorySystem = mock<InventorySystem>(),
            formulaService = formulaService,
            rngManager = mock<GameRngManager>(),
            assignmentGate = DiscipleAssignmentGate(DiscipleAssignmentRegistry()),
            ioDispatcher = IoDispatcher(Dispatchers.Unconfined)
        )
    }

    /** 构造带指定职业等级弟子的 store（disciples flow 供职业拦截查询） */
    private fun storeWithDisciple(
        alchemyLevel: Int = 0,
        forgeLevel: Int = 0
    ): FakeAtomicStateStore {
        val store = FakeAtomicStateStore()
        store.disciples.value = listOf(
            Disciple(
                id = "1",
                name = "弟子一",
                realm = 9,
                skills = SkillStats(pillRefining = 50, artifactRefining = 50,
                    alchemyLevel = alchemyLevel, forgeLevel = forgeLevel)
            )
        )
        return store
    }

    private fun idleSlot(
        buildingId: String,
        buildingType: BuildingType,
        discipleId: String? = "1"
    ) = ProductionSlot(
        id = "${buildingType.name}_0", slotIndex = 0, buildingType = buildingType,
        buildingId = buildingId, status = ProductionSlotStatus.IDLE,
        assignedDiscipleId = discipleId, assignedDiscipleName = "弟子一"
    )

    // ── 越阶拦截（RecipeTierLocked）──

    @Test
    fun `startAlchemy - 无职业炼灵品被拦截`() = runTest {
        val store = storeWithDisciple(alchemyLevel = 0)
        val repo = mock<ProductionSlotRepository>()
        whenever(repo.getSlotByBuildingId(BuildingNames.ALCHEMY, 0))
            .thenReturn(idleSlot(BuildingNames.ALCHEMY, BuildingType.ALCHEMY))
        val service = newService(store, repo)

        val tier2Pill = PillRecipeDatabase.getAllRecipes().first { it.tier == 2 }
        val result = service.startAlchemy(0, tier2Pill.id)

        assertTrue(result.isFailure)
        val error = (result as DomainResult.Failure).error
        assertTrue(
            "应为 RecipeTierLocked，实际 $error",
            error is AppError.Domain.Production.RecipeTierLocked
        )
        val locked = error as AppError.Domain.Production.RecipeTierLocked
        assertEquals("无职业可炼最高阶应为 tier1", 1, locked.maxCraftableTier)
    }

    @Test
    fun `startAlchemy - 一级炼丹师炼灵品放行且成功率含职业加成`() = runTest {
        val store = storeWithDisciple(alchemyLevel = 1)
        val repo = mock<ProductionSlotRepository>()
        whenever(repo.getSlotByBuildingId(BuildingNames.ALCHEMY, 0))
            .thenReturn(idleSlot(BuildingNames.ALCHEMY, BuildingType.ALCHEMY))
        val coordinator = mock<ProductionCoordinator>()
        val startData = ProductionSlot(
            slotIndex = 0, buildingType = BuildingType.ALCHEMY,
            buildingId = BuildingNames.ALCHEMY, status = ProductionSlotStatus.IDLE
        )
        whenever(coordinator.startAlchemyAtomic(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(DomainResult.Success(
                ProductionStartData(startData, MaterialUpdate(emptyList(), emptyList()))
            ))
        val formulaService = mock<FormulaService>()
        // 职业加成：一级（可炼 tier2）炼同阶 tier2 → professionZone=0；属性 50 → skillZone=0.12
        whenever(formulaService.buildSuccessRateZones(any(), any(), any(), any(), any()))
            .thenReturn(FormulaService.SuccessRateZones(skillZone = 0.12))
        val service = newService(store, repo, coordinator, formulaService)

        val tier2Pill = PillRecipeDatabase.getAllRecipes().first { it.tier == 2 }
        val result = service.startAlchemy(0, tier2Pill.id)

        assertTrue("一级炼丹师应可炼灵品", result.isSuccess)
    }

    @Test
    fun `startForging - 无职业锻宝品被拦截`() = runTest {
        val store = storeWithDisciple(forgeLevel = 0)
        val repo = mock<ProductionSlotRepository>()
        whenever(repo.getSlotByBuildingId(BuildingNames.FORGE, 0))
            .thenReturn(idleSlot(BuildingNames.FORGE, BuildingType.FORGE))
        val service = newService(store, repo)

        val tier3Forge = ForgeRecipeDatabase.getAllRecipes().first { it.tier == 3 }
        val result = service.startForging(0, tier3Forge.id)

        assertTrue(result.isFailure)
        val error = (result as DomainResult.Failure).error
        assertTrue(
            "应为 RecipeTierLocked，实际 $error",
            error is AppError.Domain.Production.RecipeTierLocked
        )
    }

    @Test
    fun `startForging - 无职业锻凡品放行`() = runTest {
        val store = storeWithDisciple(forgeLevel = 0)
        val repo = mock<ProductionSlotRepository>()
        whenever(repo.getSlotByBuildingId(BuildingNames.FORGE, 0))
            .thenReturn(idleSlot(BuildingNames.FORGE, BuildingType.FORGE))
        val coordinator = mock<ProductionCoordinator>()
        val startData = ProductionSlot(
            slotIndex = 0, buildingType = BuildingType.FORGE,
            buildingId = BuildingNames.FORGE, status = ProductionSlotStatus.IDLE
        )
        whenever(coordinator.startForgingAtomic(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(DomainResult.Success(
                ProductionStartData(startData, MaterialUpdate(emptyList(), emptyList()))
            ))
        val formulaService = mock<FormulaService>()
        whenever(formulaService.buildSuccessRateZones(any(), any(), any(), any(), any()))
            .thenReturn(FormulaService.SuccessRateZones(skillZone = 0.12))
        val service = newService(store, repo, coordinator, formulaService)

        val tier1Forge = ForgeRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val result = service.startForging(0, tier1Forge.id)

        assertTrue("无职业应可锻凡品", result.isSuccess)
    }

    @Test
    fun `startAlchemy - 槽位无弟子返回 DiscipleNotAvailable`() = runTest {
        val store = storeWithDisciple(alchemyLevel = 1)
        val repo = mock<ProductionSlotRepository>()
        whenever(repo.getSlotByBuildingId(BuildingNames.ALCHEMY, 0))
            .thenReturn(idleSlot(BuildingNames.ALCHEMY, BuildingType.ALCHEMY, discipleId = null))
        val service = newService(store, repo)

        val tier1Pill = PillRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val result = service.startAlchemy(0, tier1Pill.id)

        assertTrue(result.isFailure)
        assertTrue(
            "应为 DiscipleNotAvailable",
            (result as DomainResult.Failure).error is AppError.Domain.Production.DiscipleNotAvailable
        )
    }
}
