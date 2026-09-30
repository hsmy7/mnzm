package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import kotlin.random.Random

/**
 * 副词条池（装备重构 B3，方案 §3.4.3/§15.6——**单一真源**）。
 *
 * 7 项全局池、与流派无关；权重即概率（合计 100 = 直接概率）：
 * 攻击力 13 / 防御力 13 / 血量 14 / 暴击率 15 / 暴击伤害 15 /
 * 物理伤害加成 15 / 法术伤害加成 15。生成时按权重**不放回**抽 3 条
 * （天然去重，S5）；单次强化收益 = 档位值 × 1（每 3 级 +1，最多 11 次）。
 *
 * **声明序参与权重前缀和比较，禁止重排**——重排会改变同种子抽取序列，
 * 破坏跨端对拍与既有存档确定性（方案 §3.4.3；C++ 对偶表逐位同序）。
 *
 * 抽取算法（双端逐位一致，禁改）：每次在剩余池上按权重前缀和做
 * `nextInt(totalWeight)` 定点整数抽取，抽中即从池移除，重复 3 次。
 */
object EquipAffixPool {

    /** 副词条池条目（声明序 = 权重前缀和序，禁重排） */
    private class AffixDef(
        val stat: EquipStat,
        val weight: Int,
        /** 品阶 1..6 单次强化档位值 */
        val tierValues: List<Double>
    )

    private val AFFIXES: List<AffixDef> = listOf(
        AffixDef(EquipStat.ATTACK, 13, listOf(1.0, 3.0, 8.0, 21.0, 64.0, 195.0)),
        AffixDef(EquipStat.DEFENSE, 13, listOf(1.0, 3.0, 6.0, 14.0, 43.0, 130.0)),
        AffixDef(EquipStat.HP, 14, listOf(14.0, 40.0, 106.0, 280.0, 860.0, 2600.0)),
        AffixDef(EquipStat.CRIT_RATE, 15, listOf(0.002, 0.003, 0.004, 0.006, 0.008, 0.010)),
        AffixDef(EquipStat.CRIT_DAMAGE, 15, listOf(0.004, 0.006, 0.008, 0.012, 0.016, 0.020)),
        AffixDef(EquipStat.PHYSICAL_DAMAGE_PCT, 15, listOf(0.004, 0.006, 0.008, 0.012, 0.016, 0.020)),
        AffixDef(EquipStat.MAGIC_DAMAGE_PCT, 15, listOf(0.004, 0.006, 0.008, 0.012, 0.016, 0.020))
    )

    /** 每件装备副词条条数 */
    const val SUB_STAT_COUNT = 3

    fun all(): List<EquipStat> = AFFIXES.map { it.stat }

    fun weightOf(stat: EquipStat): Int = AFFIXES.find { it.stat == stat }?.weight ?: 0

    fun totalWeight(): Int = AFFIXES.sumOf { it.weight }

    /** 副词条档位值（品阶 1..6；越界按最高档收敛） */
    fun tierValue(stat: EquipStat, rarity: Int): Double {
        val def = AFFIXES.find { it.stat == stat } ?: return 0.0
        return def.tierValues[(rarity - 1).coerceIn(0, 5)]
    }

    /**
     * 按权重不放回抽取 [SUB_STAT_COUNT] 条副词条（初始强化次数恒 1）。
     * 算法：剩余池权重前缀和 + `nextInt(total)` 定点整数定位，抽中即移除。
     */
    fun rollSubStats(rarity: Int, rng: Random): List<EquipStatValue> {
        val remaining = AFFIXES.toMutableList()
        return buildList {
            repeat(SUB_STAT_COUNT) {
                if (remaining.isEmpty()) return@buildList
                val total = remaining.sumOf { it.weight }
                var roll = rng.nextInt(total)
                var picked = remaining.last()
                for (def in remaining) {
                    if (roll < def.weight) {
                        picked = def
                        break
                    }
                    roll -= def.weight
                }
                remaining.remove(picked)
                add(EquipStatValue(picked.stat, picked.tierValues[(rarity - 1).coerceIn(0, 5)]))
            }
        }
    }
}
