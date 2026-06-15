package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.model.WarehouseItem
import org.junit.Assert.*
import org.junit.Test

class WarehouseModelsTest {

    // ============================================================
    // ItemFilter
    // ============================================================

    @Test
    fun `ItemFilter默认值`() {
        val filter = ItemFilter()
        assertNull(filter.types)
        assertNull(filter.minRarity)
        assertNull(filter.maxRarity)
        assertNull(filter.keyword)
        assertEquals(ItemFilter.SortBy.RARITY_DESC, filter.sortBy)
        assertNull(filter.capacityLimit)
    }

    @Test
    fun `ItemFilter-SortBy枚举完整性`() {
        val expected = listOf(
            "RARITY_DESC", "RARITY_ASC", "NAME_ASC", "NAME_DESC",
            "TYPE_ASC", "QUANTITY_DESC", "QUANTITY_ASC"
        )
        val actual = ItemFilter.SortBy.entries.map { it.name }
        assertEquals(expected, actual)
    }

    @Test
    fun `ItemFilter-matches类型过滤`() {
        val filter = ItemFilter(types = setOf("pill", "herb"))
        val pill = makeItem(itemType = "pill")
        val equipment = makeItem(itemType = "equipment")
        assertTrue(filter.matches(pill))
        assertFalse(filter.matches(equipment))
    }

    @Test
    fun `ItemFilter-matches稀有度范围`() {
        val filter = ItemFilter(minRarity = 2, maxRarity = 4)
        assertTrue(filter.matches(makeItem(rarity = 2)))
        assertTrue(filter.matches(makeItem(rarity = 3)))
        assertTrue(filter.matches(makeItem(rarity = 4)))
        assertFalse(filter.matches(makeItem(rarity = 1)))
        assertFalse(filter.matches(makeItem(rarity = 5)))
    }

    @Test
    fun `ItemFilter-matches关键词匹配`() {
        val filter = ItemFilter(keyword = "灵草")
        assertTrue(filter.matches(makeItem(itemName = "灵草精华")))
        assertTrue(filter.matches(makeItem(itemId = "lingCao_灵草")))
        assertFalse(filter.matches(makeItem(itemName = "铁剑", itemId = "iron_sword")))
    }

    @Test
    fun `ItemFilter-matches空白关键词不过滤`() {
        val filter = ItemFilter(keyword = "  ")
        assertTrue(filter.matches(makeItem(itemName = "任意物品")))
    }

    @Test
    fun `ItemFilter-matches无过滤条件全部通过`() {
        val filter = ItemFilter()
        assertTrue(filter.matches(makeItem()))
    }

    @Test
    fun `ItemFilter-matches组合条件`() {
        val filter = ItemFilter(
            types = setOf("pill"),
            minRarity = 3,
            keyword = "丹"
        )
        assertTrue(filter.matches(makeItem(itemType = "pill", rarity = 3, itemName = "突破丹")))
        assertFalse(filter.matches(makeItem(itemType = "pill", rarity = 2, itemName = "突破丹")))
        assertFalse(filter.matches(makeItem(itemType = "equipment", rarity = 3, itemName = "突破丹")))
        assertFalse(filter.matches(makeItem(itemType = "pill", rarity = 3, itemName = "铁剑")))
    }

    // ============================================================
    // WarehousePage
    // ============================================================

    @Test
    fun `WarehousePage-isEmpty`() {
        val empty = WarehousePage(items = emptyList(), pageIndex = 0, pageSize = 10, totalItems = 0, totalPages = 1, hasMore = false)
        assertTrue(empty.isEmpty)

        val nonEmpty = WarehousePage(items = listOf(makeItem()), pageIndex = 0, pageSize = 10, totalItems = 1, totalPages = 1, hasMore = false)
        assertFalse(nonEmpty.isEmpty)
    }

