package com.xianxia.sect.ui.components

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
    "manualSlot" to "功法槽位",
    "intelligenceFlat" to "智力",
    "teachingFlat" to "传道",
    "artifactRefiningFlat" to "炼器",
    "pillRefiningFlat" to "炼丹",
    "spiritPlantingFlat" to "种植",
    "charmFlat" to "魅力",
    "moralityFlat" to "道德",
    "miningFlat" to "采矿",
    "damageAmplification" to "伤害放大",
    "damageReduction" to "伤害减免",
    "critDamageBonus" to "暴击伤害",
    "defenseBonus" to "防御加成"
)

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
