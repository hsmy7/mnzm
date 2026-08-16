package com.xianxia.sect.ui.game.map.sect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.xianxia.sect.core.animation.CameraAnimator
import com.xianxia.sect.core.animation.CameraTarget
import com.xianxia.sect.core.camera.CameraState
import com.xianxia.sect.ui.game.map.BaseCameraState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 宗门地图相机状态。
 *
 * 继承 [BaseCameraState] 获得平移/缩放/边界钳制等公共实现，
 * 在此添加宗门地图特有的 [tryCenterOn] 智能居中、[CameraAnimator] 动画支持、
 * 以及「缩放中值」初始视角策略。
 *
 * 核心缩放策略：
 * - 缩放范围 [minScaleBound, MAX_ZOOM]：下界取安全值，保证缩小视角时视口
 *   不超出世界边界（不看到地图外）；上界为全局 [CameraState.MAX_ZOOM]
 * - 初始视角 = 缩放范围上下界的几何中值 √(minScaleBound × MAX_ZOOM)，
 *   保证从初始视角向放大/缩小两端可缩放的倍数一致
 *
 * 支持动态缩放（scale），v4.0.45+ 新增用户缩放（双指捏合 / 双击）：
 * - 默认缩放为缩放区间几何中值，各设备可缩放倍数一致
 * - 用户可通过 [zoom] / 缩放按钮 +/- / 双击缩放调整
 *
 * @param worldWidth 世界像素宽度
 * @param worldHeight 世界像素高度
 * @param worldWidthCells 世界水平格数（用于计算 tileSize = worldWidth / worldWidthCells）
 */
@Stable
class SectCameraState(
    worldWidth: Float,
    worldHeight: Float,
    val worldWidthCells: Int = 128
) : BaseCameraState(worldWidth, worldHeight) {

    /** 初始居中标记 */
    private var hasInitialized = false
    private var lastCenterX = 0f
    private var lastCenterY = 0f

    /** 平滑动画引擎（可选），由 UI 层注入，用于 [tryCenterOn] 等编程式移动 */
    private var animator: CameraAnimator? = null

    /**
     * 设置平滑动画引擎引用。
     * 设置后 [tryCenterOn] 将使用动画过渡而非瞬间跳转。
     * 切换实例时自动取消前一个。
     */
    fun setAnimator(anim: CameraAnimator) {
        animator?.cancel()
        animator = anim
    }

    /** 自动居中触发阈值（世界像素），避免反复居中打断用户操作 */
    private companion object {
        const val CENTER_THRESHOLD = 100f
    }

    /**
     * 更新视口尺寸。
     *
     * 当视口尺寸变化超过 [CENTER_THRESHOLD] 时（如横竖屏旋转），
     * 重置居中标记，使 [tryCenterOn] 能重新居中到世界中心。
     */
    override fun updateViewport(w: Int, h: Int) {
        val prevW = viewportWidth
        val prevH = viewportHeight
        super.updateViewport(w, h)
        // 横竖屏切换（宽高变化超过阈值）时重置居中标记
        if (prevW > 0 && prevH > 0 &&
            (abs(w - prevW) > CENTER_THRESHOLD || abs(h - prevH) > CENTER_THRESHOLD)
        ) {
            hasInitialized = false
        }
    }

    /**
     * 计算初始缩放值 — 缩放范围上下界的几何中值。
     *
     * 缩放范围 = [safeMinScale, MAX_ZOOM]：
     * - 下界 [safeMinScale]：保证缩小视角时视口不超出世界边界（不看到地图外）
     * - 上界 [CameraState.MAX_ZOOM]：全局最大放大
     * - 初始视角取几何中值 √(下界 × 上界)，使「可缩小倍数」与「可放大倍数」一致
     *
     * ```
     * minScale = max(MIN_ZOOM, vpW/worldWidth, vpH/worldHeight)
     * defaultScale = sqrt(minScale × MAX_ZOOM)
     * ```
     *
     * @param vpW 视口宽度（像素）
     * @param vpH 视口高度（像素）
     */
    override fun computeDefaultScale(vpW: Int, vpH: Int): Float {
        if (vpW <= 0 || vpH <= 0) {
            return scale.coerceIn(minScaleBound(), CameraState.MAX_ZOOM)
        }
        val minScale = safeMinScale(vpW, vpH)
        return sqrt(minScale * CameraState.MAX_ZOOM)
    }

    /**
     * 用户缩放最小下界 — 保证缩小视角时视口不超出世界边界（不看到地图外）。
     * 取 max(全局 MIN_ZOOM, 视口宽/世界宽, 视口高/世界高)：缩放不低于该值即可
     * 保证横向/纵向至少一个维度的视口不超出世界。
     */
    override fun minScaleBound(): Float {
        if (viewportWidth <= 0 || viewportHeight <= 0) return CameraState.MIN_ZOOM
        return safeMinScale(viewportWidth, viewportHeight)
    }

    /** 安全最小缩放：视口尺寸与最小下界的最大值 */
    private fun safeMinScale(vpW: Int, vpH: Int): Float = max(
        CameraState.MIN_ZOOM,
        max(vpW.toFloat() / worldWidth, vpH.toFloat() / worldHeight)
    )

    /**
     * 尝试居中到指定坐标，带初始化保护和距离阈值。
     * 仅首次调用或坐标变化 >[CENTER_THRESHOLD] 单位时生效，
     * 避免反复居中打断用户操作。
     *
     * 已注册 [CameraAnimator] 时使用平滑动画过渡，否则瞬间跳转。
     */
    fun tryCenterOn(wx: Float, wy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        val shouldCenter = !hasInitialized ||
            abs(wx - lastCenterX) > CENTER_THRESHOLD ||
            abs(wy - lastCenterY) > CENTER_THRESHOLD
        if (shouldCenter) {
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
     * 重置相机到初始状态。
     * 清除居中标记、用户缩放标记，位置归零。
     */
    override fun reset() {
        animator?.cancel()
        hasInitialized = false
        lastCenterX = 0f
        lastCenterY = 0f
        super.reset()
    }
}

/**
 * 创建并记住 [SectCameraState] 实例。
 * @param worldWidth 世界宽度（像素）
 * @param worldHeight 世界高度（像素）
 * @param worldWidthCells 世界水平格数（默认 128）
 */
@Composable
fun rememberSectCamera(
    worldWidth: Float,
    worldHeight: Float,
    worldWidthCells: Int = 128
): SectCameraState = remember(worldWidth, worldHeight, worldWidthCells) {
    SectCameraState(worldWidth, worldHeight, worldWidthCells)
}
