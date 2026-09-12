package com.xianxia.sect.core.registry

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 验证 AffixDatabase.generateForDisciple 新分布：
 * 数量 0-5（35/35/20/6/3/1）、品阶四档（负面30/下品50/中品18/上品2）、
 * 负面真实出现、模板去重、确定性。
 */
class AffixDatabaseTest {

    private companion object {
        const val COUNT_SAMPLE = 1000
        const val DIST_SAMPLE = 20_000
        const val TOLERANCE = 0.01
    }

    @Test
    fun `generateForDisciple - count within 0 to 5 and no template duplicate`() {
        val rng = kotlin.random.Random(42)
        repeat(COUNT_SAMPLE) {
            val affixes = AffixDatabase.generateForDisciple(rng)
            assertTrue("数量应在 0-5, 实际: ${affixes.size}", affixes.size in 0..5)
            val templates = affixes.mapNotNull { AffixDatabase.getAffixDataById(it.id)?.template }
            assertEquals("模板不应重复（同模板不同品阶不可共存）", templates.size, templates.toSet().size)
        }
    }

    @Test
    fun `generateForDisciple - negative affix can appear`() {
        // 每特质负面 30%，平均每弟子 1.1 个词条 → 5000 次调用下负面词条几乎必然出现
        val rng = kotlin.random.Random(42)
        repeat(5000) {
            val affixes = AffixDatabase.generateForDisciple(rng)
            if (affixes.any { it.isNegative }) return
        }
        fail("5000 次调用后仍未出现负面词条")
    }

    @Test
    fun `generateForDisciple - rarity distribution matches config`() {
        val rng = kotlin.random.Random(42)
        val counts = intArrayOf(0, 0, 0, 0)
        var total = 0
        repeat(DIST_SAMPLE) {
            for (affix in AffixDatabase.generateForDisciple(rng)) {
                counts[affix.rarity.coerceIn(0, 3)]++
                total++
            }
        }
        // 按 item 统计：负面(0)30% / 下品(1)50% / 中品(2)18% / 上品(3)2%
        val expected = listOf(0.30, 0.50, 0.18, 0.02)
        for (rarity in 0..3) {
            val actual = counts[rarity].toDouble() / total
            assertTrue(
                "rarity=$rarity actual=$actual expected≈${expected[rarity]}",
                abs(actual - expected[rarity]) <= TOLERANCE
            )
        }
    }

    @Test
    fun `generateForDisciple - same seed produces identical result`() {
        val r1 = kotlin.random.Random(42)
        val r2 = kotlin.random.Random(42)
        assertEquals(
            AffixDatabase.generateForDisciple(r1).map { it.id },
            AffixDatabase.generateForDisciple(r2).map { it.id }
        )
    }
}
