package com.xianxia.sect.core.engine

import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException

private const val YEAR_TAG = "GameEngineCore"

/**
 * 年变真相源切换管线（批 Y-switch）：生产年变路径从 Kotlin YearSettlementExecutor
 * 编排切换为 C++ `runYearSettlement` + Kotlin 残留执行器互插——
 * ① nativeSettleYear——C++ 完整年变（T1 已下沉面 + T2 已下沉面 + 年报 + 年俸）；
 * ② applyDirtyFromNative——增量镜像（失败先全量兜底，仍失败异常传播）；
 * ③ Kotlin 残留执行器（单事务：死亡链 ③ + 招募生成 ④ + AI 招募 ② + 商人
 *    收购 ③ + 交易刷新 ④）。
 *
 * 返回 false 表示 native 未就绪（调用方回退 Kotlin 完整编排——此时 C++
 * 状态未变更，回退安全）；返回 true 表示 C++ 状态已变更——**此点之后任何
 * 失败必须抛异常传播（processAuthoritativeTick 的 refund + 看门狗自愈），
 * 不得回退 Kotlin 编排**（否则 C++ 已结算 + Kotlin 再结算 = 双份执行）。
 *
 * 失败语义与旬/月变管线同构；RNG 行为基线（T1-⑨ 先于 T1-④）登记于
 * YearSettlementResidualExecutor KDoc。
 */
@Suppress("TooGenericExceptionCaught")  // 降级契约：native 链路失败统一 refund+重抛
internal suspend fun GameEngineCore.settleYearNative(): Boolean {
    if (!GameCoreBridge.isLoaded || !GameCoreBridge.nativeIsInitialized()) return false
    return try {
        // ① C++ 完整年变结算（信封当前为空——年变残留无 C++ 草稿）
        GameCoreBridge.nativeSettleYear()
        // ② 增量镜像；失败先全量兜底，仍失败走异常回退路径
        val applied = stateSyncServiceRef.applyDirtyFromNative()
        if (applied == null && !stateSyncServiceRef.syncFromNative()) {
            error("年变镜像失败（增量+全量均不可用）")
        }
        // ③ Kotlin 残留执行器（单事务——C++ 状态已变更，此处失败必须传播）
        stateStore.update { yearSettlementResidualExecutor.execute(this) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.w(YEAR_TAG, "年变 native 管线异常（C++ 状态已变更，传播至看门狗自愈）: ${e.message}")
        throw e
    }
}
