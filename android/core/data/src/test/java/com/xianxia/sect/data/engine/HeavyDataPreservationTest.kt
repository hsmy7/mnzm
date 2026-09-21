package com.xianxia.sect.data.engine

import com.xianxia.sect.core.model.GameHeavyData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计 §12-A（勘误后）heavy 数据保全的纯函数测试。
 *
 * 真缺口两条：
 * 1) 读档跳过超 CursorWindow 的单 key 后内存值为空；下一次保存若照常先删该前缀
 *    再用空值重写，会永久覆盖 DB 完整数据 ⇒ clearHeavyDataByPrefix 必须排除被跳过
 *    的 key（heavyPrefixesToClear）。
 * 2) "heavy_data 整表为空才回填"的判据过窄 ⇒ 改为任一 key 缺失即回填缺失 key
 *    （keysNeedingBackfill）。
 */
class HeavyDataPreservationTest {

    private val allKeys = GameHeavyData.ALL_KEYS

    // ── heavyPrefixesToClear ──

    @Test
    fun `empty skipped set clears all seven prefixes`() {
        val result = heavyPrefixesToClear(allKeys, emptySet())
        assertEquals(allKeys, result)
        assertEquals(7, result.size)
    }

    @Test
    fun `skipped key is excluded while other six remain`() {
        val result = heavyPrefixesToClear(allKeys, setOf(GameHeavyData.KEY_EXPLORED_SECTS))
        assertFalse(
            "被跳过的 exploredSects 不得出现在清除清单（否则空内存值覆盖 DB）",
            result.contains(GameHeavyData.KEY_EXPLORED_SECTS)
        )
        assertEquals(6, result.size)
        assertTrue(result.containsAll(
            allKeys.filterNot { it == GameHeavyData.KEY_EXPLORED_SECTS }
        ))
    }

    @Test
    fun `unknown skipped key does not affect the clear list`() {
        val result = heavyPrefixesToClear(allKeys, setOf("notAHeavyKey", "exploredSects/x"))
        assertEquals(allKeys, result)
        assertEquals(7, result.size)
    }

    // ── keysNeedingBackfill ──

    @Test
    fun `all keys present needs no backfill`() {
        assertTrue(keysNeedingBackfill(allKeys, allKeys.toSet()).isEmpty())
    }

    @Test
    fun `only missing keys need backfill`() {
        val present = allKeys.toSet() - GameHeavyData.KEY_SCOUT_INFO
        assertEquals(
            listOf(GameHeavyData.KEY_SCOUT_INFO),
            keysNeedingBackfill(allKeys, present)
        )
    }
}
