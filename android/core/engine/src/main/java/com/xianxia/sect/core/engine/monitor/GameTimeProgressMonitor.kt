package com.xianxia.sect.core.engine.monitor

/**
 * ## 游戏时间推进监控 — 看门狗统一判据（第一类监控）
 *
 * 历史教训（git log）：27 次"游戏时间停止"修复中，三层看门狗（引擎内
 * Watchdog / 主线程 HealthCheck / Alarm 兜底）全部只判 tickCount 停滞，
 * 且全部豁免 isPaused——导致 `isPaused` 卡死、`speed=0` 假运行两类冻结
 * 形态完全失明。本组件把判据升级为"游戏时间推进三元组"（tickCount +
 * totalPhases + accumulatedGameMs），并统一三层的判定出口。
 *
 * 纯 JVM 组件（无 Android 依赖），[evaluate] 为纯函数，全分支单测覆盖
 * （历史防御机制自身失效 3 次的教训：禁止 else-if 分叉与手写分支）。
 */
data class GameTimeProgressSnapshot(
    /** 循环 tick 计数（假运行时也递增，不能单独作为推进判据） */
    val tickCount: Long,
    /** 世界时间绝对旬数（TimeSystem.getTotalPhases()）— 时间推进真相源 */
    val totalPhases: Long,
    /** GameTimeClock 当旬累积游戏毫秒（speed>0 时单调增长） */
    val accumulatedGameMs: Long,
    /** 游戏循环 Job 是否活跃 */
    val loopActive: Boolean,
    val isPaused: Boolean,
    val isSaving: Boolean,
    val isLoading: Boolean,
    /** GameTimeClock.speed（0 = 时钟暂停） */
    val speed: Int,
    /** 秘境暂停锁（secretRealmPauseLock） */
    val secretRealmPauseLock: Boolean,
    /** 秘境暂停租约最后续约墙钟（elapsedRealtime） */
    val secretRealmPauseRenewedAtMs: Long,
    /** 游戏循环体最近活动墙钟（每次迭代更新，含暂停/保存跳过路径） */
    val loopActiveAtMs: Long,
    /** 采样墙钟（elapsedRealtime） */
    val recordedAtMs: Long
)

/** 停滞判定结果 — when 穷尽消费，禁止 else 分支 */
sealed interface StallVerdict {
    /** 引擎健康，正常推进 */
    data object Healthy : StallVerdict

    /** 循环无活动（线程被 OEM 挂起 / 循环死亡 / 保存死锁等锁） */
    data object LoopStalled : StallVerdict

    /** tick 在跑但世界时间不动（speed=0 / 时钟异常） */
    data object FakeRunDetected : StallVerdict

    /** 暂停有主（用户主动暂停 / 秘境界面打开且租约有效）→ 豁免 */
    data object PausedByOwner : StallVerdict

    /** 暂停无主（锁残留 / 租约过期）→ 需要自愈 */
    data object StalePauseDetected : StallVerdict
}

/**
 * 停滞判定器 — 看门狗统一判据。
 *
 * 线程安全：引擎循环（采样）与看门狗线程（evaluate）并发访问，
 * 内部状态用 synchronized 保护（采样频率低，无性能压力）。
 */
