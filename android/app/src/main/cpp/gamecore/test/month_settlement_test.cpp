// ============================================================
// month_settlement_test — 月变结算钩子黄金序列守护（T2.2）
//
// 守护目标：固定种子 + 固定状态 → SettlementEngine.onMonthChange
// （runMonthSettlement）跨月推进 → 断言八步事务各域字段值逐位符合手算期望。
//
// 覆盖：政策月度扣除（含不足自动关闭 / 广纳门徒 36 月冷却）/ 政策月度忠诚·
// 道德效果 / 住所忠诚 / 血炼到期结算与未到期保留（含 NaN 防御）/ 丹药持续
// 效果月衰减 / 道侣配对双分支（SYSTEM RNG 审计）/ 灵田收获种子 roll
// （SYSTEM RNG 审计 + 续种匹配）/ 世界关卡清理与妖兽移动（EXPLORATION
// RNG 审计 + 边界钳制）/ 灵矿月产差分结算与矿工忠诚衰减 / 游戏结束判定 /
// 招募计数归零 + SYSTEM 分区抽取顺序锁（收获 roll 先于伴侣配对）。
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
using gamecore::state::BloodRefinementProgress;
using gamecore::state::Disciple;
using gamecore::state::GameData;
using gamecore::state::GameState;
using gamecore::state::GridBuildingData;
using gamecore::state::ResidenceSlot;
using gamecore::state::SpiritFieldPlant;
using gamecore::state::SpiritMineSlot;
using gamecore::state::WorldLevel;

/// 道侣配对基础概率（PartnerSystem.PAIRING_PROBABILITY）
constexpr double kPairingProbability = 0.006;
/// 忠诚上限（GameConfig.Disciple.MAX_LOYALTY）
constexpr int32_t kMaxLoyalty = 100;
/// 教化之道道德上限（GameConfig.PolicyConfig.MORAL_EDUCATION_MAX）
constexpr int32_t kMoralEducationMax = 70;
/// 广纳门徒冷却月数（GameConfig.PolicyConfig.OPEN_RECRUITMENT_COOLDOWN_MONTHS）
constexpr int32_t kOpenRecruitmentCooldownMonths = 36;
/// 广纳门徒费用（GameConfig.PolicyConfig.OPEN_RECRUITMENT_COST）
constexpr int64_t kOpenRecruitmentCost = 50000;
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

/// 填充一个最小存活弟子（炼气一层；默认成年可配对版本见 adultDisciple）
Disciple baseDisciple(const std::string& id) {
    Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.realm = 9;
    d.realmLayer = 1;
    d.isAlive = true;
    d.spiritRootType = "metal";
    d.age = 16;
    d.lifespan = 80;
    return d;
}

/// 成年弟子（道侣配对资格 age >= 18）
Disciple adultDisciple(const std::string& id, const char* gender) {
    Disciple d = baseDisciple(id);
    d.age = 20;
    d.gender = gender;
    return d;
}

/// 将时间拨到月末下旬并推进一旬 → 触发一次月变钩子（等价生产 tick 跨月路径）
void crossMonth(std::unique_ptr<GameCore>& core) {
    auto& st = core->state();
    st.gameData.gamePhase = 2;
    core->advancePhases(1);
}

/// 在指定分区内扫描种子：首个满足 wantPaired（首抽 < 配对概率）的种子
int64_t findSeedWhereFirstSystemDraw(int64_t begin, bool wantPaired) {
    for (int64_t s = begin; s < begin + 100000; ++s) {
        auto probe = gamecore::rng::DeterministicRng::fromSeed(s + 3);
        const bool hits =
            probe.nextDouble() < kPairingProbability;
        if (hits == wantPaired) return s;
    }
    return -1;
}

// ── 步骤 1：政策月度灵石扣除 ────────────────────────────────────────

TEST(MonthSettlementTest, PolicyCostsDeductAndAutoDisable) {
    // 丹道激励(3000)+功法研习(4000)，余额 5000：前者扣成功、后者不足自动关闭
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 5000;
    st.gameData.sectPolicies.alchemyIncentive = true;
    st.gameData.sectPolicies.manualResearch = true;

    const auto result = system::runMonthSettlement(st, core->rng());

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

    const auto result = system::runMonthSettlement(st, core->rng());

    EXPECT_TRUE(result.policyCosts.allPaid);
    EXPECT_TRUE(result.policyCosts.disabledPolicies.empty());
    EXPECT_EQ(100000 - kAlchemyIncentiveMonthly - kManualResearchMonthly,
              st.gameData.spiritStones);
}

TEST(MonthSettlementTest, OpenRecruitmentCooldownGolden) {
    // 冷却期内不扣；满 36 个月扣费并记录本次付费月份（绝对月口径）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 60000;
    st.gameData.sectPolicies.openRecruitment = true;
    st.gameData.openRecruitmentLastPaidMonth = 1 * 12 + 1 - 35;   // 差值 35 < 36

    system::runMonthSettlement(st, core->rng());
    EXPECT_EQ(60000, st.gameData.spiritStones);              // 冷却期内未扣
    EXPECT_EQ(1 * 12 + 1 - 35, st.gameData.openRecruitmentLastPaidMonth);

    // 差值恰好 36 → 扣费 + lastPaidMonth 推进到当前绝对月
    st.gameData.sectPolicies.openRecruitment = true;
    st.gameData.openRecruitmentLastPaidMonth = 1 * 12 + 1 - kOpenRecruitmentCooldownMonths;
    const auto result = system::runMonthSettlement(st, core->rng());
    EXPECT_TRUE(result.policyCosts.allPaid);
    EXPECT_EQ(60000 - kOpenRecruitmentCost, st.gameData.spiritStones);
    EXPECT_EQ(1 * 12 + 1, st.gameData.openRecruitmentLastPaidMonth);
}

// ── 步骤 2：政策月度忠诚/道德效果 ──────────────────────────────────

TEST(MonthSettlementTest, PolicyMonthlyEffectsLoyaltyMoralityGolden) {
    // 仁政(+1)+松弛(+2) 合并净变化 +3；教化之道道德 68→69；
    // 上限钳制：忠诚 99→100、道德 70 保持 70（≥阈值不触发偷盗链）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.sectPolicies.benevolentGovernance = true;
    st.gameData.sectPolicies.relaxedMgmt = true;
    st.gameData.sectPolicies.moralEducation = true;
    st.gameData.spiritStones = 1000000;   // 教化之道按弟子计费 100/人

    Disciple mid = baseDisciple("1");
    mid.loyalty = 50;
    mid.morality = 68;
    Disciple capped = baseDisciple("2");
    capped.loyalty = 99;
    capped.morality = kMoralEducationMax;
    st.disciples.appendDisciple(mid);
    st.disciples.appendDisciple(capped);

    system::runMonthSettlement(st, core->rng());

    EXPECT_EQ(53, st.disciples.materialize(0).loyalty);                  // 50 + 3
    EXPECT_EQ(kMaxLoyalty, st.disciples.materialize(1).loyalty);         // 99 + 3 → clamp 100
    EXPECT_EQ(69, st.disciples.materialize(0).morality);                 // 68 + 1
    EXPECT_EQ(kMoralEducationMax, st.disciples.materialize(1).morality); // 上限不再增长
}

TEST(MonthSettlementTest, NegativeLoyaltyDeltaClampsAtZero) {
    // 严苛训练(-1)+宵禁(-1) 净变化 -2；忠诚 1 → 0 下限钳制
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.sectPolicies.strictTraining = true;
    st.gameData.sectPolicies.curfew = true;

    Disciple d = baseDisciple("1");
    d.loyalty = 1;
    st.disciples.appendDisciple(d);

    system::runMonthSettlement(st, core->rng());
    EXPECT_EQ(0, st.disciples.materialize(0).loyalty);
}

// ── 步骤 6b：住所忠诚度 ────────────────────────────────────────────

TEST(MonthSettlementTest, ResidenceLoyaltyGolden) {
    auto core = makeCore(42);
    auto& st = core->state();
    ResidenceSlot slot;                    // isActive ≡ discipleId 非空
    slot.buildingInstanceId = "res1";
    slot.discipleId = "1";
    st.gameData.residenceSlots.push_back(slot);

    Disciple resident = baseDisciple("1");
    resident.loyalty = 50;
    Disciple outsider = baseDisciple("2");
    outsider.loyalty = 50;
    st.disciples.appendDisciple(resident);
    st.disciples.appendDisciple(outsider);

    crossMonth(core);

    EXPECT_EQ(51, st.disciples.materialize(0).loyalty);      // 住户 +1
    EXPECT_EQ(50, st.disciples.materialize(1).loyalty);      // 非住户不变
}

// ── 步骤 5：血炼完成检测 ───────────────────────────────────────────

