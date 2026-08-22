// 由 scripts/gen-action-ids.mjs 生成 — 禁止手改（与 ActionIds.kt 同源）
#pragma once

// ============================================================
// ActionId 协议（业务操作码）— 与 Kotlin ActionIds.kt 同步生成
// 参数/结果一律 JSON 字节（nlohmann/json ↔ kotlinx.serialization）
// ============================================================
namespace gamecore {

/// 业务操作码（Kotlin GameCoreBridge.nativeExecute 的 actionId）
namespace action {
}  // namespace action

}  // namespace gamecore
