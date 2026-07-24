package com.xianxia.sect.data.integrity.rules

/**
 * 注册所有内置 [SaveValidationRule] 的默认集合。
 */
fun SaveValidationRuleRegistry.registerDefaults() {
    registerAll(
        listOf(
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
        )
    )
}
