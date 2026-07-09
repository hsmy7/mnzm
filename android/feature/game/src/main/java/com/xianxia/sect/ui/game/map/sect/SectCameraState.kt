package com.xianxia.sect.ui.game.map.sect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.xianxia.sect.core.animation.CameraAnimator
import com.xianxia.sect.core.animation.CameraTarget
import com.xianxia.sect.core.camera.CameraState
import com.xianxia.sect.ui.game.map.BaseCameraState
import kotlin.math.abs

/**
 * 宗门地图相机状态。
 *
 * 继承 [BaseCameraState] 获得平移/缩放/边界钳制等公共实现，
 * 在此添加宗门地图特有的 [tryCenterOn] 智能居中、[CameraAnimator] 动画支持、
 * 以及恒定可见格数缩放策略（Clash of Clans `visible_columns` 模式）。
 *
 * 核心缩放策略：所有设备水平显示恒定 [VISIBLE_COLS] 个地图格，
 * 垂直方向随设备屏占比自然变化。
 *
 * 支持动态缩放（scale），v4.0.45+ 新增用户缩放：
 * - 默认缩放自适应设备视口尺寸，保证各设备看到相同水平视野
 * - 用户可通过 [zoom] / 缩放按钮 +/- / 双击缩放调整
 * - 缩放范围 [MIN_ZOOM, MAX_ZOOM] = [0.3, 3.0]
 *
 * @param worldWidth 世界像素宽度
 * @param worldHeight 世界像素高度
 * @param worldWidthCells 世界水平格数（用于计算 tileSize = worldWidth / worldWidthCells）
 */
@Stable
class SectCameraState(
    worldWidth: Float,
    worldHeight: Float,
    private val worldWidthCells: Int = 128
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

    /** 缩放范围与阈值常量 */
    companion object {
        /** 水平可见格数 — 所有设备固定显示相同列数（对标 Clash of Clans visible_columns） */
        const val VISIBLE_COLS = 52
        /** 自动居中触发阈值（世界像素），避免反复居中打断用户操作 */
        private const val CENTER_THRESHOLD = 100f
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
     * 计算恒定可见格数缩放值（Clash of Clans `visible_columns` 模式）。
     *
     * 所有设备水平显示相同数量 [VISIBLE_COLS] 个地图格，确保：
     * - 同一布局在所有手机上看到同一水平范围（公平性）
     * - 垂直方向自然适配各设备屏占比
     * - 不超出世界边界（防溢出保护）
     *
     * ```
     * tileSize = worldWidth / worldWidthCells
     * targetScale = vpW / (VISIBLE_COLS × tileSize)
     * finalScale = maxOf(targetScale, vpW/worldWidth, vpH/worldHeight)
     * ```
     *
     * 参考行业做法（27 条来源）：
     * - Supercell EP2444134: `visible_columns` 抽象缩放单位
     * - UPC Thesis: Zoom 以"可见行列数"为单位而非像素
     * - Clash of Clans: 所有设备看到相同数量格子的设计哲学
     *
     * @param vpW 视口宽度（像素）
     * @param vpH 视口高度（像素）
     */
    override fun computeDefaultScale(vpW: Int, vpH: Int): Float {
        if (vpW <= 0 || vpH <= 0) {
            return scale.coerceIn(CameraState.MIN_ZOOM, CameraState.MAX_ZOOM)
        }
        // tileSize = worldWidth / worldWidthCells
        val tileSize = worldWidth / worldWidthCells.toFloat()
        // 目标缩放：水平显示 VISIBLE_COLS 个地图格
        val targetScale = vpW.toFloat() / (VISIBLE_COLS * tileSize)
        // 防溢出保护：视口不超出世界边界
        val minSafeScale = maxOf(
            vpW.toFloat() / worldWidth,
            vpH.toFloat() / worldHeight
        )
        return maxOf(targetScale, minSafeScale)
            .coerceIn(CameraState.MIN_ZOOM, CameraState.MAX_ZOOM)
    }

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
