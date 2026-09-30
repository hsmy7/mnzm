package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 副词条池守卫（装备重构 B3，方案 §3.4.3/§6.1——含 [EquipAffixRollTest] 的
 * 抽取行为用例，合并交付）。
 *
 * 7 项全局池、权重即概率（合计 100）；抽取算法 = 剩余池权重前缀和 +
 * `nextInt(total)` 定点整数定位、不放回抽 3 条。**声明序参与前缀和比较，
 * 禁重排**——重排会改写同种子抽取序列，破坏跨端对拍与存档确定性。
 */
class EquipAffixPoolTest {

    @Test
    fun `池内容与声明序钉死（禁重排）`() {
        val expected = listOf(
            EquipStat.ATTACK,
            EquipStat.DEFENSE,
            EquipStat.HP,
            EquipStat.CRIT_RATE,
            EquipStat.CRIT_DAMAGE,
            EquipStat.PHYSICAL_DAMAGE_PCT,
            EquipStat.MAGIC_DAMAGE_PCT
        )
        assertEquals("副词条池内容或声明序被改动", expected, EquipAffixPool.all())
    }

    @Test
    fun `权重分布与方案拍板一致且合计100`() {
        // 13/13/14/15/15/15/15（方案 §3.4.3）
        val expectedWeights = listOf(
            EquipStat.ATTACK to 13,
            EquipStat.DEFENSE to 13,
            EquipStat.HP to 14,
            EquipStat.CRIT_RATE to 15,
            EquipStat.CRIT_DAMAGE to 15,
            EquipStat.PHYSICAL_DAMAGE_PCT to 15,
            EquipStat.MAGIC_DAMAGE_PCT to 15
        )
        expectedWeights.forEach { (stat, weight) ->
            assertEquals("$stat 权重", weight, EquipAffixPool.weightOf(stat))
        }
        assertEquals("权重合计", 100, EquipAffixPool.totalWeight())
    }

    @Test
    fun `池内不含速度灵力与攻击百分比的禁入维度`() {
        // S14/§15.7 Q10：装备不提供速度/灵力；ATTACK_PCT 仅套装/功法/丹药使用
        val forbidden = setOf(EquipStat.ATTACK_PCT)
        val pool = EquipAffixPool.all().toSet()
        assertTrue("ATTACK_PCT 不得进入副词条池", forbidden.none { it in pool })
        assertEquals("池应为 7 项全局池", 7, pool.size)
        // EquipStat 枚举本身已无速度/灵力维度（B1 单列重构），此处钉死防回潮
        assertTrue(
            "EquipStat 不得新增速度/灵力维度（S14）",
            EquipStat.entries.none { it.name == "SPEED" || it.name == "SPIRIT" }
        )
    }

    @Test
    fun `副词条档位值随品阶单调不减且越界收敛`() {
        EquipAffixPool.all().forEach { stat ->
            val tiers = (1..6).map { EquipAffixPool.tierValue(stat, it) }
            tiers.zipWithNext().forEach { (low, high) ->
                assertTrue("$stat 档位值须单调不减：$low -> $high", high >= low)
            }
            assertEquals(
                "$stat rarity=0 应收敛到最低档",
                tiers.first(), EquipAffixPool.tierValue(stat, 0), 1e-12
            )
            assertEquals(
                "$stat rarity=7 应收敛到最高档",
                tiers.last(), EquipAffixPool.tierValue(stat, 7), 1e-12
            )
        }
    }

    @Test
    fun `未知词条权重与档位值安全收敛`() {
        assertEquals("未知词条权重应为 0", 0, EquipAffixPool.weightOf(EquipStat.ATTACK_PCT))
        assertEquals("未知词条档位值应为 0.0", 0.0, EquipAffixPool.tierValue(EquipStat.ATTACK_PCT, 3), 1e-12)
    }

    @Test
    fun `抽取恒为3条且互不重复`() {
        repeat(300) { seed ->
            (1..6).forEach { rarity ->
                val rolled = EquipAffixPool.rollSubStats(rarity, Random(seed))
                assertEquals("seed=$seed rarity=$rarity 应恒抽 3 条", 3, rolled.size)
                assertEquals(
                    "seed=$seed rarity=$rarity 副词条应互不重复（不放回）",
                    rolled.size, rolled.map { it.stat }.distinct().size
                )
            }
        }
    }

    @Test
    fun `抽取值等于命中词条的品阶档位值`() {
        repeat(100) { seed ->
            val rolled = EquipAffixPool.rollSubStats(4, Random(seed))
            rolled.forEach { sv ->
                assertEquals(
                    "词条 ${sv.stat} 抽取值应等于 4 阶档位值",
                    EquipAffixPool.tierValue(sv.stat, 4), sv.value, 1e-12
                )
            }
        }
    }

    @Test
    fun `同种子抽取序列逐位稳定`() {
        val sequenceA = List(64) { EquipAffixPool.rollSubStats(3, Random(20260930)) }
        val sequenceB = List(64) { EquipAffixPool.rollSubStats(3, Random(20260930)) }
        assertEquals("同种子抽取序列漂移（算法或声明序被改）", sequenceA, sequenceB)
    }

    @Test
    fun `权重分布抽样符合权重即概率口径`() {
        // 1 万次抽取 × 3 条 = 3 万次定点抽取；固定种子下确定性执行
        val counts = mutableMapOf<EquipStat, Int>()
        repeat(10_000) { seed ->
            EquipAffixPool.rollSubStats(2, Random(seed)).forEach { sv ->
                counts.merge(sv.stat, 1, Int::plus)
            }
        }
        // 期望频次 = 权重/100 × 30000；下界给足冗余防种子巧合（确定性执行，非统计波动风险）
        val expected = mapOf(
            EquipStat.ATTACK to 3900L,
            EquipStat.DEFENSE to 3900L,
            EquipStat.HP to 4200L,
            EquipStat.CRIT_RATE to 4500L,
            EquipStat.CRIT_DAMAGE to 4500L,
            EquipStat.PHYSICAL_DAMAGE_PCT to 4500L,
            EquipStat.MAGIC_DAMAGE_PCT to 4500L
        )
        expected.forEach { (stat, mean) ->
            val actual = counts[stat] ?: 0
            assertTrue(
                "$stat 抽样频次 $actual 偏离期望 $mean 过远（权重序或抽取算法被改）",
                actual in (mean * 0.75).toLong()..(mean * 1.25).toLong()
            )
        }
    }
}
