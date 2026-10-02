// ============================================================
// month_settlement_test — 月变结算钩子黄金序列守护
//
// 守护目标：固定种子 + 固定状态 → SettlementEngine.onMonthChange
// （runMonthSettlement）跨月推进 → 断言八步事务各域字段值逐位符合手算期望。
//
// 覆盖：政策月度扣除（含不足自动关闭 / 广纳门徒 36 月冷却）/ 政策月度忠诚·
// 道德效果 / 住所忠诚 / 丹药持续效果月衰减 / 灵田收获种子 roll
// （SYSTEM RNG 审计 + 续种匹配）/ 世界关卡清理与妖兽移动（EXPLORATION
// RNG 审计 + 边界钳制）/ 灵矿月产差分结算与矿工忠诚衰减 / 游戏结束判定 /
// 招募计数归零 + SYSTEM 分区抽取顺序锁（收获 roll 抽取次数与序）。
//
// RNG 审计方法（同 phase_settlement_test）：SYSTEM 分区播种规则
// fromSeed(seed + partitionId)（kSystem=3）、EXPLORATION 为 seed+2——测试用
// 独立 DeterministicRng 预演同序列，断言结算后的分区快照精确等于
// "按执行序连续调用"后的快照，同时锁定抽取次数与顺序（对拍命门）。
// ============================================================

#include "gtest/gtest.h"

#include <cmath>
#include <functional>
#include <memory>
#include <stdexcept>

#include "gamecore/game_core.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/exploration.h"
#include "gamecore/system/government.h"
#include "gamecore/system/month_settlement.h"

namespace {

using namespace gamecore;
using gamecore::state::Disciple;
using gamecore::state::GameData;
using gamecore::state::GameState;
using gamecore::state::GridBuildingData;
using gamecore::state::ResidenceSlot;
using gamecore::state::SpiritFieldPlant;
using gamecore::state::SpiritMineSlot;
using gamecore::state::WorldLevel;

/// 教化之道道德上限（GameConfig.PolicyConfig.MORAL_EDUCATION_MAX）
constexpr int32_t kMoralEducationMax = 70;
/// 丹道激励月耗 / 功法研习月耗（GameConfig.PolicyConfig）
constexpr int64_t kAlchemyIncentiveMonthly = 3000;
constexpr int64_t kManualResearchMonthly = 4000;
/// 每矿工基础产出（SPIRIT_MINE_BASE_OUTPUT_PER_MINER）
constexpr int32_t kSpiritMineBaseOutputPerMiner = 170;
/// 地图边界钳制常量（GameConfig.WorldMap；exploration.h 同源单一定义）
constexpr float kMinBound = 34.0f;
constexpr float kMaxXBound = 1698.0f - 34.0f;
constexpr float kMaxYBound = 926.0f - 34.0f;

/// 构建已初始化 GameCore（钩子已注册），种子固定
std::unique_ptr<GameCore> makeCore(int64_t seed) {
    auto core = std::unique_ptr<GameCore>(new GameCore(nullptr, nullptr));
    GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = seed;
    EXPECT_TRUE(core->initialize(config));
    return core;
}

/// 填充一个最小存活弟子（炼气一层）
Disciple baseDisciple(const std::string& id) {
    Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.realm = 9;
    d.realmLayer = 1;
    d.isAlive = true;
    d.spiritRootType = "metal";
    return d;
}

/// 将时间拨到月末下旬并推进一旬 → 触发一次月变钩子（等价生产 tick 跨月路径）
void crossMonth(std::unique_ptr<GameCore>& core) {
    auto& st = core->state();
    st.gameData.gamePhase = 2;
    core->advancePhases(1);
}

// ── 步骤 1：政策月度灵石扣除 ────────────────────────────────────────

TEST(MonthSettlementTest, PolicyCostsDeductAndAutoDisable) {
    // 丹道激励(3000)+功法研习(4000)，余额 5000：前者扣成功、后者不足自动关闭
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 5000;
    st.gameData.sectPolicies.alchemyIncentive = true;
    st.gameData.sectPolicies.manualResearch = true;

    const auto result = system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());

    EXPECT_FALSE(result.policyCosts.allPaid);
    ASSERT_EQ(1u, result.policyCosts.disabledPolicies.size());
    EXPECT_STREQ("功法研习", result.policyCosts.disabledPolicies[0].c_str());
    EXPECT_EQ(2000, st.gameData.spiritStones);          // 5000 - 3000
    EXPECT_TRUE(st.gameData.sectPolicies.alchemyIncentive);
    EXPECT_FALSE(st.gameData.sectPolicies.manualResearch);   // 自动关闭
}

TEST(MonthSettlementTest, PolicyCostsAllPaidWhenBalanceSufficient) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 100000;
    st.gameData.sectPolicies.alchemyIncentive = true;
    st.gameData.sectPolicies.manualResearch = true;

    const auto result = system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());

    EXPECT_TRUE(result.policyCosts.allPaid);
    EXPECT_TRUE(result.policyCosts.disabledPolicies.empty());
    EXPECT_EQ(100000 - kAlchemyIncentiveMonthly - kManualResearchMonthly,
              st.gameData.spiritStones);
}

// ── 步骤 2：政策月度道德效果 ──────────────────────────────────────

TEST(MonthSettlementTest, PolicyMonthlyEffectsMoralityGolden) {
    // 教化之道道德 68→69；上限钳制：道德 70 保持 70
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.sectPolicies.moralEducation = true;
    st.gameData.spiritStones = 1000000;   // 教化之道按弟子计费 100/人

    Disciple mid = baseDisciple("1");
    mid.morality = 68;
    Disciple capped = baseDisciple("2");
    capped.morality = kMoralEducationMax;
    st.disciples.appendDisciple(mid);
    st.disciples.appendDisciple(capped);

    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());

    EXPECT_EQ(69, st.disciples.materialize(0).morality);                 // 68 + 1
    EXPECT_EQ(kMoralEducationMax, st.disciples.materialize(1).morality); // 上限不再增长
}

// ── 步骤 7：丹药持续效果月衰减 ─────────────────────────────────────

TEST(MonthSettlementTest, PillDurationDecayGoldenSequence) {
    // duration=7：每月 -3 → 4 → 1 → 归零并清空全部 pill 加成组件
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.pillEffectDuration = 7;
    d.pillHpBonus = 30;
    d.pillCritRateBonus = 0.05;
    d.pillCultivationSpeedBonus = 0.2;
    d.activePillTypes = {"qiTonic"};
    st.disciples.appendDisciple(d);

    crossMonth(core);
    EXPECT_EQ(4, st.disciples.materialize(0).pillEffectDuration);
    EXPECT_EQ(30, st.disciples.materialize(0).pillHpBonus);       // 未归零不动组件

    crossMonth(core);
    EXPECT_EQ(1, st.disciples.materialize(0).pillEffectDuration);

    crossMonth(core);
    EXPECT_EQ(0, st.disciples.materialize(0).pillEffectDuration);
    EXPECT_EQ(0, st.disciples.materialize(0).pillHpBonus);
    EXPECT_DOUBLE_EQ(0.0, st.disciples.materialize(0).pillCritRateBonus);
    EXPECT_DOUBLE_EQ(0.0, st.disciples.materialize(0).pillCultivationSpeedBonus);
    EXPECT_TRUE(st.disciples.materialize(0).activePillTypes.empty());
}

// ── 步骤 4c：灵田收获（SYSTEM RNG 审计 + 续种） ────────────────────

/// 构建一块成熟灵田（plantYear/Month 相对当前月提前 1 个月；growTime=1）
SpiritFieldPlant maturePlant() {
    SpiritFieldPlant plant;
    plant.buildingInstanceId = "field-1";
    plant.seedId = "spiritGrass1Seed";
    plant.seedName = "聚灵草种";
    plant.growTime = 1;
    plant.expectedYield = 5;
    plant.plantYear = 1;
    plant.plantMonth = 1;      // 跨月到 2 月时 elapsed = 1 >= 1 成熟
    return plant;
}

TEST(MonthSettlementTest, SpiritFieldHarvestSeedRollAndClearGolden) {
    // 收获：灵草入库 5 + 种子奖励 nextInt(5)（roll>0 入仓）+ 计数字段；
    // 续种匹配要求 growTime 一致（模板 36 != 地块 1）→ 不续种清地（双端一致怪癖）
    const int64_t seed = 42;
    auto core = makeCore(seed);
    auto& st = core->state();
    GridBuildingData warehouse;            // 仓库容量来源
    warehouse.displayName = "仓库";
    warehouse.instanceId = "wh-1";
    st.gameData.placedBuildings.push_back(warehouse);
    st.gameData.spiritFieldPlants.push_back(maturePlant());

    auto probe = gamecore::rng::DeterministicRng::fromSeed(seed + 3);
    const int32_t roll = probe.nextInt(5);

    crossMonth(core);

    ASSERT_EQ(1u, st.herbs.size());
    EXPECT_STREQ("聚灵草", st.herbs[0].name.c_str());
    EXPECT_EQ(5, st.herbs[0].quantity);
    if (roll > 0) {
        ASSERT_EQ(1u, st.seeds.size());
        EXPECT_EQ(roll, st.seeds[0].quantity);
    } else {
        EXPECT_TRUE(st.seeds.empty());
    }
    // 地块已清空（无匹配种子可续种）
    ASSERT_EQ(1u, st.gameData.spiritFieldPlants.size());
    EXPECT_TRUE(st.gameData.spiritFieldPlants[0].seedId.empty());
    EXPECT_EQ(1, st.gameData.spiritFieldPlants[0].completionPhase);
    // 引导/年度计数
    EXPECT_EQ(1L, st.gameData.guideCounters["herbsHarvested"]);
    EXPECT_EQ(1, st.gameData.annualHerbCount);
    EXPECT_EQ(5, st.gameData.annualHerbBySource["spirit_field"]);
    // RNG 审计：SYSTEM 分区恰消耗一次 nextInt(5)
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kSystem).snapshot());
}

