// ============================================================
// government_tx_test — 政策开关事务守护（batch-18b：通用政策开关 /
// 广纳门徒 / 灵矿增产，追加于 government.h）
//
// 守护目标：三事务与 Kotlin SectPolicyToggleUseCase 判定序逐位一致——
//   - 关闭态 → canAfford 预检（不足 → 失败信封零写入）→ deduct(autoConvert)
//     → 置位 → policyActivated 计数 +1
//   - 开启态 → 仅置位 false（不退款、不计数）
//   - affectsCultivationRate → 同事务内列级 checkpointAllDisciples
//     （仅存活弟子；checkpoint = 当前修为；月 = year×12+month）
//   - productionCheckpointNeeded 标记矩阵（4 生产类政策真、其余假）——
//     CLAUDE.md 6.4/13.3 红线由 Kotlin 臂消费
//   零 RNG 族：全分区 rngStates 快照差分逐测试断言
//   - 信封级：execute 通道 success data 面 + 失败信封（Kotlin 回退臂契约）
// ============================================================

#include "gtest/gtest.h"

#include <map>
#include <memory>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/state/json_codec.h"
#include "gamecore/system/government.h"

namespace gamecore {
namespace {

namespace gov = gamecore::system;

using gamecore::state::Disciple;
using gamecore::state::GameState;

/// 广纳门徒固定费用（Kotlin GameConfig.PolicyConfig.OPEN_RECRUITMENT_COST）
constexpr int64_t kOpenRecruitmentCost = 50000;

class PolicyTxFixture : public ::testing::Test {
protected:
    void SetUp() override { core_ = makeCore(); }

