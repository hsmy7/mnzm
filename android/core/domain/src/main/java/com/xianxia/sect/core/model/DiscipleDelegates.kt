@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.model

/**
 * Disciple 委托扩展属性 — 由 @Embedded 字段自动映射的快捷访问属性。
 *
 * 所有扩展属性通过 KSP 处理器（DelegateFieldProcessor）从
 * @Embedded 字段的 @ExposeDelegates 注解生成。
 * 新增委托：在 @Embedded 字段上添加属性名到 @ExposeDelegates 即可。
 *
 * 例外：monthlyUsedPillIds（属性名与源字段 usedFunctionalPillTypes 不同）在 Disciple 中手动维护。
 */

// ── CombatAttributes ──

/** @deprecated 请改用 [combat.baseHp] */
var Disciple.baseHp: kotlin.Int get() = combat.baseHp; set(value) { combat.baseHp = value }
/** @deprecated 请改用 [combat.baseMp] */
var Disciple.baseMp: kotlin.Int get() = combat.baseMp; set(value) { combat.baseMp = value }
/** @deprecated 请改用 [combat.basePhysicalAttack] */
var Disciple.basePhysicalAttack: kotlin.Int get() = combat.basePhysicalAttack; set(value) { combat.basePhysicalAttack = value }
/** @deprecated 请改用 [combat.baseMagicAttack] */
var Disciple.baseMagicAttack: kotlin.Int get() = combat.baseMagicAttack; set(value) { combat.baseMagicAttack = value }
/** @deprecated 请改用 [combat.basePhysicalDefense] */
var Disciple.basePhysicalDefense: kotlin.Int get() = combat.basePhysicalDefense; set(value) { combat.basePhysicalDefense = value }
/** @deprecated 请改用 [combat.baseMagicDefense] */
var Disciple.baseMagicDefense: kotlin.Int get() = combat.baseMagicDefense; set(value) { combat.baseMagicDefense = value }
/** @deprecated 请改用 [combat.baseSpeed] */
var Disciple.baseSpeed: kotlin.Int get() = combat.baseSpeed; set(value) { combat.baseSpeed = value }
/** @deprecated 请改用 [combat.hpVariance] */
var Disciple.hpVariance: kotlin.Int get() = combat.hpVariance; set(value) { combat.hpVariance = value }
/** @deprecated 请改用 [combat.mpVariance] */
var Disciple.mpVariance: kotlin.Int get() = combat.mpVariance; set(value) { combat.mpVariance = value }
/** @deprecated 请改用 [combat.physicalAttackVariance] */
var Disciple.physicalAttackVariance: kotlin.Int get() = combat.physicalAttackVariance; set(value) { combat.physicalAttackVariance = value }
/** @deprecated 请改用 [combat.magicAttackVariance] */
var Disciple.magicAttackVariance: kotlin.Int get() = combat.magicAttackVariance; set(value) { combat.magicAttackVariance = value }
/** @deprecated 请改用 [combat.physicalDefenseVariance] */
var Disciple.physicalDefenseVariance: kotlin.Int get() = combat.physicalDefenseVariance; set(value) { combat.physicalDefenseVariance = value }
/** @deprecated 请改用 [combat.magicDefenseVariance] */
var Disciple.magicDefenseVariance: kotlin.Int get() = combat.magicDefenseVariance; set(value) { combat.magicDefenseVariance = value }
/** @deprecated 请改用 [combat.speedVariance] */
var Disciple.speedVariance: kotlin.Int get() = combat.speedVariance; set(value) { combat.speedVariance = value }
/** @deprecated 请改用 [combat.totalCultivation] */
var Disciple.totalCultivation: kotlin.Long get() = combat.totalCultivation; set(value) { combat.totalCultivation = value }
/** @deprecated 请改用 [combat.breakthroughCount] */
var Disciple.breakthroughCount: kotlin.Int get() = combat.breakthroughCount; set(value) { combat.breakthroughCount = value }
/** @deprecated 请改用 [combat.breakthroughFailCount] */
var Disciple.breakthroughFailCount: kotlin.Int get() = combat.breakthroughFailCount; set(value) { combat.breakthroughFailCount = value }
/** @deprecated 请改用 [combat.currentHp] */
var Disciple.currentHp: kotlin.Int get() = combat.currentHp; set(value) { combat.currentHp = value }
/** @deprecated 请改用 [combat.currentMp] */
var Disciple.currentMp: kotlin.Int get() = combat.currentMp; set(value) { combat.currentMp = value }

// ── PillEffects ──