TEST(MonthSettlementTest, ImmaturePlantIsUntouchedZeroRng) {
    // 未成熟地块原样保留；SYSTEM 分区零抽取
    auto core = makeCore(42);
    auto& st = core->state();
    GridBuildingData warehouse;
    warehouse.displayName = "仓库";
    warehouse.instanceId = "wh-1";
    st.gameData.placedBuildings.push_back(warehouse);
    SpiritFieldPlant plant = maturePlant();
    plant.plantMonth = 2;                  // 跨月到 2 月时 elapsed = 0 < 1
    st.gameData.spiritFieldPlants.push_back(plant);

    crossMonth(core);

    ASSERT_EQ(1u, st.gameData.spiritFieldPlants.size());
    EXPECT_EQ(1, st.gameData.spiritFieldPlants[0].completionPhase);
    EXPECT_TRUE(st.herbs.empty());
    auto probe = gamecore::rng::DeterministicRng::fromSeed(42 + 3);
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kSystem).snapshot());
}

// ── 步骤 4e：世界关卡清理 + 妖兽移动（EXPLORATION RNG 审计） ───────

TEST(MonthSettlementTest, WorldLevelsCleanupMoveAndExplorationAudit) {
    // 过期/已击败清理；活跃妖兽极坐标移动 + 边界钳制；
    // allowRefresh=false 路径：lastRefreshMonth 不推进（无玩家宗门语义）
    const int64_t seed = 42;
    auto core = makeCore(seed);
    auto& st = core->state();

    WorldLevel active;
    active.id = "beast-a";
    active.type = "BEAST";
    active.x = 100.0f;
    active.y = 100.0f;
    active.expiryYear = 99;
    active.expiryMonth = 12;
    WorldLevel defeated = active;
    defeated.id = "beast-b";
    defeated.defeated = true;
    WorldLevel expired;
    expired.id = "cave-c";
    expired.type = "CAVE";
    expired.expiryYear = 1;
    expired.expiryMonth = 1;               // 跨月后 (year1,m2)：m>=expiryMonth 过期
    WorldLevel far = active;
    far.id = "beast-d";
    far.x = 1600.0f;
    far.y = 800.0f;
    st.gameData.worldLevels = {active, defeated, expired, far};

    // 预演 EXPLORATION 序列：剩余 [a, d] 各消耗 angle+dist 两次
    auto probe = gamecore::rng::DeterministicRng::fromSeed(seed + 2);
    const double aAngle = probe.nextDouble() * 2.0 * system::kPi;
    const double aDist = probe.nextDouble() * system::kBeastMoveDistance;
    const double dAngle = probe.nextDouble() * 2.0 * system::kPi;
    const double dDist = probe.nextDouble() * system::kBeastMoveDistance;

    crossMonth(core);

    ASSERT_EQ(2u, st.gameData.worldLevels.size());
    EXPECT_STREQ("beast-a", st.gameData.worldLevels[0].id.c_str());
    EXPECT_STREQ("beast-d", st.gameData.worldLevels[1].id.c_str());
    // 手算期望位置（float 口径 + 边界钳制，与 moveBeasts 表达式逐位一致）
    const float expAx = std::max(kMinBound, std::min(kMaxXBound,
        100.0f + static_cast<float>(std::cos(aAngle) * aDist)));
    const float expAy = std::max(kMinBound, std::min(kMaxYBound,
        100.0f + static_cast<float>(std::sin(aAngle) * aDist)));
    EXPECT_FLOAT_EQ(expAx, st.gameData.worldLevels[0].x);
    EXPECT_FLOAT_EQ(expAy, st.gameData.worldLevels[0].y);
    const float expDx = std::max(kMinBound, std::min(kMaxXBound,
        1600.0f + static_cast<float>(std::cos(dAngle) * dDist)));
    const float expDy = std::max(kMinBound, std::min(kMaxYBound,
        800.0f + static_cast<float>(std::sin(dAngle) * dDist)));
    EXPECT_FLOAT_EQ(expDx, st.gameData.worldLevels[1].x);
    EXPECT_FLOAT_EQ(expDy, st.gameData.worldLevels[1].y);
    // 本场景不触发刷新生成 → lastRefreshMonth 保持不变
    EXPECT_EQ(0, st.gameData.worldLevelLastRefreshMonth);
    // RNG 审计：EXPLORATION 恰消耗 4 次 nextDouble
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kExploration).snapshot());
}

// ── 步骤 8c：灵矿月产 ─────────────────────────────────────────────

TEST(MonthSettlementTest, SpiritMineProductionGolden) {
    // 乘区：base=170×1 矿 × (1+采矿0.2)×(1+执事0.1) = 224.4 → round 224；
    // 差分结算（delta=1 月）入账 + 引导计数 + lastSettledMonth 推进
    const int64_t seed = 42;
    auto core = makeCore(seed);
    auto& st = core->state();
    Disciple miner = baseDisciple("1");
    miner.mining = 80;                      // (80-70)×0.02 = 0.2
    miner.morality = 90;                    // 执事：(90-80)×0.01 = 0.1
    st.disciples.appendDisciple(miner);
    st.gameData.spiritStones = 0;           // 显式清零（模型默认开局 1000）

    SpiritMineSlot slot;
    slot.index = 0;
    slot.discipleId = "1";
    st.gameData.spiritMineSlots.push_back(slot);
    st.gameData.elderSlots.spiritMineDeaconDisciples.push_back(
        [&] {
            state::DirectDiscipleSlot deacon;
            deacon.index = 0;
            deacon.discipleId = "1";
            return deacon;
        }());
    st.gameData.spiritMineLastSettledMonth = 1 * 12 + 1;   // 年1月1 = 13

    constexpr int64_t kExpectedMonthlyRate = 224;   // round(170×1.32)

    crossMonth(core);
    EXPECT_EQ(1 * 12 + 2, st.gameData.spiritMineLastSettledMonth);
    EXPECT_EQ(kExpectedMonthlyRate, st.gameData.spiritStones);
    EXPECT_EQ(kExpectedMonthlyRate, st.gameData.guideCounters["miningOutput"]);

    crossMonth(core);
    EXPECT_EQ(2 * kExpectedMonthlyRate, st.gameData.spiritStones);

    crossMonth(core);
    EXPECT_EQ(3 * kExpectedMonthlyRate, st.gameData.spiritStones);
}

// ── 步骤 8e：游戏结束检查 ──────────────────────────────────────────

TEST(MonthSettlementTest, GameOverTriggersOnlyWhenNoSectControlled) {
    auto core = makeCore(42);
    auto& st = core->state();
    state::WorldSect playerSect;
    playerSect.id = "sect-p";
    playerSect.isPlayerSect = true;
    playerSect.occupierSectId = "ai-1";    // 本宗被占领
    st.gameData.worldMapSects.push_back(playerSect);

    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());
    EXPECT_TRUE(st.gameData.isGameOver);
}

TEST(MonthSettlementTest, GameOverNotTriggeredWhenPlayerSectFree) {
    auto core = makeCore(42);
    auto& st = core->state();
    state::WorldSect playerSect;
    playerSect.id = "sect-p";
    playerSect.isPlayerSect = true;        // 本宗自由 → 仍控制宗门
    st.gameData.worldMapSects.push_back(playerSect);

    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());
    EXPECT_FALSE(st.gameData.isGameOver);
}

TEST(MonthSettlementTest, GameOverNotJudgedWithoutPlayerSect) {
    auto core = makeCore(42);
    auto& st = core->state();
    state::WorldSect aiSect;
    aiSect.id = "ai-1";                    // 只有 AI 宗门 → 不判定
    st.gameData.worldMapSects.push_back(aiSect);

    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());
    EXPECT_FALSE(st.gameData.isGameOver);
}

// ── 步骤 8 子事件序 + SYSTEM 抽取顺序锁 ────────────────────────────

TEST(MonthSettlementTest, RecruitResetAndSystemDrawOrderLock) {
    // 组合守护：同一月变事务内 SYSTEM 分区抽取 = 灵田收获 roll
    //（Planting@214）；recruitCountThisMonth 归零子事件同步生效
    const int64_t seed = 42;
    auto core = makeCore(seed);
    auto& st = core->state();
    GridBuildingData warehouse;
    warehouse.displayName = "仓库";
    warehouse.instanceId = "wh-1";
    st.gameData.placedBuildings.push_back(warehouse);
    st.gameData.spiritFieldPlants.push_back(maturePlant());
    st.gameData.recruitCountThisMonth = 7;

    // 预演执行序：nextInt(5)（收获）
    auto probe = gamecore::rng::DeterministicRng::fromSeed(seed + 3);
    const int32_t roll = probe.nextInt(5);

    crossMonth(core);

    EXPECT_EQ(0, st.gameData.recruitCountThisMonth);
    // 顺序锁：若实现交换步骤顺序或增减抽取次数，快照必不等
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kSystem).snapshot());
    // 结果与预演序列逐位对应
    if (roll > 0) {
        ASSERT_EQ(1u, st.seeds.size());
        EXPECT_EQ(roll, st.seeds[0].quantity);
    }
}

