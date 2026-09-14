package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.engine.monitor.GameTimeProgressMonitor
import com.xianxia.sect.core.engine.monitor.StallVerdict
import kotlinx.coroutines.*
import kotlin.concurrent.withLock
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
// ── GameEngineCore 拆分域 3/4（行为零变更） ──

private val TAG = GameEngineCore.TAG
private val GAME_DISPATCHER = GameEngineCore.GAME_DISPATCHER
/** 旧游戏线程有限 join 截止（秒） */
private val GAME_THREAD_JOIN_TIMEOUT_S = GameEngineCore.GAME_THREAD_JOIN_TIMEOUT_S
/** 被替换的旧 GameEngine-Thread 在截止内未退出计数（OEM 挂起病理观测，
 *  对应审计验证点 11 的 ps -T 线程峰值口径） */
private val leakedGameThreadCount = GameEngineCore.leakedGameThreadCount
/** 看门狗恢复动作最小间隔：防止 OEM 反复挂起时雪崩式换线程（与 Alarm 限频一致） */
private val MIN_WATCHDOG_RECOVERY_INTERVAL_MS = GameEngineCore.MIN_WATCHDOG_RECOVERY_INTERVAL_MS
/**
 * 看门狗自愈动作 — 引擎内看门狗与 Alarm 兜底共享的单一入口。
 *
 * @param verdict [progressVerdict] 的判定结果（仅需处理非健康/非豁免判定）
 */
fun GameEngineCore.handleWatchdogVerdict(verdict: StallVerdict) {
    when (verdict) {
        StallVerdict.Healthy, StallVerdict.PausedByOwner -> Unit
        StallVerdict.FakeRunDetected -> {
            if (gameClock.speed == 0) {
                // speed=0 假运行：时钟暂停但循环健康，直接恢复 1x（不动线程）
                DomainLog.w(TAG,
                    "Watchdog: fake run detected (speed=0), restoring speed to 1x")
                gameClock.setSpeed(1)
                gameClock.consumeDeadTime()
            } else {
                // speed>0 但世界时间冻结：时钟异常，走换线程恢复
                DomainLog.w(TAG,
                    "Watchdog: fake run detected (time frozen at speed=${gameClock.speed}), " +
                    "recovering with new thread")
                performWatchdogRecovery()
            }
        }
        StallVerdict.StalePauseDetected -> {
            // 秘境暂停锁残留（界面已销毁但 exitExploration 丢失）→ 自愈
            val staleSeconds = (gameClock.nowMs() - secretRealmPauseRenewedAtMs) / 1000
            DomainLog.w(TAG,
                "Watchdog: stale secret-realm pause detected ($staleSeconds" +
                "s without renewal), self-healing")
            secretRealmPauseLock = false
            secretRealmPauseRenewedAtMs = 0L
            stateStore.setPausedDirect(false)
            // V5：不主动重启循环——后台场景避免后台推进游戏时间；
            // 回前台由 resumeFromBackground 重启（_wasPausedByBackground 已置位），
            // 引擎挂起场景由看门狗下一轮 LoopStalled → 换线程恢复
        }
        StallVerdict.LoopStalled -> performWatchdogRecovery()
    }
}

/**
 * 看门狗恢复动作（换全新线程），带 60s 限频防雪崩。
 * 仅看门狗触发路径经由此限频；外部显式调用（onResume/主线程 HealthCheck/Alarm）
 * 直接调 [emergencyRestartGameLoop] 不受限。
 */

