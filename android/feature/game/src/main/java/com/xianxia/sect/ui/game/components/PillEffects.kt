package com.xianxia.sect.ui.game.components

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.util.GameUtils


// ===== 丹药效果 =====
internal fun getPillEffects(item: Pill): List<String> = buildList {
    addPillHeaderInfo(item = item)
    add("效果:")
    val isInstant = isPillInstant(item = item)
    when (item.category) {
        PillCategory.FUNCTIONAL -> addFunctionalPillEffects(item = item)
        PillCategory.CULTIVATION -> addCultivationPillEffects(item = item)
        PillCategory.BATTLE -> addBattlePillEffects(item = item)
    }
    if (!isInstant && item.duration > 0) {
        add("  持续 ${item.duration * 3} 旬")
    }
    if (isInstant) {
        add("  (一次性效果)")
    }
    addPillRecipeInfo(item.id, item.name)
}

/** 丹药基础信息 */
internal fun MutableList<String>.addPillHeaderInfo(item: Pill) {
    add("类型: ${item.category.displayName}")
    add("品级: ${item.grade.displayName}")
    add("数量: ${item.quantity}")
    if (item.minRealm < 9) {
        add("需求境界: ${GameConfig.Realm.getName(item.minRealm)}")
    }
    add("")
}

/** 丹药是否一次性效果 */
@Suppress("CyclomaticComplexMethod")
internal fun isPillInstant(item: Pill): Boolean = item.category == PillCategory.FUNCTIONAL ||
    (item.category == PillCategory.CULTIVATION && item.pillType == "breakthrough") ||
    item.cultivationAdd > 0 ||
    item.skillExpAdd > 0 ||
    item.nurtureAdd > 0 ||
    item.extendLife > 0 ||
    item.healMaxHpPercent > 0 ||
    item.mpRecoverMaxMpPercent > 0 ||
    item.revive ||
    item.clearAll ||
    item.intelligenceAdd > 0 ||
    item.charmAdd > 0 ||
    item.loyaltyAdd > 0 ||
    item.comprehensionAdd > 0 ||
    item.artifactRefiningAdd > 0 ||
    item.pillRefiningAdd > 0 ||
    item.spiritPlantingAdd > 0 ||
    item.teachingAdd > 0 ||
    item.moralityAdd > 0 ||
    item.miningAdd > 0

/** 丹药功能类效果 */
@Suppress("CyclomaticComplexMethod")
internal fun MutableList<String>.addFunctionalPillEffects(item: Pill) {
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

/** 丹药修炼类效果 */
internal fun MutableList<String>.addCultivationPillEffects(item: Pill) {
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

/** 丹药战斗类效果 */
internal fun MutableList<String>.addBattlePillEffects(item: Pill) {
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
