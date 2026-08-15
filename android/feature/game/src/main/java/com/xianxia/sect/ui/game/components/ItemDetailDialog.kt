@file:Suppress("TooManyFunctions") // 拆分聚合:提取的私有辅助函数集中在原文件,文件级复杂度为拆分代价
package com.xianxia.sect.ui.game.components

import com.xianxia.sect.ui.components.rememberChasingProgress
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
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ManualSkill
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.SmallScreenDialog
import com.xianxia.sect.ui.components.getRarityName
import com.xianxia.sect.ui.game.tabs.SpiritStoneInfo
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.theme.getRarityColor
import androidx.lifecycle.compose.collectAsStateWithLifecycle



@Composable
fun ItemDetailDialog(
    item: Any,
    onDismiss: () -> Unit,
    viewModel: GameViewModel? = null,
    extraActions: @Composable (() -> Unit)? = null,
    overlay: @Composable (() -> Unit)? = null
) {
    val info = resolveItemDetailInfo(item = item)

    SmallScreenDialog(
        onDismissRequest = onDismiss,
        title = info.name,
        titleColor = getRarityColor(info.rarity),
        overlay = overlay
    ) {
        ItemDetailDialogContent(
            item = item,
            info = info,
            viewModel = viewModel,
            extraActions = extraActions
        )
    }
}

/** 物品详情数据（ItemDetailDialog 拆分） */
private data class ItemDetailInfo(
    val name: String,
    val rarity: Int,
    val description: String,
    val effects: List<String>
)

/** 物品详情解析（ItemDetailDialog 拆分）：按物品类型解析名称/稀有度/描述/效果列表 */
// 拆分搬移:分支结构与原函数一致
@Suppress("CyclomaticComplexMethod")
private fun resolveItemDetailInfo(item: Any): ItemDetailInfo = when (item) {
    is EquipmentStack -> ItemDetailInfo(
        name = item.name, rarity = item.rarity, description = item.description, effects = getEquipmentStackEffects(item)
    )
    is EquipmentInstance -> ItemDetailInfo(
        name = item.name, rarity = item.rarity, description = item.description, effects = getEquipmentEffects(item)
    )
    is ManualStack -> ItemDetailInfo(
        name = item.name, rarity = item.rarity, description = item.description, effects = getManualStackEffects(item)
    )
    is ManualInstance -> ItemDetailInfo(
        name = item.name, rarity = item.rarity, description = item.description, effects = getManualEffects(item)
    )
    is Pill -> ItemDetailInfo(
        name = item.name, rarity = item.rarity, description = item.description, effects = getPillEffects(item)
    )
    is Material -> ItemDetailInfo(
        name = item.name, rarity = item.rarity, description = item.description, effects = getMaterialEffects(item)
    )
    is Herb -> ItemDetailInfo(
        name = item.name, rarity = item.rarity, description = item.description, effects = getHerbEffects(item)
    )
    is Seed -> ItemDetailInfo(
        name = item.name, rarity = item.rarity, description = item.description, effects = getSeedEffects(item)
    )
    is MerchantItem -> ItemDetailInfo(
        name = item.name, rarity = item.rarity, description = merchantItemDescription(item = item),
        effects = getMerchantItemEffects(item)
    )
    is StorageBagItem -> ItemDetailInfo(
        name = item.name, rarity = item.rarity, description = storageBagItemDescription(item = item),
        effects = getStorageBagItemEffects(item)
    )
    is SpiritStoneInfo -> ItemDetailInfo(
        name = "灵石", rarity = 1, description = "修仙界的通用货币，可用于购买物品、建造建筑、发放薪酬等",
        effects = listOf("数量: ${GameUtils.formatNumber(item.quantity)}")
    )
    is StorageBag -> ItemDetailInfo(
        name = item.name, rarity = item.rarity, description = item.description,
        effects = listOf("可随机获得5-20件同品阶物品", "品阶: ${getRarityName(item.rarity)}")
    )
    is EquipmentDatabase.EquipmentTemplate -> ItemDetailInfo(
        name = item.name, rarity = item.rarity, description = item.description,
        effects = equipmentTemplateEffects(item = item)
    )
    is ManualDatabase.ManualTemplate -> ItemDetailInfo(
        name = item.name, rarity = item.rarity, description = item.description,
        effects = manualTemplateEffects(item = item)
    )
    else -> ItemDetailInfo(
        name = "未知物品", rarity = 1, description = "",
        effects = emptyList()
    )
}

