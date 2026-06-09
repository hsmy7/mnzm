package com.xianxia.sect.ui.game.components

import com.xianxia.sect.ui.components.getRarityName
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.util.GameUtils
import java.util.Locale

// ===== 共享工具函数 =====

internal fun getBuffTypeName(buffType: com.xianxia.sect.core.BuffType): String = buffType.displayName

internal fun getBuffTypeName(buffType: String): String = when (buffType) {
    "physical_attack" -> com.xianxia.sect.core.BuffType.PHYSICAL_ATTACK_BOOST.displayName
    "magic_attack" -> com.xianxia.sect.core.BuffType.MAGIC_ATTACK_BOOST.displayName
    "physical_defense" -> com.xianxia.sect.core.BuffType.PHYSICAL_DEFENSE_BOOST.displayName
    "magic_defense" -> com.xianxia.sect.core.BuffType.MAGIC_DEFENSE_BOOST.displayName
    "hp" -> com.xianxia.sect.core.BuffType.HP_BOOST.displayName
    "mp" -> com.xianxia.sect.core.BuffType.MP_BOOST.displayName
    "speed" -> com.xianxia.sect.core.BuffType.SPEED_BOOST.displayName
    "crit_rate" -> com.xianxia.sect.core.BuffType.CRIT_RATE_BOOST.displayName
    "physical_attack_reduce" -> com.xianxia.sect.core.BuffType.PHYSICAL_ATTACK_REDUCE.displayName
    "magic_attack_reduce" -> com.xianxia.sect.core.BuffType.MAGIC_ATTACK_REDUCE.displayName
    "physical_defense_reduce" -> com.xianxia.sect.core.BuffType.PHYSICAL_DEFENSE_REDUCE.displayName
    "magic_defense_reduce" -> com.xianxia.sect.core.BuffType.MAGIC_DEFENSE_REDUCE.displayName
    "speed_reduce" -> com.xianxia.sect.core.BuffType.SPEED_REDUCE.displayName
    "crit_rate_reduce" -> com.xianxia.sect.core.BuffType.CRIT_RATE_REDUCE.displayName
    "poison" -> com.xianxia.sect.core.BuffType.POISON.displayName
    "burn" -> com.xianxia.sect.core.BuffType.BURN.displayName
    "stun" -> com.xianxia.sect.core.BuffType.STUN.displayName
    "freeze" -> com.xianxia.sect.core.BuffType.FREEZE.displayName
    "silence" -> com.xianxia.sect.core.BuffType.SILENCE.displayName
    "taunt" -> com.xianxia.sect.core.BuffType.TAUNT.displayName
    else -> buffType
}

internal fun parseManualStackBuffs(json: String): List<Triple<com.xianxia.sect.core.BuffType, Double, Int>> {
    if (json.isBlank()) return emptyList()
    return json.split("|").mapNotNull { buffStr ->
        val parts = buffStr.split(",")
        if (parts.size == 3) {
            val type = when (parts[0]) {
                "physical_attack" -> com.xianxia.sect.core.BuffType.PHYSICAL_ATTACK_BOOST
                "magic_attack" -> com.xianxia.sect.core.BuffType.MAGIC_ATTACK_BOOST
                "physical_defense" -> com.xianxia.sect.core.BuffType.PHYSICAL_DEFENSE_BOOST
                "magic_defense" -> com.xianxia.sect.core.BuffType.MAGIC_DEFENSE_BOOST
                "hp" -> com.xianxia.sect.core.BuffType.HP_BOOST
                "mp" -> com.xianxia.sect.core.BuffType.MP_BOOST
                "speed" -> com.xianxia.sect.core.BuffType.SPEED_BOOST
                "crit_rate" -> com.xianxia.sect.core.BuffType.CRIT_RATE_BOOST
                "physical_attack_reduce" -> com.xianxia.sect.core.BuffType.PHYSICAL_ATTACK_REDUCE
                "magic_attack_reduce" -> com.xianxia.sect.core.BuffType.MAGIC_ATTACK_REDUCE
                "physical_defense_reduce" -> com.xianxia.sect.core.BuffType.PHYSICAL_DEFENSE_REDUCE
                "magic_defense_reduce" -> com.xianxia.sect.core.BuffType.MAGIC_DEFENSE_REDUCE
                "speed_reduce" -> com.xianxia.sect.core.BuffType.SPEED_REDUCE
                "crit_rate_reduce" -> com.xianxia.sect.core.BuffType.CRIT_RATE_REDUCE
                "poison" -> com.xianxia.sect.core.BuffType.POISON
                "burn" -> com.xianxia.sect.core.BuffType.BURN
                "stun" -> com.xianxia.sect.core.BuffType.STUN
                "freeze" -> com.xianxia.sect.core.BuffType.FREEZE
                "silence" -> com.xianxia.sect.core.BuffType.SILENCE
                "taunt" -> com.xianxia.sect.core.BuffType.TAUNT
                else -> return@mapNotNull null
            }
            val value = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val duration = parts[2].toIntOrNull() ?: return@mapNotNull null
            Triple(type, value, duration)
        } else null
    }
}

