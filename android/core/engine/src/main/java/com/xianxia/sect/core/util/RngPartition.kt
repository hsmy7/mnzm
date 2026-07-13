package com.xianxia.sect.core.util

/**
 * RNG 分区枚举 — 不同游戏子系统使用独立 PRNG，防止跨域污染。
 *
 * 各分区独立序列化到 GameData.rngStates，确保读档后随机序列一致。
 */
enum class RngPartition(val id: Int) {
    /** 战斗系统：暴击/闪避/技能随机/命中判定 */
    BATTLE(0),
    /** 突破系统：突破成功/失败 */
    BREAKTHROUGH(1),
    /** 探索系统：妖兽移动/关卡生成/掠夺物品 */
    EXPLORATION(2),
    /** 系统级：UI 随机/非关键随机化 */
    SYSTEM(3)
}
