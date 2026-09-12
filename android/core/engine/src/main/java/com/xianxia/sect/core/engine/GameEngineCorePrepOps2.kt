package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.engine.system.TimeSystem
import com.xianxia.sect.core.engine.monitor.GameTimeProgressMonitor
import com.xianxia.sect.core.engine.monitor.GameTimeProgressSnapshot
import com.xianxia.sect.core.engine.monitor.StallVerdict
import kotlinx.coroutines.*
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.*

/** ADPF 目标帧时长纯函数：帧率 → 纳秒预算（10-60fps 钳制防除零/越界；internal 供单测） */
// ── GameEngineCore 拆分域 2/4（行为零变更） ──

// companion 纯函数文件级委托（扩展作用域不可直接引用 companion 成员；行为零变更）
internal fun frameDurationNs(fps: Int): Long = GameEngineCore.frameDurationNs(fps)
/**
 * 计算看门狗指数退避的下一个间隔。
 *
 * 规则：
 * - tick 有推进（已恢复）→ 重置为 [baseIntervalMs]
 * - tick 停滞 → 当前间隔翻倍，上限 [WATCHDOG_MAX_BACKOFF_MS]
 *
 * @param currentBackoffMs 当前退避间隔
 * @param baseIntervalMs 初始间隔（由 OemPowerProfile 驱动）
 * @param hasRecovered 本次检查 tick 是否有推进
 * @return 下一次检查的等待间隔
 */
private fun computeWatchdogBackoff(currentBackoffMs: Long, baseIntervalMs: Long, hasRecovered: Boolean): Long =
    GameEngineCore.computeWatchdogBackoff(currentBackoffMs, baseIntervalMs, hasRecovered)

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
private val TAG = GameEngineCore.TAG
private val LOGIC_DT_NS = GameEngineCore.LOGIC_DT_NS
private val MAX_ACCUMULATOR_NS = GameEngineCore.MAX_ACCUMULATOR_NS
private val GAME_DISPATCHER = GameEngineCore.GAME_DISPATCHER
private val WATCHDOG_DISPATCHER = GameEngineCore.WATCHDOG_DISPATCHER
/** 看门狗指数退避上限（毫秒） */
private val WATCHDOG_MAX_BACKOFF_MS = GameEngineCore.WATCHDOG_MAX_BACKOFF_MS
/**
 * 单帧迭代——心跳/玉符 tick/
 * delta 累计/暂停分支/固定步长 tick/插值因子/闲置超时/场景感知自适应等待。
 *
 * @param state 跨帧累计状态（accumulatorNs/lastFrameTimeNs）
 * @return 更新后的累计状态（暂停分支与异常路径会清零 accumulatorNs，语义同原 continue）
 */
