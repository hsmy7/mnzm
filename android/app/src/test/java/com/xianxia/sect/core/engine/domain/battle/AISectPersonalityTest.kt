package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.model.AISectPersonality
import org.junit.Assert.*
import org.junit.Test

class AISectPersonalityTest {

    // --- 枚举值存在性 ---

    @Test
    fun `four personality types exist`() {
        assertEquals(4, AISectPersonality.entries.size)
        assertNotNull(AISectPersonality.valueOf("AGGRESSIVE"))
        assertNotNull(AISectPersonality.valueOf("BALANCED"))
        assertNotNull(AISectPersonality.valueOf("CONSERVATIVE"))
        assertNotNull(AISectPersonality.valueOf("RECLUSIVE"))
    }

    // --- 权重总和 ---

    @Test
    fun `all weights sum to 100`() {
        val totalWeight = AISectPersonality.entries.sumOf { it.weight }
        assertEquals(100, totalWeight)
    }

    // --- 战力比门槛渐进性 ---

    @Test
    fun `powerRatioThreshold increases from aggressive to reclusive`() {
        val thresholds = AISectPersonality.entries.map { it.powerRatioThreshold }
        for (i in 1 until thresholds.size) {
            assertTrue(
                "战力比门槛应递增: ${thresholds[i - 1]} >= ${thresholds[i]}",
                thresholds[i - 1] <= thresholds[i]
            )
        }
    }

    // --- 好战型门槛最低 — 可攻击更强对手 ---

    @Test
    fun `AGGRESSIVE has lowest powerRatioThreshold`() {
        val min = AISectPersonality.entries.minBy { it.powerRatioThreshold }
        assertEquals(AISectPersonality.AGGRESSIVE, min)
    }

    // --- 隐世型门槛最高 — 只攻击极弱者 ---

    @Test
    fun `RECLUSIVE has highest powerRatioThreshold`() {
        val max = AISectPersonality.entries.maxBy { it.powerRatioThreshold }
        assertEquals(AISectPersonality.RECLUSIVE, max)
    }

    // --- 好战型宣战概率最高 ---

    @Test
    fun `AGGRESSIVE has highest warProbability`() {
        val max = AISectPersonality.entries.maxBy { it.warProbability }
        assertEquals(AISectPersonality.AGGRESSIVE, max)
    }

    // --- 隐世型宣战概率最低 ---

    @Test
    fun `RECLUSIVE has lowest warProbability`() {
        val min = AISectPersonality.entries.minBy { it.warProbability }
        assertEquals(AISectPersonality.RECLUSIVE, min)
    }

    // --- 攻击冷却月数递增 ---

    @Test
    fun `attackCooldownMonths increases from aggressive to reclusive`() {
        val cooldowns = AISectPersonality.entries.map { it.attackCooldownMonths }
        for (i in 1 until cooldowns.size) {
            assertTrue(
                "冷却月数应递增",
                cooldowns[i - 1] <= cooldowns[i]
            )
        }
    }
}
