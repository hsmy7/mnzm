package com.xianxia.sect.core.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CustomVelocityTracker 最小二乘速度拟合测试。
 *
 * 覆盖：
 * - 空/单样本 → 零速度
 * - 双样本（线性降级）→ 常速度
 * - 三样本+（二次拟合）→ 一阶导数速度
 * - 边缘情况：同时戳、clear、historySize 裁剪
 */
class CustomVelocityTrackerTest {

    // ======================== 基础边界 ========================

    @Test
    fun `empty tracker returns zero velocity`() {
        val tracker = CustomVelocityTracker()
        val v = tracker.computeVelocity()
        assertEquals(0f, v.x, 1e-6f)
        assertEquals(0f, v.y, 1e-6f)
    }

    @Test
    fun `single sample returns zero velocity`() {
        val tracker = CustomVelocityTracker()
        tracker.addPosition(100f, 200f, 0L)
        val v = tracker.computeVelocity()
        assertEquals(0f, v.x, 1e-6f)
        assertEquals(0f, v.y, 1e-6f)
    }

    // ======================== 线性降级（n=2） ========================

    @Test
    fun `two samples constant speed produces matching horizontal velocity`() {
        val tracker = CustomVelocityTracker()
        // 100px/s 匀速水平移动，持续 1 秒
        tracker.addPosition(0f, 0f, 0L)
        tracker.addPosition(100f, 0f, 1_000_000_000L)
        val v = tracker.computeVelocity()
        assertEquals("Linear fit should match 100px/s", 100f, v.x, 1f)
        assertEquals(0f, v.y, 1f)
    }

    @Test
    fun `two samples constant speed produces matching vertical velocity`() {
        val tracker = CustomVelocityTracker()
        tracker.addPosition(0f, 0f, 0L)
        tracker.addPosition(0f, 200f, 1_000_000_000L)
        val v = tracker.computeVelocity()
        assertEquals(0f, v.x, 1f)
        assertEquals("Linear fit should match 200px/s", 200f, v.y, 1f)
    }

    @Test
    fun `two samples fast diagonal velocity`() {
        val tracker = CustomVelocityTracker()
        // 500px/s 对角，持续 0.5 秒
        tracker.addPosition(0f, 0f, 0L)
        tracker.addPosition(250f, 250f, 500_000_000L)
        val v = tracker.computeVelocity()
        assertEquals(500f, v.x, 5f)
        assertEquals(500f, v.y, 5f)
    }

    @Test
    fun `two samples negative velocity`() {
        val tracker = CustomVelocityTracker()
        tracker.addPosition(200f, 300f, 0L)
        tracker.addPosition(100f, 200f, 1_000_000_000L)
        val v = tracker.computeVelocity()
        assertEquals("Negative horizontal velocity", -100f, v.x, 1f)
        assertEquals("Negative vertical velocity", -100f, v.y, 1f)
    }

    @Test
    fun `two samples very close timestamps produces high velocity`() {
        val tracker = CustomVelocityTracker()
        // 2px 在 10ms 内 → 约 200px/s
        tracker.addPosition(0f, 0f, 0L)
        tracker.addPosition(2f, 0f, 10_000_000L)
        val v = tracker.computeVelocity()
        assertTrue("Velocity should be high with small dt", v.x > 150f)
        assertEquals(0f, v.y, 1f)
    }

    // ======================== 二次拟合（n≥3） ========================

    @Test
    fun `three samples constant speed matches expected velocity`() {
        val tracker = CustomVelocityTracker()
        // 每秒移动 100px，3 个采样点: (0,0) at 0s, (100,0) at 1s, (200,0) at 2s
        tracker.addPosition(0f, 0f, 0L)
        tracker.addPosition(100f, 0f, 1_000_000_000L)
        tracker.addPosition(200f, 0f, 2_000_000_000L)
        val v = tracker.computeVelocity()
        assertEquals("Constant velocity should be 100px/s", 100f, v.x, 5f)
        assertEquals(0f, v.y, 1f)
    }

    @Test
    fun `simple two sample debug`() {
        val tracker = CustomVelocityTracker()
        tracker.addPosition(0f, 0f, 0L)
        tracker.addPosition(10f, 0f, 100_000_000L) // 100px/s
        val v = tracker.computeVelocity()
        org.junit.Assert.assertTrue("two sample linear x=$v.x", kotlin.math.abs(v.x - 100f) < 5f)
    }

    @Test
    fun `five samples quadratic acceleration produces correct derivative`() {
        val tracker = CustomVelocityTracker()
        // 匀加速运动: x(t) = 10 + 50*t + 5*t²
        // 速度 v(t) = 50 + 10*t
        // 在 t=2 时: v_x = 70 px/s
        var t = 0L
        for (i in 0 until 5) {
            val tx = 10.0 + 50.0 * (t / 1e9) + 5.0 * (t / 1e9) * (t / 1e9)
            tracker.addPosition(tx.toFloat(), 0f, t)
            t += 500_000_000L  // 每 0.5 秒
        }
        val v = tracker.computeVelocity()
        assertEquals("Quadratic fit should approximate derivative at newest point",
            70f, v.x, 10f)
    }

