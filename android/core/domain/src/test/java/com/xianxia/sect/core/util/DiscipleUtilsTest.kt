package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.Disciple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sortedByRealmForDefense] 防守选人排序守卫。
 *
 * 2026-08-15 宗门防守战/妖兽防守战缺陷回归：原实现按 realmLayer（小层）降序，
 * 高境界弟子突破大境界后 layer 重置为 1，会被同池中低境界高 layer 弟子挤出防守队，
 * 导致"玩家高境界弟子不上场、被 AI 低境界弟子击败"。
 */
class DiscipleUtilsTest {

    private fun makeDisciple(id: String, realm: Int, layer: Int): Disciple =
        Disciple(id = id, name = id, realm = realm, realmLayer = layer, isAlive = true)

    @Test
    fun `sortedByRealmForDefense - 大境界优先 同境界小层降序`() {
        val disciples = listOf(
            makeDisciple("d1", realm = 9, layer = 9), // 炼气 9 层
            makeDisciple("d2", realm = 1, layer = 1), // 渡劫 1 层（最高境界，必须最前）
            makeDisciple("d3", realm = 1, layer = 5), // 渡劫 5 层
            makeDisciple("d4", realm = 5, layer = 3), // 化神 3 层
            makeDisciple("d5", realm = 0, layer = 2)  // 仙人 2 层（最高境界）
        )
        val sorted = disciples.sortedByRealmForDefense()
        assertEquals(listOf("d5", "d3", "d2", "d4", "d1"), sorted.map { it.id })
    }

    @Test
    fun `sortedByRealmForDefense - 高境界低小层优先于低境界高小层入选防守队`() {
        // 用户场景复现：5 名高境界弟子（渡劫 realm=1，突破后 layer=1~2）
        // + 10 名炼气弟子（realm=9，修炼已久 layer=7~9）
        val highRealm = (1..5).map { makeDisciple("high$it", realm = 1, layer = 1 + it % 2) }
        val lowRealm = (1..10).map { makeDisciple("low$it", realm = 9, layer = 7 + it % 3) }
        val pool = (lowRealm + highRealm).shuffled()

        val selected = pool.sortedByRealmForDefense().take(10)

        // 修复前（realmLayer 降序）：10 个炼气 layer 7~9 全部优先，高境界弟子一个都不上场
        // 修复后：全部 5 名高境界弟子必须入选
        val selectedIds = selected.map { it.id }.toSet()
        assertEquals(
            "高境界弟子必须全部入选防守队（修复前被炼气高小层弟子挤出）",
            highRealm.map { it.id }.toSet(),
            selectedIds.filter { it.startsWith("high") }.toSet()
        )
        assertTrue(selected.all { it.isAlive })
    }
}
