package com.xianxia.sect.core.util

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.render.SpriteAtlasDef

/**
 * 宗门地图瓦片数据生成器。
 *
 * 使用位置哈希使装饰物呈自然聚集分布，4 种草变体 + 3 种石头 + 2 种树变体。
 * 纯函数，给定相同输入始终产生相同输出，便于测试。
 *
 * 与 C++ `gamecore/map/terrain.h`（单一权威）**位级等价**：装饰 pass 顺序
 * （草 → 石 → 树 → 边界树环 → 门楼清场）、hash 种子混合式与比较链逐项一致，
 * 由 `DiffSectTerrainTest` 全数组对拍守护——任一侧改判定序即变红。
 */
object SectMapTileGenerator {

    // 瓦片类型常量（由 SpriteAtlasDef.TileType.index 生成提供——
    // 与 C++ TextureAtlas.h TILE_* 定义同源，双端索引单一数据源）
    val TILE_GROUND = SpriteAtlasDef.TileType.GROUND.index
    val TILE_GRASS1 = SpriteAtlasDef.TileType.GRASS1.index
    val TILE_GRASS2 = SpriteAtlasDef.TileType.GRASS2.index
    val TILE_GRASS3 = SpriteAtlasDef.TileType.GRASS3.index
    val TILE_GRASS4 = SpriteAtlasDef.TileType.GRASS4.index
    val TILE_STONE1 = SpriteAtlasDef.TileType.STONE1.index
    val TILE_STONE2 = SpriteAtlasDef.TileType.STONE2.index
    val TILE_STONE3 = SpriteAtlasDef.TileType.STONE3.index
    val TILE_TREE1 = SpriteAtlasDef.TileType.TREE1.index
    val TILE_TREE2 = SpriteAtlasDef.TileType.TREE2.index
    val TILE_BUILDING = SpriteAtlasDef.TileType.TILE_BUILDING.index

    /**
     * 基于位置哈希的宗门地图瓦片数据生成（确定性）。
     *
     * @param worldWidthCells 地图宽度（格数）
     * @param worldHeightCells 地图高度（格数）
     * @param decorationDensity 总装饰密度 (0.0~1.0)，默认 0.18
     * @param worldSeed 世界随机种子，不同种子产生不同地图分布；默认 0 保持向后兼容
     * @param borderTreeRing 四周强制树木边界厚度（0 = 无）
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
        placeStoneScatter(data, worldWidthCells, worldHeightCells, decorationDensity, worldSeed)
        placeTreeClusters(data, worldWidthCells, worldHeightCells, decorationDensity, worldSeed)
        if (borderTreeRing > 0) {
            placeBorderTrees(data, worldWidthCells, worldHeightCells, borderTreeRing)
        }
        placeSectGateway(data, worldWidthCells, worldHeightCells)
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
                if (isBorderCell(x, y, w, h, ring)) {
                    data[y][x] = borderTileFor(x, y)
                }
            }
        }
    }

    /**
     * 边界环带判定：四周 [ring] 格内为边界树覆盖区。
     * 条件顺序与 Kotlin/C++ 位级对拍基准一致，不得调整求值序。
     */
    private fun isBorderCell(x: Int, y: Int, w: Int, h: Int, ring: Int): Boolean =
        x < ring || x >= w - ring || y < ring || y >= h - ring

    /** 边界树变体：棋盘格交替 TREE1/TREE2 */
    private fun borderTileFor(x: Int, y: Int): Int =
        if ((x + y) % 2 == 0) TILE_TREE1 else TILE_TREE2

