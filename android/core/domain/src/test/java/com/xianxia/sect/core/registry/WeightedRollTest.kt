package com.xianxia.sect.core.registry

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证弟子特质公共分布工具（WeightedRoll）：
 * 数量 0-5（35/35/20/6/3/1）、品阶四档（负面30/下品50/中品18/上品2）、确定性；
 * 洗炼/新增品阶三档（下品40/中品30/上品30，无负面）、确定性。
 */
class WeightedRollTest {

    private companion object {
        const val SAMPLE_SIZE = 100_000
        const val TOLERANCE = 0.01
    }

    @Test
    fun `rollTraitCount - large sample matches configured distribution`() {
        val rng = kotlin.random.Random(42)
        val counts = IntArray(6)
        repeat(SAMPLE_SIZE) { counts[rollTraitCount(rng)]++ }

        val expected = listOf(0.35, 0.35, 0.20, 0.06, 0.03, 0.01)
        for (count in 0..5) {
            val actual = counts[count].toDouble() / SAMPLE_SIZE
            assertTrue(
                "count=$count actual=$actual expected≈${expected[count]}",
                abs(actual - expected[count]) <= TOLERANCE
            )
        }
    }

    @Test
    fun `rollTraitQuality - large sample matches configured distribution`() {
        val rng = kotlin.random.Random(42)
        val counts = IntArray(4)
        repeat(SAMPLE_SIZE) { counts[rollTraitQuality(rng)]++ }

        val expected = listOf(0.30, 0.50, 0.18, 0.02)
        for (quality in 0..3) {
            val actual = counts[quality].toDouble() / SAMPLE_SIZE
            assertTrue(
                "quality=$quality actual=$actual expected≈${expected[quality]}",
                abs(actual - expected[quality]) <= TOLERANCE
            )
        }
    }

    @Test
    fun `rollWashTraitQuality - large sample matches configured distribution and never negative`() {
        // 洗炼/新增（玉符消耗玩法）品阶分布：下品40% / 中品30% / 上品30%，无负面（恒不返回 0）
        val rng = kotlin.random.Random(2026)
        val counts = IntArray(4)
        repeat(SAMPLE_SIZE) { counts[rollWashTraitQuality(rng)]++ }

        assertEquals("洗炼品阶分布不得包含负面（quality=0）", 0, counts[0])
        val expected = listOf(0.40, 0.30, 0.30)
        for (quality in 1..3) {
            val actual = counts[quality].toDouble() / SAMPLE_SIZE
            assertTrue(
                "quality=$quality actual=$actual expected≈${expected[quality - 1]}",
                abs(actual - expected[quality - 1]) <= TOLERANCE
            )
        }
    }

    @Test
    fun `rollTraitCount - weights sum to 1`() {
        assertEquals(1.0, DISCIPLE_TRAIT_COUNT_DISTRIBUTION.sumOf { it.second }, 1e-9)
    }

    @Test
    fun `rollTraitQuality - weights sum to 1`() {
        assertEquals(1.0, DISCIPLE_TRAIT_QUALITY_DISTRIBUTION.sumOf { it.second }, 1e-9)
    }

    @Test
    fun `rollWashTraitQuality - weights sum to 1`() {
        assertEquals(1.0, WASH_TRAIT_QUALITY_DISTRIBUTION.sumOf { it.second }, 1e-9)
    }

    @Test
    fun `rollTraitCount - same seed produces identical sequence`() {
        val r1 = kotlin.random.Random(7)
        val r2 = kotlin.random.Random(7)
        val s1 = List(1000) { rollTraitCount(r1) }
        val s2 = List(1000) { rollTraitCount(r2) }
        assertEquals(s1, s2)
    }

    @Test
    fun `rollTraitQuality - same seed produces identical sequence`() {
        val r1 = kotlin.random.Random(7)
        val r2 = kotlin.random.Random(7)
        val s1 = List(1000) { rollTraitQuality(r1) }
        val s2 = List(1000) { rollTraitQuality(r2) }
        assertEquals(s1, s2)
    }

    @Test
    fun `rollWashTraitQuality - same seed produces identical sequence`() {
        val r1 = kotlin.random.Random(7)
        val r2 = kotlin.random.Random(7)
        val s1 = List(1000) { rollWashTraitQuality(r1) }
        val s2 = List(1000) { rollWashTraitQuality(r2) }
        assertEquals(s1, s2)
    }
}
