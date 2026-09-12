package com.xianxia.sect.login

/**
 * 登录/防沉迷验证流程状态机（纯 Kotlin，零 Android 依赖，可 JVM 单测）。
 *
 * ## 设计动机
 *
 * 4.00.98 以来"登录后卡在登录界面 / 弹出实名认证界面"反复回归的根因是登录/防沉迷
 * 验证的状态散落在 MainActivity 的多个手工布尔字段（complianceCheckInFlight /
 * complianceCheckDeferredStarted）中，生命周期互不约束：
 * - 一次性标记永不复位 → "退出认证/切换账号后再登录"验证被永久跳过
 * - 回调注册与 SDK 就绪时序无统一契约 → 冷启动路径注册失败后永久失去回调
 *
 * 本状态机将"登录 → 防沉迷验证 → 进模式选择"收敛为单一真相源：状态转移表唯一、
 * 副作用经 [LoginFlowHost] 执行、事件幂等防重入。详见 docs/login-flow-state-machine.md。
 *
 * ## 关键机制
 *
 * - **resumedReady**：ActivityResumed 事件（任意状态）置位，会话内不复位；
 *   登录成功/重试进入 VerifyPending 时若已置位则立即启动验证——替代原一次性标记且天然可复位。
 * - **单飞语义**：仅在 VerifyPending → Verifying 转移时启动一次 startup，重复事件 no-op。
 * - **SDK 就绪契约**：LoginSuccess / ColdStart / RetryVerification 三个事件发送方保证
 *   TapTap 登录 SDK 已就绪；startup 前宿主再做防御性 isReady 检查。
 */
class LoginFlowStateMachine(private val host: LoginFlowHost) {

    /** 当前状态（只读暴露，供 UI 与测试断言） */
    var state: LoginFlowState = LoginFlowState.Idle
        private set

    /** Activity 是否已稳定进入 RESUMED（ActivityResumed 事件无条件置位） */
    private var resumedReady = false

    /** 当前会话待验证的 unionId（VerifyPending/Verifying/VerificationFailed 期间持有） */
    private var pendingUnionId: String? = null

    /**
     * 事件入口（主线程调用）。状态转移后经 [LoginFlowHost] 执行副作用并记录转移日志。
     */
    fun onEvent(event: LoginFlowEvent) {
        val before = state
        when (event) {
            LoginFlowEvent.LoginRequested -> onLoginRequested()
            is LoginFlowEvent.LoginSuccess -> onLoginSuccess(event)
            is LoginFlowEvent.LoginFailure -> onLoginAborted(event.message)
            LoginFlowEvent.LoginTimeout -> onLoginAborted("登录超时，请重试")
            LoginFlowEvent.ActivityResumed -> onActivityResumed()
            LoginFlowEvent.VerificationSuccess -> onVerificationSuccess()
            LoginFlowEvent.VerificationExited -> onVerificationExited()
            LoginFlowEvent.VerificationNetworkError -> onVerificationNetworkError()
            LoginFlowEvent.VerificationTimeout -> onVerificationTimeout()
            LoginFlowEvent.RetryVerification -> onRetryVerification()
            LoginFlowEvent.LogoutRequested -> onLogoutRequested()
            is LoginFlowEvent.ColdStart -> onColdStart(event)
        }
        if (state != before) {
            host.onLog("登录流程: $before --${event.javaClass.simpleName}--> $state")
        }
    }

    private fun onLoginRequested() {
        if (state != LoginFlowState.Idle) {
            host.onLog("忽略 LoginRequested（当前 $state）")
            return
        }
        state = LoginFlowState.LoggingIn
        host.onSetLoginTimeout(true)
    }

    private fun onLoginSuccess(event: LoginFlowEvent.LoginSuccess) {
        if (state != LoginFlowState.LoggingIn) {
            host.onLog("忽略 LoginSuccess（当前 $state）")
            return
        }
        host.onSetLoginTimeout(false)
        // 新会话：重置 RESUMED 前置（转场窗口期防护）——必须等待"登录成功之后"的
        // 稳定 RESUMED 才启动验证，防止授权页转场窗口期 startup 导致实名认证弹窗
        // 展示失败。Activity 已在 RESUMED（静默登录无转场）时由事件发送方补发
        // ActivityResumed 立即启动。
        resumedReady = false
        tryEnterVerifyPending(event.unionId)
    }