// ── 回归守护 ───────────────────────────────────────────────────────

TEST(MonthSettlementTest, EmptyMonthChangeIsSafe) {
    // 空档跨月：全部步骤纯 no-op，仅时间推进（不得误改资源/事件/RNG）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 777;

    const auto before = core->rng().exportStates();
    crossMonth(core);

    EXPECT_EQ(777, st.gameData.spiritStones);
    EXPECT_TRUE(st.gameData.gameEventRecords.empty());
    // 无任何抽取点触发 → 全部分区快照保持
    EXPECT_EQ(before, core->rng().exportStates());
}

// ── S8 子事件 8：侦察信息过期清理────────────────────────

TEST(MonthSettlementTest, ScoutExpiryRemovesExpiredAndFlipsKnown) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 3;

    // scoutInfo：ai-1 过期(2/2)、ai-2 未过期(2/3 当月边界不算过期)、ai-3 过期(1/12)
    state::SectScoutInfo expired1;
    expired1.sectId = "ai-1"; expired1.sectName = "青岚宗";
    expired1.expiryYear = 2; expired1.expiryMonth = 2;
    expired1.disciples["5"] = 3;
    state::SectScoutInfo alive;
    alive.sectId = "ai-2"; alive.sectName = "赤水宗";
    alive.expiryYear = 2; alive.expiryMonth = 3;   // month > expiryMonth 为 false
    alive.resources["灵石"] = 42;
    state::SectScoutInfo expired2;
    expired2.sectId = "ai-3"; expired2.sectName = "黄泉宗";
    expired2.expiryYear = 1; expired2.expiryMonth = 12;
    st.gameData.scoutInfo["ai-1"] = expired1;
    st.gameData.scoutInfo["ai-2"] = alive;
    st.gameData.scoutInfo["ai-3"] = expired2;

    // sectDetails：ai-1 有明细（scoutInfo.sectId 非空 + 保留字段画像）；
    // ai-2 无明细（剩余条目应新建 SectDetail(sectId) 并刷新 scoutInfo）
    state::SectDetail detail1;
    detail1.sectId = "ai-1";
    detail1.scoutInfo = expired1;
    detail1.portraitRes = "sect_ai1";
    detail1.lastGiftYear = 1;
    st.gameData.sectDetails["ai-1"] = detail1;

    // worldMapSects：ai-1 已知（scout 过期 → isKnown 翻 false）、ai-2 保持已知
    state::WorldSect sect1; sect1.id = "ai-1"; sect1.isKnown = true;
    state::WorldSect sect2; sect2.id = "ai-2"; sect2.isKnown = true;
    st.gameData.worldMapSects.push_back(sect1);
    st.gameData.worldMapSects.push_back(sect2);

    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());

    // 过期条目移除、未过期保留
    ASSERT_EQ(1u, st.gameData.scoutInfo.size());
    ASSERT_EQ(1u, st.gameData.scoutInfo.count("ai-2"));
    EXPECT_EQ(42, st.gameData.scoutInfo.at("ai-2").resources.at("灵石"));
    // ① 剩余条目刷新明细（ai-2 无明细 → 新建）
    ASSERT_EQ(2u, st.gameData.sectDetails.size());
    EXPECT_EQ("ai-2", st.gameData.sectDetails.at("ai-2").sectId);
    EXPECT_EQ("ai-2", st.gameData.sectDetails.at("ai-2").scoutInfo.sectId);
    // ② 被移除条目：原明细 scoutInfo 清空、其余字段保留
    EXPECT_EQ("", st.gameData.sectDetails.at("ai-1").scoutInfo.sectId);
    EXPECT_EQ("sect_ai1", st.gameData.sectDetails.at("ai-1").portraitRes);
    EXPECT_EQ(1, st.gameData.sectDetails.at("ai-1").lastGiftYear);
    // ③ isKnown 翻转：ai-1 false、ai-2 true
    EXPECT_FALSE(st.gameData.worldMapSects[0].isKnown);
    EXPECT_TRUE(st.gameData.worldMapSects[1].isKnown);
}

TEST(MonthSettlementTest, ScoutExpiryNoOpWhenNothingExpired) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 3;

    state::SectScoutInfo alive;
    alive.sectId = "ai-1"; alive.sectName = "青岚宗";
    alive.expiryYear = 2; alive.expiryMonth = 4;   // 下月才过期
    st.gameData.scoutInfo["ai-1"] = alive;
    state::SectDetail detail1;
    detail1.sectId = "ai-1";
    detail1.scoutInfo = alive;
    st.gameData.sectDetails["ai-1"] = detail1;
    state::WorldSect sect1; sect1.id = "ai-1"; sect1.isKnown = true;
    st.gameData.worldMapSects.push_back(sect1);

    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());

    // 无过期 → 零写入（Lazy 门控等价）
    ASSERT_EQ(1u, st.gameData.scoutInfo.size());
    EXPECT_EQ("青岚宗", st.gameData.scoutInfo.at("ai-1").sectName);
    EXPECT_EQ("ai-1", st.gameData.sectDetails.at("ai-1").scoutInfo.sectId);
    EXPECT_TRUE(st.gameData.worldMapSects[0].isKnown);
}

/// 系统分区黄金序列：seed+3 播种的独立预演（RNG 审计方法同文件头说明）
gamecore::rng::DeterministicRng sysReplica(int64_t seed) {
    return gamecore::rng::DeterministicRng::fromSeed(seed + 3);
}

// ── S8 子事件 12：附庸脱离检查────────────────────────────

/// 附庸脱离场景：玩家宗门 p1 + 附属 ai-9（玄水宗）契约 + AI 弟子 + N 名
/// 同规格玩家弟子（realm 9 全同 → 战力比 = N 精确整数倍）→ 月度 SYSTEM
/// 抽取仅剩附庸判定 1 次。
void setupVassalScene(GameState& st, int playerDiscipleCount) {
    state::WorldSect player;
    player.id = "p1"; player.name = "青云宗"; player.isPlayerSect = true;
    st.gameData.worldMapSects.push_back(player);
    state::WorldSect vassal;
    vassal.id = "ai-9"; vassal.name = "玄水宗"; vassal.isKnown = true;
    st.gameData.worldMapSects.push_back(vassal);
    state::VassalContract contract;
    contract.vassalSectId = "ai-9"; contract.establishedYear = 1;
    st.gameData.vassalContracts.push_back(contract);
    state::Disciple ai = baseDisciple("90");
    st.aiSectDisciples["ai-9"].push_back(ai);
    for (int k = 0; k < playerDiscipleCount; ++k) {
        Disciple d = baseDisciple(std::to_string(k + 1));
        d.morality = 50;
        st.disciples.appendDisciple(d);
    }
}

TEST(MonthSettlementTest, VassalBreakawayEmptyContractsZeroDraws) {
    auto core = makeCore(42);
    auto& st = core->state();
    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());
    EXPECT_TRUE(st.gameData.vassalContracts.empty());
    EXPECT_EQ(before, core->rng().exportStates());
}

TEST(MonthSettlementTest, VassalBreakawayNoPlayerSectZeroDraws) {
    // 有契约但无 isPlayerSect 宗门 → 纯早退零抽取
    auto core = makeCore(42);
    auto& st = core->state();
    state::VassalContract contract;
    contract.vassalSectId = "ai-9"; contract.establishedYear = 1;
    st.gameData.vassalContracts.push_back(contract);
    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());
    EXPECT_EQ(1u, st.gameData.vassalContracts.size());
    EXPECT_EQ(before, core->rng().exportStates());
}

TEST(MonthSettlementTest, VassalBreakawaySectMissingRemovesSilentlyNoDraw) {
    // 附属宗门已不存在 → 契约静默移除（无抽取无事件）
    auto core = makeCore(42);
    auto& st = core->state();
    state::WorldSect player;
    player.id = "p1"; player.name = "青云宗"; player.isPlayerSect = true;
    st.gameData.worldMapSects.push_back(player);
    // 预置刷新月（==当前绝对月 13）→ 关卡刷新不触发（零抽取断言
    // 不受步骤 4e 生成干扰）
    st.gameData.worldLevelLastRefreshMonth = 1 * 12 + 1;
    state::VassalContract contract;
    contract.vassalSectId = "gone"; contract.establishedYear = 1;
    st.gameData.vassalContracts.push_back(contract);
    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());
    EXPECT_TRUE(st.gameData.vassalContracts.empty());
    EXPECT_TRUE(st.gameData.gameEventRecords.empty());
    EXPECT_EQ(before, core->rng().exportStates());
}

TEST(MonthSettlementTest, VassalBreakawayZeroAiPowerNoDraw) {
    // 附属宗门存在但 aiSectDisciples 缺失 → aiPower 0 → 不脱离零抽取
    auto core = makeCore(42);
    auto& st = core->state();
    setupVassalScene(st, 1);
    st.gameData.worldLevelLastRefreshMonth = 1 * 12 + 1;   // 不刷新
    st.aiSectDisciples.clear();
    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());
    EXPECT_EQ(1u, st.gameData.vassalContracts.size());
    EXPECT_TRUE(st.gameData.gameEventRecords.empty());
    EXPECT_EQ(before, core->rng().exportStates());
}

