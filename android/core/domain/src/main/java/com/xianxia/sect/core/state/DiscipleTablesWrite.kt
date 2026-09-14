package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.accessoryId
import com.xianxia.sect.core.model.accessoryNurture
import com.xianxia.sect.core.model.activePillCategory
import com.xianxia.sect.core.model.armorId
import com.xianxia.sect.core.model.armorNurture
import com.xianxia.sect.core.model.artifactRefining
import com.xianxia.sect.core.model.baseHp
import com.xianxia.sect.core.model.baseMagicAttack
import com.xianxia.sect.core.model.baseMagicDefense
import com.xianxia.sect.core.model.baseMp
import com.xianxia.sect.core.model.basePhysicalAttack
import com.xianxia.sect.core.model.basePhysicalDefense
import com.xianxia.sect.core.model.baseSpeed
import com.xianxia.sect.core.model.bootsId
import com.xianxia.sect.core.model.bootsNurture
import com.xianxia.sect.core.model.breakthroughCount
import com.xianxia.sect.core.model.breakthroughFailCount
import com.xianxia.sect.core.model.charm
import com.xianxia.sect.core.model.childBirthMonth
import com.xianxia.sect.core.model.comprehension
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.currentMp
import com.xianxia.sect.core.model.griefEndYear
import com.xianxia.sect.core.model.hasClearAllEffect
import com.xianxia.sect.core.model.hasReviveEffect
import com.xianxia.sect.core.model.hpVariance
import com.xianxia.sect.core.model.intelligence
import com.xianxia.sect.core.model.lastChildYear
import com.xianxia.sect.core.model.loyalty
import com.xianxia.sect.core.model.magicAttackVariance
import com.xianxia.sect.core.model.magicDefenseVariance
import com.xianxia.sect.core.model.mining
import com.xianxia.sect.core.model.morality
import com.xianxia.sect.core.model.mpVariance
import com.xianxia.sect.core.model.parentId1
import com.xianxia.sect.core.model.parentId2
import com.xianxia.sect.core.model.partnerId
import com.xianxia.sect.core.model.partnerSectId
import com.xianxia.sect.core.model.physicalAttackVariance
import com.xianxia.sect.core.model.physicalDefenseVariance
import com.xianxia.sect.core.model.pillCritEffectBonus
import com.xianxia.sect.core.model.pillCritRateBonus
import com.xianxia.sect.core.model.pillCultivationSpeedBonus
import com.xianxia.sect.core.model.pillEffectDuration
import com.xianxia.sect.core.model.pillHpBonus
import com.xianxia.sect.core.model.pillMagicAttackBonus
import com.xianxia.sect.core.model.pillMagicDefenseBonus
import com.xianxia.sect.core.model.pillMpBonus
import com.xianxia.sect.core.model.pillNurtureSpeedBonus
import com.xianxia.sect.core.model.pillPhysicalAttackBonus
import com.xianxia.sect.core.model.pillPhysicalDefenseBonus
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
import com.xianxia.sect.core.model.usedExtendLifePillIds
import com.xianxia.sect.core.model.weaponId
import com.xianxia.sect.core.model.weaponNurture

internal fun DiscipleTables.writeAllFields(disciple: Disciple) {
    val id = disciple.id.toInt()
    writeBasicFields(id = id, disciple = disciple)
    writeCombatFields(id = id, disciple = disciple)
    writePillFields(id = id, disciple = disciple)
    writeEquipmentFields(id = id, disciple = disciple)
    writeSocialFields(id = id, disciple = disciple)
    writeSkillFields(id = id, disciple = disciple)
    writeUsageFields(id = id, disciple = disciple)
}

internal fun DiscipleTables.writeBasicFields(id: Int, disciple: Disciple) {
    // 基础信息
    names[id] = disciple.name; surnames[id] = disciple.surname
    genders[id] = disciple.gender; portraitRes[id] = disciple.portraitRes
    discipleTypes[id] = disciple.discipleType
    spiritRootTypes[id] = disciple.spiritRootType; slotIds[id] = disciple.slotId

    // 境界与修为
    realms[id] = disciple.realm; realmLayers[id] = disciple.realmLayer
    cultivations[id] = disciple.cultivation
    cultivationCheckpoints[id] = disciple.cultivationCheckpoint
    cultivationCheckpointGameMonths[id] = disciple.cultivationCheckpointGameMonth
    ages[id] = disciple.age; lifespans[id] = disciple.lifespan
    isAlive[id] = if (disciple.isAlive) 1 else 0; soulPowers[id] = disciple.soulPower

    // 修炼加速
    cultivationSpeedBonuses[id] = disciple.cultivationSpeedBonus
    cultivationSpeedDurations[id] = disciple.cultivationSpeedDuration

    // 列表/映射
    manualIds[id] = disciple.manualIds; talentIds[id] = disciple.talentIds
    physiqueIds[id] = disciple.physiqueIds; affixIds[id] = disciple.affixIds
    lifeEvents[id] = disciple.lifeEvents; manualMasteries[id] = disciple.manualMasteries

    // 状态
    statuses[id] = disciple.status; statusData[id] = disciple.statusData
}

