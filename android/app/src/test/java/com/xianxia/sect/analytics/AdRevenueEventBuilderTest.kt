package com.xianxia.sect.analytics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AdRevenueEventBuilder] 纯 JSON 构建测试。
 *
 * Robolectric 提供真实 org.json 实现（returnDefaultValues 下 JVM stub 不可用）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdRevenueEventBuilderTest {

    private val config = AdRevenueConfig(
        spaceId = 1061442L,
        adType = "reward",
        unionType = "other",
        adNetwork = null,
        estimatedEcpmFen = 0L,
        currency = "CNY"
    )

    @Test
    fun `build - 包含 TapDB 必需字段且省略空网络字段`() {
        val json = AdRevenueEventBuilder.build(config)
        assertEquals("other", json.getString("#ad_union_type"))
        assertEquals("1061442", json.getString("#ad_placement_id"))
        assertEquals("reward", json.getString("#ad_type"))
        assertEquals(0L, json.getLong("#ecpm"))
        assertEquals("CNY", json.getString("currency_type"))
        assertFalse("adNetwork 为空时应省略可选字段 #ad_network", json.has("#ad_network"))
    }

    @Test
    fun `build - adNetwork 非空时包含 #ad_network`() {
        val json = AdRevenueEventBuilder.build(config.copy(adNetwork = "dirichlet"))
        assertEquals("dirichlet", json.getString("#ad_network"))
    }

    @Test
    fun `build - ecpm 配置非零时如实写入`() {
        val json = AdRevenueEventBuilder.build(config.copy(estimatedEcpmFen = 6000L))
        assertEquals(6000L, json.getLong("#ecpm"))
    }

    @Test
    fun `build - 产物为合法 JSONObject`() {
        val json: JSONObject = AdRevenueEventBuilder.build(config)
        // union/placement/type/ecpm/currency 共 5 键（adNetwork 为空省略）
        assertEquals(5, json.length())
    }
}
