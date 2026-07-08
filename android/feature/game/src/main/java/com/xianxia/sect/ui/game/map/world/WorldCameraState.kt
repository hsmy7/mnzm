package com.xianxia.sect.ui.game.map.world

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xianxia.sect.core.camera.CameraState
import kotlin.math.abs

/**
 * 世界地图相机状态。
 *
 * 实现 [CameraState] 接口，与 [SectCameraState] 共享统一契约。
 * 支持用户缩放 [zoom]、锚定焦点缩放。
 */
@Stable
class WorldCameraState(
    override val worldWidth: Float,
    override val worldHeight: Float,
    initialScale: Float = 1.0f
) : CameraState {

    override var scale by mutableFloatStateOf(initialScale)
        private set
    override var cameraX by mutableFloatStateOf(0f)
        private set
    override var cameraY by mutableFloatStateOf(0f)
        private set
    override var viewportWidth by mutableIntStateOf(0)
        private set
    override var viewportHeight by mutableIntStateOf(0)
        private set

    private var hasInitialized = false
    private var lastCenterX = 0f
    private var lastCenterY = 0f

    companion object {
        const val MIN_ZOOM = 0.3f
        const val MAX_ZOOM = 3.0f
    }

    override fun worldToScreenX(wx: Float): Float = (wx - cameraX) * scale
    override fun worldToScreenY(wy: Float): Float = (wy - cameraY) * scale
    override fun screenToWorldX(sx: Float): Float = sx / scale + cameraX
    override fun screenToWorldY(sy: Float): Float = sy / scale + cameraY

    override fun updateViewport(w: Int, h: Int) {
        viewportWidth = w
        viewportHeight = h
    }

    /**
     * 更新缩放（保持现有的 [updateScale] 兼容性）。
     * 无焦点锚定，直接以视口中心为锚点。
     */
    fun updateScale(newScale: Float) {
        if (newScale > 0f && newScale != scale) {
            val cx = cameraX + viewportWidth / (2f * scale)
            val cy = cameraY + viewportHeight / (2f * scale)
            scale = newScale.coerceIn(MIN_ZOOM, MAX_ZOOM)
            cameraX = cx - viewportWidth / (2f * scale)
            cameraY = cy - viewportHeight / (2f * scale)
            clamp()
        }
    }

    override fun pan(dx: Float, dy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX -= dx / scale
        cameraY -= dy / scale
        clamp()
    }

    override fun centerOn(wx: Float, wy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX = wx - viewportWidth / (2f * scale)
        cameraY = wy - viewportHeight / (2f * scale)
        clamp()
    }

    /**
     * 以屏幕焦点为中心的锚定缩放。
     */
    override fun zoom(delta: Float, focusX: Float, focusY: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        val worldBeforeX = screenToWorldX(focusX)
        val worldBeforeY = screenToWorldY(focusY)
        val newScale = (scale * delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newScale == scale) return
        scale = newScale
        cameraX = worldBeforeX - focusX / scale
        cameraY = worldBeforeY - focusY / scale
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

    override fun isVisible(wx: Float, wy: Float, margin: Float): Boolean {
        if (viewportWidth <= 0 || viewportHeight <= 0) return true
        val sx = worldToScreenX(wx)
        val sy = worldToScreenY(wy)
        val m = margin * scale
        return sx >= -m && sx <= viewportWidth + m && sy >= -m && sy <= viewportHeight + m
    }

    override fun setPosition(x: Float, y: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            cameraX = x
            cameraY = y
            return
        }
        cameraX = x
        cameraY = y
        clamp()
    }

    override fun applyScale(newScale: Float) {
        scale = newScale.coerceIn(MIN_ZOOM, MAX_ZOOM)
        clamp()
    }

    override fun reset() {
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

/**
 * 创建并记住 [WorldCameraState] 实例。
 * @param worldWidth 世界宽度（像素）
 * @param worldHeight 世界高度（像素）
 * @param scale 初始缩放值
 */
@Composable
fun rememberWorldCamera(
    worldWidth: Float,
    worldHeight: Float,
    scale: Float = 1.0f
): WorldCameraState = remember(worldWidth, worldHeight) {
    WorldCameraState(worldWidth, worldHeight, scale)
}
