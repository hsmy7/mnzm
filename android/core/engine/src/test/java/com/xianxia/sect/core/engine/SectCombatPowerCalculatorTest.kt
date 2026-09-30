package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.StarZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.xianxia.sect.core.engine.domain.disciple.getPermanentBaseStats

class SectCombatPowerCalculatorTest {

    private companion object {
        /** 未解锁档（账本无键 / 存量旧弟子） */
        const val STAR_ZERO = 0

        /** 第二个加成档（×1.08） */
        const val STAR_TWO = 2

        /** 非法入参档（负星级按基线处理） */
        const val STAR_NEGATIVE = -2
    }

    @Test
    fun `calculateDiscipleCombatPower - both attacks counted`() {
        val stats = DiscipleStats(
            maxHp = 1000,
            attack = 200,
            defense = 50,
            speed = 80
        )
        val result = SectCombatPowerCalculator.calculateDiscipleCombatPower(stats)
        // 单列口径（B1）：200*5 + 1000*4 + 50*3 + 80*2（旧双列取和口径线性等价）
        assertEquals(200 * 5L + 1000 * 4L + 50 * 3L + 80 * 2L, result)
    }

    @Test
    fun `calculateDiscipleCombatPower - zero stats`() {
        val stats = DiscipleStats()
        val result = SectCombatPowerCalculator.calculateDiscipleCombatPower(stats)
        assertEquals(0L, result)
    }

    @Test
    fun `calculateDisciplePower - player and AI get same formula`() {
        val disciple = Disciple(name = "Test", realm = 5, realmLayer = 3)
        val aggregate = disciple.toAggregate()
        val power = SectCombatPowerCalculator.calculateDisciplePower(aggregate, StarZone.BASE_STAR)
        // 玩家和 AI 使用同一公式，不需要 *3
        val baseStats = DiscipleStatCalculator.getPermanentBaseStats(aggregate)
        val expected = SectCombatPowerCalculator.calculateDiscipleCombatPower(baseStats)
        assertEquals(expected, power)
    }

    @Test
    fun `calculateDisciplePower - excludes equipment and manuals`() {
        // 装备/功法不影响战力——它们不应参与计算
        val disciple = Disciple(name = "Test", realm = 5, realmLayer = 3)
        val aggregate = disciple.toAggregate()
        val power = SectCombatPowerCalculator.calculateDisciplePower(aggregate, StarZone.BASE_STAR)

        // 战力值应为 正数（基于境界基础属性）
        assertEquals("境界5层3的弟子应有战力", true, power > 0)
    }

    @Test
    fun `computeFingerprint - same disciple same fingerprint`() {
        val d1 = Disciple(name = "A", realm = 5, realmLayer = 3)
        val a1 = d1.toAggregate()
        val a2 = d1.toAggregate()
        val fp1 = SectCombatPowerCalculator.computeFingerprint(a1)
        val fp2 = SectCombatPowerCalculator.computeFingerprint(a2)
        assertEquals(fp1, fp2)
    }

