@file:Suppress("TooManyFunctions") // 私有辅助函数集中在本文件
package com.xianxia.sect.ui.game.components

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ItemEffect
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.util.GameUtils



// ===== 材料/灵草/种子效果 =====

internal fun getMaterialEffects(item: Material): List<String> = buildList {
    add("类型: ${item.category.displayName}")
    add("数量: ${item.quantity}")
    if (item.description.isNotBlank()) {
        add(item.description)
    }

    val templateId = com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialByName(item.name)?.id ?: item.id
    val forgeRecipes = ForgeRecipeDatabase.getRecipesByMaterial(templateId)
    if (forgeRecipes.isNotEmpty()) {
        val recipesText = forgeRecipes.take(5).map { recipe ->
            val count = recipe.materials[templateId] ?: 1
            "${recipe.name}×$count"
        }.joinToString("、")
        add("")
        add("可用于锻造：$recipesText")
        if (forgeRecipes.size > 5) {
            add("  等${forgeRecipes.size}种装备")
        }
    }
}

internal fun getHerbEffects(item: Herb): List<String> = buildList {
    add("类型: ${getHerbCategoryName(item.category)}")
    add("数量: ${item.quantity}")
    if (item.description.isNotBlank()) {
        add(item.description)
    }

    val templateId = HerbDatabase.getHerbByName(item.name)?.id ?: item.id
    val pillRecipes = PillRecipeDatabase.getRecipesByHerb(templateId)
    if (pillRecipes.isNotEmpty()) {
        val recipesText = pillRecipes.take(5).map { recipe ->
            val count = recipe.materials[templateId] ?: 1
            "${recipe.name}×$count"
        }.joinToString("、")
        add("")
        add("可用于炼制：$recipesText")
        if (pillRecipes.size > 5) {
            add("  等${pillRecipes.size}种丹药")
        }
    }
}

internal fun getSeedEffects(item: Seed): List<String> = buildList {
    add("类型: 种子")
    add("生长时间: ${item.growTime / 12}年")
    add("收获数量: ${item.yield}")
    add("数量: ${item.quantity}")
    if (item.description.isNotBlank()) {
        add(item.description)
    }

    val herb = HerbDatabase.getHerbFromSeedName(item.name)
        ?: HerbDatabase.getHerbFromSeed(item.id)
    if (herb != null) {
        add("")
        add("成熟后：${herb.name}")
        if (herb.description.isNotBlank()) {
            add("  ${herb.description}")
        }

        val pillRecipes = PillRecipeDatabase.getRecipesByHerb(herb.id)
        if (pillRecipes.isNotEmpty()) {
            val recipesText = pillRecipes.take(3).map { recipe ->
                val count = recipe.materials[herb.id] ?: 1
                "${recipe.name}×$count"
            }.joinToString("、")
            add("")
            add("可用于炼制：$recipesText")
            if (pillRecipes.size > 3) {
                add("  等${pillRecipes.size}种丹药")
            }
        }
    } else {
        val herbName = HerbDatabase.getHerbNameFromSeedName(item.name)
        add("")
        add("成熟后：$herbName")
    }
}

// ===== 商人/储物袋物品效果 =====

internal fun getMerchantItemEffects(item: MerchantItem): List<String> = buildList {
    addMerchantItemHeader(item = item)

    when (item.type) {
        "equipment" -> addMerchantEquipmentInfo(item = item)
        "manual" -> addMerchantManualInfo(item = item)
        "pill" -> addMerchantPillInfo(item = item)
        "material" -> addMerchantMaterialInfo(item = item)
        "herb" -> addMerchantHerbInfo(item = item)
        "seed" -> addMerchantSeedInfo(item = item)
        else -> {
            if (item.description.isNotEmpty()) {
                add("效果:")
                add("  ${item.description}")
            }
        }
    }
}

/** 商人物品头部信息 */
private fun MutableList<String>.addMerchantItemHeader(item: MerchantItem) {
    val typeName = when (item.type) {
        "equipment" -> "装备"
        "manual" -> "功法"
        "pill" -> "丹药"
        "material" -> "材料"
        "herb" -> "灵草"
        "seed" -> "种子"
        else -> "物品"
    }
    add("类型: $typeName")
    if (!item.grade.isNullOrEmpty()) {
        add("品级: ${item.grade}")
    }
    add("数量: ${item.quantity}")
    if (item.price > 0) {
        add("价格: ${item.price}灵石")
    }
    add("")
}

