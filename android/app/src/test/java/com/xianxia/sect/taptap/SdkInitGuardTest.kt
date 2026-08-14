package com.xianxia.sect.taptap

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SdkInitGuard 幂等守卫单元测试。
 *
 * 覆盖：首次放行 / 重复拦截 / 失败释放后可重试 / 并发 CAS 单赢家 /
 * 双守卫互相独立 / 测试复位。纯 JVM 无 Android 依赖。
 */
class SdkInitGuardTest {

    @Before
    fun setUp() {
        SdkInitGuard.resetForTest()
    }

    @After
    fun tearDown() {
        SdkInitGuard.resetForTest()
    }

    @Test
    fun `tryInitAdSdk - 首次返回 true 且后续返回 false`() {
        assertTrue("首次调用应获得初始化权", SdkInitGuard.tryInitAdSdk())
        assertFalse("第二次调用应被拦截", SdkInitGuard.tryInitAdSdk())
        assertFalse("第三次调用仍被拦截", SdkInitGuard.tryInitAdSdk())
    }

    @Test
    fun `tryInitTapTapSdk - 首次返回 true 且后续返回 false`() {
        assertTrue("首次调用应获得初始化权", SdkInitGuard.tryInitTapTapSdk())
        assertFalse("第二次调用应被拦截", SdkInitGuard.tryInitTapTapSdk())
    }

    @Test
    fun `releaseAdSdkInit - 释放后允许再次初始化`() {
        assertTrue(SdkInitGuard.tryInitAdSdk())
        SdkInitGuard.releaseAdSdkInit()
        assertTrue("释放后应允许重新初始化", SdkInitGuard.tryInitAdSdk())
    }

    @Test
    fun `releaseTapTapSdkInit - 释放后允许再次初始化`() {
        assertTrue(SdkInitGuard.tryInitTapTapSdk())
        SdkInitGuard.releaseTapTapSdkInit()
        assertTrue("释放后应允许重新初始化", SdkInitGuard.tryInitTapTapSdk())
    }

    @Test
    fun `双守卫互相独立`() {
        assertTrue(SdkInitGuard.tryInitAdSdk())
        assertTrue("TapTap 守卫不受广告守卫影响", SdkInitGuard.tryInitTapTapSdk())
        assertFalse(SdkInitGuard.tryInitAdSdk())
        assertFalse(SdkInitGuard.tryInitTapTapSdk())
    }

    @Test
    fun `并发调用 - 仅一个线程获得初始化权`() {
        val threadCount = 16
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)
        val winners = AtomicInteger(0)
        repeat(threadCount) {
            Thread {
                startGate.await()
                if (SdkInitGuard.tryInitAdSdk()) {
                    winners.incrementAndGet()
                }
                doneGate.countDown()
            }.start()
        }
        startGate.countDown()
        assertTrue("所有线程应在超时前完成", doneGate.await(5, TimeUnit.SECONDS))
        assertEquals("并发竞争下仅一个获胜者", 1, winners.get())
    }

    @Test
    fun `resetForTest - 复位后再次放行`() {
        assertTrue(SdkInitGuard.tryInitAdSdk())
        SdkInitGuard.resetForTest()
        assertTrue("复位后应重新放行", SdkInitGuard.tryInitAdSdk())
    }
}
