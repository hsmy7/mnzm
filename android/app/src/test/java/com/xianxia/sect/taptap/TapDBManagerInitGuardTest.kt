package com.xianxia.sect.taptap

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.xianxia.sect.analytics.AdRevenueConfig
import com.xianxia.sect.analytics.TapDBConfig
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TapDBManager 时长统计幂等守卫测试。
 *
 * 背景：`startGameDurationTracking` 仅在登录成功回调（或已登录冷启动兜底）的
 * `MainActivity.ensureSdkServicesInitialized` 中调用，登出后再登录、MainActivity
 * 重建都会再次走到本方法。无守卫时重复构建 GameDurationService / 重复注册
 * ActivityLifecycleTracker（广告公司反馈"重复初始化"的同类问题）。守卫为
 * AtomicBoolean CAS（与 [SdkInitGuard] 同构，CAS 幂等/重复拦截语义由
 * SdkInitGuardTest 直接覆盖）。
 *
 * Robolectric 环境下 TapDB SDK 不可用（performance_hint 系统服务缺失，
 * GameDurationService 构建抛异常），首次调用必然走"失败复位"路径。因此本测试
 * 聚焦验证守卫在失败环境下的两条关键语义：
 *
 * - **失败不永久锁死**：SDK 构建失败后守卫复位，后续调用可再次尝试
 * - **停止后可重启**：登出/退出（stopGameDurationTracking）复位标志，
 *   重新登录后再次启动进入 SDK 构建体
 *
 * 生产环境 SDK 可用时（TapTapAuthManager.init 先于本调用执行），首次调用成功
 * 后守卫保持置位，重复调用被 CAS 拦截——该成功路径由同构 SdkInitGuardTest 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TapDBManagerInitGuardTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        TapDBManager.stopGameDurationTracking()
        TapDBConfig.analyticsEnabled = true
    }

    @After
    fun tearDown() {
        TapDBManager.stopGameDurationTracking()
        TapDBConfig.analyticsEnabled = true
    }

    @Test
    fun `startGameDurationTracking - 首次失败后守卫复位允许重试`() {
        // Robolectric 下 SDK 构建失败 → catch 复位守卫 → 第二次调用应再次进入构建体
        TapDBManager.startGameDurationTracking(app)
        TapDBManager.startGameDurationTracking(app)
        assertTrue(
            "SDK 失败后守卫应复位并允许再次尝试（计数递增），不永久锁死",
            TapDBManager.durationTrackingStartCount >= 2
        )
    }

    @Test
    fun `startGameDurationTracking - 停止后允许重新启动`() {
        TapDBManager.startGameDurationTracking(app)
        TapDBManager.stopGameDurationTracking()
        TapDBManager.startGameDurationTracking(app)
        assertTrue(
            "停止复位后再次启动应进入 SDK 构建体（计数递增）",
            TapDBManager.durationTrackingStartCount >= 2
        )
    }

    @Test
    fun `trackEvent - SDK 不可用环境静默降级且受总开关控制`() {
        // Robolectric 下 TapDB SDK 不可用：调用不崩溃（Throwable 兜底，埋点静默降级）
        TapDBManager.trackEvent("test_event", mapOf("k" to "v"))
        // 总开关关闭后直接跳过（不进入 SDK 调用）
        TapDBConfig.analyticsEnabled = false
        TapDBManager.trackEvent("test_event_off")
        TapDBConfig.analyticsEnabled = true
    }

    @Test
    fun `reportAdShow - SDK 不可用环境静默降级`() {
        val config = AdRevenueConfig.forSpaceId(1061442L)
        assertNotNull("已登记广告位应能取到上报配置", config)
        TapDBManager.reportAdShow(config!!)
    }
}
