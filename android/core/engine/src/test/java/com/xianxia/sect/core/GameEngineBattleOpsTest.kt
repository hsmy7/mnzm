package com.xianxia.sect.core

import com.xianxia.sect.core.engine.sectBattleRewardCount
import com.xianxia.sect.core.util.DeterministicRng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-01 分区 RNG 迁移守卫：sectBattleRewardCount 确定性 + 值域 + 分布覆盖。
 */
class GameEngineBattleOpsTest {

    private val occupyRange = 80..130
    private val routRange = 20..60

    @Test
    fun `sectBattleRewardCount - occupy victory stays within 80 to 130`() {
        val rng = DeterministicRng.fromSeed(42)
        repeat(SAMPLES) {
            val value = sectBattleRewardCount(canOccupy = true, rng = rng)
            assertTrue("occupy reward $value out of $occupyRange", value in occupyRange)
        }
    }

    @Test
    fun `sectBattleRewardCount - rout victory stays within 20 to 60`() {
        val rng = DeterministicRng.fromSeed(42)
        repeat(SAMPLES) {
            val value = sectBattleRewardCount(canOccupy = false, rng = rng)
            assertTrue("rout reward $value out of $routRange", value in routRange)
        }
    }

    @Test
    fun `sectBattleRewardCount - same seed produces same sequence`() {
        val rng1 = DeterministicRng.fromSeed(20260805)
        val rng2 = DeterministicRng.fromSeed(20260805)
        val seq1 = List(SAMPLES) { sectBattleRewardCount(it % 2 == 0, rng1) }
        val seq2 = List(SAMPLES) { sectBattleRewardCount(it % 2 == 0, rng2) }
        assertEquals("same seed must reproduce identical reward sequence", seq1, seq2)
    }

    @Test
    fun `sectBattleRewardCount - different seeds can produce different values`() {
        val values = (1..20).map { seed ->
            sectBattleRewardCount(canOccupy = true, rng = DeterministicRng.fromSeed(seed.toLong()))
        }
        assertTrue("20 different seeds should not all produce identical rewards", values.distinct().size > 1)
    }

    private companion object {
        const val SAMPLES = 200
    }
}
