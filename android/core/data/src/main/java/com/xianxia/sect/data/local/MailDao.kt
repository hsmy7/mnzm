package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.MailEntity
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // Room DAO @Query 契约面：函数数=数据访问协议面（查询维度×读写双向），
// Room 要求 DAO 方法驻留接口承载实现代理生成；项目已做过一轮 DAO 域拆分（DiscipleSubDaos），
// 继续拆分只会碎片化数据访问协议并倍增注入面
interface MailDao {
    /**
     * 未删除邮件列表（过期邮件由 [deleteExpired] 自动删除后自然不再出现；
     * expireTime=0 视为永久有效，永不过期）。
     */
    @Query("SELECT * FROM mails WHERE slotId = :slotId ORDER BY isRead ASC, sendTime DESC")
    fun getActiveMails(slotId: Int): Flow<List<MailEntity>>

    /**
     * 删除槽位内全部过期邮件（决策项② 2026-09-09：过期即删）。
     * 过期邮件领取路径本就返回 Expired 不可领——删除无功能损失。
     * expireTime=0（永久有效）不受影响。@return 删除行数
     */
    @Query("DELETE FROM mails WHERE slotId = :slotId AND expireTime > 0 AND expireTime < :now")
    suspend fun deleteExpired(slotId: Int, now: Long): Int

    @Query("SELECT COUNT(*) FROM mails WHERE slotId = :slotId AND isRead = 0")
    fun getUnreadCount(slotId: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM mails WHERE slotId = :slotId")
    suspend fun countMails(slotId: Int): Int

    @Transaction
    suspend fun insertWithEnforceLimit(mail: MailEntity, maxLimit: Int = 1000) {
        // 决策项② 2026-09-09：过期邮件自动删除——每次写入顺带清理本槽过期
        // 邮件（过期不可领取，删除无功能损失）；expireTime=0 永久有效不受影响。
        // REPLACE 保证确定性 mailId 重放幂等。
        insertAll(listOf(mail))
        deleteExpired(mail.slotId, System.currentTimeMillis())
        // 容量溢出可见化（审计 P1-4）：过期删除后若仍超限（大量永久有效
        // 邮件的极端档），计数留痕供观察——不做容量淘汰
        val count = countMails(mail.slotId)
        if (count > maxLimit) {
            mailOverflowCount.incrementAndGet()
            android.util.Log.w("MailDao", "Mail slot ${mail.slotId} over limit: " +
                "count=$count maxLimit=$maxLimit overflowTotal=${mailOverflowCount.get()}")
        }
    }

    /** 容量溢出累计计数（决策项②未确认期间的可见性埋点；AtomicLong 自带线程安全） */
    companion object {
        val mailOverflowCount = java.util.concurrent.atomic.AtomicLong(0)
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
