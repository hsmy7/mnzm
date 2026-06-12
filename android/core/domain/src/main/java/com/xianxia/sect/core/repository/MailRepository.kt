package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.MailEntity
import kotlinx.coroutines.flow.Flow

/**
 * Mail persistence interface — defined in domain, implemented in data module.
 * Engine's MailService depends on this interface, not on MailDao directly.
 */
interface MailRepository {

    fun getActiveMails(slotId: Int, nowMs: Long): Flow<List<MailEntity>>

    fun getUnreadCount(slotId: Int, nowMs: Long): Flow<Int>

    suspend fun getById(mailId: String): MailEntity?

    suspend fun existsByRemoteId(remoteId: String): Boolean

    suspend fun insertWithEnforceLimit(entity: MailEntity, maxPerSlot: Int)

    suspend fun update(entity: MailEntity)

    suspend fun deleteById(mailId: String)

    suspend fun deleteAllForSlot(slotId: Int)

    suspend fun deleteAllReadAndClaimed(slotId: Int)

    suspend fun deleteExpired(slotId: Int, nowMs: Long)

    suspend fun deleteMailsWithoutAttachments(slotId: Int)
}
