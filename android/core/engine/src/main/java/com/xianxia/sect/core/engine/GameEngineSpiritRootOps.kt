package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.shuffled
import kotlin.coroutines.cancellation.CancellationException

// GameEngineSpiritRootOps.kt — 洗炼灵根（玉符消耗玩法）GameEngine 扩展入口
// （对照 startBloodRefinementAtomic 的原子消耗 + sealed 结果模式）

/** 洗炼灵根结果 */
sealed interface SpiritRootWashResult {
    /** 洗炼成功：newRootType 为洗炼产物（英文元素 key 逗号串），newPityCount 为下次保底计数 */
    data class Success(val newRootType: String, val newPityCount: Int) : SpiritRootWashResult
    /** 玉符不足（余额不足时不消耗随机序列） */
    data class InsufficientJadeSymbols(val current: Int, val required: Int) : SpiritRootWashResult
    /** 其他错误（弟子不存在/非法参数/引擎异常） */
    data class Error(val message: String) : SpiritRootWashResult
}

/** 确认替换洗炼结果 */
sealed interface SpiritRootWashConfirmResult {
    data object Success : SpiritRootWashConfirmResult
    data class Error(val message: String) : SpiritRootWashConfirmResult
}

/** 单次洗炼纯随机产物（不落盘，由 UI 洗炼会话持有） */
internal data class SpiritRootWashRoll(val rootType: String, val newPityCount: Int)

/**
 * 洗炼灵根纯随机函数（保底 + 概率 + 元素抽取）。
 *
 * draw 次数固定：保底路径 5 次（仅元素洗牌），普通路径 6 次（1 nextDouble + 5 nextInt）；
 * 同一种子 + 同一 [pityCount] 结果完全确定（确定性 RNG 要求，见 RngConsumptionGuardTest）。
 *
 * @param pityCount 当前保底计数（连续未出单灵根次数，UI 会话持有）
 */
internal fun rollSpiritRootWash(rng: DeterministicRng, pityCount: Int): SpiritRootWashRoll {
    val rootCount = if (pityCount >= GameConfig.SpiritRoot.WASH_PITY_THRESHOLD) {
        // 保底：连续第 3 次必出单灵根，不消耗随机 draw
        1
    } else if (rng.nextDouble() < GameConfig.SpiritRoot.WASH_DOUBLE_WEIGHT) {
        2
    } else {
        1
    }
    val type = GameConfig.SpiritRoot.WASH_ELEMENT_KEYS
        .shuffled(rng)
        .take(rootCount)
        .joinToString(",")
    return SpiritRootWashRoll(type, if (rootCount == 1) 0 else pityCount + 1)
}

/**
 * 洗炼灵根：校验弟子存在 → 事务内扣 1 玉符 → 按 [pityCount] 保底判定抽取。
 *
 * 玉符不足时提前返回且**不消耗随机序列**（随机序列确定性保持）。
 * 洗炼只返回产物不写弟子；"确认替换"由 [confirmSpiritRootWash] 负责。
 */
@Suppress("TooGenericExceptionCaught") // 兜底转 Error（项目范式，同 GameEngineSelfHealOps）
suspend fun GameEngine.washSpiritRoot(
    discipleId: String,
    pityCount: Int
): SpiritRootWashResult = engineContextDispatcher.withEngineContext {
    if (pityCount < 0) {
        return@withEngineContext SpiritRootWashResult.Error("非法保底计数")
    }
    val id = discipleId.toIntOrNull()
    if (id == null) {
        return@withEngineContext SpiritRootWashResult.Error("非法弟子ID")
    }
    try {
        val required = GameConfig.SpiritRoot.WASH_JADE_COST
        val result = stateStore.updateAndReturn {
            if (id !in discipleTables.ids) {
                return@updateAndReturn SpiritRootWashResult.Error("弟子不存在")
            }
            if (!jadeSymbolService.deduct(this, required)) {
                return@updateAndReturn SpiritRootWashResult.InsufficientJadeSymbols(
                    current = gameData.jadeSymbols,
                    required = required
                )
            }
            val roll = rollSpiritRootWash(
                gameRngManager.getRng(RngPartition.SYSTEM),
                pityCount
            )
            SpiritRootWashResult.Success(roll.rootType, roll.newPityCount)
        }
        if (result is SpiritRootWashResult.Success) {
            // 事务外刷新玉符 UI 状态（清 1Hz 节流，徽章/详情即时更新）
            jadeSymbolService.publishJadeSymbolStateNow()
        }
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.e("GameEngine", "洗炼灵根失败: id=$discipleId", e)
        SpiritRootWashResult.Error(e.message ?: "未知错误")
    }
}

/**
 * 确认替换：把弟子灵根替换为洗炼产物（同事务 remove + insert，仿 renameDisciple 形态）。
 *
 * 灵根加成（修炼速度/突破率/父母灵根加成）全部读取时现场推导、无缓存字段，
 * 替换后即刻生效，无需 checkpointDisciple。
 */
@Suppress("TooGenericExceptionCaught") // 兜底转 Error（项目范式，同 GameEngineSelfHealOps）
suspend fun GameEngine.confirmSpiritRootWash(
    discipleId: String,
    newRootType: String
): SpiritRootWashConfirmResult = engineContextDispatcher.withEngineContext {
    if (!isValidWashedRootType(newRootType)) {
        return@withEngineContext SpiritRootWashConfirmResult.Error("非法灵根数据")
    }
    val id = discipleId.toIntOrNull()
    if (id == null) {
        return@withEngineContext SpiritRootWashConfirmResult.Error("非法弟子ID")
    }
    try {
        val replaced = stateStore.updateAndReturn<Boolean> {
            if (id !in discipleTables.ids) return@updateAndReturn false
            val current: Disciple = discipleTables.assemble(id)
            discipleTables.remove(id)
            discipleTables.insert(current.copy(spiritRootType = newRootType))
            true
        }
        if (replaced) {
            SpiritRootWashConfirmResult.Success
        } else {
            SpiritRootWashConfirmResult.Error("弟子不存在")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.e("GameEngine", "确认洗炼灵根失败: id=$discipleId", e)
        SpiritRootWashConfirmResult.Error(e.message ?: "未知错误")
    }
}

/** 洗炼产物合法性校验：1~2 个元素且全部在洗炼元素表内（防外部篡改写入；空串/空白拆出空元素自然被拒） */
private fun isValidWashedRootType(newRootType: String): Boolean {
    val types = newRootType.split(",")
    return types.size in 1..2 && types.all { it in GameConfig.SpiritRoot.WASH_ELEMENT_KEYS }
}