/** @deprecated 请改用 [pillEffects.pillPhysicalAttackBonus] */
var Disciple.pillPhysicalAttackBonus: kotlin.Int get() = pillEffects.pillPhysicalAttackBonus; set(value) { pillEffects.pillPhysicalAttackBonus = value }
/** @deprecated 请改用 [pillEffects.pillMagicAttackBonus] */
var Disciple.pillMagicAttackBonus: kotlin.Int get() = pillEffects.pillMagicAttackBonus; set(value) { pillEffects.pillMagicAttackBonus = value }
/** @deprecated 请改用 [pillEffects.pillPhysicalDefenseBonus] */
var Disciple.pillPhysicalDefenseBonus: kotlin.Int get() = pillEffects.pillPhysicalDefenseBonus; set(value) { pillEffects.pillPhysicalDefenseBonus = value }
/** @deprecated 请改用 [pillEffects.pillMagicDefenseBonus] */
var Disciple.pillMagicDefenseBonus: kotlin.Int get() = pillEffects.pillMagicDefenseBonus; set(value) { pillEffects.pillMagicDefenseBonus = value }
/** @deprecated 请改用 [pillEffects.pillHpBonus] */
var Disciple.pillHpBonus: kotlin.Int get() = pillEffects.pillHpBonus; set(value) { pillEffects.pillHpBonus = value }
/** @deprecated 请改用 [pillEffects.pillMpBonus] */
var Disciple.pillMpBonus: kotlin.Int get() = pillEffects.pillMpBonus; set(value) { pillEffects.pillMpBonus = value }
/** @deprecated 请改用 [pillEffects.pillSpeedBonus] */
var Disciple.pillSpeedBonus: kotlin.Int get() = pillEffects.pillSpeedBonus; set(value) { pillEffects.pillSpeedBonus = value }
/** @deprecated 请改用 [pillEffects.pillEffectDuration] */
var Disciple.pillEffectDuration: kotlin.Int get() = pillEffects.pillEffectDuration; set(value) { pillEffects.pillEffectDuration = value }
/** @deprecated 请改用 [pillEffects.pillCritRateBonus] */
var Disciple.pillCritRateBonus: kotlin.Double get() = pillEffects.pillCritRateBonus; set(value) { pillEffects.pillCritRateBonus = value }
/** @deprecated 请改用 [pillEffects.pillCritEffectBonus] */
var Disciple.pillCritEffectBonus: kotlin.Double get() = pillEffects.pillCritEffectBonus; set(value) { pillEffects.pillCritEffectBonus = value }
/** @deprecated 请改用 [pillEffects.pillCultivationSpeedBonus] */
var Disciple.pillCultivationSpeedBonus: kotlin.Double get() = pillEffects.pillCultivationSpeedBonus; set(value) { pillEffects.pillCultivationSpeedBonus = value }
/** @deprecated 请改用 [pillEffects.pillSkillExpSpeedBonus] */
var Disciple.pillSkillExpSpeedBonus: kotlin.Double get() = pillEffects.pillSkillExpSpeedBonus; set(value) { pillEffects.pillSkillExpSpeedBonus = value }
/** @deprecated 请改用 [pillEffects.pillNurtureSpeedBonus] */
var Disciple.pillNurtureSpeedBonus: kotlin.Double get() = pillEffects.pillNurtureSpeedBonus; set(value) { pillEffects.pillNurtureSpeedBonus = value }
/** @deprecated 请改用 [pillEffects.activePillCategory] */
var Disciple.activePillCategory: kotlin.String get() = pillEffects.activePillCategory; set(value) { pillEffects.activePillCategory = value }

// ── EquipmentSet ──

/** @deprecated 请改用 [equipment.weaponId] */
var Disciple.weaponId: kotlin.String get() = equipment.weaponId; set(value) { equipment.weaponId = value }
/** @deprecated 请改用 [equipment.armorId] */
var Disciple.armorId: kotlin.String get() = equipment.armorId; set(value) { equipment.armorId = value }
/** @deprecated 请改用 [equipment.bootsId] */
var Disciple.bootsId: kotlin.String get() = equipment.bootsId; set(value) { equipment.bootsId = value }
/** @deprecated 请改用 [equipment.accessoryId] */
var Disciple.accessoryId: kotlin.String get() = equipment.accessoryId; set(value) { equipment.accessoryId = value }
/** @deprecated 请改用 [equipment.weaponNurture] */
var Disciple.weaponNurture: EquipmentNurtureData get() = equipment.weaponNurture; set(value) { equipment.weaponNurture = value }
/** @deprecated 请改用 [equipment.armorNurture] */
var Disciple.armorNurture: EquipmentNurtureData get() = equipment.armorNurture; set(value) { equipment.armorNurture = value }
/** @deprecated 请改用 [equipment.bootsNurture] */
var Disciple.bootsNurture: EquipmentNurtureData get() = equipment.bootsNurture; set(value) { equipment.bootsNurture = value }
/** @deprecated 请改用 [equipment.accessoryNurture] */
var Disciple.accessoryNurture: EquipmentNurtureData get() = equipment.accessoryNurture; set(value) { equipment.accessoryNurture = value }
/** @deprecated 请改用 [equipment.storageBagItems] */
var Disciple.storageBagItems: kotlin.collections.List<StorageBagItem> get() = equipment.storageBagItems; set(value) { equipment.storageBagItems = value }
/** @deprecated 请改用 [equipment.storageBagSpiritStones] */
var Disciple.storageBagSpiritStones: kotlin.Long get() = equipment.storageBagSpiritStones; set(value) { equipment.storageBagSpiritStones = value }
/** @deprecated 请改用 [equipment.spiritStones] */
var Disciple.spiritStones: kotlin.Int get() = equipment.spiritStones; set(value) { equipment.spiritStones = value }

