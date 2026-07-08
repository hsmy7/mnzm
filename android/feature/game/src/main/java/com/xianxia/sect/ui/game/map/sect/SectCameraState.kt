package com.xianxia.sect.ui.game.map.sect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xianxia.sect.core.animation.CameraAnimator
import com.xianxia.sect.core.animation.CameraTarget
import com.xianxia.sect.core.camera.CameraState
import kotlin.math.abs

/**
 * 宗门地图相机状态。
 *
 * 支持动态缩放（scale），v4.0.45+ 新增用户缩放：
 * - 默认 scale=0.5（视角提高 50%，取整），让玩家初期看到更多地图
 * - 用户可通过 [zoom] / 缩放按钮 +/- / 双击缩放调整
 * - 缩放范围 [MIN_ZOOM, MAX_ZOOM] = [0.3, 3.0]
 *
 * 实现 [CameraState] 接口，与 [WorldCameraState] 共享统一契约。
 */
@Stable
class SectCameraState(
    override val worldWidth: Float,
    override val worldHeight: Float
) : CameraState {

    override var cameraX by mutableFloatStateOf(0f)
        private set
    override var cameraY by mutableFloatStateOf(0f)
        private set
    override var viewportWidth by mutableIntStateOf(0)
        private set
    override var viewportHeight by mutableIntStateOf(0)
        private set
    override var scale by mutableFloatStateOf(1f)
        private set

    /** 初始居中标记 */
    private var hasInitialized = false
    private var lastCenterX = 0f
    private var lastCenterY = 0f

    /** 用户是否已主动缩放（zoom / 缩放按钮 / 双击），
     * 为 true 时 [updateViewport] 不再覆盖 scale */
    private var userScale = false

    /** 仅首次调用 [updateViewport] 时应用默认视角高度 */
    private var vpInitialized = false

    /** 平滑动画引擎（可选），由 UI 层注入，用于 [tryCenterOn] 等编程式移动 */
    private var animator: CameraAnimator? = null

    /**
     * 设置平滑动画引擎引用。
     * 设置后 [tryCenterOn] 将使用动画过渡而非瞬间跳转。
     */
    fun setAnimator(anim: CameraAnimator) {
        animator = anim
    }

    /** 缩放范围常量 */
    companion object {
        const val MIN_ZOOM = 0.3f
        const val MAX_ZOOM = 3.0f
        /** 默认视角高度 scale，提高 50% 取整 */
        const val DEFAULT_SCALE = 0.5f
    }

    /**
     * 世界坐标 → 屏幕坐标（含 scale 缩放）。
     * 屏幕坐标 = (世界坐标 - 相机位置) × scale
     */
    override fun worldToScreenX(wx: Float): Float = (wx - cameraX) * scale
    override fun worldToScreenY(wy: Float): Float = (wy - cameraY) * scale

    /**
     * 屏幕坐标 → 世界坐标（含 scale 缩放）。
     * 世界坐标 = 屏幕坐标 / scale + 相机位置
     */
    override fun screenToWorldX(sx: Float): Float = sx / scale + cameraX
    override fun screenToWorldY(sy: Float): Float = sy / scale + cameraY

    /**
     * 更新视口尺寸。
     *
     * 首次调用时：
     * - 小视口（手机常见）：应用默认视角高度 [DEFAULT_SCALE]（0.5）
     * - 大视口（外接显示）：应用 Fill 适配策略，确保地图填满视口无白边
     * 用户已缩放后（[userScale]=true）不再覆盖 scale，仅重新 clamp。
     */
    override fun updateViewport(w: Int, h: Int) {
        viewportWidth = w
        viewportHeight = h
        if (!vpInitialized && !userScale) {
            val wf = w.toFloat()
            val hf = h.toFloat()
            val needScaleX = wf > worldWidth
            val needScaleY = hf > worldHeight
            scale = if (needScaleX || needScaleY) {
                // 大屏/外接显示: Fill 适配
                maxOf(wf / worldWidth, hf / worldHeight)
            } else {
                // 正常手机: 视角提高 50%, 取整
                DEFAULT_SCALE
            }
            vpInitialized = true
        }
        clamp()
    }

    /**
     * 平移相机（拖拽响应）。
     * @param dx 屏幕坐标 X 方向偏移像素
     * @param dy 屏幕坐标 Y 方向偏移像素
     */
    override fun pan(dx: Float, dy: Float) {
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
    override fun centerOn(wx: Float, wy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX = wx - viewportWidth / (2f * scale)
        cameraY = wy - viewportHeight / (2f * scale)
        clamp()
    }

    /**
     * 以屏幕焦点为中心的锚定缩放。
     *
     * 缩放后焦点下的世界坐标保持不变（类似 Google Maps / LibGDX 标准做法）。
     * 调用后标记 [userScale]=true，viewport 变化不再覆盖 scale。
     *
     * @param delta 缩放倍数（>1 放大, <1 缩小）
     * @param focusX 缩放焦点屏幕 X 坐标
     * @param focusY 缩放焦点屏幕 Y 坐标
     */
    override fun zoom(delta: Float, focusX: Float, focusY: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        // 缩放前焦点下的世界坐标
        val worldBeforeX = screenToWorldX(focusX)
        val worldBeforeY = screenToWorldY(focusY)
        // 计算新 scale 并钳制
        val newScale = (scale * delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newScale == scale) return
        userScale = true
        scale = newScale
        // 调整相机使焦点世界坐标不变
        cameraX = worldBeforeX - focusX / scale
        cameraY = worldBeforeY - focusY / scale
        clamp()
    }

    /**
     * 尝试居中到指定坐标，带初始化保护和距离阈值。
     * 仅首次调用或坐标变化 >100 单位时生效，避免反复居中打断用户操作。
     *
     * 已注册 [CameraAnimator] 时使用平滑动画过渡，否则瞬间跳转。
     */
    fun tryCenterOn(wx: Float, wy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        if (!hasInitialized || abs(wx - lastCenterX) > 100f || abs(wy - lastCenterY) > 100f) {
            val targetX = wx - viewportWidth / (2f * scale)
            val targetY = wy - viewportHeight / (2f * scale)
            val anim = animator
            if (anim != null) {
                anim.animateTo(CameraTarget(targetX, targetY))
            } else {
                centerOn(wx, wy)
            }
            lastCenterX = wx
            lastCenterY = wy
            hasInitialized = true
        }
    }

    /**
     * 直接设置相机位置（用于惯性滑行 / Fling 动画 / CameraAnimator）。
     * 值会被 [clamp] 限制在世界边界内。
     */
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

    /**
     * 设置缩放值（用于 CameraAnimator）。钳制到合法范围。
     */
    override fun applyScale(newScale: Float) {
        scale = newScale.coerceIn(MIN_ZOOM, MAX_ZOOM)
        userScale = true
        clamp()
    }

    /**
     * 判断世界坐标点是否在视口可见范围内。
     * @param margin 视口外扩检测边距（世界坐标单位）
     */
    override fun isVisible(wx: Float, wy: Float, margin: Float): Boolean {
        if (viewportWidth <= 0 || viewportHeight <= 0) return true
        val sx = worldToScreenX(wx)
        val sy = worldToScreenY(wy)
        val m = margin * scale
        return sx >= -m && sx <= viewportWidth + m &&
               sy >= -m && sy <= viewportHeight + m
    }

    /**
     * 重置相机到初始状态（位置归零，清除初始化标记和用户缩放标记）。
     */
    override fun reset() {
        hasInitialized = false
        userScale = false
        vpInitialized = false
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
