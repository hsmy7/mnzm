package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.StorageBagItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class DiscipleDelegate(
    private val gameEngine: GameEngine,
    private val scope: CoroutineScope
) {

    // 招募相关，防止重复点击
    private val recruitingDiscipleIds = mutableSetOf<String>()

    fun expelDisciple(discipleId: String) {
        scope.launch { gameEngine.expelDisciple(discipleId) }
    }

    fun toggleFollowDisciple(discipleId: String) {
        scope.launch {
            gameEngine.updateDisciple(discipleId) { disciple ->
                val currentFollowed = disciple.statusData["followed"] == "true"
                val newStatusData = disciple.statusData.toMutableMap().apply {
                    if (currentFollowed) remove("followed") else this["followed"] = "true"
                }
                disciple.copy(statusData = newStatusData)
            }
        }
    }

    fun changeDiscipleType(discipleId: String, newType: String) {
        scope.launch {
            gameEngine.changeDiscipleTypeAtomic(discipleId, newType)
        }
    }

    fun toggleAutoEquipFromWarehouse(discipleId: String, enabled: Boolean) {
        scope.launch {
            gameEngine.updateDisciple(discipleId) { disciple ->
                disciple.copyWith(autoEquipFromWarehouse = enabled)
            }
        }
    }

    fun toggleAutoLearnFromWarehouse(discipleId: String, enabled: Boolean) {
        scope.launch {
            gameEngine.updateDisciple(discipleId) { disciple ->
                disciple.copyWith(autoLearnFromWarehouse = enabled)
            }
        }
    }

    suspend fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>) {
        gameEngine.rewardItemsToDisciple(discipleId, items)
    }

    fun confiscateStorageBagItem(discipleId: String, item: StorageBagItem) {
        scope.launch {
            try {
                gameEngine.confiscateStorageBagItem(discipleId, item)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun equipItem(discipleId: String, equipmentId: String) {
        scope.launch {
            try {
                gameEngine.equipItem(discipleId, equipmentId)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun unequipItem(discipleId: String, slot: EquipmentSlot) {
        scope.launch {
            try {
                gameEngine.unequipItem(discipleId, slot)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun unequipItem(discipleId: String, equipmentId: String) {
        scope.launch {
            try {
                gameEngine.unequipItemById(discipleId, equipmentId)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun forgetManual(discipleId: String, instanceId: String) {
        scope.launch {
            try {
                gameEngine.forgetManual(discipleId, instanceId)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun replaceManual(discipleId: String, oldInstanceId: String, newStackId: String) {
        scope.launch {
            try {
                gameEngine.replaceManual(discipleId, oldInstanceId, newStackId)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun learnManual(discipleId: String, stackId: String) {
        scope.launch {
            try {
                gameEngine.learnManual(discipleId, stackId)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun usePill(discipleId: String, pillId: String) {
        scope.launch {
            try {
                gameEngine.usePill(discipleId, pillId)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun usePill(discipleId: String, pill: Pill) {
        scope.launch {
            try {
                gameEngine.usePill(discipleId, pill.id)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) {
        scope.launch {
            try {
                gameEngine.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun recruitDisciple() {
        scope.launch {
            try {
                gameEngine.recruitDisciple()
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun recruitDiscipleFromList(discipleId: String) {
        if (recruitingDiscipleIds.contains(discipleId)) return
        recruitingDiscipleIds.add(discipleId)
        scope.launch {
            try {
                gameEngine.recruitDiscipleFromList(discipleId)
            } finally {
                recruitingDiscipleIds.remove(discipleId)
            }
        }
    }

    fun recruitAllDisciples() {
        scope.launch {
            try {
                gameEngine.recruitAllFromList()
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun rejectDiscipleFromList(discipleId: String) {
        scope.launch {
            gameEngine.removeFromRecruitList(discipleId)
        }
    }

    fun recruitDisciple(disciple: DiscipleAggregate) {
        if (recruitingDiscipleIds.contains(disciple.id)) return
        recruitingDiscipleIds.add(disciple.id)
        scope.launch {
            try {
                gameEngine.recruitDiscipleFromList(disciple.id)
            } finally {
                recruitingDiscipleIds.remove(disciple.id)
            }
        }
    }

    fun expelTheftDisciple(discipleId: String) {
        scope.launch {
            gameEngine.expelTheftDisciple(discipleId)
            gameEngine.clearPendingNotification()
        }
    }

    suspend fun imprisonTheftDisciple(discipleId: String, currentYear: Int) {
        gameEngine.imprisonTheftDisciple(discipleId, currentYear)
        gameEngine.clearPendingNotification()
    }

    suspend fun releaseTheftDisciple(discipleId: String): Int {
        return gameEngine.releaseTheftDisciple(discipleId)
    }

    fun onLoyaltyDialogDismissed() {
        gameEngine.clearPendingNotification()
    }

    fun getDiscipleById(id: String): DiscipleAggregate? {
        return gameEngine.discipleAggregates.value.find { it.id == id }
    }

    fun setAutoRecruitFilter(filter: Set<Int>) {
        scope.launch {
            gameEngine.updateGameData { gd ->
                gd.copy(autoRecruitSpiritRootFilter = filter)
            }
        }
    }
}
