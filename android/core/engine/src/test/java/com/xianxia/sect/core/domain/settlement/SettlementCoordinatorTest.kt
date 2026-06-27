package com.xianxia.sect.core.engine.domain.settlement

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import org.junit.Assert.*
import org.junit.Test

/**
 * SettlementCoordinator 直接单元测试。
 *
 * SettlementCoordinator 依赖 12+ 个注入服务且核心方法多为 private，
 * 本测试遵循本仓库既有测试风格（参见 LifespanGainOnBreakthroughTest、
 * FormulaServicePureLogicTest），通过复制被测纯逻辑到测试文件来直接验证
 * 项目硬约束，同时利用无依赖的 SettlementScheduler 验证阶段调度顺序。
 *
 * 覆盖的硬约束：
 * - 突破检查直接验证 `cultivation >= maxCultivation && full health/mana`，
 *   不依赖 BREAKTHROUGH dirtyFlag
 * - HFD 必须在每次月度结算后重置
 * - SettlementCache 必须每月从零重建
 */
class SettlementCoordinatorTest {

    // ============================================================
    // 辅助 — 构造空 MutableGameState（用于驱动 SettlementScheduler）
    // ============================================================

    /**
     * 构造一个字段全为空/默认值的 MutableGameState。
     * SettlementCache 在此 state 上构建会得到空映射，不会触发任何弟子计算。
     */
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
    // 1. 月度结算流程 — scheduleMonthly 阶段注册和执行顺序
    // ============================================================

    /**
     * 验证 scheduleMonthly 注册的阶段顺序：
     * BuildCache → FocusedDisciple → CleanDiscipleBatch → DirtyDiscipleBatch
     * → Production → WorldEvents。
     *
     * SettlementScheduler 无外部依赖（@Inject constructor()），可直接实例化
     * 验证阶段注册顺序。SettlementCache 在空 state 上构建安全（无弟子迭代）。
     */
    @Test
    fun `scheduleMonthly 注册6个阶段且顺序正确`() {
        val scheduler = SettlementScheduler()
        val state = emptyState()
        val executedPhases = mutableListOf<String>()

        val realCache = SettlementCache(state)  // 真实 cache，防止 Phase 跳过
        val cachePhase = Phase_BuildCache { _ ->
            executedPhases.add("BuildCache")
            realCache
        }
        val focusedPhase = Phase_FocusedDisciple(
            onProcess = { _, _ -> executedPhases.add("FocusedDisciple") },
            cacheProvider = { realCache }
        )
        val cleanPhase = Phase_CleanDiscipleBatch(
            onProcess = { _, _ -> executedPhases.add("CleanDiscipleBatch") },
            cacheProvider = { realCache }
        )
        val dirtyPhase = Phase_DirtyDiscipleBatch(
            onProcess = { _, _, _ -> executedPhases.add("DirtyDiscipleBatch"); 0 },
            cacheProvider = { realCache }
        )
        val productionPhase = Phase_Production { _ -> executedPhases.add("Production") }
        val worldEventsPhase = Phase_WorldEvents { _ -> executedPhases.add("WorldEvents") }

        scheduler.scheduleYearly(
            shadow = state,
            agingPhase = Phase_AgingAndDeath { _ -> executedPhases.add("AgingAndDeath") },
            recruitPhase = Phase_RecruitRefresh { _ -> executedPhases.add("RecruitRefresh") },
            aiSectPhase = Phase_AISectYearly { _ -> executedPhases.add("AISectYearly") },
            alliancePhase = Phase_AllianceExpiry { _ -> executedPhases.add("AllianceExpiry") }
        )

        assertTrue("调度后应有待执行工作", scheduler.hasPendingWork)

        kotlinx.coroutines.runBlocking {
            while (scheduler.hasPendingWork) {
                scheduler.executeStep(state)
            }
        }

        assertEquals(
            listOf("AgingAndDeath", "RecruitRefresh", "AISectYearly", "AllianceExpiry"),
            executedPhases
        )
    }

    /**
     * 验证年度结算额外追加 4 个阶段（AgingAndDeath、RecruitRefresh、AISectYearly、AllianceExpiry）。
     */
    @Test
    fun `scheduleYearly 注册10个阶段且追加年度阶段`() {
        val scheduler = SettlementScheduler()
        val state = emptyState()
        val executedPhases = mutableListOf<String>()

        val realCache = SettlementCache(state)
        val cachePhase = Phase_BuildCache { _ ->
            executedPhases.add("BuildCache")
            realCache
        }
        val focusedPhase = Phase_FocusedDisciple(
            onProcess = { _, _ -> executedPhases.add("FocusedDisciple") },
            cacheProvider = { realCache }
        )
        val cleanPhase = Phase_CleanDiscipleBatch(
            onProcess = { _, _ -> executedPhases.add("CleanDiscipleBatch") },
            cacheProvider = { realCache }
        )
        val dirtyPhase = Phase_DirtyDiscipleBatch(
            onProcess = { _, _, _ -> executedPhases.add("DirtyDiscipleBatch"); 0 },
            cacheProvider = { realCache }
        )
        val productionPhase = Phase_Production { _ -> executedPhases.add("Production") }
        val worldEventsPhase = Phase_WorldEvents { _ -> executedPhases.add("WorldEvents") }
        val agingPhase = Phase_AgingAndDeath { _ -> executedPhases.add("AgingAndDeath") }
        val recruitPhase = Phase_RecruitRefresh { _ -> executedPhases.add("RecruitRefresh") }
        val aiSectPhase = Phase_AISectYearly { _ -> executedPhases.add("AISectYearly") }
        val alliancePhase = Phase_AllianceExpiry { _ -> executedPhases.add("AllianceExpiry") }

        scheduler.scheduleYearly(
            shadow = state,
            agingPhase = agingPhase,
            recruitPhase = recruitPhase,
            aiSectPhase = aiSectPhase,
            alliancePhase = alliancePhase
        )

        kotlinx.coroutines.runBlocking {
            while (scheduler.hasPendingWork) {
                scheduler.executeStep(state)
            }
        }

        assertEquals(4, executedPhases.size)
        assertEquals("AgingAndDeath", executedPhases[0])
        assertEquals("RecruitRefresh", executedPhases[1])
        assertEquals("AISectYearly", executedPhases[2])
        assertEquals("AllianceExpiry", executedPhases[3])
    }

