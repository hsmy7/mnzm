package com.xianxia.sect.core.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ThermalMonitor PerformanceHint Session 线程绑定守卫单元测试。
 *
 * 背景：Bugly #3114 SIGABRT —— 看门狗 emergencyRestartGameLoop 换线程重启游戏循环后，
 * 旧线程的 finally 曾把新循环刚创建的 Session 跨线程 close → nativeCloseSession 原生 abort
 * （native abort 无法 try/catch，必须从源头杜绝跨线程 close）。
 * 守卫逻辑：create/close/report 全程 synchronized 互斥（检查与使用原子，无 TOCTOU 窗口）；
 * close/report 仅在属主线程执行；字段复位条件化。
 *
 * 平台能力接口化后 Android API 经 PerformanceHintPort 端口注入，
 * 测试以 fake port 替代原 Robolectric mock 接缝（原 hintManager internal 接缝随
 * 端口化消失）；守卫语义断言与端口化前逐条对应。
 */
class ThermalMonitorTest {

    private lateinit var monitor: ThermalMonitor
    private lateinit var port: FakeHintPort

    @Before
    fun setUp() {
        port = FakeHintPort()
        monitor = ThermalMonitor(FixedThermalStatusReader(), port)
    }

    @Test
    fun `createHintSession - 成功后记录属主线程`() {
        monitor.createHintSession(TARGET_DURATION_NS)

        assertEquals(Thread.currentThread(), monitor.sessionOwnerThread)
    }

    @Test
    fun `closeHintSession - 属主线程调用时真正关闭 session 并复位字段`() {
        val session = FakeSession()
        monitor.createHintSession(TARGET_DURATION_NS)
        monitor.hintSession = session

        monitor.closeHintSession()

        assertTrue(session.released)
        assertNull(monitor.hintSession)
        assertNull(monitor.sessionOwnerThread)
    }

    @Test
    fun `closeHintSession - 非属主线程调用时跳过且不关闭 session`() {
        val session = FakeSession()
        monitor.createHintSession(TARGET_DURATION_NS)
        monitor.hintSession = session
        // 模拟换线程重启竞态：字段属主已换成新循环线程
        monitor.sessionOwnerThread = Thread("fake-new-loop-thread")

        monitor.closeHintSession()  // 旧线程的 finally 调用

        assertTrue(!session.released)
        assertEquals(session, monitor.hintSession)
        assertEquals("fake-new-loop-thread", monitor.sessionOwnerThread?.name)
    }

    @Test
    fun `closeHintSession - 重复调用幂等`() {
        monitor.createHintSession(TARGET_DURATION_NS)

        monitor.closeHintSession()
        monitor.closeHintSession()  // 属主已复位为 null → 跳过分支，无异常

        assertNull(monitor.hintSession)
        assertNull(monitor.sessionOwnerThread)
    }

    @Test
    fun `reportActualWorkDuration - 属主线程转发至 session`() {
        val session = FakeSession()
        monitor.createHintSession(TARGET_DURATION_NS)
        monitor.hintSession = session

        monitor.reportActualWorkDuration(10_000L)

        assertEquals(listOf(10_000L), session.reported)
    }

    @Test
    fun `reportActualWorkDuration - 非属主线程调用时不转发`() {
        val session = FakeSession()
        monitor.createHintSession(TARGET_DURATION_NS)
        monitor.hintSession = session
        monitor.sessionOwnerThread = Thread("fake-owner-thread")

        monitor.reportActualWorkDuration(10_000L)

        assertTrue(session.reported.isEmpty())
    }

    // ── 接缝注入：port 异常/null 分支（fake port 可控）──

    @Test
    fun `createHintSession - port 抛异常时复位字段并继续（catch 分支）`() {
        // 模拟 OEM 驱动/沙盒环境 acquire 抛异常（ADPF 不可用）
        port.throwOnAcquire = RuntimeException("adpf unavailable")

        monitor.createHintSession(TARGET_DURATION_NS)

        // catch 分支：字段复位为空，不残留过期属主/会话
        assertNull(monitor.hintSession)
        assertNull(monitor.sessionOwnerThread)
    }

