package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.model.WarehouseItem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WarehouseCompressorTest {

    @Before
    fun setUp() {
        WarehouseCompressor.clearPools()
    }

    // ============================================================
    // compress
    // ============================================================

    @Test
    fun `compress基本压缩`() {
        val item = WarehouseItem(
            itemId = "pill_001",
            itemName = "聚气丹",
            itemType = "pill",
            rarity = 2,
            quantity = 10
        )
        val compact = WarehouseCompressor.compress(item)
        assertEquals("pill_001", WarehouseCompressor.getString(compact.id))
        assertEquals("聚气丹", WarehouseCompressor.getName(compact.nameId))
        assertEquals(CompactWarehouseItem.TYPE_PILL, compact.type)
        assertEquals(2, compact.rarity.toInt())
        assertEquals(10, compact.quantity.toInt())
    }

    @Test
    fun `compress各类型映射`() {
        val typeMap = mapOf(
            "pill" to CompactWarehouseItem.TYPE_PILL,
            "equipment" to CompactWarehouseItem.TYPE_EQUIPMENT,
            "manual" to CompactWarehouseItem.TYPE_MANUAL,
            "material" to CompactWarehouseItem.TYPE_MATERIAL,
            "herb" to CompactWarehouseItem.TYPE_HERB,
            "seed" to CompactWarehouseItem.TYPE_SEED
        )
        typeMap.forEach { (type, expectedByte) ->
            WarehouseCompressor.clearPools()
            val item = WarehouseItem(itemId = "id_$type", itemName = "name_$type", itemType = type, rarity = 1, quantity = 1)
            val compact = WarehouseCompressor.compress(item)
            assertEquals("类型 $type 映射错误", expectedByte, compact.type)
        }
    }

    @Test
    fun `compress未知类型映射为0`() {
        val item = WarehouseItem(itemId = "id1", itemName = "name1", itemType = "unknown_type", rarity = 1, quantity = 1)
        val compact = WarehouseCompressor.compress(item)
        assertEquals(0, compact.type.toInt())
    }

    @Test
    fun `compress数量超过Short-MAX-VALUE被截断`() {
        val item = WarehouseItem(itemId = "id1", itemName = "name1", itemType = "pill", rarity = 1, quantity = 100000)
        val compact = WarehouseCompressor.compress(item)
        assertEquals(Short.MAX_VALUE.toInt(), compact.quantity.toInt())
    }

    @Test
    fun `compress数量为负被修正为0`() {
        val item = WarehouseItem(itemId = "id1", itemName = "name1", itemType = "pill", rarity = 1, quantity = -5)
        val compact = WarehouseCompressor.compress(item)
        assertEquals(0, compact.quantity.toInt())
    }

    // ============================================================
    // compressAll / decompressAll
    // ============================================================

    @Test
    fun `compressAll批量压缩`() {
        val items = listOf(
            makeItem(itemId = "id1", itemName = "物品A", itemType = "pill", rarity = 1, quantity = 5),
            makeItem(itemId = "id2", itemName = "物品B", itemType = "herb", rarity = 2, quantity = 10)
        )
        val compacts = WarehouseCompressor.compressAll(items)
        assertEquals(2, compacts.size)
    }

    @Test
    fun `decompressAll批量解压`() {
        val items = listOf(
            makeItem(itemId = "id1", itemName = "物品A", itemType = "pill", rarity = 2, quantity = 5),
            makeItem(itemId = "id2", itemName = "物品B", itemType = "herb", rarity = 3, quantity = 10)
        )
        val compacts = WarehouseCompressor.compressAll(items)
        val decompressed = WarehouseCompressor.decompressAll(compacts)
        assertEquals(2, decompressed.size)
        assertEquals("id1", decompressed[0].itemId)
        assertEquals("id2", decompressed[1].itemId)
    }

    // ============================================================
    // compress / decompress 往返
    // ============================================================

    @Test
    fun `压缩解压往返一致性`() {
        val item = WarehouseItem(
            itemId = "pill_001",
            itemName = "聚气丹",
            itemType = "pill",
            rarity = 3,
            quantity = 50
        )
        val compact = WarehouseCompressor.compress(item)
        val restored = WarehouseCompressor.decompress(compact)
        assertEquals(item.itemId, restored.itemId)
        assertEquals(item.itemName, restored.itemName)
        assertEquals(item.itemType, restored.itemType)
        assertEquals(item.rarity, restored.rarity)
        assertEquals(item.quantity, restored.quantity)
    }

    @Test
    fun `多个物品压缩解压往返`() {
        val items = listOf(
            makeItem(itemId = "id1", itemName = "丹药", itemType = "pill", rarity = 2, quantity = 5),
            makeItem(itemId = "id2", itemName = "灵草", itemType = "herb", rarity = 1, quantity = 20),
            makeItem(itemId = "id3", itemName = "功法", itemType = "manual", rarity = 4, quantity = 1)
        )
        val compacts = WarehouseCompressor.compressAll(items)
        val restored = WarehouseCompressor.decompressAll(compacts)
        items.forEachIndexed { i, original ->
            assertEquals(original.itemId, restored[i].itemId)
            assertEquals(original.itemName, restored[i].itemName)
            assertEquals(original.itemType, restored[i].itemType)
            assertEquals(original.rarity, restored[i].rarity)
            assertEquals(original.quantity, restored[i].quantity)
        }
    }

    // ============================================================
    // compressToBytes / decompressFromBytes
    // ============================================================

    @Test
    fun `字节序列化往返一致性`() {
        val items = listOf(
            makeItem(itemId = "id1", itemName = "丹药", itemType = "pill", rarity = 2, quantity = 5),
            makeItem(itemId = "id2", itemName = "灵草", itemType = "herb", rarity = 1, quantity = 20)
        )
        WarehouseCompressor.compressAll(items) // 确保字符串池已建立
        val bytes = WarehouseCompressor.compressToBytes(items)
        assertTrue(bytes.isNotEmpty())
        val restored = WarehouseCompressor.decompressFromBytes(bytes)
        assertEquals(items.size, restored.size)
        items.forEachIndexed { i, original ->
            assertEquals(original.itemId, restored[i].itemId)
            assertEquals(original.itemName, restored[i].itemName)
            assertEquals(original.itemType, restored[i].itemType)
            assertEquals(original.rarity, restored[i].rarity)
            assertEquals(original.quantity, restored[i].quantity)
        }
    }

    @Test
    fun `字节序列化空列表`() {
        val bytes = WarehouseCompressor.compressToBytes(emptyList())
        val restored = WarehouseCompressor.decompressFromBytes(bytes)
        assertTrue(restored.isEmpty())
    }

    // ============================================================
    // getStringId / getNameId
    // ============================================================

    @Test
    fun `getStringId相同字符串返回相同id`() {
        val id1 = WarehouseCompressor.getStringId("test_string")
        val id2 = WarehouseCompressor.getStringId("test_string")
        assertEquals(id1, id2)
    }

    @Test
    fun `getStringId不同字符串返回不同id`() {
        val id1 = WarehouseCompressor.getStringId("string_a")
        val id2 = WarehouseCompressor.getStringId("string_b")
        assertNotEquals(id1, id2)
    }

    @Test
    fun `getNameId相同名称返回相同id`() {
        val id1 = WarehouseCompressor.getNameId("聚气丹")
        val id2 = WarehouseCompressor.getNameId("聚气丹")
        assertEquals(id1, id2)
    }

    @Test
    fun `getNameId不同名称返回不同id`() {
        val id1 = WarehouseCompressor.getNameId("聚气丹")
        val id2 = WarehouseCompressor.getNameId("筑基丹")
        assertNotEquals(id1, id2)
    }

    // ============================================================
    // getString / getName
    // ============================================================

    @Test
    fun `getString通过id反查字符串`() {
        val id = WarehouseCompressor.getStringId("test_string")
        assertEquals("test_string", WarehouseCompressor.getString(id))
    }

    @Test
    fun `getName通过id反查名称`() {
        val id = WarehouseCompressor.getNameId("聚气丹")
        assertEquals("聚气丹", WarehouseCompressor.getName(id))
    }

    @Test
    fun `getString不存在的id返回null`() {
        assertNull(WarehouseCompressor.getString(99999))
    }

    @Test
    fun `getName不存在的id返回null`() {
        assertNull(WarehouseCompressor.getName(99999))
    }

    // ============================================================
    // clearPools
    // ============================================================

    @Test
    fun `clearPools清空字符串池`() {
        WarehouseCompressor.getStringId("test")
        WarehouseCompressor.getNameId("名称")
        WarehouseCompressor.clearPools()
        val stats = WarehouseCompressor.getPoolStats()
        assertEquals(0, stats.stringPoolSize)
        assertEquals(0, stats.namePoolSize)
    }

    // ============================================================
    // getPoolStats
    // ============================================================

    @Test
    fun `getPoolStats返回正确统计`() {
        WarehouseCompressor.getStringId("a")
        WarehouseCompressor.getStringId("b")
        WarehouseCompressor.getNameId("名称A")
        val stats = WarehouseCompressor.getPoolStats()
        assertEquals(2, stats.stringPoolSize)
        assertEquals(1, stats.namePoolSize)
        assertEquals(2, stats.uniqueStrings)
        assertEquals(1, stats.uniqueNames)
    }

    // ============================================================
    // estimateCompressionRatio
    // ============================================================

    @Test
    fun `estimateCompressionRatio空列表返回1`() {
        assertEquals(1f, WarehouseCompressor.estimateCompressionRatio(emptyList()), 0.001f)
    }

    @Test
    fun `estimateCompressionRatio非空列表返回合理值`() {
        val items = listOf(
            makeItem(itemId = "pill_001", itemName = "聚气丹", itemType = "pill", rarity = 1, quantity = 5)
        )
        val ratio = WarehouseCompressor.estimateCompressionRatio(items)
        assertTrue("压缩比应大于0", ratio > 0f)
        assertTrue("压缩比应小于等于1", ratio <= 1f)
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
