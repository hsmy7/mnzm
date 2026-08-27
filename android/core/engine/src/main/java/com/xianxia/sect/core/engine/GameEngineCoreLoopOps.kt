package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.monitor.StallVerdict
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.NativeLoopPlan
import com.xianxia.sect.core.perf.ThermalState
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val TAG = "GameEngineCore"

/**
 * GameEngineCoreLoopOps — 计划 v2 阶段 5 AUTHORITATIVE 引擎循环接线
 * （独立文件承载以守住 GameEngineCore.kt 行数约束，模式同
 * GameEngineCoreAuthoritativeOps.kt）。
 *
 * 职责切分（阶段 5「游戏循环入 C++」）：
 * - **C++ 真相源**：帧累积/逻辑步进/墙钟消费（速度/暂停/refund 状态机）/
 *   tick 计数/循环心跳/看门狗判据——每帧一次 [GameCoreBridge.nativeLoopFrame]
 *   返回执行指令（[NativeLoopPlan]）
 * - **Kotlin 驱动侧（平台机制）**：协程线程本体/delay 与 OEM 反挂起忙等
 *   （adaptiveWait）/场景与闲置帧率策略/ADPF 上报/热控电量判据
 *   （ThermalController）与状态推送（平台能力接口化端口）
 * - **Kotlin 残留执行器**：每旬 ①-⑤ 互插管线（processAuthoritativeTick，
 *   阶段 2d 语义不变）
 * - **共享辅助**：tickInternal 与 AUTHORITATIVE 帧迭代共用的心跳/镜像回推/
 *   热控/残留职责/平台推送（自 GameEngineCore.kt 拆分，行数约束）
 *
 * 回退契约：native 帧计划不可用（未初始化/异常）→ 下一帧由
 * gameLoopIteration 走纯 Kotlin 累积器路径（代码全保留）。
 */

// ── 共享辅助（tickInternal 与 AUTHORITATIVE 帧迭代共用） ──

/** 循环活动心跳更新（含暂停分支——看门狗区分"正常慢保存/暂停"与"循环停滞"） */
internal fun GameEngineCore.notifyLoopActivity() {
    lastLoopActivityMs = gameClock.nowMs()
}

/** native 帧计划回推：tick 计数真相源镜像（UI/崩溃上下文消费方不变） */
internal fun GameEngineCore.publishNativeTickTotal(total: Long) {
    _tickCount.value = total
}

/** native 帧计划回推：插值因子（经 JitterSmoother 一阶滤波，渲染契约不变） */
internal fun GameEngineCore.publishNativeAlpha(rawAlpha: Float) {
    currentAlpha = jitterSmoother.filter(rawAlpha)
}

/** 电量感知热控（低电量提前降载 + 帧率驱动降级判据；消费者阶段 6 迁 C++） */
internal fun GameEngineCore.tickThermalControl() {
    thermalController.setThresholdOffsetC(batteryStatusProvider.thermalThresholdOffsetC)
    thermalController.checkAndAdjust(_fps.value)
}

/** 每 tick 尾部残留职责：年变分帧 drain + 巡逻结果入待战斗 */
internal suspend fun GameEngineCore.postTickResidualDuties() {
    cultivationService.drainYearlyOpsQueue()
    val patrolResults = explorationService.consumePendingPatrolResults()
    for (result in patrolResults) {
        stateStore.setPendingBattleResult(result)
    }
}

/** ADPF 帧耗时上报（平台通道；AUTHORITATIVE 帧计划回传 frameDeltaNs） */
internal fun GameEngineCore.reportAdpfWorkDuration(deltaNs: Long) {
    thermalMonitor.reportActualWorkDuration(deltaNs)
}

/**
 * 平台能力推送（阶段 5 接口化）：Kotlin 平台层热控/电量状态 → C++
 * Settable 端口（遥测记录/后续阶段判据消费）。失败静默（推送为尽力而为）。
 */
internal fun GameEngineCore.pushPlatformStatusToNative() {
    if (!NativeEngineFlag.authoritative) return
    runCatching {
        GameCoreBridge.nativeLoopSetBatteryStatus(
            isLowBattery = batteryStatusProvider.isLowBattery,
            isPowerSaveMode = batteryStatusProvider.isPowerSaveMode,
            fpsCap = batteryStatusProvider.fpsCap,
            thermalThresholdOffsetC = batteryStatusProvider.thermalThresholdOffsetC
        )
        GameCoreBridge.nativeLoopSetThermalStatus(
            thermalSeverityCode(thermalMonitor.thermalState.value)
        )
    }
}

/**
 * AUTHORITATIVE 单帧迭代（gameLoopIteration 判据迁移）。
 * 对应 Kotlin 原帧迭代：心跳/玉符 tick/暂停分支/固定步长（C++）/插值因子/
 * 闲置检测/自适应等待（Kotlin 平台机制）。
 */
