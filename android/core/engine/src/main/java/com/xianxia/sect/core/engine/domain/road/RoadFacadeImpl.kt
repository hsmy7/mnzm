package com.xianxia.sect.core.engine.domain.road

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.RoadData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.FixedSectGateway
import com.xianxia.sect.core.util.GridSystem
import com.xianxia.sect.core.util.RoadPlacementResult
import com.xianxia.sect.core.util.RoadTileType
import com.xianxia.sect.core.util.RoadTiling
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 道路门面实现。
 *
 * 玩家只负责放置/删除道路，本类按邻接位掩码自动拼接，并只重算「当前格 + 上下左右」
 * 最多 5 格（O(1) 局部更新，满足大规模地图性能要求）。所有改动经 [GameStateStore]
 * 原子事务提交，不直接触碰 UI 层。
 */
@Singleton
class RoadFacadeImpl @Inject constructor(
    private val stateStore: GameStateStore
) : RoadFacade {

    private val width get() = GameConfig.SectMap.WORLD_WIDTH_CELLS
    private val height get() = GameConfig.SectMap.WORLD_HEIGHT_CELLS
    private val border get() = GameConfig.SectMap.BORDER_TREE_RING

    override fun canPlaceRoad(gridX: Int, gridY: Int): Boolean {
        val data = stateStore.gameDataSnapshot
        val occupied = buildingOccupiedCells(data) + FixedSectGateway.blockedCells
        return canPlaceCell(gridX, gridY, occupied, data)
    }

    override fun placeRoad(gridX: Int, gridY: Int): RoadPlacementResult {
        val data = stateStore.gameDataSnapshot
        val occupied = buildingOccupiedCells(data) + FixedSectGateway.blockedCells
        if (!canPlaceCell(gridX, gridY, occupied, data)) {
            return RoadPlacementResult.Blocked(blockReason(gridX, gridY, occupied, data))
        }
        if (data.spiritStones < GameConfig.Road.COST_PER_CELL) {
            return RoadPlacementResult.Blocked("灵石不足")
        }
        stateStore.update {
            val gd = gameData
            if (gd.roads.any { it.gridX == gridX && it.gridY == gridY }) return@update
            val added = gd.roads + RoadData(gridX, gridY, 0, RoadTileType.SINGLE.name)
            gameData = gd.copy(
                spiritStones = gd.spiritStones - GameConfig.Road.COST_PER_CELL,
                roads = recomputeNeighborhood(added, gridX, gridY)
            )
        }
        return RoadPlacementResult.Success(neighborhoodCells(gridX, gridY))
    }

    override fun removeRoad(gridX: Int, gridY: Int): RoadPlacementResult {
        val existed = stateStore.gameDataSnapshot.roads.any {
            it.gridX == gridX && it.gridY == gridY
        }
        if (!existed) return RoadPlacementResult.Blocked("该格没有道路")
        stateStore.update {
            val gd = gameData
            val remaining = gd.roads.filterNot { it.gridX == gridX && it.gridY == gridY }
            gameData = gd.copy(roads = recomputeNeighborhood(remaining, gridX, gridY))
        }
        return RoadPlacementResult.Success(neighborhoodCells(gridX, gridY))
    }

    // ── 可建造判定（独立方法，逻辑不散落 UI）────────────────────────

    private fun canPlaceCell(
        gridX: Int,
        gridY: Int,
        occupied: Set<Long>,
        data: GameData
    ): Boolean {
        if (gridX < border || gridY < border ||
            gridX >= width - border || gridY >= height - border
        ) return false
        if (occupied.contains(GridSystem.packCell(gridX, gridY))) return false
        if (data.roads.any { it.gridX == gridX && it.gridY == gridY }) return false
        return true
    }

    private fun blockReason(
        gridX: Int,
        gridY: Int,
        occupied: Set<Long>,
        data: GameData
    ): String = when {
        gridX < border || gridY < border ||
            gridX >= width - border || gridY >= height - border -> "网格过小/越界可建环之外"
        occupied.contains(GridSystem.packCell(gridX, gridY)) -> "该格已有建筑或固定结构"
        data.roads.any { it.gridX == gridX && it.gridY == gridY } -> "该格已是道路"
        else -> "无法放置"
    }

    // ── 邻接重算 ─────────────────────────────────────────────────

    /** 当前 + 上/下/左/右 共 5 格坐标。 */
    private fun neighborhoodCells(x: Int, y: Int): List<Pair<Int, Int>> =
        listOf(x to y, x to y - 1, x to y + 1, x - 1 to y, x + 1 to y)

    /**
     * 对给定道路集合，重算 [gridX, gridY] 及其上下左右（最多 5 格）的位掩码与形态。
     * 非道路格自动被移除。只更新受影响格，O(1)。
     */
    private fun recomputeNeighborhood(
        roads: List<RoadData>,
        gridX: Int,
        gridY: Int
    ): List<RoadData> {
        val cellSet = HashSet<Long>(roads.size * 2)
        for (r in roads) cellSet.add(GridSystem.packCell(r.gridX, r.gridY))
        val byKey = HashMap<Long, RoadData>(roads.size)
        for (r in roads) byKey[GridSystem.packCell(r.gridX, r.gridY)] = r

        var result = roads
        for ((nx, ny) in neighborhoodCells(gridX, gridY)) {
            val key = GridSystem.packCell(nx, ny)
            if (key !in cellSet) continue  // 该格非道路（含刚删除的中心格）
            val mask = RoadTiling.bitmaskAt(cellSet, nx, ny, width, height)
            val type = RoadTiling.tileTypeForBitmask(mask)
            val updated = byKey[key]?.copy(bitMask = mask, roadType = type.name) ?: continue
            byKey[key] = updated
            result = result.map { if (GridSystem.packCell(it.gridX, it.gridY) == key) updated else it }
        }
        return result
    }

    /** 已放置建筑（本宗）占用的格子集合。 */
    private fun buildingOccupiedCells(data: GameData): Set<Long> {
        val sectId = data.activeSectId
        val buildings = data.placedBuildings.filter { it.sectId == sectId || it.sectId.isEmpty() }
        val cells = HashSet<Long>()
        for (b: GridBuildingData in buildings) {
            for (cx in b.gridX until b.gridX + b.width) {
                for (cy in b.gridY until b.gridY + b.height) {
                    cells.add(GridSystem.packCell(cx, cy))
                }
            }
        }
        return cells
    }
}
