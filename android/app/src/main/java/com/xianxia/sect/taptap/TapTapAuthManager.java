package com.xianxia.sect.taptap;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.xianxia.sect.BuildConfig;

import com.taptap.sdk.core.TapTapEventOptions;
import com.taptap.sdk.core.TapTapRegion;
import com.taptap.sdk.core.TapTapSdk;
import com.taptap.sdk.core.TapTapSdkOptions;
import com.taptap.sdk.kit.internal.callback.TapTapCallback;
import com.taptap.sdk.kit.internal.exception.TapTapException;
import com.taptap.sdk.login.TapTapLogin;
import com.taptap.sdk.login.TapTapAccount;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.lang.reflect.Field;

public class TapTapAuthManager {
    private static final String TAG = "TapTapAuthManager";
    private static boolean isInitialized = false;
    /** SDK 真正就绪（init 成功 + context 可访问），登录按钮此标记为 true 才可点击 */
    private static boolean isSdkReady = false;
    private static boolean limitAdTrackingEnabled = true;

    private static final ExecutorService initExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TapTap-Init");
        t.setDaemon(true);
        return t;
    });

    public static void init(Activity activity, String clientId, String clientToken, boolean isCN) {
        init(activity, clientId, clientToken, isCN, true);
    }

    public static void init(Activity activity, String clientId, String clientToken, boolean isCN, boolean limitAdTracking) {
        limitAdTrackingEnabled = limitAdTracking;

        if (isInitialized) {
            Log.d(TAG, "TapTap SDK 已初始化，更新限制广告追踪状态: " + limitAdTracking);
            if (!isSdkReady) {
                // 上次初始化可能超时，重新尝试验证 context
                ensureTapTapKitContext(activity.getApplicationContext());
                isSdkReady = true;
                Log.d(TAG, "二次验证 TapTapKit.context 成功");
            }
            return;
        }

        TapTapSdkOptions options = new TapTapSdkOptions(
            clientId,
            clientToken,
            isCN ? TapTapRegion.CN : TapTapRegion.GLOBAL
        );

        options.setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        options.setEnableLog(BuildConfig.DEBUG);
        options.setGameVersion(BuildConfig.VERSION_NAME);

        TapTapEventOptions eventOptions = TapTapEventOptions.builder()
            .channel(BuildConfig.TAPDB_CHANNEL)
            .autoIAPEventEnabled(true)
            .build();

        Future<?> future = initExecutor.submit(() -> {
            try {
                TapTapSdk.init(activity.getApplicationContext(), options, eventOptions);
            } catch (Throwable t) {
                // 捕获 TapTap SDK 内部异常（包括 sandbox hook 等底层错误），
                // 防止初始化失败导致应用崩溃
                Log.e(TAG, "TapTapSdk.init 内部异常（可能是 sandbox/hook 兼容性问题）", t);
                throw new RuntimeException("TapTap init failed", t);
            }
        });
        long timeoutMs = com.xianxia.sect.core.util.DeviceCompatibilityHelper.INSTANCE.getTapTapInitTimeoutMs();
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            isInitialized = true;
            ensureTapTapKitContext(activity.getApplicationContext());
            isSdkReady = true;
            Log.d(TAG, "TapTap SDK 初始化完成，区域: " + (isCN ? "CN" : "GLOBAL") + "，限制广告追踪: " + limitAdTracking);
        } catch (TimeoutException e) {
            // 超时不标记 isInitialized/isSdkReady，避免进入未就绪状态
            Log.e(TAG, "TapTap SDK 初始化超时（模拟器环境），跳过登录功能", e);
        } catch (Exception e) {
            Log.e(TAG, "TapTap SDK 初始化异常", e);
            // 兜底：即使 init 失败，仍尝试设置 context，让后续登录等操作能优雅降级
            try {
                ensureTapTapKitContext(activity.getApplicationContext());
            } catch (Exception ctxEx) {
                Log.w(TAG, "兜底设置 context 也失败: " + ctxEx.getMessage());
            }
        }
    }

    public static boolean isLimitAdTrackingEnabled() {
        return limitAdTrackingEnabled;
    }

    /** SDK 是否已就绪，可安全调用登录等 UI 操作 */
    public static boolean isReady() {
        if (!isSdkReady) return false;
        try {
            // 双重验证：反射确认 context 确实可达
            Class<?> kitClass = Class.forName("com.taptap.sdk.kit.internal.TapTapKit");
            Field contextField = kitClass.getDeclaredField("context");
            contextField.setAccessible(true);
            return contextField.get(null) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 反射设置 TapTapKit.context。
     * TapTapKitInitProvider 在 AndroidManifest 中被移除（合规需要），
     * 导致 lateinit context 从未赋值。此方法在 TapTapSdk.init() 成功后兜底。
     */
    private static void ensureTapTapKitContext(Context appContext) {
        try {
            Class<?> kitClass = Class.forName("com.taptap.sdk.kit.internal.TapTapKit");
            Field contextField = kitClass.getDeclaredField("context");
            contextField.setAccessible(true);
            if (contextField.get(null) == null) {
                contextField.set(null, appContext);
                Log.d(TAG, "TapTapKit.context 已通过反射设置（兜底）");
            } else {
                Log.d(TAG, "TapTapKit.context 已存在，无需兜底");
            }
        } catch (Exception e) {
            Log.w(TAG, "无法设置 TapTapKit.context: " + e.getMessage());
        }
    }

    public static void login(Activity activity, final LoginResultCallback callback) {
        if (!isReady()) {
            Log.w(TAG, "TapTap SDK 未就绪，拒绝登录请求");
            callback.onFailure(new Exception("TapTap SDK 正在初始化，请稍后再试"));
            return;
        }
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
        if (!isReady()) return false;
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
            TapDBManager.INSTANCE.clearUser();
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
