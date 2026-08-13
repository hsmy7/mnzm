package com.xianxia.sect.core.util

import com.xianxia.sect.core.render.SpriteAtlasDef

/**
 * 宗门地图瓦片数据生成器。
 *
 * 使用位置哈希使装饰物呈自然聚集分布，3 种草变体 + 2 种树变体。
 * 纯函数，给定相同输入始终产生相同输出，便于测试。
 */
object SectMapTileGenerator {

    // 瓦片类型常量（2026-08-13 起由 SpriteAtlasDef.TileType.index 生成提供——
    // 与 C++ TextureAtlas.h TILE_* 定义同源，双端索引单一数据源）
    val TILE_GROUND = SpriteAtlasDef.TileType.GROUND.index
    val TILE_GRASS_SMALL = SpriteAtlasDef.TileType.GRASS_SMALL.index
    val TILE_GRASS_MEDIUM = SpriteAtlasDef.TileType.GRASS_MEDIUM.index
    val TILE_GRASS_LARGE = SpriteAtlasDef.TileType.GRASS_LARGE.index
    val TILE_TREE1 = SpriteAtlasDef.TileType.TREE1.index
    val TILE_TREE2 = SpriteAtlasDef.TileType.TREE2.index
    val TILE_BUILDING = SpriteAtlasDef.TileType.TILE_BUILDING.index
    val TILE_GROUND_V2 = SpriteAtlasDef.TileType.GROUND_V2.index   // 地面变体2（与 TILE_GROUND 随机混用）

    /**
     * 基于位置哈希的宗门地图瓦片数据生成（确定性）。
     *
     * @param worldWidthCells 地图宽度（格数）
     * @param worldHeightCells 地图高度（格数）
     * @param decorationDensity 总装饰密度 (0.0~1.0)，默认 0.18
     * @param worldSeed 世界随机种子，不同种子产生不同地图分布；默认 0 保持向后兼容
     */
    fun generateTileData(
        worldWidthCells: Int,
        worldHeightCells: Int,
        decorationDensity: Float = 0.18f,
        worldSeed: Int = 0,
        borderTreeRing: Int = 0
    ): Array<IntArray> {
        val data = Array(worldHeightCells) {
            IntArray(worldWidthCells) { TILE_GROUND }
        }
        placeGrassPatches(data, worldWidthCells, worldHeightCells, decorationDensity, worldSeed)
        placeTreeClusters(data, worldWidthCells, worldHeightCells, decorationDensity, worldSeed)
        mixGroundVariants(data, worldWidthCells, worldHeightCells, worldSeed)
        if (borderTreeRing > 0) {
            placeBorderTrees(data, worldWidthCells, worldHeightCells, borderTreeRing)
        }
        return data
    }