internal fun DiscipleTables.writeCombatFields(id: Int, disciple: Disciple) {
    // 战斗属性
    val c = disciple.combat
    baseHps[id] = c.baseHp; baseMps[id] = c.baseMp
    basePhysicalAttacks[id] = c.basePhysicalAttack
    baseMagicAttacks[id] = c.baseMagicAttack
    basePhysicalDefenses[id] = c.basePhysicalDefense
    baseMagicDefenses[id] = c.baseMagicDefense; baseSpeeds[id] = c.baseSpeed
    hpVariances[id] = c.hpVariance; mpVariances[id] = c.mpVariance
    physicalAttackVariances[id] = c.physicalAttackVariance
    magicAttackVariances[id] = c.magicAttackVariance
    physicalDefenseVariances[id] = c.physicalDefenseVariance
    magicDefenseVariances[id] = c.magicDefenseVariance
    speedVariances[id] = c.speedVariance; totalCultivations[id] = c.totalCultivation
    breakthroughCounts[id] = c.breakthroughCount
    breakthroughFailCounts[id] = c.breakthroughFailCount
    currentHps[id] = c.currentHp; currentMps[id] = c.currentMp
}

internal fun DiscipleTables.writePillFields(id: Int, disciple: Disciple) {
    // 丹药效果
    val p = disciple.pillEffects
    pillPhysicalAttackBonuses[id] = p.pillPhysicalAttackBonus
    pillMagicAttackBonuses[id] = p.pillMagicAttackBonus
    pillPhysicalDefenseBonuses[id] = p.pillPhysicalDefenseBonus
    pillMagicDefenseBonuses[id] = p.pillMagicDefenseBonus
    pillHpBonuses[id] = p.pillHpBonus; pillMpBonuses[id] = p.pillMpBonus
    pillSpeedBonuses[id] = p.pillSpeedBonus; pillEffectDurations[id] = p.pillEffectDuration
    pillCritRateBonuses[id] = p.pillCritRateBonus
    pillCritEffectBonuses[id] = p.pillCritEffectBonus
    pillCultivationSpeedBonuses[id] = p.pillCultivationSpeedBonus
    pillSkillExpSpeedBonuses[id] = p.pillSkillExpSpeedBonus
    pillNurtureSpeedBonuses[id] = p.pillNurtureSpeedBonus
    activePillCategories[id] = p.activePillCategory; activePillTypes[id] = p.activePillTypes
}

internal fun DiscipleTables.writeEquipmentFields(id: Int, disciple: Disciple) {
    // 装备
    val e = disciple.equipment
    weaponIds[id] = e.weaponId; armorIds[id] = e.armorId
    bootsIds[id] = e.bootsId; accessoryIds[id] = e.accessoryId
    weaponNurtures[id] = e.weaponNurture; armorNurtures[id] = e.armorNurture
    bootsNurtures[id] = e.bootsNurture; accessoryNurtures[id] = e.accessoryNurture
    storageBagItems[id] = e.storageBagItems; storageBagSpiritStones[id] = e.storageBagSpiritStones
    discipleSpiritStones[id] = e.spiritStones
    cultivationCompletionMonths[id] = disciple.cultivationCompletionMonth
    cultivationCompletionPhases[id] = disciple.cultivationCompletionPhase
    manualCompletionMonths[id] = disciple.manualCompletionMonth
    manualCompletionPhases[id] = disciple.manualCompletionPhase
    equipmentNurturingCompletionMonths[id] = disciple.equipmentNurturingCompletionMonth
    equipmentNurturingCompletionPhases[id] = disciple.equipmentNurturingCompletionPhase
}

internal fun DiscipleTables.writeSocialFields(id: Int, disciple: Disciple) {
    // 社交
    val s = disciple.social
    partnerIds[id] = s.partnerId; partnerSectIds[id] = s.partnerSectId
    parentId1s[id] = s.parentId1; parentId2s[id] = s.parentId2
    lastChildYears[id] = s.lastChildYear
    childBirthMonths[id] = s.childBirthMonth
    griefEndYears[id] = s.griefEndYear ?: DiscipleTables.GRIEF_YEAR_NULL_SENTINEL
    masterIds[id] = s.masterId
}

internal fun DiscipleTables.writeSkillFields(id: Int, disciple: Disciple) {
    // 技能
    val sk = disciple.skills
    intelligences[id] = sk.intelligence; charms[id] = sk.charm
    loyalties[id] = sk.loyalty; comprehensions[id] = sk.comprehension
    artifactRefinings[id] = sk.artifactRefining; pillRefinings[id] = sk.pillRefining
    spiritPlantings[id] = sk.spiritPlanting; minings[id] = sk.mining
    teachings[id] = sk.teaching; moralities[id] = sk.morality
    aptitudes[id] = sk.aptitude
    salaryPaidCounts[id] = sk.salaryPaidCount; salaryMissedCounts[id] = sk.salaryMissedCount
    alchemyLevels[id] = sk.alchemyLevel; alchemyPromotionCounts[id] = sk.alchemyPromotionCount
    forgeLevels[id] = sk.forgeLevel; forgePromotionCounts[id] = sk.forgePromotionCount
}

internal fun DiscipleTables.writeUsageFields(id: Int, disciple: Disciple) {
    // 使用追踪
    val u = disciple.usage
    usedFunctionalPillTypes[id] = u.usedFunctionalPillTypes
    usedExtendLifePillIds[id] = u.usedExtendLifePillIds
    usedPermanentPillKeys[id] = u.usedPermanentPillKeys
    usedExtendLifePillTypes[id] = u.usedExtendLifePillTypes
    recruitedMonths[id] = u.recruitedMonth
    hasReviveEffects[id] = if (u.hasReviveEffect) 1 else 0
    hasClearAllEffects[id] = if (u.hasClearAllEffect) 1 else 0
}
