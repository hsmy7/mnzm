// ============================================================
// month_accrual_test — B6 月度连续积分轨守护
//
// 守护目标（结算改造 2026-09-27 §10 B6）：连续臂（realtimeAccrual）下
// 月结积分型四项由 accrueMonthlyContinuous 按游戏秒连续承担——
//   1. 政策月度灵石（费率 = 月费 ÷ 6.0，INV-6 小数累积进位；不足关政策）
//   2. 政策月度道德（教化之道 1/6 点/游戏秒，clamp 70）
//   6. 丹药持续效果衰减（0.5 旬/游戏秒；≤0 清零；新服丹药不受残留 carry 影响）
//   子事件 11 灵矿月产（spiritMineLastSettledGameMs 毫秒差分）
// 以及月判定入口 runMonthEvents（连续臂跳过四项防双计）与臂甄别
//（settleOnePhase/accrue 同步 accrualMode，settleMonth 按臂分流 +
// 离散臂毫秒孪生双写）。
//
// 总量口径：1 游戏月 = 6 游戏秒（kGameSecondsPerMonth），1 旬 = 2 游戏秒；
// 30 tick × 200ms = 1 个月的连续积分与离散月结同值（测试逐例断言）。
// ============================================================

#include "gtest/gtest.h"

#include <memory>

#include "gamecore/game_core.h"
#include "gamecore/system/government.h"
#include "gamecore/system/month_settlement.h"