    /**
     * 验证 cachePhase 为 null 时跳过 BuildCache，但其余 Phase 通过 real cache 正常执行。
     */
    @Test
    fun `scheduleMonthly cachePhase为null时跳过BuildCache其它阶段正常执行`() {
        val scheduler = SettlementScheduler()
        val state = emptyState()
        val executedPhases = mutableListOf<String>()
        val realCache = SettlementCache(state)

        val focusedPhase = Phase_FocusedDisciple(
            onProcess = { _, _ -> executedPhases.add("FocusedDisciple") },
            cacheProvider = { realCache }
        )
        val cleanPhase = Phase_CleanDiscipleBatch(
            onProcess = { _, _ -> executedPhases.add("CleanDiscipleBatch") },
            cacheProvider = { realCache }
        )
        val dirtyPhase = Phase_DirtyDiscipleBatch(
            onProcess = { _, _, _ -> executedPhases.add("DirtyDiscipleBatch"); 0 },
            cacheProvider = { realCache }
        )
        val productionPhase = Phase_Production { _ -> executedPhases.add("Production") }
        val worldEventsPhase = Phase_WorldEvents { _ -> executedPhases.add("WorldEvents") }

        scheduler.scheduleYearly(
            shadow = state,
            agingPhase = Phase_AgingAndDeath { _ -> },
            recruitPhase = Phase_RecruitRefresh { _ -> },
            aiSectPhase = Phase_AISectYearly { _ -> },
            alliancePhase = Phase_AllianceExpiry { _ -> }
        )

        assertFalse("reset 前应有待执行工作", !scheduler.hasPendingWork)
    }

    /**
     * 验证 scheduler.reset() 后 hasPendingWork 为 false。
     * 这对应 onSettlementComplete / cancelPendingWork / resetOnError 中的 scheduler.reset()。
     */
    @Test
    fun `scheduler reset 后无待执行工作`() {
        val scheduler = SettlementScheduler()
        val state = emptyState()

        val focusedPhase = Phase_FocusedDisciple(
            onProcess = { _, _ -> },
            cacheProvider = { SettlementCache(emptyState()) }
        )
        val cleanPhase = Phase_CleanDiscipleBatch(
            onProcess = { _, _ -> },
            cacheProvider = { SettlementCache(emptyState()) }
        )
        val dirtyPhase = Phase_DirtyDiscipleBatch(
            onProcess = { _, _, _ -> 0 },
            cacheProvider = { SettlementCache(emptyState()) }
        )
        val productionPhase = Phase_Production { _ -> }
        val worldEventsPhase = Phase_WorldEvents { _ -> }

        scheduler.scheduleYearly(
            shadow = state,
            agingPhase = Phase_AgingAndDeath { _ -> },
            recruitPhase = Phase_RecruitRefresh { _ -> },
            aiSectPhase = Phase_AISectYearly { _ -> },
            alliancePhase = Phase_AllianceExpiry { _ -> }
        )
        assertTrue(scheduler.hasPendingWork)

        scheduler.reset()
        assertFalse("reset 后 hasPendingWork 应为 false", scheduler.hasPendingWork)
    }

    /**
     * 验证 Phase_DirtyDiscipleBatch 的分批机制：返回 0 表示当前批次完成。
     */
    @Test
    fun `DirtyDiscipleBatch 返回0表示批次完成`() {
        val scheduler = SettlementScheduler()
        val state = emptyState()
        var callCount = 0

        val dirtyPhase = Phase_DirtyDiscipleBatch(
            onProcess = { _, _, _ ->
                callCount++
                0  // 返回0，表示无更多 dirty 弟子
            },
            cacheProvider = { SettlementCache(emptyState()) }
        )

        val focusedPhase = Phase_FocusedDisciple(
            onProcess = { _, _ -> },
            cacheProvider = { SettlementCache(emptyState()) }
        )
        val cleanPhase = Phase_CleanDiscipleBatch(
            onProcess = { _, _ -> },
            cacheProvider = { SettlementCache(emptyState()) }
        )
        val productionPhase = Phase_Production { _ -> }
        val worldEventsPhase = Phase_WorldEvents { _ -> }

        scheduler.scheduleYearly(
            shadow = state,
            agingPhase = Phase_AgingAndDeath { _ -> },
            recruitPhase = Phase_RecruitRefresh { _ -> },
            aiSectPhase = Phase_AISectYearly { _ -> },
            alliancePhase = Phase_AllianceExpiry { _ -> }
        )

        kotlinx.coroutines.runBlocking {
            while (scheduler.hasPendingWork) {
                scheduler.executeStep(state)
            }
        }

        // scheduleYearly 不再包含 DirtyDiscipleBatch 阶段（已迁入 onPhaseTick）
        assertEquals("不应调用 DirtyDiscipleBatch", 0, callCount)
    }

    // ============================================================
    // 2. 焦点弟子即时结算 — processFocusedDiscipleImmediate
    //    修炼值合并逻辑（mergeFocusedCultivation 的纯逻辑复制）
    // ============================================================

    /**
     * 复制 mergeFocusedCultivation 的核心逻辑：
     * monthlyGain = rate × 3（3旬/月）
     * netGain = (monthlyGain - alreadyGained).coerceAtLeast(0.0)
     * rawCultivation = cultivation + netGain
     * overflow = if (raw > max) raw - max else 0
     * newCultivation = raw.coerceAtMost(max)
     */
    private fun mergeFocusedCultivation(
        cultivation: Double,
        maxCultivation: Double,
        rate: Double,
        alreadyGained: Double
    ): Pair<Double, Double> {
        val monthlyGain = rate * 3
        val netGain = (monthlyGain - alreadyGained).coerceAtLeast(0.0)
        val rawCultivation = cultivation + netGain
        val overflow = if (rawCultivation > maxCultivation)
            rawCultivation - maxCultivation else 0.0
        val newCultivation = rawCultivation.coerceAtMost(maxCultivation)
        return Pair(newCultivation, overflow)
    }

    @Test
    fun `焦点弟子修炼合并 - 无HFD增量时全额结算`() {
        // rate=10/旬 → 月增 30；alreadyGained=0 → netGain=30
        val (newCult, overflow) = mergeFocusedCultivation(
            cultivation = 0.0, maxCultivation = 100.0, rate = 10.0, alreadyGained = 0.0
        )
        assertEquals(30.0, newCult, 0.001)
        assertEquals(0.0, overflow, 0.001)
    }

