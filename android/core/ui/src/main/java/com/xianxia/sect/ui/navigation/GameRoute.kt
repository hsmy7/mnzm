package com.xianxia.sect.ui.navigation

import com.xianxia.sect.core.domain.dialog.DialogType

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

/** 1:1 路由映射：无参路由 → 同名 DialogType（数据驱动，复杂度不随路由数增长） */
private val simpleDialogTypes: Map<GameRoute, DialogType> = mapOf(
    GameRoute.Disciples to DialogType.Disciples,
    GameRoute.Warehouse to DialogType.Warehouse,
    GameRoute.Settings to DialogType.Settings,
    GameRoute.Buildings to DialogType.Buildings,
    GameRoute.Recruit to DialogType.Recruit,
    GameRoute.Diplomacy to DialogType.Diplomacy,
    GameRoute.Planting to DialogType.Planting,
    GameRoute.Merchant to DialogType.Merchant,
    GameRoute.WorldMap to DialogType.WorldMap,
    GameRoute.BattleLog to DialogType.BattleLog,
    GameRoute.Mail to DialogType.Mail,
    GameRoute.HerbGarden to DialogType.HerbGarden,
    GameRoute.Library to DialogType.Library,
    GameRoute.WenDaoPeak to DialogType.WenDaoPeak,
    GameRoute.QingyunPeak to DialogType.QingyunPeak,
    GameRoute.TianshuHall to DialogType.TianshuHall,
    GameRoute.LawEnforcementHall to DialogType.LawEnforcementHall,
    GameRoute.MissionHall to DialogType.MissionHall,
    GameRoute.ReflectionCliff to DialogType.ReflectionCliff,
    GameRoute.GameOver to DialogType.GameOver,
)

/**
 * 路由 → 对话框类型。带实例 ID 的建筑路由就地构造；其余经
 * [simpleDialogTypes] 查表（穷举性由 GameRouteDialogTypeMappingTest 以
 * sealedSubclasses 全量断言守护——新增路由漏登记即测试红）。
 */
fun GameRoute.toDialogType(buildingInstanceId: String = ""): DialogType = when (this) {
    GameRoute.SpiritMine -> DialogType.SpiritMine(buildingInstanceId)
    GameRoute.Alchemy -> DialogType.Alchemy(buildingInstanceId)
    GameRoute.Forge -> DialogType.Forge(buildingInstanceId)
    GameRoute.PatrolTower -> DialogType.PatrolTower(buildingInstanceId)
    GameRoute.BloodRefiningPool -> DialogType.BloodRefiningPool(buildingInstanceId)
    GameRoute.Residence -> DialogType.Residence(buildingInstanceId)
    GameRoute.WarehouseBuilding -> DialogType.WarehouseBuilding(buildingInstanceId)
    GameRoute.BattleResult -> DialogType.None
    else -> simpleDialogTypes.getValue(this)
}
