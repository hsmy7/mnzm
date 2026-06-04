package com.xianxia.sect.core.config

import com.xianxia.sect.core.config.GiftConfig
import org.junit.Assert.*
import org.junit.Test

class GiftConfigTest {

    // ============================================================
    // SpiritStoneGiftConfig
    // ============================================================

    @Test
    fun `灵石送礼档位应有4个条目`() {
        assertEquals(4, GiftConfig.SpiritStoneGiftConfig.TIERS.size)
    }

    @Test
    fun `getTier1应返回薄礼20000灵石baseFavor2`() {
        val tier = GiftConfig.SpiritStoneGiftConfig.getTier(1)
        assertNotNull(tier)
        assertEquals(1, tier!!.tier)
        assertEquals("薄礼", tier.name)
        assertEquals(20000L, tier.spiritStones)
        assertEquals(2, tier.baseFavor)
    }

    @Test
    fun `getTier2应返回厚礼200000灵石baseFavor5`() {
        val tier = GiftConfig.SpiritStoneGiftConfig.getTier(2)
        assertNotNull(tier)
        assertEquals(2, tier!!.tier)
        assertEquals("厚礼", tier.name)
        assertEquals(200000L, tier.spiritStones)
        assertEquals(5, tier.baseFavor)
    }

    @Test
    fun `getTier3应返回重礼800000灵石baseFavor10`() {
        val tier = GiftConfig.SpiritStoneGiftConfig.getTier(3)
        assertNotNull(tier)
        assertEquals(3, tier!!.tier)
        assertEquals("重礼", tier.name)
        assertEquals(800000L, tier.spiritStones)
        assertEquals(10, tier.baseFavor)
    }

    @Test
    fun `getTier4应返回大礼4000000灵石baseFavor15`() {
        val tier = GiftConfig.SpiritStoneGiftConfig.getTier(4)
        assertNotNull(tier)
        assertEquals(4, tier!!.tier)
        assertEquals("大礼", tier.name)
        assertEquals(4000000L, tier.spiritStones)
        assertEquals(15, tier.baseFavor)
    }

    @Test
    fun `getTier5应返回null`() {
        assertNull(GiftConfig.SpiritStoneGiftConfig.getTier(5))
    }

    @Test
    fun `getAllTiers应返回4个条目`() {
        val allTiers = GiftConfig.SpiritStoneGiftConfig.getAllTiers()
        assertEquals(4, allTiers.size)
    }

    // ============================================================
    // FavorPercentageConfig
    // ============================================================

    @Test
    fun `小型宗门tier1好感度百分比应为20`() {
        assertEquals(20, GiftConfig.FavorPercentageConfig.getFavorPercentage(0, 1)!!.toInt())
    }

    @Test
    fun `小型宗门tier4好感度百分比应为200`() {
        assertEquals(200, GiftConfig.FavorPercentageConfig.getFavorPercentage(0, 4)!!.toInt())
    }

    @Test
    fun `顶级宗门tier4好感度百分比应为50`() {
        assertEquals(50, GiftConfig.FavorPercentageConfig.getFavorPercentage(3, 4)!!.toInt())
    }

    @Test
    fun `大型宗门tier1好感度百分比应为null`() {
        assertNull(GiftConfig.FavorPercentageConfig.getFavorPercentage(2, 1))
    }

    @Test
    fun `小型宗门tier1应可用`() {
        assertTrue(GiftConfig.FavorPercentageConfig.isTierAvailableForSect(0, 1))
    }

    @Test
    fun `大型宗门tier1应不可用`() {
        assertFalse(GiftConfig.FavorPercentageConfig.isTierAvailableForSect(2, 1))
    }

    // ============================================================
    // RarityFavorConfig
    // ============================================================

    @Test
    fun `稀有度配置应有6个条目`() {
        assertEquals(6, GiftConfig.RarityFavorConfig.CONFIGS.size)
    }

    @Test
    fun `稀有度1基础好感度应为1`() {
        assertEquals(1, GiftConfig.RarityFavorConfig.getBaseFavor(1))
    }

    @Test
    fun `稀有度2基础好感度应为2`() {
        assertEquals(2, GiftConfig.RarityFavorConfig.getBaseFavor(2))
    }

    @Test
    fun `稀有度3基础好感度应为5`() {
        assertEquals(5, GiftConfig.RarityFavorConfig.getBaseFavor(3))
    }

    @Test
    fun `稀有度4基础好感度应为8`() {
        assertEquals(8, GiftConfig.RarityFavorConfig.getBaseFavor(4))
    }

    @Test
    fun `稀有度5基础好感度应为12`() {
        assertEquals(12, GiftConfig.RarityFavorConfig.getBaseFavor(5))
    }

    @Test
    fun `稀有度6基础好感度应为15`() {
        assertEquals(15, GiftConfig.RarityFavorConfig.getBaseFavor(6))
    }

    @Test
    fun `未知稀有度基础好感度应默认为1`() {
        assertEquals(1, GiftConfig.RarityFavorConfig.getBaseFavor(99))
    }

    @Test
    fun `基础好感度应随稀有度递增`() {
        val favors = (1..6).map { GiftConfig.RarityFavorConfig.getBaseFavor(it) }
        for (i in 0 until favors.size - 1) {
            assertTrue(
                "稀有度${i + 1}的基础好感度(${favors[i]})应小于稀有度${i + 2}的基础好感度(${favors[i + 1]})",
                favors[i] < favors[i + 1]
            )
        }
    }

