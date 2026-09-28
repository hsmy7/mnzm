package com.xianxia.sect.core.engine

import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.NativeLoopPlan

/**
 * AUTHORITATIVE 帧迭代的双臂执行体（从 [GameEngineCoreLoopOps] 提取，
 * detekt 文件函数数口径；臂选择 = `NativeEngineFlag.realtimeAccrual`，
 * 判据见 authoritativeLoopIteration）。
 *
 * 两臂语义（B4，结算改造 2026-09-27）：
 * - [runAccrualArm] 连续臂：单 tick 单事务（方案 §2.4）
 * - [runDiscreteArm] 离散臂（旧行为臂）：逐 tick 旬事务
 */

/**
 * 连续臂（结算改造 2026-09-27 B4，方案 §2.4 单 tick 单事务）。
 * 每帧一次：积分 Δt = 权威轴差分（INV-2 全额，不随分帧方式漂移）
 * → 单次增量镜像 → 边界派发（月/年叙事与执行器，非资源结算入口）。
 * 判定窗口（自动装备/丹药/突破）在 native 内按权威轴整数差执行
 *（INV-3，RNG 序列与离散臂逐位一致）。
 */
internal suspend fun GameEngineCore.runAccrualArm(plan: NativeLoopPlan) {
    sampleProgressSnapshot()
    tickThermalControl()
    val deltaGameMs = (plan.elapsedGameMs - accruedElapsedGameMs)
        .coerceAtLeast(0L)
    accruedElapsedGameMs = plan.elapsedGameMs
    val settleFlags = GameCoreBridge.nativeAccrue(deltaGameMs, true)
    val applied = stateSyncServiceRef.applyDirtyFromNative()
    if (applied == null && !stateSyncServiceRef.syncFromNative()) {
        error("AUTHORITATIVE 连续臂镜像失败（增量+全量均不可用）")
    }
    if (settleFlags != 0) {
        processMonthYearChange(
            monthChanged = (settleFlags and GameCoreBridge.FLAG_MONTH_CHANGED) != 0,
            yearChanged = (settleFlags and GameCoreBridge.FLAG_YEAR_CHANGED) != 0
        )
    }
    postTickResidualDuties()
}

/** 离散臂（旧行为臂）：逐 tick 旬事务；isSaving tick 跳过（死区已在 native 消费） */
internal suspend fun GameEngineCore.runDiscreteArm(plan: NativeLoopPlan) {
    for (step in 0 until plan.tickCount) {
        if (plan.tickKind[step] == 0) {
            // isSaving 跳过 tick（skipTickIfNeeded 语义）
            checkAndResetStuckStates(
                isSaving = stateStore.isSaving.value,
                isLoading = stateStore.isLoading.value
            )
            continue
        }
        tickAuthoritativeStep(plan.tickPhases[step])
    }
}
