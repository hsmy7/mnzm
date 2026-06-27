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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuildingFacadeImpl @Inject constructor(
    private val buildingService: BuildingService,
    private val stateStore: GameStateStore,
    private val gameEngineCore: GameEngineCore,
    private val productionCoordinator: ProductionCoordinator,
    private val inventorySystem: InventorySystem
) : BuildingFacade {

    override suspend fun placeBuilding(building: GridBuildingData) {
        val sectId = stateStore.gameDataSnapshot.activeSectId
        stateStore.update { gameData = gameData.copy(placedBuildings = gameData.placedBuildings + building.copy(sectId = sectId)) }
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

    override fun clearPlantSlot(slotIndex: Int) = buildingService.clearPlantSlot(slotIndex)

    override fun getForgeSlots(): List<BuildingSlot> = buildingService.getBuildingSlots()

    override fun getAlchemyFurnaceCount(): Int {
        val activeSectId = stateStore.gameDataSnapshot.activeSectId
        return stateStore.gameDataSnapshot.placedBuildings.count { it.displayName == "炼丹炉" && it.sectId == activeSectId }
    }

    override fun getForgeWorkshopCount(): Int {
        val activeSectId = stateStore.gameDataSnapshot.activeSectId
        return stateStore.gameDataSnapshot.placedBuildings.count { it.displayName == "锻造坊" && it.sectId == activeSectId }
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

    override suspend fun startManualPlanting(slotIndex: Int, seedId: String) {
        val seed = stateStore.getCurrentSeeds().find { it.id == seedId } ?: return
        if (seed.quantity <= 0) return

        val data = stateStore.gameData.value
        val existingSlot = productionCoordinator.repository.getSlotByBuildingId(BuildingNames.HERB_GARDEN, slotIndex)

        if (existingSlot != null && existingSlot.isCompleted) {
            harvestHerbFromCompletedSlot(existingSlot)
        }

        val herbDbSeedId = HerbDatabase.getSeedByName(seed.name)?.id
        val herbId = herbDbSeedId?.let { HerbDatabase.getHerbIdFromSeedId(it) }
        val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(data.gameYear, data.gameMonth)
        val newSlot = ProductionSlot(
            id = existingSlot?.id ?: java.util.UUID.randomUUID().toString(),
            slotIndex = slotIndex,
            buildingType = BuildingType.HERB_GARDEN,
            buildingId = BuildingNames.HERB_GARDEN,
            status = ProductionSlotStatus.WORKING,
            recipeId = herbDbSeedId ?: seedId,
            recipeName = seed.name,
            startYear = data.gameYear,
            startMonth = data.gameMonth,
            duration = seed.growTime,
            outputItemId = herbId ?: "",
            outputItemName = seed.name,
            expectedYield = seed.yield,
            completionMonth = currentAbsoluteMonth + seed.growTime.coerceAtLeast(1),
            completionPhase = 3  // 种植下旬
        )

        if (existingSlot != null) {
            productionCoordinator.repository.updateSlotByBuildingId(BuildingNames.HERB_GARDEN, slotIndex) { newSlot }
        } else {
            productionCoordinator.repository.addSlot(newSlot)
        }

        inventorySystem.removeSeedSync(seedId, 1)
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
        val ids = mutableSetOf<String>()
        when {
            name == "炼丹炉" || name == "锻造坊" -> {
                val bid = if (name == "炼丹炉") BuildingNames.ALCHEMY else BuildingNames.FORGE
                // 按 buildingInstanceId 精确匹配，替代旧的 maxByOrNull { it.slotIndex }
                // 修复多建筑同类型时移除错误槽位的问题
                gameData.productionSlots
                    .filter { it.buildingInstanceId == instanceId && it.buildingId == bid }
                    .mapNotNull { it.assignedDiscipleId }
                    .filter { it.isNotEmpty() }
                    .forEach { ids.add(it) }
            }
            name.contains("住所") -> gameData.residenceSlots
                .filter { it.buildingInstanceId == instanceId }
                .mapNotNull { it.discipleId }.filter { it.isNotEmpty() }
                .forEach { ids.add(it) }
            name == "仓库" -> gameData.warehouseGarrisons
                .filter { it.buildingInstanceId == instanceId }
                .mapNotNull { it.discipleId }.filter { it.isNotEmpty() }
                .forEach { ids.add(it) }
            name == "灵矿场" -> gameData.spiritMineSlots
                .filter { it.buildingInstanceId == instanceId }
                .mapNotNull { it.discipleId }.filter { it.isNotEmpty() }
                .forEach { ids.add(it) }
            name == "巡视楼" -> gameData.patrolSlots
                .filter { it.buildingInstanceId == instanceId }
                .mapNotNull { it.discipleId }.filter { it.isNotEmpty() }
                .forEach { ids.add(it) }
            name == "血炼池" -> gameData.activeBloodRefinements[instanceId]
                ?.discipleId?.takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        }
        return ids
    }

    private fun MutableGameState.cleanupBuildingSlots(
        name: String, instanceId: String, refund: Long
    ): GameData {
        var gd = gameData.copy(
            placedBuildings = gameData.placedBuildings.filter { it.instanceId != instanceId },
            spiritStones = gameData.spiritStones + refund
        )
        when {
            name == "灵矿场" ->
                // 按 buildingInstanceId 精确移除，替代旧的 dropLast(3)
                gd = gd.copy(spiritMineSlots = gd.spiritMineSlots.filter {
                    it.buildingInstanceId != instanceId
                })
            name == "巡视楼" ->
                // 按 buildingInstanceId 精确移除，替代旧的 dropLast(8) + patrolConfigs.dropLast(1)
                gd = gd.copy(
                    patrolSlots = gd.patrolSlots.filter { it.buildingInstanceId != instanceId },
                    patrolConfigs = gd.patrolConfigs.filterIndexed { idx, _ ->
                        // patrolConfigs 与巡视楼按顺序一一对应，需找到该 instanceId 对应的索引
                        val towerIdx = gameData.placedBuildings
                            .filter { it.displayName == "巡视楼" }
                            .indexOfFirst { it.instanceId == instanceId }
                        idx != towerIdx
                    }
                )
            name == "灵田" ->
                gd = gd.copy(spiritFieldPlants = gd.spiritFieldPlants.filter {
                    it.buildingInstanceId != instanceId })
            name.contains("住所") ->
                gd = gd.copy(residenceSlots = gd.residenceSlots.filter {
                    it.buildingInstanceId != instanceId })
            name == "仓库" ->
                gd = gd.copy(warehouseGarrisons = gd.warehouseGarrisons.filter {
                    it.buildingInstanceId != instanceId })
            name == "炼丹炉" || name == "锻造坊" -> {
                val bid = if (name == "炼丹炉") BuildingNames.ALCHEMY else BuildingNames.FORGE
                // 按 buildingInstanceId 精确移除，替代旧的 maxOfOrNull { it.slotIndex }
                gd = gd.copy(productionSlots = gd.productionSlots.filter {
                    it.buildingInstanceId != instanceId
                })
                // 同步清理 Repository 中的槽位（ProductionSlot 已迁移到 Repository 管理）
                gameEngineCore.launchInScope {
                    productionCoordinator.repository.getSlotsByBuildingId(bid)
                        .filter { it.buildingInstanceId == instanceId }
                        .forEach { slot ->
                            productionCoordinator.repository.removeSlot(slot.id)
                        }
                }
            }
            name == "血炼池" -> gd = gd.copy(
                activeBloodRefinements = gd.activeBloodRefinements
                    .toMutableMap().apply { remove(instanceId) }
            )
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

    private suspend fun harvestHerbFromCompletedSlot(slot: ProductionSlot) {
        val herb = HerbDatabase.getHerbFromSeedName(slot.recipeName)
            ?: slot.recipeId?.let { HerbDatabase.getHerbFromSeed(it) }
            ?: return

        val herbGrowthBonus = if (stateStore.gameData.value.sectPolicies.herbCultivation) GameConfig.PolicyConfig.HERB_CULTIVATION_BASE_EFFECT else 0.0
        val actualYield = HerbGardenSystem.calculateIncreasedYield(slot.expectedYield, herbGrowthBonus)

        val herbItem = Herb(
            name = herb.name,
            rarity = herb.rarity,
            description = herb.description,
            category = herb.category,
            quantity = actualYield
        )
        inventorySystem.addHerb(herbItem)
    }

    internal companion object {
        /**
         * 纯函数：从建筑槽位中收集指定建筑实例关联的弟子 ID。
         *
         * 提取为 companion object 静态方法以便单元测试。
         * 替代旧的 `takeLast(3)` / `takeLast(8)` / `maxByOrNull { it.slotIndex }` 模式，
         * 按 buildingInstanceId 精确匹配。
         *
         * @param displayName 建筑显示名（"灵矿场"/"巡视楼"/"炼丹炉"/"锻造坊" 等）
         * @param instanceId 建筑实例 ID
         * @param gameData 当前游戏状态
         * @return 需释放的弟子 ID 集合
         */
        fun collectDiscipleIdsForBuildingRemoval(
            displayName: String, instanceId: String, gameData: GameData
        ): Set<String> {
            val ids = mutableSetOf<String>()
            when {
                displayName == "炼丹炉" || displayName == "锻造坊" -> {
                    val bid = if (displayName == "炼丹炉") BuildingNames.ALCHEMY else BuildingNames.FORGE
                    @Suppress("DEPRECATION")
                    gameData.productionSlots
                        .filter { it.buildingInstanceId == instanceId && it.buildingId == bid }
                        .mapNotNull { it.assignedDiscipleId }
                        .filter { it.isNotEmpty() }
                        .forEach { ids.add(it) }
                }
                displayName.contains("住所") -> gameData.residenceSlots
                    .filter { it.buildingInstanceId == instanceId }
                    .mapNotNull { it.discipleId }.filter { it.isNotEmpty() }
                    .forEach { ids.add(it) }
                displayName == "仓库" -> gameData.warehouseGarrisons
                    .filter { it.buildingInstanceId == instanceId }
                    .mapNotNull { it.discipleId }.filter { it.isNotEmpty() }
                    .forEach { ids.add(it) }
                displayName == "灵矿场" -> gameData.spiritMineSlots
                    .filter { it.buildingInstanceId == instanceId }
                    .mapNotNull { it.discipleId }.filter { it.isNotEmpty() }
                    .forEach { ids.add(it) }
                displayName == "巡视楼" -> gameData.patrolSlots
                    .filter { it.buildingInstanceId == instanceId }
                    .mapNotNull { it.discipleId }.filter { it.isNotEmpty() }
                    .forEach { ids.add(it) }
                displayName == "血炼池" -> gameData.activeBloodRefinements[instanceId]
                    ?.discipleId?.takeIf { it.isNotEmpty() }?.let { ids.add(it) }
            }
            return ids
        }

        /**
         * 纯函数：过滤掉指定建筑实例关联的槽位。
         *
         * 用于建筑移除时清理关联槽位，替代旧的 `dropLast(N)` / `maxOfOrNull { it.slotIndex }` 模式。
         * 按 buildingInstanceId 精确匹配，不影响其他同类型建筑的槽位。
         *
         * @param displayName 建筑显示名
         * @param instanceId 建筑实例 ID
         * @param gameData 当前游戏状态
         * @return 过滤后的 GameData（仅槽位字段变更）
         */
        fun filterBuildingSlots(
            displayName: String, instanceId: String, gameData: GameData
        ): GameData {
            return when {
                displayName == "灵矿场" -> gameData.copy(
                    spiritMineSlots = gameData.spiritMineSlots.filter { it.buildingInstanceId != instanceId }
                )
                displayName == "巡视楼" -> {
                    val towerIdx = gameData.placedBuildings
                        .filter { it.displayName == "巡视楼" }
                        .indexOfFirst { it.instanceId == instanceId }
                    gameData.copy(
                        patrolSlots = gameData.patrolSlots.filter { it.buildingInstanceId != instanceId },
                        patrolConfigs = if (towerIdx >= 0) gameData.patrolConfigs.filterIndexed { idx, _ -> idx != towerIdx } else gameData.patrolConfigs
                    )
                }
                displayName == "灵田" -> gameData.copy(
                    spiritFieldPlants = gameData.spiritFieldPlants.filter { it.buildingInstanceId != instanceId }
                )
                displayName.contains("住所") -> gameData.copy(
                    residenceSlots = gameData.residenceSlots.filter { it.buildingInstanceId != instanceId }
                )
                displayName == "仓库" -> gameData.copy(
                    warehouseGarrisons = gameData.warehouseGarrisons.filter { it.buildingInstanceId != instanceId }
                )
                displayName == "炼丹炉" || displayName == "锻造坊" -> {
                    @Suppress("DEPRECATION")
                    gameData.copy(
                        productionSlots = gameData.productionSlots.filter { it.buildingInstanceId != instanceId }
                    )
                }
                displayName == "血炼池" -> gameData.copy(
                    activeBloodRefinements = gameData.activeBloodRefinements
                        .filterKeys { it != instanceId }
                )
                else -> gameData
            }
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
