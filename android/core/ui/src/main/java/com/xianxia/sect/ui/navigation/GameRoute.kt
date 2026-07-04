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
    object SalaryConfig : GameRoute("salary_config")
    object WorldMap : GameRoute("world_map")
    object BattleLog : GameRoute("battle_log")
    object Mail : GameRoute("mail")
    object Activity : GameRoute("activity")
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

sealed class DialogRoute {
    /**
     * 域映射键 — 对应 [com.xianxia.sect.core.engine.system.InterfaceDomainMap] 中的键。
     * 默认返回简单类名，与 map 中的条目精确匹配。
     * 子类可通过覆写此属性提供自定义映射键（例如兼容旧调用方）。
     *
     * 新增 [InterfaceDomainMap] 条目时，常规做法是让 DialogRoute 子类的类名
     * 与 map 键一致，由默认实现自动匹配。如需自定义键名，覆写此属性即可。
     */
    open val domainKey: String get() = this::class.simpleName ?: ""

    /**
     * 返回 [domainKey] 作为字符串表示，确保与 [InterfaceDomainMap] 的键精确匹配，
     * 而非返回默认的 FQN@hash（object）或 data class 的完整参数串。
     */
    override fun toString(): String = domainKey

    object None : DialogRoute()

    object Disciples : DialogRoute()
    object Warehouse : DialogRoute()
    object Settings : DialogRoute()
    object Buildings : DialogRoute()

    object Recruit : DialogRoute()
    object Diplomacy : DialogRoute()
    object Planting : DialogRoute()
    object Merchant : DialogRoute()
    object SalaryConfig : DialogRoute()
    object WorldMap : DialogRoute()
    object BattleLog : DialogRoute()
    object Mail : DialogRoute()
    object Activity : DialogRoute()

    data class SpiritMine(val buildingInstanceId: String) : DialogRoute()
    object HerbGarden : DialogRoute()
    data class Alchemy(val buildingInstanceId: String) : DialogRoute()
    data class Forge(val buildingInstanceId: String) : DialogRoute()
    object Library : DialogRoute()
    object WenDaoPeak : DialogRoute()
    object QingyunPeak : DialogRoute()
    object TianshuHall : DialogRoute()
    object LawEnforcementHall : DialogRoute()
    object MissionHall : DialogRoute()
    object ReflectionCliff : DialogRoute()
    data class PatrolTower(val buildingInstanceId: String) : DialogRoute()
    data class BloodRefiningPool(val buildingInstanceId: String) : DialogRoute()
    data class Residence(val buildingInstanceId: String) : DialogRoute()
    data class WarehouseBuilding(val buildingInstanceId: String) : DialogRoute()
    object GameOver : DialogRoute()
    object SectLevelDetail : DialogRoute()
    object RenameSect : DialogRoute()
}

fun GameRoute.toDialogRoute(buildingInstanceId: String = ""): DialogRoute = when (this) {
    GameRoute.Disciples -> DialogRoute.Disciples
    GameRoute.Warehouse -> DialogRoute.Warehouse
    GameRoute.Settings -> DialogRoute.Settings
    GameRoute.Buildings -> DialogRoute.Buildings
    GameRoute.Recruit -> DialogRoute.Recruit
    GameRoute.Diplomacy -> DialogRoute.Diplomacy
    GameRoute.Planting -> DialogRoute.Planting
    GameRoute.Merchant -> DialogRoute.Merchant
    GameRoute.SalaryConfig -> DialogRoute.SalaryConfig
    GameRoute.WorldMap -> DialogRoute.WorldMap
    GameRoute.BattleLog -> DialogRoute.BattleLog
    GameRoute.Mail -> DialogRoute.Mail
    GameRoute.Activity -> DialogRoute.Activity
    GameRoute.SpiritMine -> DialogRoute.SpiritMine(buildingInstanceId)
    GameRoute.HerbGarden -> DialogRoute.HerbGarden
    GameRoute.Alchemy -> DialogRoute.Alchemy(buildingInstanceId)
    GameRoute.Forge -> DialogRoute.Forge(buildingInstanceId)
    GameRoute.Library -> DialogRoute.Library
    GameRoute.WenDaoPeak -> DialogRoute.WenDaoPeak
    GameRoute.QingyunPeak -> DialogRoute.QingyunPeak
    GameRoute.TianshuHall -> DialogRoute.TianshuHall
    GameRoute.LawEnforcementHall -> DialogRoute.LawEnforcementHall
    GameRoute.MissionHall -> DialogRoute.MissionHall
    GameRoute.ReflectionCliff -> DialogRoute.ReflectionCliff
    GameRoute.PatrolTower -> DialogRoute.PatrolTower(buildingInstanceId)
    GameRoute.BloodRefiningPool -> DialogRoute.BloodRefiningPool(buildingInstanceId)
    GameRoute.Residence -> DialogRoute.Residence(buildingInstanceId)
    GameRoute.WarehouseBuilding -> DialogRoute.WarehouseBuilding(buildingInstanceId)
    GameRoute.GameOver -> DialogRoute.GameOver
    GameRoute.BattleResult -> DialogRoute.None
}
