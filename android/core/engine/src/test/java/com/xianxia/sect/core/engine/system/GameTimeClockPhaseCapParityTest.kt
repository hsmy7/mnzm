package com.xianxia.sect.core.engine.system

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 追补公式双端对拍（ / 方案第六部分护栏 PhaseCapParityTest）：
 * 锁定 Kotlin 侧公式 maxPhasesPerTick(speed)=MAX_PHASES_PER_TICK×max(speed,1)
 * （GameTimeClock companion 单一来源）；C++ 侧对应 engine_loop_test.cpp 的
 * PhaseCapParityTest（settlement.h maxPhasesPerTick 同公式同常量）——
 * 两测互为锚点，改值须双端同步。
 *
 * 语义：2x 速度下挂起 ≥6s 计划 6 旬全部执行——不再被无缩放 cap(3) 静默丢旬
 * 且不 refund（游戏时间相对墙钟持续变慢的根因）。
 */
class GameTimeClockPhaseCapParityTest {

    @Test
    fun `formula scales with speed`() {
        assertEquals(3, GameTimeClock.maxPhasesPerTick(0))
        assertEquals(3, GameTimeClock.maxPhasesPerTick(1))
        assertEquals(6, GameTimeClock.maxPhasesPerTick(2))
        assertEquals(3, GameTimeClock.maxPhasesPerTick(-1))  // 负速度消毒为 1x 档
    }

    @Test
    fun `base constant is the documented 3`() {
        // 双端锚定常量：C++ settlement.h kMaxPhasesPerTick = 3
        assertEquals(3, GameTimeClock.MAX_PHASES_PER_TICK)
    }
}
