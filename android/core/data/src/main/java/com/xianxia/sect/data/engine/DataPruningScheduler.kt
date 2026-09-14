package com.xianxia.sect.data.engine

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class PruningConfig(
    val checkIntervalMs: Long = 300_000L,
    val maxBattleLogs: Int = StorageConstants.DEFAULT_MAX_BATTLE_LOGS,
    val battleLogRetentionMs: Long = 7 * 24 * 60 * 60 * 1000L,
    // 审计 P3-10：从 StorageConstants 派生全合法槽（0=云存档槽 .. 上限），
    // 替代硬编码 1..5——slot 0/6 的无主行从此受治理（验证点 15）
    val slotIds: List<Int> = (0..StorageConstants.DEFAULT_MAX_SLOTS).toList(),
    val enableAutoPruning: Boolean = true
)

data class PruningResult(
    val battleLogsDeleted: Int,
    val walSizeBeforeBytes: Long,
    val walSizeAfterBytes: Long,
    val dbSizeBeforeBytes: Long,
    val dbSizeAfterBytes: Long,
    val elapsedMs: Long
)

data class PruningStats(
    val totalPruningRuns: Long,
    val totalBattleLogsDeleted: Long,
    val lastPruningTime: Long,
    val lastPruningResult: PruningResult?,
    val isRunning: Boolean
)

