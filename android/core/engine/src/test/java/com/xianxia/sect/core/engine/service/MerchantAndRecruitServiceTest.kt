package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.SpiritStoneExchange
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.`when`

class MerchantAndRecruitServiceTest {

    // ==================== buildMerchantItemPools ====================

    @Test
    fun `buildMerchantItemPools - contains mid and high grade spirit stones`() {
        val service = MerchantAndRecruitService(
            FakeAtomicStateStore(),
            mockSmart(GameRngManager::class.java)
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

    // ==================== selectRarity 年份品阶曲线 ====================

    /** 构造服务并 stub SYSTEM 分区 RNG 为固定种子（现有 mock 未 stub getRng，新测试必须 stub） */
    private fun createServiceWithRng(seed: Long): MerchantAndRecruitService {
        val rngManager = mockSmart(GameRngManager::class.java)
        `when`(rngManager.getRng(RngPartition.SYSTEM)).thenReturn(DeterministicRng.fromSeed(seed))
        return MerchantAndRecruitService(FakeAtomicStateStore(), rngManager)
    }

    @Test
    fun `selectRarity - 第1年全部凡品`() {
        val service = createServiceWithRng(42L)
        repeat(200) { assertEquals(1, service.selectRarity(1)) }
    }

    @Test
    fun `selectRarity - 第50年结果仅限凡品灵品`() {
        val service = createServiceWithRng(42L)
        repeat(200) { assertTrue(service.selectRarity(50) in 1..2) }
    }

    @Test
    fun `selectRarity - 第2000年结果覆盖1到6`() {
        val service = createServiceWithRng(42L)
        repeat(200) { assertTrue(service.selectRarity(2000) in 1..6) }
    }

    // ==================== 保底 ====================

    @Test
    fun `addGuaranteedTopRarityItem - 保底品阶为下一阶段最高品阶`() {
        val service = createServiceWithRng(7L)
        val pools = service.buildMerchantItemPools()
        val cases = mapOf(
            5 to 2,    // [1,20) → 灵品
            60 to 3,   // [20,80) → 宝品（用户例子）
            250 to 4,  // [80,300) → 玄品
            1000 to 6, // [500,1500) → 天品
            2000 to 6  // 末段 → 天品
        )
        for ((year, expected) in cases) {
            val items = mutableListOf<MerchantItem>()
            service.addGuaranteedTopRarityItem(items, pools, year, 1, 10)
            assertEquals("year=$year", expected, items.single().rarity)
        }
    }

    @Test
    fun `addGuaranteedTopRarityItem - 保底品阶池为空时跳过不崩溃`() {
        val service = createServiceWithRng(7L)
        val emptyPools = MerchantItemPools() // poolByRarity 全空
        val items = mutableListOf<MerchantItem>()
        service.addGuaranteedTopRarityItem(items, emptyPools, 60, 1, 10)
        assertTrue(items.isEmpty())
    }
}
