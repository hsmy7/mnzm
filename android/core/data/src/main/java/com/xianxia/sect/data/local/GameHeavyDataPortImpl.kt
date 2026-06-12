package com.xianxia.sect.data.local

import com.xianxia.sect.core.model.GameHeavyData
import com.xianxia.sect.core.repository.GameHeavyDataPort
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameHeavyDataPortImpl @Inject constructor(
    private val database: GameDatabase
) : GameHeavyDataPort {

    override suspend fun getLoadedKeys(slot: Int): List<String> =
        database.gameHeavyDataDao().getLoadedKeys(slot)

    override suspend fun getByKey(slot: Int, key: String): GameHeavyData? =
        database.gameHeavyDataDao().getByKey(slot, key)

    override suspend fun deleteByKey(slot: Int, key: String) =
        database.gameHeavyDataDao().deleteByKey(slot, key)
}