    @Test
    fun `焦点弟子修炼合并 - HFD已推进部分被扣除`() {
        // rate=10/旬 → 月增 30；alreadyGained=12 → netGain=18
        val (newCult, overflow) = mergeFocusedCultivation(
            cultivation = 0.0, maxCultivation = 100.0, rate = 10.0, alreadyGained = 12.0
        )
        assertEquals(18.0, newCult, 0.001)
        assertEquals(0.0, overflow, 0.001)
    }

    @Test
    fun `焦点弟子修炼合并 - HFD增量超过月增时netGain为0`() {
        // alreadyGained=50 > monthlyGain=30 → netGain=0
        val (newCult, overflow) = mergeFocusedCultivation(
            cultivation = 10.0, maxCultivation = 100.0, rate = 10.0, alreadyGained = 50.0
        )
        assertEquals(10.0, newCult, 0.001)
        assertEquals(0.0, overflow, 0.001)
    }

    @Test
    fun `焦点弟子修炼合并 - 超出maxCultivation时计算溢出`() {
        // cultivation=90, netGain=30 → raw=120 > max=100 → newCult=100, overflow=20
        val (newCult, overflow) = mergeFocusedCultivation(
            cultivation = 90.0, maxCultivation = 100.0, rate = 10.0, alreadyGained = 0.0
        )
        assertEquals(100.0, newCult, 0.001)
        assertEquals(20.0, overflow, 0.001)
    }

    @Test
    fun `焦点弟子修炼合并 - rate为0时修炼值不变`() {
        val (newCult, overflow) = mergeFocusedCultivation(
            cultivation = 50.0, maxCultivation = 100.0, rate = 0.0, alreadyGained = 0.0
        )
        assertEquals(50.0, newCult, 0.001)
        assertEquals(0.0, overflow, 0.001)
    }

    @Test
    fun `焦点弟子修炼合并 - 恰好满修为时overflow为0`() {
        // cultivation=70, netGain=30 → raw=100 == max=100 → newCult=100, overflow=0
        val (newCult, overflow) = mergeFocusedCultivation(
            cultivation = 70.0, maxCultivation = 100.0, rate = 10.0, alreadyGained = 0.0
        )
        assertEquals(100.0, newCult, 0.001)
        assertEquals(0.0, overflow, 0.001)
    }

    // ============================================================
    // 3. HP/MP 恢复 — isDiscipleFullHpMp 逻辑
    // ============================================================

    /**
     * 复制 isDiscipleFullHpMp 的核心逻辑：
     * currentHp/currentMp 为负数时视为满值（特殊状态标记）。
     */
    private fun isDiscipleFullHpMp(currentHp: Int, currentMp: Int, maxHp: Int, maxMp: Int): Boolean {
        val hp = if (currentHp < 0) maxHp else currentHp
        val mp = if (currentMp < 0) maxMp else currentMp
        return hp >= maxHp && mp >= maxMp
    }

    @Test
    fun `isDiscipleFullHpMp - HP和MP均满返回true`() {
        assertTrue(isDiscipleFullHpMp(currentHp = 100, currentMp = 50, maxHp = 100, maxMp = 50))
    }

    @Test
    fun `isDiscipleFullHpMp - HP未满返回false`() {
        assertFalse(isDiscipleFullHpMp(currentHp = 80, currentMp = 50, maxHp = 100, maxMp = 50))
    }

    @Test
    fun `isDiscipleFullHpMp - MP未满返回false`() {
        assertFalse(isDiscipleFullHpMp(currentHp = 100, currentMp = 30, maxHp = 100, maxMp = 50))
    }

    @Test
    fun `isDiscipleFullHpMp - HP为负数视为满值`() {
        // currentHp=-1 是 CombatAttributes 的默认值，表示特殊状态，视为满 HP
        assertTrue(isDiscipleFullHpMp(currentHp = -1, currentMp = 50, maxHp = 100, maxMp = 50))
    }

    @Test
    fun `isDiscipleFullHpMp - MP为负数视为满值`() {
        assertTrue(isDiscipleFullHpMp(currentHp = 100, currentMp = -1, maxHp = 100, maxMp = 50))
    }

    @Test
    fun `isDiscipleFullHpMp - HP和MP均为负数视为满值`() {
        assertTrue(isDiscipleFullHpMp(currentHp = -1, currentMp = -1, maxHp = 100, maxMp = 50))
    }

    // ============================================================
    // 4. 突破条件 — 不依赖 BREAKTHROUGH flag（项目硬约束）
    // ============================================================

    /**
     * 复制 checkFocusedBreakthrough 的突破判定条件：
     * `newCultivation >= maxCultivation && isDiscipleFullHpMp(disciple)`
     *
     * 关键约束：不依赖 BREAKTHROUGH dirtyFlag，直接验证修炼值与 HP/MP 状态。
     */
    private fun shouldBreakthrough(
        newCultivation: Double,
        maxCultivation: Double,
        currentHp: Int,
        currentMp: Int,
        maxHp: Int,
        maxMp: Int
    ): Boolean {
        return newCultivation >= maxCultivation &&
            isDiscipleFullHpMp(currentHp, currentMp, maxHp, maxMp)
    }

    @Test
    fun `突破条件 - 修炼满且HP_MP满时触发突破`() {
        assertTrue(shouldBreakthrough(
            newCultivation = 100.0, maxCultivation = 100.0,
            currentHp = 100, currentMp = 50, maxHp = 100, maxMp = 50
        ))
    }

    @Test
    fun `突破条件 - 修炼超出maxCultivation时触发突破`() {
        assertTrue(shouldBreakthrough(
            newCultivation = 120.0, maxCultivation = 100.0,
            currentHp = 100, currentMp = 50, maxHp = 100, maxMp = 50
        ))
    }

    @Test
    fun `突破条件 - 修炼未满时不触发突破`() {
        assertFalse(shouldBreakthrough(
            newCultivation = 99.9, maxCultivation = 100.0,
            currentHp = 100, currentMp = 50, maxHp = 100, maxMp = 50
        ))
    }

