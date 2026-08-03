package com.xianxia.sect.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.Talent
import com.xianxia.sect.core.model.Physique
import com.xianxia.sect.core.model.Affix
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.theme.GameColors
import java.util.Locale

object DiscipleCardStyles {
    val smallShape: Shape = RoundedCornerShape(8.dp)
    val mediumShape: Shape = RoundedCornerShape(12.dp)
    val largeShape: Shape = RoundedCornerShape(16.dp)
    val cardPadding = 12.dp
}

fun Modifier.discipleCardBorder(
    shape: Shape = DiscipleCardStyles.mediumShape,
    background: Color = Color.White
): Modifier = this
    .clip(shape)
    .background(background)
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFFE0E0E0),
                Color(0xFFBDBDBD)
            )
        ),
        shape = shape
    )

object DiscipleAttrDefaults {
    val Color = androidx.compose.ui.graphics.Color.Black
    val FontSize = 11.sp
}

/**
 * 格式化弟子年龄显示文本。
 *
 * @param age 弟子年龄
 * @return 用于 UI 显示的年龄文本，如 "18岁"
 */
fun formatDiscipleAge(age: Int): String = "${age}岁"

@Composable
fun DiscipleAttrText(
    name: String,
    value: Any,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = DiscipleAttrDefaults.FontSize,
    color: Color = DiscipleAttrDefaults.Color,
    fontWeight: FontWeight? = null
) {
    Text(
        text = "$name: $value",
        fontSize = fontSize,
        color = color,
        fontWeight = fontWeight,
        modifier = modifier
    )
}

@Composable
fun FollowedTag(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFFFFD700))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "已关注",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/**
 * 统一的弟子卡片，左侧半身像 + 右侧多行信息。
 * 用于所有弟子列表和选择界面。
 * actions: 替换第一行右侧（状态/选中标记）
 * customAttributes: 替换第三行（悟性/忠诚）
 * extraAttributes: 追加在第三行后面
 */
