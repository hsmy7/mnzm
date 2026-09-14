// ============================================================
// year_settlement_test — 年变结算黄金序列守护
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

TEST(YearSettlementTest, YearChangeConsumesOnlySystemPartition) {
    // 商人收购下沉后年变行为基线：收购刷新每年
    // 消费 SYSTEM 分区（数量 1×nextInt(9) + 每 item 品阶/选池/库存/grade/
    // 价格波动）；其余 8 分区（BATTLE/BREAKTHROUGH/EXPLORATION/ENEMY_GEN/
    // MAIL/AI_SECT/SECRET_REALM/MISSION）保持播种初值不变（零消耗）。
    // 场景无 sectDetails/worldMapSects → 交易刷新与 AI 招募零效果。
    const int64_t seed = 77;
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 12;
    st.gameData.gamePhase = 2;

    const auto sysBefore =
        core->rng().getRng(rng::RngPartition::kSystem).snapshot();
    core->advancePhases(1);

    // SYSTEM 分区被商人收购刷新消耗
    EXPECT_NE(sysBefore,
              core->rng().getRng(rng::RngPartition::kSystem).snapshot());
    // 其余分区不变（遍历 0..8 跳过 SYSTEM）
    for (int p = 0; p < 9; ++p) {
        if (p == static_cast<int>(rng::RngPartition::kSystem)) continue;
        auto fresh = rng::DeterministicRng::fromSeed(seed + p);
        EXPECT_EQ(fresh.snapshot(),
                  core->rng().getRng(static_cast<rng::RngPartition>(p)).snapshot())
            << "partition " << p;
    }
}

// ════════════════════════════════════════════════════════════════
// 年变零 RNG 小件黄金序列
// 每件直接调 detail:: 函数（runYearSettlement 内部同源），零 RNG 断言。
// ════════════════════════════════════════════════════════════════

TEST(YearSettlementTest, Y1T1VassalTributeDeductsByIncomeRatio) {
    // 附庸年贡：income=10000 → tribute=5000（0.5 比例）；钱包扣 LOW
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
    // 附属年贡：中型（level=1）→ 800000；lastTributeYear 更新；
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
    // 自动拒绝：filter {1} → 1 灵根 recruit 移除、2 灵根保留、
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
    // 商人刷新机会：lastGrant=0 → 首次授予；差值 30 → 再授；差值 29 跳过
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
    ecs::World world;   // E2 残留：临时实体集（首调惰性装配）
    // 年度老化：deathYears<=currentYear-1 移除；未来死亡保留
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

    system::detail::processYearlyAging(st, /*currentYear=*/2, world);

    ASSERT_EQ(2u, st.disciples.size());
    EXPECT_EQ("2", st.disciples.materialize(0).id);
    EXPECT_EQ("3", st.disciples.materialize(1).id);
}

TEST(YearSettlementTest, Y1T1RecruitAgingAgesAndSanitizes) {
    ecs::World world;   // E2 残留：临时实体集（首调惰性装配）
    // 招募老化：age+1；超寿元移除；净化（损坏移除）
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

    system::detail::processRecruitAging(st, world);

    ASSERT_EQ(1u, st.gameData.recruitList.size());
    EXPECT_EQ("r1", st.gameData.recruitList[0].id);
    EXPECT_EQ(17, st.gameData.recruitList[0].age);
}

TEST(YearSettlementTest, Y1T2SectDisciplesAgingFiltersOverMaxAge) {
    // AI 弟子老化：非玩家宗门 age+1 + 超寿元过滤；玩家宗门不动
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
    // 联盟到期：startYear 5 年前 → 解散 + 成员宗门清 alliance 字段
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
    // 联盟好感过低（<80）自动解散；player 哨兵匹配
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
    // 好感衰减：玩家相关 + favor>80 + 距上次交互 ≥1 年 → 减 1 保底 80
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
    ecs::World world;   // E2 残留：临时实体集（首调惰性装配）
    // 哀悼期到期：griefEndYears 到期（>= currentYear）→ 置 -1
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

    system::detail::processGriefExpiry(st, /*currentYear=*/2, world);

    EXPECT_EQ(-1, st.disciples.griefEndYears[0]);
    EXPECT_EQ(5, st.disciples.griefEndYears[1]);
}

