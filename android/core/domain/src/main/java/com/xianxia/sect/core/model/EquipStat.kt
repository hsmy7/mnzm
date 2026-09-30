package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * 装备可提供的加成维度（装备重构 B3，方案 §3.4.1）。
 *
 * 单列属性重构（B1）后 [ATTACK]/[DEFENSE] 即弟子统一单列属性本身，
 * 不存在物攻/法攻分列维度；装备不提供速度/灵力（S14，方案 §13-13）。
 * [ATTACK_PCT] 仅套装/功法/丹药使用，不进副词条池（§15.7 Q10）。
 *
 * EquipStat 为新引入枚举、从未上线 ⇒ 编号自 1 起，无历史包袱。
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
    /** 物理伤害加成（乘区；套装/副词条） */
    @ProtoNumber(7) PHYSICAL_DAMAGE_PCT,
    /** 法术伤害加成（乘区；套装/副词条） */
    @ProtoNumber(8) MAGIC_DAMAGE_PCT;

    val displayName: String get() = when (this) {
        ATTACK -> "攻击力"
        DEFENSE -> "防御力"
        HP -> "血量"
        CRIT_RATE -> "暴击率"
        CRIT_DAMAGE -> "暴击伤害"
        ATTACK_PCT -> "攻击力%"
        PHYSICAL_DAMAGE_PCT -> "物理伤害加成"
        MAGIC_DAMAGE_PCT -> "法术伤害加成"
    }

    /** flat 维度（直接加到面板值）；百分比/乘区维度返回 false */
    val isFlat: Boolean get() = this == ATTACK || this == DEFENSE || this == HP
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
