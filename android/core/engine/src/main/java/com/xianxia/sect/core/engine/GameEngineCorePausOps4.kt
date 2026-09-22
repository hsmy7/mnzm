package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.service.PhaseSettlementExecutor
import com.xianxia.sect.core.engine.service.PolicyCostResult
import com.xianxia.sect.core.concurrent.ThermalController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.xianxia.sect.core.engine.service.checkpointAllProduction

// ── GameEngineCore 拆分域 4/4（行为零变更） ──

private val TAG = GameEngineCore.TAG
private val SAVE_LOAD_STUCK_TIMEOUT_MS = GameEngineCore.SAVE_LOAD_STUCK_TIMEOUT_MS
private val TICK_TIME_BUDGET_MS = GameEngineCore.TICK_TIME_BUDGET_MS
private val MIN_REPORTED_FPS = GameEngineCore.MIN_REPORTED_FPS
private val MAX_REPORTED_FPS = GameEngineCore.MAX_REPORTED_FPS

fun GameEngineCore.clearBackgroundPauseFlag() {
    _wasPausedByBackground = false
    wasUserPausedBeforeBackground = false
}

/**
 * UI 层调用：通知引擎用户活跃。
 * 连续无操作 30s 后自动切到 IDLE 场景降帧保电。
 */

fun GameEngineCore.onUserInteraction() {
    onUserActivity()
}

internal suspend fun GameEngineCore.tickInternal() {
    if (skipTickIfNeeded()) return
    _tickCount.value++
    val tickStartNanos = System.nanoTime()
    val tickStartDiagnostic = if (OemPowerProfileProvider.currentManufacturer == OemManufacturer.XIAOMI)
        System.currentTimeMillis() else 0L
    // 进度快照采样：看门狗统一判据输入（tickCount + totalPhases + accumulatedGameMs）
    sampleProgressSnapshot()
    val tickResult = gameClock.tick(isSettlementPending = false)
    // 电量感知热控阈值偏移 + 帧率驱动降级（AUTHORITATIVE
    // 帧迭代每 tick 复用；checkAndAdjust 10s 间隔检查，此处仅浮点赋值无锁开销）
    tickThermalControl()
    if (ensureAuthoritativeNative()) {
        // 真相源切换——每旬标量通道 + 残留执行器
        // 互插。tick 结算恒走 native（单引擎终态，无
        // Kotlin 回退路径；OFF 仅影响逐动作转发，不影响 tick）
        processAuthoritativeTick(tickResult.phasesToAdvance)
    } else {
        // 单引擎终态：native 链路未就绪（.so 加载/初始化
        // 失败等）时本旬跳过结算并归还未落地旬数（时间不丢，native 恢复后
        // 自然追进）；持续不可用由看门狗停滞判据 → 紧急重启路径自愈
        gameClock.refundPhases(tickResult.phasesToAdvance)
        DomainLog.w(TAG,
            "tickInternal: native 引擎未就绪，本旬跳过结算 " +
                "(refund=${tickResult.phasesToAdvance}，看门狗自愈路径)")
    }
    // L3a 年变分帧：延迟组按 30ms 预算逐 tick drain（1 月重活分摊到后续 tick；
    // 非 1 月残留由 forceDrain 兜底不跨月）
    postTickResidualDuties()
    lastTickDurationMs = (System.nanoTime() - tickStartNanos) / 1_000_000
    if (tickStartDiagnostic > 0) {
        val tickDuration = System.currentTimeMillis() - tickStartDiagnostic
        if (tickDuration > TICK_TIME_BUDGET_MS) {
            DomainLog.w(TAG,
                "tick over budget: ${tickDuration}ms " +
                "(budget=${TICK_TIME_BUDGET_MS}ms, " +
                "month=${stateStore.gameData.value.gameMonth}, " +
                "year=${stateStore.gameData.value.gameYear})")
        }
    }
}

internal suspend fun GameEngineCore.skipTickIfNeeded(): Boolean {
    val isPaused = stateStore.isPaused.value
    val isLoading = stateStore.isLoading.value
    val isSaving = stateStore.isSaving.value
    if (!isPaused && !isLoading && !isSaving) return false
    checkAndResetStuckStates(isSaving, isLoading)
    gameClock.consumeDeadTime()
    if (_tickCount.value % 100 == 0L) {
        DomainLog.d(TAG, "tickInternal: tick #${_tickCount.value} skipped " +
            "(isPaused=$isPaused, isLoading=$isLoading, isSaving=$isSaving)")
    }
    return true
}

@Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
internal suspend fun GameEngineCore.processMonthYearChange(monthChanged: Boolean, yearChanged: Boolean) {
    if (yearChanged) {
        // 年变真相源切换：native 就绪走 C++ runYearSettlement
        // + Kotlin 残留执行器互插；native 未就绪回退 Kotlin 完整编排
        //（C++ 状态未变更——回退安全；nativeSettleYear 后失败传播自愈）
        if (!settleYearNative()) {
            // 年变编排（processYearlyEvents 分帧 + 1 月年俸）位于
            // YearSettlementExecutor（生产 tick 与跨语言对拍测试共用
            // 同一入口）；本方法仅保留委托。
            val gd = stateStore.gameData.value
            yearSettlementExecutor.execute(
                gameYear = gd.gameYear,
                isJanuary = gd.gameMonth == 1
            )
        }
    }
    if (monthChanged) {
        // 月变真相源切换：AUTHORITATIVE 下 C++ runMonthSettlement
        // + Kotlin 残留执行器互插；native 未就绪回退 Kotlin 完整八步编排。
        val env = try {
            settleMonthNative()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // nativeSettleMonth 已执行则 C++ 状态已变更——必须传播给外层
            // processAuthoritativeTick 的 refund + 看门狗自愈路径，**不得**
            // 回退 Kotlin 编排（C++ 已结算 + Kotlin 再结算 = 双份执行）
            DomainLog.w(TAG, "tickInternal: 月变 native 管线异常（传播自愈）: ${e.message}")
            throw e
        }
        if (env == null) {
            // 回退：native 未就绪（C++ 状态未变更——回退安全）
            // 八步月变编排（政策扣除/月效/AI 预计算/七系统扇出/血炼/排班忠诚/
            // 月衰减/月度事件）位于 MonthSettlementExecutor（
            // 生产 tick 与跨语言对拍测试共用同一入口）；本方法仅保留委托与
            // 事务外三件。
            var policyResult: PolicyCostResult = PolicyCostResult.AllPaid
            stateStore.update {
                policyResult = monthSettlementExecutor.execute(this)
            }
            if (policyResult is PolicyCostResult.SomeDisabled) {
                val disabledList = (policyResult as PolicyCostResult.SomeDisabled).disabledPolicies
                cultivationService.checkpointAllProduction()
                DomainLog.w(TAG, "tickInternal: policies auto-disabled due to insufficient spirit stones: " +
                    "${disabledList.joinToString(", ")}")
            }
        } else {
            // native 路径：policyCosts 决策（政策被禁用 → 重算生产 checkpoints）
            if (env.disabledPolicies.isNotEmpty()) {
                cultivationService.checkpointAllProduction()
                DomainLog.w(TAG, "tickInternal: policies auto-disabled due to insufficient spirit stones: " +
                    "${env.disabledPolicies.joinToString(", ")}")
            }
        }
        // 事务外尾务（原两分支重复段 SR-4 去重提取，顺序逐行不变）+ 月变完整结算发布
        finalizeMonthBoundary()
    }
}

/**
 * 月变尾务（SR-4 提取）：结算/政策 checkpoint 之后的三件——任务检测 → 灵石变更事件
 * 事务外 flush → **月变完整结算发布**（自动存档触发源，方案 D6/§4 SR-4）。
 *
 * 提取动机有二：① 原为 native/回退两分支各写一遍的重复段（去重，行为零变更）；
 * ② 让"月副作用完整结算之后才触发自动存档"成为**结构事实**——发布点是尾务最后一句，
 * 两分支的结算与 checkpoint 必然先于它返回，不必在两处各摆一次、也不会漏一处。
 */
internal suspend fun GameEngineCore.finalizeMonthBoundary() {
    missionCheck?.invoke()
    // 事务外 flush 灵石变更事件，避免 UI 层读到部分状态窗口
    spiritStoneWallet.flushPendingEvents(eventBus)
    notifyMonthSettled()
}

/** 每旬结算纯编排器（无状态，懒初始化复用同一实例；从 checkBreakthroughsAndPills 提取）。
 *
 * 生产 AUTHORITATIVE 每旬由 C++
 * runPhaseSettlementCore 完整结算，本属性与
 * [PhaseSettlementExecutor.execute] 完整版保留为跨语言对拍 Kotlin 基准
 * （DiffAuthoritativeTickTest 侧 B 使用）。
 */

