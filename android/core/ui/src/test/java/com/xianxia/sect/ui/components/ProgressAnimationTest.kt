package com.xianxia.sect.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressAnimationTest {

    // ========== nextChasingProgressTick ==========

    @Test
    fun `nextChasingProgressTick - target unchanged returns same`() {
        assertEquals(0.5f, nextChasingProgressTick(0.5f, 0.5f, 0.3f))
        assertEquals(1f, nextChasingProgressTick(1f, 1f, 0.3f))
    }

    @Test
    fun `nextChasingProgressTick - target zero snaps to zero`() {
        assertEquals(0f, nextChasingProgressTick(0.5f, 0f, 0.3f))
        assertEquals(0f, nextChasingProgressTick(1f, 0f, 0.3f))
    }

    @Test
    fun `nextChasingProgressTick - target below current snaps to target`() {
        assertEquals(0.3f, nextChasingProgressTick(0.8f, 0.3f, 0.3f))
        assertEquals(0.1f, nextChasingProgressTick(0.9f, 0.1f, 0.3f))
    }

    @Test
    fun `nextChasingProgressTick - lerps toward target`() {
        // 0.3 + (1.0 - 0.3) * 0.3 = 0.3 + 0.21 = 0.51
        assertEquals(0.51f, nextChasingProgressTick(0.3f, 1f, 0.3f), 0.0001f)
        // 0.5 + (1.0 - 0.5) * 0.5 = 0.5 + 0.25 = 0.75
        assertEquals(0.75f, nextChasingProgressTick(0.5f, 1f, 0.5f), 0.0001f)
    }

    @Test
    fun `nextChasingProgressTick - lerpFactor 1 snaps immediately`() {
        assertEquals(1f, nextChasingProgressTick(0.3f, 1f, 1f), 0f)
        assertEquals(0.5f, nextChasingProgressTick(0.1f, 0.5f, 1f), 0f)
    }

    @Test
    fun `nextChasingProgressTick - lerpFactor 0 stays put`() {
        assertEquals(0.3f, nextChasingProgressTick(0.3f, 1f, 0f), 0f)
        assertEquals(0.5f, nextChasingProgressTick(0.5f, 0.5f, 0f), 0f)
    }

    @Test
    fun `nextChasingProgressTick - snap threshold prevents float never-converge`() {
        // 差距 < 0.001 时精确 snap
        assertEquals(1f, nextChasingProgressTick(0.9995f, 1f, 0.3f), 0f)
        assertEquals(0.5f, nextChasingProgressTick(0.4999f, 0.5f, 0.3f), 0f)
    }

    @Test
    fun `nextChasingProgressTick - multiple ticks converge to target`() {
        var c = 0f
        val ticks = mutableListOf<Float>()
        repeat(30) {
            c = nextChasingProgressTick(c, 1f, 0.3f)
            ticks.add(c)
        }
        // 10 ticks (~1s): >= 97%
        assertTrue("tick 10 should converge >= 97%, but was ${ticks[9]}",
            ticks[9] >= 0.97f)
        // 30 ticks (~3s): 必然已 snap 到 1.0
        assertEquals(1f, ticks[29], 0f)
    }

    @Test
    fun `nextChasingProgressTick - clamps target and lerpFactor to 0-1`() {
        // target > 1 clamped to 1
        assertEquals(0.65f, nextChasingProgressTick(0.5f, 1.5f, 0.3f), 0.0001f)
        // negative lerpFactor treated as 0
        assertEquals(0.5f, nextChasingProgressTick(0.5f, 1f, -0.1f), 0f)
    }

    // ========== Constants ==========

    @Test
    fun `PROGRESS_TICK_MS equals 100`() = assertEquals(100L, PROGRESS_TICK_MS)

    @Test
    fun `CHASE_LERP_FACTOR_DEFAULT equals 0_3`() =
        assertEquals(0.3f, CHASE_LERP_FACTOR_DEFAULT)
}
