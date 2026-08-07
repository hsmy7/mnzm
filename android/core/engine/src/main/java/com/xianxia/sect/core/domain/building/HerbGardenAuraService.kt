package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.*
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

object HerbGardenAuraService {

    /** 灵植阁建筑显示名（光环判定用） */
    private const val HERB_GARDEN_DISPLAY_NAME = "灵植阁"

    /**
     * 批量构建"建筑 instanceId → 是否处于灵植阁光环内"索引（一次 O(b×灵植阁数) 遍历，
     * 供收获循环每块地 O(1) 查询；原逐块调用 [isSpiritFieldInAura] 为 O(n×b)）。
     *
     * @param placedBuildings 全部已放置建筑（任意 sectId 混合）
     * @return 建筑 instanceId → 是否命中光环（索引缺失的 id 与"find 失败返回 false"语义一致）
     */
    fun buildSpiritFieldAuraMap(placedBuildings: List<GridBuildingData>): Map<String, Boolean> {
        val herbGardensBySect = placedBuildings
            .filter { it.displayName == HERB_GARDEN_DISPLAY_NAME }
            .groupBy { it.sectId }
        return placedBuildings.associate { building ->
            building.instanceId to isInAura(building, herbGardensBySect[building.sectId].orEmpty())
        }
    }

    fun calculateElderMaturityBonus(elderSlots: ElderSlots, allDisciples: List<Disciple>): Double {
        val elderId = elderSlots.herbGardenElder
        if (elderId.isBlank()) return 0.0

        val elder = allDisciples.find { it.id == elderId } ?: return 0.0
        val sp = elder.spiritPlanting
        if (sp <= GameConfig.PolicyConfig.HERB_GARDEN_ELDER_SPIRIT_BASE) return 0.0

        val bonus = ((sp - GameConfig.PolicyConfig.HERB_GARDEN_ELDER_SPIRIT_BASE) /
                GameConfig.PolicyConfig.HERB_GARDEN_ELDER_SPIRIT_STEP) * 0.01
        // 体质/词条的职务加成：作为乘算因子作用于长老职能效果
        val posBonus = DiscipleStatCalculator.getPositionEffectBonus(elder, ElderSlotType.HERB_GARDEN)
        return min(bonus * (1.0 + posBonus), GameConfig.PolicyConfig.HERB_GARDEN_ELDER_MAX)
    }

    fun calculateAuraMaturityBonus(elderSlots: ElderSlots, allDisciples: List<Disciple>): Double {
        val activeSlot = elderSlots.herbGardenDisciples.firstOrNull { it.isActive } ?: return 0.0

        val disciple = allDisciples.find { it.id == activeSlot.discipleId } ?: return 0.0
        val sp = disciple.spiritPlanting
        if (sp <= GameConfig.PolicyConfig.HERB_GARDEN_DISCIPLE_SPIRIT_BASE) return 0.0

        val bonus = ((sp - GameConfig.PolicyConfig.HERB_GARDEN_DISCIPLE_SPIRIT_BASE) /
                GameConfig.PolicyConfig.HERB_GARDEN_DISCIPLE_SPIRIT_STEP) * 0.01
        return min(bonus, GameConfig.PolicyConfig.HERB_GARDEN_DISCIPLE_MAX)
    }

    fun isSpiritFieldInAura(spiritFieldInstanceId: String, placedBuildings: List<GridBuildingData>): Boolean {
        val sf = placedBuildings.find { it.instanceId == spiritFieldInstanceId } ?: return false

        val herbGardens = placedBuildings.filter {
            it.displayName == HERB_GARDEN_DISPLAY_NAME && it.sectId == sf.sectId
        }
        if (herbGardens.isEmpty()) return false
        return isInAura(sf, herbGardens)
    }

    /** 判定灵田是否处于任一灵植阁光环内（距离判定，部分覆盖也算命中） */
    private fun isInAura(sf: GridBuildingData, herbGardens: List<GridBuildingData>): Boolean {
        for (hg in herbGardens) {
            val hgCenterX = hg.gridX + hg.width / 2.0
            val hgCenterY = hg.gridY + hg.height / 2.0
            // Closest point on spirit field rect to herb garden center — partial coverage counts
            val closestX = hgCenterX.coerceIn(sf.gridX.toDouble(), (sf.gridX + sf.width).toDouble())
            val closestY = hgCenterY.coerceIn(sf.gridY.toDouble(), (sf.gridY + sf.height).toDouble())
            val dx = closestX - hgCenterX
            val dy = closestY - hgCenterY
            if (sqrt(dx * dx + dy * dy) <= GameConfig.HerbGarden.AURA_RADIUS_TILES) {
                return true
            }
        }
        return false
    }

    fun calculateEffectiveGrowTime(baseGrowTime: Int, totalSpeedBonus: Double): Int {
        if (totalSpeedBonus <= 0.0) return baseGrowTime
        return ceil(baseGrowTime / (1.0 + totalSpeedBonus)).toInt()
    }
}