internal fun GameEngineCore.checkAndResetStuckStates(
    isSaving: Boolean,
    isLoading: Boolean,
    nowMs: Long = System.currentTimeMillis()
) {
    // nowMs<=0 会静默失效（savingStartTime==0 判据恒真）
    // 或立即误触发（负值减出超大间隔）——防御性回退真实时钟
    val now = if (nowMs > 0) nowMs else System.currentTimeMillis()

    // 跟踪 isSaving 变为 true 的时间
    if (isSaving) {
        if (savingStartTime == 0L) {
            savingStartTime = now
        } else if (now - savingStartTime > SAVE_LOAD_STUCK_TIMEOUT_MS) {
            DomainLog.e(TAG, "isSaving has been true for ${now - savingStartTime}ms, force resetting")
            watchdogForceResetStuckStates("保存操作超时(${SAVE_LOAD_STUCK_TIMEOUT_MS / 1000}s)，已自动复位，请重试")
        }
    } else {
        savingStartTime = 0L
    }

    // 跟踪 isLoading 变为 true 的时间
    if (isLoading) {
        if (loadingStartTime == 0L) {
            loadingStartTime = now
        } else if (now - loadingStartTime > SAVE_LOAD_STUCK_TIMEOUT_MS) {
            DomainLog.e(TAG, "isLoading has been true for ${now - loadingStartTime}ms, force resetting")
            watchdogForceResetStuckStates("读档操作超时(${SAVE_LOAD_STUCK_TIMEOUT_MS / 1000}s)，已自动复位，请重试")
        }
    } else {
        loadingStartTime = 0L
    }
}

/**
 * 看门狗专用复位：先发用户可见事件再复位。
 * [forceResetStuckStates] 保持静默——onCleared 正常清理路径不可弹窗。
 */

internal fun GameEngineCore.watchdogForceResetStuckStates(reason: String) {
    _stuckResetEvents.tryEmit(reason)
    forceResetStuckStates()
}

/**
 * 注册当前正在运行的加载协程 Job，供看门狗强制取消。
 * 在 finally 块中应调用 [clearActiveLoadJob] 清除引用。
 *
 * 读-改-写加锁原子化——主线程注册与看门狗线程
 * 复位交错时，陈旧 cancel 会误杀新注册操作、`= null` 会使在途操作脱离看门狗监管。
 */

/**
 * 清除加载协程 Job 引用（协程正常结束时调用）。
 * 归属判定 + 清理原子完成——仅当 [activeLoadJob] === job
 * 时置 null 并返回 true；被新操作取代的旧 job（owned=false）不清理，
 * 避免旧协程 finally 抹掉新操作的在途状态与看门狗监管。
 * 看门狗 [forceResetStuckStates] 为全能路径，不受归属约束。
 *
 * @param job 发起清理的协程 Job
 * @return 是否归本 job（true 表示本次清理生效）
 */

fun GameEngineCore.clearActiveLoadJob(job: Job): Boolean = synchronized(activeLoadJobLock) {
    if (activeLoadJob === job) {
        activeLoadJob = null
        true
    } else {
        false
    }
}

/** activeLoadJob 互斥锁（注册/清除/看门狗复位三处共享） */

/**
 * 渲染线程上报渲染能力帧率（EWMA 反推，非墙钟帧率——挂机主动降帧时
 * 能力仍高，不会误触发热控降级），激活 [ThermalController] 的帧率驱动降级分支。
 *
 * 与引擎 tick 解耦：热控判据基于渲染能力帧率而非引擎逻辑帧率。
 * 渲染线程高频回调必须限频，避免 StateFlow 通知风暴。
 *
 * @param fps 渲染线程能力帧率（每秒回调一次）
 */

fun GameEngineCore.setObservedRenderFps(fps: Float) {
    if (fps <= 0f || fps.isNaN()) return
    // 限频用单调时钟（SystemClock.elapsedRealtime）：墙钟回拨（NTP 校正/手动改时间）
    // 会让差值变负导致限频失效，热控 fps 输入被长时间阻塞
    val now = android.os.SystemClock.elapsedRealtime()
    synchronized(fpsReportLock) {
        if (now - lastFpsReportMs < fpsReportIntervalMs) return
        lastFpsReportMs = now
        _fps.value = fps.coerceIn(MIN_REPORTED_FPS, MAX_REPORTED_FPS)
    }
}
