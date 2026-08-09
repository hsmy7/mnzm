package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.service.FormulaService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 读档收获路径死亡弟子与产出失败测试（2026-08-09 B3/B4 预存问题修复回归）。
 *
 * - B3：死弟子槽位卡死——读档收获后 Repository 槽位补清弟子关联
 *       （SlotStateMachine.resetSlot 保留弟子字段，不清导致死弟子永久占用槽位）
 * - B4：锁内吞失败——产出入库失败（addPill/addEquipmentStack Failure）视为炼制失败，
 *       不结算晋升但计数照常（防装备/丹药静默丢失）
 */
@RunWith(RobolectricTestRunner::class)
class BuildingServiceDeathDiscipleTest {

    /** 构造指定存活状态的弟子 store（列式写入 DiscipleTables） */
    private fun newStoreWithDisciple(alive: Boolean): FakeAtomicStateStore {
        val store = FakeAtomicStateStore()
        store.update {
            discipleTables.writeAllowed = true
            discipleTables.addId(1)
            discipleTables.names[1] = "弟子一"
            discipleTables.statuses[1] = DiscipleStatus.IDLE
            discipleTables.isAlive[1] = if (alive) 1 else 0
            discipleTables.realms[1] = 9
            discipleTables.realmLayers[1] = 1
            discipleTables.portraitRes[1] = "portrait_1"
            discipleTables.pillRefinings[1] = 50
            discipleTables.artifactRefinings[1] = 50
            discipleTables.alchemyLevels[1] = 0
            discipleTables.alchemyPromotionCounts[1] = 0
            discipleTables.forgeLevels[1] = 0
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
        whenever(inv.createEquipmentFromRecipe(any()))
            .thenReturn(EquipmentStack(name = "精铁剑", rarity = 1))
        whenever(inv.addPill(any())).thenAnswer { invocation ->
            DomainResult.Success(invocation.getArgument(0) as Pill)
        }
        whenever(inv.addEquipmentStack(any())).thenAnswer { invocation ->
            DomainResult.Success(invocation.getArgument(0) as EquipmentStack)
        }
        return inv
    }

    private fun forgeCompletedSlot(recipeId: String) = ProductionSlot(
        id = "forge_0", slotIndex = 0, buildingType = BuildingType.FORGE,
        buildingId = BuildingNames.FORGE, status = ProductionSlotStatus.COMPLETED,
        recipeId = recipeId, assignedDiscipleId = "1", assignedDiscipleName = "弟子一",
        successRate = 1.0, outputItemName = "装备", outputItemRarity = 1
    )

    private fun alchemyCompletedSlot(recipeId: String) = ProductionSlot(
        id = "alchemy_0", slotIndex = 0, buildingType = BuildingType.ALCHEMY,
        buildingId = BuildingNames.ALCHEMY, status = ProductionSlotStatus.COMPLETED,
        recipeId = recipeId, assignedDiscipleId = "1", assignedDiscipleName = "弟子一",
        successRate = 1.0, outputItemName = "丹药", outputItemRarity = 1
    )

    // ── B3：死弟子槽位卡死（读档路径） ──

    @Test
    fun `读档收获 - 死弟子槽位单事务重置并清空弟子关联`() = runTest {
        val store = newStoreWithDisciple(alive = false)
        val repo = mock<ProductionSlotRepository>()
        val tier1 = ForgeRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val service = newService(store, repo, inventorySystem = stubInventory())

        service.autoHarvestForgeSlot(forgeCompletedSlot(tier1.id))

        // 合并事务（发现2/L5）：reset 与 B3 补清在单次 updateSlotByBuildingId 内完成，
        // 死弟子 → 重置回 IDLE 且清空弟子关联（无"IDLE+死弟子"中间态被并发排班占用）
        val captor = argumentCaptor<(ProductionSlot) -> ProductionSlot>()
        verify(repo).updateSlotByBuildingId(
            eq(BuildingNames.FORGE), eq(0), captor.capture())
        val transformed = captor.firstValue(forgeCompletedSlot(tier1.id))
        assertEquals("死弟子槽位应重置回 IDLE",
            ProductionSlotStatus.IDLE, transformed.status)
        assertNull("死弟子槽位应清空关联", transformed.assignedDiscipleId)
        assertEquals("死弟子槽位名称应清空", "", transformed.assignedDiscipleName)
    }

    @Test
    fun `读档收获 - 存活弟子单事务重置且保留弟子关联（供自动续炼）`() = runTest {
        val store = newStoreWithDisciple(alive = true)
        val repo = mock<ProductionSlotRepository>()
        val tier1 = ForgeRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val service = newService(store, repo, inventorySystem = stubInventory())

        service.autoHarvestForgeSlot(forgeCompletedSlot(tier1.id))

        // 合并事务后无论生死都走单次 updateSlotByBuildingId：
        // 存活 → 重置回 IDLE 但保留弟子关联（auto-restart 排班从 IDLE 槽续炼）
        val captor = argumentCaptor<(ProductionSlot) -> ProductionSlot>()
        verify(repo).updateSlotByBuildingId(
            eq(BuildingNames.FORGE), eq(0), captor.capture())
        val transformed = captor.firstValue(forgeCompletedSlot(tier1.id))
        assertEquals("存活弟子槽位应重置回 IDLE",
            ProductionSlotStatus.IDLE, transformed.status)
        assertEquals("存活弟子关联保留（供自动续炼）", "1", transformed.assignedDiscipleId)
        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("存活弟子收获后回空闲", DiscipleStatus.IDLE, disciple.status)
    }

    // ── B4：锁内吞失败（读档路径） ──

    @Test
    fun `读档收获 - 炼丹入库失败视为炼制失败不晋升但计数照常`() = runTest {
        val store = newStoreWithDisciple(alive = true)
        val inv = stubInventory()
        whenever(inv.addPill(any()))
            .thenReturn(DomainResult.Failure(AppError.Domain.Production.InvalidSlot(slotIndex = 0)))
        val tier1 = com.xianxia.sect.core.registry.PillRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val repo = mock<ProductionSlotRepository>()
        whenever(repo.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.ALCHEMY))
            .thenReturn(listOf(alchemyCompletedSlot(tier1.id)))
        val service = newService(store, repo, inventorySystem = inv)

        service.autoHarvestCompletedAlchemySlots()

        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("入库失败不晋升", 0, disciple.skills.alchemyLevel)
        assertEquals("弟子回空闲", DiscipleStatus.IDLE, disciple.status)
        assertEquals("失败也计入完成次数", 1L,
            store.latestGameData.guideCounters[GuideCounterKeys.ALCHEMY_COMPLETED])
    }

