package com.xianxia.sect.core.util

/**
 * 宗门地图瓦片数据生成器。
 *
 * 使用位置哈希使装饰物呈自然聚集分布，3 种草变体 + 2 种树变体。
 * 纯函数，给定相同输入始终产生相同输出，便于测试。
 */
object SectMapTileGenerator {

    // 瓦片类型常量
    const val TILE_GROUND = 0
    const val TILE_GRASS_SMALL = 1
    const val TILE_GRASS_MEDIUM = 2
    const val TILE_GRASS_LARGE = 3
    const val TILE_TREE1 = 4
    const val TILE_TREE2 = 5
    const val TILE_BUILDING = 6

    /**
     * 基于位置哈希的宗门地图瓦片数据生成（确定性）。
     *
     * @param worldWidthCells 地图宽度（格数）
     * @param worldHeightCells 地图高度（格数）
     * @param decorationDensity 总装饰密度 (0.0~1.0)，默认 0.30
     */
    fun generateTileData(
        worldWidthCells: Int,
        worldHeightCells: Int,
        decorationDensity: Float = 0.30f
    ): Array<IntArray> {
        val data = Array(worldHeightCells) {
            IntArray(worldWidthCells) { TILE_GROUND }
        }
        val rng = java.util.Random(42)

        // 树：使用 5×5 网格簇生成（每簇随机偏移 0-2 格）
        val treeClusterProb = decorationDensity * 0.50f
        for (tx in 0 until worldWidthCells / 5) {
            for (ty in 0 until worldHeightCells / 5) {
                if (rng.nextFloat() < treeClusterProb) {
                    val offset = rng.nextInt(3)
                    val cx = (tx * 5 + offset).coerceIn(0, worldWidthCells - 1)
                    val cy = (ty * 5 + offset).coerceIn(0, worldHeightCells - 1)
                    data[cy][cx] = if (rng.nextFloat() < 0.5f) {
                        TILE_TREE1
                    } else {
                        TILE_TREE2
                    }
                }
            }
        }

        // 草：逐格决定，基于位置哈希产生自然分布
        // 使用 (1.0 - grassProb) 作为噪声阈值：density=0 时全跳过，density↑ 时跳过↓
        val grassProb = decorationDensity * 0.60f
        val grassThreshold = 1.0f - grassProb.coerceIn(0f, 1f)
        for (gx in 0 until worldWidthCells) {
            for (gy in 0 until worldHeightCells) {
                if (data[gy][gx] != TILE_GROUND) continue
                val noise = cellHash(gx, gy, 42)
                if (noise < grassThreshold) continue
                data[gy][gx] = when {
                    noise > 0.92f -> TILE_GRASS_LARGE
                    noise > 0.82f -> TILE_GRASS_MEDIUM
                    else -> TILE_GRASS_SMALL
                }
            }
        }
        return data
    }

    /** 位置哈希：相同 (x,y,seed) 产生相同 [0,1) 值。 */
    fun cellHash(x: Int, y: Int, seed: Int): Float {
        val h = (x * 374761393L + y * 668265263L + seed.toLong()).toInt()
        val hash = h * (h xor (h shl 13))
        return ((hash and 0x7FFFFFFF) % 10000) / 10000f
    }
}