internal fun GameEngineCore.performWatchdogRecovery() {
    val now = gameClock.nowMs()
    // S8：lastWatchdogRecoveryMs==0 表示开机后首次恢复——不拦截（否则开机 1 分钟内
    // 首次停滞恢复被延迟最长 60s）
    val throttled = lastWatchdogRecoveryMs != 0L &&
        now - lastWatchdogRecoveryMs < MIN_WATCHDOG_RECOVERY_INTERVAL_MS
    if (throttled) {
        DomainLog.w(TAG,
            "Watchdog recovery throttled (interval=${MIN_WATCHDOG_RECOVERY_INTERVAL_MS}ms)")
        return
    }
    val previous = lastWatchdogRecoveryMs
    lastWatchdogRecoveryMs = now
    // 被拒的恢复（stop/shutdown 已抢占，phase
    // 非 RUNNING）也消耗 60s 限频预算 → 后续真实停滞恢复被限频拖延。
    // 拒绝时回滚预算——恢复动作未执行，不应计为一次有效恢复
    if (!emergencyRestartGameLoop()) {
        lastWatchdogRecoveryMs = previous
    }
}

/**
 * 重启看门狗（如果已死亡）。
 * 由主线程健康监控器调用。
 */

fun GameEngineCore.restartWatchdog() {
    if (watchdogJob?.isActive == true) return
    DomainLog.w(TAG, "Watchdog: restarting (was active=${watchdogJob?.isActive})")
    startWatchdog()
}

/**
 * 创建一个全新的游戏调度器（新线程）。
 * 用于 [emergencyRestartGameLoop]，当原 GAME_DISPATCHER 的线程被
 * OEM 电源管理挂起时，用全新线程替代。
 */

internal fun GameEngineCore.recreateGameDispatcher(): CoroutineDispatcher {
    val oldDispatcher = gameDispatcher
    val newDispatcher = Executors.newSingleThreadExecutor(ThreadFactory {
        val thread = Thread(it, "GameEngine-Thread")
        thread.priority = Thread.MAX_PRIORITY
        thread.isDaemon = false
        thread
    }).asCoroutineDispatcher()
    gameDispatcher = newDispatcher
    // F5/S9：旧 executor 队列清空并 shutdown——被 OEM 挂起的线程无法中断，
    // 但清理任务队列防止残留任务与新循环竞争；非静态单例才关闭
    // （GAME_DISPATCHER 是静态单例，shutdown 后未来引用会 RejectedExecutionException）
    if (oldDispatcher !== GAME_DISPATCHER) {
        (oldDispatcher as? ExecutorCoroutineDispatcher)?.executor
            ?.let { it as? ExecutorService }?.let { oldExecutor ->
                oldExecutor.shutdown()
                // shutdown 后有限 join（与 Kotlin 收尾对称）——
                // 可中断线程 2s 内退出；被 OEM 挂起的线程无法中断属预期，
                // 超时计数留痕（病理观测，对应审计验证点 11 的 ps -T 峰值口径）
                if (!oldExecutor.awaitTermination(GAME_THREAD_JOIN_TIMEOUT_S, TimeUnit.SECONDS)) {
                    leakedGameThreadCount.incrementAndGet()
                    DomainLog.w(TAG, "Old GameEngine-Thread did not terminate in " +
                        "${GAME_THREAD_JOIN_TIMEOUT_S}s (OEM suspend?) " +
                        "leakedCount=${leakedGameThreadCount.get()}")
                }
            }
    }
    DomainLog.w(TAG, "Created new game dispatcher (old thread may be suspended by OEM)")
    return newDispatcher
}

/**
 * 紧急重启游戏循环——创建全新调度器线程，绕过 OEM 线程挂起。
 *
 * 全恢复路径统一入口（引擎看门狗/主线程 HealthCheck/Alarm 兜底均调用）：
 * 创建一个全新的 GAME_DISPATCHER（新线程），确保不被 HyperOS 等
 * OEM 电源管理挂起的旧线程影响。可从任意线程调用（内部经协程协作式
 * 取消，重启流程可完整执行；看门狗触发路径经 [performWatchdogRecovery]
 * 60s 限频）。
 *
 * @return true=重启完成（RESTARTING→RUNNING 成功）；false=被拒绝
 * （stop/shutdown 已抢占/重入中/abort）——调用方据此回滚限频预算。
 */
