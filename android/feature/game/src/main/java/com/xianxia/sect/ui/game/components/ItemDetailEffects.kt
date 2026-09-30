package com.xianxia.sect.ui.game.components

import com.xianxia.sect.ui.components.getRarityName
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase





/** Buff 键 → BuffType 映射表（getBuffTypeName/parseManualStackBuffs 查表） */

/** 属性键 → 中文显示名映射表（getStatDisplayName 查表） */
private val STAT_DISPLAY_NAMES: Map<String, String> = mapOf(
    "cultivationSpeedPercent" to "修炼速度",
    "skillExpSpeedPercent" to "功法熟练度速度",
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

/**
 * 装备展开条目查找（B3 新表 72 条）：优先按条目 id（`{pieceId}_r{rarity}`），
 * 兜底按部件名取最低品阶条目（旧数据面只有名称时）。
 */
internal fun findEquipmentEntry(id: String, name: String): EquipmentDatabase.EquipPieceEntry? =
    EquipmentDatabase.getById(id)
        ?: EquipmentDatabase.entries.values.filter { it.name == name }.minByOrNull { it.rarity }

internal fun getEquipmentEffects(item: EquipmentInstance): List<String> = buildList {
    add("部位: ${item.part.displayName}")
    add("稀有度: ${getRarityName(item.rarity)}")
    add("等级: Lv.${item.level}")
    if (item.minRealm < 9) {
        add("需求境界: ${GameConfig.Realm.getName(item.minRealm)}")
    }
    add("")
    add("词条:")
    item.totalBonus().forEach { bonus ->
        add("  $bonus")
    }
}
