package com.xianxia.sect.login

/**
 * 登录/防沉迷验证流程状态定义（纯 Kotlin，零 Android 依赖，可 JVM 单测）。
 *
 * 覆盖"登录 → 防沉迷验证 → 进模式选择"全链路，替代 MainActivity 中散落的
 * 手工布尔标志（complianceCheckInFlight / complianceCheckDeferredStarted），
 * 根治"退出认证/切换账号后再登录被永久跳过""回调注册与 SDK 就绪时序竞态"
 * 等反复回归（详见 docs/login-flow-state-machine.md）。
 */
sealed interface LoginFlowState {
    /** 未登录：显示登录界面 */
    data object Idle : LoginFlowState

    /** TapTap 授权页展示中（登录请求已发出，等待回调） */
    data object LoggingIn : LoginFlowState

    /** 登录成功：等待 Activity 稳定进入 RESUMED 后再启动防沉迷验证 */
    data object VerifyPending : LoginFlowState

    /** 防沉迷验证已启动（startup 已调用），等待 SDK 回调 */
    data object Verifying : LoginFlowState

    /** 防沉迷验证成功（CODE_LOGIN_SUCCESS）：可进入模式选择 */
    data object Verified : LoginFlowState

    /** 验证失败/超时/网络错误：显示实名认证界面，等待手动重试或切换账号 */
    data object VerificationFailed : LoginFlowState
}

/**
 * 登录流程事件。
 *
 * 发送方保证：LoginSuccess（TapTap 登录前置 isReady 检查）、ColdStart（冷启动已 await）、
 * RetryVerification（按钮回调先 await）三个事件发出时 TapTap 登录 SDK 已就绪。
 */
sealed interface LoginFlowEvent {
    /** 用户点击"进入游戏"（隐私/就绪校验通过后） */
    data object LoginRequested : LoginFlowEvent

    /** TapTap 登录成功（携带防沉迷验证所需的 unionId） */
    data class LoginSuccess(val unionId: String) : LoginFlowEvent

    /** TapTap 登录失败（用户取消/网络错误/SDK 异常） */
    data class LoginFailure(val message: String) : LoginFlowEvent

    /** 登录请求超时（授权页无回调，防御性兜底） */
    data object LoginTimeout : LoginFlowEvent

    /** Activity 已稳定进入 RESUMED（repeatOnLifecycle 每次进入触发） */
    data object ActivityResumed : LoginFlowEvent

    /** 防沉迷验证成功回调（CODE_LOGIN_SUCCESS） */
    data object VerificationSuccess : LoginFlowEvent

    /** 防沉迷退出/切换账号/实名停止回调（CODE_EXITED / CODE_SWITCH_ACCOUNT / CODE_REAL_NAME_STOP） */
    data object VerificationExited : LoginFlowEvent

    /** 防沉迷网络错误回调（CODE_NETWORK_ERROR / startup 异常） */
    data object VerificationNetworkError : LoginFlowEvent

    /** 防沉迷验证超时（30s 无任何回调，SDK 静默失败兜底） */
    data object VerificationTimeout : LoginFlowEvent

    /** 实名认证界面点"开始认证"（发送方先保证 SDK 就绪） */
    data object RetryVerification : LoginFlowEvent

    /** 任意登出入口（模式选择/合规弹窗/实名认证界面/防沉迷退出） */
    data object LogoutRequested : LoginFlowEvent

    /** 冷启动恢复（进程销毁复用后已登录）：按合规标记与 unionId 路由 */
    data class ColdStart(val complianceVerified: Boolean, val unionId: String?) : LoginFlowEvent
}

/**
 * 状态机转移要求宿主（MainActivity）执行的副作用。
 *
 * 副作用 = 状态机与 Android 的唯一耦合面；纯逻辑可测，宿主按清单执行。
 */
sealed interface LoginFlowSideEffect {
    /** 启动防沉迷验证：宿主先 ensureCallbackRegistered（自愈）再 TapTapCompliance.startup */
    data class StartComplianceVerification(val unionId: String) : LoginFlowSideEffect

    /** 显示 app 自有实名认证界面（手动重试/切换账号；unionId 由状态机内部持有） */
    data object ShowComplianceVerificationScreen : LoginFlowSideEffect

    /** 进入模式选择界面 */
    data object ShowModeSelection : LoginFlowSideEffect

    /** 回到登录界面 */
    data object ShowLoginScreen : LoginFlowSideEffect

    /** 登出统一四件套：清会话 + 清 TapTap SDK 登录态 + 停时长统计 + 解绑合规回调（唯一实现点） */
    data object ClearSessionAndLogout : LoginFlowSideEffect

    /** 用户提示（Toast）：网络异常/验证超时/登录超时，保留会话可重试 */
    data class ShowToast(val message: String) : LoginFlowSideEffect

    /** 恢复 SDK 运行状态：ComplianceManager.exit() + 反射复位 TapComplianceInternal.isRunning */
    data object RecoverSdkRunningState : LoginFlowSideEffect

    /** 调度防沉迷验证 30s 超时任务（宿主实现，触发 VerificationTimeout 事件） */
    data object ScheduleVerificationTimeout : LoginFlowSideEffect

    /** 取消防沉迷验证超时任务 */
    data object CancelVerificationTimeout : LoginFlowSideEffect

    /** 调度登录 60s 超时任务（宿主实现，触发 LoginTimeout 事件） */
    data object ScheduleLoginTimeout : LoginFlowSideEffect

    /** 取消登录超时任务 */
    data object CancelLoginTimeout : LoginFlowSideEffect
}

/**
 * 状态机宿主接口——MainActivity 实现，按副作用清单执行 Android 侧动作。
 *
 * 状态机纯逻辑可测：测试用 Fake 实现记录副作用调用序列。
 * 超时调度/取消合并为 [onSetVerificationTimeout] / [onSetLoginTimeout] 布尔语义
 *（true=调度、false=取消），保持接口方法数 ≤11（detekt TooManyFunctions 接口阈值）。
 */
interface LoginFlowHost {
    /** 启动防沉迷验证：宿主先 ensureCallbackRegistered（自愈）再 TapTapCompliance.startup */
    fun onStartComplianceVerification(unionId: String)

    /** 显示 app 自有实名认证界面（手动重试/切换账号） */
    fun onShowComplianceVerificationScreen()

    /** 进入模式选择界面 */
    fun onShowModeSelection()

    /** 回到登录界面 */
    fun onShowLoginScreen()

    /** 登出统一四件套：清会话 + 清 TapTap SDK 登录态 + 停时长统计 + 解绑合规回调 */
    fun onClearSessionAndLogout()

    /** 用户提示（Toast） */
    fun onShowToast(message: String)

    /** 恢复 SDK 运行状态：ComplianceManager.exit() + 反射复位 TapComplianceInternal.isRunning */
    fun onRecoverSdkRunningState()

    /** 调度(true)/取消(false) 防沉迷验证 30s 超时任务（触发 VerificationTimeout 事件） */
    fun onSetVerificationTimeout(active: Boolean)

    /** 调度(true)/取消(false) 登录 60s 超时任务（触发 LoginTimeout 事件） */
    fun onSetLoginTimeout(active: Boolean)

    /** 状态转移/忽略日志（可观测性） */
    fun onLog(message: String)
}
