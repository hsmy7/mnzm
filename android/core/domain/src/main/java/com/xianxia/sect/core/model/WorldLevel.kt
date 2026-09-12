package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlinx.serialization.protobuf.ProtoNumber

@Immutable
@Keep
@Serializable
data class WorldLevel(
    @ProtoNumber(1) val id: String = UUID.randomUUID().toString(),
    @ProtoNumber(2) val type: LevelType = LevelType.BEAST,
    @ProtoNumber(3) val beastType: Int? = null,
    @ProtoNumber(4) val realm: Int = 9,
    @ProtoNumber(5) val realmLayer: Int = 1,
    @ProtoNumber(6) val beastName: String = "",
    @ProtoNumber(7) val guardianName: String = "",
    @ProtoNumber(8) val caveName: String = "",
    @ProtoNumber(9) val x: Float = 0f,
    @ProtoNumber(10) val y: Float = 0f,
    @ProtoNumber(11) val spawnYear: Int = 1,
    @ProtoNumber(12) val spawnMonth: Int = 1,
    @ProtoNumber(13) val expiryYear: Int = 1,
    @ProtoNumber(14) val expiryMonth: Int = 1,
    @ProtoNumber(15) val count: Int = 5,
    @ProtoNumber(16) val caveImageIndex: Int = 0,
    @ProtoNumber(17) val defeated: Boolean = false,

    // ========== 预计算妖兽最终属性（生成时含随机方差，直接用于战斗和战力计算） ==========
    @ProtoNumber(18) val beastMaxHp: Int = 0,
    @ProtoNumber(19) val beastMaxMp: Int = 0,
    @ProtoNumber(20) val beastPhysicalAttack: Int = 0,
    @ProtoNumber(21) val beastMagicAttack: Int = 0,
    @ProtoNumber(22) val beastPhysicalDefense: Int = 0,
    @ProtoNumber(23) val beastMagicDefense: Int = 0,
    @ProtoNumber(24) val beastSpeed: Int = 0
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
