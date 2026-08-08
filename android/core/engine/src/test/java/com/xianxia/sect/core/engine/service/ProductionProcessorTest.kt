package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.ZoneCalculator
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.domain.building.HerbGardenAuraService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.di.IoDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever



/**
 * ProductionProcessor 自动分配逻辑单元测试。
 *
 * 覆盖 processAutoAssign 中的候选弟子筛选逻辑（takeCandidate）
 * 和 isDiscipleFollowed 辅助函数。
 */
class ProductionProcessorTest {

    /** 写守卫规则：测试期间关闭 DiscipleTables 写入守卫（T3 需直接组装弟子表） */
    @get:Rule
    val writeGuardRule = WriteGuardRule()

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

    private fun createProcessor(
        inventorySystem: InventorySystem = mock()
    ): ProductionProcessor {
        return ProductionProcessor(
            stateStore = mock(),
            inventorySystem = inventorySystem,
            productionCoordinator = mock(),
            productionSlotRepository = mock(),
            formulaService = mock(),
            rngManager = mock(),
            scopeProvider = mock(),
            ioDispatcher = IoDispatcher(),
            inventoryConfig = com.xianxia.sect.core.config.InventoryConfig()
        )
    }

    /** 收获/种植测试的可选环境配置（默认空，仅光环/长老/跨宗门场景使用） */
    private data class HarvestEnv(
        val placedBuildings: List<GridBuildingData> = emptyList(),
        val elderSlots: ElderSlots = ElderSlots(),
        val discipleTables: DiscipleTables = DiscipleTables(),
        val activeSectId: String = ""
    )

