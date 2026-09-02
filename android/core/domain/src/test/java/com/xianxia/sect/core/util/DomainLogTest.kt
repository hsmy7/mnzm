package com.xianxia.sect.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DomainLog] 契约测试（S-07 清偿守护）。
 *
 * 覆盖：setLogger 返回旧实现（保存-恢复能力）、日志路由到当前实现、
 * 以及恢复旧实现后不再路由到被替换的实现（基准测试 finally 恢复语义）。
 *
 * 命名规范：`方法名_状态_预期行为`（规范 §9.4 Given-When-Then）。
 */
class DomainLogTest {

    /** 记录每一次日志调用的实现，供断言路由目标 */
    private class RecordingLogger(private val name: String) : DomainLog.Logger {
        val calls = mutableListOf<String>()
        override fun d(tag: String, msg: String) { calls += "$name|d|$tag|$msg|null" }
        override fun i(tag: String, msg: String) { calls += "$name|i|$tag|$msg|null" }
        override fun w(tag: String, msg: String, throwable: Throwable?) {
            calls += "$name|w|$tag|$msg|${throwable?.message ?: "null"}"
        }
        override fun e(tag: String, msg: String, throwable: Throwable?) {
            calls += "$name|e|$tag|$msg|${throwable?.message ?: "null"}"
        }
    }

    /** 基准记录器：字段初始化后即替换前 current，setLogger 应返回它（用例内比较基准）。 */
    private val initialRecorder = RecordingLogger("initial")

    /** 最初原始实现（setLogger 的返回值）；@After 用它还原全局单例，避免污染同 JVM 其他测试。 */
    private val initialLogger: DomainLog.Logger = DomainLog.setLogger(initialRecorder)

    @After
    fun restoreInitialLogger() {
        DomainLog.setLogger(initialLogger)
    }

    @Test
    fun `setLogger returns previous implementation for save-restore`() {
        val first = RecordingLogger("first")
        val previous = DomainLog.setLogger(first)
        assertSame("首次替换应返回替换前 current（基准记录器）", initialRecorder, previous)

        val second = RecordingLogger("second")
        val previousOfSecond = DomainLog.setLogger(second)
        assertSame("连续替换应返回上一实现", first, previousOfSecond)

        val restored = DomainLog.setLogger(initialRecorder)  // 恢复到基准记录器
        assertSame("恢复应返回被替换的当前实现", second, restored)
    }

    @Test
    fun `log calls route to current logger with all levels and throwable`() {
        val recording = RecordingLogger("current")
        DomainLog.setLogger(recording)

        DomainLog.d("TAG_D", "debug 消息")
        DomainLog.i("TAG_I", "info 消息")
        DomainLog.w("TAG_W", "warn 消息", IllegalStateException("warn 异常"))
        DomainLog.e("TAG_E", "error 消息", IllegalStateException("error 异常"))

        assertEquals(
            listOf(
                "current|d|TAG_D|debug 消息|null",
                "current|i|TAG_I|info 消息|null",
                "current|w|TAG_W|warn 消息|warn 异常",
                "current|e|TAG_E|error 消息|error 异常"
            ),
            recording.calls
        )
    }

    @Test
    fun `restored logger no longer receives calls`() {
        val recording = RecordingLogger("current")
        val previous = DomainLog.setLogger(recording)

        DomainLog.setLogger(previous)
        DomainLog.d("TAG", "恢复后消息")

        assertTrue("恢复旧实现后被替换实现不应再收到调用", recording.calls.isEmpty())
    }
}
