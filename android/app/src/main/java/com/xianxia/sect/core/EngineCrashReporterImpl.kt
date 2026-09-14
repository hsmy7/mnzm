package com.xianxia.sect.core

import android.os.SystemClock
import com.xianxia.sect.core.engine.EngineCrashReporter
import com.xianxia.sect.core.platform.CrashReporter
import com.xianxia.sect.core.util.DomainLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 引擎异常上报实现：委托 [CrashReporter] 端口上报，Bugly SDK 直引收敛于
 * app 层 `BuglyCrashReporter`。
 *
 * 上报策略：
 * 1. 经 [CrashReporter.reportCaughtException] 上报（Bugly 实现内部回退
 *    [CrashHandler.recordCaughtException] 本地落盘兜底）；
 * 2. 上报失败绝不影响引擎循环继续运行（端口实现内部全部吞异常）。
 *
 * 结构化上下文（年/月/旬/tickCount/speed/OEM 等）写入 DomainLog 与本地兜底日志头部；
 * Bugly 仅收原始异常堆栈（postCatchedException 无 context 重载）。
 */
@Singleton
class BuglyEngineCrashReporter @Inject constructor(
    private val crashReporter: CrashReporter
) : EngineCrashReporter {

    // V2：崩溃循环速率限制——循环持续抛异常时 catch 每 100ms 上报一次，
    // 会刷屏 Bugly 队列 + 游戏线程文件 IO 恶化崩溃循环。同一异常类
    // [REPORT_MIN_INTERVAL_MS] 内只上报 1 次（循环 catch 的 DomainLog 承担日常日志）
    private val lastReportByType = HashMap<String, Long>()
    private val reportLock = Any()

    override fun postCatchedException(throwable: Throwable, context: Map<String, String>) {
        val typeKey = throwable.javaClass.name
        synchronized(reportLock) {
            val now = SystemClock.elapsedRealtime()
            val last = lastReportByType[typeKey] ?: 0L
            if (now - last < REPORT_MIN_INTERVAL_MS) return
            lastReportByType[typeKey] = now
            if (lastReportByType.size > MAX_TRACKED_EXCEPTION_TYPES) {
                lastReportByType.clear() // 防异常类型爆炸
            }
        }
        val contextText = context.entries.joinToString(", ") { "${it.key}=${it.value}" }
        DomainLog.e(TAG, "Engine crash context: $contextText")
        crashReporter.reportCaughtException(throwable, context)
    }

    private companion object {
        const val TAG = "BuglyEngineCrashReporter"

        /** 同异常类最小上报间隔（崩溃循环速率限制） */
        const val REPORT_MIN_INTERVAL_MS = 10_000L

        /** 追踪的异常类型上限（超过清空，防内存无限增长） */
        const val MAX_TRACKED_EXCEPTION_TYPES = 64
    }
}

/** EngineCrashReporter Hilt 绑定（引擎模块 @Inject 构造消费此实现） */
@Module
@InstallIn(SingletonComponent::class)
object EngineCrashReporterModule {
    @Provides
    @Singleton
    fun provideEngineCrashReporter(impl: BuglyEngineCrashReporter): EngineCrashReporter = impl
}
