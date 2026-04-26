package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.ForgeSlot
import com.xianxia.sect.data.local.ForgeSlotDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForgeRepository @Inject constructor(
    private val forgeSlotDao: ForgeSlotDao
) {
    companion object {
        const val DEFAULT_SLOT_ID = 0
    }

    suspend fun getForgeSlotBySlotIndex(slotIndex: Int, slotId: Int = DEFAULT_SLOT_ID): ForgeSlot? =
        forgeSlotDao.getBySlotIndex(slotId, slotIndex)
}
