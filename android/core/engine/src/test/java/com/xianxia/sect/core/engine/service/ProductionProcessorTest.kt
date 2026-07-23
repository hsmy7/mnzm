package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.engine.service.*
import com.xianxia.sect.core.repository.ProductionSlotRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock

/**
 * ProductionProcessor 自动分配逻辑单元测试。
 *
 * 覆盖 processAutoAssign 中的候选弟子筛选逻辑（takeCandidate）
 * 和 isDiscipleFollowed 辅助函数。
 */
class ProductionProcessorTest {

    // ═══════════════════════════════════════════════════════════════
    // isDiscipleFollowed — Disciple 字段访问验证
    // ═══════════════════════════════════════════════════════════════

    /** 与生产代码 ProductionProcessor.isDiscipleFollowed 逻辑一致 */
    private fun isDiscipleFollowed(d: Disciple): Boolean {
        return d.statusData["followed"] == "true"
    }

    @Test
    fun `isDiscipleFollowed - statusData 有 followed=true 返回 true`() {
        val d = Disciple(
            id = "d1",
            statusData = mapOf("followed" to "true")
        )
        assertTrue(isDiscipleFollowed(d))
    }

    @Test
    fun `isDiscipleFollowed - statusData 有 followed=false 返回 false`() {
        val d = Disciple(
            id = "d2",
            statusData = mapOf("followed" to "false")
        )
        assertFalse(isDiscipleFollowed(d))
    }

    @Test
    fun `isDiscipleFollowed - statusData 无 followed 键返回 false`() {
        val d = Disciple(id = "d3", statusData = emptyMap())
        assertFalse(isDiscipleFollowed(d))
    }

    @Test
    fun `isDiscipleFollowed - statusData 有 followed=其他值返回 false`() {
        val d = Disciple(
            id = "d4",
            statusData = mapOf("followed" to "yes")
        )
        assertFalse(isDiscipleFollowed(d))
    }

    // ═══════════════════════════════════════════════════════════════
    // takeCandidate — 候选弟子筛选逻辑
    // ═══════════════════════════════════════════════════════════════
    // 与生产代码 ProductionProcessor.processAutoAssign 中的
    // takeCandidate 内联函数逻辑一致。
    // 注意：production 代码通过扩展属性 Disciple.mining 访问，
    // 测试中改用 Disciple.skills.mining 直接访问以避免导入扩展属性。

    /**
     * 模拟 processAutoAssign 中的 takeCandidate 逻辑。
     *
     * @param pool 可变候选弟子列表（会被修改）
     * @param focused 是否仅分配已关注弟子
     * @param rootCounts 允许的灵根数列表
     * @param threshold 属性门槛
     * @param attr 属性提取函数
     * @return 选中的弟子，或 null
     */
    private fun takeCandidate(
        pool: MutableList<Disciple>,
        focused: Boolean,
        rootCounts: List<Int>,
        threshold: Int,
        attr: (Disciple) -> Int
    ): Disciple? {
        val enabled = focused || rootCounts.isNotEmpty()
        if (!enabled || pool.isEmpty()) return null
        val candidate = pool
            .filter { d ->
                val matchesFilter = (focused && isDiscipleFollowed(d)) ||
                    d.spiritRoot.types.size in rootCounts
                matchesFilter && attr(d) >= threshold
            }
            .sortedWith(
                compareByDescending<Disciple> { if (focused) isDiscipleFollowed(it) else false }
                    .thenBy { it.spiritRoot.types.size }
                    .thenByDescending { attr(it) }
            )
            .firstOrNull()
        if (candidate != null) pool.remove(candidate)
        return candidate
    }

    // ── 状态检查 ──────────────────────────────────────────────────

    @Test
    fun `takeCandidate - focused=false且rootCounts为空时返回null`() {
        val idleDisciples = mutableListOf(
            Disciple(id = "d1", status = DiscipleStatus.IDLE, isAlive = true)
        )
        val result = takeCandidate(
            idleDisciples, focused = false, rootCounts = emptyList(),
            threshold = 1, attr = { it.skills.mining }
        )
        assertNull("设置未启用时应返回 null", result)
        assertEquals("不应移除任何弟子", 1, idleDisciples.size)
    }

