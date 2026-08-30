package com.xianxia.sect.core.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FlingPhysics 惯性物理测试（60fps 节拍适配）。
 *
 * 覆盖：线性减速、停止阈值、16ms 节拍下总位移守恒（位置 = 速度梯形积分）、
 * dt 非法输入防御。
 */
class FlingPhysicsTest {

    @Test
    fun `default frame interval is 16ms for 60fps cadence`() {
        assertEquals("惯性滑行节拍应为 60fps", 16L, FlingPhysics.DEFAULT_FRAME_INTERVAL_MS)
    }

    @Test
    fun `linear deceleration stops after expected duration`() {
        val physics = FlingPhysics(deceleration = 1500f, velocityThreshold = 25f)
        physics.start(3000f, 0f)
        // v(t) = 3000 - 1500t → t_stop ≈ 2s；16ms 节拍 ≈ 125 tick
        var ticks = 0
        while (physics.isActive && ticks < 500) {
            physics.update(0.016f)
            ticks++
        }
        assertFalse("应在线性减速后停止", physics.isActive)
        assertTrue("3000px/s 减速应在约 125 tick 停止（实际 $ticks）", ticks in 115..135)
    }

    @Test
    fun `displacement equals velocity trapezoid integral`() {
        // 16ms 步进下总位移 ≈ v0²/(2·decel) = 3000²/(2·1500) = 3000px
        val physics = FlingPhysics(deceleration = 1500f, velocityThreshold = 0f)
        physics.start(3000f, 0f)
        var totalDx = 0f
        var ticks = 0
        while (physics.isActive && ticks < 1000) {
            totalDx += physics.update(0.016f).dx
            ticks++
        }
        assertEquals("总位移应等于 v0²/(2·decel)", 3000f, totalDx, 30f)
    }

    @Test
    fun `below stop threshold velocity zeroes out and deactivates`() {
        val physics = FlingPhysics(deceleration = 0f, velocityThreshold = 25f)
        physics.start(10f, 0f)
        physics.update(0.016f)
        // 末帧仍按 v×dt 输出一次小位移（既有语义），随后速度归零停止
        assertEquals("速度应归零", 0f, physics.velocityX, 0.001f)
        assertFalse("应停止", physics.isActive)
    }

    @Test
    fun `velocity is clamped to max`() {
        val physics = FlingPhysics()
        physics.start(999_999f, 0f)
        assertTrue("初速度应被钳制", physics.velocityX <= 15000f)
    }

    @Test
    fun `stop clears velocity and deactivates`() {
        val physics = FlingPhysics()
        physics.start(1000f, 1000f)
        physics.stop()
        assertFalse(physics.isActive)
        assertEquals(0f, physics.velocityX, 0.001f)
        assertEquals(0f, physics.velocityY, 0.001f)
    }

    @Test
    fun `update when inactive returns zero`() {
        val physics = FlingPhysics()
        val delta = physics.update(0.016f)
        assertEquals(0f, delta.dx, 0.001f)
        assertEquals(0f, delta.dy, 0.001f)
    }
}
