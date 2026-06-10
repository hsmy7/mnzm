package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.model.SectWarehouse
import com.xianxia.sect.core.model.WarehouseItem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WarehouseDiffManagerTest {

    @Before
    fun setUp() {
        WarehouseDiffManager.reset()
    }

    // ============================================================
    // computeDiff
    // ============================================================

    @Test
    fun `computeDiff无快照时全部视为新增`() {
        val warehouse = SectWarehouse(
            items = listOf(makeItem("id1", "物品A", "pill", 1, 5)),
            spiritStones = 100L
        )
        val diff = WarehouseDiffManager.computeDiff(warehouse)
        assertEquals(1, diff.addedItems.size)
        assertEquals("id1", diff.addedItems[0].itemId)
        assertTrue(diff.removedItemIds.isEmpty())
        assertTrue(diff.quantityChanges.isEmpty())
        assertEquals(100L, diff.spiritStonesDelta)
    }

    @Test
    fun `computeDiff检测新增物品`() {
        val last = SectWarehouse(
            items = listOf(makeItem("id1", "物品A", "pill", 1, 5)),
            spiritStones = 100L
        )
        val current = SectWarehouse(
            items = listOf(
                makeItem("id1", "物品A", "pill", 1, 5),
                makeItem("id2", "物品B", "herb", 2, 3)
            ),
            spiritStones = 100L
        )
        WarehouseDiffManager.setSnapshot(last)
        val diff = WarehouseDiffManager.computeDiff(current)
        assertEquals(1, diff.addedItems.size)
        assertEquals("id2", diff.addedItems[0].itemId)
    }

    @Test
    fun `computeDiff检测删除物品`() {
        val last = SectWarehouse(
            items = listOf(
                makeItem("id1", "物品A", "pill", 1, 5),
                makeItem("id2", "物品B", "herb", 2, 3)
            ),
            spiritStones = 100L
        )
        val current = SectWarehouse(
            items = listOf(makeItem("id1", "物品A", "pill", 1, 5)),
            spiritStones = 100L
        )
        WarehouseDiffManager.setSnapshot(last)
        val diff = WarehouseDiffManager.computeDiff(current)
        assertTrue(diff.removedItemIds.contains("id2"))
    }

    @Test
    fun `computeDiff检测数量变化`() {
        val last = SectWarehouse(
            items = listOf(makeItem("id1", "物品A", "pill", 1, 5)),
            spiritStones = 100L
        )
        val current = SectWarehouse(
            items = listOf(makeItem("id1", "物品A", "pill", 1, 8)),
            spiritStones = 100L
        )
        WarehouseDiffManager.setSnapshot(last)
        val diff = WarehouseDiffManager.computeDiff(current)
        assertEquals(mapOf("id1" to 3), diff.quantityChanges)
    }

    @Test
    fun `computeDiff检测灵石变化`() {
        val last = SectWarehouse(items = emptyList(), spiritStones = 100L)
        val current = SectWarehouse(items = emptyList(), spiritStones = 250L)
        WarehouseDiffManager.setSnapshot(last)
        val diff = WarehouseDiffManager.computeDiff(current)
        assertEquals(150L, diff.spiritStonesDelta)
    }

    @Test
    fun `computeDiff无变化时`() {
        val warehouse = SectWarehouse(
            items = listOf(makeItem("id1", "物品A", "pill", 1, 5)),
            spiritStones = 100L
        )
        WarehouseDiffManager.setSnapshot(warehouse)
        val diff = WarehouseDiffManager.computeDiff(warehouse)
        assertFalse(diff.hasChanges)
    }

    // ============================================================
    // applyDiff
    // ============================================================

    @Test
    fun `applyDiff应用新增物品`() {
        val base = SectWarehouse(
            items = listOf(makeItem("id1", "物品A", "pill", 1, 5)),
            spiritStones = 100L
        )
        val diff = WarehouseDiff(addedItems = listOf(makeItem("id2", "物品B", "herb", 2, 3)))
        val result = WarehouseDiffManager.applyDiff(base, diff)
        assertEquals(2, result.items.size)
    }

    @Test
    fun `applyDiff新增同id同type同rarity物品合并数量`() {
        val base = SectWarehouse(
            items = listOf(makeItem("id1", "物品A", "pill", 1, 5)),
            spiritStones = 100L
        )
        val diff = WarehouseDiff(addedItems = listOf(makeItem("id1", "物品A", "pill", 1, 3)))
        val result = WarehouseDiffManager.applyDiff(base, diff)
        assertEquals(1, result.items.size)
        assertEquals(8, result.items[0].quantity)
    }

    @Test
    fun `applyDiff应用删除物品`() {
        val base = SectWarehouse(
            items = listOf(
                makeItem("id1", "物品A", "pill", 1, 5),
                makeItem("id2", "物品B", "herb", 2, 3)
            ),
            spiritStones = 100L
        )
        val diff = WarehouseDiff(removedItemIds = setOf("id2"))
        val result = WarehouseDiffManager.applyDiff(base, diff)
        assertEquals(1, result.items.size)
        assertEquals("id1", result.items[0].itemId)
    }

    @Test
    fun `applyDiff应用数量变化`() {
        val base = SectWarehouse(
            items = listOf(makeItem("id1", "物品A", "pill", 1, 5)),
            spiritStones = 100L
        )
        val diff = WarehouseDiff(quantityChanges = mapOf("id1" to 3))
        val result = WarehouseDiffManager.applyDiff(base, diff)
        assertEquals(8, result.items[0].quantity)
    }

    @Test
    fun `applyDiff数量变化后为0则移除物品`() {
        val base = SectWarehouse(
            items = listOf(makeItem("id1", "物品A", "pill", 1, 5)),
            spiritStones = 100L
        )
        val diff = WarehouseDiff(quantityChanges = mapOf("id1" to -5))
        val result = WarehouseDiffManager.applyDiff(base, diff)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `applyDiff应用灵石变化`() {
        val base = SectWarehouse(items = emptyList(), spiritStones = 100L)
        val diff = WarehouseDiff(spiritStonesDelta = 50L)
        val result = WarehouseDiffManager.applyDiff(base, diff)
        assertEquals(150L, result.spiritStones)
    }

    // ============================================================
    // queueChange / flush / applyPendingChanges
    // ============================================================

    @Test
    fun `queueChange后hasPendingChanges为true`() {
        WarehouseDiffManager.queueAdd(makeItem())
        assertTrue(WarehouseDiffManager.hasPendingChanges())
        assertEquals(1, WarehouseDiffManager.getPendingChangeCount())
    }

    @Test
    fun `flush返回排队的变更`() {
        WarehouseDiffManager.queueAdd(makeItem("id1", "物品A", "pill", 1, 5))
        WarehouseDiffManager.queueSpiritStones(100L)
        val changes = WarehouseDiffManager.flush()
        assertEquals(2, changes.size)
        assertFalse(WarehouseDiffManager.hasPendingChanges())
    }

    @Test
    fun `flush空队列返回空列表`() {
        val changes = WarehouseDiffManager.flush()
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `flush后getLastDiff非空`() {
        WarehouseDiffManager.queueAdd(makeItem("id1", "物品A", "pill", 1, 5))
        WarehouseDiffManager.flush()
        assertNotNull(WarehouseDiffManager.getLastDiff())
    }

    @Test
    fun `applyPendingChanges应用Add变更`() {
        val warehouse = SectWarehouse(items = emptyList(), spiritStones = 0L)
        WarehouseDiffManager.queueAdd(makeItem("id1", "物品A", "pill", 1, 5))
        val result = WarehouseDiffManager.applyPendingChanges(warehouse)
        assertEquals(1, result.items.size)
        assertEquals("id1", result.items[0].itemId)
    }

    @Test
    fun `applyPendingChanges应用Remove变更`() {
        val warehouse = SectWarehouse(
            items = listOf(makeItem("id1", "物品A", "pill", 1, 5)),
            spiritStones = 0L
        )
        WarehouseDiffManager.queueRemove("id1", 5)
        val result = WarehouseDiffManager.applyPendingChanges(warehouse)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `applyPendingChanges应用Update变更`() {
        val warehouse = SectWarehouse(
            items = listOf(makeItem("id1", "物品A", "pill", 1, 5)),
            spiritStones = 0L
        )
        WarehouseDiffManager.queueUpdate("id1", 10)
        val result = WarehouseDiffManager.applyPendingChanges(warehouse)
        assertEquals(10, result.items[0].quantity)
    }

    @Test
    fun `applyPendingChanges应用SpiritStones变更`() {
        val warehouse = SectWarehouse(items = emptyList(), spiritStones = 100L)
        WarehouseDiffManager.queueSpiritStones(50L)
        val result = WarehouseDiffManager.applyPendingChanges(warehouse)
        assertEquals(150L, result.spiritStones)
    }

    @Test
    fun `applyPendingChanges无变更时返回原仓库`() {
        val warehouse = SectWarehouse(items = emptyList(), spiritStones = 100L)
        val result = WarehouseDiffManager.applyPendingChanges(warehouse)
        assertEquals(warehouse, result)
    }

    // ============================================================
    // clearPendingChanges / reset
    // ============================================================

    @Test
    fun `clearPendingChanges清空排队变更`() {
        WarehouseDiffManager.queueAdd(makeItem())
        WarehouseDiffManager.clearPendingChanges()
        assertFalse(WarehouseDiffManager.hasPendingChanges())
        assertEquals(0, WarehouseDiffManager.getPendingChangeCount())
    }

    @Test
    fun `reset清空所有状态`() {
        WarehouseDiffManager.setSnapshot(SectWarehouse())
        WarehouseDiffManager.queueAdd(makeItem())
        WarehouseDiffManager.flush()
        WarehouseDiffManager.reset()
        assertFalse(WarehouseDiffManager.hasPendingChanges())
        assertNull(WarehouseDiffManager.getLastDiff())
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
