package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import kotlinx.coroutines.*
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.*
import com.xianxia.sect.core.engine.GameEngineCore.GameScene

// ── GameEngineCore 拆分域 1/4（行为零变更） ──

private val TAG = GameEngineCore.TAG
private val FPS_IDLE = GameEngineCore.FPS_IDLE
private val FPS_STILL = GameEngineCore.FPS_STILL
private val FPS_ACTIVE = GameEngineCore.FPS_ACTIVE
fun GameEngineCore.setPerformanceMode(mode: PerformanceMode) {
    if (performanceMode != mode) {
        DomainLog.i(TAG, "Performance mode: ${performanceMode.displayName} → ${mode.displayName}")
        performanceMode = mode
        updateRenderFrameRate()
    }
}

/**
 * 设置自选清晰度（UI 层调用），立即重算质量（渲染缩放/装饰 LOD 联动）。
 */

fun GameEngineCore.setClarityMode(mode: com.xianxia.sect.core.render.ClarityMode) {
    if (clarityMode != mode) {
        DomainLog.i(TAG, "Clarity mode: ${clarityMode.displayName} → ${mode.displayName}")
        clarityMode = mode
        updateRenderFrameRate()
    }
}

/** 设置游戏场景，引擎据此调整帧率预算和等待时间 */

fun GameEngineCore.onSceneChanged(scene: GameScene) {
    if (currentScene != scene) {
        DomainLog.i(TAG, "Scene changed: ${currentScene.displayName} → ${scene.displayName}")
        currentScene = scene
        // 先更新帧率再发布场景流：Game State 上报与帧率生效保持同序，
        // 避免系统收到新场景时读到旧帧率
        updateRenderFrameRate()
        sceneStateFlow.value = scene
    }
}

/** 玉符运行时状态（1Hz 节流，UI 徽章/倒计时订阅入口） */

internal fun GameEngineCore.sceneFpsFor(mode: PerformanceMode, scene: GameScene): Int = when (scene) {
    GameScene.IDLE -> FPS_IDLE
    // 滚动/惯性滑行是玩家注意力集中交互：恒 60fps（原 30fps 与手势引擎 16ms 节拍
    // 不匹配，相机 60fps 更新被渲染 30fps 丢帧 → 惯性滚动卡顿；省电仅影响松手后数秒）
    GameScene.MAP_SCROLL -> FPS_ACTIVE
    GameScene.GAMEPLAY -> if (mode == PerformanceMode.ENERGY_SAVING) FPS_STILL else FPS_ACTIVE
    GameScene.GAMEPLAY_IDLE -> FPS_STILL
    GameScene.BATTLE -> if (mode == PerformanceMode.ENERGY_SAVING) FPS_STILL else FPS_ACTIVE
}

/**
 * 计算有效渲染帧率：场景 × 性能模式 × 热控/电量 三者取 min（降级优先）。
 * internal（AUTHORITATIVE 帧迭代复用）。
 */

internal fun GameEngineCore.updateRenderFrameRate() {
    val thermalFps = thermalController.recommendedTargetFps
    val mode = performanceMode
    val sceneFps = sceneFpsFor(mode, currentScene)
    val batteryCap = batteryStatusProvider.fpsCap
    val effectiveFps = minOf(thermalFps, sceneFps, batteryCap)
    _renderFrameRate.value = effectiveFps
    _renderingQualityFactor.value = minOf(
        thermalController.renderingQualityFactor,
        mode.qualityFactor,
        clarityMode.qualityFactor
    )
    _decorationsDisabled.value = thermalController.particlesDisabled || mode == PerformanceMode.ENERGY_SAVING
}

/**
 * UI 层通知引擎：用户活跃（有触摸/操作），自动切换到 GAMEPLAY。
 * 均衡模式：闲置 5s 降 GAMEPLAY_IDLE(30fps)，闲置 30s 降 IDLE(10fps)。
 * 节能/性能模式：闲置 30s 直接降 IDLE(10fps)（深闲置是通用省电层，全模式保留）。
 */

