package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.model.WarehouseItem
import org.junit.Assert.*
import org.junit.Test

class WarehousePagerTest {

    // ============================================================
    // 常量
    // ============================================================

    @Test
    fun `DEFAULT_PAGE_SIZE为72`() {
        assertEquals(72, WarehousePager.DEFAULT_PAGE_SIZE)
    }

    @Test
    fun `MAX_PAGE_SIZE为100`() {
        assertEquals(100, WarehousePager.MAX_PAGE_SIZE)
    }

    // ============================================================
    // loadPage
    // ============================================================

    @Test
    fun `loadPage基本分页`() {
        val items = (1..150).map { makeItem(itemId = "item_$it", itemName = "物品$it", rarity = it % 6 + 1) }
        val page = WarehousePager.loadPage(items, 0, 50)
        assertEquals(50, page.items.size)
        assertEquals(0, page.pageIndex)
        assertEquals(50, page.pageSize)
        assertEquals(150, page.totalItems)
        assertEquals(3, page.totalPages)
        assertTrue(page.hasMore)
        assertTrue(page.isFirstPage)
        assertFalse(page.isLastPage)
    }

    @Test
    fun `loadPage第二页`() {
        val items = (1..150).map { makeItem(itemId = "item_$it", rarity = it % 6 + 1) }
        val page = WarehousePager.loadPage(items, 1, 50)
        assertEquals(50, page.items.size)
        assertEquals(1, page.pageIndex)
        assertTrue(page.hasMore)
    }

    @Test
    fun `loadPage最后一页`() {
        val items = (1..150).map { makeItem(itemId = "item_$it", rarity = it % 6 + 1) }
        val page = WarehousePager.loadPage(items, 2, 50)
        assertEquals(50, page.items.size)
        assertFalse(page.hasMore)
        assertTrue(page.isLastPage)
    }

    @Test
    fun `loadPage空列表`() {
        val page = WarehousePager.loadPage(emptyList(), 0, 50)
        assertTrue(page.isEmpty)
        assertEquals(0, page.totalItems)
        assertEquals(1, page.totalPages)
        assertFalse(page.hasMore)
    }

    @Test
    fun `loadPage超出范围页码自动修正`() {
        val items = (1..10).map { makeItem(itemId = "item_$it") }
        val page = WarehousePager.loadPage(items, 999, 50)
        // 页码被修正到最后一页
        assertTrue(page.pageIndex < 999)
    }

    @Test
    fun `loadPage负页码自动修正为0`() {
        val items = (1..10).map { makeItem(itemId = "item_$it") }
        val page = WarehousePager.loadPage(items, -1, 50)
        assertEquals(0, page.pageIndex)
    }

    @Test
    fun `loadPage超大pageSize被限制为MAX_PAGE_SIZE`() {
        val items = (1..200).map { makeItem(itemId = "item_$it") }
        val page = WarehousePager.loadPage(items, 0, 500)
        assertEquals(100, page.pageSize)
    }

    @Test
    fun `loadPage零pageSize被修正为1`() {
        val items = listOf(makeItem())
        val page = WarehousePager.loadPage(items, 0, 0)
        assertEquals(1, page.pageSize)
    }

    @Test
    fun `loadPage带过滤`() {
        val items = listOf(
            makeItem(itemId = "pill1", itemType = "pill", rarity = 1),
            makeItem(itemId = "herb1", itemType = "herb", rarity = 2),
            makeItem(itemId = "pill2", itemType = "pill", rarity = 3)
        )
        val filter = ItemFilter(types = setOf("pill"))
        val page = WarehousePager.loadPage(items, 0, 10, filter)
        assertEquals(2, page.items.size)
        assertEquals(2, page.totalItems)
    }

    @Test
    fun `loadPage带排序`() {
        val items = listOf(
            makeItem(itemId = "a", itemName = "A", rarity = 1, quantity = 10),
            makeItem(itemId = "c", itemName = "C", rarity = 3, quantity = 30),
            makeItem(itemId = "b", itemName = "B", rarity = 2, quantity = 20)
        )
        val filterAsc = ItemFilter(sortBy = ItemFilter.SortBy.RARITY_ASC)
        val page = WarehousePager.loadPage(items, 0, 10, filterAsc)
        assertEquals(1, page.items[0].rarity)
        assertEquals(2, page.items[1].rarity)
        assertEquals(3, page.items[2].rarity)
    }

    // ============================================================
    // loadAllPages
    // ============================================================

    @Test
    fun `loadAllPages返回所有页`() {
        val items = (1..150).map { makeItem(itemId = "item_$it", rarity = it % 6 + 1) }
        val pages = WarehousePager.loadAllPages(items, 50)
        assertEquals(3, pages.size)
        assertEquals(150, pages.sumOf { it.items.size })
    }

    @Test
    fun `loadAllPages空列表`() {
        val pages = WarehousePager.loadAllPages(emptyList(), 50)
        assertEquals(1, pages.size)
        assertTrue(pages[0].isEmpty)
    }

    @Test
    fun `loadAllPages带过滤`() {
        val items = listOf(
            makeItem(itemId = "pill1", itemType = "pill"),
            makeItem(itemId = "herb1", itemType = "herb"),
            makeItem(itemId = "pill2", itemType = "pill")
        )
        val filter = ItemFilter(types = setOf("pill"))
        val pages = WarehousePager.loadAllPages(items, 10, filter)
        assertEquals(2, pages[0].totalItems)
    }

    // ============================================================
    // search
    // ============================================================

