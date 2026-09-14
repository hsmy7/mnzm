package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.TrialEnemyDef
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.util.DeterministicRng
import java.util.Locale
import com.xianxia.sect.core.engine.domain.battle.HeavenlyTrialService.BeastStats
import com.xianxia.sect.core.engine.domain.battle.HeavenlyTrialService.StatBonus
import com.xianxia.sect.core.engine.domain.battle.HeavenlyTrialService.TrialBaseStats
import com.xianxia.sect.core.engine.domain.battle.HeavenlyTrialService.TrialEquipmentSelection

/** 妖兽属性计算：±0.2 方差（加在类型 mod 上）+ 下界钳制 */
// ── 天劫试炼敌人构筑与装备/功法选择域（自 HeavenlyTrialService 拆出，行为零变更） ──
internal fun HeavenlyTrialService.computeBeastStats(
    realmStats: GameConfig.Beast.RealmStats,
    beastType: GameConfig.BeastTypeConfig,
    layerMult: Double,
    enemyRng: DeterministicRng
): BeastStats {
    fun variance(): Double = -0.2 + enemyRng.nextDouble() * 0.4
    val hpVariance = variance()
    val atkVariance = variance()
    val defVariance = variance()
    val speedVariance = variance()

    // 属性下界钳制：防止未来配置新增低 mod 妖兽类型时出现 0/负属性
    fun safeStat(value: Double): Int = value.toInt().coerceAtLeast(1)
    return BeastStats(
        hp = safeStat(realmStats.hp * layerMult * (beastType.hpMod + hpVariance)),
        mp = safeStat(realmStats.mp * layerMult * (beastType.hpMod + hpVariance)),
        physicalAttack = safeStat(realmStats.attack * layerMult * (beastType.atkMod + atkVariance)),
        magicAttack = safeStat(realmStats.attack * layerMult * (beastType.atkMod + atkVariance)),
        physicalDefense = safeStat(realmStats.defense * layerMult * (beastType.defMod + defVariance)),
        magicDefense = safeStat(realmStats.defense * layerMult * (beastType.defMod + defVariance)),
        speed = safeStat(realmStats.speed * layerMult * (beastType.speedMod + speedVariance))
    )
}

/** 妖兽技能构建：类型技能配置 → CombatSkill 列表 */

internal fun HeavenlyTrialService.buildBeastSkills(beastType: GameConfig.BeastTypeConfig): List<CombatSkill> {
    return beastType.skills.map { skillConfig ->
        CombatSkill(
            name = skillConfig.name,
            skillType = skillConfig.skillType,
            damageType = skillConfig.damageType,
            damageMultiplier = skillConfig.damageMultiplier,
            mpCost = skillConfig.mpCost,
            cooldown = skillConfig.cooldown,
            hits = skillConfig.hits,
            healPercent = skillConfig.healPercent,
            healFixed = skillConfig.healFixed,
            healType = skillConfig.healType,
            buffType = skillConfig.buffType,
            buffValue = skillConfig.buffValue,
            buffDuration = skillConfig.buffDuration,
            buffs = skillConfig.buffs,
            isAoe = skillConfig.isAoe,
            targetScope = skillConfig.targetScope,
            shieldPercent = skillConfig.shieldPercent,
            turnAdvancePercent = skillConfig.turnAdvancePercent,
            damageSharePercent = skillConfig.damageSharePercent,
            damageLinkPercent = skillConfig.damageLinkPercent,
            skillDescription = skillConfig.skillDescription
        )
    }
}

/** 试炼功法选取（buildDiscipleEnemy 提取）：固定 manualIds → 角色精选 → 随机 */
@Suppress("UnusedParameter") // levelIndex: 角色选取由 def 承载，保留签名位供难度分档扩展
internal fun HeavenlyTrialService.selectTrialManuals(
    def: TrialEnemyDef, levelIndex: Int
): List<ManualDatabase.ManualTemplate> {
    // 功法：优先用固定 manualIds → 按角色精选 → 随机
    if (def.manualIds.isNotEmpty()) {
        val resolved = def.manualIds.mapNotNull { ManualDatabase.allManuals[it] }
        if (resolved.isNotEmpty()) return resolved
    }
    val eligible = ManualDatabase.allManuals.values
        .filter { it.minRealm <= def.realm }
        .sortedByDescending { it.rarity }
    return if (def.role.isNotEmpty()) selectManualsForRole(eligible, def.role, def.realm)
    else selectManuals(eligible, def.realm)
}

