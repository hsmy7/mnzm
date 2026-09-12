package com.xianxia.sect.ui.game.sect

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自适应帧率追踪器测试（EWMA 平滑 + 1s 防抖 + 档位映射）。
 *
 * 覆盖维度：
 * - 首帧使用实际耗时但上限 22ms（防 JIT 预热误降）
 * - 稳定 20ms 帧时间 → 60fps
 * - 稳定 40ms 帧时间 → 30fps
 * - 帧耗时 >50ms → 下限 20fps
 * - 1 秒防抖窗口内不切档
 */
class AdaptiveFpsTrackerTest {

    @Test
    fun `first frame caps at 22ms and returns 60`() {
        val tracker = AdaptiveFpsTracker()
        // 首帧 500ms 异常耗时（JIT 预热）→ 上限 22ms，返回 60
        assertEquals(60, tracker.recordFrameTime(500_000_000L, 1_000L))
    }

    @Test
    fun `stable 20ms frame time stays 60fps`() {
        val tracker = AdaptiveFpsTracker()
        tracker.recordFrameTime(10_000_000L, 1_000L)  // 首帧
        // 持续 20ms 帧时间（50fps 达不到 60 但阈值 22ms 内）
        var now = 10_000L
        repeat(30) {
            tracker.recordFrameTime(20_000_000L, now)
            now += 1_000L
        }
        // 20ms 帧时间 → EWMA 收敛到 60fps 档
        assertEquals(60, tracker.recordFrameTime(20_000_000L, now))
    }

    @Test
    fun `stable 40ms frame time downgrades to 30fps`() {
        val tracker = AdaptiveFpsTracker()
        tracker.recordFrameTime(10_000_000L, 1_000L)  // 首帧
        var now = 10_000L
        repeat(20) {
            tracker.recordFrameTime(40_000_000L, now)
            now += 1_000L
        }
        assertEquals(30, tracker.recordFrameTime(40_000_000L, now))
    }

    @Test
    fun `frame time above 50ms floors at 20fps`() {
        val tracker = AdaptiveFpsTracker()
        tracker.recordFrameTime(10_000_000L, 1_000L)  // 首帧
        var now = 10_000L
        repeat(20) {
            tracker.recordFrameTime(80_000_000L, now)
            now += 1_000L
        }
        assertEquals(AdaptiveFpsTracker.MIN_FPS, tracker.recordFrameTime(80_000_000L, now))
    }

    @Test
    fun `hysteresis window prevents rapid switching within 1s`() {
        val tracker = AdaptiveFpsTracker()
        tracker.recordFrameTime(10_000_000L, 1_000L)  // 首帧
        // 第一次跨档：80ms 帧时间 → EWMA 31ms → 45fps 档，lastFpsSwitchMs=1100
        assertEquals(45, tracker.recordFrameTime(80_000_000L, 1_100L))
        // 防抖窗口内（2000-1100=900ms < 1000ms）：帧时间持续恶化仍保持 45 档
        assertEquals(45, tracker.recordFrameTime(80_000_000L, 2_000L))
        // 跨过防抖窗口后允许继续降档至下限
        tracker.recordFrameTime(80_000_000L, 2_100L)
        tracker.recordFrameTime(80_000_000L, 3_100L)
        tracker.recordFrameTime(80_000_000L, 4_100L)
        assertEquals(AdaptiveFpsTracker.MIN_FPS, tracker.recordFrameTime(80_000_000L, 5_100L))
    }

    @Test
    fun `ewma smooths single spike without permanent downgrade`() {
        val tracker = AdaptiveFpsTracker()
        tracker.recordFrameTime(10_000_000L, 1_000L)  // 首帧
        // 单次 200ms 尖峰被 EWMA(alpha=0.3) 平滑，不触发降档
        var now = 10_000L
        repeat(5) {
            tracker.recordFrameTime(200_000_000L, now)
            now += 1_000L
        }
        repeat(10) {
            tracker.recordFrameTime(16_000_000L, now)
            now += 1_000L
        }
        // 尖峰被平滑 + 恢复后回 60fps
        assertEquals(60, tracker.recordFrameTime(16_000_000L, now))
    }

    @Test
    fun `negative frame time is sanitized and does not poison ewma`() {
        val tracker = AdaptiveFpsTracker()
        tracker.recordFrameTime(10_000_000L, 1_000L)  // 首帧
        // 负数帧耗时（时钟异常）按 0 处理，不污染 EWMA 为负
        var now = 10_000L
        repeat(5) {
            tracker.recordFrameTime(-100_000_000L, now)
            now += 1_000L
        }
        // 恢复正常帧时间后应回到 60fps（未被负数污染锁死 MIN_FPS）
        repeat(5) {
            tracker.recordFrameTime(16_000_000L, now)
            now += 1_000L
        }
        assertEquals(60, tracker.recordFrameTime(16_000_000L, now))
    }

    @Test
    fun `reset clears ewma state and restores first-frame protection`() {
        val tracker = AdaptiveFpsTracker()
        // 进入低帧率状态（80ms 帧时间 → MIN_FPS）
        tracker.recordFrameTime(10_000_000L, 1_000L)
        var now = 10_000L
        repeat(10) {
            tracker.recordFrameTime(80_000_000L, now)
            now += 1_000L
        }
        assertEquals(AdaptiveFpsTracker.MIN_FPS, tracker.recordFrameTime(80_000_000L, now))

        // reset 后：首帧重新走 22ms 上限保护，返回 60 而非沿用旧 EWMA
        tracker.reset()
        assertEquals(60, tracker.recordFrameTime(500_000_000L, now))
    }
}
