package com.xianxia.sect.core.state

/**
 * 子对象组装组——[DiscipleTables.assembleAllPatched] 的复用粒度。
 * 每组对应一个 assembleXxx 子对象（lifeEvents 单独一组）。
 */
internal enum class AssembleGroup { COMBAT, PILL, EQUIPMENT, SOCIAL, SKILLS, USAGE, LIFEEVENTS }

/**
 * 列名 → 子对象组映射（原 DiscipleTables 内联表下放；由 lazy 首次访问时单次构建）。
 * 列名从 DiscipleTables 的 buildCopyableRefs 注册表按名解析为索引；未知列（新列未注册映射）
 * 值为 -1 → DiscipleTables.assembleAllPatched 整体退化全量（正确性优先，绝不复用旧数据）。
 * 映射表从 assembleCombat/assemblePillEffects/assembleEquipment/assembleSocial/
 * assembleSkills/assembleUsage 的读取点逐行推导，新增列必须同步更新。
 */
// P3-20（审计）：纯静态映射按需单次构建——deepCopy/回滚基线等纯写副本
// 零成本（原为构造期 eager 构建，90 项 mapOf 每实例重建）
@Suppress("LongMethod") // 列→组映射注册表: 每列一行与 assemble 读取点 1:1 对照, 拆分即碎片化协议对照面（与 DiscipleTables.buildCopyableRefs 同一豁免口径）
internal fun discipleColumnGroupByName(): Map<String, AssembleGroup> = mapOf(
            // assembleCombat 读取列
            "baseHps" to AssembleGroup.COMBAT,
            "baseMps" to AssembleGroup.COMBAT,
            "basePhysicalAttacks" to AssembleGroup.COMBAT,
            "baseMagicAttacks" to AssembleGroup.COMBAT,
            "basePhysicalDefenses" to AssembleGroup.COMBAT,
            "baseMagicDefenses" to AssembleGroup.COMBAT,
            "baseSpeeds" to AssembleGroup.COMBAT,
            "hpVariances" to AssembleGroup.COMBAT,
            "mpVariances" to AssembleGroup.COMBAT,
            "physicalAttackVariances" to AssembleGroup.COMBAT,
            "magicAttackVariances" to AssembleGroup.COMBAT,
            "physicalDefenseVariances" to AssembleGroup.COMBAT,
            "magicDefenseVariances" to AssembleGroup.COMBAT,
            "speedVariances" to AssembleGroup.COMBAT,
            "totalCultivations" to AssembleGroup.COMBAT,
            "breakthroughCounts" to AssembleGroup.COMBAT,
            "breakthroughFailCounts" to AssembleGroup.COMBAT,
            "currentHps" to AssembleGroup.COMBAT,
            "currentMps" to AssembleGroup.COMBAT,
            // assemblePillEffects 读取列
            "pillPhysicalAttackBonuses" to AssembleGroup.PILL,
            "pillMagicAttackBonuses" to AssembleGroup.PILL,
            "pillPhysicalDefenseBonuses" to AssembleGroup.PILL,
            "pillMagicDefenseBonuses" to AssembleGroup.PILL,
            "pillHpBonuses" to AssembleGroup.PILL,
            "pillMpBonuses" to AssembleGroup.PILL,
            "pillSpeedBonuses" to AssembleGroup.PILL,
            "pillEffectDurations" to AssembleGroup.PILL,
            "pillCritRateBonuses" to AssembleGroup.PILL,
            "pillCritEffectBonuses" to AssembleGroup.PILL,
            "pillCultivationSpeedBonuses" to AssembleGroup.PILL,
            "pillSkillExpSpeedBonuses" to AssembleGroup.PILL,
            "pillNurtureSpeedBonuses" to AssembleGroup.PILL,
            "activePillCategories" to AssembleGroup.PILL,
            "activePillTypes" to AssembleGroup.PILL,
            // assembleEquipment 读取列
            "weaponIds" to AssembleGroup.EQUIPMENT,
            "armorIds" to AssembleGroup.EQUIPMENT,
            "bootsIds" to AssembleGroup.EQUIPMENT,
            "accessoryIds" to AssembleGroup.EQUIPMENT,
            "weaponNurtures" to AssembleGroup.EQUIPMENT,
            "armorNurtures" to AssembleGroup.EQUIPMENT,
            "bootsNurtures" to AssembleGroup.EQUIPMENT,
            "accessoryNurtures" to AssembleGroup.EQUIPMENT,
            "storageBagItems" to AssembleGroup.EQUIPMENT,
            "storageBagSpiritStones" to AssembleGroup.EQUIPMENT,
            "discipleSpiritStones" to AssembleGroup.EQUIPMENT,
            // assembleSocial 读取列
            "partnerIds" to AssembleGroup.SOCIAL,
            "partnerSectIds" to AssembleGroup.SOCIAL,
            "parentId1s" to AssembleGroup.SOCIAL,
            "parentId2s" to AssembleGroup.SOCIAL,
            "lastChildYears" to AssembleGroup.SOCIAL,
            "childBirthMonths" to AssembleGroup.SOCIAL,
            "griefEndYears" to AssembleGroup.SOCIAL,
            "masterIds" to AssembleGroup.SOCIAL,
            // assembleSkills 读取列
            "intelligences" to AssembleGroup.SKILLS,
            "charms" to AssembleGroup.SKILLS,
            "loyalties" to AssembleGroup.SKILLS,
            "comprehensions" to AssembleGroup.SKILLS,
            "artifactRefinings" to AssembleGroup.SKILLS,
            "pillRefinings" to AssembleGroup.SKILLS,
            "spiritPlantings" to AssembleGroup.SKILLS,
            "minings" to AssembleGroup.SKILLS,
            "teachings" to AssembleGroup.SKILLS,
            "moralities" to AssembleGroup.SKILLS,
            "aptitudes" to AssembleGroup.SKILLS,
            "salaryPaidCounts" to AssembleGroup.SKILLS,
            "salaryMissedCounts" to AssembleGroup.SKILLS,
            "alchemyLevels" to AssembleGroup.SKILLS,
            "alchemyPromotionCounts" to AssembleGroup.SKILLS,
            "forgeLevels" to AssembleGroup.SKILLS,
            "forgePromotionCounts" to AssembleGroup.SKILLS,
            // assembleUsage 读取列
            "usedFunctionalPillTypes" to AssembleGroup.USAGE,
            "usedExtendLifePillIds" to AssembleGroup.USAGE,
            "usedPermanentPillKeys" to AssembleGroup.USAGE,
            "usedExtendLifePillTypes" to AssembleGroup.USAGE,
            "recruitedMonths" to AssembleGroup.USAGE,
            "hasReviveEffects" to AssembleGroup.USAGE,
            "hasClearAllEffects" to AssembleGroup.USAGE,
            // lifeEvents（assemble .also 读取列）
            "lifeEvents" to AssembleGroup.LIFEEVENTS
)

/** 脏列 → 子对象组位图；返回 null 表示含未注册列需整体退化 */
internal fun computeDirtyGroups(dirtyColumnIndices: Set<Int>, columnGroups: IntArray): Int? {
    var dirtyGroups = 0
    for (ci in dirtyColumnIndices) {
        if (ci < 0 || ci >= columnGroups.size) return null
        val group = columnGroups[ci]
        if (group >= 0) dirtyGroups = dirtyGroups or (1 shl group)
    }
    return dirtyGroups
}
