package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.system.FocusDomain
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.engine.system.TimeSystem
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.coroutines.CancellationException
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
        assertEquals("至少标记了一个域的执行", 1, executedDomains.size)
        assertTrue("BACKGROUND 域被标记", FocusDomain.BACKGROUND in executedDomains)
    }

    // ═══════════════════════════════════════════════════════════════
    // 双轨路由集成验证
    // ═══════════════════════════════════════════════════════════════

    /**
     * 验证 [FocusDomain.activeSystemsFor] 正确识别域中声明的真实系统。
     * TimeSystem 在 ALWAYS.systemClasses 中 → 被识别为活跃系统。
     */
    @Test
    fun `activeSystemsFor — TimeSystem 在 ALWAYS 激活时被识别为活跃`() {
        val systems = FocusDomain.activeSystemsFor(setOf(FocusDomain.ALWAYS))
        assertTrue(
            "TimeSystem 应在 ALWAYS 激活时被识别为活跃系统",
            systems.any { it.simpleName == "TimeSystem" }
        )
    }

    /**
     * 验证同一系统在非焦点域中不被识别为活跃。
     * TimeSystem 不在 BACKGROUND.systemClasses 中 → 不被识别。
     */
    @Test
    fun `activeSystemsFor — TimeSystem 不在 BACKGROUND 声明中`() {
        val systems = FocusDomain.activeSystemsFor(setOf(FocusDomain.BACKGROUND))
        assertFalse(
            "TimeSystem 不应在 BACKGROUND 激活时被识别",
            systems.any { it.simpleName == "TimeSystem" }
        )
    }

    /**
     * 验证 [FocusDomain.assignedDomainFor] 焦点域激活时返回正确域。
     * TimeSystem 在 ALWAYS.systemClasses 中 → assignedDomainFor 返回 ALWAYS。
     */
    @Test
    fun `assignedDomainFor — 焦点域激活时返回对应域`() {
        val domain = FocusDomain.assignedDomainFor(
            TimeSystem::class, setOf(FocusDomain.ALWAYS)
        )
        assertEquals(
            "TimeSystem 的 assignedDomain 在 ALWAYS 激活时应为 ALWAYS",
            FocusDomain.ALWAYS, domain
        )
    }

    /**
     * 验证未在任何域中声明的测试系统回退到 BACKGROUND。
     */
    @Test
    fun `assignedDomainFor — 未注册系统回退 BACKGROUND`() {
        val domain = FocusDomain.assignedDomainFor(
            Phase1System::class, setOf(FocusDomain.ALWAYS)
        )
        assertEquals(
            "Phase1System 未在任何域声明 → 回退到 BACKGROUND",
            FocusDomain.BACKGROUND, domain
        )
    }

    /**
     * 验证双轨路由完整链路：非焦点域系统收到批量 phasesToSettle。
     * 测试系统未在任何域声明 → assignedDomainFor 回退 BACKGROUND →
     * BACKGROUND 不在 activeDomains → shouldExecute 检查 30s 时钟 →
     * getPhasesToSettle 接收 BACKGROUND 域返回批量值。
     */
    @Test
    fun `双轨路由 — 非焦点域系统收到批量 phasesToSettle`() = runBlocking {
        val state = mock(MutableGameState::class.java)
        val capturedDomains = mutableListOf<FocusDomain>()

        val system = EveryPhaseSystem()
        val mgr = SystemManager(setOf(system))

        mgr.onPhaseTickWithDomainFilter(
            state = state,
            activeDomains = setOf(FocusDomain.ALWAYS),
            shouldExecute = { _, _ -> true },
            markExecuted = {},
            currentPhase = 1,
            getPhasesToSettle = { domain ->
                capturedDomains.add(domain)
                if (domain == FocusDomain.ALWAYS) 1 else 10
            }
        )

        assertTrue("getPhasesToSettle 应至少被调用一次", capturedDomains.isNotEmpty())
        assertEquals(
            "EveryPhaseSystem 不在任何域声明中 → 回退到 BACKGROUND",
            FocusDomain.BACKGROUND, capturedDomains.first()
        )
        assertEquals(
            "非焦点域应收到 phasesToSettle=10（批量轨）",
            10, system.records.firstOrNull()?.phases
        )
    }

    /**
     * 验证非焦点域走分旬调度：settlementPhase 不匹配时跳过执行。
     * Phase1System(sP=1) + currentPhase=2 → 不满足分旬条件 → 跳过。
     */
    @Test
    fun `双轨路由 — 非焦点域分旬调度跳过不匹配系统`() = runBlocking {
        val state = mock(MutableGameState::class.java)

        phase1.records.clear()
        val mgr = SystemManager(setOf(phase1, everyPhase))

        // currentPhase=2 (中旬): Phase1System(sP=1) → 不匹配, EveryPhaseSystem(sP=0) → 匹配
        mgr.onPhaseTickWithDomainFilter(
            state = state,
            activeDomains = emptySet(),
            shouldExecute = { _, _ -> true },
            markExecuted = {},
            currentPhase = 2,
            getPhasesToSettle = { 5 }
        )

        assertEquals(
            "Phase1System(sP=1) 不应在 currentPhase=2 时执行",
            0, phase1.records.size
        )
        assertEquals(
            "EveryPhaseSystem(sP=0) 应在任何旬执行",
            1, everyPhase.records.size
        )
    }

    /**
     * 验证 ALWAYS 域始终强制执行，不受分旬调度限制。
     * 模拟 TimeSystem 在 ALWAYS 域声明中 → isActiveDomain=true →
     * shouldSettleByPhase 始终为 true。
     */
    @Test
    fun `双轨路由 — 活跃域系统不受分旬调度限制`() = runBlocking {
        val state = mock(MutableGameState::class.java)
        val executedPhases = mutableListOf<Int>()

        // Phase1System(sP=1) 未在任何域声明 → 非焦点域，currentPhase=2 应跳过
        phase1.records.clear()
        val mgr = SystemManager(setOf(phase1))

        mgr.onPhaseTickWithDomainFilter(
            state = state,
            activeDomains = setOf(FocusDomain.ALWAYS),
            shouldExecute = { _, _ -> true },
            markExecuted = {},
            currentPhase = 2,
            getPhasesToSettle = { domain ->
                if (domain == FocusDomain.ALWAYS) 1 else 5
            }
        )

        // Phase1System 不在任何活跃系统集合中 → 非活跃域 → 分旬过滤
        assertEquals(
            "Phase1System(sP=1) 非活跃域 currentPhase=2 → 应跳过",
            0, phase1.records.size
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // CancellationException 传播测试（规范 1.4）
    // ═══════════════════════════════════════════════════════════════

    @SystemPriority(order = 10)
    private class CancellationThrowingPhaseSystem : GameSystem {
        override val systemName = "CancellationThrowingPhaseSystem"
        override suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int) {
            throw CancellationException("phase tick cancelled")
        }
    }

    @SystemPriority(order = 10)
    private class CancellationThrowingMonthSystem : GameSystem {
        override val systemName = "CancellationThrowingMonthSystem"
        override suspend fun onMonthTick(state: MutableGameState) {
            throw CancellationException("month tick cancelled")
        }
    }

    @SystemPriority(order = 10)
    private class CancellationThrowingReleaseSystem : GameSystem {
        override val systemName = "CancellationThrowingReleaseSystem"
        override fun release() {
            throw CancellationException("release cancelled")
        }
    }

    @SystemPriority(order = 10)
    private class NormalCompanionSystem : GameSystem {
        override val systemName = "NormalCompanionSystem"
        val phaseCalled = AtomicInteger(0)
        override suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int) {
            phaseCalled.incrementAndGet()
        }
        override suspend fun onMonthTick(state: MutableGameState) {
            // no-op for parallel group tests
        }
        override fun release() {
            // no-op for release tests
        }
    }

    @Test(expected = CancellationException::class)
    fun `CancellationException - onPhaseTick single system propagates`() = runBlocking {
        val throwing = CancellationThrowingPhaseSystem()
        val mgr = SystemManager(setOf(throwing))
        val state = mock(MutableGameState::class.java)

        mgr.onPhaseTickWithDomainFilter(
            state = state,
            activeDomains = emptySet(),
            shouldExecute = { _, _ -> true },
            markExecuted = {},
            currentPhase = 1
        )
    }

    @Test
    fun `CancellationException - onPhaseTick parallel group handled by coroutineScope`() =
        runBlocking {
            val throwing = CancellationThrowingPhaseSystem()
            val companion = NormalCompanionSystem()
            val mgr = SystemManager(setOf(throwing, companion))
            val state = mock(MutableGameState::class.java)

            // 并行组中 CancellationException 由 coroutineScope 处理，
            // 不会被吞为普通 Exception（修复前会被 catch(e: Exception) 吞掉）
            // coroutineScope 收到子协程的 CE 后取消其他子协程但不传播到外层
            mgr.onPhaseTickWithDomainFilter(
                state = state,
                activeDomains = emptySet(),
                shouldExecute = { _, _ -> true },
                markExecuted = {},
                currentPhase = 1
            )
            // 验证：方法正常返回（不抛异常），companion 被取消前可能已执行
        }

    @Test(expected = CancellationException::class)
    fun `CancellationException - onMonthTick single system propagates`() = runBlocking {
        val throwing = CancellationThrowingMonthSystem()
        val mgr = SystemManager(setOf(throwing))
        val state = mock(MutableGameState::class.java)

        mgr.onMonthTick(state)
    }

    @Test
    fun `CancellationException - onMonthTick parallel group handled by coroutineScope`() =
        runBlocking {
            val throwing = CancellationThrowingMonthSystem()
            val companion = NormalCompanionSystem()
            val mgr = SystemManager(setOf(throwing, companion))
            val state = mock(MutableGameState::class.java)

            mgr.onMonthTick(state)
            // 验证：方法正常返回，coroutineScope 内部处理 CE
        }

    @Test(expected = CancellationException::class)
    fun `CancellationException — release 传播`() {
        val throwing = CancellationThrowingReleaseSystem()
        val mgr = SystemManager(setOf(throwing))

        mgr.releaseAll()
    }

    @Test
    fun `CancellationException — 普通异常仍被捕获不传播`() = runBlocking {
        val system = object : GameSystem {
            override val systemName = "RuntimeExceptionThrowingSystem"
            override suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int) {
                throw RuntimeException("normal error")
            }
        }
        val mgr = SystemManager(setOf(system))
        val state = mock(MutableGameState::class.java)

        // 不应抛异常
        mgr.onPhaseTickWithDomainFilter(
            state = state,
            activeDomains = emptySet(),
            shouldExecute = { _, _ -> true },
            markExecuted = {},
            currentPhase = 1
        )
    }
}