/** 商人物品装备信息 */
private fun MutableList<String>.addMerchantEquipmentInfo(item: MerchantItem) {
    val template = EquipmentDatabase.getTemplateByName(item.name)
    if (template != null) {
        add("部位: ${template.slot.displayName}")
        add("属性:")
        if (template.physicalAttack > 0) add("  物理攻击 +${template.physicalAttack}")
        if (template.magicAttack > 0) add("  法术攻击 +${template.magicAttack}")
        if (template.physicalDefense > 0) add("  物理防御 +${template.physicalDefense}")
        if (template.magicDefense > 0) add("  法术防御 +${template.magicDefense}")
        if (template.hp > 0) add("  生命 +${template.hp}")
        if (template.mp > 0) add("  灵力 +${template.mp}")
        if (template.speed > 0) add("  速度 +${template.speed}")
        if (template.critChance > 0) add("  暴击率 +${GameUtils.formatPercent(template.critChance)}")
        addForgeMaterialsInfo(item.name)
    }
}

/** 商人物品功法信息 */
@Suppress("NestedBlockDepth")
private fun MutableList<String>.addMerchantManualInfo(item: MerchantItem) {
    if (ManualDatabase.isInitialized) {
        val manualTemplate = ManualDatabase.getByName(item.name)
        if (manualTemplate != null) {
            add("功法类型: ${manualTemplate.type.displayName}")
            val stats = manualTemplate.stats
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
            manualTemplate.skillName?.let { sName ->
                add("")
                add("技能: $sName")
                addManualSkillInfo(manualTemplate)
            }
        }
    }
}

/** 商人物品丹药信息 */
private fun MutableList<String>.addMerchantPillInfo(item: MerchantItem) {
    val pillTemplate = ItemDatabase.getPillById(item.itemId)
        ?: ItemDatabase.getPillByName(item.name)
    if (pillTemplate != null) {
        add("效果:")
        val isInstant = merchantPillIsInstant(pill = pillTemplate)
        when (pillTemplate.category) {
            PillCategory.FUNCTIONAL -> addFunctionalPillTemplateEffects(pill = pillTemplate)
            PillCategory.CULTIVATION -> addCultivationPillTemplateEffects(pill = pillTemplate)
            PillCategory.BATTLE -> addBattlePillTemplateEffects(pill = pillTemplate)
        }
        if (!isInstant && pillTemplate.duration > 0) {
            add("  持续 ${pillTemplate.duration * 3} 旬")
        }
        if (isInstant) {
            add("  (一次性效果)")
        }
        addPillRecipeInfo(pillTemplate.id, item.name)
    } else if (item.description.isNotEmpty()) {
        add("效果:")
        add("  ${item.description}")
    }
}

/** 商人物品丹药是否一次性效果 */
@Suppress("CyclomaticComplexMethod")
private fun merchantPillIsInstant(pill: ItemDatabase.PillTemplate): Boolean =
    pill.category == PillCategory.FUNCTIONAL ||
    (pill.category == PillCategory.CULTIVATION && pill.pillType == "breakthrough") ||
    pill.cultivationAdd > 0 ||
    pill.skillExpAdd > 0 ||
    pill.nurtureAdd > 0 ||
    pill.extendLife > 0 ||
    pill.healMaxHpPercent > 0 ||
    pill.mpRecoverMaxMpPercent > 0 ||
    pill.revive ||
    pill.clearAll ||
    pill.intelligenceAdd > 0 ||
    pill.charmAdd > 0 ||
    pill.loyaltyAdd > 0 ||
    pill.comprehensionAdd > 0 ||
    pill.artifactRefiningAdd > 0 ||
    pill.pillRefiningAdd > 0 ||
    pill.spiritPlantingAdd > 0 ||
    pill.teachingAdd > 0 ||
    pill.moralityAdd > 0 ||
    pill.miningAdd > 0

