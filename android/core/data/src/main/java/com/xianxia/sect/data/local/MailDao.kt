package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.MailEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MailDao {
    /**
     * 邮件永久保留：不过滤过期时间，过期邮件仍可见——邮件只能被玩家手动
     * "删除已读"清理，任何自动删除（读档/容量/过期）都不得触发。
     */
    @Query("SELECT * FROM mails WHERE slotId = :slotId ORDER BY isRead ASC, sendTime DESC")
    fun getActiveMails(slotId: Int): Flow<List<MailEntity>>

    @Query("SELECT COUNT(*) FROM mails WHERE slotId = :slotId AND isRead = 0")
    fun getUnreadCount(slotId: Int): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM mails WHERE slotId = :slotId AND remoteMailId = :remoteId LIMIT 1)")
    suspend fun existsByRemoteId(slotId: Int, remoteId: String): Boolean

    @Transaction
    suspend fun insertWithEnforceLimit(mail: MailEntity, maxLimit: Int = 1000) {
        // 只增不删：不按容量自动淘汰（避免未领取附件被静默清掉）。
        // REPLACE 保证确定性 mailId 重放幂等。
        insertAll(listOf(mail))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(mails: List<MailEntity>)

    @Update
    suspend fun update(mail: MailEntity)

    @Query("DELETE FROM mails WHERE slotId = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    /** 仅当邮件无附件或附件已领取时删除，原子化替代 deleteMail 的 TOCTOU 读-改-写模式 */
    @Query("DELETE FROM mails WHERE slotId = :slotId AND id = :id AND (hasAttachment = 0 OR attachmentClaimed = 1)")
    suspend fun deleteIfClaimed(slotId: Int, id: String)

    @Query("DELETE FROM mails WHERE slotId = :slotId AND id IN (:ids)")
    suspend fun deleteByIds(slotId: Int, ids: List<String>)

    @Query("SELECT * FROM mails WHERE slotId = :slotId AND id = :id LIMIT 1")
    suspend fun getById(slotId: Int, id: String): MailEntity?

    /**
     * 玩家手动"删除已读"：唯一允许的邮件删除入口。
     * 仅删已读且已领取的邮件——未领取附件仍留在邮件里，绝不产生资产丢失。
     */
    @Query("DELETE FROM mails WHERE slotId = :slotId AND isRead = 1 AND attachmentClaimed = 1")
    suspend fun deleteAllReadAndClaimed(slotId: Int)

    @Query("DELETE FROM mails WHERE slotId = :slotId")
    suspend fun deleteAllForSlot(slotId: Int)

    @Query("DELETE FROM mails WHERE slotId = :slotId AND id = :builtinId AND source = 'builtin'")
    suspend fun deleteByBuiltinId(slotId: Int, builtinId: String)
}