TEST(MonthSettlementTest, BloodRefinementDueSettlesWithEvent) {
    // 到期（elapsed >= duration）：百分比累加 + 材料记录 + 清 statusData +
    // 事件记录（status 保持 REFINING 怪癖保留）；条目从 active 移除
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.statusData["buildingId"] = "pool-1";
    st.disciples.appendDisciple(d);

    BloodRefinementProgress progress;
    progress.discipleId = "1";
    progress.discipleName = "弟子1";
    progress.materialId = "mat-1";
    progress.startYear = 1;
    progress.startMonth = 1;
    progress.durationMonths = 2;
    progress.selectedStat = "hp";
    progress.bonusPercent = 5.0;
    st.gameData.activeBloodRefinements["pool-1"] = progress;
    st.gameData.gameMonth = 3;   // elapsed = 2 >= 2 到期

    system::runMonthSettlement(st, core->rng());

    EXPECT_TRUE(st.gameData.activeBloodRefinements.empty());
    EXPECT_DOUBLE_EQ(5.0, st.gameData.bloodRefinementPctTotals["1"].hpBonusPct);
    ASSERT_EQ(1u, st.gameData.bloodRefinements["1"].size());
    EXPECT_STREQ("mat-1", st.gameData.bloodRefinements["1"][0].c_str());
    EXPECT_EQ(0, st.disciples.materialize(0).statusData.count("buildingId"));
    ASSERT_EQ(1u, st.gameData.gameEventRecords.size());
    EXPECT_STREQ("blood_refinement",
                 st.gameData.gameEventRecords[0].eventType.c_str());
    EXPECT_NE(std::string::npos,
              st.gameData.gameEventRecords[0].summary.find("血练已完成"));
    EXPECT_NE(std::string::npos,
              st.gameData.gameEventRecords[0].summary.find("生命"));
}

TEST(MonthSettlementTest, BloodRefinementNotDueRetainedAndNaNDefended) {
    // 未到期保留；到期但 bonusPercent 为 NaN → 归零防御（NaN 无法被
    // coerceAtLeast 拦下，先 isFinite 归零）
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));

    BloodRefinementProgress pending;
    pending.discipleId = "1";
    pending.startYear = 1;
    pending.startMonth = 1;
    pending.durationMonths = 6;
    pending.selectedStat = "speed";
    pending.bonusPercent = 3.0;
    st.gameData.activeBloodRefinements["pool-a"] = pending;

    BloodRefinementProgress nanCase = pending;
    nanCase.durationMonths = 1;
    nanCase.selectedStat = "magicAttack";
    nanCase.bonusPercent = std::nan("");
    st.gameData.activeBloodRefinements["pool-b"] = nanCase;
    st.gameData.gameMonth = 2;   // elapsed = 1：pool-b 到期结算、pool-a(需6月)保留

    system::runMonthSettlement(st, core->rng());

    // pool-a 未到期保留；pool-b 到期结算且 NaN → 0
    EXPECT_EQ(1u, st.gameData.activeBloodRefinements.count("pool-a"));
    EXPECT_EQ(0u, st.gameData.activeBloodRefinements.count("pool-b"));
    EXPECT_DOUBLE_EQ(0.0,
                     st.gameData.bloodRefinementPctTotals["1"].magicAttackBonusPct);
    EXPECT_DOUBLE_EQ(0.0, st.gameData.bloodRefinementPctTotals["1"].speedBonusPct);
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

// ── 步骤 4f：道侣配对（SYSTEM RNG 审计） ───────────────────────────

TEST(MonthSettlementTest, PartnerMatchingPairedWithRngAudit) {
    // 1 男 × 1 女：恰 1 次 SYSTEM nextDouble；选中配对成功种子 → 双向写
    // partnerId + 婚姻事件；分区快照 == 预演一次 nextDouble 后快照
    const int64_t seed = findSeedWhereFirstSystemDraw(1, true);
    ASSERT_GT(seed, 0);
    auto core = makeCore(seed);
    auto& st = core->state();
    st.disciples.appendDisciple(adultDisciple("1", "male"));
    st.disciples.appendDisciple(adultDisciple("2", "female"));

    crossMonth(core);

    EXPECT_STREQ("2", st.disciples.materialize(0).partnerId.c_str());
    EXPECT_STREQ("1", st.disciples.materialize(1).partnerId.c_str());
    ASSERT_GE(st.gameData.gameEventRecords.size(), 1u);
    const bool hasMarriageEvent = [&] {
        for (const auto& e : st.gameData.gameEventRecords) {
            if (e.eventType == "marriage") return true;
        }
        return false;
    }();
    EXPECT_TRUE(hasMarriageEvent);

    auto probe = gamecore::rng::DeterministicRng::fromSeed(seed + 3);
    probe.nextDouble();
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kSystem).snapshot());
}

TEST(MonthSettlementTest, PartnerMatchingNotPairedWithRngAudit) {
    // 配对失败种子：仍消耗恰 1 次抽卡（组合通过过滤即抽），但不写任何字段
    const int64_t seed = findSeedWhereFirstSystemDraw(1, false);
    ASSERT_GT(seed, 0);
    auto core = makeCore(seed);
    auto& st = core->state();
    st.disciples.appendDisciple(adultDisciple("1", "male"));
    st.disciples.appendDisciple(adultDisciple("2", "female"));

    crossMonth(core);

    EXPECT_TRUE(st.disciples.materialize(0).partnerId.empty());
    EXPECT_TRUE(st.disciples.materialize(1).partnerId.empty());
    EXPECT_TRUE(st.gameData.gameEventRecords.empty());

    auto probe = gamecore::rng::DeterministicRng::fromSeed(seed + 3);
    probe.nextDouble();
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kSystem).snapshot());
}

TEST(MonthSettlementTest, PartnerMatchingSkipsUnderageAndPairedFemales) {
    // 未成年不入候选（零抽取）；已配对女性被跳过（pairedFemaleIds 不再抽）
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple boy = adultDisciple("1", "male");
    boy.age = 17;                          // 未成年：男候选为空 → 整体早退
    Disciple girl = adultDisciple("2", "female");
    girl.partnerId = "9";                  // 已有道侣：不入女候选
    st.disciples.appendDisciple(boy);
    st.disciples.appendDisciple(girl);

    crossMonth(core);

    auto probe = gamecore::rng::DeterministicRng::fromSeed(42 + 3);
    EXPECT_EQ(probe.snapshot(),              // 早退零抽取
              core->rng().getRng(rng::RngPartition::kSystem).snapshot());
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
    // 刷新生成未下沉 → lastRefreshMonth 保持不变
    EXPECT_EQ(0, st.gameData.worldLevelLastRefreshMonth);
    // RNG 审计：EXPLORATION 恰消耗 4 次 nextDouble
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kExploration).snapshot());
}

// ── 步骤 8c：灵矿月产 + 矿工忠诚衰减 ───────────────────────────────

TEST(MonthSettlementTest, SpiritMineProductionAndLoyaltyDecayGolden) {
    // 乘区：base=170×1 矿 × (1+采矿0.2)×(1+执事0.1) = 224.4 → round 224；
    // 差分结算（delta=1 月）入账 + 引导计数 + lastSettledMonth 推进；
    // 连续挖矿第 3 个月忠诚 -1 且计数归零
    const int64_t seed = 42;
    auto core = makeCore(seed);
    auto& st = core->state();
    Disciple miner = baseDisciple("1");
    miner.mining = 80;                      // (80-70)×0.02 = 0.2
    miner.morality = 90;                    // 执事：(90-80)×0.01 = 0.1
    miner.loyalty = 50;
    st.disciples.appendDisciple(miner);
    st.gameData.spiritStones = 0;           // 显式清零（模型默认开局 1000）

    SpiritMineSlot slot;
    slot.index = 0;
    slot.discipleId = "1";
    slot.consecutiveMiningMonths = 0;
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
    EXPECT_EQ(1, st.gameData.spiritMineSlots[0].consecutiveMiningMonths);
    EXPECT_EQ(50, st.disciples.materialize(0).loyalty);

    crossMonth(core);
    EXPECT_EQ(2 * kExpectedMonthlyRate, st.gameData.spiritStones);
    EXPECT_EQ(2, st.gameData.spiritMineSlots[0].consecutiveMiningMonths);

    crossMonth(core);
    EXPECT_EQ(3 * kExpectedMonthlyRate, st.gameData.spiritStones);
    EXPECT_EQ(0, st.gameData.spiritMineSlots[0].consecutiveMiningMonths);  // 归零
    EXPECT_EQ(49, st.disciples.materialize(0).loyalty);                                 // -1
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

    system::runMonthSettlement(st, core->rng());
    EXPECT_TRUE(st.gameData.isGameOver);
}

TEST(MonthSettlementTest, GameOverNotTriggeredWhenPlayerSectFree) {
    auto core = makeCore(42);
    auto& st = core->state();
    state::WorldSect playerSect;
    playerSect.id = "sect-p";
    playerSect.isPlayerSect = true;        // 本宗自由 → 仍控制宗门
    st.gameData.worldMapSects.push_back(playerSect);

    system::runMonthSettlement(st, core->rng());
    EXPECT_FALSE(st.gameData.isGameOver);
}

TEST(MonthSettlementTest, GameOverNotJudgedWithoutPlayerSect) {
    auto core = makeCore(42);
    auto& st = core->state();
    state::WorldSect aiSect;
    aiSect.id = "ai-1";                    // 只有 AI 宗门 → 不判定
    st.gameData.worldMapSects.push_back(aiSect);

    system::runMonthSettlement(st, core->rng());
    EXPECT_FALSE(st.gameData.isGameOver);
}

// ── 步骤 8 子事件序 + SYSTEM 抽取顺序锁 ────────────────────────────

