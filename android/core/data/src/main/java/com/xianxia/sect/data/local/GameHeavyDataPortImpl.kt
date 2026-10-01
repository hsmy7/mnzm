package com.xianxia.sect.data.local

import com.xianxia.sect.core.model.GameHeavyData
import com.xianxia.sect.core.repository.GameHeavyDataPort
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameHeavyDataPortImpl @Inject constructor(
    private val database: GameDatabase
) : GameHeavyDataPort {

    override fun getLoadedKeys(): List<String> =
        database.gameHeavyDataDao().getLoadedKeys()

    override fun getByKey(key: String): GameHeavyData? =
        database.gameHeavyDataDao().getByKey(key)

    override fun deleteByKey(key: String) =
        database.gameHeavyDataDao().deleteByKey(key)
}
