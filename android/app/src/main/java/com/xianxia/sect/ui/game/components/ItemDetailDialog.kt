package com.xianxia.sect.ui.game.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.getRarityName
import com.xianxia.sect.ui.theme.getRarityColor
import java.util.Locale

@Composable
fun ItemDetailDialog(
    item: Any,
    onDismiss: () -> Unit,
    extraActions: @Composable (() -> Unit)? = null
) {
    val name: String
    val rarity: Int
    val description: String
    val effects: List<String>
    
    when (item) {
        is Equipment -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = getEquipmentEffects(item)
        }
        is Manual -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = getManualEffects(item)
        }
        is Pill -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = getPillEffects(item)
        }
        is Material -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = getMaterialEffects(item)
        }
        is Herb -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = getHerbEffects(item)
        }
        is Seed -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = getSeedEffects(item)
        }
        is MerchantItem -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = getMerchantItemEffects(item)
        }
        is StorageBagItem -> {
            name = item.name
            rarity = item.rarity
            description = ""
            effects = getStorageBagItemEffects(item)
        }
        else -> {
            name = "未知物品"
            rarity = 1
            description = ""
            effects = emptyList()
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = getRarityColor(rarity)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = getRarityName(rarity),
                    fontSize = 11.sp,
                    color = GameColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = GameColors.Background, thickness = 1.dp)
                Spacer(modifier = Modifier.height(8.dp))
                
                effects.forEach { effect ->
                    if (effect.isEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                    } else {
                        Text(
                            text = effect,
                            fontSize = 12.sp,
                            color = if (effect.startsWith("属性") || effect.startsWith("效果") || effect.startsWith("技能")) {
                                GameColors.Primary
                            } else {
                                GameColors.TextPrimary
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                
                if (description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = GameColors.Background, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = description,
                        fontSize = 11.sp,
                        color = GameColors.TextSecondary
                    )
                }
            }
        },
        confirmButton = {
            if (extraActions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    extraActions()
                    GameButton(
                        text = "关闭",
                        onClick = onDismiss
                    )
                }
            } else {
                GameButton(
                    text = "关闭",
                    onClick = onDismiss
                )
            }
        }
    )
}

@Composable
fun LearnedManualDetailDialog(
    manual: Manual,
    proficiencyData: ManualProficiencyData?,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
    extraActions: @Composable (() -> Unit)? = null
) {
    val rarityColor = getRarityColor(manual.rarity)
    
    val proficiency = proficiencyData?.proficiency ?: 0.0
    val masteryLevel = proficiencyData?.masteryLevel ?: 0
    val mastery = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel)
    val thresholds = ManualProficiencySystem.getProficiencyThresholds(manual.rarity)
    val maxProficiency = ManualProficiencySystem.getMaxProficiency(manual.rarity)

    val currentThreshold = when (mastery) {
        ManualProficiencySystem.MasteryLevel.NOVICE -> 0.0
        ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS -> thresholds[ManualProficiencySystem.MasteryLevel.NOVICE] ?: 0.0
        ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS -> thresholds[ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS] ?: 100.0
        ManualProficiencySystem.MasteryLevel.PERFECTION -> thresholds[ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS] ?: 200.0
    }
    val nextThreshold = when (mastery) {
        ManualProficiencySystem.MasteryLevel.NOVICE -> thresholds[ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS] ?: 100.0
        ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS -> thresholds[ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS] ?: 200.0
        ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS -> thresholds[ManualProficiencySystem.MasteryLevel.PERFECTION] ?: 300.0
        ManualProficiencySystem.MasteryLevel.PERFECTION -> maxProficiency
    }

    val progressInCurrentLevel = if (mastery == ManualProficiencySystem.MasteryLevel.PERFECTION) {
        1.0
    } else {
        val denominator = nextThreshold - currentThreshold
        if (denominator > 0) {
            ((proficiency - currentThreshold) / denominator).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = manual.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = rarityColor
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GameColors.CardBackground)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "${getRarityName(manual.rarity)} · ${manual.type.displayName}",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )

                HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

                Text(
                    text = manual.description,
                    fontSize = 12.sp,
                    color = Color(0xFF333333)
                )

                HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

                Text(
                    text = "熟练度",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${mastery.displayName}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = rarityColor
                    )
                    Text(
                        text = "${proficiency.toInt()}/${maxProficiency.toInt()}",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFE8E8E8))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = progressInCurrentLevel.toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(rarityColor)
                    )
                }

                if (mastery != ManualProficiencySystem.MasteryLevel.PERFECTION) {
                    val nextLevelName = when (mastery) {
                        ManualProficiencySystem.MasteryLevel.NOVICE -> "小成"
                        ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS -> "大成"
                        ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS -> "圆满"
                        ManualProficiencySystem.MasteryLevel.PERFECTION -> "已圆满"
                    }
                    Text(
                        text = "距离${nextLevelName}还需 ${(nextThreshold - proficiency).toInt()} 熟练度",
                        fontSize = 10.sp,
                        color = Color(0xFF999999)
                    )
                } else {
                    Text(
                        text = "已达圆满境界",
                        fontSize = 10.sp,
                        color = Color(0xFFFFD700)
                    )
                }

                HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

                ManualStatsContent(manual, mastery.bonus, rarityColor)

                if (manual.minRealm < 9) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "需求境界：${GameConfig.Realm.getName(manual.minRealm)}",
                        fontSize = 10.sp,
                        color = Color(0xFF999999)
                    )
                }
            }
        },
        confirmButton = {
            if (extraActions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    extraActions()
                    GameButton(
                        text = "遗忘",
                        onClick = onForget
                    )
                }
            } else {
                GameButton(
                    text = "遗忘",
                    onClick = onForget
                )
            }
        }
    )
}

