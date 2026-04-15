package com.xianxia.sect.ui.game.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.xianxia.sect.core.model.MapCoordinateSystem
import kotlin.math.abs

@Stable
class MapCameraState(
    val scaleRange: ClosedFloatingPointRange<Float> = 0.2f..3f
) {
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set
    var scale by mutableFloatStateOf(1f)
        private set
    var screenWidth by mutableIntStateOf(0)
        private set
    var screenHeight by mutableIntStateOf(0)
        private set

    private var hasInitialized = false

    val canvasWidth: Float get() = baseMapWidthPx * scale
    val canvasHeight: Float get() = baseMapHeightPx * scale

    private var baseMapWidthPx: Int = 0
    private var baseMapHeightPx: Int = 0

    private val offsetRangeX: ClosedFloatingPointRange<Float>
        get() {
            val diff = canvasWidth - screenWidth
            return if (diff >= 0) -diff..0f
            else {
                val center = -diff / 2f
                center..center
            }
        }

    private val offsetRangeY: ClosedFloatingPointRange<Float>
        get() {
            val diff = canvasHeight - screenHeight
            return if (diff >= 0) -diff..0f
            else {
                val center = -diff / 2f
                center..center
            }
        }

    val effectiveMinScale: Float
        get() {
            if (baseMapWidthPx <= 0 || baseMapHeightPx <= 0) return scaleRange.start
            if (screenWidth <= 0 || screenHeight <= 0) return scaleRange.start
            val fitScale = minOf(
                screenWidth.toFloat() / baseMapWidthPx,
                screenHeight.toFloat() / baseMapHeightPx
            )
            return (fitScale * 0.85f).coerceIn(scaleRange.start, scaleRange.endInclusive)
        }

    fun initialize(density: Float, baseWidthDp: Float, baseHeightDp: Float) {
        if (baseMapWidthPx == 0) {
            baseMapWidthPx = (baseWidthDp * density).toInt()
            baseMapHeightPx = (baseHeightDp * density).toInt()
        }
    }

    fun updateScreenSize(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
    }

    fun focusOnWorld(worldX: Float, worldY: Float) {
        if (baseMapWidthPx <= 0 || baseMapHeightPx <= 0) return
        val canvasX = (worldX / MapCoordinateSystem.WORLD_WIDTH) * canvasWidth
        val canvasY = (worldY / MapCoordinateSystem.WORLD_HEIGHT) * canvasHeight
        offsetX = (screenWidth / 2f - canvasX).coerceIn(offsetRangeX)
        offsetY = (screenHeight / 2f - canvasY).coerceIn(offsetRangeY)
        hasInitialized = true
    }

    fun tryInitialFocus(worldX: Float, worldY: Float) {
        if (!hasInitialized && screenWidth > 0 && screenHeight > 0) {
            focusOnWorld(worldX, worldY)
        }
    }

    fun applyDrag(dx: Float, dy: Float) {
        val rangeX = offsetRangeX
        val rangeY = offsetRangeY
        val canPanX = rangeX.start != rangeX.endInclusive
        val canPanY = rangeY.start != rangeY.endInclusive
        if (canPanX || canPanY) {
            offsetX = (offsetX + dx).coerceIn(rangeX)
            offsetY = (offsetY + dy).coerceIn(rangeY)
        }
    }

    fun applyZoom(zoomFactor: Float, centroid: Offset) {
        val minScale = effectiveMinScale
        val newScale = (scale * zoomFactor).coerceIn(minScale, scaleRange.endInclusive)
        if (abs(newScale - scale) < 0.001f) return

        val scaleFactor = newScale / scale
        val centroidX = centroid.x - offsetX
        val centroidY = centroid.y - offsetY

        offsetX -= (centroidX * (scaleFactor - 1))
        offsetY -= (centroidY * (scaleFactor - 1))
        scale = newScale

        offsetX = offsetX.coerceIn(offsetRangeX)
        offsetY = offsetY.coerceIn(offsetRangeY)
    }

    fun isWorldPositionVisible(
        worldX: Float,
        worldY: Float,
        margin: Float = 200f
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return true
        return MapCoordinateSystem.isWorldPositionVisible(
            worldX, worldY,
            canvasWidth, canvasHeight,
            offsetX, offsetY,
            screenWidth, screenHeight,
            margin
        )
    }

    fun reset() {
        hasInitialized = false
        offsetX = 0f
        offsetY = 0f
        scale = 1f
    }
}

@Composable
fun rememberMapCameraState(
    scaleRange: ClosedFloatingPointRange<Float> = 0.2f..3f
): MapCameraState = remember { MapCameraState(scaleRange) }
