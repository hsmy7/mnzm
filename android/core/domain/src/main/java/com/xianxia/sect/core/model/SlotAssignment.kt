package com.xianxia.sect.core.model

/**
 * 槽位类别 — 标识弟子分配到的子系统类型。
 * 新增槽位系统时在此添加枚举值，并在 [DiscipleSlotCleanup] 和注册表中同步处理。
 */
enum class SlotCategory {
    /** 长老职位（副宗主、各堂长老、亲传弟子） */
    ELDER_POSITION,

    /** 生产槽位（炼丹炉、锻造坊） */
    PRODUCTION_SLOT,

    /** 灵矿场（矿工、执事） */
    SPIRIT_MINE,

    /** 藏经阁 */
    LIBRARY_SLOT,

    /** 住所 */
    RESIDENCE_SLOT,

    /** 巡视楼 */
    PATROL_SLOT,

    /** 仓库驻守 */
    WAREHOUSE_GARRISON,

    /** 战斗队伍 */
    BATTLE_TEAM,

    /** 驻军（玩家宗门据点） */
    GARRISON_SLOT,

    /** 血炼 */
    BLOOD_REFINEMENT,

    /** 探索队伍 */
    EXPLORATION_TEAM;
}

/**
 * 统一槽位引用 — 标识一个具体的槽位位置。
 *
 * 所有槽位系统通过此结构被统一引用。
 * [category] 标识子系统类型，[slotType] 标识子类型（如 "viceSectMaster"、"alchemy:0"），
 * [slotId] 是全局唯一 ID（如 "elder_viceSectMaster"、"production_alchemy_0"）。
 *
 * @property category 槽位所属子系统
 * @property slotType 槽位子类型标识
 * @property slotId  全局唯一槽位 ID
 */
data class SlotRef(
    val category: SlotCategory,
    val slotType: String,
    val slotId: String,
)

/**
 * 槽位分配记录 — 表示一个弟子当前被分配在哪个槽位。
 *
 * @property discipleId  被分配的弟子 ID
 * @property slotRef    弟子所在的槽位引用
 * @property assignedAt 分配时间（游戏年份 * 12 + 月份）
 */
data class SlotAssignment(
    val discipleId: String,
    val slotRef: SlotRef,
    val assignedAt: Int = 0,
)
