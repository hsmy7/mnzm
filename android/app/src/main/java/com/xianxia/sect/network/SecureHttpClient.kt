package com.xianxia.sect.network

import android.util.Log
import com.xianxia.sect.BuildConfig
import com.xianxia.sect.data.crypto.SecureKeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 安全 HTTP 客户端封装
 *
 * 基于 OkHttp 4.12 构建的安全网络层，集成以下安全能力：
 *
 * 1. **证书固定** (Certificate Pinning)
 *    - 通过 [CertificatePinnerProvider] 注入 SPKI SHA-256 固定规则
 *    - Debug 构建可按需绕过
 *
 * 2. **TLS 硬化**
 *    - 强制最低 TLS 1.2，优先 TLS 1.3
 *    - 密码套件白名单（仅 AEAD 套件）
 *
 * 3. **请求自动签名** (via [RequestSigner])
 *    - 每个请求自动注入 HMAC-SHA256 签名头
 *    - 时间戳 + Nonce 防重放
 *    - 设备指纹绑定
 *
 * 4. **响应解密** (AES-256-GCM)
 *    - 服务端返回的加密响应体可自动解密
 *    - 使用 [SecureKeyManager] 的密钥进行对称解密
 *    - IV 从响应头 `X-Response-IV` 获取（Base64 编码）
 *
 * ## 使用方式
 *
 * ```kotlin
 * @Inject lateinit var secureClient: SecureHttpClient
 *
 * // 获取已配置好的 OkHttpClient 实例（用于 Retrofit 等）
 * val okHttpClient = secureClient.client
 *
 * // 或直接执行请求
 * val response = secureClient.execute(request)
 * ```
 */
