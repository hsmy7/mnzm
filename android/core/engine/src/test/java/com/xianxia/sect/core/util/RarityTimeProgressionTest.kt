package com.xianxia.sect.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RarityTimeProgression] 年份→品阶权重曲线测试。
 *
 * 关键设计数据点（用户需求验证）：
 * - 第 20 年（凡~灵段起点）：凡品 90% 灵品 10%
 * - 第 50 年（分段中点）：凡品 50% 灵品 50%
 */
class RarityTimeProgressionTest {

    // ── maxRarityForYear 分段边界 ──────────────────────────────────────

    @Test
    fun `maxRarityForYear - 分段边界年份正确`() {
        val cases = mapOf(
            0 to 1, -1 to 1, 1 to 1, 19 to 1, 20 to 2, 79 to 2, 80 to 3, 299 to 3,
            300 to 4, 499 to 4, 500 to 5, 1499 to 5, 1500 to 6, 2999 to 6,
            3000 to 6, 4000 to 6, Int.MAX_VALUE to 6
        )
        for ((year, expected) in cases) {
            assertEquals("year=$year", expected, RarityTimeProgression.maxRarityForYear(year))
        }
    }

    // ── pityRarityForYear 保底品阶（下一阶段最高品阶） ────────────────────

    @Test
    fun `pityRarityForYear - 保底为下一阶段最高品阶`() {
        val cases = mapOf(
            5 to 2,   // [1,20) → 下一段 [20,80) 最高品阶 灵品
            60 to 3,  // [20,80) → 下一段 [80,300) 最高品阶 宝品（用户例子）
            250 to 4, // [80,300) → 玄品
            400 to 5, // [300,500) → 地品
            1000 to 6, // [500,1500) → 天品
            2000 to 6 // 末段 → 自身最高品阶 天品
        )
        for ((year, expected) in cases) {
            assertEquals("year=$year", expected, RarityTimeProgression.pityRarityForYear(year))
        }
    }

    @Test
    fun `pityRarityForYear - 3000年后保底必出天品`() {
        // 用户需求：超出 3000 年后保底必出天品（末段 [1500,∞) 保底取自身最高品阶 6）
        for (year in listOf(3001, 3500, 4000, 5000, 10000)) {
            assertEquals("year=$year", 6, RarityTimeProgression.pityRarityForYear(year))
        }
    }

    // ── 权重数据点（用户需求验证） ──────────────────────────────────────

    @Test
    fun `rarityWeightsForYear - 第20年凡品90灵品10（分段起点）`() {
        val w = weights(20)
        assertEquals(0.9, w[1]!!, 1e-9)
        assertEquals(0.1, w[2]!!, 1e-9)
        assertEquals(2, w.size)
    }

    @Test
    fun `rarityWeightsForYear - 第50年凡品50灵品50（分段中点）`() {
        val w = weights(50)
        assertEquals(0.5, w[1]!!, 1e-9)
        assertEquals(0.5, w[2]!!, 1e-9)
    }

    @Test
    fun `rarityWeightsForYear - 第19年纯凡品（除零回归）`() {
        val w = weights(19)
        assertEquals(mapOf(1 to 1.0), w)
    }

    @Test
    fun `rarityWeightsForYear - 第80年宝品段起点凡品90`() {
        val w = weights(80)
        assertEquals(0.9, w[1]!!, 1e-9)
        // 其余 0.1 按幂份额：灵品 4/5、宝品 1/5
        assertEquals(0.08, w[2]!!, 1e-9)
        assertEquals(0.02, w[3]!!, 1e-9)
    }

    @Test
    fun `rarityWeightsForYear - 第1500年天品段起点天品约0_18`() {
        val w = weights(1500)
        assertEquals(0.9, w[1]!!, 1e-9)
        // 其余共享 0.1，幂份额 25:16:9:4:1，天品 = 0.1 × 1/55（天品刚开放，极稀有）
        assertEquals(0.1 / 55, w[6]!!, 1e-9)
    }

    @Test
    fun `rarityWeightsForYear - 第3000年天品段终点天品约1_64`() {
        val w = weights(3000)
        assertEquals(0.1, w[1]!!, 1e-9)
        // 段终点凡品降至 0.1，其余共享 0.9，天品 = 0.9 × 1/55
        assertEquals(0.9 / 55, w[6]!!, 1e-9)
    }

    // ── 高品阶稀有性约束 ────────────────────────────────────────────────

    @Test
    fun `rarityWeightsForYear - 天品概率全年份不超过2`() {
        var year = 1
        while (year <= 20000) {
            val w = weights(year)
            val w6 = w[6] ?: 0.0
            assertTrue("year=$year w6=$w6", w6 <= 0.02)
            year += 17 // 步进覆盖全范围
        }
    }

