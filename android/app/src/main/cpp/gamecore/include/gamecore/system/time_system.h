#pragma once

#include <cstdint>

#include "gamecore/state/models.h"
#include "gamecore/system/time_units.h"

// ============================================================
// 游戏时间系统
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
///
/// B5 起同步推进 GameData 权威轴：elapsedGameMs += kGameMsPerPhase（旬网格
/// 整数累积）。本函数是**全部旬推进的汇聚点**（生产 settleOnePhase / B4
/// accrual advanceOnePhaseAccrual / 对拍 advancePhases / shadow tick 均经此），
/// 权威轴在此单点保持与日历投影同步（INV-1）；未截断 ns 轴（PhaseClock）仍是
/// INV-2 完整轴，GameData 轴为其旬粒度投影——槽位毫秒判据
///（production.h isSlotCompleteDynamic）与存档/镜像面消费此字段。
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
    gd.elapsedGameMs += kGameMsPerPhase;
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

// ── 日历投影（INV-1：日历是权威时间轴的派生投影，不是推进源）────────
// 结算改造方案 2026-09-27 §2.1/§2.4：唯一权威时间轴是单调时钟累计的
// 游戏毫秒（elapsedGameMs）；年/月/旬由其纯函数派生，供展示与叙事。
// 换算基数 kGameMsPerPhase=2000（1 旬 = 2 游戏秒，1x 下与现实毫秒恒等）。

/// 绝对游戏毫秒 → 日历投影（gameYear/gameMonth/gamePhase 写出参）。
/// 负输入按 0 处理（单调时钟不回拨；防御性钳制）。
inline void projectCalendar(int64_t gameMs, int32_t& outYear,
                            int32_t& outMonth, int32_t& outPhase) {
    if (gameMs < 0) gameMs = 0;
    const int64_t totalPhaseCount = gameMs / kGameMsPerPhase;
    outYear = static_cast<int32_t>(totalPhaseCount /
                                   (static_cast<int64_t>(kMonthsPerYear) * kPhasesPerMonth)) + 1;
    const int64_t withinYear = totalPhaseCount %
                               (static_cast<int64_t>(kMonthsPerYear) * kPhasesPerMonth);
    outMonth = static_cast<int32_t>(withinYear / kPhasesPerMonth) + 1;
    outPhase = static_cast<int32_t>(withinYear % kPhasesPerMonth);
}

/// 日历 → 绝对游戏毫秒（读档归一化换算：旧档只有日历字段时回填
/// elapsedGameMs 的唯一口径；与 projectCalendar 精确互逆）。
inline int64_t calendarToGameMs(int32_t year, int32_t month, int32_t phase) {
    const int64_t totalPhaseCount =
        static_cast<int64_t>(year - 1) * kMonthsPerYear * kPhasesPerMonth +
        static_cast<int64_t>(month - 1) * kPhasesPerMonth + phase;
    return totalPhaseCount * kGameMsPerPhase;
}

// 编译期一致性：整数换算基数与秒族常量逐位一致（防双处漂移）
static_assert(kGameMsPerPhase ==
                  static_cast<int64_t>(kGameSecondsPerPhase * kMsPerGameSecond),
              "kGameMsPerPhase must equal kGameSecondsPerPhase * kMsPerGameSecond");

}  // namespace gamecore::system
