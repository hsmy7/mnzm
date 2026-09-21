package com.xianxia.sect.data.engine

import android.util.Log
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.archive.ArchivedBattleLog
import com.xianxia.sect.data.archive.ArchivedDisciple
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.local.ProtobufConverters
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
    // 审计 §12-F：原硬编码 `listOf(1,2,3,4,5)` **漏 slot 6**（该槽永不归档，数据无界增长），
    // 与 DataPruningScheduler 的 `(0..DEFAULT_MAX_SLOTS)` 口径不一致。改为覆盖全部
    // **本地存档槽** 1..DEFAULT_MAX_SLOTS；slot 0 是云档槽（本地无行）故不纳入。
    val slotIds: List<Int> = (1..StorageConstants.DEFAULT_MAX_SLOTS).toList(),
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

    /**
     * 战斗日志溢出归档：把超出 [ArchiveConfig.battleLogHotCount] 的最旧日志搬出主表。
     *
     * 归档行现为**可还原载荷**（`dataBlob` = 全量序列化 [BattleLog] 的 Base64，
     * 见 [encodeArchivedBattleLogBlob]）——此前固定写 ""，配合"归档表零查询调用者"
     * 等于单向数据销毁（审计 §12-F）。
     * "搬出主表"本身保留（主表保持精简），保留期由 [ArchiveConfig.archiveRetentionMs]
     * 控制，到期由 `deleteArchivedBefore` 清理。
     */
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
                dataBlob = encodeArchivedBattleLogBlob(log)
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

    /**
     * 已故弟子归档：把死亡弟子搬出 `disciples` 主表。
     *
     * 归档行现为**可还原载荷**（`dataBlob` = 全量序列化 [Disciple] 的 Base64，
     * 见 [encodeArchivedDiscipleBlob]）——此前固定写 ""（只剩 id/名/境界），
     * 不可还原（审计 §12-F）。
     * "搬出主表"本身保留（主表保持精简），保留期由 [ArchiveConfig.archiveRetentionMs]
     * 控制，到期由 `deleteArchivedBefore` 清理。
     */
    private suspend fun archiveDeadDisciples(slotId: Int): Int {
        val deadDisciples = database.discipleDao().getDeadBySlotSync(slotId)

        if (deadDisciples.isEmpty()) return 0

        val archived = deadDisciples.map { disciple ->
            ArchivedDisciple(
                slotId = slotId,
                originalId = disciple.id,
                name = disciple.name,
                realm = disciple.realm,
                dataBlob = encodeArchivedDiscipleBlob(disciple)
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

/**
 * 归档载荷编码（审计 §12-F）：把已故弟子**全量序列化**为 Base64 载荷，存入
 * [ArchivedDisciple.dataBlob]，使归档行可还原——此前固定写 ""（仅存 id/名/境界），
 * 配合"归档表零查询调用者"等于单向数据销毁。
 *
 * 保留策略不变：搬出主表仍在 [DataArchiveScheduler.archiveDeadDisciples] 执行，
 * 保留期由 [ArchiveConfig.archiveRetentionMs]（180 天）控制，到期由
 * `deleteArchivedBefore` 清理。本函数只负责载荷的可还原性。
 *
 * 注：[DiscipleSerializer] 不序列化 `slotId` 与 `@Ignore` 的 lifeEvents，且
 * `cultivationCheckpoint` 以 Double↔Long 取整——还原时这些字段回到默认值。
 */
internal fun encodeArchivedDiscipleBlob(disciple: Disciple): String =
    ProtobufConverters.encodeToBase64(Disciple.serializer(), disciple)

/**
 * 归档载荷编码（审计 §12-F）：把战斗日志**全量序列化**为 Base64 载荷，存入
 * [ArchivedBattleLog.dataBlob]，使归档行可还原——此前固定写 ""。
 *
 * 保留策略不变：搬出主表仍在 [DataArchiveScheduler.archiveBattleLogs] 执行，
 * 保留期由 [ArchiveConfig.archiveRetentionMs]（180 天）控制，到期由
 * `deleteArchivedBefore` 清理。
 *
 * 注：[BattleLog] 的 `@Transient` 字段（slotId/teamId/battleResult）不序列化。
 */
internal fun encodeArchivedBattleLogBlob(log: BattleLog): String =
    ProtobufConverters.encodeToBase64(BattleLog.serializer(), log)