/** 商人物品丹药功能类效果 */
@Suppress("CyclomaticComplexMethod")
private fun MutableList<String>.addFunctionalPillTemplateEffects(pill: ItemDatabase.PillTemplate) {
    if (pill.breakthroughChance > 0) add("  突破概率 +${GameUtils.formatPercent(pill.breakthroughChance)}")
    if (pill.targetRealm > 0) add("  目标境界: ${GameConfig.Realm.getName(pill.targetRealm)}")
    if (pill.isAscension) add("  可用于渡劫")
    if (pill.extendLife > 0) add("  延寿 +${pill.extendLife}年")
    if (pill.intelligenceAdd > 0) add("  悟性 +${pill.intelligenceAdd}")
    if (pill.charmAdd > 0) add("  魅力 +${pill.charmAdd}")
    if (pill.loyaltyAdd > 0) add("  忠诚 +${pill.loyaltyAdd}")
    if (pill.comprehensionAdd > 0) add("  领悟 +${pill.comprehensionAdd}")
    if (pill.artifactRefiningAdd > 0) add("  炼器 +${pill.artifactRefiningAdd}")
    if (pill.pillRefiningAdd > 0) add("  炼丹 +${pill.pillRefiningAdd}")
    if (pill.spiritPlantingAdd > 0) add("  灵植 +${pill.spiritPlantingAdd}")
    if (pill.teachingAdd > 0) add("  教导 +${pill.teachingAdd}")
    if (pill.moralityAdd > 0) add("  道德 +${pill.moralityAdd}")
    if (pill.miningAdd > 0) add("  采矿 +${pill.miningAdd}")
    if (pill.healMaxHpPercent > 0) add("  恢复生命 ${GameUtils.formatPercent(pill.healMaxHpPercent)} 最大生命")
    if (pill.mpRecoverMaxMpPercent > 0) add("  恢复灵力 ${GameUtils.formatPercent(pill.mpRecoverMaxMpPercent)} 最大灵力")
    if (pill.revive) add("  可复活弟子")
    if (pill.clearAll) add("  清除所有负面状态")
    if (pill.hpAdd > 0) add("  生命 +${pill.hpAdd}")
    if (pill.mpAdd > 0) add("  灵力 +${pill.mpAdd}")
    if (pill.physicalAttackAdd > 0) add("  物理攻击 +${pill.physicalAttackAdd}")
    if (pill.magicAttackAdd > 0) add("  法术攻击 +${pill.magicAttackAdd}")
    if (pill.physicalDefenseAdd > 0) add("  物理防御 +${pill.physicalDefenseAdd}")
    if (pill.magicDefenseAdd > 0) add("  法术防御 +${pill.magicDefenseAdd}")
    if (pill.speedAdd > 0) add("  速度 +${pill.speedAdd}")
}

/** 商人物品丹药修炼类效果 */
private fun MutableList<String>.addCultivationPillTemplateEffects(pill: ItemDatabase.PillTemplate) {
    if (pill.cultivationSpeedPercent > 0) add("  修炼速度 +${GameUtils.formatPercent(pill.cultivationSpeedPercent)}")
    if (pill.skillExpSpeedPercent > 0) add("  功法熟练度速度 +${GameUtils.formatPercent(pill.skillExpSpeedPercent)}")
    if (pill.nurtureSpeedPercent > 0) add("  孕养速度 +${GameUtils.formatPercent(pill.nurtureSpeedPercent)}")
    if (pill.cultivationAdd > 0) add("  修为 +${pill.cultivationAdd}")
    if (pill.skillExpAdd > 0) add("  功法熟练度 +${pill.skillExpAdd}")
    if (pill.nurtureAdd > 0) add("  孕养值 +${pill.nurtureAdd}")
    if (pill.breakthroughChance > 0) add("  突破概率 +${GameUtils.formatPercent(pill.breakthroughChance)}")
    if (pill.targetRealm > 0) add("  目标境界: ${GameConfig.Realm.getName(pill.targetRealm)}")
    if (pill.isAscension) add("  可用于渡劫")
}