// ── SocialData ──

/** @deprecated 请改用 [social.partnerId] */
var Disciple.partnerId: kotlin.String? get() = social.partnerId; set(value) { social.partnerId = value }
/** @deprecated 请改用 [social.partnerSectId] */
var Disciple.partnerSectId: kotlin.String? get() = social.partnerSectId; set(value) { social.partnerSectId = value }
/** @deprecated 请改用 [social.parentId1] */
var Disciple.parentId1: kotlin.String? get() = social.parentId1; set(value) { social.parentId1 = value }
/** @deprecated 请改用 [social.parentId2] */
var Disciple.parentId2: kotlin.String? get() = social.parentId2; set(value) { social.parentId2 = value }
/** @deprecated 请改用 [social.lastChildYear] */
var Disciple.lastChildYear: kotlin.Int get() = social.lastChildYear; set(value) { social.lastChildYear = value }
/** @deprecated 请改用 [social.childBirthMonth] */
var Disciple.childBirthMonth: kotlin.Int? get() = social.childBirthMonth; set(value) { social.childBirthMonth = value }
/** @deprecated 请改用 [social.griefEndYear] */
var Disciple.griefEndYear: kotlin.Int? get() = social.griefEndYear; set(value) { social.griefEndYear = value }

// ── SkillStats ──

/** @deprecated 请改用 [skills.intelligence] */
var Disciple.intelligence: kotlin.Int get() = skills.intelligence; set(value) { skills.intelligence = value }
/** @deprecated 请改用 [skills.charm] */
var Disciple.charm: kotlin.Int get() = skills.charm; set(value) { skills.charm = value }
/** @deprecated 请改用 [skills.loyalty] */
var Disciple.loyalty: kotlin.Int get() = skills.loyalty; set(value) { skills.loyalty = value }
/** @deprecated 请改用 [skills.comprehension] */
var Disciple.comprehension: kotlin.Int get() = skills.comprehension; set(value) { skills.comprehension = value }
/** @deprecated 请改用 [skills.artifactRefining] */
var Disciple.artifactRefining: kotlin.Int get() = skills.artifactRefining; set(value) { skills.artifactRefining = value }
/** @deprecated 请改用 [skills.pillRefining] */
var Disciple.pillRefining: kotlin.Int get() = skills.pillRefining; set(value) { skills.pillRefining = value }
/** @deprecated 请改用 [skills.spiritPlanting] */
var Disciple.spiritPlanting: kotlin.Int get() = skills.spiritPlanting; set(value) { skills.spiritPlanting = value }
/** @deprecated 请改用 [skills.teaching] */
var Disciple.teaching: kotlin.Int get() = skills.teaching; set(value) { skills.teaching = value }
/** @deprecated 请改用 [skills.mining] */
var Disciple.mining: kotlin.Int get() = skills.mining; set(value) { skills.mining = value }
/** @deprecated 请改用 [skills.morality] */
var Disciple.morality: kotlin.Int get() = skills.morality; set(value) { skills.morality = value }
/** @deprecated 请改用 [skills.salaryPaidCount] */
var Disciple.salaryPaidCount: kotlin.Int get() = skills.salaryPaidCount; set(value) { skills.salaryPaidCount = value }
/** @deprecated 请改用 [skills.salaryMissedCount] */
var Disciple.salaryMissedCount: kotlin.Int get() = skills.salaryMissedCount; set(value) { skills.salaryMissedCount = value }

// ── UsageTracking ──

/** @deprecated 请改用 [usage.usedExtendLifePillIds] */
var Disciple.usedExtendLifePillIds: kotlin.collections.List<String> get() = usage.usedExtendLifePillIds; set(value) { usage.usedExtendLifePillIds = value }
/** @deprecated 请改用 [usage.recruitedMonth] */
var Disciple.recruitedMonth: kotlin.Int get() = usage.recruitedMonth; set(value) { usage.recruitedMonth = value }
/** @deprecated 请改用 [usage.hasReviveEffect] */
var Disciple.hasReviveEffect: kotlin.Boolean get() = usage.hasReviveEffect; set(value) { usage.hasReviveEffect = value }
/** @deprecated 请改用 [usage.hasClearAllEffect] */
var Disciple.hasClearAllEffect: kotlin.Boolean get() = usage.hasClearAllEffect; set(value) { usage.hasClearAllEffect = value }
