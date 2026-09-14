#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/cultivation.h"

// ============================================================
// 世界关卡管理
//
// 等价移植 Kotlin WorldLevelManager 的**纯逻辑**部分：
//   - 关卡过期判定（checkExpired：月度窗口）
//   - 过期/已击败关卡清理（processMonthly 第 1 步）
//   - 妖兽移动（moveBeasts：极坐标随机偏移 + 边界钳制）
//   - 月度刷新判定（每 3 月）
//
// 与 Kotlin 语义对齐要点：
//   - RNG 走 EXPLORATION 分区（rng.getRng(RngPartition::kExploration)）
//   - 移动：angle = nextDouble()×2π；dist = nextDouble()×BEAST_MOVE_DISTANCE(25)
//   - 位置钳制：BORDER_PADDING(34) ≤ x ≤ MAP_WIDTH-34；y 同理
//   - 洞府/已击败/已过期关卡不移动
// ============================================================
namespace gamecore::system {

// ── 世界地图常量（Kotlin GameConfig.WorldMap）────────────────

constexpr double kMapWidth = 1698.0;
constexpr double kMapHeight = 926.0;
constexpr double kBorderPadding = 34.0;
constexpr double kBeastMoveDistance = 25.0;
constexpr int32_t kLevelRefreshIntervalMonths = 3;
/// 单次刷新新关卡数量上限（Kotlin LevelGenerator.generateWorldLevels 的
/// maxNewLevels 默认值 6——数量 = nextInt(6) + 1）
constexpr int32_t kMaxNewLevelsDefault = 6;
constexpr double kPi = 3.14159265358979323846;

/// 关卡过期判定（Kotlin WorldLevel.checkExpired）
/// 规则：defeated → 过期；year > expiryYear → 过期；
/// year == expiryYear && month >= expiryMonth → 过期（含等号）
inline bool checkLevelExpired(const state::WorldLevel& level,
                              int32_t year, int32_t month) {
    if (level.defeated) return true;
    if (year > level.expiryYear) return true;
    if (year == level.expiryYear && month >= level.expiryMonth) return true;
    return false;
}

/// 清理过期/已击败关卡（Kotlin processMonthly 第 1 步）
inline std::vector<state::WorldLevel> filterExpiredLevels(
    const std::vector<state::WorldLevel>& levels,
    int32_t year, int32_t month) {
    std::vector<state::WorldLevel> out;
    for (const auto& l : levels) {
        if (!checkLevelExpired(l, year, month)) out.push_back(l);
    }
    return out;
}

/// 月度刷新判定（Kotlin processMonthly 第 2 步：每 3 月）
inline bool shouldRefreshLevels(int32_t lastRefreshMonth, int32_t year,
                                int32_t month) {
    const int32_t absoluteMonth = toAbsoluteMonth(year, month);
    return lastRefreshMonth == 0 ||
           (absoluteMonth - lastRefreshMonth) >= kLevelRefreshIntervalMonths;
}

/// 妖兽移动（Kotlin moveBeasts：极坐标偏移 + 边界钳制）
inline std::vector<state::WorldLevel> moveBeasts(
    const std::vector<state::WorldLevel>& levels,
    int32_t year, int32_t month, rng::RngManager& rng) {
    const float minX = static_cast<float>(kBorderPadding);
    const float maxX = static_cast<float>(kMapWidth - kBorderPadding);
    const float minY = static_cast<float>(kBorderPadding);
    const float maxY = static_cast<float>(kMapHeight - kBorderPadding);

    std::vector<state::WorldLevel> out;
    out.reserve(levels.size());
    for (auto level : levels) {
        // 洞府（type != "BEAST"）、已击败、已过期不移动
        if (level.type != "BEAST" || level.defeated ||
            checkLevelExpired(level, year, month)) {
            out.push_back(level);
            continue;
        }
        const double angle = rng.getRng(rng::RngPartition::kExploration)
                                 .nextDouble() * 2.0 * kPi;
        const double dist = rng.getRng(rng::RngPartition::kExploration)
                                .nextDouble() * kBeastMoveDistance;
        level.x = std::max(minX, std::min(maxX,
            level.x + static_cast<float>(std::cos(angle) * dist)));
        level.y = std::max(minY, std::min(maxY,
            level.y + static_cast<float>(std::sin(angle) * dist)));
        out.push_back(level);
    }
    return out;
}

/// 月度处理入口（Kotlin processMonthly 核心：清理 + 刷新判定 + 移动）
/// @param lastRefreshMonth 上次刷新月（输出时更新）
/// @param shouldRefresh 是否刷新（由调用方决定——需玩家宗门存在）
struct MonthlyLevelResult {
    std::vector<state::WorldLevel> levels;
    bool refreshed = false;
};

inline MonthlyLevelResult processWorldLevelsMonthly(
    const std::vector<state::WorldLevel>& levels,
    int32_t lastRefreshMonth, int32_t year, int32_t month,
    rng::RngManager& rng, bool allowRefresh) {
    MonthlyLevelResult out;
    // 1. 清理过期
    auto remaining = filterExpiredLevels(levels, year, month);
    // 2. 刷新判定
    const bool refresh = allowRefresh &&
                         shouldRefreshLevels(lastRefreshMonth, year, month);
    out.refreshed = refresh;
    // 3. 妖兽移动（无论是否刷新）
    out.levels = moveBeasts(remaining, year, month, rng);
    return out;
}

}  // namespace gamecore::system
