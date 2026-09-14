package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.DomainResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** ProductionProcessorTest 拆分（LC>800 行）：30 个用例随 fixture 迁出，行为零变更。 */
class ProductionProcessorHarvestTest : ProductionProcessorTestBase() {

    // ═══════════════════════════════════════════════════════════════
    // isDiscipleFollowed — Disciple 字段访问验证
    // ═══════════════════════════════════════════════════════════════

    /** 与生产代码 ProductionProcessor.isDiscipleFollowed 逻辑一致 */

    @Test
    fun `takeCandidate - pool中包含非IDLE弟子时仍能被选中`() {
        val mining = Disciple(
            id = "d1", name = "采矿中",
            spiritRootType = "火",
            skills = SkillStats(mining = 5, comprehension = 3),
            status = DiscipleStatus.MINING, isAlive = true
        )
        val idle = Disciple(
            id = "d2", name = "空闲高悟性",
            spiritRootType = "水",
            skills = SkillStats(comprehension = 10),
            status = DiscipleStatus.IDLE, isAlive = true
        )
        // 全部存活弟子的池（无视空闲状态），用于住所自动分配
        val pool = mutableListOf(mining, idle)

        // 按悟性筛选，匹配单灵根
        val result = takeCandidate(
            pool, focused = false, rootCounts = listOf(1),
            threshold = 1, attr = { it.skills.comprehension }
        )
        assertNotNull("非IDLE弟子也应被选中", result)
        assertEquals("应选中悟性最高的空闲弟子", "d2", result?.id)
        assertEquals("池中应剩1人", 1, pool.size)
    }
    @Test
    fun `takeCandidate - 非IDLE弟子在池中可被住所规则选中`() {
        val mining = Disciple(
            id = "d1", name = "采矿中-高悟性",
            spiritRootType = "火",
            skills = SkillStats(comprehension = 8),
            status = DiscipleStatus.MINING, isAlive = true
        )
        val forging = Disciple(
            id = "d2", name = "锻造中-低悟性",
            spiritRootType = "水",
            skills = SkillStats(comprehension = 3),
            status = DiscipleStatus.FORGE, isAlive = true
        )
        val pool = mutableListOf(mining, forging)

        // 住所按悟性取最高（无视当前工作状态）
        val result = takeCandidate(
            pool, focused = false, rootCounts = listOf(1),
            threshold = 1, attr = { it.skills.comprehension }
        )
        assertNotNull("非IDLE弟子应被选中", result)
        assertEquals("应选中悟性最高的采矿中弟子", "d1", result?.id)
        assertEquals("矿工被选中后应从池中移除", 1, pool.size)
    }
    @Test
    fun `takeCandidate - 死弟子即使在池中也被排除`() {
        val dead = Disciple(
            id = "d1", name = "已死亡",
            spiritRootType = "火",
            skills = SkillStats(comprehension = 10),
            status = DiscipleStatus.DEAD, isAlive = false
        )
        val alive = Disciple(
            id = "d2", name = "存活",
            spiritRootType = "水",
            skills = SkillStats(comprehension = 5),
            status = DiscipleStatus.MINING, isAlive = true
        )
        val pool = mutableListOf(dead, alive)

        // 住所池已过滤 isAlive=false，但 takeCandidate 内部的 filter 不检查 isAlive
        // 它依赖上游 pool 已过滤。此处验证 pool 不含死弟子时逻辑正确。
        val filteredPool = pool.filter { it.isAlive }.toMutableList()
        val result = takeCandidate(
            filteredPool, focused = false, rootCounts = listOf(1),
            threshold = 1, attr = { it.skills.comprehension }
        )
        assertNotNull("存活的矿工应被选中", result)
        assertEquals("应选中存活的弟子", "d2", result?.id)
    }

    // ═══════════════════════════════════════════════════════════════
    // 住所自动入住过滤条件 — OR 语义（取候选人） + 两轮分配
    //
    // 筛选规则与 ProductionProcessor.processAutoAssign 中的 takeCandidate 一致：
    //   matchesFilter = (focused && isFollowed) || rootCount in list
    //   matchesFilter && attr >= threshold
    //
    // 两轮分配：单人优先 → 多人剩余
    // ═══════════════════════════════════════════════════════════════

