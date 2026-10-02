package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.activePillCategory
import com.xianxia.sect.core.model.artifactRefining
import com.xianxia.sect.core.model.baseAttack
import com.xianxia.sect.core.model.baseDefense
import com.xianxia.sect.core.model.baseHp
import com.xianxia.sect.core.model.baseMp
import com.xianxia.sect.core.model.baseSpeed
import com.xianxia.sect.core.model.breakthroughCount
import com.xianxia.sect.core.model.breakthroughFailCount
import com.xianxia.sect.core.model.charm
import com.xianxia.sect.core.model.comprehension
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.currentMp
import com.xianxia.sect.core.model.hasClearAllEffect
import com.xianxia.sect.core.model.hasReviveEffect
import com.xianxia.sect.core.model.attackVariance
import com.xianxia.sect.core.model.defenseVariance
import com.xianxia.sect.core.model.hpVariance
import com.xianxia.sect.core.model.intelligence
import com.xianxia.sect.core.model.mining
import com.xianxia.sect.core.model.morality
import com.xianxia.sect.core.model.mpVariance
import com.xianxia.sect.core.model.pillCritEffectBonus
import com.xianxia.sect.core.model.pillCritRateBonus
import com.xianxia.sect.core.model.pillCultivationSpeedBonus
import com.xianxia.sect.core.model.pillEffectDuration
import com.xianxia.sect.core.model.pillAttackBonus
import com.xianxia.sect.core.model.pillDefenseBonus
import com.xianxia.sect.core.model.pillHpBonus
import com.xianxia.sect.core.model.pillMpBonus
import com.xianxia.sect.core.model.pillRefining
import com.xianxia.sect.core.model.pillSkillExpSpeedBonus
import com.xianxia.sect.core.model.pillSpeedBonus
import com.xianxia.sect.core.model.recruitedMonth
import com.xianxia.sect.core.model.salaryMissedCount
import com.xianxia.sect.core.model.salaryPaidCount
import com.xianxia.sect.core.model.speedVariance
import com.xianxia.sect.core.model.spiritPlanting
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.model.storageBagSpiritStones
import com.xianxia.sect.core.model.teaching
import com.xianxia.sect.core.model.totalCultivation

internal fun DiscipleTables.writeAllFields(disciple: Disciple) {
    val id = disciple.id.toInt()
    writeBasicFields(id = id, disciple = disciple)
    writeCombatFields(id = id, disciple = disciple)
    writePillFields(id = id, disciple = disciple)
    writeEquipmentFields(id = id, disciple = disciple)
    writeSkillFields(id = id, disciple = disciple)
    writeUsageFields(id = id, disciple = disciple)
}

internal fun DiscipleTables.writeBasicFields(id: Int, disciple: Disciple) {
    // 基础信息
    names[id] = disciple.name; surnames[id] = disciple.surname
    genders[id] = disciple.gender; portraitRes[id] = disciple.portraitRes
    templateIds[id] = disciple.templateId
    discipleTypes[id] = disciple.discipleType
    spiritRootTypes[id] = disciple.spiritRootType

    // 境界与修为
    realms[id] = disciple.realm; realmLayers[id] = disciple.realmLayer
    cultivations[id] = disciple.cultivation
    cultivationCheckpoints[id] = disciple.cultivationCheckpoint
    cultivationCheckpointGameMonths[id] = disciple.cultivationCheckpointGameMonth
    isAlive[id] = if (disciple.isAlive) 1 else 0

    // 修炼加速
    cultivationSpeedBonuses[id] = disciple.cultivationSpeedBonus
    cultivationSpeedDurations[id] = disciple.cultivationSpeedDuration

    // 列表/映射
    manualIds[id] = disciple.manualIds
    lifeEvents[id] = disciple.lifeEvents; manualMasteries[id] = disciple.manualMasteries

    // 状态
    statuses[id] = disciple.status; statusData[id] = disciple.statusData
}

