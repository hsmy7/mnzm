package com.xianxia.sect.core

import com.xianxia.sect.core.engine.EngineCrashReporter
import com.xianxia.sect.core.util.DomainLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 引擎异常上报的 Bugly 实现（app 层绑定，引擎模块端口化消费）。
 *
 * 上报策略：
 * 1. 反射调用 Bugly `CrashReport.postCatchedException`（与 GameLoopDelegate 现有
 *    模式一致——引擎模块无 Bugly 依赖，反射避免模块依赖反转）；
 * 2. Bugly 不可用（非 release/类加载失败/调用异常）时回退 [CrashHandler.recordCaughtException]
 *    本地落盘兜底；
 * 3. 上报失败绝不影响引擎循环继续运行（实现内部全部吞异常）。
 *
 * 结构化上下文（年/月/旬/tickCount/speed/OEM 等）写入 DomainLog 与本地兜底日志头部；
 * Bugly 仅收原始异常堆栈（postCatchedException 无 context 重载）。
 */
@Singleton
class BuglyEngineCrashReporter @Inject constructor(
    private val crashHandler: CrashHandler
) : EngineCrashReporter {

    override fun postCatchedException(throwable: Throwable, context: Map<String, String>) {
        val contextText = context.entries.joinToString(", ") { "${it.key}=${it.value}" }
        DomainLog.e(TAG, "Engine crash context: $contextText")

        @Suppress("TooGenericExceptionCaught") // 反射失败类型不可预期（类缺失/方法签名变化），必须全吞
        val buglyReported = try {
            val crashReport = Class.forName("com.tencent.bugly.crashreport.CrashReport")
            crashReport.getMethod("postCatchedException", Throwable::class.java)
                .invoke(null, throwable)
            true
        } catch (e: Exception) {
            DomainLog.w(TAG, "Bugly postCatchedException unavailable, falling back to local log", e)
            false
        }

        if (!buglyReported) {
            crashHandler.recordCaughtException(throwable, context)
        }
    }

    private companion object {
        const val TAG = "BuglyEngineCrashReporter"
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
