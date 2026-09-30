package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipLevelCurve
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.registry.EquipAffixPool
import com.xianxia.sect.data.model.SaveData

/**
 * 装备实例数值消毒（装备重构 B3，方案 §3.6/§6.1 `EquipmentValueSanitizeRuleTest`）。
 *
 * 修复口径（双端逐位一致，**截断用 coerce 而非 clamp 后归一**）：
 * - `level` coerce 到 1..30；`exp` coerce 到 `0..expRequired(level)-1`
 *   （满级收敛 0、不再累计——S4）；
 * - 副词条恒 3 条、互不重复（重复保首条）、`stat` 合法；`subRolls` 与词条等长、
 *   各 coerce 到 1..11；
 * - `part` 合法六部位（未知值由 JsonConverters 回退 HEAD，此处不再处理）；
 * - 修复幂等（修复一次后再跑恒 Passed）。
 *
 * 无损坏实例时恒 [RuleOutcome.Passed] 不落盘（B2「无存量恒 Passed」先例）。
 */
object EquipmentValueSanitizeRule : SaveValidationRule {
    override val id = "equipment_value_sanitize"
    override val order = 28

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        var repaired = false
        val instances = data.equipmentInstances.map { instance ->
            sanitize(instance) { repaired = true } ?: instance
        }
        return if (repaired) {
            RuleOutcome.Repaired(
                data.copy(equipmentInstances = instances),
                listOf("装备实例数值越界已按 coerce 口径修正（等级/经验/副词条/强化次数）")
            )
        } else {
            RuleOutcome.Passed
        }
    }

    /** 消毒单实例；无改动返回 null（避免无谓对象分配） */
    private fun sanitize(instance: EquipmentInstance, onRepaired: () -> Unit): EquipmentInstance? {
        val growth = instance.growth
        val level = growth.level.coerceIn(EquipLevelCurve.MIN_LEVEL, EquipLevelCurve.MAX_LEVEL)
        val expMax = if (level >= EquipLevelCurve.MAX_LEVEL) 0
        else EquipLevelCurve.expRequired(level, instance.rarity) - 1
        val exp = growth.exp.coerceIn(0, expMax)
        val affix = sanitizeAffix(growth.affix)
        if (level == growth.level && exp == growth.exp && affix == growth.affix) return null
        onRepaired()
        return instance.copy(growth = growth.copy(level = level, exp = exp, affix = affix))
    }

    /** 副词条消毒：恒 3 条去重 + 强化次数越界收敛 */
    private fun sanitizeAffix(affix: EquipAffixSet): EquipAffixSet {
        val seenStats = mutableSetOf<EquipStat>()
        val kept = ArrayList<EquipStatValue>(EquipAffixPool.SUB_STAT_COUNT)
        val keptRolls = ArrayList<Int>(EquipAffixPool.SUB_STAT_COUNT)
        for ((index, sub) in affix.subStats.withIndex()) {
            if (kept.size < EquipAffixPool.SUB_STAT_COUNT && seenStats.add(sub.stat)) {
                kept.add(sub)
                keptRolls.add(affix.subRolls.getOrElse(index) { 1 }.coerceIn(1, EquipLevelCurve.MAX_SUB_ROLLS))
            }
        }
        val sanitized = EquipAffixSet(
            mainStat = affix.mainStat,
            subStats = kept.toList(),
            subRolls = keptRolls.toList()
        )
        return if (sanitized == affix) affix else sanitized
    }

    /** 部位枚举合法性（供测试与调用面复用；规则本体经 JsonConverters 已兜底） */
    fun isValidPart(name: String): Boolean =
        EquipmentSlot.entries.any { it.name == name }
}
