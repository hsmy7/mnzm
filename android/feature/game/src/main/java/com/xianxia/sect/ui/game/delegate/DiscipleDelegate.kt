package com.xianxia.sect.ui.game.delegate

import android.util.Log
import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.engine.BreakthroughBonusResult
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.SpiritRootWashConfirmResult
import com.xianxia.sect.core.engine.SpiritRootWashResult
import com.xianxia.sect.core.engine.TraitWashConfirmResult
import com.xianxia.sect.core.engine.TraitWashResult
import com.xianxia.sect.core.engine.apprenticeToMaster
import com.xianxia.sect.core.engine.assignDiscipleToBuilding
import com.xianxia.sect.core.engine.changeDiscipleTypeAtomic
import com.xianxia.sect.core.engine.confirmSpiritRootWash
import com.xianxia.sect.core.engine.confirmTraitWash
import com.xianxia.sect.core.engine.confiscateStorageBagItem
import com.xianxia.sect.core.engine.equipItem
import com.xianxia.sect.core.engine.expelDisciple
import com.xianxia.sect.core.engine.forgetManual
import com.xianxia.sect.core.engine.getDiscipleAggregate
import com.xianxia.sect.core.engine.learnManual
import com.xianxia.sect.core.engine.recruitAllFromList
import com.xianxia.sect.core.engine.recruitDiscipleFromList
import com.xianxia.sect.core.engine.releaseReflectionDisciple
import com.xianxia.sect.core.engine.removeFromRecruitList
import com.xianxia.sect.core.engine.purchaseBreakthroughBonus
import com.xianxia.sect.core.engine.renameDisciple
import com.xianxia.sect.core.engine.replaceManual
import com.xianxia.sect.core.engine.rewardItemsToDisciple
import com.xianxia.sect.core.engine.unequipItem
import com.xianxia.sect.core.engine.unequipItemById
import com.xianxia.sect.core.engine.updateDisciple
import com.xianxia.sect.core.engine.updateGameData
import com.xianxia.sect.core.engine.usePill
import com.xianxia.sect.core.engine.washSpiritRoot
import com.xianxia.sect.core.engine.washTraitSlot
import com.xianxia.sect.core.engine.service.RecruitService
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.StorageBagItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext



