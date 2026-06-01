package com.xianxia.sect.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xianxia.sect.core.model.ClaimedMailRecord

@Dao
interface ClaimedMailDao {
    @Query("SELECT EXISTS(SELECT 1 FROM claimed_mail_records WHERE mailGlobalId = :globalId AND slotId = :slotId LIMIT 1)")
    suspend fun isClaimed(globalId: String, slotId: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: ClaimedMailRecord)

    @Query("SELECT * FROM claimed_mail_records WHERE mailGlobalId = :globalId AND slotId = :slotId LIMIT 1")
    suspend fun getRecord(globalId: String, slotId: Int): ClaimedMailRecord?

    @Query("DELETE FROM claimed_mail_records WHERE mailGlobalId IN (SELECT 'remote:' || remoteMailId FROM mails WHERE slotId = :slotId AND expireTime <= :now) OR mailGlobalId IN (SELECT 'builtin:' || id FROM mails WHERE slotId = :slotId AND expireTime <= :now AND source = 'builtin')")
    suspend fun deleteOrphanedForExpiredMails(slotId: Int, now: Long)

    @Query("DELETE FROM claimed_mail_records WHERE slotId = :slotId")
    suspend fun deleteAllForSlot(slotId: Int)
}
