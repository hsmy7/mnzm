package com.xianxia.sect.ui.game.components

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
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

    when (item.type) {
        "equipment" -> {
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
        "manual" -> {
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
        "pill" -> {
            val pillTemplate = ItemDatabase.getPillById(item.itemId)
                ?: ItemDatabase.getPillByName(item.name)
            if (pillTemplate != null) {
                add("效果:")
                val isInstant = pillTemplate.category == PillCategory.FUNCTIONAL ||
                    (pillTemplate.category == PillCategory.CULTIVATION && pillTemplate.pillType == "breakthrough") ||
                    pillTemplate.cultivationAdd > 0 ||
                    pillTemplate.skillExpAdd > 0 ||
                    pillTemplate.nurtureAdd > 0 ||
                    pillTemplate.extendLife > 0 ||
                    pillTemplate.healMaxHpPercent > 0 ||
                    pillTemplate.mpRecoverMaxMpPercent > 0 ||
                    pillTemplate.revive ||
                    pillTemplate.clearAll ||
                    pillTemplate.intelligenceAdd > 0 ||
                    pillTemplate.charmAdd > 0 ||
                    pillTemplate.loyaltyAdd > 0 ||
                    pillTemplate.comprehensionAdd > 0 ||
                    pillTemplate.artifactRefiningAdd > 0 ||
                    pillTemplate.pillRefiningAdd > 0 ||
                    pillTemplate.spiritPlantingAdd > 0 ||
                    pillTemplate.teachingAdd > 0 ||
                    pillTemplate.moralityAdd > 0 ||
                    pillTemplate.miningAdd > 0
                when (pillTemplate.category) {
                    PillCategory.FUNCTIONAL -> {
                        if (pillTemplate.breakthroughChance > 0) add("  突破概率 +${GameUtils.formatPercent(pillTemplate.breakthroughChance)}")
                        if (pillTemplate.targetRealm > 0) add("  目标境界: ${GameConfig.Realm.getName(pillTemplate.targetRealm)}")
                        if (pillTemplate.isAscension) add("  可用于渡劫")
                        if (pillTemplate.extendLife > 0) add("  延寿 +${pillTemplate.extendLife}年")
                        if (pillTemplate.intelligenceAdd > 0) add("  悟性 +${pillTemplate.intelligenceAdd}")
                        if (pillTemplate.charmAdd > 0) add("  魅力 +${pillTemplate.charmAdd}")
                        if (pillTemplate.loyaltyAdd > 0) add("  忠诚 +${pillTemplate.loyaltyAdd}")
                        if (pillTemplate.comprehensionAdd > 0) add("  领悟 +${pillTemplate.comprehensionAdd}")
                        if (pillTemplate.artifactRefiningAdd > 0) add("  炼器 +${pillTemplate.artifactRefiningAdd}")
                        if (pillTemplate.pillRefiningAdd > 0) add("  炼丹 +${pillTemplate.pillRefiningAdd}")
                        if (pillTemplate.spiritPlantingAdd > 0) add("  灵植 +${pillTemplate.spiritPlantingAdd}")
                        if (pillTemplate.teachingAdd > 0) add("  教导 +${pillTemplate.teachingAdd}")
                        if (pillTemplate.moralityAdd > 0) add("  道德 +${pillTemplate.moralityAdd}")
                        if (pillTemplate.miningAdd > 0) add("  采矿 +${pillTemplate.miningAdd}")
                        if (pillTemplate.healMaxHpPercent > 0) add("  恢复生命 ${GameUtils.formatPercent(pillTemplate.healMaxHpPercent)} 最大生命")
                        if (pillTemplate.mpRecoverMaxMpPercent > 0) add("  恢复灵力 ${GameUtils.formatPercent(pillTemplate.mpRecoverMaxMpPercent)} 最大灵力")
                        if (pillTemplate.revive) add("  可复活弟子")
                        if (pillTemplate.clearAll) add("  清除所有负面状态")
                        if (pillTemplate.hpAdd > 0) add("  生命 +${pillTemplate.hpAdd}")
                        if (pillTemplate.mpAdd > 0) add("  灵力 +${pillTemplate.mpAdd}")
                        if (pillTemplate.physicalAttackAdd > 0) add("  物理攻击 +${pillTemplate.physicalAttackAdd}")
                        if (pillTemplate.magicAttackAdd > 0) add("  法术攻击 +${pillTemplate.magicAttackAdd}")
                        if (pillTemplate.physicalDefenseAdd > 0) add("  物理防御 +${pillTemplate.physicalDefenseAdd}")
                        if (pillTemplate.magicDefenseAdd > 0) add("  法术防御 +${pillTemplate.magicDefenseAdd}")
                        if (pillTemplate.speedAdd > 0) add("  速度 +${pillTemplate.speedAdd}")
                    }
                    PillCategory.CULTIVATION -> {
                        if (pillTemplate.cultivationSpeedPercent > 0) add("  修炼速度 +${GameUtils.formatPercent(pillTemplate.cultivationSpeedPercent)}")
                        if (pillTemplate.skillExpSpeedPercent > 0) add("  功法熟练度速度 +${GameUtils.formatPercent(pillTemplate.skillExpSpeedPercent)}")
                        if (pillTemplate.nurtureSpeedPercent > 0) add("  孕养速度 +${GameUtils.formatPercent(pillTemplate.nurtureSpeedPercent)}")
                        if (pillTemplate.cultivationAdd > 0) add("  修为 +${pillTemplate.cultivationAdd}")
                        if (pillTemplate.skillExpAdd > 0) add("  功法熟练度 +${pillTemplate.skillExpAdd}")
                        if (pillTemplate.nurtureAdd > 0) add("  孕养值 +${pillTemplate.nurtureAdd}")
                        if (pillTemplate.breakthroughChance > 0) add("  突破概率 +${GameUtils.formatPercent(pillTemplate.breakthroughChance)}")
                        if (pillTemplate.targetRealm > 0) add("  目标境界: ${GameConfig.Realm.getName(pillTemplate.targetRealm)}")
                        if (pillTemplate.isAscension) add("  可用于渡劫")
                    }
                    PillCategory.BATTLE -> {
                        if (pillTemplate.physicalAttackAdd > 0) add("  物理攻击 +${pillTemplate.physicalAttackAdd}")
                        if (pillTemplate.magicAttackAdd > 0) add("  法术攻击 +${pillTemplate.magicAttackAdd}")
                        if (pillTemplate.physicalDefenseAdd > 0) add("  物理防御 +${pillTemplate.physicalDefenseAdd}")
                        if (pillTemplate.magicDefenseAdd > 0) add("  法术防御 +${pillTemplate.magicDefenseAdd}")
                        if (pillTemplate.hpAdd > 0) add("  生命 +${pillTemplate.hpAdd}")
                        if (pillTemplate.mpAdd > 0) add("  灵力 +${pillTemplate.mpAdd}")
                        if (pillTemplate.speedAdd > 0) add("  速度 +${pillTemplate.speedAdd}")
                        if (pillTemplate.critRateAdd > 0) add("  暴击率 +${GameUtils.formatPercent(pillTemplate.critRateAdd)}")
                        if (pillTemplate.critEffectAdd > 0) add("  暴击效果 +${GameUtils.formatPercent(pillTemplate.critEffectAdd)}")
                    }
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
        "material" -> {
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
        "herb" -> {
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
        "seed" -> {
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
        else -> {
            if (item.description.isNotEmpty()) {
                add("效果:")
                add("  ${item.description}")
            }
        }
    }
}

internal fun getStorageBagItemEffects(item: StorageBagItem): List<String> = buildList {
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

    when (item.itemType) {
        "equipment" -> {
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
        "manual" -> {
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
        "pill" -> {
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
                val isInstantPill = effect.pillCategory == PillCategory.FUNCTIONAL.name ||
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
                when (effect.pillCategory) {
                    PillCategory.FUNCTIONAL.name -> {
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
                    PillCategory.CULTIVATION.name -> {
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
                    PillCategory.BATTLE.name -> {
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
        "material" -> {
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
        "herb" -> {
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
        "seed" -> {
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
        else -> {
            item.effect?.let { effect ->
                add("效果:")
                if (effect.cultivationSpeedPercent > 0) { add("  修炼速度 +${GameUtils.formatPercent(effect.cultivationSpeedPercent)}") }
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
    }
}
