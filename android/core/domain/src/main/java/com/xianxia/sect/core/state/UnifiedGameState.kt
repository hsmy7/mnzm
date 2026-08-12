package com.xianxia.sect.core.state

import androidx.compose.runtime.Immutable
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag



@Immutable
data class UnifiedGameState(
    val gameData: GameData = GameData(),
    val disciples: List<Disciple> = emptyList(),
    val equipmentStacks: List<EquipmentStack> = emptyList(),
    val equipmentInstances: List<EquipmentInstance> = emptyList(),
    val manualStacks: List<ManualStack> = emptyList(),
    val manualInstances: List<ManualInstance> = emptyList(),
    val pills: List<Pill> = emptyList(),
    val materials: List<Material> = emptyList(),
    val herbs: List<Herb> = emptyList(),
    val seeds: List<Seed> = emptyList(),
    val storageBags: List<StorageBag> = emptyList(),
    val battleLogs: List<BattleLog> = emptyList(),
    val alliances: List<Alliance> = emptyList(),
    val isPaused: Boolean = true,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val gameSpeed: Int = 1,
    val pendingBattleResult: BattleResultUIData? = null,
    val pendingNotification: GameNotification? = null,
    val lastUpdateTime: Long = System.currentTimeMillis(),
    val version: Int = 1
) {
    val isRunning: Boolean get() = !isPaused && !isLoading && !isSaving
    val aliveDisciples: List<Disciple> get() = disciples.filter { it.isAlive }
    val idleDisciples: List<Disciple> get() = disciples.filter { it.isAlive && it.status == DiscipleStatus.IDLE }
    val workingDisciples: List<Disciple> get() = disciples.filter { it.isAlive && it.status != DiscipleStatus.IDLE }
    fun getDiscipleById(id: String): Disciple? = disciples.find { it.id == id }
    fun getEquipmentById(id: String): EquipmentInstance? = equipmentInstances.find { it.id == id }
    fun getManualById(id: String): ManualInstance? = manualInstances.find { it.id == id }
    fun getEquipmentByOwner(discipleId: String): List<EquipmentInstance> = equipmentInstances.filter { it.ownerId == discipleId }
    fun getManualsByOwner(discipleId: String): List<ManualInstance> = manualInstances.filter { it.ownerId == discipleId }
}
