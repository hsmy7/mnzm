package com.xianxia.sect.ui.game.delegate

import android.util.Log
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.apprenticeToMaster
import com.xianxia.sect.core.engine.applyConversationEffectAtomic
import com.xianxia.sect.core.engine.assignDiscipleToBuilding
import com.xianxia.sect.core.engine.changeDiscipleTypeAtomic
import com.xianxia.sect.core.engine.confiscateStorageBagItem
import com.xianxia.sect.core.engine.expelDisciple
import com.xianxia.sect.core.engine.getDiscipleAggregate
import com.xianxia.sect.core.engine.releaseReflectionDisciple
import com.xianxia.sect.core.engine.renameDisciple
import com.xianxia.sect.core.engine.rewardItemsToDisciple
import com.xianxia.sect.core.engine.toggleFollowDisciple
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
) {
    fun expelDisciple(discipleId: String) {
        gameEngine.launchOnEngine { gameEngine.expelDisciple(discipleId) }
    }

    /** 拜师：将 discipleId 设为 masterId 的徒弟 */
    fun apprenticeToMaster(discipleId: String, masterId: String) {
        gameEngine.launchOnEngine { gameEngine.apprenticeToMaster(discipleId, masterId) }
    }

    fun toggleFollowDisciple(discipleId: String) {
        // W4-A·w3-01：关注切换事务化（C++ 真相先行 + Kotlin 回退臂，
        // 原 updateDisciple lambda 直改面收口至引擎入口）
        gameEngine.launchOnEngine {
            gameEngine.toggleFollowDisciple(discipleId)
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
                // 引擎层原子改名（C++ 真相先行，失败回退 Kotlin 同事务写）
                gameEngine.renameDisciple(discipleId, newName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
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
        cultivationDelta: Double,
        intelligenceDelta: Int
    ) {
        gameEngine.launchOnEngine {
            try {
                // W4-D 续批：C++ 真相先行（DISCIPLE_CHAT_EFFECT_TX=1860，零 RNG——
                // 增量已由引擎侧 CHAT 分区签发后参数传入）；失败信封/降级 →
                // updateDisciple Kotlin 回退臂（红线 3）
                gameEngine.applyConversationEffectAtomic(
                    discipleId = discipleId,
                    currentYear = currentYear,
                    moralityDelta = moralityDelta,
                    cultivationDelta = cultivationDelta,
                    intelligenceDelta = intelligenceDelta
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DiscipleDelegate", "operation failed", e)
            }
        }
    }
}
