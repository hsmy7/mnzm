package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.ItemEffect
import org.junit.Test
import org.junit.Assert.*

class BreakthroughPillTest {
    
    @Test
    fun `test ascension pill not used for mahayana breakthrough`() {
        // 创建一个大乘境弟子（realm=2）
        val disciple = Disciple(
            id = "test",
            name = "测试弟子",
            realm = 2, // 大乘境
            realmLayer = 9,
            cultivation = 64000.0,
            maxCultivation = 64000.0
        )
        
        // 创建登仙丹（targetRealm=9）
        val ascensionPillItem = StorageBagItem(
            itemId = "ascensionPill",
            itemType = "pill",
            name = "登仙丹",
            rarity = 6,
            quantity = 1,
            effect = ItemEffect(
                breakthroughChance = 0.30,
                targetRealm = 9 // 对应渡劫→仙人
            )
        )
        
        val discipleWithAscensionPill = disciple.copy(
            storageBagItems = listOf(ascensionPillItem)
        )
        
        // 模拟自动使用突破丹药
        val (updatedDisciple, pillBonus) = GameEngine().autoUseBreakthroughPills(discipleWithAscensionPill)
        
        // 验证没有使用登仙丹
        assertEquals(0.0, pillBonus, 0.001)
        assertEquals(1, updatedDisciple.storageBagItems.size)
        assertEquals("登仙丹", updatedDisciple.storageBagItems[0].name)
    }
    
    @Test
    fun `test tribulation pill used for mahayana breakthrough`() {
        // 创建一个大乘境弟子（realm=2）
        val disciple = Disciple(
            id = "test",
            name = "测试弟子",
            realm = 2, // 大乘境
            realmLayer = 9,
            cultivation = 64000.0,
            maxCultivation = 64000.0
        )
        
        // 创建渡劫丹（targetRealm=8）
        val tribulationPillItem = StorageBagItem(
            itemId = "tribulationPill",
            itemType = "pill",
            name = "渡劫丹",
            rarity = 6,
            quantity = 1,
            effect = ItemEffect(
                breakthroughChance = 0.30,
                targetRealm = 8 // 对应大乘→渡劫
            )
        )
        
        val discipleWithTribulationPill = disciple.copy(
            storageBagItems = listOf(tribulationPillItem)
        )
        
        // 模拟自动使用突破丹药
        val (updatedDisciple, pillBonus) = GameEngine().autoUseBreakthroughPills(discipleWithTribulationPill)
        
        // 验证使用了渡劫丹
        assertEquals(0.30, pillBonus, 0.001)
        assertTrue(updatedDisciple.storageBagItems.isEmpty())
    }
    
    @Test
    fun `test correct pill selected when multiple pills available`() {
        // 创建一个大乘境弟子（realm=2）
        val disciple = Disciple(
            id = "test",
            name = "测试弟子",
            realm = 2, // 大乘境
            realmLayer = 9,
            cultivation = 64000.0,
            maxCultivation = 64000.0
        )
        
        // 创建登仙丹和渡劫丹
        val ascensionPillItem = StorageBagItem(
            itemId = "ascensionPill",
            itemType = "pill",
            name = "登仙丹",
            rarity = 6,
            quantity = 1,
            effect = ItemEffect(
                breakthroughChance = 0.30,
                targetRealm = 9
            )
        )
        
        val tribulationPillItem = StorageBagItem(
            itemId = "tribulationPill",
            itemType = "pill",
            name = "渡劫丹",
            rarity = 6,
            quantity = 1,
            effect = ItemEffect(
                breakthroughChance = 0.30,
                targetRealm = 8
            )
        )
        
        val discipleWithBothPills = disciple.copy(
            storageBagItems = listOf(ascensionPillItem, tribulationPillItem)
        )
        
        // 模拟自动使用突破丹药
        val (updatedDisciple, pillBonus) = GameEngine().autoUseBreakthroughPills(discipleWithBothPills)
        
        // 验证使用了渡劫丹，登仙丹仍然存在
        assertEquals(0.30, pillBonus, 0.001)
        assertEquals(1, updatedDisciple.storageBagItems.size)
        assertEquals("登仙丹", updatedDisciple.storageBagItems[0].name)
    }
    
    @Test
    fun `test no pill used when no suitable pill available`() {
        // 创建一个大乘境弟子（realm=2）
        val disciple = Disciple(
            id = "test",
            name = "测试弟子",
            realm = 2, // 大乘境
            realmLayer = 9,
            cultivation = 64000.0,
            maxCultivation = 64000.0
        )
        
        // 创建一个不适合的丹药（targetRealm=7，对应元婴→化神）
        val不合适PillItem = StorageBagItem(
            itemId = "unsuitablePill",
            itemType = "pill",
            name = "不合适的丹药",
            rarity = 6,
            quantity = 1,
            effect = ItemEffect(
                breakthroughChance = 0.30,
                targetRealm = 7
            )
        )
        
        val discipleWithUnsuitablePill = disciple.copy(
            storageBagItems = listOf(不合适PillItem)
        )
        
        // 模拟自动使用突破丹药
        val (updatedDisciple, pillBonus) = GameEngine().autoUseBreakthroughPills(discipleWithUnsuitablePill)
        
        // 验证没有使用丹药
        assertEquals(0.0, pillBonus, 0.001)
        assertEquals(1, updatedDisciple.storageBagItems.size)
        assertEquals("不合适的丹药", updatedDisciple.storageBagItems[0].name)
    }
}
