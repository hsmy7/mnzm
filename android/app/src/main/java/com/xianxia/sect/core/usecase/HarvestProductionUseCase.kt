package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.model.AlchemyResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HarvestProductionUseCase @Inject constructor(
    private val buildingFacade: BuildingFacade
) {
    suspend operator fun invoke(): Result<List<AlchemyResult>> = runCatching {
        buildingFacade.autoHarvestCompletedAlchemySlots()
    }
}