    private fun onLoginAborted(message: String) {
        if (state != LoginFlowState.LoggingIn) {
            host.onLog("忽略登录中止（当前 $state）")
            return
        }
        host.onSetLoginTimeout(false)
        state = LoginFlowState.Idle
        host.onLog(message)
    }

    private fun onActivityResumed() {
        resumedReady = true
        if (state == LoginFlowState.VerifyPending) {
            tryEnterVerifyPending(pendingUnionId ?: return)
        }
    }

    private fun onVerificationSuccess() {
        if (state != LoginFlowState.Verifying) {
            host.onLog("忽略 VerificationSuccess（当前 $state）")
            return
        }
        host.onSetVerificationTimeout(false)
        pendingUnionId = null
        state = LoginFlowState.Verified
        host.onShowModeSelection()
    }

    private fun onVerificationExited() {
        if (state != LoginFlowState.Verifying) {
            host.onLog("忽略 VerificationExited（当前 $state）")
            return
        }
        host.onSetVerificationTimeout(false)
        clearToIdle()
    }

    private fun onVerificationNetworkError() {
        if (state != LoginFlowState.Verifying) {
            host.onLog("忽略 VerificationNetworkError（当前 $state）")
            return
        }
        host.onSetVerificationTimeout(false)
        state = LoginFlowState.VerificationFailed
        host.onShowToast("网络连接异常，请检查网络后重试")
        showVerificationScreen()
    }

    private fun onVerificationTimeout() {
        if (state != LoginFlowState.Verifying) {
            host.onLog("忽略 VerificationTimeout（当前 $state）")
            return
        }
        host.onSetVerificationTimeout(false)
        host.onRecoverSdkRunningState()
        state = LoginFlowState.VerificationFailed
        host.onShowToast("实名认证无响应，请点击重新认证")
        showVerificationScreen()
    }

    private fun onRetryVerification() {
        if (state != LoginFlowState.VerificationFailed) {
            host.onLog("忽略 RetryVerification（当前 $state）")
            return
        }
        tryEnterVerifyPending(pendingUnionId ?: return)
    }

    private fun onLogoutRequested() {
        when (state) {
            LoginFlowState.LoggingIn -> host.onSetLoginTimeout(false)
            LoginFlowState.VerifyPending, LoginFlowState.Verifying -> host.onSetVerificationTimeout(false)
            LoginFlowState.Idle -> {
                host.onLog("忽略 LogoutRequested（当前 Idle）")
                return
            }
            LoginFlowState.Verified, LoginFlowState.VerificationFailed -> Unit
        }
        clearToIdle()
    }

    private fun onColdStart(event: LoginFlowEvent.ColdStart) {
        if (state != LoginFlowState.Idle) {
            host.onLog("忽略 ColdStart（当前 $state）")
            return
        }
        pendingUnionId = event.unionId
        when {
            event.complianceVerified -> {
                state = LoginFlowState.Verified
                host.onShowModeSelection()
            }
            event.unionId.isNullOrEmpty() -> {
                host.onLog("已登录但缺少 unionId，需要重新登录")
                clearToIdle()
            }
            else -> {
                state = LoginFlowState.VerificationFailed
                showVerificationScreen()
            }
        }
    }

    /**
     * 进入验证待启动态：resumedReady 已置位（Activity 稳定 RESUMED）则立即启动验证。
     * 幂等：VerifyPending 期间重复调用只会在满足前置时启动一次（启动后状态离开 VerifyPending）。
     */
    private fun tryEnterVerifyPending(unionId: String) {
        pendingUnionId = unionId
        state = LoginFlowState.VerifyPending
        if (resumedReady) {
            startVerification(unionId)
        }
    }

    /** 启动防沉迷验证（单飞：仅在此处从 VerifyPending 转移） */
    private fun startVerification(unionId: String) {
        state = LoginFlowState.Verifying
        host.onStartComplianceVerification(unionId)
        host.onSetVerificationTimeout(true)
    }

    /** 统一登出：清空会话上下文 + 登出四件套副作用 + 回登录界面 */
    private fun clearToIdle() {
        pendingUnionId = null
        state = LoginFlowState.Idle
        host.onClearSessionAndLogout()
        host.onShowLoginScreen()
    }

    /** 显示实名认证界面（pendingUnionId 由宿主后续经 RetryVerification 事件取用） */
    private fun showVerificationScreen() {
        host.onShowComplianceVerificationScreen()
    }
}