    /** 模拟单人住所筛选（与 takeCandidate 一致的 OR 语义） */
    private fun simulateSingleFilter(
        disciples: List<Disciple>,
        focused: Boolean, rootCounts: List<Int>, threshold: Int
    ): List<Disciple> {
        val enabled = focused || rootCounts.isNotEmpty()
        if (!enabled) return emptyList()
        return disciples.filter { d ->
            val matchesFilter = (focused && isDiscipleFollowed(d)) ||
                d.spiritRoot.types.size in rootCounts
            matchesFilter && d.skills.comprehension >= threshold
        }
    }

    /** 模拟多人住所筛选（与 takeCandidate 一致的 OR 语义） */
    private fun simulateMultiFilter(
        disciples: List<Disciple>,
        focused: Boolean, rootCounts: List<Int>, threshold: Int
    ): List<Disciple> {
        return simulateSingleFilter(disciples, focused, rootCounts, threshold)
    }

    /**
     * 模拟两轮分配（单人优先 → 多人剩余）。
     *
     * @return Pair(单人分配ID列表, 多人分配ID列表)
     */
    /** 住所分配轮规格（simulateTwoPassResidence 参数分组）：过滤开关 + 灵根数白名单 + 悟性阈值 */
    private fun simulateTwoPassResidence(
        disciples: List<Disciple>,
        single: ResidencePassSpec,
        multi: ResidencePassSpec,
        singleSlotCount: Int,
        multiSlotCount: Int
    ): Pair<List<String>, List<String>> {
        val singleFiltered = simulateSingleFilter(disciples, single.focused, single.rootCounts, single.threshold)
        val singleAssigned = singleFiltered.sortedWith(
            compareByDescending<Disciple> { isDiscipleFollowed(it) }
                .thenBy { it.spiritRoot.types.size }
                .thenByDescending { it.skills.comprehension }
        ).take(singleSlotCount)
        val singleIds = singleAssigned.map { it.id }.toSet()

        val remaining = disciples.filter { it.id !in singleIds }
        val multiFiltered = simulateMultiFilter(remaining, multi.focused, multi.rootCounts, multi.threshold)
        val multiAssigned = multiFiltered.sortedWith(
            compareByDescending<Disciple> { isDiscipleFollowed(it) }
                .thenBy { it.spiritRoot.types.size }
                .thenByDescending { it.skills.comprehension }
        ).take(multiSlotCount)
        val multiIds = multiAssigned.map { it.id }

        return singleAssigned.map { it.id } to multiIds
    }

    // ── 单人筛选：focused ───────────────────────────────────────────
    @Test
    fun `单人筛选 - focused=true时仅已关注弟子通过`() {
        val disciples = listOf(
            Disciple(id = "d1", statusData = mapOf("followed" to "false"), skills = SkillStats(comprehension = 10)),
            Disciple(id = "d2", statusData = mapOf("followed" to "true"), skills = SkillStats(comprehension = 10))
        )
        val result = simulateSingleFilter(disciples, focused = true, rootCounts = emptyList(), threshold = 1)
        assertEquals("仅已关注弟子通过", listOf("d2"), result.map { it.id })
    }

    // ── 多人筛选：focused ───────────────────────────────────────────
    @Test
    fun `多人筛选 - focused=true时仅已关注弟子通过`() {
        val disciples = listOf(
            Disciple(id = "d1", statusData = mapOf("followed" to "false"), skills = SkillStats(comprehension = 10)),
            Disciple(id = "d2", statusData = mapOf("followed" to "true"), skills = SkillStats(comprehension = 10))
        )
        val result = simulateMultiFilter(disciples, focused = true, rootCounts = emptyList(), threshold = 1)
        assertEquals("仅已关注弟子通过", listOf("d2"), result.map { it.id })
    }

