#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/exploration.h"

namespace gamecore::system {
namespace {

using gamecore::rng::RngManager;
using gamecore::state::WorldLevel;

/// 构造妖兽关卡
WorldLevel makeBeast(const std::string& id, float x, float y,
                     int32_t expiryYear, int32_t expiryMonth,
                     bool defeated = false) {
    WorldLevel l;
    l.id = id;
    l.type = "BEAST";
    l.x = x;
    l.y = y;
    l.expiryYear = expiryYear;
    l.expiryMonth = expiryMonth;
    l.defeated = defeated;
    return l;
}

// ── checkExpired ──────────────────────────────────────────────

TEST(CheckExpiredTest, DefeatedExpired) {
    const auto l = makeBeast("b1", 100, 100, 99, 1, /*defeated=*/true);
    EXPECT_TRUE(checkLevelExpired(l, 1, 1));
}

TEST(CheckExpiredTest, YearBoundary) {
    const auto l = makeBeast("b1", 100, 100, 3, 6, false);
    EXPECT_FALSE(checkLevelExpired(l, 2, 12));
    EXPECT_FALSE(checkLevelExpired(l, 3, 5));
    EXPECT_TRUE(checkLevelExpired(l, 3, 6));    // 含等号
    EXPECT_TRUE(checkLevelExpired(l, 3, 7));
    EXPECT_TRUE(checkLevelExpired(l, 4, 1));
}

// ── filterExpiredLevels ───────────────────────────────────────

TEST(FilterExpiredTest, KeepsActiveRemovesExpired) {
    std::vector<WorldLevel> levels = {
        makeBeast("b1", 100, 100, 3, 6, false),  // 活跃
        makeBeast("b2", 200, 200, 3, 1, false),  // 已过期
        makeBeast("b3", 300, 300, 99, 1, true),  // 已击败
    };
    const auto out = filterExpiredLevels(levels, 3, 3);
    ASSERT_EQ(out.size(), 1u);
    EXPECT_EQ(out[0].id, "b1");
}

// ── shouldRefreshLevels ───────────────────────────────────────

TEST(RefreshTest, EveryThreeMonths) {
    // 首次（lastRefresh==0）→ 刷新
    EXPECT_TRUE(shouldRefreshLevels(0, 1, 1));
    // 差 2 个月 → 否
    EXPECT_FALSE(shouldRefreshLevels(toAbsoluteMonth(1, 1), 1, 3));
    // 差 3 个月 → 是（含等号）
    EXPECT_TRUE(shouldRefreshLevels(toAbsoluteMonth(1, 1), 1, 4));
    // 差 12 个月 → 是
    EXPECT_TRUE(shouldRefreshLevels(toAbsoluteMonth(1, 1), 2, 1));
}

// ── moveBeasts ────────────────────────────────────────────────

TEST(MoveBeastsTest, OnlyActiveBeastsMove) {
    RngManager rng;
    rng.initSystemSeed(42);
    std::vector<WorldLevel> levels = {
        makeBeast("b1", 500, 400, 3, 6, false),  // 活跃 → 移动
        makeBeast("b2", 500, 400, 3, 1, false),  // 已过期 → 不动
        makeBeast("b3", 500, 400, 99, 1, true),  // 已击败 → 不动
    };
    WorldLevel cave;
    cave.id = "c1";
    cave.type = "CAVE";
    cave.x = 500;
    cave.y = 400;
    cave.expiryYear = 3;
    cave.expiryMonth = 6;
    levels.push_back(cave);  // 洞府 → 不动

    const auto out = moveBeasts(levels, 3, 3, rng);
    ASSERT_EQ(out.size(), 4u);
    // 活跃妖兽位置变化
    EXPECT_NE(out[0].x, 500.0f);
    EXPECT_NE(out[0].y, 400.0f);
    // 已过期/已击败/洞府原地
    EXPECT_EQ(out[1].x, 500.0f);
    EXPECT_EQ(out[2].x, 500.0f);
    EXPECT_EQ(out[3].x, 500.0f);
}

TEST(MoveBeastsTest, PositionClampedToBorder) {
    RngManager rng;
    rng.initSystemSeed(7);
    // 贴边位置：移动后应钳制在 [34, 1664]×[34, 892]
    std::vector<WorldLevel> levels = {
        makeBeast("b1", 34.0f, 34.0f, 3, 6, false),
        makeBeast("b2", 1664.0f, 892.0f, 3, 6, false),
    };
    const auto out = moveBeasts(levels, 3, 3, rng);
    for (const auto& l : out) {
        EXPECT_GE(l.x, 34.0f);
        EXPECT_LE(l.x, 1664.0f);
        EXPECT_GE(l.y, 34.0f);
        EXPECT_LE(l.y, 892.0f);
    }
}

TEST(MoveBeastsTest, DeterministicWithSameSeed) {
    auto run = [](int64_t seed) {
        RngManager rng;
        rng.initSystemSeed(seed);
        std::vector<WorldLevel> levels = {
            makeBeast("b1", 500, 400, 3, 6, false),
        };
        return moveBeasts(levels, 3, 3, rng);
    };
    const auto a1 = run(42);
    const auto a2 = run(42);
    EXPECT_EQ(a1[0].x, a2[0].x);
    EXPECT_EQ(a1[0].y, a2[0].y);
    const auto b = run(43);
    // 不同种子 → 大概率不同位置
    EXPECT_TRUE(a1[0].x != b[0].x || a1[0].y != b[0].y);
}

// ── processWorldLevelsMonthly ─────────────────────────────────

TEST(MonthlyTest, CleansAndMoves) {
    RngManager rng;
    rng.initSystemSeed(42);
    std::vector<WorldLevel> levels = {
        makeBeast("b1", 500, 400, 3, 6, false),   // 活跃
        makeBeast("b2", 600, 500, 3, 1, false),   // 已过期 → 清理
    };
    const auto out = processWorldLevelsMonthly(levels, 0, 3, 3, rng, true);
    EXPECT_TRUE(out.refreshed);   // 首次刷新
    ASSERT_EQ(out.levels.size(), 1u);
    EXPECT_EQ(out.levels[0].id, "b1");
    EXPECT_NE(out.levels[0].x, 500.0f);  // 已移动
}

TEST(MonthlyTest, NoRefreshKeepsLevels) {
    RngManager rng;
    rng.initSystemSeed(42);
    std::vector<WorldLevel> levels = {
        makeBeast("b1", 500, 400, 3, 6, false),
    };
    const auto out = processWorldLevelsMonthly(
        levels, toAbsoluteMonth(1, 1), 1, 2, rng, true);
    EXPECT_FALSE(out.refreshed);  // 差 1 个月
    ASSERT_EQ(out.levels.size(), 1u);
}

}  // namespace
}  // namespace gamecore::system
