package com.xianxia.sect.ui.game.building

import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.engine.domain.building.BuildingFeature
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.engine.domain.building.SlotGroup
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.SectLevel

/**
 * BuildingFeature 默认注册表初始化。
 *
 * 此函数在 :feature:game 模块中定义以引用 R.drawable 资源，
 * 在 XianxiaApplication.onCreate() 中调用。
 */
fun BuildingFeatureRegistry.registerDefaults() {
    listOf(
        resourceBuildingFeatures(),
        productionBuildingFeatures(),
        peakBuildingFeatures(),
        hallBuildingFeatures(),
        residenceBuildingFeatures(),
        bloodRefiningBuildingFeatures()
    ).flatten().forEach { register(it) }
}

/** 资源型建筑（registerDefaults 拆分）：灵矿场 / 灵田 / 灵植阁 */
private fun resourceBuildingFeatures(): List<BuildingFeature> = listOf(
    BuildingFeature("spirit_mine", "灵矿场", BuildingType.MINING,
        listOf(SlotGroup.SpiritMine(), SlotGroup.ElderPositions.SPIRIT_MINE),
        unlimitedBuild = true,
        drawableRes = R.drawable.building_spirit_mine, color = 0xFFBCAAA4,
        cost = 1500, gridWidth = 4, gridHeight = 4, description = "开采灵石和矿石"),
    BuildingFeature("spirit_field", "灵田", BuildingType.SPIRIT_FIELD,
        listOf(SlotGroup.SpiritField()), unlimitedBuild = true,
        drawableRes = R.drawable.building_spirit_field, color = 0xFFC8E6C9,
        cost = 200, gridWidth = 1, gridHeight = 1, description = "种植灵草的田地"),
    BuildingFeature("herb_garden", "灵植阁", BuildingType.HERB_GARDEN,
        listOf(SlotGroup.ProductionSlotGroup(), SlotGroup.ElderPositions.HERB_GARDEN),
        unlimitedBuild = true,
        drawableRes = R.drawable.building_herb_garden, color = 0xFFA5D6A7,
        cost = 3000, gridWidth = 4, gridHeight = 3, spriteWidth = 5, spriteHeight = 6,
        baseSuccessRate = 1.0, description = "种植灵草的园地")
)

/** 生产型建筑（registerDefaults 拆分）：炼丹炉 / 锻造坊 / 仓库 / 藏经阁 */
private fun productionBuildingFeatures(): List<BuildingFeature> = listOf(
    BuildingFeature("alchemy", "炼丹炉", BuildingType.ALCHEMY,
        listOf(SlotGroup.ProductionSlotGroup(), SlotGroup.ElderPositions.ALCHEMY),
        unlimitedBuild = true,
        drawableRes = R.drawable.building_alchemy, color = 0xFFEF9A9A,
        cost = 6000, gridWidth = 4, gridHeight = 3, spriteWidth = 4, spriteHeight = 4,
        baseSuccessRate = 0.7, autoRestartEnabled = true, description = "用于炼制各种丹药的场所"),
    BuildingFeature("forge", "锻造坊", BuildingType.FORGE,
        listOf(SlotGroup.ProductionSlotGroup(), SlotGroup.ElderPositions.FORGE),
        unlimitedBuild = true,
        drawableRes = R.drawable.building_forge, color = 0xFFB0BEC5,
        cost = 6000, gridWidth = 5, gridHeight = 3, spriteWidth = 5, spriteHeight = 6,
        baseSuccessRate = 0.7, autoRestartEnabled = true, description = "锻造装备的场所"),
    BuildingFeature("warehouse", "仓库", BuildingType.WAREHOUSE,
        listOf(SlotGroup.Warehouse()), unlimitedBuild = true,
        drawableRes = R.drawable.building_warehouse, color = 0xFFFFCC80,
        cost = 20000, gridWidth = 6, gridHeight = 4, spriteWidth = 6, spriteHeight = 6,
        description = "储存宗门物资，每座+75格容量"),
    BuildingFeature("library", "藏经阁", BuildingType.LIBRARY,
        listOf(SlotGroup.Library(slotsPerInstance = 3)),
        drawableRes = R.drawable.building_library, color = 0xFF80CBC4,
        cost = 8000, gridWidth = 6, gridHeight = 3, spriteWidth = 6, spriteHeight = 6,
        description = "弟子修习功法的场所，提升修炼速度")
)

/** 峰塔型建筑（registerDefaults 拆分）：问道塔 / 青云塔 */
private fun peakBuildingFeatures(): List<BuildingFeature> = listOf(
    BuildingFeature("wen_dao_peak", "问道塔", BuildingType.WEN_DAO_PEAK,
        listOf(SlotGroup.ElderPositions.WEN_DAO_PEAK),
        drawableRes = R.drawable.building_wen_dao_peak, color = 0xFFFFAB91,
        cost = 8000, gridWidth = 4, gridHeight = 3, spriteWidth = 4, spriteHeight = 8,
        description = "管理外门弟子与传道授业"),
    BuildingFeature("qingyun_peak", "青云塔", BuildingType.QINGYUN_PEAK,
        listOf(SlotGroup.ElderPositions.QINGYUN_PEAK),
        drawableRes = R.drawable.building_qingyun_peak, color = 0xFF9FA8DA,
        cost = 8000, gridWidth = 4, gridHeight = 3, spriteWidth = 4, spriteHeight = 8,
        description = "管理内门弟子与精英培养")
)

