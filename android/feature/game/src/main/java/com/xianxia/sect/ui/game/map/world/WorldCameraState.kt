package com.xianxia.sect.ui.game.map.world

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.abs

@Stable
class WorldCameraState(
    val worldWidth: Float,
    val worldHeight: Float,
    initialScale: Float = 1.0f
) {
    var scale by mutableFloatStateOf(initialScale)
        private set
    var cameraX by mutableFloatStateOf(0f)
        private set
    var cameraY by mutableFloatStateOf(0f)
        private set
    var viewportWidth by mutableIntStateOf(0)
        private set
    var viewportHeight by mutableIntStateOf(0)
        private set

    private var hasInitialized = false
    private var lastCenterX = 0f
    private var lastCenterY = 0f

    fun worldToScreenX(wx: Float): Float = (wx - cameraX) * scale
    fun worldToScreenY(wy: Float): Float = (wy - cameraY) * scale
    fun screenToWorldX(sx: Float): Float = sx / scale + cameraX
    fun screenToWorldY(sy: Float): Float = sy / scale + cameraY

    fun updateViewport(w: Int, h: Int) {
        viewportWidth = w
        viewportHeight = h
    }

    fun updateScale(newScale: Float) {
        if (newScale > 0f && newScale != scale) {
            scale = newScale
            clamp()
        }
    }

    fun pan(dx: Float, dy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX -= dx / scale
        cameraY -= dy / scale
        clamp()
    }

    fun centerOn(wx: Float, wy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX = wx - viewportWidth / (2f * scale)
        cameraY = wy - viewportHeight / (2f * scale)
        clamp()
    }

    fun tryCenterOn(wx: Float, wy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        if (!hasInitialized || abs(wx - lastCenterX) > 100f || abs(wy - lastCenterY) > 100f) {
            centerOn(wx, wy)
            lastCenterX = wx
            lastCenterY = wy
            hasInitialized = true
        }
    }

    fun isVisible(wx: Float, wy: Float, margin: Float = 1f): Boolean {
        if (viewportWidth <= 0 || viewportHeight <= 0) return true
        val sx = worldToScreenX(wx)
        val sy = worldToScreenY(wy)
        val m = margin * scale
        return sx >= -m && sx <= viewportWidth + m && sy >= -m && sy <= viewportHeight + m
    }

    fun reset() {
        hasInitialized = false
        cameraX = 0f
        cameraY = 0f
    }

    private fun clamp() {
        if (scale <= 0f) return
        val ew = viewportWidth / scale
        val eh = viewportHeight / scale
        cameraX = cameraX.coerceIn(0f, (worldWidth - ew).coerceAtLeast(0f))
        cameraY = cameraY.coerceIn(0f, (worldHeight - eh).coerceAtLeast(0f))
    }
}

@Composable
fun rememberWorldCamera(
    worldWidth: Float,
    worldHeight: Float,
    scale: Float = 1.0f
): WorldCameraState = remember(worldWidth, worldHeight) {
    WorldCameraState(worldWidth, worldHeight, scale)
}