    @Test
    fun `读档收获 - 锻造入库失败视为炼制失败不晋升但计数照常`() = runTest {
        val store = newStoreWithDisciple(alive = true)
        val inv = stubInventory()
        whenever(inv.addEquipmentStack(any()))
            .thenReturn(DomainResult.Failure(AppError.Domain.Production.InvalidSlot(slotIndex = 0)))
        val tier1 = ForgeRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val service = newService(store, inventorySystem = inv)

        service.autoHarvestForgeSlot(forgeCompletedSlot(tier1.id))

        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("入库失败不晋升", 0, disciple.skills.forgeLevel)
        assertEquals("弟子回空闲", DiscipleStatus.IDLE, disciple.status)
        assertEquals("失败也计入完成次数", 1L,
            store.latestGameData.guideCounters[GuideCounterKeys.FORGE_COMPLETED])
        verify(inv).addEquipmentStack(any())
    }

    @Test
    fun `读档收获 - 入库部分成功（溢出转邮件）视为炼制成功正常晋升`() = runTest {
        val store = newStoreWithDisciple(alive = true)
        val inv = stubInventory()
        whenever(inv.addPill(any())).thenAnswer { invocation ->
            DomainResult.Partial(invocation.getArgument(0) as Pill, overflow = 1)
        }
        val tier1 = com.xianxia.sect.core.registry.PillRecipeDatabase.getAllRecipes()
            .first { it.tier == 1 }
        val repo = mock<ProductionSlotRepository>()
        whenever(repo.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.ALCHEMY))
            .thenReturn(listOf(alchemyCompletedSlot(tier1.id)))
        val service = newService(store, repo, inventorySystem = inv)

        service.autoHarvestCompletedAlchemySlots()

        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("溢出转邮件是成功路径 → 凡品成功一次晋升一级",
            1, disciple.skills.alchemyLevel)
        assertEquals("弟子回空闲", DiscipleStatus.IDLE, disciple.status)
    }
}