    /**
     * 宗门入口固定结构区域清理：把底部中央门楼精灵包围盒及其左右紧邻各 1 列清为地面，
     * 并清掉右列外侧的树格（2×2 树精灵向左越界会盖住右侧空白列）。门楼精灵由建筑层
     * 固定条目渲染（FixedSectGateway）。确定性、与 seed 无关、每宗地图一致。
     * 小地图（测试）尺寸不足时跳过。
     */
    private fun placeSectGateway(data: Array<IntArray>, w: Int, h: Int) {
        val cfg = GameConfig.SectMap
        val maxX = cfg.GATE_X + cfg.GATE_WIDTH
        val maxY = cfg.GATE_Y + cfg.GATE_HEIGHT
        // 清空范围：门楼 + 左右各 1 空白列 + 右侧越界树列（x = GATE_X-1 .. maxX+1，含）
        val clearFrom = (cfg.GATE_X - 1).coerceAtLeast(0)
        val clearTo = (maxX + 2).coerceAtMost(w)
        if (clearFrom >= clearTo || h < maxY) return
        for (y in cfg.GATE_SPRITE_Y until maxY) {
            for (x in clearFrom until clearTo) {
                data[y][x] = TILE_GROUND
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
        val fill = (density * GRASS_DENSITY_FACTOR).coerceIn(0f, 1f)
        val threshold = 1.0f - fill
        for (gx in 0 until w) {
            for (gy in 0 until h) {
                decorateGrassCell(data, gx, gy, threshold, worldSeed)
            }
        }
    }

    /**
     * 石堆装饰：稀疏岩石散布（平滑噪声 14×14 地块，斑块内仅 [STONE_SCATTER_LIMIT] 概率）。
     * 只落在裸地（草滩之后运行，不覆盖草/树）——岩石成簇出现在空旷地面。
     */
    private fun placeStoneScatter(
        data: Array<IntArray>, w: Int, h: Int, density: Float, worldSeed: Int = 0
    ) {
        val fill = (density * STONE_DENSITY_FACTOR).coerceIn(0f, 1f)
        val threshold = 1.0f - fill
        for (gx in 0 until w) {
            for (gy in 0 until h) {
                decorateStoneCell(data, gx, gy, threshold, worldSeed)
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
        val fill = (density * TREE_DENSITY_FACTOR).coerceIn(0f, 1f)
        val threshold = 1.0f - fill
        for (gx in 0 until w) {
            for (gy in 0 until h) {
                decorateTreeCell(data, gx, gy, threshold, worldSeed)
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
     * - 实体障碍瓦片（石/树/建筑占位，[SpriteAtlasDef.isSolidTile]）不参与过渡
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
                // 实体障碍（石/树/建筑）不参与 autotile 过渡
                if (SpriteAtlasDef.isSolidTile(here)) continue

                mask[y][x] = autotileMaskFor(tileData, y, x, here)
            }
        }
        return mask
    }
}

/** 单格 autotile 位掩码：8 邻居比较与位组装原样搬移，位级输出不变 */
private fun autotileMaskFor(tileData: Array<IntArray>, y: Int, x: Int, here: Int): Byte {
    val n  = if (tileData[y-1][x]   == here) 1 else 0
    val s  = if (tileData[y+1][x]   == here) 1 else 0
    val w  = if (tileData[y][x-1]   == here) 1 else 0
    val e  = if (tileData[y][x+1]   == here) 1 else 0

    // 角邻居仅当两卡相邻也匹配时计入（防止对角误连）
    val nw = cornerBit(tileData[y-1][x-1] == here, n == 1, w == 1)
    val ne = cornerBit(tileData[y-1][x+1] == here, n == 1, e == 1)
    val sw = cornerBit(tileData[y+1][x-1] == here, s == 1, w == 1)
    val se = cornerBit(tileData[y+1][x+1] == here, s == 1, e == 1)

    return (n or (ne shl 1) or (e shl 2) or (se shl 3) or
                  (s shl 4) or (sw shl 5) or (w shl 6) or (nw shl 7)).toByte()
}

/** 角邻居位：对角匹配且两正邻均匹配才计 1（防止对角误连） */
private fun cornerBit(diagonalMatches: Boolean, adjacent1Matches: Boolean, adjacent2Matches: Boolean): Int =
    if (diagonalMatches && adjacent1Matches && adjacent2Matches) 1 else 0

// ============================================================
// 装饰 pass 参数常量（与 C++ gamecore/map/terrain.h 同值——
// 位级对拍红线：改动此处必须同步 C++ 侧，否则 DiffSectTerrainTest 变红）
// ============================================================

/** 草滩密度系数（总密度中用于草的比例） */
private const val GRASS_DENSITY_FACTOR = 0.80f

/** 草滩噪声尺度 / 噪声种子 / 散布种子 / 变体种子 / 散布上限 */
private const val GRASS_NOISE_SCALE = 8
private const val GRASS_NOISE_SEED = 42
private const val GRASS_SCATTER_SEED = 43
private const val GRASS_VARIANT_SEED = 44
private const val GRASS_SCATTER_LIMIT = 0.80f

/** 草簇变体抽取阈值（4 等分：[0,0.25)=变体1 … [0.75,1)=变体4） */
private const val GRASS_VARIANT_STEP = 0.25f

/** 石堆密度系数（总密度中用于石头的比例，低于树） */
private const val STONE_DENSITY_FACTOR = 0.12f

/** 石堆噪声尺度 / 噪声种子 / 散布种子 / 变体种子 / 散布上限 */
private const val STONE_NOISE_SCALE = 14
private const val STONE_NOISE_SEED = 202
private const val STONE_SCATTER_SEED = 203
private const val STONE_VARIANT_SEED = 204
private const val STONE_SCATTER_LIMIT = 0.35f

/** 石堆变体抽取阈值（三等分：[0,0.34)=变体1、[0.34,0.67)=变体2、其余=变体3） */
private const val STONE_VARIANT_STEP_LOW = 0.34f
private const val STONE_VARIANT_STEP_HIGH = 0.67f

/** 树丛密度系数 / 噪声尺度 / 各阶段种子 / 散布上限 / 变体分界 */
private const val TREE_DENSITY_FACTOR = 0.35f
private const val TREE_NOISE_SCALE = 12
private const val TREE_NOISE_SEED = 101
private const val TREE_SCATTER_SEED = 45
private const val TREE_VARIANT_SEED = 46
private const val TREE_SCATTER_LIMIT = 0.35f
private const val TREE_VARIANT_SPLIT = 0.5f

/** 单格草装饰：非裸地/噪声未达阈值/装饰 hash 超限保持裸地，命中则按变体 hash 四选一
 *（&& 短路序固定——位级对拍红线，调整求值序即 DiffSectTerrainTest 变红）。 */
private fun decorateGrassCell(data: Array<IntArray>, gx: Int, gy: Int, threshold: Float, worldSeed: Int) {
    val shouldDecorate = data[gy][gx] == SectMapTileGenerator.TILE_GROUND &&
        SectMapTileGenerator.smoothNoise(gx, gy, GRASS_NOISE_SCALE,
            GRASS_NOISE_SEED xor worldSeed) >= threshold &&
        SectMapTileGenerator.cellHash(gx, gy, GRASS_SCATTER_SEED xor worldSeed) <
        GRASS_SCATTER_LIMIT
    if (!shouldDecorate) return
    val pick = SectMapTileGenerator.cellHash(gx, gy, GRASS_VARIANT_SEED xor worldSeed)
    data[gy][gx] = when {
        pick < GRASS_VARIANT_STEP -> SectMapTileGenerator.TILE_GRASS1
        pick < GRASS_VARIANT_STEP * 2 -> SectMapTileGenerator.TILE_GRASS2
        pick < GRASS_VARIANT_STEP * 3 -> SectMapTileGenerator.TILE_GRASS3
        else -> SectMapTileGenerator.TILE_GRASS4
    }
}

/** 单格石堆装饰：非裸地/噪声未达阈值/散布 hash 超限保持裸地，命中则按变体 hash 三选一
 *（在草滩之后运行——只落在裸地，不覆盖草/树）。 */
private fun decorateStoneCell(data: Array<IntArray>, gx: Int, gy: Int, threshold: Float, worldSeed: Int) {
    val shouldPlace = data[gy][gx] == SectMapTileGenerator.TILE_GROUND &&
        SectMapTileGenerator.smoothNoise(gx, gy, STONE_NOISE_SCALE,
            STONE_NOISE_SEED xor worldSeed) >= threshold &&
        SectMapTileGenerator.cellHash(gx, gy, STONE_SCATTER_SEED xor worldSeed) <
        STONE_SCATTER_LIMIT
    if (!shouldPlace) return
    val pick = SectMapTileGenerator.cellHash(gx, gy, STONE_VARIANT_SEED xor worldSeed)
    data[gy][gx] = when {
        pick < STONE_VARIANT_STEP_LOW -> SectMapTileGenerator.TILE_STONE1
        pick < STONE_VARIANT_STEP_HIGH -> SectMapTileGenerator.TILE_STONE2
        else -> SectMapTileGenerator.TILE_STONE3
    }
}

/** 单格树装饰：非裸地/噪声未达阈值/树 hash 超限保持裸地
 *（&& 短路序固定——位级对拍红线，调整求值序即 DiffSectTerrainTest 变红）。 */
private fun decorateTreeCell(data: Array<IntArray>, gx: Int, gy: Int, threshold: Float, worldSeed: Int) {
    val shouldPlant = data[gy][gx] == SectMapTileGenerator.TILE_GROUND &&
        SectMapTileGenerator.smoothNoise(gx, gy, TREE_NOISE_SCALE,
            TREE_NOISE_SEED xor worldSeed) >= threshold &&
        SectMapTileGenerator.cellHash(gx, gy, TREE_SCATTER_SEED xor worldSeed) <
        TREE_SCATTER_LIMIT
    if (!shouldPlant) return
    data[gy][gx] = if (SectMapTileGenerator.cellHash(gx, gy,
            TREE_VARIANT_SEED xor worldSeed) < TREE_VARIANT_SPLIT
    ) {
        SectMapTileGenerator.TILE_TREE1
    } else {
        SectMapTileGenerator.TILE_TREE2
    }
}
