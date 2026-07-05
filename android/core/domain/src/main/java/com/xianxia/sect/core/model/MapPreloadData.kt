package com.xianxia.sect.core.model

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 宗门地图预加载数据。
 *
 * v4.0.41+ 架构：地面/装饰/建筑三层分离，废除 fullMapBmp 单层位图模式。
 * v4.0.42+ 更新：单格地图格 `map_tile` 拼接地面，3 草丛 + 2 树木装饰变体。
 */
data class MapPreloadData(
    /** 地面单格纹理（由 GameActivity 平铺成 groundTileBmp） */
    val mapTileBmp: ImageBitmap,
    /** 拉伸到渲染分辨率的地面位图（mapTileBmp 平铺合成） */
    val groundTileBmp: ImageBitmap,
    /** 3 种草装饰精灵图：[小草丛, 中草丛, 大草丛] */
    val grassDecBitmaps: List<ImageBitmap>,
    /** 2 种树装饰精灵图：[树木1, 树木2] */
    val treeDecBitmaps: List<ImageBitmap>,
    /** 瓦片类型数据（含装饰变体和建筑占位标记） */
    val rawTileData: Array<IntArray>,
    val worldWidthCells: Int,
    val worldHeightCells: Int,
    val tileSize: Int,
    val worldPixelWidth: Int,
    val worldPixelHeight: Int,
    val renderWidth: Int,
    val renderHeight: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MapPreloadData) return false
        return worldWidthCells == other.worldWidthCells &&
            worldHeightCells == other.worldHeightCells &&
            tileSize == other.tileSize &&
            worldPixelWidth == other.worldPixelWidth &&
            worldPixelHeight == other.worldPixelHeight &&
            renderWidth == other.renderWidth &&
            renderHeight == other.renderHeight &&
            mapTileBmp == other.mapTileBmp &&
            groundTileBmp == other.groundTileBmp &&
            grassDecBitmaps == other.grassDecBitmaps &&
            treeDecBitmaps == other.treeDecBitmaps &&
            rawTileData.contentDeepEquals(other.rawTileData)
    }

    override fun hashCode(): Int {
        var result = mapTileBmp.hashCode()
        result = 31 * result + groundTileBmp.hashCode()
        result = 31 * result + grassDecBitmaps.hashCode()
        result = 31 * result + treeDecBitmaps.hashCode()
        result = 31 * result + rawTileData.contentDeepHashCode()
        result = 31 * result + worldWidthCells
        result = 31 * result + worldHeightCells
        result = 31 * result + tileSize
        result = 31 * result + worldPixelWidth
        result = 31 * result + worldPixelHeight
        result = 31 * result + renderWidth
        result = 31 * result + renderHeight
        return result
    }
}
