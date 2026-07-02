package com.xianxia.sect.ui.game.map.sect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * 宗门地图相机状态。
 *
 * 支持动态缩放（scale），采用 Fill 适配策略：
 * - 视口尺寸 ≤ 世界尺寸（手机、平板等常见设备）→ scale=1.0，1:1 像素映射
 * - 视口尺寸 > 世界尺寸（外接大屏、折叠屏展开等）→ scale 缩小至
 *   `maxOf(viewportW/worldW, viewportH/worldH)`，确保地图填满视口无白边
 */
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
    var scale by mutableFloatStateOf(1f)
        private set

    private var hasInitialized = false
    private var lastCenterX = 0f
    private var lastCenterY = 0f

    /**
     * 世界坐标 → 屏幕坐标（含 scale 缩放）。
     * 屏幕坐标 = (世界坐标 - 相机位置) × scale
     */
    fun worldToScreenX(wx: Float): Float = (wx - cameraX) * scale
    fun worldToScreenY(wy: Float): Float = (wy - cameraY) * scale

    /**
     * 屏幕坐标 → 世界坐标（含 scale 缩放）。
     * 世界坐标 = 屏幕坐标 / scale + 相机位置
     */
    fun screenToWorldX(sx: Float): Float = sx / scale + cameraX
    fun screenToWorldY(sy: Float): Float = sy / scale + cameraY

    /**
     * 更新视口尺寸并重新计算 Fill 缩放。
     *
     * Fill 策略：当视口至少一个维度大于世界时，
     * scale = maxOf(viewportW/worldW, viewportH/worldH)，
     * 确保地图始终填满整个视口（类比 CSS object-fit: cover）。
     *
     * 当视口完全小于世界时，scale=1.0 保持原生 1:1 像素映射。
     */
    fun updateViewport(w: Int, h: Int) {
        viewportWidth = w
        viewportHeight = h
        val needScaleX = w.toFloat() > worldWidth
        val needScaleY = h.toFloat() > worldHeight
        scale = if (needScaleX || needScaleY) {
            val sx = w.toFloat() / worldWidth
            val sy = h.toFloat() / worldHeight
            if (sx > sy) sx else sy
        } else {
            1f
        }
        clamp()
    }

    /**
     * 平移相机（拖拽响应）。
     * @param dx 屏幕坐标 X 方向偏移像素
     * @param dy 屏幕坐标 Y 方向偏移像素
     */
    fun pan(dx: Float, dy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX -= dx / scale
        cameraY -= dy / scale
        clamp()
    }

    /**
     * 将相机中心移动到指定世界坐标。
     * @param wx 目标世界坐标 X
     * @param wy 目标世界坐标 Y
     */
    fun centerOn(wx: Float, wy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX = wx - viewportWidth / (2f * scale)
        cameraY = wy - viewportHeight / (2f * scale)
        clamp()
    }

    /**
     * 尝试居中到指定坐标，带初始化保护和距离阈值。
     * 仅首次调用或坐标变化 >100 单位时生效，避免反复居中打断用户操作。
     */
    fun tryCenterOn(wx: Float, wy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        if (!hasInitialized || abs(wx - lastCenterX) > 100f || abs(wy - lastCenterY) > 100f) {
            centerOn(wx, wy)
            lastCenterX = wx
            lastCenterY = wy
            hasInitialized = true
        }
    }

    /**
     * 判断世界坐标点是否在视口可见范围内。
     * @param margin 视口外扩检测边距（世界坐标单位）
     */
    fun isVisible(wx: Float, wy: Float, margin: Float = 0f): Boolean {
        if (viewportWidth <= 0 || viewportHeight <= 0) return true
        val sx = worldToScreenX(wx)
        val sy = worldToScreenY(wy)
        val m = margin * scale
        return sx >= -m && sx <= viewportWidth + m &&
               sy >= -m && sy <= viewportHeight + m
    }

    /**
     * 重置相机到初始状态（位置归零，清初始化标记）。
     * 适用于地图切换或重开场景。
     */
    fun reset() {
        hasInitialized = false
        cameraX = 0f
        cameraY = 0f
    }

    /**
     * 将相机位置限制在世界边界内。
     * 可见世界尺寸 = 视口尺寸 / scale，比 scale=1 时看到更多世界区域。
     */
    private fun clamp() {
        val ew = viewportWidth / scale
        val eh = viewportHeight / scale
        cameraX = cameraX.coerceIn(0f, (worldWidth - ew).coerceAtLeast(0f))
        cameraY = cameraY.coerceIn(0f, (worldHeight - eh).coerceAtLeast(0f))
    }
}

/**
 * 创建并记住 [SectCameraState] 实例。
 * @param worldWidth 世界宽度（像素）
 * @param worldHeight 世界高度（像素）
 */
@Composable
fun rememberSectCamera(
    worldWidth: Float,
    worldHeight: Float
): SectCameraState = remember { SectCameraState(worldWidth, worldHeight) }