class DiscipleDelegate(
    private val gameEngine: GameEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private const val TAG = "DiscipleDelegate"
    }

    // 招募相关，防止重复点击
    private val recruitingDiscipleIds = mutableSetOf<String>()
    private val recruitingLock = Any()
    @Volatile private var isRecruitingAll = false

    fun expelDisciple(discipleId: String) {
        gameEngine.launchOnEngine { gameEngine.expelDisciple(discipleId) }
    }

    /** 拜师：将 discipleId 设为 masterId 的徒弟 */
    fun apprenticeToMaster(discipleId: String, masterId: String) {
        gameEngine.launchOnEngine { gameEngine.apprenticeToMaster(discipleId, masterId) }
    }

    fun toggleFollowDisciple(discipleId: String) {
        gameEngine.launchOnEngine {
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
        gameEngine.launchOnEngine {
            gameEngine.changeDiscipleTypeAtomic(discipleId, newType)
        }
    }

    suspend fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>) {
        withContext(dispatcher) {
            gameEngine.rewardItemsToDisciple(discipleId, items)
        }
    }

    fun confiscateStorageBagItem(discipleId: String, item: StorageBagItem) {
        gameEngine.launchOnEngine {
            try {
                gameEngine.confiscateStorageBagItem(discipleId, item)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "confiscateStorageBagItem failed", e)
            }
        }
    }

    fun equipItem(discipleId: String, equipmentId: String) {
        gameEngine.launchOnEngine {
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
        gameEngine.launchOnEngine {
            try {
                val result = gameEngine.unequipItem(discipleId, slot)
                if (result == null) return@launchOnEngine // disciple not found or slot empty
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
        gameEngine.launchOnEngine {
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
        gameEngine.launchOnEngine {
            try {
                gameEngine.forgetManual(discipleId, instanceId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun replaceManual(discipleId: String, oldInstanceId: String, newStackId: String) {
        gameEngine.launchOnEngine {
            try {
                gameEngine.replaceManual(discipleId, oldInstanceId, newStackId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun learnManual(discipleId: String, stackId: String) {
        gameEngine.launchOnEngine {
            try {
                gameEngine.learnManual(discipleId, stackId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun usePill(discipleId: String, pillId: String) {
        gameEngine.launchOnEngine {
            try {
                gameEngine.usePill(discipleId, pillId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun usePill(discipleId: String, pill: Pill) {
        gameEngine.launchOnEngine {
            try {
                gameEngine.usePill(discipleId, pill.id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) {
        gameEngine.launchOnEngine {
            try {
                gameEngine.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun renameDisciple(discipleId: String, newName: String) {
        gameEngine.launchOnEngine {
            try {
                // 引擎层原子改名 + 同事务净化招募列表同人残留（防改名后重复可招募）
                gameEngine.renameDisciple(discipleId, newName)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    /** 洗炼灵根：扣 1 玉符 + 保底判定抽取，返回产物（UI 会话持有结果，未确认不写弟子） */
    suspend fun washSpiritRoot(discipleId: String, pityCount: Int): SpiritRootWashResult =
        gameEngine.washSpiritRoot(discipleId, pityCount)

    /** 确认替换：把弟子灵根替换为洗炼产物 */
    suspend fun confirmSpiritRootWash(discipleId: String, newRootType: String): SpiritRootWashConfirmResult =
        gameEngine.confirmSpiritRootWash(discipleId, newRootType)

    /** 消耗 1 玉符提高弟子突破率（上限 0.30 即最多 2 次；突破尝试后自动清除重置） */
    suspend fun purchaseBreakthroughBonus(discipleId: String): BreakthroughBonusResult =
        gameEngine.purchaseBreakthroughBonus(discipleId)

    // ── 洗炼天赋/体质/词条（玉符消耗玩法，流程对齐洗炼灵根；单槽语义：只洗炼目标特质）──

    /**
     * 洗炼天赋的单个目标槽位：扣 1 玉符 + 保底判定抽取，返回产物
     * （UI 会话持有结果，未确认不写弟子；其余天赋保留不动）。
     */
    suspend fun washTalent(discipleId: String, targetId: String, pityCount: Int): TraitWashResult =
        gameEngine.washTraitSlot(discipleId, TraitWashType.TALENT, targetId, pityCount)

    /** 洗炼体质的单个目标槽位：扣 1 玉符 + 保底判定抽取，返回产物 */
    suspend fun washPhysique(discipleId: String, targetId: String, pityCount: Int): TraitWashResult =
        gameEngine.washTraitSlot(discipleId, TraitWashType.PHYSIQUE, targetId, pityCount)

    /** 洗炼词条的单个目标槽位：扣 1 玉符 + 保底判定抽取，返回产物 */
    suspend fun washAffix(discipleId: String, targetId: String, pityCount: Int): TraitWashResult =
        gameEngine.washTraitSlot(discipleId, TraitWashType.AFFIX, targetId, pityCount)

    /** 确认替换天赋：把目标天赋槽位替换为洗炼产物（其余天赋保留） */
    suspend fun confirmTalent(discipleId: String, targetId: String, newId: String): TraitWashConfirmResult =
        gameEngine.confirmTraitWash(discipleId, TraitWashType.TALENT, targetId, newId)

    /** 确认替换体质：把目标体质槽位替换为洗炼产物（其余体质保留） */
    suspend fun confirmPhysique(discipleId: String, targetId: String, newId: String): TraitWashConfirmResult =
        gameEngine.confirmTraitWash(discipleId, TraitWashType.PHYSIQUE, targetId, newId)

    /** 确认替换词条：把目标词条槽位替换为洗炼产物（其余词条保留） */
    suspend fun confirmAffix(discipleId: String, targetId: String, newId: String): TraitWashConfirmResult =
        gameEngine.confirmTraitWash(discipleId, TraitWashType.AFFIX, targetId, newId)

    fun recruitDiscipleFromList(discipleId: String) {
        if (discipleId.isBlank()) {
            Log.w(TAG, "recruitDiscipleFromList: skipped (empty id)")
            return
        }
        synchronized(recruitingLock) {
            if (isRecruitingAll) {
                Log.w(TAG, "recruitDiscipleFromList: skipped (isRecruitingAll=true) for $discipleId")
                return
            }
            if (recruitingDiscipleIds.contains(discipleId)) {
                Log.w(TAG, "recruitDiscipleFromList: skipped (duplicate) for $discipleId")
                return
            }
            recruitingDiscipleIds.add(discipleId)
        }
        gameEngine.launchOnEngine {
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
                synchronized(recruitingLock) {
                    recruitingDiscipleIds.remove(discipleId)
                }
            }
        }
    }

    fun recruitAllDisciples() {
        synchronized(recruitingLock) {
            if (isRecruitingAll) return
            if (recruitingDiscipleIds.isNotEmpty()) return
            isRecruitingAll = true
        }
        gameEngine.launchOnEngine {
            try {
                val count = gameEngine.recruitAllFromList()
                Log.d(TAG, "recruitAllDisciples: recruited $count disciples")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "recruitAllDisciples: failed", e)
            } finally {
                synchronized(recruitingLock) { recruitingDiscipleIds.clear() }
                isRecruitingAll = false
            }
        }
    }

    fun rejectDiscipleFromList(discipleId: String) {
        gameEngine.launchOnEngine {
            gameEngine.removeFromRecruitList(discipleId)
        }
    }

    fun recruitDisciple(disciple: DiscipleAggregate) {
        recruitDiscipleFromList(disciple.id)
    }

    fun releaseReflectionDisciple(discipleId: String) {
        gameEngine.launchOnEngine { gameEngine.releaseReflectionDisciple(discipleId) }
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
        gameEngine.launchOnEngine {
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
        val validated = filter.filter { it in 1..5 }.toSet()
        gameEngine.launchOnEngine {
            gameEngine.updateGameData { gd ->
                gd.copy(autoRecruitSpiritRootFilter = validated)
            }
            RecruitService.resetAutoRecruitIdle()
        }
    }

    fun setAutoRejectFilter(filter: Set<Int>) {
        val validated = filter.filter { it in 1..5 }.toSet()
        gameEngine.launchOnEngine {
            gameEngine.updateGameData { gd ->
                gd.copy(autoRejectSpiritRootFilter = validated)
            }
            RecruitService.resetAutoRejectIdle()
        }
    }
}