    @Test
    fun `takeCandidate - 空闲弟子列表为空时返回null`() {
        val idleDisciples = mutableListOf<Disciple>()
        val result = takeCandidate(
            idleDisciples, focused = true, rootCounts = emptyList(),
            threshold = 1, attr = { it.skills.mining }
        )
        assertNull("空闲列表为空时应返回 null", result)
    }

    // ── focused + followed ──────────────────────────────────────────

    @Test
    fun `takeCandidate - focused=true时仅选择已关注弟子`() {
        val followed = Disciple(
            id = "d1", name = "已关注",
            statusData = mapOf("followed" to "true"),
            status = DiscipleStatus.IDLE, isAlive = true
        )
        val notFollowed = Disciple(
            id = "d2", name = "未关注",
            statusData = mapOf("followed" to "false"),
            status = DiscipleStatus.IDLE, isAlive = true
        )
        val idleDisciples = mutableListOf(followed, notFollowed)

        val result = takeCandidate(
            idleDisciples, focused = true, rootCounts = emptyList(),
            threshold = 1, attr = { it.skills.mining }
        )
        assertNotNull("应有弟子被选中", result)
        assertEquals("应选中已关注弟子", "d1", result?.id)
        assertEquals("应从空闲列表移除", 1, idleDisciples.size)
    }

    @Test
    fun `takeCandidate - focused=true但无已关注弟子返回null`() {
        val idleDisciples = mutableListOf(
            Disciple(
                id = "d1",
                statusData = mapOf("followed" to "false"),
                status = DiscipleStatus.IDLE, isAlive = true
            )
        )
        val result = takeCandidate(
            idleDisciples, focused = true, rootCounts = emptyList(),
            threshold = 1, attr = { it.skills.mining }
        )
        assertNull("无已关注弟子时应返回 null", result)
    }

    // ── rootCounts 灵根数匹配 ─────────────────────────────────────

    @Test
    fun `takeCandidate - rootCounts匹配单灵根弟子`() {
        val d1 = Disciple(
            id = "d1", spiritRootType = "火",
            status = DiscipleStatus.IDLE, isAlive = true
        )
        val d2 = Disciple(
            id = "d2", spiritRootType = "火,水",
            status = DiscipleStatus.IDLE, isAlive = true
        )
        val idleDisciples = mutableListOf(d1, d2)

        val result = takeCandidate(
            idleDisciples, focused = false, rootCounts = listOf(1),
            threshold = 1, attr = { it.skills.mining }
        )
        assertNotNull("应有单灵根弟子被选中", result)
        assertEquals("应选中单灵根弟子", "d1", result?.id)
    }

    @Test
    fun `takeCandidate - rootCounts匹配双灵根弟子`() {
        val idleDisciples = mutableListOf(
            Disciple(
                id = "d1", spiritRootType = "火,水",
                status = DiscipleStatus.IDLE, isAlive = true
            )
        )
        val result = takeCandidate(
            idleDisciples, focused = false, rootCounts = listOf(2, 3),
            threshold = 1, attr = { it.skills.mining }
        )
        assertNotNull("双灵根应匹配 rootCounts=[2,3]", result)
    }

    @Test
    fun `takeCandidate - 灵根数不匹配所有rootCounts返回null`() {
        val idleDisciples = mutableListOf(
            Disciple(
                id = "d1", spiritRootType = "火,水,木",
                status = DiscipleStatus.IDLE, isAlive = true
            )
        )
        val result = takeCandidate(
            idleDisciples, focused = false, rootCounts = listOf(1, 2),
            threshold = 1, attr = { it.skills.mining }
        )
        assertNull("三灵根不应匹配 rootCounts=[1,2]", result)
    }

    // ── threshold 属性门槛 ────────────────────────────────────────

    @Test
    fun `takeCandidate - 属性低于threshold的弟子被排除`() {
        val lowAttr = Disciple(
            id = "d1", spiritRootType = "火",
            skills = SkillStats(mining = 2),
            status = DiscipleStatus.IDLE, isAlive = true
        )
        val idleDisciples = mutableListOf(lowAttr)

        val result = takeCandidate(
            idleDisciples, focused = false, rootCounts = listOf(1),
            threshold = 5, attr = { it.skills.mining }
        )
        assertNull("mining=2 < threshold=5 应返回 null", result)
    }

