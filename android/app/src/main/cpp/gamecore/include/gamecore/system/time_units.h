#pragma once

#include <cstdint>

// ============================================================
// 时间单位常量栈（结算改造方案 2026-09-27 §2.2 —— 全仓唯一口径）
//
// 双轨时间改造的语义基座：
//   - 连续积分轨以「每游戏秒」为速率单位（结算项 ÷2/÷6/÷72 换算表）
//   - 游戏日历（年/月/旬）退化为派生投影，不再作为积分单位
//
// Kotlin 同源锚点：core/domain GameConfig.Time（同名常量同值同公式，
// GameTimeUnitsParityTest / time_units_test.cpp 双端各自锁定，改值须双端同步——
// 沿 maxPhasesPerTick 先例 settlement.h:34-40）。
//
// B1 批次为纯加性：本头文件当前零生产消费者（仅测试锁定）；
// 消费面随 B2（未截断推进）/B4（积分轨）接入。
// ============================================================
namespace gamecore::system {

/// 游戏秒定义：1 游戏秒 = 1000 游戏毫秒
constexpr int64_t kMsPerGameSecond = 1000;

/// 1 旬 = 2 游戏秒（1x 下 2000 现实毫秒；与 kMsPerPhase1x 同值同源换算）
constexpr double kGameSecondsPerPhase = 2.0;

/// 1 月 = 3 旬 = 6 游戏秒
constexpr double kGameSecondsPerMonth = 6.0;

/// 1 年 = 12 月 = 72 游戏秒
constexpr double kGameSecondsPerYear = 72.0;

/// 1 旬的游戏毫秒数（日历投影 ↔ 游戏毫秒互换的整数换算基数；
/// 1x 速度下与现实毫秒恒等——GameTimeClock.MS_PER_PHASE_1X 同值）
constexpr int64_t kGameMsPerPhase = 2000;

/// 1 月的游戏毫秒数（B5 槽位毫秒判据/回填的时长换算基数：
/// startedAt/completeAt 孪生、checkpoint 重算共用；整数形态防浮点漂移）
constexpr int64_t kGameMsPerMonth = 6000;

// 编译期一致性：整数换算基数与秒族常量逐位一致（防双处漂移）
static_assert(kGameMsPerMonth ==
                  static_cast<int64_t>(kGameSecondsPerMonth * kMsPerGameSecond),
              "kGameMsPerMonth must equal kGameSecondsPerMonth * kMsPerGameSecond");

// ── 换算公式（全仓唯一口径，方案 §2.2）─────────────────────────────

/// 每旬量 → 每游戏秒量（÷2.0）
inline double perPhaseToPerGameSecond(double perPhase) {
    return perPhase / kGameSecondsPerPhase;
}

/// 每月量 → 每游戏秒量（÷6.0）
inline double perMonthToPerGameSecond(double perMonth) {
    return perMonth / kGameSecondsPerMonth;
}

/// 每年量 → 每游戏秒量（÷72.0）
inline double perYearToPerGameSecond(double perYear) {
    return perYear / kGameSecondsPerYear;
}

}  // namespace gamecore::system