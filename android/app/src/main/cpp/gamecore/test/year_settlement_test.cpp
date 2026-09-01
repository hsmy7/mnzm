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
        st.disciples.appendDisciple(d);
    }

    core->advancePhases(1);

    EXPECT_EQ(9000L, st.gameData.spiritStones);   // 10000 - Σ原额 1000
    for (std::size_t i = 0; i < st.disciples.size(); ++i) {
        const auto d = st.disciples.materialize(i);
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
    st.disciples.appendDisciple(poor);

    core->advancePhases(1);

    EXPECT_EQ(100L, st.gameData.spiritStones);     // 未扣减
    EXPECT_EQ(4, st.disciples.materialize(0).loyalty);          // 5-1=4 ≥ MIN_LOYALTY 0
    EXPECT_EQ(0L, st.disciples.materialize(0).storageBagSpiritStones);
    EXPECT_EQ(0, st.disciples.materialize(0).salaryPaidCount);
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
    st.disciples.appendDisciple(d);

    core->advancePhases(1);

    // 扣减 Σ原额 500；发放 round(500×0.7)=350
    EXPECT_EQ(9500L, st.gameData.spiritStones);
    EXPECT_EQ(350L, st.disciples.materialize(0).storageBagSpiritStones);
    EXPECT_EQ(1, st.disciples.materialize(0).salaryPaidCount);
    EXPECT_EQ(50, st.disciples.materialize(0).loyalty);         // 开源节流不发忠诚
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
    st.disciples.appendDisciple(d);

    core->advancePhases(1);

    EXPECT_EQ(10000L, st.gameData.spiritStones);
    EXPECT_EQ(0L, st.disciples.materialize(0).storageBagSpiritStones);
    EXPECT_EQ(50, st.disciples.materialize(0).loyalty);
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
    st.disciples.appendDisciple(ghost);

    core->advancePhases(1);

    EXPECT_EQ(10000L, st.gameData.spiritStones);   // plan 空 → 整体跳过
    EXPECT_EQ(0L, st.disciples.materialize(0).storageBagSpiritStones);
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

// ════════════════════════════════════════════════════════════════
// 批 Y-1：年变零 RNG 小件黄金序列（T1-①/②/⑤/⑥/⑦/⑧ + T2-①/⑥/⑦/⑨/⑩）
// 每件直接调 detail:: 函数（runYearSettlement 内部同源），零 RNG 断言。
// ════════════════════════════════════════════════════════════════

TEST(YearSettlementTest, Y1T1VassalTributeDeductsByIncomeRatio) {
    // T1-① 附庸年贡：income=10000 → tribute=5000（0.5 比例）；钱包扣 LOW
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.suzerainSectId = "ai-1";
    st.gameData.lastYearSpiritStoneIncome = 10000L;
    st.gameData.spiritStones = 10000L;

    system::detail::processYearlyTribute(st);

    EXPECT_EQ(5000L, st.gameData.spiritStones);   // 10000 - 5000
}

TEST(YearSettlementTest, Y1T1VassalTributeNoSuzerainOrZeroIncomeSkips) {
    // 无主宗 → 早退；income=0 → tribute=0（max(0, 0)）早退
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 10000L;
    system::detail::processYearlyTribute(st);
    EXPECT_EQ(10000L, st.gameData.spiritStones);

    st.gameData.suzerainSectId = "ai-1";
    st.gameData.lastYearSpiritStoneIncome = 0L;
    system::detail::processYearlyTribute(st);
    EXPECT_EQ(10000L, st.gameData.spiritStones);
}

TEST(YearSettlementTest, Y1T1VassalTributePositiveIncomeUsesMin) {
    // income=1 → tribute=max(0.5→0, 1)=1（>0 时保底 1）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.suzerainSectId = "ai-1";
    st.gameData.lastYearSpiritStoneIncome = 1L;
    st.gameData.spiritStones = 10L;
    system::detail::processYearlyTribute(st);
    EXPECT_EQ(9L, st.gameData.spiritStones);
}

TEST(YearSettlementTest, Y1T1YearlyVassalTributeGrantsBySectLevel) {
    // T1-② 附属年贡：中型（level=1）→ 800000；lastTributeYear 更新；
    // 已贡（lastTributeYear==year）跳过；宗门不存在 → 契约移除
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 0L;
    state::WorldSect sect;
    sect.id = "ai-1";
    sect.isPlayerSect = false;
    sect.level = 1;
    st.gameData.worldMapSects.push_back(sect);

    state::VassalContract c1;
    c1.vassalSectId = "ai-1";
    c1.establishedYear = 1;
    c1.lastTributeYear = 0;
    st.gameData.vassalContracts.push_back(c1);

    state::VassalContract c2;   // 新建立当年（establishedYear >= year）不计
    c2.vassalSectId = "ai-1";
    c2.establishedYear = 3;
    c2.lastTributeYear = 0;
    st.gameData.vassalContracts.push_back(c2);

    state::VassalContract c3;   // 宗门不存在 → 移除
    c3.vassalSectId = "ai-gone";
    c3.establishedYear = 1;
    c3.lastTributeYear = 0;
    st.gameData.vassalContracts.push_back(c3);

    system::detail::processYearlyVassalTribute(st, /*year=*/2);

    EXPECT_EQ(800'000L, st.gameData.spiritStones);
    ASSERT_EQ(2u, st.gameData.vassalContracts.size());
    EXPECT_EQ(2, st.gameData.vassalContracts[0].lastTributeYear);
}

TEST(YearSettlementTest, Y1T1AutoRejectFiltersBySpiritRootCount) {
    // T1-⑤ 自动拒绝：filter {1} → 1 灵根 recruit 移除、2 灵根保留、
    // 损坏条目（空白名）保留；返回 1
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.autoRejectSpiritRootFilter = {1};

    state::Disciple r1;
    r1.id = "r1";
    r1.name = "一灵根";
    r1.spiritRootType = "metal";
    st.gameData.recruitList.push_back(r1);

    state::Disciple r2;
    r2.id = "r2";
    r2.name = "双灵根";
    r2.spiritRootType = "metal,wood";
    st.gameData.recruitList.push_back(r2);

    state::Disciple corrupt;
    corrupt.id = "r3";
    corrupt.name = "";          // 损坏（isValidRecruit 拒绝）但 1 灵根 → 保留
    corrupt.spiritRootType = "metal";
    st.gameData.recruitList.push_back(corrupt);

    const int32_t rejected = system::detail::processAutoReject(st);

    EXPECT_EQ(1, rejected);
    ASSERT_EQ(2u, st.gameData.recruitList.size());
    EXPECT_EQ("r2", st.gameData.recruitList[0].id);   // 非匹配保留
    EXPECT_EQ("r3", st.gameData.recruitList[1].id);   // 损坏保留
    EXPECT_FALSE(st.autoRejectIdle);
}

TEST(YearSettlementTest, Y1T1AutoRejectIdleWhenNoValidMatch) {
    // 全部非匹配 → 惰性置位、列表不变
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.autoRejectSpiritRootFilter = {3};

    state::Disciple r1;
    r1.id = "r1";
    r1.name = "一灵根";
    r1.spiritRootType = "metal";
    st.gameData.recruitList.push_back(r1);

    EXPECT_EQ(0, system::detail::processAutoReject(st));
    EXPECT_TRUE(st.autoRejectIdle);
    ASSERT_EQ(1u, st.gameData.recruitList.size());
}

TEST(YearSettlementTest, Y1T1MerchantRefreshChanceGrantedEvery30Years) {
    // T1-⑥ 商人刷新机会：lastGrant=0 → 首次授予；差值 30 → 再授；差值 29 跳过
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.merchantRefreshChances = 1;
    st.gameData.merchantLastRefreshChanceGrantYear = 0;

    system::detail::processMerchantRefreshChance(st, 10);
    EXPECT_EQ(2, st.gameData.merchantRefreshChances);
    EXPECT_EQ(10, st.gameData.merchantLastRefreshChanceGrantYear);

    system::detail::processMerchantRefreshChance(st, 39);   // 差 29 → 不授
    EXPECT_EQ(2, st.gameData.merchantRefreshChances);

    system::detail::processMerchantRefreshChance(st, 40);   // 差 30 → 授予
    EXPECT_EQ(3, st.gameData.merchantRefreshChances);
    EXPECT_EQ(40, st.gameData.merchantLastRefreshChanceGrantYear);
}

TEST(YearSettlementTest, Y1T1MerchantRefreshChanceCapped) {
    // 达上限（999）跳过
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.merchantRefreshChances = 999;
    st.gameData.merchantLastRefreshChanceGrantYear = 0;
    system::detail::processMerchantRefreshChance(st, 1);
    EXPECT_EQ(999, st.gameData.merchantRefreshChances);
    EXPECT_EQ(0, st.gameData.merchantLastRefreshChanceGrantYear);
}

TEST(YearSettlementTest, Y1T1YearlyAgingCullsDeadPastThreshold) {
    // T1-⑦ 年度老化：deathYears<=currentYear-1 移除；未来死亡保留
    auto core = makeCore(42);
    auto& st = core->state();
    for (const char* id : {"1", "2", "3"}) {
        Disciple d;
        d.id = id;
        d.name = std::string("死") + id;
        d.isAlive = false;
        st.disciples.appendDisciple(d);
    }
    st.disciples.deathYears[0] = 1;   // 已故 1 年 → 清理
    st.disciples.deathYears[1] = 3;   // 未来死亡 → 保留
    st.disciples.deathYears[2] = 0;   // 无条目（存活语义）→ 保留

    system::detail::processYearlyAging(st, /*currentYear=*/2);

    ASSERT_EQ(2u, st.disciples.size());
    EXPECT_EQ("2", st.disciples.materialize(0).id);
    EXPECT_EQ("3", st.disciples.materialize(1).id);
}

TEST(YearSettlementTest, Y1T1RecruitAgingAgesAndSanitizes) {
    // T1-⑧ 招募老化：age+1；超寿元移除；净化（损坏移除）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.recruitList.clear();

    state::Disciple young;
    young.id = "r1";
    young.name = "少年";
    young.age = 16;
    young.realm = 9;
    young.spiritRootType = "metal";
    st.gameData.recruitList.push_back(young);

    state::Disciple elder;
    elder.id = "r2";
    elder.name = "将死";
    elder.age = 19999;          // 老化后 20000 ≥ 上限 → 移除
    elder.realm = 9;
    elder.spiritRootType = "wood";
    st.gameData.recruitList.push_back(elder);

    state::Disciple corrupt;
    corrupt.id = "r3";
    corrupt.name = "";          // 损坏 → 净化移除
    corrupt.age = 16;
    corrupt.realm = 9;
    corrupt.spiritRootType = "fire";
    st.gameData.recruitList.push_back(corrupt);

    system::detail::processRecruitAging(st);

    ASSERT_EQ(1u, st.gameData.recruitList.size());
    EXPECT_EQ("r1", st.gameData.recruitList[0].id);
    EXPECT_EQ(17, st.gameData.recruitList[0].age);
}

TEST(YearSettlementTest, Y1T2SectDisciplesAgingFiltersOverMaxAge) {
    // T2-① AI 弟子老化：非玩家宗门 age+1 + 超寿元过滤；玩家宗门不动
    auto core = makeCore(42);
    auto& st = core->state();
    state::WorldSect aiSect;
    aiSect.id = "ai-1";
    aiSect.isPlayerSect = false;
    st.gameData.worldMapSects.push_back(aiSect);
    state::WorldSect playerSect;
    playerSect.id = "p1";
    playerSect.isPlayerSect = true;
    st.gameData.worldMapSects.push_back(playerSect);

    state::Disciple a1;
    a1.id = "a1";
    a1.age = 30;
    a1.isAlive = true;
    st.aiSectDisciples["ai-1"].push_back(a1);
    state::Disciple a2;
    a2.id = "a2";
    a2.age = 19999;
    a2.isAlive = true;
    st.aiSectDisciples["ai-1"].push_back(a2);
    state::Disciple p1;
    p1.id = "p1";
    p1.age = 30;
    p1.isAlive = true;
    st.aiSectDisciples["p1"].push_back(p1);

    system::detail::processSectDisciplesAging(st);

    ASSERT_EQ(1u, st.aiSectDisciples["ai-1"].size());
    EXPECT_EQ("a1", st.aiSectDisciples["ai-1"][0].id);
    EXPECT_EQ(31, st.aiSectDisciples["ai-1"][0].age);
    ASSERT_EQ(1u, st.aiSectDisciples["p1"].size());
    EXPECT_EQ(30, st.aiSectDisciples["p1"][0].age);   // 玩家宗门不老化
}

TEST(YearSettlementTest, Y1T2AllianceExpiryDissolvesAndClearsSects) {
    // T2-⑥ 联盟到期：startYear 5 年前 → 解散 + 成员宗门清 alliance 字段
    auto core = makeCore(42);
    auto& st = core->state();
    state::Alliance expired;
    expired.id = "all-1";
    expired.startYear = 1;
    expired.sectIds = {"p1", "ai-1"};
    st.gameData.alliances.push_back(expired);
    state::Alliance active;
    active.id = "all-2";
    active.startYear = 4;
    active.sectIds = {"ai-2"};
    st.gameData.alliances.push_back(active);

    state::WorldSect s1;
    s1.id = "p1";
    s1.allianceId = "all-1";
    s1.allianceStartYear = 1;
    st.gameData.worldMapSects.push_back(s1);
    state::WorldSect s2;
    s2.id = "ai-2";
    s2.allianceId = "all-2";
    s2.allianceStartYear = 4;
    st.gameData.worldMapSects.push_back(s2);

    system::detail::processAllianceExpiry(st, /*year=*/6);

    ASSERT_EQ(1u, st.gameData.alliances.size());
    EXPECT_EQ("all-2", st.gameData.alliances[0].id);
    EXPECT_EQ("", st.gameData.worldMapSects[0].allianceId);
    EXPECT_EQ(0, st.gameData.worldMapSects[0].allianceStartYear);
    EXPECT_EQ("all-2", st.gameData.worldMapSects[1].allianceId);
}

TEST(YearSettlementTest, Y1T2AllianceFavorDropDissolvesLowFavor) {
    // T2-⑦ 联盟好感过低（<80）自动解散；player 哨兵匹配
    auto core = makeCore(42);
    auto& st = core->state();
    state::Alliance alliance;
    alliance.id = "all-1";
    alliance.sectIds = {"player", "ai-1"};
    st.gameData.alliances.push_back(alliance);

    state::WorldSect playerSect;
    playerSect.id = "p1";
    playerSect.isPlayerSect = true;
    st.gameData.worldMapSects.push_back(playerSect);
    state::WorldSect aiSect;
    aiSect.id = "ai-1";
    aiSect.allianceId = "all-1";
    aiSect.allianceStartYear = 2;
    st.gameData.worldMapSects.push_back(aiSect);

    state::SectRelation rel;
    rel.sectId1 = "p1";
    rel.sectId2 = "ai-1";
    rel.favor = 79;             // < MIN_ALLIANCE_FAVOR 80
    st.gameData.sectRelations.push_back(rel);

    system::detail::processAllianceFavorDrop(st);

    ASSERT_TRUE(st.gameData.alliances.empty());
    EXPECT_EQ("", st.gameData.worldMapSects[1].allianceId);
}

TEST(YearSettlementTest, Y1T2AllianceFavorDropKeepsHighFavor) {
    // 好感 90 ≥ 80 → 联盟保留
    auto core = makeCore(42);
    auto& st = core->state();
    state::Alliance alliance;
    alliance.id = "all-1";
    alliance.sectIds = {"player", "ai-1"};
    st.gameData.alliances.push_back(alliance);
    state::WorldSect playerSect;
    playerSect.id = "p1";
    playerSect.isPlayerSect = true;
    st.gameData.worldMapSects.push_back(playerSect);
    state::SectRelation rel;
    rel.sectId1 = "p1";
    rel.sectId2 = "ai-1";
    rel.favor = 90;
    st.gameData.sectRelations.push_back(rel);

    system::detail::processAllianceFavorDrop(st);

    ASSERT_EQ(1u, st.gameData.alliances.size());
}

TEST(YearSettlementTest, Y1T2FavorDecayAppliesOnlyToPlayerRelations) {
    // T2-⑨ 好感衰减：玩家相关 + favor>80 + 距上次交互 ≥1 年 → 减 1 保底 80
    auto core = makeCore(42);
    auto& st = core->state();
    state::WorldSect playerSect;
    playerSect.id = "p1";
    playerSect.isPlayerSect = true;
    st.gameData.worldMapSects.push_back(playerSect);

    state::SectRelation decay;
    decay.sectId1 = "p1";
    decay.sectId2 = "ai-1";
    decay.favor = 85;
    decay.acquainted = true;
    decay.lastInteractionYear = 1;
    decay.noGiftYears = 1;
    st.gameData.sectRelations.push_back(decay);

    state::SectRelation lowFavor;
    lowFavor.sectId1 = "p1";
    lowFavor.sectId2 = "ai-2";
    lowFavor.favor = 80;        // ≤ 阈值 → 不衰减
    lowFavor.acquainted = true;
    lowFavor.lastInteractionYear = 1;
    st.gameData.sectRelations.push_back(lowFavor);

    state::SectRelation aiOnly;
    aiOnly.sectId1 = "ai-3";
    aiOnly.sectId2 = "ai-4";
    aiOnly.favor = 90;
    aiOnly.acquainted = true;
    aiOnly.lastInteractionYear = 1;
    st.gameData.sectRelations.push_back(aiOnly);

    system::detail::processFavorDecay(st, /*currentYear=*/3);

    EXPECT_EQ(84, st.gameData.sectRelations[0].favor);
    EXPECT_EQ(2, st.gameData.sectRelations[0].noGiftYears);
    EXPECT_EQ(80, st.gameData.sectRelations[1].favor);
    EXPECT_EQ(90, st.gameData.sectRelations[2].favor);
}

TEST(YearSettlementTest, Y1T2GriefExpiryClearsExpiredSentinel) {
    // T2-⑩ 哀悼期到期：griefEndYears 到期（>= currentYear）→ 置 -1
    auto core = makeCore(42);
    auto& st = core->state();
    for (const char* id : {"1", "2"}) {
        Disciple d;
        d.id = id;
        d.name = std::string("哀") + id;
        d.isAlive = true;
        st.disciples.appendDisciple(d);
    }
    st.disciples.griefEndYears[0] = 2;   // 到期（currentYear=2）
    st.disciples.griefEndYears[1] = 5;   // 未到期

    system::detail::processGriefExpiry(st, /*currentYear=*/2);

    EXPECT_EQ(-1, st.disciples.griefEndYears[0]);
    EXPECT_EQ(5, st.disciples.griefEndYears[1]);
}

// ════════════════════════════════════════════════════════════════
// 批 Y-2：年变中件黄金序列（T1-⑨ 思过释放 + T1-⑩ 驻军轮换）
// ════════════════════════════════════════════════════════════════

TEST(YearSettlementTest, Y2T1ReflectionReleaseFreesAndBonuses) {
    // T1-⑨ 思过到期释放：IDLE + 道德/忠诚 +5（cap 200/100）+ 清思过字段；
    // 未到期弟子保留 REFLECTING。到期弟子道德 50 ≥ 阈值 → 零 SYSTEM 抽取。
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 1;
    st.gameData.spiritStones = 1000L;

    Disciple due;
    due.id = "1";
    due.name = "思一";
    due.isAlive = true;
    due.status = "REFLECTING";
    due.statusData["reflectionStartYear"] = "1";
    due.statusData["reflectionEndYear"] = "2";
    due.morality = 50;
    due.loyalty = 50;
    st.disciples.appendDisciple(due);

    Disciple pending;
    pending.id = "2";
    pending.name = "思二";
    pending.isAlive = true;
    pending.status = "REFLECTING";
    pending.statusData["reflectionEndYear"] = "5";
    st.disciples.appendDisciple(pending);

    system::detail::processReflectionRelease(st, /*year=*/2, core->rng());

    const auto r1 = st.disciples.materialize(0);
    EXPECT_EQ("IDLE", r1.status);
    EXPECT_EQ(55, r1.morality);                    // 50 + 5
    EXPECT_EQ(55, r1.loyalty);                     // 50 + 5
    EXPECT_EQ(r1.statusData.end(), r1.statusData.find("reflectionEndYear"));
    EXPECT_EQ(r1.statusData.end(), r1.statusData.find("reflectionStartYear"));
    const auto r2 = st.disciples.materialize(1);
    EXPECT_EQ("REFLECTING", r2.status);            // 未到期保留
}

TEST(YearSettlementTest, Y2T1ReflectionReleaseMoralityCap) {
    // 道德 198 → +5 cap 200；忠诚 198 → cap 100
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 1;

    Disciple due;
    due.id = "1";
    due.name = "巅峰";
    due.isAlive = true;
    due.status = "REFLECTING";
    due.statusData["reflectionEndYear"] = "2";
    due.morality = 198;
    due.loyalty = 198;
    st.disciples.appendDisciple(due);

    system::detail::processReflectionRelease(st, /*year=*/2, core->rng());

    const auto r = st.disciples.materialize(0);
    EXPECT_EQ(200, r.morality);                    // min(203, 200)
    EXPECT_EQ(100, r.loyalty);                     // min(203, 100)
}

TEST(YearSettlementTest, Y2T1ReflectionReleaseLowMoralityTriggersTheft) {
    // 释放后道德 < 阈值（30）→ 单弟子偷盗判定触发——SYSTEM 分区快照变化
    //（偷盗链至少 1 次 nextDouble）；前置链构造：灵石 >0、平均忠诚 <50、
    // IDLE、保护期外（recruitedMonths 默认 0）、年/月/年上限未满。
    const int64_t seed = 2026;
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 1;
    st.gameData.spiritStones = 1000L;

    Disciple thief;
    thief.id = "1";
    thief.name = "恶思";
    thief.isAlive = true;
    thief.status = "REFLECTING";
    thief.statusData["reflectionEndYear"] = "2";
    thief.morality = 10;        // 释放后 15 < 阈值 30 → 判定
    thief.loyalty = 40;
    st.disciples.appendDisciple(thief);

    Disciple peer;
    peer.id = "2";
    peer.name = "同门";
    peer.isAlive = true;
    peer.loyalty = 40;          // 平均忠诚 40 < 50 → 从众门控通过
    st.disciples.appendDisciple(peer);

    const int64_t before = core->rng()
        .getRng(rng::RngPartition::kSystem)
        .snapshot();
    system::detail::processReflectionRelease(st, /*year=*/2, core->rng());
    const int64_t after = core->rng()
        .getRng(rng::RngPartition::kSystem)
        .snapshot();

    EXPECT_NE(before, after) << "低道德思过释放必须触发 SYSTEM 偷盗判定抽取";
    EXPECT_EQ("IDLE", st.disciples.materialize(0).status);
}

TEST(YearSettlementTest, Y2T1GarrisonRotationFillsOccupiedSlots) {
    // T1-⑩ 驻军轮换：玩家宗门 + AI 占领宗门（occupier ai-1）+ 12 存活弟子
    // → 前 10 留守（不入 garrison）、第 11/12 名填占领宗门 10 槽的前 2 槽
    auto core = makeCore(42);
    auto& st = core->state();
    state::WorldSect player;
    player.id = "p1";
    player.isPlayerSect = true;
    st.gameData.worldMapSects.push_back(player);
    state::WorldSect occupied;
    occupied.id = "s-1";
    occupied.isPlayerSect = false;
    occupied.occupierSectId = "ai-1";
    occupied.garrisonSlots.resize(10);
    st.gameData.worldMapSects.push_back(occupied);

    for (int32_t i = 1; i <= 12; ++i) {
        state::Disciple d;
        d.id = "d" + std::to_string(i);
        d.name = "驻" + std::to_string(i);
        d.isAlive = true;
        d.realm = 9 - (i % 4);   // realm 交错：8/9/6/7…
        d.spiritRootType = "metal";
        d.portraitRes = "p" + std::to_string(i);
        st.aiSectDisciples["ai-1"].push_back(d);
    }

    system::detail::processGarrisonRotation(st);

    const auto& slots = st.gameData.worldMapSects[1].garrisonSlots;
    // realm 值（i%4）：1→8, 2→7, 3→6, 4→9, 5→8, 6→7, 7→6, 8→9,
    // 9→8, 10→7, 11→6, 12→9 → 稳定升序：3,7,11,2,6,10,1,5,9,4,8,12
    //（realm 6,6,6,7,7,7,8,8,8,9,9,9）
    // 前 10 名留守宗门 → 外派第 11/12 名 = d8（realm 9）、d12（realm 9）
    EXPECT_EQ("d8", slots[0].discipleId) << "realm 升序第 11 名（9）";
    EXPECT_EQ("d12", slots[1].discipleId) << "realm 升序第 12 名（9）";
    EXPECT_TRUE(slots[2].discipleId.empty()) << "第 13 名起无候选";
    EXPECT_EQ(10, static_cast<int>(slots.size()));
    // 留守弟子不入 garrison：assert slot[2..9] 全空
    for (int32_t i = 2; i < 10; ++i) {
        EXPECT_TRUE(slots[static_cast<std::size_t>(i)].discipleId.empty()) << "slot " << i;
    }
}

TEST(YearSettlementTest, Y2T1GarrisonRotationNoPlayerSectIdempotent) {
    // 无玩家宗门 → 恒等（return gameData）
    auto core = makeCore(42);
    auto& st = core->state();
    state::WorldSect occupied;
    occupied.id = "s-1";
    occupied.occupierSectId = "ai-1";
    st.gameData.worldMapSects.push_back(occupied);
    const auto before = st.gameData.worldMapSects;

    system::detail::processGarrisonRotation(st);

    ASSERT_EQ(before.size(), st.gameData.worldMapSects.size());
    EXPECT_EQ(before[0].garrisonSlots.size(),
              st.gameData.worldMapSects[0].garrisonSlots.size());
}

TEST(YearSettlementTest, Y2T1GarrisonRotationNoOccupiedSectsIdempotent) {
    // 无 AI 占领宗门 → 恒等
    auto core = makeCore(42);
    auto& st = core->state();
    state::WorldSect player;
    player.id = "p1";
    player.isPlayerSect = true;
    st.gameData.worldMapSects.push_back(player);
    state::WorldSect free;
    free.id = "s-1";
    free.isPlayerSect = false;
    st.gameData.worldMapSects.push_back(free);

    system::detail::processGarrisonRotation(st);

    ASSERT_EQ(2u, st.gameData.worldMapSects.size());
    EXPECT_TRUE(st.gameData.worldMapSects[1].garrisonSlots.empty());
}

TEST(YearSettlementTest, Y2T2SecretRealmSpawnGeneratesRealm) {
    // T2-⑪ 秘境年变刷新：冷却满（year - cooldown >= 50）→ 生成（SECRET_REALM
    // 分区位置 + 变体）；断言 id 空（镜像生成）、位置在边界内、spawnYear/month、
    // spriteIndex ∈ [0,3)
    auto core = makeCore(2026);
    auto& st = core->state();
    st.gameData.gameYear = 51;
    st.gameData.gameMonth = 3;
    st.gameData.secretRealmCooldownYear = 1;   // 51 - 1 = 50 >= 50 ✅

    system::detail::processAncientSecretRealmSpawn(st, /*year=*/51, core->rng());

    EXPECT_FALSE(st.gameData.secretRealmState.id.empty() == false) << "id 为镜像生成占位（空串）";
    EXPECT_EQ(51, st.gameData.secretRealmState.spawnYear);
    EXPECT_EQ(3, st.gameData.secretRealmState.spawnMonth);
    EXPECT_GE(st.gameData.secretRealmState.spriteIndex, 0);
    EXPECT_LT(st.gameData.secretRealmState.spriteIndex, 3);
    // 位置在边界内（34..1664 x / 34..892 y——宽松断言）
    EXPECT_GE(st.gameData.secretRealmState.x, 34.0f);
    EXPECT_GE(st.gameData.secretRealmState.y, 34.0f);
}

TEST(YearSettlementTest, Y2T2SecretRealmSpawnCooldownNotMetSkips) {
    // 冷却未满（year - cooldown < 50）→ 零生成零 RNG
    const int64_t seed = 2026;
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.gameYear = 49;
    st.gameData.secretRealmCooldownYear = 0;   // 49 - 0 = 49 < 50

    const int64_t before = core->rng()
        .getRng(rng::RngPartition::kSecretRealm)
        .snapshot();
    system::detail::processAncientSecretRealmSpawn(st, /*year=*/49, core->rng());
    const int64_t after = core->rng()
        .getRng(rng::RngPartition::kSecretRealm)
        .snapshot();

    EXPECT_TRUE(st.gameData.secretRealmState.id.empty());
    EXPECT_EQ(before, after) << "冷却未满必须零 SECRET_REALM 抽取";
}

TEST(YearSettlementTest, Y2T2SecretRealmSpawnAlreadyPresentSkips) {
    // 已现世 → 不重复生成
    auto core = makeCore(2026);
    auto& st = core->state();
    st.gameData.gameYear = 51;
    st.gameData.secretRealmState.id = "sr-1";
    st.gameData.secretRealmCooldownYear = 1;

    const int64_t before = core->rng()
        .getRng(rng::RngPartition::kSecretRealm)
        .snapshot();
    system::detail::processAncientSecretRealmSpawn(st, /*year=*/51, core->rng());
    const int64_t after = core->rng()
        .getRng(rng::RngPartition::kSecretRealm)
        .snapshot();

    EXPECT_EQ("sr-1", st.gameData.secretRealmState.id);
    EXPECT_EQ(before, after) << "已现世必须零 SECRET_REALM 抽取";
}

// ════════════════════════════════════════════════════════════════
// 批 Y-3：年变招募刷新（T1-④）黄金序列
// ════════════════════════════════════════════════════════════════

TEST(YearSettlementTest, Y3T1RefreshRecruitListSkipsWhenIntervalNotMet) {
    // 差值判据：year - lastRecruitYear < 3 → 零刷新零 RNG
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 4;
    st.gameData.lastRecruitYear = 2;   // 差值 2 < 3

    const int64_t before = core->rng()
        .getRng(rng::RngPartition::kSystem)
        .snapshot();
    system::detail::processRefreshRecruitList(st, /*year=*/4, core->rng());
    const int64_t after = core->rng()
        .getRng(rng::RngPartition::kSystem)
        .snapshot();

    EXPECT_TRUE(st.gameData.recruitList.empty());
    EXPECT_EQ(before, after) << "差值未满必须零 SYSTEM 抽取";
}

TEST(YearSettlementTest, Y3T1RefreshRecruitListGeneratesRecruits) {
    // 玩家宗门（大 1..10）+ lastRecruitYear 差值 3 → 刷新：recruitList 追加、
    // lastRecruitYear 更新、弟子字段合法（名字非空/年龄 16..29/realm 9）
    auto core = makeCore(2026);
    auto& st = core->state();
    st.gameData.gameYear = 5;
    st.gameData.lastRecruitYear = 2;   // 差值 3 ≥ 3 ✅
    state::WorldSect player;
    player.id = "p1";
    player.isPlayerSect = true;
    player.level = 2;                  // 大（1..10）
    st.gameData.worldMapSects.push_back(player);

    system::detail::processRefreshRecruitList(st, /*year=*/5, core->rng());

    EXPECT_EQ(5, st.gameData.lastRecruitYear);
    ASSERT_FALSE(st.gameData.recruitList.empty());
    EXPECT_LE(st.gameData.recruitList.size(), 10u);
    for (const auto& r : st.gameData.recruitList) {
        EXPECT_FALSE(r.name.empty()) << "名字非空";
        EXPECT_GE(r.age, 16);
        EXPECT_LE(r.age, 29);          // 16 + nextInt(14) → 16..29
        EXPECT_EQ(9, r.realm);
    }
}

TEST(YearSettlementTest, Y3T1RefreshRecruitListOpenRecruitmentBonus) {
    // 广纳门徒政策 +50%（roundToInt）：固定数量场景验证政策加成生效
    //（判据差值满足；政策开 → 数量 ≥ 无政策场景）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 6;
    st.gameData.lastRecruitYear = 3;
    st.gameData.sectPolicies.openRecruitment = true;
    state::WorldSect player;
    player.id = "p1";
    player.isPlayerSect = true;
    player.level = 3;                  // 顶级（1..15）
    st.gameData.worldMapSects.push_back(player);

    system::detail::processRefreshRecruitList(st, /*year=*/6, core->rng());

    EXPECT_EQ(6, st.gameData.lastRecruitYear);
    ASSERT_FALSE(st.gameData.recruitList.empty());
    EXPECT_LE(st.gameData.recruitList.size(), 15u);
}

TEST(YearSettlementTest, Y3T1RefreshRecruitListNoPlayerFallback) {
    // 无玩家宗门 → 兜底 nextInt(7) coerceAtLeast 1（≥1）
    auto core = makeCore(2026);
    auto& st = core->state();
    st.gameData.gameYear = 8;
    st.gameData.lastRecruitYear = 5;

    system::detail::processRefreshRecruitList(st, /*year=*/8, core->rng());

    EXPECT_EQ(8, st.gameData.lastRecruitYear);
    ASSERT_FALSE(st.gameData.recruitList.empty());
    EXPECT_GE(st.gameData.recruitList.size(), 1u);
}

// ════════════════════════════════════════════════════════════════
// 批 Y-3（T1-③ 弟子老化死亡链）黄金序列
// ════════════════════════════════════════════════════════════════

TEST(YearSettlementTest, Y3T1AgingAliveDisciplesWithoutDeath) {
    // 无死亡：活弟子 age+1；5 岁境界层回正
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d1;
    d1.id = "1";
    d1.name = "少年";
    d1.age = 4;
    d1.realmLayer = 0;   // 老化后 5 岁 → 回正 1
    d1.isAlive = true;
    st.disciples.appendDisciple(d1);
    Disciple d2;
    d2.id = "2";
    d2.name = "青年";
    d2.age = 30;
    d2.isAlive = true;
    st.disciples.appendDisciple(d2);

    system::YearSettlementDraft draft;
    system::detail::processDiscipleAgingStep(st, /*currentYear=*/2, &draft);

    ASSERT_EQ(2u, st.disciples.size());
    EXPECT_EQ(5, st.disciples.materialize(0).age);
    EXPECT_EQ(1, st.disciples.materialize(0).realmLayer);   // 5 岁回正
    EXPECT_EQ(31, st.disciples.materialize(1).age);
    EXPECT_TRUE(draft.agedDeaths.empty());
}

TEST(YearSettlementTest, Y3T1AgingDeathRemovesAndDrafts) {
    // 寿元耗尽死亡：age 79（lifespan 80）老化后 80 >= maxAge 80 → 死亡——
    // store 移除 + annualDeceasedDisciples+1 + 死亡事件 + 草稿（agedDeaths）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.annualDeceasedDisciples = 0;
    Disciple elder;
    elder.id = "1";
    elder.name = "老寿";
    elder.age = 79;
    elder.lifespan = 80;
    elder.realm = 9;
    elder.isAlive = true;
    st.disciples.appendDisciple(elder);
    Disciple young;
    young.id = "2";
    young.name = "后辈";
    young.age = 30;
    young.isAlive = true;
    st.disciples.appendDisciple(young);

    system::YearSettlementDraft draft;
    system::detail::processDiscipleAgingStep(st, /*currentYear=*/3, &draft);

    ASSERT_EQ(1u, st.disciples.size());                     // 老者移除
    EXPECT_EQ("2", st.disciples.materialize(0).id);
    EXPECT_EQ(31, st.disciples.materialize(0).age);         // 活者老化
    EXPECT_EQ(1, st.gameData.annualDeceasedDisciples);
    ASSERT_EQ(1u, draft.agedDeaths.size());
    EXPECT_EQ("1", draft.agedDeaths[0].discipleId);
    EXPECT_EQ("老寿", draft.agedDeaths[0].name);
    EXPECT_EQ(80, draft.agedDeaths[0].age);
    EXPECT_EQ(3, draft.agedDeaths[0].deathYear);
}

TEST(YearSettlementTest, Y3T1AgingGriefPropagationAndUnbind) {
    // 哀悼传播 + 解绑：夫妻互指 partnerIds，夫死 → 妻 griefEndYears=year+1 +
    // partnerIds 清空 + 丧亲草稿（道侣）
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple husband;
    husband.id = "1";
    husband.name = "夫君";
    husband.age = 79;
    husband.lifespan = 80;
    husband.isAlive = true;
    husband.partnerId = "2";
    st.disciples.appendDisciple(husband);
    Disciple wife;
    wife.id = "2";
    wife.name = "妻子";
    wife.age = 30;
    wife.isAlive = true;
    wife.partnerId = "1";
    st.disciples.appendDisciple(wife);

    system::YearSettlementDraft draft;
    system::detail::processDiscipleAgingStep(st, /*currentYear=*/5, &draft);

    ASSERT_EQ(1u, st.disciples.size());
    const auto w = st.disciples.materialize(0);
    EXPECT_EQ(6, w.griefEndYear);                     // currentYear+1
    EXPECT_TRUE(w.partnerId.empty());                 // 解绑
    ASSERT_EQ(1u, draft.bereavements.size());
    EXPECT_EQ(2, draft.bereavements[0].grievingId);
    EXPECT_EQ("道侣", draft.bereavements[0].relationship);
    EXPECT_EQ("夫君", draft.bereavements[0].deceasedName);
}

TEST(YearSettlementTest, Y3T1AgingSlotCleanupClearsElder) {
    // 槽位清理：死亡弟子在纳徒长老槽 → 槽清空（elderSlots.recruitingElder）
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple elder;
    elder.id = "1";
    elder.name = "长老";
    elder.age = 79;
    elder.lifespan = 80;
    elder.isAlive = true;
    st.disciples.appendDisciple(elder);
    st.gameData.elderSlots.recruitingElder = "1";

    system::YearSettlementDraft draft;
    system::detail::processDiscipleAgingStep(st, /*currentYear=*/3, &draft);

    EXPECT_EQ(0u, st.disciples.size());
    EXPECT_TRUE(st.gameData.elderSlots.recruitingElder.empty());
}

}  // namespace
