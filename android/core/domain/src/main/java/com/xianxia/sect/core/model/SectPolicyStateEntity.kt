package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

@Keep
@Serializable
@Entity(
    tableName = "sect_policy_state",
    primaryKeys = ["slot_id"],
    indices = [Index(value = ["slot_id"], unique = true)]
)
data class SectPolicyState(
    @ColumnInfo(name = "slot_id")
    var slotId: Int = 1,
    var sectPolicies: SectPolicies = SectPolicies(),
    var autoRecruitSpiritRootFilter: Set<Int> = emptySet(),
    var daoCompanionBannedRootCounts: Set<Int> = emptySet(),
    var daoCompanionConsentRequired: Boolean = false,
    var breakthroughAutoPillFocused: Boolean = false,
    var breakthroughAutoPillRootCounts: Set<Int> = emptySet(),
    var autoEquipFromWarehouseFocused: Boolean = false,
    var autoEquipFromWarehouseRootCounts: Set<Int> = emptySet(),
    var autoLearnFromWarehouseFocused: Boolean = false,
    var autoLearnFromWarehouseRootCounts: Set<Int> = emptySet(),
    var yearlySalary: Map<Int, Int> = mapOf(
        9 to 240, 8 to 720, 7 to 1200, 6 to 1920, 5 to 2640,
        4 to 4320, 3 to 5280, 2 to 6720, 1 to 8640, 0 to 12000
    ),
    var yearlySalaryEnabled: Map<Int, Boolean> = mapOf(
        9 to true, 8 to true, 7 to true, 6 to true, 5 to true,
        4 to true, 3 to true, 2 to true, 1 to true, 0 to true
    ),
    var autoSaveIntervalMonths: Int = 3
)
