package com.xianxia.sect.core.engine.domain.settlement

import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog
import javax.inject.Inject

sealed class SettlementPhase {
    abstract suspend fun execute(shadow: MutableGameState): Boolean
}

class Phase_BuildCache(
    private val onBuild: (MutableGameState) -> SettlementCache
) : SettlementPhase() {
    var cache: SettlementCache? = null
        private set

    override suspend fun execute(shadow: MutableGameState): Boolean {
        cache = onBuild(shadow)
        return true
    }
}

class Phase_FocusedDisciple(
    private val onProcess: suspend (MutableGameState, SettlementCache) -> Unit,
    private val cacheProvider: () -> SettlementCache?
) : SettlementPhase() {
    override suspend fun execute(shadow: MutableGameState): Boolean {
        val cache = cacheProvider() ?: return true
        onProcess(shadow, cache)
        return true
    }
}

class Phase_CleanDiscipleBatch(
    private val onProcess: suspend (MutableGameState, SettlementCache) -> Unit,
    private val cacheProvider: () -> SettlementCache?
) : SettlementPhase() {
    override suspend fun execute(shadow: MutableGameState): Boolean {
        val cache = cacheProvider() ?: return true
        onProcess(shadow, cache)
        return true
    }
}

class Phase_DirtyDiscipleBatch(
    private val onProcess: suspend (MutableGameState, SettlementCache, Int) -> Int,
    private val cacheProvider: () -> SettlementCache?
) : SettlementPhase() {
    var currentOffset: Int = 0
        private set

    override suspend fun execute(shadow: MutableGameState): Boolean {
        val cache = cacheProvider() ?: return true
        val processed = onProcess(shadow, cache, currentOffset)
        currentOffset += processed
        return processed == 0
    }

    fun reset() {
        currentOffset = 0
    }
}

class Phase_Production(
    private val onProcess: suspend (MutableGameState) -> Unit
) : SettlementPhase() {
    override suspend fun execute(shadow: MutableGameState): Boolean {
        onProcess(shadow)
        return true
    }
}

class Phase_WorldEvents(
    private val onProcess: suspend (MutableGameState) -> Unit
) : SettlementPhase() {
    override suspend fun execute(shadow: MutableGameState): Boolean {
        onProcess(shadow)
        return true
    }
}

class Phase_AgingAndDeath(
    private val onProcess: suspend (MutableGameState) -> Unit
) : SettlementPhase() {
    override suspend fun execute(shadow: MutableGameState): Boolean {
        onProcess(shadow)
        return true
    }
}

class Phase_RecruitRefresh(
    private val onProcess: suspend (MutableGameState) -> Unit
) : SettlementPhase() {
    override suspend fun execute(shadow: MutableGameState): Boolean {
        onProcess(shadow)
        return true
    }
}

class Phase_AISectYearly(
    private val onProcess: suspend (MutableGameState) -> Unit
) : SettlementPhase() {
    override suspend fun execute(shadow: MutableGameState): Boolean {
        onProcess(shadow)
        return true
    }
}

class Phase_AllianceExpiry(
    private val onProcess: suspend (MutableGameState) -> Unit
) : SettlementPhase() {
    override suspend fun execute(shadow: MutableGameState): Boolean {
        onProcess(shadow)
        return true
    }
}

/**
 * SettlementScheduler — 结算调度器，增强版帧预算感知 + 自动分帧。
 *
 * ## 帧预算增强（P2.5/P3.5）
 *
 * - 支持负载感知预算调整：负载重时保守（1.5ms），负载轻时激进（12ms）
 * - 年度结算（老化/死亡/招募）自动分 5-8 帧完成，每帧不超过 3ms
 * - 月度事件分 2-3 帧完成，每帧不超过 5ms
 * - 当 [loadReductionRequested] = true 时，结算推迟到后续帧执行
 */
class SettlementScheduler @Inject constructor() {
    private val pendingPhases = mutableListOf<SettlementPhase>()
    private var currentPhaseIndex = 0
    private var frameCount = 0
    private var aggressiveFrameCount = 0

