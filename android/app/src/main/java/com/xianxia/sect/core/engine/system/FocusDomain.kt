package com.xianxia.sect.core.engine.system

/**
 * 玩家关注域 — 两档制 tick 分频。
 *
 * 规则：
 * - 玩家当前界面所属域 → 100ms 高频（activeDomains 中的域）
 * - 非活跃域 → 30 秒一次慢结算
 * - 玩家切换界面时 → 目标域立即执行「追赶结算」
 */
enum class FocusDomain {
    /** 时间推进 — 始终高频，每 tick 必执行 */
    ALWAYS,

    /** 弟子 — 列表、详情、修炼、装备、突破 */
    DISCIPLES,

    /** 建筑/生产 — 建造、生产队列、灵田、灵矿、药园、炼丹、锻器 */
    BUILDINGS,

    /** 仓库/物品 */
    WAREHOUSE,

    /** 世界地图 */
    WORLD_MAP,

    /** 外交 */
    DIPLOMACY,

    /** 探索/巡逻/战斗 */
    EXPLORATION,

    /** 后台 — AI 宗门、生育、邮件等玩家不可见的系统（30 秒一次） */
    BACKGROUND
}
