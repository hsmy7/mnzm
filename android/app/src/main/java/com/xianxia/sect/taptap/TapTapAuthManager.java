package com.xianxia.sect.taptap;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.taptap.sdk.core.TapTapRegion;
import com.taptap.sdk.core.TapTapSdk;
import com.taptap.sdk.core.TapTapSdkOptions;
import com.taptap.sdk.kit.internal.callback.TapTapCallback;
import com.taptap.sdk.kit.internal.exception.TapTapException;
import com.taptap.sdk.login.TapTapLogin;
import com.taptap.sdk.login.TapTapAccount;

public class TapTapAuthManager {
    private static final String TAG = "TapTapAuthManager";
    private static boolean isInitialized = false;
    private static boolean limitAdTrackingEnabled = true;

    public static void init(Activity activity, String clientId, String clientToken, boolean isCN) {
        init(activity, clientId, clientToken, isCN, true);
    }

    public static void init(Activity activity, String clientId, String clientToken, boolean isCN, boolean limitAdTracking) {
        if (isInitialized) {
            Log.d(TAG, "TapTap SDK 已初始化");
            return;
        }

        limitAdTrackingEnabled = limitAdTracking;

        TapTapSdkOptions options = new TapTapSdkOptions(
            clientId,
            clientToken,
            isCN ? TapTapRegion.CN : TapTapRegion.GLOBAL
        );

        options.setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        TapTapSdk.init(activity.getApplicationContext(), options);
        isInitialized = true;

        Log.d(TAG, "TapTap SDK 初始化完成，区域: " + (isCN ? "CN" : "GLOBAL") + "，限制广告追踪: " + limitAdTracking);
    }

    public static boolean isLimitAdTrackingEnabled() {
        return limitAdTrackingEnabled;
    }

    public static void login(Activity activity, final LoginResultCallback callback) {
        Log.d(TAG, "开始 TapTap 登录...");

        try {
            String[] scopes = new String[]{"public_profile"};

            TapTapLogin.loginWithScopes(activity, scopes, new TapTapCallback<TapTapAccount>() {
                @Override
                public void onSuccess(TapTapAccount result) {
                    Log.d(TAG, "TapTap 登录成功: " + (result.getName() != null ? result.getName() : "unknown"));

                    LoginData data = new LoginData(
                        result.getOpenId() != null ? result.getOpenId() : "",
                        result.getUnionId() != null ? result.getUnionId() : "",
                        result.getName() != null ? result.getName() : "",
                        result.getAvatar() != null ? result.getAvatar() : "",
                        result.getAccessToken() != null && result.getAccessToken().getKid() != null ? result.getAccessToken().getKid() : "",
                        result.getAccessToken() != null && result.getAccessToken().getTokenType() != null ? result.getAccessToken().getTokenType() : "",
                        result.getAccessToken() != null && result.getAccessToken().getMacKey() != null ? result.getAccessToken().getMacKey() : "",
                        result.getAccessToken() != null && result.getAccessToken().getMacAlgorithm() != null ? result.getAccessToken().getMacAlgorithm() : ""
                    );

                    callback.onSuccess(data);
                }

                @Override
                public void onCancel() {
                    Log.d(TAG, "用户取消 TapTap 登录");
                    callback.onFailure(new Exception("用户取消登录"));
                }

                @Override
                public void onFail(@NonNull TapTapException exception) {
                    String errorMsg = exception.getMessage() != null ? exception.getMessage() : "登录失败";
                    Log.e(TAG, "TapTap 登录失败: " + errorMsg, exception);
                    callback.onFailure(new Exception(errorMsg));
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "TapTap 登录调用异常: " + e.getMessage(), e);
            callback.onFailure(e);
        }
    }

    public static boolean isLoggedIn() {
        try {
            TapTapAccount account = TapTapLogin.getCurrentTapAccount();
            return account != null && account.getAccessToken() != null;
        } catch (Exception e) {
            Log.e(TAG, "检查登录状态失败: " + e.getMessage());
            return false;
        }
    }

    public static TapTapAccount getCurrentAccount() {
        try {
            return TapTapLogin.getCurrentTapAccount();
        } catch (Exception e) {
            Log.e(TAG, "获取当前账号失败: " + e.getMessage());
            return null;
        }
    }

    public static void logout() {
        try {
            TapTapLogin.logout();
            Log.d(TAG, "TapTap 登出成功");
        } catch (Exception e) {
            Log.e(TAG, "TapTap 登出失败: " + e.getMessage());
        }
    }

    public interface LoginResultCallback {
        void onSuccess(LoginData data);
        void onFailure(Exception error);
    }
}
