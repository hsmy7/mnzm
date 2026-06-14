package com.xianxia.sect.core.config

import android.util.Log
import com.xianxia.sect.core.util.HttpClientProvider
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 HttpClientProvider 的远程配置实现。
 *
 * 复用 app 层 SecureHttpClient（OkHttp + HMAC 签名 + 证书钉选 + AES-GCM 解密）基础设施，
 * 通过 HttpClientProvider.get(url) 拉取远程 JSON 配置。
 *
 * 安全设计：
 * - 10 秒网络超时，避免阻塞启动
 * - 仅接受非空且长度合理的响应（防御截断/空响应）
 * - 不在此层做 JSON 解析；解析交由 ConfigLoader 统一处理（含 ignoreUnknownKeys 兜底）
 *
 * 绑定：默认未在 DI 中绑定。启用远程配置热更新时，在 CoreModule 添加：
 *   @Binds @Singleton
 *   fun bindRemoteConfigProvider(impl: HttpRemoteConfigProvider): RemoteConfigProvider
 */
@Singleton
class HttpRemoteConfigProvider @Inject constructor(
    private val httpClient: HttpClientProvider
) : RemoteConfigProvider {

    private companion object {
        const val TAG = "HttpRemoteConfig"
        const val FETCH_TIMEOUT_MS = 10_000L
        const val MIN_VALID_RESPONSE_LENGTH = 16
    }

    // 首次成功拉取后置 true；进程生命周期内不再重试失败（避免高频无效请求）
    @Volatile
    private var everSucceeded = false

    override val isAvailable: Boolean
        get() = everSucceeded

    override suspend fun fetchRemoteConfig(url: String): String? {
        return try {
            val raw = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                httpClient.get(url)
            }
            if (raw == null) {
                Log.w(TAG, "Remote config fetch timed out (${FETCH_TIMEOUT_MS}ms): $url")
                return null
            }
            if (raw.length < MIN_VALID_RESPONSE_LENGTH) {
                Log.w(TAG, "Remote config response too short (${raw.length} bytes), likely truncated: $url")
                return null
            }
            everSucceeded = true
            Log.d(TAG, "Remote config fetched successfully (${raw.length} bytes)")
            raw
        } catch (e: Exception) {
            Log.w(TAG, "Remote config fetch failed: ${e.message}")
            null
        }
    }
}