    @Test
    fun `rarityWeightsForYear - 3000年后天品非降凡品非增`() {
        var prevW6 = weights(3000).getValue(6)
        var prevW1 = weights(3000).getValue(1)
        var year = 3100
        while (year <= 5000) {
            val w = weights(year)
            assertTrue("year=$year", w.getValue(6) >= prevW6 - 1e-12)
            assertTrue("year=$year", w.getValue(1) <= prevW1 + 1e-12)
            prevW6 = w.getValue(6)
            prevW1 = w.getValue(1)
            year += 100
        }
    }

    @Test
    fun `rarityWeightsForYear - 大年份天品封顶后权重稳定`() {
        val w3000 = weights(3000)
        val w10000 = weights(10000)
        val w20000 = weights(20000)
        assertEquals("封顶后完全稳定", w10000, w20000)
        assertTrue("比 3000 年提升", w10000.getValue(6) > w3000.getValue(6))
        assertTrue("不超上限", w10000.getValue(6) <= 0.02)
        // 凡品不跌破下限保护
        assertTrue("凡品下限保护", w10000.getValue(1) >= 0.04)
        assertTrue("灵品下限保护", w10000.getValue(2) >= 0.15)
    }

    // ── 权重表结构约束 ──────────────────────────────────────────────────

    @Test
    fun `rarityWeightsForYear - 权重和为1`() {
        for (year in listOf(1, 20, 50, 80, 150, 300, 400, 500, 1000, 1500, 2250, 3000, 3200, 4000, 10000)) {
            val w = weights(year)
            assertEquals("year=$year sum=${w.values.sum()}", 1.0, w.values.sum(), 1e-9)
        }
    }

    @Test
    fun `rarityWeightsForYear - 键集恰为1到maxRarity`() {
        for (year in listOf(1, 19, 20, 79, 80, 299, 300, 499, 500, 1499, 1500, 3000, 5000)) {
            val w = weights(year)
            val max = RarityTimeProgression.maxRarityForYear(year)
            assertEquals("year=$year", (1..max).toSet(), w.keys)
        }
    }

    @Test
    fun `rarityWeightsForYear - 段内凡品权重线性递减`() {
        // 3 品阶段 [80,300) 内抽查：t=0/0.25/0.5/0.75 → w1 = 0.9/0.7/0.5/0.3
        val cases = mapOf(
            80 to 0.9, 135 to 0.7, 190 to 0.5, 245 to 0.3
        )
        for ((year, expected) in cases) {
            assertEquals("year=$year", expected, weights(year).getValue(1), 1e-9)
        }
        // 299 年为 3 品阶段内最后一年（300 年进入玄品段后权重重置，段间不连续）
        val t299 = (299 - 80) / 220.0
        assertEquals(0.9 - 0.8 * t299, weights(299).getValue(1), 1e-9)
    }

    // ── rollRarity 确定性 ──────────────────────────────────────────────

    @Test
    fun `rollRarity - 同种子同年份两次逐位一致`() {
        val rng1 = DeterministicRng.fromSeed(12345L)
        val rng2 = DeterministicRng.fromSeed(12345L)
        val seq1 = (1..1000).map { RarityTimeProgression.rollRarity(rng1, 50) }
        val seq2 = (1..1000).map { RarityTimeProgression.rollRarity(rng2, 50) }
        assertEquals(seq1, seq2)
    }

    @Test
    fun `rollRarity - 同种子不同年份序列不同（年份参与输出）`() {
        val rng1 = DeterministicRng.fromSeed(12345L)
        val rng2 = DeterministicRng.fromSeed(12345L)
        val seq1 = (1..200).map { RarityTimeProgression.rollRarity(rng1, 50) }
        val seq2 = (1..200).map { RarityTimeProgression.rollRarity(rng2, 500) }
        assertNotEquals(seq1, seq2)
    }

    @Test
    fun `rollRarity - 任意年份结果不超maxRarity`() {
        for (year in listOf(1, 19, 20, 50, 80, 300, 500, 1500, 3000, 10000)) {
            val rng = DeterministicRng.fromSeed(year * 7919L)
            val max = RarityTimeProgression.maxRarityForYear(year)
            repeat(5000) {
                val roll = RarityTimeProgression.rollRarity(rng, year)
                assertTrue("year=$year roll=$roll max=$max", roll in 1..max)
            }
        }
    }

    @Test
    fun `rollRarity - 1至19年全部凡品`() {
        for (year in 1..19) {
            val rng = DeterministicRng.fromSeed(year * 131L)
            repeat(500) {
                assertEquals("year=$year", 1, RarityTimeProgression.rollRarity(rng, year))
            }
        }
    }

    @Test
    fun `rollRarity - 第50年凡品频率接近50`() {
        val rng = DeterministicRng.fromSeed(987654321L)
        var count1 = 0
        val total = 20000
        repeat(total) { if (RarityTimeProgression.rollRarity(rng, 50) == 1) count1++ }
        val freq = count1.toDouble() / total
        assertTrue("freq=$freq", freq in 0.47..0.53)
    }

    private fun weights(year: Int): Map<Int, Double> = RarityTimeProgression.rarityWeightsForYear(year)
}
