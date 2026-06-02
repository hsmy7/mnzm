package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.model.production.BuildingType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StartProductionUseCase @Inject constructor(
    private val buildingFacade: BuildingFacade
) {
    suspend operator fun invoke(buildingType: BuildingType, slotIndex: Int, recipeId: String): Result<Boolean> = runCatching {
        when (buildingType) {
            BuildingType.ALCHEMY -> buildingFacade.startAlchemy(slotIndex, recipeId)
            BuildingType.FORGE -> buildingFacade.startForging(slotIndex, recipeId)
            else -> throw IllegalArgumentException("不支持的生产类型: $buildingType")
        }
    }
}