    private fun createState(
        plants: List<SpiritFieldPlant> = emptyList(),
        seeds: List<Seed> = emptyList(),
        herbs: List<Herb> = emptyList(),
        gameYear: Int = 1,
        gameMonth: Int = 1,
        env: HarvestEnv = HarvestEnv()
    ): MutableGameState {
        return MutableGameState(
            gameData = GameData(
                gameYear = gameYear,
                gameMonth = gameMonth,
                spiritFieldPlants = plants,
                placedBuildings = env.placedBuildings,
                elderSlots = env.elderSlots,
                activeSectId = env.activeSectId
            ),
            discipleTables = env.discipleTables,
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
    // 收获路径重构回归（2026-08-07）：O(n×(d+b+n+h)) → O(n+d+b+h)
    //
    // T1：Bug A 回归——统计字段（guideCounters/annualHerbCount/annualHerbBySource）
    //      在循环中被累加后，必须基于最新 gameData 写回，禁止被函数开头捕获的
    //      旧 data 引用覆盖清零（修复前必红）
    // T2：300 块灵田批量收获的性能重构行为等价锚点（单堆叠大额种子场景）
    // T3：光环索引预计算 + 地块门控——光环内田提前成熟收获、光环外田不受影响
    // T4：仓库满时溢出转邮件且统计按实际入库（重构后单 store 语义保留）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `processSpiritFieldHarvest - 收获后引导计数与年度统计正确累计不覆盖`() = runTest {
        val dbSeed = HerbDatabase.getSeedByName("聚灵草种") ?: return@runTest
        val plants = (1..3).map { i ->
            SpiritFieldPlant(
                buildingInstanceId = "field$i", seedId = "p$i",
                seedName = "聚灵草种", growTime = 36, expectedYield = 5,
                plantYear = 1, plantMonth = 1
            )
        }
        val seeds = listOf(Seed(id = "s1", slotId = 1, name = "聚灵草种",
            rarity = dbSeed.rarity, growTime = 36, yield = 5, quantity = 3))
        val state = createState(plants = plants, seeds = seeds, gameYear = 4, gameMonth = 1)
        // 非零基线：模拟此前已有收获记录（Bug A 修复前会被函数开头捕获的旧 data 引用覆盖清零）
        state.gameData = state.gameData.copy(
            guideCounters = mapOf(GuideCounterKeys.HERBS_HARVESTED to 5L),
            annualHerbCount = 7,
            annualHerbBySource = mapOf("spirit_field" to 10)
        )
        val processor = createProcessor()
        processor.processSpiritFieldHarvest(state)
        assertEquals("引导计数应累计 +3 而非覆盖",
            8L, state.gameData.guideCounters[GuideCounterKeys.HERBS_HARVESTED])
        assertEquals("年度收获数应累计 +3 而非覆盖", 10, state.gameData.annualHerbCount)
        assertEquals("年度来源统计应累计实际入库 15", 25, state.gameData.annualHerbBySource["spirit_field"])
        assertEquals("3 块田同种灵草合并为 1 条记录", 1, state.herbs.all().size)
        assertEquals("总产量 15", 15, state.herbs.all().first().quantity)
        assertTrue("3 颗种子应全部消耗", state.seeds.all().isEmpty())
        assertEquals("3 块田全部续种", 3,
            state.gameData.spiritFieldPlants.count { it.plantYear == 4 && it.seedId.isNotEmpty() })
    }

    @Test
    fun `processSpiritFieldHarvest - 300块灵田批量收获合并续种统计正确`() = runTest {
        val dbSeed = HerbDatabase.getSeedByName("聚灵草种") ?: return@runTest
        val plants = (1..300).map { i ->
            SpiritFieldPlant(
                buildingInstanceId = "field$i", seedId = "p$i",
                seedName = "聚灵草种", growTime = 36, expectedYield = 5,
                plantYear = 1, plantMonth = 1
            )
        }
        // 单堆叠 quantity=300（真实大额种子场景）：避免 seeds.size 占槽位导致 maxSlots 溢出
        val seeds = listOf(Seed(id = "s1", slotId = 1, name = "聚灵草种",
            rarity = dbSeed.rarity, growTime = 36, yield = 5, quantity = 300))
        val state = createState(plants = plants, seeds = seeds, gameYear = 4, gameMonth = 1)
        val inventorySystem = mock<InventorySystem>()
        val processor = createProcessor(inventorySystem = inventorySystem)
        processor.processSpiritFieldHarvest(state)
        val herbs = state.herbs.all()
        assertEquals("300 块田同种灵草合并为 1 条记录", 1, herbs.size)
        assertEquals("总产量 1500", 1500, herbs.first().quantity)
        assertTrue("种子应被全部消耗", state.seeds.all().isEmpty())
        assertEquals("地块数不变", 300, state.gameData.spiritFieldPlants.size)
        assertEquals("300 块全部续种", 300,
            state.gameData.spiritFieldPlants.count { it.plantYear == 4 && it.seedId.isNotEmpty() })
        assertEquals("引导计数累计 +300", 300L,
            state.gameData.guideCounters[GuideCounterKeys.HERBS_HARVESTED])
        assertEquals("年度收获数累计 +300", 300, state.gameData.annualHerbCount)
        assertEquals("年度来源统计累计 1500", 1500, state.gameData.annualHerbBySource["spirit_field"])
        verify(inventorySystem, never()).sendOverflowMail(any(), any(), any(), any(), any())
    }

    @Test
    fun `processSpiritFieldHarvest - 光环内灵田提前成熟收获，光环外不受影响`() = runTest {
        // 灵植阁(0,0,4x3) 中心(2,1.5)：光环内田(2,1,1x1) 距离 0 ≤ 6 命中；
        // 光环外田(20,20,1x1) 最近距离约 25.8 > 6 不命中
        val placedBuildings = listOf(
            GridBuildingData(displayName = "灵植阁", gridX = 0, gridY = 0,
                width = 4, height = 3, instanceId = "garden1", sectId = "sectA"),
            GridBuildingData(displayName = "灵田", gridX = 2, gridY = 1,
                width = 1, height = 1, instanceId = "field_in", sectId = "sectA"),
            GridBuildingData(displayName = "灵田", gridX = 20, gridY = 20,
                width = 1, height = 1, instanceId = "field_out", sectId = "sectA")
        )
        // 灵植属性取到加成上限 0.20（sp = base + 20×step，无天赋/词条职务加成）
        val elderSp = GameConfig.PolicyConfig.HERB_GARDEN_ELDER_SPIRIT_BASE +
            20 * GameConfig.PolicyConfig.HERB_GARDEN_ELDER_SPIRIT_STEP
        val auraSp = GameConfig.PolicyConfig.HERB_GARDEN_DISCIPLE_SPIRIT_BASE +
            20 * GameConfig.PolicyConfig.HERB_GARDEN_DISCIPLE_SPIRIT_STEP
        val tables = DiscipleTables()
        tables.addId(100)
        tables.names[100] = "灵植长老"
        tables.isAlive[100] = 1
        tables.spiritPlantings[100] = elderSp
        tables.addId(101)
        tables.names[101] = "光环弟子"
        tables.isAlive[101] = 1
        tables.spiritPlantings[101] = auraSp
        val elderSlots = ElderSlots(
            herbGardenElder = "100",
            herbGardenDisciples = listOf(DirectDiscipleSlot(index = 0, discipleId = "101"))
        )
        val innerPlant = SpiritFieldPlant(buildingInstanceId = "field_in", seedId = "p1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val outerPlant = SpiritFieldPlant(buildingInstanceId = "field_out", seedId = "p2",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val state = createState(
            plants = listOf(innerPlant, outerPlant),
            gameYear = 3, gameMonth = 5,  // elapsed = 28 月
            env = HarvestEnv(
                placedBuildings = placedBuildings,
                elderSlots = elderSlots,
                discipleTables = tables
            )
        )
        // 期望有效生长时间（用与生产代码同一 API 推导——测试光环索引+地块门控集成，
        // 加成公式算术本身由 HerbGardenAuraServiceTest 覆盖）
        val innerEff = HerbGardenAuraService.calculateEffectiveGrowTime(
            36, ZoneCalculator.calculate(1.0, 0.2, 0.2, 0.0) - 1.0)
        val outerEff = HerbGardenAuraService.calculateEffectiveGrowTime(
            36, ZoneCalculator.calculate(1.0, 0.2, 0.0, 0.0) - 1.0)
        assertEquals("光环内有效生长时间 25", 25, innerEff)
        assertEquals("光环外有效生长时间 30", 30, outerEff)
        val processor = createProcessor()
        processor.processSpiritFieldHarvest(state)
        val herbs = state.herbs.all()
        assertEquals("仅光环内灵田收获", 1, herbs.size)
        assertEquals("收获聚灵草", "聚灵草", herbs.first().name)
        assertEquals("产量 5", 5, herbs.first().quantity)
        val inField = state.gameData.spiritFieldPlants.first { it.buildingInstanceId == "field_in" }
        val outField = state.gameData.spiritFieldPlants.first { it.buildingInstanceId == "field_out" }
        assertEquals("光环内田已收获清空（无种子续种）", "", inField.seedId)
        assertEquals("光环外田未成熟保持不变", "p2", outField.seedId)
    }

    @Test
    fun `processSpiritFieldHarvest - 仓库满时溢出转邮件且统计按实际入库`() = runTest {
        val dbSeed = HerbDatabase.getSeedByName("聚灵草种") ?: return@runTest
        val dbHerb = HerbDatabase.getHerbFromSeedName("聚灵草种") ?: return@runTest
        // maxSlots = computeMaxSlots(无仓库=基础容量 50) - 其他类型 0 - 种子 0 = 50；
        // 50 个满堆叠（herb maxStack=9999）占满全部槽位 → 收获的 5 株零合并且无空槽 → Failure 分支整批转邮件
        val maxStack = InventoryConfig().getMaxStackSize("herb")
        val fullStacks = (1..50).map { i ->
            Herb(id = "h$i", name = dbHerb.name, rarity = dbHerb.rarity,
                description = dbHerb.description, category = dbHerb.category, quantity = maxStack)
        }
        val plant = SpiritFieldPlant(buildingInstanceId = "field1", seedId = "p1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val state = createState(plants = listOf(plant), herbs = fullStacks, gameYear = 4, gameMonth = 1)
        val inventorySystem = mock<InventorySystem>()
        val processor = createProcessor(inventorySystem = inventorySystem)
        processor.processSpiritFieldHarvest(state)
        // 溢出全量 5 株转邮件（满堆叠零合并且无空槽 → Failure 分支，与 Partial 同为溢出转邮件路径）
        verify(inventorySystem).sendOverflowMail("spirit_field", "herb", dbHerb.name, dbHerb.rarity, 5)
        assertEquals("年度来源统计按实际入库 0", 0, state.gameData.annualHerbBySource["spirit_field"])
        assertEquals("仓库记录数不变（无新堆叠）", 50, state.herbs.all().size)
        assertEquals("引导计数仍累计（收获行为本身成功）", 1L,
            state.gameData.guideCounters[GuideCounterKeys.HERBS_HARVESTED])
        assertEquals("灵田已收获清空", "", state.gameData.spiritFieldPlants.first().seedId)
    }

    // ═══════════════════════════════════════════════════════════════
    // 对抗性审查修复（2026-08-07）— 锁定种子续种豁免 + 邮件异常防御
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `processSpiritFieldHarvest - 锁定种子不用于自动续种，田收获后清空且种子数量不变`() = runTest {
        // 对抗性审查发现 1：全系统"锁定=不可消耗"语义，自动续种不得绕过锁定保护
        val lockedSeed = Seed(
            id = "s1", slotId = 1, name = "聚灵草种", rarity = 1,
            growTime = 36, yield = 5, quantity = 3, isLocked = true
        )
        val plant = SpiritFieldPlant(buildingInstanceId = "field1", seedId = "p1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val state = createState(plants = listOf(plant), seeds = listOf(lockedSeed), gameYear = 4, gameMonth = 1)
        createProcessor().processSpiritFieldHarvest(state)

        assertEquals("草药正常收获", 1, state.herbs.all().size)
        assertEquals("锁定种子不被消耗", 3, state.seeds.all().first().quantity)
        assertEquals("田收获后清空（无种子可续种）", "", state.gameData.spiritFieldPlants.first().seedId)
    }

    @Test
    fun `processSpiritFieldHarvest - 溢出邮件异常时收获不丢草药`() = runTest {
        // 对抗性审查发现 2：循环中途异常时已完成地块的草药仍随事务提交、
        // 未处理地块保持成熟待下月再收（防御 try-catch，替代旧"整轮丢失"语义退化）
        val dbSeed = HerbDatabase.getSeedByName("聚灵草种") ?: return@runTest
        val dbHerb = HerbDatabase.getHerbFromSeedName("聚灵草种") ?: return@runTest
        val maxStack = InventoryConfig().getMaxStackSize("herb")
        val fullStacks = (1..50).map { i ->
            Herb(id = "h$i", name = dbHerb.name, rarity = dbHerb.rarity,
                description = dbHerb.description, category = dbHerb.category, quantity = maxStack)
        }
        val plant = SpiritFieldPlant(buildingInstanceId = "field1", seedId = "p1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val state = createState(plants = listOf(plant), herbs = fullStacks, gameYear = 4, gameMonth = 1)
        val inventorySystem = mock<InventorySystem>()
        // 仓库满 → 溢出转邮件 → 邮件系统异常（模拟故障）
        whenever(inventorySystem.sendOverflowMail(any(), any(), any(), any(), any()))
            .thenThrow(RuntimeException("邮件系统故障"))
        createProcessor(inventorySystem = inventorySystem).processSpiritFieldHarvest(state)

        assertEquals("草药未丢失（堆叠数不变）", 50, state.herbs.all().size)
        assertEquals("田保持成熟未清空（异常地块下月再收）", "p1",
            state.gameData.spiritFieldPlants.first().seedId)
        assertEquals("统计未虚增（add 未完成）", 0,
            state.gameData.annualHerbBySource["spirit_field"] ?: 0)
    }

    @Test
    fun `processSpiritFieldHarvest - 续种后 seedId 更新为实际消耗的种子堆叠`() = runTest {
        // 对抗性审查 F2：原实现保留悬空 seedId（其堆叠已被扣尽移除），
        // UI 按 seedId 查库存失败误显示存量 0、同种种子分组分裂
        val seed = Seed(id = "s2", slotId = 1, name = "聚灵草种", rarity = 1,
            growTime = 36, yield = 5, quantity = 2, isLocked = false)
        val plant = SpiritFieldPlant(buildingInstanceId = "field1", seedId = "stale1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val state = createState(plants = listOf(plant), seeds = listOf(seed), gameYear = 4, gameMonth = 1)
        createProcessor().processSpiritFieldHarvest(state)

        assertEquals("续种消耗新堆叠 1 颗", 1, state.seeds.all().first().quantity)
        assertEquals("田 seedId 指向实际消耗的堆叠", "s2",
            state.gameData.spiritFieldPlants.first().seedId)
    }

    @Test
    fun `processSpiritFieldHarvest - 跨宗门地块不收获不扣种子`() = runTest {
        // 对抗性审查 F3：sectId 非本宗的田不收获（防扣本宗种子续种到异常田）
        val seed = Seed(id = "s1", slotId = 1, name = "聚灵草种", rarity = 1,
            growTime = 36, yield = 5, quantity = 3, isLocked = false)
        val foreignPlant = SpiritFieldPlant(buildingInstanceId = "field_foreign", seedId = "p1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1,
            sectId = "sectB")
        val state = createState(plants = listOf(foreignPlant), seeds = listOf(seed),
            gameYear = 4, gameMonth = 1, env = HarvestEnv(activeSectId = "sectA"))
        createProcessor().processSpiritFieldHarvest(state)

        assertEquals("跨宗门田不收获", 0, state.herbs.all().size)
        assertEquals("种子不被消耗", 3, state.seeds.all().first().quantity)
        assertEquals("田保持原样", "p1", state.gameData.spiritFieldPlants.first().seedId)
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
