package com.xianxia.sect.core.util

object BuildingNames {
    private val DISPLAY_NAMES = mapOf(
        "alchemy" to "炼丹炉",
        "forge" to "锻造坊",
        "mining" to "灵矿",
        "spiritMine" to "灵矿",
        "herb_garden" to "灵植阁",
        "herbGarden" to "灵植阁",
        "library" to "藏经阁",
        "tianshu_hall" to "天枢殿",
        "tianShuHall" to "天枢殿",
        "wendaopeak" to "问道塔",
        "wenDaoPeak" to "问道塔",
        "qingyunpeak" to "青云塔",
        "qingyunPeak" to "青云塔",
        "lawenforcementhall" to "执法堂",
        "lawEnforcementHall" to "执法堂",
        "missionhall" to "任务阁",
        "missionHall" to "任务阁",
        "reflectioncliff" to "监牢",
        "reflectionCliff" to "监牢"
    )

    fun getDisplayName(buildingId: String): String =
        DISPLAY_NAMES[buildingId] ?: DISPLAY_NAMES[buildingId.lowercase(java.util.Locale.getDefault()).replace("_", "")] ?: "建筑"
}
