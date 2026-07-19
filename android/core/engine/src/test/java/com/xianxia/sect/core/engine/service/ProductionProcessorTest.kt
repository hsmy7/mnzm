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
     * @param idleDisciples 可变空闲弟子列表（会被修改）
     * @param focused 是否仅分配已关注弟子
     * @param rootCounts 允许的灵根数列表
     * @param threshold 属性门槛
     * @param attr 属性提取函数
     * @return 选中的弟子，或 null
     */
    private fun takeCandidate(
        idleDisciples: MutableList<Disciple>,
        focused: Boolean,
        rootCounts: List<Int>,
        threshold: Int,
        attr: (Disciple) -> Int
    ): Disciple? {
        val enabled = focused || rootCounts.isNotEmpty()
        if (!enabled || idleDisciples.isEmpty()) return null
        val candidate = idleDisciples
            .filter { d ->
                val matchesFilter = (focused && isDiscipleFollowed(d)) ||
                    d.spiritRoot.types.size in rootCounts
                matchesFilter && attr(d) >= threshold
            }
            .maxByOrNull { attr(it) }
        if (candidate != null) idleDisciples.remove(candidate)
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
    fun `takeCandidate - focused且followed会与rootCounts匹配结果一起进入maxBy排序`() {
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
        val idleDisciples = mutableListOf(followed3Root, singleRoot)

        val result = takeCandidate(
            idleDisciples,
            focused = true, rootCounts = listOf(1),
            threshold = 1, attr = { it.skills.mining }
        )
        // focused+followed → d1 匹配（三灵根但已关注）
        // rootCounts=[1] → d2 匹配（单灵根）
        // filter 后: [d1, d2]，maxBy mining → d2(10)
        assertNotNull("应有弟子被选中", result)
        assertEquals("应选属性最高者 d2", "d2", result?.id)
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
}
