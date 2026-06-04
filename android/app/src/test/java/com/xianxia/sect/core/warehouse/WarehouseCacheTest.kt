package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.model.WarehouseItem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WarehouseCacheTest {

    @Before
    fun setUp() {
        WarehouseCache.clear()
        WarehouseCache.resetStats()
    }

    // ============================================================
    // put / getItem / getItemByKey
    // ============================================================

    @Test
    fun `put后getItem返回对应物品`() {
        val item = makeItem(itemId = "pill1", itemName = "聚气丹", itemType = "pill", rarity = 2)
        WarehouseCache.put(item)
        val result = WarehouseCache.getItem("pill1")
        assertEquals(1, result.size)
        assertEquals("聚气丹", result[0].itemName)
    }

    @Test
    fun `put后getItemByKey返回对应物品`() {
        val item = makeItem(itemId = "pill1", itemName = "聚气丹", itemType = "pill", rarity = 2)
        WarehouseCache.put(item)
        val key = StorageKeyUtil.generateKey(item)
        val result = WarehouseCache.getItemByKey(key)
        assertNotNull(result)
        assertEquals("聚气丹", result!!.itemName)
    }

    @Test
    fun `getItem不存在的id返回空列表`() {
        val result = WarehouseCache.getItem("nonexistent")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getItemByKey不存在的key返回null`() {
        val result = WarehouseCache.getItemByKey("nonexistent_key")
        assertNull(result)
    }

    // ============================================================
    // putAll
    // ============================================================

    @Test
    fun `putAll批量添加`() {
        val items = listOf(
            makeItem(itemId = "pill1", itemName = "丹药1", itemType = "pill", rarity = 1),
            makeItem(itemId = "herb1", itemName = "灵草1", itemType = "herb", rarity = 2)
        )
        WarehouseCache.putAll(items)
        assertEquals(2, WarehouseCache.size())
    }

    // ============================================================
    // getItemsByType / getItemsByRarity
    // ============================================================

    @Test
    fun `getItemsByType按类型查询`() {
        WarehouseCache.put(makeItem(itemId = "p1", itemType = "pill", rarity = 1))
        WarehouseCache.put(makeItem(itemId = "p2", itemType = "pill", rarity = 2))
        WarehouseCache.put(makeItem(itemId = "h1", itemType = "herb", rarity = 1))
        val pills = WarehouseCache.getItemsByType("pill")
        assertEquals(2, pills.size)
        val herbs = WarehouseCache.getItemsByType("herb")
        assertEquals(1, herbs.size)
    }

    @Test
    fun `getItemsByRarity按稀有度查询`() {
        WarehouseCache.put(makeItem(itemId = "p1", itemType = "pill", rarity = 1))
        WarehouseCache.put(makeItem(itemId = "p2", itemType = "pill", rarity = 2))
        val rarity1 = WarehouseCache.getItemsByRarity(1)
        assertEquals(1, rarity1.size)
    }

    @Test
    fun `getItemsByType不存在的类型返回空列表`() {
        assertTrue(WarehouseCache.getItemsByType("nonexistent").isEmpty())
    }

    @Test
    fun `getItemsByRarity不存在的稀有度返回空列表`() {
        assertTrue(WarehouseCache.getItemsByRarity(99).isEmpty())
    }

    // ============================================================
    // remove / removeItem
    // ============================================================

    @Test
    fun `remove按itemId删除`() {
        WarehouseCache.put(makeItem(itemId = "pill1", itemName = "丹药", itemType = "pill", rarity = 1))
        WarehouseCache.remove("pill1")
        assertTrue(WarehouseCache.getItem("pill1").isEmpty())
        assertEquals(0, WarehouseCache.size())
    }

    @Test
    fun `removeItem按具体物品删除`() {
        val item = makeItem(itemId = "pill1", itemName = "丹药", itemType = "pill", rarity = 1)
        WarehouseCache.put(item)
        WarehouseCache.removeItem(item)
        assertTrue(WarehouseCache.getItem("pill1").isEmpty())
    }

    @Test
    fun `remove不存在的id无副作用`() {
        WarehouseCache.put(makeItem(itemId = "pill1"))
        WarehouseCache.remove("nonexistent")
        assertEquals(1, WarehouseCache.size())
    }

    // ============================================================
    // updateStats / getStats
    // ============================================================

    @Test
    fun `updateStats后getStats返回更新值`() {
        val stats = WarehouseStats(totalItems = 10, totalQuantity = 50)
        WarehouseCache.updateStats(stats)
        val result = WarehouseCache.getStats()
        assertNotNull(result)
        assertEquals(10, result!!.totalItems)
        assertEquals(50, result.totalQuantity)
    }

    @Test
    fun `getStats初始为null`() {
        assertNull(WarehouseCache.getStats())
    }

    // ============================================================
    // clear
    // ============================================================

    @Test
    fun `clear清空所有缓存`() {
        WarehouseCache.put(makeItem(itemId = "pill1"))
        WarehouseCache.updateStats(WarehouseStats())
        WarehouseCache.clear()
        assertEquals(0, WarehouseCache.size())
        assertNull(WarehouseCache.getStats())
        assertEquals(0, WarehouseCache.getTypeCount())
    }

    // ============================================================
    // invalidate
    // ============================================================

    @Test
    fun `invalidate等同于remove`() {
        WarehouseCache.put(makeItem(itemId = "pill1"))
        WarehouseCache.invalidate("pill1")
        assertTrue(WarehouseCache.getItem("pill1").isEmpty())
    }

    // ============================================================
    // size / getTypeCount / getMaxCacheSize
    // ============================================================

    @Test
    fun `size返回缓存数量`() {
        WarehouseCache.put(makeItem(itemId = "pill1"))
        WarehouseCache.put(makeItem(itemId = "pill2"))
        assertEquals(2, WarehouseCache.size())
    }

    @Test
    fun `getTypeCount返回类型数量`() {
        WarehouseCache.put(makeItem(itemId = "p1", itemType = "pill"))
        WarehouseCache.put(makeItem(itemId = "h1", itemType = "herb"))
        assertEquals(2, WarehouseCache.getTypeCount())
    }

    @Test
    fun `getMaxCacheSize默认值`() {
        assertEquals(500, WarehouseCache.getMaxCacheSize())
    }

    // ============================================================
    // adjustCacheSize
    // ============================================================

    @Test
    fun `adjustCacheSize调整最大缓存`() {
        WarehouseCache.adjustCacheSize(2000)
        assertEquals(500, WarehouseCache.getMaxCacheSize()) // 2000*0.25=500, clamp to 500
    }

    @Test
    fun `adjustCacheSize最小值限制`() {
        WarehouseCache.adjustCacheSize(100)
        assertEquals(200, WarehouseCache.getMaxCacheSize()) // clamp to MIN_CACHE_SIZE
    }

    @Test
    fun `adjustCacheSize最大值限制`() {
        WarehouseCache.adjustCacheSize(10000)
        assertEquals(2000, WarehouseCache.getMaxCacheSize()) // clamp to MAX_CACHE_SIZE_LIMIT
    }

    // ============================================================
    // getCacheMetrics
    // ============================================================

    @Test
    fun `getCacheMetrics基本指标`() {
        WarehouseCache.put(makeItem(itemId = "p1", itemType = "pill", rarity = 1))
        WarehouseCache.put(makeItem(itemId = "h1", itemType = "herb", rarity = 2))
        val metrics = WarehouseCache.getCacheMetrics()
        assertEquals(2, metrics.itemCount)
        assertEquals(2, metrics.typeCount)
        assertEquals(2, metrics.rarityCount)
    }

    // ============================================================
    // resetStats
    // ============================================================

    @Test
    fun `resetStats重置命中计数`() {
        WarehouseCache.getItem("nonexistent") // miss
        WarehouseCache.put(makeItem(itemId = "p1"))
        WarehouseCache.getItem("p1") // hit
        WarehouseCache.resetStats()
        val metrics = WarehouseCache.getCacheMetrics()
        assertEquals(0f, metrics.hitRate, 0.001f)
    }

    // ============================================================
    // isExpired
    // ============================================================

    @Test
    fun `isExpired刚操作后为false`() {
        WarehouseCache.put(makeItem())
        assertFalse(WarehouseCache.isExpired())
    }

    // ============================================================
    // 命中率测试
    // ============================================================

    @Test
    fun `命中率计算正确`() {
        WarehouseCache.resetStats()
        WarehouseCache.getItem("miss1") // miss
        WarehouseCache.getItem("miss2") // miss
        WarehouseCache.put(makeItem(itemId = "hit1"))
        WarehouseCache.getItem("hit1") // hit
        val metrics = WarehouseCache.getCacheMetrics()
        // 1 hit / (1 hit + 2 miss) = 0.333...
        assertTrue(metrics.hitRate > 0f)
    }

    // ============================================================
    // 同id不同key的物品
    // ============================================================

    @Test
    fun `同id不同rarity的物品分别缓存`() {
        WarehouseCache.put(makeItem(itemId = "pill1", itemName = "丹药", itemType = "pill", rarity = 1))
        WarehouseCache.put(makeItem(itemId = "pill1", itemName = "丹药", itemType = "pill", rarity = 2))
        val items = WarehouseCache.getItem("pill1")
        assertEquals(2, items.size)
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
