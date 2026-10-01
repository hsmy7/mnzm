package com.xianxia.sect.ui.game.delegate

import android.util.Log
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.BreakthroughBonusResult
import com.xianxia.sect.core.engine.applyConversationEffectAtomic
import com.xianxia.sect.core.engine.assignDiscipleToBuilding
import com.xianxia.sect.core.engine.changeDiscipleTypeAtomic
import com.xianxia.sect.core.engine.confiscateStorageBagItem
import com.xianxia.sect.core.engine.getDiscipleAggregate
import com.xianxia.sect.core.engine.purchaseBreakthroughBonus
import com.xianxia.sect.core.engine.releaseReflectionDisciple
import com.xianxia.sect.core.engine.rewardItemsToDisciple
import com.xianxia.sect.core.engine.toggleFollowDisciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.state.CriticalSaveEventBus
import com.xianxia.sect.core.state.MONEY_SAVE_ACK_TIMEOUT_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiscipleDelegate(
    /** internal：同包操作族扩展（GearOps/LifecycleOps）消费——TMF 收敛外移 */
    internal val gameEngine: GameEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    // SS6：关键事件自动存档总线——玉符购买成功后等待涉钱数据落盘（方案 §2.5 同步语义）；
    // internal：同文件顶层扩展（purchaseBreakthroughBonus）消费；
    // 默认值仅供测试直构（等待立即超时返回），生产由 GameVmDelegateServices 注入单例
    internal val criticalSaveEvents: CriticalSaveEventBus = CriticalSaveEventBus()
) {
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

/** 消耗 1 玉符提高弟子突破率（上限 0.30 即最多 2 次；突破尝试后自动清除重置） */
suspend fun DiscipleDelegate.purchaseBreakthroughBonus(discipleId: String): BreakthroughBonusResult {
    val result = gameEngine.purchaseBreakthroughBonus(discipleId)
    if (result is BreakthroughBonusResult.Success) {
        // 涉钱同步落盘承诺（方案 §2.5）：扣费流水已在引擎侧 append，
        // 事件方法返回前挂起等待含本笔流水的保存完成（超时由节拍兜底）
        criticalSaveEvents.awaitNextSaveCompletion(MONEY_SAVE_ACK_TIMEOUT_MS)
    }
    return result
}