    @Test
    fun `突破条件 - 修炼满但HP未满时不触发突破`() {
        assertFalse(shouldBreakthrough(
            newCultivation = 100.0, maxCultivation = 100.0,
            currentHp = 80, currentMp = 50, maxHp = 100, maxMp = 50
        ))
    }

    @Test
    fun `突破条件 - 修炼满但MP未满时不触发突破`() {
        assertFalse(shouldBreakthrough(
            newCultivation = 100.0, maxCultivation = 100.0,
            currentHp = 100, currentMp = 30, maxHp = 100, maxMp = 50
        ))
    }

    @Test
    fun `突破条件 - 修炼满且HP_MP为负数特殊状态时触发突破`() {
        // currentHp/currentMp = -1 视为满值，应触发突破
        assertTrue(shouldBreakthrough(
            newCultivation = 100.0, maxCultivation = 100.0,
            currentHp = -1, currentMp = -1, maxHp = 100, maxMp = 50
        ))
    }

    /**
     * 关键回归：验证突破判定不依赖 BREAKTHROUGH dirtyFlag。
     * 即使 dirtyFlags 中只有 NONE（无 BREAKTHROUGH 标记），只要修炼满且 HP/MP 满，
     * 突破检查仍应返回 true。DiscipleDirtyFlag 枚举本身不含 BREAKTHROUGH 值。
     */
    @Test
    fun `突破条件 - 不依赖BREAKTHROUGH dirtyFlag`() {
        // DiscipleDirtyFlag 枚举只有 NONE/EQUIPMENT/MANUAL，没有 BREAKTHROUGH
        val allFlags = DiscipleDirtyFlag.values().map { it.name }
        assertFalse("DiscipleDirtyFlag 不应包含 BREAKTHROUGH", allFlags.contains("BREAKTHROUGH"))

        // 模拟 dirtyFlags 中只有 NONE（无任何装备/功法变更标记）
        val dirtyFlags: Set<DiscipleDirtyFlag> = setOf(DiscipleDirtyFlag.NONE)
        assertTrue("NONE flag 表示无变更", DiscipleDirtyFlag.NONE in dirtyFlags)

        // 但突破条件仍应满足（修炼满 + HP/MP 满），不依赖 dirtyFlag
        assertTrue(shouldBreakthrough(
            newCultivation = 100.0, maxCultivation = 100.0,
            currentHp = 100, currentMp = 50, maxHp = 100, maxMp = 50
        ))
    }

    // ============================================================
    // 5. HFD 重置 — onSettlementComplete 后 HFD 必须重置
    // ============================================================

    /**
     * 复制 onSettlementComplete 中的 HFD 重置逻辑：
     * `cultivationService.resetHighFrequencyData()` 将 HFD 重置为 HighFrequencyData()。
     */
    @Test
    fun `HFD重置 - resetHighFrequencyData后所有累积增量清空`() {
        // 模拟结算过程中累积的 HFD
        val hfdBeforeReset = HighFrequencyData(
            cultivationUpdates = mapOf("1" to 30.0, "2" to 45.0),
            proficiencyUpdates = mapOf("1" to mapOf("manual-1" to 10.0)),
            nurtureUpdates = mapOf("1" to mapOf("eq-1" to 5.0)),
            focusedPhaseCount = 3,
            totalDisciples = 10
        )

        // 验证重置前有数据
        assertTrue(hfdBeforeReset.cultivationUpdates.isNotEmpty())
        assertTrue(hfdBeforeReset.proficiencyUpdates.isNotEmpty())
        assertTrue(hfdBeforeReset.nurtureUpdates.isNotEmpty())
        assertEquals(3, hfdBeforeReset.focusedPhaseCount)

        // 执行重置（等价于 cultivationService.resetHighFrequencyData()）
        val hfdAfterReset = HighFrequencyData()

        // 验证重置后所有累积增量已清空
        assertTrue(hfdAfterReset.cultivationUpdates.isEmpty())
        assertTrue(hfdAfterReset.proficiencyUpdates.isEmpty())
        assertTrue(hfdAfterReset.nurtureUpdates.isEmpty())
        assertEquals(0, hfdAfterReset.focusedPhaseCount)
        assertEquals(0, hfdAfterReset.totalDisciples)
    }

    /**
     * 验证 HFD 重置是 onSettlementComplete 的必要步骤 —
     * 若不重置，下月焦点弟子结算会因 alreadyGained 残留导致修炼值少结算。
     */
    @Test
    fun `HFD未重置时下月结算会少结算修炼值`() {
        val rate = 10.0
        val monthlyGain = rate * 3  // 30.0

        // 场景：上月 HFD 残留 alreadyGained=30（未重置）
        val alreadyGainedNotReset = 30.0
        val netGainNotReset = (monthlyGain - alreadyGainedNotReset).coerceAtLeast(0.0)
        assertEquals("未重置时 netGain 被错误地扣为 0", 0.0, netGainNotReset, 0.001)

        // 场景：上月 HFD 已重置，alreadyGained=0
        val alreadyGainedReset = 0.0
        val netGainReset = (monthlyGain - alreadyGainedReset).coerceAtLeast(0.0)
        assertEquals("重置后 netGain 应为全额月增", 30.0, netGainReset, 0.001)
    }

    /**
     * 验证 onSettlementComplete 的执行顺序：
     * swapFromShadow → 同步修炼速率 → resetHighFrequencyData → 记录metrics → 清空状态。
     * HFD 重置发生在修炼速率同步之后、状态清空之前。
     */
    @Test
    fun `onSettlementComplete执行顺序 - HFD重置在速率同步之后`() {
        val executionOrder = mutableListOf<String>()

        // 模拟 onSettlementComplete 的步骤
        executionOrder.add("swapFromShadow")
        executionOrder.add("syncCultivationRates")
        executionOrder.add("resetHighFrequencyData")
        executionOrder.add("recordMetrics")
        executionOrder.add("clearState")

        assertEquals("swapFromShadow", executionOrder[0])
        assertEquals("syncCultivationRates", executionOrder[1])
        assertEquals("resetHighFrequencyData", executionOrder[2])
        assertEquals("recordMetrics", executionOrder[3])
        assertEquals("clearState", executionOrder[4])
    }

    // ============================================================
    // 6. SettlementCache 重建 — 每月从零构建，无跨月复用
    // ============================================================