TEST(MonthSettlementTest, RecruitResetAndSystemDrawOrderLock) {
    // 组合守护：同一月变事务内 SYSTEM 分区抽取顺序 = 灵田收获 roll（先，
    // Planting@214）→ 伴侣配对（后，Partner@240）；
    // recruitCountThisMonth 归零子事件同步生效
    const int64_t seed = 42;
    auto core = makeCore(seed);
    auto& st = core->state();
    GridBuildingData warehouse;
    warehouse.displayName = "仓库";
    warehouse.instanceId = "wh-1";
    st.gameData.placedBuildings.push_back(warehouse);
    st.gameData.spiritFieldPlants.push_back(maturePlant());
    st.disciples.appendDisciple(adultDisciple("1", "male"));
    st.disciples.appendDisciple(adultDisciple("2", "female"));
    st.gameData.recruitCountThisMonth = 7;

    // 预演执行序：nextInt(5)（收获）→ nextDouble（配对，1 男×1 女 = 1 组合）
    auto probe = gamecore::rng::DeterministicRng::fromSeed(seed + 3);
    const int32_t roll = probe.nextInt(5);
    const double pairDraw = probe.nextDouble();

    crossMonth(core);

    EXPECT_EQ(0, st.gameData.recruitCountThisMonth);
    // 顺序锁：若实现交换两步顺序或增减抽取次数，快照必不等
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kSystem).snapshot());
    // 各步结果与预演序列逐位对应
    if (roll > 0) {
        ASSERT_EQ(1u, st.seeds.size());
        EXPECT_EQ(roll, st.seeds[0].quantity);
    }
    EXPECT_EQ(pairDraw < kPairingProbability,
              !st.disciples.materialize(0).partnerId.empty());
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

// ── S8 子事件 8：侦察信息过期清理（批 10-1）────────────────────────

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

    system::runMonthSettlement(st, core->rng());

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

    system::runMonthSettlement(st, core->rng());

    // 无过期 → 零写入（Lazy 门控等价）
    ASSERT_EQ(1u, st.gameData.scoutInfo.size());
    EXPECT_EQ("青岚宗", st.gameData.scoutInfo.at("ai-1").sectName);
    EXPECT_EQ("ai-1", st.gameData.sectDetails.at("ai-1").scoutInfo.sectId);
    EXPECT_TRUE(st.gameData.worldMapSects[0].isKnown);
}

// ── S8 子事件 4：月度叛逃检测（批 10-2）────────────────────────────

/// 系统分区黄金序列：seed+3 播种的独立预演（RNG 审计方法同文件头说明）
gamecore::rng::DeterministicRng sysReplica(int64_t seed) {
    return gamecore::rng::DeterministicRng::fromSeed(seed + 3);
}

TEST(MonthSettlementTest, DesertionHerdGateBlocksAndZeroDraws) {
    auto core = makeCore(42);
    auto& st = core->state();
    // 两弟子忠诚 50 → 平均 50 不低于阈值 50 → 门控拦截，零抽取
    for (const char* id : {"1", "2"}) {
        Disciple d = adultDisciple(id, "male");
        d.loyalty = 50;
        st.disciples.appendDisciple(d);
    }
    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng());
    EXPECT_EQ(2u, st.disciples.size());
    EXPECT_EQ(0u, st.gameData.gameEventRecords.size());
    EXPECT_EQ(before, core->rng().exportStates());
}

TEST(MonthSettlementTest, DesertionEscapeGolden) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 1;
    // 单弟子：IDLE（默认）、忠诚 0 → 叛逃概率 (30-0)×0.01=0.30；
    // 入伍月 0 → 2×12+1-0=25 ≥ 12 保护期；无长老/政策 → 捕获率 0
    // → 抽 2 次时必走逃脱（第二次抽取 < 0 不可能）
    Disciple d = baseDisciple("1");
    d.gender = "male";
    d.loyalty = 0;
    d.recruitedMonth = 0;
    state::EquipmentInstance eq;
    eq.id = "eq-1"; eq.name = "铁剑"; eq.rarity = 1; eq.slot = "WEAPON";
    st.equipmentInstances.push_back(eq);
    d.weaponId = "eq-1";
    state::ManualInstance mn;
    mn.id = "mn-1"; mn.name = "青云心法"; mn.rarity = 1;
    st.manualInstances.push_back(mn);
    d.manualIds.push_back("mn-1");
    st.disciples.appendDisciple(d);

    auto sys = sysReplica(42);
    const double d1 = sys.nextDouble();
    const bool triggered = d1 < 0.30;
    if (triggered) sys.nextDouble();   // 第二次抽取（捕获判定）

    system::runMonthSettlement(st, core->rng());

    if (triggered) {
        EXPECT_EQ(0u, st.disciples.size());
        EXPECT_TRUE(st.equipmentInstances.empty());
        EXPECT_TRUE(st.manualInstances.empty());
        EXPECT_EQ(1, st.gameData.annualDesertedDisciples);
        ASSERT_EQ(1u, st.gameData.gameEventRecords.size());
        EXPECT_EQ("desertion", st.gameData.gameEventRecords[0].eventType);
    } else {
        EXPECT_EQ(1u, st.disciples.size());
        EXPECT_EQ(0, st.gameData.annualDesertedDisciples);
    }
    // SYSTEM 分区快照锁：d1（+ 触发时的 d2）与执行序逐位一致
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, DesertionCaptureGoldenWithElderAndPolicy) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 1;
    st.gameData.sectPolicies.rewardPunish = true;   // +0.30
    // 长老：智力 200 → (200-50)×0.01=1.5，忠诚 50 不在 at-risk
    Disciple elder = baseDisciple("9");
    elder.gender = "male";
    elder.intelligence = 200;
    elder.loyalty = 50;
    st.disciples.appendDisciple(elder);
    // 叛逃者：忠诚 0 → 概率 0.30
    Disciple d = baseDisciple("1");
    d.gender = "male";
    d.loyalty = 0;
    d.recruitedMonth = 0;
    st.disciples.appendDisciple(d);
    st.gameData.elderSlots.lawEnforcementElder = "9";
    // 捕获率 = 1.5 + 0.3 = 1.8 → clamp 1.0 → 第二次抽取必捕获

    auto sys = sysReplica(42);
    const double d1 = sys.nextDouble();
    const bool triggered = d1 < 0.30;
    if (triggered) sys.nextDouble();

    system::runMonthSettlement(st, core->rng());

    if (triggered) {
        EXPECT_EQ(2u, st.disciples.size());
        // 叛逃者被 remove + 末尾重插（行序 [长老, 叛逃者] 保持）
        const auto idx = gamecore::system::settle_util::indexById(st.disciples);
        const auto row = idx.at(1);
        EXPECT_EQ("REFLECTING", st.disciples.statuses[row]);
        EXPECT_EQ("2", st.disciples.materialize(row).statusData.at("reflectionStartYear"));
        EXPECT_EQ("7", st.disciples.materialize(row).statusData.at("reflectionEndYear"));
        EXPECT_EQ(1, st.gameData.guideCounters.at("discipleImprisoned"));
        ASSERT_EQ(1u, st.gameData.gameEventRecords.size());
        EXPECT_EQ("desertion_caught", st.gameData.gameEventRecords[0].eventType);
        EXPECT_EQ(0, st.gameData.annualDesertedDisciples);
    } else {
        EXPECT_EQ(2u, st.disciples.size());
        EXPECT_EQ(0u, st.gameData.gameEventRecords.size());
    }
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

// ── S8 子事件 3：月度偷盗兜底（批 10-3）────────────────────────────
//
// 偷盗常量（GameConfig.LawEnforcementConfig / PolicyConfig）
constexpr int32_t kTheftMoralityThreshold = 30;
constexpr double kTheftProbPerPoint = 0.01;

