package com.xianxia.sect.core.model

/**
 * 宗门地图预加载数据。
 *
 * 渲染架构：Vulkan 原生渲染管线负责绘制；地面/装饰/建筑纹理经 Vulkan 纹理图集
 * 在 [NativeSurfaceView.buildAtlas] 中独立加载和上传，不经过 [ImageBitmap]。
 *
 * 地图数据模型：**展平一维是唯一表示**，不保留 2D 孪生结构
 * （避免每建筑变动触发 O(全图) 处理）。生成真源在 C++ `gamecore/map/terrain.h`
 * （SectTerrainBridge native 优先、Kotlin 生成器降级，每种子一次）。
 */
data class MapPreloadData(
    /** 纯地形瓦片（展平行主序，index = row*worldWidthCells+col；TILE_* 索引，
     *  无建筑占位标记——建筑占用由渲染侧在副本上动态标记，本数组不可变） */
    val flatTileData: IntArray,
    val worldWidthCells: Int,
    val worldHeightCells: Int,
    val tileSize: Int,
    val worldPixelWidth: Int,
    val worldPixelHeight: Int,
    /** 地图种子（岛屿边缘变体确定性来源；与瓦片生成同源，仅内存传递不序列化） */
    val seed: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MapPreloadData) return false
        return worldWidthCells == other.worldWidthCells &&
            worldHeightCells == other.worldHeightCells &&
            tileSize == other.tileSize &&
            worldPixelWidth == other.worldPixelWidth &&
            worldPixelHeight == other.worldPixelHeight &&
            seed == other.seed &&
            flatTileData.contentEquals(other.flatTileData)
    }

    override fun hashCode(): Int {
        var result = flatTileData.contentHashCode()
        result = 31 * result + worldWidthCells
        result = 31 * result + worldHeightCells
        result = 31 * result + tileSize
        result = 31 * result + worldPixelWidth
        result = 31 * result + worldPixelHeight
        result = 31 * result + seed
        return result
    }
}
