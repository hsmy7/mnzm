package com.xianxia.sect.taptap

import android.content.Context
import android.util.Log
import com.tencent.bugly.crashreport.CrashReport
import com.xianxia.sect.core.CrashHandler
import com.xianxia.sect.core.platform.CrashReporter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bugly 崩溃上报实现（core 层 [CrashReporter] 接口 + app 层实现）。
 *
 * 契约（见 [CrashReporter]）：全部方法永不抛出——SDK 初始化/调用失败静默降级
 * （[reportCaughtException] 回退 [CrashHandler.recordCaughtException] 本地落盘）。
 * iOS 对等实现映射 iOS 崩溃上报 SDK，接口不变。
 */
@Singleton
class BuglyCrashReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crashHandler: CrashHandler
) : CrashReporter {

    companion object {
        private const val TAG = "BuglyCrashReporter"
    }

    // SDK 边界全量兜底
    @Suppress("TooGenericExceptionCaught")
    override fun initialize() {
        try {
            CrashReport.initCrashReport(
                context,
                com.xianxia.sect.BuildConfig.BUGLY_APP_ID,
                com.xianxia.sect.BuildConfig.DEBUG
            )
        } catch (e: Exception) {
            Log.e(TAG, "Bugly initCrashReport failed", e)
        }
    }

    // SDK 边界全量兜底
    @Suppress("TooGenericExceptionCaught")
    override fun setAppVersion(version: String) {
        try {
            CrashReport.setAppVersion(context, version)
        } catch (e: Exception) {
            Log.w(TAG, "Bugly setAppVersion failed", e)
        }
    }

    // SDK 边界全量兜底
    @Suppress("TooGenericExceptionCaught")
    override fun setUserId(userId: String) {
        try {
            CrashReport.setUserId(userId)
        } catch (e: Exception) {
            Log.w(TAG, "Bugly setUserId failed", e)
        }
    }

    // SDK 边界全量兜底
    @Suppress("TooGenericExceptionCaught")
    override fun putUserData(key: String, value: String) {
        try {
            CrashReport.putUserData(context, key, value)
        } catch (e: Exception) {
            Log.w(TAG, "Bugly putUserData failed", e)
        }
    }

    // SDK 边界全量兜底
    @Suppress("TooGenericExceptionCaught")
    override fun reportCaughtException(throwable: Throwable, context: Map<String, String>) {
        val buglyReported = try {
            CrashReport.postCatchedException(throwable)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Bugly postCatchedException unavailable, falling back to local log", e)
            false
        }
        if (!buglyReported) {
            crashHandler.recordCaughtException(throwable, context)
        }
    }
}

/** CrashReporter Hilt 绑定（core/domain 接口 ← app 层 Bugly 实现） */
@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
object CrashReporterModule {
    @dagger.Provides
    @javax.inject.Singleton
    fun provideCrashReporter(impl: BuglyCrashReporter): CrashReporter = impl
}