TEST(MonthSettlementTest, VassalBreakawayIntimateFavorStaysGolden) {
    // 6 名同规格弟子 vs 1 AI 弟子 → 战力比 6.0 ≥ 5x → powerScore 0；
    // 至交好感 100 → favorScore 0 → 脱离概率 0.0：恰抽 1 次，必不脱离
    auto core = makeCore(42);
    auto& st = core->state();
    setupVassalScene(st, 6);
    state::SectRelation relation;
    relation.sectId1 = "p1"; relation.sectId2 = "ai-9"; relation.favor = 100;
    st.gameData.sectRelations.push_back(relation);

    auto sys = sysReplica(42);
    sys.nextDouble();   // 唯一抽取（< 0.0 不可能）

    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());

    EXPECT_EQ(1u, st.gameData.vassalContracts.size());
    EXPECT_TRUE(st.gameData.gameEventRecords.empty());
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, VassalBreakawayWeakPlayerBreaksGolden) {
    // 玩家无存活弟子 → 战力比 0 → BREAKAWAY_BASE_WEAK 0.35 + 敌对好感
    // 0.15 = 0.50 → clamp 0.40；种子扫描 d1 < 0.40 → 脱离 + 事件
    int64_t seed = -1;
    for (int64_t s = 7000; s < 7000 + 100000; ++s) {
        auto probe = gamecore::rng::DeterministicRng::fromSeed(s + 3);
        if (probe.nextDouble() < 0.40) { seed = s; break; }
    }
    ASSERT_GE(seed, 0);
    auto core = makeCore(seed);
    auto& st = core->state();
    setupVassalScene(st, 0);   // 无玩家弟子
    state::SectRelation relation;
    relation.sectId1 = "p1"; relation.sectId2 = "ai-9"; relation.favor = 0;
    st.gameData.sectRelations.push_back(relation);

    auto sys = sysReplica(seed);
    sys.nextDouble();   // 唯一抽取

    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());

    EXPECT_TRUE(st.gameData.vassalContracts.empty());
    // 附属宗门本体保留（仅契约移除）+ 事件
    EXPECT_EQ(2u, st.gameData.worldMapSects.size());
    ASSERT_EQ(1u, st.gameData.gameEventRecords.size());
    EXPECT_EQ("WORLD", st.gameData.gameEventRecords[0].category);
    EXPECT_EQ("vassal_breakaway", st.gameData.gameEventRecords[0].eventType);
    EXPECT_EQ("玄水宗脱离了附属关系", st.gameData.gameEventRecords[0].summary);
    // AI 弟子域不受影响
    EXPECT_EQ(1u, st.aiSectDisciples.at("ai-9").size());
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, VassalBreakawayRollFailStays) {
    // 同场景，种子扫描 d1 ≥ 0.40 → 判定不脱离，恰抽 1 次
    int64_t seed = -1;
    for (int64_t s = 7000; s < 7000 + 100000; ++s) {
        auto probe = gamecore::rng::DeterministicRng::fromSeed(s + 3);
        if (probe.nextDouble() >= 0.40) { seed = s; break; }
    }
    ASSERT_GE(seed, 0);
    auto core = makeCore(seed);
    auto& st = core->state();
    setupVassalScene(st, 0);
    state::SectRelation relation;
    relation.sectId1 = "p1"; relation.sectId2 = "ai-9"; relation.favor = 0;
    st.gameData.sectRelations.push_back(relation);

    auto sys = sysReplica(seed);
    sys.nextDouble();

    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());

    EXPECT_EQ(1u, st.gameData.vassalContracts.size());
    EXPECT_TRUE(st.gameData.gameEventRecords.empty());
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, VassalBreakawayBattleRecordWindowAndCountsGolden) {
    // 近 3 年窗口边界（year ≥ gy-3）+ 四类计数：战力比 6.0 → powerScore 0；
    // occLoss 0.5×0.30 + skLoss 0.5×0.15 + 普通好感 0.4×0.15 = 0.285；
    // gy-4 的战报不入窗（双端边界口径）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 5;
    setupVassalScene(st, 6);   // ratio 6.0 ≥ 5x → powerScore 0
    st.gameData.sectBattleRecords.push_back(state::SectBattleRecord{2, "CONQUEST"});   // gy-3 窗内
    st.gameData.sectBattleRecords.push_back(state::SectBattleRecord{2, "LOST_SECT"});
    st.gameData.sectBattleRecords.push_back(state::SectBattleRecord{2, "BATTLE_WIN"});
    st.gameData.sectBattleRecords.push_back(state::SectBattleRecord{2, "BATTLE_LOSS"});
    st.gameData.sectBattleRecords.push_back(state::SectBattleRecord{1, "BATTLE_WIN"}); // gy-4 窗外
    st.gameData.sectBattleRecords.push_back(state::SectBattleRecord{1, "CONQUEST"});   // 窗外
    // 无 sectRelations 条目 → favor 默认 50（NORMAL 0.4×0.15=0.06）
    const double expectedChance = 0.5 * 0.30 + 0.5 * 0.15 + 0.4 * 0.15;

    // 扫描两个种子分别钉住脱离/不脱离分支（抽取数恒 1）
    int64_t breakSeed = -1, staySeed = -1;
    for (int64_t s = 7000; s < 7000 + 100000 && (breakSeed < 0 || staySeed < 0); ++s) {
        auto probe = gamecore::rng::DeterministicRng::fromSeed(s + 3);
        const double d1 = probe.nextDouble();
        if (d1 < expectedChance && breakSeed < 0) breakSeed = s;
        if (d1 >= expectedChance && staySeed < 0) staySeed = s;
    }
    ASSERT_GE(breakSeed, 0);
    ASSERT_GE(staySeed, 0);

    // 脱离分支
    {
        auto core2 = makeCore(breakSeed);
        auto& st2 = core2->state();
        st2.gameData.gameYear = 5;
        setupVassalScene(st2, 6);
        for (const auto& r : st.gameData.sectBattleRecords) {
            st2.gameData.sectBattleRecords.push_back(r);
        }
        system::runMonthSettlement(st2, core2->rng(), core2->aiRng(), core2->aiMonthBatch(), core2->ecsWorld());
        EXPECT_TRUE(st2.gameData.vassalContracts.empty());
        ASSERT_EQ(1u, st2.gameData.gameEventRecords.size());
        EXPECT_EQ("vassal_breakaway", st2.gameData.gameEventRecords[0].eventType);
    }
    // 留守分支
    {
        auto core2 = makeCore(staySeed);
        auto& st2 = core2->state();
        st2.gameData.gameYear = 5;
        setupVassalScene(st2, 6);
        for (const auto& r : st.gameData.sectBattleRecords) {
            st2.gameData.sectBattleRecords.push_back(r);
        }
        system::runMonthSettlement(st2, core2->rng(), core2->aiRng(), core2->aiMonthBatch(), core2->ecsWorld());
        EXPECT_EQ(1u, st2.gameData.vassalContracts.size());
        EXPECT_TRUE(st2.gameData.gameEventRecords.empty());
    }
}


TEST(VassalProbe, JsonImportThenMonthlyDrawCount) {
    auto core = makeCore(20260901);
    {
        auto& st = core->state();
        st.gameData.gameYear = 1; st.gameData.gameMonth = 1;
        st.gameData.spiritStones = 10000;
        st.gameData.sectPolicies.benevolentGovernance = true;
        setupVassalScene(st, 6);
        state::SectRelation relation;
        relation.sectId1 = "p1"; relation.sectId2 = "ai-9"; relation.favor = 100;
        st.gameData.sectRelations.push_back(relation);
        // 预推进 SYSTEM 分区 3 次（直接作用于实时分区——exportStateJson 先
        // syncRngStates 以分区实时状态覆盖 gameData.rngStates，手动写
        // gameData.rngStates 会被冲掉）
        auto presys = gamecore::rng::DeterministicRng::fromSeed(20260901 + 3);
        presys.nextInt(); presys.nextInt(); presys.nextInt();
        core->rng().restoreStates({{3, presys.snapshot()}});
    }
    const std::string json = core->exportStateJson();
    auto core2 = makeCore(20260901);
    ASSERT_TRUE(core2->importStateJson(json));
    auto& st2 = core2->state();
    // 导入域校验
    EXPECT_EQ(1u, st2.gameData.vassalContracts.size());
    EXPECT_EQ(1u, st2.aiSectDisciples.at("ai-9").size());
    EXPECT_TRUE(st2.gameData.worldMapSects[0].isPlayerSect);
    EXPECT_EQ(1u, st2.aiSectDisciples.count("ai-9"));
    // 跑月变：场景弟子不触发灵田/生产抽取 → SYSTEM 恰抽 1 次
    // = 附庸脱离判定
    auto sys = gamecore::rng::DeterministicRng::fromSeed(20260901 + 3);
    sys.nextInt(); sys.nextInt(); sys.nextInt();
    sys.nextDouble();
    st2.gameData.gamePhase = 2;
    core2->advancePhases(1);
    auto states = core2->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

// ── 子事件 15/16：秘境到期关闭 + AI 队伍派遣 ─────────────

using gamecore::system::secret_realm_settle::processMonthlyAiTeams;
using gamecore::system::secret_realm_settle::processMonthlyExpiryCheck;

TEST(SecretRealmSettlement, AiTeamsDispatchIdempotent) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 2;
    st.gameData.secretRealmState.id = "sr1";
    st.gameData.secretRealmState.spawnYear = 1;   // 未到期 → 不触发关闭
    // AI 宗门弟子（两宗，键升序遍历 ai-1 < ai-2；ai-2 含已故弟子）
    state::Disciple a1 = baseDisciple("90");
    a1.name = "玄一"; a1.realm = 9; a1.portraitRes = "p1";
    state::Disciple a2 = baseDisciple("91");
    a2.name = "玄二"; a2.realm = 5; a2.portraitRes = "p2";
    state::Disciple dead = baseDisciple("92");
    dead.name = "亡者"; dead.isAlive = false;
    st.aiSectDisciples["ai-1"] = {a2, a1};       // 存活 2 → 按境界升序取 4
    st.aiSectDisciples["ai-2"] = {dead};         // 无存活 → 不派遣
    gamecore::state::WorldSect ws;
    ws.id = "ai-1"; ws.name = "万剑宗"; ws.isKnown = true;
    ws.x = 100.0f; ws.y = 100.0f;
    st.gameData.worldMapSects.push_back(ws);

    processMonthlyAiTeams(st);

    ASSERT_EQ(1u, st.gameData.secretRealmAITeams.size());
    const auto& team = st.gameData.secretRealmAITeams[0];
    EXPECT_EQ("ai-1", team.sectId);
    EXPECT_EQ("万剑宗", team.sectName);
    EXPECT_EQ(0, team.sectLevel);
    ASSERT_EQ(2u, team.members.size());
    EXPECT_EQ("91", team.members[0].discipleId);   // 境界 5 在前（sortedBy 稳定）
    EXPECT_EQ("90", team.members[1].discipleId);
    EXPECT_EQ("gc-sr-team-1", team.id);

    // 幂等：再次调用不重复派遣
    processMonthlyAiTeams(st);
    ASSERT_EQ(1u, st.gameData.secretRealmAITeams.size());
}

