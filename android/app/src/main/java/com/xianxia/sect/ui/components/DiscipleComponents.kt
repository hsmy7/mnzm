package com.xianxia.sect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.Talent
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
    val Color = Color(0xFF666666)
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
 * 统一的横向弟子选择卡片，用于所有弟子选择界面。
 * 标准布局：第一行名称+状态，第二行灵根+境界，第三行悟性/忠诚/道德
 * extraAttributes: 建筑特定额外属性，如 [("灵植", 72)]，追加在第三行后面
 */
@Composable
fun HorizontalDiscipleCard(
    disciple: DiscipleAggregate,
    isSelected: Boolean = false,
    isCurrent: Boolean = false,
    extraAttributes: List<Pair<String, Int>> = emptyList(),
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) GameColors.Gold else GameColors.Border
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val statusText = disciple.status.displayName

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) GameColors.Gold.copy(alpha = 0.08f) else Color.White)
            .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // 第一行：弟子名称 + 状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = disciple.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    if (disciple.isFollowed) FollowedTag()
                    if (isCurrent) {
                        Text(text = "当前", fontSize = 10.sp, color = Color(0xFFE74C3C))
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = statusText, fontSize = 10.sp, color = Color(0xFF888888))
                    if (isSelected) {
                        Text(text = "✓", fontSize = 13.sp, color = GameColors.GoldDark, fontWeight = FontWeight.Bold)
                    }
                }
            }
            // 第二行：灵根 + 境界
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val spiritRootColor = try {
                    Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                } catch (_: Exception) { Color(0xFF666666) }
                Text(
                    text = disciple.spiritRootName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = spiritRootColor
                )
                Text(text = disciple.realmName, fontSize = 11.sp, color = Color(0xFF666666))
            }
            // 第三行：悟性 + 忠诚 + 道德（+ 额外属性）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "悟性: ${disciple.comprehension}", fontSize = 11.sp, color = Color(0xFF666666))
                Text(text = "忠诚: ${disciple.loyalty}", fontSize = 11.sp, color = Color(0xFF666666))
                Text(text = "道德: ${disciple.morality}", fontSize = 11.sp, color = Color(0xFF666666))
                extraAttributes.forEach { (name, value) ->
                    Text(text = "$name: $value", fontSize = 11.sp, color = Color(0xFF555555))
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
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = talent.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = rarityColor
            )
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
                        color = Color(0xFF666666)
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
                                color = Color(0xFF999999)
                            )
                            Text(
                                text = effectText,
                                fontSize = 12.sp,
                                color = Color(0xFF666666)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            GameButton(
                text = "关闭",
                onClick = onDismiss
            )
        }
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
