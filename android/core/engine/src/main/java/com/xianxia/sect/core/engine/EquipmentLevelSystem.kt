package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.EquipLevelCurve

/**
 * 装备等级系统（装备重构 B3，方案 §3.6——替换孕养体系 R4）。
 *
 * 等级/经验只存 [com.xianxia.sect.core.model.EquipmentInstance.growth] 单点
 * （R5 装卸不改等级）；本系统提供**纯函数**的升级判定与经验计算，
 * 写入面走 [EquipmentUpgradeService]（native 事务真相先行 + Kotlin 回退臂）。
 */
object EquipmentLevelSystem {

    /** 是否满级（30 级后不再获得经验，S4） */
    fun isMaxLevel(level: Int): Boolean = level >= EquipLevelCurve.MAX_LEVEL

    /** 升到 level+1 级所需经验 */
    fun expRequired(level: Int, rarity: Int): Int = EquipLevelCurve.expRequired(level, rarity)

    /**
     * 经验累计推进：返回推进后的 (level, exp)。
     * 一次性连升时**按节点顺序**逐级判定；`level == MAX_LEVEL` 后 exp 恒 0、
     * 不再累计（溢出不保留，方案 §3.6「满级」口径）。
     */
    fun advance(level: Int, exp: Int, expGain: Int, rarity: Int): Pair<Int, Int> {
        var lv = level.coerceIn(EquipLevelCurve.MIN_LEVEL, EquipLevelCurve.MAX_LEVEL)
        var cur = if (isMaxLevel(lv)) 0 else exp.coerceIn(0, expRequired(lv, rarity) - 1)
        var remain = if (isMaxLevel(lv)) 0 else expGain
        while (remain > 0 && !isMaxLevel(lv)) {
            val need = expRequired(lv, rarity)
            if (cur + remain >= need) {
                remain -= need - cur
                lv += 1
                cur = 0
                if (isMaxLevel(lv)) return lv to 0
            } else {
                cur += remain
                remain = 0
            }
        }
        return lv to cur
    }

    /** 新等级是否触发副词条强化节点（升级动作完成时判定 `newLevel % 3 == 0`） */
    fun triggersReinforcement(newLevel: Int): Boolean =
        newLevel in EquipLevelCurve.MIN_LEVEL..EquipLevelCurve.MAX_LEVEL &&
            newLevel % EquipLevelCurve.REINFORCE_INTERVAL == 0

    /** 升级消耗（灵石，level → level+1） */
    fun spiritStonesCost(level: Int, rarity: Int): Long =
        EquipLevelCurve.spiritStonesCost(level, rarity)

    /** 升级消耗（兽材件数，level → level+1） */
    fun beastMaterialCost(level: Int): Int = EquipLevelCurve.beastMaterialCost(level)

    /** 分解返还（累计消耗 × 50%）：灵石 + 兽材件数 */
    fun dismantleRefund(rarity: Int, level: Int): Pair<Long, Int> =
        EquipLevelCurve.dismantleRefund(rarity, level)
}
