package com.xianxia.sect.data.archive

import android.util.Log
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.local.ProtobufConverters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 归档表读面默认条数（诊断列表量级） */
internal const val ARCHIVE_LIST_DEFAULT_LIMIT = 50

/** 归档概览：两张归档表的行数读数 */
data class ArchiveOverview(
    val battleLogCount: Int,
    val discipleCount: Int
)

/** 归档战报行摘要（列表展示面，不含载荷） */
data class ArchivedBattleLogSummary(
    val id: Long,
    val originalId: String,
    val battleType: String,
    val result: String,
    val timestamp: Long,
    val attackerName: String,
    val defenderName: String,
    val archivedAt: Long
)

/** 归档弟子（陨落历史）行摘要 */
data class ArchivedDiscipleSummary(
    val id: Long,
    val originalId: String,
    val name: String,
    val realm: Int,
    val archivedAt: Long
)

/**
 * 归档表唯一读面：列表（按归档时间倒序）+ 按 id 还原载荷。
 *
 * 归档行的 `dataBlob` 是可还原全量载荷（写入侧 `DataArchiveScheduler` 的
 * `encodeArchived*Blob`），本门面是其唯一读取通道——消费方为存档诊断与
 * 战报/陨落历史；删档重置后不存在"恢复旧档"语义，还原只用于展示，
 * **不得**把归档行写回主表（`ArchiveWriteOnlyGuardTest` 锁定该边界）。
 */
@Singleton
class ArchiveReader @Inject constructor(
    private val database: GameDatabase
) {
    companion object {
        private const val TAG = "ArchiveReader"
    }

    suspend fun overview(): ArchiveOverview = withContext(Dispatchers.IO) {
        ArchiveOverview(
            battleLogCount = database.archivedBattleLogDao().countAll(),
            discipleCount = database.archivedDiscipleDao().countAll()
        )
    }

    suspend fun listRecentBattleLogs(limit: Int = ARCHIVE_LIST_DEFAULT_LIMIT): List<ArchivedBattleLogSummary> =
        withContext(Dispatchers.IO) {
            database.archivedBattleLogDao().listRecent(limit).map { row ->
                ArchivedBattleLogSummary(
                    id = row.id,
                    originalId = row.originalId,
                    battleType = row.battleType,
                    result = row.result,
                    timestamp = row.timestamp,
                    attackerName = row.attackerName,
                    defenderName = row.defenderName,
                    archivedAt = row.archivedAt
                )
            }
        }

    suspend fun listRecentDisciples(limit: Int = ARCHIVE_LIST_DEFAULT_LIMIT): List<ArchivedDiscipleSummary> =
        withContext(Dispatchers.IO) {
            database.archivedDiscipleDao().listRecent(limit).map { row ->
                ArchivedDiscipleSummary(
                    id = row.id,
                    originalId = row.originalId,
                    name = row.name,
                    realm = row.realm,
                    archivedAt = row.archivedAt
                )
            }
        }

    /** 按 id 还原归档战报载荷；行缺失或载荷损坏返回 null（诊断场景如实降级） */
    suspend fun restoreBattleLog(rowId: Long): BattleLog? = withContext(Dispatchers.IO) {
        val row = database.archivedBattleLogDao().getById(rowId) ?: return@withContext null
        decodeBlob("archived_battle_logs#$rowId", row.dataBlob) {
            ProtobufConverters.decodeFromBase64(BattleLog.serializer(), row.dataBlob) {
                throw ArchivedBlobCorruptionException()
            }
        }
    }

    /** 按 id 还原归档弟子载荷；行缺失或载荷损坏返回 null（诊断场景如实降级） */
    suspend fun restoreDisciple(rowId: Long): Disciple? = withContext(Dispatchers.IO) {
        val row = database.archivedDiscipleDao().getById(rowId) ?: return@withContext null
        decodeBlob("archived_disciples#$rowId", row.dataBlob) {
            ProtobufConverters.decodeFromBase64(Disciple.serializer(), row.dataBlob) {
                throw ArchivedBlobCorruptionException()
            }
        }
    }

    /** 载荷解码统一降级：空载荷/损坏载荷记日志返回 null，不向诊断 UI 抛异常 */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 载荷损坏异常源跨序列化栈不可枚举, 降级 null+日志留痕, 非静默吞噬
    private inline fun <T> decodeBlob(source: String, blob: String, decode: () -> T): T? {
        if (blob.isEmpty()) {
            Log.w(TAG, "归档载荷为空（不可还原）: $source")
            return null
        }
        return try {
            decode()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "归档载荷损坏（不可还原）: $source", e)
            null
        }
    }
}

/** 归档载荷损坏（`decodeFromBase64` 的降级哨兵，由 [ArchiveReader] 统一转为 null） */
private class ArchivedBlobCorruptionException : IllegalStateException("archived blob corrupted")