TEST(MonthSettlementTest, TheftSucceedsGoldenSequence) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 1;
    st.gameData.spiritStones = 10000;
    // 小偷：道德 0 → 候选（概率 (30-0)×0.01=0.30，种子 42 首抽 0.286 必触发）；
    // 忠诚 30 → 偷后叛逃概率 0 且不低于叛逃阈值 30（子事件 4 零抽取）
    Disciple thief = baseDisciple("1");
    thief.morality = 0;
    thief.loyalty = 30;
    thief.recruitedMonth = 0;
    st.disciples.appendDisciple(thief);
    // 同门：默认道德 50 非候选；忠诚 50 → 平均 40 < 50 过从众门控
    st.disciples.appendDisciple(baseDisciple("2"));

    auto sys = sysReplica(42);
    const bool attempted = sys.nextDouble() < 0.30;
    if (attempted) {
        sys.nextDouble();   // Step 2 捕获（捕获率 0 → 必不中）
        sys.nextDouble();   // 金额随机波动（clamp 后恒 1000）
        sys.nextDouble();   // 偷后叛逃（概率 0 → 不叛逃）
    }

    system::runMonthSettlement(st, core->rng());

    // 判定标记先于概率抽取——两分支一致（弟子年标记 + 月度计数 +1）
    EXPECT_EQ(1, st.gameData.theftJudgementsThisMonth);
    EXPECT_EQ(2, st.disciples.lastTheftJudgementYears[0]);
    if (attempted) {
        EXPECT_EQ(9000, st.gameData.spiritStones);
        EXPECT_EQ(1, st.gameData.annualTheftCount);
        ASSERT_EQ(1u, st.gameData.gameEventRecords.size());
        EXPECT_EQ("warehouse_theft", st.gameData.gameEventRecords[0].eventType);
        EXPECT_EQ(std::string("宗门仓库被盗，损失1000灵石"),
                  st.gameData.gameEventRecords[0].summary);
        const auto idx = gamecore::system::settle_util::indexById(st.disciples);
        const auto row = idx.at(1);
        EXPECT_EQ(1000, st.disciples.materialize(row).storageBagSpiritStones);
        EXPECT_TRUE(st.disciples.materialize(row).storageBagItems.empty());
    } else {
        EXPECT_EQ(10000, st.gameData.spiritStones);
        EXPECT_EQ(0, st.gameData.annualTheftCount);
        EXPECT_EQ(0u, st.gameData.gameEventRecords.size());
    }
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, TheftCaptureGoldenWithElder) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 1;
    st.gameData.spiritStones = 10000;
    st.gameData.elderSlots.lawEnforcementElder = "9";
    // 长老：智力 200 → 捕获率 (200-50)×0.01 = 1.5 → clamp 1.0；忠诚 40
    // （平均 45 过门控；≥ 30 非叛逃候选）；道德 50 非偷盗候选
    Disciple elder = baseDisciple("9");
    elder.intelligence = 200;
    elder.loyalty = 40;
    st.disciples.appendDisciple(elder);
    Disciple thief = baseDisciple("1");
    thief.morality = 0;
    thief.loyalty = 50;
    thief.recruitedMonth = 0;
    st.disciples.appendDisciple(thief);

    auto sys = sysReplica(42);
    const bool attempted = sys.nextDouble() < 0.30;   // 首抽 0.286 必触发
    if (attempted) sys.nextDouble();   // 捕获判定：< 1.0 必捕获

    system::runMonthSettlement(st, core->rng());

    EXPECT_EQ(1, st.gameData.theftJudgementsThisMonth);
    if (attempted) {
        EXPECT_EQ(2u, st.disciples.size());   // 原位状态改写（不 remove/重插）
        const auto idx = gamecore::system::settle_util::indexById(st.disciples);
        const auto row = idx.at(1);
        EXPECT_EQ("REFLECTING", st.disciples.statuses[row]);
        const auto d = st.disciples.materialize(row);
        EXPECT_EQ(std::string("2"), d.statusData.at("reflectionStartYear"));
        EXPECT_EQ(std::string("7"), d.statusData.at("reflectionEndYear"));
        // 异于叛逃捕获：无引导计数
        EXPECT_TRUE(st.gameData.guideCounters.find("discipleImprisoned") ==
                    st.gameData.guideCounters.end());
        ASSERT_EQ(1u, st.gameData.gameEventRecords.size());
        EXPECT_EQ("theft_caught", st.gameData.gameEventRecords[0].eventType);
        EXPECT_EQ(std::string("弟子1偷盗被捕"),
                  st.gameData.gameEventRecords[0].summary);
        EXPECT_EQ("1", st.gameData.gameEventRecords[0].relatedEntityId);
        EXPECT_EQ(10000, st.gameData.spiritStones);   // 未得手
        EXPECT_EQ(0, st.gameData.annualTheftCount);
    } else {
        EXPECT_EQ(0u, st.gameData.gameEventRecords.size());
    }
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, TheftDesertAfterTheftGolden) {
    // 扫描种子：首抽 < 0.30（偷盗尝试触发）且第 4 抽 < 0.30（偷后叛逃触发）
    int64_t seed = -1;
    for (int64_t s = 42; s < 42 + 100000; ++s) {
        auto probe = gamecore::rng::DeterministicRng::fromSeed(s + 3);
        if (probe.nextDouble() >= 0.30) continue;   // d1 偷盗尝试
        probe.nextDouble();                          // d2 捕获（率 0）
        probe.nextDouble();                          // d3 金额波动
        if (probe.nextDouble() < 0.30) { seed = s; break; }   // d4 偷后叛逃
    }
    ASSERT_GE(seed, 0);
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 1;
    st.gameData.spiritStones = 10000;
    // 小偷：忠诚 0 → 偷后叛逃概率 (30-0)×0.01 = 0.30；带装备/功法实例
    Disciple thief = baseDisciple("1");
    thief.morality = 0;
    thief.loyalty = 0;
    thief.recruitedMonth = 0;
    state::EquipmentInstance eq;
    eq.id = "eq-1"; eq.name = "铁剑"; eq.rarity = 1; eq.slot = "WEAPON";
    st.equipmentInstances.push_back(eq);
    thief.weaponId = "eq-1";
    state::ManualInstance mn;
    mn.id = "mn-1"; mn.name = "青云心法"; mn.rarity = 1;
    st.manualInstances.push_back(mn);
    thief.manualIds.push_back("mn-1");
    st.disciples.appendDisciple(thief);
    // 同门：忠诚 50 → 平均 25 过门控；非候选、非叛逃候选
    st.disciples.appendDisciple(baseDisciple("2"));

    auto sys = sysReplica(seed);
    const bool attempted = sys.nextDouble() < 0.30;
    bool deserted = false;
    if (attempted) {
        sys.nextDouble();                   // Step 2 捕获（率 0 → 不中）
        sys.nextDouble();                   // 金额波动（clamp 1000）
        deserted = sys.nextDouble() < 0.30; // 偷后叛逃
    }

    system::runMonthSettlement(st, core->rng());

    EXPECT_EQ(1, st.gameData.theftJudgementsThisMonth);
    if (attempted && deserted) {
        EXPECT_EQ(1u, st.disciples.size());   // 小偷被移除（储物袋随行）
        EXPECT_TRUE(st.equipmentInstances.empty());
        EXPECT_TRUE(st.manualInstances.empty());
        EXPECT_EQ(1, st.gameData.annualDesertedDisciples);
        ASSERT_EQ(2u, st.gameData.gameEventRecords.size());
        EXPECT_EQ("warehouse_theft", st.gameData.gameEventRecords[0].eventType);
        EXPECT_EQ("theft_desertion", st.gameData.gameEventRecords[1].eventType);
        EXPECT_EQ(std::string("弟子1偷盗后叛逃"),
                  st.gameData.gameEventRecords[1].summary);
        EXPECT_EQ(9000, st.gameData.spiritStones);
        EXPECT_EQ(1, st.gameData.annualTheftCount);
    } else if (attempted) {
        EXPECT_EQ(2u, st.disciples.size());
        EXPECT_EQ(0, st.gameData.annualDesertedDisciples);
        ASSERT_EQ(1u, st.gameData.gameEventRecords.size());
        EXPECT_EQ(9000, st.gameData.spiritStones);
        EXPECT_EQ(1, st.gameData.annualTheftCount);
    } else {
        EXPECT_EQ(2u, st.disciples.size());
        EXPECT_EQ(0u, st.gameData.gameEventRecords.size());
        EXPECT_EQ(10000, st.gameData.spiritStones);
    }
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, TheftWarehouseGarrisonCaught) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 1;
    st.gameData.spiritStones = 10000;
    // 仓库 + 活跃驻守（高智力守卫）
    GridBuildingData wh;
    wh.displayName = "仓库";
    wh.instanceId = "wh-1";
    st.gameData.placedBuildings.push_back(wh);
    state::WarehouseGarrisonSlot garrison;
    garrison.buildingInstanceId = "wh-1";
    garrison.discipleId = "9";
    st.gameData.warehouseGarrisons.push_back(garrison);
    Disciple guard = baseDisciple("9");
    guard.intelligence = 200;
    guard.loyalty = 40;
    st.disciples.appendDisciple(guard);
    // 小偷智力 50 ≤ 守卫 200 → 被捕；无长老/政策 → 捕获率 0（Step 2 必落空）
    Disciple thief = baseDisciple("1");
    thief.morality = 0;
    thief.loyalty = 50;
    thief.recruitedMonth = 0;
    st.disciples.appendDisciple(thief);

    auto sys = sysReplica(42);
    const bool attempted = sys.nextDouble() < 0.30;   // 首抽 0.286 必触发
    if (attempted) {
        sys.nextDouble();          // Step 2 捕获（率 0 → 不中）
        sys.nextInt(1);            // Step 3 仓库选取（唯一仓库）
    }

    system::runMonthSettlement(st, core->rng());

    EXPECT_EQ(1, st.gameData.theftJudgementsThisMonth);
    if (attempted) {
        const auto idx = gamecore::system::settle_util::indexById(st.disciples);
        const auto row = idx.at(1);
        EXPECT_EQ("REFLECTING", st.disciples.statuses[row]);
        ASSERT_EQ(1u, st.gameData.gameEventRecords.size());
        EXPECT_EQ("theft_caught", st.gameData.gameEventRecords[0].eventType);
        EXPECT_EQ(10000, st.gameData.spiritStones);   // 未得手
        EXPECT_EQ(0, st.gameData.annualTheftCount);
    } else {
        EXPECT_EQ(0u, st.gameData.gameEventRecords.size());
    }
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, TheftItemSelectionAndStoreDecrementGolden) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 1;
    st.gameData.spiritStones = 10000;
    Disciple thief = baseDisciple("1");   // realm 9：基准 32M/20000 = 1600 容量
    thief.morality = 0;                    // 概率 0.30，种子 42 首抽 0.286 必触发
    thief.loyalty = 50;
    thief.recruitedMonth = 0;
    st.disciples.appendDisciple(thief);
    Disciple mate = baseDisciple("2");
    mate.loyalty = 40;                     // 平均 (50+40)/2=45 过从众门控
    st.disciples.appendDisciple(mate);
    // 物品池（展开序：材料 → 丹药）：m1×3 + m2×2 + p1×1 = 6 单位
    // 容量 1600 ≥ 6 → 全池抽空，分组计数固定 {m1:3, m2:2, p1:1}
    state::Material m1; m1.id = "m1"; m1.name = "妖皮"; m1.rarity = 1; m1.quantity = 3;
    state::Material m2; m2.id = "m2"; m2.name = "妖骨"; m2.rarity = 2; m2.quantity = 2;
    st.materials.push_back(m1);
    st.materials.push_back(m2);
    state::Pill p1; p1.id = "p1"; p1.name = "回气丹"; p1.rarity = 3; p1.quantity = 1;
    st.pills.push_back(p1);

    auto sys = sysReplica(42);
    std::vector<std::string> pool = {"m1", "m1", "m1", "m2", "m2", "p1"};
    const bool attempted = sys.nextDouble() < 0.30;
    std::vector<std::string> picked;
    if (attempted) {
        sys.nextDouble();   // Step 2 捕获（率 0）
        sys.nextDouble();   // 金额波动（clamp 1000）
        for (std::size_t k = pool.size(); k > 0; --k) {
            const int32_t pick = sys.nextInt(static_cast<int32_t>(k));
            picked.push_back(pool[static_cast<std::size_t>(pick)]);
            pool.erase(pool.begin() + pick);
        }
        sys.nextDouble();   // 偷后叛逃（概率 0）
    }

    system::runMonthSettlement(st, core->rng());

    EXPECT_EQ(1, st.gameData.theftJudgementsThisMonth);
    if (attempted) {
        // 首现序分组（Kotlin groupBy LinkedHashMap 语义）
        std::vector<std::string> order;
        std::map<std::string, int32_t> counts;
        for (const auto& id : picked) {
            if (counts.find(id) == counts.end()) order.push_back(id);
            counts[id] += 1;
        }
        EXPECT_EQ(9000, st.gameData.spiritStones);
        EXPECT_EQ(1, st.gameData.annualTheftCount);
        ASSERT_EQ(1u, st.gameData.gameEventRecords.size());
        EXPECT_EQ(std::string("宗门仓库被盗，损失1000灵石（含3种物品）"),
                  st.gameData.gameEventRecords[0].summary);
        // 仓库全数被盗 → 0 数量条目过滤
        EXPECT_TRUE(st.materials.empty());
        EXPECT_TRUE(st.pills.empty());
        // 储物袋按首现序入账
        const auto idx = gamecore::system::settle_util::indexById(st.disciples);
        const auto row = idx.at(1);
        const auto bag = st.disciples.materialize(row).storageBagItems;
        ASSERT_EQ(order.size(), bag.size());
        for (std::size_t k = 0; k < order.size(); ++k) {
            EXPECT_EQ(order[k], bag[k].itemId);
            EXPECT_EQ(counts.at(order[k]), bag[k].quantity);
            EXPECT_EQ(2, bag[k].obtainedYear);
            EXPECT_EQ(1, bag[k].obtainedMonth);
        }
    } else {
        EXPECT_EQ(10000, st.gameData.spiritStones);
        EXPECT_EQ(0u, st.gameData.gameEventRecords.size());
        EXPECT_EQ(1u, st.materials.size() + st.pills.size());
        EXPECT_EQ(3, st.materials[0].quantity);
    }
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, TheftAnnualCapBlocksButStillResets) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 1;
    st.gameData.spiritStones = 10000;
    st.gameData.annualTheftCount = 3;          // 年度成功上限已满
    st.gameData.theftJudgementsThisMonth = 2;
    Disciple thief = baseDisciple("1");
    thief.morality = 10;
    thief.loyalty = 30;
    st.disciples.appendDisciple(thief);
    st.disciples.appendDisciple(baseDisciple("2"));

    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng());

    EXPECT_EQ(0, st.gameData.theftJudgementsThisMonth);   // 归零无条件执行
    EXPECT_EQ(3, st.gameData.annualTheftCount);
    EXPECT_EQ(0u, st.gameData.gameEventRecords.size());
    EXPECT_EQ(before, core->rng().exportStates());        // 零抽取
}

