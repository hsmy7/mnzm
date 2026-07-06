package com.xianxia.sect.core.model

/**
 * 宗门地图预加载数据。
 *
 * v4.0.43+ 架构：Vulkan 原生渲染管线已替代 Compose Canvas 绘制。
 * 地面/装饰/建筑纹理不再通过 [ImageBitmap] 传递，改为 Vulkan 纹理图集
 * 在 [NativeSurfaceView.buildAtlas] 中独立加载和上传。
 * 本类仅保留瓦片数据、地图配置等非纹理信息。
 */
data class MapPreloadData(
    /** 瓦片类型数据（含装饰变体和建筑占位标记） */
    val rawTileData: Array<IntArray>,
    val worldWidthCells: Int,
    val worldHeightCells: Int,
    val tileSize: Int,
    val worldPixelWidth: Int,
    val worldPixelHeight: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MapPreloadData) return false
        return worldWidthCells == other.worldWidthCells &&
            worldHeightCells == other.worldHeightCells &&
            tileSize == other.tileSize &&
            worldPixelWidth == other.worldPixelWidth &&
            worldPixelHeight == other.worldPixelHeight &&
            rawTileData.contentDeepEquals(other.rawTileData)
    }

    override fun hashCode(): Int {
        var result = rawTileData.contentDeepHashCode()
        result = 31 * result + worldWidthCells
        result = 31 * result + worldHeightCells
        result = 31 * result + tileSize
        result = 31 * result + worldPixelWidth
        result = 31 * result + worldPixelHeight
        return result
    }
}