    @Test
    fun `WarehousePage-isFirstPage`() {
        val first = WarehousePage(items = emptyList(), pageIndex = 0, pageSize = 10, totalItems = 0, totalPages = 1, hasMore = false)
        assertTrue(first.isFirstPage)

        val second = WarehousePage(items = emptyList(), pageIndex = 1, pageSize = 10, totalItems = 0, totalPages = 1, hasMore = false)
        assertFalse(second.isFirstPage)
    }

    @Test
    fun `WarehousePage-isLastPage`() {
        val last = WarehousePage(items = emptyList(), pageIndex = 0, pageSize = 10, totalItems = 0, totalPages = 1, hasMore = false)
        assertTrue(last.isLastPage)

        val notLast = WarehousePage(items = emptyList(), pageIndex = 0, pageSize = 10, totalItems = 0, totalPages = 1, hasMore = true)
        assertFalse(notLast.isLastPage)
    }

    // ============================================================
    // WarehouseDiff
    // ============================================================

    @Test
    fun `WarehouseDiff默认值无变化`() {
        val diff = WarehouseDiff()
        assertFalse(diff.hasChanges)
        assertTrue(diff.addedItems.isEmpty())
        assertTrue(diff.removedItemIds.isEmpty())
        assertTrue(diff.quantityChanges.isEmpty())
        assertEquals(0L, diff.spiritStonesDelta)
    }

    @Test
    fun `WarehouseDiff-hasChanges有新增时为true`() {
        val diff = WarehouseDiff(addedItems = listOf(makeItem()))
        assertTrue(diff.hasChanges)
    }

    @Test
    fun `WarehouseDiff-hasChanges有删除时为true`() {
        val diff = WarehouseDiff(removedItemIds = setOf("item1"))
        assertTrue(diff.hasChanges)
    }

    @Test
    fun `WarehouseDiff-hasChanges有数量变化时为true`() {
        val diff = WarehouseDiff(quantityChanges = mapOf("item1" to 5))
        assertTrue(diff.hasChanges)
    }

    @Test
    fun `WarehouseDiff-hasChanges灵石变化时为true`() {
        val diff = WarehouseDiff(spiritStonesDelta = 100L)
        assertTrue(diff.hasChanges)
    }

    @Test
    fun `WarehouseDiff-timestamp非零`() {
        val diff = WarehouseDiff()
        assertTrue(diff.timestamp > 0)
    }

    // ============================================================
    // CompactWarehouseItem
    // ============================================================

    @Test
    fun `CompactWarehouseItem类型常量`() {
        assertEquals(1, CompactWarehouseItem.TYPE_PILL.toInt())
        assertEquals(2, CompactWarehouseItem.TYPE_EQUIPMENT.toInt())
        assertEquals(3, CompactWarehouseItem.TYPE_MANUAL.toInt())
        assertEquals(4, CompactWarehouseItem.TYPE_MATERIAL.toInt())
        assertEquals(5, CompactWarehouseItem.TYPE_HERB.toInt())
        assertEquals(6, CompactWarehouseItem.TYPE_SEED.toInt())
    }

    @Test
    fun `CompactWarehouseItem数据类属性`() {
        val compact = CompactWarehouseItem(id = 1, nameId = 2, type = 1, rarity = 3, quantity = 10)
        assertEquals(1, compact.id)
        assertEquals(2, compact.nameId)
        assertEquals(1.toByte(), compact.type)
        assertEquals(3.toByte(), compact.rarity)
        assertEquals(10.toShort(), compact.quantity)
    }

    // ============================================================
    // WarehouseStats
    // ============================================================

    @Test
    fun `WarehouseStats默认值`() {
        val stats = WarehouseStats()
        assertEquals(0, stats.totalItems)
        assertEquals(0, stats.totalQuantity)
        assertTrue(stats.itemsByType.isEmpty())
        assertTrue(stats.itemsByRarity.isEmpty())
        assertEquals(0, stats.uniqueItemCount)
        assertTrue(stats.lastUpdated > 0)
    }