TEST(MonthSettlementTest, TheftHerdGateBlocksButStillResets) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 1;
    st.gameData.spiritStones = 10000;
    st.gameData.theftJudgementsThisMonth = 5;
    // 平均忠诚 50 不低于阈值 50 → 门控拦截
    st.disciples.appendDisciple(baseDisciple("1"));
    st.disciples.appendDisciple(baseDisciple("2"));

    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng());

    EXPECT_EQ(0, st.gameData.theftJudgementsThisMonth);   // 归零在门控之前
    EXPECT_EQ(0u, st.gameData.gameEventRecords.size());
    EXPECT_EQ(before, core->rng().exportStates());
}

TEST(MonthSettlementTest, TheftNoCandidateOrProtectedZeroDraws) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 1;                  // currentMonth = 25
    st.gameData.spiritStones = 10000;
    st.gameData.theftJudgementsThisMonth = 7;
    // 非候选（道德 50）
    st.disciples.appendDisciple(baseDisciple("1"));
    // 候选但保护期未满（上月入伍：25-24=1 < 12）——计入门控、排除于候选
    Disciple protected_ = baseDisciple("2");
    protected_.morality = 10;
    protected_.recruitedMonth = 24;
    st.disciples.appendDisciple(protected_);

    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng());

    EXPECT_EQ(0, st.gameData.theftJudgementsThisMonth);   // 归零执行，无标记递增
    EXPECT_EQ(0u, st.gameData.gameEventRecords.size());
    EXPECT_EQ(before, core->rng().exportStates());        // 零抽取
}

// ── S8 子事件 3：月度偷盗兜底（批 10-3）────────────────────────────

/// 偷盗链 SYSTEM 黄金序列种子扫描：从 begin 起首个满足 pred 的种子
///（pred 内按执行序连续抽取副本）
int64_t findTheftSeed(
    int64_t begin,
    const std::function<bool(gamecore::rng::DeterministicRng&)>& pred) {
    for (int64_t s = begin; s < begin + 100000; ++s) {
        auto probe = sysReplica(s);
        if (pred(probe)) return s;
    }
    return -1;
}

/// 偷盗候选弟子：忠诚 40（从众门开 + 不落入叛逃 at-risk）、IDLE、
/// 入伍月 0（保护期已过）
Disciple theftDisciple(const std::string& id) {
    Disciple d = baseDisciple(id);
    d.loyalty = 40;
    d.recruitedMonth = 0;
    return d;
}

TEST(MonthSettlementTest, TheftResetsCounterAndHerdGateBlocksZeroDraws) {
    // 计数器归零无条件先于门控；平均忠诚 50 不低于阈值 → 门控拦截零抽取
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.theftJudgementsThisMonth = 7;
    Disciple d = baseDisciple("1");
    d.loyalty = 50;
    st.disciples.appendDisciple(d);
    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng());
    EXPECT_EQ(0, st.gameData.theftJudgementsThisMonth);
    EXPECT_EQ(before, core->rng().exportStates());
}

TEST(MonthSettlementTest, TheftNoCandidateZeroDraws) {
    // 门控开启（平均 40 < 50）但无道德候选（50 ≥ 30）→ hasCandidate false
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.theftJudgementsThisMonth = 7;
    st.gameData.spiritStones = 10000;
    Disciple d = theftDisciple("1");
    st.disciples.appendDisciple(d);
    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng());
    EXPECT_EQ(0, st.gameData.theftJudgementsThisMonth);
    EXPECT_EQ(before, core->rng().exportStates());
}

TEST(MonthSettlementTest, TheftProtectionMonthsBlockCandidatesZeroDraws) {
    // hasCandidate 无保护期检查（门过）→ 候选收集含保护期 → 空 → 零抽取零标记
    //（对拍口径：Kotlin 基线读绝对月 13 / C++ 事务内 14——recruitedMonth=13
    //  使双端差值均 < 12，场景规避 S-14 同族分歧）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 10000;
    Disciple d = theftDisciple("1");
    d.morality = 20;
    d.recruitedMonth = 13;   // 绝对月 13 → 差 0 < 12 保护期
    st.disciples.appendDisciple(d);
    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng());
    EXPECT_EQ(0, st.gameData.theftJudgementsThisMonth);
    EXPECT_EQ(0, st.disciples.lastTheftJudgementYears[0]);
    EXPECT_EQ(before, core->rng().exportStates());
}

TEST(MonthSettlementTest, TheftAnnualCapBlocksZeroDraws) {
    // 年度成功偷盗已达上限 → 全年停止判定（门控/候选前短路）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 10000;
    st.gameData.annualTheftCount = 3;
    Disciple d = theftDisciple("1");
    d.morality = 20;
    st.disciples.appendDisciple(d);
    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng());
    EXPECT_EQ(0, st.gameData.theftJudgementsThisMonth);
    EXPECT_EQ(before, core->rng().exportStates());
}

