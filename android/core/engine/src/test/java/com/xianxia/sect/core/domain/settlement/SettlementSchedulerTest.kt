package com.xianxia.sect.core.engine.domain.settlement

import com.xianxia.sect.core.engine.domain.settlement.Phase_AgingAndDeath
import com.xianxia.sect.core.engine.domain.settlement.Phase_RecruitRefresh
import com.xianxia.sect.core.engine.domain.settlement.Phase_AISectYearly
import com.xianxia.sect.core.engine.domain.settlement.Phase_AllianceExpiry
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SettlementScheduler 直接单元测试。
 *
 * 覆盖（P2.5/P3.5）：
 * - 初始状态无待执行工作
 * - scheduleYearly 注册 Aging/Recruit/AISect/Alliance 四个阶段
 * - executeStep 按顺序执行所有阶段
 * - loadReductionRequested 切换预算模式
 * - reset 清除所有状态
 * - frameCount 递增
 */
@RunWith(RobolectricTestRunner::class)
class SettlementSchedulerTest {

    // ============================================================
    // 辅助
    // ============================================================

    private fun emptyState(): MutableGameState = MutableGameState(
        gameData = GameData(),
        discipleTables = DiscipleTables(),
        equipmentStacks = EntityStore(),
        equipmentInstances = EntityStore(),
        manualStacks = EntityStore(),
        manualInstances = EntityStore(),
        pills = EntityStore(),
        materials = EntityStore(),
        herbs = EntityStore(),
        seeds = EntityStore(),
        storageBags = EntityStore(),
        teams = emptyList(),
        battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )

    // ============================================================
    // 初始状态
    // ============================================================

    @Test
    fun `new scheduler has no pending work`() {
        val scheduler = SettlementScheduler()
        assertFalse("新建调度器应无待执行工作", scheduler.hasPendingWork)
    }

    @Test
    fun `initial loadReductionRequested is false`() {
        val scheduler = SettlementScheduler()
        assertFalse("初始负载降级标志应为 false", scheduler.loadReductionRequested)
    }

    // ============================================================
    // scheduleYearly 阶段注册与执行
    // ============================================================

    @Test
    fun `scheduleYearly registers 4 phases in order`() {
        val scheduler = SettlementScheduler()
        val state = emptyState()
        val executedPhases = mutableListOf<String>()

        scheduler.scheduleYearly(
            shadow = state,
            agingPhase = Phase_AgingAndDeath { _ -> executedPhases.add("Aging") },
            recruitPhase = Phase_RecruitRefresh { _ -> executedPhases.add("Recruit") },
            aiSectPhase = Phase_AISectYearly { _ -> executedPhases.add("AISect") },
            alliancePhase = Phase_AllianceExpiry { _ -> executedPhases.add("Alliance") }
        )

        assertTrue("调度后应有待执行工作", scheduler.hasPendingWork)

        runBlocking {
            while (scheduler.hasPendingWork) {
                scheduler.executeStep(state)
            }
        }

        assertEquals(
            "年度结算阶段应按 Aging→Recruit→AISect→Alliance 顺序执行",
            listOf("Aging", "Recruit", "AISect", "Alliance"),
            executedPhases
        )
    }

    @Test
    fun `executeStep reports completion when all phases done`() {
        val scheduler = SettlementScheduler()
        val state = emptyState()

        scheduler.scheduleYearly(
            shadow = state,
            agingPhase = Phase_AgingAndDeath { _ -> },
            recruitPhase = Phase_RecruitRefresh { _ -> },
            aiSectPhase = Phase_AISectYearly { _ -> },
            alliancePhase = Phase_AllianceExpiry { _ -> }
        )

        runBlocking {
            val completed = scheduler.executeStep(state)
            assertTrue("所有阶段应在一次 executeStep 内完成", completed)
        }

        assertFalse("完成后应无待执行工作", scheduler.hasPendingWork)
    }

    // ============================================================
    // loadReductionRequested
    // ============================================================

    @Test
    fun `loadReductionRequested flag is settable and readable`() {
        val scheduler = SettlementScheduler()

        assertFalse(scheduler.loadReductionRequested)
        scheduler.requestLoadReduction()
        assertTrue(scheduler.loadReductionRequested)
        scheduler.clearLoadReduction()
        assertFalse(scheduler.loadReductionRequested)
    }

    @Test
    fun `executeStep under loadReduction works correctly`() {
        val scheduler = SettlementScheduler()
        val state = emptyState()

        scheduler.scheduleYearly(
            shadow = state,
            agingPhase = Phase_AgingAndDeath { _ -> },
            recruitPhase = Phase_RecruitRefresh { _ -> },
            aiSectPhase = Phase_AISectYearly { _ -> },
            alliancePhase = Phase_AllianceExpiry { _ -> }
        )

        scheduler.requestLoadReduction()

        runBlocking {
            scheduler.executeStep(state)
        }

        assertFalse("负载降级下阶段仍应执行完成", scheduler.hasPendingWork)
    }

    // ============================================================
    // reset
    // ============================================================

    @Test
    fun `reset clears all state`() {
        val scheduler = SettlementScheduler()
        val state = emptyState()

        scheduler.scheduleYearly(
            shadow = state,
            agingPhase = Phase_AgingAndDeath { _ -> },
            recruitPhase = Phase_RecruitRefresh { _ -> },
            aiSectPhase = Phase_AISectYearly { _ -> },
            alliancePhase = Phase_AllianceExpiry { _ -> }
        )
        scheduler.requestLoadReduction()

        assertTrue(scheduler.hasPendingWork)
        assertTrue(scheduler.loadReductionRequested)

        scheduler.reset()

        assertFalse("reset 后应无待执行工作", scheduler.hasPendingWork)
        assertFalse("reset 后负载降级标志应清除", scheduler.loadReductionRequested)
    }

    // ============================================================
    // getFrameCount
    // ============================================================

    @Test
    fun `frame count increases after executeStep`() {
        val scheduler = SettlementScheduler()
        val state = emptyState()

        assertEquals(0, scheduler.getFrameCount())

        scheduler.scheduleYearly(
            shadow = state,
            agingPhase = Phase_AgingAndDeath { _ -> },
            recruitPhase = Phase_RecruitRefresh { _ -> },
            aiSectPhase = Phase_AISectYearly { _ -> },
            alliancePhase = Phase_AllianceExpiry { _ -> }
        )

        runBlocking {
            scheduler.executeStep(state)
        }

        assertTrue("执行后 frameCount 应 > 0", scheduler.getFrameCount() > 0)
    }
}
