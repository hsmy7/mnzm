package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.currentActiveSectId
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.getDiscipleAggregate
import com.xianxia.sect.core.engine.removeBuilding
import com.xianxia.sect.core.engine.updateGameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.PatrolConfig
import com.xianxia.sect.ui.game.building.BuildingDef
import com.xianxia.sect.ui.game.building.BuildingRegistry
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
     * 放置建筑。根据建筑类型自动创建对应的生产槽位（炼丹、炼器）、
     * 灵矿槽位、巡逻楼槽位、住宅槽位等。
     */
    fun placeBuilding(name: String, gridX: Int, gridY: Int, width: Int = 2, height: Int = 3) {
        scope.launch {
            val config = buildingConfigService.getBuildingConfigByDisplayName(name)
            val cost = config?.cost ?: 1000L
            val (gridW, gridH) = buildingConfigService.getBuildingGridSize(name)

            val newBuildingInstanceId = java.util.UUID.randomUUID().toString()

            var newProductionSlot: ProductionSlot? = null

            val activeId = gameEngine.currentActiveSectId()
            gameEngine.updateGameData { data ->
                when {
                    BuildingRegistry.hasNoLimit(name) -> { /* No build limit */ }
                    else -> {
                        if (data.placedBuildings.any { it.displayName == name && it.sectId == activeId }) return@updateGameData data
                    }
                }
                if (data.spiritStones < cost) return@updateGameData data

                val newBuilding = GridBuildingData(
                    buildingId = name,
                    displayName = name,
                    gridX = gridX, gridY = gridY,
                    width = gridW, height = gridH,
                    sectId = activeId,
                    instanceId = newBuildingInstanceId
                )

                newProductionSlot = when (name) {
                    BuildingDef.ALCHEMY.displayName -> {
                        val idx = data.placedBuildings.count { it.displayName == BuildingDef.ALCHEMY.displayName }
                        ProductionSlot.createIdle(slotIndex = idx, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")
                            .copy(buildingInstanceId = newBuildingInstanceId)
                    }
                    BuildingDef.FORGE.displayName -> {
                        val idx = data.placedBuildings.count { it.displayName == BuildingDef.FORGE.displayName }
                        ProductionSlot.createIdle(slotIndex = idx, buildingType = BuildingType.FORGE, buildingId = "forge")
                            .copy(buildingInstanceId = newBuildingInstanceId)
                    }
                    else -> null
                }

                val newSlots = if (name == BuildingDef.SPIRIT_MINE.displayName) {
                    val nextIndex = data.spiritMineSlots.size
                    (0 until 3).map { SpiritMineSlot(index = nextIndex + it, buildingInstanceId = newBuilding.instanceId) }
                } else emptyList()

                val newPatrolSlots = if (name == BuildingDef.PATROL_TOWER.displayName) {
                    val nextIndex = data.patrolSlots.size
                    val slotCount = buildingConfigService.getSlotCountByDisplayName(name)
                    (0 until slotCount).map { PatrolSlot(index = nextIndex + it, buildingInstanceId = newBuilding.instanceId) }
                } else emptyList()

                val newPatrolConfigs = if (name == BuildingDef.PATROL_TOWER.displayName) {
                    data.patrolConfigs + PatrolConfig()
                } else emptyList()

                val newResidenceSlots = when (name) {
                    BuildingDef.SINGLE_RESIDENCE.displayName -> (0 until 1).map { ResidenceSlot(buildingInstanceId = newBuilding.instanceId, slotIndex = it) }
                    BuildingDef.MULTI_RESIDENCE.displayName -> (0 until 4).map { ResidenceSlot(buildingInstanceId = newBuilding.instanceId, slotIndex = it) }
                    else -> emptyList()
                }

                val newSpiritFieldPlants = if (name == BuildingDef.SPIRIT_FIELD.displayName) {
                    listOf(SpiritFieldPlant(buildingInstanceId = newBuilding.instanceId, sectId = activeId))
                } else emptyList()

                @Suppress("DEPRECATION")
                val slot = newProductionSlot
                val updatedProductionSlots = if (slot != null) data.productionSlots + slot else data.productionSlots

                data.copy(
                    spiritStones = data.spiritStones - cost,
                    placedBuildings = data.placedBuildings + newBuilding,
                    spiritFieldPlants = data.spiritFieldPlants + newSpiritFieldPlants,
                    spiritMineSlots = data.spiritMineSlots + newSlots,
                    patrolSlots = if (newPatrolSlots.isNotEmpty()) data.patrolSlots + newPatrolSlots else data.patrolSlots,
                    patrolConfigs = if (newPatrolConfigs.isNotEmpty()) newPatrolConfigs else data.patrolConfigs,
                    residenceSlots = data.residenceSlots + newResidenceSlots,
                    productionSlots = updatedProductionSlots
                )
            }

            newProductionSlot?.let { slot ->
                buildingFacade.addProductionSlot(slot)
            }
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
        }
    }

    /** 从住宅移除弟子 */
    fun removeFromResidence(buildingInstanceId: String, slotIndex: Int) {
        scope.launch {
            gameEngine.updateGameData { data ->
                data.copy(residenceSlots = data.residenceSlots.map { slot ->
                    if (slot.buildingInstanceId == buildingInstanceId && slot.slotIndex == slotIndex)
                        slot.copy(discipleId = "", discipleName = "")
                    else slot
                })
            }
        }
    }

    /** 判断住所是否可升级 */
    fun canUpgradeResidence(buildingInstanceId: String): Boolean {
        val data = gameEngine.gameDataSnapshot
        val building = data.placedBuildings.find { it.instanceId == buildingInstanceId } ?: return false
        return building.displayName == "单人住所"
    }

    /** 升级单人住所为中级单人住所 */
    fun upgradeSingleResidence(buildingInstanceId: String) {
        scope.launch {
            gameEngine.updateGameData { data ->
                val canAfford = data.spiritStones >= 50000L
                if (!canAfford) return@updateGameData data
                data.copy(
                    spiritStones = data.spiritStones - 50000L,
                    placedBuildings = data.placedBuildings.map { b ->
                        if (b.instanceId == buildingInstanceId && b.displayName == "单人住所")
                            b.copy(displayName = "中级单人住所")
                        else b
                    }
                )
            }
        }
    }
}
