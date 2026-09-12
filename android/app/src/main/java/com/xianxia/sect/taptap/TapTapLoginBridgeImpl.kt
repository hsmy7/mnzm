package com.xianxia.sect.taptap

import android.app.Activity
import android.util.Log
import com.xianxia.sect.taptap.TapTapLoginBridge.LoginResult
import javax.inject.Inject

/**
 * TapTapLoginBridge 的 app 模块实现（经 Hilt BridgeBindingsModule 绑定）。
 * 桥接 TapTapAuthManager 静态登录链路；全部异常兜底为 Error 结果，不抛裸异常。
 */
class TapTapLoginBridgeImpl @Inject constructor() : TapTapLoginBridge {

    companion object {
        private const val TAG = "TapTapLoginBridge"
    }

    override fun isLoggedIn(): Boolean = TapTapAuthManager.isLoggedIn()

    @Suppress("TooGenericExceptionCaught")
    // Exception：登录链路防御性兜底（SDK 未就绪/网络异常等），统一收敛为 LoginResult
    override fun login(activity: Activity, onResult: (LoginResult) -> Unit) {
        try {
            TapTapAuthManager.login(activity, object : TapTapAuthManager.LoginResultCallback {
                override fun onSuccess(data: LoginData) {
                    Log.d(TAG, "TapTap 登录成功: ${data.name}")
                    onResult(LoginResult.Success)
                }

                override fun onFailure(error: Exception) {
                    val message = error.message ?: "登录失败"
                    Log.w(TAG, "TapTap 登录未完成: $message")
                    // TapTapAuthManager 对用户取消统一包装为"用户取消登录"异常
                    onResult(
                        if (message.contains("取消")) LoginResult.Canceled
                        else LoginResult.Error(message)
                    )
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "TapTap 登录调用异常", e)
            onResult(LoginResult.Error(e.message ?: "登录失败"))
        }
    }
}