TEST(SecretRealmSettlement, ExpiryCheckClosesRealmStateSegment) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 2;
    st.gameData.spiritStones = 1000;
    st.gameData.secretRealmState.id = "sr1";
    st.gameData.secretRealmState.spawnYear = -49;   // 1 < -49+50=1 不成立 → 到期
    st.gameData.secretRealmSession.secretRealmId = "sr1";
    state::SecretRealmMemberState member;
    member.discipleId = "11";
    member.name = "甲";
    st.gameData.secretRealmSession.members.push_back(member);
    st.gameData.secretRealmSession.backpack.spiritStones = 500;
    state::EquipmentInstance eq;   // B3：背包装备实例轨
    eq.id = "eq1"; eq.name = "木剑"; eq.meta.rarity = 1;
    st.gameData.secretRealmSession.backpack.equipment.push_back(eq);
    st.gameData.secretRealmAITeams.push_back(
        gamecore::state::SecretRealmAITeam{"t1", "ai-1", "万剑宗", {}, 1});

    processMonthlyExpiryCheck(st, 1);

    // 灵石入钱包（LOW/SecretRealm）+ 背包清空 + 会话/秘境/AI 队伍清场 + 冷却年
    EXPECT_EQ(1500LL, st.gameData.spiritStones);
    EXPECT_EQ(500LL, st.gameData.annualIncomeBySource.at("SecretRealm"));
    EXPECT_EQ(0LL, st.gameData.secretRealmSession.backpack.spiritStones);
    EXPECT_TRUE(st.gameData.secretRealmSession.backpack.equipment.empty());
    EXPECT_TRUE(st.gameData.secretRealmSession.members.empty());
    EXPECT_TRUE(st.gameData.secretRealmState.id.empty());
    EXPECT_EQ(1, st.gameData.secretRealmCooldownYear);
    EXPECT_TRUE(st.gameData.secretRealmAITeams.empty());
    ASSERT_EQ(1u, st.gameData.gameEventRecords.size());
    EXPECT_EQ("SECT", st.gameData.gameEventRecords[0].category);
    EXPECT_EQ("secret_realm", st.gameData.gameEventRecords[0].eventType);

    // 幂等：再次调用零写入
    const auto recordsBefore = st.gameData.gameEventRecords.size();
    processMonthlyExpiryCheck(st, 1);
    EXPECT_EQ(recordsBefore, st.gameData.gameEventRecords.size());
    EXPECT_EQ(1500LL, st.gameData.spiritStones);
}

TEST(SecretRealmSettlement, ExpiryCheckNotDueKeepsRealm) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 2;
    st.gameData.secretRealmState.id = "sr1";
    st.gameData.secretRealmState.spawnYear = 1;   // 1 < 51 → 未到期
    st.gameData.secretRealmSession.backpack.spiritStones = 500;

    processMonthlyExpiryCheck(st, 1);

    EXPECT_EQ("sr1", st.gameData.secretRealmState.id);
    EXPECT_EQ(500LL, st.gameData.secretRealmSession.backpack.spiritStones);
    EXPECT_TRUE(st.gameData.gameEventRecords.empty());
}

// ── 子事件 10：12 月自动购买 ─────────────────────────────

using gamecore::system::merchant_settle::executeAutoBuy;

/// B3：executeAutoBuy 装备臂统一入口（kEquipment 分区流）

TEST(AutoBuySettlement, DecemberAutoBuyMatchesKnownTemplate) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 12;
    st.gameData.spiritStones = 10000;
    st.gameData.autoBuyList.push_back({"裂天罡煞·头冠", "equipment", 1});
    st.gameData.autoBuyList.push_back({"聚气丹", "pill", 1});
    state::MerchantItem sword;
    sword.id = "m1"; sword.name = "裂天罡煞·头冠"; sword.type = "equipment";
    sword.rarity = 1; sword.price = 100; sword.quantity = 3;
    state::MerchantItem pill;
    pill.id = "m2"; pill.name = "聚气丹"; pill.type = "pill";
    pill.rarity = 1; pill.price = 50; pill.quantity = 2; pill.grade = "中品";
    st.gameData.travelingMerchantItems = {sword, pill};

    executeAutoBuy(st, core->rng().getRng(gamecore::rng::RngPartition::kEquipment));

    // 灵石扣除：裂天罡煞·头冠 3×100 + 聚气丹 2×50 = 400 → 10000-400
    EXPECT_EQ(9600LL, st.gameData.spiritStones);
    // 商人库存清空（数量耗尽 → 移除）
    EXPECT_TRUE(st.gameData.travelingMerchantItems.empty());
    // 仓库入库（实例轨）：头冠实例 ×3 + 聚气丹堆叠 ×2
    ASSERT_EQ(3u, st.equipmentInstances.size());
    for (const auto& inst : st.equipmentInstances) {
        EXPECT_EQ("裂天罡煞·头冠", inst.name);
        EXPECT_EQ("lietian", inst.setId);
        EXPECT_EQ("HEAD", inst.part);
        EXPECT_EQ(1, inst.meta.rarity);
        EXPECT_EQ(3u, inst.growth.affix.subStats.size());
    }
    ASSERT_EQ(1u, st.pills.size());
    EXPECT_EQ("聚气丹", st.pills[0].name);
    EXPECT_EQ(2, st.pills[0].quantity);
    EXPECT_EQ("MEDIUM", st.pills[0].grade);
    // 年度来源追踪（B3 实例轨与 Kotlin withTrackingSource 同口径：merchant:rarity）
    EXPECT_EQ(3, st.gameData.annualEquipmentBySource.at("merchant:1"));
    EXPECT_EQ(2, st.gameData.annualPillBySource.at("merchant:MEDIUM"));
    // 年度支出追踪（Purchase 原因）
    EXPECT_EQ(400LL, st.gameData.annualTotalExpenditure);
    // 零 SYSTEM RNG 抽取（已知模板主路径）
    const auto sys0 = gamecore::rng::DeterministicRng::fromSeed(20260901 + 3);
    EXPECT_EQ(sys0.snapshot(), core->rng().exportStates()[
        static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(AutoBuySettlement, DecemberAutoBuySkipsOnInsufficientFunds) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 12;
    st.gameData.spiritStones = 150;
    st.gameData.autoBuyList.push_back({"裂天罡煞·头冠", "equipment", 1});
    state::MerchantItem sword;
    sword.id = "m1"; sword.name = "裂天罡煞·头冠"; sword.type = "equipment";
    sword.rarity = 1; sword.price = 100; sword.quantity = 3;
    st.gameData.travelingMerchantItems = {sword};

    executeAutoBuy(st, core->rng().getRng(gamecore::rng::RngPartition::kEquipment));

    // 可买 1 件（150/100=1）→ 扣除 100；商人剩余 2；实例 1 条
    EXPECT_EQ(50LL, st.gameData.spiritStones);
    ASSERT_EQ(1u, st.gameData.travelingMerchantItems.size());
    EXPECT_EQ(2, st.gameData.travelingMerchantItems[0].quantity);
    ASSERT_EQ(1u, st.equipmentInstances.size());
    EXPECT_EQ("裂天罡煞·头冠", st.equipmentInstances[0].name);
}

TEST(AutoBuySettlement, DecemberAutoBuySpiritstoneAndNonMatch) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 12;
    st.gameData.spiritStones = 10000;
    st.gameData.autoBuyList.push_back({"中品灵石", "spiritstone", 1});
    st.gameData.autoBuyList.push_back({"不存在物品", "equipment", 9});  // 无匹配
    state::MerchantItem stone;
    stone.id = "m1"; stone.name = "中品灵石"; stone.type = "spiritstone";
    stone.rarity = 1; stone.price = 100; stone.quantity = 5;
    st.gameData.travelingMerchantItems = {stone};

    executeAutoBuy(st, core->rng().getRng(gamecore::rng::RngPartition::kEquipment));

    // 中品灵石入袋 ×5；不存在物品条目跳过
    EXPECT_EQ(9500LL, st.gameData.spiritStones);   // 10000 - 5×100
    EXPECT_EQ(5, st.gameData.midGradeSpiritStones);
    EXPECT_TRUE(st.gameData.travelingMerchantItems.empty());
    EXPECT_TRUE(st.equipmentInstances.empty());
}

