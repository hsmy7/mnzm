package com.xianxia.sect.ui.game.dialogs

import com.xianxia.sect.core.registry.PillRecipeDatabase

internal data class PillStatLine(val label: String, val value: String)

/** 丹药详情属性行列表 */
@Suppress("CyclomaticComplexMethod", "UnusedParameter")
internal fun pillDetailStatLines(
    recipe: PillRecipeDatabase.PillRecipe,
    low: PillRecipeDatabase.PillRecipe?,
    high: PillRecipeDatabase.PillRecipe?,
    intRange: (getter: (PillRecipeDatabase.PillRecipe) -> Int) -> String,
    pctRange: (getter: (PillRecipeDatabase.PillRecipe) -> Double) -> String
): List<PillStatLine> = buildList {
    if (recipe.breakthroughChance > 0) {
        add(PillStatLine("突破成功率", pctRange { it.breakthroughChance }))
        if (recipe.targetRealm > 0) add(PillStatLine("目标境界", "${recipe.targetRealm}阶"))
    }
    if (recipe.cultivationSpeedPercent > 0) add(PillStatLine("修炼速度", pctRange { it.cultivationSpeedPercent }))
    if (recipe.cultivationAdd > 0) add(PillStatLine("修为", intRange { it.cultivationAdd }))
    if (recipe.physicalAttackAdd > 0) add(PillStatLine("物理攻击", intRange { it.physicalAttackAdd }))
    if (recipe.magicAttackAdd > 0) add(PillStatLine("法术攻击", intRange { it.magicAttackAdd }))
    if (recipe.physicalDefenseAdd > 0) add(PillStatLine("物理防御", intRange { it.physicalDefenseAdd }))
    if (recipe.magicDefenseAdd > 0) add(PillStatLine("法术防御", intRange { it.magicDefenseAdd }))
    if (recipe.hpAdd > 0) add(PillStatLine("生命值", intRange { it.hpAdd }))
    if (recipe.mpAdd > 0) add(PillStatLine("灵力容量", intRange { it.mpAdd }))
    if (recipe.speedAdd > 0) add(PillStatLine("身法", intRange { it.speedAdd }))
    if (recipe.critRateAdd > 0) add(PillStatLine("暴击率", pctRange { it.critRateAdd }))
    if (recipe.critEffectAdd > 0) add(PillStatLine("暴击效果", pctRange { it.critEffectAdd }))
    if (recipe.skillExpAdd > 0) add(PillStatLine("功法熟练度", intRange { it.skillExpAdd }))
    if (recipe.nurtureAdd > 0) add(PillStatLine("孕育值", intRange { it.nurtureAdd }))
    if (recipe.extendLife > 0) add(PillStatLine("延长寿命", "${intRange { it.extendLife }}年"))
    if (recipe.intelligenceAdd > 0) add(PillStatLine("悟性", intRange { it.intelligenceAdd }))
    if (recipe.charmAdd > 0) add(PillStatLine("魅力", intRange { it.charmAdd }))
    if (recipe.loyaltyAdd > 0) add(PillStatLine("忠诚", intRange { it.loyaltyAdd }))
    if (recipe.comprehensionAdd > 0) add(PillStatLine("领悟", intRange { it.comprehensionAdd }))
    if (recipe.artifactRefiningAdd > 0) add(PillStatLine("炼器", intRange { it.artifactRefiningAdd }))
    if (recipe.pillRefiningAdd > 0) add(PillStatLine("炼丹", intRange { it.pillRefiningAdd }))
    if (recipe.spiritPlantingAdd > 0) add(PillStatLine("种植", intRange { it.spiritPlantingAdd }))
    if (recipe.teachingAdd > 0) add(PillStatLine("传授", intRange { it.teachingAdd }))
    if (recipe.moralityAdd > 0) add(PillStatLine("道德", intRange { it.moralityAdd }))
}