    /**
     * 将地图四周 [ring] 格强制覆盖为树木，形成不可建造的边界。
     * 在全部过程化装饰之后运行，确保覆盖草地/树木/地面变体。
     * 使用棋盘格交替 TILE_TREE1 / TILE_TREE2。
     */
    private fun placeBorderTrees(
        data: Array<IntArray>, w: Int, h: Int, ring: Int
    ) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x < ring || x >= w - ring || y < ring || y >= h - ring) {
                    data[y][x] = if ((x + y) % 2 == 0) TILE_TREE1 else TILE_TREE2
                }
            }
        }
    }

    /**
     * 草装饰：成片草滩（平滑噪声 8×8 地块）。
     * 在噪声确定的草地斑块内密集长草，形成自然草滩。
     */
    private fun placeGrassPatches(
        data: Array<IntArray>, w: Int, h: Int, density: Float, worldSeed: Int = 0
    ) {
        val fill = (density * 0.80f).coerceIn(0f, 1f)
        val threshold = 1.0f - fill
        for (gx in 0 until w) {
            for (gy in 0 until h) {
                if (data[gy][gx] != TILE_GROUND) continue
                if (smoothNoise(gx, gy, 8, 42 xor worldSeed) < threshold) continue
                if (cellHash(gx, gy, 43 xor worldSeed) >= 0.80f) continue
                data[gy][gx] = when (cellHash(gx, gy, 44 xor worldSeed)) {
                    in 0.93f..1.0f -> TILE_GRASS_LARGE
                    in 0.80f..0.93f -> TILE_GRASS_MEDIUM
                    else -> TILE_GRASS_SMALL
                }
            }
        }
    }

    /**
     * 树装饰：稀疏树丛（平滑噪声 12×12 地块）。
     * 树在更大尺度上成簇，密度低于草地。
     */
    private fun placeTreeClusters(
        data: Array<IntArray>, w: Int, h: Int, density: Float, worldSeed: Int = 0
    ) {
        val fill = (density * 0.35f).coerceIn(0f, 1f)
        val threshold = 1.0f - fill
        for (gx in 0 until w) {
            for (gy in 0 until h) {
                if (data[gy][gx] != TILE_GROUND) continue
                if (smoothNoise(gx, gy, 12, 101 xor worldSeed) < threshold) continue
                if (cellHash(gx, gy, 45 xor worldSeed) >= 0.35f) continue
                data[gy][gx] = if (cellHash(gx, gy, 46 xor worldSeed) < 0.5f)
                    TILE_TREE1 else TILE_TREE2
            }
        }
    }

    /**
     * 地面变体混合：约 30% 的 TILE_GROUND 改为 TILE_GROUND_V2。
     * 双线性插值平滑噪声，产生自然地块而非噪点。
     */
    private fun mixGroundVariants(data: Array<IntArray>, w: Int, h: Int, worldSeed: Int = 0) {
        for (gx in 0 until w) {
            for (gy in 0 until h) {
                if (data[gy][gx] == TILE_GROUND &&
                    smoothNoise(gx, gy, 6, 999 xor worldSeed) < 0.3f) {
                    data[gy][gx] = TILE_GROUND_V2
                }
            }
        }
    }

    /**
     * 平滑噪声：在粗网格上采样 cellHash 后做双线性插值 + smoothstep。
     *
     * @param scale 特征尺度（= 一个"地块"的格数），默认 6
     * @param seed 噪声种子
     * @return [0,1) 连续值，相邻格变化平滑
     */
    fun smoothNoise(x: Int, y: Int, scale: Int, seed: Int): Float {
        val sx = x.toFloat() / scale
        val sy = y.toFloat() / scale
        val ix = sx.toInt()
        val iy = sy.toInt()
        val fx = sx - ix          // 格内偏移 [0,1)
        val fy = sy - iy

        // Smoothstep：让插值曲线更自然（S 形而非线性）
        val sx2 = fx * fx * (3f - 2f * fx)
        val sy2 = fy * fy * (3f - 2f * fy)

        // 4 个粗网格角点的噪声值
        val v00 = cellHash(ix,     iy,     seed)
        val v10 = cellHash(ix + 1, iy,     seed)
        val v01 = cellHash(ix,     iy + 1, seed)
        val v11 = cellHash(ix + 1, iy + 1, seed)

        // 双线性插值
        return v00 * (1f - sx2) * (1f - sy2) +
               v10 * sx2 * (1f - sy2) +
               v01 * (1f - sx2) * sy2 +
               v11 * sx2 * sy2
    }

    /** 位置哈希：相同 (x,y,seed) 产生相同 [0,1) 值。 */
    fun cellHash(x: Int, y: Int, seed: Int): Float {
        val h = (x * 374761393L + y * 668265263L + seed.toLong()).toInt()
        val hash = h * (h xor (h shl 13))
        return ((hash and 0x7FFFFFFF) % 10000) / 10000f
    }

    /**
     * 计算 Autotile 8-bit bitmask（用于未来 Biome 过渡）。
     *
     * 8-bit blob tile 算法：
     * - 检查 8 个邻居，构建位掩码
     * - 角邻居规则：对角线仅在两卡相邻也匹配时计入
     *
     * @return Array<ByteArray> 每格的 bitmask 值（0-255）
     */
    fun computeAutotileBitmask(tileData: Array<IntArray>): Array<ByteArray> {
        val h = tileData.size
        if (h == 0) return emptyArray()
        val w = tileData[0].size

        val mask = Array(h) { ByteArray(w) { 0 } }

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val here = tileData[y][x]
                // 树和建筑不参与 autotile 过渡
                if (here == TILE_BUILDING || here == TILE_TREE1 || here == TILE_TREE2) continue

                val n  = if (tileData[y-1][x]   == here) 1 else 0
                val s  = if (tileData[y+1][x]   == here) 1 else 0
                val w  = if (tileData[y][x-1]   == here) 1 else 0
                val e  = if (tileData[y][x+1]   == here) 1 else 0

                // 角邻居仅当两卡相邻也匹配时计入（防止对角误连）
                val nw = if (tileData[y-1][x-1] == here && n == 1 && w == 1) 1 else 0
                val ne = if (tileData[y-1][x+1] == here && n == 1 && e == 1) 1 else 0
                val sw = if (tileData[y+1][x-1] == here && s == 1 && w == 1) 1 else 0
                val se = if (tileData[y+1][x+1] == here && s == 1 && e == 1) 1 else 0

                mask[y][x] = (n or (ne shl 1) or (e shl 2) or (se shl 3) or
                              (s shl 4) or (sw shl 5) or (w shl 6) or (nw shl 7)).toByte()
            }
        }
        return mask
    }
}
