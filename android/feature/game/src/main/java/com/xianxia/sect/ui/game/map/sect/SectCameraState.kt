package com.xianxia.sect.ui.game.map.sect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.abs

@Stable
class SectCameraState(
    val worldWidth: Float,
    val worldHeight: Float
) {
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

    fun worldToScreenX(wx: Float): Float = wx - cameraX
    fun worldToScreenY(wy: Float): Float = wy - cameraY
    fun screenToWorldX(sx: Float): Float = sx + cameraX
    fun screenToWorldY(sy: Float): Float = sy + cameraY

    fun updateViewport(w: Int, h: Int) {
        viewportWidth = w
        viewportHeight = h
    }

    fun pan(dx: Float, dy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX -= dx
        cameraY -= dy
        clamp()
    }

    fun centerOn(wx: Float, wy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX = wx - viewportWidth / 2f
        cameraY = wy - viewportHeight / 2f
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

    fun isVisible(wx: Float, wy: Float, margin: Float = 0f): Boolean {
        if (viewportWidth <= 0 || viewportHeight <= 0) return true
        val sx = worldToScreenX(wx)
        val sy = worldToScreenY(wy)
        return sx >= -margin && sx <= viewportWidth + margin &&
               sy >= -margin && sy <= viewportHeight + margin
    }

    fun reset() {
        hasInitialized = false
        cameraX = 0f
        cameraY = 0f
    }

    private fun clamp() {
        val ew = viewportWidth.toFloat()
        val eh = viewportHeight.toFloat()
        cameraX = cameraX.coerceIn(0f, (worldWidth - ew).coerceAtLeast(0f))
        cameraY = cameraY.coerceIn(0f, (worldHeight - eh).coerceAtLeast(0f))
    }
}

@Composable
fun rememberSectCamera(
    worldWidth: Float,
    worldHeight: Float
): SectCameraState = remember { SectCameraState(worldWidth, worldHeight) }
