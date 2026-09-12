package com.xianxia.sect.core.state

/**
 * 声明 GameData 字段在结算合并时的策略。
 *
 * 惰性结算引擎使用 stateStore.update {} 事务直接写入，不再有三路合并。
 * 此注解保留用于标记字段的合并语义（PRESERVE_OLD / DELTA 等），
 * 供自定义合并函数在特殊场景下使用。
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class SettlementStrategy(val value: Strategy)

enum class Strategy {
    /**
     * 始终保留 oldState —— 结算不修改此字段，玩家可能修改。
     * 适用场景：游戏设置（gameSpeed、autoSaveInterval）、兑换码记录、宗门切换等。
     */
    PRESERVE_OLD,

    /**
     * 始终使用 shadow —— 玩家不修改此字段（或结算独占写入）。
     * 适用场景：结算独有的元数据（isGameOver、merchantRefreshCount）、internal ID 等。
     */
    USE_SHADOW,

    /**
     * 数值增量合并：适用于 Long/Int/Double 等数值类型。
     * 适用场景：spiritStones（玩家买卖 + 结算灵矿/薪酬/政策）。
     */
    DELTA,

    /**
     * ID 三路合并：适用于 List<T> 且 T 有稳定 id 字段。
     * 适用场景：recruitList、activeMissions、alliances。
     */
    THREE_WAY_ID,

    /**
     * 自定义合并 —— 字段合并逻辑复杂无法自动处理。
     * 必须在 [GameStateStore.customFieldMergers] 中注册对应的合并函数。
     * 适用场景：worldLevels、worldMapSects、sectDetails、manualProficiencies 等。
     */
    CUSTOM
}
