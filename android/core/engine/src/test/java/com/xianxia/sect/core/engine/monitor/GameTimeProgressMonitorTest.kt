package com.xianxia.sect.core.engine.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 游戏时间推进监控 — 全分支判定矩阵测试。
 *
 * 历史教训（git log 27 次"游戏时间停止"修复）：防御机制自身失效 3 次
 * （else-if 互斥/参数错误/守卫自我禁用）——判据组件必须是纯函数、
 * 全分支单测覆盖，禁止 else-if 分叉。
 */
class GameTimeProgressMonitorTest {

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

    // ── 正常推进 ──

    @Test
    fun `evaluate - first call records baseline and returns Healthy`() {
        val monitor = GameTimeProgressMonitor()
        val verdict = monitor.evaluate(snapshot(recordedAtMs = 1_000L))
        assertEquals(StallVerdict.Healthy, verdict)
    }

    @Test
    fun `evaluate - tick and world time both progress returns Healthy`() {
        val monitor = GameTimeProgressMonitor()
        monitor.evaluate(
            snapshot(tickCount = 10, totalPhases = 100, accumulatedGameMs = 100, recordedAtMs = 1_000L)
        )
        val verdict = monitor.evaluate(
            snapshot(tickCount = 11, totalPhases = 100, accumulatedGameMs = 200, recordedAtMs = 2_000L)
        )
        assertEquals(StallVerdict.Healthy, verdict)
    }

    @Test
    fun `evaluate - phase boundary where accumulatedMs wraps but totalPhases advanced returns Healthy`() {
        // 旬界回绕：accumulatedGameMs 从 1900 回到 100，但 totalPhases 从 100 → 101
        val monitor = GameTimeProgressMonitor()
        monitor.evaluate(
            snapshot(tickCount = 10, totalPhases = 100, accumulatedGameMs = 1900, recordedAtMs = 1_000L)
        )
        val verdict = monitor.evaluate(
            snapshot(tickCount = 11, totalPhases = 101, accumulatedGameMs = 100, recordedAtMs = 2_000L)
        )
        assertEquals(StallVerdict.Healthy, verdict)
    }

    // ── tick 停滞 ──

    @Test
    fun `evaluate - tick count stalled returns LoopStalled`() {
        val monitor = GameTimeProgressMonitor()
        monitor.evaluate(snapshot(tickCount = 10, recordedAtMs = 1_000L))
        val verdict = monitor.evaluate(snapshot(tickCount = 10, recordedAtMs = 30_000L))
        assertEquals(StallVerdict.LoopStalled, verdict)
    }

    @Test
    fun `evaluate - loop dead and not paused returns LoopStalled`() {
        val monitor = GameTimeProgressMonitor()
        monitor.evaluate(snapshot(loopActive = true, recordedAtMs = 1_000L))
        val verdict = monitor.evaluate(snapshot(loopActive = false, recordedAtMs = 2_000L))
        assertEquals(StallVerdict.LoopStalled, verdict)
    }

    // ── 假运行（tick 在跑但世界时间不动）──

    @Test
    fun `evaluate - fake run frozen within window returns Healthy then FakeRunDetected after window`() {
        val monitor = GameTimeProgressMonitor()
        // 基准
        monitor.evaluate(snapshot(tickCount = 10, totalPhases = 100, accumulatedGameMs = 100, recordedAtMs = 1_000L))
        // 冻结 50s（<90s 窗口）：容忍
        val withinWindow = monitor.evaluate(
            snapshot(tickCount = 200, totalPhases = 100, accumulatedGameMs = 100, recordedAtMs = 51_000L)
        )
        assertEquals(StallVerdict.Healthy, withinWindow)
        // 冻结持续超过 90s：判假运行
        val beyondWindow = monitor.evaluate(
            snapshot(tickCount = 300, totalPhases = 100, accumulatedGameMs = 100, recordedAtMs = 200_000L)
        )
        assertEquals(StallVerdict.FakeRunDetected, beyondWindow)
    }

