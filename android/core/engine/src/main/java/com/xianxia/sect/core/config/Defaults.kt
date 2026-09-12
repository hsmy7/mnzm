package com.xianxia.sect.core.config


// ── BuildingConfigService 拆分域（行为零变更） ──
internal fun BuildingConfigService.createDefaultConfig(): BuildingsConfig {
    return BuildingsConfig(
        version = "3.0.0",
        buildings = createDefaultBuildings(),
        buildingAliases = createDefaultBuildingAliases()
    )
}

/** 默认建筑配置表：按发展阶段分组合并 */

internal fun BuildingConfigService.createDefaultBuildings(): Map<String, BuildingConfigModel> = buildMap {
    putAll(createEarlyStageBuildings())
    putAll(createGrowthStageBuildings())
    putAll(createManagementStageBuildings())
    putAll(createCultivationStageBuildings())
    putAll(createLeapStageBuildings())
    putAll(createPeakStageBuildings())
}

/** 初创期建筑（0~3月，200~1,500 灵石） */

internal fun BuildingConfigService.createEarlyStageBuildings(): Map<String, BuildingConfigModel> = mapOf(
    "spirit_field" to BuildingConfigModel(
        id = "spirit_field",
        displayName = "灵田",
        buildingType = "SPIRIT_FIELD",
        slotCount = 1,
        cost = 200,
        gridWidth = 1,
        gridHeight = 1,
        spriteWidth = 1,
        spriteHeight = 1,
        description = "种植灵草的田地"
    ),
    "mining" to BuildingConfigModel(
        id = "mining",
        displayName = "灵矿场",
        buildingType = "MINING",
        slotCount = 3,
        baseSuccessRate = 1.0,
        cost = 1500,
        gridWidth = 4,
        gridHeight = 4,
        spriteWidth = 4,
        spriteHeight = 5,
        description = "开采灵石和矿石"
    ),
    "warehouse" to BuildingConfigModel(
        id = "warehouse",
        displayName = "仓库",
        buildingType = "WAREHOUSE",
        slotCount = 1,
        cost = 20000,
        gridWidth = 6,
        gridHeight = 4,
        spriteWidth = 6,
        spriteHeight = 7,
        description = "储存宗门物资，每座+75格容量"
    )
)

/** 发展期建筑（3~6月，3,000~5,000 灵石） */

internal fun BuildingConfigService.createGrowthStageBuildings(): Map<String, BuildingConfigModel> = mapOf(
    "herb_garden" to BuildingConfigModel(
        id = "herb_garden",
        displayName = "灵植阁",
        buildingType = "HERB_GARDEN",
        slotCount = 1,
        baseSuccessRate = 1.0,
        cost = 3000,
        gridWidth = 4,
        gridHeight = 3,
        spriteWidth = 4,
        spriteHeight = 5,
        description = "种植灵草的园地"
    ),
    "alchemy" to BuildingConfigModel(
        id = "alchemy",
        displayName = "炼丹炉",
        buildingType = "ALCHEMY",
        slotCount = 1,
        baseSuccessRate = 0.7,
        autoRestartEnabled = true,
        cost = 6000,
        gridWidth = 4,
        gridHeight = 2,
        spriteWidth = 4,
        spriteHeight = 5,
        description = "用于炼制各种丹药的场所"
    ),
    "forge" to BuildingConfigModel(
        id = "forge",
        displayName = "锻造坊",
        buildingType = "FORGE",
        slotCount = 1,
        baseSuccessRate = 0.7,
        autoRestartEnabled = true,
        cost = 6000,
        gridWidth = 5,
        gridHeight = 3,
        spriteWidth = 5,
        spriteHeight = 7,
        description = "锻造装备的场所"
    )
)

/** 管理期建筑-纪律/任务（6~12月，5,000~10,000 灵石） */