internal fun getStatDisplayName(key: String): String = when (key) {
    "cultivationSpeedPercent" -> "修炼速度"
    "skillExpSpeedPercent" -> "功法熟练度速度"
    "nurtureSpeedPercent" -> "孕养速度"
    "physicalAttack" -> "物理攻击"
    "magicAttack" -> "法术攻击"
    "physicalDefense" -> "物理防御"
    "magicDefense" -> "法术防御"
    "hp" -> "生命"
    "mp" -> "灵力"
    "speed" -> "速度"
    "critRate" -> "暴击率"
    "critEffect" -> "暴击效果"
    "intelligence" -> "悟性"
    "charm" -> "魅力"
    "loyalty" -> "忠诚"
    "comprehension" -> "领悟"
    "artifactRefining" -> "炼器"
    "pillRefining" -> "炼丹"
    "spiritPlanting" -> "灵植"
    "teaching" -> "教导"
    "morality" -> "道德"
    else -> key
}

internal fun getHerbCategoryName(category: String): String = when (category) {
    "grass" -> "灵草"
    "flower" -> "灵花"
    "fruit" -> "灵果"
    else -> if (category.isNotEmpty()) category else "灵药"
}

internal fun MutableList<String>.addForgeMaterialsInfo(equipmentName: String) {
    val forgeRecipe = ForgeRecipeDatabase.getAllRecipes().find { it.name == equipmentName }
    if (forgeRecipe != null && forgeRecipe.materials.isNotEmpty()) {
        add("")
        add("锻造所需:")
        forgeRecipe.materials.forEach { (materialId, count) ->
            val materialName = com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialById(materialId)?.name ?: materialId
            add("  · $materialName $count")
        }
    }
}

internal fun MutableList<String>.addManualSkillInfo(template: ManualDatabase.ManualTemplate) {
    val sDesc = template.skillDescription
    if (!sDesc.isNullOrEmpty()) {
        add("  $sDesc")
    }
    if (template.skillType == "support") {
        add("  类型: 辅助")
    }
    if (template.skillTargetScope == "team") {
        add("  作用范围: 全队")
    }
    if (template.skillHealPercent > 0) {
        val healTypeName = if (template.skillHealType == "mp") "灵力" else "生命"
        add("  治疗: ${(template.skillHealPercent * 100).toInt()}% $healTypeName")
    }
    if (template.skillDamageMultiplier > 0 && template.skillType != "support") {
        add("  伤害类型: ${if (template.skillDamageType == "magic") "法术" else "物理"}")
        add("  伤害倍率: ${(template.skillDamageMultiplier * 100).toInt()}%")
    }
    add("  连击次数: ${template.skillHits}")
    if (template.skillCooldown > 0) {
        add("  冷却回合: ${template.skillCooldown}")
    }
    if (template.skillMpCost > 0) {
        add("  灵力消耗: ${template.skillMpCost}")
    }
    template.skillBuffs.forEach { buff ->
        val buffName = getBuffTypeName(buff.type)
        add("  $buffName +${(buff.value * 100).toInt()}% (${buff.duration}回合)")
    }
    if (template.skillBuffs.isEmpty() && template.skillBuffType != null && template.skillBuffValue > 0) {
        val buffName = getBuffTypeName(template.skillBuffType)
        val durationText = if (template.skillBuffDuration > 0) " (${template.skillBuffDuration}回合)" else ""
        add("  $buffName +${(template.skillBuffValue * 100).toInt()}%$durationText")
    }
}