namespace {

using namespace gamecore;
using gamecore::state::Disciple;
using gamecore::state::SpiritMineSlot;

/// 每矿工基础产出（SPIRIT_MINE_BASE_OUTPUT_PER_MINER；期望月产 = 224 的构成
/// 见 MonthSettlementTest.SpiritMineProductionGolden——同 fixture 同乘区）
constexpr int32_t kSpiritMineBaseOutputPerMiner = 170;
constexpr int64_t kExpectedMonthlyRate = 224;   // round(170×1.32)

/// 构建已初始化 GameCore（钩子已注册），种子固定
std::unique_ptr<GameCore> makeCore(int64_t seed) {
    auto core = std::unique_ptr<GameCore>(new GameCore(nullptr, nullptr));
    GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = seed;
    EXPECT_TRUE(core->initialize(config));
    return core;
}

/// 最小存活弟子（炼气一层）
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

// ── 积分项 1：政策月度灵石连续扣 ────────────────────────────────────

TEST(MonthAccrualTest, PolicyCostsContinuousTotalEquivalentToMonthly) {
    // 30 tick × 200ms = 1 游戏月：连续积分累计 == 离散月结一次扣除
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 100000;
    st.gameData.sectPolicies.alchemyIncentive = true;    // 3000/月
    st.gameData.sectPolicies.manualResearch = true;      // 4000/月

    system::MonthlyAccrualCarry carry;
    for (int i = 0; i < 30; ++i) {
        system::accrueMonthlyContinuous(st, 200, carry);
    }

    EXPECT_EQ(100000 - 3000 - 4000, st.gameData.spiritStones);
    EXPECT_TRUE(st.gameData.sectPolicies.alchemyIncentive);
    EXPECT_TRUE(st.gameData.sectPolicies.manualResearch);
    EXPECT_TRUE(carry.disabledPolicies.empty());
}

TEST(MonthAccrualTest, PolicyCostsContinuousAutoDisableAndReportOnce) {
    // 余额不足 → 关政策 + 禁用名单累积（月界信封上报一次，不重复累积）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 100;
    st.gameData.sectPolicies.alchemyIncentive = true;

    system::MonthlyAccrualCarry carry;
    system::accrueMonthlyContinuous(st, 6000, carry);   // 应扣 3000 > 余额

    EXPECT_FALSE(st.gameData.sectPolicies.alchemyIncentive);
    ASSERT_EQ(1u, carry.disabledPolicies.size());
    EXPECT_STREQ("丹道激励", carry.disabledPolicies[0].c_str());
    // 政策已关 → 后续 tick 不再重复上报，carry 清零（重开从零起）
    system::accrueMonthlyContinuous(st, 6000, carry);
    EXPECT_EQ(1u, carry.disabledPolicies.size());
}

// ── 积分项 2：政策月度道德连续 ──────────────────────────────────────

TEST(MonthAccrualTest, MoralityContinuousAccrualAndClamp) {
    // 教化之道：1/6 点/游戏秒；1 个月（6000ms）+1；上限 70 钳制；
    // 附带断言：教化之道月费（100/弟子/月）同 tick 连续扣除
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 1000000;
    st.gameData.sectPolicies.moralEducation = true;

    Disciple mid = baseDisciple("1");
    mid.morality = 68;
    Disciple capped = baseDisciple("2");
    capped.morality = 70;
    st.disciples.appendDisciple(mid);
    st.disciples.appendDisciple(capped);

    system::MonthlyAccrualCarry carry;
    for (int i = 0; i < 30; ++i) {
        system::accrueMonthlyContinuous(st, 200, carry);
    }

    EXPECT_EQ(69, st.disciples.materialize(0).morality);                 // 68 + 1
    EXPECT_EQ(70, st.disciples.materialize(1).morality);                 // 上限保持
    // 教化之道月费：100/弟子/月 × 2 弟子（连续累计与离散同值）
    EXPECT_EQ(1000000 - 200, st.gameData.spiritStones);
}

TEST(MonthAccrualTest, MoralityCarryFractionsCarryAcrossTicks) {
    // 3000ms = 0.5 点：不进位；再 3000ms 凑整 +1——小数跨 tick 累积
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 1000000;
    st.gameData.sectPolicies.moralEducation = true;
    Disciple d = baseDisciple("1");
    d.morality = 10;
    st.disciples.appendDisciple(d);

    system::MonthlyAccrualCarry carry;
    system::accrueMonthlyContinuous(st, 3000, carry);
    EXPECT_EQ(10, st.disciples.materialize(0).morality);   // 0.5 不进位
    system::accrueMonthlyContinuous(st, 3000, carry);
    EXPECT_EQ(11, st.disciples.materialize(0).morality);   // 凑整进位
}

// ── 积分项 3：丹药持续效果连续衰减 ──────────────────────────────────

TEST(MonthAccrualTest, PillDecayContinuousAndZeroClearsBonuses) {
    // duration=7 旬：6000ms（3 旬）→ 4；再 12000ms（6 旬）→ ≤0 清零全部加成
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.pillEffectDuration = 7;
    d.pillHpBonus = 5;
    d.pillCultivationSpeedBonus = 0.25;
    d.activePillTypes = {"pill-1"};
    st.disciples.appendDisciple(d);

    system::MonthlyAccrualCarry carry;
    for (int i = 0; i < 30; ++i) {
        system::accrueMonthlyContinuous(st, 200, carry);
    }
    EXPECT_EQ(4, st.disciples.materialize(0).pillEffectDuration);   // 7 − 3
    EXPECT_EQ(5, st.disciples.materialize(0).pillHpBonus);

    for (int i = 0; i < 60; ++i) {
        system::accrueMonthlyContinuous(st, 200, carry);
    }
    const Disciple settled = st.disciples.materialize(0);
    EXPECT_EQ(0, settled.pillEffectDuration);        // 4 − 6 → 清零
    EXPECT_EQ(0, settled.pillHpBonus);
    EXPECT_EQ(0.0, settled.pillCultivationSpeedBonus);
    EXPECT_TRUE(settled.activePillTypes.empty());
}

TEST(MonthAccrualTest, PillDecayCarryResetProtectsNewPill) {
    // duration≤0 后清残留 carry：新服丹药不受历史累积即扣
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.pillEffectDuration = 1;
    st.disciples.appendDisciple(d);

    system::MonthlyAccrualCarry carry;
    system::accrueMonthlyContinuous(st, 2000, carry);   // −1 旬 → 清零
    EXPECT_EQ(0, st.disciples.materialize(0).pillEffectDuration);

    // 新服丹药（Kotlin 服用链写镜像列——测试直摆状态模拟服用后状态）
    st.disciples.pillEffectDurations[0] = 5;
    st.disciples.pillHpBonuses[0] = 9;
    system::accrueMonthlyContinuous(st, 100, carry);    // 100ms → 0.05 旬不进位
    EXPECT_EQ(5, st.disciples.materialize(0).pillEffectDuration);
    EXPECT_EQ(9, st.disciples.materialize(0).pillHpBonus);
}

// ── 积分项 4：灵矿月产毫秒差分 ──────────────────────────────────────

TEST(MonthAccrualTest, SpiritMineMsDifferentialContinuous) {
    // span=6000ms（1 月）→ 224 入账；span=0 不重复；再跨 1 月 → 448
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple miner = baseDisciple("1");
    miner.mining = 80;                      // (80-70)×0.02 = 0.2
    miner.morality = 90;                    // 执事：(90-80)×0.01 = 0.1
    st.disciples.appendDisciple(miner);
    st.gameData.spiritStones = 0;
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

    system::MonthlyAccrualCarry carry;
    st.gameData.elapsedGameMs = 6000;
    st.gameData.spiritMineLastSettledGameMs = 0;
    system::accrueMonthlyContinuous(st, 1, carry);      // delta 参数不参与（span 决定）
    EXPECT_EQ(kExpectedMonthlyRate, st.gameData.spiritStones);
    EXPECT_EQ(kExpectedMonthlyRate, st.gameData.guideCounters["miningOutput"]);
    EXPECT_EQ(6000, st.gameData.spiritMineLastSettledGameMs);   // 基准推进

    system::accrueMonthlyContinuous(st, 1, carry);      // span=0 → 零重复
    EXPECT_EQ(kExpectedMonthlyRate, st.gameData.spiritStones);

    st.gameData.elapsedGameMs = 12000;
    system::accrueMonthlyContinuous(st, 1, carry);
    EXPECT_EQ(2 * kExpectedMonthlyRate, st.gameData.spiritStones);
    // 旧月字段不被连续轨消费（月界投影同步走 runMonthEvents）
    EXPECT_EQ(0, st.gameData.spiritMineLastSettledMonth);
}

// ── 月判定入口：runMonthEvents 跳过积分型四项（防双计） ────────────

TEST(MonthAccrualTest, RunMonthEventsSkipsIntegralSteps) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 100000;
    st.gameData.sectPolicies.alchemyIncentive = true;    // 月费政策
    st.gameData.sectPolicies.moralEducation = true;      // 道德政策（费 100/弟子）
    Disciple d = baseDisciple("1");
    d.morality = 10;
    d.pillEffectDuration = 6;
    d.pillHpBonus = 3;
    st.disciples.appendDisciple(d);

