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
 * 以及 [DEFAULT_SCALE] 默认视角高度策略。
 *
 * 支持动态缩放（scale），v4.0.45+ 新增用户缩放：
 * - 默认 scale=0.5（视角提高 50%，取整），让玩家初期看到更多地图
 * - 用户可通过 [zoom] / 缩放按钮 +/- / 双击缩放调整
 * - 缩放范围 [MIN_ZOOM, MAX_ZOOM] = [0.3, 3.0]
 */
@Stable
class SectCameraState(
    worldWidth: Float,
    worldHeight: Float
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
        /** 默认视角高度 scale，提高 50% 取整 */
        const val DEFAULT_SCALE = 0.5f
        /** 自动居中触发阈值（世界像素），避免反复居中打断用户操作 */
        private const val CENTER_THRESHOLD = 100f
    }

    /**
     * 计算默认缩放值。
     *
     * - 小视口（手机常见）：应用默认视角高度 [DEFAULT_SCALE]（0.5）
     * - 大视口（外接显示/平板）：应用 Fill 适配策略，确保地图填满视口无白边
     * - 屏幕旋转后再次调用此方法适配新尺寸
     */
    override fun computeDefaultScale(vpW: Int, vpH: Int): Float {
        val wf = vpW.toFloat()
        val hf = vpH.toFloat()
        return if (wf > worldWidth || hf > worldHeight) {
            maxOf(wf / worldWidth, hf / worldHeight)
        } else {
            DEFAULT_SCALE
        }
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
        super.reset()
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
