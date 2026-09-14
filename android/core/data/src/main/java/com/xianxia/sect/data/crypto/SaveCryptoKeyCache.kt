package com.xianxia.sect.data.crypto

import android.util.Log
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.data.config.StorageConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// SaveCrypto 的派生密钥缓存域:统一缓存表/周期清理/容量驱逐/初始化编排。

private const val TAG = SaveCrypto.TAG

object SaveCryptoKeyCache {



    /**
 * 密钥来源枚举（用于统一缓存的来源追踪）
 */
    internal enum class KeySource {
    /** 启动时预计算（precomputeDerivedKey() 触发�?*/
    PRECOMPUTED,

    /** 实时派生（encrypt/decrypt 时按需计算�?*/
    DERIVED
}


    /**
 * 统一缓存数据结构 (v3)
 *
 * 合并了原 CachedDerivedKey �?PrecomputedKeyEntry 的功能，
 * 通过 source 字段区分密钥来源以便监控和调试�?
 */
    internal data class UnifiedCachedKey(
    val key: ByteArray,
    val createdAt: Long,
    val salt: ByteArray,
    val source: KeySource
    )


    /**
 * 统一密钥派生结果缓存 (v3 - 单一缓存架构)
 *
 * 结构：cacheKey -> UnifiedCachedKey
 * - cacheKey �?password + salt 的哈希构�?
 * - 包含派生密钥、创建时间戳、原始盐值、来源标�?
 * - TTL = 5 分钟（与清理间隔一致）
 *
 * 线程安全保证�?
 * - ConcurrentHashMap 保证单个操作的原子�?
 * - 复合操作（检�?然后-操作）通过函数式方法保证一致�?
 */
    internal val unifiedKeyCache = ConcurrentHashMap<String, UnifiedCachedKey>()


