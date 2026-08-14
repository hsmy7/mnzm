package com.xianxia.sect.ui.components

import androidx.annotation.VisibleForTesting
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 输入对话框挂载期间的窗口系统栏操作冻结作用域。
 *
 * 背景（2026-08 荣耀 X70 键盘频闪根治）：
 * Activity 的 [android.view.WindowInsetsControllerCompat.hide]（hideSystemBars）会与
 * Android 15 强制 edge-to-edge 下"IME 可见期间系统接管导航栏"的行为对抗；
 * 荣耀 MagicOS 在键盘弹出/收起期间存在窗口焦点抖动（onWindowFocusChanged 反复回调），
 * 每次抖动都触发 hide() → insets 翻转 → 键盘收起再弹出，形成振荡回路。
 * 输入对话框挂载期间冻结一切系统栏窗口操作，切断该回路的放大器环节；
 * 对话框销毁后解冻并通过监听器触发宿主恢复系统栏隐藏。
 *
 * 线程模型：进入/退出由 Compose [DisposableEffect]（主线程）驱动；
 * 状态读取发生在 Activity 主线程回调；[AtomicInteger] 与 [CopyOnWriteArrayList]
 * 保证多线程读写下的一致性。
 */
object SystemBarFreezeScope {

    private val freezeCount = AtomicInteger(0)
    private val unfreezeListeners = CopyOnWriteArrayList<() -> Unit>()

    /** 是否处于冻结状态（存在至少一个活跃的输入对话框） */
    val isFrozen: Boolean
        get() = freezeCount.get() > 0

    /** 输入对话框挂载：冻结计数 +1 */
    fun enterFreeze() {
        freezeCount.incrementAndGet()
    }

    /**
     * 输入对话框销毁：冻结计数 -1；归零时通知所有监听器
     * （宿主 Activity 借此恢复系统栏隐藏）。对未冻结状态调用是安全的 no-op。
     */
    fun exitFreeze() {
        if (freezeCount.get() <= 0) return
        if (freezeCount.decrementAndGet() == 0) {
            unfreezeListeners.forEach { listener ->
                try {
                    listener.invoke()
                } catch (_: Exception) {
                    // 宿主可能已销毁，监听器异常不影响解冻语义
                }
            }
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

    @VisibleForTesting
    internal fun resetForTest() {
        freezeCount.set(0)
        unfreezeListeners.clear()
    }
}