/** 商人物品丹药战斗类效果 */
private fun MutableList<String>.addBattlePillTemplateEffects(pill: ItemDatabase.PillTemplate) {
    if (pill.physicalAttackAdd > 0) add("  物理攻击 +${pill.physicalAttackAdd}")
    if (pill.magicAttackAdd > 0) add("  法术攻击 +${pill.magicAttackAdd}")
    if (pill.physicalDefenseAdd > 0) add("  物理防御 +${pill.physicalDefenseAdd}")
    if (pill.magicDefenseAdd > 0) add("  法术防御 +${pill.magicDefenseAdd}")
    if (pill.hpAdd > 0) add("  生命 +${pill.hpAdd}")
    if (pill.mpAdd > 0) add("  灵力 +${pill.mpAdd}")
    if (pill.speedAdd > 0) add("  速度 +${pill.speedAdd}")
    if (pill.critRateAdd > 0) add("  暴击率 +${GameUtils.formatPercent(pill.critRateAdd)}")
    if (pill.critEffectAdd > 0) add("  暴击效果 +${GameUtils.formatPercent(pill.critEffectAdd)}")
}

/** 商人物品材料信息 */
private fun MutableList<String>.addMerchantMaterialInfo(item: MerchantItem) {
    val materialData = com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialByName(item.name)
    if (materialData != null && materialData.description.isNotBlank()) {
        add("效果:")
        add("  ${materialData.description}")
    } else if (item.description.isNotEmpty()) {
        add("效果:")
        add("  ${item.description}")
    }
    val templateId = materialData?.id ?: item.itemId
    val forgeRecipes = ForgeRecipeDatabase.getRecipesByMaterial(templateId)
    if (forgeRecipes.isNotEmpty()) {
        val recipesText = forgeRecipes.take(5).map { recipe ->
            val count = recipe.materials[templateId] ?: 1
            "${recipe.name}×$count"
        }.joinToString("、")
        add("")
        add("可用于锻造：$recipesText")
        if (forgeRecipes.size > 5) {
            add("  等${forgeRecipes.size}种装备")
        }
    } else if (materialData == null && item.description.isEmpty()) {
        add("效果:")
        add("  炼器材料")
    }
}

/** 商人物品灵草信息 */
private fun MutableList<String>.addMerchantHerbInfo(item: MerchantItem) {
    val herbData = HerbDatabase.getHerbById(item.itemId)
        ?: HerbDatabase.getHerbByName(item.name)
    if (herbData != null) {
        add("类型: ${getHerbCategoryName(herbData.category)}")
        if (herbData.description.isNotBlank()) {
            add("效果:")
            add("  ${herbData.description}")
        }
    }
    val templateId = herbData?.id ?: item.itemId
    val pillRecipes = PillRecipeDatabase.getRecipesByHerb(templateId)
    if (pillRecipes.isNotEmpty()) {
        val recipesText = pillRecipes.take(5).map { recipe ->
            val count = recipe.materials[templateId] ?: 1
            "${recipe.name}×$count"
        }.joinToString("、")
        add("")
        add("可用于炼制：$recipesText")
        if (pillRecipes.size > 5) {
            add("  等${pillRecipes.size}种丹药")
        }
    } else if (herbData == null) {
        add("效果:")
        add("  炼丹材料")
    }
}

/** 商人物品种子信息 */
private fun MutableList<String>.addMerchantSeedInfo(item: MerchantItem) {
    val seedData = HerbDatabase.getSeedByName(item.name)
    if (seedData != null && seedData.description.isNotBlank()) {
        add("效果:")
        add("  ${seedData.description}")
    } else if (item.description.isNotEmpty()) {
        add("效果:")
        add("  ${item.description}")
    }
    val herb = HerbDatabase.getHerbFromSeedName(item.name)
        ?: HerbDatabase.getHerbFromSeed(item.itemId)
    if (herb != null) {
        add("")
        add("成熟后：${herb.name}")
        if (herb.description.isNotBlank()) {
            add("  ${herb.description}")
        }
        val pillRecipes = PillRecipeDatabase.getRecipesByHerb(herb.id)
        if (pillRecipes.isNotEmpty()) {
            val recipesText = pillRecipes.take(3).map { recipe ->
                val count = recipe.materials[herb.id] ?: 1
                "${recipe.name}×$count"
            }.joinToString("、")
            add("")
            add("可用于炼制：$recipesText")
            if (pillRecipes.size > 3) {
                add("  等${pillRecipes.size}种丹药")
            }
        }
    } else {
        val herbName = HerbDatabase.getHerbNameFromSeedName(item.name)
        add("")
        add("成熟后：$herbName")
    }
}

