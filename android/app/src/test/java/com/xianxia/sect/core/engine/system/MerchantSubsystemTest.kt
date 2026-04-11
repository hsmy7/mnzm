package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.MerchantItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MerchantSubsystemTest {

    private lateinit var system: MerchantSubsystem

    @Before
    fun setUp() {
        system = MerchantSubsystem()
        system.initialize()
    }

    private fun createMerchantItem(
        id: String = "item1",
        name: String = "测试物品",
        price: Int = 100
    ): MerchantItem {
        return MerchantItem(
            id = id,
            name = name,
            price = price
        )
    }

    @Test
    fun `loadMerchantData - 加载商店数据`() {
        val items = listOf(createMerchantItem(id = "i1"), createMerchantItem(id = "i2"))
        val playerItems = listOf(createMerchantItem(id = "p1"))
        system.loadMerchantData(items, playerItems, lastRefreshYear = 5, autoTradingEnabled = true)
        assertEquals(2, system.getMerchantItems().size)
        assertEquals(1, system.getPlayerListedItems().size)
        assertEquals(5, system.getLastRefreshYear())
    }

    @Test
    fun `updateMerchantItems - 更新商店物品`() {
        val items = listOf(createMerchantItem(id = "i1"), createMerchantItem(id = "i2"))
        system.updateMerchantItems(items)
        assertEquals(2, system.getMerchantItems().size)
    }

    @Test
    fun `updateMerchantItems - 空列表清空商店`() {
        system.updateMerchantItems(listOf(createMerchantItem()))
        system.updateMerchantItems(emptyList())
        assertEquals(0, system.getMerchantItems().size)
    }

    @Test
    fun `getMerchantItemById - 获取存在的物品`() {
        val item = createMerchantItem(id = "i1", name = "灵石")
        system.updateMerchantItems(listOf(item))
        val found = system.getMerchantItemById("i1")
        assertNotNull(found)
        assertEquals("灵石", found!!.name)
    }

    @Test
    fun `getMerchantItemById - 获取不存在的物品返回null`() {
        assertNull(system.getMerchantItemById("nonexistent"))
    }

    @Test
    fun `removeMerchantItem - 删除存在的物品`() {
        system.updateMerchantItems(listOf(createMerchantItem(id = "i1")))
        val result = system.removeMerchantItem("i1")
        assertTrue(result)
        assertEquals(0, system.getMerchantItems().size)
    }

    @Test
    fun `removeMerchantItem - 删除不存在的物品返回false`() {
        val result = system.removeMerchantItem("nonexistent")
        assertFalse(result)
    }

    @Test
    fun `updateMerchantItem - 更新物品信息`() {
        system.updateMerchantItems(listOf(createMerchantItem(id = "i1", name = "旧名")))
        val result = system.updateMerchantItem("i1") { it.copy(name = "新名") }
        assertTrue(result)
        assertEquals("新名", system.getMerchantItemById("i1")!!.name)
    }

    @Test
    fun `addPlayerListedItem - 添加玩家上架物品`() {
        system.addPlayerListedItem(createMerchantItem(id = "p1"))
        assertEquals(1, system.getPlayerListedItems().size)
    }

    @Test
    fun `removePlayerListedItem - 删除玩家上架物品`() {
        system.addPlayerListedItem(createMerchantItem(id = "p1"))
        val result = system.removePlayerListedItem("p1")
        assertTrue(result)
        assertEquals(0, system.getPlayerListedItems().size)
    }

    @Test
    fun `removePlayerListedItem - 删除不存在的物品返回false`() {
        assertFalse(system.removePlayerListedItem("nonexistent"))
    }

    @Test
    fun `updatePlayerListedItem - 更新玩家上架物品`() {
        system.addPlayerListedItem(createMerchantItem(id = "p1", name = "旧名"))
        val result = system.updatePlayerListedItem("p1") { it.copy(name = "新名") }
        assertTrue(result)
        assertEquals("新名", system.getPlayerListedItems()[0].name)
    }

    @Test
    fun `setLastRefreshYear - 设置刷新年份`() {
        system.setLastRefreshYear(10)
        assertEquals(10, system.getLastRefreshYear())
    }

    @Test
    fun `setAutoTradingEnabled - 设置自动交易`() {
        system.setAutoTradingEnabled(true)
        assertTrue(system.merchantAutoTradingEnabled.value)
        system.setAutoTradingEnabled(false)
        assertFalse(system.merchantAutoTradingEnabled.value)
    }

    @Test
    fun `clear - 清空所有数据`() = runBlocking {
        system.updateMerchantItems(listOf(createMerchantItem()))
        system.addPlayerListedItem(createMerchantItem())
        system.setLastRefreshYear(5)
        system.setAutoTradingEnabled(true)
        system.clear()
        assertEquals(0, system.getMerchantItems().size)
        assertEquals(0, system.getPlayerListedItems().size)
        assertEquals(0, system.getLastRefreshYear())
        assertFalse(system.merchantAutoTradingEnabled.value)
    }

    @Test
    fun `systemName 正确`() {
        assertEquals("MerchantSubsystem", system.systemName)
    }
}
