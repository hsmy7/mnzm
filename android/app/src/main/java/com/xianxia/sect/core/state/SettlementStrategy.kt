package com.xianxia.sect.core.state

/**
 * 声明 GameData 字段在结算影子合并时的策略。
 *
 * 灵感来源：Microsoft Research "Concurrent Revisions" 论文中的 Isolation Types 模式
 * —— Versioned<T>、Cumulative<T>、CumulativeList<T> 将合并语义编码到类型层面。
 * 此处以注解形式实现，策略与字段定义放在一起，避免 swapFromShadow 中的白名单遗漏。
 *
 * 未标注的字段默认采用 [Strategy.USE_SHADOW]（结算独有，玩家不修改）。
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class SettlementStrategy(val value: Strategy)

enum class Strategy {
    /**
     * 始终保留 oldState —— 结算不修改此字段，玩家可能修改。
     * 合并引擎自动从 oldState 取值，无需在 swapFromShadow 中手动编写。
     * 适用场景：游戏设置（gameSpeed、autoSaveInterval）、兑换码记录、宗门切换等。
     */
    PRESERVE_OLD,

    /**
     * 始终使用 shadow —— 玩家不修改此字段（或结算独占写入）。
     * 合并引擎自动从 shadow 取值。**这是默认策略**，未标注的字段均为此策略。
     * 适用场景：结算独有的元数据（isGameOver、merchantRefreshCount）、internal ID 等。
     */
    USE_SHADOW,

    /**
     * 三路 delta 合并：oldValue + (shadowValue - originValue)。
     * 合并引擎自动计算。适用于 Long/Int/Double 等数值类型。
     * 适用场景：spiritStones（玩家买卖 + 结算灵矿/薪酬/政策）。
     */
    DELTA,

    /**
     * 三路 ID 合并：shadow 做底，oldState 中有而 origin 中无的条目视为玩家新增添加，
     * origin 中有而 oldState 中无的条目视为玩家删除。
     * 合并引擎自动处理增删。适用于 List<T> 且 T 有稳定 id 字段。
     * 适用场景：recruitList、activeMissions、alliances。
     */
    THREE_WAY_ID,

    /**
     * 自定义合并 —— 字段的合并逻辑过于复杂无法自动处理。
     * 必须在 [GameStateStore.customFieldMergers] 中注册对应的合并函数，
     * 否则合并时会抛出异常（测试也会捕获）。
     * 适用场景：worldLevels（defeated 单向标志）、worldMapSects（字段级合并）、
     *          sectDetails（子字段合并）、manualProficiencies（Map 增删合并）、
     *          aiSectDisciples（引用变更检测）等。
     */
    CUSTOM
}
