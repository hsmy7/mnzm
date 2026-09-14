package com.xianxia.sect.ui.game.components

internal val BUFF_TYPE_BY_KEY: Map<String, com.xianxia.sect.core.BuffType> = mapOf(
    "physical_attack" to com.xianxia.sect.core.BuffType.PHYSICAL_ATTACK_BOOST,
    "magic_attack" to com.xianxia.sect.core.BuffType.MAGIC_ATTACK_BOOST,
    "physical_defense" to com.xianxia.sect.core.BuffType.PHYSICAL_DEFENSE_BOOST,
    "magic_defense" to com.xianxia.sect.core.BuffType.MAGIC_DEFENSE_BOOST,
    "hp" to com.xianxia.sect.core.BuffType.HP_BOOST,
    "mp" to com.xianxia.sect.core.BuffType.MP_BOOST,
    "speed" to com.xianxia.sect.core.BuffType.SPEED_BOOST,
    "crit_rate" to com.xianxia.sect.core.BuffType.CRIT_RATE_BOOST,
    "physical_attack_reduce" to com.xianxia.sect.core.BuffType.PHYSICAL_ATTACK_REDUCE,
    "magic_attack_reduce" to com.xianxia.sect.core.BuffType.MAGIC_ATTACK_REDUCE,
    "physical_defense_reduce" to com.xianxia.sect.core.BuffType.PHYSICAL_DEFENSE_REDUCE,
    "magic_defense_reduce" to com.xianxia.sect.core.BuffType.MAGIC_DEFENSE_REDUCE,
    "speed_reduce" to com.xianxia.sect.core.BuffType.SPEED_REDUCE,
    "crit_rate_reduce" to com.xianxia.sect.core.BuffType.CRIT_RATE_REDUCE,
    "poison" to com.xianxia.sect.core.BuffType.POISON,
    "burn" to com.xianxia.sect.core.BuffType.BURN,
    "stun" to com.xianxia.sect.core.BuffType.STUN,
    "freeze" to com.xianxia.sect.core.BuffType.FREEZE,
    "silence" to com.xianxia.sect.core.BuffType.SILENCE,
    "taunt" to com.xianxia.sect.core.BuffType.TAUNT,
    "damage_link" to com.xianxia.sect.core.BuffType.DAMAGE_LINK,
    "damage_share" to com.xianxia.sect.core.BuffType.DAMAGE_SHARE,
    "shield" to com.xianxia.sect.core.BuffType.SHIELD,
    "damage_reduction" to com.xianxia.sect.core.BuffType.DAMAGE_REDUCTION,
    "damage_boost" to com.xianxia.sect.core.BuffType.DAMAGE_BOOST,
    "turn_advance" to com.xianxia.sect.core.BuffType.TURN_ADVANCE
)



// ===== 共享工具函数 =====

internal fun getBuffTypeName(buffType: com.xianxia.sect.core.BuffType): String = buffType.displayName

internal fun getBuffTypeName(buffType: String): String =
    BUFF_TYPE_BY_KEY[buffType]?.displayName ?: buffType

/** 解析单段 buff 文本 "type,value,duration"；类型/数值/时长任一非法返回 null */
internal fun parseBuffEntry(buffStr: String): Triple<com.xianxia.sect.core.BuffType, Double, Int>? {
    val parts = buffStr.split(",")
    if (parts.size != 3) return null
    val type = BUFF_TYPE_BY_KEY[parts[0]] ?: return null
    val value = parts[1].toDoubleOrNull() ?: return null
    val duration = parts[2].toIntOrNull() ?: return null
    return Triple(type, value, duration)
}

internal fun parseManualStackBuffs(json: String): List<Triple<com.xianxia.sect.core.BuffType, Double, Int>> {
    if (json.isBlank()) return emptyList()
    return json.split("|").mapNotNull { buffStr -> parseBuffEntry(buffStr) }
}

internal fun getTargetScopeName(scope: String): String = when (scope) {
    "self" -> "自身"
    "ally" -> "友方"
    "enemy" -> "敌方"
    "team" -> "全队"
    else -> scope
}

internal fun formatBuffLine(buffType: String, value: Double, duration: Int): String {
    val buffName = getBuffTypeName(buffType)
    val durationText = if (duration > 0) " (${duration}回合)" else ""
    val specialTypes = setOf("poison", "burn", "stun", "freeze", "silence", "taunt")
    if (buffType in specialTypes) {
        return "$buffName$durationText"
    }
    val isDebuff = try {
        com.xianxia.sect.core.BuffType.valueOf(buffType.uppercase()).isDebuff
    } catch (_: IllegalArgumentException) {
        false
    }
    val sign = if (isDebuff) "-" else "+"
    return "$buffName ${sign}${(value * 100).toInt()}%$durationText"
}

internal fun formatBuffLine(buffType: com.xianxia.sect.core.BuffType, value: Double, duration: Int): String {
    val buffName = buffType.displayName
    val durationText = if (duration > 0) " (${duration}回合)" else ""
    val specialTypes = setOf(
        com.xianxia.sect.core.BuffType.POISON, com.xianxia.sect.core.BuffType.BURN,
        com.xianxia.sect.core.BuffType.STUN, com.xianxia.sect.core.BuffType.FREEZE,
        com.xianxia.sect.core.BuffType.SILENCE, com.xianxia.sect.core.BuffType.TAUNT
    )
    if (buffType in specialTypes) {
        return "$buffName$durationText"
    }
    val sign = if (buffType.isDebuff) "-" else "+"
    return "$buffName ${sign}${(value * 100).toInt()}%$durationText"
}