TEST(MonthSettlementTest, TheftFailedAttemptStillMarksJudgement) {
    // 标记判定先于概率抽取：偷盗未遂（d1 ≥ 0.30）同样计数 + 年判定标记
    const int64_t seed = findTheftSeed(7000, [](auto& r) {
        return r.nextDouble() >= 0.30;   // d1
    });
    ASSERT_GE(seed, 0);
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.spiritStones = 10000;
    Disciple d = theftDisciple("1");
    d.morality = 0;   // 概率 (30-0)×0.01 = 0.30
    st.disciples.appendDisciple(d);

    auto sys = sysReplica(seed);
    sys.nextDouble();   // d1（未遂）

    system::runMonthSettlement(st, core->rng());

    EXPECT_EQ(1, st.gameData.theftJudgementsThisMonth);
    EXPECT_EQ(1, st.disciples.lastTheftJudgementYears[0]);
    EXPECT_EQ(0, st.gameData.annualTheftCount);
    EXPECT_TRUE(st.gameData.gameEventRecords.empty());
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, TheftCaptureGoldenWithPolicy) {
    // 捕获率 0.30（赏罚分明）：d1 < 0.30（尝试）+ d2 < 0.30（捕获）→
    // 原位 REFLECTING（不 remove+重插、无引导计数）+ theft_caught 事件
    const int64_t seed = findTheftSeed(7000, [](auto& r) {
        const double d1 = r.nextDouble();
        const double d2 = r.nextDouble();
        return d1 < 0.30 && d2 < 0.30;
    });
    ASSERT_GE(seed, 0);
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.spiritStones = 10000;
    st.gameData.sectPolicies.rewardPunish = true;
    Disciple d = theftDisciple("1");
    d.morality = 0;
    st.disciples.appendDisciple(d);

    auto sys = sysReplica(seed);
    sys.nextDouble();   // d1 尝试
    sys.nextDouble();   // d2 捕获

    system::runMonthSettlement(st, core->rng());

    EXPECT_EQ(1u, st.disciples.size());   // 原位改写：行数与行序均不变
    const std::size_t row = st.disciples.idToRow.at("1");
    EXPECT_EQ(0, row);
    EXPECT_EQ("REFLECTING", st.disciples.statuses[row]);
    EXPECT_EQ("1", st.disciples.materialize(row).statusData.at("reflectionStartYear"));
    EXPECT_EQ("6", st.disciples.materialize(row).statusData.at("reflectionEndYear"));
    EXPECT_EQ(1, st.gameData.theftJudgementsThisMonth);
    EXPECT_EQ(1, st.disciples.lastTheftJudgementYears[row]);
    EXPECT_TRUE(st.gameData.guideCounters.empty());   // 与叛逃捕获的差异点
    ASSERT_EQ(1u, st.gameData.gameEventRecords.size());
    EXPECT_EQ("theft_caught", st.gameData.gameEventRecords[0].eventType);
    EXPECT_EQ("弟子1偷盗被捕", st.gameData.gameEventRecords[0].summary);
    // 未得手；但步骤 1 政策月费先行扣除（赏罚分明 3000）
    EXPECT_EQ(7000, st.gameData.spiritStones);
    EXPECT_EQ(0, st.gameData.annualTheftCount);
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, TheftGarrisonGuardCatchGolden) {
    // 仓库+活跃驻守：d3 = nextInt(1) 选仓；守卫智力 50 ≥ 小偷 10 → 抓捕，
    // 无金额/物品/叛逃抽取（捕获截断）
    const int64_t seed = findTheftSeed(7000, [](auto& r) {
        return r.nextDouble() < 0.30;   // 仅 d1 谓词
    });
    ASSERT_GE(seed, 0);
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.spiritStones = 10000;
    GridBuildingData warehouse;
    warehouse.displayName = "仓库";
    warehouse.instanceId = "wh-1";
    st.gameData.placedBuildings.push_back(warehouse);
    state::WarehouseGarrisonSlot garrison;
    garrison.buildingInstanceId = "wh-1";
    garrison.discipleId = "2";
    st.gameData.warehouseGarrisons.push_back(garrison);
    Disciple guard = theftDisciple("2");
    guard.intelligence = 50;
    st.disciples.appendDisciple(guard);
    Disciple d = theftDisciple("1");
    d.morality = 0;
    d.intelligence = 10;
    st.disciples.appendDisciple(d);

    auto sys = sysReplica(seed);
    sys.nextDouble();   // d1 尝试
    sys.nextDouble();   // d2 捕获（率 0 → 必不捕获）
    sys.nextInt(1);     // d3 仓库选取

    system::runMonthSettlement(st, core->rng());

    const std::size_t thiefRow = st.disciples.idToRow.at("1");
    EXPECT_EQ("REFLECTING", st.disciples.statuses[thiefRow]);
    const std::size_t guardRow = st.disciples.idToRow.at("2");
    EXPECT_EQ("IDLE", st.disciples.statuses[guardRow]);
    ASSERT_EQ(1u, st.gameData.gameEventRecords.size());
    EXPECT_EQ("theft_caught", st.gameData.gameEventRecords[0].eventType);
    EXPECT_EQ(10000, st.gameData.spiritStones);
    EXPECT_EQ(0, st.gameData.annualTheftCount);
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, TheftSuccessGoldenSpiritStonesOnly) {
    // realm 1（渡劫）基准 500；stats.speed = 境界基准身法 18000（Realm 配置，
    // 非弟子 baseSpeed 列）→ 身法加成 (18000-50)×0.005 = 89.75 →
    // rawAmount = 500×90.75×(0.8+d4×0.4) ≈ 3.6 万 → clamp [100, 1000] 恒 1000；
    // 空池跳过物品抽取；忠诚 40 → 叛逃概率 0（抽取不叛逃）；抽取序 d1 d2 d3 d4
    const int64_t seed = findTheftSeed(7000, [](auto& r) {
        return r.nextDouble() < 0.30;   // 仅 d1 谓词
    });
    ASSERT_GE(seed, 0);
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.spiritStones = 10000;
    Disciple d = theftDisciple("1");
    d.morality = 0;
    d.realm = 1;
    st.disciples.appendDisciple(d);

    auto sys = sysReplica(seed);
    sys.nextDouble();                   // d1 尝试
    sys.nextDouble();                   // d2 捕获（率 0 → 未捕获）
    sys.nextDouble();                   // d3 金额波动（clamp 后恒 1000）
    sys.nextDouble();                   // d4 叛逃（概率 0 → 未叛逃）
    const int64_t expectedAmount = 1000;

    system::runMonthSettlement(st, core->rng());

    EXPECT_EQ(10000 - expectedAmount, st.gameData.spiritStones);
    EXPECT_EQ(expectedAmount, st.disciples.storageBagSpiritStones[0]);
    EXPECT_TRUE(st.disciples.storageBagItems[0].empty());
    EXPECT_EQ(1, st.gameData.annualTheftCount);
    EXPECT_EQ(1, st.gameData.theftJudgementsThisMonth);
    ASSERT_EQ(1u, st.gameData.gameEventRecords.size());
    EXPECT_EQ("warehouse_theft", st.gameData.gameEventRecords[0].eventType);
    EXPECT_EQ("宗门仓库被盗，损失" + std::to_string(expectedAmount) + "灵石",
              st.gameData.gameEventRecords[0].summary);
    EXPECT_EQ(1u, st.disciples.size());   // 未叛逃
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, TheftSuccessStealsWarehouseItemGolden) {
    // 仓库（无驻守）+ 材料×2 + 丹药×1 → 池 3 条目；finalCount 269 → 全池抽空；
    // 抽取序 d1 d2 d3(nextInt(1)) d4 金额 + 物品×3 + 叛逃；物品扣除 + 储物袋入袋
    const int64_t seed = findTheftSeed(7000, [](auto& r) {
        return r.nextDouble() < 0.30;   // 仅 d1 谓词
    });
    ASSERT_GE(seed, 0);
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.spiritStones = 10000;
    GridBuildingData warehouse;
    warehouse.displayName = "仓库";
    warehouse.instanceId = "wh-1";
    st.gameData.placedBuildings.push_back(warehouse);
    state::Material mat;
    mat.id = "m1"; mat.name = "铁矿石"; mat.rarity = 2; mat.quantity = 2;
    st.materials.push_back(mat);
    state::Pill pill;
    pill.id = "p1"; pill.name = "回气丹"; pill.rarity = 1; pill.quantity = 1;
    st.pills.push_back(pill);
    Disciple d = theftDisciple("1");
    d.morality = 0;
    d.realm = 1;
    st.disciples.appendDisciple(d);

    // 预演抽取序与池命中（池展开序：材料×2 → 丹药×1；抽取即移除；
    // realm 1 身法加成 (18000-50)×0.005×3 = 269 → finalCount 269 ≥ 3 全池抽空）
    auto sys = sysReplica(seed);
    sys.nextDouble();                    // d1 尝试
    sys.nextDouble();                    // d2 捕获（率 0 → 未捕获）
    sys.nextInt(1);                      // d3 仓库选取
    sys.nextDouble();                    // d4 金额波动（clamp 后恒 1000）
    const int64_t expectedAmount = 1000;
    std::vector<std::string> pool = {"m1", "m1", "p1"};
    std::vector<std::string> picked;
    for (std::size_t k = pool.size(); k > 0; --k) {
        const int32_t pick = sys.nextInt(static_cast<int32_t>(k));
        picked.push_back(pool[static_cast<std::size_t>(pick)]);
        pool.erase(pool.begin() + pick);
    }
    sys.nextDouble();                    // 叛逃（概率 0 → 未叛逃）
    // 首现序分组（Kotlin groupBy LinkedHashMap 语义）
    std::vector<std::string> order;
    std::map<std::string, int32_t> counts;
    for (const auto& id : picked) {
        if (counts.find(id) == counts.end()) order.push_back(id);
        counts[id] += 1;
    }
    ASSERT_EQ(2u, order.size());         // m1 与 p1 两名命中（全池抽空）

    system::runMonthSettlement(st, core->rng());

    EXPECT_EQ(10000 - expectedAmount, st.gameData.spiritStones);
    const auto& bag = st.disciples.storageBagItems[0];
    ASSERT_EQ(order.size(), bag.size());
    for (std::size_t k = 0; k < order.size(); ++k) {
        EXPECT_EQ(order[k], bag[k].itemId);
        EXPECT_EQ(counts.at(order[k]), bag[k].quantity);
        EXPECT_EQ(1, bag[k].obtainedYear);
        EXPECT_EQ(1, bag[k].obtainedMonth);
    }
    EXPECT_EQ(expectedAmount, st.disciples.storageBagSpiritStones[0]);
    // 全池抽空 → 数量归零条目统一过滤删除
    EXPECT_TRUE(st.materials.empty());
    EXPECT_TRUE(st.pills.empty());
    EXPECT_EQ(1, st.gameData.annualTheftCount);
    ASSERT_EQ(1u, st.gameData.gameEventRecords.size());
    EXPECT_EQ("宗门仓库被盗，损失" + std::to_string(expectedAmount) + "灵石（含2种物品）",
              st.gameData.gameEventRecords[0].summary);
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, TheftDesertionAfterTheftGolden) {
    // 忠诚 0 → 叛逃概率 0.30：d6 < 0.30 → 偷盗后叛逃（装备/功法实例移除 +
    // 熟练度移除 + 弟子移除 + theft_desertion 事件 + 年度计数）；成功偷窃在前
    const int64_t seed = findTheftSeed(7000, [](auto& r) {
        const double d1 = r.nextDouble();
        r.nextDouble();               // d2
        r.nextDouble();               // d4
        const double d6 = r.nextDouble();
        return d1 < 0.30 && d6 < 0.30;
    });
    ASSERT_GE(seed, 0);
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.spiritStones = 10000;
    state::EquipmentInstance eq;
    eq.id = "eq-1"; eq.name = "铁剑"; eq.rarity = 1; eq.slot = "WEAPON";
    st.equipmentInstances.push_back(eq);
    state::ManualInstance mn;
    mn.id = "mn-1"; mn.name = "青云心法"; mn.rarity = 1;
    st.manualInstances.push_back(mn);
    st.gameData.manualProficiencies["1"].push_back(
        state::ManualProficiencyData{"mn-1", "青云心法", 10.0, 100, 1, 0});
    Disciple d = theftDisciple("1");
    d.morality = 0;
    d.loyalty = 0;   // 叛逃概率 0.30
    d.realm = 1;
    d.weaponId = "eq-1";
    d.manualIds.push_back("mn-1");
    st.disciples.appendDisciple(d);

    auto sys = sysReplica(seed);
    sys.nextDouble();   // d1
    sys.nextDouble();   // d2
    sys.nextDouble();   // d4
    sys.nextDouble();   // d6

    system::runMonthSettlement(st, core->rng());

    EXPECT_EQ(0u, st.disciples.size());   // 偷盗得手后叛逃离场（袋随弟子删除）
    EXPECT_TRUE(st.equipmentInstances.empty());
    EXPECT_TRUE(st.manualInstances.empty());
    EXPECT_EQ(0u, st.gameData.manualProficiencies.count("1"));
    EXPECT_EQ(1, st.gameData.annualTheftCount);       // 成功偷窃先于叛逃
    EXPECT_EQ(1, st.gameData.annualDesertedDisciples);
    ASSERT_EQ(2u, st.gameData.gameEventRecords.size());
    EXPECT_EQ("warehouse_theft", st.gameData.gameEventRecords[0].eventType);
    EXPECT_EQ("theft_desertion", st.gameData.gameEventRecords[1].eventType);
    EXPECT_EQ("弟子1偷盗后叛逃", st.gameData.gameEventRecords[1].summary);
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, TheftAmountUnderflowAbortsSubeventPreservingMark) {
    // S-14：灵石 500 → maxAmount 50 < THEFT_MIN_AMOUNT 100 → Kotlin coerceIn
    // 抛 IllegalArgumentException 被 safelyRunInState 吞掉——标记保留、
    // 偷盗中止、月变继续；金额波动 d4 在抛出前已消费
    const int64_t seed = findTheftSeed(7000, [](auto& r) {
        return r.nextDouble() < 0.30;   // 仅 d1 谓词
    });
    ASSERT_GE(seed, 0);
    auto core = makeCore(seed);
    auto& st = core->state();
    st.gameData.spiritStones = 500;
    Disciple d = theftDisciple("1");
    d.morality = 0;
    d.realm = 1;
    st.disciples.appendDisciple(d);

    auto sys = sysReplica(seed);
    sys.nextDouble();   // d1 尝试
    sys.nextDouble();   // d2 捕获（率 0）
    sys.nextDouble();   // d4 金额波动（抛出前消费）

    system::runMonthSettlement(st, core->rng());   // 异常吞没不外抛

    EXPECT_EQ(1, st.gameData.theftJudgementsThisMonth);   // 标记保留
    EXPECT_EQ(1, st.disciples.lastTheftJudgementYears[0]);
    EXPECT_EQ(500, st.gameData.spiritStones);             // 未扣减
    EXPECT_EQ(0, st.gameData.annualTheftCount);
    EXPECT_TRUE(st.gameData.gameEventRecords.empty());
    auto states = core->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(MonthSettlementTest, TheftAmountCoerceUnderflowThrowsDirectly) {
    // calcTheftAmount 直抛（Kotlin Long.coerceIn 空 range 语义同构）
    Disciple d = baseDisciple("1");
    d.realm = 1;
    auto sys = sysReplica(42);
    EXPECT_THROW(gamecore::system::detail::calcTheftAmount(d, 500, sys),
                 std::runtime_error);
    // 灵石充足（1000 → maxAmount 100）不抛
    auto sys2 = sysReplica(42);
    EXPECT_NO_THROW(gamecore::system::detail::calcTheftAmount(d, 1000, sys2));
}

// ── S8 子事件 12：附庸脱离检查（批 10-4）────────────────────────────

/// 附庸脱离场景：玩家宗门 p1 + 附属 ai-9（玄水宗）契约 + AI 弟子 + N 名
/// 同规格玩家弟子（realm 9 全同 → 战力比 = N 精确整数倍）。玩家弟子
/// morality 50（≥30 偷盗候选门控零抽取——spiritStones>0 场景必需）+ 忠诚
/// 40（≥30 非叛逃候选）→ 月度 SYSTEM 抽取仅剩附庸判定 1 次。
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
        d.loyalty = 40;   // 从众门开但不落入叛逃/偷盗候选
        d.morality = 50;  // ≥30 → 偷盗候选门控关闭（灵石>0 场景零抽取）
        st.disciples.appendDisciple(d);
    }
}