// ── 子事件 12：弟子智能购买 ─────────────────────────────

using gamecore::system::disciple_purchase::processDisciplePurchase;

/// 构造单弟子购买场景：练气弟子（realm 9）随身 1000 灵石、无装备功法；
/// 上架功法/装备/丹药各 1 件（均已知模板、仓库有货、未锁定）
TEST(DisciplePurchaseSettlement, SingleDiscipleBuysAllThreeCategories) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 2;
    st.gameData.spiritStones = 0;   // 宗门初始 0，验证购买款入账

    // 弟子：存活 + 随身 1000 灵石（无储物袋灵石）
    Disciple d = baseDisciple("1");
    d.spiritStones = 1000;
    st.disciples.appendDisciple(d);

    // 上架商品（playerListedItems）：
    state::MerchantItem manualItem;
    manualItem.id = "list-m1"; manualItem.name = "青云心法";
    manualItem.type = "manual"; manualItem.rarity = 1; manualItem.price = 100;
    manualItem.quantity = 1; manualItem.itemId = "wh-m1";
    state::MerchantItem equipItem;
    equipItem.id = "list-e1"; equipItem.name = "精铁剑";
    equipItem.type = "equipment"; equipItem.rarity = 1; equipItem.price = 200;
    equipItem.quantity = 1; equipItem.itemId = "wh-e1";
    state::MerchantItem pillItem;
    pillItem.id = "list-p1"; pillItem.name = "聚气丹";
    pillItem.type = "pill"; pillItem.rarity = 1; pillItem.price = 50;
    pillItem.quantity = 1; pillItem.itemId = "wh-p1"; pillItem.grade = "中品";
    st.gameData.playerListedItems = {manualItem, equipItem, pillItem};

    // 仓库库存（未锁定、数量 1）：
    state::ManualStack whManual;
    whManual.id = "wh-m1"; whManual.name = "青云心法"; whManual.rarity = 1;
    whManual.quantity = 1;
    st.manualStacks.push_back(whManual);
    // B3 实例轨：仓库装备 = 实例（name+rarity 匹配首条未锁定）
    state::EquipmentInstance whEquip;
    whEquip.id = "wh-e1"; whEquip.name = "精铁剑"; whEquip.meta.rarity = 1;
    whEquip.part = "WEAPON";
    st.equipmentInstances.push_back(whEquip);
    state::Pill whPill;
    whPill.id = "wh-p1"; whPill.name = "聚气丹"; whPill.rarity = 1;
    whPill.quantity = 1; whPill.grade = "MEDIUM";
    st.pills.push_back(whPill);

    processDisciplePurchase(st, core->rng(), nullptr, core->ecsWorld());

    // 弟子灵石：1000 - 100(功法) - 200(装备) - 50(丹药) = 650
    const auto idx = gamecore::system::settle_util::indexById(st.disciples);
    const auto row = idx.at(1);
    EXPECT_EQ(650, st.disciples.spiritStones[row]);
    // 宗门灵石入账：100 + 200 + 50 = 350
    EXPECT_EQ(350LL, st.gameData.spiritStones);
    // 储物袋三件条目（顺序：功法 → 装备 → 丹药）；B3 装备 = 实例条目
    const auto& bag = st.disciples.storageBagItems[row];
    ASSERT_EQ(3u, bag.size());
    EXPECT_EQ("equipment_instance", bag[1].itemType);
    EXPECT_EQ("manual_stack", bag[0].itemType);
    EXPECT_EQ("pill", bag[2].itemType);
    ASSERT_TRUE(bag[1].equipmentInstance.has_value());
    EXPECT_EQ("wh-e1", bag[1].equipmentInstance->id);
    EXPECT_EQ("WEAPON", bag[1].equipmentInstance->part);
    EXPECT_EQ("MIND", bag[0].stackedData->manualType);
    EXPECT_EQ("中品", *bag[2].grade);
    EXPECT_TRUE(bag[2].effect.has_value());
    // 仓库库存清空（装备实例整条迁入袋）
    EXPECT_TRUE(st.manualStacks.empty());
    EXPECT_TRUE(st.equipmentInstances.empty());
    EXPECT_TRUE(st.pills.empty());
    // RNG：makeCore 的 rng 为 initSystemSeed(20260901) 未额外抽取（对比 Diag
    // 测试显式 restoreStates 场景）——购买 3 次 nextInt()（功法/装备/丹药
    // 各 1 候选 shuffled）
    auto sys0 = gamecore::rng::DeterministicRng::fromSeed(20260901 + 3);
    sys0.nextInt();
    sys0.nextInt();
    sys0.nextInt();
    EXPECT_EQ(sys0.snapshot(), core->rng().exportStates()[
        static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

/// 无资金弟子：不上架商品时零写入零 RNG
TEST(DisciplePurchaseSettlement, NoListedItemsOrNoFundsZeroEffects) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 2;
    st.gameData.spiritStones = 0;   // 宗门初始 0
    Disciple d = baseDisciple("1");
    d.spiritStones = 0;   // 无资金
    st.disciples.appendDisciple(d);

    const auto before = core->rng().exportStates();
    processDisciplePurchase(st, core->rng(), nullptr, core->ecsWorld());
    EXPECT_EQ(before, core->rng().exportStates());
    EXPECT_EQ(0, st.gameData.spiritStones);
    EXPECT_TRUE(st.disciples.storageBagItems[0].empty());
}

/// 仓库无货：决策生成但扣减失败 → 跳过购买（listing 保留）
TEST(DisciplePurchaseSettlement, NoWarehouseStockSkipsPurchase) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 2;
    st.gameData.spiritStones = 0;   // 宗门初始 0
    Disciple d = baseDisciple("1");
    d.spiritStones = 1000;
    st.disciples.appendDisciple(d);

    state::MerchantItem manualItem;
    manualItem.id = "list-m1"; manualItem.name = "青云心法";
    manualItem.type = "manual"; manualItem.rarity = 1; manualItem.price = 100;
    manualItem.quantity = 1; manualItem.itemId = "wh-m1";
    st.gameData.playerListedItems = {manualItem};
    // 仓库无对应库存 → 扣减失败

    processDisciplePurchase(st, core->rng(), nullptr, core->ecsWorld());

    // 弟子灵石不变（购买未发生）；listing 保留
    const auto idx = gamecore::system::settle_util::indexById(st.disciples);
    const auto row = idx.at(1);
    EXPECT_EQ(1000, st.disciples.spiritStones[row]);
    EXPECT_EQ(0LL, st.gameData.spiritStones);
    EXPECT_EQ(1u, st.gameData.playerListedItems.size());
    EXPECT_TRUE(st.disciples.storageBagItems[row].empty());
}

// ── 子事件 14：任务刷新 ─────────────────────────────────

using gamecore::system::mission_settle::processMissionRefresh;
using gamecore::state::Mission;

/// MISSION 分区黄金序列预演：seed+8 播种（kMission 分区 id=8）
gamecore::rng::DeterministicRng missionReplica(int64_t seed) {
    return gamecore::rng::DeterministicRng::fromSeed(seed + 8);
}

TEST(MissionSettlement, RefreshMonthGeneratesMissions) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 3;   // 3 % 3 == 0 → 刷新月

    // 旧任务列表（刷新月应被清空）
    Mission old;
    old.id = "old-1"; old.name = "旧任务"; old.template_ = "ESCORT_CARAVAN";
    st.gameData.availableMissions = {old};

    // 预演 MISSION 序列：nextInt(7) 决定刷新数，每任务 1 次 nextDouble 加权
    auto mission = missionReplica(42);
    const int32_t refreshCount = mission.nextInt(7);
    std::vector<std::string> expectedTemplates;
    for (int32_t i = 0; i < refreshCount; ++i) {
        const auto pool = gamecore::system::mission_settle::buildWeightedPool();
        const double totalWeight = pool.back().second;
        const double roll = mission.nextDouble() * totalWeight;
        for (const auto& [t, cumulative] : pool) {
            if (roll < cumulative) { expectedTemplates.push_back(t); break; }
        }
    }

    processMissionRefresh(st, core->rng());

    // 刷新月：旧列表清空，新任务 = refreshCount 个
    EXPECT_EQ(static_cast<std::size_t>(refreshCount),
              st.gameData.availableMissions.size());
    for (std::size_t i = 0; i < expectedTemplates.size(); ++i) {
        EXPECT_EQ(expectedTemplates[i], st.gameData.availableMissions[i].template_);
        // name = difficulty.displayName + template.displayName
        const auto& m = st.gameData.availableMissions[i];
        EXPECT_FALSE(m.name.empty());
        EXPECT_FALSE(m.description.empty());
        EXPECT_FALSE(m.difficulty.empty());
        EXPECT_GT(m.duration, 0);
        EXPECT_EQ(1, m.createdYear);
        EXPECT_EQ(3, m.createdMonth);
    }
    // MISSION 分区快照与预演一致（抽取序：nextInt(7) + refreshCount 次 nextDouble）
    const auto states = core->rng().exportStates();
    EXPECT_EQ(mission.snapshot(), states.at(
        static_cast<int32_t>(gamecore::rng::RngPartition::kMission)));
}

