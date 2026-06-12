package com.xianxia.sect.core.util

import kotlin.math.roundToInt

object GridSnapHelper {

    enum class SnapMode { ROUND, FLOOR, CEIL }

    data class BuildingSize(val width: Int, val height: Int)

    data class GridRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val tag: String = ""
    )

    sealed class PlacementValidity {
        data object Valid : PlacementValidity()
        data class Overlap(val names: List<String>) : PlacementValidity()
        data object OutOfBounds : PlacementValidity()
    }

    fun screenToWorld(screenCoord: Float, cameraX: Float, scale: Float = 1f): Float =
        screenCoord / scale + cameraX

    fun worldToScreen(worldCoord: Int, cameraX: Float, scale: Float = 1f): Float =
        (worldCoord - cameraX) * scale

    fun worldToGrid(worldCoord: Float, tileSize: Int): Int =
        (worldCoord / tileSize).roundToInt()

    fun gridToWorld(gridCoord: Int, tileSize: Int): Int =
        gridCoord * tileSize

    fun gridToScreen(
        gridCoord: Int,
        tileSize: Int,
        cameraX: Float,
        scale: Float = 1f
    ): Float =
        (gridCoord * tileSize - cameraX) * scale

    fun validatePlacement(
        gridX: Int,
        gridY: Int,
        width: Int,
        height: Int,
        maxGridX: Int,
        maxGridY: Int,
        occupiedRects: List<GridRect> = emptyList()
    ): PlacementValidity {
        if (gridX < 0 || gridY < 0 || gridX + width > maxGridX || gridY + height > maxGridY) {
            return PlacementValidity.OutOfBounds
        }
        val overlapped = occupiedRects.filter { rect ->
            !(gridX >= rect.x + rect.width ||
              gridX + width <= rect.x ||
              gridY >= rect.y + rect.height ||
              gridY + height <= rect.y)
        }
        if (overlapped.isNotEmpty()) {
            return PlacementValidity.Overlap(
                overlapped.map { it.tag }.filter { it.isNotEmpty() }
            )
        }
        return PlacementValidity.Valid
    }
}
