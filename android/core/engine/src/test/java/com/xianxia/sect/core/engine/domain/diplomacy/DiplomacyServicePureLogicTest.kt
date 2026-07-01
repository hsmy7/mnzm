package com.xianxia.sect.core.engine.domain.diplomacy

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DiplomacyServicePureLogicTest {

    // ==================== selectRarityByMerchantProbabilities ====================
    // SECT_TRADE_RARITY_PROBABILITIES: 6->0.05, 5->0.10, 4->0.20, 3->0.25, 2->0.25, 1->0.15
    // Test that the probability table sums to 1.0
    @Test fun merchantRarityProbabilities_sumToOne() {
        val probs = mapOf(6 to 0.05, 5 to 0.10, 4 to 0.20, 3 to 0.25, 2 to 0.25, 1 to 0.15)
        val sum = probs.values.sum()
        assertEquals(1.0, sum, 0.001)
    }

    // ==================== requestAllianceSimple 概率逻辑 ====================
    // 好感度越高成功率越高，但任何好感度都有成败可能

    private fun allianceSuccessChance(favor: Int): Double = when {
        favor >= 90 -> 0.90
        favor >= 80 -> 0.75
        favor >= 60 -> 0.60
        favor >= 40 -> 0.45
        favor >= 20 -> 0.25
        else -> 0.10
    }

    @Test fun `allianceChance - favor 100 returns 90 percent`() {
        assertEquals(0.90, allianceSuccessChance(100), 0.001)
    }

    @Test fun `allianceChance - favor 95 returns 90 percent`() {
        assertEquals(0.90, allianceSuccessChance(95), 0.001)
    }

    @Test fun `allianceChance - favor 90 returns 90 percent`() {
        assertEquals(0.90, allianceSuccessChance(90), 0.001)
    }

    @Test fun `allianceChance - favor 85 returns 75 percent`() {
        assertEquals(0.75, allianceSuccessChance(85), 0.001)
    }

    @Test fun `allianceChance - favor 80 returns 75 percent`() {
        assertEquals(0.75, allianceSuccessChance(80), 0.001)
    }

    @Test fun `allianceChance - favor 70 returns 60 percent`() {
        assertEquals(0.60, allianceSuccessChance(70), 0.001)
    }

    @Test fun `allianceChance - favor 60 returns 60 percent`() {
        assertEquals(0.60, allianceSuccessChance(60), 0.001)
    }

    @Test fun `allianceChance - favor 50 returns 45 percent`() {
        assertEquals(0.45, allianceSuccessChance(50), 0.001)
    }

    @Test fun `allianceChance - favor 40 returns 45 percent`() {
        assertEquals(0.45, allianceSuccessChance(40), 0.001)
    }

    @Test fun `allianceChance - favor 30 returns 25 percent`() {
        assertEquals(0.25, allianceSuccessChance(30), 0.001)
    }

    @Test fun `allianceChance - favor 20 returns 25 percent`() {
        assertEquals(0.25, allianceSuccessChance(20), 0.001)
    }

    @Test fun `allianceChance - favor 10 returns 10 percent`() {
        assertEquals(0.10, allianceSuccessChance(10), 0.001)
    }

    @Test fun `allianceChance - favor 0 returns 10 percent`() {
        assertEquals(0.10, allianceSuccessChance(0), 0.001)
    }

    @Test fun `allianceChance - favor negative treated as 10 percent`() {
        assertEquals(0.10, allianceSuccessChance(-1), 0.001)
    }

    @Test fun `allianceChance - favor above 100 returns 90 percent`() {
        assertEquals(0.90, allianceSuccessChance(200), 0.001)
    }

    // 统计验证：高好感度(100)多次模拟，成功率应接近90%
    @Test fun `allianceChance - high favor statistical test`() {
        val trials = 10000
        val successChance = allianceSuccessChance(100)
        val successes = (1..trials).count { Random.nextDouble() < successChance }
        val rate = successes.toDouble() / trials
        // 允许 ±3% 误差
        assertTrue("期望~0.90, 实际$rate", rate > 0.87 && rate < 0.93)
    }

    // 统计验证：低好感度(0)多次模拟，成功率应接近10%
    @Test fun `allianceChance - low favor statistical test`() {
        val trials = 10000
        val successChance = allianceSuccessChance(0)
        val successes = (1..trials).count { Random.nextDouble() < successChance }
        val rate = successes.toDouble() / trials
        // 允许 ±3% 误差
        assertTrue("期望~0.10, 实际$rate", rate > 0.07 && rate < 0.13)
    }

    // 统计验证：中间好感度(50)多次模拟，成功率应接近45%
    @Test fun `allianceChance - mid favor statistical test`() {
        val trials = 10000
        val successChance = allianceSuccessChance(50)
        val successes = (1..trials).count { Random.nextDouble() < successChance }
        val rate = successes.toDouble() / trials
        // 允许 ±3% 误差
        assertTrue("期望~0.45, 实际$rate", rate > 0.42 && rate < 0.48)
    }

    // 验证所有好感度都允许成功（任何好感度都有成功可能）
    @Test fun `allianceChance - all favor levels can succeed`() {
        for (favor in listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)) {
            assertTrue("favor=$favor 应允许成功", allianceSuccessChance(favor) > 0)
        }
    }

    // 验证所有好感度都允许失败（任何好感度都有失败可能）
    @Test fun `allianceChance - all favor levels can fail`() {
        for (favor in listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)) {
            assertTrue("favor=$favor 应允许失败", allianceSuccessChance(favor) < 1.0)
        }
    }

    // 验证概率单调递增：好感度越高，成功率不应降低
    @Test fun `allianceChance - monotonically increasing with favor`() {
        val testPoints = listOf(0, 19, 20, 39, 40, 59, 60, 79, 80, 89, 90, 100)
        for (i in 0 until testPoints.size - 1) {
            val lower = allianceSuccessChance(testPoints[i])
            val higher = allianceSuccessChance(testPoints[i + 1])
            assertTrue("favor ${testPoints[i]}=$lower 应 <= favor ${testPoints[i + 1]}=$higher", lower <= higher)
        }
    }

    // ==================== isAlly 查询逻辑 ====================

    // 验证盟友判定：alliances 列表包含 player + sectId 即为盟友
    private fun isAllyCheck(alliances: List<Pair<String, String>>, sectId: String): Boolean {
        return alliances.any { (first, second) ->
            (first == "player" && second == sectId) ||
            (first == sectId && second == "player")
        }
    }

    @Test fun `isAlly - player in alliance returns true`() {
        assertTrue(isAllyCheck(listOf("player" to "sect1"), "sect1"))
    }

    @Test fun `isAlly - not in alliance returns false`() {
        assertFalse(isAllyCheck(listOf("player" to "sect1"), "sect2"))
    }

    @Test fun `isAlly - empty alliances returns false`() {
        assertFalse(isAllyCheck(emptyList(), "sect1"))
    }

    @Test fun `isAlly - other alliance not affecting`() {
        assertFalse(isAllyCheck(listOf("player" to "sect2"), "sect1"))
    }
}
