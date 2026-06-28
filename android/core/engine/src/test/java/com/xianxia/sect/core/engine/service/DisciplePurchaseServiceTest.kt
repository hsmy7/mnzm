package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import org.junit.Assert.*
import org.junit.Test

class DisciplePurchaseServiceTest {

    // ═══════════════════════════════════════════════════════════════
    // canUseItem — 境界检查
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `canUseItem - realm sufficient returns true`() {
        // 化神(5) vs 玄品(4) → minRealm=5, 5<=5 → 可用
        assertTrue(DisciplePurchaseService.canUseItem(5, 4))
    }

    @Test
    fun `canUseItem - realm insufficient returns false`() {
        // 金丹(7) vs 玄品(4) → minRealm=5, 7>5 → 不可用
        assertFalse(DisciplePurchaseService.canUseItem(7, 4))
    }

    @Test
    fun `canUseItem - 仙人可用所有品阶`() {
        assertTrue(DisciplePurchaseService.canUseItem(0, 6))
    }

    @Test
    fun `canUseItem - 炼气只能用品阶1`() {
        assertTrue(DisciplePurchaseService.canUseItem(9, 1))
        assertFalse(DisciplePurchaseService.canUseItem(9, 2))
    }

    // ═══════════════════════════════════════════════════════════════
    // calculateBudget — 预算计算
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `calculateBudget - saves 30pct minimum`() {
        // total=10000, highest=5000
        // reserve = max(3000, 1000) = 3000, budget = 7000
        assertEquals(7000L,
            DisciplePurchaseService.calculateBudget(10000L, 5000L))
    }

    @Test
    fun `calculateBudget - saves more for expensive targets`() {
        // total=10000, highest=30000
        // reserve = max(3000, 6000) = 6000, budget = 4000
        assertEquals(4000L,
            DisciplePurchaseService.calculateBudget(10000L, 30000L))
    }

    @Test
    fun `calculateBudget - zero funds returns zero`() {
        assertEquals(0L,
            DisciplePurchaseService.calculateBudget(0L, 1000L))
    }

    @Test
    fun `calculateBudget - no needed items still saves 30pct`() {
        assertEquals(7000L,
            DisciplePurchaseService.calculateBudget(10000L, 0L))
    }

    @Test
    fun `calculateBudget - small funds may result in zero budget`() {
        // total=100, highest=1000 → reserve=max(30,200)=200 → 0
        assertEquals(0L,
            DisciplePurchaseService.calculateBudget(100L, 1000L))
    }

    // ═══════════════════════════════════════════════════════════════
    // needsManual — 功法需求判断
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `needsManual - unlearned returns true`() {
        val learned = setOf("太乙剑诀", "九阳神功")
        assertTrue(DisciplePurchaseService.needsManual(
            learned, "六脉神剑", 5, 0))
    }

    @Test
    fun `needsManual - learned but higher rarity returns true`() {
        val learned = setOf("太乙剑诀")
        assertTrue(DisciplePurchaseService.needsManual(
            learned, "太乙剑诀", 5, 3))
    }

    @Test
    fun `needsManual - learned same rarity returns false`() {
        val learned = setOf("太乙剑诀")
        assertFalse(DisciplePurchaseService.needsManual(
            learned, "太乙剑诀", 5, 5))
    }

    @Test
    fun `needsManual - learned higher rarity returns false`() {
        val learned = setOf("太乙剑诀")
        assertFalse(DisciplePurchaseService.needsManual(
            learned, "太乙剑诀", 3, 6))
    }

    @Test
    fun `needsManual - empty name returns false`() {
        val learned = setOf("太乙剑诀")
        assertFalse(DisciplePurchaseService.needsManual(
            learned, "", 5, 0))
    }

    // ═══════════════════════════════════════════════════════════════
    // needsEquipmentSlot — 装备需求判断
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `needsEquipmentSlot - empty slot returns true`() {
        assertTrue(DisciplePurchaseService.needsEquipmentSlot("", 5, 0))
    }

    @Test
    fun `needsEquipmentSlot - higher rarity returns true`() {
        assertTrue(DisciplePurchaseService.needsEquipmentSlot(
            "eq_001", 5, 3))
    }

    @Test
    fun `needsEquipmentSlot - lower rarity returns false`() {
        assertFalse(DisciplePurchaseService.needsEquipmentSlot(
            "eq_001", 2, 4))
    }

    @Test
    fun `needsEquipmentSlot - equal rarity returns false`() {
        assertFalse(DisciplePurchaseService.needsEquipmentSlot(
            "eq_001", 5, 5))
    }

    // ═══════════════════════════════════════════════════════════════
    // calculateHighestNeededPrice — 最高价格计算
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `calculateHighestNeededPrice - returns max price of usable items`() {
        val items = listOf(
            MerchantItem("m1", "物品A", "manual", "a", 1, 1000L, 5),
            MerchantItem("m2", "物品B", "manual", "b", 2, 5000L, 5),
            MerchantItem("m3", "物品C", "manual", "c", 3, 3000L, 5),
        )
        // 炼气(9)只能用品阶1 → 只有1000价格的能用
        assertEquals(1000L,
            DisciplePurchaseService.calculateHighestNeededPrice(items, 9))
    }

    @Test
    fun `calculateHighestNeededPrice - empty list returns zero`() {
        assertEquals(0L,
            DisciplePurchaseService.calculateHighestNeededPrice(
                emptyList(), 9))
    }

    @Test
    fun `calculateHighestNeededPrice - all usable returns highest`() {
        val items = listOf(
            MerchantItem("m1", "物品A", "manual", "a", 5, 1000L, 5),
            MerchantItem("m2", "物品B", "manual", "b", 5, 5000L, 5),
        )
        // 仙人(0)能用全品阶 → 最高5000
        assertEquals(5000L,
            DisciplePurchaseService.calculateHighestNeededPrice(items, 0))
    }

    // ═══════════════════════════════════════════════════════════════
    // 常量验证
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `per-type purchase limits are as specified`() {
        assertEquals(2, DisciplePurchaseService.MAX_MANUAL_PURCHASES)
        assertEquals(2, DisciplePurchaseService.MAX_EQUIPMENT_PURCHASES)
        assertEquals(10, DisciplePurchaseService.MAX_PILL_PURCHASES)
    }
}