/** 商人物品描述解析（ItemDetailDialog 拆分） */
private fun merchantItemDescription(item: MerchantItem): String = when (item.type) {
    "equipment" -> EquipmentDatabase.getTemplateByName(item.name)?.description ?: item.description
    "manual" -> ManualDatabase.getByName(item.name)?.description ?: item.description
    "pill" -> ItemDatabase.getPillByName(item.name)?.description ?: item.description
    "herb" -> HerbDatabase.getHerbByName(item.name)?.description ?: item.description
    "seed" -> HerbDatabase.getSeedByName(item.name)?.description ?: item.description
    "material" -> com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialByName(item.name)?.description ?: item.description
    else -> item.description
}

/** 储物袋物品描述解析（ItemDetailDialog 拆分） */
private fun storageBagItemDescription(item: StorageBagItem): String = when (item.itemType) {
    "equipment" -> EquipmentDatabase.getTemplateByName(item.name)?.description ?: ""
    "manual" -> ManualDatabase.getByName(item.name)?.description ?: ""
    "pill" -> ItemDatabase.getPillByName(item.name)?.description ?: ""
    "herb" -> HerbDatabase.getHerbByName(item.name)?.description ?: ""
    "seed" -> HerbDatabase.getSeedByName(item.name)?.description ?: ""
    "material" -> com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialByName(item.name)?.description ?: ""
    else -> ""
}

/** 装备模板效果列表（ItemDetailDialog 拆分） */
private fun equipmentTemplateEffects(item: EquipmentDatabase.EquipmentTemplate): List<String> = buildList {
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

/** 功法模板效果列表（ItemDetailDialog 拆分） */
private fun manualTemplateEffects(item: ManualDatabase.ManualTemplate): List<String> = buildList {
    add("类型: ${item.type.displayName}")
    item.stats.forEach { (key, value) ->
        val statName = getStatDisplayName(key)
        if (value > 0) add("$statName: +$value")
    }
    item.skillName?.let { skillName ->
        add("")
        add("技能: $skillName")
        addManualSkillInfo(item)
    }
    if (item.minRealm < 9) {
        add("")
        add("需求境界: ${GameConfig.Realm.getName(item.minRealm)}")
    }
}

/** 物品详情内容区（ItemDetailDialog 拆分） */
@Composable
private fun ItemDetailDialogContent(
    item: Any,
    info: ItemDetailInfo,
    viewModel: GameViewModel?,
    extraActions: (@Composable () -> Unit)?
) {
    Text(
        text = getRarityName(info.rarity),
        fontSize = 11.sp,
        color = GameColors.TextSecondary
    )
    // 关注键：viewModel 非空且物品可关注（灵石/储物袋不可关注）时返回非空
    val watchKey = remember(item) { watchKeyOf(item) }
    val watchedKeys = viewModel?.watchedItemIds?.collectAsStateWithLifecycle()?.value
        ?: emptySet()
    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider(color = GameColors.Background, thickness = 1.dp)
    Spacer(modifier = Modifier.height(8.dp))

    ItemDetailEffectsList(effects = info.effects)

    // 装备孕养进度条
    if (item is EquipmentInstance) {
        ItemDetailNurtureProgress(item = item)
    }

    if (info.description.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = GameColors.Background, thickness = 1.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = info.description,
            fontSize = 11.sp,
            color = GameColors.TextSecondary
        )
    }

    // 底部操作区：关注按钮与其他操作按钮并列（关注按钮移至此位置）
    if (extraActions != null || (watchKey != null && viewModel != null)) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (watchKey != null && viewModel != null) {
                WatchItemButton(
                    watchKey = watchKey,
                    watchedKeys = watchedKeys,
                    onToggleWatch = { key -> viewModel.toggleWatchItem(key) }
                )
            }
            extraActions?.invoke()
        }
    }
}

