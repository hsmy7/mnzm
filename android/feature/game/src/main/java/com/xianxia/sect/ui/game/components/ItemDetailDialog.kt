package com.xianxia.sect.ui.game.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.SmallScreenDialog
import com.xianxia.sect.ui.components.getRarityName
import com.xianxia.sect.ui.game.tabs.SpiritStoneInfo
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
        is EquipmentStack -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = getEquipmentStackEffects(item)
        }
        is EquipmentInstance -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = getEquipmentEffects(item)
        }
        is ManualStack -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = getManualStackEffects(item)
        }
        is ManualInstance -> {
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
            description = when (item.type) {
                "equipment" -> EquipmentDatabase.getTemplateByName(item.name)?.description ?: item.description
                "manual" -> ManualDatabase.getByName(item.name)?.description ?: item.description
                "pill" -> ItemDatabase.getPillByName(item.name)?.description ?: item.description
                "herb" -> HerbDatabase.getHerbByName(item.name)?.description ?: item.description
                "seed" -> HerbDatabase.getSeedByName(item.name)?.description ?: item.description
                "material" -> com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialByName(item.name)?.description ?: item.description
                else -> item.description
            }
            effects = getMerchantItemEffects(item)
        }
        is StorageBagItem -> {
            name = item.name
            rarity = item.rarity
            description = when (item.itemType) {
                "equipment" -> EquipmentDatabase.getTemplateByName(item.name)?.description ?: ""
                "manual" -> ManualDatabase.getByName(item.name)?.description ?: ""
                "pill" -> ItemDatabase.getPillByName(item.name)?.description ?: ""
                "herb" -> HerbDatabase.getHerbByName(item.name)?.description ?: ""
                "seed" -> HerbDatabase.getSeedByName(item.name)?.description ?: ""
                "material" -> com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialByName(item.name)?.description ?: ""
                else -> ""
            }
            effects = getStorageBagItemEffects(item)
        }
        is SpiritStoneInfo -> {
            name = "灵石"
            rarity = 1
            description = "修仙界的通用货币，可用于购买物品、建造建筑、发放薪酬等"
            effects = listOf("数量: ${item.quantity.toLong()}")
        }
        is StorageBag -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = listOf("可随机获得5-20件同品阶物品", "品阶: ${getRarityName(item.rarity)}")
        }
        is EquipmentDatabase.EquipmentTemplate -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("槽位: ${item.slot.displayName}")
                if (item.physicalAttack > 0) add("物理攻击: +${item.physicalAttack}")
                if (item.magicAttack > 0) add("法术攻击: +${item.magicAttack}")
                if (item.physicalDefense > 0) add("物理防御: +${item.physicalDefense}")
                if (item.magicDefense > 0) add("法术防御: +${item.magicDefense}")
                if (item.speed > 0) add("速度: +${item.speed}")
                if (item.hp > 0) add("生命: +${item.hp}")
                if (item.mp > 0) add("灵力: +${item.mp}")
                if (item.critChance > 0) add("暴击率: +${(item.critChance * 100).toInt()}%")
            }
        }
        is ManualDatabase.ManualTemplate -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("类型: ${item.type.displayName}")
                item.stats.forEach { (key, value) ->
                    val statName = getStatDisplayName(key)
                    if (value > 0) add("$statName: +$value")
                }
                item.skillName?.let { skillName ->
                    add("")
                    add("技能: $skillName")
                    item.skillDescription?.let { add(it) }
                    if (item.skillDamageMultiplier > 0) {
                        add("伤害类型: ${if (item.skillDamageType == "physical") "物理" else "法术"}")
                        add("伤害倍率: ${(item.skillDamageMultiplier * 100).toInt()}%")
                        add("连击次数: ${item.skillHits}")
                    }
                    if (item.skillIsAoe) add("范围: 全体")
                    if (item.skillHealPercent > 0) add("治疗: ${(item.skillHealPercent * 100).toInt()}% ${if (item.skillHealType == "hp") "生命" else "灵力"}")
                    if (item.skillShieldPercent > 0) add("护盾: ${(item.skillShieldPercent * 100).toInt()}% 最大生命")
                    if (item.skillDamageSharePercent > 0) add("伤害分摊: ${(item.skillDamageSharePercent * 100).toInt()}%")
                    if (item.skillTurnAdvancePercent > 0) add("行动提前: ${(item.skillTurnAdvancePercent * 100).toInt()}%")
                    if (item.skillDamageLinkPercent > 0) add("伤害链接: ${(item.skillDamageLinkPercent * 100).toInt()}%")
                    if (item.skillCooldown > 0) add("冷却: ${item.skillCooldown}回合")
                    if (item.skillMpCost > 0) add("灵力消耗: ${item.skillMpCost}")
                    item.skillBuffs.forEach { buff ->
                        val bt = com.xianxia.sect.core.BuffType.entries
                            .find { it.name.equals(buff.type, ignoreCase = true) }
                        if (bt != null) {
                            val buffName = getBuffTypeName(bt)
                            add("$buffName: +${(buff.value * 100).toInt()}% (${buff.duration}回合)")
                        }
                    }
                    if (item.skillBuffs.isEmpty() && item.skillBuffType != null && item.skillBuffValue > 0) {
                        val bt = com.xianxia.sect.core.BuffType.entries
                            .find { it.name.equals(item.skillBuffType, ignoreCase = true) }
                        if (bt != null) {
                            val buffName = getBuffTypeName(bt)
                            val durText = if (item.skillBuffDuration > 0) " (${item.skillBuffDuration}回合)" else ""
                            add("$buffName: +${(item.skillBuffValue * 100).toInt()}%$durText")
                        }
                    }
                }
                if (item.minRealm < 9) {
                    add("")
                    add("需求境界: ${GameConfig.Realm.getName(item.minRealm)}")
                }
            }
        }
        else -> {
            name = "未知物品"
            rarity = 1
            description = ""
            effects = emptyList()
        }
    }
    
    SmallScreenDialog(
        onDismissRequest = onDismiss,
        title = name,
        titleColor = getRarityColor(rarity)
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

        // 装备孕养进度条
        if (item is EquipmentInstance) {
            val nurtureLevel = item.nurtureLevel
            val maxLevel = EquipmentNurtureSystem.getMaxNurtureLevel(item.rarity)
            if (nurtureLevel < maxLevel) {
                val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(nurtureLevel, item.rarity)
                val progressFraction = (item.nurtureProgress / expRequired).toFloat().coerceIn(0f, 1f)

                val prevNurtureLevel = remember { mutableIntStateOf(nurtureLevel) }
                val prevTarget = remember { mutableStateOf(progressFraction) }
                val shouldSnapNurture = nurtureLevel > prevNurtureLevel.intValue || progressFraction < prevTarget.value - 0.5f
                val animatedNurtureProgress by animateFloatAsState(
                    targetValue = progressFraction,
                    animationSpec = if (shouldSnapNurture) snap() else tween(durationMillis = 300),
                    label = "nurtureProgress"
                )
                SideEffect {
                    prevNurtureLevel.intValue = nurtureLevel
                    prevTarget.value = progressFraction
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = GameColors.Background, thickness = 1.dp)
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "孕养进度",
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
                        text = "Lv.$nurtureLevel",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = getRarityColor(item.rarity)
                    )
                    Text(
                        text = "${item.nurtureProgress.toInt()}/${expRequired.toInt()}",
                        fontSize = 10.sp,
                        color = Color.Black
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
                            .fillMaxWidth(fraction = animatedNurtureProgress)
                            .fillMaxHeight()
                            .background(getRarityColor(item.rarity))
                    )
                }
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

        if (extraActions != null) {
            Spacer(modifier = Modifier.height(8.dp))
            extraActions()
        }
    }
}

