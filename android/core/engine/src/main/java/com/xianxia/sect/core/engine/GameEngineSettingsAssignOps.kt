package com.xianxia.sect.core.engine

/**
 * GameEngineSettingsAssignOps — 自动分配策略族设置项入口（batch-23 残余域下沉）。
 *
 * 与 `GameEngineSettingsOps.kt` 同域拆分（两域入口按"通用/音频"与
 * "自动分配策略"两族拆分）：承载自动装备/学习/突破丹药三组
 * "开关 + 灵根数白名单"配对字段入口；
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