    @Test
    fun `evaluate - speed zero and not paused returns FakeRunDetected immediately`() {
        val monitor = GameTimeProgressMonitor()
        monitor.evaluate(snapshot(speed = 0, recordedAtMs = 1_000L))
        val verdict = monitor.evaluate(snapshot(tickCount = 11, speed = 0, recordedAtMs = 2_000L))
        assertEquals(StallVerdict.FakeRunDetected, verdict)
    }

    @Test
    fun `evaluate - freeze recovery refreshes baseline`() {
        val monitor = GameTimeProgressMonitor()
        monitor.evaluate(snapshot(tickCount = 10, totalPhases = 100, accumulatedGameMs = 100, recordedAtMs = 1_000L))
        monitor.evaluate(snapshot(tickCount = 200, totalPhases = 100, accumulatedGameMs = 100, recordedAtMs = 51_000L))
        // 时间恢复推进：判 Healthy，且新基准生效（后续不再冻结误判）
        val recovered = monitor.evaluate(
            snapshot(tickCount = 300, totalPhases = 101, accumulatedGameMs = 200, recordedAtMs = 60_000L)
        )
        assertEquals(StallVerdict.Healthy, recovered)
    }

    // ── 用户主动暂停（a63338f3 教训：永不自动恢复）──

    @Test
    fun `evaluate - user paused without secret realm lock returns PausedByOwner`() {
        val monitor = GameTimeProgressMonitor()
        monitor.evaluate(snapshot(recordedAtMs = 1_000L))
        val verdict = monitor.evaluate(snapshot(isPaused = true, recordedAtMs = 30_000L))
        assertEquals(StallVerdict.PausedByOwner, verdict)
    }

    // ── 秘境暂停租约 ──

    @Test
    fun `evaluate - secret realm paused with valid lease returns PausedByOwner`() {
        val monitor = GameTimeProgressMonitor()
        val renewedAtMs = 10_000L
        monitor.evaluate(snapshot(recordedAtMs = 5_000L))
        // 租约 20s 前续约，仍有效（<45s TTL）
        val verdict = monitor.evaluate(
            snapshot(isPaused = true, secretRealmPauseLock = true,
                secretRealmPauseRenewedAtMs = renewedAtMs, recordedAtMs = renewedAtMs + 20_000L)
        )
        assertEquals(StallVerdict.PausedByOwner, verdict)
    }

    @Test
    fun `evaluate - secret realm pause lease expired returns StalePauseDetected`() {
        val monitor = GameTimeProgressMonitor()
        val renewedAtMs = 10_000L
        monitor.evaluate(snapshot(recordedAtMs = 5_000L))
        // 租约 60s 前续约，已过期（>45s TTL）
        val verdict = monitor.evaluate(
            snapshot(isPaused = true, secretRealmPauseLock = true,
                secretRealmPauseRenewedAtMs = renewedAtMs, recordedAtMs = renewedAtMs + 60_000L)
        )
        assertEquals(StallVerdict.StalePauseDetected, verdict)
    }

    @Test
    fun `evaluate - secret realm lock held but lease never renewed returns StalePauseDetected`() {
        // 老代码路径/未置位：renewedAtMs=0 → 立即判残留，不等 TTL
        val monitor = GameTimeProgressMonitor()
        monitor.evaluate(snapshot(recordedAtMs = 5_000L))
        val verdict = monitor.evaluate(
            snapshot(isPaused = true, secretRealmPauseLock = true,
                secretRealmPauseRenewedAtMs = 0L, recordedAtMs = 50_000L)
        )
        assertEquals(StallVerdict.StalePauseDetected, verdict)
    }

    // ── 保存/加载豁免（慢保存不误伤）──

