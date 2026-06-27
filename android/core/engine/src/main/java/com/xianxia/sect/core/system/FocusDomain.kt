package com.xianxia.sect.core.engine.system

/**
 * 玩家关注域 — 两档制 tick 分频，纯视角驱动。
 *
 * - 视角驱动：当前界面所在的域即为焦点域 → 100ms 实时结算
 * - 其他域：非当前视角的域 → 30s 批量结算
 * - 界面→域映射定义在 [InterfaceDomainMap] 中，新增界面只需在那里加一行
 */
enum class FocusDomain {
    /** 时间推进 — 始终高频，每 tick 必执行 */
    ALWAYS,

    /** 弟子 — 修炼进度、突破、HP/MP 恢复（弟子列表/详情/宗门地图） */
    DISCIPLES,

    /** 建筑/生产 — 灵矿产出、炼丹/锻器/种植/血炼进度 */
    BUILDINGS,

    /** 仓库/物品 — 物品数量变化（仓库 Tab/商人/交易） */
    WAREHOUSE,

    /** 世界地图 — 地图标记/探索状态（世界地图界面） */
    WORLD_MAP,

    /** 外交 — 好感度/关系变化（外交界面） */
    DIPLOMACY,

    /** 探索/任务 — 任务进度 */
    EXPLORATION,

    /** 后台 — AI 宗门、生育、邮件等玩家不可见的系统（30 秒一次） */
    BACKGROUND
}

/**
 * 界面→域映射表 — 焦点域判定的唯一数据源。
 *
 * [GameEngineCore.resolveDomainsFromView] 和 [GameEngineCoordination.domainForDialog]
 * 均从此表读取，新增界面只需在此加一行。
 *
 * 判定原则：**界面显示随时间变化的数据（进度条、倒计时、数量增减），就应映射到对应域。**
 */
internal val InterfaceDomainMap: Map<String, Set<FocusDomain>> = mapOf(
    // ═══ Tab ═══
    "OVERVIEW" to setOf(FocusDomain.DISCIPLES, FocusDomain.BUILDINGS),
    "DISCIPLES" to setOf(FocusDomain.DISCIPLES),
    "BUILDINGS" to setOf(FocusDomain.BUILDINGS),
    "WAREHOUSE" to setOf(FocusDomain.BUILDINGS, FocusDomain.WAREHOUSE),

    // ═══ 生产建筑（显示进度/产出）═══
    "Alchemy" to setOf(FocusDomain.BUILDINGS),
    "Forge" to setOf(FocusDomain.BUILDINGS),
    "HerbGarden" to setOf(FocusDomain.BUILDINGS),
    "SpiritMine" to setOf(FocusDomain.BUILDINGS),
    "Planting" to setOf(FocusDomain.BUILDINGS),

    // ═══ 仓库/物品 ═══
    "Warehouse" to setOf(FocusDomain.BUILDINGS, FocusDomain.WAREHOUSE),
    "Merchant" to setOf(FocusDomain.BUILDINGS, FocusDomain.WAREHOUSE),
    "SectTrade" to setOf(FocusDomain.BUILDINGS, FocusDomain.WAREHOUSE),

    // ═══ 任务/探索 ═══
    "MissionHall" to setOf(FocusDomain.EXPLORATION, FocusDomain.DISCIPLES),

    // ═══ 弟子相关 ═══
    "BloodRefiningPool" to setOf(FocusDomain.DISCIPLES, FocusDomain.BUILDINGS),
    "Disciples" to setOf(FocusDomain.DISCIPLES),
    "Buildings" to setOf(FocusDomain.BUILDINGS),

    // ═══ 地图/外交 ═══
    "WorldMap" to setOf(FocusDomain.WORLD_MAP),
    "Diplomacy" to setOf(FocusDomain.DIPLOMACY),

    // ═══ 新增界面在此加一行 ═══
)