TEST(MonthSettlementTest, VassalBreakawayEmptyContractsZeroDraws) {
    auto core = makeCore(42);
    auto& st = core->state();
    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng());
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
    system::runMonthSettlement(st, core->rng());
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
    state::VassalContract contract;
    contract.vassalSectId = "gone"; contract.establishedYear = 1;
    st.gameData.vassalContracts.push_back(contract);
    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng());
    EXPECT_TRUE(st.gameData.vassalContracts.empty());
    EXPECT_TRUE(st.gameData.gameEventRecords.empty());
    EXPECT_EQ(before, core->rng().exportStates());
}

TEST(MonthSettlementTest, VassalBreakawayZeroAiPowerNoDraw) {
    // 附属宗门存在但 aiSectDisciples 缺失 → aiPower 0 → 不脱离零抽取
    auto core = makeCore(42);
    auto& st = core->state();
    setupVassalScene(st, 1);
    st.aiSectDisciples.clear();
    const auto before = core->rng().exportStates();
    system::runMonthSettlement(st, core->rng());
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

    system::runMonthSettlement(st, core->rng());

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

    system::runMonthSettlement(st, core->rng());

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

    system::runMonthSettlement(st, core->rng());

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
        system::runMonthSettlement(st2, core2->rng());
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
        system::runMonthSettlement(st2, core2->rng());
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
        st.gameData.daoCompanionConsentRequired = false;
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
    // 跑月变：场景弟子 morality 50（偷盗候选门控关闭）+ loyalty 40（非叛逃
    // 候选）+ 未成年（不参与配对）→ SYSTEM 恰抽 1 次 = 附庸脱离判定
    auto sys = gamecore::rng::DeterministicRng::fromSeed(20260901 + 3);
    sys.nextInt(); sys.nextInt(); sys.nextInt();
    sys.nextDouble();
    st2.gameData.gamePhase = 2;
    core2->advancePhases(1);
    auto states = core2->rng().exportStates();
    EXPECT_EQ(sys.snapshot(),
              states[static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

// ── 子事件 2：自动招募（批 11-1） ─────────────────────────────────

using gamecore::system::recruit_settle::processAutoRecruit;

/// 招募候选弟子（无装备/功法——俘虏落库 no-op；资质缺省 50 触发散列补算）
Disciple recruitCandidate(const std::string& id, const char* roots) {
    Disciple d = baseDisciple(id);
    d.name = "候选" + id;
    d.age = 16;
    d.gender = "male";
    d.spiritRootType = roots;
    d.currentHp = -1;
    d.currentMp = -1;
    return d;
}

TEST(RecruitAutoRecruit, MonthlyAutoRecruitGolden) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 2;   // 月变后（与 Diff 对拍场景同相位）
    st.gameData.autoRecruitSpiritRootFilter = {1};
    st.gameData.recruitList = {
        recruitCandidate("r1", "metal"),          // 1 根 → 匹配
        recruitCandidate("r2", "metal,fire")      // 2 根 → 不匹配保留
    };
    st.disciples.appendDisciple(adultDisciple("11", "male"));
    st.disciples.appendDisciple(adultDisciple("12", "female"));

    processAutoRecruit(st);

    // 匹配候选入宗：id = max+1 = 13；资质 50 → 80 + floorMod(13*527+31, 21)
    ASSERT_EQ(3u, st.disciples.size());
    const auto& d = st.disciples.materialize(2);
    EXPECT_EQ("13", d.id);
    const int64_t roll = gamecore::system::recruit_settle::floorMod(
        13LL * 527 + 31, 21);
    EXPECT_EQ(80 + static_cast<int32_t>(roll), d.aptitude);
    EXPECT_EQ(14, d.recruitedMonth);   // 1*12 + 2
    // 不匹配候选保留；recruitCountThisMonth / annualNewDisciples 各 +1
    ASSERT_EQ(1u, st.gameData.recruitList.size());
    EXPECT_EQ("r2", st.gameData.recruitList[0].id);
    EXPECT_EQ(1, st.gameData.recruitCountThisMonth);
    EXPECT_EQ(1, st.gameData.annualNewDisciples);
    EXPECT_FALSE(st.autoRecruitIdle);
    // 零 RNG 抽取（SYSTEM 分区状态不变——不扰动后续子事件抽取序）
    const auto sys0 = gamecore::rng::DeterministicRng::fromSeed(20260901 + 3);
    EXPECT_EQ(sys0.snapshot(), core->rng().exportStates()[
        static_cast<int32_t>(gamecore::rng::RngPartition::kSystem)]);
}

TEST(RecruitAutoRecruit, LazyGateSkipsAfterNoCandidates) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 2;
    st.gameData.autoRecruitSpiritRootFilter = {1};
    st.gameData.recruitList = {recruitCandidate("r1", "metal,fire")};  // 无匹配

    processAutoRecruit(st);
    EXPECT_TRUE(st.autoRecruitIdle);      // 无候选 → 惰性置位
    EXPECT_EQ(0, st.gameData.recruitCountThisMonth);
    EXPECT_EQ(1u, st.gameData.recruitList.size());

    // 惰性门：后续调用直接跳过（列表不变时与 Kotlin 语义一致）
    const auto before = st.gameData.recruitList;
    processAutoRecruit(st);
    EXPECT_EQ(0, st.gameData.recruitCountThisMonth);
    EXPECT_EQ(before.size(), st.gameData.recruitList.size());
}

TEST(RecruitAutoRecruit, MonthlyLimitBlocksRecruit) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 2;
    st.gameData.autoRecruitSpiritRootFilter = {1};
    st.gameData.recruitCountThisMonth = 30;   // GameConfig.RECRUIT_MONTHLY_LIMIT
    st.gameData.recruitList = {recruitCandidate("r1", "metal")};

    processAutoRecruit(st);
    EXPECT_EQ(0u, st.disciples.size());
    EXPECT_EQ(30, st.gameData.recruitCountThisMonth);
    EXPECT_FALSE(st.autoRecruitIdle);   // 上限早退不置惰性（与 Kotlin 一致）
}

