package com.xianxia.sect.core.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "disciples_extended",
    indices = [
        Index(value = ["discipleId"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = DiscipleCore::class,
            parentColumns = ["id"],
            childColumns = ["discipleId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ]
)
data class DiscipleExtended(
    @PrimaryKey
    var discipleId: String = "",
    var manualIds: List<String> = emptyList(),
    var talentIds: List<String> = emptyList(),
    var manualMasteries: Map<String, Int> = emptyMap(),
    var statusData: Map<String, String> = emptyMap(),
    var cultivationSpeedBonus: Double = 1.0,
    var cultivationSpeedDuration: Int = 0,
    var partnerId: String? = null,
    var partnerSectId: String? = null,
    var parentId1: String? = null,
    var parentId2: String? = null,
    var lastChildYear: Int = 0,
    var griefEndYear: Int? = null,
    var monthlyUsedPillIds: List<String> = emptyList(),
    var usedExtendLifePillIds: List<String> = emptyList(),
    var hasReviveEffect: Boolean = false,
    var hasClearAllEffect: Boolean = false
) {
    val hasPartner: Boolean get() = partnerId != null
    
    companion object {
        fun fromDisciple(disciple: Disciple): DiscipleExtended {
            return DiscipleExtended(
                discipleId = disciple.id,
                manualIds = disciple.manualIds,
                talentIds = disciple.talentIds,
                manualMasteries = disciple.manualMasteries,
                statusData = disciple.statusData,
                cultivationSpeedBonus = disciple.cultivationSpeedBonus,
                cultivationSpeedDuration = disciple.cultivationSpeedDuration,
                partnerId = disciple.partnerId,
                partnerSectId = disciple.partnerSectId,
                parentId1 = disciple.parentId1,
                parentId2 = disciple.parentId2,
                lastChildYear = disciple.lastChildYear,
                griefEndYear = disciple.griefEndYear,
                monthlyUsedPillIds = disciple.monthlyUsedPillIds,
                usedExtendLifePillIds = disciple.usedExtendLifePillIds,
                hasReviveEffect = disciple.hasReviveEffect,
                hasClearAllEffect = disciple.hasClearAllEffect
            )
        }
    }
}