@Suppress("TooGenericExceptionCaught", "ReturnCount")  // 崩溃兜底契约：单帧异常不得杀死循环
internal suspend fun GameEngineCore.authoritativeLoopIteration(): LoopIterationState {
    try {
        // 循环活动心跳（Kotlin 侧保留——日志/回退路径判据；native 侧由帧计划维护）
        notifyLoopActivity()
        // 玉符在线时长累计（暂停分支照常累计，语义同原循环顶部挂钩）
        jadeSymbolServiceRef.onLoopTick()

        val frameStartNs = System.nanoTime()
        val pausedOrLoading = stateStore.isPaused.value || stateStore.isLoading.value
        val plan = runCatching {
            NativeLoopPlan.unpack(
                GameCoreBridge.nativeLoopFrame(pausedOrLoading, stateStore.isSaving.value)
            )
        }.getOrNull()
        if (plan == null) {
            // 引擎未初始化/协议异常：本帧回退 Kotlin 累积器路径（由调用方走原分支）
            DomainLog.w(TAG, "AUTHORITATIVE 帧计划不可用，本帧回退 Kotlin 循环")
            return LoopIterationState(accumulatorNs = 0L, lastFrameTimeNs = 0L)
        }
        nativeLoopPipelineActive = true

        // 暂停/加载分支（handlePausedIteration 语义；死区消费已在 native 完成）
        if (plan.paused) {
            sampleProgressSnapshot()
            checkAndResetStuckStates(
                isSaving = stateStore.isSaving.value,
                isLoading = stateStore.isLoading.value
            )
            delay(50)
            return LoopIterationState(accumulatorNs = 0L, lastFrameTimeNs = 0L)
        }

        // 固定步长执行（native 已完成累积/步进/时间消费；tick 计数镜像回推）
        publishNativeTickTotal(plan.tickTotal)
        for (step in 0 until plan.tickCount) {
            if (plan.tickKind[step] == 0) {
                // isSaving 跳过 tick（skipTickIfNeeded 语义；死区已在 native 消费）
                checkAndResetStuckStates(
                    isSaving = stateStore.isSaving.value,
                    isLoading = stateStore.isLoading.value
                )
                continue
            }
            tickAuthoritativeStep(plan.tickPhases[step])
        }

        // 插值因子（JitterSmoother 一阶滤波留渲染侧）+ 时钟镜像推送
        publishNativeAlpha(plan.alpha)
        gameClock.mirrorFromNative(plan.accumulatedGameMs)

        // 闲置超时检测（判定维持 Kotlin 场景状态机；native idleNs 供渲染策略消费）
        checkIdleTimeout(System.nanoTime())

        // 平台能力推送（热控/电量 → C++ Settable 端口）
        pushPlatformStatusToNative()

        // 自适应等待（OEM 反挂起忙等为平台线程机制，保留 Kotlin）
        adaptiveWait(plan.tickCount, plan.frameDeltaNs, frameStartNs)
        updateRenderFrameRate()
        // ADPF 帧耗时上报（平台通道）
        reportAdpfWorkDuration(plan.frameDeltaNs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleTickCrash(e)
        runCatching { GameCoreBridge.nativeLoopConsumeDeadTime() }
        gameClock.consumeDeadTime()
    }
    // AUTHORITATIVE 下帧累积由 C++ 持有；返回占位状态（不走 Kotlin 累积器）
    return LoopIterationState(accumulatorNs = 0L, lastFrameTimeNs = 0L)
}

/** 每逻辑 tick 残留职责（tickInternal AUTHORITATIVE 化：时间消费已由 native 帧计划承担） */
internal suspend fun GameEngineCore.tickAuthoritativeStep(phasesToAdvance: Int) {
    // 进度快照采样（Kotlin 判据回退路径输入；AUTHORITATIVE 判据走 native）
    sampleProgressSnapshot()
    // 电量感知热控（判据消费者阶段 6 渲染统一迁入；本阶段维持 Kotlin）
    tickThermalControl()
    // ①-⑤ 互插（C++ 核心结算 + 增量镜像 + 残留执行器 + 边界编排 + 反向回导）
    processAuthoritativeTick(phasesToAdvance)
    // 年变分帧 drain + 巡逻结果（Kotlin 残留服务）
    postTickResidualDuties()
}

/**
 * 看门狗判定码 → [StallVerdict]（C++ ProgressMonitor 数值码；
 * 未知码/null → 调用方回退 Kotlin 判据）。
 */
internal fun nativeVerdictToStall(code: Int): StallVerdict? = when (code) {
    GameCoreBridge.VERDICT_HEALTHY -> StallVerdict.Healthy
    GameCoreBridge.VERDICT_LOOP_STALLED -> StallVerdict.LoopStalled
    GameCoreBridge.VERDICT_FAKE_RUN_DETECTED -> StallVerdict.FakeRunDetected
    GameCoreBridge.VERDICT_PAUSED_BY_OWNER -> StallVerdict.PausedByOwner
    GameCoreBridge.VERDICT_STALE_PAUSE_DETECTED -> StallVerdict.StalePauseDetected
    else -> null
}

/** 热控状态 → C++ ThermalState 数值码（0=None 1=Light 2=Moderate 3=Severe 5=Emergency） */
internal fun thermalSeverityCode(state: ThermalState): Int = when (state) {
    ThermalState.NORMAL -> 0
    ThermalState.LIGHT -> 1
    ThermalState.MODERATE -> 2
    ThermalState.SEVERE -> 3
    ThermalState.EMERGENCY -> 5
}