TEST(RecruitAutoRecruit, CaptiveGearMaterializedToInstances) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 2;
    st.gameData.autoRecruitSpiritRootFilter = {1};
    Disciple captive = recruitCandidate("r1", "metal");
    captive.weaponId = "ironSword";                 // 装备模板（equipment_db 存在）
    captive.manualIds = {"common_phys_single_1"};   // 功法模板（manual_db 存在）
    captive.manualMasteries = {{"common_phys_single_1", 5000}};
    st.gameData.recruitList = {captive};
    st.disciples.appendDisciple(adultDisciple("11", "male"));
    st.disciples.appendDisciple(adultDisciple("12", "female"));

    processAutoRecruit(st);

    // 装备实例落库 + 槽位列回写（实例 id 确定性自增）
    ASSERT_EQ(1u, st.equipmentInstances.size());
    EXPECT_EQ("精铁剑", st.equipmentInstances[0].name);   // ironSword 模板显示名
    EXPECT_EQ("gc-inst-1", st.equipmentInstances[0].id);
    EXPECT_EQ(true, st.equipmentInstances[0].isEquipped);
    ASSERT_EQ(3u, st.disciples.size());
    const auto& d = st.disciples.materialize(2);
    EXPECT_EQ(st.equipmentInstances[0].id, d.weaponId);
    // 功法实例 + 熟练度注册 + HP/MP 增量（common_phys_single_1 无 hp/mp 增益）
    ASSERT_EQ(1u, st.manualInstances.size());
    EXPECT_EQ("青冥剑诀", st.manualInstances[0].name);
    EXPECT_EQ(true, st.manualInstances[0].isLearned);
    EXPECT_EQ(st.manualInstances[0].id, d.manualIds[0]);
    ASSERT_EQ(1u, st.gameData.manualProficiencies.size());
    const auto& prof = st.gameData.manualProficiencies.at("13")[0];
    EXPECT_EQ(5000.0, prof.proficiency);
    EXPECT_EQ(1, prof.masteryLevel);   // 5000 ∈ [1000, 10000) → SMALL_SUCCESS
    EXPECT_EQ(st.manualInstances[0].id, prof.manualId);
    EXPECT_EQ(5000, d.manualMasteries.at(st.manualInstances[0].id));  // 值保持，键重映射
}

TEST(RecruitAutoRecruit, DedupeCorruptedRecruitsBeforeFilter) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 2;
    st.gameData.autoRecruitSpiritRootFilter = {1};
    // 损坏条目（空名）先于正常条目且同 id——净化必须丢弃损坏者而非正常者
    Disciple corrupted = recruitCandidate("r1", "metal");
    corrupted.name = "   ";
    st.gameData.recruitList = {
        corrupted,
        recruitCandidate("r1", "metal"),           // 同 id 正常条目
        recruitCandidate("r1", "metal,fire"),      // 同 id 不同内容（id 去重保留首个）
        recruitCandidate("r2", "metal,fire")       // 不匹配保留
    };
    st.disciples.appendDisciple(adultDisciple("11", "male"));
    st.disciples.appendDisciple(adultDisciple("12", "female"));

    processAutoRecruit(st);

    // 损坏条目随列表重建移除；r1 正常条目入宗；r2 保留
    ASSERT_EQ(1u, st.gameData.recruitList.size());
    EXPECT_EQ("r2", st.gameData.recruitList[0].id);
    ASSERT_EQ(3u, st.disciples.size());
    EXPECT_EQ("13", st.disciples.idAt(2));
    EXPECT_EQ(1, st.gameData.recruitCountThisMonth);
}

// ── 子事件 15/16：秘境到期关闭 + AI 队伍派遣（批 11-2） ─────────────

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
    state::EquipmentStack eq;
    eq.name = "木剑"; eq.rarity = 1; eq.quantity = 2;
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

// ── 子事件 10：12 月自动购买（批 11-3） ─────────────────────────────

using gamecore::system::merchant_settle::executeAutoBuy;

TEST(AutoBuySettlement, DecemberAutoBuyMatchesKnownTemplate) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 12;
    st.gameData.spiritStones = 10000;
    st.gameData.autoBuyList.push_back({"精铁剑", "equipment", 1});
    st.gameData.autoBuyList.push_back({"聚气丹", "pill", 1});
    state::MerchantItem sword;
    sword.id = "m1"; sword.name = "精铁剑"; sword.type = "equipment";
    sword.rarity = 1; sword.price = 100; sword.quantity = 3;
    state::MerchantItem pill;
    pill.id = "m2"; pill.name = "聚气丹"; pill.type = "pill";
    pill.rarity = 1; pill.price = 50; pill.quantity = 2; pill.grade = "中品";
    st.gameData.travelingMerchantItems = {sword, pill};

    executeAutoBuy(st);

    // 灵石扣除：精铁剑 3×100 + 聚气丹 2×50 = 400 → 10000-400
    EXPECT_EQ(9600LL, st.gameData.spiritStones);
    // 商人库存清空（数量耗尽 → 移除）
    EXPECT_TRUE(st.gameData.travelingMerchantItems.empty());
    // 仓库入库：精铁剑堆叠 ×3 + 聚气丹堆叠 ×2
    ASSERT_EQ(1u, st.equipmentStacks.size());
    EXPECT_EQ("精铁剑", st.equipmentStacks[0].name);
    EXPECT_EQ(3, st.equipmentStacks[0].quantity);
    ASSERT_EQ(1u, st.pills.size());
    EXPECT_EQ("聚气丹", st.pills[0].name);
    EXPECT_EQ(2, st.pills[0].quantity);
    EXPECT_EQ("MEDIUM", st.pills[0].grade);
    // 年度来源追踪（merchant:稀有度）
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
    st.gameData.autoBuyList.push_back({"精铁剑", "equipment", 1});
    state::MerchantItem sword;
    sword.id = "m1"; sword.name = "精铁剑"; sword.type = "equipment";
    sword.rarity = 1; sword.price = 100; sword.quantity = 3;
    st.gameData.travelingMerchantItems = {sword};

    executeAutoBuy(st);

    // 可买 1 把（150/100=1）→ 扣除 100；商人剩余 2
    EXPECT_EQ(50LL, st.gameData.spiritStones);
    ASSERT_EQ(1u, st.gameData.travelingMerchantItems.size());
    EXPECT_EQ(2, st.gameData.travelingMerchantItems[0].quantity);
    ASSERT_EQ(1u, st.equipmentStacks.size());
    EXPECT_EQ(1, st.equipmentStacks[0].quantity);
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

    executeAutoBuy(st);

    // 中品灵石入袋 ×5；不存在物品条目跳过
    EXPECT_EQ(9500LL, st.gameData.spiritStones);   // 10000 - 5×100
    EXPECT_EQ(5, st.gameData.midGradeSpiritStones);
    EXPECT_TRUE(st.gameData.travelingMerchantItems.empty());
    EXPECT_TRUE(st.equipmentStacks.empty());
}

}  // namespace