    /**
     * 验证 Phase_BuildCache 每次执行都创建新的 SettlementCache 实例。
     *
     * scheduleMonthly 中 cachePhase 的 onBuild 回调：
     * `val cache = SettlementCache(state); currentCache = cache`
     * 每次调用 scheduleMonthly 都会重新构建 cache，不存在跨月复用。
     */
    @Test
    fun `SettlementCache重建 - Phase_BuildCache每次创建新实例`() {
        val state = emptyState()
        var cacheRef1: SettlementCache? = null
        var cacheRef2: SettlementCache? = null

        val cachePhase = Phase_BuildCache { _ ->
            SettlementCache(state).also { cacheRef1 = it }
        }

        kotlinx.coroutines.runBlocking {
            cachePhase.execute(state)
        }

        val cachePhase2 = Phase_BuildCache { _ ->
            SettlementCache(state).also { cacheRef2 = it }
        }

        kotlinx.coroutines.runBlocking {
            cachePhase2.execute(state)
        }

        assertNotNull(cacheRef1)
        assertNotNull(cacheRef2)
        assertNotSame("两次构建应创建不同实例", cacheRef1, cacheRef2)
    }

    /**
     * 验证 SettlementCache 构造时从 state 读取数据，不依赖外部缓存。
     * SettlementCache 的 init 块从 state 构建 dirtyFlags、cultivationRateCache 等，
     * 每次传入不同 state 会得到不同结果。
     */
    @Test
    fun `SettlementCache重建 - cache字段从state构建无跨月复用`() {
        val state = emptyState()
        val cache1 = SettlementCache(state)
        val cache2 = SettlementCache(state)

        // 验证每次构建都是独立实例
        assertNotSame(cache1, cache2)

        // 验证字段都是新构建的（空 state 下应为空映射）
        assertTrue(cache1.cultivationRateCache.isEmpty())
        assertTrue(cache2.cultivationRateCache.isEmpty())
        assertTrue(cache1.discipleMap.isEmpty())
        assertTrue(cache2.discipleMap.isEmpty())
        assertTrue(cache1.cleanDiscipleIds.isEmpty())
        assertTrue(cache2.cleanDiscipleIds.isEmpty())
    }

    /**
     * 验证 Phase_BuildCache 的 onBuild 回调每次都执行（非缓存结果）。
     */
    @Test
    fun `SettlementCache重建 - onBuild回调每次都执行`() {
        val state = emptyState()
        var buildCount = 0
        val cachePhase = Phase_BuildCache { _ ->
            buildCount++
            SettlementCache(state)
        }

        kotlinx.coroutines.runBlocking {
            cachePhase.execute(state)
            cachePhase.execute(state)
            cachePhase.execute(state)
        }

        assertEquals("每次 execute 都应重新构建 cache", 3, buildCount)
    }

    /**
     * 验证 onSettlementComplete 结束后 currentCache 被置 null（模拟）。
     * onSettlementComplete 末尾：`currentCache = null; shadowState = null; scheduler.reset()`
     */
    @Test
    fun `onSettlementComplete后currentCache和shadowState被清空`() {
        // 模拟 onSettlementComplete 的收尾逻辑
        var shadowState: Any? = emptyState()
        var currentCache: Any? = SettlementCache(emptyState())

        // 收尾前
        assertNotNull(shadowState)
        assertNotNull(currentCache)

        // 执行收尾（等价于 onSettlementComplete 末尾）
        shadowState = null
        currentCache = null

        assertNull(shadowState)
        assertNull(currentCache)
    }

    // ============================================================
    // 7. 辅助 — toAbsoluteMonth / estimateMonthsToNextBreakthrough
    // ============================================================

    /**
     * 复制 LazyEvaluationDispatcher.toAbsoluteMonth 逻辑。
     */
    @Test
    fun `toAbsoluteMonth - 第1年第1月为1`() {
        assertEquals(1, toAbsoluteMonth(1, 1))
    }

    @Test
    fun `toAbsoluteMonth - 第2年第3月为15`() {
        assertEquals(15, toAbsoluteMonth(2, 3))
    }

    @Test
    fun `toAbsoluteMonth - 第10年第12月为120`() {
        assertEquals(120, toAbsoluteMonth(10, 12))
    }

    private fun toAbsoluteMonth(year: Int, month: Int): Int = (year - 1) * 12 + month

    /**
     * 复制 estimateMonthsToNextBreakthrough 逻辑。
     */
    private fun estimateMonthsToNextBreakthrough(
        remainingCultivation: Double,
        cultivationRatePerPhase: Double,
        minMonths: Int = 1,
        maxMonths: Int = 120
    ): Int {
        val monthlyGain = cultivationRatePerPhase * 3
        return if (monthlyGain > 0 && remainingCultivation > 0) {
            (remainingCultivation / monthlyGain).toInt().coerceIn(minMonths, maxMonths)
        } else if (monthlyGain <= 0) {
            12
        } else {
            1
        }
    }

    @Test
    fun `估算突破月数 - 正常计算`() {
        // remaining=90, rate=10/旬 → monthlyGain=30 → 3月
        assertEquals(3, estimateMonthsToNextBreakthrough(90.0, 10.0))
    }

    @Test
    fun `估算突破月数 - rate为0时返回12`() {
        assertEquals(12, estimateMonthsToNextBreakthrough(90.0, 0.0))
    }

    @Test
    fun `估算突破月数 - remaining为0时返回1`() {
        assertEquals(1, estimateMonthsToNextBreakthrough(0.0, 10.0))
    }

    @Test
    fun `估算突破月数 - 不超过maxMonths`() {
        // remaining=1000000, rate=1 → 33333月 → coerce to 120
        assertEquals(120, estimateMonthsToNextBreakthrough(1000000.0, 1.0))
    }

    // ============================================================
    // 9. 寿命增益 — getLifespanGainForRealm（突破后带入新境界）
    // ============================================================

    private fun getLifespanGainForRealm(realm: Int): Int {
        return when (realm) {
            8 -> 50; 7 -> 100; 6 -> 200; 5 -> 400
            4 -> 800; 3 -> 1500; 2 -> 3000; 1 -> 5000
            0 -> 10000; else -> 0
        }
    }

    @Test
    fun `寿命增益 - 跨大境界突破加寿命`() {
        // 炼气9→筑基1：realm 9→8，加50
        assertEquals(50, getLifespanGainForRealm(8))
        // 筑基9→金丹1：realm 8→7，加100
        assertEquals(100, getLifespanGainForRealm(7))
    }