internal fun BuildingConfigService.createManagementStageBuildings(): Map<String, BuildingConfigModel> = mapOf(
    "reflection_cliff" to BuildingConfigModel(
        id = "reflection_cliff",
        displayName = "监牢",
        buildingType = "REFLECTION_CLIFF",
        slotCount = 6,
        baseSuccessRate = 1.0,
        cost = 20000,
        gridWidth = 4,
        gridHeight = 4,
        spriteWidth = 4,
        spriteHeight = 5,
        description = "悔过自新之地，关押违规弟子"
    ),
    "law_enforcement_hall" to BuildingConfigModel(
        id = "law_enforcement_hall",
        displayName = "执法堂",
        buildingType = "LAW_ENFORCEMENT_HALL",
        slotCount = 3,
        baseSuccessRate = 1.0,
        cost = 6000,
        gridWidth = 6,
        gridHeight = 3,
        spriteWidth = 6,
        spriteHeight = 5,
        description = "维护宗门纪律，执行奖惩"
    ),
    "mission_hall" to BuildingConfigModel(
        id = "mission_hall",
        displayName = "任务阁",
        buildingType = "MISSION_HALL",
        slotCount = 4,
        baseSuccessRate = 1.0,
        cost = 50000,
        gridWidth = 4,
        gridHeight = 3,
        spriteWidth = 4,
        spriteHeight = 5,
        description = "派遣弟子执行宗门任务"
    )
)

/** 管理期建筑-培养/传承（6~12月，5,000~10,000 灵石） */

internal fun BuildingConfigService.createCultivationStageBuildings(): Map<String, BuildingConfigModel> = mapOf(
    "wen_dao_peak" to BuildingConfigModel(
        id = "wen_dao_peak",
        displayName = "问道塔",
        buildingType = "WEN_DAO_PEAK",
        slotCount = 5,
        baseSuccessRate = 1.0,
        cost = 8000,
        gridWidth = 4,
        gridHeight = 2,
        spriteWidth = 4,
        spriteHeight = 8,
        description = "管理外门弟子与传道授业"
    ),
    "qingyun_peak" to BuildingConfigModel(
        id = "qingyun_peak",
        displayName = "青云塔",
        buildingType = "QINGYUN_PEAK",
        slotCount = 5,
        baseSuccessRate = 1.0,
        cost = 8000,
        gridWidth = 4,
        gridHeight = 2,
        spriteWidth = 4,
        spriteHeight = 8,
        description = "管理内门弟子与精英培养"
    ),
    "library" to BuildingConfigModel(
        id = "library",
        displayName = "藏经阁",
        buildingType = "LIBRARY",
        slotCount = 3,
        baseSuccessRate = 1.0,
        cost = 8000,
        gridWidth = 6,
        gridHeight = 3,
        spriteWidth = 6,
        spriteHeight = 5,
        description = "弟子修习功法的场所，提升修炼速度"
    )
)

/** 飞跃期建筑（12~24月，10,000~25,000 灵石） */

internal fun BuildingConfigService.createLeapStageBuildings(): Map<String, BuildingConfigModel> = mapOf(
    "single_residence" to BuildingConfigModel(
        id = "single_residence",
        displayName = "初级单人住所",
        buildingType = "SINGLE_RESIDENCE",
        slotCount = 1,
        baseSuccessRate = 1.0,
        cost = 20000,
        gridWidth = 4,
        gridHeight = 4,
        spriteWidth = 4,
        spriteHeight = 6,
        description = "为弟子提供清修之所，修炼速度+20%"
    ),
    "tianshu_hall" to BuildingConfigModel(
        id = "tianshu_hall",
        displayName = "天枢殿",
        buildingType = "ADMINISTRATION",
        slotCount = 2,
        baseSuccessRate = 1.0,
        cost = 15000,
        gridWidth = 18,
        gridHeight = 13,
        spriteWidth = 18,
        spriteHeight = 19,
        description = "处理宗门事务的核心建筑"
    ),
    "multi_residence" to BuildingConfigModel(
        id = "multi_residence",
        displayName = "初级多人住所",
        buildingType = "MULTI_RESIDENCE",
        slotCount = 4,
        baseSuccessRate = 1.0,
        cost = 30000,
        gridWidth = 6,
        gridHeight = 4,
        spriteWidth = 6,
        spriteHeight = 7,
        description = "供多名弟子共同修炼，修炼速度+10%"
    )
)

/** 鼎盛期建筑（24月+，30,000~50,000 灵石） */

