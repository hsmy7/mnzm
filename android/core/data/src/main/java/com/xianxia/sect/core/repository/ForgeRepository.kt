package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.ForgeSlot
import com.xianxia.sect.data.local.ForgeSlotDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForgeRepositoryImpl @Inject constructor(
    private val forgeSlotDao: ForgeSlotDao
) : ForgeRepository {
    companion object {
        const val DEFAULT_SLOT_ID = 0
    }

    override suspend fun getForgeSlotBySlotIndex(slotIndex: Int, slotId: Int): ForgeSlot? =
        forgeSlotDao.getBySlotIndex(slotId, slotIndex)
}
