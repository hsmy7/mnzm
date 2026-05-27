package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.ui.game.building.BuildingDef
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

object HerbGardenAuraService {

    fun calculateElderMaturityBonus(elderSlots: ElderSlots, allDisciples: List<Disciple>): Double {
        val elderId = elderSlots.herbGardenElder
        if (elderId.isBlank()) return 0.0

        val elder = allDisciples.find { it.id == elderId } ?: return 0.0
        val sp = elder.spiritPlanting
        if (sp <= GameConfig.PolicyConfig.HERB_GARDEN_ELDER_SPIRIT_BASE) return 0.0

        val bonus = ((sp - GameConfig.PolicyConfig.HERB_GARDEN_ELDER_SPIRIT_BASE) /
                GameConfig.PolicyConfig.HERB_GARDEN_ELDER_SPIRIT_STEP) * 0.01
        return min(bonus, GameConfig.PolicyConfig.HERB_GARDEN_ELDER_MAX)
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
            it.displayName == BuildingDef.HERB_GARDEN.displayName && it.sectId == sf.sectId
        }
        if (herbGardens.isEmpty()) return false

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
