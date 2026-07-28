package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.config.SectDecisionConfig
import com.xianxia.sect.core.model.AISectPersonality
import com.xianxia.sect.core.model.SectRelationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IntelligentSectDecisionEngine] 的全面测试（好感度等级化版本）。
 *
 * 覆盖：
 * - 各 profile 的权重约束
 * - 好感度等级分值配置完整性
 * - 战力比分档映射
 * - 好感度等级门槛（攻击：FRIENDLY/INTIMATE → 0 分）
 * - 负数/零值处理
 * - 个性修正因子
 * - 脱离概率计算
 * - 最大概率上限约束
 * - 五级好感度在三种决策下的输出差异
 */
class IntelligentSectDecisionEngineTest {

    // ═══════════════════════════════════
    // Profile 权重校验
    // ═══════════════════════════════════

    @Test
    fun `weights - attack profile weights sum to 1`() {
        val p = IntelligentSectDecisionEngine.ATTACK_PROFILE
        val sum = p.powerWeight + p.occupyWeight + p.skirmishWeight + p.favorWeight
        assertEquals(1.0, sum, 0.001)
    }

    @Test
    fun `weights - alliance profile weights sum to 1`() {
        val p = IntelligentSectDecisionEngine.ALLIANCE_PROFILE
        val sum = p.powerWeight + p.occupyWeight + p.skirmishWeight + p.favorWeight
        assertEquals(1.0, sum, 0.001)
    }

    @Test
    fun `weights - vassal profile weights sum to 1`() {
        val p = IntelligentSectDecisionEngine.VASSAL_PROFILE
        val sum = p.powerWeight + p.occupyWeight + p.skirmishWeight + p.favorWeight
        assertEquals(1.0, sum, 0.001)
    }

    @Test
    fun `weights - maxChances within valid range`() {
        assertTrue(IntelligentSectDecisionEngine.ATTACK_PROFILE.maxChance in 0.0..1.0)
        assertTrue(IntelligentSectDecisionEngine.ALLIANCE_PROFILE.maxChance in 0.0..1.0)
        assertTrue(IntelligentSectDecisionEngine.VASSAL_PROFILE.maxChance in 0.0..1.0)
    }

    // ═══════════════════════════════════
    // 好感度等级分值配置完整性
    // ═══════════════════════════════════

    @Test
    fun `favorScoreByLevel - attack has all 5 levels`() {
        val scores = IntelligentSectDecisionEngine.ATTACK_PROFILE.favorScoreByLevel
        assertEquals(SectRelationLevel.entries.size, scores.size)
        SectRelationLevel.entries.forEach { level ->
            assertTrue("attack missing level $level", level in scores)
        }
    }

    @Test
    fun `favorScoreByLevel - alliance has all 5 levels`() {
        val scores = IntelligentSectDecisionEngine.ALLIANCE_PROFILE.favorScoreByLevel
        assertEquals(SectRelationLevel.entries.size, scores.size)
        SectRelationLevel.entries.forEach { level ->
            assertTrue("alliance missing level $level", level in scores)
        }
    }

    @Test
    fun `favorScoreByLevel - vassal has all 5 levels`() {
        val scores = IntelligentSectDecisionEngine.VASSAL_PROFILE.favorScoreByLevel
        assertEquals(SectRelationLevel.entries.size, scores.size)
        SectRelationLevel.entries.forEach { level ->
            assertTrue("vassal missing level $level", level in scores)
        }
    }

    @Test
    fun `favorScoreByLevel - breakaway has all 5 levels`() {
        assertEquals(SectRelationLevel.entries.size, SectDecisionConfig.BREAKAWAY_FAVOR_SCORE.size)
        SectRelationLevel.entries.forEach { level ->
            assertTrue("breakaway missing level $level", level in SectDecisionConfig.BREAKAWAY_FAVOR_SCORE)
        }
    }

    // ═══════════════════════════════════
    // 攻击判定：战力差
    // ═══════════════════════════════════

