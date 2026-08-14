package com.xianxia.sect.core.util

import android.util.Log
import android.view.View
import android.view.Window
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.lang.ref.WeakReference

private const val TAG = "ImeGuard"

/**
 * 键盘（IME）可见性跟踪器（2026-08 荣耀 X70 键盘频闪根治）。
 *
 * 在 [Window] 的 decorView 上安装 insets 监听，跟踪 [WindowInsetsCompat.Type.ime]
 * 的可见性，供 [SystemBarHidePolicy] 在键盘可见期间冻结系统栏隐藏操作。
 *
 * 关键约束：监听器**原样透传** insets（[ViewCompat.onApplyWindowInsets]），
 * 绝不消费——否则会截断 Compose 的 WindowInsets 分发，破坏 imePadding 布局。
 * 仅当可见性发生**翻转**时才记录日志，避免键盘动画期间每帧刷屏。
 */
object ImeVisibilityTracker {

    /** 键盘当前是否可见（跨线程一致，主线程写、任意线程读） */
    @Volatile
    var isImeVisible: Boolean = false
        private set

    /** 已接管的窗口弱引用（幂等防重复安装监听，窗口销毁后自动失效） */
    private var attachedWindowRef = WeakReference<Window>(null)

    /**
     * IME 可见性提取函数（默认实现走 androidx.core 官方 [WindowInsetsCompat.isVisible]；
     * 测试可注入替换——Robolectric 对 android.view.WindowInsets 的 ime 类型支持不全，
     * 注入后状态机逻辑可脱离框架限制验证）。
     */
    @VisibleForTesting
    internal var imeVisibilityExtractor: (WindowInsetsCompat) -> Boolean = { insets ->
        insets.isVisible(WindowInsetsCompat.Type.ime())
    }

    /**
     * 接管 [window] 的 IME 可见性跟踪。重复对同一窗口调用是幂等 no-op；
     * 新窗口（如 Activity 重建）会替换旧监听目标。
     */
    fun attach(window: Window) {
        if (attachedWindowRef.get() === window) return
        attachedWindowRef = WeakReference(window)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            onInsetsApplied(view, insets)
        }
    }

    /** insets 回调处理（提取为独立函数便于 Robolectric 单测直接驱动） */
    internal fun onInsetsApplied(view: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val visible = imeVisibilityExtractor(insets)
        if (visible != isImeVisible) {
            isImeVisible = visible
            Log.d(TAG, "IME 可见性翻转: $visible")
        }
        // 原样透传，不消费 insets，保证 Compose WindowInsets 分发链路完整
        return ViewCompat.onApplyWindowInsets(view, insets)
    }

    @VisibleForTesting
    internal fun setImeVisibleForTest(visible: Boolean) {
        isImeVisible = visible
    }

    @VisibleForTesting
    internal fun resetForTest() {
        isImeVisible = false
        attachedWindowRef = WeakReference(null)
        imeVisibilityExtractor = { insets -> insets.isVisible(WindowInsetsCompat.Type.ime()) }
    }
}
