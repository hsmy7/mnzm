package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.engine.system.TimeSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * DiffEngineLoopTest — 引擎循环跨语言差分对拍（计划 v2 阶段 5 验收核心）。
 *
 * 守护目标：
 * - **场景 A（时钟状态机）**：C++ PhaseClock（墙钟消费/速度/追补上限/refund）
 *   与 Kotlin [GameTimeClock] 同时间脚本逐位一致——GameTimeClock 语义的
 *   双端锚定（对齐 GameTimeClockTest 23 条语义的代表性场景）
 * - **场景 B（帧计划）**：C++ EngineLoop 帧迭代判据（累积钳制 5 步/暂停
 *   分支/isSaving 跳过/alpha/idleNs/tickTotal）符合 gameLoopIteration 语义
 *
 * 驱动方式：C++ 侧经 [DiffRngBridge.nativeCoreLoopSetMonoMs] 推进
 * FixedMonotonicClock（100ms 帧间隔 = 生产逻辑步长），帧计划经
 * [DiffRngBridge.nativeCoreLoopFrame] 读取；Kotlin 侧 FakeTimeSource
 * 手工推进 gameClock.tick()。对齐语义：生产循环首帧 delta≈0（协程启动即
 * 计时），故 C++ 循环基准重置后先点火一帧（resetCppLoop 内）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffEngineLoopTest {

    /** 可手工推进的单调时钟（Kotlin 基准侧） */
    private class FakeTimeSource(var now: Long = 0L) : TimeSource {
        override fun elapsedRealtime(): Long = now
        fun advanceBy(ms: Long) { now += ms }
    }

    private lateinit var fakeTime: FakeTimeSource
    private lateinit var gameClock: GameTimeClock

    /**
     * C++ 引擎就绪（幂等 init + 循环状态完全重置 + 时钟归零 + 循环基准重置 +
     * 首帧点火——对齐 Kotlin gameClock.start() 在 t=0 的基准语义）。
     * nativeCoreInit 幂等复用单例（阶段 1 既有设计），tick 计数/速度/累积
     * 跨用例残留——须经 nativeCoreLoopReset 重建基准，否则与 Kotlin 侧
     * 每用例 new GameTimeClock 的干净状态不对称。
     */
    private fun resetCppLoop() {
        DiffRngBridge.nativeCoreInit()
        DiffRngBridge.nativeCoreLoopReset()
        DiffRngBridge.nativeCoreLoopSetMonoMs(0L)
        DiffRngBridge.nativeCoreLoopStart()
        DiffRngBridge.nativeCoreLoopFrame(false, false)  // 首帧点火（delta=0）
    }

    @Before
    fun setUp() {
        assumeTrue("桌面 JNI 未注入，跳过对拍", DiffRngBridge.isAvailable())
        fakeTime = FakeTimeSource()
        gameClock = GameTimeClock(fakeTime)
        gameClock.start()
        resetCppLoop()
    }

    /**
     * 推进一个 100ms 逻辑帧（双端）：返回 (kotlinPhases, cppPhasesSum)。
     * C++ 帧内各 tick phases 求和 == Kotlin 单次 tick 的 phasesToAdvance
     * （C++ 首 tick 消费全部墙钟 delta，与 Kotlin 每 100ms tick 语义一致）。
     */
    private fun advanceFrame100ms(): Pair<Int, Int> {
        fakeTime.advanceBy(100L)
        val kotlinPhases = gameClock.tick(isSettlementPending = false).phasesToAdvance

        DiffRngBridge.nativeCoreLoopSetMonoMs(fakeTime.now)
        val plan = DiffRngBridge.nativeCoreLoopFrame(false, false)
        assertEquals("帧计划应为 17 槽", 17, plan.size)
        val cppPhases = (7..11).sumOf { plan[it].toInt() }
        // 100ms 帧恰 1 个逻辑 tick
        assertEquals("100ms 帧应恰 1 个逻辑 tick", 1L, plan[1])
        return kotlinPhases to cppPhases
    }

    /** 双端断言帧推进旬数一致 */
    private fun assertFramePhases() {
        val (kotlinPhases, cppPhases) = advanceFrame100ms()
        assertEquals("帧推进旬数不一致", kotlinPhases, cppPhases)
    }

    // ── 场景 A：时钟状态机对拍 ──────────────────────────────

    @Test
    fun `speed1x 2000ms advances 1 phase at same frame both ends`() {
        // 20 帧 × 100ms：恰在第 20 帧凑满 2000ms → 1 旬（双端同帧触发）
        repeat(19) { assertFramePhases() }
        val (kotlinPhases, cppPhases) = advanceFrame100ms()
        assertEquals("第 20 帧双端应各 1 旬", 1, kotlinPhases)
        assertEquals(kotlinPhases, cppPhases)
    }

    @Test
    fun `speed2x doubles accumulation both ends`() {
        gameClock.setSpeed(2)
        DiffRngBridge.nativeCoreLoopSetSpeed(2)
        // 2x 下每旬 1000ms 游戏时间（每帧 100ms 墙钟 → 200ms 游戏时间）：
        // 帧 5 首次凑满 1000ms → 1 旬；帧 10 第二次凑满 → 1 旬（共 2 旬）。
        // 注意首旬在第 5 帧消费——第 10 帧只剩 1 旬，不能断言 2。
        repeat(4) { assertFramePhases() }
        val (firstKotlin, firstCpp) = advanceFrame100ms()
        assertEquals("帧 5 双端应各 1 旬", 1, firstKotlin)
        assertEquals(firstKotlin, firstCpp)
        repeat(4) { assertFramePhases() }
        val (secondKotlin, secondCpp) = advanceFrame100ms()
        assertEquals("帧 10 双端应各 1 旬", 1, secondKotlin)
        assertEquals(secondKotlin, secondCpp)
    }

    @Test
    fun `speed switch preserves accumulation both ends`() {
        // 1x 累积 1500ms（15 帧）
        repeat(15) { assertFramePhases() }
        assertEquals(1500L, gameClock.accumulatedGameMs)
        // 切 2x（双端各自按旧速度结算）
        gameClock.setSpeed(2)
        DiffRngBridge.nativeCoreLoopSetSpeed(2)
        // 2x 再 5 帧（500ms → 1000ms 游戏时间）：累积 2500，消费 2000，余 500
        repeat(5) { assertFramePhases() }
        assertEquals("双端累积应一致", gameClock.accumulatedGameMs,
            DiffRngBridge.nativeCoreLoopAccumulatedGameMs())
    }

    @Test
    fun `pause speed0 freezes accumulation both ends`() {
        repeat(5) { assertFramePhases() }
        val accBefore = gameClock.accumulatedGameMs
        gameClock.setSpeed(0)
        DiffRngBridge.nativeCoreLoopSetSpeed(0)
        repeat(10) { assertFramePhases() }
        assertEquals("Kotlin speed=0 不累积", accBefore, gameClock.accumulatedGameMs)
        assertEquals("C++ speed=0 不累积", accBefore,
            DiffRngBridge.nativeCoreLoopAccumulatedGameMs())
    }

    @Test
    fun `catch-up cap drops remainder both ends`() {
        // 8000ms 一帧：Kotlin 单 tick → 3 旬截断、余量丢弃；C++ 钳制 500ms
        // 5 tick、首 tick 消费 8000ms → 同样 3 旬截断
        fakeTime.advanceBy(8000L)
        val kotlinPhases = gameClock.tick(isSettlementPending = false).phasesToAdvance
        assertEquals(GameTimeClock.MAX_PHASES_PER_TICK, kotlinPhases)

        DiffRngBridge.nativeCoreLoopSetMonoMs(fakeTime.now)
        val plan = DiffRngBridge.nativeCoreLoopFrame(false, false)
        assertEquals("C++ 追补截断应一致", kotlinPhases, (7..11).sumOf { plan[it].toInt() })
        assertEquals("余量丢弃：双端累积归零一致", gameClock.accumulatedGameMs,
            DiffRngBridge.nativeCoreLoopAccumulatedGameMs())
        // 下一帧 100ms：双端均 0 旬（爆炸余量已丢弃，不残留）
        val (nextKotlin, nextCpp) = advanceFrame100ms()
        assertEquals(0, nextKotlin)
        assertEquals(0, nextCpp)
    }

    @Test
    fun `refund phases restores accumulation both ends`() {
        repeat(10) { assertFramePhases() }  // 累积 1000ms
        gameClock.refundPhases(1)
        DiffRngBridge.nativeCoreLoopRefundPhases(1)
        assertEquals("refund 后双端累积一致（1000+2000）", gameClock.accumulatedGameMs,
            DiffRngBridge.nativeCoreLoopAccumulatedGameMs())
    }

    @Test
    fun `dead time consumption skips accumulation both ends`() {
        // 阻塞 3000ms：Kotlin 死区消费（gameClock 基准刷新）；C++ 侧先推进
        // 时钟到同一墙钟点再消费死区（旬累积基准，与 gameClock.consumeDeadTime
        // 对拍），随后走一帧暂停分支（生产语义：保存/加载阻塞期间帧迭代
        // pausedOrLoading=true，内部 consumeDeadTime + 帧基准刷新）——只消费
        // 死区不补暂停期，恢复帧不会产生 3000ms 虚高 delta
        fakeTime.advanceBy(3000L)
        gameClock.consumeDeadTime()
        DiffRngBridge.nativeCoreLoopSetMonoMs(fakeTime.now)
        DiffRngBridge.nativeCoreLoopConsumeDeadTime()
        val pausedPlan = DiffRngBridge.nativeCoreLoopFrame(true, false)
        assertTrue("阻塞帧应标记 paused", pausedPlan[0] != 0L)
        repeat(3) { assertFramePhases() }
        assertEquals("死区消费后双端累积一致（仅 300ms）", gameClock.accumulatedGameMs,
            DiffRngBridge.nativeCoreLoopAccumulatedGameMs())
    }

    // ── 场景 B：帧计划语义（gameLoopIteration 判据） ────────

    /** 首帧 delta=0（loopStart 后哨兵语义：协程启动即计时，首帧无流逝） */
    @Test
    fun `frame plan - first frame has zero delta`() {
        DiffRngBridge.nativeCoreLoopStart()
        val plan = DiffRngBridge.nativeCoreLoopFrame(false, false)
        assertEquals(0L, plan[0])   // paused
        assertEquals(0L, plan[1])   // tickCount
        assertEquals(0L, plan[13])  // frameDeltaNs
    }

    /** 2000ms 一帧：累积钳制 500ms → 5 tick；首 tick 1 旬，其余 0 */
    @Test
    fun `frame plan - 2000ms clamped to five steps`() {
        DiffRngBridge.nativeCoreLoopSetMonoMs(2000L)
        val plan = DiffRngBridge.nativeCoreLoopFrame(false, false)
        assertEquals(5L, plan[1])
        assertEquals(1L, plan[7])
        assertArrayEquals("后续 tick 墙钟 delta=0",
            longArrayOf(0, 0, 0, 0), plan.slice(8..11).toLongArray())
        assertEquals(500_000_000L, plan[13])
        assertEquals(5L, plan[15])  // tickTotal
    }

    /** 暂停分支：paused=1，死区消费（恢复帧不产生虚高 delta） */
    @Test
    fun `frame plan - paused branch consumes dead time`() {
        DiffRngBridge.nativeCoreLoopSetMonoMs(300L)
        assertEquals(1L, DiffRngBridge.nativeCoreLoopFrame(true, false)[0])
        DiffRngBridge.nativeCoreLoopSetMonoMs(400L)
        val resumed = DiffRngBridge.nativeCoreLoopFrame(false, false)
        assertEquals("恢复帧仅 1 tick（100ms）", 1L, resumed[1])
        assertEquals("不补 300ms 暂停期", 0L, resumed[7])
    }

    /** isSaving 跳过 tick：kind=0、计数不推进、死区消费（不补 600ms） */
    @Test
    fun `frame plan - saving skips ticks`() {
        DiffRngBridge.nativeCoreLoopSetMonoMs(600L)
        val plan = DiffRngBridge.nativeCoreLoopFrame(false, true)
        assertEquals("600ms 帧钳制 5 步", 5L, plan[1])
        assertArrayEquals("全部跳过（kind=0）",
            longArrayOf(0, 0, 0, 0, 0), plan.slice(2..6).toLongArray())
        assertEquals("tick 计数不推进", 0L, plan[15])
        assertArrayEquals("无旬推进",
            longArrayOf(0, 0, 0, 0, 0), plan.slice(7..11).toLongArray())
        // 恢复（100ms）：600ms 帧钳制 500ms 恰 5 步、帧累积归零，死区已消费，
        // PhaseClock 从 600ms 重新起算 → 1 个逻辑 tick，均无旬推进
        DiffRngBridge.nativeCoreLoopSetMonoMs(700L)
        val resumed = DiffRngBridge.nativeCoreLoopFrame(false, false)
        assertEquals(1L, resumed[1])
        assertEquals(1L, resumed[15])
        assertEquals(0L, resumed[7])
    }

    /** alpha = accumulator/LOGIC_DT：150ms 帧 → 1 tick 余 50ms → 0.5 */
    @Test
    fun `frame plan - alpha is accumulator ratio`() {
        DiffRngBridge.nativeCoreLoopSetMonoMs(150L)
        val plan = DiffRngBridge.nativeCoreLoopFrame(false, false)
        assertEquals(1L, plan[1])
        assertEquals(0.5f, Float.fromBits(plan[12].toInt()), 1e-6f)
    }

    /** 输入端口：未活跃 idleNs=-1；notifyUserActivity 后 idleNs=流逝纳秒 */
    @Test
    fun `frame plan - user activity maintains idleNs`() {
        assertEquals(-1L, DiffRngBridge.nativeCoreLoopFrame(false, false)[14])
        DiffRngBridge.nativeCoreLoopSetMonoMs(1000L)
        DiffRngBridge.nativeCoreLoopNotifyUserActivity()
        DiffRngBridge.nativeCoreLoopSetMonoMs(3000L)
        assertEquals(2_000_000_000L, DiffRngBridge.nativeCoreLoopFrame(false, false)[14])
    }

    /** tickTotal 跨帧累计（Kotlin _tickCount 镜像真相源） */
    @Test
    fun `frame plan - tick total accumulates across frames`() {
        DiffRngBridge.nativeCoreLoopSetMonoMs(100L)
        assertEquals(1L, DiffRngBridge.nativeCoreLoopFrame(false, false)[15])
        DiffRngBridge.nativeCoreLoopSetMonoMs(200L)
        assertEquals(2L, DiffRngBridge.nativeCoreLoopFrame(false, false)[15])
        assertEquals("查询通道一致", 2L, DiffRngBridge.nativeCoreLoopTickTotal())
    }

    /** 循环重启（紧急重启换线程）：帧状态清零、首帧 delta=0 */
    @Test
    fun `frame plan - loop restart clears frame state`() {
        DiffRngBridge.nativeCoreLoopSetMonoMs(300L)
        DiffRngBridge.nativeCoreLoopFrame(false, false)
        DiffRngBridge.nativeCoreLoopStart()
        val plan = DiffRngBridge.nativeCoreLoopFrame(false, false)
        assertEquals(0L, plan[13])
        assertEquals(0L, plan[1])
        // tickTotal 跨重启保留（Kotlin _tickCount 生命周期同引擎实例）
        assertTrue("tickTotal 跨重启保留", plan[15] >= 1L)
    }
}
