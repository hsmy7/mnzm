package com.xianxia.sect.core.engine.domain.road

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.RoadData
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.FixedSectGateway
import com.xianxia.sect.core.util.GridSystem
import com.xianxia.sect.core.util.RoadPlacementResult
import com.xianxia.sect.core.util.RoadTileType
import com.xianxia.sect.core.util.RoadTiling
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * 道路门面实现。
 *
 * 玩家只负责放置/删除道路，本类按邻接位掩码自动拼接，并只重算「当前格 + 上下左右」
 * 最多 5 格（O(1) 局部更新，满足大规模地图性能要求）。所有改动经 [GameStateStore]
 * 原子事务提交，不直接触碰 UI 层。
 *
 * batch-07 写者下沉：AUTHORITATIVE 稳态下放置/删除事务经 [roadNativeTx] 转发
 * C++（road_tx.h——校验链/灵石扣减/邻域掩码重算 C++ 唯一真相），成功臂经
 * 脏段镜像回读同步 gameData.roads/spiritStones；native 降级或失败信封回退
 * Kotlin 原路径（下方原实现保留为回退臂，语义逐字不变）。
 *
 * DI 装配：本类不经 @Inject 构造注入——镜像通道是 GameEngineCore 手工持有的
 * StateSyncService 单例（其 reverseSender 默认 lambda 无 Dagger 绑定，构造注入
 * 会 MissingBinding 且分叉反向通道实例），由 [createRoadFacade] 工厂接线、
 * CoreModule @Provides 提供单例。构造器的 stateSyncService 形参仅供测试直传。
 */
class RoadFacadeImpl(
    private val stateStore: GameStateStore,
    // 镜像通道（native 臂脏段回读用）；null = 测试替身场景，恒走 Kotlin 回退臂
    private val stateSyncService: StateSyncService? = null
) : RoadFacade {

    private val width get() = GameConfig.SectMap.WORLD_WIDTH_CELLS
    private val height get() = GameConfig.SectMap.WORLD_HEIGHT_CELLS
    private val border get() = GameConfig.SectMap.BORDER_TREE_RING

    override fun canPlaceRoad(gridX: Int, gridY: Int): Boolean {
        val data = stateStore.gameDataSnapshot
        val occupied = buildingOccupiedCells(data) + FixedSectGateway.blockedCells
        return canPlaceCell(gridX, gridY, occupied, data)
    }

    @Suppress("ReturnCount")  // 多 return 为放置失败分支（Blocked 原因逐级短路）+ native 臂降级，有 RoadFacadeImplTest 守护
    override fun placeRoad(gridX: Int, gridY: Int): RoadPlacementResult {
        roadNativeTx(ActionIds.ROAD_PLACE, gridX, gridY)?.let { return it }
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
        roadNativeTx(ActionIds.ROAD_REMOVE, gridX, gridY)?.let { return it }
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

    /**
     * 道路事务 native 转发（batch-07 写者下沉——稳态写者归 C++ 唯一真相）。
     *
     * AUTHORITATIVE 门控 + 镜像回读：tryExecuteNative 成功后经
     * applyDirtyFromNative 把 C++ 脏段（gameData.roads/spiritStones）回读镜像；
     * 失败信封/降级返回 null（调用方回退 Kotlin 原路径重执行校验链——双实现
     * 并行契约，Blocked 文案由 Kotlin 臂产出）。roads 为 gameData 快照列，
     * 成功臂无 Room 回放需求（与生产槽位不同）。
     *
     * 占用集合在 Kotlin 组装（本宗建筑占地 ∪ 固定结构）：C++ GridBuildingData
     * 无 sectId 字段（models.h 本批禁改），宗门过滤不可在 C++ 复现——见
     * road_tx.h 头注释。镜像写入不参与反向捕获，GameEngineRoadOps 的即时
     * 回导对本臂为空窗口零发送（契约自洽）。
     */
    private fun roadNativeTx(actionId: Int, gridX: Int, gridY: Int): RoadPlacementResult? {
        if (!NativeEngineFlag.authoritative) return null
        if (stateSyncService == null) return null
        GameEngineNativeOps.tryExecuteNative(
            stateSyncService = stateSyncService,
            actionId = actionId,
            paramsJson = params {
                put("gridX", gridX)
                put("gridY", gridY)
                put("width", width)
                put("height", height)
                put("border", border)
                put("cost", GameConfig.Road.COST_PER_CELL)
                put(
                    "occupiedCells",
                    JsonArray(occupiedCellsForNative().map { JsonPrimitive(it) })
                )
            }
        ) ?: return null
        return RoadPlacementResult.Success(neighborhoodCells(gridX, gridY))
    }

    /** 占用格集合（packed cell）展开为协议数组：本宗建筑占地 ∪ 固定结构禁建格。 */
    private fun occupiedCellsForNative(): List<Long> {
        val data = stateStore.gameDataSnapshot
        return (buildingOccupiedCells(data) + FixedSectGateway.blockedCells).toList()
    }

    // ── 可建造判定（独立方法，逻辑不散落 UI）────────────────────────

    @Suppress("ReturnCount", "ComplexCondition")  // 边界+占用+道路三重判据逐级短路，有 RoadFacadeImplTest 守护
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

    @Suppress("ComplexCondition")  // 与 canPlaceCell 同构的 reason 分支，保持对应可读性
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
    @Suppress("LoopWithTooManyJumpStatements")  // 邻域重算的 continue 短路为 O(1) 局部更新本质，有测试守护
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

/**
 * DI 装配工厂（CoreModule @Provides 调用）：镜像通道取 [GameEngineCore] 手工
 * 持有的 StateSyncService 单例（internal 可见性限定在本模块内接线，:app 不直读），
 * 保证与引擎 tick ⑤/即时回导共用同一反向通道实例。
 */
fun createRoadFacade(stateStore: GameStateStore, gameEngineCore: GameEngineCore): RoadFacadeImpl =
    RoadFacadeImpl(stateStore, gameEngineCore.stateSyncServiceRef)