// ════════════════════════════════════════════════════════════════
// 年变中件黄金序列（思过释放 + 驻军轮换）
// ════════════════════════════════════════════════════════════════

TEST(YearSettlementTest, Y2T1ReflectionReleaseFreesAndBonuses) {
    ecs::World world;   // E2 残留：临时实体集（首调惰性装配）
    // 思过到期释放：IDLE + 道德/忠诚 +5（cap 200/100）+ 清思过字段；
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

    system::detail::processReflectionRelease(st, /*year=*/2, core->rng(), world);

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
    ecs::World world;   // E2 残留：临时实体集（首调惰性装配）
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

    system::detail::processReflectionRelease(st, /*year=*/2, core->rng(), world);

    const auto r = st.disciples.materialize(0);
    EXPECT_EQ(200, r.morality);                    // min(203, 200)
    EXPECT_EQ(100, r.loyalty);                     // min(203, 100)
}

TEST(YearSettlementTest, Y2T1ReflectionReleaseLowMoralityTriggersTheft) {
    ecs::World world;   // E2 残留：临时实体集（首调惰性装配）
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
    system::detail::processReflectionRelease(st, /*year=*/2, core->rng(), world);
    const int64_t after = core->rng()
        .getRng(rng::RngPartition::kSystem)
        .snapshot();

    EXPECT_NE(before, after) << "低道德思过释放必须触发 SYSTEM 偷盗判定抽取";
    EXPECT_EQ("IDLE", st.disciples.materialize(0).status);
}

