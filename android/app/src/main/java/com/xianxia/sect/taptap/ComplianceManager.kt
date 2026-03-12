package com.xianxia.sect.taptap

import android.app.Activity
import android.util.Log
import com.taptap.sdk.compliance.TapTapCompliance
import com.taptap.sdk.compliance.TapTapComplianceCallback

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

    fun registerCallback(callback: ComplianceCallback) {
        this.callback = callback
        
        if (!isCallbackRegistered) {
            try {
                TapTapCompliance.registerComplianceCallback(object : TapTapComplianceCallback {
                    override fun onComplianceResult(code: Int, extra: Map<String, Any>?) {
                        Log.d(TAG, "合规认证回调: code=$code, extra=$extra")
                        handleResult(code, extra?.toString())
                    }
                })
                isCallbackRegistered = true
                Log.d(TAG, "合规认证回调已注册")
            } catch (e: Exception) {
                Log.e(TAG, "注册合规认证回调失败: ${e.message}", e)
            }
        }
    }

    fun unregisterCallback() {
        callback = null
        Log.d(TAG, "合规认证回调已清理")
    }

    private fun handleResult(code: Int, message: String?) {
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

    fun startup(activity: Activity, userIdentifier: String) {
        Log.d(TAG, "启动合规认证，userIdentifier: $userIdentifier")
        try {
            TapTapCompliance.startup(activity, userIdentifier)
        } catch (e: Exception) {
            Log.e(TAG, "启动合规认证失败: ${e.message}", e)
            callback?.onNetworkError()
        }
    }

    fun exit() {
        Log.d(TAG, "退出合规认证")
        try {
            TapTapCompliance.exit()
        } catch (e: Exception) {
            Log.e(TAG, "退出合规认证失败: ${e.message}", e)
        }
    }
}
