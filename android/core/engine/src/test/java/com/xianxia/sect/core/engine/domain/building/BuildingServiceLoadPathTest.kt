package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.FormulaService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.system.building.ForgeSystem
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * BuildingService 读档/惰性收获路径测试（2026-08-09 对抗性审查修复回归）。
 *
 * 覆盖读档路径与月变路径的行为一致性：
 * - 炼丹读档收获补职业晋升与引导/年度统计（原缺失——正常玩家读档即丢一次晋升计数）
 * - 锻造读档从 100% 产出改为真实成功率判定（成功才产出 + 晋升，失败计数照常）
 * - 配方无效（数据损坏）时不结算晋升（recipeTier 兜底 0）
 * - ForgeSystem 不再重复触发生产结算（双结算修复）
 */
@RunWith(RobolectricTestRunner::class)
class BuildingServiceLoadPathTest {

    /** 构造带指定职业等级弟子的 store（列式写入 DiscipleTables） */
    private fun newStoreWithDisciple(
        alchemyLevel: Int = 0,
        forgeLevel: Int = 0
    ): FakeAtomicStateStore {
        val store = FakeAtomicStateStore()
        store.update {
            discipleTables.writeAllowed = true
            discipleTables.addId(1)
            discipleTables.names[1] = "弟子一"
            discipleTables.statuses[1] = DiscipleStatus.IDLE
            discipleTables.isAlive[1] = 1
            discipleTables.realms[1] = 9
            discipleTables.realmLayers[1] = 1
            discipleTables.portraitRes[1] = "portrait_1"
            discipleTables.pillRefinings[1] = 50
            discipleTables.artifactRefinings[1] = 50
            discipleTables.alchemyLevels[1] = alchemyLevel
            discipleTables.alchemyPromotionCounts[1] = 0
            discipleTables.forgeLevels[1] = forgeLevel
            discipleTables.forgePromotionCounts[1] = 0
        }
        return store
    }

    private fun newService(
        store: FakeAtomicStateStore,
        repo: ProductionSlotRepository = mock(),
        inventorySystem: InventorySystem = mock()
    ): BuildingService {
        val rngManager = GameRngManager()
        rngManager.initSystemSeed(20260809L)
        return BuildingService(
            stateStore = store,
            productionCoordinator = mock(),
            productionSlotRepository = repo,
            inventorySystem = inventorySystem,
            formulaService = mock<FormulaService>(),
            rngManager = rngManager,
            assignmentGate = DiscipleAssignmentGate(DiscipleAssignmentRegistry()),
            ioDispatcher = IoDispatcher(Dispatchers.Unconfined)
        )
    }

    /** withTrackingSource 透传 + 入库成功（mock 默认不执行 lambda 且返回 null） */
    private fun stubInventory(): InventorySystem {
        val inv = mock<InventorySystem>()
        whenever(inv.withTrackingSource<Any>(any(), any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.getArgument(1) as () -> Any)()
        }
        whenever(inv.addPill(any())).thenAnswer { invocation ->
            DomainResult.Success(invocation.getArgument(0) as Pill)
        }
        whenever(inv.addEquipmentStack(any())).thenAnswer { invocation ->
            DomainResult.Success(invocation.getArgument(0) as EquipmentStack)
        }
        return inv
    }

    private fun alchemyCompletedSlot(
        recipeId: String,
        successRate: Double = 1.0
    ) = ProductionSlot(
        id = "alchemy_0", slotIndex = 0, buildingType = BuildingType.ALCHEMY,
        buildingId = BuildingNames.ALCHEMY, status = ProductionSlotStatus.COMPLETED,
        recipeId = recipeId, assignedDiscipleId = "1", assignedDiscipleName = "弟子一",
        successRate = successRate, outputItemName = "丹药", outputItemRarity = 1
    )

    private fun forgeCompletedSlot(
        recipeId: String,
        successRate: Double = 1.0
    ) = ProductionSlot(
        id = "forge_0", slotIndex = 0, buildingType = BuildingType.FORGE,
        buildingId = BuildingNames.FORGE, status = ProductionSlotStatus.COMPLETED,
        recipeId = recipeId, assignedDiscipleId = "1", assignedDiscipleName = "弟子一",
        successRate = successRate, outputItemName = "装备", outputItemRarity = 1
    )

    // ── 炼丹读档路径 ──

    @Test
    fun `autoHarvestCompletedAlchemySlots - 读档成功收获补职业晋升与统计`() = runTest {
        val store = newStoreWithDisciple(alchemyLevel = 0)
        val repo = com.xianxia.sect.core.engine.testProductionSlotRepository()
        val tier1 = PillRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        repo.loadSlots(listOf(alchemyCompletedSlot(tier1.id, successRate = 1.0)))
        val service = newService(store, repo, inventorySystem = stubInventory())

        service.autoHarvestCompletedAlchemySlots()

        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("读档成功收获应晋升一级", 1, disciple.skills.alchemyLevel)
        assertEquals("晋升后计数清零", 0, disciple.skills.alchemyPromotionCount)
        assertEquals("弟子应回到空闲", DiscipleStatus.IDLE, disciple.status)
        assertEquals("引导计数 +1", 1L, store.latestGameData.guideCounters[GuideCounterKeys.ALCHEMY_COMPLETED])
        assertEquals("年度炼丹计数 +1", 1, store.latestGameData.annualAlchemyCount)
    }

