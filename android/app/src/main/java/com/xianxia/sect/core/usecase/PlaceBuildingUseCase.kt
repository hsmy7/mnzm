package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.model.GridBuildingData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaceBuildingUseCase @Inject constructor(
    private val buildingFacade: BuildingFacade
) {
    suspend operator fun invoke(building: GridBuildingData): Result<Unit> = runCatching {
        buildingFacade.placeBuilding(building)
    }
}
