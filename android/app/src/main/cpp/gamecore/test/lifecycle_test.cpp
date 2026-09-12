#include <gtest/gtest.h>

#include <cmath>

#include "gamecore/system/lifecycle.h"

namespace gamecore::system {
namespace {

// ── computeMaxAge ──────────────────────────────────────────────

TEST(MaxAgeTest, LifespanDominates) {
    // lifespan 100 > realmMaxAge 80 → 100
    EXPECT_EQ(computeMaxAge(100, 80, 0.0), 100);
}

TEST(MaxAgeTest, RealmMaxAgeDominates) {
    // realmMaxAge 500 > lifespan 100 → 500
    EXPECT_EQ(computeMaxAge(100, 500, 0.0), 500);
}

TEST(MaxAgeTest, TraitBonusBoosts) {
    // realmMaxAge 80 × (1+0.5) = 120 > lifespan 100
    EXPECT_EQ(computeMaxAge(100, 80, 0.5), 120);
    // realmMaxAge 80 × (1+0.1) = 88 < lifespan 100 → lifespan 保持
    EXPECT_EQ(computeMaxAge(100, 80, 0.1), 100);
}

TEST(MaxAgeTest, TraitBonusTruncation) {
    // 80 × 1.15 = 92.0 → 92（整数截断）
    EXPECT_EQ(computeMaxAge(0, 80, 0.15), 92);
}

TEST(MaxAgeTest, AbsoluteCeiling) {
    // 巨大 lifespan → 钳制 20000
    EXPECT_EQ(computeMaxAge(999999, 80, 0.0), 20000);
    // 巨大加成 → 钳制 20000
    EXPECT_EQ(computeMaxAge(100, 80, 1000.0), 20000);
}

TEST(MaxAgeTest, TraitLifespanMinOne) {
    // realmMaxAge × (1 + (-1.0)) = 0 → coerceAtLeast(1)
    EXPECT_EQ(computeMaxAge(0, 80, -1.0), 80);  // max(0, 80, 1) = 80
}

// ── ageDisciple ────────────────────────────────────────────────

TEST(AgeDiscipleTest, AgeIncrements) {
    const auto out = ageDisciple(30, 1, 80);
    EXPECT_EQ(out.age, 31);
    EXPECT_FALSE(out.dead);
    EXPECT_EQ(out.realmLayer, 1);
}

TEST(AgeDiscipleTest, AgeFiveLayerReset) {
    // 4 岁 + 1 = 5 岁，realmLayer==0 → 回正 1
    const auto out = ageDisciple(4, 0, 80);
    EXPECT_EQ(out.age, 5);
    EXPECT_EQ(out.realmLayer, 1);
    EXPECT_FALSE(out.dead);

    // 5 岁但 layer != 0 → 不变
    const auto out2 = ageDisciple(4, 2, 80);
    EXPECT_EQ(out2.realmLayer, 2);
}

TEST(AgeDiscipleTest, LifespanExhaustedDead) {
    const auto out = ageDisciple(79, 1, 80);  // 80 >= 80 → 死
    EXPECT_TRUE(out.dead);
    EXPECT_EQ(out.age, 80);

    const auto out2 = ageDisciple(78, 1, 80);  // 79 < 80 → 活
    EXPECT_FALSE(out2.dead);
    EXPECT_EQ(out2.age, 79);
}

// ── ageAliveDisciple ───────────────────────────────────────────

TEST(AgeAliveDiscipleTest, AliveAgeIncrement) {
    int32_t layer = 1;
    const int32_t age = ageAliveDisciple(30, layer, layer);
    EXPECT_EQ(age, 31);
    EXPECT_EQ(layer, 1);
}

TEST(AgeAliveDiscipleTest, AliveLayerResetAtFive) {
    int32_t layer = 0;
    const int32_t age = ageAliveDisciple(4, layer, layer);
    EXPECT_EQ(age, 5);
    EXPECT_EQ(layer, 1);
}

}  // namespace
}  // namespace gamecore::system