// catch Throwable 为 phase 中毒回滚语义必需（任何异常都须回滚 RESTARTING）

/**
 * 紧急重启工作体：CAS 状态机闸 + 锁内
 * 拆除/重建/重启 + RESTARTING→RUNNING 收尾。
 *
 * @return true=重启完成（RESTARTING→RUNNING 成功）；false=被 stop/shutdown
 * 抢占（循环保持停止）——调用方据此回滚限频预算。
 */

internal fun GameEngineCore.performEmergencyRestart(): Boolean {
    // 状态机闸：仅 RUNNING → RESTARTING，且递增启动世代 epoch
    //（与 phase 原子绑定——start 从自身 CAS 结果
    // 取得的 epoch 一旦落后即放弃启动，闭合双循环 ABA 窗口）。
    // stop/shutdown 已抢占（STOPPING/STOPPED）时拒绝——否则 shutdown
    // 完成后 emergency 又重建循环（孤儿循环）。拒绝路径零副作用：
    // 未取消循环、未重建 dispatcher（不泄漏线程）
    val won = transitionLoopPhase(
        setOf(LoopPhase.RUNNING), LoopPhase.RESTARTING, bumpEpoch = true
    )
    if (won == null) {
        DomainLog.w(TAG, "EMERGENCY restart rejected (phase=${engineLoopPhase})")
        return false
    }
    val epoch = won.epoch
    // 紧急重启工作体在锁内执行，
    // 与 start/stop/shutdown 工作体串行化——重建的 dispatcher/launch 的
    // 循环不会与并发启动体交错；被 stop 抢占的拆除体等锁，不误杀新循环
    return loopOpLock.withLock {
        val gd = stateStore.gameDataSnapshot
        DomainLog.e(TAG, "EMERGENCY restart: year=${gd.gameYear}, " +
            "month=${gd.gameMonth}, recruitList.size=" +
            "${gd.recruitList.size}, sectName=${gd.sectName}")

        // 1. 取消旧游戏循环和看门狗
        gameLoopJob?.cancel()
        gameLoopJob = null
        stopWatchdog()

        // 2. 重置卡住的状态
        forceResetStuckStates()

        // 2.5 cancel 旧 engineJob（与 shutdown 的拆除对称）：
        // 不取消则旧 SupervisorJob 连同其 children 泄漏，随重启次数单调增长
        engineJob.cancel()

        // 3. 用全新调度器重建 engineScope
        engineJob = SupervisorJob(scopeProvider.scope.coroutineContext[Job])
        engineScope = CoroutineScope(engineJob + recreateGameDispatcher() + engineExceptionHandler)

        // 4. 消耗死区时间，防止时间跳变
        gameClock.consumeDeadTime()
        // native 引擎循环帧状态清零（换线程重启——首帧 delta 归零）
        if (NativeEngineFlag.authoritative) {
            runCatching { GameCoreBridge.nativeLoopOnRestart() }
        }

        // 5. 重启循环——保留降级计数（F6：startGameLoop 不清零，
        //    否则每次紧急重启后的新看门狗都从激进模式起步，降级模式永不生效）
        //    launch 前/后双重校验由 startGameLoopInternal 承担（phase/epoch
        //    未变才启动；被抢占即 abort，循环不复活）
        startGameLoopInternal(
            resetWatchdogAttempts = false,
            expectedPhase = LoopPhase.RESTARTING,
            expectedEpoch = epoch
        )

        // 6. 重启完成收尾：RESTARTING → RUNNING；被 stop 抢占（STOPPING/STOPPED）
        //    则保持——循环已由 stop 停止，不强行复位
        val restarted = transitionLoopPhase(
            setOf(LoopPhase.RESTARTING), LoopPhase.RUNNING
        )
        if (restarted == null) {
            DomainLog.w(TAG, "EMERGENCY restart: phase moved away " +
                "(${engineLoopPhase}), loop stays stopped")
        } else {
            DomainLog.i(TAG, "EMERGENCY restart complete")
        }
        restarted != null
    }
}

