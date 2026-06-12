package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.production.ProductionSlot

/**
 * Save snapshot — domain-level representation of a complete game state.
 * Used by SaveStorage to persist/restore game state without depending on data-module types.
 */
data class SaveSnapshot(
    val gameData: GameData,
    val disciples: List<Disciple>,
    val equipmentStacks: List<EquipmentStack>,
    val equipmentInstances: List<EquipmentInstance>,
    val manualStacks: List<ManualStack>,
    val manualInstances: List<ManualInstance>,
    val pills: List<Pill>,
    val materials: List<Material>,
    val herbs: List<Herb>,
    val seeds: List<Seed>,
    val teams: List<ExplorationTeam>,
    val battleLogs: List<com.xianxia.sect.core.model.BattleLog>,
    val alliances: List<com.xianxia.sect.core.model.Alliance>,
    val productionSlots: List<ProductionSlot>,
)

/**
 * Save storage port — defined in domain, implemented in data module.
 * SavePipeline depends on this interface, not on StorageFacade or SaveData directly.
 */
interface SaveStorage {
    suspend fun save(slot: Int, snapshot: SaveSnapshot): Boolean
    suspend fun load(slot: Int): SaveSnapshot?
    suspend fun delete(slot: Int)
}
