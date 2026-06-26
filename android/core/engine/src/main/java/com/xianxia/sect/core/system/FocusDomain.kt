package com.xianxia.sect.core.engine.system

/**
 * 玩家关注域 — 两档制 tick 分频。
 *
 * ## 判定标准
 * - 焦点域：界面显示生产信息（灵石/境界/修炼进度/生产进度条等）→ 100ms 高频
 * - 非焦点域：界面不显示生产信息 → 30 秒批量结算
 * - 切换界面时 → 焦点域执行「追赶结算」
 *
 * ## 界面归属（参见 [com.xianxia.sect.core.GameEngineCore.computeDomainsFromView]）
 *
 * | 界面 | 域 | 原因 |
 * |------|-----|------|
 * | 宗门地图 | DISCIPLES + BUILDINGS | SectInfoCard 显示灵石/弟子数 |
 * | 弟子列表/详情 | DISCIPLES | 卡片显示境界 |
 * | 仓库 Tab | BUILDINGS + WAREHOUSE | 显示灵石+物品数量 |
 * | 灵矿/药园/炼丹/锻器/种植 | BUILDINGS | 显示生产进度 |
 * | 商人/宗门交易 | BUILDINGS + WAREHOUSE | 显示灵石数量 |
 * | 任务阁 | EXPLORATION + DISCIPLES | 显示任务进度 |
 * | 血炼池 | DISCIPLES + BUILDINGS | 显示血炼进度 |
 * | 外交/活动/邮件/世界地图 | (非焦点域) | 不显示生产信息 |
 * | 天枢殿/宗门等级详情 | (非焦点域) | 长老任命/升级条件，无实时数据 |
 * | 设置/建造/招募/藏经阁/问道塔/青云塔 | (非焦点域) | 不显示生产信息 |
 * | 执法堂/思过崖/巡视楼/住所/建筑仓库 | (非焦点域) | 不显示生产信息 |
 *
 * WORLD_MAP/DIPLOMACY 枚举保留但不再用于焦点域高频结算（仅 30s 批量）。
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

    /** 世界地图（已降为非焦点域，保留枚举兼容旧代码） */
    WORLD_MAP,

    /** 外交（已降为非焦点域，保留枚举兼容旧代码） */
    DIPLOMACY,

    /** 探索/任务 — 任务进度 */
    EXPLORATION,

    /** 后台 — AI 宗门、生育、邮件等玩家不可见的系统（30 秒一次） */
    BACKGROUND
}
