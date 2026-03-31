package com.xianxia.sect.core.model.production

data class BuildingConfig(
    val buildingType: BuildingType,
    val slotCount: Int,
    val unlockConditions: UnlockConditions = UnlockConditions(),
    val baseSuccessRate: Double = 1.0,
    val speedBonusPerDiscipleRealm: Double = 0.0
)

data class UnlockConditions(
    val sectLevel: Int = 1,
    val requiredSpiritStones: Long = 0,
    val requiredYear: Int = 1
)

object BuildingConfigs {
    private val configs: Map<BuildingType, BuildingConfig> = mapOf(
        BuildingType.ALCHEMY to BuildingConfig(
            buildingType = BuildingType.ALCHEMY,
            slotCount = 3,
            unlockConditions = UnlockConditions(sectLevel = 1),
            baseSuccessRate = 0.7
        ),
        BuildingType.FORGE to BuildingConfig(
            buildingType = BuildingType.FORGE,
            slotCount = 2,
            unlockConditions = UnlockConditions(sectLevel = 2),
            baseSuccessRate = 0.7
        ),
        BuildingType.MINING to BuildingConfig(
            buildingType = BuildingType.MINING,
            slotCount = 3,
            unlockConditions = UnlockConditions(sectLevel = 1)
        ),
        BuildingType.HERB_GARDEN to BuildingConfig(
            buildingType = BuildingType.HERB_GARDEN,
            slotCount = 6,
            unlockConditions = UnlockConditions(sectLevel = 1)
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
