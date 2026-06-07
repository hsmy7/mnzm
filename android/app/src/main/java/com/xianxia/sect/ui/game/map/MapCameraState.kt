package com.xianxia.sect.ui.game.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 统一相机状态 — 相机在世界空间中移动（而非移动地图画布）
 *
 * cameraX/cameraY = 视口左上角在世界空间中的坐标
 * screenX = worldX - cameraX
 * worldX = screenX + cameraX
 */
@Stable
class CameraState(
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

    // -- 坐标转换 --

    fun worldToScreenX(worldX: Float): Float = worldX - cameraX
    fun worldToScreenY(worldY: Float): Float = worldY - cameraY

    fun screenToWorldX(screenX: Float): Float = screenX + cameraX
    fun screenToWorldY(screenY: Float): Float = screenY + cameraY

    // -- 视口 --

    fun updateViewport(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
    }

    // -- 平移（接收屏幕空间增量） --

    fun pan(deltaScreenX: Float, deltaScreenY: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX -= deltaScreenX
        cameraY -= deltaScreenY
        clamp()
    }

    // -- 居中 --

    fun centerOn(worldX: Float, worldY: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX = worldX - viewportWidth / 2f
        cameraY = worldY - viewportHeight / 2f
        clamp()
    }

    fun tryCenterOn(worldX: Float, worldY: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        // 首次初始化；或焦点坐标相比上次居中位置变化超过 100px → 允许重定位
        // 用户拖拽不会改变 focusWorldX/Y，因此不会误触发
        if (!hasInitialized || kotlin.math.abs(worldX - lastCenterX) > 100f || kotlin.math.abs(worldY - lastCenterY) > 100f) {
            centerOn(worldX, worldY)
            lastCenterX = worldX
            lastCenterY = worldY
            hasInitialized = true
        }
    }

    // -- 可见性 --

    fun isVisible(worldX: Float, worldY: Float, margin: Float = 0f): Boolean {
        if (viewportWidth <= 0 || viewportHeight <= 0) return true
        val sx = worldToScreenX(worldX)
        val sy = worldToScreenY(worldY)
        return sx >= -margin && sx <= viewportWidth + margin &&
               sy >= -margin && sy <= viewportHeight + margin
    }

    // -- 重置 --

    fun reset() {
        hasInitialized = false
        cameraX = 0f
        cameraY = 0f
    }

    // -- 内部钳制 --

    private fun clamp() {
        if (viewportWidth >= worldWidth) {
            cameraX = -(viewportWidth - worldWidth) / 2f
        } else {
            cameraX = cameraX.coerceIn(0f, worldWidth - viewportWidth)
        }

        if (viewportHeight >= worldHeight) {
            cameraY = -(viewportHeight - worldHeight) / 2f
        } else {
            cameraY = cameraY.coerceIn(0f, worldHeight - viewportHeight)
        }
    }
}

@Composable
fun rememberCameraState(
    worldWidth: Float,
    worldHeight: Float
): CameraState = remember { CameraState(worldWidth, worldHeight) }
