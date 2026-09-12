package com.xianxia.sect.taptap

import android.util.Log
import com.xianxia.sect.data.SessionManager
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 防沉迷合规回调进程级宿主。
 *
 * ## 背景
 *
 * 合规回调若绑定单个 Activity 实例，玩家登录后经 `launchGame` 进入 GameActivity、
 * MainActivity 立即 `finish()`，回调会被 `isFinishing/isDestroyed` 检查丢弃：
 * 游戏内时长限制（CODE_DURATION_LIMIT）/时间限制（CODE_PERIOD_RESTRICT）/
 * 年龄限制提示将永远无法弹出——故回调注册必须为进程级。
 *
 * ## 设计
 *
 * 进程级单例持有 `ComplianceManager` 的合规回调注册（进程存活期间仅注册一次），
 * 按"当前前台窗口"弱引用转发。窗口以 [WindowPort] 接口注册（MainActivity /
 * GameActivity 各自提供端口实现），接口抽象使宿主纯 JVM 可测（Fake 端口）。
 *
 * - **登录流程回调**（onLoginSuccess/onExited/onSwitchAccount/onNetworkError/
 *   onRealNameStop）→ 转发给登录窗口——这些回调只在登录流程有意义
 * - **限制类回调**（onPeriodRestrict/onDurationLimit/onAgeLimit）→ 优先转发给
 *   游戏窗口（玩家游戏中最常见），无游戏窗口时回退登录窗口
 *
 * 窗口注册采用 onResume 注册 / onStop 清除（Android 生命周期保证新旧 Activity
 * 切换无缝：新 Activity onResume 先于旧 Activity onStop），弱引用防 Activity
 * 泄漏，`===` 身份校验防旧实例误清新实例。
 */
@Singleton
class ComplianceCallbackHost @Inject constructor(
    private val sessionManager: SessionManager
) {
    companion object {
        private const val TAG = "ComplianceCallbackHost"
    }

    /**
     * 合规回调窗口端口——Activity 侧适配器接口。
     *
     * [postToUi] 主线程投递；[isAlive] 窗口存活判定（销毁窗口期展示 Dialog 会抛
     * BadToken，Bugly #3098 教训）；其余方法为各类回调的 UI 响应入口。
     */
    interface WindowPort {
        /** 主线程投递 block（窗口存活语义由 [isAlive] 单独判定） */
        fun postToUi(block: () -> Unit)

        /** 窗口是否存活（未 finishing 且未 destroyed） */
        fun isAlive(): Boolean

        /** 登录成功回调响应 */
        fun onLoginSuccess()

        /** 退出/切换账号/实名停止统一响应 */
        fun onExited()

        /** 网络异常回调响应 */
        fun onNetworkError()

        /** 时间/时长限制弹窗 */
        fun onRestrict(title: String, message: String)

        /** 适龄限制弹窗 */
        fun onAgeLimit()
    }

    private val loginWindowRef = AtomicReference<WeakReference<WindowPort>?>(WeakReference(null))
    private val gameWindowRef = AtomicReference<WeakReference<WindowPort>?>(WeakReference(null))

    /** 登录窗口注册（MainActivity.onResume） */
    fun registerLoginWindow(port: WindowPort) {
        loginWindowRef.set(WeakReference(port))
    }

    /** 登录窗口清除（MainActivity.onStop，身份校验防旧实例误清新实例） */
    fun clearLoginWindow(port: WindowPort) {
        if (loginWindowRef.get()?.get() === port) {
            loginWindowRef.set(WeakReference(null))
        }
    }

    /** 游戏窗口注册（GameActivity.onResume） */
    fun registerGameWindow(port: WindowPort) {
        gameWindowRef.set(WeakReference(port))
    }

    /** 游戏窗口清除（GameActivity.onStop） */
    fun clearGameWindow(port: WindowPort) {
        if (gameWindowRef.get()?.get() === port) {
            gameWindowRef.set(WeakReference(null))
        }
    }

    /**
     * 进程级合规回调实例——`ComplianceManager.registerCallback` 注册一次后进程存活期间有效，
     * 不再因 Activity 重建/切换而失效。
     */
    val callback: ComplianceManager.ComplianceCallback = object : ComplianceManager.ComplianceCallback {
        override fun onLoginSuccess() {
            forwardToLoginWindow("onLoginSuccess") { it.onLoginSuccess() }
        }

        override fun onExited() {
            forwardToLoginWindow("onExited") { it.onExited() }
        }

        override fun onSwitchAccount() {
            forwardToLoginWindow("onSwitchAccount") { it.onExited() }
        }

        override fun onPeriodRestrict() {
            forwardToRestrictWindow(
                "时间限制",
                "根据防沉迷规定，未成年人仅可在周五、周六、周日及法定节假日的20:00-21:00进行游戏。"
            )
        }

        override fun onDurationLimit() {
            forwardToRestrictWindow(
                "时长限制",
                "您今日的游戏时长已用尽，请合理安排游戏时间。"
            )
        }

        override fun onAgeLimit() {
            forwardToAgeLimitWindow()
        }

        override fun onNetworkError() {
            forwardToLoginWindow("onNetworkError") { it.onNetworkError() }
        }

        override fun onRealNameStop() {
            forwardToLoginWindow("onRealNameStop") { it.onExited() }
        }
    }

    /** 登录流程回调转发——登录窗口不存在时丢弃并记录（游戏内不适用此类回调） */
    private fun forwardToLoginWindow(event: String, action: (WindowPort) -> Unit) {
        val port = loginWindowRef.get()?.get()
        if (port == null || !port.isAlive()) {
            Log.w(TAG, "合规回调 $event 到达但登录窗口不可用，丢弃")
            return
        }
        port.postToUi { action(port) }
    }

    /** 限制类回调转发——优先游戏窗口（玩家游戏中最常见），回退登录窗口 */
    private fun forwardToRestrictWindow(title: String, message: String) {
        sessionManager.complianceVerified = false
        val game = gameWindowRef.get()?.get()
        if (game != null && game.isAlive()) {
            game.postToUi { game.onRestrict(title, message) }
            return
        }
        forwardToLoginWindow("onRestrict($title)") { it.onRestrict(title, message) }
    }

    /** 年龄限制回调转发——优先游戏窗口，回退登录窗口 */
    private fun forwardToAgeLimitWindow() {
        sessionManager.complianceVerified = false
        val game = gameWindowRef.get()?.get()
        if (game != null && game.isAlive()) {
            game.postToUi { game.onAgeLimit() }
            return
        }
        forwardToLoginWindow("onAgeLimit") { it.onAgeLimit() }
    }
}
