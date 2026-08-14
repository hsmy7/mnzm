package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.model.production.SlotStateMachine
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.repository.ProductionSlotDataPort
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 生产槽位结算健壮性测试（2026-08-09 B3/B4/B5 预存问题修复回归
 * + 对抗性审查 T1/T2 测试真实化）。
 *
 * - B3：死亡弟子槽位卡死——月变结算后死弟子槽位重置同时清空关联（三路径之一）
 * - B4：锁内吞失败——产出入库失败（addPill/addEquipmentStack Failure）视为炼制失败，
 *       不结算晋升但计数照常（防装备/丹药静默丢失）
 * - B5：resetSlotToIdle 与 auto-restart 排班异步竞争——repository 写串行化
 *       （缓存 RMW + DAO 写原子段）+ reset 守卫（仅 WORKING 且身份一致的槽才重置，
 *       防结算快照重建覆盖窗口内的玩家取消/关自动/排班新炼制）
 *
 * 对抗性审查修正：
 * - T1：原"并发双写"测试用 mock DAO（无真实挂起）实为顺序执行假阳性——改为
 *       GatedDao 挂起桥制造真实交错（首个 DAO 写挂起，排班协程在此期间并发进入），
 *       删除 writeMutex 本测试必然失败（缓存与 DAO 分叉）。
 * - T2：原守卫测试复制守卫表达式（生产代码改动测试仍绿）——改为直接调用
 *       [shouldResetSlotForCompletion] 真身 + 集成用例。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class ProductionSlotSettlementRobustnessTest {

    private fun newStore(alive: Boolean): FakeAtomicStateStore {
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
            gameData = gameData.copy(gameYear = 1, gameMonth = 3)
        }
        return store
    }

    private suspend fun newRepo(
        slots: List<ProductionSlot>,
        dao: ProductionSlotDataPort = mock()
    ): ProductionSlotRepository {
        val scopeProvider = mock<CoroutineScopeProvider>()
        whenever(scopeProvider.scope).thenReturn(CoroutineScope(Dispatchers.Unconfined))
        val repo = ProductionSlotRepository(dao, mock<BuildingConfigService>(), scopeProvider)
        repo.restoreSlots(slots, slotId = 1)
        return repo
    }

    private fun newProcessor(
        store: FakeAtomicStateStore,
        repo: ProductionSlotRepository,
        inventorySystem: InventorySystem
    ): ProductionProcessor {
        val rngManager = GameRngManager()
        rngManager.initSystemSeed(20260809L)
        val scopeProvider = mock<CoroutineScopeProvider>()
        whenever(scopeProvider.scope).thenReturn(CoroutineScope(Dispatchers.Unconfined))
        val formulaService = mock<FormulaService>()
        whenever(formulaService.calculateWorkDurationWithAllDisciples(any(), any())).thenReturn(1)
        return ProductionProcessor(
            stateStore = store,
            inventorySystem = inventorySystem,
            productionCoordinator = mock(),
            productionSlotRepository = repo,
            formulaService = formulaService,
            rngManager = rngManager,
            scopeProvider = scopeProvider,
            ioDispatcher = IoDispatcher(Dispatchers.Unconfined),
            inventoryConfig = mock()
        )
    }

    /** 产出成功 stub（withTrackingSource 透传 + 入库成功） */
    private fun stubInventorySuccess(): InventorySystem {
        val inv = mock<InventorySystem>()
        whenever(inv.withTrackingSource<Any>(any(), any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.getArgument(1) as () -> Any)()
        }
        whenever(inv.createEquipmentFromRecipe(any()))
            .thenReturn(EquipmentStack(name = "精铁剑", rarity = 1))
        whenever(inv.addEquipmentStack(any())).thenAnswer { invocation ->
            DomainResult.Success(invocation.getArgument(0) as EquipmentStack)
        }
        whenever(inv.addPill(any())).thenAnswer { invocation ->
            DomainResult.Success(invocation.getArgument(0) as Pill)
        }
        return inv
    }

    private fun forgeWorkingSlot(recipeId: String, discipleId: String) = ProductionSlot(
        id = "forge_0", slotIndex = 0, buildingType = BuildingType.FORGE,
        buildingId = BuildingNames.FORGE, status = ProductionSlotStatus.WORKING,
        recipeId = recipeId, assignedDiscipleId = discipleId, assignedDiscipleName = "弟子一",
        successRate = 1.0, outputItemName = "装备", outputItemRarity = 1,
        startYear = 1, startMonth = 1, duration = 1, baseDuration = 1
    )

    private fun alchemyWorkingSlot(recipeId: String, discipleId: String) = ProductionSlot(
        id = "alchemy_0", slotIndex = 0, buildingType = BuildingType.ALCHEMY,
        buildingId = BuildingNames.ALCHEMY, status = ProductionSlotStatus.WORKING,
        recipeId = recipeId, assignedDiscipleId = discipleId, assignedDiscipleName = "弟子一",
        successRate = 1.0, outputItemName = "丹药", outputItemRarity = 1,
        startYear = 1, startMonth = 1, duration = 1, baseDuration = 1
    )

    // ── B3：死亡弟子槽位卡死（月变路径） ──

    @Test
    fun `月变结算 - 死弟子槽位重置同时清空弟子关联`() = runTest {
        val store = newStore(alive = false)
        val tier1 = ForgeRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val repo = newRepo(listOf(forgeWorkingSlot(tier1.id, "1")))
        val processor = newProcessor(store, repo, stubInventorySuccess())

        processor.processBuildingProduction(1, 3)

        val slot = repo.getSlotsByBuildingId(BuildingNames.FORGE).first()
        assertEquals("槽位应重置为 IDLE", ProductionSlotStatus.IDLE, slot.status)
        assertNull("死弟子槽位应清空关联（防永久占用）", slot.assignedDiscipleId)
        assertEquals("死弟子槽位名称应清空", "", slot.assignedDiscipleName)
    }

    @Test
    fun `月变结算 - 存活弟子槽位保留关联供自动续炼`() = runTest {
        val store = newStore(alive = true)
        val tier1 = ForgeRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val repo = newRepo(listOf(forgeWorkingSlot(tier1.id, "1")))
        val processor = newProcessor(store, repo, stubInventorySuccess())

        processor.processBuildingProduction(1, 3)

        val slot = repo.getSlotsByBuildingId(BuildingNames.FORGE).first()
        assertEquals(ProductionSlotStatus.IDLE, slot.status)
        assertEquals("存活弟子关联应保留", "1", slot.assignedDiscipleId)
    }

    // ── B4：锁内吞失败（月变路径） ──

    @Test
    fun `月变结算 - 锻造入库失败视为炼制失败不晋升但计数照常`() = runTest {
        val store = newStore(alive = true)
        val inv = stubInventorySuccess()
        whenever(inv.addEquipmentStack(any()))
            .thenReturn(DomainResult.Failure(AppError.Domain.Production.InvalidSlot(slotIndex = 0)))
        val tier1 = ForgeRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val repo = newRepo(listOf(forgeWorkingSlot(tier1.id, "1")))
        val processor = newProcessor(store, repo, inv)

        processor.processBuildingProduction(1, 3)

        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("入库失败不晋升", 0, disciple.skills.forgeLevel)
        assertEquals("弟子回空闲", DiscipleStatus.IDLE, disciple.status)
        assertEquals("失败也计入完成次数", 1L,
            store.latestGameData.guideCounters[GuideCounterKeys.FORGE_COMPLETED])
    }

    @Test
    fun `月变结算 - 炼丹入库失败视为炼制失败不晋升但计数照常`() = runTest {
        val store = newStore(alive = true)
        val inv = stubInventorySuccess()
        whenever(inv.addPill(any()))
            .thenReturn(DomainResult.Failure(AppError.Domain.Production.InvalidSlot(slotIndex = 0)))
        val tier1 = PillRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val repo = newRepo(listOf(alchemyWorkingSlot(tier1.id, "1")))
        val processor = newProcessor(store, repo, inv)

        processor.processBuildingProduction(1, 3)

        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("入库失败不晋升", 0, disciple.skills.alchemyLevel)
        assertEquals(1L, store.latestGameData.guideCounters[GuideCounterKeys.ALCHEMY_COMPLETED])
    }

    // ── B5：reset 与排班竞态 ──

    /**
     * T2（对抗性审查）：守卫真身单测——守卫已提取为顶层 internal
     * [shouldResetSlotForCompletion]，测试直接调用真身（原测试复制表达式，
     * 生产代码改守卫测试仍绿）。
     */
    @Test
    fun `reset 守卫 - 身份一致重置许可 身份不符与已重置跳过（调用真身）`() {
        val tier1 = ForgeRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val settled = forgeWorkingSlot(tier1.id, "1").copy(completionMonth = 2)

        assertTrue(
            "同身份（WORKING + 同 completionMonth/recipeId）应允许重置",
            shouldResetSlotForCompletion(settled, settled)
        )
        val newProduction = settled.copy(recipeId = "new_recipe_id", completionMonth = 15)
        assertFalse(
            "排班新炼制（身份不同）应跳过——防材料双扣",
            shouldResetSlotForCompletion(newProduction, settled)
        )
        val idle = settled.copy(
            status = ProductionSlotStatus.IDLE, completionMonth = 0, recipeId = null
        )
        assertFalse(
            "已重置 IDLE 应跳过——防快照重建覆盖玩家取消/关自动",
            shouldResetSlotForCompletion(idle, settled)
        )
        val completed = settled.copy(status = ProductionSlotStatus.COMPLETED)
        assertFalse(
            "已收获 COMPLETED 应跳过——防快照重建覆盖收获结果",
            shouldResetSlotForCompletion(completed, settled)
        )
    }

    @Test
    fun `reset 守卫 - 集成：缓存槽已是排班新炼制时 repo transform 跳过重置`() = runTest {
        val tier1 = ForgeRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        // 本次结算的炼制（reset 参数持有）：completionMonth = 2
        val oldProduction = forgeWorkingSlot(tier1.id, "1").copy(completionMonth = 2)
        // 缓存槽已被排班启动新炼制：completionMonth/recipeId 均与旧炼制不同
        val newProduction = oldProduction.copy(
            recipeId = "new_recipe_id", completionMonth = 15, startMonth = 3
        )
        val repo = newRepo(listOf(newProduction))

        // 走真实守卫（真身）的 repo transform（resetSlotToIdle 同款语义）
        repo.updateSlotByBuildingId(BuildingNames.FORGE, 0) { s ->
            if (!shouldResetSlotForCompletion(s, oldProduction)) {
                s
            } else {
                ProductionSlot.createIdle(
                    id = s.id, slotIndex = s.slotIndex,
                    buildingType = BuildingType.FORGE, buildingId = BuildingNames.FORGE,
                    autoRestartEnabled = s.autoRestartEnabled,
                    assignedDiscipleId = s.assignedDiscipleId,
                    assignedDiscipleName = s.assignedDiscipleName,
                    recipeId = s.recipeId
                )
            }
        }

        val slot = repo.getSlotByBuildingId(BuildingNames.FORGE, 0)
            ?: error("槽位不存在")
        assertEquals("排班新炼制不应被打回 IDLE", ProductionSlotStatus.WORKING, slot.status)
        assertEquals("新炼制配方保持", "new_recipe_id", slot.recipeId)
    }

    @Test
    fun `reset 守卫 - 集成：缓存槽已被玩家取消（IDLE）时不被快照重建复活`() = runTest {
        val tier1 = ForgeRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        // 结算快照：WORKING 炼制（completionMonth = 2）
        val settled = forgeWorkingSlot(tier1.id, "1").copy(completionMonth = 2)
        // 缓存槽：玩家在 reset 执行前已取消（IDLE，配方已清）
        val cancelled = settled.copy(
            status = ProductionSlotStatus.IDLE, completionMonth = 0, recipeId = null,
            startYear = 0, startMonth = 0
        )
        val repo = newRepo(listOf(cancelled))

        repo.updateSlotByBuildingId(BuildingNames.FORGE, 0) { s ->
            if (!shouldResetSlotForCompletion(s, settled)) {
                s
            } else {
                ProductionSlot.createIdle(
                    id = s.id, slotIndex = s.slotIndex,
                    buildingType = BuildingType.FORGE, buildingId = BuildingNames.FORGE,
                    autoRestartEnabled = s.autoRestartEnabled,
                    assignedDiscipleId = s.assignedDiscipleId,
                    assignedDiscipleName = s.assignedDiscipleName,
                    recipeId = s.recipeId
                )
            }
        }

        val slot = repo.getSlotByBuildingId(BuildingNames.FORGE, 0)
            ?: error("槽位不存在")
        assertEquals("已取消的 IDLE 槽应保持 IDLE（不被复活）",
            ProductionSlotStatus.IDLE, slot.status)
        assertNull("已取消槽配方应保持清空", slot.recipeId)
    }

    /**
     * T1（对抗性审查）：真实交错并发测试——原 mock DAO 无真实挂起（Unconfined
     * 下顺序执行，删除 writeMutex 也通过）。改用 [GatedDao] 挂起桥：reset 协程
     * 在首个 DAO 写处挂起（持有 writeMutex），排班协程在此期间并发进入——
     * 有 writeMutex：排班阻塞至 reset 释放，读到最新 IDLE 缓存，DAO 写入顺序
     * 与缓存一致；无 writeMutex：排班读到 reset 已完成的缓存（IDLE）并抢先
     * DAO 写 WORKING，reset 恢复后写过期 IDLE → DAO 最后写入 ≠ 缓存（分叉），
     * 断言失败——判别性成立。
     */
    @Test
    fun `并发双写 - reset 与排班交错时 writeMutex 保证缓存与 DAO 最终一致不分叉`() = runTest {
        val dao = GatedDao()
        val tier1 = ForgeRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val completedSlot = forgeWorkingSlot(tier1.id, "1")
            .copy(status = ProductionSlotStatus.COMPLETED)
        val repo = newRepo(listOf(completedSlot), dao)

        // reset 协程：COMPLETED→IDLE（缓存 RMW 完成）后挂在 DAO 写上
        val resetJob = async {
            repo.updateSlotByBuildingId(BuildingNames.FORGE, 0) { s ->
                ProductionSlot.createIdle(
                    id = s.id, slotIndex = s.slotIndex,
                    buildingType = BuildingType.FORGE, buildingId = BuildingNames.FORGE,
                    autoRestartEnabled = s.autoRestartEnabled,
                    assignedDiscipleId = s.assignedDiscipleId,
                    assignedDiscipleName = s.assignedDiscipleName,
                    recipeId = s.recipeId
                )
            }
        }
        runCurrent() // resetJob 执行至 dao.update 挂起（持有 writeMutex）

        // 排班协程：IDLE→WORKING（必须等 reset 的 DAO 写完成后才能读到最新缓存）
        val startJob = async {
            repo.updateSlotByBuildingId(BuildingNames.FORGE, 0) { s ->
                if (s.status != ProductionSlotStatus.IDLE) s
                else SlotStateMachine.startProduction(
                    s, tier1.id, "凡品配方", 1, 1, 1,
                    "1", "弟子一", 1.0, emptyMap(), null, "装备", 1
                ).getOrElse { s }
            }
        }
        runCurrent() // startJob 阻塞在 writeMutex 上
        dao.firstUpdateGate.complete(Unit)
        resetJob.await()
        startJob.await()

        // 不变量：排班在 reset 完成后启动成功 + DAO 最后一次写入 == 缓存最终值
        val finalCache = repo.getSlotByBuildingId(BuildingNames.FORGE, 0)
            ?: error("槽位不存在")
        assertEquals("排班应在 reset 完成后读到 IDLE 并启动",
            ProductionSlotStatus.WORKING, finalCache.status)
        assertEquals("缓存与 DAO 最终一致（不分叉）", finalCache, dao.updates.last())
    }
}

