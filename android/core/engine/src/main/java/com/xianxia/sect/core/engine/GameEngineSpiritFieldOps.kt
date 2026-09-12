package com.xianxia.sect.core.engine



suspend fun GameEngine.plantOnSpiritField(buildingInstanceId: String, seedId: String,
    sectId: String) = buildingFacade.plantOnSpiritField(buildingInstanceId, seedId, sectId)
suspend fun GameEngine.plantOnSpiritFields(instanceIds: List<String>, seedId: String,
    sectId: String) = buildingFacade.plantOnSpiritFields(instanceIds, seedId, sectId)
suspend fun GameEngine.removePlantFromSpiritField(buildingInstanceId: String) = buildingFacade
    .removePlantFromSpiritField(buildingInstanceId)
suspend fun GameEngine.removePlantsFromSpiritFields(instanceIds: List<String>) = buildingFacade
    .removePlantsFromSpiritFields(instanceIds)