/** 厅堂/职能型建筑（registerDefaults 拆分）：天枢殿 / 执法堂 / 任务阁 / 巡视楼 / 监牢 */
private fun hallBuildingFeatures(): List<BuildingFeature> = listOf(
    BuildingFeature("tianshu_hall", "天枢殿", BuildingType.ADMINISTRATION,
        listOf(SlotGroup.ElderPositions.TIANSHU_HALL),
        drawableRes = R.drawable.building_tianshu_hall, color = 0xFFFFF176,
        cost = 15000, gridWidth = 6, gridHeight = 3, spriteWidth = 6, spriteHeight = 6,
        description = "处理宗门事务的核心建筑", isGloballyUnique = true),
    BuildingFeature("law_enforcement_hall", "执法堂", BuildingType.LAW_ENFORCEMENT_HALL,
        listOf(SlotGroup.ElderPositions.LAW_ENFORCEMENT),
        drawableRes = R.drawable.building_law_enforcement, color = 0xFFCE93D8,
        cost = 6000, gridWidth = 6, gridHeight = 3, spriteWidth = 6, spriteHeight = 6,
        description = "维护宗门纪律，执行奖惩"),
    BuildingFeature("mission_hall", "任务阁", BuildingType.MISSION_HALL,
        emptyList(),
        drawableRes = R.drawable.building_mission_hall, color = 0xFF90CAF9,
        cost = 50000, gridWidth = 4, gridHeight = 3, spriteWidth = 4, spriteHeight = 6,
        description = "派遣弟子执行宗门任务"),
    BuildingFeature("patrol_tower", "巡视楼", BuildingType.PATROL,
        listOf(SlotGroup.PatrolTower()), unlimitedBuild = true,
        drawableRes = R.drawable.building_patrol_tower, color = 0xFF795548,
        cost = 35000, gridWidth = 4, gridHeight = 3, spriteWidth = 4, spriteHeight = 8,
        description = "驻守弟子自动巡视地图攻击妖兽"),
    BuildingFeature("reflection_cliff", "监牢", BuildingType.REFLECTION_CLIFF,
        emptyList(),
        drawableRes = R.drawable.building_reflection_cliff, color = 0xFFBDBDBD,
        cost = 20000, gridWidth = 4, gridHeight = 4, description = "悔过自新之地，关押违规弟子")
)

/** 住所型建筑（registerDefaults 拆分）：初级/中级单人+多人住所（显示名带分级前缀，精灵名保持图集历史名称） */
private fun residenceBuildingFeatures(): List<BuildingFeature> = listOf(
    BuildingFeature("single_residence", "初级单人住所", BuildingType.SINGLE_RESIDENCE,
        listOf(SlotGroup.Residence(1)), isResidence = true, unlimitedBuild = true,
        spriteName = "单人住所",
        drawableRes = R.drawable.building_single_residence, color = 0xFFEEEEEE,
        cost = 20000, gridWidth = 4, gridHeight = 4, description = "为弟子提供清修之所，修炼速度+20%",
        residenceSpeedBonus = "修炼速度+20%"),
    BuildingFeature("single_residence_upgraded", "中级单人住所", BuildingType.SINGLE_RESIDENCE,
        listOf(SlotGroup.Residence(1)), isResidence = true, isConstructible = true, unlimitedBuild = true,
        requiredSectLevel = SectLevel.MEDIUM,
        drawableRes = R.drawable.building_single_residence_upgraded, color = 0xFFEEEEEE,
        cost = 50000, gridWidth = 6, gridHeight = 6, description = "单人修炼之所，修炼速度+40%",
        residenceSpeedBonus = "修炼速度+40%"),
    BuildingFeature("multi_residence", "初级多人住所", BuildingType.MULTI_RESIDENCE,
        listOf(SlotGroup.Residence(4)), isResidence = true, unlimitedBuild = true,
        spriteName = "多人住所",
        drawableRes = R.drawable.building_multi_residence, color = 0xFFEEEEEE,
        cost = 30000, gridWidth = 6, gridHeight = 4, spriteWidth = 6, spriteHeight = 4,
        description = "供多名弟子共同修炼，修炼速度+10%",
        residenceSpeedBonus = "修炼速度+10%"),
    BuildingFeature("multi_residence_upgraded", "中级多人住所", BuildingType.MULTI_RESIDENCE,
        listOf(SlotGroup.Residence(4)), isResidence = true, isConstructible = true, unlimitedBuild = true,
        requiredSectLevel = SectLevel.MEDIUM,
        drawableRes = R.drawable.building_multi_residence_upgraded, color = 0xFFEEEEEE,
        cost = 80000, gridWidth = 6, gridHeight = 5, spriteWidth = 6, spriteHeight = 5,
        description = "供多名弟子共同修炼，修炼速度+15%",
        residenceSpeedBonus = "修炼速度+15%")
)

/** 血炼池（registerDefaults 拆分） */
private fun bloodRefiningBuildingFeatures(): List<BuildingFeature> = listOf(
    BuildingFeature("blood_refining_pool", "血炼池", BuildingType.BLOOD_REFINING_POOL,
        listOf(SlotGroup.BloodRefining()), unlimitedBuild = true,
        drawableRes = R.drawable.blood_refining_pool, color = 0xFFB71C1C,
        cost = 40000, gridWidth = 4, gridHeight = 4, spriteWidth = 4, spriteHeight = 4,
        description = "消耗妖兽精血材料淬炼弟子肉身，永久提升战斗属性")
)