    @Test
    fun `WarehouseStats自定义值`() {
        val stats = WarehouseStats(
            totalItems = 10,
            totalQuantity = 50,
            itemsByType = mapOf("pill" to 5, "herb" to 5),
            itemsByRarity = mapOf(1 to 3, 2 to 7),
            uniqueItemCount = 8
        )
        assertEquals(10, stats.totalItems)
        assertEquals(50, stats.totalQuantity)
        assertEquals(2, stats.itemsByType.size)
        assertEquals(2, stats.itemsByRarity.size)
        assertEquals(8, stats.uniqueItemCount)
    }

    // ============================================================
    // WarehouseChange
    // ============================================================

    @Test
    fun `WarehouseChange-Add`() {
        val item = makeItem()
        val change = WarehouseChange.Add(item)
        assertEquals(item, change.item)
    }

    @Test
    fun `WarehouseChange-Remove`() {
        val change = WarehouseChange.Remove("item1", 3)
        assertEquals("item1", change.itemId)
        assertEquals(3, change.count)
    }

    @Test
    fun `WarehouseChange-Update`() {
        val change = WarehouseChange.Update("item1", 10)
        assertEquals("item1", change.itemId)
        assertEquals(10, change.newQuantity)
    }

    @Test
    fun `WarehouseChange-SpiritStones`() {
        val change = WarehouseChange.SpiritStones(500L)
        assertEquals(500L, change.delta)
    }

    // ============================================================
    // WarehouseCapacity
    // ============================================================

    @Test
    fun `WarehouseCapacity默认值`() {
        val cap = WarehouseCapacity()
        assertEquals(200, cap.maxSlots)
        assertEquals(999, cap.maxStack)
        assertEquals(0, cap.usedSlots)
        assertEquals(0, cap.expansionLevel)
    }

    @Test
    fun `WarehouseCapacity-availableSlots计算`() {
        val cap = WarehouseCapacity(maxSlots = 200, usedSlots = 50, expansionLevel = 2)
        assertEquals(250, cap.availableSlots) // 200 + 2*50 - 50
    }

    @Test
    fun `WarehouseCapacity-isFull`() {
        val full = WarehouseCapacity(maxSlots = 200, usedSlots = 200, expansionLevel = 0)
        assertTrue(full.isFull)

        val notFull = WarehouseCapacity(maxSlots = 200, usedSlots = 199, expansionLevel = 0)
        assertFalse(notFull.isFull)
    }

    @Test
    fun `WarehouseCapacity-isFull超出容量也为true`() {
        val overFull = WarehouseCapacity(maxSlots = 200, usedSlots = 250, expansionLevel = 0)
        assertTrue(overFull.isFull)
    }

    @Test
    fun `WarehouseCapacity-utilizationRate计算`() {
        val cap = WarehouseCapacity(maxSlots = 200, usedSlots = 100, expansionLevel = 0)
        assertEquals(0.5f, cap.utilizationRate, 0.001f)
    }

    @Test
    fun `WarehouseCapacity-utilizationRate含扩容`() {
        val cap = WarehouseCapacity(maxSlots = 200, usedSlots = 150, expansionLevel = 1)
        // total = 200 + 1*50 = 250, rate = 150/250 = 0.6
        assertEquals(0.6f, cap.utilizationRate, 0.001f)
    }

    @Test
    fun `WarehouseCapacity-utilizationRate零容量返回0`() {
        val cap = WarehouseCapacity(maxSlots = 0, usedSlots = 0, expansionLevel = 0)
        assertEquals(0f, cap.utilizationRate, 0.001f)
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private fun makeItem(
        itemId: String = "test_item",
        itemName: String = "测试物品",
        itemType: String = "pill",
        rarity: Int = 1,
        quantity: Int = 1
    ): WarehouseItem = WarehouseItem(
        itemId = itemId,
        itemName = itemName,
        itemType = itemType,
        rarity = rarity,
        quantity = quantity
    )
}
