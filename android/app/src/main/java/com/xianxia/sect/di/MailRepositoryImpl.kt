package com.xianxia.sect.di

import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.repository.MailRepository
import com.xianxia.sect.data.local.MailDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge implementation of [MailRepository] — lives in :app module,
 * delegates all persistence calls to [MailDao].
 */
@Singleton
class MailRepositoryImpl @Inject constructor(
    private val mailDao: MailDao
) : MailRepository {

    override fun getActiveMails(slotId: Int, nowMs: Long): Flow<List<MailEntity>> =
        mailDao.getActiveMails(slotId, nowMs)

    override fun getUnreadCount(slotId: Int, nowMs: Long): Flow<Int> =
        mailDao.getUnreadCount(slotId, nowMs)

    override suspend fun getById(mailId: String): MailEntity? =
        mailDao.getById(mailId)

    override suspend fun existsByRemoteId(remoteId: String): Boolean =
        mailDao.existsByRemoteId(remoteId)

    override suspend fun insertWithEnforceLimit(entity: MailEntity, maxPerSlot: Int) =
        mailDao.insertWithEnforceLimit(entity, maxPerSlot)

    override suspend fun update(entity: MailEntity) =
        mailDao.update(entity)

    override suspend fun deleteById(mailId: String) =
        mailDao.deleteById(mailId)

    override suspend fun deleteAllForSlot(slotId: Int) =
        mailDao.deleteAllForSlot(slotId)

    override suspend fun deleteAllReadAndClaimed(slotId: Int) =
        mailDao.deleteAllReadAndClaimed(slotId)

    override suspend fun deleteExpired(slotId: Int, nowMs: Long) =
        mailDao.deleteExpired(slotId, nowMs)

    override suspend fun deleteMailsWithoutAttachments(slotId: Int) =
        mailDao.deleteMailsWithoutAttachments(slotId)
}
