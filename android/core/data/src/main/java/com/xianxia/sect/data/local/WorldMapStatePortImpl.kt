package com.xianxia.sect.data.local

import com.xianxia.sect.core.model.WorldMapStateEntity
import com.xianxia.sect.core.repository.WorldMapStatePort
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorldMapStatePortImpl @Inject constructor(
    private val database: GameDatabase
) : WorldMapStatePort {

    override suspend fun getBySlot(slot: Int): WorldMapStateEntity? {
        return database.worldMapStateDao().getBySlot(slot)
    }
}