    std::unique_ptr<GameCore> makeCore() {
        auto clock = std::make_unique<FixedClock>();
        auto logger = std::make_unique<ConsoleLogger>();
        auto core = std::make_unique<GameCore>(clock.get(), logger.get());
        GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core->initialize(config);
        clocks_.push_back(std::move(clock));
        loggers_.push_back(std::move(logger));
        return core;
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result = core_->execute(actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    GameState& state() { return core_->state(); }

    static int64_t counter(const GameState& st, const std::string& key) {
        const auto it = st.gameData.guideCounters.find(key);
        return it == st.gameData.guideCounters.end() ? -1 : it->second;
    }

    /// 追加弟子并返回行号（isAlive / cultivation 两列参与 checkpoint 断言）
    std::size_t addDisciple(const std::string& id, bool alive, double cultivation) {
        Disciple d;
        d.id = id;
        d.name = "弟子" + id;
        d.isAlive = alive;
        d.cultivation = cultivation;
        d.realm = 9;
        d.realmLayer = 1;
        state().disciples.appendDisciple(d);
        return *state().disciples.rowOf(id);
    }

    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    std::vector<std::unique_ptr<FixedClock>> clocks_;
    std::vector<std::unique_ptr<ConsoleLogger>> loggers_;
    std::unique_ptr<GameCore> core_;
};

// ── 通用政策开关：开启 / 关闭 / 失败三臂 ─────────────────────────────────

TEST_F(PolicyTxFixture, ToggleEnableDeductsAndCounts) {
    auto& gd = state().gameData;
    gd.spiritStones = 100000;

    const auto r = gov::policyToggleTx(state(), "enhancedSecurity", 3000, false);

    ASSERT_TRUE(r.ok);
    EXPECT_FALSE(r.wasEnabled);
    EXPECT_TRUE(r.enabled);
    EXPECT_EQ(3000, r.costPaid);
    EXPECT_EQ(97000, gd.spiritStones);
    EXPECT_TRUE(gd.sectPolicies.enhancedSecurity);
    EXPECT_EQ(1, counter(state(), gov::kPolicyActivatedCounterKey));
    EXPECT_FALSE(r.cultivationCheckpoint);
}

TEST_F(PolicyTxFixture, ToggleInsufficientStonesIsFailureWithZeroWrite) {
    auto& gd = state().gameData;
    gd.spiritStones = 10;

    const auto r = gov::policyToggleTx(state(), "enhancedSecurity", 3000, false);

    EXPECT_FALSE(r.ok);
    EXPECT_EQ("INSUFFICIENT_STONES", r.errorType);
    EXPECT_EQ(10, gd.spiritStones) << "失败零写入：余额未动";
    EXPECT_FALSE(gd.sectPolicies.enhancedSecurity) << "失败零写入：政策未置位";
    EXPECT_EQ(-1, counter(state(), gov::kPolicyActivatedCounterKey))
        << "失败零写入：激活计数未写";
}

TEST_F(PolicyTxFixture, ToggleDisableClearsFlagWithoutCostOrCounter) {
    auto& gd = state().gameData;
    gd.spiritStones = 100000;
    gd.sectPolicies.enhancedSecurity = true;

    const auto r = gov::policyToggleTx(state(), "enhancedSecurity", 3000, false);

    ASSERT_TRUE(r.ok);
    EXPECT_TRUE(r.wasEnabled);
    EXPECT_FALSE(r.enabled);
    EXPECT_EQ(0, r.costPaid) << "关闭不扣费";
    EXPECT_EQ(100000, gd.spiritStones);
    EXPECT_FALSE(gd.sectPolicies.enhancedSecurity);
    EXPECT_EQ(-1, counter(state(), gov::kPolicyActivatedCounterKey)) << "关闭不计数";
}

TEST_F(PolicyTxFixture, ToggleUnknownFieldIsFailureWithZeroWrite) {
    auto& gd = state().gameData;
    gd.spiritStones = 100000;

    const auto r = gov::policyToggleTx(state(), "noSuchPolicy", 3000, false);

    EXPECT_FALSE(r.ok);
    EXPECT_EQ("UNKNOWN_POLICY", r.errorType);
    EXPECT_EQ(100000, gd.spiritStones);
    EXPECT_TRUE(gd.guideCounters.empty());
}

TEST_F(PolicyTxFixture, ToggleFreePolicySkipsDeduction) {
    auto& gd = state().gameData;
    gd.spiritStones = 0;

    const auto r = gov::policyToggleTx(state(), "frugality", 0, false);

    ASSERT_TRUE(r.ok);
    EXPECT_TRUE(r.enabled);
    EXPECT_EQ(0, r.costPaid);
    EXPECT_EQ(0, gd.spiritStones) << "零费用政策不触钱包";
    EXPECT_EQ(1, counter(state(), gov::kPolicyActivatedCounterKey));
}

// ── 修炼速率 checkpoint（同事务列级全量） ────────────────────────────────

TEST_F(PolicyTxFixture, ToggleCultivationRateCheckpointsAliveDisciplesOnly) {
    auto& gd = state().gameData;
    gd.gameYear = 3;
    gd.gameMonth = 5;
    const int32_t expectedMonth = 3 * 12 + 5;
    const std::size_t aliveRow = addDisciple("1", true, 120.5);
    const std::size_t deadRow = addDisciple("2", false, 88.0);

    const auto r =
        gov::policyToggleTx(state(), "asceticTraining", 0, /*affectsCultivationRate=*/true);

    ASSERT_TRUE(r.ok);
    EXPECT_TRUE(r.cultivationCheckpoint);
    EXPECT_EQ(expectedMonth, r.checkpointMonth);
    auto& ds = state().disciples;
    EXPECT_DOUBLE_EQ(120.5, ds.cultivationCheckpoints[aliveRow]);
    EXPECT_EQ(expectedMonth, ds.cultivationCheckpointGameMonths[aliveRow]);
    EXPECT_DOUBLE_EQ(0.0, ds.cultivationCheckpoints[deadRow]) << "阵亡弟子跳过 checkpoint";
    EXPECT_EQ(0, ds.cultivationCheckpointGameMonths[deadRow]);
}

TEST_F(PolicyTxFixture, ToggleWithoutCultivationRateLeavesCheckpointsUntouched) {
    auto& gd = state().gameData;
    gd.spiritStones = 100000;
    const std::size_t row = addDisciple("1", true, 42.0);

    const auto r = gov::policyToggleTx(state(), "enhancedSecurity", 3000, false);

    ASSERT_TRUE(r.ok);
    EXPECT_FALSE(r.cultivationCheckpoint);
    EXPECT_EQ(0, r.checkpointMonth);
    auto& ds = state().disciples;
    EXPECT_DOUBLE_EQ(0.0, ds.cultivationCheckpoints[row]);
    EXPECT_EQ(0, ds.cultivationCheckpointGameMonths[row]);
}

// ── 生产类政策标记矩阵（13.3 红线：Kotlin 臂据此触发 checkpointAllProduction）──

TEST_F(PolicyTxFixture, ProductionClassFlagMatrix) {
    auto& gd = state().gameData;
    gd.spiritStones = 1000000;

    for (const char* field : {"alchemyIncentive", "forgeIncentive", "herbCultivation",
                              "spiritSpring"}) {
        const auto r = gov::policyToggleTx(state(), field, 0, false);
        ASSERT_TRUE(r.ok) << field;
        EXPECT_TRUE(r.productionCheckpointNeeded)
            << field << " 属生产类政策（影响炼丹/锻造/灵田速率）";
    }
    for (const char* field : {"enhancedSecurity", "curfew", "rewardPunish",
                              "manualResearch", "frugality", "moralEducation",
                              "benevolentGovernance", "cultivationSubsidy",
                              "asceticTraining", "strictTraining", "relaxedMgmt"}) {
        // 已置位的政策再翻转即关闭，标记仍应稳定反映字段类别
        const auto r = gov::policyToggleTx(state(), field, 0, false);
        ASSERT_TRUE(r.ok) << field;
        EXPECT_FALSE(r.productionCheckpointNeeded)
            << field << " 非生产类（灵矿增产另经时间戳差分承扣，亦不在列）";
    }
}

TEST_F(PolicyTxFixture, SpiritMineBoostIsNotProductionClassFlag) {
    const auto r = gov::spiritMineBoostToggleTx(state());
    ASSERT_TRUE(r.ok);
    EXPECT_FALSE(r.productionCheckpointNeeded)
        << "灵矿速率变化由 spiritMineLastSettledMonth 差分承扣，无 duration 重算";
}

// ── 广纳门徒（固定费用 + 付费月戳） ──────────────────────────────────────

TEST_F(PolicyTxFixture, OpenRecruitmentEnablePaysAndStampsMonth) {
    auto& gd = state().gameData;
    gd.spiritStones = 100000;
    gd.gameYear = 2;
    gd.gameMonth = 7;

    const auto r = gov::openRecruitmentToggleTx(state());

    ASSERT_TRUE(r.ok);
    EXPECT_TRUE(r.enabled);
    EXPECT_EQ(kOpenRecruitmentCost, r.costPaid);
    EXPECT_EQ(100000 - kOpenRecruitmentCost, gd.spiritStones);
    EXPECT_TRUE(gd.sectPolicies.openRecruitment);
    EXPECT_EQ(2 * 12 + 7, gd.openRecruitmentLastPaidMonth);
    EXPECT_EQ(1, counter(state(), gov::kPolicyActivatedCounterKey));
}

TEST_F(PolicyTxFixture, OpenRecruitmentDisableKeepsPaidMonth) {
    auto& gd = state().gameData;
    gd.spiritStones = 100000;
    gd.sectPolicies.openRecruitment = true;
    gd.openRecruitmentLastPaidMonth = 36;

    const auto r = gov::openRecruitmentToggleTx(state());

    ASSERT_TRUE(r.ok);
    EXPECT_FALSE(r.enabled);
    EXPECT_EQ(0, r.costPaid);
    EXPECT_EQ(100000, gd.spiritStones);
    EXPECT_FALSE(gd.sectPolicies.openRecruitment);
    EXPECT_EQ(36, gd.openRecruitmentLastPaidMonth) << "关闭不回退付费月戳";
    EXPECT_EQ(-1, counter(state(), gov::kPolicyActivatedCounterKey));
}

TEST_F(PolicyTxFixture, OpenRecruitmentInsufficientIsFailureWithZeroWrite) {
    auto& gd = state().gameData;
    gd.spiritStones = 100;

    const auto r = gov::openRecruitmentToggleTx(state());

    EXPECT_FALSE(r.ok);
    EXPECT_EQ("INSUFFICIENT_STONES", r.errorType);
    EXPECT_EQ(100, gd.spiritStones);
    EXPECT_FALSE(gd.sectPolicies.openRecruitment);
    EXPECT_EQ(0, gd.openRecruitmentLastPaidMonth);
}

// ── 灵矿增产（免费 + 结算月戳推前） ──────────────────────────────────────

TEST_F(PolicyTxFixture, SpiritMineBoostEnableIsFreeAndStampsSettleMonth) {
    auto& gd = state().gameData;
    gd.spiritStones = 0;
    gd.gameYear = 4;
    gd.gameMonth = 2;
    gd.spiritMineLastSettledMonth = 30;

    const auto r = gov::spiritMineBoostToggleTx(state());

    ASSERT_TRUE(r.ok);
    EXPECT_TRUE(r.enabled);
    EXPECT_EQ(0, r.costPaid);
    EXPECT_EQ(0, gd.spiritStones);
    EXPECT_TRUE(gd.sectPolicies.spiritMineBoost);
    EXPECT_EQ(4 * 12 + 2, gd.spiritMineLastSettledMonth) << "开启时推进结算月戳";
    EXPECT_EQ(1, counter(state(), gov::kPolicyActivatedCounterKey));
}

TEST_F(PolicyTxFixture, SpiritMineBoostDisableKeepsCounterAndSettleMonth) {
    auto& gd = state().gameData;
    gd.sectPolicies.spiritMineBoost = true;
    gd.gameYear = 4;
    gd.gameMonth = 9;
    gd.spiritMineLastSettledMonth = 40;

    const auto r = gov::spiritMineBoostToggleTx(state());

    ASSERT_TRUE(r.ok);
    EXPECT_FALSE(r.enabled);
    EXPECT_FALSE(gd.sectPolicies.spiritMineBoost);
    EXPECT_EQ(40, gd.spiritMineLastSettledMonth) << "关闭不动结算月戳";
    EXPECT_EQ(-1, counter(state(), gov::kPolicyActivatedCounterKey));
}

// ── 零 RNG 全分区快照差分 ───────────────────────────────────────────────

TEST_F(PolicyTxFixture, PolicyTogglesLeaveRngStatesUntouched) {
    auto& gd = state().gameData;
    gd.spiritStones = 1000000;
    const auto before = rngSnapshot();

    (void)gov::policyToggleTx(state(), "alchemyIncentive", 3000, true);
    (void)gov::policyToggleTx(state(), "alchemyIncentive", 3000, true);
    (void)gov::openRecruitmentToggleTx(state());
    (void)gov::spiritMineBoostToggleTx(state());

    EXPECT_EQ(before, rngSnapshot()) << "政策开关族为零 RNG 纯事务";
}

// ── 信封级（execute 通道：success data 面 + 失败信封回退契约） ───────────

TEST_F(PolicyTxFixture, DispatchEnvelopeHappyPaths) {
    state().gameData.spiritStones = 1000000;

    const auto toggle = exec(action::GOV_POLICY_TOGGLE_TX,
                             {{"field", "enhancedSecurity"},
                              {"monthlyCost", 3000},
                              {"affectsCultivationRate", false}});
    EXPECT_EQ(toggle["status"], "success");
    EXPECT_EQ(toggle["data"]["enabled"], true);
    EXPECT_EQ(toggle["data"]["costPaid"], 3000);
    EXPECT_EQ(toggle["data"]["productionCheckpointNeeded"], false);

    const auto prod = exec(action::GOV_POLICY_TOGGLE_TX,
                           {{"field", "alchemyIncentive"}, {"monthlyCost", 0}});
    EXPECT_EQ(prod["status"], "success");
    EXPECT_EQ(prod["data"]["productionCheckpointNeeded"], true);

    const auto open = exec(action::GOV_OPEN_RECRUITMENT_TOGGLE_TX,
                           nlohmann::json::object());
    EXPECT_EQ(open["status"], "success");
    EXPECT_EQ(open["data"]["costPaid"], kOpenRecruitmentCost);

    const auto boost = exec(action::GOV_SPIRIT_MINE_BOOST_TOGGLE_TX,
                            nlohmann::json::object());
    EXPECT_EQ(boost["status"], "success");
    EXPECT_EQ(boost["data"]["costPaid"], 0);
}

TEST_F(PolicyTxFixture, DispatchEnvelopeFailurePaths) {
    state().gameData.spiritStones = 0;

    // 缺 field → 顶层 catch → failure 信封（Kotlin 回退臂契约）
    const auto missing = exec(action::GOV_POLICY_TOGGLE_TX, nlohmann::json::object());
    EXPECT_EQ(missing["status"], "failure");

    // 余额不足 → 业务失败信封
    const auto poor = exec(action::GOV_POLICY_TOGGLE_TX,
                           {{"field", "enhancedSecurity"}, {"monthlyCost", 3000}});
    EXPECT_EQ(poor["status"], "failure");
    EXPECT_EQ(poor["code"], "INSUFFICIENT_STONES");

    const auto openPoor = exec(action::GOV_OPEN_RECRUITMENT_TOGGLE_TX,
                               nlohmann::json::object());
    EXPECT_EQ(openPoor["status"], "failure");
}

}  // namespace
}  // namespace gamecore
