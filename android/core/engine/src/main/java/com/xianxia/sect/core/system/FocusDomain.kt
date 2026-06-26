package com.xianxia.sect.core.engine.system

/**
 * 玩家关注域 — 两档制 tick 分频，纯视角驱动。
 *
 * ## 判定标准
 * - 视角驱动：当前界面所在的域即为焦点域 → 100ms 高频实时结算
 * - 其他域：非当前视角的域 → 30 秒批量结算
 * - 切换界面时 → 新焦点域执行「追赶结算」
 *
 * ## 界面→域映射（参见 [com.xianxia.sect.core.GameEngineCore.computeDomainsFromView]）
 *
 * | 界面 | 域 | 原因 |
 * |------|-----|------|
 * | 宗门地图 | DISCIPLES + BUILDINGS | SectInfoCard 显示灵石/弟子数 |
 * | 弟子列表/详情 | DISCIPLES | 卡片显示境界 |
 * | 建筑 Tab | BUILDINGS | 显示生产进度 |
 * | 仓库 Tab | BUILDINGS + WAREHOUSE | 显示灵石+物品数量 |
 * | 灵矿/药园/炼丹/锻器/种植 | BUILDINGS | 显示生产进度 |
 * | 商人/宗门交易 | BUILDINGS + WAREHOUSE | 显示灵石数量 |
 * | 任务阁 | EXPLORATION + DISCIPLES | 显示任务进度 |
 * | 血炼池 | DISCIPLES + BUILDINGS | 显示血炼进度 |
 * | 世界地图 | WORLD_MAP | 地图标记/探索状态 |
 * | 外交 | DIPLOMACY | 好感度/关系变化 |
 * | 活动/邮件 | (仅 ALWAYS) | 一次性领取，无实时数据 |
 * | 天枢殿/宗门等级详情 | (仅 ALWAYS) | 长老任命/升级条件，无实时数据 |
 * | 设置/建造/招募/藏经阁/问道塔/青云塔 | (仅 ALWAYS) | 不显示随时间变化的数据 |
 * | 执法堂/思过崖/巡视楼/住所/建筑仓库 | (仅 ALWAYS) | 不显示随时间变化的数据 |
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