    /**
 * 统一缓存 TTL�?分钟
 *
 * v3 重构变更：从原来�?2 分钟统一�?5 分钟�?
 * �?StorageConfig.DEFAULT_KEY_CACHE_DURATION_MS (300000L) 和清理间隔对齐�?
 *
 * 设计考量�?
 * - 游戏安全性优先，�?5 分钟仍在合理范围
 * - Argon2id/PBKDF2 派生在中端设备上�?100-200ms
 * - 缓存仅在内存中，进程终止即清�?
 * - 统一 TTL 和清理间隔消除时间窗口不一致导致的状态不一�?
 */
    private val UNIFIED_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5)


    /** 最大缓存条目数�?0（数据量有限，减少内存中密钥驻留�?*/
    private const val MAX_CACHE_SIZE = 10


    /** 统一清理间隔�?分钟（与 TTL 一致，确保过期键及时清理） */
    private val CACHE_CLEANUP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)

    private lateinit var scopeProvider: CoroutineScopeProvider

    private val cleanupScope get() = scopeProvider.ioScope

    private var cacheCleanupJob: Job? = null

    private val cleanupInitialized = AtomicBoolean(false)

    /**
 * 初始化 SaveCrypto 的 CoroutineScopeProvider。
 *
 * 由于 SaveCrypto 是 object 单例，无法使用 Hilt 构造器注入，
 * 需在 Application.onCreate() 中调用此方法。
 *
 * @param provider 应用级 CoroutineScope 提供者
 */
    fun initialize(provider: CoroutineScopeProvider) {
    scopeProvider = provider
}

    /**
 * 确保清理器已初始化（懒加载模式）
 *
 * 使用 compareAndSet 保证只执行一次初始化�?
 * 即使多线程并发调用也只会启动一个清理协程�?
 */
    internal fun ensureCleanupInitialized() {
    if (cleanupInitialized.compareAndSet(false, true)) {
        startPeriodicCacheCleanup()
        Log.d(TAG, "Lazy cache cleanup initialized on first access")
    }
}

    /**
 * 获取当前缓存大小（用于监控和调试�?
 */
    fun getDerivedKeyCacheSize(): Int = unifiedKeyCache.size

    /**
 * 清除所有缓存的派生密钥（安全清理敏感数据）
 */
    fun clearDerivedKeyCache() {
    unifiedKeyCache.values.forEach { cached ->
        securelyClear(cached.key)
        securelyClear(cached.salt)
    }
    unifiedKeyCache.clear()
    Log.d(TAG, "Unified key cache cleared (securely wiped)")
}

    /**
 * 启动定期缓存清理协程 (v3: 仅通过懒初始化调用)
 *
 * �?5 分钟检查一次过期条目并清理�?
 * 防止密钥在内存中驻留时间过长（安全优先）�?
 * 使用 SupervisorJob 确保单个清理失败不影响后续调度�?
 *
 * v3 变更：不再从 init 块直接调用，
 * �?ensureCleanupInitialized() 按需触发�?
 */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun startPeriodicCacheCleanup() {
    cacheCleanupJob?.cancel()
    cacheCleanupJob = cleanupScope.launch {
        while (isActive) {
            try {
                delay(CACHE_CLEANUP_INTERVAL_MS)
                val removed = clearExpiredCacheEntries()
                if (removed > 0) {
                    Log.d(TAG, "Periodic cache cleanup: removed $removed expired entries")
                }
            } catch (e: CancellationException) {
                throw e // 取消穿透: 清理协程停止时静默退出, 不误报周期清理错误
            } catch (e: Exception) {
                Log.w(TAG, "Periodic cache cleanup error", e)
            }
        }
    }
    Log.d(TAG,
        "Periodic cache cleanup started (interval=${CACHE_CLEANUP_INTERVAL_MS}ms, ttl=${UNIFIED_CACHE_TTL_MS}ms)")
}

    /**
 * 停止定期缓存清理协程
 *
 * 在应用退出或需要释放资源时调用�?
 */
    fun stopPeriodicCacheCleanup() {
    cacheCleanupJob?.cancel()
    cacheCleanupJob = null
    // 重置懒初始化标志，允许后续重新启�?
    cleanupInitialized.set(false)
    Log.d(TAG, "Periodic cache cleanup stopped (lazy-init flag reset)")
}

    /**
 * 清理过期的缓存条�?(v3: 操作统一缓存)
 *
 * @param ttlMs 自定义TTL（毫秒），默认使用统一�?5 分钟 TTL
 * @return 清除的过期条目数
 */
    fun clearExpiredCacheEntries(ttlMs: Long = UNIFIED_CACHE_TTL_MS): Int {
    val now = System.currentTimeMillis()
    val effectiveTtl = StorageConfig.DEFAULT_KEY_CACHE_DURATION_MS.coerceAtLeast(ttlMs)
    val expiredKeys = unifiedKeyCache.filter {
        now - it.value.createdAt > effectiveTtl
    }.keys.toList()

    expiredKeys.forEach { key ->
        val removed = unifiedKeyCache.remove(key)
        if (removed != null) {
            securelyClear(removed.key)
            securelyClear(removed.salt)
        }
    }

    if (expiredKeys.isNotEmpty()) {
        Log.d(TAG, "Cleared ${expiredKeys.size} expired cache entries (unified cache)")
    }
    return expiredKeys.size
}

    /**
 * 构建缓存键：password + salt �?SHA-256 哈希组合
 *
 * 使用双重哈希确保�?
 * - 密码明文不会出现在缓存键�?
 * - 固定长度便于 HashMap 性能优化
 */
    internal fun buildCacheKey(password: String, salt: ByteArray): String {
    val passwordHash = sha256Hex(password.toByteArray())
    val saltHash = sha256Hex(salt)
    return "${passwordHash}_${saltHash}"
}

    /**
 * 将密钥存入统一缓存 (v3)
 *
 * 自动处理缓存容量限制和淘汰策�?
 */
    internal fun putToUnifiedCache(
    cacheKey: String,
    key: ByteArray,
    salt: ByteArray,
    source: KeySource
    ) {
    // 容量检查和淘汰
    if (unifiedKeyCache.size >= MAX_CACHE_SIZE) {
        clearExpiredCacheEntries()
        if (unifiedKeyCache.size >= MAX_CACHE_SIZE) {
            aggressiveCacheEviction(MAX_CACHE_SIZE / 2)
        }
    }

    // 存储副本，防止外部修改影响缓�?
    unifiedKeyCache[cacheKey] = UnifiedCachedKey(
        key = key.copyOf(),
        createdAt = System.currentTimeMillis(),
        salt = salt.copyOf(),
        source = source
    )
}

    /**
 * 激进缓存淘汰策�?(v3: 操作统一缓存)
 *
 * 当缓存满且无过期条目时，移除最早创建的条目
 * 被移除的条目会安全擦除其中的敏感数据
 */
    internal fun aggressiveCacheEviction(targetSize: Int) {
    val entriesToRemove = unifiedKeyCache.size - targetSize
    if (entriesToRemove <= 0) return

    val sortedEntries = unifiedKeyCache.entries
        .sortedBy { it.value.createdAt }

    var removedCount = 0
    for (i in 0 until minOf(entriesToRemove, sortedEntries.size)) {
        val entry = sortedEntries[i]
        // 安全清除敏感数据
        securelyClear(entry.value.key)
        securelyClear(entry.value.salt)
        unifiedKeyCache.remove(entry.key)
        removedCount++
    }

    Log.d(TAG, "Aggressive cache eviction: securely removed $removedCount entries from unified cache")
}

    /**
 * 清除所有密钥缓存 (v3: 统一缓存清理)
 */
    fun clearAllKeyCache() {
    // 停止定期清理协程
    stopPeriodicCacheCleanup()

    val snapshot = unifiedKeyCache.entries.toList()
    unifiedKeyCache.clear()
    snapshot.forEach { (_, entry) ->
        securelyClear(entry.key)
        securelyClear(entry.salt)
    }
    Log.d(TAG, "All unified key caches securely cleared (including periodic cleanup stopped)")
}
}
