package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.engine.di.IoDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuildingFacadeImpl @Inject constructor(
    private val buildingService: BuildingService,
    private val stateStore: GameStateStore,
    private val gameEngineCore: GameEngineCore,
    private val productionCoordinator: ProductionCoordinator,
    private val inventorySystem: InventorySystem,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val assignmentGate: com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate,
    private val discipleStatusService: DiscipleStatusService,
    private val ioDispatcher: IoDispatcher,
) : BuildingFacade {

    override suspend fun placeBuilding(building: GridBuildingData) {
        val sectId = stateStore.gameDataSnapshot.activeSectId
        stateStore.update { gameData = gameData.copy(placedBuildings = gameData.placedBuildings + building.copy(sectId = sectId)) }
        if (BuildingFeatureRegistry.findByDisplayName(building.displayName)?.buildingType == BuildingType.MINING) {
            syncSpiritMineSlotsAfterPlace()
        }
    }

    /**
     * 建造灵矿场后同步 slot：为所有灵矿场建筑重建 3 槽位，
     * 新槽位初始化 lastSettledGameMonth 为当前月份（防回档双计）。
     */
    private fun syncSpiritMineSlotsAfterPlace() {
        gameEngineCore.launchInScope {
            stateStore.update {
                val data = gameData
                val globalMines = data.placedBuildings.filter {
                    BuildingFeatureRegistry.findByDisplayName(it.displayName)?.buildingType == BuildingType.MINING
                }
                val rebuiltSlots = mutableListOf<SpiritMineSlot>()
                var slotIdx = 0
                for (mine in globalMines) {
                    for (offset in 0 until 3) {
                        val existing = data.spiritMineSlots.getOrNull(slotIdx + offset)
                        val slot = if (existing != null) {
                            existing.copy(index = rebuiltSlots.size, buildingInstanceId = mine.instanceId)
                        } else {
                            SpiritMineSlot(
                                index = rebuiltSlots.size,
                                sectId = mine.sectId,
                                buildingInstanceId = mine.instanceId
                            )
                        }
                        rebuiltSlots.add(slot)
                    }
                    slotIdx += 3
                }
                if (rebuiltSlots != data.spiritMineSlots) {
                    gameData = data.copy(spiritMineSlots = rebuiltSlots)
                }
            }
        }
    }

    override suspend fun moveBuildingDirect(instanceId: String, newGridX: Int, newGridY: Int) {
        val sectId = stateStore.gameDataSnapshot.activeSectId
        // 第二层防御：验证新位置不在边界树木区域内
        val border = GameConfig.SectMap.BORDER_TREE_RING
        val building = stateStore.gameDataSnapshot.placedBuildings
            .find { it.instanceId == instanceId && it.sectId == sectId } ?: return
        if (newGridX < border || newGridY < border ||
            newGridX + building.width > GameConfig.SectMap.WORLD_WIDTH_CELLS - border ||
            newGridY + building.height > GameConfig.SectMap.WORLD_HEIGHT_CELLS - border
        ) return

        stateStore.update {
            gameData = gameData.copy(
                placedBuildings = gameData.placedBuildings.map {
                    if (it.instanceId == instanceId && it.sectId == sectId) it.copy(gridX = newGridX, gridY = newGridY)
                    else it
                }
            )
        }
    }

    override suspend fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) {
        buildingService.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)
    }

    override suspend fun removeDiscipleFromBuilding(buildingId: String, slotIndex: Int) {
        buildingService.removeDiscipleFromBuilding(buildingId, slotIndex)
    }

    override fun getBuildingSlots(buildingId: String): List<BuildingSlot> =
        productionCoordinator.getSlotsByBuildingId(buildingId).map { it.toBuildingSlot() }

    override suspend fun startAlchemy(slotIndex: Int, recipeId: String): DomainResult<ProductionSlot> {
        return buildingService.startAlchemy(slotIndex, recipeId)
    }

    override suspend fun startForging(slotIndex: Int, recipeId: String): DomainResult<ProductionSlot> {
        return buildingService.startForging(slotIndex, recipeId)
    }

    override suspend fun autoHarvestCompletedAlchemySlots(): List<AlchemyResult> {
        return buildingService.autoHarvestCompletedAlchemySlots()
    }

    override fun getForgeSlots(): List<BuildingSlot> =
        productionCoordinator.getSlotsByBuildingId(BuildingNames.FORGE).map { it.toBuildingSlot() }

    private fun ProductionSlot.toBuildingSlot(): BuildingSlot = BuildingSlot(
        id = id,
        buildingId = buildingId,
        slotIndex = slotIndex,
        discipleId = assignedDiscipleId,
        discipleName = assignedDiscipleName,
        startYear = startYear,
        startMonth = startMonth,
        duration = duration,
        recipeId = recipeId,
        recipeName = recipeName,
        status = when (status) {
            ProductionSlotStatus.IDLE -> SlotStatus.IDLE
            ProductionSlotStatus.WORKING -> SlotStatus.WORKING
            ProductionSlotStatus.COMPLETED -> SlotStatus.COMPLETED
        }
    )

    override fun getAlchemyFurnaceCount(): Int {
        return BuildingFeatureRegistry.countByType(stateStore.gameDataSnapshot, BuildingType.ALCHEMY)
    }

    override fun getForgeWorkshopCount(): Int {
        return BuildingFeatureRegistry.countByType(stateStore.gameDataSnapshot, BuildingType.FORGE)
    }

    override fun getAssignedDiscipleForSlot(buildingType: BuildingType, slotIndex: Int): Pair<String, String>? {
        val slot = productionCoordinator.repository.getSlotByIndex(buildingType, slotIndex)
        val id = slot?.assignedDiscipleId
        return if (id.isNullOrEmpty()) null else Pair(id, slot.assignedDiscipleName)
    }

    override fun assignDiscipleToProductionSlot(
        buildingType: BuildingType,
        slotIndex: Int,
        discipleId: String,
        discipleName: String
    ) {
        gameEngineCore.launchInScope {
            // 登记新分配（覆盖任何旧分配记录）
            val targetSlot = com.xianxia.sect.core.model.SlotRef(
                category = com.xianxia.sect.core.model.SlotCategory.PRODUCTION_SLOT,
                slotType = "${buildingType}:${slotIndex}",
                slotId = "production_${buildingType}_${slotIndex}"
            )

            // 若目标槽位已有弟子，先释放并恢复为空闲状态
            val existingSlot = productionCoordinator.repository.getSlotByIndex(buildingType, slotIndex)
            existingSlot?.assignedDiscipleId?.let { oldDiscipleId ->
                if (oldDiscipleId.isNotEmpty() && oldDiscipleId != discipleId) {
                    assignmentGate.release(oldDiscipleId)
                    updateDiscipleStatus(oldDiscipleId, DiscipleStatus.IDLE)
                }
            }

            // 若该弟子已被分配到其他槽位（门卫注册表中有记录），先释放旧分配
            if (assignmentGate.isAssigned(discipleId)) {
                withContext(ioDispatcher.dispatcher) {
                    productionCoordinator.repository.getSlots()
                        .filter { it.assignedDiscipleId == discipleId }
                        .forEach { slot ->
                            productionCoordinator.repository.updateSlot(slot.buildingType, slot.slotIndex) { s ->
                                s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                            }
                        }
                }
                assignmentGate.release(discipleId)
                updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
            }

            withContext(ioDispatcher.dispatcher) {
                productionCoordinator.repository.updateSlot(buildingType, slotIndex) { slot ->
                    slot.copy(
                        assignedDiscipleId = discipleId,
                        assignedDiscipleName = discipleName
                    )
                }
            }

            assignmentGate.confirmAssign(discipleId, targetSlot)
        }
    }

    override fun removeDiscipleFromProductionSlot(buildingType: BuildingType, slotIndex: Int) {
        gameEngineCore.launchInScope {
            val data = stateStore.gameDataSnapshot
            val currentYear = data.gameYear
            val currentMonth = data.gameMonth
            val slot = productionCoordinator.repository.getSlotByIndex(buildingType, slotIndex)
            val discipleId = slot?.assignedDiscipleId

            withContext(ioDispatcher.dispatcher) {
                productionCoordinator.repository.updateSlot(buildingType, slotIndex) { s ->
                    if (s.isWorking && !s.assignedDiscipleId.isNullOrEmpty()) {
                        val remaining = s.remainingTime(currentYear, currentMonth)
                        s.copy(
                            assignedDiscipleId = null,
                            assignedDiscipleName = "",
                            startYear = currentYear,
                            startMonth = currentMonth,
                            duration = remaining.coerceAtLeast(1)
                        )
                    } else {
                        s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                    }
                }
            }
            if (discipleId != null) {
                assignmentGate.release(discipleId)
                discipleStatusService.syncSingleDiscipleStatus(discipleId)
            }
        }
    }

    override suspend fun toggleAutoRestart(buildingType: BuildingType, slotIndex: Int) {
        withContext(ioDispatcher.dispatcher) {
            productionCoordinator.repository.updateSlot(buildingType, slotIndex) { slot ->
                slot.copy(autoRestartEnabled = !slot.autoRestartEnabled)
            }
        }
    }

    override suspend fun addProductionSlot(slot: ProductionSlot) {
        withContext(ioDispatcher.dispatcher) {
            productionCoordinator.repository.addSlot(slot)
        }
    }

    override suspend fun plantOnSpiritField(buildingInstanceId: String, seedId: String, sectId: String) {
        val seed = inventorySystem.getSeedById(seedId) ?: return
        if (seed.quantity <= 0) return

        stateStore.update {
            val idx = gameData.spiritFieldPlants.indexOfFirst { it.buildingInstanceId == buildingInstanceId && it.seedId.isEmpty() }
            if (idx < 0) return@update

            val currentYear = gameData.gameYear
            val currentMonth = gameData.gameMonth
            val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(currentYear, currentMonth)
            val updatedPlants = gameData.spiritFieldPlants.toMutableList()
            updatedPlants[idx] = updatedPlants[idx].copy(
                seedId = seedId,
                seedName = seed.name,
                growTime = seed.growTime,
                expectedYield = seed.yield,
                plantYear = currentYear,
                plantMonth = currentMonth,
                sectId = sectId,
                completionMonth = currentAbsoluteMonth + seed.growTime.coerceAtLeast(1),
                completionPhase = 3  // 种植下旬
            )
            gameData = gameData.copy(spiritFieldPlants = updatedPlants)
        }

        inventorySystem.removeSeedSync(seedId, 1)
    }

    override suspend fun plantOnSpiritFields(instanceIds: List<String>, seedId: String, sectId: String) {
        if (instanceIds.isEmpty()) return
        val seed = inventorySystem.getSeedById(seedId) ?: return
        if (seed.quantity <= 0) return

        var planted = 0
        stateStore.update {
            val currentYear = gameData.gameYear
            val currentMonth = gameData.gameMonth
            val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(currentYear, currentMonth)
            val updatedPlants = gameData.spiritFieldPlants.toMutableList()
            for (i in updatedPlants.indices) {
                if (planted >= instanceIds.size) break
                val p = updatedPlants[i]
                if (p.buildingInstanceId in instanceIds && p.seedId.isEmpty()) {
                    updatedPlants[i] = p.copy(
                        seedId = seedId, seedName = seed.name,
                        growTime = seed.growTime, expectedYield = seed.yield,
                        plantYear = currentYear, plantMonth = currentMonth, sectId = sectId,
                        completionMonth = currentAbsoluteMonth + seed.growTime.coerceAtLeast(1),
                        completionPhase = 3  // 种植下旬
                    )
                    planted++
                }
            }
            gameData = gameData.copy(spiritFieldPlants = updatedPlants)
        }

        if (planted > 0) {
            inventorySystem.removeSeedSync(seedId, planted)
        }
    }

    override suspend fun removePlantFromSpiritField(buildingInstanceId: String) {
        stateStore.update {
            val idx = gameData.spiritFieldPlants.indexOfFirst { it.buildingInstanceId == buildingInstanceId }
            if (idx < 0) return@update

            val updatedPlants = gameData.spiritFieldPlants.toMutableList()
            updatedPlants[idx] = updatedPlants[idx].copy(
                seedId = "",
                seedName = "",
                growTime = 0,
                expectedYield = 0,
                plantYear = 0,
                plantMonth = 0,
                completionMonth = 0,
                completionPhase = 1
            )
            gameData = gameData.copy(spiritFieldPlants = updatedPlants)
        }
    }

    override suspend fun removePlantsFromSpiritFields(instanceIds: List<String>) {
        if (instanceIds.isEmpty()) return
        stateStore.update {
            val idSet = instanceIds.toSet()
            val updatedPlants = gameData.spiritFieldPlants.map { plant ->
                if (plant.buildingInstanceId in idSet) {
                    plant.copy(
                        seedId = "",
                        seedName = "",
                        growTime = 0,
                        expectedYield = 0,
                        plantYear = 0,
                        plantMonth = 0,
                        completionMonth = 0,
                        completionPhase = 1
                    )
                } else plant
            }
            gameData = gameData.copy(spiritFieldPlants = updatedPlants)
        }
    }

    override fun clearAlchemySlot(slotIndex: Int): DomainResult<Unit> {
        if (slotIndex < 0) return DomainResult.Failure(AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex))
        gameEngineCore.launchInScope {
            withContext(ioDispatcher.dispatcher) {
                productionCoordinator.resetSlotByBuildingIdAtomic(BuildingNames.ALCHEMY, slotIndex)
            }
        }
        return DomainResult.Success(Unit)
    }

    override fun clearForgeSlot(slotIndex: Int): DomainResult<Unit> {
        if (slotIndex < 0) return DomainResult.Failure(AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex))
        gameEngineCore.launchInScope {
            val slot = productionCoordinator.repository.getSlotByBuildingId(BuildingNames.FORGE, slotIndex)
            if (slot != null && !slot.isWorking) {
                slot.assignedDiscipleId?.let { discipleId ->
                    updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
                }
            }
            withContext(ioDispatcher.dispatcher) {
                productionCoordinator.resetSlotByBuildingIdAtomic(BuildingNames.FORGE, slotIndex)
            }
        }
        return DomainResult.Success(Unit)
    }

    override suspend fun removeBuilding(instanceId: String, refund: Long) {
        stateStore.update {
            val building = gameData.placedBuildings.find { it.instanceId == instanceId }
                ?: return@update
            val name = building.displayName

            // 移除建筑 + 返还灵石 + 清洁关联槽位
            gameData = cleanupBuildingSlots(name, instanceId, refund)
        }
        discipleStatusService.syncAllDiscipleStatuses()
    }

    private fun MutableGameState.cleanupBuildingSlots(
        name: String, instanceId: String, refund: Long
    ): GameData {
        val feature = BuildingFeatureRegistry.findByDisplayName(name) ?: return gameData
        // 通过钱包记录灵石返还
        spiritStoneWallet.add(this, refund, SpiritStoneGrade.LOW, SpiritStoneSource.Refund)
        // 移除建筑 + 清洁关联槽位（灵石已由 applyAdd 处理）
        var gd = gameData.copy(
            placedBuildings = gameData.placedBuildings.filter { it.instanceId != instanceId }
        )
        for (group in feature.slotGroups) {
            gd = group.filterFromGameData(gd, instanceId, feature)
        }
        // 生产槽位同步清理 Repository
        if (feature.slotGroups.any { it is SlotGroup.ProductionSlotGroup }) {
            gameEngineCore.launchInScope {
                productionCoordinator.repository.getSlots()
                    .filter { it.buildingInstanceId == instanceId }
                    .forEach { slot -> productionCoordinator.repository.removeSlot(slot.id) }
            }
        }
        // 任务阁拆除：清理所有活跃任务并释放卡在 ON_MISSION 的弟子
        if (feature.buildingType == BuildingType.MISSION_HALL) {
            gd = gd.copy(activeMissions = emptyList())
            for (id in discipleTables.ids) {
                if (discipleTables.statuses[id] == DiscipleStatus.ON_MISSION &&
                    discipleTables.isAlive[id] == 1
                ) {
                    discipleTables.statuses[id] = DiscipleStatus.IDLE
                }
            }
        }
        return gd
    }

    private fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        discipleStatusService.syncSingleDiscipleStatus(discipleId)
    }

}
