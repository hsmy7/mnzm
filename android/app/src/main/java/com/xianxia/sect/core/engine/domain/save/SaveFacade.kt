package com.xianxia.sect.core.engine.domain.save

import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.model.*

interface SaveFacade {
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
        battleLogs: List<BattleLog>,
        teams: List<ExplorationTeam>
    )
    fun validateState(): List<String>
    fun getStateStatistics(): Map<String, Any>
    fun isGameStarted(): Boolean
    fun getFormattedGameTime(): String
}
