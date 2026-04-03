package com.xianxia.sect.core.model.production

import kotlinx.serialization.Serializable

@Serializable
@Deprecated(
    message = "Use BuildingConfigModel from BuildingConfigService instead. This class is legacy hardcoded config.",
    replaceWith = ReplaceWith("BuildingConfigModel", "com.xianxia.sect.core.config.BuildingConfigModel"),
    level = DeprecationLevel.WARNING
)
data class BuildingConfig(
    val buildingType: BuildingType,
    val slotCount: Int,
    val baseSuccessRate: Double = 1.0
)

@Deprecated(
    message = "Use BuildingConfigService instead. This object contains legacy hardcoded building configs.",
    replaceWith = ReplaceWith("BuildingConfigService", "com.xianxia.sect.core.config.BuildingConfigService"),
    level = DeprecationLevel.WARNING
)
object BuildingConfigs {
    private val configs: Map<BuildingType, BuildingConfig> = mapOf(
        BuildingType.ALCHEMY to BuildingConfig(
            buildingType = BuildingType.ALCHEMY,
            slotCount = 3,
            baseSuccessRate = 0.7
        ),
        BuildingType.FORGE to BuildingConfig(
            buildingType = BuildingType.FORGE,
            slotCount = 2,
            baseSuccessRate = 0.7
        ),
        BuildingType.MINING to BuildingConfig(
            buildingType = BuildingType.MINING,
            slotCount = 3,
            baseSuccessRate = 1.0
        ),
        BuildingType.HERB_GARDEN to BuildingConfig(
            buildingType = BuildingType.HERB_GARDEN,
            slotCount = 6,
            baseSuccessRate = 1.0
        ),
        BuildingType.ADMINISTRATION to BuildingConfig(
            buildingType = BuildingType.ADMINISTRATION,
            slotCount = 2,
            baseSuccessRate = 1.0
        )
    )
    
    fun getConfig(buildingType: BuildingType): BuildingConfig = 
        configs[buildingType] ?: BuildingConfig(buildingType = buildingType, slotCount = 1)
    
    fun getSlotCount(buildingType: BuildingType): Int = 
        getConfig(buildingType).slotCount
    
    fun getBaseSuccessRate(buildingType: BuildingType): Double = 
        getConfig(buildingType).baseSuccessRate
    
    fun isValidSlotIndex(buildingType: BuildingType, slotIndex: Int): Boolean =
        slotIndex >= 0 && slotIndex < getSlotCount(buildingType)
}
