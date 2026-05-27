package com.xianxia.sect.ui.navigation

sealed class GameRoute(val route: String) {
    // Half-screen construction dialogs
    object Alchemy : GameRoute("alchemy/{buildingInstanceId}") {
        fun createRoute(buildingInstanceId: String) = "alchemy/$buildingInstanceId"
    }
    object Forge : GameRoute("forge/{buildingInstanceId}") {
        fun createRoute(buildingInstanceId: String) = "forge/$buildingInstanceId"
    }
    object HerbGarden : GameRoute("herb_garden")
    object SpiritMine : GameRoute("spirit_mine/{buildingInstanceId}") {
        fun createRoute(buildingInstanceId: String) = "spirit_mine/$buildingInstanceId"
    }
    object Library : GameRoute("library")
    object WenDaoPeak : GameRoute("wendao_peak")
    object QingyunPeak : GameRoute("qingyun_peak")
    object TianshuHall : GameRoute("tianshu_hall")
    object LawEnforcementHall : GameRoute("law_enforcement_hall")
    object MissionHall : GameRoute("mission_hall")
    object ReflectionCliff : GameRoute("reflection_cliff")
    object PatrolTower : GameRoute("patrol_tower")

    // Residence
    object Residence : GameRoute("residence/{buildingInstanceId}") {
        fun createRoute(buildingInstanceId: String) = "residence/$buildingInstanceId"
    }

    // Full-screen overlays (floating button triggered)
    object Recruit : GameRoute("recruit")
    object Merchant : GameRoute("merchant")
    object Diplomacy : GameRoute("diplomacy")
    object Planting : GameRoute("planting")
    object SalaryConfig : GameRoute("salary_config")
    object WorldMap : GameRoute("world_map")
    object BattleLog : GameRoute("battle_log")
    object Disciples : GameRoute("disciples")
    object Warehouse : GameRoute("warehouse")
    object WarehouseBuilding : GameRoute("warehouse_building/{buildingInstanceId}") {
        fun createRoute(buildingInstanceId: String) = "warehouse_building/$buildingInstanceId"
    }
    object Settings : GameRoute("settings")
    object Buildings : GameRoute("buildings")
    object BattleResult : GameRoute("battle_result")

    // Misc
    object GameOver : GameRoute("game_over")
}
