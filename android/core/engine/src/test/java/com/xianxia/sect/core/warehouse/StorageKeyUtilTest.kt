package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.model.WarehouseItem
import org.junit.Assert.*
import org.junit.Test

class StorageKeyUtilTest {

    @Test
    fun `generateKey格式正确`() {
        val item = WarehouseItem(
            itemId = "pill_001",
            itemType = "pill",
            rarity = 3,
            itemName = "聚气丹"
        )
        val key = StorageKeyUtil.generateKey(item)
        assertEquals("pill_001:pill:3:聚气丹", key)
    }

    @Test
    fun `generateKey不同物品生成不同key`() {
        val item1 = WarehouseItem(itemId = "id1", itemName = "物品A", itemType = "pill", rarity = 1)
        val item2 = WarehouseItem(itemId = "id2", itemName = "物品B", itemType = "herb", rarity = 2)
        assertNotEquals(StorageKeyUtil.generateKey(item1), StorageKeyUtil.generateKey(item2))
    }

    @Test
    fun `generateKey相同物品生成相同key`() {
        val item1 = WarehouseItem(itemId = "id1", itemName = "物品A", itemType = "pill", rarity = 1)
        val item2 = WarehouseItem(itemId = "id1", itemName = "物品A", itemType = "pill", rarity = 1)
        assertEquals(StorageKeyUtil.generateKey(item1), StorageKeyUtil.generateKey(item2))
    }

    @Test
    fun `generateKey仅数量不同时key相同`() {
        val item1 = WarehouseItem(itemId = "id1", itemName = "物品A", itemType = "pill", rarity = 1, quantity = 1)
        val item2 = WarehouseItem(itemId = "id1", itemName = "物品A", itemType = "pill", rarity = 1, quantity = 99)
        assertEquals(StorageKeyUtil.generateKey(item1), StorageKeyUtil.generateKey(item2))
    }

    @Test
    fun `generateKey稀有度不同时key不同`() {
        val item1 = WarehouseItem(itemId = "id1", itemName = "物品A", itemType = "pill", rarity = 1)
        val item2 = WarehouseItem(itemId = "id1", itemName = "物品A", itemType = "pill", rarity = 2)
        assertNotEquals(StorageKeyUtil.generateKey(item1), StorageKeyUtil.generateKey(item2))
    }

    @Test
    fun `generateKey类型不同时key不同`() {
        val item1 = WarehouseItem(itemId = "id1", itemName = "物品A", itemType = "pill", rarity = 1)
        val item2 = WarehouseItem(itemId = "id1", itemName = "物品A", itemType = "herb", rarity = 1)
        assertNotEquals(StorageKeyUtil.generateKey(item1), StorageKeyUtil.generateKey(item2))
    }

    @Test
    fun `generateKey空字符串字段`() {
        val item = WarehouseItem(itemId = "", itemName = "", itemType = "", rarity = 0)
        val key = StorageKeyUtil.generateKey(item)
        assertEquals("::0:", key)
    }
}
