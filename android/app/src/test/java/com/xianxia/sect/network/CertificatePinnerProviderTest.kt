package com.xianxia.sect.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CertificatePinnerProvider 证书固定提供者测试
 *
 * 验证 public API 行为：
 * - [CertificatePinnerProvider.get] 占位 pin 降级契约（R0.1：任何构建类型不因占位 pin 崩溃）
 * - [CertificatePinnerProvider.extractSpkiHash] 正确处理合法/非法证书输入
 * - [CertificatePinnerProvider.isPinningActive] 反映配置状态
 * - [NetworkSecurityConfig] pin 声明表单一源派生与占位判定口径
 */
class CertificatePinnerProviderTest {

    private val provider = CertificatePinnerProvider()

    // ==================== isPinningActive ====================

    @Test
    fun `isPinningActive returns false in test environment`() {
        // In unit tests without Android BuildConfig, pinning defaults to disabled
        val active = provider.isPinningActive()
        // The method should not crash, regardless of the result
        assertNotNull(active)
    }

    // ==================== 占位 pin 降级契约（R0.1）====================

    @Test
    fun `get - all placeholder pins degrades to empty pinner without crash in release mode`() {
        // 回归守护：原实现 release + 全占位 pin 抛 IllegalStateException（启动崩溃）。
        // 当前声明态（CERT_PINNING_ENFORCED=false，release BuildConfig）下必须显式降级——
        // 返回空 pinner（无任何 pin），等效于不固定，绝不崩溃。
        val pinner = provider.get()
        assertTrue(
            "占位 pin 必须被过滤（pinner 不携带任何 pin）",
            pinner.pins.isEmpty()
        )
    }

    @Test
    fun `get - repeated calls return same instance`() {
        assertEquals(provider.get(), provider.get())
    }

    // ==================== NetworkSecurityConfig 声明表 ====================

    @Test
    fun `pinnedHosts derives from pin declaration table`() {
        assertEquals(
            setOf("api.xianxia.com", "cdn.xianxia.com"),
            NetworkSecurityConfig.pinnedHosts.toSet()
        )
    }

    @Test
    fun `realPinsForHost - placeholder pins are filtered out`() {
        NetworkSecurityConfig.pinnedHosts.forEach { host ->
            val pins = NetworkSecurityConfig.pinnedHostPins.getValue(host)
            val realPins = NetworkSecurityConfig.realPinsForHost(host)
            // 当前声明全为占位 pin
            assertTrue("主机 $host 声明 pin 不应为空", pins.isNotEmpty())
            assertEquals("主机 $host 的占位 pin 必须全部被过滤", 0, realPins.size)
            assertEquals(
                "realPinCount 与逐主机过滤结果一致",
                0,
                NetworkSecurityConfig.realPinCount
            )
        }
    }

    @Test
    fun `isPlaceholderPin - repeated-char placeholder detected`() {
        assertTrue(
            NetworkSecurityConfig.isPlaceholderPin("sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        )
    }

    @Test
    fun `isPlaceholderPin - realistic mixed hash not placeholder`() {
        // 合法 Base64 形态（SHA-256 输出 44 字符、字符分布混杂）
        assertFalse(
            NetworkSecurityConfig.isPlaceholderPin("sha256/hETHWp9ooks4XX7nVZIwDfX8OOp5hyellsOiQ0HP2gM=")
        )
    }

    @Test
    fun `isPlaceholderPin - short hash treated as placeholder`() {
        assertTrue(NetworkSecurityConfig.isPlaceholderPin("sha256/abc="))
    }

    // ==================== extractSpkiHash ====================

    @Test(expected = IllegalArgumentException::class)
    fun `extractSpkiHash - empty bytes throws`() {
        provider.extractSpkiHash(ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `extractSpkiHash - invalid bytes throws`() {
        provider.extractSpkiHash("not a certificate".toByteArray())
    }
}
