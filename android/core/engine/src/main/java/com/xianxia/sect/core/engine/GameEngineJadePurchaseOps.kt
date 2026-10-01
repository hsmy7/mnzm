package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.service.notifyMoneyLedgerChanged
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.JadeLedgerReasons
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.util.DomainLog
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

// GameEngineJadePurchaseOps.kt — 玉符购买玩法
// （账本落账 + sealed 结果 + 事务外 publish 模式）

/** 消耗玉符购买突破率加成结果 */
sealed interface BreakthroughBonusResult {
    /** 购买成功：突破率加成已生效（statusData["adBreakthroughBonus"] 累加） */
    data object Success : BreakthroughBonusResult
    /** 玉符不足（余额不足时不写入任何状态） */
    data class InsufficientJadeSymbols(val current: Int, val required: Int) : BreakthroughBonusResult
    /** 已达上限（0.30，最多 2 次玉符）：不扣玉符 */
    data class LimitReached(val currentBonus: Double) : BreakthroughBonusResult
    /** 其他错误（弟子不存在/非法参数/引擎异常） */
    data class Error(val message: String) : BreakthroughBonusResult
}

/** 消耗玉符购买商人刷新次数结果 */
sealed interface MerchantRefreshResult {
    /** 购买成功：刷新次数已增加 */
    data object Success : MerchantRefreshResult
    /** 玉符不足（余额不足时不写入任何状态） */
    data class InsufficientJadeSymbols(val current: Int, val required: Int) : MerchantRefreshResult
    /** 已达上限（999 次）：不扣玉符 */
    data object LimitReached : MerchantRefreshResult
    /** 其他错误（引擎异常） */
    data class Error(val message: String) : MerchantRefreshResult
}

/**
 * 消耗 1 玉符提高弟子突破率（每次 +0.15，上限 0.30 即最多 2 次玉符）。
 *
 * 校验顺序：弟子存在 → 存活 → 上限校验（先于扣款，达上限不扣玉符）→ 落账扣减 → 写 statusData。
 * statusData key 为 "adBreakthroughBonus"（沿用旧档 key 以保兼容；当前语义为玉符加成，
 * 突破尝试后由 DiscipleBreakthroughHandler 清除重置，见其 performBreakthrough）。
 *
 * 玉符不足时提前返回且不写入任何状态（账本落账语义见 JadeSymbolService KDoc，
 * 守卫测试 JadeSymbolConsumptionGuardTest 约束）。
 *
 * @param discipleId 目标弟子 ID（字符串形式的整数）
 * @return [BreakthroughBonusResult] 三态结果
 */
