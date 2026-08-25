#include <gtest/gtest.h>

#include <cmath>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/system/battle.h"

namespace gamecore::battle {
namespace {

using gamecore::rng::RngManager;

// ── calculateFinalDamage 乘区法 ────────────────────────────────

TEST(FinalDamageTest, BasicNoZones) {
    DamageZones zones;
    // 攻击 100，防御 0 → reduction=0 → 100×1×1 = 100
    EXPECT_EQ(calculateFinalDamage(100, 0, 1.0, zones, false, 1.0), 100);
}

TEST(FinalDamageTest, DefenseReduction) {
    DamageZones zones;
    // 攻击 100，防御 500 → reduction=0.5 → 50
    EXPECT_EQ(calculateFinalDamage(100, 500, 1.0, zones, false, 1.0), 50);
    // 防御 1500 → reduction=0.75 → 25
    EXPECT_EQ(calculateFinalDamage(100, 1500, 1.0, zones, false, 1.0), 25);
}

TEST(FinalDamageTest, CritMultiplier) {
    DamageZones zones;
    // 暴击 → ×1.5
    EXPECT_EQ(calculateFinalDamage(100, 0, 1.0, zones, true, 1.0), 150);
}

TEST(FinalDamageTest, AmplificationAndReduction) {
    DamageZones zones;
    zones.damageAmplification = 0.5;
    zones.damageReduction = 0.2;
    // 100 × 1.5 × 0.8 = 120
    EXPECT_EQ(calculateFinalDamage(100, 0, 1.0, zones, false, 1.0), 120);
}

TEST(FinalDamageTest, IndependentMultipliers) {
    DamageZones zones;
    zones.physiqueDamageAmplification = 0.1;
    zones.affixDamageAmplification = 0.2;
    zones.realmGapDamageAmplification = 0.3;
    zones.majorRealmDamageAmplification = 0.4;
    // 100 × 1.1 × 1.2 × 1.3 × 1.4 = 240.24 → 240
    EXPECT_EQ(calculateFinalDamage(100, 0, 1.0, zones, false, 1.0), 240);
}

TEST(FinalDamageTest, VarianceScales) {
    DamageZones zones;
    // 100 × 1.1 = 110
    EXPECT_EQ(calculateFinalDamage(100, 0, 1.0, zones, false, 1.1), 110);
}

TEST(FinalDamageTest, MinDamageClamp) {
    DamageZones zones;
    zones.damageReduction = 0.99;
    zones.physiqueDamageReduction = 0.99;
    // 极低伤害 → clamp 到 1
    EXPECT_EQ(calculateFinalDamage(100, 100000, 1.0, zones, false, 1.0), 1);
}

TEST(FinalDamageTest, DefenseBonusIndependent) {
    DamageZones zones;
    zones.physiqueDefenseBonus = 0.5;  // 防御减半
    zones.affixDefenseBonus = 0.5;     // 再减半
    // 防御 500 → ×0.5×0.5 = 125 → reduction = 125/625 = 0.2 → 100×0.8 = 80
    EXPECT_EQ(calculateFinalDamage(100, 500, 1.0, zones, false, 1.0), 80);
}

// ── calculateDamageVariance ────────────────────────────────────

TEST(DamageVarianceTest, WithinRange) {
    RngManager rng;
    rng.initSystemSeed(42);
    for (int i = 0; i < 50; ++i) {
        const double v = calculateDamageVariance(rng);
        EXPECT_GE(v, 1.0 - 0.20);
        EXPECT_LE(v, 1.0 + 0.20);
    }
}

TEST(DamageVarianceTest, OneDecimalPrecision) {
    RngManager rng;
    rng.initSystemSeed(42);
    for (int i = 0; i < 50; ++i) {
        const double v = calculateDamageVariance(rng);
        // (v-1)*100 应为 0.1 的整数倍
        const double pct = (v - 1.0) * 100.0;
        EXPECT_NEAR(pct, std::round(pct * 10.0) / 10.0, 1e-9);
    }
}

// ── calculateDodgeChance ───────────────────────────────────────

TEST(DodgeChanceTest, Basic) {
    // 速度差 100/总 300 × 0.5 = 0.1667
    EXPECT_DOUBLE_EQ(calculateDodgeChance(200, 100, 0.5), 100.0 / 300.0 * 0.5);
    // 攻击方更快 → 正闪避
    EXPECT_GT(calculateDodgeChance(300, 100, 0.5), 0.0);
    // 防御方更快 → 0
    EXPECT_DOUBLE_EQ(calculateDodgeChance(100, 300, 0.5), 0.0);
    // 钳制上限 0.5：defenderSpeed=0 → ratio=1 → ×0.5 = 0.5（恰好等于上限不触发钳制，
    // 但语义上速度差极大时比率≈1，结果≈0.5；此处验证上限行为）
    EXPECT_DOUBLE_EQ(calculateDodgeChance(1000, 0, 0.5), 0.5);
}

// ── calculateRealmGapFactors ───────────────────────────────────

TEST(RealmGapTest, AttackerHigherLayer) {
    // 同境界（9），攻击方层 5，防守方层 1 → layerGap = 4 → +120%
    const auto f = calculateRealmGapFactors(9, 5, 9, 1);
    EXPECT_DOUBLE_EQ(f.damageAmplification, 0.30 * 4);
    EXPECT_DOUBLE_EQ(f.damageReduction, 0.0);
    EXPECT_DOUBLE_EQ(f.majorRealmDamageAmplification, 0.0);
}

TEST(RealmGapTest, DefenderHigherLayer) {
    // 攻击方层 1，防守方层 5 → layerGap = -4 → 减伤 120% 封顶 100%
    const auto f = calculateRealmGapFactors(9, 1, 9, 5);
    EXPECT_DOUBLE_EQ(f.damageAmplification, 0.0);
    EXPECT_DOUBLE_EQ(f.damageReduction, 1.0);
}

TEST(RealmGapTest, MajorRealmGap) {
    // 攻击方炼气(9) vs 防守方筑基(8)：majorGap = -1（攻击方低）→ 无大境界加成
    const auto f = calculateRealmGapFactors(9, 1, 8, 1);
    EXPECT_DOUBLE_EQ(f.majorRealmDamageAmplification, 0.0);
    // 攻击方筑基(8) vs 防守方炼气(9)：majorGap = +1 → +100%
    const auto f2 = calculateRealmGapFactors(8, 1, 9, 1);
    EXPECT_DOUBLE_EQ(f2.majorRealmDamageAmplification, 1.0);
    EXPECT_DOUBLE_EQ(f2.damageAmplification, 0.30 * 9);  // 层差 9
}

TEST(RealmGapTest, TamperClamped) {
    // 篡改 realm 越界：攻击方 realm=99 → safeRealm=9
    const auto f = calculateRealmGapFactors(99, 1, 9, 1);
    EXPECT_DOUBLE_EQ(f.majorRealmDamageAmplification, 0.0);
    // 篡改 realmLayer 越界：safeLayer 钳制
    const auto f2 = calculateRealmGapFactors(9, INT32_MAX, 9, 1);
    EXPECT_GT(f2.damageAmplification, 0.0);
    EXPECT_LE(f2.damageAmplification, 0.30 * 8.0);  // 9 层钳制 → 最多 8 层差
}

// ── checkInstantKill ───────────────────────────────────────────

TEST(InstantKillTest, TwoMajorRealmsKills) {
    // 攻击方仙人(0) vs 防守方炼气(9)：gap = 9×9 = 81 > 9 → 斩杀
    EXPECT_TRUE(checkInstantKill(0, 9, 1, 1));
}

TEST(InstantKillTest, OneMajorRealmNoKill) {
    // 攻击方筑基(8) vs 防守方炼气(9)：gap = 1×9 = 9，INSTANT_KILL_GAP=1 → 9 > 9 false
    EXPECT_FALSE(checkInstantKill(8, 9, 1, 1));
    // 同境界不同层不斩杀
    EXPECT_FALSE(checkInstantKill(9, 9, 5, 1));
}

// ── calculateShieldAbsorption ──────────────────────────────────

TEST(ShieldTest, NoShieldPassesDamage) {
    const auto r = calculateShieldAbsorption(1000, 0.0, false, 300);
    EXPECT_EQ(r.absorbed, 0);
    EXPECT_EQ(r.remainingDamage, 300);
}

TEST(ShieldTest, ShieldAbsorbsPartially) {
    // 护盾 = 1000×0.3 = 300
    const auto r = calculateShieldAbsorption(1000, 0.3, true, 500);
    EXPECT_EQ(r.absorbed, 300);
    EXPECT_EQ(r.remainingDamage, 200);
    EXPECT_EQ(r.remainingShield, 0);
}

TEST(ShieldTest, ShieldSurvives) {
    // 护盾 = 1000×0.3 = 300，伤害 100 → 剩余护盾 200
    const auto r = calculateShieldAbsorption(1000, 0.3, true, 100);
    EXPECT_EQ(r.absorbed, 100);
    EXPECT_EQ(r.remainingDamage, 0);
    EXPECT_EQ(r.remainingShield, 200);
}

TEST(ShieldTest, TamperedValueClamped) {
    // 篡改 value=5.0 → 钳制 1.0 → 护盾 = maxHp
    const auto r = calculateShieldAbsorption(1000, 5.0, true, 100);
    EXPECT_EQ(r.absorbed, 100);
    EXPECT_EQ(r.remainingShield, 900);
}

// ── applyDotDamage ─────────────────────────────────────────────

TEST(DotTest, Basic) {
    EXPECT_EQ(applyDotDamage(500, 100), 400);
    EXPECT_EQ(applyDotDamage(50, 100), 0);
    EXPECT_EQ(applyDotDamage(500, 0), 500);  // 无 DoT
    EXPECT_EQ(applyDotDamage(500, -10), 500);
}

// ── updateCooldowns ────────────────────────────────────────────

TEST(CooldownTest, UsedSkillSetOthersDecrement) {
    std::vector<std::string> names = {"a", "b", "c"};
    std::vector<int32_t> cooldowns = {2, 3, 1};
    const auto out = updateCooldowns(names, cooldowns, "b", 4);
    EXPECT_EQ(out[0], 1);  // a: 2-1
    EXPECT_EQ(out[1], 4);  // b: 置 4
    EXPECT_EQ(out[2], 0);  // c: 1-1
}

TEST(CooldownTest, DecrementFloorZero) {
    std::vector<std::string> names = {"a"};
    std::vector<int32_t> cooldowns = {0};
    const auto out = updateCooldowns(names, cooldowns, "x", 3);
    EXPECT_EQ(out[0], 0);
}

}  // namespace
}  // namespace gamecore::battle
