package com.xianxia.sect.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BuildingRenderGeometry 共享几何纯函数测试（2026-08-10 新增，WP3）。
 *
 * 双后端（Vulkan C++ / Canvas Kotlin）的阴影/精灵偏移/命中判定共用同一数学来源，
 * 此处锁定 Kotlin 侧行为；C++ 侧一致性由代码审查 + SoftwareCanvasBackendTest
 * 像素断言（同数学渲染结果）覆盖。
 */
class BuildingRenderGeometryTest {

    // ── spriteOffset（精灵底部对齐偏移）──

    @Test
    fun `spriteOffset - 占地2x2 精灵2x2 偏移为0`() {
        val (ox, oy) = BuildingRenderGeometry.spriteOffset(2, 2, 2f, 2f, 32)
        assertEquals(0f, ox, 0.001f)
        assertEquals(0f, oy, 0.001f)
    }

    @Test
    fun `spriteOffset - 占地2x2 精灵3x4 水平居中 底部对齐`() {
        val (ox, oy) = BuildingRenderGeometry.spriteOffset(2, 2, 3f, 4f, 32)
        // 水平：(fpW - sw) * tileSize * 0.5 = (2-3)*32*0.5 = -16
        assertEquals(-16f, ox, 0.001f)
        // 垂直：(fpH - sh) * tileSize = (2-4)*32 = -64（精灵更高，向上偏移底部对齐）
        assertEquals(-64f, oy, 0.001f)
    }

    @Test
    fun `spriteOffset - 精灵小于占地 偏移为正值`() {
        val (ox, oy) = BuildingRenderGeometry.spriteOffset(4, 3, 2f, 2f, 16)
        assertEquals(16f, ox, 0.001f) // (4-2)*16*0.5
        assertEquals(16f, oy, 0.001f) // (3-2)*16
    }

    // ── shadowRect（阴影矩形，右下偏移 0.25 格）──

    @Test
    fun `shadowRect - 偏移0_25格 尺寸与占地一致`() {
        val r = BuildingRenderGeometry.shadowRect(3, 5, 2, 2, 32)
        val offset = 32 * 0.25f
        assertEquals(3 * 32 + offset, r[0], 0.001f)
        assertEquals(5 * 32 + offset, r[1], 0.001f)
        assertEquals(64f, r[2], 0.001f)
        assertEquals(64f, r[3], 0.001f)
    }

    @Test
    fun `shadowRect - 大占地与不同tileSize`() {
        val r = BuildingRenderGeometry.shadowRect(0, 0, 6, 4, 16)
        val offset = 16 * 0.25f
        assertEquals(offset, r[0], 0.001f)
        assertEquals(offset, r[1], 0.001f)
        assertEquals(96f, r[2], 0.001f)
        assertEquals(64f, r[3], 0.001f)
    }

    // ── findBuildingIndex（占地命中判定）──

    private fun buildingData(vararg entries: FloatArray): FloatArray {
        val flat = FloatArray(entries.size * 5)
        entries.forEachIndexed { i, e -> System.arraycopy(e, 0, flat, i * 5, 5) }
        return flat
    }

    @Test
    fun `findBuildingIndex - 命中占地内任意格`() {
        // 建筑0: 灵矿场 nameIdx=0（占地 4x4）位于 (5,5)；建筑1: 灵植阁 nameIdx=1（占地 4x3）位于 (0,0)
        val data = buildingData(
            floatArrayOf(5f, 5f, 4f, 4f, 0f),
            floatArrayOf(0f, 0f, 5f, 6f, 1f)
        )
        // 建筑0 占地 (5,5)-(8,8) 内命中
        assertEquals(0, BuildingRenderGeometry.findBuildingIndex(5, 5, data, 2))
        assertEquals(0, BuildingRenderGeometry.findBuildingIndex(8, 8, data, 2))
        // 越出占地一格不命中
        assertEquals(-1, BuildingRenderGeometry.findBuildingIndex(9, 8, data, 2))
        // 建筑1 占地 (0,0)-(3,2) 内命中
        assertEquals(1, BuildingRenderGeometry.findBuildingIndex(3, 2, data, 2))
        // 空地不命中
        assertEquals(-1, BuildingRenderGeometry.findBuildingIndex(9, 9, data, 2))
    }

    @Test
    fun `findBuildingIndex - 使用footprint判定 精灵超出占地不算命中`() {
        // 灵植阁 nameIdx=1（占地 4x3）；精灵尺寸 5x6 超出占地——仍以占地判定
        val data = buildingData(floatArrayOf(0f, 0f, 5f, 6f, 1f))
        assertEquals(0, BuildingRenderGeometry.findBuildingIndex(0, 0, data, 1))
        assertEquals(0, BuildingRenderGeometry.findBuildingIndex(3, 2, data, 1))
        // 精灵超出部分（占地外一格）不命中
        assertEquals(-1, BuildingRenderGeometry.findBuildingIndex(4, 0, data, 1))
        assertEquals(-1, BuildingRenderGeometry.findBuildingIndex(0, 3, data, 1))
    }

    @Test
    fun `findBuildingIndex - count超出数组容量 自动coerce`() {
        val data = buildingData(floatArrayOf(0f, 0f, 2f, 2f, 0f))
        // count=10 但数组只有 1 条 → 只检查 1 条，不越界
        assertEquals(0, BuildingRenderGeometry.findBuildingIndex(1, 1, data, 10))
        assertEquals(-1, BuildingRenderGeometry.findBuildingIndex(5, 5, data, 10))
    }

    @Test
    fun `findBuildingIndex - 防御路径 空数组与负count`() {
        assertEquals(-1, BuildingRenderGeometry.findBuildingIndex(0, 0, null, 1))
        assertEquals(-1, BuildingRenderGeometry.findBuildingIndex(0, 0, FloatArray(0), 1))
        assertEquals(-1, BuildingRenderGeometry.findBuildingIndex(0, 0, FloatArray(5) { 0f }, 0))
        assertEquals(-1, BuildingRenderGeometry.findBuildingIndex(0, 0, FloatArray(5) { 0f }, -3))
        // 负坐标格不命中
        val data = buildingData(floatArrayOf(0f, 0f, 2f, 2f, 0f))
        assertEquals(-1, BuildingRenderGeometry.findBuildingIndex(-1, -1, data, 1))
    }

    @Test
    fun `findBuildingIndex - nameIdx越界 回退默认占地2x2`() {
        val data = buildingData(floatArrayOf(0f, 0f, 2f, 2f, 999f))
        // 回退占地 2x2：(0,0)-(1,1) 命中，(2,0) 不命中
        assertEquals(0, BuildingRenderGeometry.findBuildingIndex(1, 1, data, 1))
        assertEquals(-1, BuildingRenderGeometry.findBuildingIndex(2, 0, data, 1))
    }

    @Test
    fun `findBuildingIndex - 自定义footprint 与 SpriteAtlasDef 默认表一致`() {
        // 灵矿场 nameIdx=0：占地 4x4（FOOTPRINT_BY_NAME_INDEX 注册值）
        val data = buildingData(floatArrayOf(10f, 10f, 4f, 4f, 0f))
        val footprint = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX[0] ?: (4 to 4)
        assertTrue("灵矿场占地应为 4x4，实际 $footprint", footprint == 4 to 4)
        assertEquals(0, BuildingRenderGeometry.findBuildingIndex(10, 10, data, 1))
        assertEquals(0, BuildingRenderGeometry.findBuildingIndex(13, 13, data, 1))
        assertEquals(-1, BuildingRenderGeometry.findBuildingIndex(14, 10, data, 1))
    }
}