internal fun GameEngineCore.stopWatchdog() {
    watchdogJob?.cancel()
    watchdogJob = null
}

/**
 * 状态机 CAS 迁移：当前 phase ∈ [expected] → 迁移到 [new]。
 * 并发抢占方（stop/shutdown/emergency）先完成迁移时返回 null。
 * [bumpEpoch] 仅在 emergency 抢占 RUNNING 时置 true——递增启动世代，
 * 使并发 start 的 epoch 校验失败（放弃启动），闭合双循环 ABA 窗口。
 * 成功时返回新状态（原子）：startGameLoop/emergency 从中读取 epoch，
 * 避免"CAS 后另读 epoch"的读取窗口。
 * 循环重试处理 compareAndSet 的瞬时失败（期望态未被抢占时必然成功）。
 */

internal fun GameEngineCore.transitionLoopPhase(
    expected: Collection<LoopPhase>,
    new: LoopPhase,
    bumpEpoch: Boolean = false
): LoopState? {
    while (true) {
        /** 当前设备的电源管理配置 */
        val current = engineLoopState.get()
        if (current.phase !in expected) return null
        val next = if (bumpEpoch) {
            current.copy(phase = new, epoch = current.epoch + 1)
        } else {
            current.copy(phase = new)
        }
        if (engineLoopState.compareAndSet(current, next)) return next
    }
}

suspend fun GameEngineCore.pause() = withEngineContext {
    stateStore.update { isPaused = true }
    cultivationService.resetHighFrequencyData()
}

/** 恢复播放：从暂停点继续。未暂停则忽略。 */
suspend fun GameEngineCore.resume() = withEngineContext {
    stateStore.update { isPaused = false }
}


/**
 * 秘境探索暂停：若当前未暂停则暂停游戏时间并记录锁（退出探索时恢复）。
 * 用户在探索前手动暂停的场景不抢占。
 *
 * 同时记录暂停租约续约时间戳：UI 探索界面每 [SECRET_REALM_RENEW_INTERVAL_MS]
 * 调用 [renewSecretRealmPauseLease] 续约；续约中断超过
 * [GameTimeProgressMonitor.STALE_PAUSE_TTL_MS] 视为界面已销毁（onDispose 丢失），
 * 由看门狗以 StalePauseDetected 自愈。
 */
fun GameEngineCore.pauseForSecretRealm() {
    if (!stateStore.isPaused.value) {
        stateStore.setPausedDirect(true)
        secretRealmPauseLock = true
        secretRealmPauseRenewedAtMs = gameClock.nowMs()
    }
}

/** 秘境探索恢复：仅当暂停由秘境持有（进入探索时记录）时才恢复游戏时间。 */

fun GameEngineCore.resumeFromSecretRealm() {
    if (secretRealmPauseLock) {
        stateStore.setPausedDirect(false)
        secretRealmPauseLock = false
        secretRealmPauseRenewedAtMs = 0L
        // 自检：若游戏循环未运行则重启（防御探索期间后台恢复等路径导致循环丢失，
        // 不依赖 GameForegroundService 的隐式重启——代码质量审查问题 6）
        if (!isGameLoopRunning) {
            startGameLoop()
        }
    }
}

/**
 * 续约秘境暂停租约：由 UI 探索界面每 [SECRET_REALM_RENEW_INTERVAL_MS] 调用，
 * 证明"秘境界面仍打开中"（暂停由 UI 持有）。
 * 续约中断超过 [GameTimeProgressMonitor.STALE_PAUSE_TTL_MS] 后，
 * 看门狗判定锁残留并自愈，消除 Activity 重建导致 exitExploration 丢失的永久冻结路径。
 */

fun GameEngineCore.renewSecretRealmPauseLease() {
    secretRealmPauseRenewedAtMs = gameClock.nowMs()
}
