package com.xianxia.sect.core.model.domain

import com.xianxia.sect.core.model.*

/**
 * 宗门政策领域状态 — 从 GameData 中提取的政策相关字段聚合。
 *
 * 纯领域模型，仅用于业务层传递。序列化由 GameData 负责，本类不标 @Serializable，
 * 以允许使用 Set/Map 类型（与 GameData 字段类型一致），避免 ProtoBuf 兼容问题。
 */
data class SectPolicyDomainState(
    val sectPolicies: SectPolicies = SectPolicies(),
    val autoRecruitSpiritRootFilter: Set<Int> = emptySet(),
    val daoCompanionBannedRootCounts: Set<Int> = emptySet(),
    val daoCompanionConsentRequired: Boolean = false,
    val breakthroughAutoPillFocused: Boolean = false,
    val breakthroughAutoPillRootCounts: Set<Int> = emptySet(),
    val autoEquipFromWarehouseFocused: Boolean = false,
    val autoEquipFromWarehouseRootCounts: Set<Int> = emptySet(),
    val autoLearnFromWarehouseFocused: Boolean = false,
    val autoLearnFromWarehouseRootCounts: Set<Int> = emptySet(),
    val monthlySalary: Map<Int, Int> = mapOf(
        9 to 20, 8 to 60, 7 to 100, 6 to 160, 5 to 220,
        4 to 360, 3 to 440, 2 to 560, 1 to 720, 0 to 1000
    ),
    val monthlySalaryEnabled: Map<Int, Boolean> = mapOf(
        9 to true, 8 to true, 7 to true, 6 to true, 5 to true,
        4 to true, 3 to true, 2 to true, 1 to true, 0 to true
    ),
    val autoSaveIntervalMonths: Int = 3
)

/** 从 GameData 提取宗门政策领域状态 */
fun GameData.extractSectPolicyState(): SectPolicyDomainState = SectPolicyDomainState(
    sectPolicies = sectPolicies,
    autoRecruitSpiritRootFilter = autoRecruitSpiritRootFilter,
    daoCompanionBannedRootCounts = daoCompanionBannedRootCounts,
    daoCompanionConsentRequired = daoCompanionConsentRequired,
    breakthroughAutoPillFocused = breakthroughAutoPillFocused,
    breakthroughAutoPillRootCounts = breakthroughAutoPillRootCounts,
    autoEquipFromWarehouseFocused = autoEquipFromWarehouseFocused,
    autoEquipFromWarehouseRootCounts = autoEquipFromWarehouseRootCounts,
    autoLearnFromWarehouseFocused = autoLearnFromWarehouseFocused,
    autoLearnFromWarehouseRootCounts = autoLearnFromWarehouseRootCounts,
    monthlySalary = monthlySalary,
    monthlySalaryEnabled = monthlySalaryEnabled,
    autoSaveIntervalMonths = autoSaveIntervalMonths
)

/** 将宗门政策领域状态合并回 GameData */
fun GameData.mergeSectPolicyState(state: SectPolicyDomainState): GameData = copy(
    sectPolicies = state.sectPolicies,
    autoRecruitSpiritRootFilter = state.autoRecruitSpiritRootFilter,
    daoCompanionBannedRootCounts = state.daoCompanionBannedRootCounts,
    daoCompanionConsentRequired = state.daoCompanionConsentRequired,
    breakthroughAutoPillFocused = state.breakthroughAutoPillFocused,
    breakthroughAutoPillRootCounts = state.breakthroughAutoPillRootCounts,
    autoEquipFromWarehouseFocused = state.autoEquipFromWarehouseFocused,
    autoEquipFromWarehouseRootCounts = state.autoEquipFromWarehouseRootCounts,
    autoLearnFromWarehouseFocused = state.autoLearnFromWarehouseFocused,
    autoLearnFromWarehouseRootCounts = state.autoLearnFromWarehouseRootCounts,
    monthlySalary = state.monthlySalary,
    monthlySalaryEnabled = state.monthlySalaryEnabled,
    autoSaveIntervalMonths = state.autoSaveIntervalMonths
)
