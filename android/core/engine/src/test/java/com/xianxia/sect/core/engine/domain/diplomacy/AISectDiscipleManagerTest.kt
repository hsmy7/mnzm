package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.PlantSlotData
import org.junit.Assert.*
import org.junit.Test

class AISectDiscipleManagerTest {

    // ── truncateToLimit ──
    // 回归覆盖：战胜AI宗门后玩家宗门涌入1000+弟子 (commit f8475620)
    // 合并逻辑曾使截断失效，这里直接验证 truncateToLimit 纯函数行为。

    @Test
    fun `truncateToLimit - 空列表原样返回`() {
        val result = AISectDiscipleManager.truncateToLimit(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `truncateToLimit - 未超上限原样返回`() {
        val disciples = (0 until 100).map { makeDisciple("d$it", power = it * 10) }
        val result = AISectDiscipleManager.truncateToLimit(disciples)
        assertEquals(100, result.size)
        // 顺序保持不变（未触发排序）
        assertEquals(disciples, result)
    }

    @Test
    fun `truncateToLimit - 刚好等于上限原样返回`() {
        val limit = PlantSlotData.MAX_AI_DISCIPLES_PER_SECT
        val disciples = (0 until limit).map { makeDisciple("d$it", power = it) }
        val result = AISectDiscipleManager.truncateToLimit(disciples)
        assertEquals(limit, result.size)
    }

    @Test
    fun `truncateToLimit - 超过上限截断至上限`() {
        val limit = PlantSlotData.MAX_AI_DISCIPLES_PER_SECT
        val disciples = (0 until limit + 50).map { makeDisciple("d$it", power = it) }
        val result = AISectDiscipleManager.truncateToLimit(disciples)
        assertEquals(limit, result.size)
    }

    @Test
    fun `truncateToLimit - 按战力降序保留强者`() {
        // 战力 = basePhysicalAttack + baseMagicAttack + baseHp
        // weak:   10 + 10 + 120 = 140
        // filler: 50 + 50 + 120 = 220
        // strong: 100 + 100 + 120 = 320
        val weak = (0 until 10).map { makeDisciple("weak_$it", pa = 10, ma = 10, hp = 120) }
        val strong = (0 until 10).map { makeDisciple("strong_$it", pa = 100, ma = 100, hp = 120) }
        // filler 数量 = 上限 - strong 数量，确保 weak 被完全淘汰
        val fillerCount = PlantSlotData.MAX_AI_DISCIPLES_PER_SECT - strong.size
        val filler = (0 until fillerCount).map { makeDisciple("filler_$it", pa = 50, ma = 50, hp = 120) }
        val all = strong + filler + weak
        val result = AISectDiscipleManager.truncateToLimit(all)
        assertEquals(PlantSlotData.MAX_AI_DISCIPLES_PER_SECT, result.size)
        // 强者战力 320 必须全部保留
        val strongSurvivors = result.filter { it.id.startsWith("strong_") }
        assertEquals(10, strongSurvivors.size)
        // 弱者战力 140 应被淘汰（filler 战力 220 优先于 weak）
        val weakSurvivors = result.filter { it.id.startsWith("weak_") }
        assertEquals(0, weakSurvivors.size)
    }

    @Test
    fun `truncateToLimit - 战力相同时不丢数据`() {
        val limit = PlantSlotData.MAX_AI_DISCIPLES_PER_SECT
        val disciples = (0 until limit + 10).map { makeDisciple("d$it", pa = 50, ma = 50, hp = 120) }
        val result = AISectDiscipleManager.truncateToLimit(disciples)
        assertEquals(limit, result.size)
        // 全部战力相同，截断后应保留 limit 个不同 id（无重复）
        assertEquals(result.map { it.id }.toSet().size, result.size)
    }

    @Test
    fun `truncateToLimit - 单次大批量涌入被截断`() {
        // 模拟路径A：多年累积后单次涌入远超上限
        val limit = PlantSlotData.MAX_AI_DISCIPLES_PER_SECT
        val disciples = (0 until limit * 3).map { makeDisciple("d$it", power = it) }
        val result = AISectDiscipleManager.truncateToLimit(disciples)
        assertEquals(limit, result.size)
        // 保留的应是战力最高的 limit 个（id 后段）
        val maxId = result.maxOf { it.id.removePrefix("d").toInt() }
        assertTrue("应保留高战力弟子", maxId >= limit * 3 - 1)
    }

    // ── 辅助 ──

    private fun makeDisciple(
        id: String,
        power: Int = 0,
        pa: Int = 12,
        ma: Int = 12,
        hp: Int = 120
    ): Disciple {
        // power 参数便捷设定整体战力（覆盖 pa/ma/hp 的和）
        val (actualPa, actualMa, actualHp) = if (power != 0) {
            Triple(power / 3, power / 3, power - 2 * (power / 3))
        } else {
            Triple(pa, ma, hp)
        }
        return Disciple(
            id = id,
            combat = CombatAttributes(
                basePhysicalAttack = actualPa,
                baseMagicAttack = actualMa,
                baseHp = actualHp
            )
        )
    }
}