@Suppress("TooGenericExceptionCaught") // 兜底转 Error（项目范式）
suspend fun GameEngine.purchaseBreakthroughBonus(discipleId: String): BreakthroughBonusResult =
    engineContextDispatcher.withEngineContext {
        val id = discipleId.toIntOrNull()
        if (id == null) {
            return@withEngineContext BreakthroughBonusResult.Error("非法弟子ID")
        }
        try {
            val required = GameConfig.JadePurchase.COST
            // native 臂：玉符购买落账（JADE_PURCHASE_BREAKTHROUGH_BONUS_TX——
            // 校验链（弟子存在/存活/上限**先于**扣款）+ 账本 SPEND_BREAKTHROUGH_BONUS
            // 落账 + statusData 写回，零 RNG）。余额权威在账本，Kotlin 以回执跟随。
            tryNativeJadeBreakthroughBonus(discipleId, required)?.let {
                return@withEngineContext it
            }
            val result = stateStore.updateAndReturn {
                if (id !in discipleTables.ids) {
                    return@updateAndReturn BreakthroughBonusResult.Error("弟子不存在")
                }
                // 死亡弟子拒绝购买（对死人无意义，防止误操作扣玉符）
                if (discipleTables.isAlive[id] != 1) {
                    return@updateAndReturn BreakthroughBonusResult.Error("弟子已死亡")
                }
                val currentBonus = discipleTables.assemble(id).statusData["adBreakthroughBonus"]
                    ?.toDoubleOrNull() ?: 0.0
                // 上限校验先于扣款：达上限不消耗玉符
                if (currentBonus >= GameConfig.JadePurchase.BREAKTHROUGH_BONUS_MAX) {
                    return@updateAndReturn BreakthroughBonusResult.LimitReached(currentBonus)
                }
                if (!jadeSymbolService.deduct(
                        this, required, JadeLedgerReasons.SPEND_BREAKTHROUGH_BONUS
                    )
                ) {
                    return@updateAndReturn BreakthroughBonusResult.InsufficientJadeSymbols(
                        current = gameData.jadeSymbols,
                        required = required
                    )
                }
                // 同事务写弟子表（assemble/remove/insert 行原子替换）
                val newBonus = (currentBonus + GameConfig.JadePurchase.BREAKTHROUGH_BONUS_PER_JADE)
                    .coerceAtMost(GameConfig.JadePurchase.BREAKTHROUGH_BONUS_MAX)
                val current: Disciple = discipleTables.assemble(id)
                val newStatusData = current.statusData.toMutableMap().apply {
                    this["adBreakthroughBonus"] = newBonus.toString()
                }
                discipleTables.remove(id)
                discipleTables.insert(current.copy(statusData = newStatusData))
                BreakthroughBonusResult.Success
            }
            if (result is BreakthroughBonusResult.Success) {
                // 事务外刷新玉符 UI 状态（清 1Hz 节流，徽章/详情即时更新）
                jadeSymbolService.publishJadeSymbolStateNow()
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e("GameEngine", "购买突破率加成失败: id=$discipleId", e)
            BreakthroughBonusResult.Error(e.message ?: "未知错误")
        }
    }

/**
 * 消耗 1 玉符获取 3 次商人刷新次数（上限 999，先上限后扣款）。
 *
 * @return [MerchantRefreshResult] 三态结果
 */
@Suppress("TooGenericExceptionCaught") // 兜底转 Error（项目范式）
suspend fun GameEngine.purchaseMerchantRefresh(): MerchantRefreshResult =
    engineContextDispatcher.withEngineContext {
        try {
            val required = GameConfig.JadePurchase.COST
            // native 臂：玉符购买落账（JADE_PURCHASE_MERCHANT_REFRESH_TX——
            // 上限校验先于扣款 + 账本 SPEND_MERCHANT_REFRESH 落账 + 刷新次数
            // 累加钳制，零 RNG）。同 purchaseBreakthroughBonus：余额权威在账本。
            tryNativeJadeMerchantRefresh(required)?.let {
                return@withEngineContext it
            }
            val result = stateStore.updateAndReturn {
                // 上限校验先于扣款：达上限不消耗玉符
                if (gameData.merchantRefreshChances >= GameConfig.JadePurchase.MERCHANT_REFRESH_MAX) {
                    return@updateAndReturn MerchantRefreshResult.LimitReached
                }
                if (!jadeSymbolService.deduct(
                        this, required, JadeLedgerReasons.SPEND_MERCHANT_REFRESH
                    )
                ) {
                    return@updateAndReturn MerchantRefreshResult.InsufficientJadeSymbols(
                        current = gameData.jadeSymbols,
                        required = required
                    )
                }
                gameData = gameData.copy(
                    merchantRefreshChances = (gameData.merchantRefreshChances +
                        GameConfig.JadePurchase.MERCHANT_REFRESH_PER_JADE)
                        .coerceAtMost(GameConfig.JadePurchase.MERCHANT_REFRESH_MAX)
                )
                MerchantRefreshResult.Success
            }
            if (result is MerchantRefreshResult.Success) {
                // 事务外刷新玉符 UI 状态（清 1Hz 节流，徽章/详情即时更新）
                jadeSymbolService.publishJadeSymbolStateNow()
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e("GameEngine", "购买商人刷新次数失败", e)
            MerchantRefreshResult.Error(e.message ?: "未知错误")
        }
    }

// ── native 臂（玉符购买落账段下沉 C++，jade_tx.h 账本模型） ───────────────
//
// 玉符扣减走 C++ 账本：事务以账本末条 balance_after（O(1)）判定余额，落
// SPEND_* 条目并同事务双写派生缓存。回执 total = 落账后余额（权威），
// drift = 派生缓存与账本基准不一致（C++ 已以账本为准重锚，Log 上报）。
// 门控降级契约：flag OFF / 桥未加载 / 镜像服务缺失（可空局部判空，handover
// findings 13——测试 mock 未 stub `stateSyncServiceRef` 时返回 null 不得 NPE）/
// 业务失败信封（上限已达/余额不足/弟子不存在等）→ 返回 null，由调用方走
// Kotlin 原路径重执行校验链并产出用户可见三态文案。

/**
 * 玉符购买突破率加成 native 臂。
 *
 * @return 成功（C++ 已落账扣减 + 写 statusData）时返回
 *         [BreakthroughBonusResult.Success]；任一降级/失败返回 null
 *         （调用方回退 Kotlin 原路径 → 三态文案不变）
 */
@Suppress("ReturnCount")  // 降级契约：逐级早退（同 tryNativeSectLevelUpgrade）
private suspend fun GameEngine.tryNativeJadeBreakthroughBonus(
    discipleId: String,
    required: Int
): BreakthroughBonusResult? {
    if (!NativeEngineFlag.authoritative) return null
    val sync: StateSyncService? = gameEngineCore.stateSyncServiceRef ?: return null
    val data = GameEngineNativeOps.tryExecuteNative(
        stateSyncService = sync,
        actionId = ActionIds.JADE_PURCHASE_BREAKTHROUGH_BONUS_TX,
        paramsJson = params {
            put("discipleId", discipleId)
            put("cost", required)
            put("perJade", GameConfig.JadePurchase.BREAKTHROUGH_BONUS_PER_JADE)
            put("maxBonus", GameConfig.JadePurchase.BREAKTHROUGH_BONUS_MAX)
            put("nowMs", jadeSymbolService.wallClockNowMs())
        }
    ) ?: return null
    // C++ 权威落账完成 → 清 1Hz 节流刷新 UI（余额读数随镜像更新）+ 涉钱事件请求立即落盘
    jadeSymbolService.publishJadeSymbolStateNow()
    jadeSymbolService.notifyMoneyLedgerChanged()
    DomainLog.i("GameEngine", "purchaseBreakthroughBonus: native applied (jade=${data})")
    return BreakthroughBonusResult.Success
}

/**
 * 玉符购买商人刷新 native 臂。
 *
 * @return 成功（C++ 已落账扣减 + 累加刷新次数）时返回
 *         [MerchantRefreshResult.Success]；任一降级/失败返回 null
 *         （调用方回退 Kotlin 原路径 → 三态文案不变）
 */
@Suppress("ReturnCount")  // 降级契约：逐级早退（同上）
private fun GameEngine.tryNativeJadeMerchantRefresh(required: Int): MerchantRefreshResult? {
    if (!NativeEngineFlag.authoritative) return null
    val sync: StateSyncService? = gameEngineCore.stateSyncServiceRef ?: return null
    val data = GameEngineNativeOps.tryExecuteNative(
        stateSyncService = sync,
        actionId = ActionIds.JADE_PURCHASE_MERCHANT_REFRESH_TX,
        paramsJson = params {
            put("cost", required)
            put("perJade", GameConfig.JadePurchase.MERCHANT_REFRESH_PER_JADE)
            put("maxChances", GameConfig.JadePurchase.MERCHANT_REFRESH_MAX)
            put("nowMs", jadeSymbolService.wallClockNowMs())
        }
    ) ?: return null
    // 同突破率加成臂：落账完成 → 清 1Hz 节流刷新 UI + 涉钱事件请求立即落盘
    jadeSymbolService.publishJadeSymbolStateNow()
    jadeSymbolService.notifyMoneyLedgerChanged()
    DomainLog.i("GameEngine", "purchaseMerchantRefresh: native applied (jade=${data})")
    return MerchantRefreshResult.Success
}
