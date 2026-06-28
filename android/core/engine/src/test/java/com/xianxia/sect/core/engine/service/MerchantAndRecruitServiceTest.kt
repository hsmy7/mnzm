package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory
import com.xianxia.sect.core.model.SpiritStoneExchange
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

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

    // ==================== buildMerchantItemPools ====================

    @Test
    fun `buildMerchantItemPools - contains mid and high grade spirit stones`() {
        val service = MerchantAndRecruitService(
            mock(GameStateStore::class.java),
            mock(CoroutineScopeProvider::class.java),
            mock(DiscipleFactory::class.java)
        )
        val pools = service.buildMerchantItemPools()

        val midEntry = pools.poolByRarity[3]?.find { it.name == "中品灵石" && it.type == "spiritStone" }
        val highEntry = pools.poolByRarity[4]?.find { it.name == "上品灵石" && it.type == "spiritStone" }

        assertNotNull("中品灵石应加入稀有度 3 池", midEntry)
        assertNotNull("上品灵石应加入稀有度 4 池", highEntry)

        assertEquals(SpiritStoneExchange.RATIO, pools.priceMap["中品灵石"])
        assertEquals(SpiritStoneExchange.RATIO * SpiritStoneExchange.RATIO, pools.priceMap["上品灵石"])
        assertEquals(3, pools.rarityMap["中品灵石"])
        assertEquals(4, pools.rarityMap["上品灵石"])
    }
}