fun GameEngineCore.onUserActivity() {
    lastUserActivityTimeNs = System.nanoTime()
    // 输入端口——AUTHORITATIVE 下用户活跃通知 native 引擎循环
    if (NativeEngineFlag.authoritative) {
        runCatching { GameCoreBridge.nativeLoopNotifyUserActivity() }
    }
    if (currentScene == GameScene.IDLE || currentScene == GameScene.GAMEPLAY_IDLE) {
        onSceneChanged(GameScene.GAMEPLAY)
    }
}

/**
 * 闲置降档纯函数：返回应切换的目标场景（null = 不切换）。
 *
 * 状态机：
 * - 均衡模式：GAMEPLAY(60) ──5s──> GAMEPLAY_IDLE(30) ──30s──> IDLE(10)
 * - 节能/性能：GAMEPLAY ──30s──> IDLE（无中间档）
 * - GAMEPLAY_IDLE / MAP_SCROLL：无操作 30s 后统一降 IDLE
 * - BATTLE / IDLE：不因闲置切换
 */

internal fun GameEngineCore.evaluateIdleTransition(
    scene: GameScene,
    idleNs: Long,
    mode: PerformanceMode
): GameScene? = when (scene) {
    GameScene.GAMEPLAY -> when {
        mode.dynamic && idleNs >= activityDowngradeTimeoutNs -> GameScene.GAMEPLAY_IDLE
        !mode.dynamic && idleNs >= idleTimeoutNs -> GameScene.IDLE
        else -> null
    }
    GameScene.GAMEPLAY_IDLE, GameScene.MAP_SCROLL ->
        if (idleNs >= idleTimeoutNs) GameScene.IDLE else null
    else -> null
}

/** 检查是否需要因闲置而降帧（两级降档：5s 静止 → 30fps，30s 深闲置 → 10fps）。
 *  internal（AUTHORITATIVE 帧迭代复用） */

internal fun GameEngineCore.checkIdleTimeout(nowNs: Long) {
    if (lastUserActivityTimeNs <= 0) return
    val target = evaluateIdleTransition(currentScene, nowNs - lastUserActivityTimeNs, performanceMode)
    if (target != null) {
        // 二次验证：判定与切换之间若发生触摸（时间戳已刷新、当前评估不再满足
        // 降档条件），放弃降档——否则"降档瞬间触摸"会被吞，玩家需再触摸一次才恢复
        if (evaluateIdleTransition(currentScene, nowNs - lastUserActivityTimeNs, performanceMode) == null) return
        onSceneChanged(target)
    }
}

fun GameEngineCore.launchInScope(

    block: suspend CoroutineScope.() -> Unit): Job = engineScope.launch(block = block
)

/**
 * 在引擎线程上执行指定代码块并返回结果。
 * 若当前已在引擎线程上，则不切换上下文（coroutines 自动优化）。
 * 用于确保 [stateStore.update] 调用在引擎线程上执行，避免主线程 ANR。
 */

fun GameEngineCore.scopeForStateIn(): CoroutineScope = engineScope
/** @Volatile（F3）：三个看门狗线程并发读 isActive，弱内存模型下非 volatile 可能读到陈旧 job */

fun GameEngineCore.initialize() {
    if (isInitialized) {
        DomainLog.w(TAG, "GameEngineCore already initialized")
        return
    }
    systemManager.initializeAll()
    isInitialized = true
    DomainLog.i(TAG, "GameEngineCore initialized")
    DomainLog.i(TAG, "GameEngineCore initialized successfully")
}

// catch Throwable 为 phase 中毒回滚语义必需（任何异常都须回滚状态机）

@Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
fun GameEngineCore.startGameLoop(resetWatchdogAttempts: Boolean = true) {
    if (gameLoopJob?.isActive == true) {
        // 冷启动读档竞态修复：循环已被第三方（前台服务 ACTION_START / watchdog
        // 兜底 / START_STICKY 重投递）抢先启动，而运行时玉符仍锚定在读档前的
        // 旧/空快照——boot 的 startGameLoop 走到此处若直接 return，后续
        // grantFromAd/checkpointNow/settleGrants 的绝对值写会覆盖已持久化余额
        // （玩家反馈：读档后看广告玉符 20→3）。无条件重锚（幂等：volatile
        // 内存写 + UI 发布，写库延迟到引擎线程首帧 tick，主线程调用安全）。
        jadeSymbolService.onLoopStart()
        DomainLog.w(TAG, "startGameLoop: 循环已运行，重锚玉符运行时")
        return
    }
    // 状态机：仅 STOPPED → RUNNING。RESTARTING（emergency 独占）/
    // STOPPING/重复 RUNNING 一律拒绝——防止 emergency 与正常启动交错出双循环
    val won = transitionLoopPhase(setOf(LoopPhase.STOPPED), LoopPhase.RUNNING)
    if (won == null) {
        DomainLog.w(TAG, "startGameLoop rejected (phase=${engineLoopPhase})")
        return
    }
    // CAS 只赢下转换意图，启动体必须在锁内执行——
    // stop/shutdown/emergency 的工作体与 start 的启动体串行化，过期拆除体
    // 不误杀新循环；epoch 取自 CAS 结果（原子），启动前/后校验世代未变
    val epoch = won.epoch
    loopOpLock.withLock {
        try {
            startGameLoopInternal(resetWatchdogAttempts, LoopPhase.RUNNING, epoch)
        } catch (t: Throwable) {
            // phase 中毒防御：启动体异常（drain/onLoopStart/
            // startWatchdog/launch 拒绝）→ 回滚 RUNNING → STOPPED，避免
            // phase 停 RUNNING 无 job 导致后续 start 永久被拒
            engineLoopState.compareAndSet(
                LoopState(LoopPhase.RUNNING, epoch),
                LoopState(LoopPhase.STOPPED, epoch)
            )
            throw t
        }
    }
}

/**
 * 启动体——公共 [startGameLoop]（expectedPhase=RUNNING）与
 * emergencyRestartGameLoop（expectedPhase=RESTARTING，由 emergency 自行
 * 收尾转 RUNNING）经 CAS + 锁后直调。launch 前/后双重校验 phase 与 epoch
 * 未变（根治孤儿循环/双循环）：
 * - launch 前：CAS 与锁之间被抢占（stop 已移走 phase / emergency 已递增
 *   epoch / emergency 的循环已启动）→ 放弃启动，不复活
 * - launch 后：防御性二次校验（锁纪律破坏时兜底）→ 撤销自身 job
 */

internal fun GameEngineCore.startGameLoopInternal(
    resetWatchdogAttempts: Boolean,
    expectedPhase: LoopPhase,
    expectedEpoch: Int
) {
    val preLaunchState = engineLoopState.get()
    if (preLaunchState.phase != expectedPhase || preLaunchState.epoch != expectedEpoch) {
        DomainLog.w(TAG, "startGameLoopInternal aborted (phase=${preLaunchState.phase}, " +
            "epoch=${preLaunchState.epoch}, expected=$expectedPhase/$expectedEpoch)")
        return
    }
    // 防御：emergency 的循环已在 CAS 与锁之间启动（仅理论 ABA 残留窗口）
    if (gameLoopJob?.isActive == true) {
        DomainLog.w(TAG, "startGameLoopInternal aborted (loop already active)")
        return
    }
    prepareLoopStart(resetWatchdogAttempts)

    // signal 跨代完成防御：捕获局部变量——旧循环 finally 完成的是
    // 捕获时点的 signal，而非字段。字段在每次启动时重建，旧 job 若读字段
    // 会完成新一代 signal：等待方挂满超时（重启中断）或提前放行（与运行中
    // 循环并发写状态）。捕获局部变量使跨代引用不可能发生
    val signal = gameLoopStoppedSignal
    val job = engineScope.launch { gameLoopMainLoop(signal) }
    gameLoopJob = job
    // launch 后二次校验：CAS 与 launch 之间被抢占
    // （stop/shutdown 移走 phase / emergency 递增 epoch）→ 撤销自身 job，
    // 循环不复活。锁内串行化下恒真，保留为锁纪律的防御性兜底
    if (engineLoopState.get().phase != expectedPhase ||
        engineLoopState.get().epoch != expectedEpoch
    ) {
        job.cancel()
        if (gameLoopJob === job) gameLoopJob = null
        DomainLog.w(TAG, "startGameLoopInternal cancelled after launch " +
            "(phase=${engineLoopPhase})")
    }
}