@Singleton
class DataPruningScheduler @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val database: GameDatabase,
    private val circuitBreaker: StorageCircuitBreaker,
    private val core: StorageCoreFacade,
    private val scopeProvider: CoroutineScopeProvider
) {
    private val config = PruningConfig()
    private var pruningJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private val scope get() = scopeProvider.ioScope

    private val totalPruningRuns = AtomicLong(0)

    /** change_log 保留窗口（审计 P2-14：7 天排障窗口） */
    private val changeLogRetentionMs = 7 * 24 * 60 * 60 * 1000L

    /** legacy snapshots/ 一次性删除标志（审计 P3-9） */
    private val legacySnapshotsDeleted = AtomicBoolean(false)

    /** 审计 P3-9：一次性删除历史版本遗留的 filesDir/snapshots/ 孤儿目录 */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private fun deleteLegacySnapshotsOnce() {
        if (!legacySnapshotsDeleted.compareAndSet(false, true)) return
        try {
            val legacy = java.io.File(appContext.filesDir, StorageConstants.SNAPSHOT_DIR_NAME)
            if (legacy.exists()) {
                val deleted = legacy.deleteRecursively()
                Log.i(TAG, "Legacy snapshots dir deleted (success=$deleted)")
            }
        } catch (e: Exception) {
            Log.d(TAG, "legacy snapshots cleanup skipped: ${e.message}")
        }
    }
    private val totalBattleLogsDeleted = AtomicLong(0)
    private var lastPruningTime = 0L
    private var lastPruningResult: PruningResult? = null

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun start() {
        if (!config.enableAutoPruning) {
            Log.i(TAG, "Auto pruning is disabled by config")
            return
        }

        if (pruningJob?.isActive == true) {
            Log.w(TAG, "Pruning scheduler already running")
            return
        }

        pruningJob = scope.launch {
            Log.i(TAG, "Data pruning scheduler started (interval=${config.checkIntervalMs}ms)")
            delay(config.checkIntervalMs)

            while (isActive) {
                try {
                    if (circuitBreaker.allowRequest("pruning")) {
                        performPruning()
                    } else {
                        Log.w(TAG, "Pruning skipped: circuit breaker is OPEN")
                    }
                } catch (e: CancellationException) {
                    throw e // 取消穿透: 调度器 stop() 后立即退出轮询, 取消不计熔断失败
                } catch (e: Exception) {
                    Log.e(TAG, "Pruning failed", e)
                    circuitBreaker.recordFailure("pruning")
                }

                delay(config.checkIntervalMs)
            }
        }
    }

   fun stop() {
        pruningJob?.cancel()
        pruningJob = null
        isRunning.set(false)
        Log.i(TAG, "Data pruning scheduler stopped")
    }

    fun shutdown() {
        stop()
        Log.i(TAG, "Data pruning scheduler shutdown")
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    suspend fun performPruning(): PruningResult {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Pruning already in progress, skipping")
            return lastPruningResult ?: PruningResult(0, 0, 0, 0, 0, 0)
        }

        val startTime = System.currentTimeMillis()

        return try {
            val dbSizeBefore = database.getDatabaseSize()
            val walSizeBefore = database.getWalFileSize()

            val totalLogsDeleted = pruneStorageAreas()

            val dbSizeAfter = database.getDatabaseSize()
            val walSizeAfter = database.getWalFileSize()
            val elapsed = System.currentTimeMillis() - startTime

            val result = PruningResult(
                battleLogsDeleted = totalLogsDeleted,
                walSizeBeforeBytes = walSizeBefore,
                walSizeAfterBytes = walSizeAfter,
                dbSizeBeforeBytes = dbSizeBefore,
                dbSizeAfterBytes = dbSizeAfter,
                elapsedMs = elapsed
            )

            this.totalPruningRuns.incrementAndGet()
            this.totalBattleLogsDeleted.addAndGet(totalLogsDeleted.toLong())
            lastPruningTime = System.currentTimeMillis()
            lastPruningResult = result

            circuitBreaker.recordSuccess("pruning")

            Log.i(TAG, "Pruning complete: logs=$totalLogsDeleted, " +
                "dbSize=${dbSizeBefore / 1024}KB->${dbSizeAfter / 1024}KB, elapsed=${elapsed}ms")

            result
        } catch (e: CancellationException) {
            throw e // 取消穿透: 取消不记熔断失败(非修剪质量退化), isRunning 由 finally 复位
        } catch (e: Exception) {
            circuitBreaker.recordFailure("pruning")
            Log.e(TAG, "Pruning failed", e)
            PruningResult(0, 0, 0, 0, 0, System.currentTimeMillis() - startTime)
        } finally {
            isRunning.set(false)
        }
    }

    /**
     * 存储域修剪三段：battleLog 主表裁剪（槽位写锁互斥）+ change_log 保留窗口 +
     * 迁移备份窗口裁剪，外加 legacy snapshots/ 一次性删除（审计 P3-9）。
     * @return 本轮删除的 battleLog 行数
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount") // 前者: 防御兜底异常源不可枚举; 后者: 各段取消穿透
    // rethrow 刻意独立抛出(结构化取消语义), 非疏忽超标
    private suspend fun pruneStorageAreas(): Int {
        val battleLogCutoff = System.currentTimeMillis() - config.battleLogRetentionMs
        var totalLogsDeleted = 0
        for (slotId in config.slotIds) {
            try {
                // 修剪与保存互斥：保存事务全量重写 battleLogs，与修剪删除
                // 交叉会导致主表/归档表数据漂移，须持槽位写锁
                core.lockManager.withWriteLockLight(slotId) {
                    // 审计 P3-11：累加 deleteOld 返回的**删除行数**（原
                    // totalLogsDeleted++ 计的是槽位数——口径失真）
                    totalLogsDeleted += database.battleLogDao()
                        .deleteOld(slotId, battleLogCutoff)
                }
            } catch (e: CancellationException) {
                throw e // 取消穿透: 修剪取消时中止剩余槽位, 槽位写锁不跨取消持锁
            } catch (e: Exception) {
                Log.d(TAG, "Battle log pruning for slot $slotId: ${e.message}")
            }
        }

        // 审计 P2-14：change_log 清理接线（deleteOlderThan 既有实现零调用方）
        // ——保留 7 天排障窗口
        try {
            database.changeLogDao().deleteOlderThan(
                System.currentTimeMillis() - changeLogRetentionMs
            )
        } catch (e: CancellationException) {
            throw e // 取消穿透: change_log 清理取消时上抛, 不误标"跳过"
        } catch (e: Exception) {
            Log.d(TAG, "Change log pruning skipped: ${e.message}")
        }

        // 审计 P1-5 双保险：迁移备份保留窗口裁剪（主接线在 GameDatabase
        // verifyAndRecoverDatabase 版本达标分支）
        try {
            database.pruneMigrationBackups()
        } catch (e: CancellationException) {
            throw e // 取消穿透: 备份窗口裁剪取消时上抛, 不误标"跳过"
        } catch (e: Exception) {
            Log.d(TAG, "Migration backup pruning skipped: ${e.message}")
        }

        // 审计 P3-9：legacy snapshots/ 目录一次性删除
        deleteLegacySnapshotsOnce()
        return totalLogsDeleted
    }

    fun getStats(): PruningStats {
        return PruningStats(
            totalPruningRuns = totalPruningRuns.get(),
            totalBattleLogsDeleted = totalBattleLogsDeleted.get(),
            lastPruningTime = lastPruningTime,
            lastPruningResult = lastPruningResult,
            isRunning = isRunning.get()
        )
    }

    companion object {
        private const val TAG = "DataPruningScheduler"
    }
}
