package com.xianxia.sect.ui.components

import android.util.Log
import android.view.Window
import androidx.annotation.VisibleForTesting

private const val TAG = "ImeGuard"

/**
 * 文本输入会话状态机（显式状态操作，禁用 Toggle 语义）。
 *
 * 背景："是否显示输入框"若仅依赖 Compose 组合存在性（bool 语义），
 * 打开/关闭请求无幂等约束——异常组合时序（快速开→关→开）可产生重复创建 /
 * 重复弹收键盘。本状态机把每个输入会话建模为四态：
 *
 * ```
 * CLOSED → OPENING → OPEN          （打开请求 → 组件挂载 → 键盘可见性确认）
 * OPEN  → CLOSING → CLOSED         （关闭请求 → 组件销毁收键盘 → 会话结束）
 * ```
 *
 * **按窗口隔离**：会话以 `(Window 身份, sessionKey)`
 * 为唯一标识——不同 Activity/Dialog Window 的输入会话互不共享状态；
 * 同刻多会话可并存（状态隔离 + 日志可区分），单实例场景行为不变。
 *
 * 规则（硬约束，违反即日志告警；合法转换由 [open]/[close]/[markOpen]/
 * [finishClose] 内部保证，非法调用一律 no-op 并 Log.e）：
 * - OPENING/OPEN 状态下重复 [open]：幂等 no-op（**禁止 OPENING→OPENING / OPEN→OPEN 重复动作**）；
 * - CLOSED/CLOSING 状态下重复 [close]：幂等 no-op（**禁止重复执行关闭流程**）；
 * - CLOSING 中的新 [open]：只记 pendingReopen，**禁止 CLOSING→OPENING 直接跳转**，
 *   待 [finishClose] 落回 CLOSED 后重放一次；
 * - [markOpen] 仅在 OPENING 时合法（键盘可见性翻转驱动），其余状态 no-op。
 *
 * 线程模型：Compose 组合生命周期（主线程）驱动；[synchronized] 保证跨线程读取
 * （测试/日志查询）一致性；窗口引用以弱引用保存（会话结束即清理，防窗口泄漏）。
 */
object InputSessionStateMachine {

    /** 输入会话相位 */
    enum class Phase { CLOSED, OPENING, OPEN, CLOSING }

    /** 单个会话的状态（保存原始 Window 引用做身份比较；会话生命周期 = 输入窗口生命周期，有界） */
    private class SessionState(
        val window: Window?,
        val sessionKey: String
    ) {
        @Volatile
        var phase: Phase = Phase.CLOSED

        /** CLOSING 期间收到的新打开请求（待 CLOSED 后重放一次） */
        var pendingReopen: Boolean = false
    }

    private val lock = Any()

    private val sessions = ArrayList<SessionState>()

    /**
     * 当前是否存在活跃输入会话（OPENING/OPEN/CLOSING；供渲染/窗口诊断日志查询）。
     * 任意线程只读。
     */
    fun hasActiveSessions(): Boolean = synchronized(lock) {
        pruneDeadSessions()
        sessions.any { it.phase != Phase.CLOSED }
    }

    /** 活跃会话摘要（日志用；无会话返回"无"） */
    fun activeSessionsSummary(): String = synchronized(lock) {
        pruneDeadSessions()
        sessions.filter { it.phase != Phase.CLOSED }
            .joinToString(", ") { "${it.sessionKey}:${it.phase}" }
            .ifEmpty { "无" }
    }

