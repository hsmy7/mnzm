package com.xianxia.sect.ui.components

import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ImeGuard"

/**
 * 输入对话框挂载期间的窗口系统栏操作冻结作用域。
 *
 * 背景（2026-08 荣耀 X70 键盘频闪根治；2026-09 泄漏自愈升级）：
 * Activity 的 [android.view.WindowInsetsControllerCompat.hide]（hideSystemBars）会与
 * Android 15 强制 edge-to-edge 下"IME 可见期间系统接管导航栏"的行为对抗；
 * 荣耀 MagicOS 在键盘弹出/收起期间存在窗口焦点抖动（onWindowFocusChanged 反复回调），
 * 每次抖动都触发 hide() → insets 翻转 → 键盘收起再弹出，形成振荡回路。
 * 输入对话框挂载期间冻结一切系统栏窗口操作，切断该回路的放大器环节；
 * 对话框销毁后解冻并通过监听器触发宿主恢复系统栏隐藏。
 *
 * 泄漏自愈（2026-09 IME 状态机根治）：freezeCount 依赖 Compose onDispose 对称调用，
 * 异常路径（快速销毁 / key() 强制重组 / 组合中断）可致 onDispose 未执行 → 计数泄漏 →
 * isFrozen 恒 true → 系统栏永久不隐藏。现记录冻结起始时间戳，[isFrozen] 查询时若
 * 冻结时长超过 [FREEZE_LEAK_THRESHOLD_MS] 即强制归零并触发解冻监听器（Log.w 记录），
 * 保证"系统栏永久异常"不可能发生。
 *
 * 线程模型：进入/退出由 Compose [androidx.compose.runtime.DisposableEffect]（主线程）驱动；
 * 状态读取发生在 Activity 主线程回调；[AtomicInteger] 与 [CopyOnWriteArrayList] 保证多线程
 * 读写下的一致性。
 */
object SystemBarFreezeScope {

    /** 冻结泄漏自愈阈值（毫秒）：超过该时长仍处于冻结 → 视为 onDispose 泄漏，强制解冻 */
    private const val FREEZE_LEAK_THRESHOLD_MS = 10 * 60 * 1000L

    private val freezeCount = AtomicInteger(0)

    /** 最近一次"0→1"冻结的起始时间戳（elapsedRealtime，泄漏自愈用） */
    private val freezeEnteredAtMs = AtomicLong(0L)

    /**
     * 时间源（测试可注入假时钟；默认 SystemClock.elapsedRealtime）。
     * 泄漏自愈依赖时间差判定，注入后纯 JVM 测试可脱离 Android 运行时驱动。
     */
    @VisibleForTesting
    internal var freezeClock: () -> Long = { SystemClock.elapsedRealtime() }

    private val unfreezeListeners = CopyOnWriteArrayList<() -> Unit>()

    /** 是否处于冻结状态（存在至少一个活跃的输入对话框）；超时泄漏自动自愈 */
    val isFrozen: Boolean
        get() {
            val count = freezeCount.get()
            if (count <= 0) return false
            val enteredAt = freezeEnteredAtMs.get()
            if (freezeClock() - enteredAt > FREEZE_LEAK_THRESHOLD_MS) {
                Log.w(TAG, "SystemBarFreezeScope 泄漏自愈：冻结超时强制解冻")
                forceUnfreeze()
                return false
            }
            return true
        }

    /** 输入对话框挂载：冻结计数 +1（0→1 时记录起始时间戳） */
    fun enterFreeze() {
        if (freezeCount.getAndIncrement() == 0) {
            freezeEnteredAtMs.set(freezeClock())
        }
    }

    /**
     * 输入对话框销毁：冻结计数 -1；归零时通知所有监听器
     * （宿主 Activity 借此恢复系统栏隐藏）。对未冻结状态调用是安全的 no-op。
     */
    fun exitFreeze() {
        if (freezeCount.get() <= 0) return
        if (freezeCount.decrementAndGet() == 0) {
            notifyUnfreeze()
        }
    }

    /**
     * 注册解冻监听器（宿主 Activity 在 onCreate 注册，onDestroy 注销）。
     * 重复注册同一实例不会去重，调用方自行保证对称。
     */
    fun addOnUnfreezeListener(listener: () -> Unit) {
        unfreezeListeners += listener
    }

    /** 注销解冻监听器 */
    fun removeOnUnfreezeListener(listener: () -> Unit) {
        unfreezeListeners -= listener
    }

    /** 泄漏自愈：强制归零并通知解冻监听器（对未冻结状态安全 no-op） */
    private fun forceUnfreeze() {
        if (freezeCount.get() <= 0) return
        freezeCount.set(0)
        notifyUnfreeze()
    }

    private fun notifyUnfreeze() {
        unfreezeListeners.forEach { listener ->
            try {
                listener.invoke()
            } catch (_: Exception) {
                // 宿主可能已销毁，监听器异常不影响解冻语义
            }
        }
    }

    @VisibleForTesting
    internal fun resetForTest() {
        freezeCount.set(0)
        freezeEnteredAtMs.set(0L)
        unfreezeListeners.clear()
        freezeClock = { SystemClock.elapsedRealtime() }
    }
}
