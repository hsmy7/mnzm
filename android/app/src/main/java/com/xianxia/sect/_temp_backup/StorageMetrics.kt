package com.xianxia.sect.data.engine

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageMetrics @Inject constructor() {
    private val saveCount = AtomicLong(0)
    private val loadCount = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)

    fun recordSave() {
        saveCount.incrementAndGet()
    }

    fun recordLoad() {
        loadCount.incrementAndGet()
    }

    fun recordCacheHit() {
        cacheHits.incrementAndGet()
    }

    fun recordCacheMiss() {
        cacheMisses.incrementAndGet()
    }

    fun getSaveCount(): Long = saveCount.get()

    fun getLoadCount(): Long = loadCount.get()

    fun getCacheHits(): Long = cacheHits.get()

    fun getCacheMisses(): Long = cacheMisses.get()

    fun getCacheHitRate(): Float {
        val total = cacheHits.get() + cacheMisses.get()
        return if (total > 0) cacheHits.get().toFloat() / total else 0f
    }
}
