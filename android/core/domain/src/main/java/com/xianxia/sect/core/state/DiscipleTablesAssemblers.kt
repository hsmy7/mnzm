package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.PillEffects
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.model.UsageTracking

/** 辅助：dirtyGroups 位图是否包含指定组。 */
private fun Int.hasGroup(group: AssembleGroup): Boolean = (this and (1 shl group.ordinal)) != 0

/** 脏组组件解析：无前快照或组脏即重装配，否则复用前值 */
private fun <T> resolveGroupPart(
prev: Disciple?,
dirtyGroups: Int,
group: AssembleGroup,
reuse: (Disciple) -> T,
assemble: () -> T
): T = if (prev == null || dirtyGroups.hasGroup(group)) assemble() else reuse(prev)

/** 三表齐全判据：isAlive + names + realms 任一缺失 → 幽灵（ID 未完整写入）。
 *  assembleAll / assembleAllIncremental / deepCopy 三处共用，保证快照 ids、
 *  UI 列表、序列化三条路径的幽灵防御粒度一致。 */
internal fun DiscipleTables.isCompleteId(id: Int): Boolean =
    isAlive.contains(id) && names.contains(id) && realms.contains(id)

/**
 * 子对象级 patch 组装：仅重装脏列所属子对象组，未脏组复用 [prev] 引用。
 *
 * 每旬 changedIds ≈ 全量（cultivation 列几乎全部弟子写入）时，原全量
 * [assembleAll] 每弟子 ~100 列读 + 10 个嵌套对象分配。patch 后本体字段
 * （~33 列，含 cultivation）始终重读，6 个子对象 + lifeEvents 仅在对应组
 * 脏时重装——每旬典型（仅 cultivation + HP/MP 变化）可复用全部子对象引用，
 * 消除 ~67 列读与 6 个对象分配/弟子。
 *
 * @param id 弟子 ID
 * @param prev 上一快照中的同 ID 弟子（未脏组复用的引用来源）
 * @param dirtyGroups 脏列所属组位图（[AssembleGroup.ordinal] 位），0=全部未脏
 * @return 组装后的 Disciple
 */
internal fun DiscipleTables.assembleCoreFields(id: Int, prev: Disciple?, dirtyGroups: Int): Disciple {
    val combat = resolveGroupPart(prev, dirtyGroups, AssembleGroup.COMBAT, { it.combat }) { assembleCombat(id) }
    val pillEffects =
        resolveGroupPart(prev, dirtyGroups, AssembleGroup.PILL, { it.pillEffects }) { assemblePillEffects(id) }
    val equipment =
        resolveGroupPart(prev, dirtyGroups, AssembleGroup.EQUIPMENT, { it.equipment }) { assembleEquipment(id) }
    val social = resolveGroupPart(prev, dirtyGroups, AssembleGroup.SOCIAL, { it.social }) { assembleSocial(id) }
    val skills = resolveGroupPart(prev, dirtyGroups, AssembleGroup.SKILLS, { it.skills }) { assembleSkills(id) }
    val usage = resolveGroupPart(prev, dirtyGroups, AssembleGroup.USAGE, { it.usage }) { assembleUsage(id) }
    val disciple = Disciple(
        id = id.toString(),
        slotId = slotIds.getOrDefault(id, 0),
        name = names.getOrDefault(id, ""),
        surname = surnames.getOrDefault(id, ""),
        realm = realms.getOrDefault(id, 9),
        realmLayer = realmLayers.getOrDefault(id, 1),
        cultivation = cultivations.getOrDefault(id, 0.0),
        cultivationCheckpoint = cultivationCheckpoints.getOrDefault(id, 0.0),
        cultivationCheckpointGameMonth = cultivationCheckpointGameMonths.getOrDefault(id, 0),
        spiritRootType = spiritRootTypes.getOrDefault(id, "metal"),
        age = ages.getOrDefault(id, 16),
        lifespan = lifespans.getOrDefault(id, 80),
        isAlive = isAlive.getOrDefault(id, 1) == 1,
        gender = genders.getOrDefault(id, "male"),
        portraitRes = portraitRes.getOrDefault(id, ""),
        manualIds = manualIds.getOrDefault(id, emptyList()),
        talentIds = talentIds.getOrDefault(id, emptyList()),
        physiqueIds = physiqueIds.getOrDefault(id, emptyList()),
        affixIds = affixIds.getOrDefault(id, emptyList()),
        manualMasteries = manualMasteries.getOrDefault(id, emptyMap()),
        status = statuses.getOrDefault(id, DiscipleStatus.IDLE),
        statusData = statusData.getOrDefault(id, emptyMap()),
        cultivationSpeedBonus = cultivationSpeedBonuses.getOrDefault(id, 0.0),
        cultivationSpeedDuration = cultivationSpeedDurations.getOrDefault(id, 0),
        discipleType = discipleTypes.getOrDefault(id, "outer"),
        soulPower = soulPowers.getOrDefault(id, 0),
        cultivationCompletionMonth = cultivationCompletionMonths.getOrDefault(id, 0),
        cultivationCompletionPhase = cultivationCompletionPhases.getOrDefault(id, 1),
        manualCompletionMonth = manualCompletionMonths.getOrDefault(id, 0),
        manualCompletionPhase = manualCompletionPhases.getOrDefault(id, 1),
        equipmentNurturingCompletionMonth = equipmentNurturingCompletionMonths.getOrDefault(id, 0),
        equipmentNurturingCompletionPhase = equipmentNurturingCompletionPhases.getOrDefault(id, 1),
        combat = combat,
        pillEffects = pillEffects,
        equipment = equipment,
        social = social,
        skills = skills,
        usage = usage
    )
    disciple.lifeEvents = resolveGroupPart(
        prev, dirtyGroups, AssembleGroup.LIFEEVENTS,
        { it.lifeEvents }
    ) { lifeEvents.getOrDefault(id, emptyList()) }
    return disciple
}