/** 储物袋未知类型物品效果兜底：直接按 ItemEffect 字段铺开 */
private fun MutableList<String>.addStorageBagEffectFallback(item: StorageBagItem) {
    item.effect?.let { effect ->
        add("效果:")
        if (effect.cultivationSpeedPercent >
            0) { add("  修炼速度 +${GameUtils.formatPercent(effect.cultivationSpeedPercent)}") }
        if (effect.cultivationAdd > 0) { add("  修为 +${effect.cultivationAdd}") }
        if (effect.hpAdd > 0) { add("  生命 +${effect.hpAdd}") }
        if (effect.mpAdd > 0) { add("  灵力 +${effect.mpAdd}") }
        if (effect.physicalAttackAdd > 0) { add("  物理攻击 +${effect.physicalAttackAdd}") }
        if (effect.magicAttackAdd > 0) { add("  法术攻击 +${effect.magicAttackAdd}") }
        if (effect.physicalDefenseAdd > 0) { add("  物理防御 +${effect.physicalDefenseAdd}") }
        if (effect.magicDefenseAdd > 0) { add("  法术防御 +${effect.magicDefenseAdd}") }
        if (effect.speedAdd > 0) { add("  速度 +${effect.speedAdd}") }
    }
}

internal fun getStorageBagItemEffects(item: StorageBagItem): List<String> = buildList {
    addStorageBagItemHeader(item = item)

    when (item.itemType) {
        "equipment" -> addStorageBagEquipmentInfo(item = item)
        "manual" -> addStorageBagManualInfo(item = item)
        "pill" -> addStorageBagPillInfo(item = item)
        "material" -> addStorageBagMaterialInfo(item = item)
        "herb" -> addStorageBagHerbInfo(item = item)
        "seed" -> addStorageBagSeedInfo(item = item)
        else -> addStorageBagEffectFallback(item)
    }
}

/** 储物袋物品头部信息 */
private fun MutableList<String>.addStorageBagItemHeader(item: StorageBagItem) {
    val typeName = when (item.itemType) {
        "equipment" -> "装备"
        "manual" -> "功法"
        "pill" -> "丹药"
        "material" -> "材料"
        "herb" -> "灵草"
        "seed" -> "种子"
        else -> "物品"
    }
    add("类型: $typeName")
    if (!item.grade.isNullOrEmpty()) {
        add("品级: ${item.grade}")
    }
    add("数量: ${item.quantity}")
    add("获得时间: 第${item.obtainedYear}年${item.obtainedMonth}月")
    add("")
}

/** 储物袋物品装备信息 */
@Suppress("CyclomaticComplexMethod")
private fun MutableList<String>.addStorageBagEquipmentInfo(item: StorageBagItem) {
    val template = EquipmentDatabase.getTemplateByName(item.name)
    if (template != null) {
        add("部位: ${template.slot.displayName}")
        add("属性:")
        if (template.physicalAttack > 0) add("  物理攻击 +${template.physicalAttack}")
        if (template.magicAttack > 0) add("  法术攻击 +${template.magicAttack}")
        if (template.physicalDefense > 0) add("  物理防御 +${template.physicalDefense}")
        if (template.magicDefense > 0) add("  法术防御 +${template.magicDefense}")
        if (template.hp > 0) add("  生命 +${template.hp}")
        if (template.mp > 0) add("  灵力 +${template.mp}")
        if (template.speed > 0) add("  速度 +${template.speed}")
        if (template.critChance > 0) add("  暴击率 +${GameUtils.formatPercent(template.critChance)}")
        addForgeMaterialsInfo(item.name)
    } else {
        item.effect?.let { effect ->
            add("属性:")
            if (effect.physicalAttackAdd > 0) { add("  物理攻击 +${effect.physicalAttackAdd}") }
            if (effect.magicAttackAdd > 0) { add("  法术攻击 +${effect.magicAttackAdd}") }
            if (effect.physicalDefenseAdd > 0) { add("  物理防御 +${effect.physicalDefenseAdd}") }
            if (effect.magicDefenseAdd > 0) { add("  法术防御 +${effect.magicDefenseAdd}") }
            if (effect.hpAdd > 0) { add("  生命 +${effect.hpAdd}") }
            if (effect.mpAdd > 0) { add("  灵力 +${effect.mpAdd}") }
            if (effect.speedAdd > 0) { add("  速度 +${effect.speedAdd}") }
            if (effect.critRateAdd > 0) { add("  暴击率 +${GameUtils.formatPercent(effect.critRateAdd)}") }
            if (effect.critEffectAdd > 0) { add("  暴击效果 +${GameUtils.formatPercent(effect.critEffectAdd)}") }
        }
    }
}

