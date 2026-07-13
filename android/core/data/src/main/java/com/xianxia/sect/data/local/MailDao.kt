package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.MailEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MailDao {
    @Query("SELECT * FROM mails WHERE slotId = :slotId AND expireTime > :now ORDER BY isRead ASC, sendTime DESC")
    fun getActiveMails(slotId: Int, now: Long): Flow<List<MailEntity>>

    @Query("SELECT COUNT(*) FROM mails WHERE slotId = :slotId AND isRead = 0 AND expireTime > :now")
    fun getUnreadCount(slotId: Int, now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM mails WHERE slotId = :slotId")
    fun getMailCount(slotId: Int): Int

    @Query("SELECT EXISTS(SELECT 1 FROM mails WHERE remoteMailId = :remoteId LIMIT 1)")
    fun existsByRemoteId(remoteId: String): Boolean

    @Transaction
    fun insertWithEnforceLimit(mail: MailEntity, maxLimit: Int = 1000) {
        val currentCount = getMailCount(mail.slotId)
        if (currentCount >= maxLimit) {
            deleteOldestReadAndClaimed(mail.slotId, currentCount - maxLimit + 1)
        }
        insertAll(listOf(mail))
    }

    @Query("DELETE FROM mails WHERE id IN (SELECT id FROM mails WHERE slotId = :slotId AND isRead = 1 AND attachmentClaimed = 1 ORDER BY sendTime ASC LIMIT :count)")
    fun deleteOldestReadAndClaimed(slotId: Int, count: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(mails: List<MailEntity>)

    @Update
    fun update(mail: MailEntity)

    @Query("UPDATE mails SET isRead = 1 WHERE slotId = :slotId AND isRead = 0 AND expireTime > :now")
    fun markAllAsRead(slotId: Int, now: Long)

    @Query("DELETE FROM mails WHERE id = :id")
    fun deleteById(id: String)

    @Query("DELETE FROM mails WHERE slotId = :slotId AND id IN (:ids)")
    fun deleteByIds(slotId: Int, ids: List<String>)

    @Query("DELETE FROM mails WHERE slotId = :slotId AND expireTime <= :now")
    fun deleteExpired(slotId: Int, now: Long)

    @Query("SELECT * FROM mails WHERE id = :id LIMIT 1")
    fun getById(id: String): MailEntity?

    @Query("DELETE FROM mails WHERE slotId = :slotId AND isRead = 1 AND attachmentClaimed = 1")
    fun deleteAllReadAndClaimed(slotId: Int)

    @Query("DELETE FROM mails WHERE slotId = :slotId AND hasAttachment = 0")
    fun deleteMailsWithoutAttachments(slotId: Int)

    @Query("DELETE FROM mails WHERE slotId = :slotId")
    fun deleteAllForSlot(slotId: Int)

    @Query("DELETE FROM mails WHERE id = :builtinId AND source = 'builtin'")
    fun deleteByBuiltinId(builtinId: String)
}