    core->settlement().setAccrualMode(true);
    system::MonthlyAccrualCarry carry;
    const std::string env = core->settleMonth();

    // 四项积分全部不动（由连续轨承担——月界不重复结算）
    EXPECT_EQ(100000, st.gameData.spiritStones);
    EXPECT_EQ(10, st.disciples.materialize(0).morality);
    EXPECT_EQ(6, st.disciples.materialize(0).pillEffectDuration);
    EXPECT_EQ(3, st.disciples.materialize(0).pillHpBonus);
    // 旧月字段投影同步（双臂切换安全：离散臂差分基准新鲜）
    EXPECT_EQ(1 * 12 + 1, st.gameData.spiritMineLastSettledMonth);
    // 政策未被禁用 → 信封无禁用名单
    EXPECT_TRUE(st.gameData.sectPolicies.alchemyIncentive);
    (void)env;
}

TEST(MonthAccrualTest, RunMonthEventsReportsContinuousDisabledPolicies) {
    // 连续轨禁用政策 → 月界信封带回名单（Kotlin checkpointAllProduction 口径）。
    // 禁用经 core->accrue 触发（GameCore 成员 monthlyCarry_ 才是信封数据源）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.sectPolicies.alchemyIncentive = true;
    st.gameData.spiritStones = 0;
    core->accrue(6000, true);                            // 余额 0 → 禁用累积
    EXPECT_FALSE(st.gameData.sectPolicies.alchemyIncentive);

    core->settlement().setAccrualMode(true);
    st.gameData.spiritStones = 100000;
    const std::string env = core->settleMonth();
    EXPECT_EQ(100000, st.gameData.spiritStones);        // 连续臂月界不扣费
    EXPECT_NE(std::string::npos, env.find("丹道激励"));   // 信封携带禁用名单
}

// ── 臂甄别：settleOnePhase/accrue 同步 accrualMode + 离散臂 ms 双写 ──

TEST(MonthAccrualTest, ArmSwitchSyncAndDiscreteMsTwinWrite) {
    auto core = makeCore(42);
    auto& st = core->state();

    // settleOnePhase 清连续臂 → settleMonth 走完整版（政策费月结内扣除）
    core->settlement().setAccrualMode(true);
    core->settleOnePhase();
    EXPECT_FALSE(core->settlement().accrualMode());

    st.gameData.spiritStones = 100000;
    st.gameData.sectPolicies.alchemyIncentive = true;
    st.gameData.elapsedGameMs = 4000;                   // 2 旬
    core->settleMonth();
    EXPECT_EQ(100000 - 3000, st.gameData.spiritStones); // 离散臂月结扣费
    // 离散臂毫秒孪生双写：灵矿差分基准与权威轴同界（双臂切换不重复结算）
    EXPECT_EQ(4000, st.gameData.spiritMineLastSettledGameMs);

    // accrue 置连续臂 → settleMonth 走 runMonthEvents（月界不扣费）
    const int flags = core->accrue(100, true);
    EXPECT_TRUE(core->settlement().accrualMode());
    (void)flags;
    st.gameData.spiritStones = 100000;
    st.gameData.sectPolicies.alchemyIncentive = true;
    core->settleMonth();
    EXPECT_EQ(100000, st.gameData.spiritStones);
}

}  // namespace
