package com.xianxia.sect.ui.components

import android.util.Log
import android.view.Window
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "ImeGuard"

/**
 * IME 键盘显隐动画状态跟踪器
 * （依据 docs/ime-android-system-research.md M3/M9/M10）。
 *
 * 在窗口 decorView 上安装 [WindowInsetsAnimationCompat.Callback]，跟踪键盘
 * 显隐动画是否进行中（[isAnimating]），供系统栏冻结/恢复链路与自动聚焦重试使用：
 * - 动画进行中（onPrepare）→ 系统栏必须冻结、禁止一切窗口操作（hide/show 与
 *   IME 动画并发即闪屏来源，M9）
 * - 动画结束（onEnd，含动画取消场景）→ 触发恢复回调，宿主经 isVisible 复查 +
 *   延时兜底恢复系统栏隐藏（替代固定 350ms 主路径，M10 stale insets 防御）
 *
 * 多窗口计数语义：[attach] 可由 Activity 与各 Dialog 窗口
 * 分别调用（[DialogSystemBarGuard] 挂载时补 attach——对话框窗口输入的键盘
 * 动画纳入全局跟踪，恢复链路与自动聚焦共享动画门控）。
 * [isAnimating] 为计数真值：任一窗口动画进行中即为 true，全部结束才恢复。
 *
 * 安装语义：[attach] 在 decorView 上 set 动画回调（`DISPATCH_MODE_CONTINUE_ON_SUBTREE`
 * 不截断子树分发，与 Compose 自身动画共存）；同一窗口重复 attach 幂等。
 */
object ImeAnimationTracker {

    /** 进行中的键盘动画窗口数（任一 > 0 即全局动画中；主线程回调写，任意线程读） */
    private val animatingCount = AtomicInteger(0)

    /** 任一已接管窗口的键盘动画当前是否进行中（计数真值，跨线程一致） */
    val isAnimating: Boolean
        get() = animatingCount.get() > 0

    /** 动画结束回调（键盘收起动画 onEnd/onCancel 后触发，供宿主恢复系统栏隐藏） */
    private val onAnimationEndedListeners = CopyOnWriteArrayList<() -> Unit>()

    /** 已安装动画回调的窗口（弱引用防泄漏） */
    private val windows = CopyOnWriteArrayList<WeakReference<Window>>()

    /** 键盘动画回调（过滤 ime 类型；onProgress 原样透传不消费；onEnd 含取消场景统一触发） */
    private val animationCallback = object : WindowInsetsAnimationCompat.Callback(
        WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
    ) {
        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
            if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                onImeAnimationEvent(end = false)
            }
        }

        override fun onProgress(
            insets: WindowInsetsCompat,
            runningAnimations: MutableList<WindowInsetsAnimationCompat>
        ): WindowInsetsCompat = insets

        override fun onEnd(animation: WindowInsetsAnimationCompat) {
            if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                onImeAnimationEvent(end = true)
            }
        }
    }

    /**
     * 键盘动画事件（开始/结束）统一入口——计数语义核心（Robolectric 单测直接驱动）。
     *
     * 开始：计数 +1（任一窗口动画中全局即 true）；
     * 结束：计数 -1（多窗口动画互不干扰），全部结束（归零）才触发恢复回调。
     */
    @VisibleForTesting
    internal fun onImeAnimationEvent(end: Boolean) {
        if (end) {
            val remaining = animatingCount.updateAndGet { current ->
                if (current <= 0) 0 else current - 1
            }
            Log.d(TAG, "ImeAnimationTracker: 键盘动画结束 (count=$remaining)")
            if (remaining == 0) {
                notifyAnimationEnded()
            }
        } else {
            animatingCount.incrementAndGet()
            Log.d(TAG, "ImeAnimationTracker: 键盘动画开始 (count=${animatingCount.get()})")
        }
    }

    /**
     * 接管 [window] 的键盘动画跟踪（decorView 安装回调；幂等——同窗口重复 attach 零操作）。
     * MainActivity/GameActivity onCreate 与 DialogSystemBarGuard 挂载时调用。
     */
    fun attach(window: Window) {
        if (windows.any { it.get() === window }) return
        windows += WeakReference(window)
        ViewCompat.setWindowInsetsAnimationCallback(window.decorView, animationCallback)
    }

    /** 解除对 [window] 的动画跟踪（窗口销毁前调用；decorView 动画回调随 View 生命周期销毁） */
    fun detach(window: Window) {
        windows.removeAll { it.get() === window }
        recomputeGlobal()
    }

    /** 注册动画结束回调（宿主 Activity 解冻恢复链路，onDestroy 注销） */
    fun addOnAnimationEndedListener(listener: () -> Unit) {
        onAnimationEndedListeners += listener
    }

    /** 注销动画结束回调 */
    fun removeOnAnimationEndedListener(listener: () -> Unit) {
        onAnimationEndedListeners -= listener
    }

    private fun notifyAnimationEnded() {
        onAnimationEndedListeners.forEach { listener ->
            try {
                listener.invoke()
            } catch (_: Exception) {
                // 宿主可能已销毁，监听器异常不影响动画状态
            }
        }
    }

    /** 惰性清理窗口已销毁的条目；无存活窗口 → 全局回落非动画中 */
    private fun recomputeGlobal() {
        windows.removeAll { it.get() == null }
        if (windows.isEmpty()) {
            animatingCount.set(0)
        }
    }

    @VisibleForTesting
    internal fun setAnimatingForTest(animating: Boolean) {
        animatingCount.set(if (animating) 1 else 0)
    }

    @VisibleForTesting
    internal fun animatingCountForTest(): Int = animatingCount.get()

    @VisibleForTesting
    internal fun resetForTest() {
        animatingCount.set(0)
        windows.clear()
        onAnimationEndedListeners.clear()
    }
}