internal fun DiscipleTables.assembleCombat(id: Int) = CombatAttributes(
    baseHp = baseHps.getOrDefault(id, 0), baseMp = baseMps.getOrDefault(id, 0),
    basePhysicalAttack = basePhysicalAttacks.getOrDefault(id, 0),
    baseMagicAttack = baseMagicAttacks.getOrDefault(id, 0),
    basePhysicalDefense = basePhysicalDefenses.getOrDefault(id, 0),
    baseMagicDefense = baseMagicDefenses.getOrDefault(id, 0),
    baseSpeed = baseSpeeds.getOrDefault(id, 0),
    hpVariance = hpVariances.getOrDefault(id, 0), mpVariance = mpVariances.getOrDefault(id, 0),
    physicalAttackVariance = physicalAttackVariances.getOrDefault(id, 0),
    magicAttackVariance = magicAttackVariances.getOrDefault(id, 0),
    physicalDefenseVariance = physicalDefenseVariances.getOrDefault(id, 0),
    magicDefenseVariance = magicDefenseVariances.getOrDefault(id, 0),
    speedVariance = speedVariances.getOrDefault(id, 0),
    totalCultivation = totalCultivations.getOrNull(id) ?: 0L,
    breakthroughCount = breakthroughCounts.getOrDefault(id, 0),
    breakthroughFailCount = breakthroughFailCounts.getOrDefault(id, 0),
    currentHp = currentHps.getOrDefault(id, 0), currentMp = currentMps.getOrDefault(id, 0)
)

internal fun DiscipleTables.assemblePillEffects(id: Int) = PillEffects(
    pillPhysicalAttackBonus = pillPhysicalAttackBonuses.getOrDefault(id, 0),
    pillMagicAttackBonus = pillMagicAttackBonuses.getOrDefault(id, 0),
    pillPhysicalDefenseBonus = pillPhysicalDefenseBonuses.getOrDefault(id, 0),
    pillMagicDefenseBonus = pillMagicDefenseBonuses.getOrDefault(id, 0),
    pillHpBonus = pillHpBonuses.getOrDefault(id, 0), pillMpBonus = pillMpBonuses.getOrDefault(id, 0),
    pillSpeedBonus = pillSpeedBonuses.getOrDefault(id, 0),
    pillEffectDuration = pillEffectDurations.getOrDefault(id, 0),
    pillCritRateBonus = pillCritRateBonuses.getOrDefault(id, 0.0),
    pillCritEffectBonus = pillCritEffectBonuses.getOrDefault(id, 0.0),
    pillCultivationSpeedBonus = pillCultivationSpeedBonuses.getOrDefault(id, 0.0),
    pillSkillExpSpeedBonus = pillSkillExpSpeedBonuses.getOrDefault(id, 0.0),
    pillNurtureSpeedBonus = pillNurtureSpeedBonuses.getOrDefault(id, 0.0),
    activePillCategory = activePillCategories.getOrNull(id) ?: "",
    activePillTypes = activePillTypes.getOrNull(id) ?: emptySet()
)