/** 试炼装备选取（buildDiscipleEnemy 提取）：固定 equipmentIds，否则境界最高品阶 */

internal fun HeavenlyTrialService.selectTrialEquipment(def: TrialEnemyDef): TrialEquipmentSelection {
    if (def.equipmentIds.isNotEmpty()) {
        val eqRecipes = def.equipmentIds.mapNotNull { ForgeRecipeDatabase.getRecipeById(it) }
        return TrialEquipmentSelection(
            weapon = eqRecipes.find { it.type == EquipmentSlot.WEAPON },
            armor = eqRecipes.find { it.type == EquipmentSlot.ARMOR },
            boots = eqRecipes.find { it.type == EquipmentSlot.BOOTS },
            accessory = eqRecipes.find { it.type == EquipmentSlot.ACCESSORY }
        )
    }
    val eligibleEquip = ForgeRecipeDatabase.getAllRecipes()
        .filter { it.tier <= getMaxTierForRealm(def.realm) }
        .sortedByDescending { it.rarity }
    return TrialEquipmentSelection(
        weapon = eligibleEquip.find { it.type == EquipmentSlot.WEAPON },
        armor = eligibleEquip.find { it.type == EquipmentSlot.ARMOR },
        boots = eligibleEquip.find { it.type == EquipmentSlot.BOOTS },
        accessory = eligibleEquip.find { it.type == EquipmentSlot.ACCESSORY }
    )
}

/** 试炼敌人基础属性（buildDiscipleEnemy 提取）：7 次 rngVar 抽数顺序保持，装备+功法加成随后 */
internal fun HeavenlyTrialService.buildTrialBaseStats(
    def: TrialEnemyDef,
    layerMult: Double,
    rng: DeterministicRng,
    selected: List<ManualDatabase.ManualTemplate>,
    equipment: TrialEquipmentSelection
): TrialBaseStats {
    val realmConfig = GameConfig.Realm.get(def.realm)
    fun rngVar(): Double = 1.0 + (rng.nextInt(61) - 30) / 100.0

    val baseHp = (realmConfig.baseHp * rngVar() * layerMult).toInt()
    val baseMp = (realmConfig.baseMp * rngVar() * layerMult).toInt()
    val basePhysAtk = (realmConfig.basePhysicalAttack * rngVar() * layerMult).toInt()
    val baseMagAtk = (realmConfig.baseMagicAttack * rngVar() * layerMult).toInt()
    val basePhysDef = (realmConfig.basePhysicalDefense * rngVar() * layerMult).toInt()
    val baseMagDef = (realmConfig.baseMagicDefense * rngVar() * layerMult).toInt()
    val baseSpeed = (realmConfig.baseSpeed * rngVar() * layerMult).toInt()

    val equipBonus = sumEquipStatBonuses(equipment, rng)
    val manualBonus = sumManualStatBonuses(selected)

    return TrialBaseStats(
        hp = baseHp + equipBonus.hp + manualBonus.hp,
        mp = baseMp + equipBonus.mp + manualBonus.mp,
        physAtk = basePhysAtk + equipBonus.physAtk + manualBonus.physAtk,
        magAtk = baseMagAtk + equipBonus.magAtk + manualBonus.magAtk,
        physDef = basePhysDef + equipBonus.physDef + manualBonus.physDef,
        magDef = baseMagDef + equipBonus.magDef + manualBonus.magDef,
        speed = baseSpeed + equipBonus.speed + manualBonus.speed,
        critChance = manualBonus.critChance
    )
}

/** 装备属性加成汇总（buildTrialBaseStats 提取）：weapon → armor → boots → accessory 顺序保持 */