    @Test
    fun `search按名称搜索`() {
        val items = listOf(
            makeItem(itemId = "id1", itemName = "聚气丹"),
            makeItem(itemId = "id2", itemName = "筑基丹"),
            makeItem(itemId = "id3", itemName = "铁剑")
        )
        val result = WarehousePager.search(items, "丹")
        assertEquals(2, result.size)
    }

    @Test
    fun `search按itemId搜索`() {
        val items = listOf(
            makeItem(itemId = "pill_001", itemName = "聚气丹", itemType = "medicine"),
            makeItem(itemId = "herb_001", itemName = "灵草", itemType = "herb")
        )
        val result = WarehousePager.search(items, "pill")
        assertEquals(1, result.size)
    }

    @Test
    fun `search按itemType搜索`() {
        val items = listOf(
            makeItem(itemId = "id1", itemName = "聚气丹", itemType = "pill"),
            makeItem(itemId = "id2", itemName = "灵草", itemType = "herb")
        )
        val result = WarehousePager.search(items, "pill")
        assertEquals(1, result.size)
    }

    @Test
    fun `search空白关键词返回全部受limit限制`() {
        val items = (1..100).map { makeItem(itemId = "item_$it") }
        val result = WarehousePager.search(items, "  ", 10)
        assertEquals(10, result.size)
    }

    @Test
    fun `search限制结果数量`() {
        val items = (1..100).map { makeItem(itemId = "pill_$it", itemName = "丹药$it", itemType = "pill") }
        val result = WarehousePager.search(items, "丹", 5)
        assertTrue(result.size <= 5)
    }

    @Test
    fun `search大小写不敏感`() {
        val items = listOf(
            makeItem(itemId = "PILL_001", itemName = "丹药", itemType = "pill"),
            makeItem(itemId = "herb_001", itemName = "灵草", itemType = "herb")
        )
        val result = WarehousePager.search(items, "pill")
        assertEquals(1, result.size)
    }

    // ============================================================
    // groupByType / groupByRarity
    // ============================================================

    @Test
    fun `groupByType分组`() {
        val items = listOf(
            makeItem(itemId = "p1", itemType = "pill"),
            makeItem(itemId = "p2", itemType = "pill"),
            makeItem(itemId = "h1", itemType = "herb")
        )
        val grouped = WarehousePager.groupByType(items)
        assertEquals(2, grouped["pill"]?.size)
        assertEquals(1, grouped["herb"]?.size)
    }

    @Test
    fun `groupByRarity分组`() {
        val items = listOf(
            makeItem(itemId = "p1", rarity = 1),
            makeItem(itemId = "p2", rarity = 2),
            makeItem(itemId = "p3", rarity = 1)
        )
        val grouped = WarehousePager.groupByRarity(items)
        assertEquals(2, grouped[1]?.size)
        assertEquals(1, grouped[2]?.size)
    }

    // ============================================================
    // calculateStats
    // ============================================================

    @Test
    fun `calculateStats基本统计`() {
        val items = listOf(
            makeItem(itemId = "p1", itemType = "pill", rarity = 1, quantity = 5),
            makeItem(itemId = "p2", itemType = "pill", rarity = 2, quantity = 3),
            makeItem(itemId = "h1", itemType = "herb", rarity = 1, quantity = 10)
        )
        val stats = WarehousePager.calculateStats(items)
        assertEquals(3, stats.totalItems)
        assertEquals(18, stats.totalQuantity)
        assertEquals(2, stats.itemsByType["pill"])
        assertEquals(1, stats.itemsByType["herb"])
        assertEquals(2, stats.itemsByRarity[1])
        assertEquals(1, stats.itemsByRarity[2])
        assertEquals(3, stats.uniqueItemCount)
    }

    @Test
    fun `calculateStats空列表`() {
        val stats = WarehousePager.calculateStats(emptyList())
        assertEquals(0, stats.totalItems)
        assertEquals(0, stats.totalQuantity)
        assertEquals(0, stats.uniqueItemCount)
    }

    // ============================================================
    // 排序测试
    // ============================================================

    @Test
    fun `排序RARITY_DESC`() {
        val items = listOf(
            makeItem(itemId = "a", rarity = 1),
            makeItem(itemId = "b", rarity = 3),
            makeItem(itemId = "c", rarity = 2)
        )
        val filter = ItemFilter(sortBy = ItemFilter.SortBy.RARITY_DESC)
        val page = WarehousePager.loadPage(items, 0, 10, filter)
        assertEquals(3, page.items[0].rarity)
        assertEquals(2, page.items[1].rarity)
        assertEquals(1, page.items[2].rarity)
    }

    @Test
    fun `排序NAME_ASC`() {
        val items = listOf(
            makeItem(itemId = "a", itemName = "C"),
            makeItem(itemId = "b", itemName = "A"),
            makeItem(itemId = "c", itemName = "B")
        )
        val filter = ItemFilter(sortBy = ItemFilter.SortBy.NAME_ASC)
        val page = WarehousePager.loadPage(items, 0, 10, filter)
        assertEquals("A", page.items[0].itemName)
        assertEquals("B", page.items[1].itemName)
        assertEquals("C", page.items[2].itemName)
    }

    @Test
    fun `排序QUANTITY_DESC`() {
        val items = listOf(
            makeItem(itemId = "a", quantity = 5),
            makeItem(itemId = "b", quantity = 20),
            makeItem(itemId = "c", quantity = 10)
        )
        val filter = ItemFilter(sortBy = ItemFilter.SortBy.QUANTITY_DESC)
        val page = WarehousePager.loadPage(items, 0, 10, filter)
        assertEquals(20, page.items[0].quantity)
        assertEquals(10, page.items[1].quantity)
        assertEquals(5, page.items[2].quantity)
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
