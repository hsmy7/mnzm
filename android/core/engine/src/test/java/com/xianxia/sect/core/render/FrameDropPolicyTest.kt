package com.xianxia.sect.core.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FrameDropPolicy] 纯函数测试——节拍换算数学与除零防御。
 */
class FrameDropPolicyTest {

    @Test
    fun `tickStep - equal fps renders every tick`() {
        assertEquals(1, FrameDropPolicy.tickStep(60, 60))
        assertEquals(1, FrameDropPolicy.tickStep(120, 120))
    }

    @Test
    fun `tickStep - half fps skips every other tick`() {
        assertEquals(2, FrameDropPolicy.tickStep(60, 30))
        assertEquals(2, FrameDropPolicy.tickStep(120, 60))
    }

    @Test
    fun `tickStep - idles fps rounds up to next tick`() {
        // 10fps @ 60Hz → 6 节拍 1 帧
        assertEquals(6, FrameDropPolicy.tickStep(60, 10))
        // 20fps @ 60Hz → 3
        assertEquals(3, FrameDropPolicy.tickStep(60, 20))
    }

    @Test
    fun `tickStep - non-divisible rates ceil (never over-render)`() {
        // 45fps @ 60Hz → ceil(60/45)=2 → 实际 30fps（不超速）
        assertEquals(2, FrameDropPolicy.tickStep(60, 45))
        // 25fps @ 60Hz → ceil(2.4)=3 → 实际 20fps
        assertEquals(3, FrameDropPolicy.tickStep(60, 25))
    }

    @Test
    fun `tickStep - high refresh display scales step`() {
        // 120Hz 屏目标 30fps → 4 节拍 1 帧
        assertEquals(4, FrameDropPolicy.tickStep(120, 30))
    }

    @Test
    fun `tickStep - effective fps above display still renders every tick`() {
        assertEquals(1, FrameDropPolicy.tickStep(60, 120))
    }

    @Test
    fun `tickStep - zero or negative fps defensive returns 1`() {
        assertEquals(1, FrameDropPolicy.tickStep(60, 0))
        assertEquals(1, FrameDropPolicy.tickStep(60, -5))
    }

    @Test
    fun `tickStep - zero display fps defensive treats as 1`() {
        assertEquals(1, FrameDropPolicy.tickStep(0, 30))
    }

    @Test
    fun `tickStep - monotonic non-decreasing as effective fps decreases`() {
        var prev = 0
        for (fps in 60 downTo 5) {
            val step = FrameDropPolicy.tickStep(60, fps)
            assert(step >= prev) { "step must not decrease: fps=$fps step=$step prev=$prev" }
            prev = step
        }
    }
}
