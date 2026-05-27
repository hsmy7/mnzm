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

    fun assignDisciple(slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            try {
                val data = gameEngine.gameDataSnapshot
                val slots = data.patrolSlots.toMutableList()
                if (slotIndex >= slots.size) return@launch
                val disciple = gameEngine.discipleAggregatesSnapshot.find { it.id == discipleId } ?: return@launch
                slots[slotIndex] = PatrolSlot(
                    index = slotIndex,
                    discipleId = discipleId,
                    discipleName = disciple.name,
                    discipleRealm = disciple.realmName,
                    portraitRes = disciple.portraitRes
                )
                gameEngine.updatePatrolSlots(slots)
            } catch (e: Exception) {
                showError(e.message ?: "任命失败")
            }
        }
    }

    fun removeDisciple(slotIndex: Int) {
        viewModelScope.launch {
            try {
                val data = gameEngine.gameDataSnapshot
                val slots = data.patrolSlots.toMutableList()
                if (slotIndex >= slots.size) return@launch
                slots[slotIndex] = PatrolSlot(index = slotIndex)
                gameEngine.updatePatrolSlots(slots)
            } catch (e: Exception) {
                showError(e.message ?: "卸任失败")
            }
        }
    }

    fun swapDisciple(slotIndex: Int, newDiscipleId: String) {
        viewModelScope.launch {
            try {
                val data = gameEngine.gameDataSnapshot
                val slots = data.patrolSlots.toMutableList()
                if (slotIndex >= slots.size) return@launch
                val disciple = gameEngine.discipleAggregatesSnapshot.find { it.id == newDiscipleId } ?: return@launch
                slots[slotIndex] = PatrolSlot(
                    index = slotIndex,
                    discipleId = newDiscipleId,
                    discipleName = disciple.name,
                    discipleRealm = disciple.realmName,
                    portraitRes = disciple.portraitRes
                )
                gameEngine.updatePatrolSlots(slots)
            } catch (e: Exception) {
                showError(e.message ?: "更换失败")
            }
        }
    }

    fun autoAssign() {
        viewModelScope.launch {
            try {
                val available = getAvailableDisciples()
                if (available.isEmpty()) return@launch
                val data = gameEngine.gameDataSnapshot
                val slots = data.patrolSlots.toMutableList()
                while (slots.size < 10) slots.add(PatrolSlot(index = slots.size))
                var idx = 0
                for (i in 0 until 10) {
                    if (slots[i].discipleId.isEmpty() && idx < available.size) {
                        val d = available[idx]
                        slots[i] = PatrolSlot(
                            index = i,
                            discipleId = d.id,
                            discipleName = d.name,
                            discipleRealm = d.realmName,
                            portraitRes = d.portraitRes
                        )
                        idx++
                    }
                }
                gameEngine.updatePatrolSlots(slots)
            } catch (e: Exception) {
                showError(e.message ?: "一键任命失败")
            }
        }
    }

    fun updatePatrolConfig(config: PatrolConfig) {
        viewModelScope.launch {
            gameEngine.updatePatrolConfig(config)
        }
    }

    fun updateRequireFullStatus(requireFullStatus: Boolean) {
        val current = gameEngine.gameDataSnapshot.patrolConfig
        updatePatrolConfig(current.copy(requireFullStatus = requireFullStatus))
    }
}
