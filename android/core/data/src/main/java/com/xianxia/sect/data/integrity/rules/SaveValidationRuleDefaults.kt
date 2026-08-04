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
            DiscipleAgePositiveRule,   // order=3
            GamePhaseRangeRule,        // order=4
            CultivationCapRule,        // order=5
            EquipmentRefRule,          // order=6
            AgeLifespanRule,           // order=7
            BuildingRefRule,           // order=8
            DuplicateDiscipleIdRule,   // order=9
            GhostDiscipleCleanupRule,  // order=10
            GhostRefCleanupRule,       // order=11
            SpiritStoneNonNegativeRule,// order=12
            DiscipleRealmConsistencyRule, // order=13
            DiscipleDeadStatusRule,    // order=14
            EquipmentDedupeRule,       // order=15
            SlotRefRule,               // order=16
            BloodRefinementRefRule,    // order=17
            ItemRefConsistencyRule,    // order=18
            EntityCountBoundsRule,     // order=19
            RecruitListCleanupRule,    // order=20
            BattleLogRefRule,          // order=21（battleLogs 条目结构校验）
            ManualTalentRefRule,       // order=22（manualIds/talentIds 悬空引用清理）
        )
    )
}
