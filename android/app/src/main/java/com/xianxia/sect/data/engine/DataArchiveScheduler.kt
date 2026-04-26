package com.xianxia.sect.data.engine

import android.util.Log
import com.xianxia.sect.data.archive.ArchivedBattleLog
import com.xianxia.sect.data.archive.ArchivedDisciple
import com.xianxia.sect.data.archive.ArchivedGameEvent
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.di.ApplicationScopeProvider
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
    val gameEventHotCount: Int = 500,
    val deadDiscipleArchiveDelayMs: Long = 60_000L,
    val archiveRetentionMs: Long = 180L * 24 * 60 * 60 * 1000L,
    val slotIds: List<Int> = listOf(1, 2, 3, 4, 5),
    val enableAutoArchive: Boolean = true
)

data class ArchiveOperationResult(
    val battleLogsArchived: Int = 0,
    val gameEventsArchived: Int = 0,
    val disciplesArchived: Int = 0,
    val battleLogsCleaned: Int = 0,
    val gameEventsCleaned: Int = 0,
    val disciplesCleaned: Int = 0,
    val elapsedMs: Long = 0
)

@Singleton
class DataArchiveScheduler @Inject constructor(
    private val database: GameDatabase,
    private val applicationScopeProvider: ApplicationScopeProvider
) {
    private val config = ArchiveConfig()
    private var archiveJob: Job? = null
    private val scope get() = applicationScopeProvider.ioScope

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

    suspend fun performArchive(): ArchiveOperationResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var battleLogsArchived = 0
        var gameEventsArchived = 0
        var disciplesArchived = 0
        var battleLogsCleaned = 0
        var gameEventsCleaned = 0
        var disciplesCleaned = 0

        for (slotId in config.slotIds) {
            try {
                val logsResult = archiveBattleLogs(slotId)
                battleLogsArchived += logsResult

                val eventsResult = archiveGameEvents(slotId)
                gameEventsArchived += eventsResult

                val disciplesResult = archiveDeadDisciples(slotId)
                disciplesArchived += disciplesResult
            } catch (e: Exception) {
                Log.w(TAG, "Archive for slot $slotId failed: ${e.message}")
            }
        }

        try {
            val cutoff = System.currentTimeMillis() - config.archiveRetentionMs
            battleLogsCleaned = database.archivedBattleLogDao().deleteArchivedBefore(cutoff)
            gameEventsCleaned = database.archivedGameEventDao().deleteArchivedBefore(cutoff)
            disciplesCleaned = database.archivedDiscipleDao().deleteArchivedBefore(cutoff)
        } catch (e: Exception) {
            Log.w(TAG, "Archive cleanup failed: ${e.message}")
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "Archive complete: logs=$battleLogsArchived, events=$gameEventsArchived, " +
            "disciples=$disciplesArchived, cleaned=($battleLogsCleaned,$gameEventsCleaned,$disciplesCleaned), " +
            "elapsed=${elapsed}ms")

        ArchiveOperationResult(
            battleLogsArchived = battleLogsArchived,
            gameEventsArchived = gameEventsArchived,
            disciplesArchived = disciplesArchived,
            battleLogsCleaned = battleLogsCleaned,
            gameEventsCleaned = gameEventsCleaned,
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

    private suspend fun archiveGameEvents(slotId: Int): Int {
        val totalCount = database.gameEventDao().countBySlot(slotId)
        if (totalCount <= config.gameEventHotCount) return 0

        val overflow = totalCount - config.gameEventHotCount
        val toArchive = database.gameEventDao().getOldestBySlot(slotId, overflow)

        if (toArchive.isEmpty()) return 0

        val archived = toArchive.map { event ->
            ArchivedGameEvent(
                slotId = slotId,
                originalId = event.id,
                eventType = event.type.name,
                timestamp = event.timestamp,
                message = event.message,
                dataBlob = ""
            )
        }

        database.withTransaction {
            database.archivedGameEventDao().insertAll(archived)
            for (event in toArchive) {
                database.gameEventDao().deleteById(slotId, event.id)
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