    @Test
    fun `evaluate - saving with active loop returns Healthy regardless of duration`() {
        val monitor = GameTimeProgressMonitor()
        monitor.evaluate(snapshot(loopActiveAtMs = 5_000L, recordedAtMs = 5_000L))
        // 保存 15s，循环活动心跳仍在推进（loopActiveAtMs 接近 recordedAtMs）
        val verdict = monitor.evaluate(
            snapshot(tickCount = 11, isSaving = true, loopActiveAtMs = 19_000L, recordedAtMs = 20_000L)
        )
        assertEquals(StallVerdict.Healthy, verdict)
    }

    @Test
    fun `evaluate - saving but loop also stalled returns LoopStalled`() {
        // 保存死锁 + 引擎死亡组合：isSaving 永久 true 且循环停滞 → 必须判死
        val monitor = GameTimeProgressMonitor()
        monitor.evaluate(snapshot(loopActiveAtMs = 5_000L, recordedAtMs = 5_000L))
        val verdict = monitor.evaluate(
            snapshot(tickCount = 11, isSaving = true, loopActiveAtMs = 5_000L, recordedAtMs = 40_000L)
        )
        assertEquals(StallVerdict.LoopStalled, verdict)
    }

    @Test
    fun `evaluate - loading with active loop returns Healthy`() {
        val monitor = GameTimeProgressMonitor()
        monitor.evaluate(snapshot(loopActiveAtMs = 5_000L, recordedAtMs = 5_000L))
        val verdict = monitor.evaluate(
            snapshot(tickCount = 12, isLoading = true, loopActiveAtMs = 19_000L, recordedAtMs = 20_000L)
        )
        assertEquals(StallVerdict.Healthy, verdict)
    }

    // ── 边界：恰在窗口边缘 ──

    @Test
    fun `evaluate - fake run exactly at window boundary returns FakeRunDetected`() {
        val monitor = GameTimeProgressMonitor()
        monitor.evaluate(snapshot(tickCount = 10, totalPhases = 100, accumulatedGameMs = 100, recordedAtMs = 1_000L))
        // 冻结开始
        monitor.evaluate(snapshot(tickCount = 100, totalPhases = 100, accumulatedGameMs = 100, recordedAtMs = 2_000L))
        // 恰 90s 后：recordedAtMs - freezeStart > 90_000 → 91s 处（窗口为严格大于）
        val verdict = monitor.evaluate(
            snapshot(tickCount = 200, totalPhases = 100, accumulatedGameMs = 100, recordedAtMs = 92_001L)
        )
        assertEquals(StallVerdict.FakeRunDetected, verdict)
    }

    @Test
    fun `evaluate - pause lease exactly at TTL boundary`() {
        val monitor = GameTimeProgressMonitor()
        monitor.evaluate(snapshot(recordedAtMs = 5_000L))
        val renewedAtMs = 10_000L
        // 恰 45s：过期判定为 严格大于 45s → 45s 整仍有效
        val verdict = monitor.evaluate(
            snapshot(isPaused = true, secretRealmPauseLock = true,
                secretRealmPauseRenewedAtMs = renewedAtMs, recordedAtMs = renewedAtMs + 45_000L)
        )
        assertEquals(StallVerdict.PausedByOwner, verdict)
    }

    @Test
    fun `evaluate - custom monitor parameters respected`() {
        // 自定义窗口参数（测试可注入性）
        val monitor = GameTimeProgressMonitor(stalePauseTtlMs = 10_000L, fakeRunWindowMs = 20_000L)
        monitor.evaluate(snapshot(tickCount = 10, totalPhases = 100, accumulatedGameMs = 100, recordedAtMs = 1_000L))
        monitor.evaluate(snapshot(tickCount = 11, totalPhases = 100, accumulatedGameMs = 100, recordedAtMs = 10_000L))
        val verdict = monitor.evaluate(
            snapshot(tickCount = 12, totalPhases = 100, accumulatedGameMs = 100, recordedAtMs = 35_000L)
        )
        assertEquals(StallVerdict.FakeRunDetected, verdict)
        assertTrue("window parameter applied", verdict is StallVerdict.FakeRunDetected)
    }
}
