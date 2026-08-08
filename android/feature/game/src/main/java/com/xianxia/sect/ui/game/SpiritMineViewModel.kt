package com.xianxia.sect.ui.game

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.assignDirectDisciple
import com.xianxia.sect.core.engine.cancelBloodRefinement
import com.xianxia.sect.core.engine.confirmAssignDisciple
import com.xianxia.sect.core.engine.getDiscipleAggregate
import com.xianxia.sect.core.engine.isDiscipleAssigned
import com.xianxia.sect.core.engine.releaseDiscipleAssignment
import com.xianxia.sect.core.engine.releaseDiscipleFromAllSlotsAtomic
import com.xianxia.sect.core.engine.releaseReflectionDisciple
import com.xianxia.sect.core.engine.syncSingleDiscipleStatus
import com.xianxia.sect.core.engine.updateDiscipleStatus
import com.xianxia.sect.core.engine.updateGameData
import com.xianxia.sect.core.engine.validateAndFixSpiritMineData
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.mining
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import com.xianxia.sect.core.util.DomainLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
        val showAll = gameEngine.gameDataSnapshot.showAllAvailableDisciples

        return gameEngine.discipleAggregatesSnapshot
            .filterByDiscipleStatus(showAll, emptySet(), additionalCheck = {
                it.age >= GameConfig.Disciple.MIN_AGE && it.realmLayer > 0
            })
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun assignSpiritMineDeacon(slotIndex: Int, discipleId: String) {
        gameEngine.launchOnEngine {
            try {
                // 释放旧槽位（自动移除前职务）
                releaseDiscipleForReassignment(discipleId)

                // 通过 ElderManagementUseCase 统一路径分配亲传弟子
                when (elderManagement.assignDirectDisciple(
                    com.xianxia.sect.core.engine.domain.disciple.SLOT_TYPE_SPIRIT_MINE_DEACON,
                    slotIndex,
                    discipleId
                )) {
                    is ElderManagementUseCase.ElderResult.Error ->
                        showError("任命失败")
                    is ElderManagementUseCase.ElderResult.Success -> { /* 继续 */ }
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                DomainLog.e("SpiritMineVM", "任命失败", e)
                showError(e.message ?: "任命失败")
            }
        }
    }

    fun removeSpiritMineDeacon(slotIndex: Int) {
        gameEngine.launchOnEngine {
            try {
                val currentGameData = gameEngine.gameDataSnapshot
                val elderSlots = currentGameData.elderSlots

                val removedDeaconId = elderSlots.spiritMineDeaconDisciples.find { it.index == slotIndex }?.discipleId

                val currentDeacons = elderSlots.spiritMineDeaconDisciples.filter { it.index != slotIndex }
                val updatedElderSlots = elderSlots.copy(spiritMineDeaconDisciples = currentDeacons)
                gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }

                removedDeaconId?.let {
                    gameEngine.releaseDiscipleAssignment(it)
                    gameEngine.updateDiscipleStatus(it, DiscipleStatus.IDLE)
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                DomainLog.e("SpiritMineVM", "卸任失败", e)
                showError(e.message ?: "卸任失败")
            }
        }
    }

    fun validateSpiritMineData() {
        gameEngine.validateAndFixSpiritMineData()
    }

    fun getAvailableDisciplesForSpiritMining(excludeAssigned: Boolean = true): List<DiscipleAggregate> {
        val showAll = gameEngine.gameDataSnapshot.showAllAvailableDisciples

        return gameEngine.discipleAggregatesSnapshot
            .let { if (excludeAssigned) it.filter { d -> !gameEngine.isDiscipleAssigned(d.id) } else it }
            .filterByDiscipleStatus(showAll, emptySet(), additionalCheck = {
                it.age >= GameConfig.Disciple.MIN_AGE && it.realmLayer > 0
            })
            .sortedWith(compareByDescending<DiscipleAggregate> { it.mining }
                .thenBy { it.realm }
                .thenByDescending { it.realmLayer })
    }

    fun assignDisciplesToSpiritMineSlots(selectedDisciples: List<DiscipleAggregate>, mineIndex: Int = 0) {
        gameEngine.launchOnEngine {
            try {
                assignDisciplesToEmptyMineSlotsInternal(selectedDisciples, mineIndex)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                DomainLog.e("SpiritMineVM", "分配失败", e)
                showError(e.message ?: "分配失败")
            }
        }
    }

    fun removeDiscipleFromSpiritMineSlot(slotIndex: Int) {
        gameEngine.launchOnEngine {
            try {
                val currentGameData = gameEngine.gameDataSnapshot
                val currentSlots = currentGameData.spiritMineSlots.toMutableList()

                if (slotIndex < currentSlots.size) {
                    val discipleId = currentSlots[slotIndex].discipleId
                    currentSlots[slotIndex] = currentSlots[slotIndex].copy(
                        discipleId = "",
                        discipleName = "",
                        sectId = currentSlots[slotIndex].sectId
                    )
                    gameEngine.updateGameData { it.copy(spiritMineSlots = currentSlots) }
                    discipleId?.let {
                        gameEngine.releaseDiscipleAssignment(it)
                        gameEngine.updateDiscipleStatus(it, DiscipleStatus.IDLE)
                    }
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                DomainLog.e("SpiritMineVM", "卸任失败", e)
                showError(e.message ?: "卸任失败")
            }
        }
    }


    fun swapSpiritMineDisciple(slotIndex: Int, newDiscipleId: String, mineIndex: Int = 0) {
        gameEngine.launchOnEngine {
            try {
                val targetSlot = SlotRef(
                    category = SlotCategory.SPIRIT_MINE,
                    slotType = "miner:$slotIndex",
                    slotId = "spiritMine_miner_$slotIndex"
                )

                // 释放旧槽位（自动移除前职务）
                releaseDiscipleForReassignment(newDiscipleId)

                val currentGameData = gameEngine.gameDataSnapshot
                val allSlots = currentGameData.spiritMineSlots.toMutableList()
                if (slotIndex < allSlots.size) {
                    val oldDiscipleId = allSlots[slotIndex].discipleId
                    val newName = gameEngine.getDiscipleAggregate(newDiscipleId)?.name ?: ""
                    allSlots[slotIndex] = allSlots[slotIndex].copy(discipleId = newDiscipleId, discipleName = newName, sectId = allSlots[slotIndex].sectId)
                    gameEngine.updateGameData { it.copy(spiritMineSlots = allSlots) }

                    if (oldDiscipleId.isNotEmpty()) {
                        gameEngine.updateDiscipleStatus(oldDiscipleId, DiscipleStatus.IDLE)
                        // 回归：被更换的旧矿工 gate 注册残留，从可用列表"消失"
                        gameEngine.releaseDiscipleAssignment(oldDiscipleId)
                        gameEngine.syncSingleDiscipleStatus(oldDiscipleId)
                    }
                    gameEngine.confirmAssignDisciple(newDiscipleId, targetSlot)
                    gameEngine.updateDiscipleStatus(newDiscipleId, DiscipleStatus.MINING)
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                DomainLog.e("SpiritMineVM", "更换失败", e)
                showError(e.message ?: "更换失败")
            }
        }
    }

    fun autoAssignSpiritMineMiners(mineIndex: Int = 0) {
        gameEngine.launchOnEngine {
            try {
                val availableDisciples = getAvailableDisciplesForSpiritMining()
                if (availableDisciples.isEmpty()) return@launchOnEngine
                assignDisciplesToEmptyMineSlotsInternal(availableDisciples, mineIndex)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                DomainLog.e("SpiritMineVM", "一键任命失败", e)
                showError(e.message ?: "一键任命失败")
            }
        }
    }

    private suspend fun assignDisciplesToEmptyMineSlotsInternal(disciples: List<DiscipleAggregate>, mineIndex: Int = 0) {
        val currentGameData = gameEngine.gameDataSnapshot
        val mineSectId = currentGameData.placedBuildings
            .filter { it.displayName == "灵矿场" }
            .getOrNull(mineIndex)?.sectId ?: ""
        val allSlots = currentGameData.spiritMineSlots.toMutableList()
        val mineStartIndex = mineIndex * 3
        val mineEndIndex = mineStartIndex + 3

        while (allSlots.size < mineEndIndex) {
            allSlots.add(SpiritMineSlot(index = allSlots.size, sectId = mineSectId))
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
                    discipleName = disciple.name,
                    sectId = mineSectId
                )
                // 释放旧槽位
                releaseDiscipleForReassignment(disciple.id)
                assigned++
            }
        }

        // 先保存槽位（suspend，确保写入完成），再逐个更新弟子状态
        gameEngine.updateGameData { it.copy(spiritMineSlots = allSlots) }
        for (offset in 0 until assigned) {
            val disciple = disciplesToAssign[offset]
            val slotRef = SlotRef(
                category = SlotCategory.SPIRIT_MINE,
                slotType = "miner:${mineStartIndex + offset}",
                slotId = "spiritMine_miner_${mineStartIndex + offset}"
            )
            gameEngine.confirmAssignDisciple(disciple.id, slotRef)
            gameEngine.updateDiscipleStatus(disciple.id, DiscipleStatus.MINING)
        }
    }

    /**
     * 释放弟子为其分配新任务。根据当前状态决定释放方式：
     * - REFLECTING（思过中）→ 释放思过
     * - REFINING（血炼中）→ 中止血炼（不返还材料）
     * - 其他状态 → releaseDiscipleFromAllSlotsAtomic
     */
    private suspend fun releaseDiscipleForReassignment(discipleId: String) {
        val status = gameEngine.getDiscipleAggregate(discipleId)?.status ?: return
        when (status) {
            DiscipleStatus.REFLECTING -> {
                gameEngine.releaseReflectionDisciple(discipleId)
            }
            DiscipleStatus.REFINING -> {
                val gd = gameEngine.gameDataSnapshot
                val buildingInstanceId = gd?.activeBloodRefinements?.entries
                    ?.firstOrNull { it.value.discipleId == discipleId }
                    ?.key
                if (buildingInstanceId != null) {
                    gameEngine.cancelBloodRefinement(buildingInstanceId, discipleId)
                } else {
                    gameEngine.releaseDiscipleFromAllSlotsAtomic(discipleId)
                }
            }
            else -> gameEngine.releaseDiscipleFromAllSlotsAtomic(discipleId)
        }
    }
}
