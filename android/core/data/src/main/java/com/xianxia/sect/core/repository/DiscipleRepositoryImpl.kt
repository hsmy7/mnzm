package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.data.local.DiscipleDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscipleRepositoryImpl @Inject constructor(
    private val discipleDao: DiscipleDao
) : DiscipleRepository {
    companion object {
        const val DEFAULT_SLOT_ID = 0
    }

    override fun getDisciples(slotId: Int): Flow<List<Disciple>> =
        discipleDao.getAll(slotId)

    override fun getAliveDisciples(slotId: Int): Flow<List<Disciple>> =
        discipleDao.getAllAlive(slotId)

    override suspend fun getDiscipleById(id: String, slotId: Int): Disciple? =
        discipleDao.getById(slotId, id)

    override suspend fun getDisciplesByStatus(status: DiscipleStatus, slotId: Int): List<Disciple> =
        discipleDao.getByStatus(slotId, status)

    override suspend fun getAllDisciplesSync(slotId: Int): List<Disciple> =
        discipleDao.getAllAliveSync(slotId)
}
