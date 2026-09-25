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

    val paVar = safeVarianceMultiplier(variances.physicalAttackVariance)
    val maVar = safeVarianceMultiplier(variances.magicAttackVariance)
    val pdVar = safeVarianceMultiplier(variances.physicalDefenseVariance)
    val mdVar = safeVarianceMultiplier(variances.magicDefenseVariance)
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
        physicalAttack = (realmConfig.basePhysicalAttack * paVar * layerMult).roundToInt(),
        magicAttack = (realmConfig.baseMagicAttack * maVar * layerMult).roundToInt(),
        physicalDefense = (realmConfig.basePhysicalDefense * pdVar * layerMult).roundToInt(),
        magicDefense = (realmConfig.baseMagicDefense * mdVar * layerMult).roundToInt(),
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
            physicalAttackVariance = c.physicalAttackVariance,
            magicAttackVariance = c.magicAttackVariance,
            physicalDefenseVariance = c.physicalDefenseVariance,
            magicDefenseVariance = c.magicDefenseVariance,
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
    physicalAttackVariance = cs?.physicalAttackVariance ?: 0,
    magicAttackVariance = cs?.magicAttackVariance ?: 0,
    physicalDefenseVariance = cs?.physicalDefenseVariance ?: 0,
    magicDefenseVariance = cs?.magicDefenseVariance ?: 0,
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

// ==================== 装备属性 ====================

internal fun DiscipleStatCalculator.computeStatsWithEquipment(
    baseStats: DiscipleStats,
    equipmentIds: List<String>,
    equipments: Map<String, EquipmentInstance>
): DiscipleStats {
    var total = baseStats
    var totalCritChance = 0.0
    equipmentIds.forEach { equipId ->
        val equipment = equipments[equipId]
        if (equipment != null) {
            equipment.getFinalStats().toDiscipleStats().let { total = total + it }
            totalCritChance += equipment.critChance
        }
    }
    return total.copy(critRate = total.critRate + totalCritChance)
}

fun DiscipleStatCalculator.getStatsWithEquipment(
    disciple: Disciple,
    equipments: Map<String, EquipmentInstance>
): DiscipleStats {
    val equipmentIds = listOfNotNull(
        disciple.equipment.weaponId,
        disciple.equipment.armorId,
        disciple.equipment.bootsId,
        disciple.equipment.accessoryId
    )
    return computeStatsWithEquipment(getBaseStats(disciple), equipmentIds, equipments)
}

fun DiscipleStatCalculator.getStatsWithEquipment(
    aggregate: DiscipleAggregate,
    equipments: Map<String, EquipmentInstance>
): DiscipleStats {
    val eq = aggregate.equipment
    val equipmentIds = listOfNotNull(
        eq?.weaponId, eq?.armorId, eq?.bootsId, eq?.accessoryId
    ).filter { it.isNotEmpty() }
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

/** 装备加成：面板属性与暴击率按装备序累加 */

internal fun DiscipleStatCalculator.applyEquipmentStats(
    acc: StatAccum,
    equipmentIds: List<String>,
    equipments: Map<String, EquipmentInstance>
): StatAccum {
    var total = acc.total
    var critRate = acc.critRate
    equipmentIds.forEach { equipId ->
        val equipment = equipments[equipId]
        if (equipment != null) {
            equipment.getFinalStats().toDiscipleStats().let { total = total + it }
            critRate += equipment.critChance
        }
    }
    return StatAccum(total, critRate)
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
            val manualStats = DiscipleStats(
                hp = (hpValue * masteryBonus).toInt(),
                maxHp = (hpValue * masteryBonus).toInt(),
                mp = (mpValue * masteryBonus).toInt(),
                maxMp = (mpValue * masteryBonus).toInt(),
                physicalAttack = ((manual.stats["physicalAttack"] ?: 0) * masteryBonus).toInt(),
                magicAttack = ((manual.stats["magicAttack"] ?: 0) * masteryBonus).toInt(),
                physicalDefense = ((manual.stats["physicalDefense"] ?: 0) * masteryBonus).toInt(),
                magicDefense = ((manual.stats["magicDefense"] ?: 0) * masteryBonus).toInt(),
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
        physicalAttack = pillEffects.pillPhysicalAttackBonus,
        magicAttack = pillEffects.pillMagicAttackBonus,
        physicalDefense = pillEffects.pillPhysicalDefenseBonus,
        magicDefense = pillEffects.pillMagicDefenseBonus,
        speed = pillEffects.pillSpeedBonus,
        critRate = pillEffects.pillCritRateBonus
    )
    return StatAccum(acc.total + pillBonus, acc.critRate + pillEffects.pillCritRateBonus)
}
