package com.xianxia.sect.ui

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 守护"SDK 服务初始化与关键路径解耦"契约（2026-08-15 回归教训固化）。
 *
 * 背景：登录成功回调曾把 SDK 服务初始化（合规回调注册/广告/统计）与防沉迷验证
 * 串行绑定，初始化异常可阻断验证导致"登录成功但卡在登录界面"。本测试锁死
 * [safeRunAfterSdkInit] 的语义：**初始化异常绝不阻断后续关键步骤**（block 永远
 * 执行）；CancellationException 必须重抛；Error 不拦截（致命缺陷崩溃暴露）。
 *
 * 未来任何改动此编排的行为（调整顺序/吞异常/改签名）都会在此被拦截。
 */
class SafeRunAfterSdkInitTest {

    @Test
    fun `初始化正常 - 关键步骤照常执行且无失败日志`() {
        val initCalled = AtomicBoolean(false)
        val blockCalled = AtomicBoolean(false)
        val failures = AtomicInteger(0)

        safeRunAfterSdkInit(
            initSdkServices = { initCalled.set(true) },
            onInitFailed = { failures.incrementAndGet() },
            block = { blockCalled.set(true) }
        )

        assertTrue("初始化应被调用", initCalled.get())
        assertTrue("关键步骤应被调用", blockCalled.get())
        assertEquals("无异常时不应记录失败", 0, failures.get())
    }

    @Test
    fun `初始化抛异常 - 记录失败日志但关键步骤仍执行`() {
        val blockCalled = AtomicBoolean(false)
        val failures = AtomicInteger(0)

        safeRunAfterSdkInit(
            initSdkServices = { throw IllegalStateException("SDK 注册失败") },
            onInitFailed = { failures.incrementAndGet() },
            block = { blockCalled.set(true) }
        )

        assertEquals("异常应记录一次失败", 1, failures.get())
        assertTrue("初始化异常不得阻断关键步骤", blockCalled.get())
    }

    @Test
    fun `初始化抛异常 - 原始异常传递给日志回调`() {
        val captured = AtomicReference<Throwable?>()

        safeRunAfterSdkInit(
            initSdkServices = { throw IllegalStateException("boom") },
            onInitFailed = { captured.set(it) },
            block = {}
        )

        assertEquals("boom", captured.get()?.message)
    }

    @Test
    fun `CancellationException 必须重新抛出 - 不得被吞且 block 不执行`() {
        val blockCalled = AtomicBoolean(false)
        var rethrown: CancellationException? = null

        try {
            safeRunAfterSdkInit(
                initSdkServices = { throw CancellationException("cancel") },
                onInitFailed = {},
                block = { blockCalled.set(true) }
            )
            fail("CancellationException 应向上抛出")
        } catch (e: CancellationException) {
            rethrown = e
        }

        assertEquals("cancel", rethrown?.message)
        assertFalse("取消语义不得被吞，block 不应执行", blockCalled.get())
    }

    @Test
    fun `Error 不拦截 - 致命缺陷应崩溃暴露`() {
        var blockCalled = false
        val failures = AtomicInteger(0)

        try {
            safeRunAfterSdkInit(
                initSdkServices = { throw AssertionError("致命缺陷") },
                onInitFailed = { failures.incrementAndGet() },
                block = { blockCalled = true }
            )
            fail("Error 应向上抛出")
        } catch (e: AssertionError) {
            // 预期：Error 属于程序缺陷，不得吞掉（断言消息确认是原始异常）
            assertEquals("致命缺陷", e.message)
        }

        assertEquals("Error 不应被当作普通失败记录", 0, failures.get())
        assertFalse("Error 场景 block 不应执行", blockCalled)
    }

    @Test
    fun `block 抛异常 - 不经过初始化兜底直接上抛`() {
        try {
            safeRunAfterSdkInit(
                initSdkServices = {},
                onInitFailed = {},
                block = { throw IllegalStateException("关键步骤失败") }
            )
            fail("block 的异常应向上抛出")
        } catch (e: IllegalStateException) {
            assertEquals("关键步骤失败", e.message)
        }
    }
}
