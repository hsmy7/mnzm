package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.math.floor

/**
 * 等级曲线与升级消耗（装备重构 B3，方案 §3.6）。
 *
 * - 经验曲线：`expRequired(level) = 100 × level × rarityMul(rarity)`，
 *   `rarityMul` 沿用旧表 1.0/1.5/2.0/3.0/4.5/6.0；等级一律 1..[MAX_LEVEL]。
 * - 主词条成长：`mainLevelMultiplier(level) = 1 + 0.10 × (level - 1)`（Lv1=1.00，Lv30=3.90）。
 * - 副词条强化：每 [REINFORCE_INTERVAL] 级（Lv3/6/…/30 共 10 次）强化一条，
 *   在**升级动作完成时**判定 `newLevel % 3 == 0` 触发；`level == MAX_LEVEL` 后
 *   不再有强化或经验累计。
 * - 升级消耗：灵石 `100 × rarity² × level`，兽材 `max(1, floor(level / 10))` 件；
 *   分解返还 = 累计升级消耗 × [DISMANTLE_REFUND_RATIO]（向下取整）。
 */
object EquipLevelCurve {
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 30

    /** 副词条强化间隔（每 3 级一次，Lv30 封顶共 10 次） */
    const val REINFORCE_INTERVAL = 3

    /** 单条副词条最大强化次数（初始 1 + 强化 10 次） */
    const val MAX_SUB_ROLLS = 11

    /** 分解返还比例（累计升级消耗的 50%，向下取整） */
    const val DISMANTLE_REFUND_RATIO = 0.5

    /** 经验曲线的品阶倍率（rarity 1..6；声明序禁重排——改序会改变升级阈值） */
    val RARITY_EXP_MULTIPLIERS = listOf(1.0, 1.5, 2.0, 3.0, 4.5, 6.0)

    /** 升到 level+1 级所需经验（level 为当前等级；上限校验由调用方负责） */
    fun expRequired(level: Int, rarity: Int): Int {
        val mult = RARITY_EXP_MULTIPLIERS.getOrElse(rarity - 1) { RARITY_EXP_MULTIPLIERS.last() }
        return (BASE_EXP_COST * level * mult).toInt()
    }

    /** 主词条等级成长乘数 */
    fun mainLevelMultiplier(level: Int): Double =
        MAIN_LEVEL_GROWTH * (level - 1) + 1.0

    /** 从 Lv1 升到 [toLevel] 的累计灵石消耗（分解返还口径与升级消耗同源） */
    fun totalSpiritStonesCost(rarity: Int, toLevel: Int): Long {
        var sum = 0L
        for (level in MIN_LEVEL until toLevel.coerceAtMost(MAX_LEVEL)) {
            sum += spiritStonesCost(level, rarity)
        }
        return sum
    }

    /** 从 Lv1 升到 [toLevel] 的累计兽材消耗（件） */
    fun totalBeastMaterialCost(toLevel: Int): Int {
        var sum = 0
        for (level in MIN_LEVEL until toLevel.coerceAtMost(MAX_LEVEL)) {
            sum += beastMaterialCost(level)
        }
        return sum
    }

    /** 升级（level → level+1）灵石消耗 */
    fun spiritStonesCost(level: Int, rarity: Int): Long =
        BASE_SPIRIT_STONE_COST * rarity.toLong() * rarity * level

    /** 升级（level → level+1）兽材消耗（件） */
    fun beastMaterialCost(level: Int): Int = maxOf(1, floor(level / BEAST_MATERIAL_DIVISOR.toDouble()).toInt())

    /** 分解返还（累计消耗 × 50%，向下取整） */
    fun dismantleRefund(rarity: Int, level: Int): Pair<Long, Int> =
        (
            totalSpiritStonesCost(rarity, level).toDouble() * DISMANTLE_REFUND_RATIO
            ).toLong() to (
            floor(totalBeastMaterialCost(level) * DISMANTLE_REFUND_RATIO).toInt()
            )

    /** 经验曲线基础系数（`100 × level × rarityMul`） */
    private const val BASE_EXP_COST = 100

    /** 主词条每级成长系数（0.10） */
    private const val MAIN_LEVEL_GROWTH = 0.10

    /** 升级灵石基础系数（`100 × rarity² × level`） */
    private const val BASE_SPIRIT_STONE_COST = 100L

    /** 兽材消耗除数（`max(1, level / 10)`） */
    private const val BEAST_MATERIAL_DIVISOR = 10
}

/**
 * 词条面：1 主词条 + 3 副词条 + 各副词条强化次数（恒 3 条、互不重复，方案 §3.5）。
 *
 * 副词条抽取顺序（同一件装备内）：主词条 → 副词条（7 项池不放回 3 次）→
 * 等级强化；该顺序是跨端对拍基准，禁止调整（方案 §3.7）。
 */
@Keep
@Serializable
@Immutable
data class EquipAffixSet(
    @ProtoNumber(1) val mainStat: EquipStatValue,
    @ProtoNumber(2) val subStats: List<EquipStatValue> = emptyList(),
    @ProtoNumber(3) val subRolls: List<Int> = emptyList()
) {
    /** 主词条在 [level] 级的最终值（品阶基数由生成期折入 mainStat.value，此处只乘等级成长） */
    fun mainStatFinal(level: Int): EquipStatValue {
        val mult = EquipLevelCurve.mainLevelMultiplier(level)
        return EquipStatValue(mainStat.stat, mainStat.value * mult)
    }

    /** 主词条（按等级成长）+ 3 副词条（各含强化次数收益）的完整加成列表 */
    fun totalBonus(level: Int): List<EquipStatValue> {
        val result = ArrayList<EquipStatValue>(1 + subStats.size)
        result.add(mainStatFinal(level))
        subStats.forEachIndexed { index, sub ->
            val rolls = subRolls.getOrElse(index) { 1 }
            result.add(EquipStatValue(sub.stat, sub.value * rolls))
        }
        return result
    }
}

/** 横切面：品阶/门槛/描述/锁 */
@Keep
@Serializable
@Immutable
data class EquipInstanceMeta(
    @ProtoNumber(1) val rarity: Int = 1,
    @ProtoNumber(2) val minRealm: Int = 9,
    @ProtoNumber(3) val description: String = "",
    @ProtoNumber(4) val isLocked: Boolean = false
)

/** 成长面：等级 + 经验 + 词条（同生共死，聚合为单值对象——等级随实例单点，清偿 D2） */
@Keep
@Serializable
@Immutable
data class EquipGrowth(
    @ProtoNumber(1) val level: Int = EquipLevelCurve.MIN_LEVEL,
    @ProtoNumber(2) val exp: Int = 0,
    @ProtoNumber(3) val affix: EquipAffixSet
)
