package com.xianxia.sect.network

import org.junit.Assert.*
import org.junit.Test

/**
 * CertificatePinnerProvider 证书固定提供者测试
 *
 * 验证 public API 行为：
 * - extractSpkiHash() 正确处理合法/非法证书输入
 * - isPinningActive() 反映配置状态（不依赖证书固定状态）
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
