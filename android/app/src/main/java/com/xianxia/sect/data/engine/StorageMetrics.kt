package com.xianxia.sect.data.engine

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageMetrics @Inject constructor() {
    companion object {
        private const val TAG = "StorageMetrics"
    }

    private val saveCount = AtomicLong(0)
    private val loadCount = AtomicLong(0)
    private val cacheHitCount = AtomicLong(0)
    private val cacheMissCount = AtomicLong(0)

    fun recordSave() {
        saveCount.incrementAndGet()
    }

    fun recordLoad() {
        loadCount.incrementAndGet()
    }

    fun recordCacheHit() {
        cacheHitCount.incrementAndGet()
    }

    fun recordCacheMiss() {
        cacheMissCount.incrementAndGet()
    }
}