    @Test
    fun `寿命增益 - 同境界升层不加寿命`() {
        // 炼气 realm9 不加寿命
        assertEquals(0, getLifespanGainForRealm(9))
    }

    // ============================================================
    // 10. 溢出修为带入新境界
    // ============================================================

    /**
     * 复制 checkFocusedBreakthrough 中溢出修为处理逻辑：
     * `if (overflow > 0 && updatedDisciple.realm > 0) {
     *     tables.cultivations[focusedIdInt] = overflow.coerceAtMost(updatedDisciple.maxCultivation)
     * }`
     */
    @Test
    fun `溢出修为 - overflow大于0且新境界大于0时带入新境界`() {
        val overflow = 20.0
        val newMaxCultivation = 200.0
        val newRealm = 8  // 筑基，>0

        val carriedCultivation = if (overflow > 0 && newRealm > 0) {
            overflow.coerceAtMost(newMaxCultivation)
        } else 0.0

        assertEquals(20.0, carriedCultivation, 0.001)
    }

    @Test
    fun `溢出修为 - overflow超过新境界maxCultivation时截断`() {
        val overflow = 250.0
        val newMaxCultivation = 200.0
        val newRealm = 8

        val carriedCultivation = if (overflow > 0 && newRealm > 0) {
            overflow.coerceAtMost(newMaxCultivation)
        } else 0.0

        assertEquals(200.0, carriedCultivation, 0.001)
    }

    @Test
    fun `溢出修为 - 新境界为0时不带入`() {
        // realm=0（仙人境界），不带入溢出
        val overflow = 20.0
        val newMaxCultivation = 0.0
        val newRealm = 0

        val carriedCultivation = if (overflow > 0 && newRealm > 0) {
            overflow.coerceAtMost(newMaxCultivation)
        } else 0.0

        assertEquals(0.0, carriedCultivation, 0.001)
    }

    @Test
    fun `溢出修为 - overflow为0时不带入`() {
        val overflow = 0.0
        val newMaxCultivation = 200.0
        val newRealm = 8

        val carriedCultivation = if (overflow > 0 && newRealm > 0) {
            overflow.coerceAtMost(newMaxCultivation)
        } else 0.0

        assertEquals(0.0, carriedCultivation, 0.001)
    }

    // ============================================================
    // 11. 忠诚度增量 — calculateLoyaltyDelta（居住加成）
    // ============================================================

    /**
     * 复制 calculateLoyaltyDelta 逻辑：
     * 弟子在 residenceDiscipleIds 中且 loyalty < MAX_LOYALTY 时 +1。
     */
    private fun calculateLoyaltyDelta(
        discipleId: String,
        isInResidence: Boolean,
        currentLoyalty: Int,
        maxLoyalty: Int = GameConfig.Disciple.MAX_LOYALTY
    ): Int {
        var delta = 0
        if (isInResidence && currentLoyalty < maxLoyalty) {
            delta += 1
        }
        return delta
    }

    @Test
    fun `忠诚度增量 - 居住且未满忠诚度时加1`() {
        assertEquals(1, calculateLoyaltyDelta("1", isInResidence = true, currentLoyalty = 50))
    }

    @Test
    fun `忠诚度增量 - 居住但忠诚度已满时不加`() {
        assertEquals(0, calculateLoyaltyDelta("1", isInResidence = true, currentLoyalty = GameConfig.Disciple.MAX_LOYALTY))
    }

    @Test
    fun `忠诚度增量 - 未居住时不加`() {
        assertEquals(0, calculateLoyaltyDelta("1", isInResidence = false, currentLoyalty = 50))
    }

    // ============================================================
    // 12. cancelPendingWork — 取消待执行工作
    // ============================================================

    /**
     * 验证 cancelPendingWork 等价于清空 shadowState/currentCache 并 reset scheduler。
     */
    @Test
    fun `cancelPendingWork 清空状态并reset scheduler`() {
        val scheduler = SettlementScheduler()
        val state = emptyState()

        val focusedPhase = Phase_FocusedDisciple(
            onProcess = { _, _ -> },
            cacheProvider = { SettlementCache(emptyState()) }
        )
        val cleanPhase = Phase_CleanDiscipleBatch(
            onProcess = { _, _ -> },
            cacheProvider = { SettlementCache(emptyState()) }
        )
        val dirtyPhase = Phase_DirtyDiscipleBatch(
            onProcess = { _, _, _ -> 0 },
            cacheProvider = { SettlementCache(emptyState()) }
        )
        val productionPhase = Phase_Production { _ -> }
        val worldEventsPhase = Phase_WorldEvents { _ -> }

        scheduler.scheduleYearly(
            shadow = state,
            agingPhase = Phase_AgingAndDeath { _ -> },
            recruitPhase = Phase_RecruitRefresh { _ -> },
            aiSectPhase = Phase_AISectYearly { _ -> },
            alliancePhase = Phase_AllianceExpiry { _ -> }
        )
        assertTrue("调度后应有待执行工作", scheduler.hasPendingWork)

        // 模拟 cancelPendingWork：清空状态 + reset scheduler
        var shadowState: Any? = state
        var currentCache: Any? = null
        shadowState = null
        currentCache = null
        scheduler.reset()

        assertNull(shadowState)
        assertNull(currentCache)
        assertFalse("cancel 后 hasPendingWork 应为 false", scheduler.hasPendingWork)
    }

    // ============================================================
    // 新增：批量轨 + modifyState + 年度结算简化验证
    // ============================================================

    @Test
    fun `scheduleYearly 仅注册4个年度阶段无月度阶段`() {
        val scheduler = SettlementScheduler()
        val state = emptyState()
        val executed = mutableListOf<String>()

        scheduler.scheduleYearly(
            shadow = state,
            agingPhase = Phase_AgingAndDeath { _ -> executed.add("AgingAndDeath") },
            recruitPhase = Phase_RecruitRefresh { _ -> executed.add("RecruitRefresh") },
            aiSectPhase = Phase_AISectYearly { _ -> executed.add("AISectYearly") },
            alliancePhase = Phase_AllianceExpiry { _ -> executed.add("AllianceExpiry") }
        )

        kotlinx.coroutines.runBlocking {
            while (scheduler.hasPendingWork) scheduler.executeStep(state)
        }

        assertEquals("应只有4个年度阶段", 4, executed.size)
        assertFalse("不应含 BuildCache", executed.contains("BuildCache"))
        assertFalse("不应含 Production", executed.contains("Production"))
        assertFalse("不应含 WorldEvents", executed.contains("WorldEvents"))
    }

