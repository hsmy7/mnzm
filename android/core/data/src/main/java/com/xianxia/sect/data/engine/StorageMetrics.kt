package com.xianxia.sect.data.engine

import com.xianxia.sect.core.util.PersistenceTelemetryPort
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** 指标快照：诊断面与周期上报的一次性原子读数 */
data class StorageMetricsSnapshot(
    val saveCount: Long,
    val loadCount: Long,
    val cacheHitCount: Long,
    val cacheMissCount: Long,
    val backupSuccessCount: Long,
    val backupFailureCount: Long,
    val backupRestoreCount: Long,
    val backupSkippedOversizeCount: Long,
    val jadeLedgerDriftCount: Long,
    val lastJadeLedgerDriftOp: String?,
    /** 增量路径落盘次数（SS5 路径分布） */
    val incrementalSaveCount: Long,
    /** 全量路径落盘次数（SS5 路径分布，含首保基线建立） */
    val fullSaveCount: Long,
    /** 脏集越界/溢出回退全量次数（异常回退，独立于首保类全量） */
    val dirtyFallbackCount: Long,
    /** 最近一次全量路径的原因名（[FullSaveReason]） */
    val lastFullSaveReason: String?
)

@Singleton
class StorageMetrics @Inject constructor() : PersistenceTelemetryPort {

    private val saveCount = AtomicLong(0)
    private val loadCount = AtomicLong(0)
    private val cacheHitCount = AtomicLong(0)
    private val cacheMissCount = AtomicLong(0)
    private val backupSuccessCount = AtomicLong(0)
    private val backupFailureCount = AtomicLong(0)
    private val backupRestoreCount = AtomicLong(0)

    /** 备份因超限被跳过次数 */
    private val backupSkippedOversizeCount = AtomicLong(0)

    /** 账本↔派生缓存不一致次数（C++ 每次以账本为准重锚计一次） */
    private val jadeLedgerDriftCount = AtomicLong(0)

    /** 最近一次不一致的玉符事务操作名（诊断归因） */
    private val lastJadeLedgerDriftOp = AtomicReference<String?>(null)

    /** 增量路径落盘次数（SS5 路径分布） */
    private val incrementalSaveCount = AtomicLong(0)

    /** 全量路径落盘次数（SS5 路径分布，含首保基线建立） */
    private val fullSaveCount = AtomicLong(0)

    /** 脏集越界/溢出回退全量次数（异常回退，独立于首保类全量） */
    private val dirtyFallbackCount = AtomicLong(0)

    /** 最近一次全量路径的原因名（诊断归因） */
    private val lastFullSaveReason = AtomicReference<String?>(null)

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

    /** 记录一次增量路径落盘决策（SS5 路径分布） */
    fun recordIncrementalSave() {
        incrementalSaveCount.incrementAndGet()
    }

    /** 记录一次全量路径落盘决策（SS5 路径分布） */
    fun recordFullSave() {
        fullSaveCount.incrementAndGet()
    }

    /** 记录一次脏集越界/溢出回退全量（SS5 异常回退计数） */
    fun recordDirtyFallback() {
        dirtyFallbackCount.incrementAndGet()
    }

    /** 记录最近一次全量路径原因名（诊断归因） */
    fun setLastFullSaveReason(reason: String?) {
        lastFullSaveReason.set(reason)
    }

    /**
     * 全量读面快照（各计数器独立读取的非严格一致读数）——诊断与周期上报的
     * 统一 getter；SS5/SS7 的新计数器在各自批追加字段。
     */
    fun snapshot(): StorageMetricsSnapshot = StorageMetricsSnapshot(
        saveCount = saveCount.get(),
        loadCount = loadCount.get(),
        cacheHitCount = cacheHitCount.get(),
        cacheMissCount = cacheMissCount.get(),
        backupSuccessCount = backupSuccessCount.get(),
        backupFailureCount = backupFailureCount.get(),
        backupRestoreCount = backupRestoreCount.get(),
        backupSkippedOversizeCount = backupSkippedOversizeCount.get(),
        jadeLedgerDriftCount = jadeLedgerDriftCount.get(),
        lastJadeLedgerDriftOp = lastJadeLedgerDriftOp.get(),
        incrementalSaveCount = incrementalSaveCount.get(),
        fullSaveCount = fullSaveCount.get(),
        dirtyFallbackCount = dirtyFallbackCount.get(),
        lastFullSaveReason = lastFullSaveReason.get()
    )

    override fun recordJadeLedgerDrift(op: String) {
        lastJadeLedgerDriftOp.set(op)
        jadeLedgerDriftCount.incrementAndGet()
    }
}