internal fun DiscipleTables.assembleEquipment(id: Int) = EquipmentSet(
    weaponId = weaponIds.getOrNull(id) ?: "",
    armorId = armorIds.getOrNull(id) ?: "",
    bootsId = bootsIds.getOrNull(id) ?: "",
    accessoryId = accessoryIds.getOrNull(id) ?: "",
    weaponNurture = weaponNurtures.getOrNull(id) ?: EquipmentNurtureData(equipmentId = "", rarity = 0),
    armorNurture = armorNurtures.getOrNull(id) ?: EquipmentNurtureData(equipmentId = "", rarity = 0),
    bootsNurture = bootsNurtures.getOrNull(id) ?: EquipmentNurtureData(equipmentId = "", rarity = 0),
    accessoryNurture = accessoryNurtures.getOrNull(id) ?: EquipmentNurtureData(equipmentId = "", rarity = 0),
    storageBagItems = storageBagItems.getOrNull(id) ?: emptyList(),
    storageBagSpiritStones = storageBagSpiritStones.getOrNull(id) ?: 0L,
    spiritStones = discipleSpiritStones.getOrDefault(id, 0)
)

internal fun DiscipleTables.assembleSocial(id: Int) = SocialData(
    partnerId = partnerIds.getOrNull(id),
    partnerSectId = partnerSectIds.getOrNull(id),
    parentId1 = parentId1s.getOrNull(id),
    parentId2 = parentId2s.getOrNull(id),
    lastChildYear = lastChildYears.getOrDefault(id, 0),
    childBirthMonth = childBirthMonths.getOrNull(id),
    griefEndYear = griefEndYears.getOrDefault(id, DiscipleTables.GRIEF_YEAR_NULL_SENTINEL)
        .takeIf { it != DiscipleTables.GRIEF_YEAR_NULL_SENTINEL },
    masterId = masterIds.getOrNull(id)
)

internal fun DiscipleTables.assembleSkills(id: Int) = SkillStats(
    intelligence = intelligences.getOrDefault(id, 0), charm = charms.getOrDefault(id, 0),
    loyalty = loyalties.getOrDefault(id, 0), comprehension = comprehensions.getOrDefault(id, 0),
    artifactRefining = artifactRefinings.getOrDefault(id, 0),
    pillRefining = pillRefinings.getOrDefault(id, 0),
    spiritPlanting = spiritPlantings.getOrDefault(id, 0),
    mining = minings.getOrDefault(id, 0), teaching = teachings.getOrDefault(id, 0),
    morality = moralities.getOrDefault(id, 0),
    // 资质默认值必须为 DEFAULT_APTITUDE(50)（自愈哨兵，与列直读/Migration/序列化统一）
    aptitude = aptitudes.getOrDefault(id, DiscipleTables.DEFAULT_APTITUDE),
    salaryPaidCount = salaryPaidCounts.getOrDefault(id, 0),
    salaryMissedCount = salaryMissedCounts.getOrDefault(id, 0),
    alchemyLevel = alchemyLevels.getOrDefault(id, 0),
    alchemyPromotionCount = alchemyPromotionCounts.getOrDefault(id, 0),
    forgeLevel = forgeLevels.getOrDefault(id, 0),
    forgePromotionCount = forgePromotionCounts.getOrDefault(id, 0)
)

internal fun DiscipleTables.assembleUsage(id: Int) = UsageTracking(
    usedFunctionalPillTypes = usedFunctionalPillTypes.getOrNull(id) ?: emptyList(),
    usedExtendLifePillIds = usedExtendLifePillIds.getOrNull(id) ?: emptyList(),
    usedPermanentPillKeys = usedPermanentPillKeys.getOrNull(id) ?: emptySet(),
    usedExtendLifePillTypes = usedExtendLifePillTypes.getOrNull(id) ?: emptySet(),
    recruitedMonth = recruitedMonths.getOrDefault(id, 0),
    hasReviveEffect = hasReviveEffects.getOrDefault(id, 0) == 1,
    hasClearAllEffect = hasClearAllEffects.getOrDefault(id, 0) == 1,
)
