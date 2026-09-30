package com.xianxia.sect.data.integrity.rules

/**
 * 注册所有内置 [SaveValidationRule] 的默认集合。
 */
fun SaveValidationRuleRegistry.registerDefaults() {
    registerAll(
        listOf(
            NumericSanitizeRule,         // order=0（最先执行，NaN/负值消毒防穿透 cap 规则）
            DiscipleIdBoundsRule,      // order=1（C3-b：大 id 弟子扩容 OOM 前置拦截）
            SectNameRule,              // order=1
            GameDateRule,              // order=2
            GamePhaseRangeRule,        // order=4
            CultivationCapRule,        // order=5
            EquipmentRefRule,          // order=6
            BuildingRefRule,           // order=8
            DuplicateDiscipleIdRule,   // order=9
            GhostDiscipleCleanupRule,  // order=10
            GhostRefCleanupRule,       // order=11
            SpiritStoneNonNegativeRule,// order=12
            DiscipleRealmConsistencyRule, // order=13
            DiscipleDeadStatusRule,    // order=14
            EquipmentDedupeRule,       // order=15
            SlotRefRule,               // order=16
            ItemRefConsistencyRule,    // order=18
            EntityCountBoundsRule,     // order=19
            BattleLogRefRule,          // order=21（battleLogs 条目结构校验）
            JadeSymbolNonNegativeRule, // order=23（玉符字段负值/超限钳制）
            TimeAxisRule,              // order=25（双轨时间权威轴：旧档回填 + 投影一致性，结算改造 B3）
            // order=28（装备实例数值消毒：coerce 口径逐位一致，方案 §3.6/§6.1；
            // 排补偿之后——只对新生成/存量实例做数值面修正，不涉及资产折算）
            EquipmentValueSanitizeRule,
        )
    )
}
