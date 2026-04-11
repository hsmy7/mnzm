package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.WorldSect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SectSystemTest {

    private lateinit var system: SectSystem

    @Before
    fun setUp() {
        system = SectSystem()
        system.initialize()
    }

    // ========== 灵石管理测试 ==========

    @Test
    fun `addSpiritStones - 正常增加灵石`() {
        val result = system.addSpiritStones(500)
        assertTrue(result)
        assertEquals(1500, system.spiritStones)
    }

    @Test
    fun `addSpiritStones - 负数不增加`() {
        val result = system.addSpiritStones(-100)
        assertFalse(result)
        assertEquals(1000, system.spiritStones)
    }

    @Test
    fun `addSpiritStones - 零值不增加`() {
        val result = system.addSpiritStones(0)
        assertTrue(result)
        assertEquals(1000, system.spiritStones)
    }

    @Test
    fun `spendSpiritStones - 正常消费灵石`() {
        val result = system.spendSpiritStones(500)
        assertTrue(result)
        assertEquals(500, system.spiritStones)
    }

    @Test
    fun `spendSpiritStones - 灵石不足返回false`() {
        val result = system.spendSpiritStones(1500)
        assertFalse(result)
        assertEquals(1000, system.spiritStones)
    }

    @Test
    fun `spendSpiritStones - 负数不消费`() {
        val result = system.spendSpiritStones(-100)
        assertFalse(result)
        assertEquals(1000, system.spiritStones)
    }

    @Test
    fun `hasSpiritStones - 检查灵石是否足够`() {
        assertTrue(system.hasSpiritStones(500))
        assertTrue(system.hasSpiritStones(1000))
        assertFalse(system.hasSpiritStones(1001))
    }

    // ========== 灵草管理测试 ==========

    @Test
    fun `addSpiritHerbs - 正常增加灵草`() {
        val result = system.addSpiritHerbs(100)
        assertTrue(result)
        assertEquals(100, system.spiritHerbs)
    }

    @Test
    fun `addSpiritHerbs - 负数不增加`() {
        val result = system.addSpiritHerbs(-10)
        assertFalse(result)
        assertEquals(0, system.spiritHerbs)
    }

    @Test
    fun `spendSpiritHerbs - 正常消费灵草`() {
        system.addSpiritHerbs(100)
        val result = system.spendSpiritHerbs(50)
        assertTrue(result)
        assertEquals(50, system.spiritHerbs)
    }

    @Test
    fun `spendSpiritHerbs - 灵草不足返回false`() {
        system.addSpiritHerbs(50)
        val result = system.spendSpiritHerbs(100)
        assertFalse(result)
        assertEquals(50, system.spiritHerbs)
    }

    @Test
    fun `spendSpiritHerbs - 负数不消费`() {
        system.addSpiritHerbs(100)
        val result = system.spendSpiritHerbs(-10)
        assertFalse(result)
        assertEquals(100, system.spiritHerbs)
    }

    @Test
    fun `hasSpiritHerbs - 检查灵草是否足够`() {
        system.addSpiritHerbs(100)
        assertTrue(system.hasSpiritHerbs(50))
        assertTrue(system.hasSpiritHerbs(100))
        assertFalse(system.hasSpiritHerbs(101))
    }

    // ========== 时间推进测试 ==========

    @Test
    fun `advanceTime - 正常推进一天`() {
        val result = system.advanceTime()
        assertEquals(2, result.day)
        assertEquals(1, result.month)
        assertEquals(1, result.year)
        assertFalse(result.monthChanged)
        assertFalse(result.yearChanged)
    }

    @Test
    fun `advanceTime - 30天推进一月`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 1, gameDay = 30))
        val result = system.advanceTime()
        assertEquals(1, result.day)
        assertEquals(2, result.month)
        assertTrue(result.monthChanged)
    }

    @Test
    fun `advanceTime - 12月30天推进一年`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 12, gameDay = 30))
        val result = system.advanceTime()
        assertEquals(1, result.day)
        assertEquals(1, result.month)
        assertEquals(2, result.year)
        assertTrue(result.monthChanged)
        assertTrue(result.yearChanged)
    }

    // ========== 世界地图宗门测试 ==========

    @Test
    fun `updateWorldMapSects - 更新世界宗门`() {
        val sects = listOf(
            WorldSect(id = "s1", name = "宗门1"),
            WorldSect(id = "s2", name = "宗门2")
        )
        system.updateWorldMapSects(sects)
        assertEquals(2, system.worldMapSects.size)
    }

    @Test
    fun `updateWorldSect - 更新指定宗门`() {
        system.updateWorldMapSects(listOf(
            WorldSect(id = "s1", name = "宗门1", level = 1),
            WorldSect(id = "s2", name = "宗门2", level = 2)
        ))
        val result = system.updateWorldSect("s1") { it.copy(level = 3) }
        assertTrue(result)
        assertEquals(3, system.getWorldSectById("s1")?.level)
        assertEquals(2, system.getWorldSectById("s2")?.level)
    }

    @Test
    fun `updateWorldSect - 不存在的宗门返回false`() {
        val result = system.updateWorldSect("nonexistent") { it.copy(level = 3) }
        assertFalse(result)
    }

    @Test
    fun `getWorldSectById - 获取存在的宗门`() {
        system.updateWorldMapSects(listOf(WorldSect(id = "s1", name = "宗门1")))
        val sect = system.getWorldSectById("s1")
        assertNotNull(sect)
        assertEquals("宗门1", sect!!.name)
    }

    @Test
    fun `getWorldSectById - 获取不存在的宗门返回null`() {
        assertNull(system.getWorldSectById("nonexistent"))
    }

    @Test
    fun `getPlayerSect - 获取玩家宗门`() {
        system.updateWorldMapSects(listOf(
            WorldSect(id = "s1", name = "玩家宗门", isPlayerSect = true),
            WorldSect(id = "s2", name = "AI宗门", isPlayerSect = false)
        ))
        val playerSect = system.getPlayerSect()
        assertNotNull(playerSect)
        assertEquals("玩家宗门", playerSect!!.name)
    }

    @Test
    fun `getPlayerSect - 无玩家宗门返回null`() {
        system.updateWorldMapSects(listOf(
            WorldSect(id = "s1", name = "AI宗门", isPlayerSect = false)
        ))
        assertNull(system.getPlayerSect())
    }

    // ========== 宗门关系测试 ==========

    @Test
    fun `updateSectRelations - 更新宗门关系`() {
        val relations = listOf(
            SectRelation(sectId1 = "s1", sectId2 = "s2", favor = 50)
        )
        system.updateSectRelations(relations)
        assertEquals(1, system.sectRelations.size)
    }

    @Test
    fun `getSectRelation - 获取双向关系`() {
        system.updateSectRelations(listOf(
            SectRelation(sectId1 = "s1", sectId2 = "s2", favor = 50)
        ))
        val rel1 = system.getSectRelation("s1", "s2")
        val rel2 = system.getSectRelation("s2", "s1")
        assertNotNull(rel1)
        assertNotNull(rel2)
        assertEquals(50, rel1!!.favor)
        assertEquals(50, rel2!!.favor)
    }

    @Test
    fun `getSectRelation - 不存在的关系返回null`() {
        assertNull(system.getSectRelation("s1", "s2"))
    }

    @Test
    fun `updateSectRelation - 更新指定关系`() {
        system.updateSectRelations(listOf(
            SectRelation(sectId1 = "s1", sectId2 = "s2", favor = 50)
        ))
        val result = system.updateSectRelation("s1", "s2") { it.copy(favor = 80) }
        assertTrue(result)
        assertEquals(80, system.getSectRelation("s1", "s2")!!.favor)
    }

    @Test
    fun `updateSectRelation - 不存在的关系返回false`() {
        val result = system.updateSectRelation("s1", "s2") { it.copy(favor = 80) }
        assertFalse(result)
    }

    // ========== 联盟测试 ==========

    @Test
    fun `addAlliance - 正常添加联盟`() {
        val alliance = Alliance(id = "a1", sectIds = listOf("s1", "s2"))
        val result = system.addAlliance(alliance)
        assertTrue(result)
        assertEquals(1, system.alliances.size)
    }

    @Test
    fun `addAlliance - id为空不能添加`() {
        val alliance = Alliance(id = "", sectIds = listOf("s1", "s2"))
        val result = system.addAlliance(alliance)
        assertFalse(result)
    }

    @Test
    fun `addAlliance - 重复id不能添加`() {
        system.addAlliance(Alliance(id = "a1", sectIds = listOf("s1", "s2")))
        val result = system.addAlliance(Alliance(id = "a1", sectIds = listOf("s3", "s4")))
        assertFalse(result)
    }

    @Test
    fun `removeAlliance - 删除存在的联盟`() {
        system.addAlliance(Alliance(id = "a1", sectIds = listOf("s1", "s2")))
        val result = system.removeAlliance("a1")
        assertTrue(result)
        assertTrue(system.alliances.isEmpty())
    }

    @Test
    fun `removeAlliance - 删除不存在的联盟返回false`() {
        val result = system.removeAlliance("nonexistent")
        assertFalse(result)
    }

    @Test
    fun `getAllianceById - 获取存在的联盟`() {
        system.addAlliance(Alliance(id = "a1", sectIds = listOf("s1", "s2")))
        val alliance = system.getAllianceById("a1")
        assertNotNull(alliance)
        assertEquals(listOf("s1", "s2"), alliance!!.sectIds)
    }

    @Test
    fun `getAllianceById - 获取不存在的联盟返回null`() {
        assertNull(system.getAllianceById("nonexistent"))
    }

    // ========== 兑换码测试 ==========

    @Test
    fun `addUsedRedeemCode - 正常添加兑换码`() {
        val result = system.addUsedRedeemCode("CODE1")
        assertTrue(result)
        assertTrue(system.usedRedeemCodes.contains("CODE1"))
    }

    @Test
    fun `addUsedRedeemCode - 空兑换码不能添加`() {
        val result = system.addUsedRedeemCode("")
        assertFalse(result)
    }

    @Test
    fun `addUsedRedeemCode - 重复兑换码不能添加`() {
        system.addUsedRedeemCode("CODE1")
        val result = system.addUsedRedeemCode("CODE1")
        assertFalse(result)
    }

    // ========== 月俸配置测试 ==========

    @Test
    fun `updateMonthlySalaryEnabled - 正常更新`() {
        val result = system.updateMonthlySalaryEnabled(9, false)
        assertTrue(result)
        assertFalse(system.monthlySalaryEnabled[9]!!)
    }

    @Test
    fun `updateMonthlySalaryEnabled - 无效境界返回false`() {
        assertFalse(system.updateMonthlySalaryEnabled(-1, false))
        assertFalse(system.updateMonthlySalaryEnabled(10, false))
    }

    // ========== 自动存档间隔测试 ==========

    @Test
    fun `setAutoSaveIntervalMonths - 正常设置`() {
        val result = system.setAutoSaveIntervalMonths(6)
        assertTrue(result)
        assertEquals(6, system.autoSaveIntervalMonths)
    }

    @Test
    fun `setAutoSaveIntervalMonths - 无效值返回false`() {
        assertFalse(system.setAutoSaveIntervalMonths(-1))
        assertFalse(system.setAutoSaveIntervalMonths(13))
    }

    // ========== 玩家保护测试 ==========

    @Test
    fun `disablePlayerProtection - 禁用玩家保护`() {
        system.loadGameData(GameData(playerProtectionEnabled = true))
        system.disablePlayerProtection()
        assertFalse(system.isPlayerProtected)
    }

    @Test
    fun `setPlayerAttackedAI - 设置玩家已攻击AI`() {
        system.loadGameData(GameData(playerHasAttackedAI = false))
        system.setPlayerAttackedAI()
        assertTrue(system.gameData.value.playerHasAttackedAI)
    }

    // ========== loadGameData 测试 ==========

    @Test
    fun `loadGameData - 加载游戏数据`() {
        val data = GameData(
            sectName = "测试宗门",
            spiritStones = 5000,
            spiritHerbs = 200,
            gameYear = 10,
            gameMonth = 6,
            gameDay = 15
        )
        system.loadGameData(data)
        assertEquals("测试宗门", system.sectName)
        assertEquals(5000, system.spiritStones)
        assertEquals(200, system.spiritHerbs)
        assertEquals(10, system.gameYear)
        assertEquals(6, system.gameMonth)
        assertEquals(15, system.gameDay)
    }

    // ========== updateGameData 测试 ==========

    @Test
    fun `updateGameData - 更新游戏数据`() {
        system.updateGameData { it.copy(sectName = "新宗门") }
        assertEquals("新宗门", system.sectName)
    }

    // ========== clear 测试 ==========

    @Test
    fun `clear - 清空数据`() = runBlocking {
        system.addSpiritStones(500)
        system.addAlliance(Alliance(id = "a1"))
        system.clear()
        assertEquals(1000, system.spiritStones)
        assertTrue(system.alliances.isEmpty())
    }
}