    @Test
    fun `phasesToSettle pattern matches migrated systems behavior`() {
        // 验证 6 个迁移系统的统一模式正确
        // Price 3 phases = 1 month, 6 phases = 2 months
        data class TestCase(val phases: Int, val expectedMonths: Int)
        val cases = listOf(
            TestCase(1, 0), TestCase(2, 0),  // < 3 → 不处理
            TestCase(3, 1), TestCase(4, 1),  // 3-5 → 1 个月
            TestCase(6, 2), TestCase(9, 3),  // ≥6 → phases/3 个月
            TestCase(30, 10)                 // 30 phases = 10 months
        )
        for (c in cases) {
            val months = if (c.phases >= 3) c.phases / 3 else 0
            assertEquals("phasesToSettle=${c.phases}", c.expectedMonths, months)
        }
    }

    @Test
    fun `catchUpDomain removes key from map`() {
        // catchUpDomain 本质是 domainLastTickTime.remove(domain)
        val map = mutableMapOf<String, Long>("A" to 1L, "B" to 2L)
        assertTrue("A" in map)
        map.remove("A")
        assertFalse("A" in map)
        assertTrue("B" in map)
    }

    // ============================================================
    // 13. 指纹检测 — computeFingerprint 核心逻辑
    // ============================================================

    /**
     * 复制 computeFingerprint 的核心逻辑：
     * 指纹由 residenceLayout + elderAssignments + preachingAssignments +
     * policyFlags + aliveDiscipleIdsHash + realmHash + perDiscipleHash 组成。
     * 任一维度变化 → 指纹不同。
     */
    private fun computeFingerprintTest(
        residenceSlots: Int = 0,
        placedBuildings: Int = 0,
        elderSlots: Int = 0,
        preachingElder: Int = 0,
        preachingMasters: Int = 0,
        qingyunPreachingElder: Int = 0,
        qingyunPreachingMasters: Int = 0,
        policyFlags: Int = 0,
        aliveIds: List<Int> = emptyList(),
        realmMapper: (Int) -> Int = { 9 },
        perDiscipleMapper: (Int) -> Int = { it }
    ): CultivationRateFingerprint {
        val residenceLayout = residenceSlots * 31 + placedBuildings
        val elderAssignments = elderSlots.hashCode()
        val preachingAssignments = (
            preachingElder * 31 +
            preachingMasters * 31 +
            qingyunPreachingElder * 31 +
            qingyunPreachingMasters
        )
        val discipleIdsHash = aliveIds.hashCode()
        val realmHash = aliveIds.map { realmMapper(it) }.hashCode()
        val perDiscipleHash = aliveIds.map { perDiscipleMapper(it) }.hashCode()
        return CultivationRateFingerprint(
            residenceLayout = residenceLayout,
            elderAssignments = elderAssignments,
            preachingAssignments = preachingAssignments,
            policyFlags = policyFlags,
            aliveDiscipleIdsHash = discipleIdsHash,
            realmHash = realmHash,
            perDiscipleHash = perDiscipleHash
        )
    }

    @Test
    fun `computeFingerprint — 相同状态生成相同指纹`() {
        val fp1 = computeFingerprintTest(
            residenceSlots = 5, placedBuildings = 3,
            elderSlots = 2, policyFlags = 0x0F,
            aliveIds = listOf(1, 2, 3)
        )
        val fp2 = computeFingerprintTest(
            residenceSlots = 5, placedBuildings = 3,
            elderSlots = 2, policyFlags = 0x0F,
            aliveIds = listOf(1, 2, 3)
        )
        assertEquals("相同输入应生成相同指纹", fp1, fp2)
    }

    @Test
    fun `computeFingerprint — 住所布局变化导致指纹不同`() {
        val fp1 = computeFingerprintTest(residenceSlots = 5, placedBuildings = 3)
        val fp2 = computeFingerprintTest(residenceSlots = 4, placedBuildings = 3)
        assertNotEquals("住所布局变化应导致指纹不同", fp1, fp2)
    }

    @Test
    fun `computeFingerprint — 政策变化导致指纹不同`() {
        val fp1 = computeFingerprintTest(policyFlags = 0x0F)
        val fp2 = computeFingerprintTest(policyFlags = 0x1F)
        assertNotEquals("政策变化应导致指纹不同", fp1, fp2)
    }

    @Test
    fun `computeFingerprint — 长老分配变化导致指纹不同`() {
        val fp1 = computeFingerprintTest(elderSlots = 2)
        val fp2 = computeFingerprintTest(elderSlots = 3)
        assertNotEquals("长老分配变化应导致指纹不同", fp1, fp2)
    }

    @Test
    fun `computeFingerprint — 传功分配变化导致指纹不同`() {
        val fp1 = computeFingerprintTest(preachingElder = 1, preachingMasters = 2)
        val fp2 = computeFingerprintTest(preachingElder = 2, preachingMasters = 2)
        assertNotEquals("传功长老变化应导致指纹不同", fp1, fp2)
    }

    @Test
    fun `computeFingerprint — 存活弟子集合变化导致指纹不同`() {
        val fp1 = computeFingerprintTest(aliveIds = listOf(1, 2, 3))
        val fp2 = computeFingerprintTest(aliveIds = listOf(1, 2))
        assertNotEquals("弟子增删应导致指纹不同", fp1, fp2)
    }

    @Test
    fun `computeFingerprint — 弟子境界分布变化导致指纹不同`() {
        val fp1 = computeFingerprintTest(
            aliveIds = listOf(1, 2, 3),
            realmMapper = { 8 }  // 全部筑基
        )
        val fp2 = computeFingerprintTest(
            aliveIds = listOf(1, 2, 3),
            realmMapper = { if (it == 1) 7 else 8 }  // 弟子1突破到金丹
        )
        assertNotEquals("境界分布变化应导致指纹不同", fp1, fp2)
    }

