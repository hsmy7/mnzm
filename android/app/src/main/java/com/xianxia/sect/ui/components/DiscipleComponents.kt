package com.xianxia.sect.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.R
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.Talent
import com.xianxia.sect.core.registry.TalentDatabase
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
    extraAttributes: List<Pair<String, Int>> = emptyList(),
    customAttributes: @Composable (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) GameColors.Gold else Color(0xFFE0E0E0)
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val statusText = disciple.status.displayName
    val talents = remember(disciple.talentIds) {
        TalentDatabase.getTalentsByIds(disciple.talentIds)
    }

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
                val context = LocalContext.current
                val portraitResId = remember(disciple.portraitRes) {
                    PortraitPool.getResourceId(context, disciple.portraitRes)
                }
                Image(
                    painter = if (portraitResId != 0) painterResource(id = portraitResId)
                              else painterResource(id = R.drawable.disciple_portrait),
                    contentDescription = null,
                    modifier = Modifier.width(44.dp).height(56.dp),
                    contentScale = ContentScale.Fit
                )
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
                            Text(
                                text = statusText,
                                fontSize = 12.sp,
                                color = Color.Black,
                                maxLines = 1
                            )
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
                if (talents.isNotEmpty()) {
                    val talentRows = talents.chunked(3)
                    talentRows.forEach { rowTalents ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            rowTalents.forEach { talent ->
                                val rarityColor = getTalentRarityColor(talent.rarity)
                                Text(
                                    text = talent.name,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = rarityColor,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun getTalentRarityColor(rarity: Int): Color = when (rarity) {
    1 -> Color(0xFF95A5A6)
    2 -> Color(0xFF27AE60)
    3 -> Color(0xFF3498DB)
    4 -> Color(0xFF9B59B6)
    5 -> Color(0xFFF39C12)
    6 -> Color(0xFFE74C3C)
    else -> Color(0xFF95A5A6)
}

@Composable
fun TalentDetailDialog(talent: Talent, onDismiss: () -> Unit) {
    val rarityColor = getTalentRarityColor(talent.rarity)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent, tonalElevation = 0.dp,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = talent.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = rarityColor
                )
                CloseButton(onClick = onDismiss)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "天赋效果",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                if (talent.effects.isEmpty()) {
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
                }
            }
        },
        confirmButton = {}
    )
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
        "moralityFlat"
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
        "winBattleRandomAttrPlus" -> "胜利后随机属性成长（无上限）"
        else -> key
    }
}