internal fun MutableList<String>.addPillRecipeInfo(pillId: String, pillName: String) {
    val pillRecipe = PillRecipeDatabase.getRecipeById(pillId)
        ?: PillRecipeDatabase.getRecipeByName(pillName)
    if (pillRecipe != null && pillRecipe.materials.isNotEmpty()) {
        add("")
        add("炼制所需:")
        pillRecipe.materials.forEach { (herbId, count) ->
            val herbName = HerbDatabase.getHerbById(herbId)?.name ?: herbId
            add("  · $herbName $count")
        }
    }
}

// ===== 装备效果 =====

@Suppress("DEPRECATION")
internal fun getEquipmentStackEffects(item: EquipmentStack): List<String> = buildList {
    add("部位: ${item.slot.displayName}")
    add("稀有度: ${getRarityName(item.rarity)}")
    add("数量: ${item.quantity}")
    if (item.minRealm < 9) {
        add("需求境界: ${GameConfig.Realm.getName(item.minRealm)}")
    }
    add("")
    add("属性:")
    if (item.physicalAttack > 0) add("  物理攻击 +${item.physicalAttack}")
    if (item.magicAttack > 0) add("  法术攻击 +${item.magicAttack}")
    if (item.physicalDefense > 0) add("  物理防御 +${item.physicalDefense}")
    if (item.magicDefense > 0) add("  法术防御 +${item.magicDefense}")
    if (item.speed > 0) add("  速度 +${item.speed}")
    if (item.hp > 0) add("  生命 +${item.hp}")
    if (item.mp > 0) add("  灵力 +${item.mp}")
    if (item.critChance > 0) add("  暴击率 +${GameUtils.formatPercent(item.critChance)}")

    addForgeMaterialsInfo(item.name)
}

@Suppress("DEPRECATION")
internal fun getEquipmentEffects(item: EquipmentInstance): List<String> = buildList {
    add("部位: ${item.slot.displayName}")
    add("稀有度: ${getRarityName(item.rarity)}")
    if (item.minRealm < 9) {
        add("需求境界: ${GameConfig.Realm.getName(item.minRealm)}")
    }
    if (item.nurtureLevel > 0) {
        add("孕养等级: Lv.${item.nurtureLevel}")
        val nurtureBonus = (item.totalMultiplier / GameConfig.Rarity.get(item.rarity).multiplier - 1.0) * 100
        if (nurtureBonus > 0) {
            add("  孕养加成: +${String.format(Locale.getDefault(), "%.1f", nurtureBonus)}%")
        }
    }
    add("")
    add("属性:")
    val finalStats = item.getFinalStats()
    val baseStats = item.stats
    if (finalStats.physicalAttack > 0) {
        val bonus = finalStats.physicalAttack - baseStats.physicalAttack
        val bonusText = if (bonus > 0) " (↑$bonus)" else ""
        add("  物理攻击 +${finalStats.physicalAttack}$bonusText")
    }
    if (finalStats.magicAttack > 0) {
        val bonus = finalStats.magicAttack - baseStats.magicAttack
        val bonusText = if (bonus > 0) " (↑$bonus)" else ""
        add("  法术攻击 +${finalStats.magicAttack}$bonusText")
    }
    if (finalStats.physicalDefense > 0) {
        val bonus = finalStats.physicalDefense - baseStats.physicalDefense
        val bonusText = if (bonus > 0) " (↑$bonus)" else ""
        add("  物理防御 +${finalStats.physicalDefense}$bonusText")
    }
    if (finalStats.magicDefense > 0) {
        val bonus = finalStats.magicDefense - baseStats.magicDefense
        val bonusText = if (bonus > 0) " (↑$bonus)" else ""
        add("  法术防御 +${finalStats.magicDefense}$bonusText")
    }
    if (finalStats.speed > 0) {
        val bonus = finalStats.speed - baseStats.speed
        val bonusText = if (bonus > 0) " (↑$bonus)" else ""
        add("  速度 +${finalStats.speed}$bonusText")
    }
    if (finalStats.hp > 0) {
        val bonus = finalStats.hp - baseStats.hp
        val bonusText = if (bonus > 0) " (↑$bonus)" else ""
        add("  生命 +${finalStats.hp}$bonusText")
    }
    if (finalStats.mp > 0) {
        val bonus = finalStats.mp - baseStats.mp
        val bonusText = if (bonus > 0) " (↑$bonus)" else ""
        add("  灵力 +${finalStats.mp}$bonusText")
    }
    if (item.critChance > 0) add("  暴击率 +${GameUtils.formatPercent(item.critChance)}")

    addForgeMaterialsInfo(item.name)
}

