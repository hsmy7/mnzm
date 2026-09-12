package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.RoadData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.random.Random

/**
 * RoadMaskTracker 增量装配测试（O(全图) 收敛）。
 *
 * 核心守护：随机增删变更序列下，增量路径产物与全量构建
 * （RoadTiling.buildRoadMaskArray）**逐位一致**——增量只重算受影响格
 * （变更格 + 四邻），任何漏算/串位即红。附加锁定：稳定引用契约、
 * 1-based 编码（单格道路 = 1）、空集 null。
 */
class RoadMaskTrackerTest {

    private val cols = 24
    private val rows = 16

    private fun roads(vararg cells: Pair<Int, Int>): List<RoadData> =
        cells.map { RoadData(gridX = it.first, gridY = it.second) }

    @Test
    fun `incremental path matches full build across random mutation sequences`() {
        val rng = Random(20260908)
        repeat(20) {
            val tracker = RoadMaskTracker(cols, rows)
            val current = mutableSetOf<Pair<Int, Int>>()
            repeat(120) { step ->
                // 随机增或删一格（含边界格：邻接掩码的越界判据需覆盖）
                val x = rng.nextInt(cols)
                val y = rng.nextInt(rows)
                if (rng.nextBoolean()) {
                    current.add(x to y)
                } else {
                    current.remove(x to y)
                }
                // 同格重复触发（增已存格/删不存在格）也须与全量一致
                val list = roads(*current.toTypedArray())
                val actual = tracker.syncTo(list)
                val expected = RoadTiling.buildRoadMaskArray(list, cols, rows)
                assertEquals(
                    "seq=$it step=$step state=$current",
                    expected?.toList(), actual?.toList()
                )
            }
        }
    }

    @Test
    fun `stable reference when content unchanged`() {
        val tracker = RoadMaskTracker(cols, rows)
        val first = tracker.syncTo(roads(1 to 1, 2 to 1, 3 to 1))
        assertNotNull(first)
        // 同引用：直通
        assertSame(first, tracker.syncTo(roads(1 to 1, 2 to 1, 3 to 1)))
        // 不同引用但内容相等（镜像重发形态）：内容等价早退 → 稳定引用
        val again = tracker.syncTo(roads(3 to 1, 1 to 1, 2 to 1))
        assertSame("内容未变必须返回稳定引用（渲染端以引用变化驱动失效）", first, again)
    }

    @Test
    fun `empty roads returns null and recovers`() {
        val tracker = RoadMaskTracker(cols, rows)
        assertNull("空道路必须 null（渲染端跳过整层）", tracker.syncTo(emptyList()))
        val afterPlace = tracker.syncTo(roads(2 to 2))
        assertNotNull(afterPlace)
        val mask = requireNotNull(afterPlace)
        assertEquals("单格道路必须 1（1-based：掩码 0 与非道路解耦）", 1, mask[2 * cols + 2])
        assertEquals("其余格恒 0", 0, mask[0])
        assertNull("清空回到 null", tracker.syncTo(emptyList()))
        val rePlaced = tracker.syncTo(roads(2 to 2))
        assertNotNull("从空恢复走全量重建路径", rePlaced)
    }

    @Test
    fun `removing middle cell repairs both sides`() {
        val tracker = RoadMaskTracker(cols, rows)
        tracker.syncTo(roads(2 to 4, 3 to 4, 4 to 4))
        val afterRemove = requireNotNull(tracker.syncTo(roads(2 to 4, 4 to 4)))
        val expected = RoadTiling.buildRoadMaskArray(roads(2 to 4, 4 to 4), cols, rows)
        assertEquals(expected?.toList(), afterRemove.toList())
        // 断开后的两端各自是单格道路（掩码 0 → 存 1）
        assertEquals(1, afterRemove[4 * cols + 2])
        assertEquals(1, afterRemove[4 * cols + 4])
        assertEquals("被删格归零", 0, afterRemove[4 * cols + 3])
    }
}
