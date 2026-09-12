package com.xianxia.sect.data.engine

import android.util.Log
import com.xianxia.sect.data.archive.ArchivedBattleLog
import com.xianxia.sect.data.archive.ArchivedDisciple
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

data class ArchiveConfig(
    val checkIntervalMs: Long = 600_000L,
    val battleLogHotCount: Int = 200,
    val deadDiscipleArchiveDelayMs: Long = 60_000L,
    val archiveRetentionMs: Long = 180L * 24 * 60 * 60 * 1000L,
    val slotIds: List<Int> = listOf(1, 2, 3, 4, 5),
    val enableAutoArchive: Boolean = true
)

data class ArchiveOperationResult(
    val battleLogsArchived: Int = 0,
    val disciplesArchived: Int = 0,
    val battleLogsCleaned: Int = 0,
    val disciplesCleaned: Int = 0,
    val elapsedMs: Long = 0
)

@Singleton
class DataArchiveScheduler @Inject constructor(
    private val database: GameDatabase,
    private val core: StorageCoreFacade,
    private val dataArchiver: com.xianxia.sect.data.archive.DataArchiver,
    private val scopeProvider: CoroutineScopeProvider
) {
    private val config = ArchiveConfig()
    private var archiveJob: Job? = null
    private val scope get() = scopeProvider.ioScope

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun start() {
        if (!config.enableAutoArchive) {
            Log.i(TAG, "Auto archive is disabled by config")
            return
        }

        if (archiveJob?.isActive == true) {
            Log.w(TAG, "Archive scheduler already running")
            return
        }

        archiveJob = scope.launch {
            Log.i(TAG, "Data archive scheduler started (interval=${config.checkIntervalMs}ms)")
            delay(config.checkIntervalMs)

            while (isActive) {
                try {
                    performArchive()
                } catch (e: CancellationException) {
                    throw e // 取消穿透: 调度器 stop() 后立即退出轮询, 不再进入下一轮 delay
                } catch (e: Exception) {
                    Log.e(TAG, "Archive operation failed", e)
                }

                delay(config.checkIntervalMs)
            }
        }
    }

    fun stop() {
        archiveJob?.cancel()
        archiveJob = null
        Log.i(TAG, "Data archive scheduler stopped")
    }

    fun shutdown() {
        stop()
        Log.i(TAG, "Data archive scheduler shutdown")
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    suspend fun performArchive(): ArchiveOperationResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var battleLogsArchived = 0
        var disciplesArchived = 0
        var battleLogsCleaned = 0
        var disciplesCleaned = 0

        for (slotId in config.slotIds) {
            try {
                // 归档与保存互斥：保存事务全量重写 battleLogs/disciples，
                // 与归档读删交叉时会导致主表/归档表数据漂移，须持槽位写锁
                core.lockManager.withWriteLockLight(slotId) {
                    val logsResult = archiveBattleLogs(slotId)
                    battleLogsArchived += logsResult

                    val disciplesResult = archiveDeadDisciples(slotId)
                    disciplesArchived += disciplesResult
                }
            } catch (e: CancellationException) {
                throw e // 取消穿透: 归档取消时中止剩余槽位, 槽位写锁不跨取消持锁
            } catch (e: Exception) {
                Log.w(TAG, "Archive for slot $slotId failed: ${e.message}")
            }
        }

        try {
            val cutoff = System.currentTimeMillis() - config.archiveRetentionMs
            battleLogsCleaned = database.archivedBattleLogDao().deleteArchivedBefore(cutoff)
            disciplesCleaned = database.archivedDiscipleDao().deleteArchivedBefore(cutoff)
        } catch (e: CancellationException) {
            throw e // 取消穿透: 过期清理取消时上抛, 不谎报清理失败
        } catch (e: Exception) {
            Log.w(TAG, "Archive cleanup failed: ${e.message}")
        }

        // 审计 P2-15：cleanupExpiredArchives 清理链接线（原实现零调用方）——
        // 归档 .arc 文件按 12 个月保留窗口清理（与 maxBattleLogs 运行时可调
        // 5000 的配置面联动：归档产出随修剪/归档上限受控）
        try {
            dataArchiver.cleanupExpiredArchives()
        } catch (e: CancellationException) {
            throw e // 取消穿透: 归档文件清理取消时上抛, 下轮调度重新清理
        } catch (e: Exception) {
            Log.w(TAG, "Expired archive file cleanup failed: ${e.message}")
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "Archive complete: logs=$battleLogsArchived, " +
            "disciples=$disciplesArchived, cleaned=($battleLogsCleaned,$disciplesCleaned), " +
            "elapsed=${elapsed}ms")

        ArchiveOperationResult(
            battleLogsArchived = battleLogsArchived,
            disciplesArchived = disciplesArchived,
            battleLogsCleaned = battleLogsCleaned,
            disciplesCleaned = disciplesCleaned,
            elapsedMs = elapsed
        )
    }

    private suspend fun archiveBattleLogs(slotId: Int): Int {
        val totalCount = database.battleLogDao().countBySlot(slotId)
        if (totalCount <= config.battleLogHotCount) return 0

        val overflow = totalCount - config.battleLogHotCount
        val toArchive = database.battleLogDao().getOldestBySlot(slotId, overflow)

        if (toArchive.isEmpty()) return 0

        val archived = toArchive.map { log ->
            ArchivedBattleLog(
                slotId = slotId,
                originalId = log.id,
                battleType = log.type.name,
                result = log.result.name,
                timestamp = log.timestamp,
                attackerName = log.attackerName,
                defenderName = log.defenderName,
                dataBlob = ""
            )
        }

        database.withTransaction {
            database.archivedBattleLogDao().insertAll(archived)
            for (log in toArchive) {
                database.battleLogDao().deleteById(slotId, log.id)
            }
        }

        return archived.size
    }

    private suspend fun archiveDeadDisciples(slotId: Int): Int {
        val deadDisciples = database.discipleDao().getDeadBySlotSync(slotId)

        if (deadDisciples.isEmpty()) return 0

        val archived = deadDisciples.map { disciple ->
            ArchivedDisciple(
                slotId = slotId,
                originalId = disciple.id,
                name = disciple.name,
                realm = disciple.realm,
                dataBlob = ""
            )
        }

        database.withTransaction {
            database.archivedDiscipleDao().insertAll(archived)
            database.discipleDao().deleteDeadBySlot(slotId)
        }

        return archived.size
    }

    companion object {
        private const val TAG = "DataArchiveScheduler"
    }
}
