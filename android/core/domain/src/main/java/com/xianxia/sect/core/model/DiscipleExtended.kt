package com.xianxia.sect.core.model

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "disciples_extended",
    primaryKeys = ["discipleId", "slot_id"]
)
data class DiscipleExtended(
    @ColumnInfo(name = "discipleId")
    var discipleId: String = "",

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    var manualIds: List<String> = emptyList(),
    var talentIds: List<String> = emptyList(),
    var manualMasteries: Map<String, Int> = emptyMap(),
    var statusData: Map<String, String> = emptyMap(),
    var cultivationSpeedBonus: Double = 0.0,
    var cultivationSpeedDuration: Int = 0,
    var partnerId: String? = null,
    var partnerSectId: String? = null,
    var parentId1: String? = null,
    var parentId2: String? = null,
    var lastChildYear: Int = 0,
    var griefEndYear: Int? = null,
    var usedFunctionalPillTypes: List<String> = emptyList(),
    var usedExtendLifePillIds: List<String> = emptyList(),
    var hasReviveEffect: Boolean = false,
    var hasClearAllEffect: Boolean = false,
    var autoLearnFromWarehouse: Boolean = false
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
                partnerId = disciple.social.partnerId,
                partnerSectId = disciple.social.partnerSectId,
                parentId1 = disciple.social.parentId1,
                parentId2 = disciple.social.parentId2,
                lastChildYear = disciple.social.lastChildYear,
                griefEndYear = disciple.social.griefEndYear,
                usedFunctionalPillTypes = disciple.usage.usedFunctionalPillTypes,
                usedExtendLifePillIds = disciple.usage.usedExtendLifePillIds,
                hasReviveEffect = disciple.usage.hasReviveEffect,
                hasClearAllEffect = disciple.usage.hasClearAllEffect,
                autoLearnFromWarehouse = disciple.autoLearnFromWarehouse
            )
        }
    }
}