TEST(YearSettlementTest, Y2T1GarrisonRotationFillsOccupiedSlots) {
    // 驻军轮换：玩家宗门 + AI 占领宗门（occupier ai-1）+ 12 存活弟子
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
    // 秘境年变刷新：冷却满（year - cooldown >= 50）→ 生成（SECRET_REALM
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
// 年变招募刷新黄金序列
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
// 弟子老化死亡链黄金序列
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

// ════════════════════════════════════════════════════════════════
// AI 宗门交易列表刷新黄金序列
// 局部种子确定性 RNG（sectId.hashCode()+year）——**零分区 RNG 消耗**，
// 每用例断言全部分区快照不变（抽取次数与顺序锁定的最强形式）。
// id/itemId 为镜像生成字段（静态计数器自增），断言排除具体 id 值。
// ════════════════════════════════════════════════════════════════

namespace {

/// 构造 AI 宗门 + 详情（level 任意；tradeItems 可空）
void addAiSect(state::GameState& st, const std::string& id, const std::string& name,
               int32_t tradeLastRefreshYear, std::vector<state::MerchantItem> items) {
    state::WorldSect sect;
    sect.id = id;
    sect.name = name;
    sect.isPlayerSect = false;
    st.gameData.worldMapSects.push_back(sect);
    state::SectDetail detail;
    detail.sectId = id;
    detail.tradeLastRefreshYear = tradeLastRefreshYear;
    detail.tradeItems = std::move(items);
    st.gameData.sectDetails[id] = std::move(detail);
}

/// 断言两交易列表除 id/itemId 外逐字段一致（确定性验证）
void expectTradeItemsEquivalent(const std::vector<state::MerchantItem>& a,
                                const std::vector<state::MerchantItem>& b) {
    ASSERT_EQ(a.size(), b.size());
    for (std::size_t i = 0; i < a.size(); ++i) {
        EXPECT_EQ(a[i].name, b[i].name) << "idx " << i;
        EXPECT_EQ(a[i].type, b[i].type) << "idx " << i;
        EXPECT_EQ(a[i].rarity, b[i].rarity) << "idx " << i;
        EXPECT_EQ(a[i].price, b[i].price) << "idx " << i;
        EXPECT_EQ(a[i].quantity, b[i].quantity) << "idx " << i;
        EXPECT_EQ(a[i].obtainedYear, b[i].obtainedYear) << "idx " << i;
        EXPECT_EQ(a[i].obtainedMonth, b[i].obtainedMonth) << "idx " << i;
        // grade 为 optional——分开断言（避免 GTest 对 optional 的打印依赖）
        EXPECT_EQ(a[i].grade.has_value(), b[i].grade.has_value()) << "idx " << i;
        if (a[i].grade.has_value() && b[i].grade.has_value()) {
            EXPECT_EQ(*a[i].grade, *b[i].grade) << "idx " << i;
        }
    }
}

}  // namespace

TEST(YearSettlementTest, Y4aT2SectTradeRefreshGeneratesTwentyItems) {
    // 距上次刷新满 3 年 → 生成 20 条交易物品 + tradeLastRefreshYear 更新；
    // 同 (sectId, year) 确定性重放非 id 字段逐字段一致；零分区 RNG 消耗
    const int64_t seed = 42;
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.gameYear = 10;
    addAiSect(st, "ai-1", "青云宗", /*tradeLastRefreshYear=*/0, {});

    system::detail::refreshAllSectTrades(st, 10);

    ASSERT_EQ(1u, st.gameData.sectDetails.size());
    const auto& detail = st.gameData.sectDetails.at("ai-1");
    EXPECT_EQ(10, detail.tradeLastRefreshYear);
    ASSERT_EQ(20u, detail.tradeItems.size());
    // 品阶降序（sortedByDescending 稳定排序）
    for (std::size_t i = 1; i < detail.tradeItems.size(); ++i) {
        EXPECT_GE(detail.tradeItems[i - 1].rarity, detail.tradeItems[i].rarity);
    }
    // 名称去重
    std::set<std::string> names;
    for (const auto& item : detail.tradeItems) {
        EXPECT_TRUE(names.insert(item.name).second) << "重复名 " << item.name;
    }
    // 确定性：同 (sectId, year) 重放 → 非 id 字段逐字段一致
    const auto replay = system::detail::generateSectTradeItems(10, "ai-1");
    expectTradeItemsEquivalent(detail.tradeItems, replay);
    // 零分区 RNG 消耗
    expectAllPartitionsUnchanged(*core, seed);
}

TEST(YearSettlementTest, Y4aT2SectTradeRefreshSkipsWhenIntervalNotMet) {
    // 距上次刷新 2 年（<3）且列表非空 → 不刷新（原 items 保留）
    auto core = makeCore(42);
    auto& st = core->state();
    state::MerchantItem existing;
    existing.id = "keep";
    existing.name = "旧物";
    existing.type = "equipment";
    existing.rarity = 1;
    addAiSect(st, "ai-1", "青云宗", /*tradeLastRefreshYear=*/8, {existing});

    system::detail::refreshAllSectTrades(st, 10);

    ASSERT_EQ(1u, st.gameData.sectDetails.size());
    const auto& detail = st.gameData.sectDetails.at("ai-1");
    EXPECT_EQ(8, detail.tradeLastRefreshYear);              // 未推进
    ASSERT_EQ(1u, detail.tradeItems.size());
    EXPECT_EQ("旧物", detail.tradeItems[0].name);           // 原样保留
}

TEST(YearSettlementTest, Y4aT2SectTradeRefreshSkipsPlayerAndMissingDetails) {
    // 玩家宗门跳过 + 无详情宗门跳过 + 列表空兜底刷新（lastRefresh 今年但空）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 10;
    // 玩家宗门（有详情——不应刷新）
    {
        state::WorldSect player;
        player.id = "player";
        player.name = "玩家";
        player.isPlayerSect = true;
        st.gameData.worldMapSects.push_back(player);
        state::SectDetail pd;
        pd.sectId = "player";
        pd.tradeLastRefreshYear = 0;
        st.gameData.sectDetails["player"] = std::move(pd);
    }
    // 无详情宗门（不应刷新）
    {
        state::WorldSect noDetail;
        noDetail.id = "ai-x";
        noDetail.name = "无详情";
        st.gameData.worldMapSects.push_back(noDetail);
    }
    // AI 宗门：列表空兜底（tradeLastRefreshYear 今年但 items 空 → 刷新）
    addAiSect(st, "ai-1", "青云宗", /*tradeLastRefreshYear=*/10, {});

    system::detail::refreshAllSectTrades(st, 10);

    // 玩家宗门未刷新
    EXPECT_EQ(0, st.gameData.sectDetails.at("player").tradeLastRefreshYear);
    EXPECT_TRUE(st.gameData.sectDetails.at("player").tradeItems.empty());
    // AI 宗门空列表兜底刷新
    EXPECT_EQ(10, st.gameData.sectDetails.at("ai-1").tradeLastRefreshYear);
    ASSERT_EQ(20u, st.gameData.sectDetails.at("ai-1").tradeItems.size());
    // 无详情宗门未新增详情
    EXPECT_EQ(0u, st.gameData.sectDetails.count("ai-x"));
}

TEST(YearSettlementTest, Y4aT2SectTradeRefreshEmptyDetailsNoOp) {
    // sectDetails 空 → 零效果（无异常）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 10;
    state::WorldSect sect;
    sect.id = "ai-1";
    sect.name = "青云宗";
    st.gameData.worldMapSects.push_back(sect);

    system::detail::refreshAllSectTrades(st, 10);

    EXPECT_TRUE(st.gameData.sectDetails.empty());
}

TEST(YearSettlementTest, Y4aT2SectTradeGenerateHandlesAllTypes) {
    // 全 7 类型生成路径覆盖：固定 (sectId, year) 下非空列表应覆盖
    // equipment/manual/pill/material/herb/seed/spiritStone 中至少常见类型
    //（20 条目 × 7 类型随机——统计上绝大多数类型出现；防御断言：
    // 生成无异常 + 类型字符串合法 + 品阶/价格/数量合理）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 500;   // 高年份 → 品阶上限 6（灵石可出）

    const auto items = system::detail::generateSectTradeItems(500, "ai-1");
    ASSERT_EQ(20u, items.size());
    static const std::set<std::string> kTypes = {
        "equipment", "manual", "pill", "material", "herb", "seed", "spiritStone"};
    for (const auto& item : items) {
        EXPECT_TRUE(kTypes.count(item.type) == 1) << "非法类型 " << item.type;
        EXPECT_GE(item.rarity, 1);
        EXPECT_LE(item.rarity, 6);
        EXPECT_GE(item.price, 1);
        EXPECT_GE(item.quantity, 1);
        EXPECT_EQ(500, item.obtainedYear);
        EXPECT_EQ(1, item.obtainedMonth);
        if (item.type == "pill") {
            EXPECT_TRUE(item.grade.has_value());
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 商人收购刷新黄金序列
// SYSTEM 分区消费（数量 1×nextInt(9) + 每 item 品阶/选池/库存/grade/价格）。
// id/itemId 为镜像生成字段（静态计数器自增），断言排除具体 id 值。
// ════════════════════════════════════════════════════════════════

TEST(YearSettlementTest, Y4bT2MerchantAcquisitionGeneratesAndWrites) {
    // 收购刷新：1..9 条 + 写回年份；SYSTEM 分区被消耗；同种子重放非 id
    // 字段逐字段一致（确定性）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 10;
    const auto sysBefore =
        core->rng().getRng(rng::RngPartition::kSystem).snapshot();

    system::detail::refreshMerchantAcquisition(
        st, core->rng().getRng(rng::RngPartition::kSystem), 10, 1);

    EXPECT_EQ(10, st.gameData.merchantAcquisitionLastRefreshYear);
    ASSERT_FALSE(st.gameData.merchantAcquisitionItems.empty());
    EXPECT_LE(st.gameData.merchantAcquisitionItems.size(), 9u);
    for (const auto& item : st.gameData.merchantAcquisitionItems) {
        EXPECT_GE(item.rarity, 1);
        EXPECT_LE(item.rarity, 6);
        EXPECT_GE(item.price, 1);
        EXPECT_GE(item.quantity, 1);
        EXPECT_EQ(10, item.obtainedYear);
        EXPECT_EQ(1, item.obtainedMonth);
    }
    // SYSTEM 分区被消耗（快照变化）
    const auto sysAfter =
        core->rng().getRng(rng::RngPartition::kSystem).snapshot();
    EXPECT_NE(sysBefore, sysAfter);
    // 确定性：同种子（SYSTEM = seed+3）重放 → 非 id 字段逐字段一致
    auto rngA = rng::DeterministicRng::fromSeed(42 + 3);
    auto rngB = rng::DeterministicRng::fromSeed(42 + 3);
    state::GameState stA;
    state::GameState stB;
    stA.gameData.gameYear = 10;
    stB.gameData.gameYear = 10;
    system::detail::refreshMerchantAcquisition(stA, rngA, 10, 1);
    system::detail::refreshMerchantAcquisition(stB, rngB, 10, 1);
    expectTradeItemsEquivalent(stA.gameData.merchantAcquisitionItems,
                               stB.gameData.merchantAcquisitionItems);
}

TEST(YearSettlementTest, Y4bT2MerchantAcquisitionMergeWeightedPrice) {
    // mergeMerchantItems：同 key（name:type[:grade]）数量相加 + 加权平均价
    //（Int 除法）；保持首次出现序；异 key 保留
    state::MerchantItem a;
    a.name = "聚灵丹";
    a.type = "pill";
    a.grade = "中品";
    a.quantity = 2;
    a.price = 100;
    state::MerchantItem b;
    b.name = "聚灵丹";
    b.type = "pill";
    b.grade = "中品";
    b.quantity = 3;
    b.price = 150;
    state::MerchantItem c;
    c.name = "精铁剑";
    c.type = "equipment";
    c.quantity = 1;
    c.price = 4000;
    std::vector<state::MerchantItem> items = {a, b, c};
    const auto merged = system::detail::mergeMerchantItems(items);
    ASSERT_EQ(2u, merged.size());
    EXPECT_EQ("聚灵丹", merged[0].name);       // 首次出现序（非字典序）
    EXPECT_EQ(5, merged[0].quantity);
    EXPECT_EQ(130, merged[0].price);           // (100*2+150*3)/5 = 130
    EXPECT_EQ("精铁剑", merged[1].name);
    EXPECT_EQ(1, merged[1].quantity);
    EXPECT_EQ(4000, merged[1].price);
}

TEST(YearSettlementTest, Y4bT2MerchantPoolsCoverAllCategories) {
    // buildMerchantItemPools：六大类 + 灵石入池；rarityMap/priceMap 覆盖；
    // 丹药池仅 MEDIUM 品阶（名去重）
    const auto pools = system::detail::buildMerchantItemPools();
    int total = 0;
    for (const auto& pool : pools.poolByRarity) {
        total += static_cast<int>(pool.size());
    }
    EXPECT_GT(total, 0);
    EXPECT_TRUE(pools.rarityMap.count("精铁剑") == 1);    // 装备入池
    EXPECT_TRUE(pools.rarityMap.count("中品灵石") == 1);
    EXPECT_EQ(10000L, pools.priceMap.at("中品灵石"));     // RATIO
    bool hasPill = false;
    for (const auto& pool : pools.poolByRarity) {
        for (const auto& e : pool) {
            if (e.type == "pill") hasPill = true;
        }
    }
    EXPECT_TRUE(hasPill);
}

TEST(YearSettlementTest, Y4bT2CreatePillItemAppliesGradePrice) {
    // createMerchantItem 丹药：grade 随机（1×nextDouble）+ 价格 = basePrice
    // × multiplier 后价格波动（1×nextDouble）；库存 1×nextInt
    const auto pools = system::detail::buildMerchantItemPools();
    std::optional<system::detail::PoolEntry> pillEntry;
    for (const auto& pool : pools.poolByRarity) {
        for (const auto& e : pool) {
            if (e.type == "pill") { pillEntry = e; break; }
        }
        if (pillEntry.has_value()) break;
    }
    ASSERT_TRUE(pillEntry.has_value());
    auto rng = rng::DeterministicRng::fromSeed(12345);
    const auto item =
        system::detail::createMerchantItem(*pillEntry, pools, rng, 10, 1);
    EXPECT_EQ(pillEntry->name, item.name);
    EXPECT_EQ("pill", item.type);
    EXPECT_TRUE(item.grade.has_value());
    EXPECT_GE(item.price, 1);
    EXPECT_GE(item.quantity, 1);
    EXPECT_EQ(10, item.obtainedYear);
    EXPECT_EQ(1, item.obtainedMonth);
}

// ════════════════════════════════════════════════════════════════
// AI 宗门周期性招募黄金序列
// AI 独立分区 RNG（种子 systemSeed + AI_SECT.id(6)×31337——不入 rngStates
// 分区；断言 AI RNG 快照变化/不变 + SYSTEM 等分区零扰动）。
// Disciple.id 为镜像生成字段（静态计数器自增），断言排除具体 id 值。
// ════════════════════════════════════════════════════════════════

namespace {

/// 构造 AI 宗门 + 现有弟子池（aiSectDisciples 顶层条目）
void addAiSectWithDisciples(state::GameState& st, const std::string& id,
                            const std::string& name, int32_t level,
                            bool isPlayerSect, bool isPlayerOccupied,
                            const std::string& occupierSectId,
                            std::vector<state::Disciple> disciples) {
    state::WorldSect sect;
    sect.id = id;
    sect.name = name;
    sect.level = level;
    sect.isPlayerSect = isPlayerSect;
    sect.isPlayerOccupied = isPlayerOccupied;
    sect.occupierSectId = occupierSectId;
    st.gameData.worldMapSects.push_back(sect);
    st.aiSectDisciples[id] = std::move(disciples);
}

/// AI 测试弟子（炼气一层，最小字段）
state::Disciple makeAiDisciple(const std::string& id, const std::string& name) {
    state::Disciple d;
    d.id = id;
    d.name = name;
    d.surname = "测试";
    d.gender = "male";
    d.realm = 9;
    d.realmLayer = 1;
    d.isAlive = true;
    d.age = 30;
    return d;
}

/// 断言 AI 弟子全字段合法 + 等级装备/功法数量（炼气→凡品 rarity1 池非空）
void expectAiRecruitValid(const state::Disciple& d, int32_t sectLevel) {
    EXPECT_FALSE(d.name.empty());
    EXPECT_TRUE(d.gender == "male" || d.gender == "female");
    EXPECT_EQ(9, d.realm);                  // 新弟子固定炼气
    EXPECT_EQ(1, d.realmLayer);
    EXPECT_EQ(0.0, d.cultivation);
    EXPECT_TRUE(d.isAlive);
    EXPECT_EQ("outer", d.discipleType);
    EXPECT_GE(d.age, 16);
    EXPECT_LE(d.age, 29);
    EXPECT_GE(d.lifespan, 1);
    EXPECT_LE(d.lifespan, 80);
    // 灵根（英文 key 逗号串 1..5）
    EXPECT_FALSE(d.spiritRootType.empty());
    // 技能/方差在界内（GameConfig.Disciple.SKILL_MAX=200 / MAX_LOYALTY=100）
    EXPECT_GE(d.intelligence, 1);
    EXPECT_LE(d.intelligence, 200);
    EXPECT_GE(d.loyalty, 1);
    EXPECT_LE(d.loyalty, 100);
    // 装备/功法数量按宗门等级（炼气凡品池非空——应有满配）
    const int32_t equipCount =
        static_cast<int32_t>(!d.weaponId.empty()) + (!d.armorId.empty()) +
        (!d.bootsId.empty()) + (!d.accessoryId.empty());
    EXPECT_LE(equipCount, 4);
    EXPECT_GT(equipCount, 0) << "弟子 " << d.name << " 无装备";
    EXPECT_FALSE(d.manualIds.empty()) << "弟子 " << d.name << " 无功法";
}

}  // namespace

TEST(YearSettlementTest, Y4cT2AiSectRecruitRunsWhenIntervalMet) {
    // 差值判据满足（5-0>=3）→ 生成 1..5 名新弟子入自身池（无占领）；
    // lastAiSectRecruitYear 更新；AI RNG 消耗；SYSTEM 等分区零扰动
    const int64_t seed = 42;
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.gameYear = 5;
    addAiSectWithDisciples(st, "ai-1", "青云宗", /*level=*/1,
                           /*isPlayerSect=*/false, /*isPlayerOccupied=*/false,
                           /*occupierSectId=*/"", {makeAiDisciple("a1", "青云长老")});
    const auto aiBefore = core->aiRng().snapshot();
    const auto sysBefore =
        core->rng().getRng(rng::RngPartition::kSystem).snapshot();

    system::detail::runSectRecruitmentIfDue(st, core->aiRng(), 5);

    EXPECT_EQ(5, st.gameData.lastAiSectRecruitYear);
    ASSERT_EQ(1u, st.aiSectDisciples.count("ai-1"));
    const auto& disciples = st.aiSectDisciples.at("ai-1");
    ASSERT_GT(disciples.size(), 1u);        // 原 1 + 新增 1..5
    EXPECT_LE(disciples.size(), 6u);        // 原 1 + 最多 5
    for (std::size_t i = 1; i < disciples.size(); ++i) {
        expectAiRecruitValid(disciples[i], /*sectLevel=*/1);
    }
    // AI RNG 被消耗
    EXPECT_NE(aiBefore, core->aiRng().snapshot());
    // SYSTEM 等分区零扰动（差值判据路径不消费分区 RNG）
    EXPECT_EQ(sysBefore,
              core->rng().getRng(rng::RngPartition::kSystem).snapshot());
}

TEST(YearSettlementTest, Y4cT2AiSectRecruitSkipsWhenIntervalNotMet) {
    // 差值判据不满足（5-4<3）→ 零效果零消费（AI RNG 快照不变）
    const int64_t seed = 42;
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.gameYear = 5;
    st.gameData.lastAiSectRecruitYear = 4;
    addAiSectWithDisciples(st, "ai-1", "青云宗", 1, false, false, "",
                           {makeAiDisciple("a1", "青云长老")});
    const auto aiBefore = core->aiRng().snapshot();

    system::detail::runSectRecruitmentIfDue(st, core->aiRng(), 5);

    EXPECT_EQ(4, st.gameData.lastAiSectRecruitYear);   // 未推进
    ASSERT_EQ(1u, st.aiSectDisciples.at("ai-1").size());  // 未新增
    EXPECT_EQ(aiBefore, core->aiRng().snapshot());        // AI RNG 零消费
}

TEST(YearSettlementTest, Y4cT2AiSectRecruitPlayerOccupiedRoutesToRecruitList) {
    // 玩家占领（isPlayerOccupied）→ 新弟子入 recruitList（不截断）
    const int64_t seed = 42;
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.gameYear = 5;
    st.gameData.recruitList = {makeAiDisciple("r1", "原招募")};
    addAiSectWithDisciples(st, "ai-1", "青云宗", 0, false,
                           /*isPlayerOccupied=*/true, "",
                           {makeAiDisciple("a1", "青云长老")});

    system::detail::runSectRecruitmentIfDue(st, core->aiRng(), 5);

    EXPECT_EQ(5, st.gameData.lastAiSectRecruitYear);
    // 原招募池不动 + 新增 1..5 名（玩家占领路由）
    ASSERT_GT(st.gameData.recruitList.size(), 1u);
    EXPECT_LE(st.gameData.recruitList.size(), 6u);
    EXPECT_EQ("原招募", st.gameData.recruitList[0].name);
    // 被占领宗门自身池不再追加
    EXPECT_EQ(1u, st.aiSectDisciples.at("ai-1").size());
}

TEST(YearSettlementTest, Y4cT2AiSectRecruitOccupierRoutesToOccupierPool) {
    // 被其他 AI 宗门占领（occupierSectId）→ 新弟子入占领者池（truncate）
    const int64_t seed = 42;
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.gameYear = 5;
    addAiSectWithDisciples(st, "ai-1", "青云宗", 1, false, false,
                           /*occupierSectId=*/"ai-2",
                           {makeAiDisciple("a1", "青云长老")});
    addAiSectWithDisciples(st, "ai-2", "紫霄宗", 1, false, false, "",
                           {makeAiDisciple("a2", "紫霄长老")});

    system::detail::runSectRecruitmentIfDue(st, core->aiRng(), 5);

    EXPECT_EQ(5, st.gameData.lastAiSectRecruitYear);
    // ai-1 自身池不增长（新弟子去 ai-2）
    EXPECT_EQ(1u, st.aiSectDisciples.at("ai-1").size());
    // ai-2 池 = 原 1 + 新增 1..5
    const auto& occ = st.aiSectDisciples.at("ai-2");
    ASSERT_GT(occ.size(), 1u);
    EXPECT_LE(occ.size(), 6u);
}

TEST(YearSettlementTest, Y4cT2AiDiscipleGenerationDeterministic) {
    // 同种子重放 generateYearlyAiRecruits → 非 id 字段逐字段一致（确定性）
    auto rngA = rng::DeterministicRng::fromSeed(42 + 6 * 31337);
    auto rngB = rng::DeterministicRng::fromSeed(42 + 6 * 31337);
    const std::vector<state::Disciple> existing = {makeAiDisciple("a1", "青云长老")};

    const auto recruitsA =
        system::detail::generateYearlyAiRecruits(rngA, "青云宗", existing, 1);
    const auto recruitsB =
        system::detail::generateYearlyAiRecruits(rngB, "青云宗", existing, 1);

    ASSERT_EQ(recruitsA.size(), recruitsB.size());
    for (std::size_t i = 0; i < recruitsA.size(); ++i) {
        const auto& a = recruitsA[i];
        const auto& b = recruitsB[i];
        EXPECT_EQ(a.name, b.name) << "idx " << i;
        EXPECT_EQ(a.surname, b.surname) << "idx " << i;
        EXPECT_EQ(a.gender, b.gender) << "idx " << i;
        EXPECT_EQ(a.spiritRootType, b.spiritRootType) << "idx " << i;
        EXPECT_EQ(a.age, b.age) << "idx " << i;
        EXPECT_EQ(a.lifespan, b.lifespan) << "idx " << i;
        EXPECT_EQ(a.hpVariance, b.hpVariance) << "idx " << i;
        EXPECT_EQ(a.intelligence, b.intelligence) << "idx " << i;
        EXPECT_EQ(a.loyalty, b.loyalty) << "idx " << i;
        EXPECT_EQ(a.aptitude, b.aptitude) << "idx " << i;
        EXPECT_EQ(a.talentIds, b.talentIds) << "idx " << i;
        EXPECT_EQ(a.physiqueIds, b.physiqueIds) << "idx " << i;
        EXPECT_EQ(a.affixIds, b.affixIds) << "idx " << i;
        EXPECT_EQ(a.portraitRes, b.portraitRes) << "idx " << i;
        EXPECT_EQ(a.weaponId, b.weaponId) << "idx " << i;
        EXPECT_EQ(a.manualIds, b.manualIds) << "idx " << i;
        // 名字去重（含现有弟子）
        for (std::size_t j = 0; j < i; ++j) {
            EXPECT_NE(a.name, recruitsA[j].name);
        }
    }
}

}  // namespace
