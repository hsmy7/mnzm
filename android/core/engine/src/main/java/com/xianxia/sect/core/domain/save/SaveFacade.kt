package com.xianxia.sect.core.engine.domain.save

import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.repository.GameHeavyDataPort
import com.xianxia.sect.core.repository.HeavyDataDecoder
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed



interface SaveFacade {
    val saveService: SaveService
    val heavyDataPort: GameHeavyDataPort
    val heavyDataDecoder: HeavyDataDecoder
    fun getStateSnapshotSync(): GameStateSnapshot
    suspend fun getStateSnapshot(): GameStateSnapshot
    suspend fun loadFromSave(
        loadedGameData: GameData,
        disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack>,
        equipmentInstances: List<EquipmentInstance>,
        manualStacks: List<ManualStack>,
        manualInstances: List<ManualInstance>,
        pills: List<Pill>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>,
        battleLogs: List<BattleLog>
    )
    fun validateState(): List<String>
    fun getStateStatistics(): Map<String, Any>
    fun getFormattedGameTime(): String
}
