package com.xianxia.sect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.Talent
import com.xianxia.sect.core.model.Physique
import com.xianxia.sect.core.model.Affix
import com.xianxia.sect.core.model.formatSlotTypeName
import com.xianxia.sect.ui.theme.GameColors
import java.util.Locale

internal val EFFECT_KEY_NAMES: Map<String, String> = mapOf(
    "cultivationSpeed" to "修炼速度",
    "breakthroughChance" to "突破概率",
    "physicalAttack" to "物攻",
    "magicAttack" to "法攻",
    "physicalDefense" to "物防",
    "magicDefense" to "法防",
    "speed" to "速度",
    "critRate" to "暴击率",
    "maxHp" to "生命上限",
    "maxMp" to "法力上限",
    "alchemySuccess" to "炼丹成功率",
    "forgeSuccess" to "炼器成功率",
    "miningOutput" to "挖矿产量",
    "herbYield" to "草药产量",
    "rareDropRate" to "稀有掉落率",
    "manualLearnSpeed" to "功法学习速度",
    "lifespan" to "寿命",
    "partnerChance" to "结侣概率",
    "manualSlot" to "功法槽位",
    "comprehensionFlat" to "悟性",
    "intelligenceFlat" to "智力",
    "teachingFlat" to "传道",
    "artifactRefiningFlat" to "炼器",
    "pillRefiningFlat" to "炼丹",
    "spiritPlantingFlat" to "种植",
    "charmFlat" to "魅力",
    "loyaltyFlat" to "忠诚",
    "moralityFlat" to "道德",
    "miningFlat" to "采矿",
    "winBattleRandomAttrPlus" to "胜利后随机属性成长（无上限）",
    "damageAmplification" to "伤害放大",
    "damageReduction" to "伤害减免",
    "critDamageBonus" to "暴击伤害",
    "defenseBonus" to "防御加成"
)


fun getTalentRarityColor(rarity: Int): Color = when (rarity) {
    1 -> GameColors.TalentGradeLow
    2 -> GameColors.TalentGradeMid
    3 -> GameColors.TalentGradeHigh
    else -> GameColors.TalentNegative
}

@Composable
fun TalentDetailDialog(
    talent: Talent,
    onDismiss: () -> Unit,
    onWashClick: (() -> Unit)? = null,
    washOverlay: (@Composable () -> Unit)? = null
) {
    val rarityColor = getTalentRarityColor(talent.rarity)

    SmallScreenDialog(
        onDismissRequest = onDismiss,
        title = talent.name,
        titleColor = rarityColor,
        footer = {
            onWashClick?.let { GameButton(text = "洗炼天赋", onClick = it) }
        },
        overlay = washOverlay
    ) {
        TalentDetailContent(talent)
    }
}

@Composable
internal fun TalentDetailContent(talent: Talent) {
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

fun formatEffectKey(key: String): String = EFFECT_KEY_NAMES[key] ?: key

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
fun PhysiqueDetailDialog(
    physique: Physique,
    onDismiss: () -> Unit,
    onWashClick: (() -> Unit)? = null,
    washOverlay: (@Composable () -> Unit)? = null
) {
    val rarityColor = getTalentRarityColor(physique.rarity)

    SmallScreenDialog(
        onDismissRequest = onDismiss,
        title = physique.name,
        titleColor = rarityColor,
        footer = {
            onWashClick?.let { GameButton(text = "洗炼体质", onClick = it) }
        },
        overlay = washOverlay
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
fun AffixDetailDialog(
    affix: Affix,
    onDismiss: () -> Unit,
    onWashClick: (() -> Unit)? = null,
    washOverlay: (@Composable () -> Unit)? = null
) {
    val rarityColor = getTalentRarityColor(affix.rarity)

    SmallScreenDialog(
        onDismissRequest = onDismiss,
        title = affix.name,
        titleColor = rarityColor,
        footer = {
            onWashClick?.let { GameButton(text = "洗炼词条", onClick = it) }
        },
        overlay = washOverlay
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
internal fun DetailEffectRow(name: String, value: Double, key: String? = null) {
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
