#include <gtest/gtest.h>

#include <cmath>
#include <map>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/government.h"

namespace gamecore::system {
namespace {

using state::GameData;
using state::SectPolicies;

// ── ZoneCalculator 等价 ────────────────────────────────────────

TEST(ZoneCalculatorTest, CalculateMultipliesZones) {
    EXPECT_DOUBLE_EQ(zoneCalculate(100.0, {0.2, 0.5, -0.1}),
                     100.0 * 1.2 * 1.5 * 0.9);
    EXPECT_DOUBLE_EQ(zoneCalculate(50.0, {}), 50.0);
}

TEST(ZoneCalculatorTest, MultiplierConversion) {
    EXPECT_DOUBLE_EQ(multiplierToZone(1.4), 0.4);
    EXPECT_DOUBLE_EQ(zoneToMultiplier(0.4), 1.4);
}

TEST(ZoneCalculatorTest, ProbabilityClamped) {
    EXPECT_DOUBLE_EQ(calculateProbability(0.5, 0.1, 0.2), 0.5 * 1.1 * 0.8);
    EXPECT_DOUBLE_EQ(calculateProbability(0.9, 0.5, 0.0), 1.0);  // clamp 上限
    EXPECT_DOUBLE_EQ(calculateProbability(0.1, 0.0, 2.0), 0.0);  // clamp 下限
}

TEST(ZoneCalculatorTest, ReducedDuration) {
    EXPECT_EQ(calculateReducedDuration(100, {0.2, 0.5}), 40);
    EXPECT_EQ(calculateReducedDuration(10, {0.99}), 1);  // 至少 1
    EXPECT_EQ(calculateReducedDuration(10, {-0.5}), 10); // 负缩减钳制 0
}

TEST(ZoneCalculatorTest, AcceleratedTime) {
    EXPECT_EQ(calculateAcceleratedTime(100, {0.2, 0.5}), 56);  // ceil(100/1.8)
    EXPECT_EQ(calculateAcceleratedTime(10, {}), 10);
}

// ── 政策月度成本 ───────────────────────────────────────────────

TEST(PolicyCostTest, AllPoliciesPaid) {
    GameData gd;
    gd.spiritStones = 100000;
    gd.sectPolicies.alchemyIncentive = true;
    gd.sectPolicies.strictTraining = true;
    gd.sectPolicies.curfew = true;
    const auto result = processPolicyCosts(gd, 5, 3);
    EXPECT_TRUE(result.allPaid);
    EXPECT_TRUE(result.disabledPolicies.empty());
    // 3000 + 20000 + 1000 = 24000
    EXPECT_EQ(gd.spiritStones, 100000 - 24000);
    EXPECT_TRUE(gd.sectPolicies.alchemyIncentive);
    EXPECT_TRUE(gd.sectPolicies.strictTraining);
    EXPECT_TRUE(gd.sectPolicies.curfew);
}

TEST(PolicyCostTest, InsufficientDisablesPolicy) {
    GameData gd;
    gd.spiritStones = 5000;
    gd.sectPolicies.alchemyIncentive = true;
    gd.sectPolicies.strictTraining = true;  // 20000 付不起
    const auto result = processPolicyCosts(gd, 5, 3);
    EXPECT_FALSE(result.allPaid);
    ASSERT_EQ(result.disabledPolicies.size(), 1u);
    EXPECT_EQ(result.disabledPolicies[0], "严苛训练");
    EXPECT_FALSE(gd.sectPolicies.strictTraining);
    EXPECT_TRUE(gd.sectPolicies.alchemyIncentive);  // 仍开启
}

TEST(PolicyCostTest, PerDisciplePolicies) {
    GameData gd;
    gd.spiritStones = 100000;
    gd.sectPolicies.cultivationSubsidy = true;
    gd.sectPolicies.moralEducation = true;
    // 修行津贴 300×3（化神下）+ 教化 100×5 = 900 + 500 = 1400
    const auto result = processPolicyCosts(gd, 5, 3);
    EXPECT_TRUE(result.allPaid);
    EXPECT_EQ(gd.spiritStones, 100000 - 1400);
}

TEST(PolicyCostTest, OpenRecruitmentCooldown) {
    GameData gd;
    gd.spiritStones = 100000;
    gd.sectPolicies.openRecruitment = true;
    gd.gameYear = 2;
    gd.gameMonth = 1;   // 绝对 25
    gd.openRecruitmentLastPaidMonth = 25;  // 冷却期内
    const auto result = processPolicyCosts(gd, 5, 3);
    EXPECT_TRUE(result.allPaid);
    EXPECT_EQ(gd.spiritStones, 100000);  // 未扣
    // 超过 36 个月后扣
    gd.gameYear = 5;
    gd.gameMonth = 1;   // 绝对 61，61-25=36 ≥ 36 → 扣
    const auto result2 = processPolicyCosts(gd, 5, 3);
    EXPECT_EQ(gd.spiritStones, 100000 - 50000);
    EXPECT_EQ(gd.openRecruitmentLastPaidMonth, 61);
}

// ── 政策月度忠诚/道德 ──────────────────────────────────────────

TEST(PolicyMonthlyTest, LoyaltyDeltas) {
    SectPolicies p;
    p.benevolentGovernance = true;
    p.relaxedMgmt = true;
    p.strictTraining = true;
    p.curfew = true;
    const auto d = policyMonthlyDeltas(p);
    EXPECT_EQ(d.first, 1 + 2 - 1 - 1);  // +1
    EXPECT_EQ(d.second, 0);
}

TEST(PolicyMonthlyTest, MoralityDelta) {
    SectPolicies p;
    p.moralEducation = true;
    const auto d = policyMonthlyDeltas(p);
    EXPECT_EQ(d.second, 1);
}

// ── 灵矿产出 ───────────────────────────────────────────────────

TEST(SpiritMineTest, MonthlyOutput) {
    SpiritMineZones zones;
    zones.minerCount = 3;
    zones.avgMiningSkillBonus = 0.0;
    zones.deaconMoralityBonus = 0.0;
    zones.policyBoost = 0.0;
    EXPECT_EQ(calculateSpiritMineMonthly(zones, 170.0), 510);
}

TEST(SpiritMineTest, PolicyBoost) {
    SpiritMineZones zones;
    zones.minerCount = 3;
    zones.policyBoost = multiplierToZone(1.2);  // +20%
    const int64_t out = calculateSpiritMineMonthly(zones, 170.0);
    EXPECT_EQ(out, static_cast<int64_t>(std::round(510 * 1.2)));
}

TEST(SpiritMineTest, BuildZones) {
    const auto zones = buildSpiritMineZones(
        2, {80, 60}, {85}, true);  // 矿工 80/60，执事道德 85，政策开启
    EXPECT_EQ(zones.minerCount, 2);
    // 采矿加成：(80-70)×0.02 = 0.2，平均 /2 = 0.1
    EXPECT_DOUBLE_EQ(zones.avgMiningSkillBonus, 0.1);
    // 执事道德：(85-80)×0.01 = 0.05
    EXPECT_DOUBLE_EQ(zones.deaconMoralityBonus, 0.05);
    EXPECT_DOUBLE_EQ(zones.policyBoost, 0.2);
}

TEST(SpiritMineTest, SettleDifferential) {
    GameData gd;
    gd.gameYear = 2;
    gd.gameMonth = 1;   // 绝对 25
    gd.spiritMineLastSettledMonth = 13;  // 12 个月前
    SpiritMineZones zones;
    zones.minerCount = 1;
    const auto [output, settled] = settleSpiritMineProduction(gd, zones);
    EXPECT_TRUE(settled);
    EXPECT_EQ(output, 170 * 12);  // 月产出 × 12 个月
    EXPECT_EQ(gd.spiritMineLastSettledMonth, 25);
}

TEST(SpiritMineTest, SettleNoOlderSkips) {
    GameData gd;
    gd.gameYear = 2;
    gd.gameMonth = 1;
    gd.spiritMineLastSettledMonth = 25;  // 已结算
    SpiritMineZones zones;
    zones.minerCount = 1;
    const auto [output, settled] = settleSpiritMineProduction(gd, zones);
    EXPECT_FALSE(settled);
    EXPECT_EQ(output, 0);
}

// ── 年度年俸 ───────────────────────────────────────────────────

TEST(SalaryPlanTest, EligibleByRealmAndEnabled) {
    std::map<int32_t, int32_t> salary = {{9, 500}, {8, 1000}};
    std::map<int32_t, bool> enabled = {{9, true}, {8, false}};
    const auto plan = calculateSalaryPlan(
        salary, enabled, {{1, 9}, {2, 8}, {3, 7}});
    ASSERT_EQ(plan.eligibleSalaries.size(), 1u);
    EXPECT_EQ(plan.eligibleSalaries[0].first, 1);
    EXPECT_EQ(plan.eligibleSalaries[0].second, 500);
    EXPECT_EQ(plan.totalRequired, 500);
}

TEST(SalaryPlanTest, EmptyWhenNoEligible) {
    std::map<int32_t, int32_t> salary = {{9, 500}};
    std::map<int32_t, bool> enabled = {{9, false}};
    const auto plan = calculateSalaryPlan(salary, enabled, {{1, 9}});
    EXPECT_EQ(plan.totalRequired, 0);
    EXPECT_TRUE(plan.eligibleSalaries.empty());
}

TEST(SalaryTest, FrugalityReduction) {
    GameData gd;
    gd.spiritStones = 10000;
    SalaryPlan plan;
    plan.eligibleSalaries = {{1, 1000}};
    plan.totalRequired = 1000;
    const int64_t normal = payAnnualSalary(gd, plan, false);
    EXPECT_EQ(normal, 1000);
    EXPECT_EQ(gd.spiritStones, 9000);
    // 开源节流 -30%
    const int64_t frugal = payAnnualSalary(gd, plan, true);
    EXPECT_EQ(frugal, 700);
    EXPECT_EQ(gd.spiritStones, 9000 - 1000);  // 扣的是原额
}

TEST(SalaryTest, InsufficientNoPay) {
    GameData gd;
    gd.spiritStones = 500;
    SalaryPlan plan;
    plan.eligibleSalaries = {{1, 1000}};
    plan.totalRequired = 1000;
    const int64_t paid = payAnnualSalary(gd, plan, false);
    EXPECT_EQ(paid, 0);
    EXPECT_EQ(gd.spiritStones, 500);  // 未扣
}

}  // namespace
}  // namespace gamecore::system