TEST(MissionSettlement, NonRefreshMonthKeepsExisting) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 4;   // 4 % 3 != 0 → 非刷新月

    Mission old;
    old.id = "old-1"; old.name = "旧任务"; old.template_ = "ESCORT_CARAVAN";
    st.gameData.availableMissions = {old};

    const auto before = core->rng().exportStates();
    processMissionRefresh(st, core->rng());

    // 非刷新月：零写入零 RNG
    EXPECT_EQ(before, core->rng().exportStates());
    ASSERT_EQ(1u, st.gameData.availableMissions.size());
    EXPECT_EQ("old-1", st.gameData.availableMissions[0].id);
}

TEST(MissionSettlement, RewardConfigCoversAllTemplates) {
    // 24 模板全覆盖：createRewardConfig 非零（任一会回退到正确档）
    for (const auto& t :
         gamecore::system::mission_settle::missionTemplateEntries()) {
        const auto c = gamecore::system::mission_settle::createRewardConfig(t);
        const bool nonZero = c.spiritStones > 0 || c.materialCountMin > 0 ||
                             c.pillCountMin > 0 || c.baseSpiritStones > 0 ||
                             c.equipmentChance > 0.0 || c.manualChance > 0.0;
        EXPECT_TRUE(nonZero) << "模板 " << t << " 奖励配置全零";
        EXPECT_FALSE(c.equipmentChance > 0.0 && c.manualChance > 0.0)
            << "模板 " << t << " 装备与功法概率并存（Kotlin 语义互斥）";
    }
}

// ── 步骤 3：AI 兽袭目标预计算（precomputeTargets 等价移植）───
//
// 直接测 detail::precomputeTargets（规避步骤 4e moveBeasts 的 EXPLORATION
// 干扰——快照锁只锁定本函数抽取序）。场景基准：realm 9 默认弟子战力
// 1071/人（(16+16)*5+203*4+(13+10)*3+15*2），10 人 = 10710。
//   beastPower = beastCombatPower（coerceAtLeast(0)）：
//     beastMaxHp=100 → 400（<< aiPower → prob 封顶 0.9）
//     beastMaxHp=2600 → 10400（≈ aiPower → ratio 1.03 → prob≈0.309）
//     beastMaxHp=100000 → 400000（> aiPower → aiPower<=beastPower 跳过）

/// AI 宗门场景装配：玩家宗门 + N 个 AI 宗门（坐标可调）+ 各宗门弟子数
void setupBeastAttackScene(GameState& st) {
    state::WorldSect player;
    player.id = "p1"; player.isPlayerSect = true;
    player.x = 0.0f; player.y = 0.0f;
    st.gameData.worldMapSects.push_back(player);
    state::WorldSect ai1;
    ai1.id = "ai-1"; ai1.x = 200.0f; ai1.y = 100.0f;
    st.gameData.worldMapSects.push_back(ai1);
    state::WorldSect ai2;
    ai2.id = "ai-2"; ai2.x = 1500.0f; ai2.y = 100.0f;
    st.gameData.worldMapSects.push_back(ai2);
    state::WorldSect ai3;
    ai3.id = "ai-3"; ai3.x = 1600.0f; ai3.y = 100.0f;
    st.gameData.worldMapSects.push_back(ai3);
}

/// 为 ai-1 填充 n 名存活弟子（默认战力 1071/人）
void addAiDisciples(GameState& st, const std::string& sectId, int count) {
    for (int k = 0; k < count; ++k) {
        st.aiSectDisciples[sectId].push_back(baseDisciple(sectId + "-" + std::to_string(k)));
    }
}

TEST(MonthSettlementTest, PrecomputeTargetsDrawMissRecordsCooldownAndCleans) {
    // 门控 + 抽取不命中 + 过期冷却清理：
    //   ai-1 近（10 弟子，prob≈0.309 → 抽 1 次不命中记冷却 13）；
    //   ai-2 远（预置冷却 13 >= 绝对月 → 跳过零抽取）；
    //   ai-3 更远（5 弟子 < 10 → 跳过）；stale 冷却（0 < 13-12）→ 清理；
    //   beast-locked 被锁定 → 排除（不评估不写入）。
    const int64_t seed = 42;
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 1;   // 绝对月 = 13

    WorldLevel beast;
    beast.id = "beast-a";
    beast.type = "BEAST";
    beast.x = 100.0f; beast.y = 100.0f;
    beast.expiryYear = 99; beast.expiryMonth = 12;
    beast.beastMaxHp = 2600;     // beastPower=10400 ≈ aiPower → prob≈0.309
    st.gameData.worldLevels.push_back(beast);
    WorldLevel locked = beast;
    locked.id = "beast-locked";
    st.lockedBeastIds.push_back("beast-locked");

    setupBeastAttackScene(st);
    addAiDisciples(st, "ai-1", 10);
    addAiDisciples(st, "ai-3", 5);
    st.aiSectBeastSkipCooldowns["ai-2"] = 13;   // >= absoluteMonth → 跳过
    st.aiSectBeastSkipCooldowns["stale"] = 0;   // < 1 → 清理

    // 预演 EXPLORATION：ai-1 恰抽 1 次（不命中）——种子 42 首抽 >= 0.309
    auto probe = gamecore::rng::DeterministicRng::fromSeed(seed + 2);
    probe.nextDouble();

    gamecore::system::detail::precomputeTargets(st, core->rng());

    // targets 空（唯一抽取候选 ai-1 未命中）
    EXPECT_TRUE(st.aiSectBeastDirectTargets.empty());
    // 冷却：ai-1 记录（抽不中）、ai-2 预置保留、stale 已清理
    EXPECT_EQ(13, st.aiSectBeastSkipCooldowns["ai-1"]);
    EXPECT_EQ(13, st.aiSectBeastSkipCooldowns["ai-2"]);
    EXPECT_EQ(st.aiSectBeastSkipCooldowns.end(),
              st.aiSectBeastSkipCooldowns.find("stale"));
    // 锁定妖兽未评估（无目标写入）
    EXPECT_TRUE(st.aiSectBeastDirectTargets.empty());
    // RNG 审计：EXPLORATION 恰消耗 1 次 nextDouble
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kExploration).snapshot());
}

TEST(MonthSettlementTest, PrecomputeTargetsHitWritesDirectTargets) {
    // 命中路径 + 必攻路径 + qualified 上限去重：
    //   beast-a（beastPower=400 → prob=0.9，ai-1 抽 1 次命中 → targets[a]=[ai-1]）；
    //   beast-b（beastPower=0 → 必攻零抽取 → targets[b]=[ai-1]）；
    //   ai-2/ai-3 距离远且弟子不足（5 < 10）→ 跳过。
    const int64_t seed = 42;
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 1;

    WorldLevel weak;
    weak.id = "beast-a";
    weak.type = "BEAST";
    weak.x = 100.0f; weak.y = 100.0f;
    weak.expiryYear = 99; weak.expiryMonth = 12;
    weak.beastMaxHp = 100;       // beastPower=400 → prob=0.9
    st.gameData.worldLevels.push_back(weak);
    WorldLevel zero = weak;
    zero.id = "beast-b";
    zero.beastMaxHp = 0;         // beastPower=0 → 必攻零抽取
    st.gameData.worldLevels.push_back(zero);

    setupBeastAttackScene(st);
    addAiDisciples(st, "ai-1", 10);
    addAiDisciples(st, "ai-2", 5);

    // 预演 EXPLORATION：beast-a 恰抽 1 次（种子 42 首抽 < 0.9 命中）；
    // beast-b 必攻零抽取
    auto probe = gamecore::rng::DeterministicRng::fromSeed(seed + 2);
    probe.nextDouble();

    gamecore::system::detail::precomputeTargets(st, core->rng());

    // 两妖兽均命中 ai-1（列表距离升序 [ai-1]；ai-2 弟子不足不参与）
    ASSERT_EQ(2u, st.aiSectBeastDirectTargets.size());
    const auto& tA = st.aiSectBeastDirectTargets["beast-a"];
    const auto& tB = st.aiSectBeastDirectTargets["beast-b"];
    ASSERT_EQ(1u, tA.size());
    ASSERT_EQ(1u, tB.size());
    EXPECT_STREQ("ai-1", tA[0].c_str());
    EXPECT_STREQ("ai-1", tB[0].c_str());
    // 命中不记冷却
    EXPECT_TRUE(st.aiSectBeastSkipCooldowns.empty());
    // RNG 审计：恰 1 次（beast-b 必攻零抽取）
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kExploration).snapshot());
}