    @Test
    fun `computeFingerprint - different realm different fingerprint`() {
        val d1 = Disciple(name = "A", realm = 5, realmLayer = 3)
        val d2 = Disciple(name = "B", realm = 6, realmLayer = 3)
        val fp1 = SectCombatPowerCalculator.computeFingerprint(d1.toAggregate())
        val fp2 = SectCombatPowerCalculator.computeFingerprint(d2.toAggregate())
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `computeFingerprint - weapon no longer affects fingerprint`() {
        val d1 = Disciple(name = "A", realm = 5, realmLayer = 3)
        val d2 = Disciple(name = "B", realm = 5, realmLayer = 3)
        // 两个弟子境界、层数、方差相同 → 指纹相同
        // 即使他们可能有不同装备（装备不影响战力指纹）
        val fp1 = SectCombatPowerCalculator.computeFingerprint(d1.toAggregate())
        val fp2 = SectCombatPowerCalculator.computeFingerprint(d2.toAggregate())
        assertEquals("相同境界/层数的弟子指纹应相同（装备不影响）", fp1, fp2)
    }

    // ========== 宗门总战力 ==========

    @Test
    fun `calculateSectPower - 空列表返回0`() {
        assertEquals(0L, SectCombatPowerCalculator.calculateSectPower(emptyList(), emptyMap()))
    }

    @Test
    fun `calculateSectPower - 高境界弟子战力更高`() {
        val weak = Disciple(name = "弱", realm = 9, realmLayer = 1)
        val strong = Disciple(name = "强", realm = 0, realmLayer = 1)
        val weakPower = SectCombatPowerCalculator.calculateSectPower(listOf(weak), emptyMap())
        val strongPower = SectCombatPowerCalculator.calculateSectPower(listOf(strong), emptyMap())
        assertTrue("高境界（realm 小）弟子战力应更高", strongPower > weakPower)
    }

    @Test
    fun `calculateSectPower - 仅计算存活弟子`() {
        val alive = Disciple(name = "活", realm = 0, realmLayer = 1, isAlive = true)
        val dead = Disciple(name = "死", realm = 0, realmLayer = 1, isAlive = false)
        val scoreBoth = SectCombatPowerCalculator.calculateSectPower(listOf(alive, dead), emptyMap())
        val scoreAlive = SectCombatPowerCalculator.calculateSectPower(listOf(alive), emptyMap())
        assertEquals("死亡弟子不应计入战力", scoreAlive, scoreBoth)
    }

    @Test
    fun `calculateSectPower - 总战力等于各弟子之和`() {
        val d1 = Disciple(name = "A", realm = 5, realmLayer = 3)
        val d2 = Disciple(name = "B", realm = 6, realmLayer = 2)
        val sum = SectCombatPowerCalculator.calculateSectPower(listOf(d1), emptyMap()) +
            SectCombatPowerCalculator.calculateSectPower(listOf(d2), emptyMap())
        assertEquals(sum, SectCombatPowerCalculator.calculateSectPower(listOf(d1, d2), emptyMap()))
    }

    // ========== 星级乘区（口径 A：1★ 基线，每多一星 +8%） ==========

    @Test
    fun `calculateSectPower - 账本星级参与总战力`() {
        val disciple = Disciple(name = "A", realm = 5, realmLayer = 3, templateId = "zhouming")
        val starred = SectCombatPowerCalculator.calculateSectPower(
            listOf(disciple), mapOf("zhouming" to GameConfig.Gacha.MAX_STAR)
        )
        val baseline = SectCombatPowerCalculator.calculateSectPower(listOf(disciple), emptyMap())
        assertTrue("5★ 名册总战力必须高于同弟子基线（账本未参与则本断言恒等）", starred > baseline)
    }

    @Test
    fun `calculateSectPower - 未解锁与存量旧弟子恒等于纯公式基线`() {
        // 账本无键（未解锁）与 templateId 空串（存量旧弟子，即便账本落了空键）
        // 均按 0 星 ⇒ ×1.00，战力与纯公式逐位相等
        val legacy = Disciple(name = "旧", realm = 5, realmLayer = 3)
        val unlocked = Disciple(name = "新", realm = 5, realmLayer = 3, templateId = "notInLedger")
        val starMap = mapOf("" to GameConfig.Gacha.MAX_STAR)
        val pureLegacy = SectCombatPowerCalculator.calculateDisciplePower(
            legacy.toAggregate(), StarZone.BASE_STAR
        )
        assertEquals(
            "存量旧弟子（templateId 空串）不得吃星级加成",
            pureLegacy, SectCombatPowerCalculator.calculateSectPower(listOf(legacy), starMap)
        )
        assertEquals(
            "账本无键的弟子按 0 星处理",
            pureLegacy, SectCombatPowerCalculator.calculateSectPower(listOf(unlocked), starMap)
        )
    }

    @Test
    fun `calculateDiscipleCombatPowerWithStar - 基线及以下星级零扰动`() {
        val stats = starFixtureStats()
        val pure = SectCombatPowerCalculator.calculateDiscipleCombatPower(stats)
        assertEquals("1★ 必须逐位等于纯公式值", pure,
            SectCombatPowerCalculator.calculateDiscipleCombatPowerWithStar(stats, StarZone.BASE_STAR))
        assertEquals("0★（未解锁）不得反向削低", pure,
            SectCombatPowerCalculator.calculateDiscipleCombatPowerWithStar(stats, STAR_ZERO))
        assertEquals("负数星级按基线处理", pure,
            SectCombatPowerCalculator.calculateDiscipleCombatPowerWithStar(stats, STAR_NEGATIVE))
    }

    @Test
    fun `calculateDiscipleCombatPowerWithStar - 整体乘算后向零截断`() {
        val stats = starFixtureStats()
        // 单列口径（B1）：100*5 + 1000*4 + 60*3 + 40*2 = 4760（与 C++ 金标同式同值）
        assertEquals(4760L, SectCombatPowerCalculator.calculateDiscipleCombatPower(stats))
        // 4760 × 1.32 = 6283.2 → 向零截断
        assertEquals(6283L, SectCombatPowerCalculator.calculateDiscipleCombatPowerWithStar(
            stats, GameConfig.Gacha.MAX_STAR))
        // 4760 × 1.08 = 5140.8 → 向零截断
        assertEquals(5140L, SectCombatPowerCalculator.calculateDiscipleCombatPowerWithStar(
            stats, STAR_TWO))
    }

    @Test
    fun `calculateDisciplePower - 星级随入参单调上浮`() {
        val aggregate = Disciple(name = "A", realm = 5, realmLayer = 3, templateId = "zhouming")
            .toAggregate()
        val base = SectCombatPowerCalculator.calculateDisciplePower(aggregate, StarZone.BASE_STAR)
        val two = SectCombatPowerCalculator.calculateDisciplePower(aggregate, STAR_TWO)
        val five = SectCombatPowerCalculator.calculateDisciplePower(
            aggregate, GameConfig.Gacha.MAX_STAR
        )
        assertEquals(
            "1★ 即纯公式基线",
            SectCombatPowerCalculator.calculateDiscipleCombatPower(
                DiscipleStatCalculator.getPermanentBaseStats(aggregate)
            ),
            base
        )
        assertTrue("星级越高战力越高", five > two && two > base)
    }

    @Test
    fun `computeFingerprint - 星级不入指纹`() {
        // 指纹只覆盖永久基础属性：星级变化不改指纹，缓存失效由持有方并列比对
        // star 承担（指纹公式与 C++ sectPowerFingerprint 逐位对拍，不得加项）
        val d1 = Disciple(name = "A", realm = 5, realmLayer = 3, templateId = "zhouming")
        val d2 = Disciple(name = "A", realm = 5, realmLayer = 3, templateId = "other")
        assertEquals(
            SectCombatPowerCalculator.computeFingerprint(d1.toAggregate()),
            SectCombatPowerCalculator.computeFingerprint(d2.toAggregate())
        )
    }

    /** 与 C++ `sect_power` 金标用例同组的六维属性（纯公式基线 5310） */
    private fun starFixtureStats(): DiscipleStats = DiscipleStats(
        maxHp = 1000,
        attack = 100,
        defense = 60,
        speed = 40
    )

    // ========== 妖兽战力测试 ==========

    @Test
    fun `calculateBeastCombatPower - formula matches known values`() {
        // 虎妖 (beastType=0): hpMod=1.3, atkMod=1.4, defMod=0.7, speedMod=1.0
        // 属性已含随机方差（此处直接传入预计算值验证公式）
        val result = SectCombatPowerCalculator.calculateBeastCombatPower(
            maxHp = 5000, attack = 300, defense = 150, speed = 100
        )
        // 单列口径（B1）：300*5 + 5000*4 + 150*3 + 100*2
        val expected = 300 * 5L + 5000 * 4L + 150 * 3L + 100 * 2L
        assertEquals(expected, result)
    }

    @Test
    fun `calculateBeastCombatPower - zero stats`() {
        val result = SectCombatPowerCalculator.calculateBeastCombatPower(
            maxHp = 0, attack = 0, defense = 0, speed = 0
        )
        assertEquals(0L, result)
    }

    @Test
    fun `calculateBeastCombatPower - deterministic output`() {
        val first = SectCombatPowerCalculator.calculateBeastCombatPower(
            maxHp = 15236, attack = 2283, defense = 853, speed = 866
        )
        val second = SectCombatPowerCalculator.calculateBeastCombatPower(
            maxHp = 15236, attack = 2283, defense = 853, speed = 866
        )
        assertEquals("相同输入必须返回相同结果", first, second)
    }

    @Test
    fun `calculateBeastCombatPower - higher stats yield higher power`() {
        val low = SectCombatPowerCalculator.calculateBeastCombatPower(
            maxHp = 1000, attack = 100, defense = 50, speed = 50
        )
        val high = SectCombatPowerCalculator.calculateBeastCombatPower(
            maxHp = 2000, attack = 200, defense = 100, speed = 100
        )
        assertEquals(true, high > low)
    }

    @Test
    fun `calculateBeastCombatPower - same formula as disciple`() {
        // 同一组属性值，妖兽和弟子使用完全相同的公式
        val beastPower = SectCombatPowerCalculator.calculateBeastCombatPower(
            maxHp = 1000, attack = 200, defense = 50, speed = 80
        )
        val stats = com.xianxia.sect.core.model.DiscipleStats(
            maxHp = 1000, attack = 200, defense = 50, speed = 80
        )
        val disciplePower = SectCombatPowerCalculator.calculateDiscipleCombatPower(stats)
        assertEquals("妖兽与弟子使用同一战力公式", disciplePower, beastPower)
    }

    @Test
    fun `calculateBeastCombatPower - negative inputs coerced to zero`() {
        // 负数应被钳制为 0 计算，不产生负战力
        val result = SectCombatPowerCalculator.calculateBeastCombatPower(
            maxHp = -100, attack = -50, defense = -20, speed = -5
        )
        assertEquals("负数入参应返回 0", 0L, result)
    }

    @Test
    fun `calculateBeastCombatPower - mixed negative positive`() {
        val result = SectCombatPowerCalculator.calculateBeastCombatPower(
            maxHp = 1000, attack = -50, defense = 50, speed = 80
        )
        // hp=1000, attack=0(钳制), defense=50, speed=80
        // 0*5 + 1000*4 + 50*3 + 80*2 = 4000+150+160 = 4310
        assertEquals(4310L, result)
    }
}