@Composable
private fun ManualStatsContent(
    manual: Manual,
    bonusMultiplier: Double,
    rarityColor: Color
) {
    Text(
        text = "加成效果",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black
    )

    val stats = manual.stats
    if (stats.isNotEmpty()) {
        stats.forEach { (key, value) ->
            val statName = getStatDisplayName(key)
            val finalValue = (value * bonusMultiplier).toInt()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = statName,
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
                Text(
                    text = if (key.contains("Percent")) "+$finalValue%" else "+$finalValue",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )
            }
        }
    }

    manual.skill?.let { skill ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "附带技能",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Text(
            text = skill.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = rarityColor
        )
        if (skill.description.isNotEmpty()) {
            Text(
                text = skill.description,
                fontSize = 10.sp,
                color = Color(0xFF333333)
            )
        }
        if (skill.skillType == com.xianxia.sect.core.SkillType.SUPPORT) {
            Text(
                text = "类型：辅助",
                fontSize = 10.sp,
                color = Color(0xFF666666)
            )
        }
        if (skill.healPercent > 0) {
            val healTypeName = when (skill.healType) {
                com.xianxia.sect.core.HealType.HP -> "生命"
                com.xianxia.sect.core.HealType.MP -> "灵力"
            }
            Text(
                text = "治疗：${(skill.healPercent * 100).toInt()}% $healTypeName",
                fontSize = 10.sp,
                color = Color(0xFF666666)
            )
        }
        if (skill.damageMultiplier > 0 && skill.skillType == com.xianxia.sect.core.SkillType.ATTACK) {
            Text(
                text = "伤害类型：${if (skill.damageType == com.xianxia.sect.core.DamageType.PHYSICAL) "物理" else "法术"}",
                fontSize = 10.sp,
                color = Color(0xFF666666)
            )
            Text(
                text = "伤害倍率：${(skill.damageMultiplier * 100).toInt()}%",
                fontSize = 10.sp,
                color = Color(0xFF666666)
            )
        }
        Text(
            text = "连击次数：${skill.hits}",
            fontSize = 10.sp,
            color = Color(0xFF666666)
        )
        if (skill.cooldown > 0) {
            Text(
                text = "冷却回合：${skill.cooldown}",
                fontSize = 10.sp,
                color = Color(0xFF666666)
            )
        }
        if (skill.mpCost > 0) {
            Text(
                text = "灵力消耗：${skill.mpCost}",
                fontSize = 10.sp,
                color = Color(0xFF666666)
            )
        }
        skill.buffs.forEach { (buffType, value, duration) ->
            val buffName = getBuffTypeName(buffType)
            Text(
                text = "$buffName：+${(value * 100).toInt()}% (${duration}回合)",
                fontSize = 10.sp,
                color = Color(0xFF666666)
            )
        }
        if (skill.buffs.isEmpty() && skill.buffType != null && skill.buffValue > 0) {
            val buffName = getBuffTypeName(skill.buffType)
            val durationText = if (skill.buffDuration > 0) " (${skill.buffDuration}回合)" else ""
            Text(
                text = "$buffName：+${(skill.buffValue * 100).toInt()}%$durationText",
                fontSize = 10.sp,
                color = Color(0xFF666666)
            )
        }
    }
}