internal fun HeavenlyTrialService.sumEquipStatBonuses(
    equipment: TrialEquipmentSelection,
    rng: DeterministicRng
): StatBonus {
    var hp = 0; var physAtk = 0; var magAtk = 0
    var physDef = 0; var magDef = 0; var speed = 0
    val equipNames = listOfNotNull(
        equipment.weapon?.name, equipment.armor?.name,
        equipment.boots?.name, equipment.accessory?.name
    )
    // 应用装备属性加成，与 EnemyGenerator 一致：装备属性吃孕养倍率
    // （模板原值 × getNurtureMultiplier，孕养等级确定性 rng 0..maxNurture）
    for (name in equipNames) {
        EquipmentDatabase.getTemplateByName(name)?.let { t ->
            val maxNurture = EquipmentNurtureSystem.getMaxNurtureLevel(t.rarity)
            val nurtureMult = EquipmentNurtureSystem.getNurtureMultiplier(rng.nextInt(maxNurture + 1))
            physAtk += (t.physicalAttack * nurtureMult).toInt()
            magAtk += (t.magicAttack * nurtureMult).toInt()
            physDef += (t.physicalDefense * nurtureMult).toInt()
            magDef += (t.magicDefense * nurtureMult).toInt()
            speed += (t.speed * nurtureMult).toInt()
            hp += (t.hp * nurtureMult).toInt()
        }
    }
    return StatBonus(hp = hp, physAtk = physAtk, magAtk = magAtk, physDef = physDef, magDef = magDef, speed = speed)
}

/** 功法属性加成汇总（buildTrialBaseStats 提取）：
 * 默认熟练度 0 → NOVICE 1.5 倍，与玩家"刚学功法"一致 */

internal fun HeavenlyTrialService.sumManualStatBonuses(selected: List<ManualDatabase.ManualTemplate>): StatBonus {
    var hp = 0; var mp = 0
    var physAtk = 0; var magAtk = 0
    var physDef = 0; var magDef = 0
    /** 当前速度：0=暂停, 1=1x, 2=2x */
    var speed = 0; var critChance = 0.0
    val masteryBonus = com.xianxia.sect.core.engine.ManualProficiencySystem.MasteryLevel.fromLevel(0).bonus
    // 功法属性加成
    for (manual in selected) {
        val hpValue = manual.stats["hp"] ?: manual.stats["maxHp"] ?: 0
        val mpValue = manual.stats["mp"] ?: manual.stats["maxMp"] ?: 0
        hp += (hpValue * masteryBonus).toInt()
        mp += (mpValue * masteryBonus).toInt()
        physAtk += ((manual.stats["physicalAttack"] ?: 0) * masteryBonus).toInt()
        magAtk += ((manual.stats["magicAttack"] ?: 0) * masteryBonus).toInt()
        physDef += ((manual.stats["physicalDefense"] ?: 0) * masteryBonus).toInt()
        magDef += ((manual.stats["magicDefense"] ?: 0) * masteryBonus).toInt()
        speed += ((manual.stats["speed"] ?: 0) * masteryBonus).toInt()
        critChance += ((manual.stats["critRate"] ?: 0) * masteryBonus) / 100.0
    }
    return StatBonus(
        hp = hp, mp = mp, physAtk = physAtk, magAtk = magAtk,
        physDef = physDef, magDef = magDef, speed = speed, critChance = critChance
    )
}

/** 试炼敌人技能（buildDiscipleEnemy 提取）：熟练度 0（NOVICE ×1.5）倍率调整 */

internal fun HeavenlyTrialService.buildTrialSkills(selected: List<ManualDatabase.ManualTemplate>): List<CombatSkill> {
    // 技能倍率按熟练度 0（NOVICE ×1.5）调整——与上方功法属性加成同源，
    // 与 EnemyGenerator（按 mastery 0-3 调倍率）和玩家公式一致
    // （属性与技能倍率同用 NOVICE ×1.5，保证同一敌人口径一致）
    return selected.map { manual ->
        manual.copy(
            skillDamageMultiplier = com.xianxia.sect.core.engine.ManualProficiencySystem
                .calculateSkillDamageMultiplier(manual.skillDamageMultiplier, 0)
        ).toCombatSkill()
    }
}

internal fun HeavenlyTrialService.getMaxTierForRealm(realm: Int): Int = when (realm) {
    1, 2, 3, 4 -> 4
    5 -> 3
    6 -> 2
    else -> 1
}

/**
 * 试炼敌人确定性种子（C1 修复）：由关卡定义 + 敌人名派生——
 * 同一关卡的同一敌人属性恒定（预览 = 战斗），且不消费全局 ENEMY_GEN。
 * hashCode 碰撞仅导致属性略同，无正确性问题。
 */

internal fun HeavenlyTrialService.enemySeed(levelIndex: Int, def: TrialEnemyDef, index: Int): Long =
    def.name.hashCode().toLong() * 31L + levelIndex.toLong() * 7L + index

