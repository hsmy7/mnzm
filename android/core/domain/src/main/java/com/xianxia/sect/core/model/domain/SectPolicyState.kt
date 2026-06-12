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
    val yearlySalary: Map<Int, Int> = mapOf(
        9 to 240, 8 to 720, 7 to 1200, 6 to 1920, 5 to 2640,
        4 to 4320, 3 to 5280, 2 to 6720, 1 to 8640, 0 to 12000
    ),
    val yearlySalaryEnabled: Map<Int, Boolean> = mapOf(
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
    yearlySalary = yearlySalary,
    yearlySalaryEnabled = yearlySalaryEnabled,
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
    yearlySalary = state.yearlySalary,
    yearlySalaryEnabled = state.yearlySalaryEnabled,
    autoSaveIntervalMonths = state.autoSaveIntervalMonths
)
