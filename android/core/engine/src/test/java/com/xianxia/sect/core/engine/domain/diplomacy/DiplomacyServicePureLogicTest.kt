package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.domain.FavorDomain
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DiplomacyServicePureLogicTest {

    // ==================== selectRarityByMerchantProbabilities ====================
    // 实际值见 DiplomacyService.SECT_TRADE_RARITY_PROBABILITIES
    @Test fun merchantRarityProbabilities_sumToOne() {
        val probs = mapOf(6 to 0.003, 5 to 0.027, 4 to 0.05, 3 to 0.12, 2 to 0.40, 1 to 0.40)
        val sum = probs.values.sum()
        assertEquals(1.0, sum, 0.001)
    }

    // ==================== allianceSuccessChance（委托 FavorDomain） ====================

    @Test fun `allianceChance - favor 100 returns 90 percent`() {
        assertEquals(0.90, FavorDomain.calculateAllianceSuccessChance(100), 0.001)
    }

    @Test fun `allianceChance - favor 95 returns 90 percent`() {
        assertEquals(0.90, FavorDomain.calculateAllianceSuccessChance(95), 0.001)
    }

    @Test fun `allianceChance - favor 90 returns 90 percent`() {
        assertEquals(0.90, FavorDomain.calculateAllianceSuccessChance(90), 0.001)
    }

    @Test fun `allianceChance - favor 85 returns 75 percent`() {
        assertEquals(0.75, FavorDomain.calculateAllianceSuccessChance(85), 0.001)
    }

    @Test fun `allianceChance - favor 80 returns 75 percent`() {
        assertEquals(0.75, FavorDomain.calculateAllianceSuccessChance(80), 0.001)
    }

    @Test fun `allianceChance - favor 70 returns 60 percent`() {
        assertEquals(0.60, FavorDomain.calculateAllianceSuccessChance(70), 0.001)
    }

    @Test fun `allianceChance - favor 60 returns 60 percent`() {
        assertEquals(0.60, FavorDomain.calculateAllianceSuccessChance(60), 0.001)
    }

    @Test fun `allianceChance - favor 50 returns 45 percent`() {
        assertEquals(0.45, FavorDomain.calculateAllianceSuccessChance(50), 0.001)
    }

    @Test fun `allianceChance - favor 40 returns 45 percent`() {
        assertEquals(0.45, FavorDomain.calculateAllianceSuccessChance(40), 0.001)
    }

    @Test fun `allianceChance - favor 30 returns 25 percent`() {
        assertEquals(0.25, FavorDomain.calculateAllianceSuccessChance(30), 0.001)
    }

    @Test fun `allianceChance - favor 20 returns 25 percent`() {
        assertEquals(0.25, FavorDomain.calculateAllianceSuccessChance(20), 0.001)
    }

    @Test fun `allianceChance - favor 10 returns 10 percent`() {
        assertEquals(0.10, FavorDomain.calculateAllianceSuccessChance(10), 0.001)
    }

    @Test fun `allianceChance - favor 0 returns 10 percent`() {
        assertEquals(0.10, FavorDomain.calculateAllianceSuccessChance(0), 0.001)
    }

    @Test fun `allianceChance - favor negative treated as 10 percent`() {
        assertEquals(0.10, FavorDomain.calculateAllianceSuccessChance(-1), 0.001)
    }

    @Test fun `allianceChance - favor above 100 returns 90 percent`() {
        assertEquals(0.90, FavorDomain.calculateAllianceSuccessChance(200), 0.001)
    }

    @Test fun `allianceChance - high favor statistical test`() {
        val trials = 10000
        val successChance = FavorDomain.calculateAllianceSuccessChance(100)
        val successes = (1..trials).count { Random.nextDouble() < successChance }
        val rate = successes.toDouble() / trials
        assertTrue("期望~0.90, 实际$rate", rate > 0.87 && rate < 0.93)
    }

    @Test fun `allianceChance - low favor statistical test`() {
        val trials = 10000
        val successChance = FavorDomain.calculateAllianceSuccessChance(0)
        val successes = (1..trials).count { Random.nextDouble() < successChance }
        val rate = successes.toDouble() / trials
        assertTrue("期望~0.10, 实际$rate", rate > 0.07 && rate < 0.13)
    }

    @Test fun `allianceChance - mid favor statistical test`() {
        val trials = 10000
        val successChance = FavorDomain.calculateAllianceSuccessChance(50)
        val successes = (1..trials).count { Random.nextDouble() < successChance }
        val rate = successes.toDouble() / trials
        assertTrue("期望~0.45, 实际$rate", rate > 0.42 && rate < 0.48)
    }

    @Test fun `allianceChance - all favor levels can succeed`() {
        for (favor in listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)) {
            assertTrue("favor=$favor 应允许成功", FavorDomain.calculateAllianceSuccessChance(favor) > 0)
        }
    }

    @Test fun `allianceChance - all favor levels can fail`() {
        for (favor in listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)) {
            assertTrue("favor=$favor 应允许失败", FavorDomain.calculateAllianceSuccessChance(favor) < 1.0)
        }
    }

    @Test fun `allianceChance - monotonically increasing with favor`() {
        val testPoints = listOf(0, 19, 20, 39, 40, 59, 60, 79, 80, 89, 90, 100)
        for (i in 0 until testPoints.size - 1) {
            val lower = FavorDomain.calculateAllianceSuccessChance(testPoints[i])
            val higher = FavorDomain.calculateAllianceSuccessChance(testPoints[i + 1])
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
