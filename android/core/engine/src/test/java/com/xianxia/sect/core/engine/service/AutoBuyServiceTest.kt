package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import org.junit.Assert.*
import org.junit.Test

class AutoBuyServiceTest {

    // ═══════════════════════════════════════════════════════════════
    // 匹配逻辑
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `matches - 名称类型品阶一致时匹配成功`() {
        val entry = AutoBuyEntry("铁剑", "equipment", 2)
        val item = MerchantItem("m1", "铁剑", "equipment", "sword_iron", 2, 100L, 5)
        assertTrue(AutoBuyService.matches(entry, item))
    }

    @Test
    fun `matches - 品阶不同时不匹配`() {
        val entry = AutoBuyEntry("铁剑", "equipment", 3)
        val item = MerchantItem("m1", "铁剑", "equipment", "sword_iron", 2, 100L, 5)
        assertFalse(AutoBuyService.matches(entry, item))
    }

    @Test
    fun `matches - 类型不同时不匹配`() {
        val entry = AutoBuyEntry("铁剑", "manual", 2)
        val item = MerchantItem("m1", "铁剑", "equipment", "sword_iron", 2, 100L, 5)
        assertFalse(AutoBuyService.matches(entry, item))
    }

    @Test
    fun `matches - 名称不同时不匹配`() {
        val entry = AutoBuyEntry("木剑", "equipment", 2)
        val item = MerchantItem("m1", "铁剑", "equipment", "sword_iron", 2, 100L, 5)
        assertFalse(AutoBuyService.matches(entry, item))
    }

    @Test
    fun `matches - 空名称不匹配非空名称`() {
        val entry = AutoBuyEntry("", "equipment", 2)
        val item = MerchantItem("m1", "铁剑", "equipment", "sword_iron", 2, 100L, 5)
        assertFalse(AutoBuyService.matches(entry, item))
    }

    // ═══════════════════════════════════════════════════════════════
    // 购买数量计算
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `calculateBuyQuantity - 灵石充足时买入全部商人数量`() {
        val result = AutoBuyService.calculateBuyQuantity(
            spiritStones = 1000L, price = 100L, merchantQuantity = 5)
        assertEquals(5, result)
    }

    @Test
    fun `calculateBuyQuantity - 灵石不足时买入可负担部分`() {
        val result = AutoBuyService.calculateBuyQuantity(
            spiritStones = 350L, price = 100L, merchantQuantity = 10)
        assertEquals(3, result)
    }

    @Test
    fun `calculateBuyQuantity - 灵石为零时买入零`() {
        val result = AutoBuyService.calculateBuyQuantity(
            spiritStones = 0L, price = 100L, merchantQuantity = 5)
        assertEquals(0, result)
    }

    @Test
    fun `calculateBuyQuantity - 价格为零时买入全部`() {
        val result = AutoBuyService.calculateBuyQuantity(
            spiritStones = 500L, price = 0L, merchantQuantity = 5)
        assertEquals(5, result)
    }

    @Test
    fun `calculateBuyQuantity - 商人数量为零时买入零`() {
        val result = AutoBuyService.calculateBuyQuantity(
            spiritStones = 1000L, price = 100L, merchantQuantity = 0)
        assertEquals(0, result)
    }

    @Test
    fun `calculateBuyQuantity - 刚好够买一件`() {
        val result = AutoBuyService.calculateBuyQuantity(
            spiritStones = 100L, price = 100L, merchantQuantity = 10)
        assertEquals(1, result)
    }

    @Test
    fun `calculateBuyQuantity - 灵石恰好不够买一件`() {
        val result = AutoBuyService.calculateBuyQuantity(
            spiritStones = 99L, price = 100L, merchantQuantity = 10)
        assertEquals(0, result)
    }

    @Test
    fun `calculateBuyQuantity - 大数值灵石可买大量物品`() {
        val result = AutoBuyService.calculateBuyQuantity(
            spiritStones = 9_999_999L, price = 1L, merchantQuantity = 999)
        assertEquals(999, result)
    }

    // ═══════════════════════════════════════════════════════════════
    // AutoBuyEntry 唯一键去重
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `AutoBuyEntry - 同名同类型同品阶去重`() {
        val list = listOf(
            AutoBuyEntry("剑", "equipment", 3),
            AutoBuyEntry("剑", "equipment", 3),
            AutoBuyEntry("剑", "equipment", 4)
        )
        val distinct = list.distinctBy {
            "${it.itemName}:${it.itemType}:${it.rarity}"
        }
        assertEquals(2, distinct.size)
    }

    @Test
    fun `AutoBuyEntry - 同名不同类型不去重`() {
        val list = listOf(
            AutoBuyEntry("剑", "equipment", 3),
            AutoBuyEntry("剑", "manual", 3)
        )
        val distinct = list.distinctBy {
            "${it.itemName}:${it.itemType}:${it.rarity}"
        }
        assertEquals(2, distinct.size)
    }

    @Test
    fun `AutoBuyEntry - 同名同类型不同品阶不去重`() {
        val list = listOf(
            AutoBuyEntry("剑", "equipment", 1),
            AutoBuyEntry("剑", "equipment", 6)
        )
        val distinct = list.distinctBy {
            "${it.itemName}:${it.itemType}:${it.rarity}"
        }
        assertEquals(2, distinct.size)
    }
}