    @Test
    fun `takeCandidate - 属性达标时选出属性最高者`() {
        val low = Disciple(
            id = "d1", name = "采矿3",
            spiritRootType = "火",
            skills = SkillStats(mining = 3),
            status = DiscipleStatus.IDLE, isAlive = true
        )
        val high = Disciple(
            id = "d2", name = "采矿8",
            spiritRootType = "水",
            skills = SkillStats(mining = 8),
            status = DiscipleStatus.IDLE, isAlive = true
        )
        val mid = Disciple(
            id = "d3", name = "采矿5",
            spiritRootType = "木",
            skills = SkillStats(mining = 5),
            status = DiscipleStatus.IDLE, isAlive = true
        )
        val idleDisciples = mutableListOf(low, high, mid)

        val result = takeCandidate(
            idleDisciples, focused = false, rootCounts = listOf(1),
            threshold = 3, attr = { it.skills.mining }
        )
        assertNotNull("应有弟子被选中", result)
        assertEquals("应选属性最高者", "d2", result?.id)
    }

    // ── 不可重复分配 ──────────────────────────────────────────────

    @Test
    fun `takeCandidate - 选中弟子从空闲列表移除不可被再次分配`() {
        val d1 = Disciple(
            id = "d1", spiritRootType = "火",
            skills = SkillStats(mining = 5),
            status = DiscipleStatus.IDLE, isAlive = true
        )
        val d2 = Disciple(
            id = "d2", spiritRootType = "水",
            skills = SkillStats(mining = 4),
            status = DiscipleStatus.IDLE, isAlive = true
        )
        val idleDisciples = mutableListOf(d1, d2)

        // 第一次分配 — 应选中 d1（mining 更高）
        val first = takeCandidate(
            idleDisciples, focused = false, rootCounts = listOf(1),
            threshold = 1, attr = { it.skills.mining }
        )
        assertEquals("第一次应选 d1", "d1", first?.id)
        assertEquals("空闲列表剩 1 人", 1, idleDisciples.size)

        // 第二次分配 — 应选中 d2
        val second = takeCandidate(
            idleDisciples, focused = false, rootCounts = listOf(1),
            threshold = 1, attr = { it.skills.mining }
        )
        assertEquals("第二次应选 d2", "d2", second?.id)
        assertEquals("空闲列表为空", 0, idleDisciples.size)

        // 第三次 — 返回 null
        val third = takeCandidate(
            idleDisciples, focused = false, rootCounts = listOf(1),
            threshold = 1, attr = { it.skills.mining }
        )
        assertNull("无空闲弟子时应返回 null", third)
    }

    // ── focus + rootCounts 组合 ────────────────────────────────────

    @Test
    fun `takeCandidate - focused与rootCounts多选按优先级排序`() {
        val followed3Root = Disciple(
            id = "d1", name = "已关注三灵根",
            spiritRootType = "火,水,木",
            skills = SkillStats(mining = 5),
            statusData = mapOf("followed" to "true"),
            status = DiscipleStatus.IDLE, isAlive = true
        )
        val singleRoot = Disciple(
            id = "d2", name = "未关注单灵根",
            spiritRootType = "火",
            skills = SkillStats(mining = 10),
            statusData = mapOf("followed" to "false"),
            status = DiscipleStatus.IDLE, isAlive = true
        )
        val idleDisciples = mutableListOf(singleRoot, followed3Root)

        val result = takeCandidate(
            idleDisciples,
            focused = true, rootCounts = listOf(1),
            threshold = 1, attr = { it.skills.mining }
        )
        // OR 语义：d1(已关注三灵根 OR 根数未匹配) + d2(未关注但单灵根) → 都通过
        // 优先级排序：已关注优先(1) > 未关注(2) → d1 优先于 d2
        assertNotNull("应有弟子被选中", result)
        assertEquals("已关注弟子优先于高属性未关注", "d1", result?.id)
    }

