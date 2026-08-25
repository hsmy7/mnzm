#pragma once

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/disciple.h"

// ============================================================
// 修炼推进系统（Kotlin→C++ 迁移批次 5b）
//
// 等价移植 Kotlin CultivationService.accumulateCultivationPerPhase /
// computeMaxCultivation + DiscipleTables.checkpointDisciple /
// getEffectiveCultivation 的**纯逻辑**部分：
//
//   - 每旬修炼累积：cultivations[id] = min(curCult + rate, maxCultivation)
//   - maxCultivation：base + (realmLayer-1) × (nextBase - base) / maxLayers
//     （realm==0 仙人：返回当前修为，不再增长）
//   - 修炼检查点：速率变化点同步 checkpoint（cultivationCheckpoints/
//     cultivationCheckpointGameMonths）
//   - 投影：checkpoint + rate × Δmonth × 3（每旬 = 每月的 1/3）
//
// 与 Kotlin 语义对齐要点：
//   - 绝对月份 = gameYear × 12 + gameMonth
//   - monthsElapsed.coerceAtLeast(0)；rate<=0 返回 checkpoint
//   - 无检查点时回退实际修炼值（旧数据/新弟子兼容）
// ============================================================
namespace gamecore::system {

/// 绝对月份（gameYear × 12 + gameMonth）
inline int32_t toAbsoluteMonth(int32_t year, int32_t month) {
    return year * 12 + month;
}

/// 当前境界满修为值（Kotlin computeMaxCultivation）
/// realm==0（仙人）返回当前修为（不再增长，防溢出）
inline double computeMaxCultivation(int32_t realm, int32_t realmLayer,
                                    double cultivation) {
    if (realm == 0) return cultivation;
    const double base = static_cast<double>(gamecore::disciple::realmConfig(realm).cultivationBase);
    const double nextBase = static_cast<double>(
        gamecore::disciple::realmConfig(realm - 1).cultivationBase);
    const int32_t maxLayers = gamecore::disciple::realmConfig(realm).maxLayers;
    return base + (realmLayer - 1) * (nextBase - base) /
                      static_cast<double>(maxLayers);
}

/// 单弟子每旬修炼累积（Kotlin accumulateCultivationPerPhase 核心）
/// @return 更新后的修为值（未达上限时 = cur + rate）
inline double accumulateCultivationPerPhase(
    state::Disciple& disciple, double rate) {
    if (!disciple.isAlive || rate <= 0.0) return disciple.cultivation;
    const double maxCultivation =
        computeMaxCultivation(disciple.realm, disciple.realmLayer,
                              disciple.cultivation);
    if (disciple.cultivation >= maxCultivation) return disciple.cultivation;
    const double next = std::min(disciple.cultivation + rate, maxCultivation);
    disciple.cultivation = next;
    return next;
}

/// 修炼检查点（Kotlin DiscipleTables.checkpointDisciple）
inline void checkpointDisciple(state::Disciple& disciple, int32_t currentMonth) {
    if (!disciple.isAlive) return;
    disciple.cultivationCheckpoint = disciple.cultivation;
    disciple.cultivationCheckpointGameMonth = currentMonth;
}

/// 修炼投影值（Kotlin DiscipleTables.getEffectiveCultivation）
inline double getEffectiveCultivation(const state::Disciple& disciple,
                                      int32_t currentMonth, double rate) {
    // 无检查点 → 回退实际修炼值（旧数据/新弟子兼容）
    // C++ 模型用 0 表示无检查点（Kotlin 用 Map contains 判定；新弟子默认 0）
    if (disciple.cultivationCheckpointGameMonth == 0 &&
        disciple.cultivationCheckpoint == 0.0 &&
        disciple.cultivation == 0.0) {
        return disciple.cultivation;
    }
    // 对齐 Kotlin：cultivationCheckpoints 不含 id → 返回实际值。
    // C++ 侧用 hasCheckpoint 语义：checkpointGameMonth==0 视为无检查点
    // （Kotlin 新弟子无检查点条目；但读档后 checkpoint 可能为 0 且有月份——
    // 精确语义：Kotlin contains(id) 由"曾调用 checkpointDisciple"决定，
    // C++ 用 checkpointGameMonth != 0 近似（checkpointDisciple 必写月份））
    if (disciple.cultivationCheckpointGameMonth == 0) {
        return disciple.cultivation;
    }
    if (rate <= 0.0) return disciple.cultivationCheckpoint;
    const int32_t monthsElapsed = std::max(currentMonth - disciple.cultivationCheckpointGameMonth, 0);
    if (monthsElapsed <= 0) return disciple.cultivationCheckpoint;
    return disciple.cultivationCheckpoint + rate * monthsElapsed * 3.0;
}

// ── 全量 Checkpoint（Kotlin checkpointAllDisciples）─────────────

/// 全量弟子检查点（对所有存活弟子同步）
inline void checkpointAllDisciples(std::vector<state::Disciple>& disciples,
                                   int32_t currentMonth) {
    for (auto& d : disciples) {
        checkpointDisciple(d, currentMonth);
    }
}

}  // namespace gamecore::system
