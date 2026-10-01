package com.xianxia.sect.core.model

/**
 * 弟子功法等扩展字段的**内存侧**投影（v53/SR-7 起不再有 `disciples_extended`
 * 表，真相恒在 `disciples`）。
 */
data class DiscipleExtended(
    var discipleId: String = "",

    var manualIds: List<String> = emptyList(),
    var manualMasteries: Map<String, Int> = emptyMap(),
    var statusData: Map<String, String> = emptyMap(),
    var cultivationSpeedBonus: Double = 0.0,
    var cultivationSpeedDuration: Int = 0,
    var pillCultivationSpeedBonus: Double = 0.0,
    var pillEffectDuration: Int = 0,
    var usedFunctionalPillTypes: List<String> = emptyList(),
    var hasReviveEffect: Boolean = false,
    var hasClearAllEffect: Boolean = false
) {

    companion object {
        fun fromDisciple(disciple: Disciple): DiscipleExtended {
            return DiscipleExtended(
                discipleId = disciple.id,
                manualIds = disciple.manualIds,
                manualMasteries = disciple.manualMasteries,
                statusData = disciple.statusData,
                cultivationSpeedBonus = disciple.cultivationSpeedBonus,
                cultivationSpeedDuration = disciple.cultivationSpeedDuration,
                pillCultivationSpeedBonus = disciple.pillEffects.pillCultivationSpeedBonus,
                pillEffectDuration = disciple.pillEffects.pillEffectDuration,
                usedFunctionalPillTypes = disciple.usage.usedFunctionalPillTypes,
                hasReviveEffect = disciple.usage.hasReviveEffect,
                hasClearAllEffect = disciple.usage.hasClearAllEffect
            )
        }
    }
}
