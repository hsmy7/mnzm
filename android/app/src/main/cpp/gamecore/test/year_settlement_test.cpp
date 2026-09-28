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
}

TEST(YearSettlementTest, AnnualSalaryPaidWithLedger) {
    // realm=9 年俸 500（enabled）× 2 弟子；灵石充足；非开源节流
    // → spiritStones -1000、每人袋 +500、paidCount+1
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
    }
}

TEST(YearSettlementTest, AnnualSalaryFrugalityReducesPay) {
    // 开源节流：发放额 ×0.7
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
    st.disciples.appendDisciple(d);

    core->advancePhases(1);

    // 扣减 Σ原额 500；发放 round(500×0.7)=350
    EXPECT_EQ(9500L, st.gameData.spiritStones);
    EXPECT_EQ(350L, st.disciples.materialize(0).storageBagSpiritStones);
    EXPECT_EQ(1, st.disciples.materialize(0).salaryPaidCount);
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
    // 场景无 sectDetails/worldMapSects → 交易刷新零效果。
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
    st.gameData.annualTotalIncome = 10000L;
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
    st.gameData.annualTotalIncome = 0L;
    system::detail::processYearlyTribute(st);
    EXPECT_EQ(10000L, st.gameData.spiritStones);
}

TEST(YearSettlementTest, Y1T1VassalTributePositiveIncomeUsesMin) {
    // income=1 → tribute=max(0.5→0, 1)=1（>0 时保底 1）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.suzerainSectId = "ai-1";
    st.gameData.annualTotalIncome = 1L;
    st.gameData.spiritStones = 10L;
    system::detail::processYearlyTribute(st);
    EXPECT_EQ(9L, st.gameData.spiritStones);
}

// ── 缺陷 #1 修复（方案 §9.1）：思过到期释放 reflectionRelease ─────────

TEST(YearSettlementTest, Y1T1ReflectionReleaseReleasesExpiredReflecting) {
    // REFLECTING + endYear 已到 → IDLE + statusData 两键清 + 道德 +5
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d;
    d.id = "1";
    d.name = "思过弟子";
    d.realm = 9;
    d.isAlive = true;
    st.disciples.appendDisciple(d);
    st.disciples.statuses[0] = "REFLECTING";
    st.disciples.statusData[0]["reflectionStartYear"] = "8";
    st.disciples.statusData[0]["reflectionEndYear"] = "10";
    st.disciples.moralities[0] = 50;

    system::detail::processReflectionRelease(st, 10);

    EXPECT_EQ("IDLE", st.disciples.statuses[0]);
    EXPECT_EQ(st.disciples.statusData[0].end(),
              st.disciples.statusData[0].find("reflectionStartYear"));
    EXPECT_EQ(st.disciples.statusData[0].end(),
              st.disciples.statusData[0].find("reflectionEndYear"));
    EXPECT_EQ(55, st.disciples.moralities[0]);
}

TEST(YearSettlementTest, Y1T1ReflectionReleaseSkipsUnexpiredDeadOrKeyless) {
    // 未到期 / 已死 / 缺 endYear 键 / 非数字键 → 原状不动
    auto core = makeCore(42);
    auto& st = core->state();
    for (const char* id : {"1", "2", "3", "4"}) {
        Disciple d;
        d.id = id;
        d.name = std::string("弟子") + id;
        d.realm = 9;
        d.isAlive = true;
        st.disciples.appendDisciple(d);
        st.disciples.statuses[st.disciples.size() - 1] = "REFLECTING";
        st.disciples.moralities[st.disciples.size() - 1] = 40;
    }
    st.disciples.statusData[0]["reflectionEndYear"] = "11";   // 未到期
    st.disciples.isAlive[1] = 0;                              // 已死
    st.disciples.statusData[2]["reflectionEndYear"] = "10";
    st.disciples.statusData[2].erase("reflectionEndYear");    // 缺键
    st.disciples.statusData[3]["reflectionEndYear"] = "abc";  // 非数字

    system::detail::processReflectionRelease(st, 10);

    for (std::size_t row = 0; row < st.disciples.size(); ++row) {
        EXPECT_EQ("REFLECTING", st.disciples.statuses[row]) << row;
        EXPECT_EQ(40, st.disciples.moralities[row]) << row;
    }
}

TEST(YearSettlementTest, Y1T1ReflectionReleaseMoralityClampAtSkillMax) {
    // 道德 198 + 5 → clamp 200（GameConfig.Disciple.SKILL_MAX）
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d;
    d.id = "1";
    d.name = "临近圆满";
    d.realm = 9;
    d.isAlive = true;
    st.disciples.appendDisciple(d);
    st.disciples.statuses[0] = "REFLECTING";
    st.disciples.statusData[0]["reflectionEndYear"] = "10";
    st.disciples.moralities[0] = 198;

    system::detail::processReflectionRelease(st, 10);

    EXPECT_EQ(200, st.disciples.moralities[0]);
    EXPECT_EQ("IDLE", st.disciples.statuses[0]);
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

// ════════════════════════════════════════════════════════════════
// 年变中件黄金序列（驻军轮换）
// ════════════════════════════════════════════════════════════════

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

}  // namespace
