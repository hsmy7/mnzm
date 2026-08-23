package com.xianxia.sect.ui.components

import android.util.Log
import android.view.View
import android.view.Window
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "ImeGuard"

/**
 * 键盘（IME）可见性跟踪器（2026-08 荣耀 X70 键盘频闪根治；
 * 2026-08 GT 系列根治升级为多窗口跟踪）。
 *
 * 在 [Window] 的 decorView 上安装 insets 监听，跟踪 [WindowInsetsCompat.Type.ime]
 * 的可见性，供 [SystemBarHidePolicy] 在键盘可见期间冻结系统栏隐藏操作。
 *
 * 多窗口语义（2026-08 荣耀 GT 系列键盘频闪根治）：
 * 旧实现只接管 Activity 窗口——键盘在平台 Dialog 窗口内弹出时，IME insets 只
 * 派发给**获得输入焦点**的窗口（Dialog 窗口），Activity decorView 收不到，
 * `isImeVisible` 恒 false，`SystemBarHidePolicy` 第二守卫在 Dialog 输入场景
 * 完全失效（放大器 A）。现改为窗口集合跟踪：Activity 与各 Dialog 窗口各自
 * 独立跟踪，任一窗口键盘可见 → 全局 [isImeVisible] = true；
 * [isImeVisibleFor] 供 [DialogSystemBarGuard] 按窗口查询（放大器 B 防御）。
 *
 * 关键约束：监听器**原样透传** insets（[ViewCompat.onApplyWindowInsets]），
 * 绝不消费——否则会截断 Compose 的 WindowInsets 分发，破坏 imePadding 布局。
 * 仅当可见性发生**翻转**时才记录日志，避免键盘动画期间每帧刷屏。
 */
object ImeVisibilityTracker {

    /** 任一已接管窗口的键盘当前是否可见（跨线程一致，主线程写、任意线程读） */
    @Volatile
    var isImeVisible: Boolean = false
        private set

    /** 单个窗口的 IME 跟踪状态（弱引用防 Activity/Dialog 窗口泄漏） */
    private class WindowState(window: Window) {
        val windowRef = WeakReference(window)
        @Volatile
        var imeVisible: Boolean = false
        /** 本窗口可见性翻转回调（如 [DialogSystemBarGuard] 的 IME 感知切换） */
        val onFlipListeners = CopyOnWriteArrayList<() -> Unit>()
    }

    private val windows = CopyOnWriteArrayList<WindowState>()

    /**
     * IME 可见性提取函数（默认实现走 androidx.core 官方 [WindowInsetsCompat.isVisible] +
     * IME 底部高度双信号；测试可注入替换——Robolectric 对 android.view.WindowInsets 的
     * ime 类型支持不全，注入后状态机逻辑可脱离框架限制验证）。
     *
     * 双信号（2026-08 第四根因键盘频闪根治）：可见性标志 或 IME 底部高度 > 0 任一成立
     * 即视为键盘可见——ADJUST_PAN 等不 resize 的窗口在部分国产 ROM 上可见性标志可能
     * 不翻转（insets 可见性与窗口 resize 语义耦合），高度信号作为兜底，增强多窗口
     * 跟踪的鲁棒性（解冻恢复链路的二次校验依赖全局可见性的准确性）。
     */
    @VisibleForTesting
    internal var imeVisibilityExtractor: (WindowInsetsCompat) -> Boolean =
        ::defaultImeVisibilityExtractor

    /**
     * 接管 [window] 的 IME 可见性跟踪。
     *
     * 幂等：同一窗口重复 attach 不会重装 insets 监听（避免覆盖 Compose 的
     * WindowInsets 分发链），仅追加 [onFlip] 翻转回调。
     * 新窗口（Activity 重建 / Compose Dialog 挂载）自动加入跟踪集合。
     */
    fun attach(window: Window, onFlip: (() -> Unit)? = null) {
        val existing = windows.firstOrNull { it.windowRef.get() === window }
        if (existing != null) {
            if (onFlip != null) existing.onFlipListeners += onFlip
            return
        }
        val state = WindowState(window)
        if (onFlip != null) state.onFlipListeners += onFlip
        windows += state
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            onInsetsApplied(view, insets, window)
        }
    }

    /**
     * 解除对 [window] 的跟踪（窗口销毁前调用）。
     *
     * 移除条目前将本窗口状态复位为不可见——窗口销毁后其 IME 必然收起，
     * 避免"全局仍残留可见"导致 [SystemBarHidePolicy] 永久放行 hide()。
     */
    fun detach(window: Window) {
        windows.removeAll { state ->
            if (state.windowRef.get() === window) {
                state.imeVisible = false
                true
            } else {
                false
            }
        }
        recomputeGlobal()
    }

    /** 指定窗口当前是否键盘可见（窗口已销毁/未接管返回 false） */
    fun isImeVisibleFor(window: Window?): Boolean {
        if (window == null) return false
        return windows.firstOrNull { it.windowRef.get() === window }?.imeVisible ?: false
    }

    /** 默认双信号实现（初始化与 resetForTest 共用，保证测试间隔离后语义不漂移） */
    private fun defaultImeVisibilityExtractor(insets: WindowInsetsCompat): Boolean =
        insets.isVisible(WindowInsetsCompat.Type.ime()) ||
            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0

    /** insets 回调处理（提取为独立函数便于 Robolectric 单测直接驱动） */
    internal fun onInsetsApplied(
        view: View,
        insets: WindowInsetsCompat,
        window: Window
    ): WindowInsetsCompat {
        val state = windows.firstOrNull { it.windowRef.get() === window }
            ?: return ViewCompat.onApplyWindowInsets(view, insets)
        val visible = imeVisibilityExtractor(insets)
        if (visible != state.imeVisible) {
            state.imeVisible = visible
            state.onFlipListeners.forEach { listener ->
                try {
                    listener.invoke()
                } catch (_: Exception) {
                    // 守卫回调异常不影响 IME 状态机
                }
            }
            recomputeGlobal()
            Log.d(TAG, "IME 可见性翻转: $visible")
        }
        // 原样透传，不消费 insets，保证 Compose WindowInsets 分发链路完整
        return ViewCompat.onApplyWindowInsets(view, insets)
    }

    /** 全局可见性 = 任一存活窗口可见；惰性清理窗口已销毁的条目 */
    private fun recomputeGlobal() {
        windows.removeAll { it.windowRef.get() == null }
        isImeVisible = windows.any { it.imeVisible }
    }

    /** 已接管窗口数（测试断言用） */
    @VisibleForTesting
    internal fun windowCountForTest(): Int = windows.size

    @VisibleForTesting
    internal fun setImeVisibleForTest(visible: Boolean) {
        isImeVisible = visible
    }

    @VisibleForTesting
    internal fun resetForTest() {
        isImeVisible = false
        windows.clear()
        imeVisibilityExtractor = ::defaultImeVisibilityExtractor
    }
}
