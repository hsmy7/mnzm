package com.xianxia.sect.core.engine.service

import org.junit.Assert.*
import org.junit.Test

class MerchantAndRecruitServiceTest {

    // ==================== calcRecruitBonusCap ====================

    @Test
    fun calcRecruitBonusCap_charmBelow80_returnsZero() {
        assertEquals(0, MerchantAndRecruitService.calcRecruitBonusCap(50))
        assertEquals(0, MerchantAndRecruitService.calcRecruitBonusCap(79))
    }

    @Test
    fun calcRecruitBonusCap_charmAt80_returnsZero() {
        assertEquals(0, MerchantAndRecruitService.calcRecruitBonusCap(80))
    }

    @Test
    fun calcRecruitBonusCap_charm84_returnsOne() {
        assertEquals(1, MerchantAndRecruitService.calcRecruitBonusCap(84))
    }

    @Test
    fun calcRecruitBonusCap_charm88_returnsTwo() {
        assertEquals(2, MerchantAndRecruitService.calcRecruitBonusCap(88))
    }

    @Test
    fun calcRecruitBonusCap_charm100_returnsFive() {
        assertEquals(5, MerchantAndRecruitService.calcRecruitBonusCap(100))
    }

    @Test
    fun calcRecruitBonusCap_boundaryRounding() {
        // (83-80)/4 = 0.75 → floor = 0
        assertEquals(0, MerchantAndRecruitService.calcRecruitBonusCap(83))
        // (84-80)/4 = 1.0 → 1
        assertEquals(1, MerchantAndRecruitService.calcRecruitBonusCap(84))
        // (87-80)/4 = 1.75 → 1
        assertEquals(1, MerchantAndRecruitService.calcRecruitBonusCap(87))
    }
}
