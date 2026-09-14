package com.xianxia.sect.ui.components

import android.os.SystemClock
import android.util.Log
import android.view.Window
import androidx.annotation.VisibleForTesting
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ImeGuard"

/**
 * 输入对话框挂载期间的 Dialog 窗口系统栏冻结作用域。
 *
 * 背景：[SystemBarFreezeScope] 只冻结宿主 Activity 的 `hideSystemBars()`（经
 * [SystemBarHidePolicy]），但平台 Dialog 窗口自身的系统栏隐藏
 * （[DialogSystemBarGuard] 的 HIDE_NAVIGATION / [androidx.core.view.WindowInsetsControllerCompat.hide]）
 * 不经 [SystemBarHidePolicy]——输入对话框的 `freezeSystemBars = true` 对 Dialog 窗口
 * 零约束。在 HyperOS 2 / MagicOS 8/9 / Android 15 edge-to-edge 下，键盘可见期间
 * Dialog 窗口仍执行系统栏隐藏/切换，与 IME 转场动画对抗形成"键盘弹出→收起→再弹出"
 * 振荡回路（详见 rules/dialog-soft-input-guard.md）。
 *
 * 本作用域按 Window 跟踪冻结计数：含输入框的 Dialog 窗口挂载期间冻结（[enterFreeze]），
 * [DialogSystemBarGuard] 查询 [isFrozen] 决定是否隐藏导航栏；嵌套内联输入框
 * （[InlineStandardPromptDialog] 渲染于平台 Dialog 窗口内）自动传导冻结到外层窗口。
 * 冻结计数 0↔1 翻转时通知监听器，供 guard 动态恢复/延迟恢复导航栏隐藏。
 *
 * 泄漏自愈（对齐 [SystemBarFreezeScope]）：freezeCount 依赖 Compose
 * onDispose 对称调用，异常路径（快速销毁 / key() 强制重组 / 组合中断）可致
 * onDispose 未执行 → 计数泄漏 → `isFrozen(window)` 恒 true → 该窗口系统栏行为
 * 永久异常。现记录冻结起始时间戳，[isFrozen] 查询时若冻结时长超过
 * [FREEZE_LEAK_THRESHOLD_MS] 即强制归零并触发翻转监听器（Log.w 记录），
 * 保证"窗口级系统栏永久异常"不可能发生。
 *
 * 线程模型：进入/退出由 Compose [androidx.compose.runtime.DisposableEffect]（主线程）驱动；
 * 状态读取发生在 Activity 主线程回调；[AtomicInteger] 与 [CopyOnWriteArrayList] 保证多线程
 * 读写下的一致性。
 */
object DialogSystemBarFreezeScope {

    /** 冻结泄漏自愈阈值（毫秒）：超过该时长仍处于冻结 → 视为 onDispose 泄漏，强制解冻 */
    private const val FREEZE_LEAK_THRESHOLD_MS = 10 * 60 * 1000L

    /**
     * 时间源（测试可注入假时钟；默认 SystemClock.elapsedRealtime）。
     * 泄漏自愈依赖时间差判定，注入后纯 JVM 测试可脱离 Android 运行时驱动。
     */
    @VisibleForTesting
    internal var freezeClock: () -> Long = { SystemClock.elapsedRealtime() }

    /** 单个窗口的冻结状态（弱引用防 Dialog 窗口泄漏） */
    private class WindowState(window: Window) {
        val windowRef = WeakReference(window)
        val freezeCount = AtomicInteger(0)
        /** 最近一次"0→1"冻结的起始时间戳（elapsedRealtime，泄漏自愈用） */
        val freezeEnteredAtMs = AtomicLong(0L)
        /** 本窗口冻结 0↔1 翻转回调（如 [DialogSystemBarGuard] 的导航栏恢复/延迟隐藏） */
        val onFrozenChangedListeners = CopyOnWriteArrayList<() -> Unit>()
    }

    private val windows = CopyOnWriteArrayList<WindowState>()

    /** [window] 当前是否处于冻结状态（存在至少一个活跃的输入对话框）；超时泄漏自动自愈 */
    fun isFrozen(window: Window?): Boolean {
        if (window == null) return false
        pruneDeadWindows()
        val state = windows.firstOrNull { it.windowRef.get() === window }
        return state != null && state.freezeCount.get() > 0 && !freezeLeakExpired(state)
    }

    /** 冻结是否已超时泄漏（超时强制解冻并通知翻转监听器） */
    private fun freezeLeakExpired(state: WindowState): Boolean {
        val enteredAt = state.freezeEnteredAtMs.get()
        val expired = freezeClock() - enteredAt > FREEZE_LEAK_THRESHOLD_MS
        if (expired) {
            Log.w(TAG, "DialogSystemBarFreezeScope 泄漏自愈：窗口 ${state.windowRef.get()} 冻结超时强制解冻")
            forceUnfreeze(state)
        }
        return expired
    }

    /** 输入对话框挂载：本窗口冻结计数 +1；0→1 翻转时通知监听器 */
    fun enterFreeze(window: Window) {
        pruneDeadWindows()
        val state = windows.firstOrNull { it.windowRef.get() === window }
            ?: WindowState(window).also { windows += it }
        val wasFrozen = state.freezeCount.get() > 0
        if (state.freezeCount.getAndIncrement() == 0) {
            state.freezeEnteredAtMs.set(freezeClock())
        }
        if (!wasFrozen) {
            notifyFrozenChanged(state)
        }
    }

    /**
     * 输入对话框销毁：本窗口冻结计数 -1；1→0 翻转时通知监听器并清理条目。
     * 对未冻结状态调用是安全的 no-op。
     */
    fun exitFreeze(window: Window) {
        pruneDeadWindows()
        val state = windows.firstOrNull { it.windowRef.get() === window } ?: return
        if (state.freezeCount.get() <= 0) return
        val remaining = state.freezeCount.decrementAndGet()
        if (remaining == 0) {
            notifyFrozenChanged(state)
            windows.removeAll { it === state }
        }
    }

    /** 注册本窗口冻结翻转监听器（guard 挂载时注册，卸载时注销） */
    fun addOnFrozenChangedListener(window: Window, listener: () -> Unit) {
        pruneDeadWindows()
        val state = windows.firstOrNull { it.windowRef.get() === window }
            ?: WindowState(window).also { windows += it }
        state.onFrozenChangedListeners += listener
    }

    /** 注销本窗口冻结翻转监听器 */
    fun removeOnFrozenChangedListener(window: Window, listener: () -> Unit) {
        pruneDeadWindows()
        val state = windows.firstOrNull { it.windowRef.get() === window } ?: return
        state.onFrozenChangedListeners -= listener
    }

    private fun notifyFrozenChanged(state: WindowState) {
        state.onFrozenChangedListeners.forEach { listener ->
            try {
                listener.invoke()
            } catch (_: Exception) {
                // 宿主可能已销毁，监听器异常不影响冻结语义
            }
        }
    }

    /** 泄漏自愈：本窗口强制归零并通知翻转监听器（对未冻结状态安全 no-op） */
    private fun forceUnfreeze(state: WindowState) {
        if (state.freezeCount.get() <= 0) return
        state.freezeCount.set(0)
        notifyFrozenChanged(state)
        windows.removeAll { it === state }
    }

    /** 惰性清理窗口已销毁的条目（窗口销毁后 exitFreeze 可能未对称调用） */
    private fun pruneDeadWindows() {
        windows.removeAll { it.windowRef.get() == null }
    }

    @VisibleForTesting
    internal fun resetForTest() {
        windows.clear()
        freezeClock = { SystemClock.elapsedRealtime() }
    }
}
