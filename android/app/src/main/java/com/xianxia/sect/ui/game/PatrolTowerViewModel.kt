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

    fun getTowerIndex(buildingInstanceId: String): Int {
        val towers = gameEngine.gameDataSnapshot.placedBuildings
            .filter { it.displayName == "巡视楼" }
        return towers.indexOfFirst { it.instanceId == buildingInstanceId }.coerceAtLeast(0)
    }

    fun slotRange(towerIndex: Int): IntRange = (towerIndex * 10) until (towerIndex * 10 + 10)

    fun getAvailableDisciples(towerIndex: Int): List<DiscipleAggregate> {
        val range = slotRange(towerIndex)
        val assignedIds = gameEngine.gameDataSnapshot.patrolSlots
            .filter { it.discipleId.isNotEmpty() && it.index in range }
            .map { it.discipleId }.toSet()

        return gameEngine.discipleAggregatesSnapshot
            .filter { it.isAlive && it.status == DiscipleStatus.IDLE && it.id !in assignedIds }
            .sortedWith(compareBy<DiscipleAggregate> { it.realm }
                .thenByDescending { it.realmLayer })
    }

    fun assignDisciple(towerIndex: Int, slotOffset: Int, discipleId: String) {
        viewModelScope.launch {
            try {
                val data = gameEngine.gameDataSnapshot
                val slots = data.patrolSlots.toMutableList()
                val globalIndex = towerIndex * 10 + slotOffset
                while (slots.size <= globalIndex) slots.add(PatrolSlot(index = slots.size))
                val disciple = gameEngine.discipleAggregatesSnapshot.find { it.id == discipleId } ?: return@launch
                slots[globalIndex] = PatrolSlot(
                    index = globalIndex,
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

    fun removeDisciple(towerIndex: Int, slotOffset: Int) {
        viewModelScope.launch {
            try {
                val data = gameEngine.gameDataSnapshot
                val slots = data.patrolSlots.toMutableList()
                val globalIndex = towerIndex * 10 + slotOffset
                if (globalIndex >= slots.size) return@launch
                val removedId = slots[globalIndex].discipleId
                slots[globalIndex] = PatrolSlot(index = globalIndex)
                if (removedId.isNotEmpty()) gameEngine.updateDiscipleStatus(removedId, DiscipleStatus.IDLE)
                gameEngine.updatePatrolSlots(slots)
            } catch (e: Exception) {
                showError(e.message ?: "卸任失败")
            }
        }
    }

    fun swapDisciple(towerIndex: Int, slotOffset: Int, newDiscipleId: String) {
        viewModelScope.launch {
            try {
                val data = gameEngine.gameDataSnapshot
                val slots = data.patrolSlots.toMutableList()
                val globalIndex = towerIndex * 10 + slotOffset
                if (globalIndex >= slots.size) return@launch
                val oldId = slots[globalIndex].discipleId
                val disciple = gameEngine.discipleAggregatesSnapshot.find { it.id == newDiscipleId } ?: return@launch
                slots[globalIndex] = PatrolSlot(
                    index = globalIndex,
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

    fun autoAssign(towerIndex: Int) {
        viewModelScope.launch {
            try {
                val available = getAvailableDisciples(towerIndex)
                if (available.isEmpty()) return@launch
                val data = gameEngine.gameDataSnapshot
                val slots = data.patrolSlots.toMutableList()
                val start = towerIndex * 10
                val end = start + 10
                while (slots.size < end) slots.add(PatrolSlot(index = slots.size))
                var idx = 0
                for (i in start until end) {
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

    fun updatePatrolConfig(towerIndex: Int, config: PatrolConfig) {
        viewModelScope.launch {
            val configs = gameEngine.gameDataSnapshot.patrolConfigs.toMutableList()
            while (configs.size <= towerIndex) configs.add(PatrolConfig())
            configs[towerIndex] = config
            gameEngine.updatePatrolConfigs(configs)
        }
    }

    fun getPatrolConfig(towerIndex: Int): PatrolConfig {
        val configs = gameEngine.gameDataSnapshot.patrolConfigs
        return configs.getOrElse(towerIndex) { PatrolConfig() }
    }

    fun updateRequireFullStatus(towerIndex: Int, requireFullStatus: Boolean) {
        val current = getPatrolConfig(towerIndex)
        updatePatrolConfig(towerIndex, current.copy(requireFullStatus = requireFullStatus))
    }
}
