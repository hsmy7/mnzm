package com.xianxia.sect.core.util

/**
 * 纯 Kotlin 日志工具
 *
 * 在 :core:domain 中替代 android.util.Log。
 * 生产环境由 :app 模块通过 [setLogger] 注入实际实现。
 */
object DomainLog {

    private var logger: Logger = DefaultLogger

    fun setLogger(logger: Logger) {
        this.logger = logger
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
