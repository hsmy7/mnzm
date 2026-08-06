package com.xianxia.sect.core.domain.dialog

/**
 * 游戏内所有对话框类型的封闭枚举。
 *
 * 设计为 sealed interface 而非 enum，因为部分类型需要携带参数（如 buildingInstanceId）。
 * 新增对话框时在此添加枚举值，并在 [DialogManager] 的路由分发中处理。
 *
 * 非挂起、零 Android 依赖，iOS 接入时直接可用。
 */
sealed interface DialogType {
    /** 域映射键，用于 [com.xianxia.sect.core.engine.GameEngine.setActiveDialog] */
    val domainKey: String get() = this::class.simpleName ?: ""

    // ==================== 主 Tab 全屏覆盖层 ====================

    /** 弟子列表 */
    data object Disciples : DialogType

    /** 仓库 */
    data object Warehouse : DialogType

    /** 设置 */
    data object Settings : DialogType

    /** 建造 */
    data object Buildings : DialogType

    // ==================== 功能性对话框 ====================

    /** 招募 */
    data object Recruit : DialogType

    /** 外交 */
    data object Diplomacy : DialogType

    /** 世界地图 */
    data object WorldMap : DialogType

    /** 战斗日志 */
    data object BattleLog : DialogType

    /** 邮件 */
    data object Mail : DialogType

    /** 历战（活动卡片轮转入口） */
    data object Lizhan : DialogType

    /** 排行榜（双标签：天下宗门本地榜 + 玩家排行云端榜） */
    data object Leaderboard : DialogType

    /** 灵田种植 */
    data object Planting : DialogType

    /** 云游商人 */
    data object Merchant : DialogType

    // ==================== 参建生产对话框（带实例ID） ====================

    data class SpiritMine(val buildingInstanceId: String) : DialogType

    data object HerbGarden : DialogType

    data class Alchemy(val buildingInstanceId: String) : DialogType

    data class Forge(val buildingInstanceId: String) : DialogType

    data class PatrolTower(val buildingInstanceId: String) : DialogType

    data class BloodRefiningPool(val buildingInstanceId: String) : DialogType

    data class Residence(val buildingInstanceId: String) : DialogType

    data class WarehouseBuilding(val buildingInstanceId: String) : DialogType

    // ==================== 功能建筑对话框（无参数） ====================

    data object Library : DialogType

    data object WenDaoPeak : DialogType

    data object QingyunPeak : DialogType

    data object TianshuHall : DialogType

    data object LawEnforcementHall : DialogType

    data object MissionHall : DialogType

    data object ReflectionCliff : DialogType

    // ==================== 引导系统 ====================

    /** 新手引导任务界面 */
    data object Guide : DialogType

    // ==================== 系统对话框 ====================

    data object SectLevelDetail : DialogType

    data object RenameSect : DialogType

    data object GameOver : DialogType

    /** 云存档 */
    data object CloudSave : DialogType

    /** 建筑宗门等级要求提示（建造栏点击等级不足时的提示） */
    data class BuildingSectLevelRequirement(val buildingName: String) : DialogType

    /** 空状态 — 无对话框 */
    data object None : DialogType
}
