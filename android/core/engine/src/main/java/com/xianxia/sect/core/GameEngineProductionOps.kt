package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.engine.service.FormulaService

suspend fun GameEngine.startAlchemy(slotIndex: Int, recipeId: String): Boolean = buildingFacade.startAlchemy(slotIndex, recipeId)
suspend fun GameEngine.startForging(slotIndex: Int, recipeId: String): Boolean = buildingFacade.startForging(slotIndex, recipeId)
suspend fun GameEngine.addProductionSlot(slot: ProductionSlot) = buildingFacade.addProductionSlot(slot)
fun GameEngine.assignDiscipleToProductionSlot(buildingType: BuildingType, slotIndex: Int, discipleId: String, discipleName: String) = buildingFacade.assignDiscipleToProductionSlot(buildingType, slotIndex, discipleId, discipleName)
fun GameEngine.removeDiscipleFromProductionSlot(buildingType: BuildingType, slotIndex: Int) = buildingFacade.removeDiscipleFromProductionSlot(buildingType, slotIndex)
suspend fun GameEngine.toggleAutoRestart(buildingType: BuildingType, slotIndex: Int) = buildingFacade.toggleAutoRestart(buildingType, slotIndex)
fun GameEngine.getAssignedDiscipleForSlot(buildingType: BuildingType, slotIndex: Int): Pair<String, String>? = buildingFacade.getAssignedDiscipleForSlot(buildingType, slotIndex)
fun GameEngine.getAlchemyFurnaceCount(): Int = buildingFacade.getAlchemyFurnaceCount()
fun GameEngine.getForgeWorkshopCount(): Int = buildingFacade.getForgeWorkshopCount()
suspend fun GameEngine.autoHarvestCompletedAlchemySlots(): List<AlchemyResult> = buildingFacade.autoHarvestCompletedAlchemySlots()
fun GameEngine.clearPlantSlot(slotIndex: Int) = buildingFacade.clearPlantSlot(slotIndex)
fun GameEngine.getForgeSlots(): List<BuildingSlot> = buildingFacade.getForgeSlots()
fun GameEngine.clearAlchemySlot(slotIndex: Int) = buildingFacade.clearAlchemySlot(slotIndex)
fun GameEngine.clearForgeSlot(slotIndex: Int) = buildingFacade.clearForgeSlot(slotIndex)
suspend fun GameEngine.startManualPlanting(slotIndex: Int, seedId: String) = buildingFacade.startManualPlanting(slotIndex, seedId)
suspend fun GameEngine.plantOnSpiritField(buildingInstanceId: String, seedId: String, sectId: String) = buildingFacade.plantOnSpiritField(buildingInstanceId, seedId, sectId)
suspend fun GameEngine.plantOnSpiritFields(instanceIds: List<String>, seedId: String, sectId: String) = buildingFacade.plantOnSpiritFields(instanceIds, seedId, sectId)
suspend fun GameEngine.removePlantFromSpiritField(buildingInstanceId: String) = buildingFacade.removePlantFromSpiritField(buildingInstanceId)

fun GameEngine.calculateSuccessRateBonus(disciple: Disciple?, buildingId: String): Double = formulaService.calculateSuccessRateBonus(disciple, buildingId)
fun GameEngine.calculateWorkDurationWithAllDisciples(baseDuration: Int, buildingId: String): Int = formulaService.calculateWorkDurationWithAllDisciples(baseDuration, buildingId)
fun GameEngine.calculateElderAndDisciplesBonus(buildingType: String): ElderBonusData = formulaService.calculateElderAndDisciplesBonus(buildingType)