    @Test
    fun `attack - powerRatio below hard threshold returns 0`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 0.3, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE
        )
        assertEquals(0.0, chance, 0.001)
    }

    @Test
    fun `attack - powerRatio 2x gives powerScore 0_20`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 2.0, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE
        )
        // powerScore = 0.20 * (0.40/0.40) = 0.20, favorScore = 1.0*0.15=0.15
        assertEquals(0.35, chance, 0.001)
    }

    @Test
    fun `attack - powerRatio 5x gives high score`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 5.0, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE
        )
        // powerScore = 0.40, favorScore = 0.15
        assertTrue(chance > 0.50)
    }

    // ═══════════════════════════════════
    // 攻击判定：好感度等级门槛
    // ═══════════════════════════════════

    @Test
    fun `attack - HOSTILE allows attack`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 3.0, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE
        )
        assertTrue("HOSTILE 应允许攻击", chance > 0)
    }

    @Test
    fun `attack - ANTAGONISTIC allows moderate attack`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 3.0, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.ANTAGONISTIC
        )
        assertTrue(chance > 0)
    }

    @Test
    fun `attack - FRIENDLY returns 0`() {
        // ATTACK_FAVOR_SCORE: FRIENDLY→0.0, 且 favorWeight>0 → 返回 0
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 3.0, conquestCount = 5, lostSectCount = 0,
            battleWinCount = 5, battleLossCount = 0,
            favorLevel = SectRelationLevel.FRIENDLY
        )
        assertEquals("FRIENDLY 不攻击", 0.0, chance, 0.001)
    }

    @Test
    fun `attack - INTIMATE returns 0`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 3.0, conquestCount = 5, lostSectCount = 0,
            battleWinCount = 5, battleLossCount = 0,
            favorLevel = SectRelationLevel.INTIMATE
        )
        assertEquals("INTIMATE 不攻击", 0.0, chance, 0.001)
    }

    @Test
    fun `attack - NORMAL only small chance`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 3.0, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.NORMAL
        )
        // powerScore=0.30, favorScore=0.3*0.15=0.045
        assertEquals(0.345, chance, 0.001)
    }

    // ═══════════════════════════════════
    // 结盟判定：各等级输出
    // ═══════════════════════════════════

    @Test
    fun `alliance - HOSTILE returns 0`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ALLIANCE_PROFILE,
            powerRatio = 2.0, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE
        )
        assertEquals("HOSTILE 不可能结盟", 0.0, chance, 0.001)
    }

    @Test
    fun `alliance - INTIMATE gives highest chance`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ALLIANCE_PROFILE,
            powerRatio = 2.0, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.INTIMATE
        )
        // powerScore=0.10, favorScore=1.0*0.40=0.40
        assertEquals(0.50, chance, 0.001)
    }

    @Test
    fun `alliance - favor levels increase monotonically`() {
        val levels = listOf(
            SectRelationLevel.HOSTILE,
            SectRelationLevel.ANTAGONISTIC,
            SectRelationLevel.NORMAL,
            SectRelationLevel.FRIENDLY,
            SectRelationLevel.INTIMATE
        )
        var prev = -1.0
        for (level in levels) {
            val chance = IntelligentSectDecisionEngine.calculateChance(
                profile = IntelligentSectDecisionEngine.ALLIANCE_PROFILE,
                powerRatio = 2.0, conquestCount = 0, lostSectCount = 0,
                battleWinCount = 0, battleLossCount = 0,
                favorLevel = level
            )
            assertTrue("$level chance=$chance 应 >= $prev", chance >= prev)
            prev = chance
        }
    }

    // ═══════════════════════════════════
    // 附属判定
    // ═══════════════════════════════════

    @Test
    fun `vassal - powerRatio below 1_0 returns 0`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.VASSAL_PROFILE,
            powerRatio = 0.8, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.NORMAL
        )
        assertEquals(0.0, chance, 0.001)
    }

    @Test
    fun `vassal - HOSTILE returns 0`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.VASSAL_PROFILE,
            powerRatio = 2.0, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE
        )
        assertEquals("HOSTILE 不可能附属", 0.0, chance, 0.001)
    }

    @Test
    fun `vassal - powerRatio 2x with NORMAL gives expected score`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.VASSAL_PROFILE,
            powerRatio = 2.0, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.NORMAL
        )
        // powerScore=0.20, favorScore=0.4*0.15=0.06
        assertEquals(0.26, chance, 0.001)
    }

    // ═══════════════════════════════════
    // NaN/Infinity 防御
    // ═══════════════════════════════════

    @Test
    fun `attack - NaN powerRatio returns 0`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = Double.NaN, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE
        )
        assertEquals(0.0, chance, 0.001)
    }

    @Test
    fun `breakaway - NaN powerRatio returns 0`() {
        val chance = IntelligentSectDecisionEngine.calculateBreakawayChance(
            powerRatio = Double.NaN, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.NORMAL
        )
        assertEquals(0.0, chance, 0.001)
    }

    // ═══════════════════════════════════
    // 负数/零值处理
    // ═══════════════════════════════════

    @Test
    fun `attack - negative counts treated as zero`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 3.0, conquestCount = -5, lostSectCount = -3,
            battleWinCount = -10, battleLossCount = -2,
            favorLevel = SectRelationLevel.HOSTILE
        )
        // powerScore=0.30, totalOccupy=0→occupyScore=0, totalSkirmish=0→skirmishScore=0, favorScore=0.15
        assertEquals(0.45, chance, 0.001)
    }

    @Test
    fun `alliance - zero battle data yields base power and favor score`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ALLIANCE_PROFILE,
            powerRatio = 2.0, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.NORMAL
        )
        // powerScore=0.10, favorScore=0.4*0.40=0.16
        assertEquals(0.26, chance, 0.001)
    }

    // ═══════════════════════════════════
    // 最大概率上限
    // ═══════════════════════════════════

    @Test
    fun `attack - extreme values capped at maxChance`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 10.0, conquestCount = 100, lostSectCount = 0,
            battleWinCount = 100, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE
        )
        assertTrue(chance <= IntelligentSectDecisionEngine.ATTACK_PROFILE.maxChance)
    }

    @Test
    fun `alliance - extreme values capped at maxChance`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ALLIANCE_PROFILE,
            powerRatio = 10.0, conquestCount = 100, lostSectCount = 0,
            battleWinCount = 100, battleLossCount = 0,
            favorLevel = SectRelationLevel.INTIMATE
        )
        assertTrue(chance <= IntelligentSectDecisionEngine.ALLIANCE_PROFILE.maxChance)
    }

    @Test
    fun `vassal - extreme values capped at maxChance`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.VASSAL_PROFILE,
            powerRatio = 10.0, conquestCount = 100, lostSectCount = 0,
            battleWinCount = 100, battleLossCount = 0,
            favorLevel = SectRelationLevel.FRIENDLY
        )
        assertTrue(chance <= IntelligentSectDecisionEngine.VASSAL_PROFILE.maxChance)
    }

    // ═══════════════════════════════════
    // 个性修正因子
    // ═══════════════════════════════════

    @Test
    fun `attack - aggressive personality increases chance`() {
        val baseChance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 2.0, conquestCount = 1, lostSectCount = 0,
            battleWinCount = 1, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE,
            personality = AISectPersonality.BALANCED
        )
        val aggressiveChance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 2.0, conquestCount = 1, lostSectCount = 0,
            battleWinCount = 1, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE,
            personality = AISectPersonality.AGGRESSIVE
        )
        assertTrue("好战型攻击概率应更高", aggressiveChance >= baseChance)
    }

    @Test
    fun `attack - conservative personality decreases chance`() {
        val baseChance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 2.0, conquestCount = 1, lostSectCount = 0,
            battleWinCount = 1, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE,
            personality = AISectPersonality.BALANCED
        )
        val conservativeChance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 2.0, conquestCount = 1, lostSectCount = 0,
            battleWinCount = 1, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE,
            personality = AISectPersonality.CONSERVATIVE
        )
        assertTrue("保守型攻击概率应更低", conservativeChance <= baseChance)
    }

    @Test
    fun `alliance - conservative personality increases chance`() {
        val baseChance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ALLIANCE_PROFILE,
            powerRatio = 2.0, conquestCount = 1, lostSectCount = 0,
            battleWinCount = 1, battleLossCount = 0,
            favorLevel = SectRelationLevel.NORMAL,
            personality = AISectPersonality.BALANCED
        )
        val conservativeChance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ALLIANCE_PROFILE,
            powerRatio = 2.0, conquestCount = 1, lostSectCount = 0,
            battleWinCount = 1, battleLossCount = 0,
            favorLevel = SectRelationLevel.NORMAL,
            personality = AISectPersonality.CONSERVATIVE
        )
        assertTrue("保守型结盟概率应更高", conservativeChance >= baseChance)
    }

    // ═══════════════════════════════════
    // 脱离概率
    // ═══════════════════════════════════

    @Test
    fun `breakaway - high power ratio prevents breakaway`() {
        val chance = IntelligentSectDecisionEngine.calculateBreakawayChance(
            powerRatio = 5.0, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.NORMAL
        )
        assertTrue(chance <= 0.10)
    }

    @Test
    fun `breakaway - low power ratio increases breakaway`() {
        val chance = IntelligentSectDecisionEngine.calculateBreakawayChance(
            powerRatio = 1.2, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE
        )
        assertTrue(chance > 0.20)
    }

    @Test
    fun `breakaway - HOSTILE highest breakaway, INTIMATE lowest`() {
        val hostile = IntelligentSectDecisionEngine.calculateBreakawayChance(
            powerRatio = 1.5, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE
        )
        val intimate = IntelligentSectDecisionEngine.calculateBreakawayChance(
            powerRatio = 1.5, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.INTIMATE
        )
        assertTrue("HOSTILE 脱离概率应最高 > INTIMATE", hostile > intimate)
    }

    @Test
    fun `breakaway - capped at MAX_BREAKAWAY_CHANCE`() {
        val chance = IntelligentSectDecisionEngine.calculateBreakawayChance(
            powerRatio = 0.5, conquestCount = 0, lostSectCount = 100,
            battleWinCount = 0, battleLossCount = 100,
            favorLevel = SectRelationLevel.HOSTILE
        )
        assertTrue(chance <= SectDecisionConfig.Vassal.MAX_BREAKAWAY_CHANCE)
    }

    // ═══════════════════════════════════
    // 占领丢失和胜负因素
    // ═══════════════════════════════════

    @Test
    fun `attack - high conquest ratio increases chance`() {
        val highConquest = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 3.0, conquestCount = 10, lostSectCount = 0,
            battleWinCount = 5, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE
        )
        val lowConquest = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 3.0, conquestCount = 0, lostSectCount = 10,
            battleWinCount = 5, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE
        )
        assertTrue("高征服率应提高攻击概率", highConquest > lowConquest)
    }

    @Test
    fun `attack - high win rate increases chance`() {
        val highWin = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 3.0, conquestCount = 1, lostSectCount = 0,
            battleWinCount = 10, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE
        )
        val lowWin = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = 3.0, conquestCount = 1, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 10,
            favorLevel = SectRelationLevel.HOSTILE
        )
        assertTrue("高胜率应提高攻击概率", highWin > lowWin)
    }

    // ═══════════════════════════════════
    // 回归测试：与原有 VassalService 输出一致（等效 favor 值）
    // ═══════════════════════════════════

    @Test
    fun `vassal - favor 0 equivalent to HOSTILE`() {
        // 旧版 favor=0 → HOSTILE(0-19)
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.VASSAL_PROFILE,
            powerRatio = 2.0, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.HOSTILE
        )
        // HOSTILE→0.0, favorScore=0 → 回退到只有 powerScore
        assertEquals(0.20, chance, 0.001)
    }

    @Test
    fun `vassal - favor 50 equivalent to NORMAL`() {
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.VASSAL_PROFILE,
            powerRatio = 2.0, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.NORMAL
        )
        // powerScore=0.20, favorScore=0.4*0.15=0.06
        assertTrue(chance > 0.20)
    }

    // ═══════════════════════════════════
    // DecisionProfile 参数校验
    // ═══════════════════════════════════

    @Test(expected = IllegalArgumentException::class)
    fun `profile - negative powerWeight throws`() {
        DecisionProfile(
            powerWeight = -0.1, occupyWeight = 0.0, skirmishWeight = 0.0,
            favorWeight = 0.0, maxChance = 0.5, powerHardThreshold = 0.0,
            favorScoreByLevel = SectRelationLevel.entries.associateWith { 0.5 }
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `profile - maxChance over 1 throws`() {
        DecisionProfile(
            powerWeight = 0.25, occupyWeight = 0.25, skirmishWeight = 0.25,
            favorWeight = 0.25, maxChance = 1.5, powerHardThreshold = 0.0,
            favorScoreByLevel = SectRelationLevel.entries.associateWith { 0.5 }
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `profile - missing favor level throws`() {
        DecisionProfile(
            powerWeight = 0.25, occupyWeight = 0.25, skirmishWeight = 0.25,
            favorWeight = 0.25, maxChance = 0.9, powerHardThreshold = 0.0,
            favorScoreByLevel = mapOf(SectRelationLevel.HOSTILE to 1.0) // 只提供一个等级
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `profile - favorScore over 1 throws`() {
        DecisionProfile(
            powerWeight = 0.25, occupyWeight = 0.25, skirmishWeight = 0.25,
            favorWeight = 0.25, maxChance = 0.9, powerHardThreshold = 0.0,
            favorScoreByLevel = SectRelationLevel.entries.associateWith { 1.5 } // 超过 1
        )
    }
}
