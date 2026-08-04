package com.xianxia.sect.data.engine

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
    private val backupSuccessCount = AtomicLong(0)
    private val backupFailureCount = AtomicLong(0)
    private val backupRestoreCount = AtomicLong(0)

    /** 备份因超限被跳过次数（T9 2026-08-05） */
    private val backupSkippedOversizeCount = AtomicLong(0)

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

    fun recordBackupSuccess() {
        backupSuccessCount.incrementAndGet()
    }

    fun recordBackupFailure() {
        backupFailureCount.incrementAndGet()
    }

    /** 记录备份因超限被跳过（T9：主保存成功但备份未写入，不谎报成功） */
    fun recordBackupSkippedOversize() {
        backupSkippedOversizeCount.incrementAndGet()
    }

    fun recordBackupRestore() {
        backupRestoreCount.incrementAndGet()
    }
}
