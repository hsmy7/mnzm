// ============================================================
// boundary_tx_test — 月年边界编排族·引导计数面事务守护（batch-18a：
// 引导计数递增 / 自动分配策略+计数合并写 / 建造计数回填）
//
// 守护目标：boundary_tx.h 三事务与 Kotlin 源语义逐位一致——
//   - incrementGuideCounterTx：键缺省 0 起算、按 amount 递增（非幂等）
//   - autoAssignGuideBatchTx：sectPolicies 整包替换 + 三激活计数增量
//     （键名 = GuideCounterKeys 镜像，漂移即引导进度丢失）
//   - backfillBuildingGuideCountersTx：按 displayName max 语义回填、
//     幂等（二次执行零变更）、不回退已有更高计数
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
#include "gamecore/system/boundary_tx.h"

namespace gamecore {
namespace {

namespace boundary_tx = gamecore::system::boundary_tx;

using gamecore::state::GameState;
using gamecore::state::GridBuildingData;

class BoundaryTxFixture : public ::testing::Test {
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

    /// 占位建筑（displayName 为唯一参与回填的字段）
    static GridBuildingData building(const std::string& displayName,
                                     const std::string& instanceId) {
        GridBuildingData b;
        b.displayName = displayName;
        b.instanceId = instanceId;
        return b;
    }

    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    std::vector<std::unique_ptr<FixedClock>> clocks_;
    std::vector<std::unique_ptr<ConsoleLogger>> loggers_;
    std::unique_ptr<GameCore> core_;
};

// ── incrementGuideCounterTx ──────────────────────────────────────────────

TEST_F(BoundaryTxFixture, GuideCounterIncrementFromZero) {
    const auto r = boundary_tx::incrementGuideCounterTx(state(), "missionsCompleted", 1);

    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(1, r.newValue);
    EXPECT_EQ(1, counter(state(), "missionsCompleted"));
}

TEST_F(BoundaryTxFixture, GuideCounterIncrementFromExistingAndAmount) {
    auto& counters = state().gameData.guideCounters;
    counters["missionsCompleted"] = 7;

    const auto r1 = boundary_tx::incrementGuideCounterTx(state(), "missionsCompleted", 1);
    EXPECT_EQ(8, r1.newValue);
    EXPECT_EQ(8, counter(state(), "missionsCompleted"));

    const auto r2 = boundary_tx::incrementGuideCounterTx(state(), "missionsCompleted", 5);
    EXPECT_EQ(13, r2.newValue) << "amount 透传（Kotlin 默认 1，签名保留显式增量）";
    EXPECT_EQ(13, counter(state(), "missionsCompleted"));
}

TEST_F(BoundaryTxFixture, GuideCounterIncrementLeavesRngStatesUntouched) {
    const auto before = rngSnapshot();

    (void)boundary_tx::incrementGuideCounterTx(state(), "breakthroughs", 1);

    EXPECT_EQ(before, rngSnapshot()) << "计数递增为零 RNG 纯事务";
}

// ── autoAssignGuideBatchTx ───────────────────────────────────────────────

TEST_F(BoundaryTxFixture, AutoAssignBatchWritesPoliciesAndCounters) {
    auto& gd = state().gameData;
    const auto newPolicies = gd.sectPolicies;
    auto policies = newPolicies;
    policies.autoMineFocused = true;
    policies.autoMineThreshold = 3;

    const auto r = boundary_tx::autoAssignGuideBatchTx(
        state(), policies, /*mineActivated=*/true, /*plantActivated=*/false,
        /*productionActivated=*/true);

    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(r.countersChanged);
    EXPECT_TRUE(gd.sectPolicies.autoMineFocused);
    EXPECT_EQ(3, gd.sectPolicies.autoMineThreshold);
    EXPECT_EQ(1, counter(state(), boundary_tx::kAutoMineActivatedKey));
    EXPECT_EQ(-1, counter(state(), boundary_tx::kAutoPlantActivatedKey))
        << "未激活不写键（counter()==-1 即键缺失，对齐 Kotlin 仅激活分支才写 key）";
    EXPECT_EQ(1, counter(state(), boundary_tx::kAutoProductionActivatedKey));
}

TEST_F(BoundaryTxFixture, AutoAssignBatchIncrementsFromExistingCounters) {
    auto& gd = state().gameData;
    gd.guideCounters[boundary_tx::kAutoPlantActivatedKey] = 4;

    const auto r = boundary_tx::autoAssignGuideBatchTx(
        state(), gd.sectPolicies, false, /*plantActivated=*/true, false);

    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(5, counter(state(), boundary_tx::kAutoPlantActivatedKey));
    EXPECT_EQ(5, r.autoPlantActivated);
}

TEST_F(BoundaryTxFixture, AutoAssignBatchNoActivationIsCounterNoOp) {
    auto& gd = state().gameData;
    const auto policies = gd.sectPolicies;

    const auto r = boundary_tx::autoAssignGuideBatchTx(
        state(), policies, false, false, false);

    ASSERT_TRUE(r.base.ok);
    EXPECT_FALSE(r.countersChanged);
    EXPECT_TRUE(gd.guideCounters.empty());
}

TEST_F(BoundaryTxFixture, AutoAssignBatchLeavesRngStatesUntouched) {
    auto& gd = state().gameData;
    const auto before = rngSnapshot();

    (void)boundary_tx::autoAssignGuideBatchTx(state(), gd.sectPolicies, true, true, true);

    EXPECT_EQ(before, rngSnapshot()) << "自动分配为事务外判定 + 零 RNG 写";
}

// ── backfillBuildingGuideCountersTx ──────────────────────────────────────

TEST_F(BoundaryTxFixture, BackfillCreatesMaxSemanticsKeys) {
    auto& gd = state().gameData;
    gd.placedBuildings.push_back(building("单人住所", "i1"));
    gd.placedBuildings.push_back(building("单人住所", "i2"));
    gd.placedBuildings.push_back(building("炼丹房", "i3"));

    const auto r = boundary_tx::backfillBuildingGuideCountersTx(state());

    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    EXPECT_EQ(2, r.backfilledKeys);
    EXPECT_EQ(2, counter(state(), "buildingBuilt:单人住所"));
    EXPECT_EQ(1, counter(state(), "buildingBuilt:炼丹房"));
}

TEST_F(BoundaryTxFixture, BackfillIsIdempotentOnSecondRun) {
    auto& gd = state().gameData;
    gd.placedBuildings.push_back(building("单人住所", "i1"));
    gd.placedBuildings.push_back(building("单人住所", "i2"));

    (void)boundary_tx::backfillBuildingGuideCountersTx(state());
    const auto second = boundary_tx::backfillBuildingGuideCountersTx(state());

    ASSERT_TRUE(second.base.ok);
    EXPECT_FALSE(second.changed) << "二次执行零变更（幂等——读档 Step 3.6 可重入）";
    EXPECT_EQ(0, second.backfilledKeys);
    EXPECT_EQ(2, counter(state(), "buildingBuilt:单人住所"));
}

TEST_F(BoundaryTxFixture, BackfillDoesNotLowerHigherExistingCount) {
    auto& gd = state().gameData;
    gd.guideCounters["buildingBuilt:单人住所"] = 9;   // 存量 1 < 计数 9
    gd.placedBuildings.push_back(building("单人住所", "i1"));

    const auto r = boundary_tx::backfillBuildingGuideCountersTx(state());

    ASSERT_TRUE(r.base.ok);
    EXPECT_FALSE(r.changed);
    EXPECT_EQ(9, counter(state(), "buildingBuilt:单人住所"))
        << "升级/拆除不回退引导进度（max 语义）";
}

TEST_F(BoundaryTxFixture, BackfillPreservesUnrelatedCounters) {
    auto& gd = state().gameData;
    gd.guideCounters["missionsCompleted"] = 12;
    gd.placedBuildings.push_back(building("炼丹房", "i1"));

    (void)boundary_tx::backfillBuildingGuideCountersTx(state());

    EXPECT_EQ(12, counter(state(), "missionsCompleted")) << "非建筑键原样保留";
    EXPECT_EQ(1, counter(state(), "buildingBuilt:炼丹房"));
}

TEST_F(BoundaryTxFixture, BackfillEmptyBuildingsKeepsCountersUnchanged) {
    state().gameData.guideCounters["missionsCompleted"] = 3;

    const auto r = boundary_tx::backfillBuildingGuideCountersTx(state());

    ASSERT_TRUE(r.base.ok);
    EXPECT_FALSE(r.changed);
    EXPECT_EQ(3, counter(state(), "missionsCompleted"));
}

TEST_F(BoundaryTxFixture, BackfillLeavesRngStatesUntouched) {
    state().gameData.placedBuildings.push_back(building("单人住所", "i1"));
    const auto before = rngSnapshot();

    (void)boundary_tx::backfillBuildingGuideCountersTx(state());

    EXPECT_EQ(before, rngSnapshot()) << "建造计数回填为零 RNG 纯事务";
}

// ── 信封级（execute 通道：success data 面 + 失败信封回退契约） ───────────

TEST_F(BoundaryTxFixture, DispatchEnvelopeHappyPaths) {
    const auto inc = exec(action::BOUNDARY_GUIDE_COUNTER_INCREMENT_TX,
                          {{"key", "missionsCompleted"}, {"amount", 1}});
    EXPECT_EQ(inc["status"], "success");
    EXPECT_EQ(inc["data"]["newValue"], 1);

    auto& gd = state().gameData;
    nlohmann::json policies = gd.sectPolicies;
    policies["autoMineFocused"] = true;
    const auto batch = exec(action::BOUNDARY_AUTO_ASSIGN_GUIDE_TX,
                            {{"policies", policies},
                             {"mineActivated", true},
                             {"plantActivated", false},
                             {"productionActivated", false}});
    EXPECT_EQ(batch["status"], "success");
    EXPECT_EQ(batch["data"]["autoMineActivated"], 1);

    gd.placedBuildings.push_back(building("炼丹房", "i1"));
    const auto backfill =
        exec(action::BOUNDARY_BUILDING_GUIDE_BACKFILL_TX, nlohmann::json::object());
    EXPECT_EQ(backfill["status"], "success");
    EXPECT_EQ(backfill["data"]["changed"], true);
    EXPECT_EQ(backfill["data"]["backfilledKeys"], 1);
}

TEST_F(BoundaryTxFixture, DispatchEnvelopeFailureOnMissingParam) {
    // 缺 key → 顶层 catch → failure 信封（Kotlin 回退臂契约）
    const auto r = exec(action::BOUNDARY_GUIDE_COUNTER_INCREMENT_TX,
                        nlohmann::json::object());
    EXPECT_EQ(r["status"], "failure");

    const auto r2 = exec(action::BOUNDARY_AUTO_ASSIGN_GUIDE_TX,
                         nlohmann::json::object());
    EXPECT_EQ(r2["status"], "failure");
}

}  // namespace
}  // namespace gamecore