internal fun BuildingConfigService.createPeakStageBuildings(): Map<String, BuildingConfigModel> = mapOf(
    "single_residence_upgraded" to BuildingConfigModel(
        id = "single_residence_upgraded",
        displayName = "中级单人住所",
        buildingType = "SINGLE_RESIDENCE",
        slotCount = 1,
        baseSuccessRate = 1.0,
        cost = 50000,
        gridWidth = 6,
        gridHeight = 6,
        spriteWidth = 6,
        spriteHeight = 8,
        description = "单人修炼之所，修炼速度+40%"
    ),
    "multi_residence_upgraded" to BuildingConfigModel(
        id = "multi_residence_upgraded",
        displayName = "中级多人住所",
        buildingType = "MULTI_RESIDENCE",
        slotCount = 4,
        cost = 80000,
        gridWidth = 6,
        gridHeight = 5,
        spriteWidth = 6,
        spriteHeight = 8,
        description = "供多名弟子共同修炼，修炼速度+15%"
    ),
    "patrol_tower" to BuildingConfigModel(
        id = "patrol_tower",
        displayName = "巡视楼",
        buildingType = "PATROL",
        slotCount = 8,
        cost = 35000,
        gridWidth = 4,
        gridHeight = 2,
        spriteWidth = 4,
        spriteHeight = 10,
        description = "驻守弟子自动巡视地图攻击妖兽"
    ),
    "blood_refining_pool" to BuildingConfigModel(
        id = "blood_refining_pool",
        displayName = "血炼池",
        buildingType = "BLOOD_REFINING_POOL",
        slotCount = 1,
        baseSuccessRate = 1.0,
        cost = 40000,
        gridWidth = 4,
        gridHeight = 3,
        spriteWidth = 4,
        spriteHeight = 3,
        description = "消耗妖兽精血材料淬炼弟子肉身，永久提升战斗属性"
    )
)

/** 默认建筑别名表：按用途分组合并 */

internal fun BuildingConfigService.createDefaultBuildingAliases(): Map<String, String> =
    createDefaultBuildingAliasesProduction() + createDefaultBuildingAliasesAdministration()

/** 生产/传承建筑别名 */

internal fun BuildingConfigService.createDefaultBuildingAliasesProduction(): Map<String, String> = mapOf(
    // 灵矿 (mining)
    "mine" to "mining",
    "mining" to "mining",

    // 炼丹炉 (alchemy)
    "alchemyroom" to "alchemy",
    "alchemy" to "alchemy",

    // 锻造坊 (forge)
    "forging" to "forge",
    "forge" to "forge",

    // 灵植阁 (herb_garden)
    "herb" to "herb_garden",
    "herbgarden" to "herb_garden",
    "herb_garden" to "herb_garden",

    // 天枢殿 (administration / tianshu_hall)
    "tianshu" to "tianshu_hall",
    "tianshuhall" to "tianshu_hall",
    "tianshu_hall" to "tianshu_hall",
    "administration" to "tianshu_hall",

    // 藏经阁 (library)
    "library" to "library",
    "藏经阁" to "library",

    // 问道塔 (wen_dao_peak)
    "wendaopeak" to "wen_dao_peak",
    "wendao" to "wen_dao_peak",
    "wen_dao_peak" to "wen_dao_peak",
    "问道塔" to "wen_dao_peak",

    // 青云塔 (qingyun_peak)
    "qingyunpeak" to "qingyun_peak",
    "qingyun" to "qingyun_peak",
    "qingyun_peak" to "qingyun_peak",
    "青云塔" to "qingyun_peak"
)

/** 管理/住所/后勤建筑别名 */

internal fun BuildingConfigService.createDefaultBuildingAliasesAdministration(): Map<String, String> = mapOf(
    // 执法堂 (law_enforcement_hall)
    "lawenforcementhall" to "law_enforcement_hall",
    "lawenforcement" to "law_enforcement_hall",
    "zhifatang" to "law_enforcement_hall",
    "law_enforcement_hall" to "law_enforcement_hall",
    "执法堂" to "law_enforcement_hall",

    // 任务阁 (mission_hall)
    "missionhall" to "mission_hall",
    "renwuge" to "mission_hall",
    "mission_hall" to "mission_hall",
    "任务阁" to "mission_hall",

    // 监牢 (reflection_cliff)
    "reflectioncliff" to "reflection_cliff",
    "siguoya" to "reflection_cliff",
    "reflection_cliff" to "reflection_cliff",
    "监牢" to "reflection_cliff",

    // 住所 (residence)
    "singleresidence" to "single_residence",
    "single_residence" to "single_residence",
    "multiresidence" to "multi_residence",
    "multi_residence" to "multi_residence",
    "singleresidenceupgraded" to "single_residence_upgraded",
    "single_residence_upgraded" to "single_residence_upgraded",
    "multiresidenceupgraded" to "multi_residence_upgraded",
    "multi_residence_upgraded" to "multi_residence_upgraded",
    "warehouse" to "warehouse",
    "patrol_tower" to "patrol_tower",
    "patroltower" to "patrol_tower",
    "bloodrefiningpool" to "blood_refining_pool",
    "blood_refining_pool" to "blood_refining_pool"
)
