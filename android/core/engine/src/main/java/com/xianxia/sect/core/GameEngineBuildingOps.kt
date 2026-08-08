package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.GridBuildingData



suspend fun GameEngine.placeBuilding(building: GridBuildingData) = buildingFacade.placeBuilding(building)
suspend fun GameEngine.moveBuildingDirect(instanceId: String, newGridX: Int, newGridY: Int) = buildingFacade.moveBuildingDirect(instanceId, newGridX, newGridY)
suspend fun GameEngine.removeBuilding(instanceId: String, refund: Long) = buildingFacade.removeBuilding(instanceId, refund)
suspend fun GameEngine.removeBuildings(refunds: Map<String, Long>) = buildingFacade.removeBuildings(refunds)
suspend fun GameEngine.assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) = buildingFacade.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)
suspend fun GameEngine.removeDiscipleFromBuilding(buildingId: String, slotIndex: Int) = buildingFacade.removeDiscipleFromBuilding(buildingId, slotIndex)
fun GameEngine.getBuildingSlots(buildingId: String): List<BuildingSlot> = buildingFacade.getBuildingSlots(buildingId)