TEST(MonthSettlementTest, PrecomputeTargetsSameSectTwoBeastsSnapshotSemantics) {
    // 快照语义守护（对拍命门）：同一 AI 宗门对两个妖兽都最近，双妖兽各抽
    // 1 次——Kotlin `val gd = state.gameData` 值快照使 beast-a 的冷却写入
    // 不影响 beast-b 的冷却读取（引用实现会跳过 beast-b → 仅 1 抽 → 快照锁
    // 失败）。prob≈0.309 双不命中 → targets 空 + 冷却记录。
    const int64_t seed = 42;
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 1;

    WorldLevel a;
    a.id = "beast-a";
    a.type = "BEAST";
    a.x = 100.0f; a.y = 100.0f;
    a.expiryYear = 99; a.expiryMonth = 12;
    a.beastMaxHp = 2600;
    st.gameData.worldLevels.push_back(a);
    WorldLevel b = a;
    b.id = "beast-b";
    b.x = 120.0f; b.y = 100.0f;
    st.gameData.worldLevels.push_back(b);

    setupBeastAttackScene(st);
    addAiDisciples(st, "ai-1", 10);

    // 预演 EXPLORATION：双妖兽各 1 抽 = 2 次（种子 42 两抽均 >= 0.309）
    auto probe = gamecore::rng::DeterministicRng::fromSeed(seed + 2);
    probe.nextDouble();
    probe.nextDouble();

    gamecore::system::detail::precomputeTargets(st, core->rng());

    // 双抽不命中 → targets 空 + ai-1 冷却记录
    EXPECT_TRUE(st.aiSectBeastDirectTargets.empty());
    EXPECT_EQ(13, st.aiSectBeastSkipCooldowns["ai-1"]);
    EXPECT_EQ(1u, st.aiSectBeastSkipCooldowns.size());
    // RNG 审计：恰 2 次（快照语义——beast-a 冷却写入不抑制 beast-b 抽取）
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kExploration).snapshot());
}

// ── 步骤 4e：世界关卡刷新生成接线（LevelGenerator 接线）─
//
// Kotlin WorldLevelManager.processMonthly 语义：清理过期 → shouldRefresh 判定
// （lastRefreshMonth==0 || 差值>=3）→ 玩家宗门门控（无 → 只清理不生成不推进）
// → LevelGenerator.generateWorldLevels（maxNewLevels=6 → nextInt(6)+1 个）+
// playerAvgRealm 安全兜底 → lastRefreshMonth 推进 → 妖兽移动。
// 直接测 runMonthSettlement 步骤 4d 效果（场景无灵田/政策 → 其余步骤
// 零 RNG；EXPLORATION 消费仅来自 4d——生成 + 移动）。

TEST(MonthSettlementTest, WorldLevelRefreshGeneratesLevelsWithPlayerSect) {
    // 玩家宗门 + lastRefreshMonth=0 → 应刷新：生成 1~6 个新关卡 + 推进刷新月
    auto core = makeCore(42);
    auto& st = core->state();
    state::WorldSect player;
    player.id = "p1"; player.isPlayerSect = true;
    st.gameData.worldMapSects.push_back(player);
    Disciple d = baseDisciple("1");   // realm 9 → playerAvgRealm=9
    st.disciples.appendDisciple(d);

    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());

    EXPECT_FALSE(st.gameData.worldLevels.empty());     // 生成了新关卡
    EXPECT_EQ(1 * 12 + 1, st.gameData.worldLevelLastRefreshMonth);  // 推进
    // 生成数量 = nextInt(6)+1（1~6）；每关卡含妖兽属性生成（EXPLORATION 消费）
    ASSERT_LE(1u, st.gameData.worldLevels.size());
    ASSERT_LE(st.gameData.worldLevels.size(), 6u);
}

TEST(MonthSettlementTest, WorldLevelRefreshSkippedWithoutPlayerSect) {
    // 无玩家宗门：只清理不生成不推进（Kotlin 提前 return 分支）——零消费
    auto core = makeCore(42);
    auto& st = core->state();
    state::WorldSect ai;
    ai.id = "ai-1"; ai.isPlayerSect = false;
    st.gameData.worldMapSects.push_back(ai);
    Disciple d = baseDisciple("1");
    st.disciples.appendDisciple(d);

    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());

    EXPECT_TRUE(st.gameData.worldLevels.empty());
    EXPECT_EQ(0, st.gameData.worldLevelLastRefreshMonth);  // 未推进
    EXPECT_EQ(before, core->rng().exportStates());         // 零 RNG 消费
}

TEST(MonthSettlementTest, WorldLevelRefreshSkippedWhenRecentRefresh) {
    // lastRefreshMonth=当前绝对月（13）→ 差值 0 < 3 → 不刷新（非刷新月路径）
    auto core = makeCore(42);
    auto& st = core->state();
    state::WorldSect player;
    player.id = "p1"; player.isPlayerSect = true;
    st.gameData.worldMapSects.push_back(player);
    st.gameData.worldLevelLastRefreshMonth = 1 * 12 + 1;
    Disciple d = baseDisciple("1");
    st.disciples.appendDisciple(d);

    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng(), core->aiRng(), core->aiMonthBatch(), core->ecsWorld());

    EXPECT_TRUE(st.gameData.worldLevels.empty());
    EXPECT_EQ(1 * 12 + 1, st.gameData.worldLevelLastRefreshMonth);  // 保持
    EXPECT_EQ(before, core->rng().exportStates());         // 零 RNG 消费
}

// ── 步骤 6a：月度自动排班（Kotlin ProductionProcessor.
//    processAutoAssign 等价移植；零 RNG 纯数据变换）───────────────────
// 直接测 detail::processAutoAssign（政策开启场景；零 RNG 快照锁）。

TEST(MonthSettlementTest, AutoAssignResidenceGolden) {
    // 单人住所 2 空槽 + 2 弟子（comprehension 50/30，灵根 1 根匹配
    // rootCounts={1}）→ 按 comprehension 降序分配（followed 同 false →
    // rootCount 同 1 → attr 降序）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.sectPolicies.autoSingleResidenceRootCounts = {1};
    Disciple d1 = baseDisciple("1");
    d1.comprehension = 50;
    Disciple d2 = baseDisciple("2");
    d2.comprehension = 30;
    st.disciples.appendDisciple(d1);
    st.disciples.appendDisciple(d2);
    state::GridBuildingData b;
    b.displayName = "初级单人住所"; b.instanceId = "b1";
    st.gameData.placedBuildings.push_back(b);
    for (int i = 0; i < 2; ++i) {
        state::ResidenceSlot slot;
        slot.buildingInstanceId = "b1";
        slot.slotIndex = i;
        st.gameData.residenceSlots.push_back(slot);
    }

    const auto before = core->rng().exportStates();
    gamecore::system::detail::processAutoAssign(st, core->ecsWorld());

    ASSERT_EQ(2u, st.gameData.residenceSlots.size());
    EXPECT_EQ("1", st.gameData.residenceSlots[0].discipleId);
    EXPECT_EQ("弟子1", st.gameData.residenceSlots[0].discipleName);
    EXPECT_EQ("2", st.gameData.residenceSlots[1].discipleId);
    EXPECT_EQ(before, core->rng().exportStates());   // 零 RNG
}

TEST(MonthSettlementTest, AutoAssignProductionGoldenWithPoolReflow) {
    // 灵植 1 空槽 + 灵矿 1 空槽 + 3 弟子（spiritPlanting 50/40/30，
    // 灵根 1 根匹配 rootCounts={1}）：灵植优先 take(1) 取 50；灵矿
    // take(1) 取回流后的 40；30 无槽未分配
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.sectPolicies.autoPlantRootCounts = {1};
    st.gameData.sectPolicies.autoMineRootCounts = {1};
    for (int i = 1; i <= 3; ++i) {
        Disciple d = baseDisciple(std::to_string(i));
        d.spiritPlanting = 60 - i * 10;   // 50/40/30
        st.disciples.appendDisciple(d);
    }
    state::GridBuildingData hb;
    hb.displayName = "灵植阁"; hb.instanceId = "b1";
    st.gameData.placedBuildings.push_back(hb);
    state::ProductionSlot herb;
    herb.slotIndex = 0; herb.buildingType = "HERB_GARDEN";
    herb.status = "IDLE";
    st.gameData.productionSlots.push_back(herb);
    state::SpiritMineSlot mine;
    mine.index = 0;
    st.gameData.spiritMineSlots.push_back(mine);

    gamecore::system::detail::processAutoAssign(st, core->ecsWorld());

    ASSERT_EQ(1u, st.gameData.productionSlots.size());
    ASSERT_TRUE(st.gameData.productionSlots[0].assignedDiscipleId.has_value());
    EXPECT_EQ("1", *st.gameData.productionSlots[0].assignedDiscipleId);  // 灵植 50
    ASSERT_EQ(1u, st.gameData.spiritMineSlots.size());
    EXPECT_EQ("2", st.gameData.spiritMineSlots[0].discipleId);           // 灵矿 40
}

TEST(MonthSettlementTest, AutoAssignPoliciesDisabledNoop) {
    // 政策全关 → 纯早退零写入零 RNG
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    st.disciples.appendDisciple(d);
    state::ResidenceSlot slot;
    slot.buildingInstanceId = "b1"; slot.slotIndex = 0;
    st.gameData.residenceSlots.push_back(slot);

    const auto before = core->rng().exportStates();
    gamecore::system::detail::processAutoAssign(st, core->ecsWorld());

    EXPECT_TRUE(st.gameData.residenceSlots[0].discipleId.empty());
    EXPECT_EQ(before, core->rng().exportStates());
}

}  // namespace
