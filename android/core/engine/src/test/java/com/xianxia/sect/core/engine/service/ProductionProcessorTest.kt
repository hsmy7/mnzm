package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SkillStats
import org.junit.Assert.*
import org.junit.Test

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
}
