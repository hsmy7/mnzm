package com.xianxia.sect.core.render

/**
 * 精灵在图集中的像素矩形（Vulkan UV 计算和 Canvas rect 共用）。
 */
data class SpriteRect(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * 渲染后端无关的绘制命令 — 统一描述一帧的绘制内容。
 * 由 Compose 层构建，Vulkan 和 Canvas 两路径均可消费。
 */
data class FrameDrawCommand(
    val cameraX: Float,
    val cameraY: Float,
    val scale: Float,
    val tileData: IntArray,
    val cols: Int,
    val rows: Int,
    val tileSize: Int,
    val buildings: List<BuildingDrawCmd>,
    val preview: PreviewDrawCmd?
)

data class BuildingDrawCmd(
    val gridX: Int,
    val gridY: Int,
    val width: Int,
    val height: Int,
    val nameIndex: Int
)

data class PreviewDrawCmd(
    val worldX: Float,
    val worldY: Float,
    val worldW: Float,
    val worldH: Float,
    val u0: Float,
    val v0: Float,
    val u1: Float,
    val v1: Float,
    val tintR: Float = 0.25f,
    val tintG: Float = 1.0f,
    val tintB: Float = 0.25f,
    val alpha: Float = 0.5f
)

/**
 * 统一精灵图集定义。
 *
 * 这是地面瓦片、装饰和建筑在图集中位置的唯一来源。
 * 布局与 C++ TextureAtlas.h 中的 MAP_SPRITES 定义一致。
 * 新增建筑类型时只需在此处添加，Vulkan/Canvas 两路径自动同步。
 */
object SpriteAtlasDef {
    const val ATLAS_W = 2048
    const val ATLAS_H = 2048
    const val TILE_SIZE = 64
    const val BUILDING_SIZE = 128

    // ============================================================
    // 瓦片类型定义
    // ============================================================

    /** 瓦片类型（与 C++ TextureAtlas.h MAP_SPRITES 索引一致） */
    enum class TileType(
        val index: Int,
        val rect: SpriteRect
    ) {
        GROUND(0, SpriteRect(0, 0, 64, 64)),
        GRASS_SMALL(1, SpriteRect(64, 0, 64, 64)),
        GRASS_MEDIUM(2, SpriteRect(128, 0, 64, 64)),
        GRASS_LARGE(3, SpriteRect(192, 0, 64, 64)),
        TREE1(4, SpriteRect(256, 0, 128, 128)),
        TREE2(5, SpriteRect(384, 0, 128, 128)),
        TILE_BUILDING(6, SpriteRect(0, 0, 64, 64)),
        GROUND_V2(7, SpriteRect(512, 0, 64, 64));

        companion object {
            private val BY_INDEX = values().associateBy { it.index }
            fun fromIndex(index: Int): TileType = BY_INDEX[index] ?: GROUND
        }
    }

    /**
     * 瓦片 UV 映射（归一化 0-1，用于 Vulkan 纹理采样）。
     * 与 C++ TextureAtlas.h 的 UV 计算一致。
     */
    val TILE_UV_MAP: FloatArray by lazy {
        val uv = FloatArray(TileType.values().size * 4)
        for (tile in TileType.values()) {
            val r = tile.rect
            val i = tile.index * 4
            uv[i] = r.x.toFloat() / ATLAS_W
            uv[i + 1] = r.y.toFloat() / ATLAS_H
            uv[i + 2] = (r.x + r.w).toFloat() / ATLAS_W
            uv[i + 3] = (r.y + r.h).toFloat() / ATLAS_H
        }
        uv
    }

    // ============================================================
    // 建筑定义
    // ============================================================

    /** 建筑名称（按图集排列顺序，与 C++ TextureAtlas.h MAP_SPRITES 一致） */
    val BUILDING_NAMES = listOf(
        "灵矿场", "灵植阁", "灵田", "炼丹炉", "锻造坊",
        "仓库", "藏经阁", "问道塔", "青云塔", "天枢殿",
        "执法堂", "任务阁", "巡视楼", "监牢",
        "单人住所", "中级单人住所", "多人住所", "血炼池"
    )

    /** 每行建筑数（图集行分布） */
    private val BUILDING_COLS_PER_ROW = intArrayOf(5, 5, 5, 3)

    /** 建筑名称 → 索引 */
    val BUILDING_NAME_INDEX: Map<String, Int> by lazy {
        BUILDING_NAMES.withIndex().associate { it.value to it.index }
    }

    /**
     * 占地尺寸（按 BUILDING_NAMES 索引，供渲染器查找占地面积用于地砖选择）。
     * 建筑数据数组中传递的是精灵比例尺寸（spriteWidth/spriteHeight），
     * 渲染器需通过此表获取占地尺寸来计算地砖索引。
     */
    val FOOTPRINT_BY_NAME_INDEX: Array<Pair<Int, Int>> = arrayOf(
        4 to 4,   // 0: 灵矿场
        4 to 4,   // 1: 灵植阁
        1 to 1,   // 2: 灵田
        4 to 3,   // 3: 炼丹炉
        6 to 4,   // 4: 锻造坊
        6 to 5,   // 5: 仓库
        6 to 4,   // 6: 藏经阁
        4 to 3,   // 7: 问道塔
        4 to 3,   // 8: 青云塔
        6 to 4,   // 9: 天枢殿
        6 to 4,   // 10: 执法堂
        4 to 3,   // 11: 任务阁
        4 to 4,   // 12: 巡视楼
        4 to 4,   // 13: 监牢
        4 to 4,   // 14: 单人住所
        6 to 6,   // 15: 中级单人住所
        6 to 4,   // 16: 多人住所
        2 to 2    // 17: 血炼池
    )

    /**
     * 建筑 UV 映射（归一化 0-1，与 C++ NativeBridge drawAllTiles 的
     * buildingUVMap 参数一致）。
     *
     * 图集行分布：
     *   Row 1 (y=128): 5 sprites (indices 0-4)
     *   Row 2 (y=256): 5 sprites (indices 5-9)
     *   Row 3 (y=384): 5 sprites (indices 10-14)
     *   Row 4 (y=512): 3 sprites (indices 15-17)
     */
    val BUILDING_UV_MAP: FloatArray by lazy {
        val uvs = FloatArray(BUILDING_NAMES.size * 4)
        var idx = 0
        for (rowIndex in BUILDING_COLS_PER_ROW.indices) {
            for (col in 0 until BUILDING_COLS_PER_ROW[rowIndex]) {
                val px = col * BUILDING_SIZE
                val py = BUILDING_SIZE + rowIndex * BUILDING_SIZE
                val i = idx * 4
                uvs[i] = px.toFloat() / ATLAS_W
                uvs[i + 1] = py.toFloat() / ATLAS_H
                uvs[i + 2] = (px + BUILDING_SIZE).toFloat() / ATLAS_W
                uvs[i + 3] = (py + BUILDING_SIZE).toFloat() / ATLAS_H
                idx++
            }
        }
        uvs
    }

    /**
     * 获取建筑在图集中的像素矩形（供 Canvas 渲染器使用）。
     * @param nameIndex 建筑索引 (0-17)
     */
    fun buildingRect(nameIndex: Int): SpriteRect {
        var idx = 0
        for (rowIndex in BUILDING_COLS_PER_ROW.indices) {
            for (col in 0 until BUILDING_COLS_PER_ROW[rowIndex]) {
                if (idx == nameIndex) {
                    return SpriteRect(
                        col * BUILDING_SIZE,
                        BUILDING_SIZE + rowIndex * BUILDING_SIZE,
                        BUILDING_SIZE,
                        BUILDING_SIZE
                    )
                }
                idx++
            }
        }
        // 越界回退
        return SpriteRect(0, BUILDING_SIZE, BUILDING_SIZE, BUILDING_SIZE)
    }

    // ============================================================
    // 地砖类型定义
    // ============================================================

    /** 地砖精灵尺寸（原始像素，与建筑占地一致） */
    enum class FloorTileType(
        val key: String,
        val gridW: Int, val gridH: Int,
        val pixelRect: SpriteRect
    ) {
        TILE_2x2("floor_tile_2x2", 2, 2, SpriteRect(0,   640, 128, 128)),
        TILE_2x3("floor_tile_2x3", 2, 3, SpriteRect(0,   768, 128, 192)),
        TILE_3x2("floor_tile_3x2", 3, 2, SpriteRect(0,   960, 192, 128)),
        TILE_3x3("floor_tile_3x3", 3, 3, SpriteRect(192, 960, 192, 192));
    }

    /** 地砖 UV 映射（归一化 0-1，用于 Vulkan 纹理采样） */
    val FLOOR_TILE_UV_MAP: FloatArray by lazy {
        val uv = FloatArray(FloorTileType.values().size * 4)
        for (tile in FloorTileType.values()) {
            val r = tile.pixelRect
            val i = tile.ordinal * 4
            uv[i] = r.x.toFloat() / ATLAS_W
            uv[i + 1] = r.y.toFloat() / ATLAS_H
            uv[i + 2] = (r.x + r.w).toFloat() / ATLAS_W
            uv[i + 3] = (r.y + r.h).toFloat() / ATLAS_H
        }
        uv
    }

    /**
     * 根据建筑占地尺寸获取地砖类型索引。
     * 新占地尺寸会映射到最接近的现有地砖类型（纹理拉伸后视觉效果接近）。
     * @param gw 建筑占地宽度（格数）
     * @param gh 建筑占地高度（格数）
     * @return 地砖索引（0-3），或 -1（无匹配地砖）
     */
    fun floorTileIndex(gw: Int, gh: Int): Int = when {
        gw == 2 && gh == 2 -> 0  // 地砖2x2
        gw == 2 && gh == 3 -> 1  // 地砖2x3
        gw == 3 && gh == 2 -> 2  // 地砖3x2
        gw == 3 && gh == 3 -> 3  // 地砖3x3
        // 新占地尺寸映射到最接近的现有地砖
        gw == 4 && gh == 4 -> 3  // 方形 → 3x3 地砖（拉伸）
        gw == 6 && gh == 4 -> 2  // 宽扁 → 3x2 地砖
        gw == 4 && gh == 6 -> 1  // 窄高 → 2x3 地砖
        gw == 6 && gh == 6 -> 3  // 大方 → 3x3 地砖
        gw == 4 && gh == 8 -> 1  // 瘦高 → 2x3 地砖
        gw == 2 && gh == 4 -> 1  // 窄高 → 2x3 地砖
        gw == 4 && gh == 3 -> 2  // 宽扁 → 3x2 地砖
        gw == 6 && gh == 5 -> 2  // 宽扁 → 3x2 地砖
        else -> -1
    }

    /** 地砖在图集中的像素矩形（供 Canvas 渲染器使用） */
    fun floorTileRect(index: Int): SpriteRect =
        FloorTileType.values().getOrNull(index)?.pixelRect ?: SpriteRect(0, 640, 128, 128)
}
