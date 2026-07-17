package com.xianxia.sect.ui.game.delegate

import android.util.Log
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.StorageBagItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class DiscipleDelegate(
    private val gameEngine: GameEngine,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DiscipleDelegate"
    }

    // 招募相关，防止重复点击
    private val recruitingDiscipleIds = mutableSetOf<String>()
    private val recruitingLock = Any()
    @Volatile private var isRecruitingAll = false

    fun expelDisciple(discipleId: String) {
        scope.launch { gameEngine.expelDisciple(discipleId) }
    }

    /** 拜师：将 discipleId 设为 masterId 的徒弟 */
    fun apprenticeToMaster(discipleId: String, masterId: String) {
        scope.launch { gameEngine.apprenticeToMaster(discipleId, masterId) }
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

    /** 观看广告后为弟子添加一次性突破率加成 */
    fun applyAdBreakthroughBonus(discipleId: String, bonus: Double) {
        scope.launch {
            gameEngine.updateDisciple(discipleId) { disciple ->
                val currentBonus = disciple.statusData["adBreakthroughBonus"]?.toDoubleOrNull() ?: 0.0
                val newStatusData = disciple.statusData.toMutableMap().apply {
                    this["adBreakthroughBonus"] = (currentBonus + bonus).toString()
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
                disciple.copy(equipment = disciple.equipment.copy(autoEquipFromWarehouse = enabled))
            }
        }
    }

    fun toggleAutoLearnFromWarehouse(discipleId: String, enabled: Boolean) {
        scope.launch {
            gameEngine.updateDisciple(discipleId) { disciple ->
                disciple.copy(autoLearnFromWarehouse = enabled)
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
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "confiscateStorageBagItem failed", e)
            }
        }
    }

    fun equipItem(discipleId: String, equipmentId: String) {
        scope.launch {
            try {
                val result = gameEngine.equipItem(discipleId, equipmentId)
                if (result is DomainResult.Failure) {
                    android.util.Log.w(
                        "DiscipleDelegate",
                        "equipItem failed: disciple=$discipleId" +
                            " equipment=$equipmentId" +
                            " error=${result.error.message}"
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun unequipItem(discipleId: String, slot: EquipmentSlot) {
        scope.launch {
            try {
                val result = gameEngine.unequipItem(discipleId, slot)
                if (result == null) return@launch // disciple not found or slot empty
                if (result is DomainResult.Failure) {
                    android.util.Log.w(
                        "DiscipleDelegate",
                        "unequipItem(slot) failed: disciple=$discipleId" +
                            " slot=$slot error=${result.error.message}"
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun unequipItem(discipleId: String, equipmentId: String) {
        scope.launch {
            try {
                val result = gameEngine.unequipItemById(discipleId, equipmentId)
                if (result is DomainResult.Failure) {
                    android.util.Log.w(
                        "DiscipleDelegate",
                        "unequipItem(id) failed: disciple=$discipleId" +
                            " equipment=$equipmentId" +
                            " error=${result.error.message}"
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun forgetManual(discipleId: String, instanceId: String) {
        scope.launch {
            try {
                gameEngine.forgetManual(discipleId, instanceId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun replaceManual(discipleId: String, oldInstanceId: String, newStackId: String) {
        scope.launch {
            try {
                gameEngine.replaceManual(discipleId, oldInstanceId, newStackId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun learnManual(discipleId: String, stackId: String) {
        scope.launch {
            try {
                gameEngine.learnManual(discipleId, stackId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun usePill(discipleId: String, pillId: String) {
        scope.launch {
            try {
                gameEngine.usePill(discipleId, pillId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun usePill(discipleId: String, pill: Pill) {
        scope.launch {
            try {
                gameEngine.usePill(discipleId, pill.id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) {
        scope.launch {
            try {
                gameEngine.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun renameDisciple(discipleId: String, newName: String) {
        scope.launch {
            try {
                gameEngine.updateDisciple(discipleId) { disciple ->
                    disciple.copy(name = newName)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun recruitDiscipleFromList(discipleId: String) {
        if (discipleId.isBlank()) {
            Log.w(TAG, "recruitDiscipleFromList: skipped (empty id)")
            return
        }
        if (isRecruitingAll) {
            Log.w(TAG, "recruitDiscipleFromList: skipped (isRecruitingAll=true) for $discipleId")
            return
        }
        if (recruitingDiscipleIds.contains(discipleId)) {
            Log.w(TAG, "recruitDiscipleFromList: skipped (duplicate) for $discipleId")
            return
        }
        recruitingDiscipleIds.add(discipleId)
        scope.launch {
            try {
                Log.d(TAG, "recruitDiscipleFromList: launching for $discipleId")
                val newId = gameEngine.recruitDiscipleFromList(discipleId)
                if (newId.isEmpty()) {
                    Log.w(TAG, "recruitDiscipleFromList: failed for $discipleId")
                } else {
                    Log.d(TAG, "recruitDiscipleFromList: success id=$newId for $discipleId")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "recruitDiscipleFromList: exception for $discipleId", e)
            } finally {
                recruitingDiscipleIds.remove(discipleId)
            }
        }
    }

    fun recruitAllDisciples() {
        if (isRecruitingAll) return
        synchronized(recruitingLock) {
            if (recruitingDiscipleIds.isNotEmpty()) return
            isRecruitingAll = true
        }
        scope.launch {
            try {
                val count = gameEngine.recruitAllFromList()
                Log.d(TAG, "recruitAllDisciples: recruited $count disciples")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "recruitAllDisciples: failed", e)
            } finally {
                isRecruitingAll = false
            }
        }
    }

    fun rejectDiscipleFromList(discipleId: String) {
        scope.launch {
            gameEngine.removeFromRecruitList(discipleId)
        }
    }

    fun recruitDisciple(disciple: DiscipleAggregate) {
        recruitDiscipleFromList(disciple.id)
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

    fun releaseReflectionDisciple(discipleId: String) {
        scope.launch { gameEngine.releaseReflectionDisciple(discipleId) }
    }

    fun onLoyaltyDialogDismissed() {
        gameEngine.clearPendingNotification()
    }

    fun getDiscipleById(id: String): DiscipleAggregate? {
        return gameEngine.getDiscipleAggregate(id)
    }

    // ═══════════════════════════════════════════
    // 交谈效果相关
    // ═══════════════════════════════════════════

    /** 获取弟子上次获得交谈效果的游戏年份，null 表示从未获得过 */
    fun getLastChatYear(discipleId: String): Int? {
        val agg = gameEngine.getDiscipleAggregate(discipleId) ?: return null
        return agg.sourceRef?.statusData?.get("lastChatYear")?.toIntOrNull()
    }

    /** 应用交谈效果并记录冷却年份 */
    fun applyConversationEffects(
        discipleId: String,
        currentYear: Int,
        moralityDelta: Int,
        loyaltyDelta: Int,
        cultivationDelta: Double,
        intelligenceDelta: Int
    ) {
        scope.launch {
            try {
                gameEngine.updateDisciple(discipleId) { disciple ->
                    val newStatus = disciple.statusData.toMutableMap().apply {
                        this["lastChatYear"] = currentYear.toString()
                    }
                    disciple.copy(
                        cultivation = maxOf(0.0, disciple.cultivation + cultivationDelta),
                        skills = disciple.skills.copy(
                            morality = (disciple.skills.morality + moralityDelta).coerceIn(1, 100),
                            loyalty = (disciple.skills.loyalty + loyaltyDelta).coerceIn(1, 100),
                            intelligence = (disciple.skills.intelligence + intelligenceDelta).coerceIn(1, 100)
                        ),
                        statusData = newStatus
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun setAutoRecruitFilter(filter: Set<Int>) {
        scope.launch {
            gameEngine.updateGameData { gd ->
                gd.copy(autoRecruitSpiritRootFilter = filter)
            }
        }
    }
}