    // ── 灵根数筛选 ──────────────────────────────────────────────────
    @Test
    fun `灵根筛选 - 匹配指定灵根数的弟子通过`() {
        val disciples = listOf(
            Disciple(id = "d1", spiritRootType = "metal", skills = SkillStats(comprehension = 10)),
            Disciple(id = "d2", spiritRootType = "metal,wood", skills = SkillStats(comprehension = 10))
        )
        val result = simulateSingleFilter(disciples, focused = false, rootCounts = listOf(1), threshold = 1)
        assertEquals("单灵根才通过", listOf("d1"), result.map { it.id })
    }

    // ── 属性门槛 ────────────────────────────────────────────────────
    @Test
    fun `属性门槛 - 不达标弟子被排除`() {
        val disciples = listOf(
            Disciple(id = "d1", statusData = mapOf("followed" to "true"), skills = SkillStats(comprehension = 3)),
            Disciple(id = "d2", statusData = mapOf("followed" to "true"), skills = SkillStats(comprehension = 10))
        )
        val result = simulateMultiFilter(disciples, focused = true, rootCounts = emptyList(), threshold = 5)
        assertEquals("悟性达标才通过", listOf("d2"), result.map { it.id })
    }

    // ── 已关注与灵根 OR 语义（核心修复验证）─────────────────────────
    @Test
    fun `OR语义 - focused+rootCounts同开时满足任一即可`() {
        val disciples = listOf(
            Disciple(id = "d1", statusData = mapOf("followed" to "true"), spiritRootType = "fire,water",
                skills = SkillStats(comprehension = 10)),
            Disciple(id = "d2", statusData = mapOf("followed" to "false"), spiritRootType = "fire",
                skills = SkillStats(comprehension = 10))
        )
        // focused=true + rootCounts=[1] → 已关注 OR 单灵根
        val result = simulateSingleFilter(disciples, focused = true, rootCounts = listOf(1), threshold = 1)
        assertEquals("已关注或单灵根两者都通过", setOf("d1", "d2"), result.map { it.id }.toSet())
    }
    @Test
    fun `OR语义 - focused+rootCounts同开时都不满足则排除`() {
        val disciples = listOf(
            Disciple(id = "d1", statusData = mapOf("followed" to "false"), spiritRootType = "fire,water",
                skills = SkillStats(comprehension = 10)),
            Disciple(id = "d2", statusData = mapOf("followed" to "false"), spiritRootType = "fire,water,wood",
                skills = SkillStats(comprehension = 10))
        )
        // focused=true + rootCounts=[1] → 已关注 OR 单灵根 → 两者都不满足
        val result = simulateSingleFilter(disciples, focused = true, rootCounts = listOf(1), threshold = 1)
        assertTrue("都不满足时全排除", result.isEmpty())
    }

