// ============================================================
// year_settlement_test — 年变结算黄金序列守护（T2.3）
//
// 守护目标：固定状态 → SettlementEngine::onYearChange（runYearSettlement）
// 跨年边界推进 → 断言年报快照/annual* 清零/年俸发放面/忠诚惩罚逐位符合手算期望。
//
// RNG：年变 T1/T2 场景规避后全程零抽取——每用例后断言全部分区快照不变
//（"分区状态不变"即抽取次数与顺序锁定的最强形式）。
// ============================================================

#include "gtest/gtest.h"

#include <memory>

#include "gamecore/game_core.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/year_settlement.h"

namespace {

using namespace gamecore;
using gamecore::state::Disciple;
using gamecore::state::GameData;

/// 构建已初始化 GameCore（三钩子已注册），种子固定
std::unique_ptr<GameCore> makeCore(int64_t seed) {
    auto core = std::unique_ptr<GameCore>(new GameCore(nullptr, nullptr));
    GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = seed;
    EXPECT_TRUE(core->initialize(config));
    return core;
}

/// 断言全部 RNG 分区状态 == 初始播种态（年变零抽取审计）
void expectAllPartitionsUnchanged(GameCore& core, int64_t seed) {
    for (int p = 0; p < 8; ++p) {
        auto fresh = gamecore::rng::DeterministicRng::fromSeed(seed + p);
        EXPECT_EQ(fresh.snapshot(),
                  core.rng().getRng(static_cast<rng::RngPartition>(p)).snapshot())
            << "partition " << p;
    }
}

TEST(YearSettlementTest, YearlyReportSnapshotAndAnnualReset) {
    // 跨年边界：12 月下旬 → 2 年 1 月上旬
    // 年报快照 = 旧年 annual* 值；随后 annual* 十二项清零；takeLast(100)
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 12;
    st.gameData.gamePhase = 2;
    st.gameData.annualTotalIncome = 5000L;
    st.gameData.annualTotalExpenditure = 1200L;
    st.gameData.annualIncomeBySource["Mine"] = 5000L;
    st.gameData.annualAlchemyCount = 3;
    st.gameData.annualNewDisciples = 2;
    st.gameData.annualTheftCount = 1;

    const auto r = core->advancePhases(1);
    EXPECT_TRUE(r.yearChanged);
    EXPECT_EQ(2, st.gameData.gameYear);
    EXPECT_EQ(1, st.gameData.gameMonth);

    ASSERT_EQ(1u, st.gameData.yearlyReports.size());
    const auto& rep = st.gameData.yearlyReports[0];
    EXPECT_EQ(1, rep.year);                 // 归属刚结束的年份
    EXPECT_EQ(5000L, rep.totalIncome);
    EXPECT_EQ(1200L, rep.totalExpenditure);
    EXPECT_EQ(5000L, rep.incomeBySource.at("Mine"));
    EXPECT_EQ(3, rep.alchemyCompleted);
    EXPECT_EQ(2, rep.newDisciples);

    // annual* 清零
    EXPECT_EQ(0L, st.gameData.annualTotalIncome);
    EXPECT_EQ(0L, st.gameData.annualTotalExpenditure);
    EXPECT_TRUE(st.gameData.annualIncomeBySource.empty());
    EXPECT_EQ(0, st.gameData.annualAlchemyCount);
    EXPECT_EQ(0, st.gameData.annualNewDisciples);
    EXPECT_EQ(0L, st.gameData.annualTheftCount);
}

TEST(YearSettlementTest, AnnualSalaryPaidWithLoyaltyAndLedger) {
    // realm=9 年俸 500（enabled）× 2 弟子；灵石充足；非开源节流
    // → spiritStones -1000、每人袋 +500、paidCount+1、loyalty 50→51
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 12;
    st.gameData.gamePhase = 2;
    st.gameData.spiritStones = 10000L;
    st.gameData.yearlySalary[9] = 500;
    st.gameData.yearlySalaryEnabled[9] = true;

    for (const char* id : {"1", "2"}) {
        Disciple d;
        d.id = id;
        d.name = std::string("弟子") + id;
        d.realm = 9;
        d.isAlive = true;
        st.disciples.push_back(d);
    }

    core->advancePhases(1);

    EXPECT_EQ(9000L, st.gameData.spiritStones);   // 10000 - Σ原额 1000
    for (const auto& d : st.disciples) {
        EXPECT_EQ(500L, d.storageBagSpiritStones) << d.id;   // round(500×1.0)
        EXPECT_EQ(1, d.salaryPaidCount) << d.id;
        EXPECT_EQ(51, d.loyalty) << d.id;                     // 50+1 cap100
    }
}

TEST(YearSettlementTest, AnnualSalaryInsufficientFundsDropsLoyalty) {
    // 灵石不足 → 不发俸禄，应得弟子 loyalty -1（coerceAtLeast 0）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 12;
    st.gameData.gamePhase = 2;
    st.gameData.spiritStones = 100L;              // < totalRequired 1000
    st.gameData.yearlySalary[9] = 500;
    st.gameData.yearlySalaryEnabled[9] = true;

    Disciple poor;
    poor.id = "1";
    poor.name = "贫";
    poor.realm = 9;
    poor.isAlive = true;
    poor.loyalty = 5;                              // 触底保护可观察
    st.disciples.push_back(poor);

    core->advancePhases(1);

    EXPECT_EQ(100L, st.gameData.spiritStones);     // 未扣减
    EXPECT_EQ(4, st.disciples[0].loyalty);          // 5-1=4 ≥ MIN_LOYALTY 0
    EXPECT_EQ(0L, st.disciples[0].storageBagSpiritStones);
    EXPECT_EQ(0, st.disciples[0].salaryPaidCount);
}

TEST(YearSettlementTest, AnnualSalaryFrugalityReducesPayNoLoyalty) {
    // 开源节流：发放额 ×0.7 且不发忠诚
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 12;
    st.gameData.gamePhase = 2;
    st.gameData.spiritStones = 10000L;
    st.gameData.sectPolicies.frugality = true;
    st.gameData.yearlySalary[9] = 500;
    st.gameData.yearlySalaryEnabled[9] = true;

    Disciple d;
    d.id = "1";
    d.name = "节";
    d.realm = 9;
    d.isAlive = true;
    d.loyalty = 50;
    st.disciples.push_back(d);

    core->advancePhases(1);

    // 扣减 Σ原额 500；发放 round(500×0.7)=350
    EXPECT_EQ(9500L, st.gameData.spiritStones);
    EXPECT_EQ(350L, st.disciples[0].storageBagSpiritStones);
    EXPECT_EQ(1, st.disciples[0].salaryPaidCount);
    EXPECT_EQ(50, st.disciples[0].loyalty);         // 开源节流不发忠诚
}

TEST(YearSettlementTest, AnnualSalaryDisabledRealmSkipped) {
    // enabledConfig[realm]=false 的弟子不入 plan（无扣减无发放）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 12;
    st.gameData.gamePhase = 2;
    st.gameData.spiritStones = 10000L;
    st.gameData.yearlySalary[9] = 500;
    // yearlySalaryEnabled 缺失 → 非启用

    Disciple d;
    d.id = "1";
    d.name = "未启用";
    d.realm = 9;
    d.isAlive = true;
    st.disciples.push_back(d);

    core->advancePhases(1);

    EXPECT_EQ(10000L, st.gameData.spiritStones);
    EXPECT_EQ(0L, st.disciples[0].storageBagSpiritStones);
    EXPECT_EQ(50, st.disciples[0].loyalty);
}

TEST(YearSettlementTest, GhostBlankNameDiscipleSkipped) {
    // 幽灵防御：name 空白弟子跳过年俸（plan 不含 → 无扣减）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 12;
    st.gameData.gamePhase = 2;
    st.gameData.spiritStones = 10000L;
    st.gameData.yearlySalary[9] = 500;
    st.gameData.yearlySalaryEnabled[9] = true;

    Disciple ghost;
    ghost.id = "1";
    ghost.name = "   ";      // 全空白名
    ghost.realm = 9;
    ghost.isAlive = true;
    st.disciples.push_back(ghost);

    core->advancePhases(1);

    EXPECT_EQ(10000L, st.gameData.spiritStones);   // plan 空 → 整体跳过
    EXPECT_EQ(0L, st.disciples[0].storageBagSpiritStones);
}

TEST(YearSettlementTest, YearChangeConsumesZeroRng) {
    // 年变全程零 RNG 抽取：跨年后全部分区快照 == 播种初值
    const int64_t seed = 77;
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 12;
    st.gameData.gamePhase = 2;

    core->advancePhases(1);

    expectAllPartitionsUnchanged(*core, seed);
}

}  // namespace
