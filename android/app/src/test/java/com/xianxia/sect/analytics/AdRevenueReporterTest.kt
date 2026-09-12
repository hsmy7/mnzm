package com.xianxia.sect.analytics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AdRevenueReporter] 广告收入上报门面测试：开关/模式/配置路由。
 * 通过内部 [AdRevenueReporter.reportAction] 接缝观察上报调用，不触达 TapDB SDK。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdRevenueReporterTest {

    private lateinit var reporter: AdRevenueReporter
    private val reported = mutableListOf<AdRevenueConfig>()

    @Before
    fun setUp() {
        reporter = AdRevenueReporter().apply {
            reportAction = { reported.add(it) }
        }
        TapDBConfig.analyticsEnabled = true
        TapDBConfig.adRevenueMode = AdRevenueMode.CLIENT_ONLY
    }

    @After
    fun tearDown() {
        TapDBConfig.analyticsEnabled = true
        TapDBConfig.adRevenueMode = AdRevenueMode.CLIENT_ONLY
    }

    @Test
    fun `onAdShown - 已登记广告位在客户端模式下上报`() {
        reporter.onAdShown(1061442L)
        assertEquals(1, reported.size)
        assertEquals(1061442L, reported[0].spaceId)
        assertEquals("reward", reported[0].adType)
        assertEquals("CNY", reported[0].currency)
    }

    @Test
    fun `onAdShown - 未登记广告位跳过不上报`() {
        reporter.onAdShown(99999L)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `onAdShown - 分析总开关关闭时跳过`() {
        TapDBConfig.analyticsEnabled = false
        reporter.onAdShown(1061442L)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `onAdShown - 非客户端模式跳过（防重复统计）`() {
        TapDBConfig.adRevenueMode = AdRevenueMode.DISABLED
        reporter.onAdShown(1061442L)
        assertTrue(reported.isEmpty())

        TapDBConfig.adRevenueMode = AdRevenueMode.SERVER_ONLY
        reporter.onAdShown(1061442L)
        assertTrue(reported.isEmpty())
    }
}