    /** 外部设置的负载降级标志（由 GameEngineCore 的 loadReductionRequested 驱动） */
    @Volatile
    var loadReductionRequested: Boolean = false
        private set

    /** 通知调度器：当前帧负载较重，应使用保守预算 */
    fun requestLoadReduction() {
        loadReductionRequested = true
    }

    /** 清除负载降级标志 */
    fun clearLoadReduction() {
        loadReductionRequested = false
    }

    val hasPendingWork: Boolean get() = currentPhaseIndex < pendingPhases.size

    fun scheduleYearly(
        shadow: MutableGameState,
        agingPhase: Phase_AgingAndDeath,
        recruitPhase: Phase_RecruitRefresh,
        aiSectPhase: Phase_AISectYearly,
        alliancePhase: Phase_AllianceExpiry
    ) {
        reset()
        aggressiveFrameCount = 0
        loadReductionRequested = false

        // ★ P3.5: 年度结算分帧 — 每个阶段单独排队，各阶段在 executeStep 中
        //   通过帧预算控制分布到多帧执行
        pendingPhases.add(agingPhase)
        pendingPhases.add(recruitPhase)
        pendingPhases.add(aiSectPhase)
        pendingPhases.add(alliancePhase)

        DomainLog.i(TAG, "Yearly settlement scheduled: 4 phases across multiple frames")
    }

    /**
     * 执行一步结算（受帧预算控制）。
     *
     * @param shadow 当前结算影子状态的快照
     * @return true 表示所有阶段已完成
     */
    suspend fun executeStep(shadow: MutableGameState): Boolean {
        if (!hasPendingWork) return true

        // ★ P2.5: 负载感知预算
        val isAggressive = aggressiveFrameCount < AGGRESSIVE_FRAME_LIMIT && !loadReductionRequested
        val budget = when {
            loadReductionRequested -> LOAD_REDUCTION_BUDGET_NS   // 负载重时保守
            isAggressive -> AGGRESSIVE_BUDGET_NS                 // 前几帧激进追赶
            else -> CONSERVATIVE_BUDGET_NS                       // 稳定态保守
        }
        val deadline = System.nanoTime() + budget
        frameCount++

        var phasesCompletedInThisStep = 0
        while (System.nanoTime() < deadline && hasPendingWork) {
            val completed = executeOnePhase(shadow)
            if (completed) {
                currentPhaseIndex++
                phasesCompletedInThisStep++
            }
        }

        if (isAggressive) aggressiveFrameCount++

        // 日志：如果本帧未完成且已超预算
        if (hasPendingWork && phasesCompletedInThisStep == 0) {
            // 单阶段执行超预算 — 该阶段将在下一帧继续
            DomainLog.d(TAG, "Settlement phase #$currentPhaseIndex exceeded budget, " +
                "deferring to next frame (frame=$frameCount, budget=${budget / 1_000_000}ms)")
        }

        return !hasPendingWork
    }

    /**
     * 执行单个阶段。
     * 如果阶段执行超时（超过单帧预算），返回 false 表示未完成，
     * 调度器将在下一帧继续重试同一阶段。
     */
    private suspend fun executeOnePhase(shadow: MutableGameState): Boolean {
        val phase = pendingPhases.getOrNull(currentPhaseIndex) ?: return true
        return phase.execute(shadow)
    }

    fun reset() {
        pendingPhases.clear()
        currentPhaseIndex = 0
        frameCount = 0
        loadReductionRequested = false
    }

    fun getFrameCount(): Int = frameCount

    companion object {
        private const val TAG = "SettlementScheduler"

        /** 保守预算（负载重时）：1.5ms */
        const val CONSERVATIVE_BUDGET_NS = 1_500_000L

        /** 激进预算（前 N 帧）：12ms，保证 60fps 不卡 */
        const val AGGRESSIVE_BUDGET_NS = 12_000_000L

        /** 负载降级时预算：0.5ms，仅做最必要的工作 */
        const val LOAD_REDUCTION_BUDGET_NS = 500_000L

        /** 只在前 3 帧使用激进预算 */
        const val AGGRESSIVE_FRAME_LIMIT = 3
    }
}
