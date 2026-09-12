package com.xianxia.sect.ui.game.components

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.registry.ManualDatabase


/** 功法模板技能描述/类型/目标/范围段 */
internal fun MutableList<String>.addManualSkillIntroInfo(template: ManualDatabase.ManualTemplate) {
    val sDesc = template.skillDescription
    if (!sDesc.isNullOrEmpty()) {
        add("  $sDesc")
    }
    if (template.skillType == "support") {
        add("  类型: 辅助")
    }
    if (template.skillTargetScope.isNotEmpty()) {
        add("  作用目标: ${getTargetScopeName(template.skillTargetScope)}")
    }
    if (template.skillIsAoe) {
        add("  范围: 全体")
    }
}

/** 功法模板技能伤害/治疗段 */
internal fun MutableList<String>.addManualSkillCombatInfo(template: ManualDatabase.ManualTemplate) {
    if (template.skillDamageMultiplier > 0 && template.skillType != "support") {
        add("  伤害类型: ${if (template.skillDamageType == "magic") "法术" else "物理"}")
        add("  伤害倍率: ${(template.skillDamageMultiplier * 100).toInt()}%")
    }
    if (template.skillHealPercent > 0) {
        val healTypeName = if (template.skillHealType == "mp") "灵力" else "生命"
        add("  治疗: ${(template.skillHealPercent * 100).toInt()}% $healTypeName")
    }
    if (template.skillHealFixed > 0) {
        val healTypeName = if (template.skillHealType == "mp") "灵力" else "生命"
        add("  固定治疗: +${template.skillHealFixed} $healTypeName")
    }
}

/** 功法模板技能护盾/行动提前/分摊/链接/连击/冷却/消耗段 */
internal fun MutableList<String>.addManualSkillSupportInfo(template: ManualDatabase.ManualTemplate) {
    if (template.skillShieldPercent > 0) {
        add("  护盾: ${(template.skillShieldPercent * 100).toInt()}% 最大生命")
    }
    if (template.skillTurnAdvancePercent > 0) {
        add("  行动提前: ${(template.skillTurnAdvancePercent * 100).toInt()}%")
    }
    if (template.skillDamageSharePercent > 0) {
        add("  伤害分摊: ${(template.skillDamageSharePercent * 100).toInt()}%")
    }
    if (template.skillDamageLinkPercent > 0) {
        add("  伤害链接: ${(template.skillDamageLinkPercent * 100).toInt()}%")
    }
    add("  连击次数: ${template.skillHits}")
    if (template.skillCooldown > 0) {
        add("  冷却回合: ${template.skillCooldown}")
    }
    if (template.skillMpCost > 0) {
        add("  灵力消耗: ${template.skillMpCost}")
    }
}

/** 功法模板技能增益段：buff 列表 + 单 buff 兜底 */
internal fun MutableList<String>.addManualSkillBuffsInfo(template: ManualDatabase.ManualTemplate) {
    template.skillBuffs.forEach { buff ->
        add("  ${formatBuffLine(buff.type, buff.value, buff.duration)}")
    }
    if (template.skillBuffs.isEmpty() && template.skillBuffType != null && template.skillBuffValue > 0) {
        val buffType = checkNotNull(template.skillBuffType) { "skillBuffType is null" }
        add("  ${formatBuffLine(buffType, template.skillBuffValue, template.skillBuffDuration)}")
    }
}

internal fun MutableList<String>.addManualSkillInfo(template: ManualDatabase.ManualTemplate) {
    addManualSkillIntroInfo(template)
    addManualSkillCombatInfo(template)
    addManualSkillSupportInfo(template)
    addManualSkillBuffsInfo(template)
}

// ===== 功法效果 =====

@Suppress("DEPRECATION")
internal fun getManualStackEffects(item: ManualStack): List<String> = buildList {
    addManualStackBaseInfo(item = item)
    val effectiveSkillName = item.skillName
    if (effectiveSkillName != null) {
        addManualStackSkillInfo(
            item = item,
            skillName = effectiveSkillName
        )
    } else if (ManualDatabase.isInitialized) {
        addManualStackTemplateInfo(item = item)
    }
}

/** 功法堆叠基础信息 */
@Suppress("DEPRECATION")
internal fun MutableList<String>.addManualStackBaseInfo(item: ManualStack) {
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
}

