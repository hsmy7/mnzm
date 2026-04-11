package com.xianxia.sect.core.model

object MapCoordinateSystem {
    const val WORLD_WIDTH = 4000f
    const val WORLD_HEIGHT = 3500f

    fun worldToNormalized(worldX: Float, worldY: Float): Pair<Float, Float> =
        (worldX / WORLD_WIDTH).coerceIn(0f, 1f) to
        (worldY / WORLD_HEIGHT).coerceIn(0f, 1f)

    fun normalizedToWorld(nx: Float, ny: Float): Pair<Float, Float> =
        nx * WORLD_WIDTH to ny * WORLD_HEIGHT

    fun worldToCanvas(worldX: Float, worldY: Float, canvasWidth: Float, canvasHeight: Float): Pair<Float, Float> =
        (worldX / WORLD_WIDTH) * canvasWidth to
        (worldY / WORLD_HEIGHT) * canvasHeight

    fun worldToScreen(
        worldX: Float,
        worldY: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        offsetX: Float,
        offsetY: Float
    ): Pair<Float, Float> {
        val (cx, cy) = worldToCanvas(worldX, worldY, canvasWidth, canvasHeight)
        return cx + offsetX to cy + offsetY
    }

    fun isWorldPositionVisible(
        worldX: Float,
        worldY: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        offsetX: Float,
        offsetY: Float,
        screenWidth: Int,
        screenHeight: Int,
        margin: Float = 200f
    ): Boolean {
        val (sx, sy) = worldToScreen(worldX, worldY, canvasWidth, canvasHeight, offsetX, offsetY)
        return sx >= -margin && sx <= screenWidth + margin && sy >= -margin && sy <= screenHeight + margin
    }
}
