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
    object PatrolTower : GameRoute("patrol_tower/{buildingInstanceId}") {
        fun createRoute(buildingInstanceId: String) = "patrol_tower/$buildingInstanceId"
    }
    object BloodRefiningPool : GameRoute("blood_refining_pool/{buildingInstanceId}") {
        fun createRoute(buildingInstanceId: String) = "blood_refining_pool/$buildingInstanceId"
    }

    // Residence
    object Residence : GameRoute("residence/{buildingInstanceId}") {
        fun createRoute(buildingInstanceId: String) = "residence/$buildingInstanceId"
    }

    // Full-screen overlays (floating button triggered)
    object Recruit : GameRoute("recruit")
    object Merchant : GameRoute("merchant")
    object Diplomacy : GameRoute("diplomacy")
    object Planting : GameRoute("planting")
    object WorldMap : GameRoute("world_map")
    object BattleLog : GameRoute("battle_log")
    object Mail : GameRoute("mail")
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

fun GameRoute.toDialogType(buildingInstanceId: String = ""): com.xianxia.sect.core.domain.dialog.DialogType = when (this) {
    GameRoute.Disciples -> com.xianxia.sect.core.domain.dialog.DialogType.Disciples
    GameRoute.Warehouse -> com.xianxia.sect.core.domain.dialog.DialogType.Warehouse
    GameRoute.Settings -> com.xianxia.sect.core.domain.dialog.DialogType.Settings
    GameRoute.Buildings -> com.xianxia.sect.core.domain.dialog.DialogType.Buildings
    GameRoute.Recruit -> com.xianxia.sect.core.domain.dialog.DialogType.Recruit
    GameRoute.Diplomacy -> com.xianxia.sect.core.domain.dialog.DialogType.Diplomacy
    GameRoute.Planting -> com.xianxia.sect.core.domain.dialog.DialogType.Planting
    GameRoute.Merchant -> com.xianxia.sect.core.domain.dialog.DialogType.Merchant
    GameRoute.WorldMap -> com.xianxia.sect.core.domain.dialog.DialogType.WorldMap
    GameRoute.BattleLog -> com.xianxia.sect.core.domain.dialog.DialogType.BattleLog
    GameRoute.Mail -> com.xianxia.sect.core.domain.dialog.DialogType.Mail
    GameRoute.SpiritMine -> com.xianxia.sect.core.domain.dialog.DialogType.SpiritMine(buildingInstanceId)
    GameRoute.HerbGarden -> com.xianxia.sect.core.domain.dialog.DialogType.HerbGarden
    GameRoute.Alchemy -> com.xianxia.sect.core.domain.dialog.DialogType.Alchemy(buildingInstanceId)
    GameRoute.Forge -> com.xianxia.sect.core.domain.dialog.DialogType.Forge(buildingInstanceId)
    GameRoute.Library -> com.xianxia.sect.core.domain.dialog.DialogType.Library
    GameRoute.WenDaoPeak -> com.xianxia.sect.core.domain.dialog.DialogType.WenDaoPeak
    GameRoute.QingyunPeak -> com.xianxia.sect.core.domain.dialog.DialogType.QingyunPeak
    GameRoute.TianshuHall -> com.xianxia.sect.core.domain.dialog.DialogType.TianshuHall
    GameRoute.LawEnforcementHall -> com.xianxia.sect.core.domain.dialog.DialogType.LawEnforcementHall
    GameRoute.MissionHall -> com.xianxia.sect.core.domain.dialog.DialogType.MissionHall
    GameRoute.ReflectionCliff -> com.xianxia.sect.core.domain.dialog.DialogType.ReflectionCliff
    GameRoute.PatrolTower -> com.xianxia.sect.core.domain.dialog.DialogType.PatrolTower(buildingInstanceId)
    GameRoute.BloodRefiningPool -> com.xianxia.sect.core.domain.dialog.DialogType.BloodRefiningPool(buildingInstanceId)
    GameRoute.Residence -> com.xianxia.sect.core.domain.dialog.DialogType.Residence(buildingInstanceId)
    GameRoute.WarehouseBuilding -> com.xianxia.sect.core.domain.dialog.DialogType.WarehouseBuilding(buildingInstanceId)
    GameRoute.GameOver -> com.xianxia.sect.core.domain.dialog.DialogType.GameOver
    GameRoute.BattleResult -> com.xianxia.sect.core.domain.dialog.DialogType.None
}
