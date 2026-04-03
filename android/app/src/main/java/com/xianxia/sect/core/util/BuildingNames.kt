package com.xianxia.sect.core.util

object BuildingNames {
    private val DISPLAY_NAMES = mapOf(
        "alchemy" to "丹鼎殿",
        "forge" to "天工峰",
        "mining" to "灵矿",
        "spiritMine" to "灵矿",
        "herb_garden" to "灵药园",
        "herbGarden" to "灵药园",
        "library" to "藏经阁",
        "tianshu_hall" to "天枢殿",
        "tianShuHall" to "天枢殿",
        "wendaopeak" to "问道峰",
        "wenDaoPeak" to "问道峰",
        "qingyunpeak" to "青云峰",
        "qingyunPeak" to "青云峰",
        "lawenforcementhall" to "执法堂",
        "lawEnforcementHall" to "执法堂",
        "missionhall" to "任务阁",
        "missionHall" to "任务阁",
        "reflectioncliff" to "思过崖",
        "reflectionCliff" to "思过崖"
    )

    fun getDisplayName(buildingId: String): String =
        DISPLAY_NAMES[buildingId] ?: DISPLAY_NAMES[buildingId.lowercase().replace("_", "")] ?: "建筑"
}
