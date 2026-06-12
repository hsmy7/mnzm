package com.xianxia.sect.core.model

import androidx.compose.ui.graphics.ImageBitmap

data class MapPreloadData(
    val groundTileBmp: ImageBitmap,
    val grassDecBmp: ImageBitmap,
    val treeDecBmp: ImageBitmap,
    val fullMapBmp: ImageBitmap,
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
            groundTileBmp == other.groundTileBmp &&
            grassDecBmp == other.grassDecBmp &&
            treeDecBmp == other.treeDecBmp &&
            fullMapBmp == other.fullMapBmp &&
            rawTileData.contentDeepEquals(other.rawTileData)
    }

    override fun hashCode(): Int {
        var result = groundTileBmp.hashCode()
        result = 31 * result + grassDecBmp.hashCode()
        result = 31 * result + treeDecBmp.hashCode()
        result = 31 * result + fullMapBmp.hashCode()
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
