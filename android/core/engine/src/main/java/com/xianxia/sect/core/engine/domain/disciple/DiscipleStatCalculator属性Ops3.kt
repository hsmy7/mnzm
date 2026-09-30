@file:Suppress("TooManyFunctions") // 拆分域文件（B1 先例口径）：函数数=装备接线增量后的属性计算协议面，文件本身即拆分产物，再拆只会碎片化

package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleAttributes
import com.xianxia.sect.core.model.DiscipleCombatStats
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.PillEffects
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.engine.ManualProficiencySystem
import kotlin.math.roundToInt
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator.SkillInputs
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator.StatAccum
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator.VarianceInputs

// ── DiscipleStatCalculator 拆分域 3/6（行为零变更） ──
internal fun DiscipleStatCalculator.computeBaseStats(
    realm: Int,
    realmLayer: Int,
    variances: VarianceInputs,
    skills: SkillInputs
): DiscipleStats {
    val realmConfig = GameConfig.Realm.get(realm)
    val layerMult = safeLayerMult(realmLayer)

    // 单列口径（B1 §15.4/Q2）：境界面物法两列各自 round 后相加进单列——
    // 同一方差乘区作用于物法两半，与战力取和公式线性一致（迁移前后战力不变）
    val atkVar = safeVarianceMultiplier(variances.attackVariance)
    val defVar = safeVarianceMultiplier(variances.defenseVariance)
    val spdVar = safeVarianceMultiplier(variances.speedVariance)

    // maxHp/maxMp 共用实现（computeBaseHpMp），与列直读版公式单一来源
    val (maxHp, maxMp) = computeBaseHpMp(
        realm, realmLayer, variances.hpVariance, variances.mpVariance
    )

    return DiscipleStats(
        hp = maxHp,
        maxHp = maxHp,
        mp = maxMp,
        maxMp = maxMp,
        attack = (realmConfig.basePhysicalAttack * atkVar * layerMult).roundToInt() +
            (realmConfig.baseMagicAttack * atkVar * layerMult).roundToInt(),
        defense = (realmConfig.basePhysicalDefense * defVar * layerMult).roundToInt() +
            (realmConfig.baseMagicDefense * defVar * layerMult).roundToInt(),
        speed = (realmConfig.baseSpeed * spdVar * layerMult).roundToInt(),
        critRate = BASE_CRIT_RATE,
        intelligence = skills.intelligence,
        charm = skills.charm,
        comprehension = skills.comprehension,
        teaching = skills.teaching,
        morality = skills.morality,
        mining = skills.mining,
        spiritPlanting = skills.spiritPlanting,
        artifactRefining = skills.artifactRefining,
        pillRefining = skills.pillRefining
    )
}

fun DiscipleStatCalculator.getBaseStats(
    disciple: Disciple
): DiscipleStats {
    val c = disciple.combat
    val s = disciple.skills
    return computeBaseStats(
        realm = disciple.realm,
        realmLayer = disciple.realmLayer,
        variances = VarianceInputs(
            hpVariance = c.hpVariance,
            mpVariance = c.mpVariance,
            attackVariance = c.attackVariance,
            defenseVariance = c.defenseVariance,
            speedVariance = c.speedVariance
        ),
        skills = SkillInputs(
            intelligence = s.intelligence,
            charm = s.charm,
            comprehension = s.comprehension,
            teaching = s.teaching,
            morality = s.morality,
            mining = s.mining,
            spiritPlanting = s.spiritPlanting,
            artifactRefining = s.artifactRefining,
            pillRefining = s.pillRefining
        )
    )
}

/** 聚合战斗方差输入：无记录按 0 */

internal fun DiscipleStatCalculator.varianceInputsOf(cs: DiscipleCombatStats?): VarianceInputs = VarianceInputs(
    hpVariance = cs?.hpVariance ?: 0,
    mpVariance = cs?.mpVariance ?: 0,
    attackVariance = cs?.attackVariance ?: 0,
    defenseVariance = cs?.defenseVariance ?: 0,
    speedVariance = cs?.speedVariance ?: 0
)

/** 聚合技能输入：无记录按 50 缺省 */

