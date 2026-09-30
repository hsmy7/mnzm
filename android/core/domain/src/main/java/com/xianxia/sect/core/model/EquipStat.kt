package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * 装备可提供的加成维度（五行属性伤害系统，方案 §3.5）。
 *
 * 单列属性重构（B1）后 [ATTACK]/[DEFENSE] 即弟子统一单列属性本身，
 * 不存在物攻/法攻分列维度；装备不提供速度/灵力（S14，方案 §13-13）。
 * [ATTACK_PCT] 仅套装/功法/丹药使用，不进副词条池（§15.7 Q10）。
 *
 * 类型伤害加成 6 条（物理 + 五行）：[PHYSICAL_DAMAGE_PCT] 不受灵根 gate，
 * 5 条元素词条受 gate（弟子灵根含该元素才生效，方案 §3.3）。
 *
 * EquipStat 首次上线于装备重构（编号自 1 起）；[MAGIC_DAMAGE_PCT] 为退役段——
 * 序列化按枚举 name 落盘，删值会使存量档读档失败，仅保留兼容解析，禁新产出。
 */
@Keep
@Serializable
enum class EquipStat {
    /** 攻击力（flat，单列） */
    @ProtoNumber(1) ATTACK,
    /** 防御力（flat，单列） */
    @ProtoNumber(2) DEFENSE,
    /** 血量（flat） */
    @ProtoNumber(3) HP,
    /** 暴击率（比例值） */
    @ProtoNumber(4) CRIT_RATE,
    /** 暴击伤害（比例值，同时接线 D3 死字段） */
    @ProtoNumber(5) CRIT_DAMAGE,
    /** 攻击力%（乘区；仅套装/功法/丹药使用，不进副词条池） */
    @ProtoNumber(6) ATTACK_PCT,
    /** 物理伤害加成（乘区；不受灵根 gate） */
    @ProtoNumber(7) PHYSICAL_DAMAGE_PCT,
    /** 法术伤害加成（退役段：仅旧档兼容保留，禁新产出） */
    @ProtoNumber(8) MAGIC_DAMAGE_PCT,
    /** 金伤害加成（乘区；受灵根 gate） */
    @ProtoNumber(9) METAL_DAMAGE_PCT,
    /** 木伤害加成（乘区；受灵根 gate） */
    @ProtoNumber(10) WOOD_DAMAGE_PCT,
    /** 水伤害加成（乘区；受灵根 gate） */
    @ProtoNumber(11) WATER_DAMAGE_PCT,
    /** 火伤害加成（乘区；受灵根 gate） */
    @ProtoNumber(12) FIRE_DAMAGE_PCT,
    /** 土伤害加成（乘区；受灵根 gate） */
    @ProtoNumber(13) EARTH_DAMAGE_PCT;

    val displayName: String get() = when (this) {
        ATTACK -> "攻击力"
        DEFENSE -> "防御力"
        HP -> "血量"
        CRIT_RATE -> "暴击率"
        CRIT_DAMAGE -> "暴击伤害"
        ATTACK_PCT -> "攻击力%"
        PHYSICAL_DAMAGE_PCT -> "物理伤害加成"
        MAGIC_DAMAGE_PCT -> "法术伤害加成"
        METAL_DAMAGE_PCT -> "金伤害加成"
        WOOD_DAMAGE_PCT -> "木伤害加成"
        WATER_DAMAGE_PCT -> "水伤害加成"
        FIRE_DAMAGE_PCT -> "火伤害加成"
        EARTH_DAMAGE_PCT -> "土伤害加成"
    }

    /** flat 维度（直接加到面板值）；百分比/乘区维度返回 false */
    val isFlat: Boolean get() = this == ATTACK || this == DEFENSE || this == HP

    /** 元素伤害加成词条 → 元素 key（对应 DamageType.element）；非元素词条为 null */
    val element: String? get() = when (this) {
        METAL_DAMAGE_PCT -> "metal"
        WOOD_DAMAGE_PCT -> "wood"
        WATER_DAMAGE_PCT -> "water"
        FIRE_DAMAGE_PCT -> "fire"
        EARTH_DAMAGE_PCT -> "earth"
        else -> null
    }
}

/**
 * 单条装备加成值（词条/套装效果共用的同一加成模型，方案 §3.3）。
 */
@Keep
@Serializable
data class EquipStatValue(
    @ProtoNumber(1) val stat: EquipStat,
    @ProtoNumber(2) val value: Double
) {
    override fun toString(): String {
        val display = if (stat.isFlat) {
            value.toInt().toString()
        } else {
            val percent = value * 100
            "${(percent * 10).toInt() / 10.0}%"
        }
        return "${stat.displayName}+$display"
    }
}
