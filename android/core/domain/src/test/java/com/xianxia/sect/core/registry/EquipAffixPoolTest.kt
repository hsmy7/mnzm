package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 副词条池守卫（五行属性伤害系统，方案 §3.5/§6.1——E8）。
 *
 * 11 项全局池、权重即概率（合计 100）：攻击 12 / 防御 12 / 血量 13 / 暴率 13 /
 * 暴伤 13 / 物理 7 / 金 6 / 木 6 / 水 6 / 火 6 / 土 6；抽取算法 = 剩余池权重
 * 前缀和 + `nextInt(total)` 定点整数定位、不放回抽 3 条。**声明序参与前缀和
 * 比较，禁重排**——重排会改写同种子抽取序列，破坏跨端对拍与存档确定性。
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
            EquipStat.METAL_DAMAGE_PCT,
            EquipStat.WOOD_DAMAGE_PCT,
            EquipStat.WATER_DAMAGE_PCT,
            EquipStat.FIRE_DAMAGE_PCT,
            EquipStat.EARTH_DAMAGE_PCT
        )
        assertEquals("副词条池内容或声明序被改动", expected, EquipAffixPool.all())
    }

    @Test
    fun `权重分布与方案拍板一致且合计100`() {
        // 12/12/13/13/13/7/6/6/6/6/6 = 100（方案 §3.5）
        val expectedWeights = listOf(
            EquipStat.ATTACK to 12,
            EquipStat.DEFENSE to 12,
            EquipStat.HP to 13,
            EquipStat.CRIT_RATE to 13,
            EquipStat.CRIT_DAMAGE to 13,
            EquipStat.PHYSICAL_DAMAGE_PCT to 7,
            EquipStat.METAL_DAMAGE_PCT to 6,
            EquipStat.WOOD_DAMAGE_PCT to 6,
            EquipStat.WATER_DAMAGE_PCT to 6,
            EquipStat.FIRE_DAMAGE_PCT to 6,
            EquipStat.EARTH_DAMAGE_PCT to 6
        )
        expectedWeights.forEach { (stat, weight) ->
            assertEquals("$stat 权重", weight, EquipAffixPool.weightOf(stat))
        }
        assertEquals("权重合计", 100, EquipAffixPool.totalWeight())
    }

    @Test
    fun `池内不含速度灵力攻击百分比与退役段`() {
        // S14/§15.7 Q10：装备不提供速度/灵力；ATTACK_PCT 仅套装/功法/丹药使用；
        // MAGIC_DAMAGE_PCT 为退役段（方案 P1），不得再进池产新装备
        val forbidden = setOf(EquipStat.ATTACK_PCT, EquipStat.MAGIC_DAMAGE_PCT)
        val pool = EquipAffixPool.all().toSet()
        assertTrue("退役段/禁入维度不得进入副词条池", forbidden.none { it in pool })
        assertEquals("池应为 11 项全局池", 11, pool.size)
        assertTrue(
            "EquipStat 不得新增速度/灵力维度（S14）",
            EquipStat.entries.none { it.name == "SPEED" || it.name == "SPIRIT" }
        )
    }

    @Test
    fun `类型伤害词条六条同档位值`() {
        val typeStats = listOf(
            EquipStat.PHYSICAL_DAMAGE_PCT,
            EquipStat.METAL_DAMAGE_PCT,
            EquipStat.WOOD_DAMAGE_PCT,
            EquipStat.WATER_DAMAGE_PCT,
            EquipStat.FIRE_DAMAGE_PCT,
            EquipStat.EARTH_DAMAGE_PCT
        )
        val expectedTiers = listOf(0.006, 0.009, 0.012, 0.018, 0.024, 0.030)
        typeStats.forEach { stat ->
            val tiers = (1..6).map { EquipAffixPool.tierValue(stat, it) }
            assertEquals("$stat 六档位值应与方案 §3.5 ×1.5 补偿档一致", expectedTiers, tiers)
        }
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
        assertEquals("退役段权重应为 0", 0, EquipAffixPool.weightOf(EquipStat.MAGIC_DAMAGE_PCT))
        assertEquals("退役段档位值应为 0.0", 0.0, EquipAffixPool.tierValue(EquipStat.MAGIC_DAMAGE_PCT, 3), 1e-12)
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
            EquipStat.ATTACK to 3600L,
            EquipStat.DEFENSE to 3600L,
            EquipStat.HP to 3900L,
            EquipStat.CRIT_RATE to 3900L,
            EquipStat.CRIT_DAMAGE to 3900L,
            EquipStat.PHYSICAL_DAMAGE_PCT to 2100L,
            EquipStat.METAL_DAMAGE_PCT to 1800L,
            EquipStat.WOOD_DAMAGE_PCT to 1800L,
            EquipStat.WATER_DAMAGE_PCT to 1800L,
            EquipStat.FIRE_DAMAGE_PCT to 1800L,
            EquipStat.EARTH_DAMAGE_PCT to 1800L
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