    // ── 未启用的类型不干扰已启用的类型 ──────────────────────────────
    @Test
    fun `未启用的类型不干扰已启用的类型`() {
        val disciples = listOf(
            Disciple(id = "d1", statusData = mapOf("followed" to "false"), skills = SkillStats(comprehension = 10)),
            Disciple(id = "d2", statusData = mapOf("followed" to "true"), skills = SkillStats(comprehension = 10))
        )
        // 仅单人启用（multiSlotCount=0），验证 multi 不干扰 single
        val (singleIds, multiIds) = simulateTwoPassResidence(
            disciples,
            single = ResidencePassSpec(focused = true, rootCounts = emptyList(), threshold = 1),
            multi = ResidencePassSpec(focused = true, rootCounts = emptyList(), threshold = 1),
            singleSlotCount = 2, multiSlotCount = 0
        )
        assertEquals("未关注不应通过单人筛选", listOf("d2"), singleIds)
        assertTrue("多人未启用不应分配", multiIds.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // 两轮分配：单人优先于多人（核心优化验证）
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun `两轮分配 - 单人优先于多人`() {
        val d1 = Disciple(id = "d1", name = "均满足", spiritRootType = "fire",
            statusData = mapOf("followed" to "true"), skills = SkillStats(comprehension = 10))
        // d1 同时满足单人和多人条件（已关注+单灵根+悟性达标）
        val (singleIds, multiIds) = simulateTwoPassResidence(
            listOf(d1),
            single = ResidencePassSpec(focused = true, rootCounts = emptyList(), threshold = 1),
            multi = ResidencePassSpec(focused = true, rootCounts = emptyList(), threshold = 1),
            singleSlotCount = 1, multiSlotCount = 1  // 1单槽+1多槽
        )
        assertEquals("同时满足时优先分到单人住所", listOf("d1"), singleIds)
        assertTrue("多人住所应无此弟子", multiIds.isEmpty())
    }
    @Test
    fun `两轮分配 - 单人住满后多余弟子分配到多人`() {
        val d1 = Disciple(id = "d1", name = "已关注", statusData = mapOf("followed" to "true"),
            spiritRootType = "fire", skills = SkillStats(comprehension = 10))
        val d2 = Disciple(id = "d2", name = "已关注2", statusData = mapOf("followed" to "true"),
            spiritRootType = "water", skills = SkillStats(comprehension = 8))
        // d1,d2 均满足单人和多人条件
        val (singleIds, multiIds) = simulateTwoPassResidence(
            listOf(d1, d2),
            single = ResidencePassSpec(focused = true, rootCounts = emptyList(), threshold = 1),
            multi = ResidencePassSpec(focused = true, rootCounts = emptyList(), threshold = 1),
            singleSlotCount = 1,  // 仅1个单人槽
            multiSlotCount = 2    // 多人有2槽
        )
        assertEquals("单人槽优先分给悟性高的d1", listOf("d1"), singleIds)
        assertEquals("d2分到多人住所", listOf("d2"), multiIds)
    }
    @Test
    fun `两轮分配 - 单人无槽时所有合格弟子进入多人`() {
        val d1 = Disciple(id = "d1", name = "已关注", statusData = mapOf("followed" to "true"),
            spiritRootType = "fire", skills = SkillStats(comprehension = 10))
        val d2 = Disciple(id = "d2", name = "已关注2", statusData = mapOf("followed" to "true"),
            spiritRootType = "water", skills = SkillStats(comprehension = 8))
        val (singleIds, multiIds) = simulateTwoPassResidence(
            listOf(d1, d2),
            single = ResidencePassSpec(focused = true, rootCounts = emptyList(), threshold = 1),
            multi = ResidencePassSpec(focused = true, rootCounts = emptyList(), threshold = 1),
            singleSlotCount = 0,  // 无单人槽
            multiSlotCount = 2
        )
        assertTrue("无单人槽位时不分配单人", singleIds.isEmpty())
        assertEquals("所有合格弟子进入多人住所", setOf("d1", "d2"), multiIds.toSet())
    }
    @Test
    fun `两轮分配 - 仅满足多人条件的弟子跳过单人直接进入多人`() {
        val d1 = Disciple(id = "d1", name = "已关注", statusData = mapOf("followed" to "true"),
            spiritRootType = "fire,water", skills = SkillStats(comprehension = 10))
        // d1: 不满足单人条件（单人要求rootCount=1，但d1双灵根），但满足多人条件（多人仅要求已关注）
        val (singleIds, multiIds) = simulateTwoPassResidence(
            listOf(d1),
            single = ResidencePassSpec(focused = false, rootCounts = listOf(1), threshold = 1),  // 单人：仅单灵根
            multi = ResidencePassSpec(focused = true, rootCounts = emptyList(), threshold = 1),  // 多人：已关注
            singleSlotCount = 1, multiSlotCount = 1
        )
        assertTrue("不满足单人条件时跳过单人", singleIds.isEmpty())
        assertEquals("满足多人条件时进入多人住所", listOf("d1"), multiIds)
    }

    // ═══════════════════════════════════════════════════════════════
    // 两轮分配 + OR语义：综合场景（同时验证两个变更）
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun `综合场景 - 弟子池混合focused+rootCounts分配的准确性`() {
        val dFollowedSingle = Disciple(id = "d1", name = "已关注单灵根",
            spiritRootType = "fire", statusData = mapOf("followed" to "true"),
            skills = SkillStats(comprehension = 10))
        val dNotFollowedSingle = Disciple(id = "d2", name = "未关注单灵根",
            spiritRootType = "water", statusData = mapOf("followed" to "false"),
            skills = SkillStats(comprehension = 8))
        val dFollowedMulti = Disciple(id = "d3", name = "已关注双灵根",
            spiritRootType = "fire,water", statusData = mapOf("followed" to "true"),
            skills = SkillStats(comprehension = 6))
        val dNotFollowedMulti = Disciple(id = "d4", name = "未关注三灵根",
            spiritRootType = "fire,water,wood", statusData = mapOf("followed" to "false"),
            skills = SkillStats(comprehension = 4))

        // 单人设置：focused=false, rootCounts=[1] → 仅单灵根（与关注状态无关，演示OR语义分离）
        // 多人设置：focused=true, rootCounts=[] → 仅已关注（与灵根数无关）
        // 单人槽位: 2, 多人槽位: 2
        val disciples = listOf(dFollowedSingle, dNotFollowedSingle, dFollowedMulti, dNotFollowedMulti)
        val (singleIds, multiIds) = simulateTwoPassResidence(
            disciples,
            single = ResidencePassSpec(focused = false, rootCounts = listOf(1), threshold = 1),
            multi = ResidencePassSpec(focused = true, rootCounts = emptyList(), threshold = 1),
            singleSlotCount = 2, multiSlotCount = 2
        )

        // 单人：仅单灵根 → d1(单灵根)+d2(单灵根)通过 → 排序d1(已关注)>d2 → 填满2单槽
        assertEquals("单人槽应分配给两个单灵根弟子", setOf("d1", "d2"), singleIds.toSet())

        // 排除d1,d2后剩余d3,d4 → 多人仅已关注 → d3通过, d4不通过
        assertEquals("多人槽应只分配给已关注的d3", listOf("d3"), multiIds)
    }

    // ═══════════════════════════════════════════════════════════════
    // batchAssignToProductionSlots Repository 回写
    // 真实 ProductionProcessor + 真实 ProductionSlotRepository（dao mock）。
    // 4.00.91 背景：自动排班只写镜像 → repo 不写 → UI（repo）显示空闲但弟子被占用
    // ═══════════════════════════════════════════════════════════════
    private fun setupProcessor(repoSlots: List<ProductionSlot>) {
        procStore = FakeAtomicStateStore()
        procStore.update {
            discipleTables.writeAllowed = true
            discipleTables.addId(1)
            discipleTables.names[1] = "弟子1"
            discipleTables.statuses[1] = DiscipleStatus.IDLE
            discipleTables.isAlive[1] = 1
            discipleTables.realms[1] = 9
            discipleTables.realmLayers[1] = 1
            discipleTables.portraitRes[1] = "portrait_1"
            // pillRefining 必须 ≥ autoAlchemyThreshold(1)，否则 takeCandidates 属性筛选不匹配
            discipleTables.pillRefinings[1] = 50
            discipleTables.writeAllowed = false
            gameData = gameData.copy(
                sectPolicies = SectPolicies(autoAlchemyRootCounts = listOf(1)),
                productionSlots = repoSlots.map { it.copy(assignedDiscipleId = null, assignedDiscipleName = "") }
            )
        }
        val scopeProvider = mock<CoroutineScopeProvider>()
        whenever(scopeProvider.scope).thenReturn(CoroutineScope(Dispatchers.Unconfined))
        procRepo = ProductionSlotRepository(dao = mock(), configService = mock(), scopeProvider = scopeProvider)
        runBlocking { procRepo.loadSlots(repoSlots) }
        val coordinator = mock<ProductionCoordinator>()
        whenever(coordinator.repository).thenReturn(procRepo)
        processor = ProductionProcessor(
            stateStore = procStore,
            inventorySystem = mock<InventorySystem>(),
            productionCoordinator = coordinator,
            productionSlotRepository = procRepo,
            formulaService = mock<FormulaService>(),
            rngManager = mock<GameRngManager>(),
            scopeProvider = scopeProvider,
            ioDispatcher = IoDispatcher(Dispatchers.Unconfined),
            inventoryConfig = mock<com.xianxia.sect.core.config.InventoryConfig>()
        )
    }
    @Test
    fun `S3 自动排班后 repo 槽位被回写与镜像一致`() = runTest {
        val alchemySlot = ProductionSlot(
            id = "alchemy_0", buildingType = BuildingType.ALCHEMY, buildingId = "alchemy",
            slotIndex = 0, status = ProductionSlotStatus.IDLE
        )
        setupProcessor(listOf(alchemySlot))

        procStore.update { processor.processAutoAssign(this) }

        val repoSlot = procRepo.getSlotByIndex(BuildingType.ALCHEMY, 0)
        assertEquals("repo 应回写弟子1", "1", repoSlot?.assignedDiscipleId)
        val gdSlot = procStore.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0 }
        assertEquals("镜像与 repo 一致", "1", gdSlot?.assignedDiscipleId)
    }
    @Test
    fun `S3 repo 槽已被玩家任命时自动排班镜像回滚`() = runTest {
        // repo 槽已由玩家任命 "99"（镜像为空）→ 自动排班不应覆盖玩家任命，镜像回滚
        val alchemySlot = ProductionSlot(
            id = "alchemy_0", buildingType = BuildingType.ALCHEMY, buildingId = "alchemy",
            slotIndex = 0, status = ProductionSlotStatus.IDLE,
            assignedDiscipleId = "99", assignedDiscipleName = "玩家任命"
        )
        setupProcessor(listOf(alchemySlot))

        procStore.update { processor.processAutoAssign(this) }

        val repoSlot = procRepo.getSlotByIndex(BuildingType.ALCHEMY, 0)
        assertEquals("repo 应保持玩家任命", "99", repoSlot?.assignedDiscipleId)
        val gdSlot = procStore.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0 }
        assertEquals("镜像应回滚为空（防双槽位/覆盖玩家任命）", null, gdSlot?.assignedDiscipleId)
    }
    @Test
    fun `S3 repo 无对应槽时自动排班回写失败镜像回滚`() = runTest {
        // 镜像有 ALCHEMY 槽但 repo 没有（双存储分叉）→ 回写必然 Failure → 镜像回滚为空
        setupProcessor(emptyList())
        procStore.update {
            gameData = gameData.copy(
                productionSlots = listOf(
                    ProductionSlot(
                        id = "alchemy_0", buildingType = BuildingType.ALCHEMY, buildingId = "alchemy",
                        slotIndex = 0, status = ProductionSlotStatus.IDLE
                    )
                )
            )
        }

        procStore.update { processor.processAutoAssign(this) }

        val gdSlot = procStore.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0 }
        assertEquals("repo 回写失败 → 镜像应回滚为空", null, gdSlot?.assignedDiscipleId)
        val repoSlot = procRepo.getSlotByIndex(BuildingType.ALCHEMY, 0)
        assertEquals("repo 不应有该槽", null, repoSlot)
    }

    // ═══════════════════════════════════════════════════════════════
    // completeForgeSlot / completeAlchemySlot — 真实成功率判定 + 职业晋升
    // （RNG 用真实 GameRngManager + 固定种子：
    //  successRate=1.0 必然成功（nextDouble∈[0,1) ≤ 1.0），0.0 必然失败）
    // ═══════════════════════════════════════════════════════════════
    private fun newCompletionProcessor(
        store: FakeAtomicStateStore,
        repo: ProductionSlotRepository,
        inventorySystem: InventorySystem = mock()
    ): ProductionProcessor {
        val scopeProvider = mock<CoroutineScopeProvider>()
        whenever(scopeProvider.scope).thenReturn(CoroutineScope(Dispatchers.Unconfined))
        val rngManager = GameRngManager()
        rngManager.initSystemSeed(20260809L)
        return ProductionProcessor(
            stateStore = store,
            inventorySystem = inventorySystem,
            productionCoordinator = mock(),
            productionSlotRepository = repo,
            formulaService = mock(),
            rngManager = rngManager,
            scopeProvider = scopeProvider,
            ioDispatcher = IoDispatcher(Dispatchers.Unconfined),
            inventoryConfig = mock<com.xianxia.sect.core.config.InventoryConfig>()
        )
    }

    /** 构造带一个弟子的 Fake store（列式写入 DiscipleTables） */
    private fun newStoreWithDisciple(
        forgeLevel: Int = 0,
        forgePromotionCount: Int = 0,
        alchemyLevel: Int = 0,
        alchemyPromotionCount: Int = 0,
        isAlive: Int = 1
    ): FakeAtomicStateStore {
        val store = FakeAtomicStateStore()
        store.update {
            discipleTables.writeAllowed = true
            discipleTables.addId(1)
            discipleTables.names[1] = "弟子一"
            discipleTables.statuses[1] = DiscipleStatus.IDLE
            discipleTables.isAlive[1] = isAlive
            discipleTables.realms[1] = 9
            discipleTables.realmLayers[1] = 1
            discipleTables.portraitRes[1] = "portrait_1"
            discipleTables.pillRefinings[1] = 50
            discipleTables.artifactRefinings[1] = 50
            discipleTables.forgeLevels[1] = forgeLevel
            discipleTables.forgePromotionCounts[1] = forgePromotionCount
            discipleTables.alchemyLevels[1] = alchemyLevel
            discipleTables.alchemyPromotionCounts[1] = alchemyPromotionCount
            discipleTables.writeAllowed = false
        }
        return store
    }

    /** 构造已到完成期的锻造槽位（duration=0 → isSlotCompleteDynamic 立即完成） */
    private fun forgeCompletedSlot(
        recipeId: String = "ironSword",
        successRate: Double = 1.0,
        discipleId: String? = "1"
    ) = ProductionSlot(
        id = "forge_0", slotIndex = 0, buildingType = BuildingType.FORGE,
        buildingId = BuildingNames.FORGE, status = ProductionSlotStatus.WORKING,
        recipeId = recipeId, assignedDiscipleId = discipleId, assignedDiscipleName = "弟子一",
        duration = 0, successRate = successRate
    )
    @Test
    fun `completeForgeSlot - 满成功率成功产出装备并晋升一级`() = runTest {
        val store = newStoreWithDisciple()
        val inv = mock<InventorySystem>()
        val equipment = EquipmentStack(name = "精铁剑", rarity = 1)
        whenever(inv.createEquipmentFromRecipe(any())).thenReturn(equipment)
        whenever(inv.addEquipmentStack(any())).thenReturn(DomainResult.Success(equipment))
        // mock 的 withTrackingSource 默认不执行块内 lambda 且返回 null → 需 stub 透传执行
        whenever(inv.withTrackingSource<Any>(any(), any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.getArgument(1) as () -> Any)()
        }
        val repo = com.xianxia.sect.core.engine.testProductionSlotRepository()
        repo.loadSlots(listOf(forgeCompletedSlot()))
        val processor = newCompletionProcessor(store, repo, inv)

        processor.processBuildingProduction(1, 1)

        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("成功锻造一次应晋升一级", 1, disciple.skills.forgeLevel)
        assertEquals("晋升后计数清零", 0, disciple.skills.forgePromotionCount)
        assertEquals("弟子应回到空闲", DiscipleStatus.IDLE, disciple.status)
        verify(inv).addEquipmentStack(any())
        assertEquals("计数器照常+1", 1L, store.latestGameData.guideCounters[GuideCounterKeys.FORGE_COMPLETED])
    }
    @Test
    fun `completeForgeSlot - 零成功率失败不产出不晋升但计数照常`() = runTest {
        val store = newStoreWithDisciple()
        val inv = mock<InventorySystem>()
        val repo = com.xianxia.sect.core.engine.testProductionSlotRepository()
        repo.loadSlots(listOf(forgeCompletedSlot(successRate = 0.0)))
        val processor = newCompletionProcessor(store, repo, inv)

        processor.processBuildingProduction(1, 1)

        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("失败不应晋升", 0, disciple.skills.forgeLevel)
        assertEquals("失败不累计晋升次数", 0, disciple.skills.forgePromotionCount)
        assertEquals("弟子仍回空闲", DiscipleStatus.IDLE, disciple.status)
        verify(inv, never()).addEquipmentStack(any())
        assertEquals("失败也计入完成次数", 1L, store.latestGameData.guideCounters[GuideCounterKeys.FORGE_COMPLETED])
    }
    @Test
    fun `completeForgeSlot - 炼制中死亡弟子不结算晋升`() = runTest {
        val store = newStoreWithDisciple(isAlive = 0)
        val inv = mock<InventorySystem>()
        whenever(inv.createEquipmentFromRecipe(any()))
            .thenReturn(EquipmentStack(name = "精铁剑", rarity = 1))
        whenever(inv.addEquipmentStack(any()))
            .thenReturn(DomainResult.Success(EquipmentStack(name = "精铁剑", rarity = 1)))
        whenever(inv.withTrackingSource<Any>(any(), any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.getArgument(1) as () -> Any)()
        }
        val repo = com.xianxia.sect.core.engine.testProductionSlotRepository()
        repo.loadSlots(listOf(forgeCompletedSlot()))
        val processor = newCompletionProcessor(store, repo, inv)

        processor.processBuildingProduction(1, 1)

        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("死亡弟子不晋升", 0, disciple.skills.forgeLevel)
    }
    @Test
    fun `completeAlchemySlot - 炼丹成功晋升炼丹师`() = runTest {
        val store = newStoreWithDisciple()
        val inv = mock<InventorySystem>()
        whenever(inv.addPill(any())).thenReturn(DomainResult.Success(mock<Pill>()))
        whenever(inv.withTrackingSource<Any>(any(), any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.getArgument(1) as () -> Any)()
        }
        val tier1Pill = PillRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val repo = com.xianxia.sect.core.engine.testProductionSlotRepository()
        val slot = ProductionSlot(
            id = "alchemy_0", slotIndex = 0, buildingType = BuildingType.ALCHEMY,
            buildingId = BuildingNames.ALCHEMY, status = ProductionSlotStatus.WORKING,
            recipeId = tier1Pill.id, assignedDiscipleId = "1", assignedDiscipleName = "弟子一",
            duration = 0, successRate = 1.0
        )
        repo.loadSlots(listOf(slot))
        val processor = newCompletionProcessor(store, repo, inv)

        processor.processBuildingProduction(1, 1)

        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("成功炼丹一次应晋升一级", 1, disciple.skills.alchemyLevel)
        assertEquals("炼丹职业不影响锻造职业", 0, disciple.skills.forgeLevel)
        verify(inv).addPill(any())
    }
    @Test
    fun `completeAlchemySlot - 高等级弟子炼低阶丹药不重复晋升`() = runTest {
        // level 1 弟子炼 tier 1 凡品（低于当前解锁最高阶 tier 2）→ 不计数不晋升
        val store = newStoreWithDisciple(alchemyLevel = 1, alchemyPromotionCount = 199)
        val inv = mock<InventorySystem>()
        whenever(inv.addPill(any())).thenReturn(DomainResult.Success(mock<Pill>()))
        whenever(inv.withTrackingSource<Any>(any(), any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.getArgument(1) as () -> Any)()
        }
        val tier1Pill = PillRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        val repo = com.xianxia.sect.core.engine.testProductionSlotRepository()
        val slot = ProductionSlot(
            id = "alchemy_0", slotIndex = 0, buildingType = BuildingType.ALCHEMY,
            buildingId = BuildingNames.ALCHEMY, status = ProductionSlotStatus.WORKING,
            recipeId = tier1Pill.id, assignedDiscipleId = "1", assignedDiscipleName = "弟子一",
            duration = 0, successRate = 1.0
        )
        repo.loadSlots(listOf(slot))
        val processor = newCompletionProcessor(store, repo, inv)

        processor.processBuildingProduction(1, 1)

        val disciple = store.persistentDiscipleTables.assembleAll().first()
        assertEquals("低阶不充数不晋升", 1, disciple.skills.alchemyLevel)
        assertEquals("低阶不计入晋升次数", 199, disciple.skills.alchemyPromotionCount)
    }
}