internal fun DiscipleStatCalculator.skillInputsOf(attr: DiscipleAttributes?): SkillInputs = SkillInputs(
    intelligence = attr?.intelligence ?: 50,
    charm = attr?.charm ?: 50,
    comprehension = attr?.comprehension ?: 50,
    teaching = attr?.teaching ?: 50,
    morality = attr?.morality ?: 50,
    mining = attr?.mining ?: 50,
    spiritPlanting = attr?.spiritPlanting ?: 50,
    artifactRefining = attr?.artifactRefining ?: 50,
    pillRefining = attr?.pillRefining ?: 50
)

fun DiscipleStatCalculator.getBaseStats(
    aggregate: DiscipleAggregate
): DiscipleStats {
    return computeBaseStats(
        realm = aggregate.realm,
        realmLayer = aggregate.realmLayer,
        variances = varianceInputsOf(aggregate.combatStats),
        skills = skillInputsOf(aggregate.attributes)
    )
}

/**
 * 计算弟子的永久基础属性（境界基础 × 方差 × 层数）。
 *
 * 用于战力计算。不包含装备、功法、临时丹药等临时加成。
 *
 * @param aggregate 弟子聚合数据
 */

fun DiscipleStatCalculator.getPermanentBaseStats(
    aggregate: DiscipleAggregate
): DiscipleStats {
    return computeBaseStats(
        realm = aggregate.realm,
        realmLayer = aggregate.realmLayer,
        variances = varianceInputsOf(aggregate.combatStats),
        skills = skillInputsOf(aggregate.attributes)
    )
}

// ==================== 装备属性（B3：EquipStatResolver 单点结算） ====================

internal fun DiscipleStatCalculator.computeStatsWithEquipment(
    baseStats: DiscipleStats,
    equipmentIds: List<String>,
    equipments: Map<String, EquipmentInstance>
): DiscipleStats {
    val instances = equipmentIds.mapNotNull { equipments[it] }
    return applyEquipBonus(baseStats, EquipStatResolver.resolve(instances))
}

fun DiscipleStatCalculator.getStatsWithEquipment(
    disciple: Disciple,
    equipments: Map<String, EquipmentInstance>
): DiscipleStats {
    val equipmentIds = disciple.equipment.equippedItemIds
    return computeStatsWithEquipment(getBaseStats(disciple), equipmentIds, equipments)
}

fun DiscipleStatCalculator.getStatsWithEquipment(
    aggregate: DiscipleAggregate,
    equipments: Map<String, EquipmentInstance>
): DiscipleStats {
    val equipmentIds = aggregate.equipment?.equippedItemIds ?: emptyList()
    return computeStatsWithEquipment(getBaseStats(aggregate), equipmentIds, equipments)
}

// ==================== 最终属性（含装备+功法+丹药） ====================

internal fun DiscipleStatCalculator.computeFinalStats(
    baseStats: DiscipleStats,
    equipmentIds: List<String>,
    manualIds: List<String>,
    equipments: Map<String, EquipmentInstance>,
    manuals: Map<String, ManualInstance>,
    manualProficiencies: Map<String, ManualProficiencyData>,
    pillEffects: PillEffects
): DiscipleStats {
    val acc = applyPillStats(
        applyManualStats(
            applyEquipmentStats(StatAccum(baseStats, baseStats.critRate), equipmentIds, equipments),
            manualIds, manuals, manualProficiencies
        ),
        pillEffects
    )
    return acc.total.copy(critRate = acc.critRate)
}

/**
 * 装备加成（B3，方案 §3.4.1 乘区口径）：
 * `atk = (baseAtk + Σ装备flatAtk) × (1 + Σ装备atkPct) + Σ功法flat + Σ丹药flat`——
 * 装备乘区只放大装备自身贡献；功法/丹药加法序与既有对拍基线逐位不变。
 * critRate（含套装）与 critDamage（暴击伤害，接线 D3）单列累加。
 */
internal fun DiscipleStatCalculator.applyEquipmentStats(
    acc: StatAccum,
    equipmentIds: List<String>,
    equipments: Map<String, EquipmentInstance>
): StatAccum {
    val instances = equipmentIds.mapNotNull { equipments[it] }
    return applyEquipBonusToAccum(acc, EquipStatResolver.resolve(instances))
}

/** 功法加成：熟练度加成的面板属性与暴击率按功法序累加 */

