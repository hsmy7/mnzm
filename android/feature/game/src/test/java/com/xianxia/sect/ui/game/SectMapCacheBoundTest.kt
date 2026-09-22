package com.xianxia.sect.ui.game

import com.xianxia.sect.core.model.MapPreloadData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MR1-P1.2/D6：宗门地图缓存有界驱逐守卫。
 *
 * - LRU 上限：`sectMapCache` 为 accessOrder=true 的 LinkedHashMap + 上限
 *   [SectMapController.SECT_MAP_CACHE_MAX_ENTRIES]（=8）——第 9 个条目插入时
 *   驱逐最旧（原 ConcurrentHashMap 无界缓存随切宗次数线性增长）；
 * - 命中即重排（访问序）：读最旧条目使其成为最新，插入后驱逐的是另一条；
 * - `evictNonCurrent`（D3 SOFT 动作面）：仅保留当前宗门（activeSectId 派生
 *   种子），非当前整条移除——只放可重建资源，不清进度语义。
 */
class SectMapCacheBoundTest {

    private fun controller(mapSeed: Int, activeSectId: String): SectMapController {
        val gameData = MutableStateFlow(
            com.xianxia.sect.core.model.GameData(mapSeed = mapSeed, activeSectId = activeSectId)
        )
        // TestScope 本身即 CoroutineScope（仅用于 sectTransition 的延迟协程，本测试不推进）
        return SectMapController(gameData, TestScope(StandardTestDispatcher()))
    }

    private fun entry(seed: Int) = MapPreloadData(
        flatTileData = IntArray(1) { seed },
        worldWidthCells = 1,
        worldHeightCells = 1,
        tileSize = 1,
        worldPixelWidth = 1,
        worldPixelHeight = 1,
        seed = seed
    )

    @Test
    fun `缓存条目超上限时驱逐最旧条目`() {
        val controller = controller(mapSeed = 100, activeSectId = "")
        repeat(10) { index -> controller.sectMapCache[index] = entry(index) }

        assertTrue(
            "缓存条目数超过 LRU 上限（无界缓存回潮）",
            controller.sectMapCache.size <= 8
        )
        // 最旧两条（0/1）被驱逐，最新 8 条保留
        assertNull(controller.sectMapCache[0])
        assertNull(controller.sectMapCache[1])
        assertNotNull(controller.sectMapCache[9])
    }

    @Test
    fun `访问序命中使旧条目免于驱逐`() {
        val controller = controller(mapSeed = 100, activeSectId = "")
        repeat(8) { index -> controller.sectMapCache[index] = entry(index) }
        // 读最旧条目 0 → 其访问时间刷新为最新
        assertNotNull(controller.sectMapCache[0])
        // 插入第 9 条：被驱逐的应是「未刷新访问」的最旧条目 1，而非刚访问过的 0
        controller.sectMapCache[8] = entry(8)

        assertNotNull("accessOrder=true 语义失效（命中未重排）", controller.sectMapCache[0])
        assertNull(controller.sectMapCache[1])
    }

    @Test
    fun `evictNonCurrent 仅保留当前宗门条目`() {
        val controller = controller(mapSeed = 100, activeSectId = "")
        // 当前宗门（主宗 "" → seed=100）+ 三个非当前宗门
        controller.sectMapCache[100] = entry(100)
        controller.sectMapCache[101] = entry(101)
        controller.sectMapCache[102] = entry(102)
        controller.sectMapCache[103] = entry(103)

        controller.evictNonCurrent()

        assertEquals(1, controller.sectMapCache.size)
        assertNotNull("当前宗门条目不应被驱逐", controller.sectMapCache[100])
    }

    @Test
    fun `未加载态 evictNonCurrent 清空全部条目`() {
        val controller = controller(mapSeed = 0, activeSectId = "")
        repeat(3) { index -> controller.sectMapCache[index] = entry(index) }

        controller.evictNonCurrent()

        assertTrue(controller.sectMapCache.isEmpty())
    }

    @Test
    fun `deriveSectSeed 主宗与非主宗语义`() {
        assertEquals(100, deriveSectSeed(100, ""))
        assertFalse(deriveSectSeed(100, "sect-a") == deriveSectSeed(100, "sect-b"))
    }
}
