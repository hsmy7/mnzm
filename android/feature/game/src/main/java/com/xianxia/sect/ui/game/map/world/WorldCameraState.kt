package com.xianxia.sect.ui.game.map.world

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.xianxia.sect.core.camera.CameraState
import com.xianxia.sect.ui.game.map.BaseCameraState
import kotlin.math.abs

/**
 * 世界地图相机状态。
 *
 * 继承 [BaseCameraState] 获得平移/缩放/边界钳制等公共实现，
 * 在此添加特有的 [updateScale] 方法与初始缩放参数。
 */
@Stable
class WorldCameraState(
    worldWidth: Float,
    worldHeight: Float,
    initialScale: Float = 1.0f
) : BaseCameraState(worldWidth, worldHeight) {

    init {
        scale = initialScale.coerceIn(CameraState.MIN_ZOOM, CameraState.MAX_ZOOM)
    }

    private var hasInitialized = false
    private var lastCenterX = 0f
    private var lastCenterY = 0f

    companion object {
        /** 自动居中触发阈值（世界像素），避免反复居中打断用户操作 */
        private const val CENTER_THRESHOLD = 100f
    }

    /**
     * 更新视口尺寸。
     * 当视口尺寸变化超过阈值时（如横竖屏旋转），重置居中标记。
     */
    override fun updateViewport(w: Int, h: Int) {
        val prevW = viewportWidth
        val prevH = viewportHeight
        super.updateViewport(w, h)
        if (prevW > 0 && prevH > 0 &&
            (abs(w - prevW) > CENTER_THRESHOLD || abs(h - prevH) > CENTER_THRESHOLD)
        ) {
            hasInitialized = false
        }
    }

    /**
     * 世界地图的默认缩放保持当前值不变。
     * 初始缩放由构造参数 [initialScale] 设定，
     * [updateViewport] 不会覆盖它（除非用户主动缩放后 reset）。
     */
    override fun computeDefaultScale(vpW: Int, vpH: Int): Float = scale

    /**
     * 以当前视口中心为锚点更新缩放。
     * 区别于 [zoom] 的任意焦点锚定，此方法始终以屏幕中心为锚点。
     * 调用后标记用户缩放，后续 [updateViewport] 不覆盖 scale。
     */
    fun updateScale(newScale: Float) {
        if (newScale > 0f && newScale != scale) {
            val cx = cameraX + viewportWidth / (2f * scale)
            val cy = cameraY + viewportHeight / (2f * scale)
            // 委托基类 applyScale（含 NaN/isInfinite 防御 + coerceIn）
            applyScale(newScale)
            cameraX = cx - viewportWidth / (2f * scale)
            cameraY = cy - viewportHeight / (2f * scale)
            clamp()
        }
    }

    /**
     * 尝试居中到指定坐标，带初始化保护和距离阈值。
     * 仅首次调用或坐标变化 >100 单位时生效，避免反复居中打断用户操作。
     */
    fun tryCenterOn(wx: Float, wy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        val shouldCenter = !hasInitialized ||
            abs(wx - lastCenterX) > CENTER_THRESHOLD ||
            abs(wy - lastCenterY) > CENTER_THRESHOLD
        if (shouldCenter) {
            centerOn(wx, wy)
            lastCenterX = wx
            lastCenterY = wy
            hasInitialized = true
        }
    }

    /**
     * 重置相机到初始状态。
     * 清除居中标记、用户缩放标记，位置归零。
     */
    override fun reset() {
        hasInitialized = false
        lastCenterX = 0f
        lastCenterY = 0f
        super.reset()
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
): WorldCameraState = remember(worldWidth, worldHeight, scale) {
    WorldCameraState(worldWidth, worldHeight, scale)
}
