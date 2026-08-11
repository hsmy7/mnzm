package com.xianxia.sect.ui.game

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData

/**
 * 计算弟子当前血量比例（含血炼 finalStats 口径）。
 *
 * 与引擎恢复/满血判定（battleWritebackMaxHpMp）同口径：maxHp 含血炼提升，
 * currentHp 为负数（满血哨兵，combatStats 缺失时亦然）视为满血。
 * maxHp <= 0 时返回 1f（与 HpMpBars 语义对齐）。
 *
 * 用静态 [DiscipleStatCalculator.getFinalStats] 而非 aggregate 实例方法——
 * 后者走 statsProvider 委托，在 feature/game 测试环境下是 no-op 假实现。
 */
internal fun discipleHpFraction(
    disciple: DiscipleAggregate,
    equipmentMap: Map<String, EquipmentInstance>,
    manualMap: Map<String, ManualInstance>,
    manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
    bloodRefinementPct: BloodRefinementPctTotal? = null
): Float {
    val finalStats = DiscipleStatCalculator.getFinalStats(
        disciple.toDisciple(), equipmentMap, manualMap, manualProficiencies, bloodRefinementPct
    )
    val maxHp = finalStats.maxHp
    if (maxHp <= 0) return 1f
    val currentHp = disciple.currentHp
    val effectiveHp = if (currentHp < 0) maxHp else currentHp
    return (effectiveHp.toFloat() / maxHp).coerceIn(0f, 1f)
}

/**
 * 判断战斗队伍中是否存在血量未满的弟子（含血炼口径，任一弟子比例 < 100% 即 true）。
 *
 * 用于手动进攻妖兽/宗门/洞府前的二次确认弹窗。
 *
 * @param disciples 参战队伍弟子
 * @param equipmentMap 装备实例映射
 * @param manualMap 功法实例映射
 * @param manualProficiencies 功法熟练度映射（按弟子 ID → 熟练度列表，即 gameData.manualProficiencies）
 * @param bloodRefinementPctTotals 血炼比例（按弟子 ID 索引）
 * @return true 表示队伍中存在血量未满弟子
 */
internal fun hasLowHpDisciple(
    disciples: List<DiscipleAggregate>,
    equipmentMap: Map<String, EquipmentInstance>,
    manualMap: Map<String, ManualInstance>,
    manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
    bloodRefinementPctTotals: Map<String, BloodRefinementPctTotal> = emptyMap()
): Boolean = disciples.any { disciple ->
    val discipleProficiencies =
        manualProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
    discipleHpFraction(
        disciple,
        equipmentMap,
        manualMap,
        discipleProficiencies,
        bloodRefinementPctTotals[disciple.id]
    ) < 1f
}
