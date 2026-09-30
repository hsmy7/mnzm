package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentSlot
import kotlin.random.Random

/**
 * 部位主词条池（装备重构 B3，方案 §3.4.2——**单一真源**，S15）。
 *
 * 主词条不按部位固定，生成时从该部位的候选池**等权随机**抽取一条（走
 * `RngPartition.EQUIPMENT`）。池的声明序参与抽取序列（`nextInt(size)` 索引
 * 抽取），禁止重排——重排会改变同种子抽取序列，破坏跨端对拍与存档确定性。
 *
 * 部位系数口径：`最终主词条值 = 品阶基数 × 部位系数 × 等级成长`；
 * 两套套装的部位池完全同构，流派差异全部由套装效果承担。
 *
 * 候选池与系数已拍板（2026-09-29）：武器 = 输出向（攻/暴率/暴伤，1.15）、
 * 腿部 = R9 原池（防/攻/暴率/暴伤/血，0.95）、手部保持 R9 原样（1.15）。
 */
object EquipMainStatPool {

    /** 部位 → 主词条候选池（声明序 = 抽取序，禁重排） */
    private val POOLS: Map<EquipmentSlot, List<EquipStat>> = mapOf(
        EquipmentSlot.HEAD to listOf(EquipStat.HP, EquipStat.DEFENSE),
        EquipmentSlot.BODY to listOf(EquipStat.DEFENSE, EquipStat.ATTACK, EquipStat.CRIT_RATE, EquipStat.CRIT_DAMAGE),
        EquipmentSlot.HANDS to listOf(EquipStat.ATTACK, EquipStat.CRIT_RATE, EquipStat.CRIT_DAMAGE),
        EquipmentSlot.FEET to listOf(
            EquipStat.DEFENSE, EquipStat.ATTACK, EquipStat.CRIT_RATE, EquipStat.CRIT_DAMAGE, EquipStat.HP
        ),
        EquipmentSlot.WEAPON to listOf(EquipStat.ATTACK, EquipStat.CRIT_RATE, EquipStat.CRIT_DAMAGE),
        EquipmentSlot.LEGS to listOf(
            EquipStat.DEFENSE, EquipStat.ATTACK, EquipStat.CRIT_RATE, EquipStat.CRIT_DAMAGE, EquipStat.HP
        )
    )

    /** 部位数值系数（生存向 1.00 / 均衡 1.00·0.95 / 输出向 1.15，方案 §3.4.2 表） */
    private val PART_COEFFICIENTS: Map<EquipmentSlot, Double> = mapOf(
        EquipmentSlot.HEAD to 1.00,
        EquipmentSlot.BODY to 1.00,
        EquipmentSlot.HANDS to 1.15,
        EquipmentSlot.FEET to 0.95,
        EquipmentSlot.WEAPON to 1.15,
        EquipmentSlot.LEGS to 0.95
    )

    /**
     * 主词条品阶基数表（品阶 1..6；暴击伤害主词条 = 暴击率 × 2，避免第二张表）。
     *
     * T6 档 flat 基数经 EQ-B4 校准 ×1.8（780→1404 / 7800→14040，中性源
     * `equipment_db_sample.json` 同步）：暴击/类型词条不进战力公式，T6 档
     * 百分比词条占比抬升压低 flat 贡献——40%±5% 战力占比锚（大乘期 T6
     * 满套中位，`EquipmentPowerParityTest`）据此回调；T1–T5 档校准实测
     * 已在带内，保持不动。
     */
    private val RARITY_BASE: Map<EquipStat, List<Double>> = mapOf(
        EquipStat.ATTACK to listOf(3.0, 9.0, 27.0, 84.0, 255.0, 1404.0),
        EquipStat.DEFENSE to listOf(3.0, 9.0, 27.0, 84.0, 255.0, 1404.0),
        EquipStat.HP to listOf(30.0, 90.0, 270.0, 840.0, 2550.0, 14040.0),
        EquipStat.CRIT_RATE to listOf(0.002, 0.006, 0.018, 0.056, 0.170, 0.520)
    )

    fun poolFor(part: EquipmentSlot): List<EquipStat> =
        POOLS.getValue(part)

    fun partCoefficient(part: EquipmentSlot): Double =
        PART_COEFFICIENTS.getValue(part)

    /** 主词条品阶基数（暴击伤害 = 暴击率同档 × 2）；非法品阶按最高档收敛 */
    fun baseValue(stat: EquipStat, rarity: Int): Double {
        val index = (rarity - 1).coerceIn(0, 5)
        val table = RARITY_BASE[stat] ?: RARITY_BASE.getValue(EquipStat.CRIT_RATE)
        val scale = if (stat == EquipStat.CRIT_DAMAGE) CRIT_DAMAGE_FROM_CRIT_RATE_SCALE else 1.0
        return table[index] * scale
    }

    /**
     * 抽取主词条（部位池内等权；`nextInt(size)` 索引抽取——双端以同一
     * DeterministicRng 序列对拍，算法禁改）。
     */
    fun rollMainStat(part: EquipmentSlot, rng: Random): EquipStat {
        val pool = poolFor(part)
        return pool[rng.nextInt(pool.size)]
    }

    /** 主词条完整词条值：品阶基数 × 部位系数（等级成长在读取侧乘 [com.xianxia.sect.core.model.EquipLevelCurve]） */
    fun mainStatValue(stat: EquipStat, part: EquipmentSlot, rarity: Int): EquipStatValue =
        EquipStatValue(stat, baseValue(stat, rarity) * partCoefficient(part))

    /** 暴击伤害主词条基数 = 暴击率基数 × 2 */
    private const val CRIT_DAMAGE_FROM_CRIT_RATE_SCALE = 2.0
}
