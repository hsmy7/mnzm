package com.xianxia.sect.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RenderMetricsOverflowTest — 精灵容量溢出遥测折叠测试（R0.3）。
 *
 * 守护目标：C++ 侧累计计数经 [RenderMetrics.foldSpriteOverflowStats] 折叠后，
 * 溢出可观测（丢弃数/溢出帧数/降级帧数），且折叠单调（重复/乱序轮询不回退）。
 */
class RenderMetricsOverflowTest {

    @Before
    fun setUp() {
        RenderMetrics.resetForTest()
    }

    @Test
    fun `foldSpriteOverflowStats - counters fold cumulative values`() {
        RenderMetrics.foldSpriteOverflowStats(droppedTotal = 120, overflowFrames = 3, degradeFrames = 5)

        assertEquals(120L, RenderMetrics.spriteOverflowDropped.get())
        assertEquals(3L, RenderMetrics.spriteOverflowFrames.get())
        assertEquals(5L, RenderMetrics.spriteOverflowDegradeFrames.get())
    }

    @Test
    fun `foldSpriteOverflowStats - monotonic max fold ignores stale lower reads`() {
        RenderMetrics.foldSpriteOverflowStats(droppedTotal = 200, overflowFrames = 5, degradeFrames = 8)
        // 渲染器重建/轮询交错可能读到相同值——折叠必须保持单调不回退
        RenderMetrics.foldSpriteOverflowStats(droppedTotal = 200, overflowFrames = 5, degradeFrames = 8)

        assertEquals(200L, RenderMetrics.spriteOverflowDropped.get())
        assertEquals(5L, RenderMetrics.spriteOverflowFrames.get())
        assertEquals(8L, RenderMetrics.spriteOverflowDegradeFrames.get())
    }

    @Test
    fun `foldSpriteOverflowStats - growing counters accumulate forward`() {
        RenderMetrics.foldSpriteOverflowStats(droppedTotal = 100, overflowFrames = 2, degradeFrames = 4)
        RenderMetrics.foldSpriteOverflowStats(droppedTotal = 350, overflowFrames = 9, degradeFrames = 12)

        assertEquals(350L, RenderMetrics.spriteOverflowDropped.get())
        assertEquals(9L, RenderMetrics.spriteOverflowFrames.get())
        assertEquals(12L, RenderMetrics.spriteOverflowDegradeFrames.get())
    }

    @Test
    fun `snapshot - carries sprite overflow fields`() {
        RenderMetrics.foldSpriteOverflowStats(droppedTotal = 42, overflowFrames = 1, degradeFrames = 2)

        val snapshot = RenderMetrics.snapshot()
        assertEquals(42L, snapshot.spriteOverflowDropped)
        assertEquals(2L, snapshot.spriteOverflowDegradeFrames)
    }

    @Test
    fun `formatForCrashReport - renders key-value lines for all counters`() {
        RenderMetrics.resetForTest()
        RenderMetrics.totalFrames.set(120)
        RenderMetrics.softwareFrames.set(30)
        RenderMetrics.renderFrameNull.set(3)
        RenderMetrics.lockCanvasFailed.set(1)
        RenderMetrics.atlasBuildFailed.set(2)
        RenderMetrics.foldSpriteOverflowStats(droppedTotal = 42, overflowFrames = 1, degradeFrames = 2)

        val report = RenderMetrics.formatForCrashReport()

        val expectedLines = listOf(
            "TotalFrames: 120",
            "SoftwareFrames: 30",
            "RenderFrameNull: 3",
            "LockCanvasFailed: 1",
            "AtlasBuildFailed: 2",
            "SpriteOverflowDropped: 42",
            "SpriteOverflowDegradeFrames: 2"
        )
        expectedLines.forEach { line ->
            assertTrue("崩溃报告渲染段缺少 $line\n实际：\n$report", report.contains(line))
        }
        // 浮点固定 Locale.US 小数点格式（无区域设置逗号漂移）
        assertTrue("SoftwareRatio 应为 0.25\n实际：\n$report", report.contains("SoftwareRatio: 0.25"))
    }
}
