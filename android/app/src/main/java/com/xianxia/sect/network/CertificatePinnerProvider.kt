package com.xianxia.sect.network

import android.util.Log
import com.xianxia.sect.BuildConfig
import okhttp3.CertificatePinner
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 证书固定 (Certificate Pinning) 提供者
 *
 * 使用 **SPKI (Subject Public Key Info) SHA-256** 哈希进行证书固定。
 *
 * 为什么选择 SPKI 而非证书哈希：
 * - SPKI 固定的是公钥，而非整张证书。证书过期/更换时只要私钥不变，
 *   SPKI 哈希就不变，因此可跨越证书续期。
 * - 比固定整个证书更灵活，适合证书轮换场景。
 *
 * 功能：
 * - 支持每个主机配置多个 SPKI 哈希（主证书 + 备用证书）
 * - Debug 构建可通过 [NetworkSecurityConfig] 开关绕过固定（方便抓包调试）
 * - pin 声明/开关集中在 [NetworkSecurityConfig]（单一声明源）
 *
 * ## 占位 pin 的降级契约（R0.1）
 *
 * 占位 pin 一律过滤，**任何构建类型下都不因占位 pin 崩溃**：
 * - 凭证未就绪（CERT_PINNING_ENFORCED=false）→ pinning 显式关闭，等效空 pinner
 * - 声明启用但个别 pin 仍是占位 → 过滤占位，用真实 pin 子集构建
 * - "占位 pin + 声明启用"的矛盾状态由构建期任务 `validateCertificatePins`
 *   拦截（release 直接 fail），运行时只做防御性降级
 *
 * ## 如何获取 SPKI SHA-256 哈希
 *
 * ```bash
 * # 方法1: 使用 openssl（推荐）
 * echo | openssl s_client -connect api.xianxia.com:443 2>/dev/null \
 *   | openssl x509 -noout -pubkey \
 *   | openssl pkey -pubin -outform der \
 *   | openssl dgst -sha256 -binary \
 *   | base64
 *
 * # 方法2: 使用 Google 的工具脚本
 * # https://github.com/nicokruithof/certpinning
 * ```
 */
@Singleton
class CertificatePinnerProvider @Inject constructor() {

    companion object {
        private const val TAG = "CertificatePinnerProvider"
    }

    /** 已构建的 CertificatePinner 实例（懒加载） */
    private val certificatePinner: CertificatePinner by lazy { buildCertificatePinner() }

    /**
     * 获取配置好的 [CertificatePinner] 实例。
     *
     * @return 可直接传入 OkHttp.Builder.certificatePinner() 的实例
     */
    fun get(): CertificatePinner {
        return certificatePinner
    }

    /**
     * 检查证书固定是否当前处于活跃状态。
     */
    fun isPinningActive(): Boolean {
        return NetworkSecurityConfig.isCertificatePinningEnabled
    }

    // ──────────────────────────────────────────────
    // 内部构建
    // ──────────────────────────────────────────────

    /**
     * 构建 OkHttp CertificatePinner 实例。
     *
     * 根据 [NetworkSecurityConfig.isCertificatePinningEnabled] 决定是否启用：
     * - **未启用**（显式降级态 / debug 抓包态）：空 pinner，等效于不固定
     * - **启用**：仅使用各主机的有效（非占位）pin；全部占位时降级为空 pinner
     *   并告警（该矛盾状态在 release 构建期已被 `validateCertificatePins` 拦截）
     */
    @Suppress("SpreadOperator") // OkHttp CertificatePinner.add 为 vararg API，动态 pin 列表必须散布传入
    private fun buildCertificatePinner(): CertificatePinner {
        if (!NetworkSecurityConfig.isCertificatePinningEnabled) {
            Log.i(
                TAG,
                "证书固定未启用（显式降级态或 debug 抓包态，" +
                    "CERT_PINNING_ENFORCED=${BuildConfig.CERT_PINNING_ENFORCED}）"
            )
            return CertificatePinner.Builder().build()
        }

        val builder = CertificatePinner.Builder()
        var totalValidPins = 0

        for (host in NetworkSecurityConfig.pinnedHosts) {
            val pins = NetworkSecurityConfig.realPinsForHost(host)
            if (pins.isNotEmpty()) {
                builder.add(host, *pins.toTypedArray())
                totalValidPins += pins.size
                Log.i(TAG, "已为主机 [$host] 配置 ${pins.size} 个证书固定项")
            } else {
                Log.e(TAG, "[安全风险] 主机 [$host] 所有 SPKI 哈希均为占位符，证书固定对该主机无效！请替换为真实值。")
            }
        }

        if (totalValidPins == 0) {
            Log.e(
                TAG,
                """[严重安全警告] 所有主机的证书固定值均为占位符，已降级为无固定！
                   |该状态在 release 构建期应被 validateCertificatePins 拦截。
                   |请使用 extractSpkiHash() 提取真实证书哈希并替换
                   |NetworkSecurityConfig 中的占位值。
                """.trimMargin()
            )
        }

        val pinner = builder.build()
        Log.i(TAG, "CertificatePinner 构建完成，覆盖 ${NetworkSecurityConfig.pinnedHosts.size} 个主机，共 $totalValidPins 个有效 pin")

        return pinner
    }

    // ──────────────────────────────────────────────
    // 工具方法：从证书文件提取 SPKI hash
    // ──────────────────────────────────────────────

    /**
     * 从 DER/PEM 编码的 X.509 证书中提取 SPKI SHA-256 哈希。
     *
     * 此方法主要用于开发阶段的辅助工具，
     * 从 .cer / .crt 文件中提取正确的 pin 值填入 [NetworkSecurityConfig] 常量。
     *
     * @param certBytes 证书原始字节（DER 或 PEM 文本）
     * @return sha256/<Base64> 格式的 pin 字符串
     * @throws IllegalArgumentException 证书解析失败时抛出
     */
    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
    fun extractSpkiHash(certBytes: ByteArray): String {
        return try {
            val certFactory = CertificateFactory.getInstance("X.509")
            val certBytesNormalized = normalizeCertBytes(certBytes)
            val certificate = certFactory.generateCertificate(
                certBytesNormalized.inputStream()
            ) as X509Certificate

            val spki = certificate.publicKey.encoded
            val digest = MessageDigest.getInstance("SHA-256").digest(spki)
            val base64 = android.util.Base64.encodeToString(
                digest, android.util.Base64.NO_WRAP
            )

            Log.i(TAG, "成功提取 SPKI SHA-256 哈希: sha256/$base64")
            "sha256/$base64"
        } catch (e: Exception) {
            Log.e(TAG, "提取 SPKI 哈希失败", e)
            throw IllegalArgumentException("无法从证书字节提取 SPKI 哈希: ${e.message}", e)
        }
    }

    /**
     * 规范化证书字节输入：自动检测 PEM 格式并转换为 DER。
     */
    private fun normalizeCertBytes(input: ByteArray): ByteArray {
        val inputStr = String(input, Charsets.UTF_8)
        return if (inputStr.contains("-----BEGIN CERTIFICATE-----")) {
            // PEM 格式：去除头尾标记和换行
            val base64Content = inputStr
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\\s".toRegex(), "")
            android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT)
        } else {
            // 已是 DER 格式
            input
        }
    }
}
