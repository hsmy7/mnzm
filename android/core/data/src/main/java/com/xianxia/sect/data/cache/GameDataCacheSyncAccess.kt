package com.xianxia.sect.data.cache


// GameDataCacheManager 的同步内存访问器域(getSync 族)——与挂起核心 API(get/getOrNull)
// 分文件承载。模块内消费(CacheLayer 门面/挂起 API 路径),故 internal。
// 状态字段经类内 internal 可见性跨文件访问(同模块,行为零变更)。

internal fun <T : Any> GameDataCacheManager.getSync(
    key: String
): T? = synchronized(memoryCache) {
    val entry = memoryCache[key] ?: return null
    if (entry.isExpired) {
        memoryCache.remove(key)
        return null
    }
    @Suppress("UNCHECKED_CAST")
    entry.data as? T
}

internal fun GameDataCacheManager.putSync(
    key: String,
    value: Any,
    ttl: Long = CacheKey.DEFAULT_TTL
) = synchronized(memoryCache) {
    memoryCache[key] = CacheEntry(value, System.currentTimeMillis(), ttl)
    bloomFilter.put(key)
    while (memoryCache.size > currentConfig.maxEntryCount) {
        val eldest = memoryCache.keys.firstOrNull() ?: break
        memoryCache.remove(eldest)
        evictedCount.incrementAndGet()
    }
}

internal fun GameDataCacheManager.removeSync(key: String) = synchronized(memoryCache) {
    memoryCache.remove(key)
}

internal fun GameDataCacheManager.containsSync(key: String): Boolean = synchronized(memoryCache) {
    val entry = memoryCache[key] ?: return false
    if (entry.isExpired) {
        memoryCache.remove(key)
        return false
    }
    return true
}

internal fun GameDataCacheManager.clearSync() = synchronized(memoryCache) {
    memoryCache.clear()
    bloomFilter.clear()
}

internal fun GameDataCacheManager.sizeSync(): Int = synchronized(memoryCache) { memoryCache.size }
