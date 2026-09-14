package com.xianxia.sect.umeng

import android.app.Application
import android.content.Context
import android.util.Log
import com.umeng.analytics.MobclickAgent
import com.umeng.commonsdk.UMConfigure
import com.xianxia.sect.BuildConfig

/**
 * 友盟统计（U-App）初始化门面。
 *
 * 合规两段式（与 TapTap"用户同意隐私政策后才初始化"契约同源）：
 * - [preInit]：Application.onCreate 中调用（同意前）。官方契约：preInit 不采集
 *   任何个人信息，仅完成多进程等运行前准备，缺省会导致首启 init 失效。
 * - [init]：用户同意隐私政策后调用（MainActivity.proceedAfterPrivacyConsent，
 *   已同意冷启与首次同意两条路径统一经过）。真正的数据采集自此开启。
 *
 * AppKey/渠道来自 api.properties（经 BuildConfig 注入）：UMENG_APP_KEY 留空时
 * 两个入口整体跳过，SDK 完全惰性（不加载采集逻辑），不影响启动与单测。
 */
object UmengManager {

    private const val TAG = "UmengManager"

    @Volatile
    private var initialized = false

    /** 同意前预备初始化（幂等，不采集数据）。 */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun preInit(application: Application) {
        if (BuildConfig.UMENG_APP_KEY.isEmpty()) {
            Log.i(TAG, "UMENG_APP_KEY 未配置（api.properties），跳过友盟 preInit")
            return
        }
        try {
            UMConfigure.preInit(application, BuildConfig.UMENG_APP_KEY, BuildConfig.UMENG_CHANNEL)
            Log.i(TAG, "Umeng preInit completed")
        } catch (e: Throwable) {
            Log.w(TAG, "Umeng preInit failed (statistics disabled)", e)
        }
    }

    /** 同意后正式初始化（幂等；含 ABTest 等组件随 common/asms 一并生效）。 */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun init(application: Application) {
        if (BuildConfig.UMENG_APP_KEY.isEmpty()) {
            Log.i(TAG, "UMENG_APP_KEY 未配置（api.properties），跳过友盟 init")
            return
        }
        if (initialized) return
        try {
            // 集成测试期打开 SDK 日志（umeng 后台"集成测试"页可见实时日志）
            UMConfigure.setLogEnabled(BuildConfig.DEBUG_MODE)
            UMConfigure.init(
                application,
                BuildConfig.UMENG_APP_KEY,
                BuildConfig.UMENG_CHANNEL,
                UMConfigure.DEVICE_TYPE_PHONE,
                "" // pushSecret：未接友盟推送，恒空串
            )
            initialized = true
            Log.i(TAG, "Umeng init completed (channel=${BuildConfig.UMENG_CHANNEL})")
        } catch (e: Throwable) {
            Log.w(TAG, "Umeng init failed (statistics disabled)", e)
        }
    }

    /**
     * 进程被主动杀死前的统计保存（官方契约：kill/exit 类杀进程或双击 back 退出前必须调用，
     * 将内存中的统计数据落盘，下次启动补传）。
     *
     * 当前工程唯二的杀进程点位是 CrashHandler 崩溃退出路径（无"退出游戏"按钮），
     * 两处均已在 Process.killProcess 之前接入。全防御封装：未配置 AppKey 时
     * no-op，任何异常吞掉并留痕，绝不阻断/拖慢杀进程流程本身。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun onKillProcess(context: Context) {
        if (BuildConfig.UMENG_APP_KEY.isEmpty()) return
        try {
            MobclickAgent.onKillProcess(context)
        } catch (e: Throwable) {
            Log.w(TAG, "Umeng onKillProcess failed", e)
        }
    }
}