class GameTimeProgressMonitor(
    /** 秘境暂停租约过期阈值：续约中断超过此时长视为界面已销毁（锁残留） */
    private val stalePauseTtlMs: Long = STALE_PAUSE_TTL_MS,
    /** 假运行判定窗口：时间冻结持续超过此时长才触发恢复（容忍短暂卡顿） */
    private val fakeRunWindowMs: Long = FAKE_RUN_WINDOW_MS
) {

    @Volatile
    private var prev: GameTimeProgressSnapshot? = null

    @Volatile
    private var freezeStartMs: Long = 0L

    /**
     * 判定当前引擎状态。每次调用更新内部基准（恢复判定后基准随之刷新）。
     *
     * 首次调用（无基准）也能给出不依赖 prev 的判定：暂停类判定
     * （PausedByOwner/StalePauseDetected）与保存豁免——仅"循环停滞/
     * 假运行"类需要基准的判定首次豁免。
     *
     * @param current 引擎循环最新采样快照
     * @return 停滞判定结果
     */
    @Suppress("ReturnCount") // 判据函数按序短路，多 return 是正确风格
    fun evaluate(current: GameTimeProgressSnapshot): StallVerdict {
        val last = prev
        if (last == null) {
            val flagVerdict = classifyFlags(current)
            if (flagVerdict != null) {
                prev = current
                return flagVerdict
            }
            prev = current
            return StallVerdict.Healthy
        }
        val verdict = classify(current, last)
        prev = current
        return verdict
    }

    /** 纯判定逻辑（与基准维护分离，便于测试与推理） */
    @Suppress("ReturnCount") // 判据函数按序短路，多 return 是正确风格
    private fun classify(current: GameTimeProgressSnapshot, last: GameTimeProgressSnapshot): StallVerdict {
        // 1. 保存/加载：循环仍在活动（含 skip 路径）→ 正常慢保存豁免；
        //    循环也停滞 → 保存死锁或引擎死亡，需恢复
        if (current.isSaving || current.isLoading) {
            val loopStaleMs = current.recordedAtMs - current.loopActiveAtMs
            return if (loopStaleMs <= LOOP_ACTIVITY_STALE_MS) {
                StallVerdict.Healthy
            } else {
                StallVerdict.LoopStalled
            }
        }

        // 2. 暂停判定（历史教训 a63338f3：用户主动暂停永不自动恢复）
        classifyFlags(current)?.let { return it }

        // 3. 循环死亡：非暂停但循环 Job 已不活跃
        if (!current.loopActive) return StallVerdict.LoopStalled

        // 4. tick 停滞：循环活跃但 tickCount 不推进（线程被挂起）
        if (current.tickCount == last.tickCount) return StallVerdict.LoopStalled

        // 5. 假运行检测：tick 在跑但世界时间不动
        if (current.speed == 0) {
            // speed=0 等价假运行（UI 已封死 0，出现即异常，立即自愈不等窗口）
            return StallVerdict.FakeRunDetected
        }
        val timeProgressed = current.totalPhases != last.totalPhases ||
            current.accumulatedGameMs > last.accumulatedGameMs
        if (!timeProgressed) {
            if (freezeStartMs == 0L) {
                freezeStartMs = current.recordedAtMs
                return StallVerdict.Healthy // 首次冻结不判定，容忍短暂卡顿
            }
            return if (current.recordedAtMs - freezeStartMs > fakeRunWindowMs) {
                StallVerdict.FakeRunDetected
            } else {
                StallVerdict.Healthy
            }
        }

        freezeStartMs = 0L
        return StallVerdict.Healthy
    }

    /** 不依赖 prev 基准的 flag 判定（保存豁免 + 暂停语义） */
    @Suppress("ReturnCount") // 判据函数按序短路，多 return 是正确风格
    private fun classifyFlags(current: GameTimeProgressSnapshot): StallVerdict? {
        if (current.isSaving || current.isLoading) return StallVerdict.Healthy
        if (current.isPaused) {
            if (!current.secretRealmPauseLock) return StallVerdict.PausedByOwner
            val leaseExpired = current.secretRealmPauseRenewedAtMs == 0L ||
                current.recordedAtMs - current.secretRealmPauseRenewedAtMs > stalePauseTtlMs
            return if (leaseExpired) {
                StallVerdict.StalePauseDetected
            } else {
                StallVerdict.PausedByOwner
            }
        }
        return null
    }

    companion object {
        /** 秘境暂停租约过期阈值（3 × 续约间隔 15s） */
        const val STALE_PAUSE_TTL_MS: Long = 45_000L

        /** 假运行判定窗口：时间冻结持续超过此时长触发恢复 */
        const val FAKE_RUN_WINDOW_MS: Long = 90_000L

        /** 循环活动停滞阈值：最后一次循环活动距今超过此时长视为循环停滞 */
        const val LOOP_ACTIVITY_STALE_MS: Long = 20_000L
    }
}
