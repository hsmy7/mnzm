package com.xianxia.sect.ui.game.delegate

import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.currentActiveSectId
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.engine.addProductionSlot
import com.xianxia.sect.core.engine.assignToResidenceAtomic
import com.xianxia.sect.core.engine.moveBuildingDirect
import com.xianxia.sect.core.engine.removeBuilding
import com.xianxia.sect.core.engine.removeBuildings
import com.xianxia.sect.core.engine.removeFromResidenceAtomic
import com.xianxia.sect.core.engine.updateGameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.ui.game.sect.GoldFingerState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext



/**
 * 建筑建造/拆除/搬迁/住宅管理委托。
 *
 * 职责：放置/批量放置/移动/拆除建筑，修正建筑尺寸，住宅分配。
 */
class BuildingDelegate(
    private val gameEngine: GameEngine,
    private val buildingFacade: BuildingFacade,
    private val buildingConfigService: BuildingConfigService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onDemolishSuccess: (String) -> Unit = {}
) {
    private companion object {
        private const val TAG = "BuildingDelegate"
    }

    private var _currentBuildingDelegate: Any? = null

    /**
     * 放置建筑。通过 BuildingFeatureRegistry + SlotGroup 自动创建所有槽位。
     */
    fun placeBuilding(name: String, gridX: Int, gridY: Int, width: Int = 2, height: Int = 3) {
        gameEngine.launchOnEngine {
            doPlaceBuilding(name, gridX, gridY, width, height)
        }
    }

    /** placeBuilding 的引擎线程执行体，供 batchPlaceBuilding 直接复用（避免递归 launchOnEngine）。 */
    private suspend fun doPlaceBuilding(name: String, gridX: Int, gridY: Int, width: Int, height: Int) {
        val feature = BuildingFeatureRegistry.findByDisplayName(name) ?: return
        if (!hasRequiredSectLevel(feature)) return
        val config = buildingConfigService.getBuildingConfigByDisplayName(name)
        val cost = config?.cost ?: feature.cost
        val (gridW, gridH) = buildingConfigService.getBuildingGridSize(name)
        if (!isInsideBuildableArea(gridX, gridY, gridW, gridH)) return
        val newBuildingInstanceId = java.util.UUID.randomUUID().toString()
        val activeId = gameEngine.currentActiveSectId()
        val newProductionSlots = mutableListOf<ProductionSlot>()

        val newBuilding = GridBuildingData(
            buildingId = feature.key, displayName = name,
            gridX = gridX, gridY = gridY,
            width = gridW, height = gridH,
            sectId = activeId, instanceId = newBuildingInstanceId
        )

        gameEngine.updateGameData { data ->
            if (!isPlacementAllowed(
                    data = data, feature = feature, cost = cost,
                    activeId = activeId, target = newBuilding
                )
            ) {
                return@updateGameData data
            }

            // 通过 slotGroups 自动创建所有槽位
            val results = feature.slotGroups.map { group ->
                group.createSlots(newBuildingInstanceId, data, activeId, feature)
            }

            newProductionSlots += results.flatMap { it.productionSlots }
            data.copy(
                spiritStones = data.spiritStones - cost,
                placedBuildings = data.placedBuildings + newBuilding,
                spiritFieldPlants = data.spiritFieldPlants + results.flatMap { it.spiritFieldPlants },
                spiritMineSlots = data.spiritMineSlots + results.flatMap { it.spiritMineSlots },
                patrolSlots = data.patrolSlots + results.flatMap { it.patrolSlots },
                patrolConfigs = data.patrolConfigs + results.flatMap { it.patrolConfigs },
                residenceSlots = data.residenceSlots + results.flatMap { it.residenceSlots },
                productionSlots = data.productionSlots + newProductionSlots,
                warehouseGarrisons = data.warehouseGarrisons + results.flatMap { it.warehouseGarrisons },
                librarySlots = data.librarySlots + results.flatMap { it.librarySlots }
            )
        }

        // 生产槽位同步写入 Repository
        newProductionSlots.forEach { buildingFacade.addProductionSlot(it) }
    }

    /** 宗门等级检查（防御层：即使 UI 层已拦截，引擎层也做硬检查）。 */
    private fun hasRequiredSectLevel(feature: com.xianxia.sect.core.engine.domain.building.BuildingFeature): Boolean {
        if (feature.requiredSectLevel <= 0) return true
        val currentLevel = gameEngine.gameDataSnapshot
            .worldMapSects.find { it.isPlayerSect }?.level ?: SectLevel.SMALL
        return currentLevel >= feature.requiredSectLevel
    }

    /** 第二层防御：验证网格位置不在边界树木区域内。 */
    private fun isInsideBuildableArea(gridX: Int, gridY: Int, gridW: Int, gridH: Int): Boolean {
        val border = GameConfig.SectMap.BORDER_TREE_RING
        return gridX >= border && gridY >= border &&
            gridX + gridW <= GameConfig.SectMap.WORLD_WIDTH_CELLS - border &&
            gridY + gridH <= GameConfig.SectMap.WORLD_HEIGHT_CELLS - border
    }

    /**
     * 放置前校验（事务内）：限建数量 + 占用重叠复查 + 灵石。
     *
     * 第三层防御占用重叠复查（2026-08-06 第一性原理兜底）：UI 已判 Valid，
     * 引擎兜底防 UI/数据源漂移——旧档 sectId 不匹配建筑从占用检测消失的窗口期内，
     * 拖放显示绿色也不得真的叠建。
     */
    private fun isPlacementAllowed(
        data: com.xianxia.sect.core.model.GameData,
        feature: com.xianxia.sect.core.engine.domain.building.BuildingFeature,
        cost: Long,
        activeId: String,
        target: GridBuildingData
    ): Boolean {
        if (exceedsBuildLimit(data, feature, target, activeId)) return false
        val overlap = overlapsExisting(
            buildings = data.placedBuildings,
            sectId = activeId,
            gridX = target.gridX, gridY = target.gridY,
            width = target.width, height = target.height
        )
        if (overlap) {
            Log.w(TAG, "放置拒绝：${target.displayName} 与已有建筑重叠 " +
                "(${target.gridX},${target.gridY}) ${target.width}x${target.height}")
        }
        return !overlap && data.spiritStones >= cost
    }

    /** 限建数量检查（全局唯一 / 同宗门限建）。 */
    private fun exceedsBuildLimit(
        data: com.xianxia.sect.core.model.GameData,
        feature: com.xianxia.sect.core.engine.domain.building.BuildingFeature,
        target: GridBuildingData,
        activeId: String
    ): Boolean {
        if (feature.unlimitedBuild) return false
        val exists = if (feature.isGloballyUnique) {
            val globalCount = data.placedBuildings.count { it.displayName == target.displayName }
            if (globalCount > 1) {
                Log.w(TAG, "全局唯一建筑 '${target.displayName}' 在旧存档中存在 $globalCount 座，升级后限制为 1 座")
            }
            globalCount > 0
        } else {
            data.placedBuildings.any { it.displayName == target.displayName && it.sectId == activeId }
        }
        return exists
    }

    /** 查询建筑放置所需灵石。 */
    fun getBuildingCost(displayName: String): Long {
        return buildingConfigService.getBuildingConfigByDisplayName(displayName)?.cost ?: 1000L
    }

    /** 查询建筑网格尺寸。 */
    fun getBuildingGridSize(displayName: String): Pair<Int, Int> {
        return buildingConfigService.getBuildingGridSize(displayName)
    }

    /** 查询建筑精灵视觉比例尺寸。 */
    fun getBuildingSpriteSize(displayName: String): Pair<Int, Int> {
        return buildingConfigService.getBuildingSpriteSize(displayName)
    }

    /** 获取所有建筑的精灵比例尺寸映射。 */
    fun getAllBuildingSpriteSizes(): Map<String, Pair<Int, Int>> {
        return buildingConfigService.getAllBuildingSpriteSizes()
    }

    /** 金手指一键批量建造 */
    fun batchPlaceBuilding(goldFingerState: GoldFingerState) {
        if (!goldFingerState.isActive || goldFingerState.canBuildCount <= 0) return
        gameEngine.launchOnEngine {
            val name = goldFingerState.buildingName
            val (gw, gh) = buildingConfigService.getBuildingGridSize(name)
            val cost = goldFingerState.buildingCost

            for ((cellKey, valid) in goldFingerState.cellValidity) {
                if (!valid) continue
                val gx = (cellKey shr 32).toInt()
                val gy = (cellKey and 0xFFFF_FFFF).toInt()
                doPlaceBuilding(name = name, gridX = gx, gridY = gy, width = gw, height = gh)
            }
        }
    }

    /** 移动已放置的建筑到新坐标。 */
    suspend fun moveBuilding(instanceId: String, newGridX: Int, newGridY: Int) {
        withContext(dispatcher) {
            buildingFacade.moveBuildingDirect(instanceId, newGridX, newGridY)
        }
    }

    /** 拆除建筑，返还一半造价并提示。 */
    fun demolishBuilding(instanceId: String) {
        gameEngine.launchOnEngine {
            val snapshot = gameEngine.gameDataSnapshot
            val building = snapshot.placedBuildings.find { it.instanceId == instanceId } ?: return@launchOnEngine
            val config = buildingConfigService.getBuildingConfigByDisplayName(building.displayName)
            val cost = config?.cost ?: 1000L
            val refund = cost / 2

            gameEngine.removeBuilding(instanceId, refund)
            withContext(Dispatchers.Main) {
                onDemolishSuccess("已拆除${building.displayName}，返还灵石×$refund")
            }
        }
    }

    /**
     * 一键批量拆除：单次事务移除多座建筑并汇总返还灵石。
     * 未知/已不存在的实例自动跳过。
     */
    fun demolishBuildings(instanceIds: List<String>) {
        gameEngine.launchOnEngine {
            val snapshot = gameEngine.gameDataSnapshot
            val refunds = instanceIds.mapNotNull { id ->
                snapshot.placedBuildings.find { it.instanceId == id }?.let { b ->
                    val cost = buildingConfigService.getBuildingConfigByDisplayName(b.displayName)?.cost ?: 1000L
                    id to cost / 2
                }
            }.toMap()
            if (refunds.isEmpty()) return@launchOnEngine
            gameEngine.removeBuildings(refunds)
            withContext(Dispatchers.Main) {
                onDemolishSuccess("已拆除${refunds.size}座建筑，返还灵石×${refunds.values.sum()}")
            }
        }
    }

    /** 修正已有存档中的建筑尺寸（存档加载后调用）。 */
    fun fixupBuildingSizesIfNeeded() {
        gameEngine.launchOnEngine {
            gameEngine.updateGameData { data ->
                val fixed = buildingConfigService.fixupBuildingSizes(data.placedBuildings)
                val withIds = GridBuildingData.ensureAllHaveInstanceId(fixed)
                if (withIds != data.placedBuildings) data.copy(placedBuildings = withIds) else data
            }
        }
    }

    /** 分配弟子到住宅（原子操作）。
     *
     * 使用 [withContext(dispatcher)] 确保阻塞的 [stateStore.update] 不在 Main 线程执行，
     * 避免 DEBUG error() 崩溃和 RELEASE ANR。
     */
    suspend fun assignToResidence(
        buildingInstanceId: String, slotIndex: Int, discipleId: String
    ): DomainResult<Unit> = withContext(dispatcher) {
        gameEngine.assignToResidenceAtomic(buildingInstanceId, slotIndex, discipleId)
    }

    /** 从住宅移除弟子（原子操作）。
     *
     * 使用 [withContext(dispatcher)] 确保阻塞的 [stateStore.update] 不在 Main 线程执行，
     * 避免 DEBUG error() 崩溃和 RELEASE ANR。
     */
    suspend fun removeFromResidence(
        buildingInstanceId: String, slotIndex: Int
    ): DomainResult<Unit> = withContext(dispatcher) {
        gameEngine.removeFromResidenceAtomic(buildingInstanceId, slotIndex)
    }
}

/**
 * 判断目标矩形是否与同一宗门作用域内的已有建筑重叠（引擎层放置防御，2026-08-06）。
 *
 * 作用域限定 [sectId]：不同宗门的建筑使用独立网格，坐标互不干扰（与
 * MainGameScreen effectivePlacedBuildings / GridSystem 判定同源）。
 *
 * @param buildings 完整建筑列表（跨宗门全局）
 * @param sectId 当前作用域（activeSectId）
 * @param gridX 目标建筑左上角 X（格）
 * @param gridY 目标建筑左上角 Y（格）
 * @param width 目标建筑宽度（格）
 * @param height 目标建筑高度（格）
 * @return 存在重叠时 true
 */
internal fun overlapsExisting(
    buildings: List<GridBuildingData>,
    sectId: String,
    gridX: Int,
    gridY: Int,
    width: Int,
    height: Int
): Boolean {
    return buildings.any { other ->
        other.sectId == sectId &&
            gridX < other.gridX + other.width &&
            gridX + width > other.gridX &&
            gridY < other.gridY + other.height &&
            gridY + height > other.gridY
    }
}
