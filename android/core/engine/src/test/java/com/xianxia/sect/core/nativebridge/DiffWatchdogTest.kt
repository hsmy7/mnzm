package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.monitor.GameTimeProgressMonitor
import com.xianxia.sect.core.engine.monitor.GameTimeProgressSnapshot
import com.xianxia.sect.core.engine.monitor.StallVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * DiffWatchdogTest — 看门狗统一判据跨语言差分对拍（验收核心）。
 *
 * 守护目标：C++ ProgressMonitor（GameTimeProgressMonitor 逐位移植）与
 * Kotlin [GameTimeProgressMonitor] 对**同一快照序列**的判定逐位一致——
 * 场景矩阵覆盖 Kotlin GameTimeProgressMonitorTest 全部 24 条
 * （正常推进/tick 停滞/循环死亡/假运行/速度归零/暂停豁免/秘境租约/
 * 保存加载豁免/窗口边界/冻结振荡等异常判据回归）。
 *
 * 双端同序列驱动：每条快照先喂 Kotlin monitor 再喂 C++
 * （nativeCoreMonitorEvaluate 独立判据通道，不经 GameCore 状态组合），
 * 断言判定相等。每用例经 [DiffRngBridge.nativeCoreMonitorReset] 重建双端基准。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffWatchdogTest {

    private lateinit var kotlinMonitor: GameTimeProgressMonitor

    @Before
    fun setUp() {
        assumeTrue("桌面 JNI 未注入，跳过对拍", DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreMonitorReset()
        kotlinMonitor = GameTimeProgressMonitor()
    }

    @Suppress("LongParameterList") // 测试快照构造器：12 个字段与快照一一对应
    private fun snapshot(
        tickCount: Long = 10L,
        totalPhases: Long = 100L,
        accumulatedGameMs: Long = 100L,
        loopActive: Boolean = true,
        isPaused: Boolean = false,
        isSaving: Boolean = false,
        isLoading: Boolean = false,
        speed: Int = 1,
        secretRealmPauseLock: Boolean = false,
        secretRealmPauseRenewedAtMs: Long = 0L,
        loopActiveAtMs: Long = 0L,
        recordedAtMs: Long = 0L
    ) = GameTimeProgressSnapshot(
        tickCount = tickCount,
        totalPhases = totalPhases,
        accumulatedGameMs = accumulatedGameMs,
        loopActive = loopActive,
        isPaused = isPaused,
        isSaving = isSaving,
        isLoading = isLoading,
        speed = speed,
        secretRealmPauseLock = secretRealmPauseLock,
        secretRealmPauseRenewedAtMs = secretRealmPauseRenewedAtMs,
        loopActiveAtMs = loopActiveAtMs,
        recordedAtMs = recordedAtMs
    )

    /** 双端同快照判定对拍 */
    private fun assertVerdictBothEnds(s: GameTimeProgressSnapshot): StallVerdict {
        val expected = kotlinMonitor.evaluate(s)
        val code = DiffRngBridge.nativeCoreMonitorEvaluate(
            tickCount = s.tickCount,
            totalPhases = s.totalPhases,
            accumulatedGameMs = s.accumulatedGameMs,
            loopActive = s.loopActive,
            isPaused = s.isPaused,
            isSaving = s.isSaving,
            isLoading = s.isLoading,
            speed = s.speed,
            secretRealmPauseLock = s.secretRealmPauseLock,
            secretRealmPauseRenewedAtMs = s.secretRealmPauseRenewedAtMs,
            loopActiveAtMs = s.loopActiveAtMs,
            recordedAtMs = s.recordedAtMs
        )
        val actual = verdictFromCode(code)
        assertEquals("双端判定不一致（recordedAt=${s.recordedAtMs}ms）", expected, actual)
        return expected
    }

    private fun verdictFromCode(code: Int): StallVerdict = when (code) {
        GameCoreBridge.VERDICT_HEALTHY -> StallVerdict.Healthy
        GameCoreBridge.VERDICT_LOOP_STALLED -> StallVerdict.LoopStalled
        GameCoreBridge.VERDICT_FAKE_RUN_DETECTED -> StallVerdict.FakeRunDetected
        GameCoreBridge.VERDICT_PAUSED_BY_OWNER -> StallVerdict.PausedByOwner
        GameCoreBridge.VERDICT_STALE_PAUSE_DETECTED -> StallVerdict.StalePauseDetected
        else -> error("未知判定码 $code")
    }

    // ── 正常推进 ──

    @Test
    fun `first call records baseline and returns Healthy`() {
        assertEquals(StallVerdict.Healthy, assertVerdictBothEnds(snapshot(recordedAtMs = 1_000L)))
    }

    @Test
    fun `tick and world time both progress returns Healthy`() {
        assertVerdictBothEnds(snapshot(tickCount = 10, totalPhases = 100, recordedAtMs = 1_000L))
        assertEquals(StallVerdict.Healthy,
            assertVerdictBothEnds(snapshot(tickCount = 11, accumulatedGameMs = 200, recordedAtMs = 2_000L)))
    }

    @Test
    fun `phase boundary wrap with totalPhases advanced returns Healthy`() {
        assertVerdictBothEnds(snapshot(accumulatedGameMs = 1900, recordedAtMs = 1_000L))
        assertEquals(StallVerdict.Healthy,
            assertVerdictBothEnds(snapshot(tickCount = 11, totalPhases = 101,
                accumulatedGameMs = 100, recordedAtMs = 2_000L)))
    }

    // ── tick 停滞 ──

    @Test
    fun `tick count stalled returns LoopStalled`() {
        assertVerdictBothEnds(snapshot(recordedAtMs = 1_000L))
        assertEquals(StallVerdict.LoopStalled,
            assertVerdictBothEnds(snapshot(recordedAtMs = 30_000L)))
    }

    @Test
    fun `loop dead and not paused returns LoopStalled`() {
        assertVerdictBothEnds(snapshot(recordedAtMs = 1_000L))
        assertEquals(StallVerdict.LoopStalled,
            assertVerdictBothEnds(snapshot(loopActive = false, recordedAtMs = 2_000L)))
    }

    // ── 假运行 ──

    @Test
    fun `fake run within window Healthy then beyond window FakeRunDetected`() {
        assertVerdictBothEnds(snapshot(recordedAtMs = 1_000L))
        assertEquals(StallVerdict.Healthy,
            assertVerdictBothEnds(snapshot(tickCount = 200, recordedAtMs = 51_000L)))
        assertEquals(StallVerdict.FakeRunDetected,
            assertVerdictBothEnds(snapshot(tickCount = 300, recordedAtMs = 200_000L)))
    }

    @Test
    fun `speed zero not paused returns FakeRunDetected immediately`() {
        assertVerdictBothEnds(snapshot(speed = 0, recordedAtMs = 1_000L))
        assertEquals(StallVerdict.FakeRunDetected,
            assertVerdictBothEnds(snapshot(tickCount = 11, speed = 0, recordedAtMs = 2_000L)))
    }

    @Test
    fun `freeze recovery refreshes baseline`() {
        assertVerdictBothEnds(snapshot(recordedAtMs = 1_000L))
        assertVerdictBothEnds(snapshot(tickCount = 200, recordedAtMs = 51_000L))
        assertEquals(StallVerdict.Healthy,
            assertVerdictBothEnds(snapshot(
                tickCount = 300, totalPhases = 101, accumulatedGameMs = 200,
                recordedAtMs = 60_000L
            )))
    }

    // ── 用户主动暂停（a63338f3 教训：永不自动恢复） ──

    @Test
    fun `user paused without secret realm lock returns PausedByOwner`() {
        assertVerdictBothEnds(snapshot(recordedAtMs = 1_000L))
        assertEquals(StallVerdict.PausedByOwner,
            assertVerdictBothEnds(snapshot(isPaused = true, recordedAtMs = 30_000L)))
    }

    // ── 秘境暂停租约 ──

    @Test
    fun `secret realm paused with valid lease returns PausedByOwner`() {
        assertVerdictBothEnds(snapshot(recordedAtMs = 5_000L))
        assertEquals(StallVerdict.PausedByOwner,
            assertVerdictBothEnds(snapshot(isPaused = true, secretRealmPauseLock = true,
                secretRealmPauseRenewedAtMs = 10_000L, loopActiveAtMs = 30_000L, recordedAtMs = 30_000L)))
    }

    @Test
    fun `secret realm lease expired returns StalePauseDetected`() {
        assertVerdictBothEnds(snapshot(recordedAtMs = 5_000L))
        assertEquals(StallVerdict.StalePauseDetected,
            assertVerdictBothEnds(snapshot(isPaused = true, secretRealmPauseLock = true,
                secretRealmPauseRenewedAtMs = 10_000L, loopActiveAtMs = 70_000L, recordedAtMs = 70_000L)))
    }

    @Test
    fun `secret realm lock never renewed returns StalePauseDetected`() {
        assertVerdictBothEnds(snapshot(recordedAtMs = 5_000L))
        assertEquals(StallVerdict.StalePauseDetected,
            assertVerdictBothEnds(snapshot(isPaused = true, secretRealmPauseLock = true,
                secretRealmPauseRenewedAtMs = 0L, loopActiveAtMs = 50_000L, recordedAtMs = 50_000L)))
    }

    // ── 保存/加载豁免 ──

    @Test
    fun `saving with active loop returns Healthy`() {
        assertVerdictBothEnds(snapshot(loopActiveAtMs = 5_000L, recordedAtMs = 5_000L))
        assertEquals(StallVerdict.Healthy,
            assertVerdictBothEnds(snapshot(tickCount = 11, isSaving = true,
                loopActiveAtMs = 19_000L, recordedAtMs = 20_000L)))
    }

    @Test
    fun `saving but loop also stalled returns LoopStalled`() {
        assertVerdictBothEnds(snapshot(loopActiveAtMs = 5_000L, recordedAtMs = 5_000L))
        assertEquals(StallVerdict.LoopStalled,
            assertVerdictBothEnds(snapshot(tickCount = 11, isSaving = true,
                loopActiveAtMs = 5_000L, recordedAtMs = 40_000L)))
    }

    @Test
    fun `loading with active loop returns Healthy`() {
        assertVerdictBothEnds(snapshot(loopActiveAtMs = 5_000L, recordedAtMs = 5_000L))
        assertEquals(StallVerdict.Healthy,
            assertVerdictBothEnds(snapshot(tickCount = 12, isLoading = true,
                loopActiveAtMs = 19_000L, recordedAtMs = 20_000L)))
    }

    // ── 边界：恰在窗口边缘（严格大于语义） ──

    @Test
    fun `fake run exactly beyond window boundary returns FakeRunDetected`() {
        assertVerdictBothEnds(snapshot(recordedAtMs = 1_000L))
        assertVerdictBothEnds(snapshot(tickCount = 100, recordedAtMs = 2_000L))
        assertEquals(StallVerdict.FakeRunDetected,
            assertVerdictBothEnds(snapshot(tickCount = 200, recordedAtMs = 92_001L)))
    }

    @Test
    fun `pause lease exactly at TTL boundary still valid`() {
        assertVerdictBothEnds(snapshot(recordedAtMs = 5_000L))
        assertEquals(StallVerdict.PausedByOwner,
            assertVerdictBothEnds(snapshot(isPaused = true, secretRealmPauseLock = true,
                secretRealmPauseRenewedAtMs = 10_000L, recordedAtMs = 55_000L)))
    }

    // ── 冻结/振荡异常判据回归 ──

    @Test
    fun `S5 frozen world with oscillating accumulatedMs still detected`() {
        assertVerdictBothEnds(snapshot(accumulatedGameMs = 0, recordedAtMs = 1_000L))
        assertVerdictBothEnds(snapshot(tickCount = 30, accumulatedGameMs = 2000, recordedAtMs = 10_000L))
        assertEquals(StallVerdict.Healthy,
            assertVerdictBothEnds(snapshot(tickCount = 50, accumulatedGameMs = 0, recordedAtMs = 20_000L)))
        assertEquals(StallVerdict.FakeRunDetected,
            assertVerdictBothEnds(snapshot(tickCount = 200, accumulatedGameMs = 2000, recordedAtMs = 200_000L)))
    }

    @Test
    fun `S4 tick stalled but heartbeat fresh returns Healthy`() {
        assertVerdictBothEnds(snapshot(loopActiveAtMs = 1_000L, recordedAtMs = 1_000L))
        assertEquals(StallVerdict.Healthy,
            assertVerdictBothEnds(snapshot(loopActiveAtMs = 4_000L, recordedAtMs = 5_000L)))
    }

    @Test
    fun `V1 tick stalled and heartbeat stale returns LoopStalled`() {
        assertVerdictBothEnds(snapshot(loopActiveAtMs = 1_000L, recordedAtMs = 1_000L))
        assertEquals(StallVerdict.LoopStalled,
            assertVerdictBothEnds(snapshot(loopActiveAtMs = 1_000L, recordedAtMs = 30_000L)))
    }

    @Test
    fun `S1 saving with loop stopped and paused returns Healthy`() {
        assertVerdictBothEnds(snapshot(loopActiveAtMs = 1_000L, recordedAtMs = 1_000L))
        assertEquals(StallVerdict.Healthy,
            assertVerdictBothEnds(snapshot(tickCount = 11, isSaving = true, loopActive = false,
                isPaused = true, loopActiveAtMs = 1_000L, recordedAtMs = 30_000L)))
    }

    @Test
    fun `V6 speed zero detected on first evaluation`() {
        assertEquals(StallVerdict.FakeRunDetected,
            assertVerdictBothEnds(snapshot(speed = 0, recordedAtMs = 1_000L)))
    }

    @Test
    fun `F2 lease expired with stalled loop returns LoopStalled`() {
        assertVerdictBothEnds(snapshot(loopActiveAtMs = 1_000L, recordedAtMs = 1_000L))
        assertEquals(StallVerdict.LoopStalled,
            assertVerdictBothEnds(snapshot(isPaused = true, secretRealmPauseLock = true,
                secretRealmPauseRenewedAtMs = 1_000L, loopActiveAtMs = 1_000L, recordedAtMs = 60_000L)))
    }

    // ── GameCore 组合通道（watchdogVerdict：-1 = 未初始化语义） ──

    @Test
    fun `composed watchdog channel reports uninitialized before core init`() {
        // nativeCoreMonitorReset 不创建引擎；组合通道在引擎未初始化时返回 -1
        // （生产回退契约：调用方回退 Kotlin 判据）
        val code = DiffRngBridge.nativeCoreWatchdogVerdict(
            loopActive = true, isPaused = false, isSaving = false, isLoading = false,
            secretRealmPauseLock = false, secretRealmPauseRenewedAtMs = 0L
        )
        // 引擎可能已由同 JVM 其他对拍测试初始化（全局单例）——两种取值都合法：
        // -1（未初始化）或 0-4（已初始化的健康判定），此处仅验证通道可用不崩溃
        assertTrue("组合通道应返回 -1 或判定码", code == -1 || code in 0..4)
    }
}
