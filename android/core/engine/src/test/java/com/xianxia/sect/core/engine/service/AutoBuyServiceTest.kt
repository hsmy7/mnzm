package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AutoBuyServiceTest {

    @Before
    fun setUp() {
        if (!ManualDatabase.isInitialized) {
            val template = ManualDatabase.ManualTemplate(
                id = "testManual1",
                name = "测试功法",
                type = ManualType.ATTACK,
                rarity = 1,
                description = "测试用功法"
            )
            ManualDatabase.initializeWithManuals(
                mapOf("testManual1" to template)
            )
        }
    }

    // ── AutoBuyCatalogItem ──────────────────────────────────────────

    // 1. AutoBuyCatalogItem properties are accessible
    @Test
    fun autoBuyCatalogItem_propertiesAreAccessible() {
        val item = AutoBuyCatalogItem(
            name = "测试物品",
            type = "equipment",
            rarity = 6
        )
        assertEquals("测试物品", item.name)
        assertEquals("equipment", item.type)
        assertEquals(6, item.rarity)
    }

    // ── AutoBuyEntry ────────────────────────────────────────────────

    // 2. AutoBuyEntry properties are accessible
    @Test
    fun autoBuyEntry_propertiesAreAccessible() {
        val entry = AutoBuyEntry(
            itemName = "天品灵剑",
            itemType = "equipment",
            rarity = 6
        )
        assertEquals("天品灵剑", entry.itemName)
        assertEquals("equipment", entry.itemType)
        assertEquals(6, entry.rarity)
    }

    // 3. AutoBuyEntry uniqueness key format
    @Test
    fun autoBuyEntry_uniquenessKeyFormat() {
        val entry = AutoBuyEntry(
            itemName = "养气丹",
            itemType = "pill",
            rarity = 2
        )
        val key = "${entry.itemName}:${entry.itemType}:${entry.rarity}"
        assertEquals("养气丹:pill:2", key)
    }

    // 4. AutoBuyEntry distinctBy works with composite key
    @Test
    fun autoBuyEntry_distinctBy_removesDuplicatesByCompositeKey() {
        val entry1 = AutoBuyEntry("剑", "equipment", 3)
        val entry2 = AutoBuyEntry("剑", "equipment", 3) // same
        val entry3 = AutoBuyEntry("剑", "equipment", 4) // different rarity
        val list = listOf(entry1, entry2, entry3)
        val distinct = list.distinctBy {
            "${it.itemName}:${it.itemType}:${it.rarity}"
        }
        assertEquals(2, distinct.size)
        assertTrue(distinct.any { it.rarity == 3 })
        assertTrue(distinct.any { it.rarity == 4 })
    }

    // ── 匹配逻辑 ────────────────────────────────────────────────────

    // 5. MerchantItem matching by name, type, rarity
    @Test
    fun merchantItem_matching_byNameTypeAndRarity() {
        val autoEntry = AutoBuyEntry(
            itemName = "铁剑",
            itemType = "equipment",
            rarity = 2
        )
        val merchantItem = MerchantItem(
            id = "m1",
            name = "铁剑",
            type = "equipment",
            itemId = "sword_iron",
            rarity = 2,
            price = 100L,
            quantity = 5
        )
        val matches = merchantItem.name == autoEntry.itemName &&
            merchantItem.type == autoEntry.itemType &&
            merchantItem.rarity == autoEntry.rarity
        assertTrue(matches)
    }

    // 6. MerchantItem does not match different rarity
    @Test
    fun merchantItem_doesNotMatch_differentRarity() {
        val autoEntry = AutoBuyEntry(
            itemName = "铁剑",
            itemType = "equipment",
            rarity = 3
        )
        val merchantItem = MerchantItem(
            id = "m1",
            name = "铁剑",
            type = "equipment",
            itemId = "sword_iron",
            rarity = 2,
            price = 100L,
            quantity = 5
        )
        val matches = merchantItem.name == autoEntry.itemName &&
            merchantItem.type == autoEntry.itemType &&
            merchantItem.rarity == autoEntry.rarity
        assertFalse(matches)
    }

    // 7. MerchantItem does not match different type
    @Test
    fun merchantItem_doesNotMatch_differentType() {
        val autoEntry = AutoBuyEntry(
            itemName = "铁剑",
            itemType = "manual",
            rarity = 2
        )
        val merchantItem = MerchantItem(
            id = "m1",
            name = "铁剑",
            type = "equipment",
            itemId = "sword_iron",
            rarity = 2,
            price = 100L,
            quantity = 5
        )
        val matches = merchantItem.name == autoEntry.itemName &&
            merchantItem.type == autoEntry.itemType &&
            merchantItem.rarity == autoEntry.rarity
        assertFalse(matches)
    }

    // ── 购买数量计算 ────────────────────────────────────────────────

    // 8. Calculate max affordable quantity based on spirit stones
    @Test
    fun calculateMaxAffordable_whenStonesSufficient_buysAll() {
        val price = 100L
        val quantity = 5
        val stones = 1000L
        val maxAffordable = (stones / price).toInt()
        val buyQty = minOf(quantity, maxAffordable)
        assertEquals(5, buyQty)
    }

    // 9. Calculate max affordable when stones insufficient
    @Test
    fun calculateMaxAffordable_whenStonesInsufficient_buysPartial() {
        val price = 100L
        val quantity = 10
        val stones = 350L
        val maxAffordable = (stones / price).toInt()
        val buyQty = minOf(quantity, maxAffordable)
        assertEquals(3, buyQty)
        assertEquals(300L, price * buyQty)
    }

    // 10. Calculate max affordable when zero stones
    @Test
    fun calculateMaxAffordable_whenZeroStones_buysNone() {
        val price = 100L
        val quantity = 5
        val stones = 0L
        val maxAffordable = (stones / price).toInt()
        val buyQty = minOf(quantity, maxAffordable)
        assertEquals(0, buyQty)
    }

    // 11. Price is zero allows buying full quantity
    @Test
    fun calculateMaxAffordable_whenPriceIsZero_buysAll() {
        val price = 0L
        val quantity = 5
        val maxAffordable = quantity // special case for free items
        val buyQty = minOf(quantity, maxAffordable)
        assertEquals(5, buyQty)
    }

    // ── 库存减少后从列表移除 ────────────────────────────────────────

    // 12. MerchantItem removed from list when quantity reaches zero
    @Test
    fun merchantItem_removedFromList_whenQuantityReachesZero() {
        val items = mutableListOf(
            MerchantItem(id = "1", name = "剑", type = "equipment",
                rarity = 2, price = 100L, quantity = 3)
        )
        val buyQty = 3
        val item = items[0]
        val remaining = item.quantity - buyQty
        if (remaining <= 0) {
            items.removeAt(0)
        }
        assertTrue(items.isEmpty())
    }

    // 13. MerchantItem quantity reduced when partial purchase
    @Test
    fun merchantItem_quantityReduced_whenPartialPurchase() {
        val items = mutableListOf(
            MerchantItem(id = "1", name = "剑", type = "equipment",
                rarity = 2, price = 100L, quantity = 5)
        )
        val buyQty = 3
        val item = items[0]
        val remaining = item.quantity - buyQty
        items[0] = item.copy(quantity = remaining)
        assertEquals(2, items[0].quantity)
        assertEquals(1, items.size)
    }
}
