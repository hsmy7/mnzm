package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.model.WorldSect
import org.junit.Assert.*
import org.junit.Test

class LevelGeneratorTest {

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

    // ---- buildConnectionEdges ----

    @Test
    fun buildConnectionEdges_emptySects_returnsEmptyList() {
        val edges = LevelGenerator.buildConnectionEdges(emptyList())
        assertEquals(0, edges.size)
    }

    @Test
    fun buildConnectionEdges_singleSect_returnsEmptyList() {
        val sect = WorldSect(id = "s1", x = 0f, y = 0f)
        val edges = LevelGenerator.buildConnectionEdges(listOf(sect))
        assertEquals(0, edges.size)
    }

    @Test
    fun buildConnectionEdges_twoSects_returnsOneEdge() {
        val sect1 = WorldSect(id = "s1", x = 0f, y = 0f)
        val sect2 = WorldSect(id = "s2", x = 30f, y = 40f)
        val edges = LevelGenerator.buildConnectionEdges(listOf(sect1, sect2))
        assertEquals(1, edges.size)
        assertEquals(50.0, edges[0].weight, 0.01)
    }

    @Test
    fun buildConnectionEdges_threeSects_returnsThreeEdges() {
        val sect1 = WorldSect(id = "s1", x = 0f, y = 0f)
        val sect2 = WorldSect(id = "s2", x = 100f, y = 0f)
        val sect3 = WorldSect(id = "s3", x = 50f, y = 86f)
        val edges = LevelGenerator.buildConnectionEdges(listOf(sect1, sect2, sect3))
        // C(3,2) = 3 edges for all pairs
        assertEquals(3, edges.size)
    }

    // ---- generateWorldLevels ----

    @Test
    fun generateWorldLevels_returnsListWithinMaxNewLevels() {
        val levels = LevelGenerator.generateWorldLevels(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            existingLevels = emptyList(),
            maxNewLevels = 3
        )
        assertTrue("Levels count should be <= 3", levels.size <= 3)
    }

    @Test
    fun generateWorldLevels_zeroMaxNewLevels_returnsEmptyList() {
        val levels = LevelGenerator.generateWorldLevels(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            existingLevels = emptyList(),
            maxNewLevels = 0
        )
        assertEquals(0, levels.size)
    }

    @Test
    fun generateWorldLevels_levelHasCorrectSpawnTime() {
        val levels = LevelGenerator.generateWorldLevels(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
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
        val levels = LevelGenerator.generateWorldLevels(
            existingSects = emptyList(),
            connectionEdges = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            existingLevels = emptyList(),
            maxNewLevels = 5
        )
        for (level in levels) {
            assertTrue("Level type should be BEAST or CAVE", level.type in validTypes)
        }
    }

    // ---- selectBeastRealm ----

    @Test
    fun selectBeastRealm_returnsValidRealmRange() {
        for (year in listOf(0, 1, 100, 500, 2000, 9999)) {
            val realm = LevelGenerator.selectBeastRealm(year)
            assertTrue("realm should be 0..9, got $realm for year=$year",
                realm in 0..9)
        }
    }

    @Test
    fun selectBeastRealm_year1_mostlyLowRealms() {
        // 统计 500 次采样，炼气+筑基应占主导
        val samples = List(500) { LevelGenerator.selectBeastRealm(1) }
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
        val samples = List(500) { LevelGenerator.selectBeastRealm(2000) }
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
        val samples = List(500) { LevelGenerator.selectBeastRealm(500) }
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
        val year1Avg = List(200) { LevelGenerator.selectBeastRealm(1) }.average()
        val year250Avg = List(200) { LevelGenerator.selectBeastRealm(250) }.average()
        val year500Avg = List(200) { LevelGenerator.selectBeastRealm(500) }.average()
        // realm 值越高境界越低，平均值应随年份递减
        assertTrue("year250 应介于 year1($year1Avg) 和 year500($year500Avg) 之间",
            year250Avg < year1Avg)
        assertTrue("year250 应介于 year1 和 year500 之间",
            year250Avg > year500Avg)
    }
}
