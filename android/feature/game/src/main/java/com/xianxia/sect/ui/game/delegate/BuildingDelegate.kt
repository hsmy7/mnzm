package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.currentActiveSectId
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.ui.game.sect.GoldFingerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 建筑建造/拆除/搬迁/住宅管理委托。
 *
 * 职责：放置/批量放置/移动/拆除建筑，修正建筑尺寸，住宅分配与升级。
 */
class BuildingDelegate(
    private val gameEngine: GameEngine,
    private val buildingFacade: BuildingFacade,
    private val buildingConfigService: BuildingConfigService,
    private val scope: CoroutineScope,
    private val onDemolishSuccess: (String) -> Unit = {}
) {
    private var _currentBuildingDelegate: Any? = null

    /**
     * 放置建筑。通过 BuildingFeatureRegistry + SlotGroup 自动创建所有槽位。
     */
    fun placeBuilding(name: String, gridX: Int, gridY: Int, width: Int = 2, height: Int = 3) {
        scope.launch {
            val feature = BuildingFeatureRegistry.findByDisplayName(name) ?: return@launch
            val config = buildingConfigService.getBuildingConfigByDisplayName(name)
            val cost = config?.cost ?: feature.cost
            val (gridW, gridH) = buildingConfigService.getBuildingGridSize(name)
            val newBuildingInstanceId = java.util.UUID.randomUUID().toString()
            val activeId = gameEngine.currentActiveSectId()
            val newProductionSlots = mutableListOf<ProductionSlot>()

            gameEngine.updateGameData { data ->
                // 限建检查
                if (!feature.unlimitedBuild) {
                    if (data.placedBuildings.any { it.displayName == name && it.sectId == activeId }) return@updateGameData data
                }
                // 灵石检查
                if (data.spiritStones < cost) return@updateGameData data

                val newBuilding = GridBuildingData(
                    buildingId = feature.key, displayName = name,
                    gridX = gridX, gridY = gridY,
                    width = gridW, height = gridH,
                    sectId = activeId, instanceId = newBuildingInstanceId
                )

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
        scope.launch {
            val name = goldFingerState.buildingName
            val (gw, gh) = buildingConfigService.getBuildingGridSize(name)
            val cost = goldFingerState.buildingCost

            for ((cellKey, valid) in goldFingerState.cellValidity) {
                if (!valid) continue
                val gx = (cellKey shr 32).toInt()
                val gy = (cellKey and 0xFFFF_FFFF).toInt()
                placeBuilding(name = name, gridX = gx, gridY = gy, width = gw, height = gh)
            }
        }
    }

    /** 移动已放置的建筑到新坐标。 */
    suspend fun moveBuilding(instanceId: String, newGridX: Int, newGridY: Int) {
        buildingFacade.moveBuildingDirect(instanceId, newGridX, newGridY)
    }

    /** 拆除建筑，返还一半造价并提示。 */
    fun demolishBuilding(instanceId: String) {
        scope.launch {
            val snapshot = gameEngine.gameDataSnapshot
            val building = snapshot.placedBuildings.find { it.instanceId == instanceId } ?: return@launch
            val config = buildingConfigService.getBuildingConfigByDisplayName(building.displayName)
            val cost = config?.cost ?: 1000L
            val refund = cost / 2

            gameEngine.removeBuilding(instanceId, refund)
            onDemolishSuccess("已拆除${building.displayName}，返还灵石×$refund")
        }
    }

    /** 修正已有存档中的建筑尺寸（存档加载后调用）。 */
    fun fixupBuildingSizesIfNeeded() {
        scope.launch {
            gameEngine.updateGameData { data ->
                val fixed = buildingConfigService.fixupBuildingSizes(data.placedBuildings)
                val withIds = GridBuildingData.ensureAllHaveInstanceId(fixed)
                if (withIds != data.placedBuildings) data.copy(placedBuildings = withIds) else data
            }
        }
    }

    /** 分配弟子到住宅 */
    fun assignToResidence(buildingInstanceId: String, slotIndex: Int, discipleId: String) {
        scope.launch {
            // 释放旧槽位（自动移除前职务）
            gameEngine.releaseDiscipleFromAllSlotsAtomic(discipleId)

            val discipleName = gameEngine.getDiscipleAggregate(discipleId)?.name ?: ""
            gameEngine.updateGameData { data ->
                val cleared = data.residenceSlots.map { slot ->
                    if (slot.discipleId == discipleId) slot.copy(discipleId = "", discipleName = "") else slot
                }.toMutableList()

                val existingIndex = cleared.indexOfFirst {
                    it.buildingInstanceId == buildingInstanceId && it.slotIndex == slotIndex
                }
                val newSlot = ResidenceSlot(
                    buildingInstanceId = buildingInstanceId, slotIndex = slotIndex,
                    discipleId = discipleId, discipleName = discipleName
                )
                if (existingIndex >= 0) cleared[existingIndex] = newSlot else cleared.add(newSlot)
                data.copy(residenceSlots = cleared)
            }

            val slotRef = SlotRef(
                category = SlotCategory.RESIDENCE_SLOT,
                slotType = "${buildingInstanceId}:${slotIndex}",
                slotId = "residence_${buildingInstanceId}_${slotIndex}"
            )
            gameEngine.confirmAssignDisciple(discipleId, slotRef)
        }
    }

    /** 从住宅移除弟子 */
    fun removeFromResidence(buildingInstanceId: String, slotIndex: Int) {
        scope.launch {
            // 取出当前住户 ID 用于释放注册表
            val currentDiscipleId = gameEngine.gameDataSnapshot.residenceSlots
                .find { it.buildingInstanceId == buildingInstanceId && it.slotIndex == slotIndex }
                ?.discipleId.orEmpty()

            gameEngine.updateGameData { data ->
                data.copy(residenceSlots = data.residenceSlots.map { slot ->
                    if (slot.buildingInstanceId == buildingInstanceId && slot.slotIndex == slotIndex)
                        slot.copy(discipleId = "", discipleName = "")
                    else slot
                })
            }

            if (currentDiscipleId.isNotEmpty()) {
                gameEngine.releaseDiscipleAssignment(currentDiscipleId)
            }
        }
    }

    /** 判断住所是否可升级 */
    fun canUpgradeResidence(buildingInstanceId: String): Boolean {
        val data = gameEngine.gameDataSnapshot
        val building = data.placedBuildings.find { it.instanceId == buildingInstanceId } ?: return false
        val feature = BuildingFeatureRegistry.findByDisplayName(building.displayName)
        return feature?.upgradeTo != null
    }

    /** 升级单人住所（根据 BuildingFeature.upgradeTo 自动确定升级目标和费用） */
    fun upgradeSingleResidence(buildingInstanceId: String) {
        scope.launch {
            val snapshot = gameEngine.gameDataSnapshot
            val building = snapshot.placedBuildings.find { it.instanceId == buildingInstanceId } ?: return@launch
            val feature = BuildingFeatureRegistry.findByDisplayName(building.displayName) ?: return@launch
            val upgradeKey = feature.upgradeTo ?: return@launch
            val upgradeFeature = BuildingFeatureRegistry.findByKey(upgradeKey) ?: return@launch
            val upgradeCost = upgradeFeature.upgradeCost
            gameEngine.updateGameData { data ->
                if (data.spiritStones < upgradeCost) return@updateGameData data
                data.copy(
                    spiritStones = data.spiritStones - upgradeCost,
                    placedBuildings = data.placedBuildings.map { b ->
                        if (b.instanceId == buildingInstanceId && b.displayName == feature.displayName)
                            b.copy(displayName = upgradeFeature.displayName)
                        else b
                    }
                )
            }
        }
    }
}