/** 储物袋物品功法信息 */
@Suppress("NestedBlockDepth")
private fun MutableList<String>.addStorageBagManualInfo(item: StorageBagItem) {
    if (ManualDatabase.isInitialized) {
        val manualTemplate = ManualDatabase.getByName(item.name)
        if (manualTemplate != null) {
            add("功法类型: ${manualTemplate.type.displayName}")
            val stats = manualTemplate.stats
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
            manualTemplate.skillName?.let { sName ->
                add("")
                add("技能: $sName")
                addManualSkillInfo(manualTemplate)
            }
        }
    }
}

/** 储物袋物品丹药信息 */
@Suppress("CyclomaticComplexMethod")
private fun MutableList<String>.addStorageBagPillInfo(item: StorageBagItem) {
    val pillCategoryDisplayName = when (item.effect?.pillCategory) {
        PillCategory.FUNCTIONAL.name -> PillCategory.FUNCTIONAL.displayName
        PillCategory.CULTIVATION.name -> PillCategory.CULTIVATION.displayName
        PillCategory.BATTLE.name -> PillCategory.BATTLE.displayName
        "" -> null
        else -> null
    }
    if (pillCategoryDisplayName != null) {
        add("类型: $pillCategoryDisplayName")
    }
    val itemEffect = item.effect
    if (itemEffect != null && itemEffect.minRealm < 9) {
        add("需求境界: ${GameConfig.Realm.getName(itemEffect.minRealm)}")
    }
    item.effect?.let { effect ->
        add("效果:")
        val isInstantPill = storageBagPillIsInstant(effect = effect)
        when (effect.pillCategory) {
            PillCategory.FUNCTIONAL.name -> addStorageBagFunctionalPillEffects(effect = effect)
            PillCategory.CULTIVATION.name -> addStorageBagCultivationPillEffects(effect = effect)
            PillCategory.BATTLE.name -> addStorageBagBattlePillEffects(effect = effect)
        }
        if (!isInstantPill && effect.duration > 0) {
            add("  持续 ${effect.duration * 3} 旬")
        }
        if (isInstantPill) {
            add("  (一次性效果)")
        }
        addPillRecipeInfo(item.itemId, item.name)
    }
}

/** 储物袋丹药是否一次性效果 */
@Suppress("CyclomaticComplexMethod")
private fun storageBagPillIsInstant(effect: ItemEffect): Boolean =
    effect.pillCategory == PillCategory.FUNCTIONAL.name ||
    (effect.pillCategory == PillCategory.CULTIVATION.name && effect.pillType == "breakthrough") ||
    effect.cultivationAdd > 0 ||
    effect.skillExpAdd > 0 ||
    effect.nurtureAdd > 0 ||
    effect.extendLife > 0 ||
    effect.healMaxHpPercent > 0 ||
    effect.mpRecoverMaxMpPercent > 0 ||
    effect.revive ||
    effect.clearAll ||
    effect.intelligenceAdd > 0 ||
    effect.charmAdd > 0 ||
    effect.loyaltyAdd > 0 ||
    effect.comprehensionAdd > 0 ||
    effect.artifactRefiningAdd > 0 ||
    effect.pillRefiningAdd > 0 ||
    effect.spiritPlantingAdd > 0 ||
    effect.teachingAdd > 0 ||
    effect.moralityAdd > 0 ||
    effect.miningAdd > 0

