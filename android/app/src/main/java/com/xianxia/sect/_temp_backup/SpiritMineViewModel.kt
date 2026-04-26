package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.GameConfig
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
        return gameEngine.gameData.value.elderSlots.spiritMineDeaconDisciples
    }

    fun getAvailableDisciplesForSpiritMineDeacon(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return gameEngine.discipleAggregates.value
            .filter {
                it.isAlive &&
                it.discipleType == "inner" &&
                it.age >= GameConfig.Disciple.MIN_AGE &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id)
            }
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

                val currentGameData = gameEngine.gameData.value
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
                val currentGameData = gameEngine.gameData.value
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
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        val assignedMiningIds = gameEngine.gameData.value.spiritMineSlots.mapNotNull { it.discipleId }.toSet()

        return gameEngine.discipleAggregates.value
            .filter {
                it.isAlive &&
                it.discipleType == "outer" &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id) &&
                !assignedMiningIds.contains(it.id)
            }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun assignDisciplesToSpiritMineSlots(selectedDisciples: List<DiscipleAggregate>) {
        viewModelScope.launch {
            try {
                assignDisciplesToEmptyMineSlotsInternal(selectedDisciples)
            } catch (e: Exception) {
                showError(e.message ?: "分配失败")
            }
        }
    }

    fun removeDiscipleFromSpiritMineSlot(slotIndex: Int) {
        viewModelScope.launch {
            try {
                val currentGameData = gameEngine.gameData.value
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

    fun autoAssignSpiritMineMiners() {
        viewModelScope.launch {
            try {
                val availableDisciples = getAvailableDisciplesForSpiritMining()
                if (availableDisciples.isEmpty()) return@launch
                assignDisciplesToEmptyMineSlotsInternal(availableDisciples)
            } catch (e: Exception) {
                showError(e.message ?: "一键任命失败")
            }
        }
    }

    private suspend fun assignDisciplesToEmptyMineSlotsInternal(disciples: List<DiscipleAggregate>) {
        val currentGameData = gameEngine.gameData.value
        var currentSlots = currentGameData.spiritMineSlots.toMutableList()
        while (currentSlots.size < GameConfig.Production.MAX_SPIRIT_MINE_SLOTS) {
            currentSlots.add(SpiritMineSlot(index = currentSlots.size))
        }
        val disciplesToAssign = disciples.take(currentSlots.count { it.discipleId.isEmpty() })
        for (disciple in disciplesToAssign) {
            val targetSlotIndex = currentSlots.indexOfFirst { it.discipleId.isEmpty() }
            if (targetSlotIndex == -1) break
            currentSlots[targetSlotIndex] = currentSlots[targetSlotIndex].copy(
                discipleId = disciple.id,
                discipleName = disciple.name
            )
            gameEngine.updateDiscipleStatus(disciple.id, DiscipleStatus.MINING)
        }
        gameEngine.updateSpiritMineSlots(currentSlots)
    }

    private fun ElderSlots.getAllElderIds(): List<String> {
        return elderManagement.run { getAllElderIds() }
    }

    private fun ElderSlots.getAllDirectDiscipleIds(): List<String> {
        return elderManagement.run { getAllDirectDiscipleIds() }
    }
}
