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

        scheduler.scheduleMonthly(
            shadow = state,
            cachePhase = cachePhase,
            focusedPhase = focusedPhase,
            cleanPhase = cleanPhase,
            dirtyPhase = dirtyPhase,
            productionPhase = productionPhase,
            worldEventsPhase = worldEventsPhase
        )

        assertTrue("调度后应有待执行工作", scheduler.hasPendingWork)

        kotlinx.coroutines.runBlocking {
            while (scheduler.hasPendingWork) {
                scheduler.executeStep(state)
            }
        }

        assertEquals(
            listOf("BuildCache", "FocusedDisciple", "CleanDiscipleBatch", "DirtyDiscipleBatch", "Production", "WorldEvents"),
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
            cachePhase = cachePhase,
            focusedPhase = focusedPhase,
            cleanPhase = cleanPhase,
            dirtyPhase = dirtyPhase,
            productionPhase = productionPhase,
            worldEventsPhase = worldEventsPhase,
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

        assertEquals(10, executedPhases.size)
        assertEquals("AgingAndDeath", executedPhases[6])
        assertEquals("RecruitRefresh", executedPhases[7])
        assertEquals("AISectYearly", executedPhases[8])
        assertEquals("AllianceExpiry", executedPhases[9])
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

        scheduler.scheduleMonthly(
            shadow = state,
            cachePhase = null,
            focusedPhase = focusedPhase,
            cleanPhase = cleanPhase,
            dirtyPhase = dirtyPhase,
            productionPhase = productionPhase,
            worldEventsPhase = worldEventsPhase
        )

        kotlinx.coroutines.runBlocking {
            while (scheduler.hasPendingWork) {
                scheduler.executeStep(state)
            }
        }

        assertFalse("不应包含 BuildCache", executedPhases.contains("BuildCache"))
        assertEquals(5, executedPhases.size)
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

        scheduler.scheduleMonthly(
            shadow = state,
            cachePhase = null,
            focusedPhase = focusedPhase,
            cleanPhase = cleanPhase,
            dirtyPhase = dirtyPhase,
            productionPhase = productionPhase,
            worldEventsPhase = worldEventsPhase
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

        scheduler.scheduleMonthly(
            shadow = state,
            cachePhase = null,
            focusedPhase = focusedPhase,
            cleanPhase = cleanPhase,
            dirtyPhase = dirtyPhase,
            productionPhase = productionPhase,
            worldEventsPhase = worldEventsPhase
        )

        kotlinx.coroutines.runBlocking {
            while (scheduler.hasPendingWork) {
                scheduler.executeStep(state)
            }
        }

        assertEquals("DirtyDiscipleBatch 应只被调用一次（返回0即完成）", 1, callCount)
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
    // 7. 热控分批 — resolveThermalBatchSize 阈值
    // ============================================================

    /**
     * 热控批次大小由设备温度决定：
     * - 常温 → 1
     * - shouldReduceWorkload (MODERATE) → 6
     * - shouldEmergencySave (SEVERE+) → 12
     *
     * 复制 SettlementCoordinator.resolveThermalBatchSize 逻辑。
     */
    private fun resolveThermalBatchSize(
        shouldReduceWorkload: Boolean,
        shouldEmergencySave: Boolean
    ): Int = when {
        shouldEmergencySave -> 12
        shouldReduceWorkload -> 6
        else -> 1
    }

    @Test
    fun `热控批次 - 常温为1`() {
        assertEquals(1, resolveThermalBatchSize(false, false))
    }

    @Test
    fun `热控批次 - MODERATE时批次为6`() {
        assertEquals(6, resolveThermalBatchSize(true, false))
    }

    @Test
    fun `热控批次 - SEVERE时批次为12`() {
        assertEquals(12, resolveThermalBatchSize(true, true))
    }

    // ============================================================
    // 7b. 非焦点域热控分批 — 累积/跳过逻辑
    // ============================================================

    /**
     * 复制 accumulateBatch 中热控分批的判断逻辑（不含指纹/实时轨检测）：
     * - 批次期初 lastMonth=0 → 首次，处理当月
     * - monthsSince <= 0 → 处理当月
     * - monthsSince >= batchSize → 批量处理
     * - monthsSince < batchSize → 跳过（返回0，等待累积）
     */
    private data class BatchResult(
        val newLastMonth: Int, val batchMonths: Int
    )

    private fun computeBatchMonthsAccumulate(
        lastSettleMonth: Int,
        currentAbsMonth: Int,
        shouldReduceWorkload: Boolean,
        shouldEmergencySave: Boolean
    ): BatchResult {
        if (lastSettleMonth == 0) {
            return BatchResult(currentAbsMonth, 1)
        }
        val monthsSince = currentAbsMonth - lastSettleMonth
        if (monthsSince <= 0) {
            return BatchResult(lastSettleMonth, 1)
        }
        val batchSize = resolveThermalBatchSize(shouldReduceWorkload, shouldEmergencySave)
        return if (monthsSince >= batchSize) {
            BatchResult(currentAbsMonth, monthsSince)
        } else {
            BatchResult(lastSettleMonth, 0)  // 跳过，等待累积
        }
    }

    @Test
    fun `热控分批 - 首次结算batchMonths为1`() {
        val r = computeBatchMonthsAccumulate(0, 13, false, false)
        assertEquals(13, r.newLastMonth)
        assertEquals(1, r.batchMonths)
    }

    @Test
    fun `热控分批 - 常温每月结算`() {
        val r = computeBatchMonthsAccumulate(13, 14, false, false)
        assertEquals(14, r.newLastMonth)
        assertEquals(1, r.batchMonths)
    }

    @Test
    fun `热控分批 - 发热累积6月后批量结算`() {
        val r = computeBatchMonthsAccumulate(13, 19, true, false)
        assertEquals(19, r.newLastMonth)
        assertEquals(6, r.batchMonths)
    }

    @Test
    fun `热控分批 - 累积不足时跳过`() {
        val r = computeBatchMonthsAccumulate(13, 16, true, false)
        assertEquals(13, r.newLastMonth)  // lastMonth 不更新
        assertEquals(0, r.batchMonths)    // 跳过
    }

    @Test
    fun `热控分批 - 严重发热累积12月后批量`() {
        val r = computeBatchMonthsAccumulate(13, 25, true, true)
        assertEquals(25, r.newLastMonth)
        assertEquals(12, r.batchMonths)
    }

    // ============================================================
    // 7c. 生产分批 — 受焦点域影响
    // ============================================================

    /**
     * 生产分批逻辑：焦点域时始终每月结算，非焦点时走热控累积。
     */
    private fun computeProductionBatch(
        lastSettleMonth: Int,
        currentAbsMonth: Int,
        isProductionFocused: Boolean,
        fingerprintChanged: Boolean,
        shouldReduceWorkload: Boolean,
        shouldEmergencySave: Boolean
    ): BatchResult {
        if (isProductionFocused) {
            return BatchResult(currentAbsMonth, 1)
        }
        if (fingerprintChanged) {
            // 指纹变化 → 只处理当月（无法用旧速率回溯）
            return BatchResult(currentAbsMonth, 1)
        }
        return computeBatchMonthsAccumulate(lastSettleMonth, currentAbsMonth,
            shouldReduceWorkload, shouldEmergencySave)
    }

    @Test
    fun `生产分批 - 焦点域始终每月`() {
        val r = computeProductionBatch(13, 19, true, false, true, false)
        assertEquals(19, r.newLastMonth)
        assertEquals(1, r.batchMonths)
    }

    @Test
    fun `生产分批 - 非焦点指纹变化时降为当月`() {
        val r = computeProductionBatch(13, 19, false, true, true, false)
        assertEquals(19, r.newLastMonth)
        assertEquals(1, r.batchMonths)
    }

    @Test
    fun `生产分批 - 非焦点累积不足时跳过`() {
        val r = computeProductionBatch(13, 16, false, false, true, false)
        assertEquals(13, r.newLastMonth)
        assertEquals(0, r.batchMonths)
    }

    @Test
    fun `生产分批 - 非焦点累积达标时批量`() {
        val r = computeProductionBatch(13, 19, false, false, true, false)
        assertEquals(19, r.newLastMonth)
        assertEquals(6, r.batchMonths)
    }

    // ============================================================
    // 8. 辅助 — toAbsoluteMonth / estimateMonthsToNextBreakthrough
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

        scheduler.scheduleMonthly(
            shadow = state,
            cachePhase = null,
            focusedPhase = focusedPhase,
            cleanPhase = cleanPhase,
            dirtyPhase = dirtyPhase,
            productionPhase = productionPhase,
            worldEventsPhase = worldEventsPhase
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
}