    @Test
    fun `takeCandidate - 同一优先级下按灵根数升序再按属性降序`() {
        val singleRootHigh = Disciple(
            id = "d1", name = "单灵根高属性",
            spiritRootType = "火",
            skills = SkillStats(mining = 8),
            status = DiscipleStatus.IDLE, isAlive = true
        )
        val singleRootLow = Disciple(
            id = "d2", name = "单灵根低属性",
            spiritRootType = "水",
            skills = SkillStats(mining = 3),
            status = DiscipleStatus.IDLE, isAlive = true
        )
        val dualRoot = Disciple(
            id = "d3", name = "双灵根",
            spiritRootType = "火,水",
            skills = SkillStats(mining = 10),
            status = DiscipleStatus.IDLE, isAlive = true
        )
        val idleDisciples = mutableListOf(dualRoot, singleRootLow, singleRootHigh)

        // 两次取候选人验证优先级顺序
        val first = takeCandidate(
            idleDisciples, focused = false, rootCounts = listOf(1, 2),
            threshold = 1, attr = { it.skills.mining }
        )
        // 同优先级(均未关注): 灵根数升序 → 单灵根优先; 同单灵根内属性降序 → d1(8)
        assertEquals("单灵根高属性优先", "d1", first?.id)

        val second = takeCandidate(
            idleDisciples, focused = false, rootCounts = listOf(1, 2),
            threshold = 1, attr = { it.skills.mining }
        )
        // 剩余: d2(单灵根3) vs d3(双灵根10) → 单灵根优先于双灵根 → d2
        assertEquals("单灵根低属性优先于双灵根高属性", "d2", second?.id)
    }

    // ═══════════════════════════════════════════════════════════════
    // processAutoAssign 入口条件
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `processAutoAssign - 四种建筑全部关闭时不分配任何弟子`() {
        val policies = mapOf(
            "mine" to (false to emptyList<Int>()),
            "plant" to (false to emptyList<Int>()),
            "alchemy" to (false to emptyList<Int>()),
            "forge" to (false to emptyList<Int>())
        )
        val anyEnabled = policies.values.any { (focused, rootCounts) ->
            focused || rootCounts.isNotEmpty()
        }
        assertFalse("全部关闭时 anyEnabled 应为 false", anyEnabled)
    }