/** 效果列表（ItemDetailDialog 拆分） */
@Composable
private fun ItemDetailEffectsList(effects: List<String>) {
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
}

/** 装备孕养进度条（ItemDetailDialog 拆分） */
@Composable
private fun ItemDetailNurtureProgress(item: EquipmentInstance) {
    val nurtureLevel = item.nurtureLevel
    val maxLevel = EquipmentNurtureSystem.getMaxNurtureLevel(item.rarity)
    if (nurtureLevel < maxLevel) {
        val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(nurtureLevel, item.rarity)
        val progressFraction = (item.nurtureProgress / expRequired).toFloat().coerceIn(0f, 1f)

        val animatedNurtureProgress by rememberChasingProgress(
            target = progressFraction
        )

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

    val display = ManualProficiencyDisplay(
        mastery = mastery,
        proficiency = proficiency,
        nextThreshold = nextThreshold,
        progressInCurrentLevel = progressInCurrentLevel
    )

    SmallScreenDialog(
        onDismissRequest = onDismiss,
        title = manual.name,
        titleColor = rarityColor
    ) {
        ManualDetailContent(
            manual = manual,
            display = display,
            rarityColor = rarityColor,
            onForget = onForget,
            extraActions = extraActions
        )
    }
}

/** 功法熟练度展示数据（LearnedManualDetailDialog 拆分） */
private data class ManualProficiencyDisplay(
    val mastery: ManualProficiencySystem.MasteryLevel,
    val proficiency: Double,
    val nextThreshold: Double,
    val progressInCurrentLevel: Double
)

/** 已学功法详情内容（LearnedManualDetailDialog 拆分） */
@Composable
private fun ManualDetailContent(
    manual: ManualInstance,
    display: ManualProficiencyDisplay,
    rarityColor: Color,
    onForget: () -> Unit,
    extraActions: (@Composable () -> Unit)?
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

    ManualProficiencyHeader(
        mastery = display.mastery,
        proficiency = display.proficiency,
        nextThreshold = display.nextThreshold,
        rarityColor = rarityColor
    )
    ManualProficiencyProgress(
        progressInCurrentLevel = display.progressInCurrentLevel,
        rarityColor = rarityColor
    )
    ManualNextLevelHint(
        mastery = display.mastery,
        proficiency = display.proficiency,
        nextThreshold = display.nextThreshold
    )

    HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

    ManualStatsContent(
        manual = manual,
        bonusMultiplier = display.mastery.bonus,
        rarityColor = rarityColor
    )

    if (manual.minRealm < 9) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "需求境界：${GameConfig.Realm.getName(manual.minRealm)}",
            fontSize = 10.sp,
            color = Color.Black
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    ManualForgetActionRow(
        onForget = onForget,
        extraActions = extraActions
    )
}

/** 熟练度标题与数值行（LearnedManualDetailDialog 拆分） */
@Composable
private fun ManualProficiencyHeader(
    mastery: ManualProficiencySystem.MasteryLevel,
    proficiency: Double,
    nextThreshold: Double,
    rarityColor: Color
) {
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
}

/** 熟练度进度条（LearnedManualDetailDialog 拆分） */
@Composable
private fun ManualProficiencyProgress(
    progressInCurrentLevel: Double,
    rarityColor: Color
) {
    val progressTarget = progressInCurrentLevel.toFloat().coerceIn(0f, 1f)
    val animatedProgress by rememberChasingProgress(
        target = progressTarget
    )

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
}

/** 距下一熟练度等级提示（LearnedManualDetailDialog 拆分） */
@Composable
private fun ManualNextLevelHint(
    mastery: ManualProficiencySystem.MasteryLevel,
    proficiency: Double,
    nextThreshold: Double
) {
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
            color = GameColors.Gold
        )
    }
}

