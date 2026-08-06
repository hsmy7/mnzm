package com.xianxia.sect.core.perf

import android.content.Context
import android.os.PerformanceHintManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ThermalMonitor PerformanceHint Session 线程绑定守卫单元测试。
 *
 * 背景：Bugly #3114 SIGABRT —— 看门狗 emergencyRestartGameLoop 换线程重启游戏循环后，
 * 旧线程的 finally 曾把新循环刚创建的 Session 跨线程 close → nativeCloseSession 原生 abort
 * （native abort 无法 try/catch，必须从源头杜绝跨线程 close）。
 * 守卫逻辑：create/close/report 全程 synchronized 互斥（检查与使用原子，无 TOCTOU 窗口）；
 * close/report 仅在属主线程执行；字段复位条件化。
 *
 * Robolectric 下 PerformanceHintManager 服务不可用（真实 createHintSession 产出 null），
 * 因此强断言用例通过 internal 字段直接注入 mock Session（abstract 类可 mock），
 * 验证 close()/reportActualWorkDuration() 的转发与线程守卫语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThermalMonitorTest {

    private lateinit var monitor: ThermalMonitor

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        monitor = ThermalMonitor(context)
    }

    @Test
    fun `createHintSession - 成功后记录属主线程`() {
        monitor.createHintSession(TARGET_DURATION_NS)

        assertEquals(Thread.currentThread(), monitor.sessionOwnerThread)
    }

    @Test
    fun `closeHintSession - 属主线程调用时真正关闭 session 并复位字段`() {
        val session = mock(PerformanceHintManager.Session::class.java)
        monitor.createHintSession(TARGET_DURATION_NS)
        monitor.hintSession = session

        monitor.closeHintSession()

        verify(session).close()
        assertNull(monitor.hintSession)
        assertNull(monitor.sessionOwnerThread)
    }

    @Test
    fun `closeHintSession - 非属主线程调用时跳过且不关闭 session`() {
        val session = mock(PerformanceHintManager.Session::class.java)
        monitor.createHintSession(TARGET_DURATION_NS)
        monitor.hintSession = session
        // 模拟换线程重启竞态：字段属主已换成新循环线程
        monitor.sessionOwnerThread = Thread("fake-new-loop-thread")

        monitor.closeHintSession()  // 旧线程的 finally 调用

        verify(session, never()).close()
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
        val session = mock(PerformanceHintManager.Session::class.java)
        monitor.createHintSession(TARGET_DURATION_NS)
        monitor.hintSession = session

        monitor.reportActualWorkDuration(10_000L)

        verify(session).reportActualWorkDuration(10_000L)
    }

    @Test
    fun `reportActualWorkDuration - 非属主线程调用时不转发`() {
        val session = mock(PerformanceHintManager.Session::class.java)
        monitor.createHintSession(TARGET_DURATION_NS)
        monitor.hintSession = session
        monitor.sessionOwnerThread = Thread("fake-owner-thread")

        monitor.reportActualWorkDuration(10_000L)

        verify(session, never()).reportActualWorkDuration(anyLong())
    }

    @Config(sdk = [30])
    @Test
    fun `createHintSession - API 30 及以下不记录属主线程`() {
        monitor.createHintSession(TARGET_DURATION_NS)

        assertNull(monitor.sessionOwnerThread)
    }

    @Config(sdk = [30])
    @Test
    fun `closeHintSession - API 30 及以下直接跳过无异常`() {
        monitor.closeHintSession()  // SDK 分支直接返回，无异常
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

    private companion object {
        const val TARGET_DURATION_NS = 16_666_667L
    }
}
