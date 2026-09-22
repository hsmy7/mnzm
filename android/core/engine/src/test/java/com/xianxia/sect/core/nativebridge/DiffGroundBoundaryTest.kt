package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.render.GroundBoundaryBridge
import com.xianxia.sect.core.render.GroundBoundaryGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

/**
 * DiffGroundBoundaryTest — 弯曲地皮轮廓合成跨语言差分对拍（地图边缘系统 v2）。
 *
 * 守护目标：C++ `gamecore/map/ground_boundary.h`（合成单一权威，经桌面 JNI
 * `nativeComposeGroundBoundary`）与 Kotlin `GroundBoundaryGenerator`（降级实现）
 * 输出一致——native 缺席的设备上地皮边界与生产路径几何同形。
 *
 * 对拍口径：头部/尺寸段精确相等；折线/掩码/mesh 逐元素比对——
 * 折线与坐标允许 0.05px 容差（两端 FP 求值序差异的几何下限，远低于
 * 可视阈值），掩码（离散分类）与复合布局偏移必须精确一致。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffGroundBoundaryTest {

    private fun cppCompose(cols: Int, rows: Int, tileSize: Int, depth: Float): FloatArray =
        DiffRngBridge.nativeComposeGroundBoundary(cols, rows, tileSize, depth)

    private fun kotlinCompose(cols: Int, rows: Int, tileSize: Int, depth: Float): FloatArray =
        GroundBoundaryGenerator.generate(cols, rows, tileSize, depth)

    // ── 头部与布局段精确一致 ─────────────────────────────────────

    @Test
    fun `header and section offsets match exactly`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val cols = GameConfig.SectMap.WORLD_WIDTH_CELLS
        val rows = GameConfig.SectMap.WORLD_HEIGHT_CELLS
        val tile = GameConfig.SectMap.TILE_SIZE
        val depth = GroundBoundaryBridge.BOTTOM_DEPTH_PX
        val cpp = cppCompose(cols, rows, tile, depth)
        val kt = kotlinCompose(cols, rows, tile, depth)
        assertEquals(cpp.size, kt.size)
        for (i in 0 until GroundBoundaryBridge.Header.FLOATS) {
            assertEquals("header[$i]", cpp[i], kt[i], 0.0f)
        }
        // 折线点数与段偏移（float 下标）跨端逐位一致
        assertEquals(cpp[GroundBoundaryBridge.Header.Field.POLY_COUNT], kt[GroundBoundaryBridge.Header.Field.POLY_COUNT], 0.0f)
        assertEquals(cpp[GroundBoundaryBridge.Header.Field.MASK_OFFSET], kt[GroundBoundaryBridge.Header.Field.MASK_OFFSET], 0.0f)
        assertEquals(cpp[GroundBoundaryBridge.Header.Field.GROUND_MESH_OFFSET], kt[GroundBoundaryBridge.Header.Field.GROUND_MESH_OFFSET], 0.0f)
        assertEquals(cpp[GroundBoundaryBridge.Header.Field.GROUND_MESH_COUNT], kt[GroundBoundaryBridge.Header.Field.GROUND_MESH_COUNT], 0.0f)
        assertEquals(cpp[GroundBoundaryBridge.Header.Field.BOTTOM_MESH_OFFSET], kt[GroundBoundaryBridge.Header.Field.BOTTOM_MESH_OFFSET], 0.0f)
        assertEquals(cpp[GroundBoundaryBridge.Header.Field.BOTTOM_MESH_COUNT], kt[GroundBoundaryBridge.Header.Field.BOTTOM_MESH_COUNT], 0.0f)
    }

    // ── 折线（世界像素几何，0.05px 容差）─────────────────────────

    @Test
    fun `polyline matches within tolerance across sizes`() {
        assumeTrue(DiffRngBridge.isAvailable())
        for (size in intArrayOf(48, 128)) {
            val cpp = cppCompose(size, size, 48, GroundBoundaryBridge.BOTTOM_DEPTH_PX)
            val kt = kotlinCompose(size, size, 48, GroundBoundaryBridge.BOTTOM_DEPTH_PX)
            val header = GroundBoundaryBridge.Header.FLOATS
            val polyFloats = cpp[GroundBoundaryBridge.Header.Field.MASK_OFFSET].toInt() - header
            assertEquals("size=$size polyline float count", polyFloats, kt[GroundBoundaryBridge.Header.Field.MASK_OFFSET].toInt() - header)
            for (i in 0 until polyFloats) {
                val d = abs(cpp[header + i] - kt[header + i])
                assertTrue("size=$size poly[$i] Δ=$d", d <= 0.05f)
            }
        }
    }

    // ── 掩码逐字节精确一致（离散分类不容漂移）────────────────────

    @Test
    fun `tile mask matches exactly on production map`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val cols = GameConfig.SectMap.WORLD_WIDTH_CELLS
        val rows = GameConfig.SectMap.WORLD_HEIGHT_CELLS
        val tile = GameConfig.SectMap.TILE_SIZE
        val cpp = cppCompose(cols, rows, tile, GroundBoundaryBridge.BOTTOM_DEPTH_PX)
        val kt = kotlinCompose(cols, rows, tile, GroundBoundaryBridge.BOTTOM_DEPTH_PX)
        val maskOffset = cpp[GroundBoundaryBridge.Header.Field.MASK_OFFSET].toInt()
        val meshOffset = cpp[GroundBoundaryBridge.Header.Field.GROUND_MESH_OFFSET].toInt()
        for (i in maskOffset until meshOffset) {
            assertEquals("mask[$i]", cpp[i], kt[i], 0.0f)
        }
        // 可建区抽样：bit0 恒置位（两端同一不变式）
        val ring = GameConfig.SectMap.BORDER_TREE_RING
        for (cell in intArrayOf(ring * cols + ring, rows / 2 * cols + cols / 2, (rows - ring - 1) * cols + (cols - ring - 1))) {
            assertEquals(1f, cpp[maskOffset + cell] % 2f, 0.0f)
            assertEquals(1f, kt[maskOffset + cell] % 2f, 0.0f)
        }
    }

    // ── mesh 顶点流（0.05px 容差；耳切次序两端一致时逐位同）──────

    @Test
    fun `ground and bottom meshes match within tolerance`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val cols = GameConfig.SectMap.WORLD_WIDTH_CELLS
        val rows = GameConfig.SectMap.WORLD_HEIGHT_CELLS
        val cpp = cppCompose(cols, rows, 48, GroundBoundaryBridge.BOTTOM_DEPTH_PX)
        val kt = kotlinCompose(cols, rows, 48, GroundBoundaryBridge.BOTTOM_DEPTH_PX)
        compareRange(cpp, kt, GroundBoundaryBridge.Header.Field.GROUND_MESH_OFFSET, "ground")
        compareRange(cpp, kt, GroundBoundaryBridge.Header.Field.BOTTOM_MESH_OFFSET, "bottom")
    }

    /** 位置/UV 容差比对（顶点色恒 1，精确比对） */
    private fun compareRange(cpp: FloatArray, kt: FloatArray, offsetField: Int, tag: String) {
        val offset = cpp[offsetField].toInt()
        val count = cpp[offsetField + 1].toInt()
        assertEquals("$tag count", count, kt[offsetField + 1].toInt())
        for (i in 0 until count) {
            val v = offset + i
            if (i % 8 >= 4) {
                assertEquals("$tag[$v] color", cpp[v], kt[v], 0.0f)
            } else {
                val d = abs(cpp[v] - kt[v])
                assertTrue("$tag[$v] Δ=$d", d <= 0.05f)
            }
        }
    }
}
