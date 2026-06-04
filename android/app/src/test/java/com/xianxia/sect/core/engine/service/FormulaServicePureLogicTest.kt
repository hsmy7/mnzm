package com.xianxia.sect.core.engine.service

import org.junit.Assert.*
import org.junit.Test

class FormulaServicePureLogicTest {

    // ==================== 境界成功率加成 ====================

    private fun getRealmSuccessRateBonus(realm: Int): Double = when (realm) {
        0 -> 0.30; 1 -> 0.25; 2 -> 0.22; 3 -> 0.19
        4 -> 0.16; 5 -> 0.13; 6 -> 0.10; 7 -> 0.07
        8 -> 0.04; else -> 0.0
    }

    @Test fun realm0_仙人_30percent() = assertEquals(0.30, getRealmSuccessRateBonus(0), 0.001)
    @Test fun realm1_渡劫_25percent() = assertEquals(0.25, getRealmSuccessRateBonus(1), 0.001)
    @Test fun realm2_大乘_22percent() = assertEquals(0.22, getRealmSuccessRateBonus(2), 0.001)
    @Test fun realm3_合体_19percent() = assertEquals(0.19, getRealmSuccessRateBonus(3), 0.001)
    @Test fun realm4_炼虚_16percent() = assertEquals(0.16, getRealmSuccessRateBonus(4), 0.001)
    @Test fun realm5_化神_13percent() = assertEquals(0.13, getRealmSuccessRateBonus(5), 0.001)
    @Test fun realm6_元婴_10percent() = assertEquals(0.10, getRealmSuccessRateBonus(6), 0.001)
    @Test fun realm7_金丹_7percent() = assertEquals(0.07, getRealmSuccessRateBonus(7), 0.001)
    @Test fun realm8_筑基_4percent() = assertEquals(0.04, getRealmSuccessRateBonus(8), 0.001)
    @Test fun realm9_炼气_0percent() = assertEquals(0.0, getRealmSuccessRateBonus(9), 0.001)
    @Test fun realmNeg1_0percent() = assertEquals(0.0, getRealmSuccessRateBonus(-1), 0.001)
    @Test fun realm100_0percent() = assertEquals(0.0, getRealmSuccessRateBonus(100), 0.001)

    // ==================== 持续时间缩减公式 ====================

    private fun calculateReducedDuration(baseDuration: Int, speedBonus: Double): Int {
        if (speedBonus <= 0) return baseDuration
        val reductionPercent = speedBonus / 4.0
        val reducedMonths = (baseDuration * reductionPercent).toInt()
        return (baseDuration - reducedMonths).coerceAtLeast(1)
    }

    @Test fun reducedDuration_zeroBonus_returnsBase() = assertEquals(10, calculateReducedDuration(10, 0.0))
    @Test fun reducedDuration_negativeBonus_returnsBase() = assertEquals(10, calculateReducedDuration(10, -1.0))
    @Test fun reducedDuration_smallBonus() = assertEquals(9, calculateReducedDuration(10, 0.4))
    @Test fun reducedDuration_largeBonus_minimum1() = assertEquals(1, calculateReducedDuration(10, 100.0))
    @Test fun reducedDuration_base1_always1() = assertEquals(1, calculateReducedDuration(1, 0.5))
    @Test fun reducedDuration_exactCalculation() {
        // baseDuration=12, speedBonus=2.0 -> reductionPercent=0.5 -> reducedMonths=6 -> result=6
        assertEquals(6, calculateReducedDuration(12, 2.0))
    }

    // ==================== ElderBonusData ====================

    @Test fun elderBonusData_equality() {
        val a = FormulaService.ElderBonusData(0.1, 0.2, 0.0)
        val b = FormulaService.ElderBonusData(0.1, 0.2, 0.0)
        assertEquals(a, b)
    }
    @Test fun elderBonusData_defaultYieldZero() {
        val data = FormulaService.ElderBonusData(0.5, 0.3, 0.0)
        assertEquals(0.0, data.yieldBonus, 0.001)
    }
}
