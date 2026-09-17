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

    /**
     * 是否启用证书固定。
     *
     * 声明式开关：[BuildConfig.CERT_PINNING_ENFORCED]（源自 api.properties）。
     * - false（凭证未就绪的当前态）：pinning 显式关闭，release 可正常出包，
     *   构建期任务 `validateCertificatePins` 只告警不阻断
     * - true（真实 pin 已就位）：pinning 强制启用，构建期遇占位 pin 即 fail
     *   （把占位 pin 的运行时失效/崩溃前移到构建期）
     *
     * Debug 构建可经 [ENABLE_PINNING_IN_DEBUG] 关闭以便抓包调试。
     */
    val isCertificatePinningEnabled: Boolean
        get() = BuildConfig.CERT_PINNING_ENFORCED && (!BuildConfig.DEBUG || ENABLE_PINNING_IN_DEBUG)

    /** Debug 构建是否也启用证书固定（默认 false，方便本地抓包调试） */
    private const val ENABLE_PINNING_IN_DEBUG = false

    // ── SPKI SHA-256 pin 声明表（单一声明源：主机 + 主/备 pin）──────────
    //
    // 【重要】以下为占位值，部署前必须替换为实际服务器证书 SPKI 哈希！
    // 获取方式见 CertificatePinnerProvider 类注释中的 openssl 命令。
    //
    // 占位状态与声明开关的一致性由构建期任务 `validateCertificatePins` 执法：
    // CERT_PINNING_ENFORCED=true 时存在占位 pin → release 构建失败。

    /** api.xianxia.com 主 pin（占位——服务器部署后用 openssl 提取） */
    private const val API_PRIMARY_PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    /** api.xianxia.com 备用 pin（占位——证书轮换期新证书 SPKI hash，无轮换计划可留占位） */
    private const val API_BACKUP_PIN = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="

    /** cdn.xianxia.com 主 pin（占位——CDN 服务商确定后提取；不用独立 CDN 可移除该主机） */
    private const val CDN_PRIMARY_PIN = "sha256/CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC="

    /** cdn.xianxia.com 备用 pin（占位） */
    private const val CDN_BACKUP_PIN = "sha256/DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD="

    /**
     * 主机 → SPKI pin 列表声明表（主 + 备用）。
     * 占位 pin 在构建 pinner 时被过滤，等效于该主机无固定。
     */
    val pinnedHostPins: Map<String, List<String>> = linkedMapOf(
        "api.xianxia.com" to listOf(API_PRIMARY_PIN, API_BACKUP_PIN),
        "cdn.xianxia.com" to listOf(CDN_PRIMARY_PIN, CDN_BACKUP_PIN)
    )

    /** 需要固定证书的主机列表（从 [pinnedHostPins] 派生，保持声明单一源） */
    val pinnedHosts: List<String>
        get() = pinnedHostPins.keys.toList()

    /** 指定主机的有效（非占位）pin 列表 */
    fun realPinsForHost(host: String): List<String> =
        pinnedHostPins[host].orEmpty().filter { !isPlaceholderPin(it) }

    /** 全部声明 pin 中有效（非占位）pin 总数 */
    val realPinCount: Int
        get() = pinnedHostPins.values.sumOf { pins -> pins.count { !isPlaceholderPin(it) } }

    /**
     * 检测一个 pin 是否仍是占位符（尚未被替换为真实值）。
     *
     * 占位特征：哈希段内超过 80% 字符相同（如 AAAA... 模式）——
     * 不是合法 Base64 编码的 SHA-256 输出。与构建期任务
     * `validateCertificatePins` 的判定口径一致。
     */
    fun isPlaceholderPin(pin: String): Boolean {
        val hashPart = pin.removePrefix("sha256/")
        if (hashPart.length < 16) return true

        val firstChar = hashPart[0]
        var repeatCount = 0
        for (c in hashPart) {
            if (c == firstChar) repeatCount++
        }
        return repeatCount > hashPart.length * 0.8
    }

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

        if (!BuildConfig.DEBUG && !isCertificatePinningEnabled) {
            // 凭证未就绪的显式降级态（CERT_PINNING_ENFORCED=false）：有意的安全权衡，
            // 非配置错误——告警提示补齐路径，不计入校验失败
            Log.w(
                TAG,
                """[安全警告] Release 构建证书固定未启用（显式降级态）！
                   |存在中间人攻击风险。服务器证书就绪后：
                   |1. 用 openssl 提取 SPKI hash 替换 NetworkSecurityConfig 占位 pin
                   |2. api.properties 置 CERT_PINNING_ENFORCED=true（构建期强制真实值）
                """.trimMargin()
            )
        }

        if (isCertificatePinningEnabled && realPinCount == 0) {
            Log.e(TAG, "[配置错误] 证书固定声明启用但无任何有效 pin（构建期校验应已拦截）")
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
