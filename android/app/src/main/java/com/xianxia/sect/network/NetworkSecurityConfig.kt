package com.xianxia.sect.network

import android.util.Log
import com.xianxia.sect.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * 网络安全配置中心
 *
 * 集中管理所有网络安全相关参数：
 * - 证书固定策略开关
 * - TLS 版本限制（最低 TLS 1.2）
 * - 密码套件白名单
 * - 连接 / 读取 / 写入超时配置
 * - 防重放时间窗口
 */
object NetworkSecurityConfig {

    private const val TAG = "NetworkSecurityConfig"

    // ──────────────────────────────────────────────
    // 证书固定 (Certificate Pinning)
    // ──────────────────────────────────────────────

    /** 是否启用证书固定。Release 构建强制开启，Debug 构建可关闭以便开发调试 */
    val isCertificatePinningEnabled: Boolean
        get() = !BuildConfig.DEBUG || ENABLE_PINNING_IN_DEBUG

    /** Debug 构建是否也启用证书固定（默认 false，方便本地抓包调试） */
    private const val ENABLE_PINNING_IN_DEBUG = false

    /** 需要固定证书的主机列表 */
    val pinnedHosts: List<String> = listOf(
        "api.xianxia.com",
        "cdn.xianxia.com"
    )

    // ──────────────────────────────────────────────
    // TLS 版本限制
    // ──────────────────────────────────────────────

    /** 允许的最低 TLS 版本 */
    const val MIN_TLS_VERSION = "TLSv1.2"

    /** 启用的 TLS 版本列表（按优先级排序） */
    val enabledTlsVersions: Array<String> = arrayOf(
        "TLSv1.3",
        "TLSv1.2"
    )

    // ──────────────────────────────────────────────
    // 密码套件白名单 (Cipher Suites)
    // ──────────────────────────────────────────────

    /**
     * 允许的密码套件列表。
     *
     * 策略：
     * - 仅允许 AEAD 套件（提供加密 + 完整性保护）
     * - 优先使用 ECDHE 密钥交换（支持前向保密）
     * - 排除已知的弱套件（RC4, DES, 3DES, CBC 模式等）
     * - 排除无加密或导出级别套件
     */
    val allowedCipherSuites: Array<String> = arrayOf(
        // TLS 1.3 套件（由系统自动协商，此处声明用于文档目的）
        "TLS_AES_256_GCM_SHA384",
        "TLS_AES_128_GCM_SHA256",
        "TLS_CHACHA20_POLY1305_SHA256",

        // TLS 1.2 ECDHE + AES-GCM（优先）
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",

        // TLS 1.2 ECDHE + ChaCha20-Poly1305（ARM 设备性能更优）
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
    )

    // ──────────────────────────────────────────────
    // 超时配置
    // ──────────────────────────────────────────────

    /** 连接超时（毫秒） */
    const val CONNECT_TIMEOUT_MS = 10_000L

    /** 读取超时（毫秒）- 大型资源下载可能需要更长 */
    const val READ_TIMEOUT_MS = 30_000L

    /** 写入超时（毫秒） */
    const val WRITE_TIMEOUT_MS = 15_000L

    /** 完整的超时配置，可直接传入 OkHttp.Builder */
    val connectTimeoutSeconds: Long get() = TimeUnit.MILLISECONDS.toSeconds(CONNECT_TIMEOUT_MS)
    val readTimeoutSeconds: Long get() = TimeUnit.MILLISECONDS.toSeconds(READ_TIMEOUT_MS)
    val writeTimeoutSeconds: Long get() = TimeUnit.MILLISECONDS.toSeconds(WRITE_TIMEOUT_MS)

    // ──────────────────────────────────────────────
    // 请求签名 / 防重放
    // ──────────────────────────────────────────────

    /** HMAC-SHA256 签名密钥的 Hilt 绑定名称（由 DI 注入实际密钥） */
    const val SIGNATURE_KEY_ALIAS = "network_request_signing_key"

    /** 防重放时间窗口（毫秒）：服务端与客户端的时间差超过此值则拒绝请求 */
    const val REPLAY_WINDOW_MS = 5 * 60 * 1000L // 5 分钟

    /** 时间戳容差（毫秒）：客户端与服务端时钟允许的最大偏差 */
    const val TIMESTAMP_TOLERANCE_MS = 30_000L // 30 秒

    // ──────────────────────────────────────────────
    // 重试策略
    // ──────────────────────────────────────────────

    /** 最大自动重试次数（仅对幂等请求生效） */
    const val MAX_RETRY_COUNT = 2

    /** 重试基础退避时间（毫秒） */
    const val RETRY_BASE_DELAY_MS = 1_000L

    /** 重试最大退避时间（毫秒） */
    const val RETRY_MAX_DELAY_MS = 8_000L

    // ──────────────────────────────────────────────
    // 响应解密
    // ──────────────────────────────────────────────

    /** 是否启用响应体 AES-256-GCM 解密（需服务端配合加密响应） */
    const val RESPONSE_DECRYPTION_ENABLED = true

    /** 响应解密使用的 AES-GCM IV 长度（字节） */
    const val RESPONSE_IV_LENGTH = 12

    /** 响应解密使用的 GCM Tag 长度（位） */
    const val RESPONSE_GCM_TAG_LENGTH = 128

    // ──────────────────────────────────────────────
    // 调试 / 日志
    // ──────────────────────────────────────────────

    /** 是否打印网络请求/响应明细日志（仅 Debug 构建） */
    val isVerboseLoggingEnabled: Boolean get() = BuildConfig.DEBUG

    /**
     * 校验当前配置的安全性，在应用启动时调用。
     *
     * @return 配置是否通过安全校验
     */
    fun validate(): Boolean {
        var valid = true

        if (!isCertificatePinningEnabled && !BuildConfig.DEBUG) {
            Log.e(TAG, "[安全警告] Release 构建未启用证书固定！")
            valid = false
        }

        if (REPLAY_WINDOW_MS <= 0) {
            Log.e(TAG, "[配置错误] 防重放窗口必须为正数")
            valid = false
        }

        if (CONNECT_TIMEOUT_MS <= 0 || READ_TIMEOUT_MS <= 0 || WRITE_TIMEOUT_MS <= 0) {
            Log.e(TAG, "[配置错误] 所有超时值必须为正数")
            valid = false
        }

        if (valid) {
            Log.i(TAG, "网络安全配置校验通过")
        }

        return valid
    }
}
