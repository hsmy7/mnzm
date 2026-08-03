package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

// GameDataBloodRefinement.kt — 血炼进度/加成（P-2 从 GameData.kt 拆分，同包模型，序列化字段不变）

// 血炼进度数据
@Keep
@Serializable
data class BloodRefinementProgress(
    @ProtoNumber(1) val discipleId: String = "",
    @ProtoNumber(2) val discipleName: String = "",
    @ProtoNumber(3) val materialId: String = "",
    @ProtoNumber(4) val materialName: String = "",
    @ProtoNumber(5) val startYear: Int = 0,
    @ProtoNumber(6) val startMonth: Int = 0,
    @ProtoNumber(7) val durationMonths: Int = 0,
    @ProtoNumber(8) val selectedStat: String = "",    // "speed"/"hp"/"physicalAttack"/"magicAttack"/"physicalDefense"/"magicDefense"
    @ProtoNumber(9) val bonusPercent: Double = 0.0
)

/**
 * 血炼加成累计记录（单利计算基准，旧格式）。
 *
 * 用于修复血炼加成复利叠加 bug（#8）：
 * - 旧实现每次血炼 bonus = 当前 base × bonusPercent，导致 baseₙ = base₀ × (1+p)ⁿ 复利叠加
 * - 修复后 bonus = (当前 base - 已累计 bonus) × bonusPercent，实现单利
 *
 * 此字段已被 [BloodRefinementPctTotal] 替代。新系统将血炼改造为乘区百分比，
 * 不再直接修改 DiscipleTables.base* 列。仅用于旧存档迁移。
 *
 * @see com.xianxia.sect.core.domain.disciple.DiscipleStatCalculator.calculateSimpleInterestBonus
 */
@Keep
@Serializable
data class BloodRefinementBonusTotal(
    @ProtoNumber(1) val discipleId: String = "",
    @ProtoNumber(2) val hpBonus: Int = 0,
    @ProtoNumber(3) val physicalAttackBonus: Int = 0,
    @ProtoNumber(4) val magicAttackBonus: Int = 0,
    @ProtoNumber(5) val physicalDefenseBonus: Int = 0,
    @ProtoNumber(6) val magicDefenseBonus: Int = 0,
    @ProtoNumber(7) val speedBonus: Int = 0
)

/**
 * 血炼加成累计记录（百分比乘区格式）。
 *
 * 替代 [BloodRefinementBonusTotal] 的绝对值存储，采用百分比存储。
 * 每次血炼完成时：累计百分比 += 材料百分比。
 * 计算时：属性 = 境界基础 × 方差 × 层数 × (1 + 天赋% + 血炼%)。
 *
 * 优势：
 * - 突破后血炼收益随境界自动缩放
 * - 与乘区法系统统一（与天赋同乘区加算）
 * - 不再直接修改 DiscipleTables.base* 列
 */
@Keep
@Serializable
data class BloodRefinementPctTotal(
    @ProtoNumber(1) val discipleId: String = "",
    @ProtoNumber(2) val hpBonusPct: Double = 0.0,
    @ProtoNumber(3) val physicalAttackBonusPct: Double = 0.0,
    @ProtoNumber(4) val magicAttackBonusPct: Double = 0.0,
    @ProtoNumber(5) val physicalDefenseBonusPct: Double = 0.0,
    @ProtoNumber(6) val magicDefenseBonusPct: Double = 0.0,
    @ProtoNumber(7) val speedBonusPct: Double = 0.0
)