// ===== 功法效果 =====

@Suppress("DEPRECATION")
internal fun getManualStackEffects(item: ManualStack): List<String> = buildList {
    add("类型: ${item.type.displayName}")
    add("数量: ${item.quantity}")
    if (item.minRealm < 9) {
        add("需求境界: ${GameConfig.Realm.getName(item.minRealm)}")
    }
    add("")
    val stats = item.stats
    if (stats.isNotEmpty()) {
        add("属性加成:")
        stats.forEach { (key, value) ->
            val statName = getStatDisplayName(key)
            if (key.contains("Percent")) {
                add("  $statName +$value%")
            } else {
                add("  $statName +$value")
            }
        }
    }
    val effectiveSkillName = item.skillName
    if (effectiveSkillName != null) {
        add("")
        add("技能: $effectiveSkillName")
        item.skillDescription?.let { sDesc ->
            if (sDesc.isNotEmpty()) {
                add("  $sDesc")
            }
        }
        if (item.skillType == "support") {
            add("  类型: 辅助")
        }
        if (item.skillTargetScope == "team") {
            add("  作用范围: 全队")
        }
        if (item.skillHealPercent > 0) {
            val healTypeName = if (item.skillHealType == "mp") "灵力" else "生命"
            add("  治疗: ${(item.skillHealPercent * 100).toInt()}% $healTypeName")
        }
        if (item.skillDamageMultiplier > 0 && item.skillType != "support") {
            add("  伤害类型: ${if (item.skillDamageType == "magic") "法术" else "物理"}")
            add("  伤害倍率: ${(item.skillDamageMultiplier * 100).toInt()}%")
        }
        add("  连击次数: ${item.skillHits}")
        if (item.skillCooldown > 0) {
            add("  冷却回合: ${item.skillCooldown}")
        }
        if (item.skillMpCost > 0) {
            add("  灵力消耗: ${item.skillMpCost}")
        }
        val buffs = parseManualStackBuffs(item.skillBuffsJson)
        buffs.forEach { (buffType, value, duration) ->
            val buffName = getBuffTypeName(buffType)
            add("  $buffName +${(value * 100).toInt()}% (${duration}回合)")
        }
        if (buffs.isEmpty() && item.skillBuffType != null && item.skillBuffValue > 0) {
            val buffName = getBuffTypeName(item.skillBuffType)
            val durationText = if (item.skillBuffDuration > 0) " (${item.skillBuffDuration}回合)" else ""
            add("  $buffName +${(item.skillBuffValue * 100).toInt()}%$durationText")
        }
    } else if (ManualDatabase.isInitialized) {
        val template = ManualDatabase.getByName(item.name)
        if (template != null) {
            template.skillName?.let { sName ->
                add("")
                add("技能: $sName")
                addManualSkillInfo(template)
            }
        }
    }
}