@Composable
fun PortraitDiscipleCard(
    disciple: DiscipleAggregate,
    isSelected: Boolean = false,
    isCurrent: Boolean = false,
    showStatus: Boolean = true,
    extraAttributes: List<Pair<String, Int>> = emptyList(),
    customAttributes: @Composable (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) GameColors.Gold else Color(0xFFE0E0E0)
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val statusText = disciple.status.displayName

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) GameColors.Gold.copy(alpha = 0.08f) else Color.White)
            .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
            .clickable { onClick() }
    ) {
        Image(
            painter = painterResource(id = R.drawable.bg_horizontal),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(DiscipleCardStyles.cardPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(48.dp)
            ) {
                val resId = remember(disciple.portraitRes) {
                    val preloaded = PortraitPool.getResourceId(disciple.portraitRes)
                    if (preloaded != 0) preloaded
                    else (SpriteResRegistry.resolve("disciple_portrait") ?: 0)
                }
                if (resId != 0) {
                    Image(
                        painter = painterResource(id = resId),
                        contentDescription = null,
                        modifier = Modifier.width(44.dp).height(56.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Text(
                    text = disciple.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = disciple.genderName, fontSize = 12.sp, color = Color.Black)
                        Text(
                            text = formatDiscipleAge(disciple.age),
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                        // 当有自定义 actions 时，状态文字移至年龄右侧（避免与按钮挤在同一侧）
                        if (actions != null && showStatus) {
                            Text(text = statusText, fontSize = 12.sp, color = Color.Black, maxLines = 1)
                        }
                        if (disciple.isFollowed) FollowedTag()
                        if (isCurrent) {
                            Text(text = "当前", fontSize = 10.sp, color = Color(0xFFE74C3C))
                        }
                    }
                    if (actions != null) {
                        actions()
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (showStatus) {
                                Text(
                                    text = statusText,
                                    fontSize = 12.sp,
                                    color = Color.Black,
                                    maxLines = 1
                                )
                            }
                            if (isSelected) {
                                Text(
                                    text = "✓",
                                    fontSize = 13.sp,
                                    color = GameColors.GoldDark,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val spiritRootColor = try {
                        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (_: Exception) { Color.Black }
                    Text(
                        text = disciple.realmName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                    Text(
                        text = disciple.spiritRootName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = spiritRootColor,
                        maxLines = 1
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (customAttributes != null) {
                        customAttributes()
                    } else {
                        DiscipleAttrText("悟性", disciple.comprehension)
                        DiscipleAttrText("忠诚", disciple.loyalty)
                    }
                    extraAttributes.forEach { (name, value) ->
                        DiscipleAttrText(name, value)
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotContent(
    name: String,
    realmName: String,
    portraitRes: String,
    isAlive: Boolean = true
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(4.dp)
    ) {
        Text(
            text = name,
            fontSize = 9.sp,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isAlive) {
            val isBeastPortrait = portraitRes.startsWith("beast_")
            val resId = remember(portraitRes) {
                val id = if (isBeastPortrait) {
                    val suffix = portraitRes.removePrefix("beast_")
                    val index = suffix.toIntOrNull() ?: -1
                    if (index in 0..7) beastSpriteRes(index) ?: 0
                    else if (index > 0) index
                    else 0
                } else PortraitPool.getResourceId(portraitRes)
                if (id != 0) id else (SpriteResRegistry.resolve("disciple_portrait") ?: 0)
            }
            if (resId != 0) {
                Image(
                    painter = painterResource(id = resId),
                    contentDescription = null,
                    modifier = Modifier.width(40.dp).height(48.dp),
                    contentScale = ContentScale.Fit
                )
            }
        } else {
            // 阵亡：仅覆盖精灵图区域，名称和境界保持显示
            Box(
                modifier = Modifier.width(40.dp).height(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "死亡",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF44336),
                    maxLines = 1
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = realmName,
            fontSize = 10.sp,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ==================== 统一弟子槽位 ====================

/**
 * 统一的弟子槽位组件。
 * 所有弟子槽位（生产、建筑、战斗等）共用此组件。
 *
 * 布局：境界 → 分割线 → 精灵图 → 分割线 → 名称
 * 分割线样式与 DiscipleDetailScreen 标签页一致。
 */
@Composable
fun DiscipleSlot(
    disciple: DiscipleAggregate?,
    modifier: Modifier = Modifier,
    borderColor: Color = GameColors.Border,
    showActions: Boolean = false,
    onSlotClick: () -> Unit = {},
    onEmptySlotClick: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
    onSwap: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // 槽位本体
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(88.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (disciple != null) Color.White else GameColors.PageBackground)
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .clickable {
                    if (disciple != null) onSlotClick() else onEmptySlotClick()
                },
            contentAlignment = Alignment.Center
        ) {
            if (disciple != null) {
                val dividerColor = Color(0xFF757575)
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 境界（顶部）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = disciple.realmName,
                            fontSize = 9.sp,
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // 分割线
                    HorizontalDivider(thickness = 1.dp, color = dividerColor)
                    // 精灵图（中部）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        if (disciple.isAlive) {
                            val portraitRes = disciple.portraitRes
                            val isBeastPortrait = portraitRes.startsWith("beast_")
                            val resId = remember(portraitRes) {
                                val id = if (isBeastPortrait) {
                                    val suffix = portraitRes.removePrefix("beast_")
                                    val index = suffix.toIntOrNull() ?: -1
                                    if (index in 0..7) beastSpriteRes(index) ?: 0
                                    else if (index > 0) index
                                    else 0
                                } else PortraitPool.getResourceId(portraitRes)
                                if (id != 0) id else (SpriteResRegistry.resolve("disciple_portrait") ?: 0)
                            }
                            if (resId != 0) {
                                Image(
                                    painter = painterResource(id = resId),
                                    contentDescription = null,
                                    modifier = Modifier.width(40.dp).height(48.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        } else {
                            Text(
                                text = "死亡",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF44336),
                                maxLines = 1
                            )
                        }
                    }
                    // 分割线
                    HorizontalDivider(thickness = 1.dp, color = dividerColor)
                    // 名称（底部）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = disciple.name,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }

        // 操作按钮（可选）
        if (showActions && disciple != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onDismiss != null) {
                    Text(
                        text = "卸任",
                        fontSize = 9.sp,
                        color = Color(0xFFE53935),
                        modifier = Modifier.clickable { onDismiss() }
                    )
                }
                if (onSwap != null) {
                    Text(
                        text = "更换",
                        fontSize = 9.sp,
                        color = Color.Black,
                        modifier = Modifier.clickable { onSwap() }
                    )
                }
            }
        }
    }
}


@Composable
internal fun BattleParticipantSlot(
    name: String,
    realmName: String,
    hp: Int,
    maxHp: Int,
    isAlive: Boolean,
    portraitRes: String = "",
    showHpBar: Boolean = true
) {
    val hpPercent = maxHp.takeIf { it > 0 }?.let {
        (hp.toFloat() / it.toFloat()).coerceIn(0f, 1f)
    } ?: 0f

    val hpColor = when {
        hpPercent > 0.6f -> Color(0xFF4CAF50)
        hpPercent > 0.3f -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showHpBar) {
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFE0E0E0))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(hpPercent)
                        .background(hpColor)
                )
            }

            Spacer(modifier = Modifier.height(2.dp))
        }

        Box(
            modifier = Modifier
                .width(52.dp)
                .height(88.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isAlive) Color.White else Color(0xFFEEEEEE))
                .border(1.dp, if (isAlive) Color(0xFFE0E0E0) else Color(0xFFCCCCCC), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            SlotContent(
                name = name,
                realmName = realmName,
                portraitRes = portraitRes,
                isAlive = isAlive
            )
        }
    }
}

fun getTalentRarityColor(rarity: Int): Color = when (rarity) {
    1 -> GameColors.TalentGradeLow
    2 -> GameColors.TalentGradeMid
    3 -> GameColors.TalentGradeHigh
    else -> GameColors.TalentNegative
}

@Composable
fun TalentDetailDialog(talent: Talent, onDismiss: () -> Unit) {
    val rarityColor = getTalentRarityColor(talent.rarity)

    SmallScreenDialog(
        onDismissRequest = onDismiss,
        title = talent.name,
        titleColor = rarityColor
    ) {
        Text(
            text = "天赋效果",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        if (talent.effects.isEmpty() && talent.positionBonus == null) {
            Text(
                text = talent.description,
                fontSize = 12.sp,
                color = Color.Black
            )
        } else {
            talent.effects.forEach { (key, value) ->
                val effectText = formatTalentEffectText(key, value)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "•",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    Text(
                        text = effectText,
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }
            talent.positionBonus?.let { bonus ->
                val slotName = formatSlotTypeName(bonus.slotType)
                val percent = formatPercentValue(bonus.effectBonus)
                val sign = if (bonus.effectBonus >= 0) "+" else "-"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "•", fontSize = 12.sp, color = Color.Black)
                    Text(
                        text = "担任职务($slotName)时职能效果 $sign$percent",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

fun formatTalentEffectText(key: String, value: Any): String {
    val keyName = formatEffectKey(key)
    val doubleValue = value.toString().toDoubleOrNull() ?: 0.0

    if (key == "winBattleRandomAttrPlus") {
        val point = kotlin.math.abs(doubleValue).toInt().coerceAtLeast(1)
        return "$keyName +$point"
    }

    val flatKeys = setOf(
        "manualSlot",
        "comprehensionFlat",
        "intelligenceFlat",
        "teachingFlat",
        "artifactRefiningFlat",
        "pillRefiningFlat",
        "spiritPlantingFlat",
        "charmFlat",
        "loyaltyFlat",
        "moralityFlat",
        "miningFlat"
    )

    val valueText = if (key in flatKeys) {
        kotlin.math.abs(doubleValue).toInt().toString()
    } else {
        val percentValue = kotlin.math.abs(doubleValue) * 100
        if (percentValue % 1 == 0.0) {
            String.format(Locale.getDefault(), "%d%%", percentValue.toLong())
        } else {
            String.format(Locale.getDefault(), "%.1f%%", percentValue)
        }
    }

    val sign = if (doubleValue >= 0) "+" else "-"
    return "$keyName $sign$valueText"
}

fun formatEffectKey(key: String): String {
    return when (key) {
        "cultivationSpeed" -> "修炼速度"
        "breakthroughChance" -> "突破概率"
        "physicalAttack" -> "物攻"
        "magicAttack" -> "法攻"
        "physicalDefense" -> "物防"
        "magicDefense" -> "法防"
        "speed" -> "速度"
        "critRate" -> "暴击率"
        "maxHp" -> "生命上限"
        "maxMp" -> "法力上限"
        "alchemySuccess" -> "炼丹成功率"
        "forgeSuccess" -> "炼器成功率"
        "miningOutput" -> "挖矿产量"
        "herbYield" -> "草药产量"
        "rareDropRate" -> "稀有掉落率"
        "manualLearnSpeed" -> "功法学习速度"
        "lifespan" -> "寿命"
        "partnerChance" -> "结侣概率"
        "manualSlot" -> "功法槽位"
        "comprehensionFlat" -> "悟性"
        "intelligenceFlat" -> "智力"
        "teachingFlat" -> "传道"
        "artifactRefiningFlat" -> "炼器"
        "pillRefiningFlat" -> "炼丹"
        "spiritPlantingFlat" -> "种植"
        "charmFlat" -> "魅力"
        "loyaltyFlat" -> "忠诚"
        "moralityFlat" -> "道德"
        "miningFlat" -> "采矿"
        "winBattleRandomAttrPlus" -> "胜利后随机属性成长（无上限）"
        "damageAmplification" -> "伤害放大"
        "damageReduction" -> "伤害减免"
        "critDamageBonus" -> "暴击伤害"
        "defenseBonus" -> "防御加成"
        else -> key
    }
}

/** 职务类型显示名（用于 PositionBonus 文案） */
fun formatSlotTypeName(slotType: ElderSlotType): String = when (slotType) {
    ElderSlotType.VICE_SECT_MASTER -> "副宗主"
    ElderSlotType.HERB_GARDEN -> "灵田长老"
    ElderSlotType.ALCHEMY -> "炼丹长老"
    ElderSlotType.FORGE -> "炼器长老"
    ElderSlotType.OUTER_ELDER -> "外门长老"
    ElderSlotType.PREACHING -> "传道长老"
    ElderSlotType.LAW_ENFORCEMENT -> "执法长老"
    ElderSlotType.INNER_ELDER -> "内门长老"
    ElderSlotType.RECRUITING -> "纳徒长老"
    ElderSlotType.CLOUD_PREACHING -> "青云传道长老"
}

/** 格式化百分比数值：传入小数（如 0.15），输出 "15%" 或 "15.5%" */
fun formatPercentValue(value: Double): String {
    val percentValue = kotlin.math.abs(value) * 100
    return if (percentValue % 1 == 0.0) {
        String.format(Locale.getDefault(), "%d%%", percentValue.toLong())
    } else {
        String.format(Locale.getDefault(), "%.1f%%", percentValue)
    }
}

@Composable
fun PhysiqueDetailDialog(physique: Physique, onDismiss: () -> Unit) {
    val rarityColor = getTalentRarityColor(physique.rarity)

    SmallScreenDialog(
        onDismissRequest = onDismiss,
        title = physique.name,
        titleColor = rarityColor
    ) {
        Text(
            text = "体质效果",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        val hasAnyEffect = physique.cultivationSpeedBonus != 0.0 ||
            physique.damageAmplification != 0.0 ||
            physique.damageReduction != 0.0 ||
            physique.critDamageBonus != 0.0 ||
            physique.defenseBonus != 0.0

        if (!hasAnyEffect) {
            Text(
                text = physique.description,
                fontSize = 12.sp,
                color = Color.Black
            )
        } else {
            if (physique.cultivationSpeedBonus != 0.0) {
                DetailEffectRow("修炼速度", physique.cultivationSpeedBonus)
            }
            if (physique.damageAmplification != 0.0) {
                DetailEffectRow("伤害放大", physique.damageAmplification)
            }
            if (physique.damageReduction != 0.0) {
                DetailEffectRow("伤害减免", physique.damageReduction)
            }
            if (physique.critDamageBonus != 0.0) {
                DetailEffectRow("暴击伤害", physique.critDamageBonus)
            }
            if (physique.defenseBonus != 0.0) {
                DetailEffectRow("防御加成", physique.defenseBonus)
            }
        }
    }
}

@Composable
fun AffixDetailDialog(affix: Affix, onDismiss: () -> Unit) {
    val rarityColor = getTalentRarityColor(affix.rarity)

    SmallScreenDialog(
        onDismissRequest = onDismiss,
        title = affix.name,
        titleColor = rarityColor
    ) {
        Text(
            text = "词条效果",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        if (affix.effects.isEmpty() && affix.positionBonus == null) {
            Text(
                text = affix.description,
                fontSize = 12.sp,
                color = Color.Black
            )
        } else {
            affix.effects.forEach { (key, value) ->
                DetailEffectRow(formatEffectKey(key), value, key)
            }
            affix.positionBonus?.let { bonus ->
                val slotName = formatSlotTypeName(bonus.slotType)
                val percent = formatPercentValue(bonus.effectBonus)
                val sign = if (bonus.effectBonus >= 0) "+" else "-"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "•", fontSize = 12.sp, color = Color.Black)
                    Text(
                        text = "担任职务($slotName)时职能效果 $sign$percent",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailEffectRow(name: String, value: Double, key: String? = null) {
    val flatKeys = setOf(
        "manualSlot",
        "comprehensionFlat",
        "intelligenceFlat",
        "teachingFlat",
        "artifactRefiningFlat",
        "pillRefiningFlat",
        "spiritPlantingFlat",
        "charmFlat",
        "loyaltyFlat",
        "moralityFlat",
        "miningFlat"
    )
    val valueText = if (key != null && key in flatKeys) {
        kotlin.math.abs(value).toInt().toString()
    } else if (key == "winBattleRandomAttrPlus") {
        kotlin.math.abs(value).toInt().coerceAtLeast(1).toString()
    } else {
        formatPercentValue(value)
    }
    val sign = if (value >= 0) "+" else "-"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = "•", fontSize = 12.sp, color = Color.Black)
        Text(
            text = "$name $sign$valueText",
            fontSize = 12.sp,
            color = Color.Black
        )
    }
}
