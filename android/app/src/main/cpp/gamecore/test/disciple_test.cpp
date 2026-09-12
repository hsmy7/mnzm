#include <gtest/gtest.h>

#include <cmath>
#include <map>

#include "gamecore/system/disciple.h"

namespace gamecore::disciple {
namespace {

using gamecore::disciple::BaseStatsInput;
using gamecore::disciple::BreakthroughZones;
using gamecore::disciple::CultivationSpeedZones;
using gamecore::disciple::computeBaseStats;
using gamecore::disciple::realmConfig;
using gamecore::disciple::realmConfigs;

// ── Realm 配置表 ────────────────────────────────────────────────

TEST(RealmConfigTest, ConfigCountMatchesKotlin) {
    EXPECT_EQ(realmConfigs().size(), 10u);
}

TEST(RealmConfigTest, RepresentativeRealms) {
    const auto& lianqi = realmConfig(9);
    EXPECT_EQ(lianqi.name, "炼气");
    EXPECT_EQ(lianqi.baseHp, 203);
    EXPECT_EQ(lianqi.baseMp, 78);
    EXPECT_EQ(lianqi.baseSpeed, 15);

    const auto& xianren = realmConfig(0);
    EXPECT_EQ(xianren.name, "仙人");
    EXPECT_EQ(xianren.baseHp, 507000);
    EXPECT_EQ(xianren.baseSpeed, 37500);
}

TEST(RealmConfigTest, UnknownRealmFallsBackToLianqi) {
    EXPECT_EQ(realmConfig(99).realm, 9);
}

// ── 基础属性乘区法 ──────────────────────────────────────────────

TEST(BaseStatsTest, LianqiBaseStats) {
    BaseStatsInput in;
    in.realm = 9;
    in.realmLayer = 1;
    const auto s = computeBaseStats(in);
    EXPECT_EQ(s.maxHp, 203);
    EXPECT_EQ(s.maxMp, 78);
    EXPECT_EQ(s.physicalAttack, 16);
    EXPECT_EQ(s.magicAttack, 16);
    EXPECT_EQ(s.physicalDefense, 13);
    EXPECT_EQ(s.magicDefense, 10);
    EXPECT_EQ(s.speed, 15);
    EXPECT_DOUBLE_EQ(s.critRate, 0.05);
}

TEST(BaseStatsTest, LayerMultiplierScalesStats) {
    BaseStatsInput in;
    in.realm = 9;
    in.realmLayer = 3;  // 层数乘区 1.2
    const auto s = computeBaseStats(in);
    EXPECT_EQ(s.maxHp, static_cast<int32_t>(std::round(203 * 1.2)));
    EXPECT_EQ(s.speed, static_cast<int32_t>(std::round(15 * 1.2)));
}

TEST(BaseStatsTest, VarianceMultiplier) {
    BaseStatsInput in;
    in.realm = 9;
    in.realmLayer = 1;
    in.hpVariance = 100;   // 方差乘区 2.0
    in.speedVariance = -50;  // 方差乘区 0.5
    const auto s = computeBaseStats(in);
    EXPECT_EQ(s.maxHp, 406);
    EXPECT_EQ(s.speed, static_cast<int32_t>(std::round(15 * 0.5)));
}

TEST(BaseStatsTest, TalentEffectsAddPercent) {
    BaseStatsInput in;
    in.realm = 9;
    in.realmLayer = 1;
    in.talentEffects["maxHp"] = 0.50;   // +50%
    in.talentEffects["physicalAttack"] = 1.0;
    in.talentEffects["critRate"] = 0.10;
    const auto s = computeBaseStats(in);
    EXPECT_EQ(s.maxHp, static_cast<int32_t>(std::round(203 * 1.5)));
    EXPECT_EQ(s.physicalAttack, static_cast<int32_t>(std::round(16 * 2.0)));
    EXPECT_DOUBLE_EQ(s.critRate, 0.15);
}

TEST(BaseStatsTest, BloodRefinementPctClamped) {
    BaseStatsInput in;
    in.realm = 9;
    in.realmLayer = 1;
    in.bloodHpBonusPct = 0.30;  // +30%
    const auto s = computeBaseStats(in);
    EXPECT_EQ(s.maxHp, static_cast<int32_t>(std::round(203 * 1.3)));

    // 篡改巨大值 → 钳制 10.0
    BaseStatsInput in2 = in;
    in2.bloodHpBonusPct = 1000.0;
    const auto s2 = computeBaseStats(in2);
    EXPECT_EQ(s2.maxHp, static_cast<int32_t>(std::round(203 * 11.0)));
}

TEST(BaseStatsTest, SkillFlatAdds) {
    BaseStatsInput in;
    in.realm = 9;
    in.intelligence = 50;
    in.talentEffects["intelligenceFlat"] = 10;
    const auto s = computeBaseStats(in);
    EXPECT_EQ(s.intelligence, 60);
}

// ── 修炼速度乘区 ────────────────────────────────────────────────

TEST(CultivationSpeedTest, BasePerPhaseLianqiSingleRoot) {
    CultivationSpeedZones zones;
    const double v = calculateCultivationPerPhase(9, 1, zones);
    EXPECT_DOUBLE_EQ(v, 19.0);
}

TEST(CultivationSpeedTest, RootCountDividesBase) {
    CultivationSpeedZones zones;
    // 双灵根：19 / 2 = 9.5
    EXPECT_DOUBLE_EQ(calculateCultivationPerPhase(9, 2, zones), 9.5);
    // 零灵根防除零 → 按 1 处理
    EXPECT_DOUBLE_EQ(calculateCultivationPerPhase(9, 0, zones), 19.0);
}

TEST(CultivationSpeedTest, ZonesMultiply) {
    CultivationSpeedZones zones;
    zones.aptitudeBonus = 0.2;
    zones.resourceBonus = 0.5;
    zones.socialBonus = 0.1;
    zones.statusBonus = -0.1;
    zones.temporaryBonus = 0.3;
    // 19 * 1.2 * 1.5 * 1.1 * 0.9 * 1.3
    EXPECT_DOUBLE_EQ(calculateCultivationPerPhase(9, 1, zones),
                     19.0 * 1.2 * 1.5 * 1.1 * 0.9 * 1.3);
}

TEST(CultivationSpeedTest, MinClampTo1) {
    CultivationSpeedZones zones;
    zones.resourceBonus = -0.99;  // 压到 0.01 倍
    EXPECT_DOUBLE_EQ(calculateCultivationPerPhase(9, 1, zones), 1.0);
}

TEST(AptitudeBonusTest, BaselineAndCap) {
    EXPECT_DOUBLE_EQ(aptitudeCultivationBonus(80), 0.0);
    EXPECT_DOUBLE_EQ(aptitudeCultivationBonus(90), 0.10);
    EXPECT_DOUBLE_EQ(aptitudeCultivationBonus(120), 0.40);  // 上限
    EXPECT_DOUBLE_EQ(aptitudeCultivationBonus(10000), 0.40);  // 篡改钳制
    EXPECT_DOUBLE_EQ(aptitudeCultivationBonus(50), 0.0);  // 低于基准归零
}

// ── 突破概率 ────────────────────────────────────────────────────

TEST(BreakthroughTest, BaseChanceFromTable) {
    EXPECT_DOUBLE_EQ(getBreakthroughChance(9, 1, 1), 0.90);
    EXPECT_DOUBLE_EQ(getBreakthroughChance(9, 5, 1), 0.30);
    EXPECT_DOUBLE_EQ(getBreakthroughChance(5, 3, 1), 0.04);
}

TEST(BreakthroughTest, LayerInterpolation) {
    // 炼气 9 层：realm 9 (0.90) → realm 8 (0.80)，realmLayer 9 → nextRealmProb
    EXPECT_DOUBLE_EQ(getBreakthroughChance(9, 1, 9), 0.80);
    // 中间层线性插值：layer 5 → progress = 4/8 = 0.5 → 0.85
    EXPECT_DOUBLE_EQ(getBreakthroughChance(9, 1, 5), 0.85);
}

TEST(BreakthroughTest, InvalidLayerReturnsZero) {
    EXPECT_DOUBLE_EQ(getBreakthroughChance(9, 1, 0), 0.0);
    EXPECT_DOUBLE_EQ(getBreakthroughChance(9, 1, -1), 0.0);
}

TEST(BreakthroughTest, ChanceWithZones) {
    BreakthroughZones zones;
    zones.baseZone = 0.50;
    zones.elderGuidance = 0.10;
    zones.selfBonus = 0.05;
    zones.statusPenalty = 0.10;
    // 0.50 * 1.15 * 0.90 = 0.5175
    EXPECT_DOUBLE_EQ(calculateBreakthroughChance(zones), 0.5175);
}

TEST(BreakthroughTest, ChanceAdFlatAddsAfterClamp) {
    BreakthroughZones zones;
    zones.baseZone = 0.90;
    zones.adFlatBonus = 0.20;
    EXPECT_DOUBLE_EQ(calculateBreakthroughChance(zones), 1.0);  // clamp 上限
}

TEST(BreakthroughTest, SoulPowerBonus) {
    EXPECT_DOUBLE_EQ(soulPowerBreakthroughBonus(0), 0.0);
    EXPECT_DOUBLE_EQ(soulPowerBreakthroughBonus(20), 0.01);
    EXPECT_DOUBLE_EQ(soulPowerBreakthroughBonus(100), 0.05);  // 上限
    EXPECT_DOUBLE_EQ(soulPowerBreakthroughBonus(500), 0.05);
}

// ── 寿命/师徒/父母/丧亲 ─────────────────────────────────────────

TEST(LifespanTest, RemainingPercent) {
    EXPECT_DOUBLE_EQ(calculateLifespanRemainingPercent(80, 80), 0.0);
    EXPECT_DOUBLE_EQ(calculateLifespanRemainingPercent(40, 80), 0.5);
    EXPECT_DOUBLE_EQ(calculateLifespanRemainingPercent(100, 80), 0.0);  // 已超寿
    EXPECT_DOUBLE_EQ(calculateLifespanRemainingPercent(50, 0), 1.0);    // 防除零
}

TEST(LifespanTest, CultivationPenalty) {
    // 剩余 50% ≥ 20% → 无惩罚
    EXPECT_DOUBLE_EQ(calculateLifespanCultivationPenalty(40, 80), 0.0);
    // 剩余 10% → deficit = 10 → 惩罚 0.50
    EXPECT_DOUBLE_EQ(calculateLifespanCultivationPenalty(72, 80), 0.50);
}

TEST(LifespanTest, BreakthroughPenalty) {
    EXPECT_DOUBLE_EQ(calculateLifespanBreakthroughPenalty(40, 80), 0.0);
    // 剩余 10% → deficit = 10 → 惩罚 0.20
    EXPECT_DOUBLE_EQ(calculateLifespanBreakthroughPenalty(72, 80), 0.20);
}

TEST(MasterDiscipleTest, RealmGap) {
    EXPECT_EQ(getMasterDiscipleRealmGap(9, 7), 1);   // 炼气徒 + 金丹师
    EXPECT_EQ(getMasterDiscipleRealmGap(9, 9), 0);   // 同境界
    EXPECT_EQ(getMasterDiscipleRealmGap(9, 0), 8);   // 仙人师
    EXPECT_EQ(getMasterDiscipleRealmGap(7, 9), 0);   // 徒比师高 → 0
}

TEST(MasterDiscipleTest, CultivationBonus) {
    EXPECT_DOUBLE_EQ(getMasterDiscipleCultivationBonus(9, 7), 0.05);
    EXPECT_DOUBLE_EQ(getMasterDiscipleCultivationBonus(9, 0), 0.40);
}

TEST(ParentBonusTest, SpiritRootCount) {
    EXPECT_DOUBLE_EQ(getParentSpiritRootBonus(1), 0.10);
    EXPECT_DOUBLE_EQ(getParentSpiritRootBonus(3), 0.0);
    EXPECT_DOUBLE_EQ(getParentSpiritRootBonus(5), -0.10);
}

TEST(GriefTest, IsGrieving) {
    EXPECT_TRUE(isGrieving(5, true, 4));
    EXPECT_FALSE(isGrieving(5, true, 5));
    EXPECT_FALSE(isGrieving(5, true, 6));
    EXPECT_FALSE(isGrieving(5, false, 4));
}

TEST(LifespanGainTest, RealmGain) {
    EXPECT_EQ(lifespanGainForRealm(8), 40);
    EXPECT_EQ(lifespanGainForRealm(0), 10000);
    EXPECT_EQ(lifespanGainForRealm(99), 0);
}

}  // namespace
}  // namespace gamecore::disciple
