package com.xianxia.sect.core.util

/**
 * 纯 Kotlin 日志工具
 *
 * 在 :core:domain 中替代 android.util.Log。
 * 生产环境由 :app 模块通过 [setLogger] 注入实际实现。
 */
object DomainLog {

    private var logger: Logger = DefaultLogger

    /**
     * 替换当前日志实现，并返回替换前的旧实现。
     *
     * 调用方（如基准测试）可在变更前保存返回值、在 finally 中恢复，
     * 避免 setLogger 后无法还原旧 logger（恢复能力）。
     *
     * @param logger 新的日志实现
     * @return 替换前的旧实现——保存后可在 [setLogger] 中传回以恢复
     */
    fun setLogger(logger: Logger): Logger {
        val previous = this.logger
        this.logger = logger
        return previous
    }

    fun d(tag: String, msg: String) = logger.d(tag, msg)
    fun i(tag: String, msg: String) = logger.i(tag, msg)
    fun w(tag: String, msg: String, throwable: Throwable? = null) = logger.w(tag, msg, throwable)
    fun e(tag: String, msg: String, throwable: Throwable? = null) = logger.e(tag, msg, throwable)

    interface Logger {
        fun d(tag: String, msg: String)
        fun i(tag: String, msg: String)
        fun w(tag: String, msg: String, throwable: Throwable? = null)
        fun e(tag: String, msg: String, throwable: Throwable? = null)
    }

    private object DefaultLogger : Logger {
        override fun d(tag: String, msg: String) { println("DEBUG: [$tag] $msg") }
        override fun i(tag: String, msg: String) { println("INFO: [$tag] $msg") }
        override fun w(tag: String, msg: String, throwable: Throwable?) {
            println("WARN: [$tag] $msg")
            throwable?.printStackTrace()
        }
        override fun e(tag: String, msg: String, throwable: Throwable?) {
            println("ERROR: [$tag] $msg")
            throwable?.printStackTrace()
        }
    }
}