private fun getEquipmentEffects(item: Equipment): List<String> = buildList {
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
    if (item.critChance > 0) add("  暴击率 +${String.format(Locale.getDefault(), "%.1f", item.critChance * 100)}%")
}

private fun getManualEffects(item: Manual): List<String> = buildList {
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

private fun getPillEffects(item: Pill): List<String> = buildList {
    add("类型: ${item.category.displayName}")
    add("品级: ${item.grade.displayName}")
    add("数量: ${item.quantity}")
    if (item.minRealm < 9) {
        add("需求境界: ${GameConfig.Realm.getName(item.minRealm)}")
    }
    add("")
    add("效果:")
    when (item.category) {
        PillCategory.FUNCTIONAL -> {
            if (item.breakthroughChance > 0) {
                add("  突破概率 +${String.format(Locale.getDefault(), "%.1f", item.breakthroughChance * 100)}%")
            }
            if (item.targetRealm > 0) {
                add("  目标境界: ${GameConfig.Realm.getName(item.targetRealm)}")
            }
            if (item.isAscension) {
                add("  可用于渡劫")
            }
        }
        PillCategory.CULTIVATION -> {
        }
        PillCategory.BATTLE -> {
        }
    }
    if (item.duration > 0 && item.category != PillCategory.BATTLE) {
        add("  持续 ${item.duration} 月")
    }
}

private fun getMaterialEffects(item: Material): List<String> = buildList {
    add("类型: ${item.category.displayName}")
    add("数量: ${item.quantity}")

    val forgeRecipes = com.xianxia.sect.core.data.ForgeRecipeDatabase.getRecipesByMaterial(item.id)
    if (forgeRecipes.isNotEmpty()) {
        add("")
        add("可用于炼器:")
        forgeRecipes.take(5).forEach { recipe ->
            add("  · ${recipe.name}")
        }
        if (forgeRecipes.size > 5) {
            add("  · 等${forgeRecipes.size}种装备")
        }
    }
}

private fun getHerbEffects(item: Herb): List<String> = buildList {
    if (item.category.isNotEmpty()) {
        add("类型: ${item.category}")
    }
    add("数量: ${item.quantity}")

    val pillRecipes = com.xianxia.sect.core.data.PillRecipeDatabase.getRecipesByHerb(item.id)
    if (pillRecipes.isNotEmpty()) {
        add("")
        add("可用于炼丹:")
        pillRecipes.take(5).forEach { recipe ->
            add("  · ${recipe.name}")
        }
        if (pillRecipes.size > 5) {
            add("  · 等${pillRecipes.size}种丹药")
        }
    }
}

private fun getSeedEffects(item: Seed): List<String> = buildList {
    add("类型: 种子")
    add("生长时间: ${item.growTime}个月")
    add("收获数量: ${item.yield}")
    add("数量: ${item.quantity}")

    val herb = com.xianxia.sect.core.data.HerbDatabase.getHerbFromSeedName(item.name)
        ?: com.xianxia.sect.core.data.HerbDatabase.getHerbFromSeed(item.id)
    if (herb != null) {
        add("")
        add("长成后:")
        add("  · ${herb.name}")
        add("  · ${herb.description}")

        val pillRecipes = com.xianxia.sect.core.data.PillRecipeDatabase.getRecipesByHerb(herb.id)
        if (pillRecipes.isNotEmpty()) {
            add("")
            add("可用于炼丹:")
            pillRecipes.take(3).forEach { recipe ->
                add("  · ${recipe.name}")
            }
            if (pillRecipes.size > 3) {
                add("  · 等${pillRecipes.size}种丹药")
            }
        }
    } else {
        val herbName = com.xianxia.sect.core.data.HerbDatabase.getHerbNameFromSeedName(item.name)
        add("")
        add("长成后: $herbName")
    }
}

fun getBuffTypeName(buffType: com.xianxia.sect.core.BuffType): String = buffType.displayName

private fun getStatDisplayName(key: String): String = when (key) {
    "cultivationSpeedPercent" -> "修炼速度"
    "physicalAttack" -> "物理攻击"
    "magicAttack" -> "法术攻击"
    "physicalDefense" -> "物理防御"
    "magicDefense" -> "法术防御"
    "hp" -> "生命"
    "mp" -> "灵力"
    "speed" -> "速度"
    "critRate" -> "暴击率"
    else -> key
}

private fun getMerchantItemEffects(item: MerchantItem): List<String> = buildList {
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

    val template = EquipmentDatabase.getTemplateByName(item.name)
    if (template != null) {
        add("属性:")
        if (template.physicalAttack > 0) add("  物理攻击 +${template.physicalAttack}")
        if (template.magicAttack > 0) add("  法术攻击 +${template.magicAttack}")
        if (template.physicalDefense > 0) add("  物理防御 +${template.physicalDefense}")
        if (template.magicDefense > 0) add("  法术防御 +${template.magicDefense}")
        if (template.hp > 0) add("  生命 +${template.hp}")
        if (template.mp > 0) add("  灵力 +${template.mp}")
        if (template.speed > 0) add("  速度 +${template.speed}")
        if (template.critChance > 0) add("  暴击率 +${String.format(Locale.getDefault(), "%.1f%%", template.critChance * 100)}")
        return@buildList
    }

    val manualTemplate = ManualDatabase.getByName(item.name)
    if (manualTemplate != null) {
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
            val sDesc = manualTemplate.skillDescription
            if (!sDesc.isNullOrEmpty()) {
                add("  $sDesc")
            }
        }
        return@buildList
    }

    if (item.description.isNotEmpty()) {
        add("效果:")
        add("  ${item.description}")
    }
}