/** 功法堆叠技能信息；internal 供 ReplaceSelectionData 复用 */
@Suppress("CyclomaticComplexMethod", "DEPRECATION")
internal fun MutableList<String>.addManualStackSkillInfo(item: ManualStack, skillName: String) {
    add("")
    add("技能: $skillName")
    item.skillDescription?.let { sDesc ->
        if (sDesc.isNotEmpty()) {
            add("  $sDesc")
        }
    }
    if (item.skillType == "support") {
        add("  类型: 辅助")
    }
    if (item.skillTargetScope.isNotEmpty()) {
        add("  作用目标: ${getTargetScopeName(item.skillTargetScope)}")
    }
    if (item.skillIsAoe) {
        add("  范围: 全体")
    }
    if (item.skillDamageMultiplier > 0 && item.skillType != "support") {
        add("  伤害类型: ${if (item.skillDamageType == "magic") "法术" else "物理"}")
        add("  伤害倍率: ${(item.skillDamageMultiplier * 100).toInt()}%")
    }
    if (item.skillHealPercent > 0) {
        val healTypeName = if (item.skillHealType == "mp") "灵力" else "生命"
        add("  治疗: ${(item.skillHealPercent * 100).toInt()}% $healTypeName")
    }
    if (item.skillHealFixed > 0) {
        val healTypeName = if (item.skillHealType == "mp") "灵力" else "生命"
        add("  固定治疗: +${item.skillHealFixed} $healTypeName")
    }
    if (item.skillShieldPercent > 0) {
        add("  护盾: ${(item.skillShieldPercent * 100).toInt()}% 最大生命")
    }
    if (item.skillTurnAdvancePercent > 0) {
        add("  行动提前: ${(item.skillTurnAdvancePercent * 100).toInt()}%")
    }
    if (item.skillDamageSharePercent > 0) {
        add("  伤害分摊: ${(item.skillDamageSharePercent * 100).toInt()}%")
    }
    if (item.skillDamageLinkPercent > 0) {
        add("  伤害链接: ${(item.skillDamageLinkPercent * 100).toInt()}%")
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
        add("  ${formatBuffLine(buffType, value, duration)}")
    }
    if (buffs.isEmpty() && item.skillBuffType != null && item.skillBuffValue > 0) {
        val itemBuffType = item.skillBuffType
        if (itemBuffType != null) {
            add("  ${formatBuffLine(itemBuffType, item.skillBuffValue, item.skillBuffDuration)}")
        }
    }
}

/** 功法堆叠模板兜底信息 */
@Suppress("DEPRECATION")
internal fun MutableList<String>.addManualStackTemplateInfo(item: ManualStack) {
    val template = ManualDatabase.getByName(item.name)
    if (template != null) {
        template.skillName?.let { sName ->
            add("")
            add("技能: $sName")
            addManualSkillInfo(template)
        }
    }
}

@Suppress("DEPRECATION")
internal fun getManualEffects(item: ManualInstance): List<String> = buildList {
    addManualBaseInfo(item = item)
    item.skill?.let { skill ->
        addLearnedManualSkillIntro(skill = skill)
        addLearnedManualSkillStats(skill = skill)
        skill.buffs.forEach { (buffType, value, duration) ->
            add("  ${formatBuffLine(buffType, value, duration)}")
        }
        if (skill.buffs.isEmpty() && skill.buffType != null && skill.buffValue > 0) {
            val skillBuffType = skill.buffType
            if (skillBuffType != null) {
                add("  ${formatBuffLine(skillBuffType, skill.buffValue, skill.buffDuration)}")
            }
        }
    }
}

/** 已学功法基础信息 */
@Suppress("DEPRECATION")
internal fun MutableList<String>.addManualBaseInfo(item: ManualInstance) {
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
}

/** 已学功法技能基础信息：名称/描述/类型/目标/范围 */
@Suppress("DEPRECATION")
internal fun MutableList<String>.addLearnedManualSkillIntro(skill: com.xianxia.sect.core.model.ManualSkill) {
    add("")
    add("技能: ${skill.name}")
    if (skill.description.isNotEmpty()) {
        add("  ${skill.description}")
    }
    if (skill.skillType == com.xianxia.sect.core.SkillType.SUPPORT) {
        add("  类型: 辅助")
    }
    if (skill.targetScope.isNotEmpty()) {
        add("  作用目标: ${getTargetScopeName(skill.targetScope)}")
    }
    if (skill.isAoe) {
        add("  范围: 全体")
    }
}

/** 已学功法技能数值信息：伤害/治疗/护盾/连击/冷却/消耗 */
@Suppress("CyclomaticComplexMethod", "DEPRECATION")
internal fun MutableList<String>.addLearnedManualSkillStats(skill: com.xianxia.sect.core.model.ManualSkill) {
    if (skill.damageMultiplier > 0 && skill.skillType == com.xianxia.sect.core.SkillType.ATTACK) {
        add("  伤害类型: ${if (skill.damageType == com.xianxia.sect.core.DamageType.PHYSICAL) "物理" else "法术"}")
        add("  伤害倍率: ${(skill.damageMultiplier * 100).toInt()}%")
    }
    if (skill.healPercent > 0) {
        val healTypeName = when (skill.healType) {
            com.xianxia.sect.core.HealType.HP -> "生命"
            com.xianxia.sect.core.HealType.MP -> "灵力"
        }
        add("  治疗: ${(skill.healPercent * 100).toInt()}% $healTypeName")
    }
    if (skill.healFixed > 0) {
        val healTypeName = when (skill.healType) {
            com.xianxia.sect.core.HealType.HP -> "生命"
            com.xianxia.sect.core.HealType.MP -> "灵力"
        }
        add("  固定治疗: +${skill.healFixed} $healTypeName")
    }
    if (skill.shieldPercent > 0) {
        add("  护盾: ${(skill.shieldPercent * 100).toInt()}% 最大生命")
    }
    if (skill.turnAdvancePercent > 0) {
        add("  行动提前: ${(skill.turnAdvancePercent * 100).toInt()}%")
    }
    if (skill.damageSharePercent > 0) {
        add("  伤害分摊: ${(skill.damageSharePercent * 100).toInt()}%")
    }
    if (skill.damageLinkPercent > 0) {
        add("  伤害链接: ${(skill.damageLinkPercent * 100).toInt()}%")
    }
    add("  连击次数: ${skill.hits}")
    if (skill.cooldown > 0) {
        add("  冷却回合: ${skill.cooldown}")
    }
    if (skill.mpCost > 0) {
        add("  灵力消耗: ${skill.mpCost}")
    }
}
