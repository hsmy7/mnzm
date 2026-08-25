#include <gtest/gtest.h>

#include <cmath>

#include "gamecore/state/models.h"
#include "gamecore/system/cultivation.h"
#include "gamecore/system/disciple.h"

namespace gamecore::system {
namespace {

using gamecore::disciple::realmConfig;
using gamecore::state::Disciple;

// ── computeMaxCultivation ───────────────────────────────────────

TEST(MaxCultivationTest, LianqiLayer1) {
    // 炼气 base=98，筑基 base=390，maxLayers=9 → layer1 = 98
    EXPECT_DOUBLE_EQ(computeMaxCultivation(9, 1, 0.0), 98.0);
}

TEST(MaxCultivationTest, LianqiLayer9) {
    // layer9 = 98 + 8 × (390-98)/9 = 98 + 8×292/9
    const double expected = 98.0 + 8.0 * (390.0 - 98.0) / 9.0;
    EXPECT_DOUBLE_EQ(computeMaxCultivation(9, 9, 0.0), expected);
}

TEST(MaxCultivationTest, ImmortalReturnsCurrent) {
    // realm 0（仙人）→ 返回当前修为不再增长
    EXPECT_DOUBLE_EQ(computeMaxCultivation(0, 1, 12345.0), 12345.0);
}

TEST(MaxCultivationTest, MiddleLayerLinearInterpolation) {
    // layer5 = 98 + 4×292/9
    const double expected = 98.0 + 4.0 * (390.0 - 98.0) / 9.0;
    EXPECT_DOUBLE_EQ(computeMaxCultivation(9, 5, 0.0), expected);
}

// ── accumulateCultivationPerPhase ───────────────────────────────

TEST(AccumulateTest, AddsRateUpToCap) {
    Disciple d;
    d.realm = 9;
    d.realmLayer = 1;
    d.cultivation = 90.0;
    const double updated = accumulateCultivationPerPhase(d, 19.0);
    // 90 + 19 = 109 > 炼气 1 层上限 98 → 钳制到 98
    EXPECT_DOUBLE_EQ(updated, 98.0);
    EXPECT_DOUBLE_EQ(d.cultivation, 98.0);
}

TEST(AccumulateTest, DeadDiscipleNoGain) {
    Disciple d;
    d.realm = 9;
    d.isAlive = false;
    d.cultivation = 50.0;
    const double updated = accumulateCultivationPerPhase(d, 19.0);
    EXPECT_DOUBLE_EQ(updated, 50.0);
    EXPECT_DOUBLE_EQ(d.cultivation, 50.0);
}

TEST(AccumulateTest, ZeroRateNoGain) {
    Disciple d;
    d.realm = 9;
    d.realmLayer = 1;
    d.cultivation = 50.0;
    const double updated = accumulateCultivationPerPhase(d, 0.0);
    EXPECT_DOUBLE_EQ(updated, 50.0);
}

TEST(AccumulateTest, AtCapNoGrowth) {
    Disciple d;
    d.realm = 9;
    d.realmLayer = 1;
    d.cultivation = 98.0;  // 已满
    const double updated = accumulateCultivationPerPhase(d, 19.0);
    EXPECT_DOUBLE_EQ(updated, 98.0);
}

// ── checkpointDisciple / getEffectiveCultivation ────────────────

TEST(CheckpointTest, CheckpointSyncsValues) {
    Disciple d;
    d.realm = 9;
    d.cultivation = 500.0;
    checkpointDisciple(d, 100);
    EXPECT_DOUBLE_EQ(d.cultivationCheckpoint, 500.0);
    EXPECT_EQ(d.cultivationCheckpointGameMonth, 100);
}

TEST(CheckpointTest, DeadDiscipleCheckpointIgnored) {
    Disciple d;
    d.realm = 9;
    d.isAlive = false;
    d.cultivation = 500.0;
    checkpointDisciple(d, 100);
    EXPECT_EQ(d.cultivationCheckpointGameMonth, 0);
}

TEST(EffectiveCultivationTest, ProjectionFromCheckpoint) {
    Disciple d;
    d.realm = 9;
    d.cultivation = 500.0;
    checkpointDisciple(d, 100);   // checkpoint=500 @ month 100
    // 100 个月后，rate=10 → 500 + 10×0×3 = 500（Δ=0）
    EXPECT_DOUBLE_EQ(getEffectiveCultivation(d, 100, 10.0), 500.0);
    // Δ=1 个月 → 500 + 10×1×3 = 530
    EXPECT_DOUBLE_EQ(getEffectiveCultivation(d, 101, 10.0), 530.0);
    // Δ=2 个月 → 500 + 10×2×3 = 560
    EXPECT_DOUBLE_EQ(getEffectiveCultivation(d, 102, 10.0), 560.0);
}

TEST(EffectiveCultivationTest, NoCheckpointFallsBack) {
    Disciple d;
    d.realm = 9;
    d.cultivation = 800.0;  // 无检查点（checkpointGameMonth=0）
    EXPECT_DOUBLE_EQ(getEffectiveCultivation(d, 150, 10.0), 800.0);
}

TEST(EffectiveCultivationTest, ZeroRateReturnsCheckpoint) {
    Disciple d;
    d.realm = 9;
    d.cultivation = 500.0;
    checkpointDisciple(d, 100);
    EXPECT_DOUBLE_EQ(getEffectiveCultivation(d, 150, 0.0), 500.0);
}

TEST(EffectiveCultivationTest, PastMonthClampedToZero) {
    Disciple d;
    d.realm = 9;
    d.cultivation = 500.0;
    checkpointDisciple(d, 100);
    // 当前月份早于检查点月份 → Δ=0 → 返回 checkpoint
    EXPECT_DOUBLE_EQ(getEffectiveCultivation(d, 80, 10.0), 500.0);
}

// ── checkpointAllDisciples ──────────────────────────────────────

TEST(CheckpointAllTest, SyncsAllAlive) {
    std::vector<Disciple> disciples(3);
    disciples[0].realm = 9;
    disciples[0].cultivation = 100.0;
    disciples[1].realm = 8;
    disciples[1].cultivation = 200.0;
    disciples[2].realm = 7;
    disciples[2].cultivation = 300.0;
    disciples[2].isAlive = false;  // 死亡不检查点
    checkpointAllDisciples(disciples, 50);
    EXPECT_DOUBLE_EQ(disciples[0].cultivationCheckpoint, 100.0);
    EXPECT_EQ(disciples[0].cultivationCheckpointGameMonth, 50);
    EXPECT_DOUBLE_EQ(disciples[1].cultivationCheckpoint, 200.0);
    EXPECT_EQ(disciples[2].cultivationCheckpointGameMonth, 0);
}

// ── toAbsoluteMonth ─────────────────────────────────────────────

TEST(AbsoluteMonthTest, YearMonthEncoding) {
    EXPECT_EQ(toAbsoluteMonth(1, 1), 13);
    EXPECT_EQ(toAbsoluteMonth(1, 12), 24);
    EXPECT_EQ(toAbsoluteMonth(2, 1), 25);
}

}  // namespace
}  // namespace gamecore::system