@Suppress("DEPRECATION")
internal fun getManualEffects(item: ManualInstance): List<String> = buildList {
    add("类型: ${item.type.displayName}")
    if (item.minRealm < 9) {
        add("需求境界: ${GameConfig.Realm.getName(item.minRealm)}")
    }
    add("")
    val stats = item.stats
    if (stats.isNotEmpty()) {
        add("属性加成:")
        stats.forEach { (key, value) ->
            val statName = getStatDisplayName(key)
            if (key.contains("Percent")) {
                add("  $statName +$value%")
            } else {
                add("  $statName +$value")
            }
        }
    }
    item.skill?.let { skill ->
        add("")
        add("技能: ${skill.name}")
        if (skill.description.isNotEmpty()) {
            add("  ${skill.description}")
        }
        if (skill.skillType == com.xianxia.sect.core.SkillType.SUPPORT) {
            add("  类型: 辅助")
        }
        if (skill.targetScope == "team") {
            add("  作用范围: 全队")
        }
        if (skill.healPercent > 0) {
            val healTypeName = when (skill.healType) {
                com.xianxia.sect.core.HealType.HP -> "生命"
                com.xianxia.sect.core.HealType.MP -> "灵力"
            }
            add("  治疗: ${(skill.healPercent * 100).toInt()}% $healTypeName")
        }
        if (skill.damageMultiplier > 0 && skill.skillType == com.xianxia.sect.core.SkillType.ATTACK) {
            add("  伤害类型: ${if (skill.damageType == com.xianxia.sect.core.DamageType.PHYSICAL) "物理" else "法术"}")
            add("  伤害倍率: ${(skill.damageMultiplier * 100).toInt()}%")
        }
        add("  连击次数: ${skill.hits}")
        if (skill.cooldown > 0) {
            add("  冷却回合: ${skill.cooldown}")
        }
        if (skill.mpCost > 0) {
            add("  灵力消耗: ${skill.mpCost}")
        }
        skill.buffs.forEach { (buffType, value, duration) ->
            val buffName = getBuffTypeName(buffType)
            add("  $buffName +${(value * 100).toInt()}% (${duration}回合)")
        }
        if (skill.buffs.isEmpty() && skill.buffType != null && skill.buffValue > 0) {
            val buffName = getBuffTypeName(skill.buffType)
            val durationText = if (skill.buffDuration > 0) " (${skill.buffDuration}回合)" else ""
            add("  $buffName +${(skill.buffValue * 100).toInt()}%$durationText")
        }
    }
}

// ===== 丹药效果 =====

