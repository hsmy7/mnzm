package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.SectMapTileGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffSectTerrainTest — 宗门地图地形生成跨语言差分对拍。
 *
 * 守护目标：C++ `gamecore/map/terrain.h`（地形生成单一权威，JNI
 * nativeGenerateSectTerrain）与 Kotlin `SectMapTileGenerator`（降级/JVM 基线）
 * **全数组逐位一致**——生成真源迁移不改变任何玩家可见地图。
 *
 * 覆盖：多种子全数组对拍（含负种子/int 极值）、生产配置冒烟（128² 生产门楼盒）、
 * cellHash/smoothNoise 逐点位级探针（负坐标截断语义含内）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffSectTerrainTest {

    private fun kotlinFlat(
        seed: Int, w: Int, h: Int, density: Float = 0.18f, ring: Int = 0
    ): IntArray = SectMapTileGenerator.generateTileData(w, h, density, seed, ring)
        .flatMap { it.toList() }.toIntArray()

    private fun cppFlat(
        seed: Int, w: Int, h: Int, density: Float = 0.18f, ring: Int = 0
    ): IntArray {
        val cfg = GameConfig.SectMap
        return DiffRngBridge.nativeGenerateSectTerrain(
            seed = seed, width = w, height = h, density = density, borderTreeRing = ring,
            gateX = cfg.GATE_X, gateY = cfg.GATE_Y,
            gateWidth = cfg.GATE_WIDTH, gateHeight = cfg.GATE_HEIGHT,
            gateSpriteY = cfg.GATE_SPRITE_Y
        ) ?: error("桌面 JNI 返回 null（width/height 非法？）")
    }

    @Test
    fun `full map matches Kotlin across seeds`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 种子面：0（向后兼容默认）/ 常规 / 派生式异号 / 负数 / Int 极值
        for (seed in intArrayOf(0, 42, 123456789, -1, Int.MIN_VALUE)) {
            assertArrayEquals("seed=$seed", kotlinFlat(seed, 48, 48), cppFlat(seed, 48, 48))
        }
    }

    @Test
    fun `production config smoke matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 生产全参数：128² + 0.18f + 边界树环 3 + 生产门楼盒
        for (seed in intArrayOf(0, 42, -99)) {
            val w = GameConfig.SectMap.WORLD_WIDTH_CELLS
            val h = GameConfig.SectMap.WORLD_HEIGHT_CELLS
            assertArrayEquals(
                "seed=$seed",
                kotlinFlat(seed, w, h, 0.18f, GameConfig.SectMap.BORDER_TREE_RING),
                cppFlat(seed, w, h, 0.18f, GameConfig.SectMap.BORDER_TREE_RING)
            )
        }
    }

    @Test
    fun `cellHash matches Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 含负坐标（Kotlin toInt() 截断朝零语义——C++ static_cast<int> 同式）
        for (seed in intArrayOf(0, 42, -7)) {
            for (y in -4..40) {
                for (x in -4..40) {
                    assertEquals(
                        "cellHash($x,$y,seed=$seed)",
                        SectMapTileGenerator.cellHash(x, y, seed),
                        DiffRngBridge.nativeSectCellHash(x, y, seed)
                    )
                }
            }
        }
    }

    @Test
    fun `smoothNoise matches Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 双尺度（8=草滩 12=树丛）× 含 42^worldSeed / 101^worldSeed 的生产种子混合式
        for (seed in intArrayOf(0, 42, -1, 42 xor -1, 101 xor 42)) {
            for (scale in intArrayOf(8, 12)) {
                assertNoiseGridMatches(seed, scale)
            }
        }
    }

    /** 0..140 网格逐点对拍（嵌套深度收敛：seed×scale 外层枚举 + 网格扫描拆出） */
    private fun assertNoiseGridMatches(seed: Int, scale: Int) {
        for (y in 0..140) {
            for (x in 0..140) {
                assertEquals(
                    "smoothNoise($x,$y,scale=$scale,seed=$seed)",
                    SectMapTileGenerator.smoothNoise(x, y, scale, seed),
                    DiffRngBridge.nativeSectSmoothNoise(x, y, scale, seed)
                )
            }
        }
    }
}