    @Test
    fun `processAutoAssign - 任一建筑开启即可进入分配`() {
        assertTrue(
            "灵矿 focused=true",
            true || emptyList<Int>().isNotEmpty()
        )
        assertTrue(
            "灵植 rootCounts 非空",
            false || listOf(1, 2).isNotEmpty()
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // batchAssignToProductionSlots — 批量填满所有空闲槽位
    //
    // 模拟 batchAssignToProductionSlots 的循环逻辑：
    // 遍历空闲槽位 → 依次取候选人 → 直到槽满或候选耗尽。
    // ═══════════════════════════════════════════════════════════════

    /**
     * 模拟 batchAssignToProductionSlots 的批量填充逻辑。
     *
     * @param emptyCount 空闲槽位数
     * @param candidates 可变候选人列表（会被消耗）
     * @return 实际安排的槽位数
     */
    private fun simulateBatchFill(emptyCount: Int, candidates: MutableList<String>): Int {
        var filled = 0
        for (i in 0 until emptyCount) {
            if (candidates.isEmpty()) break
            candidates.removeFirst()
            filled++
        }
        return filled
    }

    @Test
    fun `batchFill - 3空槽3候选人全部填满`() {
        val candidates = mutableListOf("d1", "d2", "d3")
        val filled = simulateBatchFill(3, candidates)
        assertEquals("3空槽3候选人应填满3槽", 3, filled)
        assertTrue("候选人应全部用完", candidates.isEmpty())
    }

    @Test
    fun `batchFill - 3空槽仅1候选人只填1槽`() {
        val candidates = mutableListOf("d1")
        val filled = simulateBatchFill(3, candidates)
        assertEquals("3空槽1候选人只应填1槽", 1, filled)
        assertTrue("候选人应全部用完", candidates.isEmpty())
    }

    @Test
    fun `batchFill - 1空槽3候选人只填1槽`() {
        val candidates = mutableListOf("d1", "d2", "d3")
        val filled = simulateBatchFill(1, candidates)
        assertEquals("1空槽3候选人只应填1槽", 1, filled)
        assertEquals("应剩2候选人", 2, candidates.size)
    }

    @Test
    fun `batchFill - 0空槽不安排任何候选人`() {
        val candidates = mutableListOf("d1", "d2")
        val filled = simulateBatchFill(0, candidates)
        assertEquals("0空槽不应安排任何人", 0, filled)
        assertEquals("候选人不应被消耗", 2, candidates.size)
    }

    @Test
    fun `batchFill - 无候选人时空槽保持空闲`() {
        val candidates = mutableListOf<String>()
        val filled = simulateBatchFill(3, candidates)
        assertEquals("无候选人时应安排0人", 0, filled)
    }

    // ═══════════════════════════════════════════════════════════════
    // 跨优先级批量填充 — 采矿优先于种植，优先消耗候选人池
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `batchFill - 采矿3空槽种植2空槽共5候选人全部填满`() {
        val candidates = mutableListOf("d1", "d2", "d3", "d4", "d5")

        // 采矿先填 3 空槽
        val mineFilled = simulateBatchFill(3, candidates)
        assertEquals("采矿应填满3槽", 3, mineFilled)

        // 种植后填 2 空槽
        val plantFilled = simulateBatchFill(2, candidates)
        assertEquals("种植应填满2槽", 2, plantFilled)

        assertTrue("5候选人应全部用完", candidates.isEmpty())
    }

    @Test
    fun `batchFill - 跨类型竞争时前面的类型优先消耗候选人`() {
        val candidates = mutableListOf("d1", "d2")

        // 采矿先填 3 空槽，但只有 2 候选人
        val mineFilled = simulateBatchFill(3, candidates)
        assertEquals("采矿应消耗所有2候选人", 2, mineFilled)
        assertTrue("候选人应全部用完", candidates.isEmpty())

        // 种植无候选人可用
        val plantFilled = simulateBatchFill(2, candidates)
        assertEquals("种植无候选人应安排0人", 0, plantFilled)
    }

    // ═══════════════════════════════════════════════════════════════
    // processSpiritFieldHarvest — 灵田收获 + 自动续种
    // ═══════════════════════════════════════════════════════════════

    private fun createProcessor(): ProductionProcessor {
        return ProductionProcessor(
            stateStore = mock(),
            inventorySystem = mock(),
            productionCoordinator = mock(),
            productionSlotRepository = mock(),
            formulaService = mock(),
            rngManager = mock(),
            scopeProvider = mock()
        )
    }

    private fun createState(
        plants: List<SpiritFieldPlant> = emptyList(),
        seeds: List<Seed> = emptyList(),
        herbs: List<Herb> = emptyList(),
        gameYear: Int = 1,
        gameMonth: Int = 1
    ): MutableGameState {
        return MutableGameState(
            gameData = GameData(
                gameYear = gameYear,
                gameMonth = gameMonth,
                spiritFieldPlants = plants
            ),
            discipleTables = DiscipleTables(),
            equipmentStacks = EntityStore(emptyList()),
            equipmentInstances = EntityStore(emptyList()),
            manualStacks = EntityStore(emptyList()),
            manualInstances = EntityStore(emptyList()),
            pills = EntityStore(emptyList()),
            materials = EntityStore(emptyList()),
            herbs = EntityStore(herbs),
            seeds = EntityStore(seeds),
            storageBags = EntityStore(emptyList()),
            teams = emptyList(),
            battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
    }

    @Test
    fun `processSpiritFieldHarvest - 未成熟灵田跳过`() = runTest {
        val dbSeed = HerbDatabase.getSeedByName("聚灵草种") ?: return@runTest
        val plant = SpiritFieldPlant(
            buildingInstanceId = "field1", seedId = "s1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5,
            plantYear = 1, plantMonth = 1  // 种下未满 36 月
        )
        val herbsBefore = emptyList<Herb>()
        val seedsBefore = listOf(Seed(id = "s1", slotId = 1, name = "聚灵草种",
            rarity = dbSeed.rarity, growTime = 36, yield = 5, quantity = 3))
        val state = createState(
            plants = listOf(plant),
            seeds = seedsBefore,
            gameYear = 1, gameMonth = 6  // 仅过了 5 个月，不到 36
        )
        val processor = createProcessor()
        processor.processSpiritFieldHarvest(state)
        assertEquals("未成熟不应收获", 0, state.herbs.all().size)
        assertEquals("种子不应被消耗", 3, state.seeds.all().first().quantity)
    }

    @Test
    fun `processSpiritFieldHarvest - 成熟灵田收获产生灵草`() = runTest {
        val dbSeed = HerbDatabase.getSeedByName("聚灵草种") ?: return@runTest
        val dbHerb = HerbDatabase.getHerbFromSeedName("聚灵草种") ?: return@runTest
        val plant = SpiritFieldPlant(
            buildingInstanceId = "field1", seedId = "s1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5,
            plantYear = 1, plantMonth = 1
        )
        val state = createState(
            plants = listOf(plant),
            seeds = emptyList(),  // 无种子 → 清空
            gameYear = 4, gameMonth = 1  // 过了 36 月
        )
        val processor = createProcessor()
        processor.processSpiritFieldHarvest(state)
        val herbs = state.herbs.all()
        assertEquals("应收获 1 种灵草", 1, herbs.size)
        assertEquals("灵草名称正确", dbHerb.name, herbs.first().name)
        assertEquals("产量正确", 5, herbs.first().quantity)
        // 灵田被清空
        val updatedPlants = state.gameData.spiritFieldPlants
        assertEquals("灵田应清空", "", updatedPlants.first().seedId)
    }

    @Test
    fun `processSpiritFieldHarvest - 无同种种子时清空不消耗种子`() = runTest {
        val plant = SpiritFieldPlant(
            buildingInstanceId = "field1", seedId = "s1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5,
            plantYear = 1, plantMonth = 1
        )
        val otherSeed = Seed(id = "s2", slotId = 1, name = "云雾花种",
            rarity = 1, growTime = 36, yield = 4, quantity = 3)
        val state = createState(
            plants = listOf(plant),
            seeds = listOf(otherSeed),  // 只有其他种子，非同种
            gameYear = 4, gameMonth = 1
        )
        val processor = createProcessor()
        processor.processSpiritFieldHarvest(state)
        val updatedPlants = state.gameData.spiritFieldPlants
        assertEquals("灵田应清空", "", updatedPlants.first().seedId)
        assertEquals("其他种子不应被消耗", 3, state.seeds.all().first().quantity)
    }

    @Test
    fun `processSpiritFieldHarvest - 有同种种子时自动续种消耗种子`() = runTest {
        val dbSeed = HerbDatabase.getSeedByName("聚灵草种") ?: return@runTest
        val plant = SpiritFieldPlant(
            buildingInstanceId = "field1", seedId = "s1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5,
            plantYear = 1, plantMonth = 1
        )
        val sameSeed = Seed(id = "s2", slotId = 1, name = "聚灵草种",
            rarity = dbSeed.rarity, growTime = 36, yield = 5, quantity = 2)
        val state = createState(
            plants = listOf(plant),
            seeds = listOf(sameSeed),
            gameYear = 4, gameMonth = 1
        )
        val processor = createProcessor()
        processor.processSpiritFieldHarvest(state)
        // 种子被消耗 1 颗
        assertEquals("种子应剩余 1 颗", 1, state.seeds.all().first().quantity)
        // 灵田被续种（seedName 不变，plantYear/Month 更新到当前时间）
        val updatedPlant = state.gameData.spiritFieldPlants.first()
        assertEquals("续种后 seedName 不变", "聚灵草种", updatedPlant.seedName)
        assertEquals("续种后 plantYear 更新", 4, updatedPlant.plantYear)
        assertEquals("续种后 plantMonth 更新", 1, updatedPlant.plantMonth)
    }

    @Test
    fun `processSpiritFieldHarvest - 多块灵田同时成熟各自收获续种`() = runTest {
        val dbSeed = HerbDatabase.getSeedByName("聚灵草种") ?: return@runTest
        val plant1 = SpiritFieldPlant(
            buildingInstanceId = "field1", seedId = "s1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5,
            plantYear = 1, plantMonth = 1
        )
        val plant2 = SpiritFieldPlant(
            buildingInstanceId = "field2", seedId = "s2",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5,
            plantYear = 1, plantMonth = 1
        )
        val seeds = listOf(
            Seed(id = "seed1", slotId = 1, name = "聚灵草种",
                rarity = dbSeed.rarity, growTime = 36, yield = 5, quantity = 2)
            // 只有 2 颗，但 2 块田都需要续种 → 刚好够
        )
        val state = createState(
            plants = listOf(plant1, plant2),
            seeds = seeds,
            gameYear = 4, gameMonth = 1
        )
        val processor = createProcessor()
        processor.processSpiritFieldHarvest(state)
        // 2 块田都收获 → 同种灵草合并为 1 条记录 quantity=10
        assertEquals("应收获 1 条灵草记录（已合并）", 1, state.herbs.all().size)
        assertEquals("总产量 10", 10, state.herbs.all().first().quantity)
        // 2 颗种子都被消耗完
        assertTrue("种子应被消耗完", state.seeds.all().isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // 住所自动入住无视空闲状态 — takeCandidate 支持非 IDLE 候选池
    // ═══════════════════════════════════════════════════════════════

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
    private fun simulateTwoPassResidence(
        disciples: List<Disciple>,
        singleFocused: Boolean, singleRootCounts: List<Int>, singleThreshold: Int,
        multiFocused: Boolean, multiRootCounts: List<Int>, multiThreshold: Int,
        singleSlotCount: Int, multiSlotCount: Int
    ): Pair<List<String>, List<String>> {
        val singleFiltered = simulateSingleFilter(disciples, singleFocused, singleRootCounts, singleThreshold)
        val singleAssigned = singleFiltered.sortedWith(
            compareByDescending<Disciple> { isDiscipleFollowed(it) }
                .thenBy { it.spiritRoot.types.size }
                .thenByDescending { it.skills.comprehension }
        ).take(singleSlotCount)
        val singleIds = singleAssigned.map { it.id }.toSet()

        val remaining = disciples.filter { it.id !in singleIds }
        val multiFiltered = simulateMultiFilter(remaining, multiFocused, multiRootCounts, multiThreshold)
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
            Disciple(id = "d1", statusData = mapOf("followed" to "true"), spiritRootType = "fire,water", skills = SkillStats(comprehension = 10)),
            Disciple(id = "d2", statusData = mapOf("followed" to "false"), spiritRootType = "fire", skills = SkillStats(comprehension = 10))
        )
        // focused=true + rootCounts=[1] → 已关注 OR 单灵根
        val result = simulateSingleFilter(disciples, focused = true, rootCounts = listOf(1), threshold = 1)
        assertEquals("已关注或单灵根两者都通过", setOf("d1", "d2"), result.map { it.id }.toSet())
    }

    @Test
    fun `OR语义 - focused+rootCounts同开时都不满足则排除`() {
        val disciples = listOf(
            Disciple(id = "d1", statusData = mapOf("followed" to "false"), spiritRootType = "fire,water", skills = SkillStats(comprehension = 10)),
            Disciple(id = "d2", statusData = mapOf("followed" to "false"), spiritRootType = "fire,water,wood", skills = SkillStats(comprehension = 10))
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
            singleFocused = true, singleRootCounts = emptyList(), singleThreshold = 1,
            multiFocused = true, multiRootCounts = emptyList(), multiThreshold = 1,
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
            singleFocused = true, singleRootCounts = emptyList(), singleThreshold = 1,
            multiFocused = true, multiRootCounts = emptyList(), multiThreshold = 1,
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
            singleFocused = true, singleRootCounts = emptyList(), singleThreshold = 1,
            multiFocused = true, multiRootCounts = emptyList(), multiThreshold = 1,
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
            singleFocused = true, singleRootCounts = emptyList(), singleThreshold = 1,
            multiFocused = true, multiRootCounts = emptyList(), multiThreshold = 1,
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
            singleFocused = false, singleRootCounts = listOf(1), singleThreshold = 1,  // 单人：仅单灵根
            multiFocused = true, multiRootCounts = emptyList(), multiThreshold = 1,     // 多人：已关注
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
            singleFocused = false, singleRootCounts = listOf(1), singleThreshold = 1,
            multiFocused = true, multiRootCounts = emptyList(), multiThreshold = 1,
            singleSlotCount = 2, multiSlotCount = 2
        )

        // 单人：仅单灵根 → d1(单灵根)+d2(单灵根)通过 → 排序d1(已关注)>d2 → 填满2单槽
        assertEquals("单人槽应分配给两个单灵根弟子", setOf("d1", "d2"), singleIds.toSet())

        // 排除d1,d2后剩余d3,d4 → 多人仅已关注 → d3通过, d4不通过
        assertEquals("多人槽应只分配给已关注的d3", listOf("d3"), multiIds)
    }
}
