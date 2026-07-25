package com.xianxia.sect.di

import com.xianxia.sect.core.repository.SaveSnapshot
import com.xianxia.sect.core.repository.SaveStorage
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge implementation of [SaveStorage] — lives in :app module,
 * converts domain [SaveSnapshot] to data [SaveData] and delegates to [StorageFacade].
 */
@Singleton
class SaveStorageImpl @Inject constructor(
    private val storageFacade: StorageFacade
) : SaveStorage {

    override suspend fun save(slot: Int, snapshot: SaveSnapshot): Boolean {
        val saveData = SaveData(
            gameData = snapshot.gameData.copy(currentSlot = slot),
            disciples = snapshot.disciples,
            equipmentStacks = snapshot.equipmentStacks,
            equipmentInstances = snapshot.equipmentInstances,
            manualStacks = snapshot.manualStacks,
            manualInstances = snapshot.manualInstances,
            pills = snapshot.pills,
            materials = snapshot.materials,
            herbs = snapshot.herbs,
            seeds = snapshot.seeds,
            teams = snapshot.teams,
            battleLogs = snapshot.battleLogs,
            alliances = snapshot.alliances,
            productionSlots = snapshot.productionSlots,
            storageBags = snapshot.storageBags
        )
        val result = storageFacade.save(slot, saveData)
        return result.isSuccess
    }

    override suspend fun load(slot: Int): SaveSnapshot? {
        // Loaded via different path (StorageFacade.load) — return null for now
        // Full load implementation handled by existing save/load system
        return null
    }

    override suspend fun delete(slot: Int) {
        storageFacade.delete(slot)
    }
}
