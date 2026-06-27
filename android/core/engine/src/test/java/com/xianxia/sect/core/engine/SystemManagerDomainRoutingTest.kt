package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.system.FocusDomain
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.util.concurrent.atomic.AtomicInteger

/**
 * SystemManager 域路由集成测试。
 *
 * 测试桩未在 FocusDomain.systemClasses 中声明，因此所有测试系统
 * 的 assignedDomainFor 均回退到 BACKGROUND。测试验证：
 * 1. shouldExecute 回调控制执行
 * 2. markExecuted 回调正确记录
 * 3. settlementPhase 分旬调度
 * 4. phasesToSettle 正确传递
 */
class SystemManagerDomainRoutingTest {

    private class CallRecord(val domain: FocusDomain, val phases: Int)

    @SystemPriority(order = 10)
    private class Phase1System : GameSystem {
        override val systemName = "Phase1System"
        override val settlementPhase = 1 // 仅上旬
        val records = mutableListOf<CallRecord>()

        override suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int) {
            // 实际系统中 assignedDomainFor 会返回 BACKGROUND
            records.add(CallRecord(FocusDomain.BACKGROUND, phasesToSettle))
        }
    }

    @SystemPriority(order = 20)
    private class Phase2System : GameSystem {
        override val systemName = "Phase2System"
        override val settlementPhase = 2 // 仅中旬
        val records = mutableListOf<CallRecord>()
        var callCount = AtomicInteger(0)

        override suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int) {
            callCount.incrementAndGet()
            records.add(CallRecord(FocusDomain.BACKGROUND, phasesToSettle))
        }
    }

    @SystemPriority(order = 30)
    private class EveryPhaseSystem : GameSystem {
        override val systemName = "EveryPhaseSystem"
        override val settlementPhase = 0 // 每旬都结算
        val records = mutableListOf<CallRecord>()

        override suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int) {
            records.add(CallRecord(FocusDomain.BACKGROUND, phasesToSettle))
        }
    }

    private lateinit var phase1: Phase1System
    private lateinit var phase2: Phase2System
    private lateinit var everyPhase: EveryPhaseSystem
    private lateinit var systemManager: SystemManager

    @Before
    fun setUp() {
        phase1 = Phase1System()
        phase2 = Phase2System()
        everyPhase = EveryPhaseSystem()
        systemManager = SystemManager(setOf(phase1, phase2, everyPhase))
    }

    // ═══════════════════════════════════════════════════════════════
    // shouldExecute 控制：返回 true 时所有系统执行
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `shouldExecute 返回 true — 满足分旬条件的系统全执行`() = runBlocking {
        val state = mock(MutableGameState::class.java)
        val executedDomains = mutableSetOf<FocusDomain>()

        // 所有系统 assignedDomainFor → BACKGROUND
        // shouldExecute 对 BACKGROUND 返回 true → 执行
        // currentPhase=1: Phase1System(sP=1) ✓, Phase2System(sP=2) ✗, EveryPhaseSystem(sP=0) ✓
        systemManager.onPhaseTickWithDomainFilter(
            state = state,
            activeDomains = emptySet(), // 无活跃域 → 所有系统非活跃
            shouldExecute = { domain, _ -> domain == FocusDomain.BACKGROUND },
            markExecuted = { executedDomains.add(it) },
            currentPhase = 1
        )

        assertEquals("Phase1System 应执行", 1, phase1.records.size)
        assertEquals("Phase2System 不应执行（settlementPhase=2, currentPhase=1）", 0, phase2.records.size)
        assertEquals("EveryPhaseSystem 应执行", 1, everyPhase.records.size)
        assertTrue("BACKGROUND 应被标记为已执行", FocusDomain.BACKGROUND in executedDomains)
    }

    // ═══════════════════════════════════════════════════════════════
    // shouldExecute 控制：返回 false 时跳过所有系统
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `shouldExecute 返回 false — 不执行任何系统`() = runBlocking {
        val state = mock(MutableGameState::class.java)
        val executedDomains = mutableSetOf<FocusDomain>()

        systemManager.onPhaseTickWithDomainFilter(
            state = state,
            activeDomains = emptySet(),
            shouldExecute = { _, _ -> false },
            markExecuted = { executedDomains.add(it) },
            currentPhase = 1
        )

        assertEquals("Phase1System 不应执行", 0, phase1.records.size)
        assertEquals("Phase2System 不应执行", 0, phase2.records.size)
        assertEquals("EveryPhaseSystem 不应执行", 0, everyPhase.records.size)
        assertTrue("无域被执行标记", executedDomains.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // 分旬调度：settlementPhase 控制非活跃域系统
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `分旬调度 — 第 1 旬仅 settlementPhase≤1 的系统执行`() = runBlocking {
        val state = mock(MutableGameState::class.java)
        val executedDomains = mutableSetOf<FocusDomain>()

        // 所有系统非活跃域 → settlementPhase 过滤生效
        systemManager.onPhaseTickWithDomainFilter(
            state = state,
            activeDomains = emptySet(),
            shouldExecute = { _, _ -> true },
            markExecuted = { executedDomains.add(it) },
            currentPhase = 1 // 上旬
        )

        assertTrue("Phase1System (sP=1) 应在第1旬执行", phase1.records.isNotEmpty())
        assertTrue("Phase2System (sP=2) 不应在第1旬执行", phase2.records.isEmpty())
        assertTrue("EveryPhaseSystem (sP=0) 应在第1旬执行", everyPhase.records.isNotEmpty())
    }

    @Test
    fun `分旬调度 — 第 2 旬仅 settlementPhase=0或2 的系统执行`() = runBlocking {
        val state = mock(MutableGameState::class.java)
        val executedDomains = mutableSetOf<FocusDomain>()

        // 非活跃域：shouldSettleByPhase = (sP==0 || sP==currentPhase)
        // currentPhase=2: Phase1System(sP=1)→false, Phase2System(sP=2)→true, EveryPhaseSystem(sP=0)→true
        systemManager.onPhaseTickWithDomainFilter(
            state = state,
            activeDomains = emptySet(),
            shouldExecute = { _, _ -> true },
            markExecuted = { executedDomains.add(it) },
            currentPhase = 2
        )

        assertEquals("Phase1System (sP=1) 不应在第2旬执行（非活跃域，sP≠2且≠0）", 0, phase1.records.size)
        assertEquals("Phase2System (sP=2) 应在第2旬执行", 1, phase2.records.size)
        assertEquals("EveryPhaseSystem (sP=0) 应在每旬执行", 1, everyPhase.records.size)
    }

    // ═══════════════════════════════════════════════════════════════
    // phasesToSettle：实时轨=1，批量轨=N
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `phasesToSettle — 自定义回调控制结算旬数`() = runBlocking {
        val state = mock(MutableGameState::class.java)
        val executedDomains = mutableSetOf<FocusDomain>()

        // 模拟批量轨：非活跃域累积 5 旬后一次性结算
        systemManager.onPhaseTickWithDomainFilter(
            state = state,
            activeDomains = emptySet(),
            shouldExecute = { _, _ -> true },
            markExecuted = { executedDomains.add(it) },
            currentPhase = 1,
            getPhasesToSettle = { 5 } // 批量轨累积 5 旬
        )

        assertEquals("Phase1System 应收到 phasesToSettle=5", 5, phase1.records.firstOrNull()?.phases)
        assertEquals("EveryPhaseSystem 应收到 phasesToSettle=5", 5, everyPhase.records.firstOrNull()?.phases)
    }

    @Test
    fun `phasesToSettle — 域不在 activeSystemClasses 时走批量轨累积`() = runBlocking {
        val state = mock(MutableGameState::class.java)
        val executedDomains = mutableSetOf<FocusDomain>()

        // 测试系统未在 BACKGROUND 的 systemClasses 中声明
        // → isActiveDomain=false（即使 BACKGROUND 在 activeDomains 中）
        // → 走分旬调度（sP 过滤）+ phasesToSettle=10（批量轨）
        systemManager.onPhaseTickWithDomainFilter(
            state = state,
            activeDomains = setOf(FocusDomain.BACKGROUND),
            shouldExecute = { _, _ -> true },
            markExecuted = { executedDomains.add(it) },
            currentPhase = 1,
            getPhasesToSettle = { 10 } // 批量轨累积 10 旬
        )

        // Phase1System(sP=1) 和 EveryPhaseSystem(sP=0) 满足分旬条件
        assertTrue("Phase1System 应执行（分旬条件满足）", phase1.records.isNotEmpty())
        assertTrue("EveryPhaseSystem 应执行（sP=0 每旬都执行）", everyPhase.records.isNotEmpty())
        // Phase2System(sP=2) 不满足分旬条件（currentPhase=1）
        assertTrue("Phase2System 不应执行（sP=2≠currentPhase=1）", phase2.records.isEmpty())
        assertEquals("批量轨 phasesToSettle=10", 10, phase1.records.firstOrNull()?.phases)
    }

    @Test
    fun `活跃域系统 — BACKGROUND 域活跃时系统被识别为活跃（仅当系统在域声明中）`() = runBlocking {
        val state = mock(MutableGameState::class.java)

        // 测试系统不在 BACKGROUND.systemClasses 中 → isActiveDomain=false
        // 但 BACKGROUND 在 activeDomains 中 → shouldExecute(BACKGROUND, ...) 返回 true
        phase1.records.clear()
        phase2.records.clear()
        phase2.callCount.set(0)
        everyPhase.records.clear()

        systemManager.onPhaseTickWithDomainFilter(
            state = state,
            activeDomains = setOf(FocusDomain.BACKGROUND),
            shouldExecute = { domain, _ -> domain == FocusDomain.BACKGROUND },
            markExecuted = {},
            currentPhase = 1
        )

        // 分旬过滤：Phase1System(sP=1) ✓, Phase2System(sP=2) ✗, EveryPhaseSystem(sP=0) ✓
        assertEquals("Phase1System 应执行", 1, phase1.records.size)
        assertEquals("Phase2System (sP=2) 在 currentPhase=1 不执行", 0, phase2.callCount.get())
        assertEquals("EveryPhaseSystem 应执行", 1, everyPhase.records.size)
    }

    // ═══════════════════════════════════════════════════════════════
    // markExecuted：执行标记回调
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `markExecuted — 每次执行后正确回调`() = runBlocking {
        val state = mock(MutableGameState::class.java)
        val executedDomains = mutableSetOf<FocusDomain>()

        systemManager.onPhaseTickWithDomainFilter(
            state = state,
            activeDomains = emptySet(),
            shouldExecute = { _, _ -> true },
            markExecuted = { executedDomains.add(it) },
            currentPhase = 1
        )

        // 有两个系统执行（Phase1System + EveryPhaseSystem），都映射到 BACKGROUND
        // markExecuted 被调用两次，但两次都是 BACKGROUND → set 去重 = 1 个元素
        assertEquals("至少标记了一个域的执���", 1, executedDomains.size)
        assertTrue("BACKGROUND 域被标记", FocusDomain.BACKGROUND in executedDomains)
    }
}