@Composable
fun LearnedManualDetailDialog(
    manual: ManualInstance,
    proficiencyData: ManualProficiencyData?,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
    extraActions: @Composable (() -> Unit)? = null
) {
    val rarityColor = getRarityColor(manual.rarity)
    
    val proficiency = proficiencyData?.proficiency ?: 0.0
    val masteryLevel = proficiencyData?.masteryLevel ?: 0
    val mastery = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel)
    val thresholds = ManualProficiencySystem.PROFICIENCY_THRESHOLDS
    val maxProficiency = ManualProficiencySystem.MAX_PROFICIENCY

    val currentThreshold = when (mastery) {
        ManualProficiencySystem.MasteryLevel.NOVICE -> 0.0
        ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS -> thresholds[ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS] ?: 1000.0
        ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS -> thresholds[ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS] ?: 10000.0
        ManualProficiencySystem.MasteryLevel.PERFECTION -> thresholds[ManualProficiencySystem.MasteryLevel.PERFECTION] ?: 30000.0
    }
    val nextThreshold = when (mastery) {
        ManualProficiencySystem.MasteryLevel.NOVICE -> thresholds[ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS] ?: 1000.0
        ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS -> thresholds[ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS] ?: 10000.0
        ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS -> thresholds[ManualProficiencySystem.MasteryLevel.PERFECTION] ?: 30000.0
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

    SmallScreenDialog(
        onDismissRequest = onDismiss,
        title = manual.name,
        titleColor = rarityColor
    ) {
        Text(
            text = "${getRarityName(manual.rarity)} · ${manual.type.displayName}",
            fontSize = 11.sp,
            color = Color.Black
        )

        HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

        Text(
            text = manual.description,
            fontSize = 12.sp,
            color = Color.Black
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
                text = "${proficiency.toInt()}/${nextThreshold.toInt()}",
                fontSize = 11.sp,
                color = Color.Black
            )
        }

        val progressTarget = progressInCurrentLevel.toFloat().coerceIn(0f, 1f)
        val prevProgressTarget = remember { mutableStateOf(progressTarget) }
        val shouldSnap = progressTarget < prevProgressTarget.value - 0.5f
        val animatedProgress by animateFloatAsState(
            targetValue = progressTarget,
            animationSpec = if (shouldSnap) snap() else tween(durationMillis = 300),
            label = "manualProficiency"
        )
        SideEffect { prevProgressTarget.value = progressTarget }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFE8E8E8))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = animatedProgress)
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
                color = Color.Black
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
                color = Color.Black
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

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
}

