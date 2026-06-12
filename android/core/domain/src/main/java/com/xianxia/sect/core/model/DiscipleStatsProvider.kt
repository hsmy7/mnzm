package com.xianxia.sect.core.model

/**
 * 弟子属性计算提供者接口
 *
 * 在 :core:domain 定义，由 :app 中的 DiscipleStatCalculator 代理实现。
 * 解除 domain 对 engine 的编译依赖。
 */
interface DiscipleStatsProvider {
    fun getBaseStats(disciple: Disciple): DiscipleStats
    fun getBaseStats(aggregate: DiscipleAggregate): DiscipleStats
    fun getTalentEffects(disciple: Disciple): Map<String, Double>
    fun getTalentEffects(aggregate: DiscipleAggregate): Map<String, Double>
    fun getStatsWithEquipment(disciple: Disciple, equipments: Map<String, EquipmentInstance>): DiscipleStats
    fun getStatsWithEquipment(aggregate: DiscipleAggregate, equipments: Map<String, EquipmentInstance>): DiscipleStats
    fun getFinalStats(
        disciple: Disciple,
        equipments: Map<String, EquipmentInstance>,
        manuals: Map<String, ManualInstance>,
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
    ): DiscipleStats
    fun getFinalStats(
        aggregate: DiscipleAggregate,
        equipments: Map<String, EquipmentInstance>,
        manuals: Map<String, ManualInstance>,
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
    ): DiscipleStats
    fun calculateCultivationSpeed(
        disciple: Disciple,
        manuals: Map<String, ManualInstance> = emptyMap(),
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
        buildingBonus: Double = 1.0,
        additionalBonus: Double = 0.0,
        preachingElderBonus: Double = 0.0,
        preachingMastersBonus: Double = 0.0,
        cultivationSubsidyBonus: Double = 0.0,
        parentCultivationBonus: Double = 0.0,
        griefCultivationSpeedPenalty: Double = 0.0
    ): Double
    fun calculateCultivationSpeed(
        aggregate: DiscipleAggregate,
        manuals: Map<String, ManualInstance> = emptyMap(),
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
        buildingBonus: Double = 1.0,
        additionalBonus: Double = 0.0,
        preachingElderBonus: Double = 0.0,
        preachingMastersBonus: Double = 0.0,
        cultivationSubsidyBonus: Double = 0.0,
        parentCultivationBonus: Double = 0.0,
        griefCultivationSpeedPenalty: Double = 0.0
    ): Double
    fun getBreakthroughChance(
        disciple: Disciple,
        innerElderComprehension: Int = 0,
        outerElderComprehensionBonus: Double = 0.0,
        pillBonus: Double = 0.0,
        adBonus: Double = 0.0,
        griefBreakthroughPenalty: Double = 0.0
    ): Double
    fun getBreakthroughChance(
        aggregate: DiscipleAggregate,
        innerElderComprehension: Int = 0,
        outerElderComprehensionBonus: Double = 0.0,
        pillBonus: Double = 0.0,
        adBonus: Double = 0.0,
        griefBreakthroughPenalty: Double = 0.0
    ): Double
}
