package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.SectRelation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DiplomacySubsystemTest {

    private lateinit var system: DiplomacySubsystem

    @Before
    fun setUp() {
        system = DiplomacySubsystem()
        system.initialize()
    }

    private fun createAlliance(
        id: String = "a1",
        sectIds: List<String> = listOf("sect1", "sect2")
    ): Alliance {
        return Alliance(
            id = id,
            sectIds = sectIds
        )
    }

    private fun createSectRelation(
        sectId1: String = "sect1",
        sectId2: String = "sect2",
        favor: Int = 50
    ): SectRelation {
        return SectRelation(
            sectId1 = sectId1,
            sectId2 = sectId2,
            favor = favor
        )
    }

    @Test
    fun `addAlliance - 添加联盟`() {
        system.addAlliance(createAlliance())
        assertEquals(1, system.getAlliances().size)
    }

    @Test
    fun `addAlliance - 添加多个联盟`() {
        system.addAlliance(createAlliance(id = "a1"))
        system.addAlliance(createAlliance(id = "a2"))
        assertEquals(2, system.getAlliances().size)
    }

    @Test
    fun `getAllianceById - 获取存在的联盟`() {
        val alliance = createAlliance(id = "a1", sectIds = listOf("s1", "s2"))
        system.addAlliance(alliance)
        val found = system.getAllianceById("a1")
        assertNotNull(found)
        assertEquals(listOf("s1", "s2"), found!!.sectIds)
    }

    @Test
    fun `getAllianceById - 获取不存在的联盟返回null`() {
        assertNull(system.getAllianceById("nonexistent"))
    }

    @Test
    fun `removeAlliance - 删除存在的联盟`() {
        system.addAlliance(createAlliance(id = "a1"))
        val result = system.removeAlliance("a1")
        assertTrue(result)
        assertEquals(0, system.getAlliances().size)
    }

    @Test
    fun `removeAlliance - 删除不存在的联盟返回false`() {
        assertFalse(system.removeAlliance("nonexistent"))
    }

    @Test
    fun `updateAlliance - 更新联盟信息`() {
        system.addAlliance(createAlliance(id = "a1", sectIds = listOf("s1", "s2")))
        val result = system.updateAlliance("a1") { it.copy(sectIds = listOf("s1", "s2", "s3")) }
        assertTrue(result)
        assertEquals(listOf("s1", "s2", "s3"), system.getAllianceById("a1")!!.sectIds)
    }

    @Test
    fun `updateAlliance - 更新不存在的联盟返回false`() {
        assertFalse(system.updateAlliance("nonexistent") { it.copy(sectIds = listOf("s1")) })
    }

    @Test
    fun `loadDiplomacyData - 加载外交数据`() {
        val alliances = listOf(createAlliance(id = "a1"))
        val relations = listOf(createSectRelation())
        system.loadDiplomacyData(alliances, relations)
        assertEquals(1, system.getAlliances().size)
        assertEquals(1, system.getSectRelations().size)
    }

    @Test
    fun `getRelationBetween - 获取两个宗门的关系`() {
        val relation = createSectRelation(sectId1 = "s1", sectId2 = "s2", favor = 80)
        system.loadDiplomacyData(emptyList(), listOf(relation))
        val found = system.getRelationBetween("s1", "s2")
        assertNotNull(found)
        assertEquals(80, found!!.favor)
    }

    @Test
    fun `getRelationBetween - 反向查询也能找到`() {
        val relation = createSectRelation(sectId1 = "s1", sectId2 = "s2", favor = 80)
        system.loadDiplomacyData(emptyList(), listOf(relation))
        val found = system.getRelationBetween("s2", "s1")
        assertNotNull(found)
        assertEquals(80, found!!.favor)
    }

    @Test
    fun `getRelationBetween - 不存在的关系返回null`() {
        assertNull(system.getRelationBetween("s1", "s2"))
    }

    @Test
    fun `updateSectRelation - 更新宗门关系`() {
        val relation = createSectRelation(sectId1 = "s1", sectId2 = "s2", favor = 50)
        system.loadDiplomacyData(emptyList(), listOf(relation))
        val result = system.updateSectRelation("s1", "s2") { it.copy(favor = 90) }
        assertTrue(result)
        assertEquals(90, system.getRelationBetween("s1", "s2")!!.favor)
    }

    @Test
    fun `isAlly - 同联盟的宗门是盟友`() {
        val alliance = createAlliance(id = "a1", sectIds = listOf("player", "s1"))
        system.addAlliance(alliance)
        assertTrue(system.isAlly("s1", "player"))
    }

    @Test
    fun `isAlly - 不同联盟的宗门不是盟友`() {
        val alliance = createAlliance(id = "a1", sectIds = listOf("player", "s1"))
        system.addAlliance(alliance)
        assertFalse(system.isAlly("s2", "player"))
    }

    @Test
    fun `setSectTradeItems - 设置宗门交易物品`() {
        system.setSectTradeItems("s1", listOf(
            com.xianxia.sect.core.model.MerchantItem(id = "i1", name = "物品1", price = 100)
        ))
        val items = system.getSectTradeItems("s1")
        assertEquals(1, items.size)
        assertEquals("物品1", items[0].name)
    }

    @Test
    fun `getSectTradeItems - 不存在的宗门返回空列表`() {
        val items = system.getSectTradeItems("nonexistent")
        assertTrue(items.isEmpty())
    }

    @Test
    fun `clear - 清空所有数据`() = runBlocking {
        system.addAlliance(createAlliance())
        system.loadDiplomacyData(emptyList(), listOf(createSectRelation()))
        system.setSectTradeItems("s1", listOf(
            com.xianxia.sect.core.model.MerchantItem(id = "i1", name = "物品1", price = 100)
        ))
        system.clear()
        assertEquals(0, system.getAlliances().size)
        assertEquals(0, system.getSectRelations().size)
        assertTrue(system.getSectTradeItems("s1").isEmpty())
    }

    @Test
    fun `systemName 正确`() {
        assertEquals("DiplomacySubsystem", system.systemName)
    }
}
