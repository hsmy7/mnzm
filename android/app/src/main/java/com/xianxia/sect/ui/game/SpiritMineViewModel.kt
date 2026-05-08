package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpiritMineViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val elderManagement: ElderManagementUseCase
) : BaseViewModel() {

    fun getSpiritMineDeaconDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameDataSnapshot.elderSlots.spiritMineDeaconDisciples
    }

    fun getAvailableDisciplesForSpiritMineDeacon(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameDataSnapshot.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return gameEngine.discipleAggregatesSnapshot
            .filter { it.isEligibleForInnerPosition && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun assignSpiritMineDeacon(slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = gameEngine.disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    showError("弟子不存在")
                    return@launch
                }

                if (disciple.discipleType != "inner") {
                    showError("执事只能由内门弟子担任")
                    return@launch
                }

                val currentGameData = gameEngine.gameDataSnapshot
                val elderSlots = currentGameData.elderSlots

                val allElderIds = elderSlots.getAllElderIds()
                val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

                if (allElderIds.contains(discipleId)) {
                    showError("该弟子已担任长老职位")
                    return@launch
                }

                if (allDirectDiscipleIds.contains(discipleId)) {
                    showError("该弟子已是其他长老的亲传弟子")
                    return@launch
                }

                val currentDeacons = elderSlots.spiritMineDeaconDisciples.toMutableList()
                val existingSlot = currentDeacons.find { it.index == slotIndex }

                val newSlot = DirectDiscipleSlot(
                    index = slotIndex,
                    discipleId = discipleId,
                    discipleName = disciple.name,
                    discipleRealm = disciple.realmName,
                    discipleSpiritRootColor = disciple.spiritRoot.countColor
                )

                if (existingSlot != null) {
                    currentDeacons[currentDeacons.indexOf(existingSlot)] = newSlot
                } else {
                    currentDeacons.add(newSlot)
                }

                val updatedElderSlots = elderSlots.copy(spiritMineDeaconDisciples = currentDeacons)
                gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }

                gameEngine.updateDiscipleStatus(discipleId, DiscipleStatus.DEACONING)
            } catch (e: Exception) {
                showError(e.message ?: "任命失败")
            }
        }
    }

    fun removeSpiritMineDeacon(slotIndex: Int) {
        viewModelScope.launch {
            try {
                val currentGameData = gameEngine.gameDataSnapshot
                val elderSlots = currentGameData.elderSlots

                val removedDeaconId = elderSlots.spiritMineDeaconDisciples.find { it.index == slotIndex }?.discipleId

                val currentDeacons = elderSlots.spiritMineDeaconDisciples.filter { it.index != slotIndex }
                val updatedElderSlots = elderSlots.copy(spiritMineDeaconDisciples = currentDeacons)
                gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }

                removedDeaconId?.let { gameEngine.updateDiscipleStatus(it, DiscipleStatus.IDLE) }
            } catch (e: Exception) {
                showError(e.message ?: "卸任失败")
            }
        }
    }

    fun validateSpiritMineData() {
        gameEngine.validateAndFixSpiritMineData()
    }

    fun getAvailableDisciplesForSpiritMining(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameDataSnapshot.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        val assignedMiningIds = gameEngine.gameDataSnapshot.spiritMineSlots.mapNotNull { it.discipleId }.toSet()

        return gameEngine.discipleAggregatesSnapshot
            .filter { it.isEligibleForOuterPosition && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) && !assignedMiningIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun assignDisciplesToSpiritMineSlots(selectedDisciples: List<DiscipleAggregate>, mineIndex: Int = 0) {
        viewModelScope.launch {
            try {
                assignDisciplesToEmptyMineSlotsInternal(selectedDisciples, mineIndex)
            } catch (e: Exception) {
                showError(e.message ?: "分配失败")
            }
        }
    }

    fun removeDiscipleFromSpiritMineSlot(slotIndex: Int) {
        viewModelScope.launch {
            try {
                val currentGameData = gameEngine.gameDataSnapshot
                val currentSlots = currentGameData.spiritMineSlots.toMutableList()

                if (slotIndex < currentSlots.size) {
                    val discipleId = currentSlots[slotIndex].discipleId
                    discipleId?.let {
                        gameEngine.updateDiscipleStatus(it, DiscipleStatus.IDLE)
                    }

                    currentSlots[slotIndex] = currentSlots[slotIndex].copy(
                        discipleId = "",
                        discipleName = ""
                    )
                    gameEngine.updateSpiritMineSlots(currentSlots)
                }
            } catch (e: Exception) {
                showError(e.message ?: "卸任失败")
            }
        }
    }


    fun autoAssignSpiritMineMiners(mineIndex: Int = 0) {
        viewModelScope.launch {
            try {
                val availableDisciples = getAvailableDisciplesForSpiritMining()
                if (availableDisciples.isEmpty()) return@launch
                assignDisciplesToEmptyMineSlotsInternal(availableDisciples, mineIndex)
            } catch (e: Exception) {
                showError(e.message ?: "一键任命失败")
            }
        }
    }

    private suspend fun assignDisciplesToEmptyMineSlotsInternal(disciples: List<DiscipleAggregate>, mineIndex: Int = 0) {
        val currentGameData = gameEngine.gameDataSnapshot
        val allSlots = currentGameData.spiritMineSlots.toMutableList()
        val mineStartIndex = mineIndex * 3
        val mineEndIndex = mineStartIndex + 3

        // Ensure slot list is long enough
        while (allSlots.size < mineEndIndex) {
            allSlots.add(SpiritMineSlot(index = allSlots.size))
        }

        val emptyCount = (mineStartIndex until mineEndIndex).count { allSlots[it].discipleId.isEmpty() }
        val disciplesToAssign = disciples.take(emptyCount)

        var assigned = 0
        for (offset in 0 until 3) {
            if (assigned >= disciplesToAssign.size) break
            val globalIndex = mineStartIndex + offset
            if (allSlots[globalIndex].discipleId.isEmpty()) {
                val disciple = disciplesToAssign[assigned]
                allSlots[globalIndex] = allSlots[globalIndex].copy(
                    discipleId = disciple.id,
                    discipleName = disciple.name
                )
                gameEngine.updateDiscipleStatus(disciple.id, DiscipleStatus.MINING)
                assigned++
            }
        }

        gameEngine.updateSpiritMineSlots(allSlots)
    }

    private fun ElderSlots.getAllElderIds(): List<String> {
        return elderManagement.run { getAllElderIds() }
    }

    private fun ElderSlots.getAllDirectDiscipleIds(): List<String> {
        return elderManagement.run { getAllDirectDiscipleIds() }
    }
}