@Suppress("TooGenericExceptionCaught", "ReturnCount") // AUTHORITATIVE 分流提前返回（第 3 个 return）——判据在 C++，本方法仅剩回退路径
internal suspend fun GameEngineCore.gameLoopIteration(state: LoopIterationState): LoopIterationState {
    var accumulatorNs = state.accumulatorNs
    var lastFrameTimeNs = state.lastFrameTimeNs
    try {
        // AUTHORITATIVE 模式下帧迭代判据（累积/步进/
        // 墙钟消费/心跳）由 C++ EngineLoop 真相源驱动；帧计划不可用自动
        // 回退本方法剩余的纯 Kotlin 路径（零行为变化）
        if (NativeEngineFlag.authoritative && ensureAuthoritativeNative()) {
            return authoritativeLoopIteration()
        }
        nativeLoopPipelineActive = false
        // 循环活动心跳：每次迭代更新（含暂停分支与 skip 路径），
        // 供看门狗区分"正常慢保存/暂停"与"循环停滞/引擎死亡"
        lastLoopActivityMs = gameClock.nowMs()

        // 玉符在线时长累计：循环顶部挂钩（暂停分支 continue 在其后执行 →
        // 挂机/暂停照常累计；切后台循环整体停止 → 自然不累计）
        jadeSymbolService.onLoopTick()

        // Step 1: 计算 deltaTime
        val nowNs = System.nanoTime()
        var deltaNs = nowNs - lastFrameTimeNs
        lastFrameTimeNs = nowNs
        deltaNs = deltaNs.coerceAtMost(MAX_ACCUMULATOR_NS)

        // Step 2: 暂停/加载时不积累
        if (stateStore.isPaused.value || stateStore.isLoading.value) {
            return handlePausedIteration(lastFrameTimeNs)
        }

        // Step 3: Accumulate + FixedUpdate
        accumulatorNs += deltaNs
        var stepsExecuted = 0
        while (accumulatorNs >= LOGIC_DT_NS && stepsExecuted < 5) {
            tickInternal()
            accumulatorNs -= LOGIC_DT_NS
            stepsExecuted++
        }

        // Step 4: 插值因子（经 JitterSmoother 一阶滤波——
        // 只平滑渲染契约 alpha，游戏时间由 GameTimeClock 独立累积不受影响）
        val rawAlpha = (accumulatorNs.toFloat() / LOGIC_DT_NS.toFloat()).coerceIn(0f, 1f)
        currentAlpha = jitterSmoother.filter(rawAlpha)

        // Step 5: 闲置超时检测
        checkIdleTimeout(nowNs)

        // Step 6: 场景感知自适应等待（P1.3）
        adaptiveWait(stepsExecuted, deltaNs, nowNs)
        updateRenderFrameRate()
        // ADPF: 报告帧实际耗时（deltaNs 为帧间实际间隔）
        thermalMonitor.reportActualWorkDuration(deltaNs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleTickCrash(e)
        gameClock.consumeDeadTime()
        accumulatorNs = 0L
    }
    return LoopIterationState(accumulatorNs = accumulatorNs, lastFrameTimeNs = lastFrameTimeNs)
}

/** 单帧崩溃兜底：归因上下文采集 + 日志 + 异常上报，自身永不抛异常。
 *  internal（AUTHORITATIVE 帧迭代复用） */

internal fun GameEngineCore.handleTickCrash(e: Exception) {
    // 第一性原理：catch 块自身必须永不抛异常——任何状态读取失败
    // 都不能杀死游戏循环（测试曾复现：catch 内读 isPaused 抛异常 → 协程死亡）
    @Suppress("TooGenericExceptionCaught") // 防御性兜底：状态读取异常类型不可预期
    val crashContext = try {
        val gd = stateStore.gameDataSnapshot
        mapOf(
            "year" to gd.gameYear.toString(),
            "month" to gd.gameMonth.toString(),
            "phase" to gd.gamePhase.toString(),
            "tickCount" to _tickCount.value.toString(),
            "scene" to currentScene.name,
            "isPaused" to stateStore.isPaused.value.toString(),
            "isSaving" to stateStore.isSaving.value.toString(),
            "isLoading" to stateStore.isLoading.value.toString(),
            "speed" to gameClock.speed.toString(),
            "lastTickMs" to lastTickDurationMs.toString(),
            "watchdogAttempts" to watchdogRecoveryAttempts.toString(),
            "oem" to OemPowerProfileProvider.currentManufacturer.name
        )
    } catch (contextFailure: Exception) {
        mapOf("contextError" to (contextFailure.message ?: "unknown"))
    }
    DomainLog.e(TAG,
        "Game loop tick crashed: year=${crashContext["year"] ?: "?"} " +
        "tickCount=${_tickCount.value} scene=${currentScene}", e)
    // 异常归因上报：让下次回归有证据可循（历史多次"吞异常+重启"修复无归因）
    // 上报失败必须被吞——上报通道异常不得杀死游戏循环
    @Suppress("TooGenericExceptionCaught") // 上报通道失败类型不可预期，必须全吞
    try {
        engineCrashReporter.postCatchedException(e, crashContext)
    } catch (reportFailure: Exception) {
        DomainLog.w(TAG, "Crash reporter failed", reportFailure)
    }
}

/**
 * 暂停/加载迭代：暂停期间也刷新进度快照
 * （flags 实时化）——否则快照过期，看门狗无法判定秘境锁残留/用户暂停豁免。
 */

internal suspend fun GameEngineCore.handlePausedIteration(lastFrameTimeNs: Long): LoopIterationState {
    sampleProgressSnapshot()
    // 激活 60s 病理兜底：暂停分支必须执行 checkAndResetStuckStates
    // （跳过会使 isLoading 卡死时看门狗对其豁免且循环心跳持续跳动 →
    // 时间永久冻结、tick 驱动按钮全部无效且永不自愈）。
    // checkAndResetStuckStates 为非挂起纯函数，仅复位
    // isSaving/isLoading 标志 + 取消加载 Job，不触碰 isPaused。
    checkAndResetStuckStates(
        isSaving = stateStore.isSaving.value,
        isLoading = stateStore.isLoading.value
    )
    gameClock.consumeDeadTime()
    delay(50)
    return LoopIterationState(accumulatorNs = 0L, lastFrameTimeNs = lastFrameTimeNs)
}

/** 场景感知自适应等待（gameLoopIteration 拆分，P1.3）：无 tick 按帧预算等待，有 tick 防超预算。
 *  internal（AUTHORITATIVE 帧迭代复用——delay/忙等为平台线程机制保留 Kotlin） */

internal suspend fun GameEngineCore.adaptiveWait(stepsExecuted: Int, deltaNs: Long, nowNs: Long) {
    if (stepsExecuted == 0 && deltaNs < LOGIC_DT_NS) {
        // 无 tick 执行 → 按场景帧预算等待
        val budgetMs = currentScene.targetFrameTimeMs
        val consumedMs = (System.nanoTime() - nowNs) / 1_000_000
        val waitMs = (budgetMs - consumedMs).coerceIn(1L, budgetMs)
        delay(waitMs)
    } else {
        // 有 tick 执行 → 确保不超过场景帧预算
        val frameElapsedMs = (System.nanoTime() - nowNs) / 1_000_000
        val budgetMs = currentScene.targetFrameTimeMs
        if (frameElapsedMs < budgetMs) {
            antiFreezeDelay(budgetMs - frameElapsedMs, deltaNs / 1_000_000)
        }
    }
}

/** 停止体——调用方（stopGameLoop/shutdown）已完成状态机 CAS 且持锁后执行 */

internal fun GameEngineCore.stopGameLoopUnchecked() {
    unifiedPerformanceMonitor.stop()
    // 与 startGameLoopInternal 的 thermalMonitor.start(engineScope)
    // 对称——stopGameLoop/shutdown/pauseForBackground（后台切换走 stopGameLoop）
    // 全经本函数，热监控轮询不残留
    thermalMonitor.stop()
    stopWatchdog()
    stateStore.setPausedDirect(true)
    gameLoopJob?.cancel()
    gameLoopJob = null
}

/**
 * 等待循环停止。
 * 边界语义：kotlinx-coroutines 1.9.0 的 withTimeoutOrNull
 * 在 timeMillis<=0 时直接返回 null 不执行 block——停止实际已完成却报
 * false 误导调用方。显式处理：0/负超时视为"不等待，返回当前停止状态"
 */

internal suspend fun GameEngineCore.waitForLoopStopped(timeoutMs: Long): Boolean {
    if (timeoutMs <= 0) return !isGameLoopRunning
    return withTimeoutOrNull(timeoutMs) {
        gameLoopStoppedSignal.await()
        true
    } ?: run {
        DomainLog.w(TAG, "Game loop did not stop within ${timeoutMs}ms")
        false
    }
}

/**
 * 完整拆除引擎：停循环 + `releaseAll` +
 * 重建 engineScope + 重置 isInitialized。
 *
 * **调用语义**：`GameForegroundService.onDestroy` 不调用本方法
 * （仅 `stopGameLoop()`——初始化状态进程级持有，进出游戏不重跑 initializeAll）。
 * 本方法保留为进程级完整拆除路径，供未来"登出回主菜单释放资源"等场景使用
 * （见 docs/audio-thread-audit.md A2 偿还触发）。
 */

fun GameEngineCore.shutdown() {
    // 状态机：RUNNING/RESTARTING/STOPPING → STOPPED 单赢家；已 STOPPED
    // 幂等返回。抢占 emergency 的重启意图——emergency 启动前 phase 检查 abort
    if (transitionLoopPhase(
            setOf(LoopPhase.RUNNING, LoopPhase.RESTARTING, LoopPhase.STOPPING),
            LoopPhase.STOPPED
        ) == null
    ) {
        DomainLog.i(TAG, "shutdown: already stopped, idempotent")
        return
    }
    // 毒化态防御：拆除体（releaseAll/engineJob.cancel/重建 scope）
    // 在锁内执行——并发 start 的启动体等待锁，launch 必发生在拆除完成后
    // 的新 scope 上，不再出现"start 启动在旧 engineJob 上被迟到的 cancel
    // 连带取消 → phase=RUNNING 但循环死"的毒化态
    loopOpLock.withLock {
        stopGameLoopUnchecked()
        systemManager.releaseAll()
        engineJob.cancel()
        // 不关闭 GAME_DISPATCHER：shutdown 后可能重新 start，需保持线程池可用
        // 若必须关闭，需同时重建 GAME_DISPATCHER（静态 val 无法替换，故此处仅 cancel job）
        // WATCHDOG_DISPATCHER 同理：shutdown 后可能重新 startGameLoop → startWatchdog
        engineJob = SupervisorJob(scopeProvider.scope.coroutineContext[Job])
        engineScope = CoroutineScope(engineJob + gameDispatcher + engineExceptionHandler)
        isInitialized = false
        DomainLog.i(TAG, "GameEngineCore shutdown complete")
    }
}

// ── 独立看门狗 — 监控游戏线程是否被 PowerGenie 等 OEM 机制挂起 ──

/**
 * 启动独立看门狗协程，运行在专用非守护线程上。
 *
 * 每 3-5 秒检查一次 tickCount 是否有推进。如果在游戏循环声明为活跃
 * 的状态下 tickCount 停滞，说明游戏线程可能被 OEM 省电机制挂起，
 * 触发恢复流程。
 *
 * 看门狗线程：
 * - 非守护线程（isDaemon=false）：荣耀 MagicOS 等 OEM 会挂起守护线程池
 *   中的线程，非守护线程可防止看门狗自身被冻结。
 * - 独立单线程执行器：与 Dispatchers.Default 线程池完全隔离。
 * - 检查间隔由 [OemPowerProfileProvider.current] 数据驱动：
 *   激进 OEM（Honor/vivo）3s，中等 OEM（Xiaomi/OPPO）4s，保守 OEM 5s。
 */

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun GameEngineCore.startWatchdog() {
    stopWatchdog()
    // 跨重启累计恢复尝试次数（仅在 tick 推进时清零，不在重启时重置）
    // 连续失败超过阈值时进入降级模式，使用更长间隔减少频繁重启
    val baseIntervalMs = OemPowerProfileProvider.current.watchdogIntervalMs
    val degradedMode = watchdogRecoveryAttempts >= watchdogDegradedThreshold
    val effectiveBaseMs = if (degradedMode) {
        (baseIntervalMs * 4).coerceAtLeast(30_000L)  // 降级模式：至少 30s 间隔
    } else {
        baseIntervalMs
    }
    if (degradedMode && watchdogRecoveryAttempts == watchdogDegradedThreshold) {
        DomainLog.w(TAG,
            "Watchdog: entering degraded mode after $watchdogRecoveryAttempts " +
            "consecutive failures — increasing check interval to ${effectiveBaseMs / 1000}s")
    }
    watchdogJob = CoroutineScope(
        WATCHDOG_DISPATCHER + SupervisorJob() + watchdogExceptionHandler
    ).launch {
        // 当前退避间隔：失败后翻倍递增（如 3s→6s→12s→24s→30s 上限），成功后重置为初始间隔
        var currentBackoffMs = effectiveBaseMs
        while (isActive) {
            try {
            delay(currentBackoffMs)
            when (val verdict = progressVerdict()) {
                StallVerdict.Healthy, StallVerdict.PausedByOwner -> {
                    // 引擎健康 / 暂停有主（用户主动暂停或秘境租约有效）：重置失败计数
                    watchdogRecoveryAttempts = 0
                    currentBackoffMs = computeWatchdogBackoff(
                        currentBackoffMs, baseIntervalMs, hasRecovered = true
                    )
                }
                StallVerdict.LoopStalled, StallVerdict.FakeRunDetected,
                StallVerdict.StalePauseDetected -> {
                    watchdogRecoveryAttempts++
                    val atMaxBackoff = currentBackoffMs >= WATCHDOG_MAX_BACKOFF_MS
                    val shouldLog = !atMaxBackoff || watchdogRecoveryAttempts % 10 == 0
                    if (shouldLog) {
                        DomainLog.w(TAG,
                            "Watchdog: verdict=$verdict no progress in " +
                            "${currentBackoffMs / 1000}s " +
                            "(attempt $watchdogRecoveryAttempts" +
                            if (degradedMode) ", degraded" else "" +
                            ")")
                    }
                    handleWatchdogVerdict(verdict)
                    currentBackoffMs = computeWatchdogBackoff(
                        currentBackoffMs, effectiveBaseMs, hasRecovered = false
                    )
                }
            }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainLog.e(TAG, "Watchdog: loop error, continuing", e)
                delay(1000)
            }
        }
    }
}

/**
 * 游戏时间推进判定 — 三层看门狗（引擎内/主线程 HealthCheck/Alarm）统一出口。
 *
 * 判据为"tickCount + totalPhases + accumulatedGameMs"三元组（由
 * [GameTimeProgressMonitor] 纯函数判定），覆盖历史失明的两类冻结形态：
 * isPaused 卡死（StalePauseDetected）与 speed=0 假运行（FakeRunDetected）。
 */

fun GameEngineCore.progressVerdict(): StallVerdict {
    // AUTHORITATIVE 下看门狗统一判据走 native 真相源
    // （C++ ProgressMonitor 逐位移植 + 引擎侧状态组合）；native 不可用
    // （未加载/未初始化/未知码）自动回退 Kotlin 判据
    if (NativeEngineFlag.authoritative && GameCoreBridge.isLoaded &&
        GameCoreBridge.nativeIsInitialized()
    ) {
        val code = runCatching {
            GameCoreBridge.nativeWatchdogVerdict(
                loopActive = gameLoopJob?.isActive == true,
                isPaused = stateStore.isPaused.value,
                isSaving = stateStore.isSaving.value,
                isLoading = stateStore.isLoading.value,
                secretRealmPauseLock = secretRealmPauseLock,
                secretRealmPauseRenewedAtMs = secretRealmPauseRenewedAtMs
            )
        }.getOrDefault(-1)
        nativeVerdictToStall(code)?.let { return it }
    }
    val snapshot = lastProgressSnapshot
        ?: sampleProgressSnapshot()  // 循环从未 tick：flags-only 快照（暂停租约等仍可判定）
    /** 当前设备的电源管理配置 */
    val current = snapshot.copy(
        loopActive = gameLoopJob?.isActive == true,
        isPaused = stateStore.isPaused.value,
        isSaving = stateStore.isSaving.value,
        isLoading = stateStore.isLoading.value,
        secretRealmPauseLock = secretRealmPauseLock,
        secretRealmPauseRenewedAtMs = secretRealmPauseRenewedAtMs,
        loopActiveAtMs = lastLoopActivityMs,
        recordedAtMs = gameClock.nowMs()
    )
    return gameTimeProgressMonitor.evaluate(current)
}

/**
 * 采样进度快照：看门狗统一判据输入（tickCount + totalPhases + accumulatedGameMs + flags）。
 * 循环体每次迭代（含暂停/加载分支）调用，保证快照新鲜。
 * internal（GameEngineCoreLoopOps AUTHORITATIVE 帧迭代复用）。
 */

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun GameEngineCore.sampleProgressSnapshot(): GameTimeProgressSnapshot {
    /** 世界时间绝对旬数（TimeSystem.getTotalPhases()）— 时间推进真相源 */
    // V4：getSystem 缺失时抛 IllegalStateException（不是返回 null——`?: 0L` 是死代码），
    // 采样本身不得成为崩溃源——回退上次快照值
    val totalPhases = try {
        systemManager.getSystem(TimeSystem::class)?.getTotalPhases()?.toLong() ?: 0L
    } catch (e: Exception) {
        DomainLog.w(TAG, "getSystem(TimeSystem) failed, using last snapshot totalPhases", e)
        lastProgressSnapshot?.totalPhases ?: 0L
    }
    val snapshot = GameTimeProgressSnapshot(
        tickCount = _tickCount.value,
        totalPhases = totalPhases,
        accumulatedGameMs = gameClock.accumulatedGameMs,
        loopActive = gameLoopJob?.isActive == true,
        isPaused = stateStore.isPaused.value,
        isSaving = stateStore.isSaving.value,
        isLoading = stateStore.isLoading.value,
        speed = gameClock.speed,
        secretRealmPauseLock = secretRealmPauseLock,
        secretRealmPauseRenewedAtMs = secretRealmPauseRenewedAtMs,
        loopActiveAtMs = lastLoopActivityMs,
        recordedAtMs = gameClock.nowMs()
    )
    lastProgressSnapshot = snapshot
    return snapshot
}

/**
 * 看门狗自愈动作 — 引擎内看门狗与 Alarm 兜底共享的单一入口。
 *
 * @param verdict [progressVerdict] 的判定结果（仅需处理非健康/非豁免判定）
 */
