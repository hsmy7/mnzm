package com.xianxia.sect.core.engine.event

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  已迁移至 EventBus（GameEvents.kt）
 *
 *  GameEventBus 的所有事件类型已存在于 [com.xianxia.sect.core.event]
 *  包的 GameEvents.kt 中，由 EventBusPort/EventBus 提供。
 *
 *  迁移前状态（归档参考）：
 *
 *  GameEventBus (engine.event)   → EventBus (core.event)
 *  ──────────────────────────   ─────────────────────────────
 *  BattleCompletedEvent          → GameEvents.BattleCompletedEvent
 *  BuildingPlacedEvent           → GameEvents.BuildingCompletedEvent
 *  BuildingRemovedEvent          → (无直接对等事件，移除且无需迁移)
 *  DiscipleDeathEvent            → GameEvents.DeathEvent
 *  SettlementCompletedEvent      → (无直接对等事件，移除且无需迁移)
 *  SaveCompletedEvent            → GameEvents.SaveEvent
 *
 *  此文件保留空壳以便编译通过。在适当的时候应删除此文件。
 *
 *  ⚠ 零调用方 — GameEventBus 从未被任何代码注入或使用。
 * ═══════════════════════════════════════════════════════════════════════
 */

// GameEventBus 及其事件类型已全部迁移至 com.xianxia.sect.core.event.GameEvents.kt
// 此文件无任何运行时代码，仅保留包声明以免删除文件本身产生 Git 冲突。
