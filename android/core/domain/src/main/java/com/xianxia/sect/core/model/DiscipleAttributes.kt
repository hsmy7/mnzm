package com.xianxia.sect.core.model

/**
 * 弟子资质/忠诚等属性族的**内存侧**投影（v53/SR-7 起不再有 `disciples_attributes` 表；
 * [DiscipleStatCalculator] 直接从 [DiscipleAggregate.attributes] 读，真相恒在 `disciples`）。
 */
data class DiscipleAttributes(
    var discipleId: String = "",

    var slotId: Int = 0,

    var intelligence: Int = 50,
    var charm: Int = 50,
    var loyalty: Int = 50,
    var comprehension: Int = 50,
    var artifactRefining: Int = 50,
    var pillRefining: Int = 50,
    var spiritPlanting: Int = 50,
    var mining: Int = 50,
    var teaching: Int = 50,
    var morality: Int = 50,
    var aptitude: Int = 50,
    var salaryPaidCount: Int = 0,
    var salaryMissedCount: Int = 0,
    var alchemyLevel: Int = 0,
    var alchemyPromotionCount: Int = 0,
    var forgeLevel: Int = 0,
    var forgePromotionCount: Int = 0
) {
    companion object {
        fun fromDisciple(disciple: Disciple): DiscipleAttributes {
            return DiscipleAttributes(
                discipleId = disciple.id,
                intelligence = disciple.skills.intelligence,
                charm = disciple.skills.charm,
                loyalty = disciple.skills.loyalty,
                comprehension = disciple.skills.comprehension,
                artifactRefining = disciple.skills.artifactRefining,
                pillRefining = disciple.skills.pillRefining,
                spiritPlanting = disciple.skills.spiritPlanting,
                mining = disciple.skills.mining,
                teaching = disciple.skills.teaching,
                morality = disciple.skills.morality,
                aptitude = disciple.skills.aptitude,
                salaryPaidCount = disciple.skills.salaryPaidCount,
                salaryMissedCount = disciple.skills.salaryMissedCount,
                alchemyLevel = disciple.skills.alchemyLevel,
                alchemyPromotionCount = disciple.skills.alchemyPromotionCount,
                forgeLevel = disciple.skills.forgeLevel,
                forgePromotionCount = disciple.skills.forgePromotionCount
            )
        }
    }
}
