package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PatrolTowerViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val buildingConfigService: BuildingConfigService
) : BaseViewModel() {

    val slotsPerTower: Int get() = buildingConfigService.getSlotCountByDisplayName("巡视楼")

    fun getTowerIndex(buildingInstanceId: String): Int {
        val towers = gameEngine.gameDataSnapshot.placedBuildings
            .filter { it.displayName == "巡视楼" }
        return towers.indexOfFirst { it.instanceId == buildingInstanceId }.coerceAtLeast(0)
    }

    fun slotRange(towerIndex: Int): IntRange = (towerIndex * slotsPerTower) until (towerIndex * slotsPerTower + slotsPerTower)

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
                val globalIndex = towerIndex * slotsPerTower + slotOffset
                while (slots.size <= globalIndex) slots.add(PatrolSlot(index = slots.size))
                val disciple = gameEngine.discipleAggregatesSnapshot.find { it.id == discipleId } ?: return@launch
                slots[globalIndex] = PatrolSlot(
                    index = globalIndex,
                    discipleId = discipleId,
                    discipleName = disciple.name,
                    discipleRealm = disciple.realmName,
                    portraitRes = disciple.portraitRes
                )
                // 先保存槽位(suspend，确保写入)，再更新弟子状态
                gameEngine.updateGameData { it.copy(patrolSlots = slots) }
                gameEngine.updateDiscipleStatus(discipleId, DiscipleStatus.PATROLLING)
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
                val globalIndex = towerIndex * slotsPerTower + slotOffset
                if (globalIndex >= slots.size) return@launch
                val removedId = slots[globalIndex].discipleId
                slots[globalIndex] = PatrolSlot(index = globalIndex)
                // 先保存槽位(suspend，确保写入)，再清除弟子状态
                gameEngine.updateGameData { it.copy(patrolSlots = slots) }
                if (removedId.isNotEmpty()) gameEngine.updateDiscipleStatus(removedId, DiscipleStatus.IDLE)
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
                val globalIndex = towerIndex * slotsPerTower + slotOffset
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
                // 先保存槽位(suspend，确保写入)，再更新弟子状态
                gameEngine.updateGameData { it.copy(patrolSlots = slots) }
                if (oldId.isNotEmpty()) gameEngine.updateDiscipleStatus(oldId, DiscipleStatus.IDLE)
                gameEngine.updateDiscipleStatus(newDiscipleId, DiscipleStatus.PATROLLING)
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
                val start = towerIndex * slotsPerTower
                val end = start + slotsPerTower
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
                        idx++
                    }
                }
                // 先保存槽位(suspend，确保写入)，再逐个更新弟子状态
                gameEngine.updateGameData { it.copy(patrolSlots = slots) }
                for (i in 0 until idx) {
                    gameEngine.updateDiscipleStatus(available[i].id, DiscipleStatus.PATROLLING)
                }
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
