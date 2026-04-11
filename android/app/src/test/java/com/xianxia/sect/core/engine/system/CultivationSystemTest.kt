package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.SkillStats
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CultivationSystemTest {

    private lateinit var system: CultivationSystem

    @Before
    fun setUp() {
        system = CultivationSystem()
    }

    private fun createDisciple(
        id: String = "d1",
        name: String = "TestDisciple",
        realm: Int = 9,
        realmLayer: Int = 1,
        cultivation: Double = 0.0,
        age: Int = 16,
        isAlive: Boolean = true
    ): Disciple {
        return Disciple(
            id = id,
            name = name,
            realm = realm,
            realmLayer = realmLayer,
            cultivation = cultivation,
            age = age,
            isAlive = isAlive,
            skills = SkillStats(loyalty = 50)
        )
    }

    // ========== processDiscipleCultivation 测试 ==========

    @Test
    fun `processDiscipleCultivation - 死亡弟子不修炼`() {
        val disciple = createDisciple(isAlive = false)
        val result = system.processDiscipleCultivation(disciple, emptyList(), emptyList())
        assertEquals(0.0, result.cultivationGain, 0.01)
        assertFalse(result.breakthroughAttempted)
    }

    @Test
    fun `processDiscipleCultivation - 年龄小于5不修炼`() {
        val disciple = createDisciple(age = 4)
        val result = system.processDiscipleCultivation(disciple, emptyList(), emptyList())
        assertEquals(0.0, result.cultivationGain, 0.01)
        assertFalse(result.breakthroughAttempted)
    }

    @Test
    fun `processDiscipleCultivation - 正常修炼获得修为`() {
        val disciple = createDisciple()
        val result = system.processDiscipleCultivation(disciple, emptyList(), emptyList())
        assertTrue(result.cultivationGain > 0)
        assertEquals(result.cultivationGain, result.disciple.cultivation, 0.01)
    }

    @Test
    fun `processDiscipleCultivation - 非二跳时修为减半`() {
        val disciple = createDisciple()
        val fullResult = system.processDiscipleCultivation(disciple, emptyList(), emptyList(), isSecondTick = true)
        val halfResult = system.processDiscipleCultivation(disciple, emptyList(), emptyList(), isSecondTick = false)
        assertTrue(fullResult.cultivationGain > halfResult.cultivationGain)
    }

    @Test
    fun `processDiscipleCultivation - 修为满时尝试突破`() {
        val disciple = createDisciple(realm = 9, realmLayer = 1, cultivation = 225.0)
        val result = system.processDiscipleCultivation(disciple, emptyList(), emptyList())
        assertTrue(result.breakthroughAttempted)
    }

    // ========== attemptBreakthrough 测试 ==========

    @Test
    fun `attemptBreakthrough - realm为0时无法突破`() {
        val disciple = createDisciple(realm = 0)
        val result = system.attemptBreakthrough(disciple, emptyList())
        assertFalse(result.success)
        assertTrue(result.message.contains("最高境界"))
    }

    @Test
    fun `attemptBreakthrough - 突破成功时境界变化正确`() {
        var successCount = 0
        var lastResult: CultivationSystem.BreakthroughResult? = null
        for (i in 1..500) {
            val disciple = createDisciple(realm = 9, realmLayer = 1)
            val result = system.attemptBreakthrough(disciple, emptyList())
            if (result.success) {
                successCount++
                lastResult = result
            }
        }
        assertTrue("至少应有一次突破成功", successCount > 0)
        assertNotNull(lastResult)
        if (lastResult!!.newRealm != null) {
            assertTrue(lastResult!!.newRealm!! <= 9)
        }
    }

    @Test
    fun `attemptBreakthrough - 突破丹增加突破概率`() {
        val pill = Pill(
            id = "bp1",
            name = "突破丹",
            category = PillCategory.BREAKTHROUGH,
            targetRealm = 9,
            breakthroughChance = 0.5
        )
        var successWithPill = 0
        var successWithoutPill = 0
        val iterations = 500

        for (i in 1..iterations) {
            val disciple = createDisciple(realm = 9, realmLayer = 1)
            val resultWithPill = system.attemptBreakthrough(disciple, listOf(pill))
            if (resultWithPill.success) successWithPill++
        }

        for (i in 1..iterations) {
            val disciple = createDisciple(realm = 9, realmLayer = 1)
            val resultWithout = system.attemptBreakthrough(disciple, emptyList())
            if (resultWithout.success) successWithoutPill++
        }

        assertTrue("使用突破丹后成功率应更高", successWithPill > successWithoutPill)
    }

    @Test
    fun `attemptBreakthrough - 突破成功时寿命增加`() {
        var foundSuccess = false
        for (i in 1..500) {
            val disciple = createDisciple(realm = 9, realmLayer = 1)
            val result = system.attemptBreakthrough(disciple, emptyList())
            if (result.success) {
                assertTrue(result.lifespanGain >= 0)
                foundSuccess = true
                break
            }
        }
        assertTrue("应至少有一次突破成功", foundSuccess)
    }

    @Test
    fun `attemptBreakthrough - 层数超过9时进阶`() {
        var foundSuccess = false
        for (i in 1..500) {
            val disciple = createDisciple(realm = 9, realmLayer = 9)
            val result = system.attemptBreakthrough(disciple, emptyList())
            if (result.success) {
                if (result.newRealm != null && result.newLayer != null) {
                    assertEquals(8, result.newRealm)
                    assertEquals(1, result.newLayer)
                }
                foundSuccess = true
                break
            }
        }
        assertTrue("应至少有一次突破成功", foundSuccess)
    }

    @Test
    fun `attemptBreakthrough - pillBonus参数增加突破概率`() {
        var successWithBonus = 0
        var successWithoutBonus = 0
        val iterations = 500

        for (i in 1..iterations) {
            val disciple = createDisciple(realm = 9, realmLayer = 1)
            val result = system.attemptBreakthrough(disciple, emptyList(), pillBonus = 0.5)
            if (result.success) successWithBonus++
        }

        for (i in 1..iterations) {
            val disciple = createDisciple(realm = 9, realmLayer = 1)
            val result = system.attemptBreakthrough(disciple, emptyList(), pillBonus = 0.0)
            if (result.success) successWithoutBonus++
        }

        assertTrue("额外加成应提高成功率", successWithBonus > successWithoutBonus)
    }

    // ========== autoUseCultivationPills 测试 ==========

    @Test
    fun `autoUseCultivationPills - 使用修炼丹增加修炼加成`() {
        val disciple = createDisciple()
        val pill = Pill(
            id = "cp1",
            name = "修炼丹",
            category = PillCategory.CULTIVATION,
            cultivationSpeed = 50.0
        )
        val (updatedDisciple, bonus) = system.autoUseCultivationPills(disciple, listOf(pill))
        assertTrue(bonus > 0)
        assertTrue(updatedDisciple.monthlyUsedPillIds.contains("cp1"))
    }

    @Test
    fun `autoUseCultivationPills - 已使用的丹药不再使用`() {
        val disciple = createDisciple().copyWith(
            monthlyUsedPillIds = listOf("cp1")
        )
        val pill = Pill(
            id = "cp1",
            name = "修炼丹",
            category = PillCategory.CULTIVATION,
            cultivationSpeed = 50.0
        )
        val (_, bonus) = system.autoUseCultivationPills(disciple, listOf(pill))
        assertEquals(0.0, bonus, 0.01)
    }

    @Test
    fun `autoUseCultivationPills - 非修炼丹不使用`() {
        val disciple = createDisciple()
        val pill = Pill(
            id = "bp1",
            name = "突破丹",
            category = PillCategory.BREAKTHROUGH,
            breakthroughChance = 0.5
        )
        val (_, bonus) = system.autoUseCultivationPills(disciple, listOf(pill))
        assertEquals(0.0, bonus, 0.01)
    }

    @Test
    fun `autoUseCultivationPills - 多种修炼丹叠加`() {
        val disciple = createDisciple()
        val pill1 = Pill(id = "cp1", name = "修炼丹1", category = PillCategory.CULTIVATION, cultivationSpeed = 30.0)
        val pill2 = Pill(id = "cp2", name = "修炼丹2", category = PillCategory.CULTIVATION, cultivationSpeed = 50.0)
        val (_, bonus) = system.autoUseCultivationPills(disciple, listOf(pill1, pill2))
        assertEquals(0.8, bonus, 0.01)
    }

    // ========== autoUseBattlePills 测试 ==========

    @Test
    fun `autoUseBattlePills - 使用战斗丹增加属性加成`() {
        val disciple = createDisciple()
        val pill = Pill(
            id = "bat1",
            name = "物理丹",
            category = PillCategory.BATTLE_PHYSICAL,
            physicalAttackPercent = 20.0,
            duration = 3
        )
        val updated = system.autoUseBattlePills(disciple, listOf(pill))
        assertTrue(updated.pillPhysicalAttackBonus > 0)
        assertEquals(3, updated.pillEffectDuration)
        assertTrue(updated.monthlyUsedPillIds.contains("bat1"))
    }

    @Test
    fun `autoUseBattlePills - 已使用的战斗丹不再使用`() {
        val disciple = createDisciple().copyWith(
            monthlyUsedPillIds = listOf("bat1")
        )
        val pill = Pill(
            id = "bat1",
            name = "物理丹",
            category = PillCategory.BATTLE_PHYSICAL,
            physicalAttackPercent = 20.0,
            duration = 3
        )
        val updated = system.autoUseBattlePills(disciple, listOf(pill))
        assertEquals(0.0, updated.pillPhysicalAttackBonus, 0.01)
    }

    // ========== autoUseHealingPills 测试 ==========

    @Test
    fun `autoUseHealingPills - 使用治疗丹设置效果标记`() {
        val disciple = createDisciple()
        val pill = Pill(
            id = "hp1",
            name = "复活丹",
            category = PillCategory.HEALING,
            revive = true,
            clearAll = true
        )
        val updated = system.autoUseHealingPills(disciple, listOf(pill))
        assertTrue(updated.hasReviveEffect)
        assertTrue(updated.hasClearAllEffect)
        assertTrue(updated.monthlyUsedPillIds.contains("hp1"))
    }

    @Test
    fun `autoUseHealingPills - 无复活效果只设clearAll`() {
        val disciple = createDisciple()
        val pill = Pill(
            id = "hp1",
            name = "净化丹",
            category = PillCategory.HEALING,
            revive = false,
            clearAll = true
        )
        val updated = system.autoUseHealingPills(disciple, listOf(pill))
        assertFalse(updated.hasReviveEffect)
        assertTrue(updated.hasClearAllEffect)
    }

    // ========== processPillEffectDuration 测试 ==========

    @Test
    fun `processPillEffectDuration - 持续时间减1`() {
        val disciple = createDisciple().copyWith(
            pillPhysicalAttackBonus = 0.2,
            pillEffectDuration = 3
        )
        val updated = system.processPillEffectDuration(disciple)
        assertEquals(2, updated.pillEffectDuration)
        assertEquals(0.2, updated.pillPhysicalAttackBonus, 0.01)
    }

    @Test
    fun `processPillEffectDuration - 持续时间归零时清除所有丹药效果`() {
        val disciple = createDisciple().copyWith(
            pillPhysicalAttackBonus = 0.2,
            pillMagicAttackBonus = 0.1,
            pillPhysicalDefenseBonus = 0.15,
            pillMagicDefenseBonus = 0.1,
            pillHpBonus = 0.1,
            pillMpBonus = 0.05,
            pillSpeedBonus = 0.1,
            pillEffectDuration = 1
        )
        val updated = system.processPillEffectDuration(disciple)
        assertEquals(0, updated.pillEffectDuration)
        assertEquals(0.0, updated.pillPhysicalAttackBonus, 0.01)
        assertEquals(0.0, updated.pillMagicAttackBonus, 0.01)
        assertEquals(0.0, updated.pillPhysicalDefenseBonus, 0.01)
        assertEquals(0.0, updated.pillMagicDefenseBonus, 0.01)
        assertEquals(0.0, updated.pillHpBonus, 0.01)
        assertEquals(0.0, updated.pillMpBonus, 0.01)
        assertEquals(0.0, updated.pillSpeedBonus, 0.01)
    }

    @Test
    fun `processPillEffectDuration - 持续时间为0时不变`() {
        val disciple = createDisciple().copyWith(
            pillEffectDuration = 0
        )
        val updated = system.processPillEffectDuration(disciple)
        assertEquals(0, updated.pillEffectDuration)
    }

    // ========== resetMonthlyUsedPills 测试 ==========

    @Test
    fun `resetMonthlyUsedPills - 清空月度丹药使用记录`() {
        val disciple = createDisciple().copyWith(
            monthlyUsedPillIds = listOf("p1", "p2", "p3")
        )
        val updated = system.resetMonthlyUsedPills(disciple)
        assertTrue(updated.monthlyUsedPillIds.isEmpty())
    }

    // ========== 突破寿命增益边界测试 ==========

    @Test
    fun `attemptBreakthrough - 各境界突破成功寿命增益正确`() {
        val expectedLifespanGain = mapOf(
            8 to 50,
            7 to 100,
            6 to 200,
            5 to 400,
            4 to 800,
            3 to 1500,
            2 to 3000,
            1 to 5000
        )

        for ((realm, expectedGain) in expectedLifespanGain) {
            var foundSuccess = false
            for (i in 1..500) {
                val disciple = createDisciple(realm = realm, realmLayer = 1)
                val result = system.attemptBreakthrough(disciple, emptyList(), pillBonus = 0.9)
                if (result.success) {
                    assertEquals("realm=$realm 寿命增益", expectedGain, result.lifespanGain)
                    foundSuccess = true
                    break
                }
            }
            assertTrue("realm=$realm 应至少有一次突破成功", foundSuccess)
        }
    }
}
