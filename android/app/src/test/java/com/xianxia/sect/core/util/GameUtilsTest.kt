package com.xianxia.sect.core.util

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.*
import org.junit.Test

class GameUtilsTest {

    // ========== randomInt 测试 ==========

    @Test
    fun `randomInt - 范围内返回值`() {
        for (i in 1..100) {
            val value = GameUtils.randomInt(1, 10)
            assertTrue(value in 1..10)
        }
    }

    @Test
    fun `randomInt - min等于max返回该值`() {
        assertEquals(5, GameUtils.randomInt(5, 5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `randomInt - min大于max抛异常`() {
        GameUtils.randomInt(10, 1)
    }

    @Test
    fun `randomInt - 负数范围`() {
        for (i in 1..100) {
            val value = GameUtils.randomInt(-10, -1)
            assertTrue(value in -10..-1)
        }
    }

    // ========== randomDouble 测试 ==========

    @Test
    fun `randomDouble - 范围内返回值`() {
        for (i in 1..100) {
            val value = GameUtils.randomDouble(0.0, 1.0)
            assertTrue(value >= 0.0 && value < 1.0)
        }
    }

    @Test
    fun `randomDouble - min等于max返回该值`() {
        assertEquals(5.0, GameUtils.randomDouble(5.0, 5.0), 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `randomDouble - min大于max抛异常`() {
        GameUtils.randomDouble(1.0, 0.0)
    }

    // ========== randomChance 测试 ==========

    @Test
    fun `randomChance - 概率为1时始终返回true`() {
        for (i in 1..50) {
            assertTrue(GameUtils.randomChance(1.0))
        }
    }

    @Test
    fun `randomChance - 概率为0时始终返回false`() {
        for (i in 1..50) {
            assertFalse(GameUtils.randomChance(0.0))
        }
    }

    @Test
    fun `randomChance - 负数概率返回false`() {
        assertFalse(GameUtils.randomChance(-1.0))
    }

    @Test
    fun `randomChance - 大于1概率返回true`() {
        assertTrue(GameUtils.randomChance(2.0))
    }

    @Test
    fun `randomChance - 0点5概率大致正确`() {
        var trueCount = 0
        val iterations = 1000
        for (i in 1..iterations) {
            if (GameUtils.randomChance(0.5)) trueCount++
        }
        assertTrue("50%概率应大致正确: $trueCount/${iterations}", trueCount > 400 && trueCount < 600)
    }

    // ========== coerceIn 测试 ==========

    @Test
    fun `coerceIn - Int值在范围内不变`() {
        assertEquals(5, 5.coerceIn(1, 10))
    }

    @Test
    fun `coerceIn - Int值低于最小值`() {
        assertEquals(1, 0.coerceIn(1, 10))
    }

    @Test
    fun `coerceIn - Int值超过最大值`() {
        assertEquals(10, 15.coerceIn(1, 10))
    }

    @Test
    fun `coerceIn - Double值在范围内不变`() {
        assertEquals(5.0, 5.0.coerceIn(1.0, 10.0), 0.001)
    }

    @Test
    fun `coerceIn - Double值低于最小值`() {
        assertEquals(1.0, 0.0.coerceIn(1.0, 10.0), 0.001)
    }

    @Test
    fun `coerceIn - Double值超过最大值`() {
        assertEquals(10.0, 15.0.coerceIn(1.0, 10.0), 0.001)
    }

    @Test
    fun `coerceIn - Long值在范围内不变`() {
        assertEquals(5L, 5L.coerceIn(1L, 10L))
    }

    // ========== formatNumber 测试 ==========

    @Test
    fun `formatNumber - 亿级数字`() {
        val result = GameUtils.formatNumber(1_500_000_000L)
        assertTrue(result.contains("亿"))
    }

    @Test
    fun `formatNumber - 万级数字`() {
        val result = GameUtils.formatNumber(50_000L)
        assertTrue(result.contains("万"))
    }

    @Test
    fun `formatNumber - 普通数字`() {
        val result = GameUtils.formatNumber(999L)
        assertEquals("999", result)
    }

    @Test
    fun `formatNumber - 零`() {
        val result = GameUtils.formatNumber(0L)
        assertEquals("0", result)
    }

    // ========== formatPercent 测试 ==========

    @Test
    fun `formatPercent - 正常百分比`() {
        val result = GameUtils.formatPercent(0.5)
        assertTrue(result.contains("50"))
        assertTrue(result.contains("%"))
    }

    @Test
    fun `formatPercent - 零值`() {
        val result = GameUtils.formatPercent(0.0)
        assertTrue(result.contains("0"))
    }

    @Test
    fun `formatPercent - 满值`() {
        val result = GameUtils.formatPercent(1.0)
        assertTrue(result.contains("100"))
    }

    // ========== formatTime 测试 ==========

    @Test
    fun `formatTime - 小时级别`() {
        val result = GameUtils.formatTime(3661)
        assertTrue(result.contains("1时"))
    }

    @Test
    fun `formatTime - 分钟级别`() {
        val result = GameUtils.formatTime(61)
        assertTrue(result.contains("1分"))
    }

    @Test
    fun `formatTime - 秒级别`() {
        val result = GameUtils.formatTime(30)
        assertTrue(result.contains("30秒"))
    }

    @Test
    fun `formatTime - 零秒`() {
        val result = GameUtils.formatTime(0)
        assertTrue(result.contains("0秒"))
    }

    // ========== calculateAverageRealm 测试 ==========

    @Test
    fun `calculateAverageRealm - 空列表返回9`() {
        assertEquals(9.0, GameUtils.calculateAverageRealm(emptyList()), 0.01)
    }

    @Test
    fun `calculateAverageRealm - 单个弟子`() {
        val result = GameUtils.calculateAverageRealm(listOf(Pair(9, 1)))
        assertEquals(8.9, result, 0.01)
    }

    @Test
    fun `calculateAverageRealm - 多个弟子`() {
        val result = GameUtils.calculateAverageRealm(listOf(
            Pair(9, 1),
            Pair(7, 5)
        ))
        val expected = (8.9 + 6.5) / 2.0
        assertEquals(expected, result, 0.01)
    }

    @Test
    fun `calculateAverageRealm - null层级视为1`() {
        val result = GameUtils.calculateAverageRealm(listOf(Pair(9, null)))
        assertEquals(8.9, result, 0.01)
    }

    // ========== calculateTeamAverageRealm 测试 ==========

    @Test
    fun `calculateTeamAverageRealm - 空列表返回9`() {
        assertEquals(9.0, GameUtils.calculateTeamAverageRealm(
            emptyList<DiscipleStub>(),
            { it.realm },
            { it.layer }
        ), 0.01)
    }

    @Test
    fun `calculateTeamAverageRealm - 正常计算`() {
        val disciples = listOf(
            DiscipleStub(9, 1),
            DiscipleStub(7, 5)
        )
        val result = GameUtils.calculateTeamAverageRealm(
            disciples,
            { it.realm },
            { it.layer }
        )
        val expected = (8.9 + 6.5) / 2.0
        assertEquals(expected, result, 0.01)
    }

    // ========== calculateBeastRealm 测试 ==========

    @Test
    fun `calculateBeastRealm - 空列表返回9`() {
        assertEquals(9, GameUtils.calculateBeastRealm(
            emptyList<DiscipleStub>(),
            { it.realm },
            { it.layer }
        ))
    }

    @Test
    fun `calculateBeastRealm - 全练气弟子应返回9而非8`() {
        val disciples = listOf(
            DiscipleStub(9, 9),
            DiscipleStub(9, 5),
            DiscipleStub(9, 1)
        )
        val result = GameUtils.calculateBeastRealm(
            disciples,
            { it.realm },
            { it.layer }
        )
        assertEquals(9, result)
    }

    @Test
    fun `calculateBeastRealm - 练气9层单弟子不应映射到筑基`() {
        val disciples = listOf(DiscipleStub(9, 9))
        val result = GameUtils.calculateBeastRealm(
            disciples,
            { it.realm },
            { it.layer }
        )
        assertEquals(9, result)
    }

    @Test
    fun `calculateBeastRealm - 筑基弟子应返回8`() {
        val disciples = listOf(DiscipleStub(8, 5))
        val result = GameUtils.calculateBeastRealm(
            disciples,
            { it.realm },
            { it.layer }
        )
        assertEquals(8, result)
    }

    @Test
    fun `calculateBeastRealm - 混合境界不超过队伍最低境界`() {
        val disciples = listOf(
            DiscipleStub(9, 5),
            DiscipleStub(8, 5)
        )
        val result = GameUtils.calculateBeastRealm(
            disciples,
            { it.realm },
            { it.layer }
        )
        assertTrue(result <= 9)
        assertTrue(result >= 8)
    }

    @Test
    fun `calculateBeastRealm - null层级视为1`() {
        val disciples = listOf(DiscipleStub(9, 1))
        val result = GameUtils.calculateBeastRealm(
            disciples,
            { it.realm },
            { _ -> null }
        )
        assertEquals(9, result)
    }

    // ========== calculateBeastRealmFromAvg 测试 ==========

    @Test
    fun `calculateBeastRealmFromAvg - 练气9层平均值8点1应映射到9`() {
        assertEquals(9, GameUtils.calculateBeastRealmFromAvg(8.1))
    }

    @Test
    fun `calculateBeastRealmFromAvg - 练气1层平均值8点9应映射到9`() {
        assertEquals(9, GameUtils.calculateBeastRealmFromAvg(8.9))
    }

    @Test
    fun `calculateBeastRealmFromAvg - 筑基1层平均值7点9应映射到8`() {
        assertEquals(8, GameUtils.calculateBeastRealmFromAvg(7.9))
    }

    @Test
    fun `calculateBeastRealmFromAvg - 不超过teamMaxRealm`() {
        assertEquals(9, GameUtils.calculateBeastRealmFromAvg(8.5, teamMaxRealm = 9))
        assertEquals(8, GameUtils.calculateBeastRealmFromAvg(7.5, teamMaxRealm = 8))
    }

    private data class DiscipleStub(val realm: Int, val layer: Int)

    // ========== randomFrom 测试 ==========

    @Test
    fun `randomFrom - 空列表返回null`() {
        assertNull(GameUtils.randomFrom(emptyList<String>()))
    }

    @Test
    fun `randomFrom - 单元素列表返回该元素`() {
        assertEquals("only", GameUtils.randomFrom(listOf("only")))
    }

    @Test
    fun `randomFrom - 多元素列表返回列表中的元素`() {
        val list = listOf("a", "b", "c")
        for (i in 1..50) {
            assertTrue(list.contains(GameUtils.randomFrom(list)))
        }
    }

    // ========== randomFromWeighted 测试 ==========

    @Test
    fun `randomFromWeighted - 空列表返回null`() {
        assertNull(GameUtils.randomFromWeighted(emptyList()))
    }

    @Test
    fun `randomFromWeighted - 零权重返回null`() {
        assertNull(GameUtils.randomFromWeighted(listOf(Pair("a", 0.0))))
    }

    @Test
    fun `randomFromWeighted - 负权重返回null`() {
        assertNull(GameUtils.randomFromWeighted(listOf(Pair("a", -1.0))))
    }

    @Test
    fun `randomFromWeighted - 单元素返回该元素`() {
        assertEquals("a", GameUtils.randomFromWeighted(listOf(Pair("a", 1.0))))
    }

    @Test
    fun `randomFromWeighted - 高权重元素更频繁`() {
        var highCount = 0
        var lowCount = 0
        for (i in 1..1000) {
            val result = GameUtils.randomFromWeighted(listOf(
                Pair("low", 1.0),
                Pair("high", 99.0)
            ))
            if (result == "high") highCount++ else lowCount++
        }
        assertTrue("高权重应更频繁: $highCount vs $lowCount", highCount > lowCount * 5)
    }

    // ========== shuffle 测试 ==========

    @Test
    fun `shuffle - 返回相同元素`() {
        val original = listOf(1, 2, 3, 4, 5)
        val shuffled = GameUtils.shuffle(original)
        assertEquals(original.sorted(), shuffled.sorted())
    }

    @Test
    fun `shuffle - 空列表返回空列表`() {
        assertTrue(GameUtils.shuffle(emptyList<Int>()).isEmpty())
    }

    @Test
    fun `shuffle - 不修改原列表`() {
        val original = listOf(1, 2, 3)
        GameUtils.shuffle(original)
        assertEquals(listOf(1, 2, 3), original)
    }

    // ========== calculateBreakthroughChance 测试 ==========

    @Test
    fun `calculateBreakthroughChance - 基础概率`() {
        val result = GameUtils.calculateBreakthroughChance(0.5)
        assertEquals(0.5, result, 0.01)
    }

    @Test
    fun `calculateBreakthroughChance - 加成后概率`() {
        val result = GameUtils.calculateBreakthroughChance(0.3, pillBonus = 0.2, talentBonus = 0.1)
        assertEquals(0.6, result, 0.01)
    }

    @Test
    fun `calculateBreakthroughChance - 上限为1`() {
        val result = GameUtils.calculateBreakthroughChance(0.8, pillBonus = 0.5)
        assertEquals(1.0, result, 0.01)
    }

    @Test
    fun `calculateBreakthroughChance - 下限为0`() {
        val result = GameUtils.calculateBreakthroughChance(-0.5)
        assertEquals(0.0, result, 0.01)
    }

    // ========== calculateDamage 测试 ==========

    @Test
    fun `calculateDamage - 正常伤害计算`() {
        val damage = GameUtils.calculateDamage(100, 20)
        assertTrue(damage >= 1)
    }

    @Test
    fun `calculateDamage - 暴击伤害翻倍`() {
        val normalDamage = GameUtils.calculateDamage(100, 20, isCrit = false)
        val critDamage = GameUtils.calculateDamage(100, 20, isCrit = true)
        assertTrue(critDamage > normalDamage)
    }

    @Test
    fun `calculateDamage - 最低伤害为1`() {
        val damage = GameUtils.calculateDamage(1, 9999)
        assertEquals(1, damage)
    }

    @Test
    fun `calculateDamage - 技能倍率影响伤害`() {
        val normalDamage = GameUtils.calculateDamage(100, 20, multiplier = 1.0)
        val boostedDamage = GameUtils.calculateDamage(100, 20, multiplier = 2.0)
        assertTrue(boostedDamage > normalDamage)
    }

    // ========== calculateExperienceForLevel 测试 ==========

    @Test
    fun `calculateExperienceForLevel - 等级1返回基础经验`() {
        assertEquals(100, GameUtils.calculateExperienceForLevel(1))
    }

    @Test
    fun `calculateExperienceForLevel - 等级2返回1_5倍`() {
        assertEquals(150, GameUtils.calculateExperienceForLevel(2))
    }

    @Test
    fun `calculateExperienceForLevel - 等级0返回0`() {
        assertEquals(0, GameUtils.calculateExperienceForLevel(0))
    }

    @Test
    fun `calculateExperienceForLevel - 负等级返回0`() {
        assertEquals(0, GameUtils.calculateExperienceForLevel(-1))
    }

    @Test
    fun `calculateExperienceForLevel - 基础经验0返回0`() {
        assertEquals(0, GameUtils.calculateExperienceForLevel(5, baseExp = 0))
    }

    // ========== applyPriceFluctuation 测试 ==========

    @Test
    fun `applyPriceFluctuation - 价格在正负20百分比范围内`() {
        val basePrice = 100
        for (i in 1..100) {
            val result = GameUtils.applyPriceFluctuation(basePrice)
            assertTrue("价格波动应在80-120之间: $result", result >= 80 && result <= 120)
        }
    }

    @Test
    fun `applyPriceFluctuation - 最低价格为1`() {
        val result = GameUtils.applyPriceFluctuation(1)
        assertTrue(result >= 1)
    }

    // ========== generateRandomName 测试 ==========

    @Test
    fun `generateRandomName - 返回非空字符串`() {
        val name = GameUtils.generateRandomName()
        assertTrue(name.isNotBlank())
    }

    @Test
    fun `generateRandomName - 不同风格生成名字`() {
        val common = GameUtils.generateRandomName(GameUtils.NameStyle.COMMON)
        val xianxia = GameUtils.generateRandomName(GameUtils.NameStyle.XIANXIA)
        val full = GameUtils.generateRandomName(GameUtils.NameStyle.FULL)
        assertTrue(common.isNotBlank())
        assertTrue(xianxia.isNotBlank())
        assertTrue(full.isNotBlank())
    }

    @Test
    fun `generateRandomName - 多次生成不总是相同`() {
        val names = (1..20).map { GameUtils.generateRandomName() }.toSet()
        assertTrue("应生成多种不同名字", names.size > 1)
    }

    // ========== generateRandomSpiritRoot 测试 ==========

    @Test
    fun `generateRandomSpiritRoot - 返回有效灵根类型`() {
        val validTypes = listOf("metal", "wood", "water", "fire", "earth")
        for (i in 1..50) {
            val root = GameUtils.generateRandomSpiritRoot()
            assertTrue(validTypes.contains(root.type))
            assertTrue(root.quality in 1..10)
        }
    }

    // ========== generateId 测试 ==========

    @Test
    fun `generateId - 返回非空字符串`() {
        assertTrue(GameUtils.generateId().isNotBlank())
    }

    @Test
    fun `generateId - 每次生成不同`() {
        val id1 = GameUtils.generateId()
        val id2 = GameUtils.generateId()
        assertNotEquals(id1, id2)
    }

    @Test
    fun `generateShortId - 返回8字符`() {
        assertEquals(8, GameUtils.generateShortId().length)
    }

    // ========== String 标准库扩展测试 ==========

    @Test
    fun `isNullOrEmpty - null返回true`() {
        assertTrue((null as String?).isNullOrEmpty())
    }

    @Test
    fun `isNullOrEmpty - 空字符串返回true`() {
        assertTrue("".isNullOrEmpty())
    }

    @Test
    fun `isNullOrEmpty - 非空字符串返回false`() {
        assertFalse("test".isNullOrEmpty())
    }

    @Test
    fun `StringUtils truncate - 短字符串不变`() {
        assertEquals("hello", StringUtils.truncate("hello", 10))
    }

    @Test
    fun `StringUtils truncate - 长字符串截断`() {
        val result = StringUtils.truncate("hello world", 8)
        assertTrue(result.length <= 8)
        assertTrue(result.endsWith("..."))
    }

    @Test
    fun `StringUtils truncate - maxLength为0返回空`() {
        assertEquals("", StringUtils.truncate("hello", 0))
    }

    @Test
    fun `padStart - 左填充`() {
        assertEquals("  hello", "hello".padStart(7))
    }

    @Test
    fun `padEnd - 右填充`() {
        assertEquals("hello  ", "hello".padEnd(7))
    }

    // ========== CollectionUtils 测试 ==========

    @Test
    fun `CollectionUtils getOrDefault - 有效索引`() {
        val list = listOf(1, 2, 3)
        with(CollectionUtils) {
            assertEquals(2, list.getOrDefault(1, 0))
        }
    }

    @Test
    fun `CollectionUtils getOrDefault - 无效索引返回默认值`() {
        val list = listOf(1, 2, 3)
        with(CollectionUtils) {
            assertEquals(0, list.getOrDefault(5, 0))
        }
    }

    @Test
    fun `CollectionUtils removeFirst - 删除匹配元素`() {
        val list = mutableListOf(1, 2, 3, 2)
        with(CollectionUtils) {
            assertTrue(list.removeFirst { it == 2 })
        }
        assertEquals(listOf(1, 3, 2), list)
    }

    @Test
    fun `CollectionUtils removeFirst - 无匹配返回false`() {
        val list = mutableListOf(1, 2, 3)
        with(CollectionUtils) {
            assertFalse(list.removeFirst { it == 5 })
        }
        assertEquals(listOf(1, 2, 3), list)
    }
}
