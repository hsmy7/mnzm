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
    object Settings : GameRoute("settings")
    object Buildings : GameRoute("buildings")
    object Reward : GameRoute("reward")

    // WorldMap sub-dialogs
    object SectTrade : GameRoute("sect_trade")
    object Gift : GameRoute("gift")
    object Alliance : GameRoute("alliance")
    object EnvoyDiscipleSelect : GameRoute("envoy_disciple_select")
    object ScoutDiscipleSelect : GameRoute("scout_disciple_select")
    // Battle
    object BattleTeam : GameRoute("battle_team")
    object BattleTeamDiscipleSelect : GameRoute("battle_team_disciple_select/{slotIndex}") {
        fun createRoute(slotIndex: Int) = "battle_team_disciple_select/$slotIndex"
    }
    object BattleResult : GameRoute("battle_result")

    // Misc
    object GameOver : GameRoute("game_over")
    object Inventory : GameRoute("inventory")
}
