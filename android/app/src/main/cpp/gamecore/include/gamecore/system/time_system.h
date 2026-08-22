#pragma once

#include <cstdint>

#include "gamecore/state/models.h"

// ============================================================
// 游戏时间系统（Kotlin→C++ 迁移批次 3）
//
// 等价移植 Kotlin TimeSystem（core/engine/system/TimeSystem.kt）：
//   - onPhaseTick：推进 gamePhase，满 3 旬进位月，满 12 月进位年（纯函数）
//   - getTotalPhases：时间编码 year*12*3 + (month-1)*3 + phase
//   - isEndOfMonth / isEndOfYear
//
// 游戏时间 = 年/月/旬（1-based 月；旬 0..2 对应 GamePhase 上旬/中旬/下旬）。
// 此模块零 Android 依赖、无状态（输入 GameData 引用直接推进）。
// ============================================================
namespace gamecore::system {

constexpr int kPhasesPerMonth = 3;   // GamePhase.PHASES_PER_MONTH
constexpr int kMonthsPerYear = 12;

/// 推进一个旬（等价 TimeSystem.onPhaseTick(state, 1)）
/// 返回是否发生了月变（调用方可用 monthBefore/yearBefore 对比，或直接用返回值）
inline void advancePhase(state::GameData& gd) {
    int newPhase = gd.gamePhase + 1;
    int newMonth = gd.gameMonth;
    int newYear = gd.gameYear;
    if (newPhase >= kPhasesPerMonth) {
        newPhase = 0;
        ++newMonth;
        if (newMonth > kMonthsPerYear) {
            newMonth = 1;
            ++newYear;
        }
    }
    gd.gamePhase = newPhase;
    gd.gameMonth = newMonth;
    gd.gameYear = newYear;
}

/// 基于旬的总时间单位（等价 TimeSystem.getTotalPhases）
inline int64_t totalPhases(const state::GameData& gd) {
    return static_cast<int64_t>(gd.gameYear) * kMonthsPerYear * kPhasesPerMonth +
           (gd.gameMonth - 1) * kPhasesPerMonth + gd.gamePhase;
}

/// 月末判定（等价 TimeSystem.isEndOfMonth：gamePhase == 下旬）
inline bool isEndOfMonth(const state::GameData& gd) {
    return gd.gamePhase == kPhasesPerMonth - 1;
}

/// 年末判定（等价 TimeSystem.isEndOfYear：12 月 + 下旬）
inline bool isEndOfYear(const state::GameData& gd) {
    return gd.gameMonth == kMonthsPerYear && gd.gamePhase == kPhasesPerMonth - 1;
}

/// 旬差（等价 TimeSystem.calculatePhasesBetween）
inline int64_t phasesBetween(const state::GameData& from, const state::GameData& to) {
    return totalPhases(to) - totalPhases(from);
}

}  // namespace gamecore::system
