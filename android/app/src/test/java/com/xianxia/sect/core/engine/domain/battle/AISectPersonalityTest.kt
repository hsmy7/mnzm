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

    // --- 随机分配不会返回 null ---

    @Test
    fun `random never returns null`() {
        repeat(100) {
            assertNotNull(AISectPersonality.random())
        }
    }

    // --- 随机分配覆盖所有类型 ---

    @Test
    fun `random covers all four types over large sample`() {
        val results = (1..1000).map { AISectPersonality.random() }
        val covered = results.toSet()
        assertEquals(
            "应覆盖全部4种个性",
            4, covered.size
        )
        assertTrue(covered.contains(AISectPersonality.AGGRESSIVE))
        assertTrue(covered.contains(AISectPersonality.BALANCED))
        assertTrue(covered.contains(AISectPersonality.CONSERVATIVE))
        assertTrue(covered.contains(AISectPersonality.RECLUSIVE))
    }

    // --- 随机分配权重分布合理（允许±10%误差） ---

    @Test
    fun `random distribution matches weights within tolerance`() {
        val samples = 10000
        val counts = (1..samples).map { AISectPersonality.random() }
            .groupingBy { it }.eachCount()

        for (personality in AISectPersonality.entries) {
            val expectedRatio = personality.weight / 100.0
            val actualRatio = (counts[personality] ?: 0) / samples.toDouble()
            val tolerance = 0.05 // ±5%
            assertTrue(
                "${personality.name} 权重=${personality.weight}% 实际=${
                    "%.1f".format(actualRatio * 100)
                }% 超出容差",
                kotlin.math.abs(actualRatio - expectedRatio) < tolerance
            )
        }
    }

    // --- 随机谴责间隔在配置范围内 ---

    @Test
    fun `randomDenounceInterval respects min and max for all types`() {
        for (personality in AISectPersonality.entries) {
            repeat(100) {
                val interval = AISectPersonality.randomDenounceInterval(personality)
                assertTrue(
                    "${personality.name}: $interval < ${personality.denounceIntervalMin}",
                    interval >= personality.denounceIntervalMin
                )
                assertTrue(
                    "${personality.name}: $interval > ${personality.denounceIntervalMax}",
                    interval <= personality.denounceIntervalMax
                )
            }
        }
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
