package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.EquipmentSlot

/**
 * 装备孕养系统
 * 装备通过战斗使用获得经验，提升孕养等级，增强属性
 */
object EquipmentNurtureSystem {

    // 基础经验获取量
    const val BASE_EXP_GAIN = 10.0

    // 每秒自动获得的经验（基础值）
    const val AUTO_EXP_PER_SECOND = 1.0

    // 每级固定增加 5% 属性加成
    const val NURTURE_BONUS_PER_LEVEL = 0.05

    /**
     * 获取装备最大孕养等级（根据品阶）
     * 凡品最高5级，天品最高25级
     * 品阶：1=凡品, 2=灵品, 3=宝品, 4=玄品, 5=地品, 6=天品
     */
    fun getMaxNurtureLevel(rarity: Int): Int {
        return when (rarity) {
            1 -> 5   // 凡品
            2 -> 9   // 灵品
            3 -> 13  // 宝品
            4 -> 17  // 玄品
            5 -> 21  // 地品
            6 -> 25  // 天品
            else -> 5
        }
    }

    /**
     * 获取升级所需经验
     * 品阶越高，升级所需经验越多
     */
    fun getExpRequiredForLevelUp(currentLevel: Int, rarity: Int): Double {
        val maxLevel = getMaxNurtureLevel(rarity)
        if (currentLevel >= maxLevel) return Double.MAX_VALUE

        // 基础经验需求
        val baseExp = 100.0 * (currentLevel + 1)

        // 品阶倍率（品阶越高，需求越多）
        val rarityMultiplier = when (rarity) {
            1 -> 1.0    // 凡品
            2 -> 1.5    // 灵品
            3 -> 2.0    // 宝品
            4 -> 3.0    // 玄品
            5 -> 4.5    // 地品
            6 -> 6.0    // 天品
            else -> 1.0
        }

        return baseExp * rarityMultiplier
    }

    /**
     * 计算战斗获得的经验
     * 战斗胜利获得升级所需经验的10%
     */
    fun calculateExpGain(equipment: Equipment, isVictory: Boolean): Double {
        if (!isVictory) return 0.0
        val expRequired = getExpRequiredForLevelUp(equipment.nurtureLevel, equipment.rarity)
        return expRequired * 0.1
    }

    /**
     * 计算每秒自动获得的经验
     * 根据装备品阶调整经验获取速率
     */
    fun calculateAutoExpGain(rarity: Int): Double {
        // 品阶越高，自动获得经验越快
        val rarityMultiplier = when (rarity) {
            1 -> 1.0    // 凡品
            2 -> 1.2    // 灵品
            3 -> 1.4    // 宝品
            4 -> 1.6    // 玄品
            5 -> 1.8    // 地品
            6 -> 2.0    // 天品
            else -> 1.0
        }
        return AUTO_EXP_PER_SECOND * rarityMultiplier
    }

    /**
     * 更新装备孕养经验
     * @return 更新后的装备和是否升级的标记
     */
    fun updateNurtureExp(
        equipment: Equipment,
        expGain: Double
    ): NurtureResult {
        val maxLevel = getMaxNurtureLevel(equipment.rarity)
        if (equipment.nurtureLevel >= maxLevel) {
            return NurtureResult(equipment, false)
        }

        val newProgress = equipment.nurtureProgress + expGain.toInt()
        val expRequired = getExpRequiredForLevelUp(equipment.nurtureLevel, equipment.rarity)

        return if (newProgress >= expRequired) {
            // 升级
            val newLevel = (equipment.nurtureLevel + 1).coerceAtMost(maxLevel)
            val remainingExp = (newProgress - expRequired).toInt()

            val updatedEquipment = equipment.copy(
                nurtureLevel = newLevel,
                nurtureProgress = if (newLevel >= maxLevel) 0 else remainingExp
            )
            NurtureResult(updatedEquipment, true)
        } else {
            // 未升级，只增加进度
            val updatedEquipment = equipment.copy(
                nurtureProgress = newProgress
            )
            NurtureResult(updatedEquipment, false)
        }
    }

    /**
     * 获取孕养属性加成倍率（递增模式，最高等级25级达到300%上限）
     * 等级1: 1.012 (1.2%)
     * 等级2: 1.036 (3.6%)
     * 等级3: 1.074 (7.4%)
     * 等级5: 1.185 (18.5%)
     * 等级10: 1.631 (63.1%)
     * 等级15: 2.385 (138.5%)
     * 等级20: 3.446 (244.6%)
     * 等级25: 4.000 (300% 上限)
     */
    fun getNurtureMultiplier(nurtureLevel: Int): Double {
        if (nurtureLevel <= 0) return 1.0
        val maxLevel = 25
        val actualLevel = nurtureLevel.coerceAtMost(maxLevel)
        val totalBonus = actualLevel * (actualLevel + 1) / 2.0 * (3.0 / 325.0)
        return (1.0 + totalBonus).coerceAtMost(4.0)
    }

    /**
     * 获取孕养进度百分比
     */
    fun getNurtureProgressPercent(equipment: Equipment): Int {
        val maxLevel = getMaxNurtureLevel(equipment.rarity)
        if (equipment.nurtureLevel >= maxLevel) return 100

        val expRequired = getExpRequiredForLevelUp(equipment.nurtureLevel, equipment.rarity)
        return ((equipment.nurtureProgress / expRequired) * 100).toInt().coerceIn(0, 100)
    }

    /**
     * 获取装备总倍率（包含孕养加成）
     */
    fun getTotalMultiplier(equipment: Equipment): Double {
        val rarityMult = getRarityMultiplier(equipment.rarity)
        val nurtureMult = getNurtureMultiplier(equipment.nurtureLevel)
        return rarityMult * nurtureMult
    }

    private fun getRarityMultiplier(rarity: Int): Double {
        return when (rarity) {
            1 -> 1.0
            2 -> 1.3
            3 -> 1.6
            4 -> 2.0
            5 -> 2.5
            6 -> 3.0
            else -> 1.0
        }
    }

    data class NurtureResult(
        val equipment: Equipment,
        val leveledUp: Boolean
    )
}