    @Test
    fun `autoHarvestCompletedAlchemySlots - 读档失败不晋升但计数照常`() = runTest {
        val store = newStoreWithDisciple(alchemyLevel = 0)
        val repo = com.xianxia.sect.core.engine.testProductionSlotRepository()
        val tier1 = PillRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        repo.loadSlots(listOf(alchemyCompletedSlot(tier1.id, successRate = 0.0)))
        val service = newService(store, repo, inventorySystem = stubInventory())

        service.autoHarvestCompletedAlchemySlots()

        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("失败不晋升", 0, disciple.skills.alchemyLevel)
        assertEquals("失败不累计晋升次数", 0, disciple.skills.alchemyPromotionCount)
        assertEquals("弟子仍回空闲", DiscipleStatus.IDLE, disciple.status)
        assertEquals("失败也计入完成次数", 1L, store.latestGameData.guideCounters[GuideCounterKeys.ALCHEMY_COMPLETED])
    }

    @Test
    fun `autoHarvestCompletedAlchemySlots - 配方无效不结算晋升但计数照常`() = runTest {
        val store = newStoreWithDisciple(alchemyLevel = 0)
        val repo = com.xianxia.sect.core.engine.testProductionSlotRepository()
        repo.loadSlots(listOf(alchemyCompletedSlot("invalid_recipe_xyz", successRate = 1.0)))
        val service = newService(store, repo, inventorySystem = stubInventory())

        service.autoHarvestCompletedAlchemySlots()

        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("配方无效不结算晋升", 0, disciple.skills.alchemyLevel)
        assertEquals("计数仍 +1", 1L, store.latestGameData.guideCounters[GuideCounterKeys.ALCHEMY_COMPLETED])
    }

    // ── 锻造读档路径 ──

    @Test
    fun `autoHarvestForgeSlot - 读档锻造成功产装备并晋升`() = runTest {
        val store = newStoreWithDisciple(forgeLevel = 0)
        val inv = stubInventory()
        whenever(inv.createEquipmentFromRecipe(any()))
            .thenReturn(EquipmentStack(name = "精铁剑", rarity = 1))
        val tier1 = ForgeRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val repo = com.xianxia.sect.core.engine.testProductionSlotRepository()
        repo.loadSlots(listOf(forgeCompletedSlot(tier1.id, successRate = 1.0)))
        val service = newService(store, repo, inventorySystem = inv)

        service.autoHarvestForgeSlot(forgeCompletedSlot(tier1.id, successRate = 1.0))

        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("读档锻造成功应晋升一级", 1, disciple.skills.forgeLevel)
        assertEquals("弟子应回到空闲", DiscipleStatus.IDLE, disciple.status)
        verify(inv).addEquipmentStack(any())
        assertEquals("引导计数 +1", 1L, store.latestGameData.guideCounters[GuideCounterKeys.FORGE_COMPLETED])
    }

    @Test
    fun `autoHarvestForgeSlot - 读档锻造失败不产出不晋升但计数照常`() = runTest {
        val store = newStoreWithDisciple(forgeLevel = 0)
        val inv = stubInventory()
        whenever(inv.createEquipmentFromRecipe(any()))
            .thenReturn(EquipmentStack(name = "精铁剑", rarity = 1))
        val tier1 = ForgeRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val repo = com.xianxia.sect.core.engine.testProductionSlotRepository()
        repo.loadSlots(listOf(forgeCompletedSlot(tier1.id, successRate = 0.0)))
        val service = newService(store, repo, inventorySystem = inv)

        service.autoHarvestForgeSlot(forgeCompletedSlot(tier1.id, successRate = 0.0))

        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("失败不晋升", 0, disciple.skills.forgeLevel)
        assertEquals("失败不累计晋升次数", 0, disciple.skills.forgePromotionCount)
        assertEquals("弟子仍回空闲", DiscipleStatus.IDLE, disciple.status)
        verify(inv, never()).addEquipmentStack(any())
        assertEquals("失败也计入完成次数", 1L, store.latestGameData.guideCounters[GuideCounterKeys.FORGE_COMPLETED])
    }

    // ── 双结算修复 ──

    @Test
    fun `ForgeSystem 月变不再重复触发生产结算`() = runTest {
        val cultivationService = mock<CultivationService>()
        val scopeProvider = mock<CoroutineScopeProvider>()
        whenever(scopeProvider.scope).thenReturn(CoroutineScope(Dispatchers.Unconfined))
        val system = ForgeSystem(cultivationService, scopeProvider)
        val state = MutableGameState(
            gameData = com.xianxia.sect.core.model.GameData(gameYear = 3, gameMonth = 5),
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
            isSaving = false
        )

        system.onMonthlyEvent(state)

        verify(cultivationService).processAutoForge()
        verify(cultivationService, never()).processBuildingProduction(any(), any())
    }
}
