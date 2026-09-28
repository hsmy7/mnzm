package com.xianxia.sect.core.engine.system

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 追补上限/旬时常量锚（单一时速，无速度维度）：
 * 锁定 MAX_PHASES_PER_TICK == 3 与 MS_PER_PHASE == 2000ms
 * （GameTimeClock companion 单一来源）。符号面已无 maxPhasesPerTick(speed)
 * 参数化公式——速度维度整体删除后，追补上限与速度解耦为常量。
 * 双端行为等价由 DiffEngineLoopTest 对拍承接（3000/8000/60000ms 同输入同输出）；
 * C++ 侧常量锚：settlement.h kMaxPhasesPerTick / time_units.h kGameMsPerPhase +
 * engine_loop_test.cpp MultiPhaseInOneTickCappedAt3（8000ms → 3 旬）。
 * 改值须双端同步。
 */
class GameTimeClockPhaseCapParityTest {

    @Test
    fun `base constant is the documented 3`() {
        // 双端锚定常量：C++ settlement.h kMaxPhasesPerTick = 3
        assertEquals(3, GameTimeClock.MAX_PHASES_PER_TICK)
    }

    @Test
    fun `msPerPhase is the documented single-speed 2000ms`() {
        // 双端锚定常量：C++ settlement.h kMsPerPhase = 2000（单一时速）
        assertEquals(2000L, GameTimeClock.MS_PER_PHASE)
    }
}