/** 储物袋丹药功能类效果 */
@Suppress("CyclomaticComplexMethod")
private fun MutableList<String>.addStorageBagFunctionalPillEffects(effect: ItemEffect) {
    if (effect.breakthroughChance > 0) add("  突破概率 +${GameUtils.formatPercent(effect.breakthroughChance)}")
    if (effect.targetRealm > 0) add("  目标境界: ${GameConfig.Realm.getName(effect.targetRealm)}")
    if (effect.isAscension) add("  可用于渡劫")
    if (effect.extendLife > 0) add("  延寿 +${effect.extendLife}年")
    if (effect.intelligenceAdd > 0) add("  悟性 +${effect.intelligenceAdd}")
    if (effect.charmAdd > 0) add("  魅力 +${effect.charmAdd}")
    if (effect.loyaltyAdd > 0) add("  忠诚 +${effect.loyaltyAdd}")
    if (effect.comprehensionAdd > 0) add("  领悟 +${effect.comprehensionAdd}")
    if (effect.artifactRefiningAdd > 0) add("  炼器 +${effect.artifactRefiningAdd}")
    if (effect.pillRefiningAdd > 0) add("  炼丹 +${effect.pillRefiningAdd}")
    if (effect.spiritPlantingAdd > 0) add("  灵植 +${effect.spiritPlantingAdd}")
    if (effect.teachingAdd > 0) add("  教导 +${effect.teachingAdd}")
    if (effect.moralityAdd > 0) add("  道德 +${effect.moralityAdd}")
    if (effect.miningAdd > 0) add("  采矿 +${effect.miningAdd}")
    if (effect.healMaxHpPercent > 0) add("  恢复生命 ${GameUtils.formatPercent(effect.healMaxHpPercent)} 最大生命")
    if (effect.mpRecoverMaxMpPercent > 0) add("  恢复灵力 ${GameUtils.formatPercent(effect.mpRecoverMaxMpPercent)} 最大灵力")
    if (effect.revive) add("  可复活弟子")
    if (effect.clearAll) add("  清除所有负面状态")
    if (effect.hpAdd > 0) add("  生命 +${effect.hpAdd}")
    if (effect.mpAdd > 0) add("  灵力 +${effect.mpAdd}")
    if (effect.physicalAttackAdd > 0) add("  物理攻击 +${effect.physicalAttackAdd}")
    if (effect.magicAttackAdd > 0) add("  法术攻击 +${effect.magicAttackAdd}")
    if (effect.physicalDefenseAdd > 0) add("  物理防御 +${effect.physicalDefenseAdd}")
    if (effect.magicDefenseAdd > 0) add("  法术防御 +${effect.magicDefenseAdd}")
    if (effect.speedAdd > 0) add("  速度 +${effect.speedAdd}")
}

/** 储物袋丹药修炼类效果 */
private fun MutableList<String>.addStorageBagCultivationPillEffects(effect: ItemEffect) {
    if (effect.cultivationSpeedPercent > 0) add("  修炼速度 +${GameUtils.formatPercent(effect.cultivationSpeedPercent)}")
    if (effect.skillExpSpeedPercent > 0) add("  功法熟练度速度 +${GameUtils.formatPercent(effect.skillExpSpeedPercent)}")
    if (effect.nurtureSpeedPercent > 0) add("  孕养速度 +${GameUtils.formatPercent(effect.nurtureSpeedPercent)}")
    if (effect.cultivationAdd > 0) add("  修为 +${effect.cultivationAdd}")
    if (effect.skillExpAdd > 0) add("  功法熟练度 +${effect.skillExpAdd}")
    if (effect.nurtureAdd > 0) add("  孕养值 +${effect.nurtureAdd}")
    if (effect.breakthroughChance > 0) add("  突破概率 +${GameUtils.formatPercent(effect.breakthroughChance)}")
    if (effect.targetRealm > 0) add("  目标境界: ${GameConfig.Realm.getName(effect.targetRealm)}")
    if (effect.isAscension) add("  可用于渡劫")
}

