package com.xianxia.sect.ui.game.components

import com.xianxia.sect.ui.components.getRarityName
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.util.GameUtils
import java.util.Locale





/** Buff 键 → BuffType 映射表（getBuffTypeName/parseManualStackBuffs 查表） */

/** 属性键 → 中文显示名映射表（getStatDisplayName 查表） */
private val STAT_DISPLAY_NAMES: Map<String, String> = mapOf(
    "cultivationSpeedPercent" to "修炼速度",
    "skillExpSpeedPercent" to "功法熟练度速度",
    "nurtureSpeedPercent" to "孕养速度",
    "physicalAttack" to "物理攻击",
    "magicAttack" to "法术攻击",
    "physicalDefense" to "物理防御",
    "magicDefense" to "法术防御",
    "hp" to "生命",
    "mp" to "灵力",
    "speed" to "速度",
    "critRate" to "暴击率",
    "critEffect" to "暴击效果",
    "intelligence" to "悟性",
    "charm" to "魅力",
    "loyalty" to "忠诚",
    "comprehension" to "领悟",
    "artifactRefining" to "炼器",
    "pillRefining" to "炼丹",
    "spiritPlanting" to "灵植",
    "teaching" to "教导",
    "morality" to "道德"
)

internal fun getStatDisplayName(key: String): String = STAT_DISPLAY_NAMES[key] ?: key

internal fun getHerbCategoryName(category: String): String = when (category) {
    "grass" -> "灵草"
    "flower" -> "灵花"
    "fruit" -> "灵果"
    else -> if (category.isNotEmpty()) category else "灵药"
}

internal fun MutableList<String>.addForgeMaterialsInfo(equipmentName: String) {
    val forgeRecipe = ForgeRecipeDatabase.getAllRecipes().find { it.name == equipmentName }
    if (forgeRecipe != null && forgeRecipe.materials.isNotEmpty()) {
        val materialsText = forgeRecipe.materials.map { (materialId, count) ->
            val materialName = com.xianxia.sect.core.registry.BeastMaterialDatabase
                .getMaterialById(materialId)?.name ?: materialId
            "$materialName×$count"
        }.joinToString("、")
        add("")
        add("锻造材料：$materialsText")
    }
}

internal fun MutableList<String>.addPillRecipeInfo(pillId: String, pillName: String) {
    val pillRecipe = PillRecipeDatabase.getRecipeById(pillId)
        ?: PillRecipeDatabase.getRecipeByName(pillName)
    if (pillRecipe != null && pillRecipe.materials.isNotEmpty()) {
        val materialsText = pillRecipe.materials.map { (herbId, count) ->
            val herbName = HerbDatabase.getHerbById(herbId)?.name ?: herbId
            "$herbName×$count"
        }.joinToString("、")
        add("")
        add("炼制材料：$materialsText")
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

/** 装备最终属性行：终值 >0 时输出，加成 >0 追加 (↑x) 提示 */
private fun MutableList<String>.addFinalStatLine(label: String, finalValue: Int, baseValue: Int) {
    if (finalValue > 0) {
        val bonus = finalValue - baseValue
        val bonusText = if (bonus > 0) " (↑$bonus)" else ""
        add("  $label +$finalValue$bonusText")
    }
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
    addFinalStatLine("物理攻击", finalStats.physicalAttack, baseStats.physicalAttack)
    addFinalStatLine("法术攻击", finalStats.magicAttack, baseStats.magicAttack)
    addFinalStatLine("物理防御", finalStats.physicalDefense, baseStats.physicalDefense)
    addFinalStatLine("法术防御", finalStats.magicDefense, baseStats.magicDefense)
    addFinalStatLine("速度", finalStats.speed, baseStats.speed)
    addFinalStatLine("生命", finalStats.hp, baseStats.hp)
    addFinalStatLine("灵力", finalStats.mp, baseStats.mp)
    if (item.critChance > 0) add("  暴击率 +${GameUtils.formatPercent(item.critChance)}")

    addForgeMaterialsInfo(item.name)
}
