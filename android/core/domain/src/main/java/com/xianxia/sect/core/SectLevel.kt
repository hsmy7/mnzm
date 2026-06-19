package com.xianxia.sect.core

/**
 * 宗门等级定义与工具函数。
 *
 * 宗门等级由弟子最高境界动态决定：
 *
 * | 等级      | 条件（弟子最高境界）               | Realm 范围 |
 * |-----------|----------------------------------|-----------|
 * | 小型(0)   | 无化神及以上 → 最高元婴(6)及以下    | ≥ 6      |
 * | 中型(1)   | 有化神(5)，无炼虚及以上            | = 5      |
 * | 大型(2)   | 有炼虚(4)/合体(3)，无大乘及以上    | 3..4     |
 * | 顶级(3)   | 有大乘(2)/渡劫(1)/仙人(0)         | ≤ 2      |
 *
 * Realm Int 值越小代表境界越高（0=仙人最高，9=炼气最低）。
 */
object SectLevel {

    /** 小型宗门 */
    const val SMALL = 0

    /** 中型宗门 */
    const val MEDIUM = 1

    /** 大型宗门 */
    const val LARGE = 2

    /** 顶级宗门 */
    const val TOP = 3

    /**
     * 根据弟子最高境界推算宗门等级。
     *
     * @param highestRealm 弟子中数值最小的 realm（即最高境界）
     * @return 宗门等级 0-3
     */
    fun fromHighestRealm(highestRealm: Int): Int = when {
        highestRealm <= 2 -> TOP     // 大乘(2) / 渡劫(1) / 仙人(0)
        highestRealm <= 4 -> LARGE   // 炼虚(4) / 合体(3)
        highestRealm == 5 -> MEDIUM  // 化神(5)
        else -> SMALL                // 元婴(6) 及以下
    }

    /**
     * 宗门等级中文名称。
     */
    fun levelName(level: Int): String = when (level) {
        SMALL -> "小型宗门"
        MEDIUM -> "中型宗门"
        LARGE -> "大型宗门"
        TOP -> "顶级宗门"
        else -> "小型宗门"
    }

    /**
     * AI 宗门生成弟子时的境界上限（不可超过此境界）。
     *
     * @return 该等级宗门允许的最高 realm 值（含）
     */
    fun maxRealmForLevel(level: Int): Int = when (level) {
        SMALL -> 6   // 元婴 — 无化神及以上
        MEDIUM -> 5  // 化神 — 无炼虚及以上
        LARGE -> 3   // 合体 — 含炼虚/合体，无大乘及以上
        TOP -> 0     // 仙人 — 无上限
        else -> 6
    }
}
