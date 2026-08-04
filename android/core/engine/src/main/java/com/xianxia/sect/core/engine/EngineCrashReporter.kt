package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.annotation.GameService

/**
 * 引擎异常上报端口 — 让引擎模块的上报通道可注入（app 层提供 Bugly/CrashHandler 实现）。
 *
 * 背景：游戏循环 catch 块此前只记 DomainLog，异常源从未系统性归因（历史 27 次
 * "游戏时间停止"修复中 4 次为"吞异常+重启"模式）。通过本接口把被捕获异常
 * 连同结构化上下文（年/月/旬/tickCount/speed 等）上报，让下次回归有证据可循。
 */
@GameService(name = "EngineCrashReporter")
interface EngineCrashReporter {

    /**
     * 上报被捕获异常（带结构化上下文）。
     * 实现必须自行兜底：上报失败不得抛出异常（引擎循环继续运行）。
     *
     * @param throwable 被捕获的异常
     * @param context 结构化上下文（key 为英文标识，value 为可读值）
     */
    fun postCatchedException(throwable: Throwable, context: Map<String, String>)
}

/** 空实现：测试环境与无上报基建时使用（仅记日志）。 */
object NoopEngineCrashReporter : EngineCrashReporter {
    override fun postCatchedException(throwable: Throwable, context: Map<String, String>) {
        // 无上报基建：静默（调用方已记录 DomainLog）
    }
}
