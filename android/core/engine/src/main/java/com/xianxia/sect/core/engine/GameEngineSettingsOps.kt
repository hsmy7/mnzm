package com.xianxia.sect.core.engine

/**
 * GameEngineSettingsOps — 设置项域 UI 操作面入口（batch-23 残余域下沉）。
 *
 * 写者审计（handover §2.55）：设置项域的 AUTHORITATIVE 稳态写者曾分散于
 * 三个 Delegate（SettingsDelegate / AutoAssignDelegate / DiscipleDelegate）
 * 各自调 `gameEngine.updateGameData { it.copy(field = value) }`——共 17 个
 * gameData 标量/Int 集字段。本文件（及同域拆分文件
 * `GameEngineSettingsAssignOps.kt`）把该域收敛为**带 native 臂的域入口**：
 * C++ 事务 `updateSettingsTx`（字段名 → 值通用补丁）为 AUTHORITATIVE 稳态
 * 写者，Kotlin `updateGameData` 原路径保留为降级回退臂（双实现并行契约）。
 *
 * 语义等价性：逐字段"字段级覆盖写 + 同值不写"；Int 集字段按 `Set<Int>`
 * 集合语义比较（Kotlin data class equals 同源）。
 *
 * 事务外残差：`daoCompanionConsentRequired=false` 触发的
 * `clearPendingMarriageProposals()`（pendingMarriageProposals 为 Kotlin
 * 运行态，不入 C++ 状态）由 [setDaoCompanionConsentRequired] 在 native 成功
 * 后照原序执行。
 *
 * 文件拆分：本域入口共 19 个（超 detekt TooManyFunctions 文件阈值 15）——
 * 按"通用/音频"与"自动分配策略"两族拆分，两文件共用同一 native 转发面
 * （`ResidualNativeForward` + `updateSettingsNative`）。
 */

// ── 音频设置（SettingsDelegate setSoundEnabled / setMusicEnabled）──────

/**
 * 音效开关。音频平台效应（AudioConfig.soundEnabled —— 播放器侧开关）由
 * 调用方在引擎线程内自行处理，本入口只承 gameData 状态字段。
 */
fun GameEngine.setSoundEnabled(enabled: Boolean) {
    updateSettingsOrFallback("soundEnabled" to flagValue(enabled)) {
        it.copy(soundEnabled = enabled)
    }
}

/** 音乐开关（平台效应同 [setSoundEnabled]）。 */
fun GameEngine.setMusicEnabled(enabled: Boolean) {
    updateSettingsOrFallback("musicEnabled" to flagValue(enabled)) {
        it.copy(musicEnabled = enabled)
    }
}

// ── 通用开关（SettingsDelegate 其余四项 + Inventory 域显示项）──────────

/** 巡视战报弹窗开关。 */
fun GameEngine.setPatrolBattleResultPopup(enabled: Boolean) =
    updateSettingsOrFallback("patrolBattleResultPopup" to flagValue(enabled)) {
        it.copy(patrolBattleResultPopup = enabled)
    }

/** 中品灵石自动出售（供购买）。 */
fun GameEngine.setAutoSellMidGradeForPurchase(enabled: Boolean) =
    updateSettingsOrFallback("autoSellMidGradeForPurchase" to flagValue(enabled)) {
        it.copy(autoSellMidGradeForPurchase = enabled)
    }

/** 高阶灵石自动出售（供购买）。 */
fun GameEngine.setAutoSellHighGradeForPurchase(enabled: Boolean) =
    updateSettingsOrFallback("autoSellHighGradeForPurchase" to flagValue(enabled)) {
        it.copy(autoSellHighGradeForPurchase = enabled)
    }

/** 显示全部可选弟子（招募列表筛选）。 */
fun GameEngine.setShowAllAvailableDisciples(enabled: Boolean) =
    updateSettingsOrFallback("showAllAvailableDisciples" to flagValue(enabled)) {
        it.copy(showAllAvailableDisciples = enabled)
    }

// ── 道侣设置（AutoAssignDelegate setDaoCompanion*）─────────────────────

/**
 * 道侣结成是否需要玩家同意。
 *
 * native 成功时**仍执行**事务外残差：关闭同意模式需清理所有待处理提议
 * （pendingMarriageProposals 为 Kotlin 运行态，不入 C++ 状态快照）。
 */
fun GameEngine.setDaoCompanionConsentRequired(required: Boolean) {
    updateSettingsOrFallback("daoCompanionConsentRequired" to flagValue(required)) {
        it.copy(daoCompanionConsentRequired = required)
    }
    if (!required) clearPendingMarriageProposals()
}

/** 禁止结为道侣的灵根数集合。 */
fun GameEngine.setDaoCompanionBannedRootCounts(counts: Set<Int>) =
    updateSettingsOrFallback("daoCompanionBannedRootCounts" to intSetValue(counts)) {
        it.copy(daoCompanionBannedRootCounts = counts)
    }

// ── 灵根过滤器（俘虏 / 自动招募 / 自动拒绝）────────────────────────────

/** 俘虏灵根过滤（勾选/取消即保存）。 */
fun GameEngine.setPrisonerSpiritRootFilter(filter: Set<Int>) =
    updateSettingsOrFallback("prisonerSpiritRootFilter" to intSetValue(filter)) {
        it.copy(prisonerSpiritRootFilter = filter)
    }

/** 自动招募灵根过滤。 */
fun GameEngine.setAutoRecruitSpiritRootFilter(filter: Set<Int>) =
    updateSettingsOrFallback("autoRecruitSpiritRootFilter" to intSetValue(filter)) {
        it.copy(autoRecruitSpiritRootFilter = filter)
    }

/** 自动拒绝灵根过滤。 */
fun GameEngine.setAutoRejectSpiritRootFilter(filter: Set<Int>) =
    updateSettingsOrFallback("autoRejectSpiritRootFilter" to intSetValue(filter)) {
        it.copy(autoRejectSpiritRootFilter = filter)
    }

// ── Private helpers ───────────────────────────────────────────────────

/**
 * 单事务补丁 + Kotlin 回退臂：native 成功即完成；失败/降级走 `updateGameDataSync`
 * 原语义（回退臂重执行字段覆盖写，与 C++ 同值不写语义等价）。
 *
 * 可见性 internal：同域拆分文件（`GameEngineSettingsAssignOps.kt`）共用。
 */
internal fun GameEngine.updateSettingsOrFallback(
    vararg entries: Pair<String, SettingPatchValue>,
    fallback: (com.xianxia.sect.core.model.GameData) -> com.xianxia.sect.core.model.GameData
) {
    if (updateSettingsNative(entries.toList())) return
    updateGameDataSync(fallback)
}

internal fun flagValue(value: Boolean): SettingPatchValue = SettingPatchValue.Flag(value)

internal fun intSetValue(values: Set<Int>): SettingPatchValue = SettingPatchValue.IntSet(values)
