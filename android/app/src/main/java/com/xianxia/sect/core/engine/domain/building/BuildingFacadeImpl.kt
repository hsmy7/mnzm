package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.state.GameStateStore
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

    override suspend fun startAlchemy(slotIndex: Int, recipeId: String): Boolean =
        buildingService.startAlchemy(slotIndex, recipeId)

    override suspend fun startForging(slotIndex: Int, recipeId: String): Boolean =
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
            productionCoordinator.repository.updateSlot(buildingType, slotIndex) { slot ->
                slot.copy(
                    assignedDiscipleId = discipleId,
                    assignedDiscipleName = discipleName
                )
            }
            stateStore.update { gameData = gameData.copy(
                productionSlots = gameData.productionSlots.map { slot ->
                    if (slot.buildingType == buildingType && slot.slotIndex == slotIndex) {
                        slot.copy(
                            assignedDiscipleId = discipleId,
                            assignedDiscipleName = discipleName
                        )
                    } else slot
                }
            )}
        }
    }

    override fun removeDiscipleFromProductionSlot(buildingType: BuildingType, slotIndex: Int) {
        gameEngineCore.launchInScope {
            val data = stateStore.gameDataSnapshot
            val currentYear = data.gameYear
            val currentMonth = data.gameMonth
            val slot = data.productionSlots.find {
                it.buildingType == buildingType && it.slotIndex == slotIndex
            }
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
            stateStore.update { gameData = gameData.copy(
                productionSlots = gameData.productionSlots.map { s ->
                    if (s.buildingType == buildingType && s.slotIndex == slotIndex) {
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
                    } else s
                }
            )}
            if (discipleId != null) {
                stateStore.update {
                    disciples = disciples.map { d ->
                        if (d.id == discipleId) d.copy(status = DiscipleStatus.IDLE) else d
                    }
                }
            }
        }
    }

    override suspend fun toggleAutoRestart(buildingType: BuildingType, slotIndex: Int) {
        productionCoordinator.repository.updateSlot(buildingType, slotIndex) { slot ->
            slot.copy(autoRestartEnabled = !slot.autoRestartEnabled)
        }
        stateStore.update { gameData = gameData.copy(
            productionSlots = gameData.productionSlots.map { slot ->
                if (slot.buildingType == buildingType && slot.slotIndex == slotIndex)
                    slot.copy(autoRestartEnabled = !slot.autoRestartEnabled)
                else slot
            }
        )}
    }

    override suspend fun addProductionSlot(slot: ProductionSlot) {
        productionCoordinator.repository.addSlot(slot)
    }

    override suspend fun startManualPlanting(slotIndex: Int, seedId: String) {
        val seed = stateStore.getCurrentSeeds().find { it.id == seedId } ?: return
        if (seed.quantity <= 0) return

        val data = stateStore.gameData.value
        val existingSlot = productionCoordinator.repository.getSlotByBuildingId("herbGarden", slotIndex)

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
            buildingId = "herbGarden",
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
            productionCoordinator.repository.updateSlotByBuildingId("herbGarden", slotIndex) { newSlot }
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
            productionCoordinator.resetSlotByBuildingIdAtomic("alchemy", slotIndex)
        }
    }

    override fun clearForgeSlot(slotIndex: Int) {
        gameEngineCore.launchInScope {
            val slot = productionCoordinator.repository.getSlotByBuildingId("forge", slotIndex)
            if (slot != null && !slot.isWorking) {
                slot.assignedDiscipleId?.let { discipleId ->
                    updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
                }
            }
            productionCoordinator.resetSlotByBuildingIdAtomic("forge", slotIndex)
        }
    }

    private suspend fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        stateStore.update {
            disciples = disciples.map {
                if (it.id == discipleId) it.copy(status = status) else it
            }
        }
    }

    private fun harvestHerbFromCompletedSlot(slot: ProductionSlot) {
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
}
