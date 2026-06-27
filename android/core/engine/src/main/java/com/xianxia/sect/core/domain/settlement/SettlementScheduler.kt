package com.xianxia.sect.core.engine.domain.settlement

import com.xianxia.sect.core.state.MutableGameState
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

class SettlementScheduler @Inject constructor() {
    private val pendingPhases = mutableListOf<SettlementPhase>()
    private var currentPhaseIndex = 0
    private var frameCount = 0
    private var aggressiveFrameCount = 0

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
        pendingPhases.add(agingPhase)
        pendingPhases.add(recruitPhase)
        pendingPhases.add(aiSectPhase)
        pendingPhases.add(alliancePhase)
    }

    suspend fun executeStep(shadow: MutableGameState): Boolean {
        if (!hasPendingWork) return true

        val isAggressive = aggressiveFrameCount < AGGRESSIVE_FRAME_LIMIT
        val budget = if (isAggressive) AGGRESSIVE_BUDGET_NS else CONSERVATIVE_BUDGET_NS
        val deadline = System.nanoTime() + budget
        frameCount++

        while (System.nanoTime() < deadline && hasPendingWork) {
            val completed = executeOnePhase(shadow)
            if (completed) {
                currentPhaseIndex++
            }
        }

        if (isAggressive) aggressiveFrameCount++
        return !hasPendingWork
    }

    private suspend fun executeOnePhase(shadow: MutableGameState): Boolean {
        val phase = pendingPhases.getOrNull(currentPhaseIndex) ?: return true
        return phase.execute(shadow)
    }

    fun reset() {
        pendingPhases.clear()
        currentPhaseIndex = 0
        frameCount = 0
    }

    fun getFrameCount(): Int = frameCount

    companion object {
        const val CONSERVATIVE_BUDGET_NS = 1_500_000L    // 1.5ms
        const val AGGRESSIVE_BUDGET_NS = 12_000_000L     // 12ms（保证 60fps）
        const val AGGRESSIVE_FRAME_LIMIT = 3              // 只在前 3 帧使用激进预算
    }
}
