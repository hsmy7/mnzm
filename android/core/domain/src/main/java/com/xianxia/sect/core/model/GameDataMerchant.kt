package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

// GameDataMerchant.kt — 商人/设置/功法熟练度/矿场（P-2 从 GameData.kt 拆分，同包模型，序列化字段不变）

// 商人商品
@Keep
@Serializable
data class MerchantItem(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val type: String = "", // equipment, manual, pill, material, seed, spiritStone
    @ProtoNumber(4) val itemId: String = "",
    @ProtoNumber(5) val rarity: Int = 1,
    @ProtoNumber(6) val price: Long = 0L,
    @ProtoNumber(7) val quantity: Int = 1,
    @ProtoNumber(8) val description: String = "",
    @ProtoNumber(9) val obtainedYear: Int = 0,
    @ProtoNumber(10) val obtainedMonth: Int = 0,
    @ProtoNumber(11) val grade: String? = null
)

// 游戏设置数据
@Keep
@Serializable
data class GameSettingsData(
    val soundEnabled: Boolean = true,
    val musicEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val autoSave: Boolean = true,
    val language: String = "zh"
)

// 功法熟练度数据
@Keep
@Serializable
data class ManualProficiencyData(
    @ProtoNumber(1) val manualId: String = "",
    @ProtoNumber(2) val manualName: String = "",
    @ProtoNumber(3) val proficiency: Double = 0.0,
    @ProtoNumber(4) val maxProficiency: Int = 100,
    @ProtoNumber(5) val level: Int = 1,
    @ProtoNumber(6) val masteryLevel: Int = 0
)

// 矿脉槽位
@Keep
@Serializable
data class MineSlot(
    @ProtoNumber(1) val index: Int = 0,
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String = "",
    @ProtoNumber(4) val output: Int = 0,
    @ProtoNumber(5) val efficiency: Double = 1.0,
    @ProtoNumber(6) val isActive: Boolean = false
)

// 队伍状态
@Keep
@Serializable
enum class TeamStatus {
    IDLE,
    EXPLORING,
    RETURNING,
    COMPLETED;

    val displayName: String get() = when (this) {
        IDLE -> "待命"
        EXPLORING -> "探索中"
        RETURNING -> "返回中"
        COMPLETED -> "已完成"
    }
}
