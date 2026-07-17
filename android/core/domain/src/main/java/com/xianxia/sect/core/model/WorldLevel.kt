package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.UUID

@Immutable
@Keep
@Serializable
data class WorldLevel(
    val id: String = UUID.randomUUID().toString(),
    val type: LevelType = LevelType.BEAST,
    val beastType: Int? = null,
    val realm: Int = 9,
    val realmLayer: Int = 1,
    val beastName: String = "",
    val guardianName: String = "",
    val caveName: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val spawnYear: Int = 1,
    val spawnMonth: Int = 1,
    val expiryYear: Int = 1,
    val expiryMonth: Int = 1,
    val count: Int = 5,
    val caveImageIndex: Int = 0,
    val defeated: Boolean = false,

    // ========== 预计算妖兽最终属性（生成时含随机方差，直接用于战斗和战力计算） ==========
    val beastMaxHp: Int = 0,
    val beastMaxMp: Int = 0,
    val beastPhysicalAttack: Int = 0,
    val beastMagicAttack: Int = 0,
    val beastPhysicalDefense: Int = 0,
    val beastMagicDefense: Int = 0,
    val beastSpeed: Int = 0
) {
    val isBeast: Boolean get() = type == LevelType.BEAST
    val isCave: Boolean get() = type == LevelType.CAVE

    val realmName: String get() = when (realm) {
        0 -> "仙人"
        1 -> "渡劫"
        2 -> "大乘"
        3 -> "合体"
        4 -> "炼虚"
        5 -> "化神"
        6 -> "元婴"
        7 -> "金丹"
        8 -> "筑基"
        9 -> "炼气"
        else -> "炼气"
    }

    val isExpired: Boolean get() = defeated

    fun checkExpired(currentYear: Int, currentMonth: Int): Boolean {
        if (defeated) return true
        if (currentYear > expiryYear) return true
        if (currentYear == expiryYear && currentMonth >= expiryMonth) return true
        return false
    }
}

@Keep
@Serializable
enum class LevelType {
    BEAST,
    CAVE
}