/** 已学功法遗忘操作行（LearnedManualDetailDialog 拆分） */
@Composable
private fun ManualForgetActionRow(
    onForget: () -> Unit,
    extraActions: (@Composable () -> Unit)?
) {
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
                    color = GameColors.Success
                )
            }
        }
    }

    manual.skill?.let { skill ->
        ManualSkillSection(
            skill = skill,
            rarityColor = rarityColor
        )
    }
}

/** 功法附带技能区（ManualStatsContent 拆分） */
@Composable
@Suppress("DEPRECATION")
private fun ManualSkillSection(
    skill: ManualSkill,
    rarityColor: Color
) {
    Spacer(modifier = Modifier.height(4.dp))
    ManualSkillBaseInfo(
        skill = skill,
        rarityColor = rarityColor
    )
    ManualSkillHealInfo(skill = skill)
    ManualSkillMiscInfo(skill = skill)
    ManualSkillBuffInfo(skill = skill)
}

/** 技能基础信息（ManualStatsContent 拆分）：名称/描述/类型/目标/范围/伤害 */
@Composable
@Suppress("DEPRECATION")
private fun ManualSkillBaseInfo(
    skill: ManualSkill,
    rarityColor: Color
) {
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
    if (skill.targetScope.isNotEmpty()) {
        Text(
            text = "作用目标：${getTargetScopeName(skill.targetScope)}",
            fontSize = 10.sp,
            color = Color.Black
        )
    }
    if (skill.isAoe) {
        Text(
            text = "范围：全体",
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
}

/** 技能治疗信息（ManualStatsContent 拆分） */
@Composable
@Suppress("DEPRECATION")
private fun ManualSkillHealInfo(skill: ManualSkill) {
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
    if (skill.healFixed > 0) {
        val healTypeName = when (skill.healType) {
            com.xianxia.sect.core.HealType.HP -> "生命"
            com.xianxia.sect.core.HealType.MP -> "灵力"
        }
        Text(
            text = "固定治疗：+${skill.healFixed} $healTypeName",
            fontSize = 10.sp,
            color = Color.Black
        )
    }
}

/** 技能杂项数值（ManualStatsContent 拆分）：护盾/行动提前/分摊/链接/连击/冷却/消耗 */
@Composable
@Suppress("DEPRECATION")
private fun ManualSkillMiscInfo(skill: ManualSkill) {
    if (skill.shieldPercent > 0) {
        Text(
            text = "护盾：${(skill.shieldPercent * 100).toInt()}% 最大生命",
            fontSize = 10.sp,
            color = Color.Black
        )
    }
    if (skill.turnAdvancePercent > 0) {
        Text(
            text = "行动提前：${(skill.turnAdvancePercent * 100).toInt()}%",
            fontSize = 10.sp,
            color = Color.Black
        )
    }
    if (skill.damageSharePercent > 0) {
        Text(
            text = "伤害分摊：${(skill.damageSharePercent * 100).toInt()}%",
            fontSize = 10.sp,
            color = Color.Black
        )
    }
    if (skill.damageLinkPercent > 0) {
        Text(
            text = "伤害链接：${(skill.damageLinkPercent * 100).toInt()}%",
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
}

/** 技能增益信息（ManualStatsContent 拆分） */
@Composable
@Suppress("DEPRECATION")
private fun ManualSkillBuffInfo(skill: ManualSkill) {
    skill.buffs.forEach { (buffType, value, duration) ->
        Text(
            text = "${formatBuffLine(buffType, value, duration)}",
            fontSize = 10.sp,
            color = Color.Black
        )
    }
    if (skill.buffs.isEmpty() && skill.buffType != null && skill.buffValue > 0) {
        val skillBuffType = skill.buffType
        if (skillBuffType != null) {
            Text(
                text = "${formatBuffLine(skillBuffType, skill.buffValue, skill.buffDuration)}",
                fontSize = 10.sp,
                color = Color.Black
            )
        }
    }
}
