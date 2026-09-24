package com.xianxia.sect.core.model

/**
 * 弟子功法/天赋/关系等扩展字段的**内存侧**投影（v53/SR-7 起不再有 `disciples_extended`
 * 表，真相恒在 `disciples`）。
 */
data class DiscipleExtended(
    var discipleId: String = "",

    var slotId: Int = 0,

    var manualIds: List<String> = emptyList(),
    var talentIds: List<String> = emptyList(),
    var physiqueIds: List<String> = emptyList(),
    var affixIds: List<String> = emptyList(),
    var manualMasteries: Map<String, Int> = emptyMap(),
    var statusData: Map<String, String> = emptyMap(),
    var cultivationSpeedBonus: Double = 0.0,
    var cultivationSpeedDuration: Int = 0,
    var pillCultivationSpeedBonus: Double = 0.0,
    var pillEffectDuration: Int = 0,
    var masterId: String? = null,
    var usedFunctionalPillTypes: List<String> = emptyList(),
    var hasReviveEffect: Boolean = false,
    var hasClearAllEffect: Boolean = false
) {

    companion object {
        fun fromDisciple(disciple: Disciple): DiscipleExtended {
            return DiscipleExtended(
                discipleId = disciple.id,
                manualIds = disciple.manualIds,
                talentIds = disciple.talentIds,
                physiqueIds = disciple.physiqueIds,
                affixIds = disciple.affixIds,
                manualMasteries = disciple.manualMasteries,
                statusData = disciple.statusData,
                cultivationSpeedBonus = disciple.cultivationSpeedBonus,
                cultivationSpeedDuration = disciple.cultivationSpeedDuration,
                pillCultivationSpeedBonus = disciple.pillEffects.pillCultivationSpeedBonus,
                pillEffectDuration = disciple.pillEffects.pillEffectDuration,
                masterId = disciple.social.masterId,
                usedFunctionalPillTypes = disciple.usage.usedFunctionalPillTypes,
                hasReviveEffect = disciple.usage.hasReviveEffect,
                hasClearAllEffect = disciple.usage.hasClearAllEffect
            )
        }
    }
}