internal fun HeavenlyTrialService.mergeCardsByName(cards: List<RewardCardItem>): List<RewardCardItem> {
    return cards.groupBy { Triple(it.itemName, it.itemType, it.rarity) }
        .map { (_, list) ->
            list.first().copy(quantity = list.sumOf { it.quantity })
        }
}

// endregion

// region Reward capacity pre-check


internal fun HeavenlyTrialService.selectManuals(
    eligible: List<ManualDatabase.ManualTemplate>,
    realm: Int
): List<ManualDatabase.ManualTemplate> {
    val maxCount = when (realm) {
        1, 2 -> 10
        3, 4 -> 7
        5, 6 -> 4
        else -> 2
    }
    val types = listOf("attack", "defense", "support", "mind")
    val result = mutableListOf<ManualDatabase.ManualTemplate>()
    for (t in types) {
        eligible.filter { it.type.name.lowercase(Locale.ROOT) == t }
            .maxByOrNull { it.rarity }
            ?.let { result.add(it) }
    }
    val remaining = eligible.filter { it !in result }
        .sortedByDescending { it.rarity }
    for (m in remaining) {
        if (result.size >= maxCount) break
        result.add(m)
    }
    return result
}

internal fun HeavenlyTrialService.selectManualsForRole(
    eligible: List<ManualDatabase.ManualTemplate>,
    role: String,
    realm: Int
): List<ManualDatabase.ManualTemplate> {
    val maxCount = when (realm) {
        1, 2 -> 10
        3, 4 -> 7
        5, 6 -> 4
        else -> 2
    }
    val result = mutableListOf<ManualDatabase.ManualTemplate>()

    // 按角色优先级选取各类型功法
    val typePriority = when (role.lowercase(Locale.ROOT)) {
        "tank" -> listOf("defense" to 2, "attack" to 1, "mind" to 1, "support" to 0)
        "dps" -> listOf("attack" to 3, "mind" to 1, "defense" to 0, "support" to 0)
        "support" -> listOf("support" to 2, "mind" to 1, "defense" to 1, "attack" to 0)
        else -> listOf("attack" to 1, "defense" to 1, "support" to 1, "mind" to 1)
    }

    for ((type, desired) in typePriority) {
        val typeManuals = eligible
            .filter { it.type.name.lowercase(Locale.ROOT) == type.lowercase(Locale.ROOT) }
            .sortedByDescending { it.rarity }
            .take(desired)
        result.addAll(typeManuals)
    }

    // 补满到 maxCount
    val usedIds = result.map { it.id }.toSet()
    val remaining = eligible.filter { it.id !in usedIds }
        .sortedByDescending { it.rarity }
    for (m in remaining) {
        if (result.size >= maxCount) break
        result.add(m)
    }

    return result
}

internal fun ManualDatabase.ManualTemplate.toCombatSkill(): CombatSkill {
    val skillTypeEnum = when (skillType.lowercase(Locale.ROOT)) {
        "attack" -> SkillType.ATTACK
        "support" -> SkillType.SUPPORT
        else -> SkillType.ATTACK
    }
    val damageTypeEnum = when (skillDamageType.lowercase(Locale.ROOT)) {
        "physical" -> DamageType.PHYSICAL
        "magic" -> DamageType.MAGIC
        else -> DamageType.PHYSICAL
    }
    val buffTypeEnum = skillBuffType?.let { buffName ->
        BuffType.entries.find { it.name.equals(buffName, ignoreCase = true) }
    }
    val buffsList = skillBuffs.map { buff ->
        val bt = BuffType.entries.find { it.name.equals(buff.type, ignoreCase = true) }
        if (bt != null) Triple(bt, buff.value, buff.duration) else null
    }.filterNotNull()

    val healTypeEnum = when (skillHealType.lowercase(Locale.ROOT)) {
        "mp" -> HealType.MP
        else -> HealType.HP
    }

    return CombatSkill(
        name = skillName ?: name,
        skillType = skillTypeEnum,
        damageType = damageTypeEnum,
        damageMultiplier = skillDamageMultiplier,
        mpCost = skillMpCost,
        cooldown = skillCooldown,
        hits = skillHits,
        healPercent = skillHealPercent,
        healType = healTypeEnum,
        buffType = buffTypeEnum,
        buffValue = skillBuffValue,
        buffDuration = skillBuffDuration,
        buffs = buffsList,
        isAoe = skillIsAoe,
        targetScope = skillTargetScope,
        skillDescription = skillDescription ?: "",
        manualName = name
    )
}
