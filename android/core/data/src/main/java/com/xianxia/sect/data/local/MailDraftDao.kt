// MailDraftDao.kt — D-01 溢出邮件事务化根治：草稿持久化表（事务提交钩子落盘 + drain 消费）
package com.xianxia.sect.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.xianxia.sect.core.overflow.PersistedDirectMailDraft
import com.xianxia.sect.core.overflow.PersistedOverflowDraft

/**
 * 溢出草稿持久化行（Room 表 `overflow_mail_drafts`）。
 *
 * 存在性不变量：行存在 ⇒ 来源事务已提交（提交钩子落盘、回滚钩子丢弃）。
 * 与 [PersistedOverflowDraft] 字段一一对应，由 [MailDraftDao] 在方法签名层
 * 直接映射为领域模型（@Insert(entity=) 按属性名匹配列）。
 */
@Entity(tableName = "overflow_mail_drafts")
data class OverflowMailDraftEntity(
    @PrimaryKey val id: String,
    val slotId: Int,
    val source: String,
    val itemType: String,
    val itemName: String,
    val rarity: Int,
    val quantity: Int,
    val createdAt: Long
)

/**
 * 直发草稿持久化行（Room 表 `direct_mail_drafts`）。
 * payload 为 MailEntity 的 kotlinx.serialization JSON 文本，id 即邮件 id（天然幂等）。
 */
@Entity(tableName = "direct_mail_drafts")
data class DirectMailDraftEntity(
    @PrimaryKey val id: String,
    val slotId: Int,
    val payload: String,
    val createdAt: Long
)

/**
 * 草稿 DAO。
 *
 * **全部方法均为非挂起（阻塞）**：供 GameStateStore 事务提交钩子
 * （锁外、事务线程，禁 suspend）与 drain（异步线程池）同步调用。
 * Room 非挂起 DAO 方法在调用线程同步执行，引擎线程非主线程合法。
 */
@Dao
interface MailDraftDao {

    /** 批量插入溢出草稿行（REPLACE 幂等），返回各行 rowid（长度=成功插入数；异常=0） */
    @Insert(entity = OverflowMailDraftEntity::class, onConflict = OnConflictStrategy.REPLACE)
    fun insertOverflowDrafts(drafts: List<PersistedOverflowDraft>): List<Long>

    /** 插入直发草稿行（REPLACE 幂等，同 id 覆盖），返回 rowid（>0 = 成功） */
    @Insert(entity = DirectMailDraftEntity::class, onConflict = OnConflictStrategy.REPLACE)
    fun insertDirectMailDraft(draft: PersistedDirectMailDraft): Long

    /** 读取全量溢出草稿行（按 createdAt 升序——先入先转邮件） */
    @Query(
        "SELECT id, slotId, source, itemType, itemName, rarity, quantity, createdAt " +
            "FROM overflow_mail_drafts ORDER BY createdAt ASC"
    )
    fun getPersistedOverflowDrafts(): List<PersistedOverflowDraft>

    /** 读取全量直发草稿行（按 createdAt 升序） */
    @Query("SELECT id, slotId, payload, createdAt FROM direct_mail_drafts ORDER BY createdAt ASC")
    fun getPersistedDirectMailDrafts(): List<PersistedDirectMailDraft>

    /** 按 id 批量删除溢出草稿行，返回删除行数 */
    @Query("DELETE FROM overflow_mail_drafts WHERE id IN (:ids)")
    fun deleteOverflowDrafts(ids: List<String>): Int

    /** 按 id 批量删除直发草稿行，返回删除行数 */
    @Query("DELETE FROM direct_mail_drafts WHERE id IN (:ids)")
    fun deleteDirectMailDrafts(ids: List<String>): Int

    /** 删除指定槽位全部草稿行（槽位删除路径调用） */
    @Query("DELETE FROM overflow_mail_drafts WHERE slotId = :slotId")
    fun deleteAllOverflowDraftsForSlot(slotId: Int)

    /** 删除指定槽位全部直发草稿行（槽位删除路径调用） */
    @Query("DELETE FROM direct_mail_drafts WHERE slotId = :slotId")
    fun deleteAllDirectMailDraftsForSlot(slotId: Int)
}