internal fun getPillEffects(item: Pill): List<String> = buildList {
    add("类型: ${item.category.displayName}")
    add("品级: ${item.grade.displayName}")
    add("数量: ${item.quantity}")
    if (item.minRealm < 9) {
        add("需求境界: ${GameConfig.Realm.getName(item.minRealm)}")
    }
    add("")
    add("效果:")
    val isInstant = item.category == PillCategory.FUNCTIONAL ||
        (item.category == PillCategory.CULTIVATION && item.pillType == "breakthrough")
    when (item.category) {
        PillCategory.FUNCTIONAL -> {
            if (item.breakthroughChance > 0) {
                add("  突破概率 +${GameUtils.formatPercent(item.breakthroughChance)}")
            }
            if (item.targetRealm > 0) {
                add("  目标境界: ${GameConfig.Realm.getName(item.targetRealm)}")
            }
            if (item.isAscension) {
                add("  可用于渡劫")
            }
            if (item.extendLife > 0) add("  延寿 +${item.extendLife}年")
            if (item.intelligenceAdd > 0) add("  悟性 +${item.intelligenceAdd}")
            if (item.charmAdd > 0) add("  魅力 +${item.charmAdd}")
            if (item.loyaltyAdd > 0) add("  忠诚 +${item.loyaltyAdd}")
            if (item.comprehensionAdd > 0) add("  领悟 +${item.comprehensionAdd}")
            if (item.artifactRefiningAdd > 0) add("  炼器 +${item.artifactRefiningAdd}")
            if (item.pillRefiningAdd > 0) add("  炼丹 +${item.pillRefiningAdd}")
            if (item.spiritPlantingAdd > 0) add("  灵植 +${item.spiritPlantingAdd}")
            if (item.teachingAdd > 0) add("  教导 +${item.teachingAdd}")
            if (item.moralityAdd > 0) add("  道德 +${item.moralityAdd}")
            if (item.healMaxHpPercent > 0) add("  恢复生命 ${GameUtils.formatPercent(item.healMaxHpPercent)} 最大生命")
            if (item.mpRecoverMaxMpPercent > 0) add("  恢复灵力 ${GameUtils.formatPercent(item.mpRecoverMaxMpPercent)} 最大灵力")
            if (item.revive) add("  可复活弟子")
            if (item.clearAll) add("  清除所有负面状态")
            if (item.hpAdd > 0) add("  生命 +${item.hpAdd}")
            if (item.mpAdd > 0) add("  灵力 +${item.mpAdd}")
            if (item.physicalAttackAdd > 0) add("  物理攻击 +${item.physicalAttackAdd}")
            if (item.magicAttackAdd > 0) add("  法术攻击 +${item.magicAttackAdd}")
            if (item.physicalDefenseAdd > 0) add("  物理防御 +${item.physicalDefenseAdd}")
            if (item.magicDefenseAdd > 0) add("  法术防御 +${item.magicDefenseAdd}")
            if (item.speedAdd > 0) add("  速度 +${item.speedAdd}")
        }
        PillCategory.CULTIVATION -> {
            if (item.cultivationSpeedPercent > 0) add("  修炼速度 +${GameUtils.formatPercent(item.cultivationSpeedPercent)}")
            if (item.skillExpSpeedPercent > 0) add("  功法熟练度速度 +${GameUtils.formatPercent(item.skillExpSpeedPercent)}")
            if (item.nurtureSpeedPercent > 0) add("  孕养速度 +${GameUtils.formatPercent(item.nurtureSpeedPercent)}")
            if (item.cultivationAdd > 0) add("  修为 +${item.cultivationAdd}")
            if (item.skillExpAdd > 0) add("  功法熟练度 +${item.skillExpAdd}")
            if (item.nurtureAdd > 0) add("  孕养值 +${item.nurtureAdd}")
            if (item.breakthroughChance > 0) {
                add("  突破概率 +${GameUtils.formatPercent(item.breakthroughChance)}")
            }
            if (item.targetRealm > 0) {
                add("  目标境界: ${GameConfig.Realm.getName(item.targetRealm)}")
            }
            if (item.isAscension) {
                add("  可用于渡劫")
            }
        }
        PillCategory.BATTLE -> {
            if (item.physicalAttackAdd > 0) add("  物理攻击 +${item.physicalAttackAdd}")
            if (item.magicAttackAdd > 0) add("  法术攻击 +${item.magicAttackAdd}")
            if (item.physicalDefenseAdd > 0) add("  物理防御 +${item.physicalDefenseAdd}")
            if (item.magicDefenseAdd > 0) add("  法术防御 +${item.magicDefenseAdd}")
            if (item.hpAdd > 0) add("  生命 +${item.hpAdd}")
            if (item.mpAdd > 0) add("  灵力 +${item.mpAdd}")
            if (item.speedAdd > 0) add("  速度 +${item.speedAdd}")
            if (item.critRateAdd > 0) add("  暴击率 +${GameUtils.formatPercent(item.critRateAdd)}")
            if (item.critEffectAdd > 0) add("  暴击效果 +${GameUtils.formatPercent(item.critEffectAdd)}")
        }
    }
    if (!isInstant && item.duration > 0) {
        add("  持续 ${item.duration * 3} 旬")
    }
    if (isInstant) {
        add("  (一次性效果)")
    }

    val pillRecipe = PillRecipeDatabase.getRecipeById(item.id)
        ?: PillRecipeDatabase.getRecipeByName(item.name)
    if (pillRecipe != null && pillRecipe.materials.isNotEmpty()) {
        add("")
        add("炼制所需:")
        pillRecipe.materials.forEach { (herbId, count) ->
            val herbName = HerbDatabase.getHerbById(herbId)?.name ?: herbId
            add("  · $herbName $count")
        }
    }
}