@Composable
@Suppress("DEPRECATION")
private fun ManualStatsContent(
    manual: ManualInstance,
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
                    color = Color.Black
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
                color = Color.Black
            )
        }
        if (skill.skillType == com.xianxia.sect.core.SkillType.SUPPORT) {
            Text(
                text = "类型：辅助",
                fontSize = 10.sp,
                color = Color.Black
            )
        }
        if (skill.targetScope == "team") {
            Text(
                text = "作用范围: 全队",
                fontSize = 10.sp,
                color = Color.Black
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
                color = Color.Black
            )
        }
        if (skill.damageMultiplier > 0 && skill.skillType == com.xianxia.sect.core.SkillType.ATTACK) {
            Text(
                text = "伤害类型：${if (skill.damageType == com.xianxia.sect.core.DamageType.PHYSICAL) "物理" else "法术"}",
                fontSize = 10.sp,
                color = Color.Black
            )
            Text(
                text = "伤害倍率：${(skill.damageMultiplier * 100).toInt()}%",
                fontSize = 10.sp,
                color = Color.Black
            )
        }
        Text(
            text = "连击次数：${skill.hits}",
            fontSize = 10.sp,
            color = Color.Black
        )
        if (skill.cooldown > 0) {
            Text(
                text = "冷却回合：${skill.cooldown}",
                fontSize = 10.sp,
                color = Color.Black
            )
        }
        if (skill.mpCost > 0) {
            Text(
                text = "灵力消耗：${skill.mpCost}",
                fontSize = 10.sp,
                color = Color.Black
            )
        }
        skill.buffs.forEach { (buffType, value, duration) ->
            val buffName = getBuffTypeName(buffType)
            Text(
                text = "$buffName：+${(value * 100).toInt()}% (${duration}回合)",
                fontSize = 10.sp,
                color = Color.Black
            )
        }
        if (skill.buffs.isEmpty() && skill.buffType != null && skill.buffValue > 0) {
            val skillBuffType = skill.buffType
            if (skillBuffType != null) {
            val buffName = getBuffTypeName(skillBuffType)
            val buffDuration = skill.buffDuration
            val durationText = if (buffDuration > 0) " (${buffDuration}回合)" else ""
            Text(
                text = "$buffName：+${(skill.buffValue * 100).toInt()}%$durationText",
                fontSize = 10.sp,
                color = Color.Black
            )
            }
        }
    }
}
