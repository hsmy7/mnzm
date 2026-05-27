package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PatrolTowerViewModel @Inject constructor(
    private val gameEngine: GameEngine
) : BaseViewModel() {

    private fun slotRange(data: GameData, buildingInstanceId: String): IntRange {
        val towers = data.placedBuildings.filter { it.displayName == "巡视楼" }
        val idx = towers.indexOfFirst { it.instanceId == buildingInstanceId }.coerceAtLeast(0)
        val start = idx * 10
        return start until start + 10
    }

    private fun configIndex(data: GameData, buildingInstanceId: String): Int {
        val towers = data.placedBuildings.filter { it.displayName == "巡视楼" }
        return towers.indexOfFirst { it.instanceId == buildingInstanceId }.coerceAtLeast(0)
    }

    fun getConfig(data: GameData, buildingInstanceId: String): PatrolConfig {
        val idx = configIndex(data, buildingInstanceId)
        return data.patrolConfigs.getOrElse(idx) { PatrolConfig() }
    }

    fun getAvailableDisciples(): List<DiscipleAggregate> {
        val assignedIds = gameEngine.gameDataSnapshot.patrolSlots
            .filter { it.discipleId.isNotEmpty() }
            .map { it.discipleId }
            .toSet()

        return gameEngine.discipleAggregatesSnapshot
            .filter { it.isAlive && it.status == DiscipleStatus.IDLE && it.id !in assignedIds }
            .sortedWith(compareBy<DiscipleAggregate> { it.realm }
                .thenByDescending { it.realmLayer })
    }

    fun assignDisciple(buildingInstanceId: String, slotOffset: Int, discipleId: String) {
        viewModelScope.launch {
            try {
                val data = gameEngine.gameDataSnapshot
                val range = slotRange(data, buildingInstanceId)
                val globalIdx = range.first + slotOffset
                val slots = data.patrolSlots.toMutableList()
                while (slots.size <= globalIdx) slots.add(PatrolSlot(index = slots.size))
                val disciple = gameEngine.discipleAggregatesSnapshot.find { it.id == discipleId } ?: return@launch
                slots[globalIdx] = PatrolSlot(
                    index = globalIdx,
                    discipleId = discipleId,
                    discipleName = disciple.name,
                    discipleRealm = disciple.realmName,
                    portraitRes = disciple.portraitRes
                )
                gameEngine.updateDiscipleStatus(discipleId, DiscipleStatus.PATROLLING)
                gameEngine.updatePatrolSlots(slots)
            } catch (e: Exception) {
                showError(e.message ?: "任命失败")
            }
        }
    }

    fun removeDisciple(buildingInstanceId: String, slotOffset: Int) {
        viewModelScope.launch {
            try {
                val data = gameEngine.gameDataSnapshot
                val range = slotRange(data, buildingInstanceId)
                val globalIdx = range.first + slotOffset
                val slots = data.patrolSlots.toMutableList()
                if (globalIdx >= slots.size) return@launch
                val removedId = slots[globalIdx].discipleId
                slots[globalIdx] = PatrolSlot(index = globalIdx)
                if (removedId.isNotEmpty()) gameEngine.updateDiscipleStatus(removedId, DiscipleStatus.IDLE)
                gameEngine.updatePatrolSlots(slots)
            } catch (e: Exception) {
                showError(e.message ?: "卸任失败")
            }
        }
    }

    fun swapDisciple(buildingInstanceId: String, slotOffset: Int, newDiscipleId: String) {
        viewModelScope.launch {
            try {
                val data = gameEngine.gameDataSnapshot
                val range = slotRange(data, buildingInstanceId)
                val globalIdx = range.first + slotOffset
                val slots = data.patrolSlots.toMutableList()
                if (globalIdx >= slots.size) return@launch
                val oldId = slots[globalIdx].discipleId
                val disciple = gameEngine.discipleAggregatesSnapshot.find { it.id == newDiscipleId } ?: return@launch
                slots[globalIdx] = PatrolSlot(
                    index = globalIdx,
                    discipleId = newDiscipleId,
                    discipleName = disciple.name,
                    discipleRealm = disciple.realmName,
                    portraitRes = disciple.portraitRes
                )
                if (oldId.isNotEmpty()) gameEngine.updateDiscipleStatus(oldId, DiscipleStatus.IDLE)
                gameEngine.updateDiscipleStatus(newDiscipleId, DiscipleStatus.PATROLLING)
                gameEngine.updatePatrolSlots(slots)
            } catch (e: Exception) {
                showError(e.message ?: "更换失败")
            }
        }
    }

    fun autoAssign(buildingInstanceId: String) {
        viewModelScope.launch {
            try {
                val available = getAvailableDisciples()
                if (available.isEmpty()) return@launch
                val data = gameEngine.gameDataSnapshot
                val range = slotRange(data, buildingInstanceId)
                val slots = data.patrolSlots.toMutableList()
                while (slots.size <= range.last) slots.add(PatrolSlot(index = slots.size))
                var idx = 0
                for (i in range) {
                    if (slots[i].discipleId.isEmpty() && idx < available.size) {
                        val d = available[idx]
                        slots[i] = PatrolSlot(
                            index = i,
                            discipleId = d.id,
                            discipleName = d.name,
                            discipleRealm = d.realmName,
                            portraitRes = d.portraitRes
                        )
                        gameEngine.updateDiscipleStatus(d.id, DiscipleStatus.PATROLLING)
                        idx++
                    }
                }
                gameEngine.updatePatrolSlots(slots)
            } catch (e: Exception) {
                showError(e.message ?: "一键任命失败")
            }
        }
    }

    fun updatePatrolConfig(buildingInstanceId: String, config: PatrolConfig) {
        viewModelScope.launch {
            val data = gameEngine.gameDataSnapshot
            val idx = configIndex(data, buildingInstanceId)
            val configs = data.patrolConfigs.toMutableList()
            while (configs.size <= idx) configs.add(PatrolConfig())
            configs[idx] = config
            gameEngine.updatePatrolConfigs(configs)
        }
    }

    fun updateRequireFullStatus(buildingInstanceId: String, requireFullStatus: Boolean) {
        val data = gameEngine.gameDataSnapshot
        val config = getConfig(data, buildingInstanceId)
        updatePatrolConfig(buildingInstanceId, config.copy(requireFullStatus = requireFullStatus))
    }
}
