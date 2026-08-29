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
#include <memory>

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

}  // namespace
