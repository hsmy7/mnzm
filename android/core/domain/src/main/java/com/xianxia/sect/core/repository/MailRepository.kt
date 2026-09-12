package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.MailEntity
import kotlinx.coroutines.flow.Flow


/**
 * Mail persistence interface — defined in domain, implemented in data module.
 * Engine's MailService depends on this interface, not on MailDao directly.
 */
@Suppress("TooManyFunctions") // 邮件仓储契约：查询/领取/删除/补偿全生命周期端口，函数数即仓储协议面
interface MailRepository {

    fun getActiveMails(slotId: Int): Flow<List<MailEntity>>

    fun getUnreadCount(slotId: Int): Flow<Int>

    suspend fun getById(slotId: Int, mailId: String): MailEntity?

    suspend fun existsByRemoteId(slotId: Int, remoteId: String): Boolean

    suspend fun insertWithEnforceLimit(entity: MailEntity, maxPerSlot: Int)

    suspend fun update(entity: MailEntity)

    suspend fun deleteById(slotId: Int, mailId: String)

    /** 原子化删除：仅当邮件无附件或附件已领取时执行删除 */
    suspend fun deleteIfClaimed(slotId: Int, mailId: String)

    /** 删除槽位内过期邮件（决策项②：过期即删；expireTime=0 永久有效）。@return 删除行数 */
    suspend fun deleteExpiredMails(slotId: Int, now: Long): Int

    suspend fun deleteAllForSlot(slotId: Int)

    /** 玩家手动"删除已读"：仅删已读且已领取的邮件（邮件唯一删除入口） */
    suspend fun deleteAllReadAndClaimed(slotId: Int)

    // === 草稿持久化 ===
    // 非挂起（阻塞）方法：供 GameStateStore 事务提交钩子（锁外、事务线程）同步调用。
    // Room 非挂起 DAO 方法在调用线程同步执行，引擎线程非主线程合法。

    /**
     * 批量插入溢出草稿行（阻塞）。提交钩子落盘专用。
     * @return 实际写入行数；0 表示全部插入失败（调用方转入 unpublished 重试队列）
     */
    fun insertOverflowDraftsBlocking(drafts: List<com.xianxia.sect.core.overflow.PersistedOverflowDraft>): Int

    /**
     * 插入直发草稿行（阻塞）。提交钩子落盘专用。
     * @return true 写入成功；false 失败（调用方转入 unpublished 重试队列）
     */
    fun insertDirectMailDraftBlocking(draft: com.xianxia.sect.core.overflow.PersistedDirectMailDraft): Boolean

    /** 读取全量溢出草稿行（阻塞），按 createdAt 升序。 */
    fun getPersistedOverflowDraftsBlocking(): List<com.xianxia.sect.core.overflow.PersistedOverflowDraft>

    /** 读取全量直发草稿行（阻塞），按 createdAt 升序。 */
    fun getPersistedDirectMailDraftsBlocking(): List<com.xianxia.sect.core.overflow.PersistedDirectMailDraft>

    /** 按 id 批量删除溢出草稿行（阻塞）。 */
    fun deleteOverflowDraftsBlocking(ids: List<String>): Int

    /** 按 id 批量删除直发草稿行（阻塞）。 */
    fun deleteDirectMailDraftsBlocking(ids: List<String>): Int

    /** 删除指定槽位全部草稿行（阻塞）。槽位删除路径调用。 */
    fun deleteAllDraftsForSlotBlocking(slotId: Int)

    /**
     * 跨 DAO 原子事务：mails 写入 + 草稿删除 原子化（drain 消费）。
     *
     * 崩溃只发生在事务前/后——mails 写入与草稿删除要么都成功要么都不发生，
     * 重放不会重复发邮件（草稿行仍在 = 事务未提交）。
     *
     * @param overflowDraftIds 同组溢出草稿行 id（写邮件成功后同一事务删除）
     * @param directDraftIds 同组直发草稿行 id（同一事务删除）
     */
    suspend fun insertWithEnforceLimitAndDeleteDrafts(
        entity: MailEntity,
        maxPerSlot: Int,
        overflowDraftIds: List<String>,
        directDraftIds: List<String>
    )
}