    // ============================================================
    // ItemFavorPercentageConfig
    // ============================================================

    @Test
    fun `小型宗门稀有度1物品好感度百分比应为20`() {
        assertEquals(20, GiftConfig.ItemFavorPercentageConfig.getFavorPercentage(0, 1)!!.toInt())
    }

    @Test
    fun `小型宗门稀有度6物品好感度百分比应为300`() {
        assertEquals(300, GiftConfig.ItemFavorPercentageConfig.getFavorPercentage(0, 6)!!.toInt())
    }

    @Test
    fun `顶级宗门稀有度6物品好感度百分比应为80`() {
        assertEquals(80, GiftConfig.ItemFavorPercentageConfig.getFavorPercentage(3, 6)!!.toInt())
    }

    @Test
    fun `大型宗门稀有度1物品好感度百分比应为null`() {
        assertNull(GiftConfig.ItemFavorPercentageConfig.getFavorPercentage(2, 1))
    }

    @Test
    fun `无效宗门等级物品好感度百分比应为null`() {
        assertNull(GiftConfig.ItemFavorPercentageConfig.getFavorPercentage(99, 1))
    }

    // ============================================================
    // SectRejectConfig
    // ============================================================

    @Test
    fun `小型宗门凡品拒绝概率应为50`() {
        assertEquals(50, GiftConfig.SectRejectConfig.getRejectProbability(0, 1))
    }

    @Test
    fun `小型宗门灵品拒绝概率应为20`() {
        assertEquals(20, GiftConfig.SectRejectConfig.getRejectProbability(0, 2))
    }

    @Test
    fun `小型宗门宝品拒绝概率应为0`() {
        assertEquals(0, GiftConfig.SectRejectConfig.getRejectProbability(0, 3))
    }

    @Test
    fun `中型宗门凡品拒绝概率应为70`() {
        assertEquals(70, GiftConfig.SectRejectConfig.getRejectProbability(1, 1))
    }

    @Test
    fun `中型宗门宝品拒绝概率应为30`() {
        assertEquals(30, GiftConfig.SectRejectConfig.getRejectProbability(1, 3))
    }

    @Test
    fun `大型宗门凡品拒绝概率应为100`() {
        assertEquals(100, GiftConfig.SectRejectConfig.getRejectProbability(2, 1))
    }

    @Test
    fun `大型宗门玄品拒绝概率应为30`() {
        assertEquals(30, GiftConfig.SectRejectConfig.getRejectProbability(2, 4))
    }

    @Test
    fun `顶级宗门玄品拒绝概率应为50`() {
        assertEquals(50, GiftConfig.SectRejectConfig.getRejectProbability(3, 4))
    }

    @Test
    fun `顶级宗门天品拒绝概率应为0`() {
        assertEquals(0, GiftConfig.SectRejectConfig.getRejectProbability(3, 6))
    }

    @Test
    fun `未知宗门等级拒绝概率应回退到小型宗门配置`() {
        assertEquals(50, GiftConfig.SectRejectConfig.getRejectProbability(99, 1))
    }

    @Test
    fun `宗门等级0名称应为小型宗门`() {
        assertEquals("小型宗门", GiftConfig.SectRejectConfig.getSectLevelName(0))
    }

    @Test
    fun `宗门等级1名称应为中型宗门`() {
        assertEquals("中型宗门", GiftConfig.SectRejectConfig.getSectLevelName(1))
    }

    @Test
    fun `宗门等级2名称应为大型宗门`() {
        assertEquals("大型宗门", GiftConfig.SectRejectConfig.getSectLevelName(2))
    }

    @Test
    fun `宗门等级3名称应为顶级宗门`() {
        assertEquals("顶级宗门", GiftConfig.SectRejectConfig.getSectLevelName(3))
    }

    @Test
    fun `未知宗门等级名称应为未知宗门`() {
        assertEquals("未知宗门", GiftConfig.SectRejectConfig.getSectLevelName(99))
    }

    @Test
    fun `更高宗门等级对低稀有度物品拒绝概率应更高`() {
        // 对凡品(rarity=1)，宗门等级越高拒绝概率越高
        val reject0 = GiftConfig.SectRejectConfig.getRejectProbability(0, 1)
        val reject1 = GiftConfig.SectRejectConfig.getRejectProbability(1, 1)
        val reject2 = GiftConfig.SectRejectConfig.getRejectProbability(2, 1)
        assertTrue("中型宗门拒绝凡品概率(${reject1})应大于小型宗门(${reject0})", reject1 > reject0)
        assertTrue("大型宗门拒绝凡品概率(${reject2})应大于中型宗门(${reject1})", reject2 > reject1)
    }

    // ============================================================
    // ItemType 常量
    // ============================================================

    @Test
    fun `MANUAL常量应为manual`() {
        assertEquals("manual", GiftConfig.ItemType.MANUAL)
    }

    @Test
    fun `EQUIPMENT常量应为equipment`() {
        assertEquals("equipment", GiftConfig.ItemType.EQUIPMENT)
    }

    @Test
    fun `PILL常量应为pill`() {
        assertEquals("pill", GiftConfig.ItemType.PILL)
    }
}