internal fun DiscipleTables.writeCombatFields(id: Int, disciple: Disciple) {
    // 战斗属性
    val c = disciple.combat
    baseHps[id] = c.baseHp; baseMps[id] = c.baseMp
    baseAttacks[id] = c.baseAttack; baseDefenses[id] = c.baseDefense
    baseSpeeds[id] = c.baseSpeed
    hpVariances[id] = c.hpVariance; mpVariances[id] = c.mpVariance
    attackVariances[id] = c.attackVariance
    defenseVariances[id] = c.defenseVariance
    innateDamageTypes[id] = c.innateDamageType
    speedVariances[id] = c.speedVariance; totalCultivations[id] = c.totalCultivation
    breakthroughCounts[id] = c.breakthroughCount
    breakthroughFailCounts[id] = c.breakthroughFailCount
    currentHps[id] = c.currentHp; currentMps[id] = c.currentMp
}

internal fun DiscipleTables.writePillFields(id: Int, disciple: Disciple) {
    // 丹药效果
    val p = disciple.pillEffects
    pillAttackBonuses[id] = p.pillAttackBonus
    pillDefenseBonuses[id] = p.pillDefenseBonus
    pillHpBonuses[id] = p.pillHpBonus; pillMpBonuses[id] = p.pillMpBonus
    pillSpeedBonuses[id] = p.pillSpeedBonus; pillEffectDurations[id] = p.pillEffectDuration
    pillCritRateBonuses[id] = p.pillCritRateBonus
    pillCritEffectBonuses[id] = p.pillCritEffectBonus
    pillCultivationSpeedBonuses[id] = p.pillCultivationSpeedBonus
    pillSkillExpSpeedBonuses[id] = p.pillSkillExpSpeedBonus
    activePillCategories[id] = p.activePillCategory; activePillTypes[id] = p.activePillTypes
}

internal fun DiscipleTables.writeEquipmentFields(id: Int, disciple: Disciple) {
    // 装备
    val e = disciple.equipment
    headIds[id] = e.headId; bodyIds[id] = e.bodyId
    handsIds[id] = e.handsId; feetIds[id] = e.feetId
    storageBagItems[id] = e.storageBagItems; storageBagSpiritStones[id] = e.storageBagSpiritStones
    discipleSpiritStones[id] = e.spiritStones
    cultivationCompletionMonths[id] = disciple.cultivationCompletionMonth
    manualCompletionMonths[id] = disciple.manualCompletionMonth
    manualCompletionPhases[id] = disciple.manualCompletionPhase
}

internal fun DiscipleTables.writeSkillFields(id: Int, disciple: Disciple) {
    // 技能
    val sk = disciple.skills
    intelligences[id] = sk.intelligence; charms[id] = sk.charm
    comprehensions[id] = sk.comprehension
    artifactRefinings[id] = sk.artifactRefining; pillRefinings[id] = sk.pillRefining
    spiritPlantings[id] = sk.spiritPlanting; minings[id] = sk.mining
    teachings[id] = sk.teaching; moralities[id] = sk.morality
    salaryPaidCounts[id] = sk.salaryPaidCount; salaryMissedCounts[id] = sk.salaryMissedCount
    alchemyLevels[id] = sk.alchemyLevel; alchemyPromotionCounts[id] = sk.alchemyPromotionCount
    forgeLevels[id] = sk.forgeLevel; forgePromotionCounts[id] = sk.forgePromotionCount
}

internal fun DiscipleTables.writeUsageFields(id: Int, disciple: Disciple) {
    // 使用追踪
    val u = disciple.usage
    usedFunctionalPillTypes[id] = u.usedFunctionalPillTypes
    usedPermanentPillKeys[id] = u.usedPermanentPillKeys
    recruitedMonths[id] = u.recruitedMonth
    hasReviveEffects[id] = if (u.hasReviveEffect) 1 else 0
    hasClearAllEffects[id] = if (u.hasClearAllEffect) 1 else 0
}
