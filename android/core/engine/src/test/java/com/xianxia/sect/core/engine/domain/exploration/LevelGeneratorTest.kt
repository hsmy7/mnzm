package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.util.GameRngManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LevelGeneratorTest {

    private lateinit var generator: LevelGenerator

    @Before
    fun setUp() {
        val rngManager = GameRngManager()
        rngManager.initSystemSeed(42L) // 固定种子保证确定性
        generator = LevelGenerator(rngManager)
    }

    // ---- getCaveReward ----

    @Test
    fun getCaveReward_realm5_returnsCorrectConfig() {
        val reward = LevelGenerator.getCaveReward(5)
        assertEquals(20000.0, reward.baseSpiritStones, 0.01)
        assertEquals(1 to 2, reward.rarityRange)
    }

    @Test
    fun getCaveReward_realm4_returnsCorrectConfig() {
        val reward = LevelGenerator.getCaveReward(4)
        assertEquals(100000.0, reward.baseSpiritStones, 0.01)
        assertEquals(2 to 3, reward.rarityRange)
    }

    @Test
    fun getCaveReward_realm3_returnsCorrectConfig() {
        val reward = LevelGenerator.getCaveReward(3)
        assertEquals(300000.0, reward.baseSpiritStones, 0.01)
        assertEquals(2 to 5, reward.rarityRange)
    }

    @Test
    fun getCaveReward_realm2_returnsCorrectConfig() {
        val reward = LevelGenerator.getCaveReward(2)
        assertEquals(700000.0, reward.baseSpiritStones, 0.01)
        assertEquals(3 to 6, reward.rarityRange)
    }

    @Test
    fun getCaveReward_realm1_returnsCorrectConfig() {
        val reward = LevelGenerator.getCaveReward(1)
        assertEquals(1500000.0, reward.baseSpiritStones, 0.01)
        assertEquals(5 to 6, reward.rarityRange)
    }

    @Test
    fun getCaveReward_unknownRealm_returnsDefault() {
        val reward = LevelGenerator.getCaveReward(99)
        assertEquals(20000.0, reward.baseSpiritStones, 0.01)
        assertEquals(1 to 2, reward.rarityRange)
    }

    // ---- CaveRewardConfig ----

    @Test
    fun caveRewardConfig_construction() {
        val config = LevelGenerator.CaveRewardConfig(
            baseSpiritStones = 50000.0,
            rarityRange = 2 to 4
        )
        assertEquals(50000.0, config.baseSpiritStones, 0.01)
        assertEquals(2 to 4, config.rarityRange)
    }

    // ---- generateWorldLevels ----

    @Test
    fun generateWorldLevels_returnsListWithinMaxNewLevels() {
        val levels = generator.generateWorldLevels(
            existingSects = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            existingLevels = emptyList(),
            maxNewLevels = 3
        )
        assertTrue("Levels count should be <= 3", levels.size <= 3)
    }

    @Test
    fun generateWorldLevels_zeroMaxNewLevels_returnsEmptyList() {
        val levels = generator.generateWorldLevels(
            existingSects = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            existingLevels = emptyList(),
            maxNewLevels = 0
        )
        assertEquals(0, levels.size)
    }

    @Test
    fun generateWorldLevels_levelHasCorrectSpawnTime() {
        val levels = generator.generateWorldLevels(
            existingSects = emptyList(),
            currentYear = 5,
            currentMonth = 3,
            existingLevels = emptyList(),
            maxNewLevels = 3
        )
        for (level in levels) {
            assertEquals(5, level.spawnYear)
            assertEquals(3, level.spawnMonth)
        }
    }

    @Test
    fun generateWorldLevels_levelTypeIsBeastOrCave() {
        val validTypes = setOf(com.xianxia.sect.core.model.LevelType.BEAST, com.xianxia.sect.core.model.LevelType.CAVE)
        val levels = generator.generateWorldLevels(
            existingSects = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            existingLevels = emptyList(),
            maxNewLevels = 5
        )
        for (level in levels) {
            assertTrue("Level type should be BEAST or CAVE", level.type in validTypes)
        }
    }

    // ---- 预计算妖兽属性测试 ----

    @Test
    fun beastLevel_hasPrecomputedStats() {
        val levels = generator.generateWorldLevels(
            existingSects = emptyList(),
            currentYear = 5,
            currentMonth = 1,
            existingLevels = emptyList(),
            maxNewLevels = 20
        )
        val beastLevels = levels.filter { it.type == com.xianxia.sect.core.model.LevelType.BEAST }
        if (beastLevels.isEmpty()) return

        for (level in beastLevels) {
            assertTrue("beastMaxHp > 0, got ${level.beastMaxHp}", level.beastMaxHp > 0)
            assertTrue("beastPhysicalAttack > 0, got ${level.beastPhysicalAttack}", level.beastPhysicalAttack > 0)
            assertTrue("beastMagicAttack > 0, got ${level.beastMagicAttack}", level.beastMagicAttack > 0)
            assertTrue("beastPhysicalDefense > 0, got ${level.beastPhysicalDefense}", level.beastPhysicalDefense > 0)
            assertTrue("beastMagicDefense > 0, got ${level.beastMagicDefense}", level.beastMagicDefense > 0)
            assertTrue("beastSpeed > 0, got ${level.beastSpeed}", level.beastSpeed > 0)
            assertEquals("物攻=法攻", level.beastPhysicalAttack.toLong(), level.beastMagicAttack.toLong())
            assertEquals("物防=法防", level.beastPhysicalDefense.toLong(), level.beastMagicDefense.toLong())
        }
    }

    @Test
    fun caveLevel_hasNoPrecomputedStats() {
        val levels = generator.generateWorldLevels(
            existingSects = emptyList(),
            currentYear = 5,
            currentMonth = 1,
            existingLevels = emptyList(),
            maxNewLevels = 20
        )
        val caveLevels = levels.filter { it.type == com.xianxia.sect.core.model.LevelType.CAVE }
        for (level in caveLevels) {
            assertEquals("洞穴不应有预计算属性", 0, level.beastMaxHp)
        }
    }

    @Test
    fun beastPrecomputedStats_deterministicWithSameSeed() {
        val rng1 = GameRngManager().also { it.initSystemSeed(42L) }
        val g1 = LevelGenerator(rng1)
        val rng2 = GameRngManager().also { it.initSystemSeed(42L) }
        val g2 = LevelGenerator(rng2)

        val levels1 = g1.generateWorldLevels(emptyList(), 10, 1, emptyList(), 10)
        val levels2 = g2.generateWorldLevels(emptyList(), 10, 1, emptyList(), 10)

        assertEquals("相同种子生成相同数量", levels1.size, levels2.size)
        for (i in levels1.indices) {
            val l1 = levels1[i]
            val l2 = levels2[i]
            assertEquals("beastMaxHp 相同", l1.beastMaxHp, l2.beastMaxHp)
            assertEquals("beastPhysicalAttack 相同", l1.beastPhysicalAttack, l2.beastPhysicalAttack)
            assertEquals("beastSpeed 相同", l1.beastSpeed, l2.beastSpeed)
        }
    }

    @Test
    fun selectBeastRealm_returnsValidRealmRange() {
        for (year in listOf(0, 1, 100, 500, 2000, 9999)) {
            val realm = generator.selectBeastRealm(year)
            assertTrue("realm should be 0..9, got $realm for year=$year",
                realm in 0..9)
        }
    }

    @Test
    fun selectBeastRealm_year1_mostlyLowRealms() {
        // 统计 500 次采样，炼气+筑基应占主导
        val samples = List(500) { generator.selectBeastRealm(1) }
        val lowCount = samples.count { it in 8..9 }  // 炼气/筑基
        val highCount = samples.count { it in 0..2 } // 仙人/渡劫/大乘
        // 炼气+筑基应超过 40%
        assertTrue("year 1: 炼气+筑基比例应>40%, 实际=${lowCount * 100 / 500}%",
            lowCount > 200)
        // 高境界应少于 10%
        assertTrue("year 1: 高境界比例应<10%, 实际=${highCount * 100 / 500}%",
            highCount < 50)
    }

    @Test
    fun selectBeastRealm_year2000_mostlyHighRealms() {
        // 统计 500 次采样，高境界应占主导
        val samples = List(500) { generator.selectBeastRealm(2000) }
        val lowCount = samples.count { it in 8..9 }  // 炼气/筑基
        val highCount = samples.count { it in 0..2 } // 仙人/渡劫/大乘
        // 高境界应超过 40%
        assertTrue("year 2000: 高境界比例应>40%, 实际=${highCount * 100 / 500}%",
            highCount > 200)
        // 炼气+筑基应很少（<5%）
        assertTrue("year 2000: 炼气+筑基比例应<5%, 实际=${lowCount * 100 / 500}%",
            lowCount < 25)
    }

    @Test
    fun selectBeastRealm_year500_isMidGameDistribution() {
        val samples = List(500) { generator.selectBeastRealm(500) }
        val midCount = samples.count { it in 4..6 }  // 化神/元婴/炼虚
        val lowCount = samples.count { it in 8..9 }
        val highCount = samples.count { it in 0..2 }
        // 中期：低境界下降，高境界增长，中间段最高
        assertTrue("year 500: 中期境界应有显著占比, 实际=${midCount * 100 / 500}%",
            midCount > 150)
        assertTrue("year 500: 低境界比例应下降, 实际=${lowCount * 100 / 500}%",
            lowCount < 100)
        assertTrue("year 500: 高境界比例应增长, 实际=${highCount * 100 / 500}%",
            highCount > 50)
    }

    @Test
    fun selectBeastRealm_interpolationSmooth() {
        // 验证插值平滑性：year 250 的分布应介于 year 1 和 year 500 之间
        val year1Avg = List(200) { generator.selectBeastRealm(1) }.average()
        val year250Avg = List(200) { generator.selectBeastRealm(250) }.average()
        val year500Avg = List(200) { generator.selectBeastRealm(500) }.average()
        // realm 值越高境界越低，平均值应随年份递减
        assertTrue("year250 应介于 year1($year1Avg) 和 year500($year500Avg) 之间",
            year250Avg < year1Avg)
        assertTrue("year250 应介于 year1 和 year500 之间",
            year250Avg > year500Avg)
    }
}