/** 储物袋丹药战斗类效果 */
private fun MutableList<String>.addStorageBagBattlePillEffects(effect: ItemEffect) {
    if (effect.physicalAttackAdd > 0) add("  物理攻击 +${effect.physicalAttackAdd}")
    if (effect.magicAttackAdd > 0) add("  法术攻击 +${effect.magicAttackAdd}")
    if (effect.physicalDefenseAdd > 0) add("  物理防御 +${effect.physicalDefenseAdd}")
    if (effect.magicDefenseAdd > 0) add("  法术防御 +${effect.magicDefenseAdd}")
    if (effect.hpAdd > 0) add("  生命 +${effect.hpAdd}")
    if (effect.mpAdd > 0) add("  灵力 +${effect.mpAdd}")
    if (effect.speedAdd > 0) add("  速度 +${effect.speedAdd}")
    if (effect.critRateAdd > 0) add("  暴击率 +${GameUtils.formatPercent(effect.critRateAdd)}")
    if (effect.critEffectAdd > 0) add("  暴击效果 +${GameUtils.formatPercent(effect.critEffectAdd)}")
}

/** 储物袋物品材料信息 */
private fun MutableList<String>.addStorageBagMaterialInfo(item: StorageBagItem) {
    val materialData = com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialByName(item.name)
    if (materialData != null && materialData.description.isNotBlank()) {
        add("效果:")
        add("  ${materialData.description}")
    }
    val templateId = materialData?.id ?: item.itemId
    val forgeRecipes = ForgeRecipeDatabase.getRecipesByMaterial(templateId)
    if (forgeRecipes.isNotEmpty()) {
        val recipesText = forgeRecipes.take(5).map { recipe ->
            val count = recipe.materials[templateId] ?: 1
            "${recipe.name}×$count"
        }.joinToString("、")
        add("")
        add("可用于锻造：$recipesText")
        if (forgeRecipes.size > 5) {
            add("  等${forgeRecipes.size}种装备")
        }
    } else if (materialData == null) {
        add("炼器材料")
    }
}

/** 储物袋物品灵草信息 */
private fun MutableList<String>.addStorageBagHerbInfo(item: StorageBagItem) {
    val herbData = HerbDatabase.getHerbById(item.itemId)
        ?: HerbDatabase.getHerbByName(item.name)
    if (herbData != null) {
        add("类型: ${getHerbCategoryName(herbData.category)}")
        if (herbData.description.isNotBlank()) {
            add("效果:")
            add("  ${herbData.description}")
        }
    }
    val templateId = herbData?.id ?: item.itemId
    val pillRecipes = PillRecipeDatabase.getRecipesByHerb(templateId)
    if (pillRecipes.isNotEmpty()) {
        val recipesText = pillRecipes.take(5).map { recipe ->
            val count = recipe.materials[templateId] ?: 1
            "${recipe.name}×$count"
        }.joinToString("、")
        add("")
        add("可用于炼制：$recipesText")
        if (pillRecipes.size > 5) {
            add("  等${pillRecipes.size}种丹药")
        }
    } else if (herbData == null) {
        add("炼丹材料")
    }
}

/** 储物袋物品种子信息 */
private fun MutableList<String>.addStorageBagSeedInfo(item: StorageBagItem) {
    val seedData = HerbDatabase.getSeedByName(item.name)
    if (seedData != null && seedData.description.isNotBlank()) {
        add("效果:")
        add("  ${seedData.description}")
    }
    val herb = HerbDatabase.getHerbFromSeedName(item.name)
        ?: HerbDatabase.getHerbFromSeed(item.itemId)
    if (herb != null) {
        add("")
        add("成熟后：${herb.name}")
        if (herb.description.isNotBlank()) {
            add("  ${herb.description}")
        }
        val pillRecipes = PillRecipeDatabase.getRecipesByHerb(herb.id)
        if (pillRecipes.isNotEmpty()) {
            val recipesText = pillRecipes.take(3).map { recipe ->
                val count = recipe.materials[herb.id] ?: 1
                "${recipe.name}×$count"
            }.joinToString("、")
            add("")
            add("可用于炼制：$recipesText")
            if (pillRecipes.size > 3) {
                add("  等${pillRecipes.size}种丹药")
            }
        }
    } else {
        val herbName = HerbDatabase.getHerbNameFromSeedName(item.name)
        add("")
        add("成熟后：$herbName")
    }
}