/**
 * 挂起桥 DAO（T1 对抗性审查）：首个 [ProductionSlotDataPort.update] 在
 * [firstUpdateGate] 上挂起（模拟真实 IO 延迟），后续写入立即完成。
 * 真实挂起点使"reset 与排班交错"可达——mock DAO 无挂起点导致原并发测试假阳性。
 */
private class GatedDao : ProductionSlotDataPort {
    val firstUpdateGate = CompletableDeferred<Unit>()
    private var firstUpdateEntered = false
    val updates = mutableListOf<ProductionSlot>()

    override fun getAllSync(): List<ProductionSlot> = emptyList()

    override suspend fun update(slot: ProductionSlot) {
        if (!firstUpdateEntered) {
            firstUpdateEntered = true
            firstUpdateGate.await()
        }
        updates.add(slot)
    }

    override suspend fun updateAll(slots: List<ProductionSlot>) {
        updates.addAll(slots)
    }

    override suspend fun insert(slot: ProductionSlot) {
        updates.add(slot)
    }

    override suspend fun insertAll(slots: List<ProductionSlot>) {
        updates.addAll(slots)
    }

    override suspend fun deleteById(id: String) = Unit

    override suspend fun deleteBySlot(slotId: Int) = Unit

    override suspend fun deleteBySlotAndBuildingType(
        slotId: Int, buildingType: BuildingType
    ) = Unit
}
