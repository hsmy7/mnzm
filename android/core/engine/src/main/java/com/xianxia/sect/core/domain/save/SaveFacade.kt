package com.xianxia.sect.core.engine.domain.save

import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.repository.GameHeavyDataPort
import com.xianxia.sect.core.repository.HeavyDataDecoder



interface SaveFacade {
    val saveService: SaveService
    val heavyDataPort: GameHeavyDataPort
    val heavyDataDecoder: HeavyDataDecoder
    fun getStateSnapshotSync(): GameStateSnapshot
    suspend fun getStateSnapshot(): GameStateSnapshot
    fun validateState(): List<String>
    fun getStateStatistics(): Map<String, Any>
    fun getFormattedGameTime(): String
}
