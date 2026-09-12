package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.service.RecruitService

/**
 * GameEngineSettingsAssignOps — 自动分配策略族设置项入口（batch-23 残余域下沉）。
 *
 * 与 `GameEngineSettingsOps.kt` 同域拆分（两文件合计 19 个入口，超 detekt
 * TooManyFunctions 文件阈值 15 的一半）：承载自动装备/学习/突破丹药三组
 * "开关 + 灵根数白名单"配对字段与招募过滤的**校验包装**入口；
 * native 面共用 `updateSettingsNative`（`SETTINGS_PATCH_TX`）。
 *
 * 自动分配策略族（`sectPolicies`）走独立入口 `batchUpdateAutoAssignAndGuide`
 * ——batch-18 已下沉 `boundary_tx.h`，**不属本文件面**（见 handover §2.55）。
 */

// ── 突破丹药 / 自动装备 / 自动学习（AutoAssignDelegate 三组）──────────

/** 突破时自动使用丹药（开关 + 灵根数白名单，单补丁原子写）。 */
fun GameEngine.setBreakthroughAutoPillSettings(focused: Boolean, rootCounts: Set<Int>) =
    updateSettingsOrFallback(
        "breakthroughAutoPillFocused" to flagValue(focused),
        "breakthroughAutoPillRootCounts" to intSetValue(rootCounts)
    ) {
        it.copy(
            breakthroughAutoPillFocused = focused,
            breakthroughAutoPillRootCounts = rootCounts
        )
    }

/** 自动从仓库装备（开关 + 灵根数白名单）。 */
fun GameEngine.setAutoEquipSettings(focused: Boolean, rootCounts: Set<Int>) =
    updateSettingsOrFallback(
        "autoEquipFromWarehouseFocused" to flagValue(focused),
        "autoEquipFromWarehouseRootCounts" to intSetValue(rootCounts)
    ) {
        it.copy(
            autoEquipFromWarehouseFocused = focused,
            autoEquipFromWarehouseRootCounts = rootCounts
        )
    }

/** 自动从仓库学习（开关 + 灵根数白名单）。 */
fun GameEngine.setAutoLearnSettings(focused: Boolean, rootCounts: Set<Int>) =
    updateSettingsOrFallback(
        "autoLearnFromWarehouseFocused" to flagValue(focused),
        "autoLearnFromWarehouseRootCounts" to intSetValue(rootCounts)
    ) {
        it.copy(
            autoLearnFromWarehouseFocused = focused,
            autoLearnFromWarehouseRootCounts = rootCounts
        )
    }

// ── 招募过滤校验包装（DiscipleDelegate setAutoRecruitFilter / setAutoRejectFilter）──

/**
 * 自动招募过滤（DiscipleDelegate 入口）：1..5 合法性预筛 + native 写 +
 * **事务外惰性门重置**（`RecruitService.resetAutoRecruitIdle()` —— 纯运行态，
 * 不入 C++ 状态；native 成功路径同样必须执行，否则筛选变更后惰性门滞留）。
 */
fun GameEngine.setAutoRecruitFilterValidated(filter: Set<Int>) {
    setAutoRecruitSpiritRootFilter(filter.filter { it in SPIRIT_ROOT_COUNT_RANGE }.toSet())
    RecruitService.resetAutoRecruitIdle()
}

/**
 * 自动拒绝过滤（DiscipleDelegate 入口）：语义同 [setAutoRecruitFilterValidated]，
 * 惰性门为 `resetAutoRejectIdle()`。
 */
fun GameEngine.setAutoRejectFilterValidated(filter: Set<Int>) {
    setAutoRejectSpiritRootFilter(filter.filter { it in SPIRIT_ROOT_COUNT_RANGE }.toSet())
    RecruitService.resetAutoRejectIdle()
}

/** 灵根数合法范围（DiscipleDelegate 原 `it in 1..5` 预筛，1~5 灵根） */
private val SPIRIT_ROOT_COUNT_RANGE = 1..5
