package com.xianxia.sect.core.util

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.render.GroundBoundaryBridge
import com.xianxia.sect.core.render.GroundBoundaryGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GridSystemGroundBoundaryTest — 弯曲地皮轮廓放置校验（地图边缘系统 v2）。
 *
 * 锁定「**弯曲的边缘草地不可建造**」：轮廓外（掩码缺 bit0）的占地格 →
 * `OutOfBounds` → 拖拽占地框红色（resolvePreviewBoxValid → boxValid=false →
 * overlay 绿/红分支）+ 确认/落子被拒。全链与 C++ 事务臂（building_tx 树环复拒）
 * 双保险。
 *
 * 核心不变式（第一阶段）：轮廓钳在 BORDER_TREE_RING 树环带内 ⇒ **掩码判定与
 * 矩形环判定逐格等价**——本测试对生产地图 128² 全部格逐格对拍（16384 格零漂移），
 * 行为零变化；第二阶段曲线加深后此对拍即红，届时掩码判定自动接管禁建边界。
 */
class GridSystemGroundBoundaryTest {

    private val cols = GameConfig.SectMap.WORLD_WIDTH_CELLS
    private val rows = GameConfig.SectMap.WORLD_HEIGHT_CELLS
    private val tile = GameConfig.SectMap.TILE_SIZE
    private val ring = GameConfig.SectMap.BORDER_TREE_RING

    /** 生产同参掩码（合成器 → 桥提取） */
    private val mask: ByteArray by lazy {
        val composite = GroundBoundaryGenerator.generate(cols, rows, tile, GroundBoundaryBridge.BOTTOM_DEPTH_PX)
        GroundBoundaryBridge.tileMaskOf(composite)
            ?: error("生产参数下掩码提取不得为 null")
    }

    private val rectOnly = GridSystem(tile, cols, rows, buildableBorder = ring)
    private val withMask = GridSystem(tile, cols, rows, buildableBorder = ring, buildableMask = mask)

    private fun isValid(system: GridSystem, x: Int, y: Int, w: Int = 1, h: Int = 1): Boolean =
        system.validatePlacement(x, y, w, h) == GridSnapHelper.PlacementValidity.Valid

    // ── 核心不变式：掩码判定 ≡ 矩形判定（生产地图逐格对拍）──────────

    @Test
    fun `mask judgement is cell-for-cell identical to rect judgement on production map`() {
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val rectValid = isValid(rectOnly, x, y)
                val maskValid = isValid(withMask, x, y)
                assertEquals("cell ($x,$y) 两口径必须一致", rectValid, maskValid)
                if (rectValid) {
                    assertTrue(
                        "可建格 ($x,$y) 掩码必须 bit0=1（可建矩形 ⊆ 轮廓）",
                        mask[cy(x, y)].toInt() and GroundBoundaryBridge.MASK_BIT_QUAD != 0
                    )
                }
            }
        }
    }

    // ── 边缘草地（树环带内、曲线内）不可建 ─────────────────────────

    @Test
    fun `edge grass cells on all four sides are rejected`() {
        // 树环带中轴采样（该带即「边缘草地」——掩码 bit0=1 证明是草地而非天空）
        val edgeGrass = listOf(
            1 to rows / 2, cols / 2 to 1,          // 左、上
            cols - 2 to rows / 2, cols / 2 to rows - 2, // 右、下
            2 to 2, cols - 3 to 2, 2 to rows - 3, cols - 3 to rows - 3 // 四角区
        )
        for ((x, y) in edgeGrass) {
            assertTrue(
                "($x,$y) 必须在曲线内（是草地）",
                mask[cy(x, y)].toInt() and GroundBoundaryBridge.MASK_BIT_QUAD != 0
            )
            assertEquals(
                "边缘草地 ($x,$y) 必须判 OutOfBounds（红框 + 禁放）",
                GridSnapHelper.PlacementValidity.OutOfBounds,
                withMask.validatePlacement(x, y, 1, 1)
            )
        }
    }

    @Test
    fun `gate apron side grass is rejected`() {
        // 门楼清场区 ±1 列之外、同一底行的草地格（曲线钉边不豁免树环禁建）
        val gateX0 = (cols - GameConfig.SectMap.GATE_WIDTH) / 2
        for (x in intArrayOf(gateX0 - 3, gateX0 + GameConfig.SectMap.GATE_WIDTH + 2)) {
            assertEquals(
                "门楼两侧边缘草地 ($x,${rows - 2}) 必须拒绝",
                GridSnapHelper.PlacementValidity.OutOfBounds,
                withMask.validatePlacement(x, rows - 2, 1, 1)
            )
        }
    }

    // ── 多格建筑：任意占地格压到轮廓外即整单拒绝 ────────────────────

    @Test
    fun `multi-cell building touching border ring is rejected`() {
        // 4×4 建筑左缘压在第 2 列（树环带内）→ OutOfBounds
        assertEquals(
            GridSnapHelper.PlacementValidity.OutOfBounds,
            withMask.validatePlacement(2, rows / 2 - 2, 4, 4)
        )
        // 同尺寸完全落在可建区 → Valid
        assertEquals(
            GridSnapHelper.PlacementValidity.Valid,
            withMask.validatePlacement(3, rows / 2 - 2, 4, 4)
        )
    }

    // ── 可建区正常放置不受影响（掩码不误伤）────────────────────────

    @Test
    fun `buildable area corners and center remain valid`() {
        assertTrue(isValid(withMask, ring, ring, 4, 4))
        assertTrue(isValid(withMask, cols - ring - 4, rows - ring - 4, 4, 4))
        assertTrue(isValid(withMask, cols / 2 - 2, rows / 2 - 2, 4, 4))
    }

    // ── 掩码为空时行为与既有口径逐位一致（向后兼容）─────────────────

    @Test
    fun `null mask keeps legacy rect-only behaviour`() {
        val legacy = GridSystem(tile, cols, rows, buildableBorder = ring)
        assertEquals(
            legacy.validatePlacement(1, rows / 2, 1, 1),
            withMask.validatePlacement(1, rows / 2, 1, 1)
        )
        assertEquals(
            legacy.validatePlacement(ring, ring, 4, 4),
            withMask.validatePlacement(ring, ring, 4, 4)
        )
    }

    /** 掩码行主序下标（col,row） */
    private fun cy(x: Int, y: Int): Int = y * cols + x
}