    /**
     * 打开请求（组件挂载时调用，幂等）。
     *
     * @param window 会话归属窗口（Activity/Dialog Window；无窗口上下文可传 null）
     * @param sessionKey 会话标识（同一输入框实例挂载/卸载期间保持恒定）
     */
    fun open(window: Window?, sessionKey: String) {
        synchronized(lock) {
            pruneDeadSessions()
            val state = findSession(window, sessionKey)
            when (state?.phase) {
                null -> {
                    sessions += SessionState(window, sessionKey).also {
                        it.phase = Phase.OPENING
                    }
                    Log.d(TAG, "InputSession: OPENING （$sessionKey, window=${System.identityHashCode(window)}）")
                }
                Phase.OPENING, Phase.OPEN -> {
                    // 幂等：重复打开请求零副作用（禁止重复创建/重复弹键盘）
                    Log.d(TAG, "InputSession: open 幂等跳过（$sessionKey phase=${state.phase}）")
                }
                Phase.CLOSING -> {
                    // 禁止 CLOSING→OPENING 直接跳转；关闭未完全落定前只记意图
                    state.pendingReopen = true
                    Log.d(TAG, "InputSession: 关闭中收到新的打开请求，记 pendingReopen（$sessionKey）")
                }
                Phase.CLOSED -> {
                    state.phase = Phase.OPENING
                    Log.d(TAG, "InputSession: OPENING（重开） （$sessionKey）")
                }
            }
        }
    }

    /** 键盘可见性确认（本窗口 IME 可见翻转时调用）；仅 OPENING 合法，其余 no-op */
    fun markOpen(window: Window?, sessionKey: String) {
        synchronized(lock) {
            val state = findSession(window, sessionKey) ?: return
            if (state.phase == Phase.OPENING) {
                state.phase = Phase.OPEN
                Log.d(TAG, "InputSession: OPEN （$sessionKey, window=${System.identityHashCode(window)}）")
            }
        }
    }

    /**
     * 关闭请求（组件销毁时调用，幂等）。
     * CLOSING 表示销毁已开始、键盘收起/冻结解冻尚未落定。
     */
    fun close(window: Window?, sessionKey: String) {
        synchronized(lock) {
            val state = findSession(window, sessionKey) ?: return
            when (state.phase) {
                Phase.OPENING, Phase.OPEN -> {
                    state.phase = Phase.CLOSING
                    Log.d(TAG, "InputSession: CLOSING （$sessionKey）")
                }
                Phase.CLOSING, Phase.CLOSED -> {
                    // 幂等：重复关闭请求零副作用（禁止重复执行关闭流程）
                    Log.d(TAG, "InputSession: close 幂等跳过（$sessionKey phase=${state.phase}）")
                }
            }
        }
    }

    /**
     * 会话落定（CLOSING 的唯一出口；由销毁方 post 到主线程末拍调用，
     * 保证该帧内的所有 onDispose 清理已完成）。有 pendingReopen 则重放一次打开请求。
     */
    fun finishClose(window: Window?, sessionKey: String) {
        synchronized(lock) {
            val state = findSession(window, sessionKey) ?: return
            if (state.phase != Phase.CLOSING) return
            state.phase = Phase.CLOSED
            Log.d(TAG, "InputSession: CLOSED （$sessionKey）")
            val reopen = state.pendingReopen
            state.pendingReopen = false
            if (reopen) {
                Log.d(TAG, "InputSession: 重放 CLOSING 期间收到的打开请求（$sessionKey）")
                state.phase = Phase.OPENING
            } else {
                sessions.remove(state)
            }
        }
    }

    private fun findSession(window: Window?, sessionKey: String): SessionState? =
        sessions.firstOrNull { it.window === window && it.sessionKey == sessionKey }

    /** 惰性清理已落定的会话条目（CLOSED 为终态，无保留价值） */
    private fun pruneDeadSessions() {
        sessions.removeAll { it.phase == Phase.CLOSED }
    }

    /** 会话数（测试断言用） */
    @VisibleForTesting
    internal fun sessionCountForTest(): Int = synchronized(lock) {
        pruneDeadSessions()
        sessions.size
    }

    /** 指定会话当前相位（测试断言用；无会话返回 CLOSED） */
    @VisibleForTesting
    internal fun phaseForTest(window: Window?, sessionKey: String): Phase =
        synchronized(lock) {
            findSession(window, sessionKey)?.phase ?: Phase.CLOSED
        }

    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(lock) {
            sessions.clear()
        }
    }
}