@Singleton
class SecureHttpClient @Inject constructor(
    private val requestSigner: RequestSigner,
    private val certificatePinnerProvider: CertificatePinnerProvider,
    @ApplicationContext private val context: android.content.Context
) {

    companion object {
        private const val TAG = "SecureHttpClient"

        /** 响应加密标识头：存在此头表示响应体已加密 */
        const val HEADER_RESPONSE_ENCRYPTED = "X-Response-Encrypted"

        /** 响应 IV 头：服务端在加密响应时附带的 GCM IV */
        const val HEADER_RESPONSE_IV = "X-Response-IV"
    }

    /**
     * 已完全配置好的 OkHttpClient 单例。
     *
     * 此客户端：
     * - 配置了连接/读取/写入超时
     * - 启用了证书固定（Release 构建）
     * - 限制了 TLS 版本和密码套件
     * - 注册了签名拦截器（自动注入安全头）
     * - 注册了响应解密拦截器
     * - Debug 构建下可选 HTTP 日志拦截器
     */
    val client: OkHttpClient by lazy { buildSecureClient() }

    // ──────────────────────────────────────────────
    // 公开 API
    // ──────────────────────────────────────────────

    /**
     * 使用内部安全客户端执行一个 [Request]，返回 [Response]。
     *
     * 请求会经过完整的签名和加密管道。
     *
     * @param request 已构建好的 OkHttp Request（建议使用 [newSignedRequestBuilder] 创建）
     * @return 包含可能已解密的响应体的 Response 对象
     * @throws IOException 网络错误或 SSL 校验失败时抛出
     */
    @Throws(IOException::class)
    fun execute(request: Request): Response {
        return client.newCall(request).execute()
    }

    /**
     * 创建一个新的 [Request.Builder]，预设通用安全头。
     *
     * 通过此 builder 构建的请求会自动经过签名拦截器的完整处理。
     *
     * @param url 请求 URL
     * @return 已设置基础头的 Request.Builder
     */
    fun newRequestBuilder(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("X-App-Version", getAppVersion())
            .header("X-Platform", "android")
            .header("X-SDK-Level", android.os.Build.VERSION.SDK_INT.toString())
    }

    /**
     * 异步执行请求。
     *
     * @param request 要执行的请求
     * @param callback 回调接口
     */
    fun enqueue(request: Request, callback: okhttp3.Callback) {
        client.newCall(request).enqueue(callback)
    }

    // ──────────────────────────────────────────────
    // 内部构建逻辑
    // ──────────────────────────────────────────────

    /**
     * 构建并配置完整的 OkHttpClient。
     *
     * 拦截器执行顺序（OkHttp 按添加顺序依次调用）：
     * 1. **签名拦截器** - 注入 X-Signature / X-Timestamp / X-Nonce 等头
     * 2. **解密拦截器** - 检测并解密加密的响应体
     * 3. **日志拦截器** (仅 Debug) - 打印请求/响应明细
     */
    private fun buildSecureClient(): OkHttpClient {
        Log.i(TAG, "正在初始化安全 HTTP 客户端...")

        // 先校验全局安全配置
        NetworkSecurityConfig.validate()

        val builder = OkHttpClient.Builder()

        // ── 超时配置 ──
        builder.connectTimeout(
            NetworkSecurityConfig.connectTimeoutSeconds,
            TimeUnit.SECONDS
        )
        builder.readTimeout(
            NetworkSecurityConfig.readTimeoutSeconds,
            TimeUnit.SECONDS
        )
        builder.writeTimeout(
            NetworkSecurityConfig.writeTimeoutSeconds,
            TimeUnit.SECONDS
        )

        // ── TLS 硬化：ConnectionSpec ──
        builder.connectionSpecs(listOf(buildSecureConnectionSpec()))

        // ── 证书固定（始终配置，Release 构建强制启用）──
        //
        // 设计原则：
        // 1. 始终设置 certificatePinner，即使是在 Debug 模式
        // 2. Release 构建：使用完整的 SPKI 固定规则，失败时降级+告警
        // 3. Debug 构建：可通过 NetworkSecurityConfig 控制是否启用固定
        //
        // Fallback 策略（当 pinning 失败时）：
        // - 不直接抛出 SSL 异常导致崩溃
        // - 记录详细的安全告警日志
        // - 允许请求继续执行（降级模式）
        // - 在响应头中添加安全警告标记供上层判断

        val certificatePinner = certificatePinnerProvider.get()
        builder.certificatePinner(certificatePinner)

        if (certificatePinnerProvider.isPinningActive()) {
            Log.i(TAG, "✓ 证书固定已启用（严格模式）")
            Log.i(TAG, "  覆盖主机: ${NetworkSecurityConfig.pinnedHosts.joinToString(", ")}")

            // 添加证书固定失败时的拦截器（用于降级处理）
            builder.addInterceptor(CertificatePinningFallbackInterceptor())
        } else {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "⚠ 证书固定未启用（Debug 模式）")
                Log.w(TAG, "  生产环境必须启用证书固定以确保通信安全")
            } else {
                // Release 构建但 pinning 未启用，记录严重警告
                Log.e(TAG, "🚨 [严重安全警告] Release 构建未启用证书固定！")
                Log.e(TAG, "  这会使应用容易受到中间人攻击（MITM）")
                Log.e(TAG, "  请立即检查 NetworkSecurityConfig 配置")

                // 即使未启用固定，也添加降级拦截器以备将来启用
                builder.addInterceptor(CertificatePinningFallbackInterceptor())
            }
        }

        // ── 拦截器链 ──

        // 1. 请求签名拦截器
        builder.addInterceptor(SigningInterceptor(requestSigner))

        // 2. 响应解密拦截器
        builder.addInterceptor(ResponseDecryptionInterceptor(context))

        // 3. 日志拦截器（仅 Debug）
        if (NetworkSecurityConfig.isVerboseLoggingEnabled) {
            builder.addInterceptor(
                okhttp3.logging.HttpLoggingInterceptor()
                    .apply {
                        level = okhttp3.logging.HttpLoggingInterceptor.Level.BODY
                    }
            )
            Log.i(TAG, "HTTP 详细日志已启用")
        }

        // ── 重试配置 ──
        builder.retryOnConnectionFailure(true)

        val client = builder.build()
        Log.i(TAG, "安全 HTTP 客户端初始化完成")

        return client
    }

    /**
     * 构建安全的 ConnectionSpec，限制 TLS 版本和密码套件。
     *
     * 策略：
     * - 仅允许 TLS 1.2 和 TLS 1.3
     * - 密码套件仅允许 AEAD 类型（GCM / ChaCha20-Poly1305）
     * - 不做 fallback 到不安全连接
     */
    private fun buildSecureConnectionSpec(): ConnectionSpec {
        return ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(*NetworkSecurityConfig.enabledTlsVersions)
            .cipherSuites(*NetworkSecurityConfig.allowedCipherSuites)
            .build()
    }

    /**
     * 获取应用版本字符串。
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName, 0
            )
            @Suppress("DEPRECATION")
            "${packageInfo.versionName}(${packageInfo.versionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }

    // ══════════════════════════════════════════════
    // 内部拦截器实现
    // ══════════════════════════════════════════════

    /**
     * 请求签名拦截器
     *
     * 在每个请求发出前自动注入安全相关头。
     * 作为 Application Interceptor 运行（不处理重定向/认证等中间响应）。
     */
    private class SigningInterceptor(
        private val signer: RequestSigner
    ) : Interceptor {

        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()

            // 跳过非目标域名的请求（避免对外部 API 签名）
            if (!shouldSign(original.url)) {
                return chain.proceed(original)
            }

            // 读取原始请求体字节（body 只能消费一次）
            val bodyBytes = original.body?.let { body ->
                try {
                    val buffer = okio.Buffer()
                    body.writeTo(buffer)
                    buffer.readByteArray()
                } catch (e: Exception) {
                    Log.w(TAG, "无法读取请求体用于签名", e)
                    null
                }
            }

            // 使用签名器注入所有安全头
            val signedRequest = signer.signRequest(
                request = original.newBuilder(),
                httpUrl = original.url,
                method = original.method,
                body = bodyBytes
            ).build()

            // 如果读取了 body 字节，需要重新构造 RequestBody（因为原 body 已被消费）
            val finalRequest = if (bodyBytes != null && signedRequest.body == null && original.body != null) {
                signedRequest.newBuilder()
                    .method(original.method, original.body)
                    .build()
            } else {
                signedRequest
            }

            return chain.proceed(finalRequest)
        }

        /**
         * 判断是否需要对指定 URL 的请求进行签名。
         *
         * 目前策略：只对 pinnedHosts 列表中的域名签名，
         * 第三方 API（如 TapTap SDK）不签名以免干扰其认证机制。
         */
        private fun shouldSign(url: okhttp3.HttpUrl): Boolean {
            return NetworkSecurityConfig.pinnedHosts.any { host ->
                url.host == host || url.host.endsWith(".$host")
            }
        }
    }

    /**
     * 响应解密拦截器
     *
     * 在收到响应后检查是否为加密响应，
     * 若是则使用 AES-256-GCM 自动解密响应体。
     *
     * 服务端约定：
     * - 加密响应需携带头 `X-Response-Encrypted: true`
     * - 加密响应需携带头 `X-Response-IV: <Base64编码的12字节IV>`
     * - 密文格式：IV(12字节) + Ciphertext+Tag（GCM 标准输出）
     * - 使用与 SecureKeyManager 相同的主密钥派生出的响应解密子密钥
     */
    private class ResponseDecryptionInterceptor(
        private val context: android.content.Context
    ) : Interceptor {

        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())

            // 检查是否需要解密
            val isEncrypted = response.header(HEADER_RESPONSE_ENCRYPTED)?.toBoolean() == true

            if (!isEncrypted || !NetworkSecurityConfig.RESPONSE_DECRYPTION_ENABLED) {
                return response
            }

            val ivHeader = response.header(HEADER_RESPONSE_IV)
            if (ivHeader.isNullOrBlank()) {
                Log.e(TAG, "响应标记为加密但缺少 $HEADER_RESPONSE_IV 头")
                return response
            }

            try {
                val ciphertext = response.body?.bytes()
                if (ciphertext == null || ciphertext.isEmpty()) {
                    Log.w(TAG, "加密响应体为空")
                    return response
                }

                val decrypted = decryptResponse(ciphertext, ivHeader)

                // 构造新的响应体（替换为明文内容）
                val newResponseBody = decrypted.toResponseBody(response.body?.contentType())

                return response.newBuilder()
                    .body(newResponseBody)
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "响应解密失败", e)
                // 解密失败时抛出异常，防止原始密文被上层误用为明文
                throw java.io.IOException("响应解密失败: ${e.message}", e)
            }
        }

        /**
         * 使用 AES-256-GCM 解密响应数据。
         *
         * 密钥派生（增强版）：使用多次迭代哈希 + 盐值的方案
         * - 拼接主密钥和应用特定盐值
         * - 进行 1000 次 SHA-256 迭代哈希（类似 PBKDF2 轻量版）
         * - 清理所有中间敏感数据
         *
         * 与请求签名密钥分离，遵循密钥分离原则。
         */
        private fun decryptResponse(ciphertext: ByteArray, ivBase64: String): ByteArray {
            val iv = android.util.Base64.decode(ivBase64, android.util.Base64.NO_WRAP)
            if (iv.size != NetworkSecurityConfig.RESPONSE_IV_LENGTH) {
                throw IllegalArgumentException(
                    "响应 IV 长度异常: 期望 ${NetworkSecurityConfig.RESPONSE_IV_LENGTH} 字节, 实际 ${iv.size}"
                )
            }

            // 派生响应专用解密密钥（增强版：多次迭代哈希 + 盐值）
            val masterKey = SecureKeyManager.getOrCreateKey(context)

            // 使用应用特定盐值增加熵（与签名密钥使用不同的盐值）
            val salt = "xianxia-decrypt-v2-salt-2024".toByteArray(StandardCharsets.UTF_8)

            // 初始化派生值：masterKey || salt
            var derived = ByteArray(masterKey.size + salt.size)
            System.arraycopy(masterKey, 0, derived, 0, masterKey.size)
            System.arraycopy(salt, 0, derived, masterKey.size, salt.size)

            // 多次迭代哈希（1000次迭代，与 RequestSigner 保持一致的安全强度）
            val md = java.security.MessageDigest.getInstance("SHA-256")
            for (i in 0..999) {
                derived = md.digest(derived)
            }
            val decryptionKey = derived

            // 清理中间敏感值（防止内存转储攻击）
            masterKey.fill(0)
            salt.fill(0)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(decryptionKey, "AES")
            val gcmSpec = GCMParameterSpec(
                NetworkSecurityConfig.RESPONSE_GCM_TAG_LENGTH,
                iv
            )

            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val plaintext = cipher.doFinal(ciphertext)

            // 清理密钥副本
            decryptionKey.fill(0)

            if (NetworkSecurityConfig.isVerboseLoggingEnabled) {
                Log.v(TAG, "响应解密成功，明文大小: ${plaintext.size} 字节")
            }

            return plaintext
        }
    }

    /**
     * 证书固定失败降级拦截器
     *
     * 当证书固定验证失败时（如服务器更换了证书但客户端 pin 未更新），
     * 此拦截器会捕获 SSL 异常并执行相应策略。
     *
     * ## 安全策略（P0-3 修复后）
     *
     * | 构建类型 | 行为 |
     * |---------|------|
     * | **Release** | **直接抛出 SSLException**，禁止任何降级。防止 MITM 攻击。 |
     * | **Debug**   | 允许降级：记录警告日志 + 标记响应 + 继续请求（方便开发调试） |
     *
     * ## 降级策略（仅 Debug 构建）
     *
     * 1. **记录详细日志**：记录失败的 URL、异常类型、证书信息等
     * 2. **安全告警**：通过 Log.w 输出安全警告
     * 3. **响应标记**：在响应头中添加 `X-Security-Warning` 标记
     * 4. **允许继续**：不抛出异常，让请求继续执行（降级模式）
     *
     * ## 使用场景（仅 Debug）
     *
     * - 服务器证书轮换期间（新旧证书并存）
     * - 开发/测试环境调试
     *
     * ## ⚠️ 安全提示
     *
     * Release 构建中不存在"降级模式"。证书固定失败 = 请求终止。
     * 这是 OWASP 推荐的防御性编程实践。
     */
    private class CertificatePinningFallbackInterceptor : Interceptor {

        companion object {
            private const val TAG = "CertPinningFallback"

            /** 响应头标记：表示该请求经过了降级处理 */
            const val HEADER_SECURITY_WARNING = "X-Security-Warning"

            /** 降级原因值 */
            const val WARNING_CERT_PINNING_FAILED = "cert_pinning_failed"
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            return try {
                // 尝试正常执行请求（会经过 OkHttp 内置的 certificatePinner 校验）
                chain.proceed(chain.request())
            } catch (e: javax.net.ssl.SSLPeerUnverifiedException) {
                // 证书固定验证失败
                handleCertificatePinningFailure(chain, e)
            } catch (e: javax.net.ssl.SSLException) {
                // 其他 SSL 异常（可能是协议版本、密码套件等问题）
                if (e.message?.contains("Certificate pinning failure") == true) {
                    handleCertificatePinningFailure(chain, e)
                } else {
                    // 非 pinning 相关的 SSL 异常，重新抛出
                    throw e
                }
            }
        }

        /**
         * 处理证书固定验证失败的情况
         *
         * ## 安全策略（P0-3 修复）
         *
         * - **Release 构建**：直接抛出原始异常，禁止任何形式的降级。
         *   这是防止中间人攻击（MITM）的关键防线——证书固定失败意味着
         *   通信可能被劫持，继续请求会泄露用户敏感数据。
         *
         * - **Debug 构建**：允许降级以方便开发调试，但输出醒目的安全警告日志。
         *
         * @param chain 拦截器链
         * @param originalException 原始的 SSL 异常
         * @return 带有安全警告标记的响应（仅 Debug 构建返回；Release 构建抛异常）
         * @throws Exception Release 构建下始终重新抛出原始异常
         */
        private fun handleCertificatePinningFailure(
            chain: Interceptor.Chain,
            originalException: Exception
        ): Response {
            // ══════════════════════════════════
            // 安全门控：Release 构建禁止降级
            // ══════════════════════════════════
            if (!BuildConfig.DEBUG) {
                Log.e(TAG, "╔══════════════════════════════════════════════════════╗")
                Log.e(TAG, "║  🚨 [Release] 证书固定验证失败 — 已阻止降级          ║")
                Log.e(TAG, "╠══════════════════════════════════════════════════════╣")
                Log.e(TAG, "║  URL: ${chain.request().url}".padEnd(55) + "║")
                Log.e(TAG, "║  Host: ${chain.request().url.host}".padEnd(55) + "║")
                Log.e(TAG, "║  Exception: ${originalException.javaClass.simpleName}".padEnd(55) + "║")
                Log.e(TAG, "║  Message: ${originalException.message}".padEnd(55) + "║")
                Log.e(TAG, "╠══════════════════════════════════════════════════════╣")
                Log.e(TAG, "║  Release 环境不允许证书固定降级                   ║")
                Log.e(TAG, "║  请求已被终止以防止潜在 MITM 攻击               ║")
                Log.e(TAG, "║  请立即检查服务端证书并更新 SPKI pins             ║")
                Log.e(TAG, "╚══════════════════════════════════════════════════════╝")
                throw originalException
            }

            val request = chain.request()
            val url = request.url

            // ══════════════════════════════════
            // 1. 记录详细的安全告警日志（仅 Debug）
            // ══════════════════════════════════

            Log.w(TAG, "╔══════════════════════════════════════════════════════╗")
            Log.w(TAG, "║  ⚠️ [Debug] 证书固定验证失败 — 已启用降级模式        ║")
            Log.w(TAG, "╠══════════════════════════════════════════════════════╣")
            Log.w(TAG, "║  URL: ${url}".padEnd(55) + "║")
            Log.w(TAG, "║  Host: ${url.host}".padEnd(55) + "║")
            Log.w(TAG, "║  Method: ${request.method}".padEnd(55) + "║")
            Log.w(TAG, "║  Exception: ${originalException.javaClass.simpleName}".padEnd(55) + "║")
            Log.w(TAG, "║  Message: ${originalException.message}".padEnd(55) + "║")
            Log.w(TAG, "╠══════════════════════════════════════════════════════╣")
            Log.w(TAG, "║  ⚠️ 当前请求以降级模式完成（未经验证）             ║")
            Log.w(TAG, "║  此行为仅在 Debug 构建中允许                     ║")
            Log.w(TAG, "║  生产环境将直接拒绝此类请求                       ║")
            Log.w(TAG, "╚══════════════════════════════════════════════════════╝")

            // ══════════════════════════════════
            // 2. 创建不带证书固定的临时客户端执行请求（仅 Debug）
            // ══════════════════════════════════

            // 注意：以下降级逻辑仅在 Debug 构建中执行。
            // Release 构建已在前面的安全门控中被拦截并抛出异常。
            // 此临时客户端不设置 certificatePinner，等效于禁用固定。

            val fallbackClient = OkHttpClient.Builder()
                .connectTimeout(NetworkSecurityConfig.connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(NetworkSecurityConfig.readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(NetworkSecurityConfig.writeTimeoutSeconds, TimeUnit.SECONDS)
                // 不设置 certificatePinner，等效于禁用固定
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
                .build()

            val response = try {
                fallbackClient.newCall(request).execute()
            } catch (fallbackException: Exception) {
                // 即使降级也失败了，记录并抛出原始异常
                Log.e(TAG, "降级模式也执行失败: ${fallbackException.message}", fallbackException)
                throw originalException
            }

            // ══════════════════════════════════
            // 3. 在响应中添加安全警告标记
            // ══════════════════════════════════

            return response.newBuilder()
                .header(HEADER_SECURITY_WARNING, WARNING_CERT_PINNING_FAILED)
                .header("X-Cert-Pinning-Error", originalException.message ?: "Unknown error")
                .build()
        }
    }
}
