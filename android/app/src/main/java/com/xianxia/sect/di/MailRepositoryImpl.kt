package com.xianxia.sect.di

import android.util.Log
import androidx.room.withTransaction
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.overflow.PersistedDirectMailDraft
import com.xianxia.sect.core.overflow.PersistedOverflowDraft
import com.xianxia.sect.core.repository.MailRepository
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.local.MailDao
import com.xianxia.sect.data.local.MailDraftDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton


/**
 * Bridge implementation of [MailRepository] — lives in :app module,
 * delegates all persistence calls to [MailDao] / [MailDraftDao].
 */
@Singleton
class MailRepositoryImpl @Inject constructor(
    private val mailDao: MailDao,
    private val mailDraftDao: MailDraftDao,
    private val db: GameDatabase
) : MailRepository {

    private companion object {
        private const val TAG = "MailRepository"
    }

    override fun getActiveMails(slotId: Int, nowMs: Long): Flow<List<MailEntity>> =
        mailDao.getActiveMails(slotId, nowMs)

    override fun getUnreadCount(slotId: Int, nowMs: Long): Flow<Int> =
        mailDao.getUnreadCount(slotId, nowMs)

    override suspend fun getById(slotId: Int, mailId: String): MailEntity? =
        mailDao.getById(slotId, mailId)

    override suspend fun existsByRemoteId(slotId: Int, remoteId: String): Boolean =
        mailDao.existsByRemoteId(slotId, remoteId)

    override suspend fun insertWithEnforceLimit(entity: MailEntity, maxPerSlot: Int) =
        mailDao.insertWithEnforceLimit(entity, maxPerSlot)

    override suspend fun update(entity: MailEntity) =
        mailDao.update(entity)

    override suspend fun deleteById(slotId: Int, mailId: String) =
        mailDao.deleteById(slotId, mailId)

    override suspend fun deleteIfClaimed(slotId: Int, mailId: String) =
        mailDao.deleteIfClaimed(slotId, mailId)

    override suspend fun deleteAllForSlot(slotId: Int) =
        mailDao.deleteAllForSlot(slotId)

    override suspend fun deleteAllReadAndClaimed(slotId: Int) =
        mailDao.deleteAllReadAndClaimed(slotId)

    override suspend fun deleteExpired(slotId: Int, nowMs: Long) =
        mailDao.deleteExpired(slotId, nowMs)

    override suspend fun deleteMailsWithoutAttachments(slotId: Int) =
        mailDao.deleteMailsWithoutAttachments(slotId)

    // === 草稿持久化（D-01 事务化根治） ===
    // 非挂起（阻塞）方法：供 GameStateStore 事务提交钩子（锁外、事务线程，禁 suspend）
    // 同步调用。Room 非挂起 DAO 方法在调用线程同步执行，引擎线程非主线程合法。

    override fun insertOverflowDraftsBlocking(drafts: List<PersistedOverflowDraft>): Int {
        return try {
            if (drafts.isEmpty()) 0 else mailDraftDao.insertOverflowDrafts(drafts).size
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "溢出草稿落盘失败（转入 unpublished 重试队列）drafts=${drafts.size}", e)
            0
        }
    }

    override fun insertDirectMailDraftBlocking(draft: PersistedDirectMailDraft): Boolean {
        return try {
            mailDraftDao.insertDirectMailDraft(draft) > 0
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "直发草稿落盘失败（转入 unpublished 重试队列）id=${draft.id}", e)
            false
        }
    }

    override fun getPersistedOverflowDraftsBlocking(): List<PersistedOverflowDraft> {
        return try {
            mailDraftDao.getPersistedOverflowDrafts()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "读取溢出草稿失败", e)
            emptyList()
        }
    }

    override fun getPersistedDirectMailDraftsBlocking(): List<PersistedDirectMailDraft> {
        return try {
            mailDraftDao.getPersistedDirectMailDrafts()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "读取直发草稿失败", e)
            emptyList()
        }
    }

    override fun deleteOverflowDraftsBlocking(ids: List<String>): Int {
        return try {
            if (ids.isEmpty()) 0 else mailDraftDao.deleteOverflowDrafts(ids)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "删除溢出草稿失败 ids=${ids.size}", e)
            0
        }
    }

    override fun deleteDirectMailDraftsBlocking(ids: List<String>): Int {
        return try {
            if (ids.isEmpty()) 0 else mailDraftDao.deleteDirectMailDrafts(ids)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "删除直发草稿失败 ids=${ids.size}", e)
            0
        }
    }

    override fun deleteAllDraftsForSlotBlocking(slotId: Int) {
        try {
            mailDraftDao.deleteAllOverflowDraftsForSlot(slotId)
            mailDraftDao.deleteAllDirectMailDraftsForSlot(slotId)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "删除槽位草稿失败 slotId=$slotId", e)
        }
    }

    /**
     * 跨 DAO 原子事务：mails 写入 + 草稿删除 原子化（D-01 drain 消费）。
     *
     * 崩溃只发生在事务前/后——事务内 mails 写入与草稿删除要么都成功要么都不发生，
     * 重放不会重复发邮件（草稿行仍在 = 事务未提交）。Room 不支持跨 DAO 的
     * @Transaction 默认方法，故在 repository 层用 [androidx.room.withTransaction]。
     *
     * @param overflowDraftIds 溢出草稿行 id（同组）
     * @param directDraftIds 直发草稿行 id（同组）
     */
    override suspend fun insertWithEnforceLimitAndDeleteDrafts(
        entity: MailEntity,
        maxPerSlot: Int,
        overflowDraftIds: List<String>,
        directDraftIds: List<String>
    ) {
        db.withTransaction {
            mailDao.insertWithEnforceLimit(entity, maxPerSlot)
            if (overflowDraftIds.isNotEmpty()) mailDraftDao.deleteOverflowDrafts(overflowDraftIds)
            if (directDraftIds.isNotEmpty()) mailDraftDao.deleteDirectMailDrafts(directDraftIds)
        }
    }
}
