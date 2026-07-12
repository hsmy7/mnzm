package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuildingFacadeImpl @Inject constructor(
    private val buildingService: BuildingService,
    private val stateStore: GameStateStore,
    private val gameEngineCore: GameEngineCore,
    private val productionCoordinator: ProductionCoordinator,
    private val inventorySystem: InventorySystem,
    private val spiritStoneWallet: SpiritStoneWallet
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
        stateStore.update {
            gameData = gameData.copy(
                placedBuildings = gameData.placedBuildings.map {
                    if (it.instanceId == instanceId && it.sectId == sectId) it.copy(gridX = newGridX, gridY = newGridY)
                    else it
                }
            )
        }
    }

    override suspend fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) =
        buildingService.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)

    override suspend fun removeDiscipleFromBuilding(buildingId: String, slotIndex: Int) =
        buildingService.removeDiscipleFromBuilding(buildingId, slotIndex)

    override fun getBuildingSlots(buildingId: String): List<BuildingSlot> =
        buildingService.getBuildingSlotsForBuilding(buildingId)

    override suspend fun startAlchemy(slotIndex: Int, recipeId: String): DomainResult<ProductionSlot> =
        buildingService.startAlchemy(slotIndex, recipeId)

    override suspend fun startForging(slotIndex: Int, recipeId: String): DomainResult<ProductionSlot> =
        buildingService.startForging(slotIndex, recipeId)

    override suspend fun autoHarvestCompletedAlchemySlots(): List<AlchemyResult> =
        buildingService.autoHarvestCompletedAlchemySlots()

    override fun getForgeSlots(): List<BuildingSlot> = buildingService.getBuildingSlots()

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
            // 排他性校验：防止同一弟子被重复分配到多个生产槽位。
            //
            // 历史 bug：此方法（UI 实际调用路径）完全没有排他性检查，
            // 弟子可被同时分配到多个炼丹炉/锻造坊。修复 #4 时补齐。
            val allSlots = productionCoordinator.repository.getSlots()
            val alreadyAssigned = isDiscipleAssignedToOtherSlot(
                discipleId = discipleId,
                slots = allSlots,
                currentBuildingType = buildingType,
                currentSlotIndex = slotIndex
            )
            if (alreadyAssigned) return@launchInScope

            // 若目标槽位已有弟子，先将其恢复为空闲状态
            val existingSlot = productionCoordinator.repository.getSlotByIndex(buildingType, slotIndex)
            existingSlot?.assignedDiscipleId?.let { oldDiscipleId ->
                if (oldDiscipleId.isNotEmpty() && oldDiscipleId != discipleId) {
                    updateDiscipleStatus(oldDiscipleId, DiscipleStatus.IDLE)
                }
            }

            productionCoordinator.repository.updateSlot(buildingType, slotIndex) { slot ->
                slot.copy(
                    assignedDiscipleId = discipleId,
                    assignedDiscipleName = discipleName
                )
            }
        }
    }

    override fun removeDiscipleFromProductionSlot(buildingType: BuildingType, slotIndex: Int) {
        gameEngineCore.launchInScope {
            val data = stateStore.gameDataSnapshot
            val currentYear = data.gameYear
            val currentMonth = data.gameMonth
            val slot = productionCoordinator.repository.getSlotByIndex(buildingType, slotIndex)
            val discipleId = slot?.assignedDiscipleId

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
            if (discipleId != null) {
                stateStore.update {
                    val id = discipleId.toIntOrNull() ?: return@update
                    if (discipleTables.ids.contains(id)) {
                        discipleTables.statuses[id] = DiscipleStatus.IDLE
                    }
                }
            }
        }
    }

    override suspend fun toggleAutoRestart(buildingType: BuildingType, slotIndex: Int) {
        productionCoordinator.repository.updateSlot(buildingType, slotIndex) { slot ->
            slot.copy(autoRestartEnabled = !slot.autoRestartEnabled)
        }
    }

    override suspend fun addProductionSlot(slot: ProductionSlot) {
        productionCoordinator.repository.addSlot(slot)
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

    override fun clearAlchemySlot(slotIndex: Int) {
        if (slotIndex < 0) return
        gameEngineCore.launchInScope {
            productionCoordinator.resetSlotByBuildingIdAtomic(BuildingNames.ALCHEMY, slotIndex)
        }
    }

    override fun clearForgeSlot(slotIndex: Int) {
        if (slotIndex < 0) return
        gameEngineCore.launchInScope {
            val slot = productionCoordinator.repository.getSlotByBuildingId(BuildingNames.FORGE, slotIndex)
            if (slot != null && !slot.isWorking) {
                slot.assignedDiscipleId?.let { discipleId ->
                    updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
                }
            }
            productionCoordinator.resetSlotByBuildingIdAtomic(BuildingNames.FORGE, slotIndex)
        }
    }

    override suspend fun removeBuilding(instanceId: String, refund: Long) {
        stateStore.update {
            val building = gameData.placedBuildings.find { it.instanceId == instanceId }
                ?: return@update
            val name = building.displayName

            // 收集将被移除槽位上已分配的弟子 ID
            val discipleIdsToFree = collectDiscipleIdsForRemoval(name, instanceId)

            // 移除建筑 + 返还灵石 + 清洁关联槽位
            gameData = cleanupBuildingSlots(name, instanceId, refund)

            // 将所有关联弟子恢复为空闲状态
            for (did in discipleIdsToFree) {
                val id = did.toIntOrNull() ?: continue
                if (discipleTables.ids.contains(id)) {
                    discipleTables.statuses[id] = DiscipleStatus.IDLE
                }
            }
        }
    }

    private fun MutableGameState.collectDiscipleIdsForRemoval(
        name: String, instanceId: String
    ): Set<String> {
        val feature = BuildingFeatureRegistry.findByDisplayName(name) ?: return emptySet()
        return feature.slotGroups.flatMap { it.collectDiscipleIds(gameData, instanceId, feature) }.toSet()
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
        return gd
    }

    private suspend fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        stateStore.update {
            val id = discipleId.toIntOrNull() ?: return@update
            if (discipleTables.ids.contains(id)) {
                discipleTables.statuses[id] = status
            }
        }
    }

    internal companion object {
        @Deprecated("Use BuildingFeatureRegistry + SlotGroup instead（迁移较复杂，见 collectDiscipleIdsForRemoval 私有方法）")
        fun collectDiscipleIdsForBuildingRemoval(
            displayName: String, instanceId: String, gameData: GameData
        ): Set<String> {
            val feature = BuildingFeatureRegistry.findByDisplayName(displayName) ?: return emptySet()
            return feature.slotGroups.flatMap { it.collectDiscipleIds(gameData, instanceId, feature) }.toSet()
        }

        @Deprecated("Use BuildingFeatureRegistry.findByDisplayName + SlotGroup.filterFromGameData instead")
        fun filterBuildingSlots(
            displayName: String, instanceId: String, gameData: GameData
        ): GameData {
            val feature = BuildingFeatureRegistry.findByDisplayName(displayName) ?: return gameData
            var gd = gameData
            for (group in feature.slotGroups) {
                gd = group.filterFromGameData(gd, instanceId, feature)
            }
            return gd
        }

        /**
         * 纯函数：检查弟子是否已分配到其他生产槽位。
         *
         * 用于 [assignDiscipleToBuilding] 和 [assignDiscipleToProductionSlot] 的排他性校验，
         * 防止同一弟子被重复分配到多个建筑槽位。
         *
         * 历史 bug：旧实现使用 `it.buildingId != buildingId` 做排他判断，
         * 但 `buildingId` 是类型标识（如 "alchemy"/"forge"），非实例标识。
         * 多个同类型建筑实例共享同一 `buildingId`，导致排他检查失效，
         * 弟子可被重复分配到多个同类型建筑实例。
         *
         * @param discipleId 待分配的弟子 ID
         * @param slots 当前所有生产槽位快照
         * @param currentBuildingType 当前目标建筑类型
         * @param currentSlotIndex 当前目标槽位索引
         * @return true 表示弟子已分配到其他槽位（应阻止分配）
         */
        fun isDiscipleAssignedToOtherSlot(
            discipleId: String,
            slots: List<ProductionSlot>,
            currentBuildingType: BuildingType,
            currentSlotIndex: Int
        ): Boolean {
            if (discipleId.isEmpty()) return false
            return slots.any {
                it.assignedDiscipleId == discipleId &&
                    !(it.buildingType == currentBuildingType && it.slotIndex == currentSlotIndex)
            }
        }
    }
}
