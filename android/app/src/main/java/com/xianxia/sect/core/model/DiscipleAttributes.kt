@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "disciples_attributes",
    primaryKeys = ["discipleId", "slot_id"],
    indices = [
        Index(value = ["loyalty"])
    ]
)
data class DiscipleAttributes(
    @ColumnInfo(name = "discipleId")
    var discipleId: String = "",

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    var intelligence: Int = 50,
    var charm: Int = 50,
    var loyalty: Int = 50,
    var comprehension: Int = 50,
    var artifactRefining: Int = 50,
    var pillRefining: Int = 50,
    var spiritPlanting: Int = 50,
    var teaching: Int = 50,
    var morality: Int = 50,
    var salaryPaidCount: Int = 0,
    var salaryMissedCount: Int = 0
) {
    val comprehensionSpeedBonus: Double get() = 1.0 + (comprehension - 50) * 0.02
    
    companion object {
        fun fromDisciple(disciple: Disciple): DiscipleAttributes {
            return DiscipleAttributes(
                discipleId = disciple.id,
                intelligence = disciple.intelligence,
                charm = disciple.charm,
                loyalty = disciple.loyalty,
                comprehension = disciple.comprehension,
                artifactRefining = disciple.artifactRefining,
                pillRefining = disciple.pillRefining,
                spiritPlanting = disciple.spiritPlanting,
                teaching = disciple.teaching,
                morality = disciple.morality,
                salaryPaidCount = disciple.salaryPaidCount,
                salaryMissedCount = disciple.salaryMissedCount
            )
        }
    }
}
