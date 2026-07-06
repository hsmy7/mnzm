package com.xianxia.sect.di

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * IoDispatcher 注入包装器测试。
 *
 * 验证 Hilt 注入链路中 IoDispatcher 包装类的正确性。
 * 实际注入集成测试需在 :app 模块的 instrumented 测试中执行。
 */
class IoDispatcherTest {

    @Test
    fun `IoDispatcher provides IO dispatcher`() {
        val io = IoDispatcher()
        assertSame(
            "IoDispatcher should wrap Dispatchers.IO",
            Dispatchers.IO, io.dispatcher
        )
    }
}
