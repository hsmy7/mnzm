#include <gtest/gtest.h>

#include <cmath>
#include <functional>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/breakthrough.h"
#include "gamecore/system/disciple.h"

namespace gamecore::system {
namespace {

using gamecore::disciple::realmConfig;
using gamecore::rng::RngManager;
using gamecore::state::Disciple;

// ── applyBreakthroughSuccess ───────────────────────────────────

TEST(BreakthroughSuccessTest, LayerIncrementWithinRealm) {
    Disciple d;
    d.realm = 9;
    d.realmLayer = 1;
    d.cultivation = 98.0;
    d.lifespan = 80;
    const auto result = applyBreakthroughSuccess(d, 50);
    EXPECT_EQ(result.realm, 9);
    EXPECT_EQ(result.realmLayer, 2);
    EXPECT_DOUBLE_EQ(result.cultivation, 0.0);
    EXPECT_EQ(result.lifespan, 80);  // 同境界层数变化不加寿命
}

TEST(BreakthroughSuccessTest, RealmUpgradeAtMaxLayer) {
    Disciple d;
    d.realm = 9;
    d.realmLayer = 9;  // 满层
    d.cultivation = 98.0;
    d.lifespan = 80;
    const auto result = applyBreakthroughSuccess(d, 50);
    EXPECT_EQ(result.realm, 8);
    EXPECT_EQ(result.realmLayer, 1);
    EXPECT_EQ(result.lifespan, 130);  // 80 + 50（大境界变化加寿命）
}

TEST(BreakthroughSuccessTest, ImmortalStays) {
    Disciple d;
    d.realm = 0;
    d.realmLayer = 1;
    d.cultivation = 100.0;
    const auto result = applyBreakthroughSuccess(d, 0);
    // 仙人无更高境界：realm 0 时层数递增（maxLayers=9）
    EXPECT_EQ(result.realm, 0);
    EXPECT_EQ(result.realmLayer, 2);
}

// ── performBreakthrough 循环 ───────────────────────────────────

TEST(PerformBreakthroughTest, NotFullNoAttempt) {
    Disciple d;
    d.realm = 9;
    d.realmLayer = 1;
    d.cultivation = 50.0;  // 未满（上限 98）
    RngManager rng;
    rng.initSystemSeed(42);
    const auto out = performBreakthrough(
        d, [](const Disciple&) { return 1.0; },
        [](const Disciple&) { return 0; }, rng, 100);
    EXPECT_EQ(out.breakthroughCount, 0);
    EXPECT_EQ(out.failCount, 0);
    EXPECT_DOUBLE_EQ(out.disciple.cultivation, 50.0);
}

TEST(PerformBreakthroughTest, SuccessPath) {
    Disciple d;
    d.realm = 9;
    d.realmLayer = 1;
    d.cultivation = 98.0;  // 满
    RngManager rng;
    rng.initSystemSeed(42);
    const auto out = performBreakthrough(
        d, [](const Disciple&) { return 1.0; },   // 100% 成功
        [](const Disciple&) { return 50; }, rng, 100);
    EXPECT_EQ(out.breakthroughCount, 1);
    EXPECT_EQ(out.failCount, 0);
    EXPECT_EQ(out.disciple.realmLayer, 2);
    EXPECT_DOUBLE_EQ(out.disciple.cultivation, 0.0);
    // 检查点同步
    EXPECT_EQ(out.disciple.cultivationCheckpointGameMonth, 100);
}

TEST(PerformBreakthroughTest, ContinuousBreakthrough) {
    Disciple d;
    d.realm = 9;
    d.realmLayer = 9;  // 满层（上限 357.6）
    d.cultivation = 400.0;  // 已满
    RngManager rng;
    rng.initSystemSeed(42);
    const auto out = performBreakthrough(
        d, [](const Disciple&) { return 1.0; },   // 100% 成功
        [](const Disciple&) { return 50; }, rng, 100);
    // 满修为 → 突破到筑基 1 层（修为清零），不再满足修为满 → 停止
    EXPECT_EQ(out.breakthroughCount, 1);
    EXPECT_EQ(out.disciple.realm, 8);
    EXPECT_EQ(out.disciple.realmLayer, 1);
}

TEST(PerformBreakthroughTest, FailurePathStopsLoop) {
    Disciple d;
    d.realm = 9;
    d.realmLayer = 1;
    d.cultivation = 98.0;
    RngManager rng;
    rng.initSystemSeed(42);
    const auto out = performBreakthrough(
        d, [](const Disciple&) { return 0.0; },   // 0% 成功 → 必失败
        [](const Disciple&) { return 50; }, rng, 100);
    EXPECT_EQ(out.breakthroughCount, 0);
    EXPECT_EQ(out.failCount, 1);
    EXPECT_DOUBLE_EQ(out.disciple.cultivation, 0.0);
    // 失败后不再尝试（shouldContinue=false）
    EXPECT_EQ(out.disciple.realmLayer, 1);
}

TEST(PerformBreakthroughTest, IterationGuard) {
    // 构造异常数据：realm>0 且 maxCultivation==cultivation（realm 0 无上限）
    // 用 realm=1 满层 9 → 突破到 realm 0（无上限返回自身），此后 realm==0 停止
    Disciple d;
    d.realm = 1;
    d.realmLayer = 9;
    d.cultivation = 9999999.0;
    RngManager rng;
    rng.initSystemSeed(42);
    const auto out = performBreakthrough(
        d, [](const Disciple&) { return 1.0; },
        [](const Disciple&) { return 0; }, rng, 100, 4);
    // 突破到 realm 0 后循环停止（realm > 0 条件）
    EXPECT_EQ(out.disciple.realm, 0);
    EXPECT_LE(out.breakthroughCount, 4);
}

// ── tryBreakthrough RNG 判定 ───────────────────────────────────

TEST(TryBreakthroughTest, RngComparesToChance) {
    RngManager rng;
    rng.initSystemSeed(42);
    Disciple d;
    // chance=0 → 永远失败（nextDouble() in [0,1) >= 0）
    EXPECT_FALSE(tryBreakthrough(d, 0.0, rng));
    // chance=1.0 → 永远成功（nextDouble() < 1.0 恒真）
    EXPECT_TRUE(tryBreakthrough(d, 1.0, rng));
    // chance=0.5 → 有成功有失败（多试几次覆盖两分支）
    bool sawSuccess = false;
    bool sawFail = false;
    for (int i = 0; i < 50; ++i) {
        if (tryBreakthrough(d, 0.5, rng)) sawSuccess = true;
        else sawFail = true;
    }
    EXPECT_TRUE(sawSuccess);
    EXPECT_TRUE(sawFail);
}

// ── estimateMonthsToNextBreakthrough ───────────────────────────

TEST(EstimateMonthsTest, BasicEstimate) {
    // remaining=60, rate=10 → 60/(10*3)=2 个月
    EXPECT_EQ(estimateMonthsToNextBreakthrough(60.0, 10.0), 2);
    // remaining=61 → 61/30=2.03 → ceil=3
    EXPECT_EQ(estimateMonthsToNextBreakthrough(61.0, 10.0), 3);
    // rate=0 → 极大值
    EXPECT_EQ(estimateMonthsToNextBreakthrough(100.0, 0.0), INT32_MAX);
}

}  // namespace
}  // namespace gamecore::system
