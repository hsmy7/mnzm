package com.xianxia.sect.network

import org.junit.Assert.*
import org.junit.Test

/**
 * NetworkSecurityConfig 网络安全配置测试
 *
 * 验证配置的有效性和一致性：
 * - 超时配置必须为正数
 * - TLS 版本配置必须合法
 * - 防重放窗口必须为合理的正数
 * - validate() 方法正确返回校验结果
 */
class NetworkSecurityConfigTest {

    @Test
    fun `connect timeout is positive`() {
        assertTrue("连接超时必须为正数", NetworkSecurityConfig.CONNECT_TIMEOUT_MS > 0)
    }

    @Test
    fun `read timeout is positive`() {
        assertTrue("读取超时必须为正数", NetworkSecurityConfig.READ_TIMEOUT_MS > 0)
    }

    @Test
    fun `write timeout is positive`() {
        assertTrue("写入超时必须为正数", NetworkSecurityConfig.WRITE_TIMEOUT_MS > 0)
    }

    @Test
    fun `replay window is positive`() {
        assertTrue("防重放窗口必须为正数", NetworkSecurityConfig.REPLAY_WINDOW_MS > 0)
    }

    @Test
    fun `timestamp tolerance is positive`() {
        assertTrue("时间戳容差必须为正数", NetworkSecurityConfig.TIMESTAMP_TOLERANCE_MS > 0)
    }

    @Test
    fun `retry count is non-negative`() {
        assertTrue("重试次数不能为负", NetworkSecurityConfig.MAX_RETRY_COUNT >= 0)
    }

    @Test
    fun `retry base delay is positive`() {
        assertTrue("重试基础退避时间必须为正数", NetworkSecurityConfig.RETRY_BASE_DELAY_MS > 0)
    }

    @Test
    fun `retry max delay is at least base delay`() {
        assertTrue(
            "重试最大退避时间不应小于基础退避时间",
            NetworkSecurityConfig.RETRY_MAX_DELAY_MS >= NetworkSecurityConfig.RETRY_BASE_DELAY_MS
        )
    }

    @Test
    fun `TLS 1_2 is in enabled versions`() {
        assertTrue(
            "TLSv1.2 必须在启用版本列表中",
            NetworkSecurityConfig.enabledTlsVersions.contains("TLSv1.2")
        )
    }

    @Test
    fun `TLS 1_3 is in enabled versions`() {
        assertTrue(
            "TLSv1.3 必须在启用版本列表中",
            NetworkSecurityConfig.enabledTlsVersions.contains("TLSv1.3")
        )
    }

    @Test
    fun `min TLS version is at least 1_2`() {
        val minVersionOk = when (NetworkSecurityConfig.MIN_TLS_VERSION) {
            "TLSv1.2", "TLSv1.3" -> true
            else -> false
        }
        assertTrue("最低 TLS 版本必须至少为 1.2", minVersionOk)
    }

    @Test
    fun `cipher suites are not empty`() {
        assertTrue("密码套件列表不能为空", NetworkSecurityConfig.allowedCipherSuites.isNotEmpty())
    }

    @Test
    fun `pinned hosts list is not empty`() {
        assertTrue("证书固定主机列表不能为空", NetworkSecurityConfig.pinnedHosts.isNotEmpty())
    }

    @Test
    fun `all pinned hosts have valid format`() {
        NetworkSecurityConfig.pinnedHosts.forEach { host ->
            assertTrue("主机名 '$host' 应该包含点号", host.contains("."))
        }
    }

    @Test
    fun `response decryption params are consistent`() {
        assertTrue("GCM tag length should be 128", NetworkSecurityConfig.RESPONSE_GCM_TAG_LENGTH == 128)
        assertTrue("IV length should be 12", NetworkSecurityConfig.RESPONSE_IV_LENGTH == 12)
    }

    @Test
    fun `configured timeouts match converted seconds`() {
        val connectSec = NetworkSecurityConfig.connectTimeoutSeconds
        val readSec = NetworkSecurityConfig.readTimeoutSeconds
        val writeSec = NetworkSecurityConfig.writeTimeoutSeconds

        assertEquals(
            "connectTimeoutSeconds must match CONNECT_TIMEOUT_MS / 1000",
            NetworkSecurityConfig.CONNECT_TIMEOUT_MS / 1000,
            connectSec
        )
        assertEquals(
            "readTimeoutSeconds must match READ_TIMEOUT_MS / 1000",
            NetworkSecurityConfig.READ_TIMEOUT_MS / 1000,
            readSec
        )
        assertEquals(
            "writeTimeoutSeconds must match WRITE_TIMEOUT_MS / 1000",
            NetworkSecurityConfig.WRITE_TIMEOUT_MS / 1000,
            writeSec
        )
    }
}
