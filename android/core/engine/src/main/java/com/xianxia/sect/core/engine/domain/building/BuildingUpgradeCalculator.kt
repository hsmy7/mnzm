package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.util.FixedSectGateway
import com.xianxia.sect.core.util.GridSystem

/**
 * 单座建筑升级资格检查结果。
 */
sealed interface UpgradeCheckResult {
    /** 满足全部条件（等级 / 灵石 / 空间），可升级 */
    data object Upgradeable : UpgradeCheckResult

    /** 源建筑不在升级配置中，不可升级 */
    data object NotUpgradeable : UpgradeCheckResult

    /** 条件不满足，[reasons] 为逐条中文原因（直接用于提示框文案） */
    data class ConditionsUnmet(val reasons: List<String>) : UpgradeCheckResult
}

/**
 * 建筑升级资格/空间纯逻辑计算器（零 Android 依赖，可单测）。
 *
 * 升级占用判定：升级后建筑占地按目标建筑配置的 width×height 变化
 * （如单人住所 4×4 → 中级单人住所 6×6），须校验新占地仍在可建边界内
 * 且不与其他同宗门建筑重叠——升级不会改变建筑左上角坐标（gridX/gridY）。
 */
object BuildingUpgradeCalculator {

    /**
     * 校验单座建筑升级资格：宗门等级 / 灵石差价 / 升级后占地空间。
     *
     * @param data 当前游戏数据快照
     * @param instanceId 目标建筑实例 id
     * @return [UpgradeCheckResult.Upgradeable] / [UpgradeCheckResult.NotUpgradeable] /
     *         [UpgradeCheckResult.ConditionsUnmet]（含逐条原因）
     */
    fun checkUpgrade(data: GameData, instanceId: String): UpgradeCheckResult {
        val building = data.placedBuildings.find { it.instanceId == instanceId }
        val def = building?.let { BuildingUpgradeRegistry.findUpgrade(it.buildingId) }
        if (building == null || def == null) return UpgradeCheckResult.NotUpgradeable
        val reasons = mutableListOf<String>()

        val currentLevel = data.worldMapSects.find { it.isPlayerSect }?.level ?: SectLevel.SMALL
        if (currentLevel < SectLevel.MEDIUM) {
            reasons += "需要宗门等级达到中型（当前${SectLevel.levelName(currentLevel)}）"
        }

        val cost = BuildingUpgradeRegistry.upgradeCost(def)
        if (data.spiritStones < cost) {
            reasons += "灵石不足（升级需$cost 灵石，当前${data.spiritStones}）"
        }

        if (!canFitUpgrade(data.placedBuildings, building, def)) {
            reasons += "升级后占地扩大，空间不足"
        }

        return if (reasons.isEmpty()) UpgradeCheckResult.Upgradeable
        else UpgradeCheckResult.ConditionsUnmet(reasons)
    }

    /**
     * 目标建筑升级后新占地是否合法：可建边界内 + 不与其他同宗门建筑重叠。
     *
     * [buildings] 可为「升级中间态」列表（批量升级时用于增量校验，防止相邻
     * 建筑同时扩占地互相重叠）。
     *
     * @param buildings 参与重叠判定的建筑列表（通常为全量 placedBuildings）
     * @param building 待升级建筑（以其 gridX/gridY 为左上角，目标占地判定）
     * @param def 升级关系（决定目标占地尺寸）
     * @return 空间允许时 true
     */
    fun canFitUpgrade(
        buildings: List<GridBuildingData>,
        building: GridBuildingData,
        def: BuildingUpgradeDef
    ): Boolean {
        val target = BuildingFeatureRegistry.findByKey(def.targetKey) ?: return false
        val gridW = target.gridWidth
        val gridH = target.gridHeight
        return isInsideBuildableArea(building.gridX, building.gridY, gridW, gridH) &&
            buildings.none { other ->
                other.sectId == building.sectId &&
                    other.instanceId != building.instanceId &&
                    GridRect(building.gridX, building.gridY, gridW, gridH)
                        .overlaps(GridRect(other.gridX, other.gridY, other.width, other.height))
            }
    }

    /** 网格位置是否位于边界树木区域之内且不占用门楼固定结构占地。 */
    private fun isInsideBuildableArea(gridX: Int, gridY: Int, gridW: Int, gridH: Int): Boolean {
        val border = GameConfig.SectMap.BORDER_TREE_RING
        val withinMinCorner = gridX >= border && gridY >= border
        val withinMaxCorner = gridX + gridW <= GameConfig.SectMap.WORLD_WIDTH_CELLS - border &&
            gridY + gridH <= GameConfig.SectMap.WORLD_HEIGHT_CELLS - border
        if (!withinMinCorner || !withinMaxCorner) return false
        val blocked = FixedSectGateway.blockedCells
        return (gridY until gridY + gridH).none { cy ->
            (gridX until gridX + gridW).any { cx -> GridSystem.packCell(cx, cy) in blocked }
        }
    }
}

/**
 * 轴对齐矩形（网格坐标）——建筑放置与建筑升级占地重叠判定的单一事实。
 * 边界相接不算重叠。
 *
 * 上提自 feature:game BuildingDelegate.overlapsExisting 的矩形判定，
 * 供建筑放置与建筑升级两处共用，保证判定公式单一事实。
 */
data class GridRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    /** 与另一矩形是否重叠（边界相接不算重叠） */
    fun overlaps(other: GridRect): Boolean =
        x < other.x + other.width && x + width > other.x &&
            y < other.y + other.height && y + height > other.y
}
