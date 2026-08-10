package com.xianxia.sect.ui.game.sect

import android.content.Context
import android.util.Log
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * 显示刷新率提供器 — 接口抽象（测试可注入固定值；iOS 对等：
 * `CADisplayLink.maximumFramesPerSecond`，ProMotion 动态刷新率同接口）。
 */
fun interface DisplayFpsProvider {

    /**
     * 当前显示刷新率。
     *
     * @return 刷新率 Hz；未知/异常返回 0（调用方按 60Hz 兜底）
     */
    fun displayFps(): Int
}

/**
 * 系统实现：WindowManager 主显示刷新率（60Hz 兜底语义由调用方保证）。
 *
 * ## 刷新率动态变化
 * 渲染线程每帧循环调用（refreshRate 是 WindowManager 缓存字段，代价可忽略），
 * 高刷面板 120Hz ↔ 60Hz 切换即时生效，无需 DisplayListener 注册。
 */
class SystemDisplayFpsProvider(context: Context) : DisplayFpsProvider {

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    /**
     * @Suppress(TooGenericExceptionCaught)：WindowManager/Display 对异常 ROM 抛精确
     * RuntimeException 子类不可穷举（进程销毁窗口）——防御性兜底返回 0，
     * 调用方按 60Hz 兜底节拍，任何异常不得杀死渲染循环
     */
    @Suppress("TooGenericExceptionCaught")
    override fun displayFps(): Int {
        return try {
            val rate = windowManager?.defaultDisplay?.refreshRate ?: 0f
            if (rate > 0f && rate.isFinite()) rate.roundToInt() else 0
        } catch (e: RuntimeException) {
            Log.w(TAG, "displayFps read failed: ${e.message}")
            0
        }
    }

    companion object {
        private const val TAG = "DisplayFpsProvider"
    }
}
