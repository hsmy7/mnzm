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

    override fun getDisciples(): Flow<List<Disciple>> =
        discipleDao.getAll()

    override fun getAliveDisciples(): Flow<List<Disciple>> =
        discipleDao.getAllAlive()

    override suspend fun getDiscipleById(id: String): Disciple? =
        discipleDao.getById(id)

    override suspend fun getDisciplesByStatus(status: DiscipleStatus): List<Disciple> =
        discipleDao.getByStatus(status)

    override suspend fun getAllDisciplesSync(): List<Disciple> =
        discipleDao.getAllAliveSync()
}
