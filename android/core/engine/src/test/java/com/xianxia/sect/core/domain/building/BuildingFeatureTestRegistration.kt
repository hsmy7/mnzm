package com.xianxia.sect.core.domain.building

import com.xianxia.sect.core.engine.domain.building.BuildingFeature
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.engine.domain.building.SlotGroup
import com.xianxia.sect.core.model.production.BuildingType

/**
 * 测试用建筑注册工具 — 被 [BuildingFeatureRegistryTest] 和 [BuildingTypeCoverageTest] 共享。
 *
 * 不依赖 R.drawable，使用占位值 0。
 * 新增建筑类型时需同时在 [BuildingFeatureBoot.registerDefaults] 添加对应条目。
 */
fun BuildingFeatureRegistry.registerTestFeatures() {
    val features = listOf(
        BuildingFeature("spirit_mine", "灵矿场", BuildingType.MINING,
            listOf(SlotGroup.SpiritMine()), unlimitedBuild = true,
            cost = 1500, gridWidth = 4, gridHeight = 4),
        BuildingFeature("spirit_field", "灵田", BuildingType.SPIRIT_FIELD,
            listOf(SlotGroup.SpiritField()), unlimitedBuild = true,
            cost = 200, gridWidth = 1, gridHeight = 1),
        BuildingFeature("herb_garden", "灵植阁", BuildingType.HERB_GARDEN,
            listOf(SlotGroup.ProductionSlotGroup()), unlimitedBuild = true,
            cost = 3000, gridWidth = 4, gridHeight = 3),
        BuildingFeature("alchemy", "炼丹炉", BuildingType.ALCHEMY,
            listOf(SlotGroup.ProductionSlotGroup()), unlimitedBuild = true,
            cost = 4000, gridWidth = 4, gridHeight = 3,
            baseSuccessRate = 0.7, autoRestartEnabled = true),
        BuildingFeature("forge", "锻造坊", BuildingType.FORGE,
            listOf(SlotGroup.ProductionSlotGroup()), unlimitedBuild = true,
            cost = 4000, gridWidth = 5, gridHeight = 3,
            baseSuccessRate = 0.7, autoRestartEnabled = true),
        BuildingFeature("warehouse", "仓库", BuildingType.WAREHOUSE,
            listOf(SlotGroup.Warehouse()), unlimitedBuild = true,
            cost = 1500, gridWidth = 6, gridHeight = 4),
        BuildingFeature("library", "藏经阁", BuildingType.LIBRARY,
            listOf(SlotGroup.Library(slotsPerInstance = 3)),
            cost = 8000, gridWidth = 6, gridHeight = 3),
        BuildingFeature("wen_dao_peak", "问道塔", BuildingType.WEN_DAO_PEAK,
            emptyList(), cost = 8000, gridWidth = 4, gridHeight = 3),
        BuildingFeature("qingyun_peak", "青云塔", BuildingType.QINGYUN_PEAK,
            emptyList(), cost = 8000, gridWidth = 4, gridHeight = 3),
        BuildingFeature("tianshu_hall", "天枢殿", BuildingType.ADMINISTRATION,
            emptyList(), cost = 15000, gridWidth = 6, gridHeight = 3, isGloballyUnique = true),
        BuildingFeature("law_enforcement_hall", "执法堂", BuildingType.LAW_ENFORCEMENT_HALL,
            emptyList(), cost = 6000, gridWidth = 6, gridHeight = 3),
        BuildingFeature("mission_hall", "任务阁", BuildingType.MISSION_HALL,
            emptyList(), cost = 6000, gridWidth = 4, gridHeight = 3),
        BuildingFeature("patrol_tower", "巡视楼", BuildingType.PATROL,
            listOf(SlotGroup.PatrolTower()), unlimitedBuild = true,
            cost = 35000, gridWidth = 4, gridHeight = 3),
        BuildingFeature("reflection_cliff", "监牢", BuildingType.REFLECTION_CLIFF,
            emptyList(), cost = 5000, gridWidth = 4, gridHeight = 4),
        BuildingFeature("single_residence", "单人住所", BuildingType.SINGLE_RESIDENCE,
            listOf(SlotGroup.Residence(1)), isResidence = true, unlimitedBuild = true,
            cost = 12000, gridWidth = 4, gridHeight = 4,
            residenceSpeedBonus = "修炼速度+20%"),
        BuildingFeature("single_residence_upgraded", "中级单人住所", BuildingType.SINGLE_RESIDENCE,
            listOf(SlotGroup.Residence(1)), isResidence = true, isConstructible = true, unlimitedBuild = true,
            cost = 50000, gridWidth = 6, gridHeight = 6,
            residenceSpeedBonus = "修炼速度+40%"),
        BuildingFeature("multi_residence", "多人住所", BuildingType.MULTI_RESIDENCE,
            listOf(SlotGroup.Residence(4)), isResidence = true, unlimitedBuild = true,
            cost = 24000, gridWidth = 6, gridHeight = 4,
            residenceSpeedBonus = "修炼速度+10%"),
        BuildingFeature("multi_residence_upgraded", "中级多人住所", BuildingType.MULTI_RESIDENCE,
            listOf(SlotGroup.Residence(4)), isResidence = true, isConstructible = true, unlimitedBuild = true,
            cost = 80000, gridWidth = 6, gridHeight = 5,
            residenceSpeedBonus = "修炼速度+15%"),
        BuildingFeature("blood_refining_pool", "血炼池", BuildingType.BLOOD_REFINING_POOL,
            listOf(SlotGroup.BloodRefining()), unlimitedBuild = true,
            cost = 40000, gridWidth = 4, gridHeight = 4),
    )
    features.forEach { register(it) }
}