/**
 * 启动前置初始化：时钟/停止信号/监控/
 * 草稿排空/玉符锚定/暂停保留/看门狗启动。
 *
 * @param resetWatchdogAttempts 全新启动 true；emergencyRestart 传 false
 * （F6：保留降级计数，否则降级模式永不生效）
 */

internal fun GameEngineCore.prepareLoopStart(resetWatchdogAttempts: Boolean) {
    // 全新启动时重置看门狗累计失败计数，防止跨 session 残留
    // 导致第二次进入游戏时看门狗以降级模式（30s 间隔）启动
    if (resetWatchdogAttempts) {
        watchdogRecoveryAttempts = 0
    }

    gameClock.start()
    // AUTHORITATIVE 引擎循环时钟基准同步重置（native 未初始化时为
    // 安全空操作；ensureAuthoritativeNative 初始化完成后同样调用）
    if (NativeEngineFlag.authoritative) {
        runCatching { GameCoreBridge.nativeLoopStart() }
    }
    gameLoopStoppedSignal = CompletableDeferred()
    unifiedPerformanceMonitor.start()
    // 热状态监控绑定当前 engineScope——start 与
    // emergencyRestart 均经本函数（emergency 锁内已重建 scope，ThermalMonitor
    // start 无条件重建轮询 job 绑定新 scope）。stop 侧对称见
    // stopGameLoopUnchecked（stopGameLoop/shutdown/pauseForBackground 全经此）
    thermalMonitor.start(engineScope)
    // ADPF 目标帧时长联动（平板省电）：有效帧率（场景/模式/热控/
    // 电量四层 min）任何一次重算 → 系统性能预算同步。专用 job 重启前取消，
    // 防 stop→start 周期在单 scope 内堆积多个 collect 协程
    adpfTargetJob?.cancel()
    adpfTargetJob = engineScope.launch {
        renderFrameRate.collect { fps ->
            thermalMonitor.setTargetWorkDuration(frameDurationNs(fps))
        }
    }
    // 崩溃恢复：启动/重启（含 emergencyRestart）必经路径——排空上次
    // 崩溃遗留的持久化草稿（幂等，无草稿即空转；非挂起异步调度不阻塞启动）
    overflowMailHandler.drainPersistedDrafts()

    // 玉符在线时长结算：从 GameData 快照恢复运行时字段（读档/切档/重启天然正确）。
    // 只读快照零写入——跨天检查/首次锚定的 GameData 写入延迟到引擎线程首帧 tick
    // （startGameLoop 可能在主线程被调，update 有主线程运行时守卫）
    jadeSymbolService.onLoopStart()

    // 零触摸会话修复：游戏循环启动即视为活跃起点——否则 lastUserActivityTimeNs
    // 恒 0 导致 checkIdleTimeout 提前返回，动态帧率在无人值守挂机时永不降档
    lastUserActivityTimeNs = System.nanoTime()

    // F4：无条件置 false 会静默丢掉秘境暂停/用户暂停——按暂停来源保留，
    // 作为 startGameLoop 的通用职责（所有重启路径统一语义，不再只在
    // resumeFromBackground 一处补偿）
    val preservePause = secretRealmPauseLock || wasUserPausedBeforeBackground
    stateStore.setPausedDirect(preservePause)
    DomainLog.i(TAG, "Game state resumed (isPaused=$preservePause)")

    val gd = stateStore.gameDataSnapshot
    DomainLog.i(TAG, "startGameLoop: lifecycle=${stateStore.bootPhase.value}/${stateStore.runState.value}, " +
        "speed=${gameClock.speed}, " +
        "year=${gd.gameYear}, month=${gd.gameMonth}, " +
        "sectName=${gd.sectName}")

    // 启动独立看门狗，监控游戏线程是否被 PowerGenie 等 OEM 挂起
    startWatchdog()
}
