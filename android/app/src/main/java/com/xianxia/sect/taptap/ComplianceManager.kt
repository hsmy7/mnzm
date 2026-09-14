package com.xianxia.sect.taptap

import android.app.Activity
import android.util.Log
import com.taptap.sdk.compliance.TapTapCompliance
import com.taptap.sdk.compliance.TapTapComplianceCallback

/** 具名 TapTapComplianceCallback 实现（避免匿名内部类触发 KSP getSimpleName NPE） */
private class TapTapComplianceListener : TapTapComplianceCallback {
    override fun onComplianceResult(code: Int, extra: Map<String, Any>?) {
        Log.d("ComplianceManager", "合规认证回调: code=$code, extra=$extra")
        ComplianceManager.handleResult(code, extra?.toString())
    }
}

object ComplianceManager {
    private const val TAG = "ComplianceManager"
    
    const val CODE_LOGIN_SUCCESS = 500
    const val CODE_EXITED = 1000
    const val CODE_SWITCH_ACCOUNT = 1001
    const val CODE_PERIOD_RESTRICT = 1030
    const val CODE_DURATION_LIMIT = 1050
    const val CODE_AGE_LIMIT = 1100
    const val CODE_NETWORK_ERROR = 1200
    const val CODE_REAL_NAME_STOP = 9002

    @Volatile
    private var callback: ComplianceCallback? = null
    private var isCallbackRegistered = false

    /**
     * SDK 回调监听器注册入口（测试注入点：JVM 测试用 Fake 替换，避免依赖 TapTap SDK）。
     * 生产实现注册具名监听器；注册失败由 [ensureCallbackRegistered] 下次调用自愈重试。
     */
    internal var sdkCallbackRegistrar: (TapTapComplianceCallback) -> Unit = { listener ->
        TapTapCompliance.registerComplianceCallback(listener)
    }

    interface ComplianceCallback {
        fun onLoginSuccess()
        fun onExited()
        fun onSwitchAccount()
        fun onPeriodRestrict()
        fun onDurationLimit()
        fun onAgeLimit()
        fun onNetworkError()
        fun onRealNameStop()
    }

    /**
     * 幂等自愈注册合规回调。
     *
     * - [callback] 每次都更新（登出/重登后绑定最新宿主）；
     * - SDK 监听器仅在未注册时（重）注册——注册失败只记日志，**下次调用自动重试**，
     *   覆盖"冷启动路径注册早于 SDK 就绪导致注册失败"场景，避免永久失去回调。
     *
     * 调用时机：startup 之前必须调用（登录成功回调 / 已登录冷启动兜底 / 验证启动前置）。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun ensureCallbackRegistered(callback: ComplianceCallback) {
        this.callback = callback
        if (!isCallbackRegistered) {
            try {
                sdkCallbackRegistrar(TapTapComplianceListener())
                isCallbackRegistered = true
                Log.d(TAG, "合规认证回调已注册")
            } catch (e: Exception) {
                Log.e(TAG, "注册合规认证回调失败（下次 startup 前自愈重试）: ${e.message}", e)
            }
        }
    }

    /** 兼容入口：委托 [ensureCallbackRegistered]（历史调用方语义不变） */
    fun registerCallback(callback: ComplianceCallback) = ensureCallbackRegistered(callback)

    /** 测试辅助：复位进程级注册状态（仅测试调用，生产路径不使用） */
    internal fun resetForTest() {
        callback = null
        isCallbackRegistered = false
    }

    fun unregisterCallback() {
        callback = null
        Log.d(TAG, "合规认证回调已清理")
    }

    internal fun handleResult(code: Int, message: String?) {
        when (code) {
            CODE_LOGIN_SUCCESS -> {
                Log.d(TAG, "认证通过，可正常进入游戏")
                callback?.onLoginSuccess()
            }
            CODE_EXITED -> {
                Log.d(TAG, "退出防沉迷认证")
                callback?.onExited()
            }
            CODE_SWITCH_ACCOUNT -> {
                Log.d(TAG, "用户切换账号")
                callback?.onSwitchAccount()
            }
            CODE_PERIOD_RESTRICT -> {
                Log.d(TAG, "时间限制：当前时间无法进行游戏")
                callback?.onPeriodRestrict()
            }
            CODE_DURATION_LIMIT -> {
                Log.d(TAG, "时长限制：无可玩时长")
                callback?.onDurationLimit()
            }
            CODE_AGE_LIMIT -> {
                Log.d(TAG, "年龄限制：无法进入游戏")
                callback?.onAgeLimit()
            }
            CODE_NETWORK_ERROR -> {
                Log.e(TAG, "网络错误或配置错误: $message")
                callback?.onNetworkError()
            }
            CODE_REAL_NAME_STOP -> {
                Log.d(TAG, "用户关闭实名认证窗口")
                callback?.onRealNameStop()
            }
            else -> {
                Log.d(TAG, "其他回调码: $code, message: $message")
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun startup(activity: Activity, userIdentifier: String) {
        Log.d(
            TAG,
            "启动合规认证，userIdentifier: $userIdentifier, " +
                "activity=${activity.javaClass.simpleName}, " +
                "finishing=${activity.isFinishing}, destroyed=${activity.isDestroyed}"
        )
        try {
            TapTapCompliance.startup(activity, userIdentifier)
        } catch (e: Exception) {
            Log.e(TAG, "启动合规认证失败: ${e.message}", e)
            callback?.onNetworkError()
        }
    }

    /**
     * 复位 tap-compliance 卡死的运行状态（静默失败后的进程内恢复用）。
     *
     * tap-compliance 4.10.5 的 `TapComplianceInternal.isRunning` 静态标记仅在内部
     * `notifyMessageInternal` 终端回调处复位；若一次 startup 静默失败（实名认证
     * DialogFragment 展示失败 / 无任何回调），该标记保持 true，同进程内后续
     * `TapTapCompliance.startup()` 直接静默返回（日志 "try startup failed, cause
     * another check is running"），且 `exit()` 不复位它。反射复位与
     * [TapTapAuthManager.ensureTapTapKitContext] 兜底同模式。
     *
     * 字段名按候选列表依次尝试（SDK 升级/混淆后字段名可能变化），全部失败时
     * 日志携带字段名与异常，便于升级后快速定位。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun resetSdkRunningState() {
        val candidateFieldNames = listOf("isRunning", "mIsRunning", "running")
        val clazz = try {
            Class.forName("com.taptap.sdk.compliance.internal.TapComplianceInternal")
        } catch (e: Exception) {
            Log.e(TAG, "反射复位 TapComplianceInternal 失败（找不到类）: ${e.message}", e)
            return
        }
        for (fieldName in candidateFieldNames) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                field.setBoolean(null, false)
                Log.d(TAG, "已复位 TapComplianceInternal.$fieldName（防沉迷验证可重新启动）")
                return
            } catch (e: Exception) {
                Log.d(TAG, "字段 $fieldName 不可用，尝试下一候选: ${e.message}")
            }
        }
        Log.e(TAG, "复位 TapComplianceInternal.isRunning 失败：候选字段 $candidateFieldNames 均不可用")
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun exit() {
        Log.d(TAG, "退出合规认证")
        try {
            TapTapCompliance.exit()
        } catch (e: Exception) {
            Log.e(TAG, "退出合规认证失败: ${e.message}", e)
        }
    }
}