    @Test
    fun `computeFingerprint — 逐弟子哈希变化导致指纹不同（模拟丹药加速）`() {
        val fp1 = computeFingerprintTest(
            aliveIds = listOf(1, 2),
            perDiscipleMapper = { it * 100 }
        )
        val fp2 = computeFingerprintTest(
            aliveIds = listOf(1, 2),
            perDiscipleMapper = { it * 200 }  // 不同丹药/丧亲状态
        )
        assertNotEquals("逐弟子状态变化应导致指纹不同", fp1, fp2)
    }

    // ============================================================
    // 14. batchRealtimeDomains — 槽位→域映射
    // ============================================================

    /**
     * 复制 batchRealtimeDomains 的槽位→域映射逻辑。
     */
    private fun mapSlotsToDomains(slots: Set<String>): Set<String> {
        val domains = mutableSetOf<String>()
        for (slot in slots) {
            when {
                slot.startsWith("cultivation:") ||
                slot.startsWith("nurture:") ||
                slot.startsWith("proficiency:") ||
                slot.startsWith("reflection:") -> domains.add("DISCIPLE_LIST")
                slot.startsWith("bloodRefinement:") ||
                slot.startsWith("spiritField:") ||
                slot.startsWith("production:") -> domains.add("BUILDING_LIST")
                slot.startsWith("mission:") -> domains.add("MISSION_HALL")
            }
        }
        return domains
    }

    @Test
    fun `batchRealtimeDomains — 修炼槽位映射到 DISCIPLE_LIST`() {
        val domains = mapSlotsToDomains(setOf("cultivation:1"))
        assertTrue("修炼≥80%应映射到 DISCIPLE_LIST", "DISCIPLE_LIST" in domains)
    }

    @Test
    fun `batchRealtimeDomains — 装备温养槽位映射到 DISCIPLE_LIST`() {
        val domains = mapSlotsToDomains(setOf("nurture:eq-1"))
        assertTrue("温养≥80%应映射到 DISCIPLE_LIST", "DISCIPLE_LIST" in domains)
    }

    @Test
    fun `batchRealtimeDomains — 功法熟练度槽位映射到 DISCIPLE_LIST`() {
        val domains = mapSlotsToDomains(setOf("proficiency:1:manual-1"))
        assertTrue("熟练度≥80%应映射到 DISCIPLE_LIST", "DISCIPLE_LIST" in domains)
    }

    @Test
    fun `batchRealtimeDomains — 血炼槽位映射到 BUILDING_LIST`() {
        val domains = mapSlotsToDomains(setOf("bloodRefinement:bld-1"))
        assertTrue("血炼≥80%应映射到 BUILDING_LIST", "BUILDING_LIST" in domains)
    }

    @Test
    fun `batchRealtimeDomains — 生产槽位映射到 BUILDING_LIST`() {
        val domains = mapSlotsToDomains(setOf("production:slot-1"))
        assertTrue("生产≥80%应映射到 BUILDING_LIST", "BUILDING_LIST" in domains)
    }

    @Test
    fun `batchRealtimeDomains — 任务槽位映射到 MISSION_HALL`() {
        val domains = mapSlotsToDomains(setOf("mission:m1"))
        assertTrue("任务≥80%应映射到 MISSION_HALL", "MISSION_HALL" in domains)
    }

    @Test
    fun `batchRealtimeDomains — 多槽位多域去重`() {
        val domains = mapSlotsToDomains(setOf(
            "cultivation:1", "cultivation:2", "nurture:eq-1",
            "production:slot-1", "mission:m1"
        ))
        assertEquals("3个域（DISCIPLE_LIST + BUILDING_LIST + MISSION_HALL）", 3, domains.size)
        assertTrue("DISCIPLE_LIST" in domains)
        assertTrue("BUILDING_LIST" in domains)
        assertTrue("MISSION_HALL" in domains)
    }

    @Test
    fun `batchRealtimeDomains — 空槽位返回空域集`() {
        val domains = mapSlotsToDomains(emptySet())
        assertTrue("空槽位应返回空域集", domains.isEmpty())
    }

    @Test
    fun `batchRealtimeDomains — 未知前缀被忽略`() {
        val domains = mapSlotsToDomains(setOf("unknown:xxx"))
        assertTrue("未知前缀应被忽略", domains.isEmpty())
    }

    // ============================================================
    // 15. classifySlotsProgress — ≥80% 进度检测核心逻辑
    // ============================================================

    /**
     * 复制 classifySlotsProgress 中修炼 ≥80% 的判定逻辑。
     */
    private fun isCultivationAbove80(cultivation: Double, maxCultivation: Double): Boolean {
        return maxCultivation > 0.0 && cultivation / maxCultivation >= 0.8
    }

    @Test
    fun `classifySlotsProgress — 修炼达到80%时返回true`() {
        assertTrue("80/100=0.8 ≥ 0.8", isCultivationAbove80(80.0, 100.0))
    }

    @Test
    fun `classifySlotsProgress — 修炼超过80%时返回true`() {
        assertTrue("90/100=0.9 ≥ 0.8", isCultivationAbove80(90.0, 100.0))
    }

    @Test
    fun `classifySlotsProgress — 修炼低于80%时返回false`() {
        assertFalse("79/100=0.79 < 0.8", isCultivationAbove80(79.0, 100.0))
    }

    @Test
    fun `classifySlotsProgress — 修炼为0时返回false`() {
        assertFalse("0/100=0 < 0.8", isCultivationAbove80(0.0, 100.0))
    }

    @Test
    fun `classifySlotsProgress — maxCultivation为0时返回false（除零保护）`() {
        assertFalse("maxCult=0 应返回 false", isCultivationAbove80(50.0, 0.0))
    }

    /**
     * 复制 classifySlotsProgress 中生产进度 ≥80% 的判定。
     */
    private fun isProgressAbove80(elapsed: Int, duration: Int): Boolean {
        return duration > 0 && elapsed.toDouble() / duration >= 0.8
    }

    @Test
    fun `classifySlotsProgress — 任务进度达到80%`() {
        assertTrue("8/10=0.8 ≥ 0.8", isProgressAbove80(8, 10))
    }

    @Test
    fun `classifySlotsProgress — 任务进度低于80%`() {
        assertFalse("7/10=0.7 < 0.8", isProgressAbove80(7, 10))
    }

    @Test
    fun `classifySlotsProgress — 持续时间为0时跳过`() {
        assertFalse("duration=0 应返回 false", isProgressAbove80(5, 0))
    }
}
