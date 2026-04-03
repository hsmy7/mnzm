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
 * - Release 构建强制启用，不可绕过
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

        // ──────────────────────────────────────────
        // SPKI SHA-256 哈希值（Base64 编码）
        // ──────────────────────────────────────────
        //
        // 【重要】以下为占位值，部署前必须替换为实际的服务器证书 SPKI 哈希！
        // 获取方式见类注释中的 openssl 命令。
        //
        // 格式：sha256/<Base64编码的SPKI-SHA256>
        //

        /**
         * api.xianxia.com - 主证书 SPKI 哈希
         *
         * TODO: 替换为生产环境真实的主 CA 或叶证书 SPKI hash
         */
        private const val API_PRIMARY_PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        /**
         * api.xianxia.com - 备用证书 SPKI 哈希（用于证书轮换期）
         *
         * 当主证书即将到期、需要切换到新证书时，
         * 将新证书的 SPKI hash 添加到此位置，
         * 两套 hash 并存期间均可通过校验。
         *
         * TODO: 替换为备用证书的真实 SPKI hash
         */
        private const val API_BACKUP_PIN = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="

        /**
         * cdn.xianxia.com - 主证书 SPKI 哈希
         *
         * CDN 通常使用不同的证书（如 Cloudflare/Akamai 托管证书），
         * 需要单独配置其 SPKI hash。
         *
         * TODO: 替换为 CDN 证书的真实 SPKI hash
         */
        private const val CDN_PRIMARY_PIN = "sha256/CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC="

        /**
         * cdn.xianxia.com - 备用证书 SPKI 哈希
         *
         * TODO: 替换为 CDN 备用证书的真实 SPKI hash
         */
        private const val CDN_BACKUP_PIN = "sha256/DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD="
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
     * - **Release 构建**：始终启用，使用完整的 pin 集合
     * - **Debug 构建**：根据 [NetworkSecurityConfig.ENABLE_PINNING_IN_DEBUG] 决定；
     *   若关闭则返回空 pinner（等效于不固定），方便 Charles/Fiddler 抓包
     */
    private fun buildCertificatePinner(): CertificatePinner {
        if (!NetworkSecurityConfig.isCertificatePinningEnabled) {
            Log.w(
                TAG,
                """[安全警告] 证书固定已禁用！
                   |仅在 Debug 开发环境下允许此操作。
                   |Release 构建必须启用证书固定。
                """.trimMargin()
            )
            return CertificatePinner.Builder().build()
        }

        val builder = CertificatePinner.Builder()
        var totalValidPins = 0

        for (host in NetworkSecurityConfig.pinnedHosts) {
            val pins = getPinsForHost(host)
            if (pins.isNotEmpty()) {
                builder.add(host, *pins.toTypedArray())
                totalValidPins += pins.size
                Log.i(TAG, "已为主机 [$host] 配置 ${pins.size} 个证书固定项")
            } else {
                Log.e(TAG, "[安全风险] 主机 [$host] 所有 SPKI 哈希均为占位符，证书固定对该主机无效！请替换为真实值。")
            }
        }

        if (totalValidPins == 0 && NetworkSecurityConfig.pinnedHosts.isNotEmpty()) {
            Log.e(
                TAG,
                """[严重安全警告] 所有主机的证书固定值均为占位符！
                   |当前等效于完全禁用证书固定，存在中间人攻击风险。
                   |请立即使用 extractSpkiHash() 方法提取真实证书哈希并替换常量中的占位值。
                """.trimMargin()
            )
        }

        val pinner = builder.build()
        Log.i(TAG, "CertificatePinner 构建完成，覆盖 ${NetworkSecurityConfig.pinnedHosts.size} 个主机，共 $totalValidPins 个有效 pin")

        return pinner
    }

    /**
     * 根据主机名返回对应的主 + 备用 SPKI pin 列表。
     *
     * @param host 主机名（如 "api.xianxia.com"）
     * @return 该主机所有有效的 SPKI pin 字符串列表
     */
    private fun getPinsForHost(host: String): List<String> {
        return when (host) {
            "api.xianxia.com" -> listOfNotNull(
                API_PRIMARY_PIN.takeIf { isPlaceholderPin(it).not() },
                API_BACKUP_PIN.takeIf { isPlaceholderPin(it).not() }
            )
            "cdn.xianxia.com" -> listOfNotNull(
                CDN_PRIMARY_PIN.takeIf { isPlaceholderPin(it).not() },
                CDN_BACKUP_PIN.takeIf { isPlaceholderPin(it).not() }
            )
            else -> emptyList()
        }
    }

    /**
     * 检测一个 pin 是否仍是占位符（尚未被替换为真实值）。
     *
     * 占位特征：包含连续重复字符模式（如 AAAA..., BBBB...），
     * 这不是合法的 Base64 编码的 SHA-256 输出。
     *
     * @param pin SPKI pin 字符串（格式: sha256/...）
     * @return true 表示该 pin 是占位符，不应投入使用
     */
    private fun isPlaceholderPin(pin: String): Boolean {
        val hashPart = pin.removePrefix("sha256/")
        if (hashPart.length < 16) return true

        // 检测简单重复模式（如全 A, 全 B 等）
        val firstChar = hashPart[0]
        var repeatCount = 0
        for (c in hashPart) {
            if (c == firstChar) repeatCount++
        }
        // 如果超过 80% 的字符相同，判定为占位符
        return repeatCount > hashPart.length * 0.8
    }

    // ──────────────────────────────────────────────
    // 工具方法：从证书文件提取 SPKI hash
    // ──────────────────────────────────────────────

    /**
     * 从 DER/PEM 编码的 X.509 证书中提取 SPKI SHA-256 哈希。
     *
     * 此方法主要用于开发阶段的辅助工具，
     * 从 .cer / .crt 文件中提取正确的 pin 值填入上方常量。
     *
     * @param certBytes 证书原始字节（DER 或 PEM 文本）
     * @return sha256/<Base64> 格式的 pin 字符串
     * @throws IllegalArgumentException 证书解析失败时抛出
     */
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
