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
    @Query("SELECT * FROM mails ORDER BY isRead ASC, sendTime DESC")
    fun getActiveMails(): Flow<List<MailEntity>>

    /**
     * 全量邮件快照读取（SR-1：保存时入 SaveData 用）。
     * 如实返回表内现状（含尚未被惰性清理的过期行——30 天删除语义归 SR-5，本读取不做任何删除）。
     */
    @Query("SELECT * FROM mails ORDER BY sendTime DESC")
    suspend fun getAllSync(): List<MailEntity>

    /**
     * 删除槽位内全部过期邮件（决策项② 2026-09-09：过期即删）。
     * 过期邮件领取路径本就返回 Expired 不可领——删除无功能损失。
     * expireTime=0（永久有效）不受影响。@return 删除行数
     */
    @Query("DELETE FROM mails WHERE expireTime > 0 AND expireTime < :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("SELECT COUNT(*) FROM mails WHERE isRead = 0")
    fun getUnreadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM mails")
    suspend fun countMails(): Int

    @Transaction
    suspend fun insertWithEnforceLimit(mail: MailEntity, now: Long, maxLimit: Int = 1000) {
        // 决策项② 2026-09-09：过期邮件自动删除——每次写入顺带清理过期
        // 邮件（过期不可领取，删除无功能损失）；expireTime=0 永久有效不受影响。
        // REPLACE 保证确定性 mailId 重放幂等。
        // SR-5：`now` 由调用方（:app MailRepositoryImpl）经注入墙钟供时——
        // 本方法每次插入都会跑一遍 30 天删除，是收敛前守卫抓不到的裸钟绕行点。
        insertAll(listOf(mail))
        deleteExpired(now)
        // 容量溢出可见化（审计 P1-4）：过期删除后若仍超限（大量永久有效
        // 邮件的极端档），计数留痕供观察——不做容量淘汰
        val count = countMails()
        if (count > maxLimit) {
            mailOverflowCount.incrementAndGet()
            android.util.Log.w("MailDao", "Mail over limit: " +
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

    @Query("DELETE FROM mails WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 仅当邮件无附件或附件已领取时删除，原子化替代 deleteMail 的 TOCTOU 读-改-写模式 */
    @Query("DELETE FROM mails WHERE id = :id AND (hasAttachment = 0 OR attachmentClaimed = 1)")
    suspend fun deleteIfClaimed(id: String)

    @Query("DELETE FROM mails WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM mails WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MailEntity?

    /**
     * 玩家手动"删除已读"：唯一允许的邮件删除入口。
     * 仅删已读且已领取的邮件——未领取附件仍留在邮件里，绝不产生资产丢失。
     */
    @Query("DELETE FROM mails WHERE isRead = 1 AND attachmentClaimed = 1")
    suspend fun deleteAllReadAndClaimed()

    @Query("DELETE FROM mails")
    suspend fun deleteAll()

    @Query("DELETE FROM mails WHERE id = :builtinId AND source = 'builtin'")
    suspend fun deleteByBuiltinId(builtinId: String)
}
