package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.registry.EquipmentSetDatabase

/**
 * 装备加成汇合点（装备重构 B3，方案 §3.10——**唯一结算入口**）。
 *
 * 输入：弟子六槽位实例的 `totalBonus()` + 套装 2/4/6 档效果；输出 [EquipBonus]。
 * Kotlin 消费点：`DiscipleStatCalculator属性Ops3/4`；C++ 对偶 `disciple_stats.h`
 * 逐位一致（`DiffEquipmentSetBonusTest`/`DiffEquipmentStatTest`）。
 *
 * 乘区口径（方案 §3.4.1）：`atk = (baseAtk + Σ装备flatAtk) × (1 + Σ装备atkPct) +
 * Σ功法flat + Σ丹药flat`——装备乘区只放大装备自身贡献，功法/丹药加法序逐位不变。
 */
data class EquipBonus(
    val flatAttack: Double = 0.0,
    val flatDefense: Double = 0.0,
    val flatHp: Double = 0.0,
    val pctAttack: Double = 0.0,
    val critRate: Double = 0.0,
    val critDamage: Double = 0.0,
    val physicalDamageBonus: Double = 0.0,
    val magicDamageBonus: Double = 0.0
) {
    operator fun plus(other: EquipBonus): EquipBonus = EquipBonus(
        flatAttack = flatAttack + other.flatAttack,
        flatDefense = flatDefense + other.flatDefense,
        flatHp = flatHp + other.flatHp,
        pctAttack = pctAttack + other.pctAttack,
        critRate = critRate + other.critRate,
        critDamage = critDamage + other.critDamage,
        physicalDamageBonus = physicalDamageBonus + other.physicalDamageBonus,
        magicDamageBonus = magicDamageBonus + other.magicDamageBonus
    )
}

object EquipStatResolver {

    /**
     * 解析六件已装备实例（含词条）+ 命中套装档位 → [EquipBonus]。
     *
     * @param equippedInstances 弟子六槽位的实例（按槽位 id 从实例表取到的非空件）
     */
    fun resolve(equippedInstances: List<EquipmentInstance>): EquipBonus {
        var bonus = EquipBonus()
        for (instance in equippedInstances) {
            for (statValue in instance.totalBonus()) {
                // 🔴 禁写 `bonus += plusStat(bonus, sv)`：plusStat 已折入 current，
                // 复合赋值 = 「旧值×2 + sv」（EQ-B2 同款 Kotlin 坑复发，测试实证）
                bonus = plusStat(bonus, statValue)
            }
        }
        bonus += resolveSetBonus(equippedInstances)
        return bonus
    }

    /** 单条词条并入（求和口径，双端逐位一致） */
    private fun plusStat(current: EquipBonus, statValue: EquipStatValue): EquipBonus {
        val v = statValue.value
        return when (statValue.stat) {
            EquipStat.ATTACK -> current.copy(flatAttack = current.flatAttack + v)
            EquipStat.DEFENSE -> current.copy(flatDefense = current.flatDefense + v)
            EquipStat.HP -> current.copy(flatHp = current.flatHp + v)
            EquipStat.CRIT_RATE -> current.copy(critRate = current.critRate + v)
            EquipStat.CRIT_DAMAGE -> current.copy(critDamage = current.critDamage + v)
            EquipStat.ATTACK_PCT -> current.copy(pctAttack = current.pctAttack + v)
            EquipStat.PHYSICAL_DAMAGE_PCT -> current.copy(physicalDamageBonus = current.physicalDamageBonus + v)
            EquipStat.MAGIC_DAMAGE_PCT -> current.copy(magicDamageBonus = current.magicDamageBonus + v)
        }
    }

    /**
     * 套装档位（按 setId 统计件数，2/4/6 达档即生效、可越级不叠加——
     * 穿满 6 件时 2/4/6 三档同时生效；0.2-7：同类百分比相加）。
     * 同一 setId 只统计六槽位内件数（去重由 EquipmentDedupeRule 保证独占）。
     */
    fun resolveSetBonus(equippedInstances: List<EquipmentInstance>): EquipBonus {
        var bonus = EquipBonus()
        val countBySet = equippedInstances
            .filter { it.setId.isNotEmpty() }
            .groupingBy { it.setId }
            .eachCount()
        for ((setId, count) in countBySet) {
            val def = EquipmentSetDatabase.getById(setId) ?: continue
            for (tier in def.activeBonuses(count)) {
                for (statValue in tier.entries) {
                    bonus = plusStat(bonus, statValue)
                }
            }
        }
        return bonus
    }
}