internal fun DiscipleStatCalculator.applyManualStats(
    acc: StatAccum,
    manualIds: List<String>,
    manuals: Map<String, ManualInstance>,
    manualProficiencies: Map<String, ManualProficiencyData>
): StatAccum {
    var total = acc.total
    var critRate = acc.critRate
    manualIds.forEach { manualId ->
        val manual = manuals[manualId]
        if (manual != null) {
            val proficiencyData = manualProficiencies[manualId]
            val masteryLevel = proficiencyData?.masteryLevel ?: 0
            val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus

            val hpValue = manual.stats["hp"] ?: manual.stats["maxHp"] ?: 0
            val mpValue = manual.stats["mp"] ?: manual.stats["maxMp"] ?: 0
            // 功法保留物法双列数据（Q2：150+ 功法数据与 codegen 一字不改），结算层相加进单列
            val manualStats = DiscipleStats(
                hp = (hpValue * masteryBonus).toInt(),
                maxHp = (hpValue * masteryBonus).toInt(),
                mp = (mpValue * masteryBonus).toInt(),
                maxMp = (mpValue * masteryBonus).toInt(),
                attack = ((manual.stats["physicalAttack"] ?: 0) * masteryBonus).toInt() +
                    ((manual.stats["magicAttack"] ?: 0) * masteryBonus).toInt(),
                defense = ((manual.stats["physicalDefense"] ?: 0) * masteryBonus).toInt() +
                    ((manual.stats["magicDefense"] ?: 0) * masteryBonus).toInt(),
                speed = ((manual.stats["speed"] ?: 0) * masteryBonus).toInt(),
                critRate = 1.0
            )
            total = total + manualStats
            critRate += ((manual.stats["critRate"] ?: 0) * masteryBonus) / 100.0
        }
    }
    return StatAccum(total, critRate)
}

/** 丹药加成：有效期内的丹药面板与暴击率累加 */

internal fun DiscipleStatCalculator.applyPillStats(acc: StatAccum, pillEffects: PillEffects): StatAccum {
    if (pillEffects.pillEffectDuration <= 0) return acc
    val pillBonus = DiscipleStats(
        hp = pillEffects.pillHpBonus,
        maxHp = pillEffects.pillHpBonus,
        mp = pillEffects.pillMpBonus,
        maxMp = pillEffects.pillMpBonus,
        attack = pillEffects.pillAttackBonus,
        defense = pillEffects.pillDefenseBonus,
        speed = pillEffects.pillSpeedBonus,
        critRate = pillEffects.pillCritRateBonus
    )
    return StatAccum(acc.total + pillBonus, acc.critRate + pillEffects.pillCritRateBonus)
}

// ==================== 装备加成应用（共享实现，Ops4 列直读版复用） ====================

/** 面板版：装备块乘区只作用于 (base + 装备 flat)，功法/丹药在调用方后续加法序 */
internal fun DiscipleStatCalculator.applyEquipBonus(baseStats: DiscipleStats, bonus: EquipBonus): DiscipleStats {
    val withFlat = baseStats.copy(
        attack = baseStats.attack + bonus.flatAttack.toInt(),
        defense = baseStats.defense + bonus.flatDefense.toInt(),
        maxHp = baseStats.maxHp + bonus.flatHp.toInt(),
        hp = baseStats.hp + bonus.flatHp.toInt(),
        critRate = baseStats.critRate + bonus.critRate
    )
    val pctAttack = (withFlat.attack.toDouble() * bonus.pctAttack).toInt()
    return withFlat.copy(attack = withFlat.attack + pctAttack)
}

/** 累加器版（computeFinalStats 管线）：flat 直接加、乘区在装备块内一次乘 */
internal fun DiscipleStatCalculator.applyEquipBonusToAccum(acc: StatAccum, bonus: EquipBonus): StatAccum {
    val flat = acc.total.copy(
        attack = acc.total.attack + bonus.flatAttack.toInt(),
        defense = acc.total.defense + bonus.flatDefense.toInt(),
        maxHp = acc.total.maxHp + bonus.flatHp.toInt(),
        hp = acc.total.hp + bonus.flatHp.toInt()
    )
    val pctAttack = (flat.attack.toDouble() * bonus.pctAttack).toInt()
    val total = flat.copy(attack = flat.attack + pctAttack)
    return StatAccum(total, acc.critRate + bonus.critRate)
}

/** 暴击伤害加成（D3 接线）：战斗期字段消费，面板列不展示 */
fun DiscipleStatCalculator.critDamageBonusOf(
    equipments: Map<String, EquipmentInstance>,
    equipmentIds: List<String>
): Double = EquipStatResolver.resolve(equipmentIds.mapNotNull { equipments[it] }).critDamage