private fun getStorageBagItemEffects(item: StorageBagItem): List<String> = buildList {
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

    item.effect?.let { effect ->
        add("效果:")
        if (effect.cultivationSpeedPercent > 0) add("  修炼速度 +${GameUtils.formatPercent(effect.cultivationSpeedPercent)}")
        if (effect.cultivationAdd > 0) add("  修为 +${effect.cultivationAdd}")
        if (effect.skillExpAdd > 0) add("  功法熟练度 +${effect.skillExpAdd}")
        if (effect.breakthroughChance > 0) add("  突破概率 +${GameUtils.formatPercent(effect.breakthroughChance)}")
        if (effect.targetRealm > 0) add("  目标境界: ${GameConfig.Realm.getName(effect.targetRealm)}")
        if (effect.healMaxHpPercent > 0) add("  恢复生命 ${GameUtils.formatPercent(effect.healMaxHpPercent)} 最大生命")
        if (effect.hpAdd > 0) add("  生命 +${effect.hpAdd}")
        if (effect.mpAdd > 0) add("  灵力 +${effect.mpAdd}")
        if (effect.mpRecoverMaxMpPercent > 0) add("  恢复灵力 ${GameUtils.formatPercent(effect.mpRecoverMaxMpPercent)} 最大灵力")
        if (effect.extendLife > 0) add("  延寿 ${effect.extendLife} 年")
        if (effect.physicalAttackAdd > 0) add("  物理攻击 +${effect.physicalAttackAdd}")
        if (effect.magicAttackAdd > 0) add("  法术攻击 +${effect.magicAttackAdd}")
        if (effect.physicalDefenseAdd > 0) add("  物理防御 +${effect.physicalDefenseAdd}")
        if (effect.magicDefenseAdd > 0) add("  法术防御 +${effect.magicDefenseAdd}")
        if (effect.speedAdd > 0) add("  速度 +${effect.speedAdd}")
        if (effect.revive) add("  可复活弟子")
        if (effect.clearAll) add("  清除所有负面状态")
        if (effect.duration > 0) add("  持续 ${effect.duration} 月")
    }
}
