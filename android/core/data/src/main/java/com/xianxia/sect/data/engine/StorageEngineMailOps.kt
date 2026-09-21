package com.xianxia.sect.data.engine

import androidx.room.withTransaction
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.data.model.SaveData

// SR-1 邮件快照的 StorageEngine 读写层：按域独立成文件（模块既有拆分惯例，
// 同 HeavyDataOps/LoadOps 口径）——避免 StorageEngineWriteOps 函数数超 detekt 阈值。

private const val MAX_BATCH_SIZE = StorageEngine.MAX_BATCH_SIZE

/**
 * 邮件快照回填（SR-1 整对象替换的写侧）：SaveData.mails → `mails` 表。
 *
 * 删侧在 [StorageEngine.clearOldSlotEntities]（同处 `writeAllDataToDatabase` 的
 * 外层事务内），先删后写 = 整对象替换；调用链在 `performFullTransactionSave` 的
 * `withTransaction` 之内（IN1 原子性），且不得依赖吞内层异常做部分提交
 * （SR-0 §5 Room 2.7.0 savepoint 语义警示）。
 */
internal suspend fun StorageEngine.writeMails(slot: Int, data: SaveData) {
    data.mails.chunked(MAX_BATCH_SIZE).forEach { batch ->
        core.database.mailDao().insertAll(batch.map { it.copy(slotId = slot) })
    }
}

/**
 * 槽位全量邮件快照读取（SR-1 保存面注入用）：保存编排从表读当前 slot 全量入 SaveData。
 * 如实返回表内现状，不做任何过期清理（30 天删除逻辑归 SR-5）。
 */
internal suspend fun StorageEngine.getMailsForSlot(slot: Int): List<MailEntity> =
    core.database.mailDao().getAllForSlotSync(slot)

/**
 * 槽位邮件整对象替换（SR-1 云恢复面用）：下载快照的邮件单表回填（先删后写，单事务）。
 *
 * 仅替换邮件表——云恢复全量落盘归 SR-3（审计 §3/§12-I），本函数不越界；
 * 内层异常直接上抛（Room 2.7.0 吞内层异常 = 仅回滚内层写，不得依赖其做部分提交，
 * SR-0 §5 警示）。
 */
internal suspend fun StorageEngine.replaceMailsForSlot(slot: Int, mails: List<MailEntity>) {
    core.database.withTransaction {
        core.database.mailDao().deleteAllForSlot(slot)
        mails.chunked(MAX_BATCH_SIZE).forEach { batch ->
            core.database.mailDao().insertAll(batch.map { it.copy(slotId = slot) })
        }
    }
}