    @Test
    fun `five samples with deceleration`() {
        val tracker = CustomVelocityTracker()
        // 匀减速: x(t) = 200 - 10*t², v(t) = -20*t
        // 在 t=2 时: v_x = -40 px/s
        var t = 0L
        for (i in 0 until 5) {
            val tx = 200.0 - 10.0 * (t / 1e9) * (t / 1e9)
            tracker.addPosition(tx.toFloat(), 0f, t)
            t += 500_000_000L
        }
        val v = tracker.computeVelocity()
        assertTrue("Deceleration should give negative velocity", v.x < 0f)
    }

    @Test
    fun `three samples all stationary returns zero velocity`() {
        val tracker = CustomVelocityTracker()
        tracker.addPosition(100f, 200f, 0L)
        tracker.addPosition(100f, 200f, 1_000_000_000L)
        tracker.addPosition(100f, 200f, 2_000_000_000L)
        val v = tracker.computeVelocity()
        assertEquals("No movement should return zero velocity", 0f, v.x, 0.1f)
        assertEquals("No movement should return zero velocity", 0f, v.y, 0.1f)
    }

    // ======================== computeInstantVelocity ========================

    @Test
    fun `instant velocity with 5 samples uses last 3`() {
        val tracker = CustomVelocityTracker()
        // 前 2 个点慢速，后 3 个点快速
        tracker.addPosition(0f, 0f, 0L)
        tracker.addPosition(10f, 0f, 1_000_000_000L)  // 10px/s
        tracker.addPosition(110f, 0f, 2_000_000_000L)  // 100px/s
        tracker.addPosition(210f, 0f, 3_000_000_000L)  // 100px/s
        tracker.addPosition(310f, 0f, 4_000_000_000L)  // 100px/s
        val instant = tracker.computeInstantVelocity()
        // 后 3 个点: 110→210→310, 每步 100px/s
        assertEquals("Instant velocity should reflect recent speed",
            100f, instant.x, 10f)
    }

    @Test
    fun `instant velocity with 2 samples uses linear fit`() {
        val tracker = CustomVelocityTracker()
        // 2 个样本，linear fit 给出正确速度 100px/s
        tracker.addPosition(0f, 0f, 0L)
        tracker.addPosition(100f, 0f, 1_000_000_000L)
        val instant = tracker.computeInstantVelocity()
        assertEquals("takeLast(3) with 2 samples uses linear fit",
            100f, instant.x, 5f)
    }

    // ======================== 同时戳 ========================

    @Test
    fun `all samples at same time returns zero velocity`() {
        val tracker = CustomVelocityTracker()
        tracker.addPosition(0f, 0f, 1_000_000_000L)
        tracker.addPosition(100f, 0f, 1_000_000_000L)
        tracker.addPosition(200f, 0f, 1_000_000_000L)
        val v = tracker.computeVelocity()
        assertEquals("Zero time delta should give zero velocity",
            0f, v.x, 1e-6f)
    }

    // ======================== clear & speed ========================

    @Test
    fun `clear resets tracker to zero velocity`() {
        val tracker = CustomVelocityTracker()
        tracker.addPosition(0f, 0f, 0L)
        tracker.addPosition(100f, 0f, 1_000_000_000L)
        var v = tracker.computeVelocity()
        assertTrue("Before clear, velocity should be non-zero", v.x > 50f)
        tracker.clear()
        v = tracker.computeVelocity()
        assertEquals("After clear, velocity should be zero", 0f, v.x, 1e-6f)
        assertEquals(0f, v.y, 1e-6f)
    }

    @Test
    fun `speed returns magnitude of velocity vector`() {
        val tracker = CustomVelocityTracker()
        tracker.addPosition(0f, 0f, 0L)
        tracker.addPosition(30f, 40f, 1_000_000_000L)  // 30px/s, 40px/s → speed=50
        val s = tracker.speed()
        assertEquals("Speed should be sqrt(30²+40²)=50", 50f, s, 2f)
    }

    // ======================== historySize ========================

    @Test
    fun `historySize limits stored samples`() {
        val tracker = CustomVelocityTracker(historySize = 3)
        tracker.addPosition(0f, 0f, 0L)
        tracker.addPosition(100f, 0f, 1_000_000_000L)
        tracker.addPosition(200f, 0f, 2_000_000_000L)
        tracker.addPosition(300f, 0f, 3_000_000_000L)  // 应淘汰最早的
        val v = tracker.computeVelocity()
        // 只用后 3 个点计算
        assertTrue("Velocity should reflect recent movement", v.x > 50f)
    }
}
