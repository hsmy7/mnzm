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
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.engine.di.IoDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuildingFacadeImpl @Inject constructor(
    override val buildingService: BuildingService,
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

            // 若目标槽位已有弟子，先释放其 gate 注册（状态 sync 在事务完成后执行，
            // 此处 GameData 仍含旧槽位，sync 会推导出旧状态造成残留）
            val existingSlot = productionCoordinator.repository.getSlotByIndex(buildingType, slotIndex)
            existingSlot?.assignedDiscipleId?.let { oldDiscipleId ->
                if (oldDiscipleId.isNotEmpty() && oldDiscipleId != discipleId) {
                    assignmentGate.release(oldDiscipleId)
                }
            }

            // 事务内清理 GameData 全部槽位（回归：此前只清 Repository 生产槽，
            // 巡逻/长老/藏经阁等槽位残留导致同一弟子多槽位），并同步 GameData.productionSlots 镜像
            stateStore.update {
                gameData = DiscipleSlotCleanup(assignmentGate)
                    .clearAllSlotsDataOnly(gameData, discipleId)
                gameData = gameData.copy(
                    productionSlots = gameData.productionSlots.map { slot ->
                        when {
                            slot.buildingType == buildingType && slot.slotIndex == slotIndex ->
                                slot.copy(assignedDiscipleId = discipleId, assignedDiscipleName = discipleName)
                            slot.assignedDiscipleId == discipleId ->
                                slot.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                            else -> slot
                        }
                    }
                )
            }

            // Repository 路径：无条件清该弟子在其他生产槽的占用（不依赖 gate 判定），再写目标槽
            withContext(ioDispatcher.dispatcher) {
                productionCoordinator.repository.getSlots()
                    .filter { it.assignedDiscipleId == discipleId }
                    .forEach { slot ->
                        productionCoordinator.repository.updateSlot(slot.buildingType, slot.slotIndex) { s ->
                            s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                        }
                    }
                productionCoordinator.repository.updateSlot(buildingType, slotIndex) { slot ->
                    slot.copy(
                        assignedDiscipleId = discipleId,
                        assignedDiscipleName = discipleName
                    )
                }
            }

            // 清旧注册再登记新分配（未注册时 release 为空操作，安全）
            assignmentGate.release(discipleId)
            assignmentGate.confirmAssign(discipleId, targetSlot)
            // 旧 occupant 状态在事务完成后同步（推导式，此时 GameData 已清旧槽位）
            existingSlot?.assignedDiscipleId?.let { oldDiscipleId ->
                if (oldDiscipleId.isNotEmpty() && oldDiscipleId != discipleId) {
                    discipleStatusService.syncSingleDiscipleStatus(oldDiscipleId)
                }
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

    /** 播种时序快照（一次取值，循环内不变） */
    private data class PlantingTime(val year: Int, val month: Int, val absoluteMonth: Int)

    /** 批量播种到空地：返回 (新植物列表, 实际种植数)。同事务内调用，条件与单块播种一致 */
    private fun plantFields(
        plants: List<SpiritFieldPlant>,
        emptyFieldIds: Set<String>,
        seed: Seed,
        maxToPlant: Int,
        timing: PlantingTime,
        sectId: String
    ): Pair<List<SpiritFieldPlant>, Int> {
        val updated = plants.toMutableList()
        var planted = 0
        for (i in updated.indices) {
            if (planted >= maxToPlant) break
            val p = updated[i]
            if (p.buildingInstanceId in emptyFieldIds && p.seedId.isEmpty()) {
                updated[i] = p.copy(
                    seedId = seed.id, seedName = seed.name,
                    growTime = seed.growTime, expectedYield = seed.yield,
                    plantYear = timing.year, plantMonth = timing.month, sectId = sectId,
                    completionMonth = timing.absoluteMonth + seed.growTime.coerceAtLeast(1),
                    completionPhase = 3  // 种植下旬
                )
                planted++
            }
        }
        return updated to planted
    }

    override suspend fun plantOnSpiritField(buildingInstanceId: String, seedId: String, sectId: String) {
        val seed = inventorySystem.getSeedById(seedId) ?: return
        if (seed.quantity <= 0) return

        stateStore.update {
            // 事务内读取最新种子数量/锁定态，同事务扣种——替代事务外 removeSeedSync
            // （其返回值被忽略是"种子不足也种满、免费种田"根因，Bug B）
            val seedEntry = seeds.get(seedId)
            val available = seedEntry?.quantity ?: 0
            if (seedEntry == null || available <= 0 || seedEntry.isLocked) return@update

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
            // 同事务扣种（种植与扣种原子提交，无竞态窗口）
            val newQty = available - 1
            if (newQty <= 0) seeds.remove(seedId)
            else seeds.update(seedId) { it.copy(quantity = newQty) }
        }
    }

    override suspend fun plantOnSpiritFields(instanceIds: List<String>, seedId: String, sectId: String) {
        if (instanceIds.isEmpty()) return
        val seed = inventorySystem.getSeedById(seedId) ?: return
        if (seed.quantity <= 0) return

        stateStore.update {
            // 事务内读取最新种子数量/锁定态，同事务扣种（Bug B：种植数受种子数量约束，
            // 替代事务外 removeSeedSync——其返回值被忽略导致免费种田）
            val seedEntry = seeds.get(seedId)
            val available = seedEntry?.quantity ?: 0
            if (seedEntry == null || available <= 0 || seedEntry.isLocked) return@update

            val timing = PlantingTime(
                year = gameData.gameYear,
                month = gameData.gameMonth,
                absoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(
                    gameData.gameYear, gameData.gameMonth)
            )
            val (updatedPlants, planted) = plantFields(
                plants = gameData.spiritFieldPlants,
                emptyFieldIds = instanceIds.toSet(),
                seed = seed,
                maxToPlant = minOf(available, instanceIds.size),
                timing = timing,
                sectId = sectId
            )
            if (planted > 0) {
                gameData = gameData.copy(spiritFieldPlants = updatedPlants)
                // 同事务扣种（种植与扣种原子提交）
                val newQty = available - planted
                if (newQty <= 0) seeds.remove(seedId)
                else seeds.update(seedId) { it.copy(quantity = newQty) }
            }
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
        removeBuildings(mapOf(instanceId to refund))
    }

    override suspend fun removeBuildings(refunds: Map<String, Long>) {
        removeBuildingsInternal(refunds)
    }

    /**
     * 没收某宗门全部建筑（无灵石返还，拆除返还额为 0）。
     *
     * 玩家占领宗门被 AI 夺回时由引擎月度结算调用——玩家在该宗门建造的建筑
     * 整体拆除，槽位/弟子完整清理（复用 [removeBuildingsInternal] 全流程），
     * 灵石不返还（没收语义，与"自动拆除"产品决策一致）。
     */
    override fun seizeBuildingsOfSect(sectId: String) {
        if (sectId.isEmpty()) return  // 本宗（""）不可没收
        val refunds = stateStore.gameDataSnapshot.placedBuildings
            .filter { it.sectId == sectId }
            .associate { it.instanceId to 0L }
        if (refunds.isEmpty()) return
        removeBuildingsInternal(refunds)
    }

    /** 批量拆除主体（无真挂起点，非 suspend 供月度结算链复用） */
    private fun removeBuildingsInternal(refunds: Map<String, Long>) {
        if (refunds.isEmpty()) return
        val productionIds = mutableSetOf<String>()
        stateStore.update {
            // 预解析目标：未知建筑/实例在此跳过（避免事务循环内多处 continue）
            val targets = refunds.mapNotNull { (instanceId, refund) ->
                val building = gameData.placedBuildings.find { it.instanceId == instanceId }
                    ?: return@mapNotNull null
                val feature = BuildingFeatureRegistry.findByDisplayName(building.displayName)
                    ?: return@mapNotNull null
                Triple(feature, building, refund)
            }
            for ((feature, building, refund) in targets) {
                if (feature.slotGroups.any { it is SlotGroup.ProductionSlotGroup }) {
                    productionIds.add(building.instanceId)
                }
                gameData = cleanupBuildingSlots(feature, building, refund)
            }
        }
        removeProductionSlotsFromRepository(productionIds)
        discipleStatusService.syncAllDiscipleStatuses()
    }

    /** 事务外单个协程批量删除 Repository 生产槽位（避免原事务内多协程竞态）。 */
    private fun removeProductionSlotsFromRepository(instanceIds: Set<String>) {
        if (instanceIds.isEmpty()) return
        gameEngineCore.launchInScope {
            productionCoordinator.repository.getSlots()
                .filter { it.buildingInstanceId in instanceIds }
                .forEach { slot -> productionCoordinator.repository.removeSlot(slot.id) }
        }
    }

    private fun MutableGameState.cleanupBuildingSlots(
        feature: BuildingFeature, building: GridBuildingData, refund: Long
    ): GameData {
        val instanceId = building.instanceId
        // 收集关联弟子须在槽位过滤之前（filterFromGameData 会删除槽位记录，
        // 之后收集将丢失弟子 ID）；生产槽位须查运行时 Repository（GameData 仅存档镜像）
        val discipleIds = feature.slotGroups
            .flatMap { it.collectDiscipleIds(gameData, instanceId, feature) }
            .toMutableSet()
        if (feature.slotGroups.any { it is SlotGroup.ProductionSlotGroup }) {
            discipleIds += productionCoordinator.repository.getSlots()
                .filter { it.buildingInstanceId == instanceId }
                .mapNotNull { it.assignedDiscipleId }
        }
        // 通过钱包记录灵石返还
        spiritStoneWallet.add(this, refund, SpiritStoneGrade.LOW, SpiritStoneSource.Refund)
        // 移除建筑 + 清洁关联槽位（灵石已由 applyAdd 处理）
        var gd = gameData.copy(
            placedBuildings = gameData.placedBuildings.filter { it.instanceId != instanceId }
        )
        for (group in feature.slotGroups) {
            gd = group.filterFromGameData(gd, instanceId, feature)
        }
        releaseBuildingDiscipleIds(discipleIds)
        if (feature.buildingType == BuildingType.REFLECTION_CLIFF) {
            releaseReflectingDisciples()
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

    /**
     * 释放建筑关联弟子：Gate 注册 + 血炼 REFINING 状态。
     * 血炼受保护状态须在事务内显式打破，否则事务外重推拉不回 IDLE。
     */
    private fun MutableGameState.releaseBuildingDiscipleIds(discipleIds: Set<String>) {
        discipleIds.forEach { assignmentGate.release(it) }
        discipleIds.mapNotNull { it.toIntOrNull() }
            .filter { it in discipleTables.ids }
            .filter { discipleTables.statuses[it] == DiscipleStatus.REFINING }
            .forEach { dId ->
                discipleTables.statuses[dId] = DiscipleStatus.IDLE
                discipleTables.statusData[dId] =
                    (discipleTables.statusData[dId] ?: emptyMap()) - setOf("buildingId")
            }
    }

    /** 监牢拆除：释放所有思过弟子（监牢限建 1 座，无实例归属记录，全量释放）。 */
    private fun MutableGameState.releaseReflectingDisciples() {
        for (id in discipleTables.ids) {
            if (discipleTables.statuses[id] == DiscipleStatus.REFLECTING) {
                discipleTables.statuses[id] = DiscipleStatus.IDLE
                discipleTables.statusData[id] =
                    (discipleTables.statusData[id] ?: emptyMap()) -
                    setOf("reflectionStartYear", "reflectionEndYear")
            }
        }
    }

    private fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        discipleStatusService.syncSingleDiscipleStatus(discipleId)
    }

}
