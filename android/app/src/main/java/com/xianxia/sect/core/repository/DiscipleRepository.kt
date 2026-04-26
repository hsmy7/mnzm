package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.data.local.DiscipleDao
import com.xianxia.sect.data.local.DiscipleAttributesDao
import com.xianxia.sect.data.local.DiscipleCombatStatsDao
import com.xianxia.sect.data.local.DiscipleCoreDao
import com.xianxia.sect.data.local.DiscipleEquipmentDao
import com.xianxia.sect.data.local.DiscipleExtendedDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscipleRepository @Inject constructor(
    private val discipleDao: DiscipleDao,
    private val discipleCoreDao: DiscipleCoreDao,
    private val discipleCombatStatsDao: DiscipleCombatStatsDao,
    private val discipleEquipmentDao: DiscipleEquipmentDao,
    private val discipleExtendedDao: DiscipleExtendedDao,
    private val discipleAttributesDao: DiscipleAttributesDao
) {
    companion object {
        const val DEFAULT_SLOT_ID = 0
    }

    fun getDisciples(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Disciple>> =
        discipleDao.getAll(slotId)

    fun getAliveDisciples(slotId: Int = DEFAULT_SLOT_ID): Flow<List<Disciple>> =
        discipleDao.getAllAlive(slotId)

    suspend fun getDiscipleById(id: String, slotId: Int = DEFAULT_SLOT_ID): Disciple? =
        discipleDao.getById(slotId, id)

    suspend fun getDisciplesByStatus(status: DiscipleStatus, slotId: Int = DEFAULT_SLOT_ID): List<Disciple> =
        discipleDao.getByStatus(slotId, status)

    suspend fun getAllDisciplesSync(slotId: Int = DEFAULT_SLOT_ID): List<Disciple> =
        discipleDao.getAllAliveSync(slotId)
}
