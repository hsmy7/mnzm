package com.xianxia.sect.ui.game.delegate

import android.util.Log
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.apprenticeToMaster
import com.xianxia.sect.core.engine.assignDiscipleToBuilding
import com.xianxia.sect.core.engine.changeDiscipleTypeAtomic
import com.xianxia.sect.core.engine.confiscateStorageBagItem
import com.xianxia.sect.core.engine.expelDisciple
import com.xianxia.sect.core.engine.getDiscipleAggregate
import com.xianxia.sect.core.engine.recruitAllFromList
import com.xianxia.sect.core.engine.recruitDiscipleFromList
import com.xianxia.sect.core.engine.releaseReflectionDisciple
import com.xianxia.sect.core.engine.removeFromRecruitList
import com.xianxia.sect.core.engine.renameDisciple
import com.xianxia.sect.core.engine.rewardItemsToDisciple
import com.xianxia.sect.core.engine.updateDisciple
import com.xianxia.sect.core.engine.setAutoRecruitFilterValidated
import com.xianxia.sect.core.engine.setAutoRejectFilterValidated
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.StorageBagItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiscipleDelegate(
    /** internal：同包操作族扩展（WashOps/TraitAddOps/GearOps/LifecycleOps）消费——TMF 收敛外移 */
    internal val gameEngine: GameEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 招募被拦截时的用户可见提示回调——防抖拦截不得静默（否则玩家点击
     * "同意"无任何反馈，表现为"招募无效果"）。
     * GameViewModel 注入 showError 事件通道（线程安全 Channel.trySend）；
     * 默认空实现兼容既有测试。
     */
    private val onRecruitBlocked: (String) -> Unit = {},
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

    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
    fun confiscateStorageBagItem(discipleId: String, item: StorageBagItem) {
        gameEngine.launchOnEngine {
            try {
                gameEngine.confiscateStorageBagItem(discipleId, item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DiscipleDelegate", "confiscateStorageBagItem failed", e)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) {
        gameEngine.launchOnEngine {
            try {
                gameEngine.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
    fun renameDisciple(discipleId: String, newName: String) {
        gameEngine.launchOnEngine {
            try {
                // 引擎层原子改名 + 同事务净化招募列表同人残留（防改名后重复可招募）
                gameEngine.renameDisciple(discipleId, newName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
    fun recruitDiscipleFromList(discipleId: String) {
        if (discipleId.isBlank()) {
            Log.w(TAG, "recruitDiscipleFromList: skipped (empty id)")
            onRecruitBlocked("招募操作无效，请重试")
            return
        }
        gameEngine.launchOnEngine {
            // 防抖占位/拦截必须在协程内部执行——若在点击时占位且 launch
            // 落在 engineScope 已取消窗口（关闭/紧急重启），block 与 finally
            // 都不执行，占位永久残留 → 该弟子后续点击全部被静默拦截。
            // 占位随协程实际执行注册，取消窗口零残留。
            synchronized(recruitingLock) {
                if (isRecruitingAll) {
                    Log.w(TAG, "recruitDiscipleFromList: skipped (isRecruitingAll=true) for $discipleId")
                    onRecruitBlocked("一键招募进行中，请稍后再试")
                    return@launchOnEngine
                }
                if (recruitingDiscipleIds.contains(discipleId)) {
                    Log.w(TAG, "recruitDiscipleFromList: skipped (duplicate) for $discipleId")
                    onRecruitBlocked("该弟子招募进行中，请稍候")
                    return@launchOnEngine
                }
                recruitingDiscipleIds.add(discipleId)
            }
            try {
                Log.d(TAG, "recruitDiscipleFromList: launching for $discipleId")
                val newId = gameEngine.recruitDiscipleFromList(discipleId)
                if (newId.isEmpty()) {
                    Log.w(TAG, "recruitDiscipleFromList: failed for $discipleId")
                } else {
                    Log.d(TAG, "recruitDiscipleFromList: success id=$newId for $discipleId")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recruitDiscipleFromList: exception for $discipleId", e)
                onRecruitBlocked("招募操作异常，请重试")
            } finally {
                synchronized(recruitingLock) {
                    recruitingDiscipleIds.remove(discipleId)
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
    fun recruitAllDisciples() {
        gameEngine.launchOnEngine {
            // 同单招：isRecruitingAll 置位随协程实际执行（取消窗口零残留；
            // 点击时置位会在取消窗口永久拦截手动招募）
            synchronized(recruitingLock) {
                if (isRecruitingAll) {
                    Log.w(TAG, "recruitAllDisciples: skipped (isRecruitingAll=true)")
                    onRecruitBlocked("一键招募进行中，请稍后再试")
                    return@launchOnEngine
                }
                if (recruitingDiscipleIds.isNotEmpty()) {
                    Log.w(TAG, "recruitAllDisciples: skipped (other recruiting in progress)")
                    onRecruitBlocked("有弟子招募进行中，请稍后再试")
                    return@launchOnEngine
                }
                isRecruitingAll = true
            }
            try {
                val count = gameEngine.recruitAllFromList()
                Log.d(TAG, "recruitAllDisciples: recruited $count disciples")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recruitAllDisciples: failed", e)
                onRecruitBlocked("一键招募异常，请重试")
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
    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }

    fun setAutoRecruitFilter(filter: Set<Int>) {
        gameEngine.launchOnEngine { gameEngine.setAutoRecruitFilterValidated(filter) }
    }

    fun setAutoRejectFilter(filter: Set<Int>) {
        gameEngine.launchOnEngine { gameEngine.setAutoRejectFilterValidated(filter) }
    }
}