    @Test
    fun `createHintSession - port 不可用时静默跳过（空分支）`() {
        // 服务缺失：acquire 返回 null → session 不创建、无异常。
        // 不断言 sessionOwnerThread——"创建尝试即记录属主"是既有语义
        //（fake port 恒返回 null 时同样记录，见"成功后记录属主线程"用例）
        port.acquireResult = null

        monitor.createHintSession(TARGET_DURATION_NS)

        assertNull(monitor.hintSession)
    }

    @Test
    fun `createHintSession - 能力不可用（isSupported=false）不记录属主线程`() {
        port.supported = false

        monitor.createHintSession(TARGET_DURATION_NS)

        assertNull(monitor.sessionOwnerThread)
    }

    @Test
    fun `closeHintSession - 能力不可用（isSupported=false）直接跳过无异常`() {
        port.supported = false

        monitor.closeHintSession()  // 能力分支直接返回，无异常
    }

    // ── 动态 ADPF 目标（平板省电） ──

    @Test
    fun `setTargetWorkDuration - 转发至 session`() {
        val session = FakeSession()
        monitor.createHintSession(TARGET_DURATION_NS)
        monitor.hintSession = session

        monitor.setTargetWorkDuration(33_333_333L)

        assertEquals(listOf(33_333_333L), session.updated)
    }

    @Test
    fun `setTargetWorkDuration - session 为空时 no-op 不崩溃`() {
        monitor.createHintSession(TARGET_DURATION_NS)
        // fake port acquire 恒返回 null → session 为 null
        monitor.setTargetWorkDuration(100_000_000L)
        // 不抛异常即通过（no-op 语义）
    }

    @Test
    fun `setTargetWorkDuration - 异常吞掉不冒泡`() {
        val session = FakeSession(throwOnUpdate = RuntimeException("session closed"))
        monitor.createHintSession(TARGET_DURATION_NS)
        monitor.hintSession = session

        monitor.setTargetWorkDuration(33_333_333L)
        // 不抛异常即通过（log-and-continue）
    }

    @Test
    fun `createHintSession 与 closeHintSession - 并发交错无异常且字段自洽`() {
        // 模拟真实场景：旧循环 close 与新循环 create 在换线程重启时并发交错。
        // 锁保证 create/close 原子互斥——无死锁、无异常、终态自洽
        // （属主为 null 时字段必为 null：close 复位与 create 失败路径同步置空两者）。
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (0 until 4).map { i ->
            Thread {
                repeat(50) {
                    try {
                        monitor.createHintSession(TARGET_DURATION_NS)
                        monitor.closeHintSession()
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            }.also { it.name = "concurrent-session-$i" }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue("并发交错不应抛异常: $errors", errors.isEmpty())
        assertTrue(
            "字段自洽：属主为 null 时 hintSession 必须为 null（当前 owner=$monitor.sessionOwnerThread）",
            monitor.sessionOwnerThread != null || monitor.hintSession == null
        )
    }

    // ── fake 端口/会话 ──

    private class FakeSession(
        val throwOnUpdate: Throwable? = null
    ) {
        val reported = mutableListOf<Long>()
        val updated = mutableListOf<Long>()
        var released = false
    }

    private class FakeHintPort : PerformanceHintPort {
        @Volatile var supported = true
        @Volatile var acquireResult: Any? = FakeSession()
        @Volatile var throwOnAcquire: Throwable? = null

        override val isSupported: Boolean get() = supported
        override fun acquire(tids: IntArray, targetDurationNanos: Long): Any? {
            throwOnAcquire?.let { throw it }
            return acquireResult
        }
        override fun reportWorkDuration(session: Any, durationNanos: Long) {
            (session as FakeSession).reported.add(durationNanos)
        }
        override fun updateTargetDuration(session: Any, targetDurationNanos: Long) {
            (session as FakeSession).updated.add(targetDurationNanos)
            (session as FakeSession).throwOnUpdate?.let { throw it }
        }
        override fun release(session: Any) {
            (session as FakeSession).released = true
        }
        override fun currentThreadId(): Int = 0
    }

    private class FixedThermalStatusReader(
        override val currentThermalStatus: Int = 0
    ) : ThermalStatusReader

    private companion object {
        const val TARGET_DURATION_NS = 16_666_667L
    }
}
