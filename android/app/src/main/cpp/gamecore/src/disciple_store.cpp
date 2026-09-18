#include "gamecore/state/models.h"  // 定义 Disciple/嵌套类型 + 尾部引入 disciple_store.h

#include <algorithm>
#include <stdexcept>

#include "gamecore/state/column_dirty.h"

namespace gamecore::state {

std::pair<int32_t, int8_t> DiscipleStore::parseNumericId(const std::string& s) {
    // Kotlin String.toIntOrNull 同口径：全串严格校验（与 settle_util::
    // toIntOrNull 同实现；state 层不反向依赖 system 层故单点复制）
    if (s.empty()) return {0, 0};
    try {
        std::size_t pos = 0;
        const long v = std::stol(s, &pos);
        if (pos != s.size()) return {0, 0};   // Kotlin 全串校验
        return {static_cast<int32_t>(v), 1};
    } catch (...) {
        return {0, 0};
    }
}

Disciple DiscipleStore::materialize(std::size_t row) const {
    Disciple d;
    d.id = ids[row];
    d.name = names[row];
    d.surname = surnames[row];
    d.gender = genders[row];
    d.portraitRes = portraitRes[row];
    d.discipleType = discipleTypes[row];
    d.spiritRootType = spiritRootTypes[row];

    d.realm = realms[row];
    d.realmLayer = realmLayers[row];
    d.cultivation = cultivations[row];
    d.cultivationCheckpoint = cultivationCheckpoints[row];
    d.cultivationCheckpointGameMonth = cultivationCheckpointGameMonths[row];
    d.age = ages[row];
    d.lifespan = lifespans[row];
    d.isAlive = isAlive[row] != 0;
    d.soulPower = soulPowers[row];

    d.cultivationSpeedBonus = cultivationSpeedBonuses[row];
    d.cultivationSpeedDuration = cultivationSpeedDurations[row];

    d.manualIds = manualIds[row];
    d.talentIds = talentIds[row];
    d.physiqueIds = physiqueIds[row];
    d.affixIds = affixIds[row];
    d.manualMasteries = manualMasteries[row];
    d.status = statuses[row];
    d.statusData = statusData[row];

    d.cultivationCompletionMonth = cultivationCompletionMonths[row];
    d.cultivationCompletionPhase = cultivationCompletionPhases[row];
    d.manualCompletionMonth = manualCompletionMonths[row];
    d.manualCompletionPhase = manualCompletionPhases[row];
    d.equipmentNurturingCompletionMonth = equipmentNurturingCompletionMonths[row];
    d.equipmentNurturingCompletionPhase = equipmentNurturingCompletionPhases[row];

    // CombatAttributes
    d.baseHp = baseHps[row];
    d.baseMp = baseMps[row];
    d.basePhysicalAttack = basePhysicalAttacks[row];
    d.baseMagicAttack = baseMagicAttacks[row];
    d.basePhysicalDefense = basePhysicalDefenses[row];
    d.baseMagicDefense = baseMagicDefenses[row];
    d.baseSpeed = baseSpeeds[row];
    d.hpVariance = hpVariances[row];
    d.mpVariance = mpVariances[row];
    d.physicalAttackVariance = physicalAttackVariances[row];
    d.magicAttackVariance = magicAttackVariances[row];
    d.physicalDefenseVariance = physicalDefenseVariances[row];
    d.magicDefenseVariance = magicDefenseVariances[row];
    d.speedVariance = speedVariances[row];
    d.totalCultivation = totalCultivations[row];
    d.breakthroughCount = breakthroughCounts[row];
    d.breakthroughFailCount = breakthroughFailCounts[row];
    d.currentHp = currentHps[row];
    d.currentMp = currentMps[row];

    // PillEffects
    d.pillPhysicalAttackBonus = pillPhysicalAttackBonuses[row];
    d.pillMagicAttackBonus = pillMagicAttackBonuses[row];
    d.pillPhysicalDefenseBonus = pillPhysicalDefenseBonuses[row];
    d.pillMagicDefenseBonus = pillMagicDefenseBonuses[row];
    d.pillHpBonus = pillHpBonuses[row];
    d.pillMpBonus = pillMpBonuses[row];
    d.pillSpeedBonus = pillSpeedBonuses[row];
    d.pillCritRateBonus = pillCritRateBonuses[row];
    d.pillCritEffectBonus = pillCritEffectBonuses[row];
    d.pillCultivationSpeedBonus = pillCultivationSpeedBonuses[row];
    d.pillSkillExpSpeedBonus = pillSkillExpSpeedBonuses[row];
    d.pillNurtureSpeedBonus = pillNurtureSpeedBonuses[row];
    d.pillEffectDuration = pillEffectDurations[row];
    d.activePillTypes = activePillTypes[row];
    d.activePillCategory = activePillCategories[row];

    // EquipmentSet
    d.weaponId = weaponIds[row];
    d.armorId = armorIds[row];
    d.bootsId = bootsIds[row];
    d.accessoryId = accessoryIds[row];
    d.weaponNurture = weaponNurtures[row];
    d.armorNurture = armorNurtures[row];
    d.bootsNurture = bootsNurtures[row];
    d.accessoryNurture = accessoryNurtures[row];
    d.storageBagItems = storageBagItems[row];
    d.storageBagSpiritStones = storageBagSpiritStones[row];
    d.spiritStones = spiritStones[row];

    // SocialData
    d.partnerId = partnerIds[row];
    d.partnerSectId = partnerSectIds[row];
    d.parentId1 = parentId1s[row];
    d.parentId2 = parentId2s[row];
    d.lastChildYear = lastChildYears[row];
    d.childBirthMonth = childBirthMonths[row];
    d.griefEndYear = griefEndYears[row];
    d.masterId = masterIds[row];

    // SkillStats
    d.intelligence = intelligences[row];
    d.charm = charms[row];
    d.loyalty = loyalties[row];
    d.comprehension = comprehensions[row];
    d.artifactRefining = artifactRefinings[row];
    d.pillRefining = pillRefinings[row];
    d.spiritPlanting = spiritPlantings[row];
    d.mining = minings[row];
    d.teaching = teachings[row];
    d.morality = moralities[row];
    d.aptitude = aptitudes[row];
    d.salaryPaidCount = salaryPaidCounts[row];
    d.salaryMissedCount = salaryMissedCounts[row];
    d.alchemyLevel = alchemyLevels[row];
    d.alchemyPromotionCount = alchemyPromotionCounts[row];
    d.forgeLevel = forgeLevels[row];
    d.forgePromotionCount = forgePromotionCounts[row];

    // UsageTracking
    d.usedPermanentPillKeys = usedPermanentPillKeys[row];
    d.usedExtendLifePillTypes = usedExtendLifePillTypes[row];
    d.usedFunctionalPillTypes = usedFunctionalPillTypes[row];
    d.usedExtendLifePillIds = usedExtendLifePillIds[row];
    d.recruitedMonth = recruitedMonths[row];
    d.hasReviveEffect = hasReviveEffects[row] != 0;
    d.hasClearAllEffect = hasClearAllEffects[row] != 0;
    return d;
}

void DiscipleStore::appendDisciple(const Disciple& d) {
    // 同 id 已存在：原位覆盖（SparseArray 写入语义——不产生重复行；
    // upsertDisciple 保持行序）
    if (idToRow.find(d.id) != idToRow.end()) {
        upsertDisciple(d);
        return;
    }
    const std::size_t row = ids.size();
    ids.push_back(d.id);
    const auto [numId, numOk] = parseNumericId(d.id);
    numericIds.push_back(numOk ? numId : 0);
    hasNumericIds.push_back(numOk);
    names.push_back(d.name);
    surnames.push_back(d.surname);
    genders.push_back(d.gender);
    portraitRes.push_back(d.portraitRes);
    discipleTypes.push_back(d.discipleType);
    spiritRootTypes.push_back(d.spiritRootType);

    realms.push_back(d.realm);
    realmLayers.push_back(d.realmLayer);
    cultivations.push_back(d.cultivation);
    cultivationCheckpoints.push_back(d.cultivationCheckpoint);
    cultivationCheckpointGameMonths.push_back(d.cultivationCheckpointGameMonth);
    ages.push_back(d.age);
    lifespans.push_back(d.lifespan);
    isAlive.push_back(d.isAlive ? 1 : 0);
    deathYears.push_back(0);  // 新弟子无死亡年份（Kotlin 稀疏表无条目语义）
    lastTheftJudgementYears.push_back(0);  // 新弟子从未判定（Kotlin 稀疏表无条目语义）
    soulPowers.push_back(d.soulPower);

    cultivationSpeedBonuses.push_back(d.cultivationSpeedBonus);
    cultivationSpeedDurations.push_back(d.cultivationSpeedDuration);

    manualIds.push_back(d.manualIds);
    talentIds.push_back(d.talentIds);
    physiqueIds.push_back(d.physiqueIds);
    affixIds.push_back(d.affixIds);
    manualMasteries.push_back(d.manualMasteries);
    statuses.push_back(d.status);
    statusData.push_back(d.statusData);

    cultivationCompletionMonths.push_back(d.cultivationCompletionMonth);
    cultivationCompletionPhases.push_back(d.cultivationCompletionPhase);
    manualCompletionMonths.push_back(d.manualCompletionMonth);
    manualCompletionPhases.push_back(d.manualCompletionPhase);
    equipmentNurturingCompletionMonths.push_back(d.equipmentNurturingCompletionMonth);
    equipmentNurturingCompletionPhases.push_back(d.equipmentNurturingCompletionPhase);

    baseHps.push_back(d.baseHp);
    baseMps.push_back(d.baseMp);
    basePhysicalAttacks.push_back(d.basePhysicalAttack);
    baseMagicAttacks.push_back(d.baseMagicAttack);
    basePhysicalDefenses.push_back(d.basePhysicalDefense);
    baseMagicDefenses.push_back(d.baseMagicDefense);
    baseSpeeds.push_back(d.baseSpeed);
    hpVariances.push_back(d.hpVariance);
    mpVariances.push_back(d.mpVariance);
    physicalAttackVariances.push_back(d.physicalAttackVariance);
    magicAttackVariances.push_back(d.magicAttackVariance);
    physicalDefenseVariances.push_back(d.physicalDefenseVariance);
    magicDefenseVariances.push_back(d.magicDefenseVariance);
    speedVariances.push_back(d.speedVariance);
    totalCultivations.push_back(d.totalCultivation);
    breakthroughCounts.push_back(d.breakthroughCount);
    breakthroughFailCounts.push_back(d.breakthroughFailCount);
    currentHps.push_back(d.currentHp);
    currentMps.push_back(d.currentMp);

    pillPhysicalAttackBonuses.push_back(d.pillPhysicalAttackBonus);
    pillMagicAttackBonuses.push_back(d.pillMagicAttackBonus);
    pillPhysicalDefenseBonuses.push_back(d.pillPhysicalDefenseBonus);
    pillMagicDefenseBonuses.push_back(d.pillMagicDefenseBonus);
    pillHpBonuses.push_back(d.pillHpBonus);
    pillMpBonuses.push_back(d.pillMpBonus);
    pillSpeedBonuses.push_back(d.pillSpeedBonus);
    pillCritRateBonuses.push_back(d.pillCritRateBonus);
    pillCritEffectBonuses.push_back(d.pillCritEffectBonus);
    pillCultivationSpeedBonuses.push_back(d.pillCultivationSpeedBonus);
    pillSkillExpSpeedBonuses.push_back(d.pillSkillExpSpeedBonus);
    pillNurtureSpeedBonuses.push_back(d.pillNurtureSpeedBonus);
    pillEffectDurations.push_back(d.pillEffectDuration);
    activePillTypes.push_back(d.activePillTypes);
    activePillCategories.push_back(d.activePillCategory);

    weaponIds.push_back(d.weaponId);
    armorIds.push_back(d.armorId);
    bootsIds.push_back(d.bootsId);
    accessoryIds.push_back(d.accessoryId);
    weaponNurtures.push_back(d.weaponNurture);
    armorNurtures.push_back(d.armorNurture);
    bootsNurtures.push_back(d.bootsNurture);
    accessoryNurtures.push_back(d.accessoryNurture);
    storageBagItems.push_back(d.storageBagItems);
    storageBagSpiritStones.push_back(d.storageBagSpiritStones);
    spiritStones.push_back(d.spiritStones);

    partnerIds.push_back(d.partnerId);
    partnerSectIds.push_back(d.partnerSectId);
    parentId1s.push_back(d.parentId1);
    parentId2s.push_back(d.parentId2);
    lastChildYears.push_back(d.lastChildYear);
    childBirthMonths.push_back(d.childBirthMonth);
    griefEndYears.push_back(d.griefEndYear);
    masterIds.push_back(d.masterId);

    intelligences.push_back(d.intelligence);
    charms.push_back(d.charm);
    loyalties.push_back(d.loyalty);
    comprehensions.push_back(d.comprehension);
    artifactRefinings.push_back(d.artifactRefining);
    pillRefinings.push_back(d.pillRefining);
    spiritPlantings.push_back(d.spiritPlanting);
    minings.push_back(d.mining);
    teachings.push_back(d.teaching);
    moralities.push_back(d.morality);
    aptitudes.push_back(d.aptitude);
    salaryPaidCounts.push_back(d.salaryPaidCount);
    salaryMissedCounts.push_back(d.salaryMissedCount);
    alchemyLevels.push_back(d.alchemyLevel);
    alchemyPromotionCounts.push_back(d.alchemyPromotionCount);
    forgeLevels.push_back(d.forgeLevel);
    forgePromotionCounts.push_back(d.forgePromotionCount);

    usedPermanentPillKeys.push_back(d.usedPermanentPillKeys);
    usedExtendLifePillTypes.push_back(d.usedExtendLifePillTypes);
    usedFunctionalPillTypes.push_back(d.usedFunctionalPillTypes);
    usedExtendLifePillIds.push_back(d.usedExtendLifePillIds);
    recruitedMonths.push_back(d.recruitedMonth);
    hasReviveEffects.push_back(d.hasReviveEffect ? 1 : 0);
    hasClearAllEffects.push_back(d.hasClearAllEffect ? 1 : 0);

    // 同 id 保留最后（SparseArray 写入语义）；行序 = 追加序
    idToRow[d.id] = row;
    if (numOk) numericIdToRow[numId] = row;

    // R1.4 列级写屏障：新行整行标脏（挂载时；未挂载零开销直通）
    if (columnDirty_) columnDirty_->markRowAllColumns(row);
}

void DiscipleStore::loadFromVector(const std::vector<Disciple>& disciples) {
    clear();
    for (const Disciple& d : disciples) {
        appendDisciple(d);
    }
}

void DiscipleStore::upsertDisciple(const Disciple& d) {
    const auto it = idToRow.find(d.id);
    if (it == idToRow.end()) {
        // 新弟子：追加末尾（Kotlin ids 追加序 == max+1 升序）
        appendDisciple(d);
        return;
    }
    // 已有弟子：原位覆盖（保持行序——RNG 对拍红线）。
    // 实现：移除旧行 → 末尾追加 → 将 [row, last) 段右旋一格，使新行回到原位。
    const std::size_t row = it->second;
    // ★ 保存 deathYears（Kotlin replaceAll 恢复语义——writeAllFields 不清此列：
    //   "assembleAll → map 标记 → replaceAll → 补 deathYears" 流水线依赖
    //   replaceAll 保留已写 deathYears；appendDisciple 新行默认 0，需旋转后写回）
    const int32_t savedDeathYear = deathYears[row];
    // ★ 同法保存 lastTheftJudgementYears（Kotlin tables.update 不触碰稀疏表——
    //   偷盗年判定标记跨列写存活）
    const int32_t savedTheftJudgementYear = lastTheftJudgementYears[row];
    eraseAt(row);
    appendDisciple(d);
    for (std::size_t i = ids.size() - 1; i > row; --i) {
        swapRows(i, i - 1);
    }
    deathYears[row] = savedDeathYear;
    lastTheftJudgementYears[row] = savedTheftJudgementYear;
}

void DiscipleStore::removeById(const std::string& id) {
    const auto it = idToRow.find(id);
    if (it == idToRow.end()) return;
    eraseAt(it->second);
}

void DiscipleStore::clear() {
    // R1.4 列级写屏障：清空即全体删除——逐 id tombstone 记账（先于清空，
    // ids 仍在）；行位图随行消亡复位（tombstone 保留供导出 removed）
    if (columnDirty_) {
        for (const auto& id : ids) {
            columnDirty_->tombstone(kDisciplesCollection, id);
        }
        columnDirty_->clearRowBits();
    }
    ids.clear();
    numericIds.clear();
    hasNumericIds.clear();
    names.clear();
    surnames.clear();
    genders.clear();
    portraitRes.clear();
    discipleTypes.clear();
    spiritRootTypes.clear();
    realms.clear();
    realmLayers.clear();
    cultivations.clear();
    cultivationCheckpoints.clear();
    cultivationCheckpointGameMonths.clear();
    ages.clear();
    lifespans.clear();
    isAlive.clear();
    deathYears.clear();
    lastTheftJudgementYears.clear();
    soulPowers.clear();
    cultivationSpeedBonuses.clear();
    cultivationSpeedDurations.clear();
    manualIds.clear();
    talentIds.clear();
    physiqueIds.clear();
    affixIds.clear();
    manualMasteries.clear();
    statuses.clear();
    statusData.clear();
    cultivationCompletionMonths.clear();
    cultivationCompletionPhases.clear();
    manualCompletionMonths.clear();
    manualCompletionPhases.clear();
    equipmentNurturingCompletionMonths.clear();
    equipmentNurturingCompletionPhases.clear();
    baseHps.clear();
    baseMps.clear();
    basePhysicalAttacks.clear();
    baseMagicAttacks.clear();
    basePhysicalDefenses.clear();
    baseMagicDefenses.clear();
    baseSpeeds.clear();
    hpVariances.clear();
    mpVariances.clear();
    physicalAttackVariances.clear();
    magicAttackVariances.clear();
    physicalDefenseVariances.clear();
    magicDefenseVariances.clear();
    speedVariances.clear();
    totalCultivations.clear();
    breakthroughCounts.clear();
    breakthroughFailCounts.clear();
    currentHps.clear();
    currentMps.clear();
    pillPhysicalAttackBonuses.clear();
    pillMagicAttackBonuses.clear();
    pillPhysicalDefenseBonuses.clear();
    pillMagicDefenseBonuses.clear();
    pillHpBonuses.clear();
    pillMpBonuses.clear();
    pillSpeedBonuses.clear();
    pillCritRateBonuses.clear();
    pillCritEffectBonuses.clear();
    pillCultivationSpeedBonuses.clear();
    pillSkillExpSpeedBonuses.clear();
    pillNurtureSpeedBonuses.clear();
    pillEffectDurations.clear();
    activePillTypes.clear();
    activePillCategories.clear();
    weaponIds.clear();
    armorIds.clear();
    bootsIds.clear();
    accessoryIds.clear();
    weaponNurtures.clear();
    armorNurtures.clear();
    bootsNurtures.clear();
    accessoryNurtures.clear();
    storageBagItems.clear();
    storageBagSpiritStones.clear();
    spiritStones.clear();
    partnerIds.clear();
    partnerSectIds.clear();
    parentId1s.clear();
    parentId2s.clear();
    lastChildYears.clear();
    childBirthMonths.clear();
    griefEndYears.clear();
    masterIds.clear();
    intelligences.clear();
    charms.clear();
    loyalties.clear();
    comprehensions.clear();
    artifactRefinings.clear();
    pillRefinings.clear();
    spiritPlantings.clear();
    minings.clear();
    teachings.clear();
    moralities.clear();
    aptitudes.clear();
    salaryPaidCounts.clear();
    salaryMissedCounts.clear();
    alchemyLevels.clear();
    alchemyPromotionCounts.clear();
    forgeLevels.clear();
    forgePromotionCounts.clear();
    usedPermanentPillKeys.clear();
    usedExtendLifePillTypes.clear();
    usedFunctionalPillTypes.clear();
    usedExtendLifePillIds.clear();
    recruitedMonths.clear();
    hasReviveEffects.clear();
    hasClearAllEffects.clear();
    idToRow.clear();
    numericIdToRow.clear();
}

void DiscipleStore::eraseAt(std::size_t row) {
    const std::string removedId = ids[row];
    ids.erase(ids.begin() + static_cast<std::ptrdiff_t>(row));
    numericIds.erase(numericIds.begin() + static_cast<std::ptrdiff_t>(row));
    hasNumericIds.erase(hasNumericIds.begin() + static_cast<std::ptrdiff_t>(row));
    names.erase(names.begin() + static_cast<std::ptrdiff_t>(row));
    surnames.erase(surnames.begin() + static_cast<std::ptrdiff_t>(row));
    genders.erase(genders.begin() + static_cast<std::ptrdiff_t>(row));
    portraitRes.erase(portraitRes.begin() + static_cast<std::ptrdiff_t>(row));
    discipleTypes.erase(discipleTypes.begin() + static_cast<std::ptrdiff_t>(row));
    spiritRootTypes.erase(spiritRootTypes.begin() + static_cast<std::ptrdiff_t>(row));
    realms.erase(realms.begin() + static_cast<std::ptrdiff_t>(row));
    realmLayers.erase(realmLayers.begin() + static_cast<std::ptrdiff_t>(row));
    cultivations.erase(cultivations.begin() + static_cast<std::ptrdiff_t>(row));
    cultivationCheckpoints.erase(cultivationCheckpoints.begin() + static_cast<std::ptrdiff_t>(row));
    cultivationCheckpointGameMonths.erase(cultivationCheckpointGameMonths.begin() + static_cast<std::ptrdiff_t>(row));
    ages.erase(ages.begin() + static_cast<std::ptrdiff_t>(row));
    lifespans.erase(lifespans.begin() + static_cast<std::ptrdiff_t>(row));
    isAlive.erase(isAlive.begin() + static_cast<std::ptrdiff_t>(row));
    deathYears.erase(deathYears.begin() + static_cast<std::ptrdiff_t>(row));
    lastTheftJudgementYears.erase(lastTheftJudgementYears.begin() + static_cast<std::ptrdiff_t>(row));
    soulPowers.erase(soulPowers.begin() + static_cast<std::ptrdiff_t>(row));
    cultivationSpeedBonuses.erase(cultivationSpeedBonuses.begin() + static_cast<std::ptrdiff_t>(row));
    cultivationSpeedDurations.erase(cultivationSpeedDurations.begin() + static_cast<std::ptrdiff_t>(row));
    manualIds.erase(manualIds.begin() + static_cast<std::ptrdiff_t>(row));
    talentIds.erase(talentIds.begin() + static_cast<std::ptrdiff_t>(row));
    physiqueIds.erase(physiqueIds.begin() + static_cast<std::ptrdiff_t>(row));
    affixIds.erase(affixIds.begin() + static_cast<std::ptrdiff_t>(row));
    manualMasteries.erase(manualMasteries.begin() + static_cast<std::ptrdiff_t>(row));
    statuses.erase(statuses.begin() + static_cast<std::ptrdiff_t>(row));
    statusData.erase(statusData.begin() + static_cast<std::ptrdiff_t>(row));
    cultivationCompletionMonths.erase(cultivationCompletionMonths.begin() + static_cast<std::ptrdiff_t>(row));
    cultivationCompletionPhases.erase(cultivationCompletionPhases.begin() + static_cast<std::ptrdiff_t>(row));
    manualCompletionMonths.erase(manualCompletionMonths.begin() + static_cast<std::ptrdiff_t>(row));
    manualCompletionPhases.erase(manualCompletionPhases.begin() + static_cast<std::ptrdiff_t>(row));
    equipmentNurturingCompletionMonths.erase(equipmentNurturingCompletionMonths.begin() + static_cast<std::ptrdiff_t>(row));
    equipmentNurturingCompletionPhases.erase(equipmentNurturingCompletionPhases.begin() + static_cast<std::ptrdiff_t>(row));
    baseHps.erase(baseHps.begin() + static_cast<std::ptrdiff_t>(row));
    baseMps.erase(baseMps.begin() + static_cast<std::ptrdiff_t>(row));
    basePhysicalAttacks.erase(basePhysicalAttacks.begin() + static_cast<std::ptrdiff_t>(row));
    baseMagicAttacks.erase(baseMagicAttacks.begin() + static_cast<std::ptrdiff_t>(row));
    basePhysicalDefenses.erase(basePhysicalDefenses.begin() + static_cast<std::ptrdiff_t>(row));
    baseMagicDefenses.erase(baseMagicDefenses.begin() + static_cast<std::ptrdiff_t>(row));
    baseSpeeds.erase(baseSpeeds.begin() + static_cast<std::ptrdiff_t>(row));
    hpVariances.erase(hpVariances.begin() + static_cast<std::ptrdiff_t>(row));
    mpVariances.erase(mpVariances.begin() + static_cast<std::ptrdiff_t>(row));
    physicalAttackVariances.erase(physicalAttackVariances.begin() + static_cast<std::ptrdiff_t>(row));
    magicAttackVariances.erase(magicAttackVariances.begin() + static_cast<std::ptrdiff_t>(row));
    physicalDefenseVariances.erase(physicalDefenseVariances.begin() + static_cast<std::ptrdiff_t>(row));
    magicDefenseVariances.erase(magicDefenseVariances.begin() + static_cast<std::ptrdiff_t>(row));
    speedVariances.erase(speedVariances.begin() + static_cast<std::ptrdiff_t>(row));
    totalCultivations.erase(totalCultivations.begin() + static_cast<std::ptrdiff_t>(row));
    breakthroughCounts.erase(breakthroughCounts.begin() + static_cast<std::ptrdiff_t>(row));
    breakthroughFailCounts.erase(breakthroughFailCounts.begin() + static_cast<std::ptrdiff_t>(row));
    currentHps.erase(currentHps.begin() + static_cast<std::ptrdiff_t>(row));
    currentMps.erase(currentMps.begin() + static_cast<std::ptrdiff_t>(row));
    pillPhysicalAttackBonuses.erase(pillPhysicalAttackBonuses.begin() + static_cast<std::ptrdiff_t>(row));
    pillMagicAttackBonuses.erase(pillMagicAttackBonuses.begin() + static_cast<std::ptrdiff_t>(row));
    pillPhysicalDefenseBonuses.erase(pillPhysicalDefenseBonuses.begin() + static_cast<std::ptrdiff_t>(row));
    pillMagicDefenseBonuses.erase(pillMagicDefenseBonuses.begin() + static_cast<std::ptrdiff_t>(row));
    pillHpBonuses.erase(pillHpBonuses.begin() + static_cast<std::ptrdiff_t>(row));
    pillMpBonuses.erase(pillMpBonuses.begin() + static_cast<std::ptrdiff_t>(row));
    pillSpeedBonuses.erase(pillSpeedBonuses.begin() + static_cast<std::ptrdiff_t>(row));
    pillCritRateBonuses.erase(pillCritRateBonuses.begin() + static_cast<std::ptrdiff_t>(row));
    pillCritEffectBonuses.erase(pillCritEffectBonuses.begin() + static_cast<std::ptrdiff_t>(row));
    pillCultivationSpeedBonuses.erase(pillCultivationSpeedBonuses.begin() + static_cast<std::ptrdiff_t>(row));
    pillSkillExpSpeedBonuses.erase(pillSkillExpSpeedBonuses.begin() + static_cast<std::ptrdiff_t>(row));
    pillNurtureSpeedBonuses.erase(pillNurtureSpeedBonuses.begin() + static_cast<std::ptrdiff_t>(row));
    pillEffectDurations.erase(pillEffectDurations.begin() + static_cast<std::ptrdiff_t>(row));
    activePillTypes.erase(activePillTypes.begin() + static_cast<std::ptrdiff_t>(row));
    activePillCategories.erase(activePillCategories.begin() + static_cast<std::ptrdiff_t>(row));
    weaponIds.erase(weaponIds.begin() + static_cast<std::ptrdiff_t>(row));
    armorIds.erase(armorIds.begin() + static_cast<std::ptrdiff_t>(row));
    bootsIds.erase(bootsIds.begin() + static_cast<std::ptrdiff_t>(row));
    accessoryIds.erase(accessoryIds.begin() + static_cast<std::ptrdiff_t>(row));
    weaponNurtures.erase(weaponNurtures.begin() + static_cast<std::ptrdiff_t>(row));
    armorNurtures.erase(armorNurtures.begin() + static_cast<std::ptrdiff_t>(row));
    bootsNurtures.erase(bootsNurtures.begin() + static_cast<std::ptrdiff_t>(row));
    accessoryNurtures.erase(accessoryNurtures.begin() + static_cast<std::ptrdiff_t>(row));
    storageBagItems.erase(storageBagItems.begin() + static_cast<std::ptrdiff_t>(row));
    storageBagSpiritStones.erase(storageBagSpiritStones.begin() + static_cast<std::ptrdiff_t>(row));
    spiritStones.erase(spiritStones.begin() + static_cast<std::ptrdiff_t>(row));
    partnerIds.erase(partnerIds.begin() + static_cast<std::ptrdiff_t>(row));
    partnerSectIds.erase(partnerSectIds.begin() + static_cast<std::ptrdiff_t>(row));
    parentId1s.erase(parentId1s.begin() + static_cast<std::ptrdiff_t>(row));
    parentId2s.erase(parentId2s.begin() + static_cast<std::ptrdiff_t>(row));
    lastChildYears.erase(lastChildYears.begin() + static_cast<std::ptrdiff_t>(row));
    childBirthMonths.erase(childBirthMonths.begin() + static_cast<std::ptrdiff_t>(row));
    griefEndYears.erase(griefEndYears.begin() + static_cast<std::ptrdiff_t>(row));
    masterIds.erase(masterIds.begin() + static_cast<std::ptrdiff_t>(row));
    intelligences.erase(intelligences.begin() + static_cast<std::ptrdiff_t>(row));
    charms.erase(charms.begin() + static_cast<std::ptrdiff_t>(row));
    loyalties.erase(loyalties.begin() + static_cast<std::ptrdiff_t>(row));
    comprehensions.erase(comprehensions.begin() + static_cast<std::ptrdiff_t>(row));
    artifactRefinings.erase(artifactRefinings.begin() + static_cast<std::ptrdiff_t>(row));
    pillRefinings.erase(pillRefinings.begin() + static_cast<std::ptrdiff_t>(row));
    spiritPlantings.erase(spiritPlantings.begin() + static_cast<std::ptrdiff_t>(row));
    minings.erase(minings.begin() + static_cast<std::ptrdiff_t>(row));
    teachings.erase(teachings.begin() + static_cast<std::ptrdiff_t>(row));
    moralities.erase(moralities.begin() + static_cast<std::ptrdiff_t>(row));
    aptitudes.erase(aptitudes.begin() + static_cast<std::ptrdiff_t>(row));
    salaryPaidCounts.erase(salaryPaidCounts.begin() + static_cast<std::ptrdiff_t>(row));
    salaryMissedCounts.erase(salaryMissedCounts.begin() + static_cast<std::ptrdiff_t>(row));
    alchemyLevels.erase(alchemyLevels.begin() + static_cast<std::ptrdiff_t>(row));
    alchemyPromotionCounts.erase(alchemyPromotionCounts.begin() + static_cast<std::ptrdiff_t>(row));
    forgeLevels.erase(forgeLevels.begin() + static_cast<std::ptrdiff_t>(row));
    forgePromotionCounts.erase(forgePromotionCounts.begin() + static_cast<std::ptrdiff_t>(row));
    usedPermanentPillKeys.erase(usedPermanentPillKeys.begin() + static_cast<std::ptrdiff_t>(row));
    usedExtendLifePillTypes.erase(usedExtendLifePillTypes.begin() + static_cast<std::ptrdiff_t>(row));
    usedFunctionalPillTypes.erase(usedFunctionalPillTypes.begin() + static_cast<std::ptrdiff_t>(row));
    usedExtendLifePillIds.erase(usedExtendLifePillIds.begin() + static_cast<std::ptrdiff_t>(row));
    recruitedMonths.erase(recruitedMonths.begin() + static_cast<std::ptrdiff_t>(row));
    hasReviveEffects.erase(hasReviveEffects.begin() + static_cast<std::ptrdiff_t>(row));
    hasClearAllEffects.erase(hasClearAllEffects.begin() + static_cast<std::ptrdiff_t>(row));

    // 重建 idToRow（删除后的行索引位移）
    idToRow.clear();
    for (std::size_t i = 0; i < ids.size(); ++i) {
        idToRow[ids[i]] = i;
    }
    // 同步重建数值索引（复用已解析数值列，免逐行重解析；同 id 保留最后
    // 与 idToRow 重建循环一致）
    numericIdToRow.clear();
    for (std::size_t i = 0; i < ids.size(); ++i) {
        if (hasNumericIds[i] != 0) numericIdToRow[numericIds[i]] = i;
    }
    // R1.4 列级写屏障：被删 id tombstone 记账 + 行位移段（[row, 新行数)
    // 每行内容整体左移一格）全列标脏
    if (columnDirty_) {
        columnDirty_->tombstone(kDisciplesCollection, removedId);
        columnDirty_->markRowsShiftedFrom(row, ids.size());
    }
    (void)removedId;
}

/// 交换两行完整数据（upsertDisciple 保序旋转用；列多，逐列 swap）
void DiscipleStore::swapRows(std::size_t a, std::size_t b) {
    using std::swap;
    swap(ids[a], ids[b]);
    swap(numericIds[a], numericIds[b]);
    swap(hasNumericIds[a], hasNumericIds[b]);
    swap(names[a], names[b]);
    swap(surnames[a], surnames[b]);
    swap(genders[a], genders[b]);
    swap(portraitRes[a], portraitRes[b]);
    swap(discipleTypes[a], discipleTypes[b]);
    swap(spiritRootTypes[a], spiritRootTypes[b]);
    swap(realms[a], realms[b]);
    swap(realmLayers[a], realmLayers[b]);
    swap(cultivations[a], cultivations[b]);
    swap(cultivationCheckpoints[a], cultivationCheckpoints[b]);
    swap(cultivationCheckpointGameMonths[a], cultivationCheckpointGameMonths[b]);
    swap(ages[a], ages[b]);
    swap(lifespans[a], lifespans[b]);
    swap(isAlive[a], isAlive[b]);
    swap(deathYears[a], deathYears[b]);
    swap(lastTheftJudgementYears[a], lastTheftJudgementYears[b]);
    swap(soulPowers[a], soulPowers[b]);
    swap(cultivationSpeedBonuses[a], cultivationSpeedBonuses[b]);
    swap(cultivationSpeedDurations[a], cultivationSpeedDurations[b]);
    swap(manualIds[a], manualIds[b]);
    swap(talentIds[a], talentIds[b]);
    swap(physiqueIds[a], physiqueIds[b]);
    swap(affixIds[a], affixIds[b]);
    swap(manualMasteries[a], manualMasteries[b]);
    swap(statuses[a], statuses[b]);
    swap(statusData[a], statusData[b]);
    swap(cultivationCompletionMonths[a], cultivationCompletionMonths[b]);
    swap(cultivationCompletionPhases[a], cultivationCompletionPhases[b]);
    swap(manualCompletionMonths[a], manualCompletionMonths[b]);
    swap(manualCompletionPhases[a], manualCompletionPhases[b]);
    swap(equipmentNurturingCompletionMonths[a], equipmentNurturingCompletionMonths[b]);
    swap(equipmentNurturingCompletionPhases[a], equipmentNurturingCompletionPhases[b]);
    swap(baseHps[a], baseHps[b]);
    swap(baseMps[a], baseMps[b]);
    swap(basePhysicalAttacks[a], basePhysicalAttacks[b]);
    swap(baseMagicAttacks[a], baseMagicAttacks[b]);
    swap(basePhysicalDefenses[a], basePhysicalDefenses[b]);
    swap(baseMagicDefenses[a], baseMagicDefenses[b]);
    swap(baseSpeeds[a], baseSpeeds[b]);
    swap(hpVariances[a], hpVariances[b]);
    swap(mpVariances[a], mpVariances[b]);
    swap(physicalAttackVariances[a], physicalAttackVariances[b]);
    swap(magicAttackVariances[a], magicAttackVariances[b]);
    swap(physicalDefenseVariances[a], physicalDefenseVariances[b]);
    swap(magicDefenseVariances[a], magicDefenseVariances[b]);
    swap(speedVariances[a], speedVariances[b]);
    swap(totalCultivations[a], totalCultivations[b]);
    swap(breakthroughCounts[a], breakthroughCounts[b]);
    swap(breakthroughFailCounts[a], breakthroughFailCounts[b]);
    swap(currentHps[a], currentHps[b]);
    swap(currentMps[a], currentMps[b]);
    swap(pillPhysicalAttackBonuses[a], pillPhysicalAttackBonuses[b]);
    swap(pillMagicAttackBonuses[a], pillMagicAttackBonuses[b]);
    swap(pillPhysicalDefenseBonuses[a], pillPhysicalDefenseBonuses[b]);
    swap(pillMagicDefenseBonuses[a], pillMagicDefenseBonuses[b]);
    swap(pillHpBonuses[a], pillHpBonuses[b]);
    swap(pillMpBonuses[a], pillMpBonuses[b]);
    swap(pillSpeedBonuses[a], pillSpeedBonuses[b]);
    swap(pillCritRateBonuses[a], pillCritRateBonuses[b]);
    swap(pillCritEffectBonuses[a], pillCritEffectBonuses[b]);
    swap(pillCultivationSpeedBonuses[a], pillCultivationSpeedBonuses[b]);
    swap(pillSkillExpSpeedBonuses[a], pillSkillExpSpeedBonuses[b]);
    swap(pillNurtureSpeedBonuses[a], pillNurtureSpeedBonuses[b]);
    swap(pillEffectDurations[a], pillEffectDurations[b]);
    swap(activePillTypes[a], activePillTypes[b]);
    swap(activePillCategories[a], activePillCategories[b]);
    swap(weaponIds[a], weaponIds[b]);
    swap(armorIds[a], armorIds[b]);
    swap(bootsIds[a], bootsIds[b]);
    swap(accessoryIds[a], accessoryIds[b]);
    swap(weaponNurtures[a], weaponNurtures[b]);
    swap(armorNurtures[a], armorNurtures[b]);
    swap(bootsNurtures[a], bootsNurtures[b]);
    swap(accessoryNurtures[a], accessoryNurtures[b]);
    swap(storageBagItems[a], storageBagItems[b]);
    swap(storageBagSpiritStones[a], storageBagSpiritStones[b]);
    swap(spiritStones[a], spiritStones[b]);
    swap(partnerIds[a], partnerIds[b]);
    swap(partnerSectIds[a], partnerSectIds[b]);
    swap(parentId1s[a], parentId1s[b]);
    swap(parentId2s[a], parentId2s[b]);
    swap(lastChildYears[a], lastChildYears[b]);
    swap(childBirthMonths[a], childBirthMonths[b]);
    swap(griefEndYears[a], griefEndYears[b]);
    swap(masterIds[a], masterIds[b]);
    swap(intelligences[a], intelligences[b]);
    swap(charms[a], charms[b]);
    swap(loyalties[a], loyalties[b]);
    swap(comprehensions[a], comprehensions[b]);
    swap(artifactRefinings[a], artifactRefinings[b]);
    swap(pillRefinings[a], pillRefinings[b]);
    swap(spiritPlantings[a], spiritPlantings[b]);
    swap(minings[a], minings[b]);
    swap(teachings[a], teachings[b]);
    swap(moralities[a], moralities[b]);
    swap(aptitudes[a], aptitudes[b]);
    swap(salaryPaidCounts[a], salaryPaidCounts[b]);
    swap(salaryMissedCounts[a], salaryMissedCounts[b]);
    swap(alchemyLevels[a], alchemyLevels[b]);
    swap(alchemyPromotionCounts[a], alchemyPromotionCounts[b]);
    swap(forgeLevels[a], forgeLevels[b]);
    swap(forgePromotionCounts[a], forgePromotionCounts[b]);
    swap(usedPermanentPillKeys[a], usedPermanentPillKeys[b]);
    swap(usedExtendLifePillTypes[a], usedExtendLifePillTypes[b]);
    swap(usedFunctionalPillTypes[a], usedFunctionalPillTypes[b]);
    swap(usedExtendLifePillIds[a], usedExtendLifePillIds[b]);
    swap(recruitedMonths[a], recruitedMonths[b]);
    swap(hasReviveEffects[a], hasReviveEffects[b]);
    swap(hasClearAllEffects[a], hasClearAllEffects[b]);

    // 同步 idToRow（行索引随交换更新——upsert 保序旋转的索引一致性命门；
    // 业务保证 id 唯一，重复 id 时以交换后的行覆盖为准）
    idToRow[ids[a]] = a;
    idToRow[ids[b]] = b;
    // 数值索引同法同步（键域 = 数值 id 行）
    if (hasNumericIds[a] != 0) numericIdToRow[numericIds[a]] = a;
    if (hasNumericIds[b] != 0) numericIdToRow[numericIds[b]] = b;

    // R1.4 列级写屏障：两行内容互换 ⇒ 两行整行标脏
    if (columnDirty_) {
        columnDirty_->markRowAllColumns(a);
        columnDirty_->markRowAllColumns(b);
    }
}

void DiscipleStore::markCol(DiscipleColumn col, std::size_t row) {
    // B09 R2 热路径写点列标脏：未挂载零开销直通；宁多标不漏标
    //（多标 = 导出重写同值；漏标 = 镜像静默丢变更，对拍守卫红）。
    if (columnDirty_ != nullptr) columnDirty_->markColumn(col, row);
}

}  // namespace gamecore::state
