// ============================================================
// diplomacy_selfheal_tx_test — 外交/自愈/运行态族事务守护
// （W4-B/B4，w3-12 实裁面：DIPLOMACY_WARNING_STAGE_TX=1843；
//   段内其余号本批不认领——见 diplomacy_selfheal_tx.h 头注登记）
//
// 守护目标：markWarningStageShownTx 与 Kotlin GameEngineDiplomacyOps
// 逐位一致——List 追加**不去重**；空 key 校验失败零写入；零 RNG。
// ============================================================

#include "gtest/gtest.h"

#include <memory>
#include <string>
#include <utility>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/system/diplomacy_selfheal_tx.h"

namespace gamecore {
namespace {

namespace selfheal_tx = gamecore::system::diplomacy_selfheal_tx;

class DiplomacySelfHealTxFixture : public ::testing::Test {
protected:
    void SetUp() override {
        auto clock = std::make_unique<FixedClock>();
        auto logger = std::make_unique<ConsoleLogger>();
        core_ = std::make_unique<GameCore>(clock.get(), logger.get());
        GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core_->initialize(config);
        clock_ = std::move(clock);
        logger_ = std::move(logger);
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result = core_->execute(actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    std::unique_ptr<FixedClock> clock_;
    std::unique_ptr<ConsoleLogger> logger_;
    std::unique_ptr<GameCore> core_;
};

TEST_F(DiplomacySelfHealTxFixture, MarkWarningStageAppendsWithoutDedup) {
    const auto r1 = selfheal_tx::markWarningStageShownTx(core_->state(), "stage_a");
    ASSERT_TRUE(r1.base.ok);
    EXPECT_EQ(r1.stageCount, 1);
    // 同 key 重复调用：与 Kotlin List 追加语义逐位一致（不去重）
    const auto r2 = selfheal_tx::markWarningStageShownTx(core_->state(), "stage_a");
    ASSERT_TRUE(r2.base.ok);
    EXPECT_EQ(r2.stageCount, 2);
    const auto r3 = selfheal_tx::markWarningStageShownTx(core_->state(), "stage_b");
    ASSERT_TRUE(r3.base.ok);
    auto& ids = core_->state().gameData.shownWarningStageIds;
    ASSERT_EQ(ids.size(), 3u);
    EXPECT_EQ(ids[0], "stage_a");
    EXPECT_EQ(ids[1], "stage_a");
    EXPECT_EQ(ids[2], "stage_b");
}

TEST_F(DiplomacySelfHealTxFixture, EmptyStageKeyFailsZeroWrite) {
    const auto before = core_->exportStateJson();
    const auto r = selfheal_tx::markWarningStageShownTx(core_->state(), "");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "INVALID_PARAMS");
    EXPECT_EQ(core_->exportStateJson(), before);
}

TEST_F(DiplomacySelfHealTxFixture, ZeroRngLeavesRngStatesUntouched) {
    const auto baseline = core_->state().gameData.rngStates;
    ASSERT_TRUE(selfheal_tx::markWarningStageShownTx(core_->state(), "s1").base.ok);
    ASSERT_FALSE(selfheal_tx::markWarningStageShownTx(core_->state(), "").base.ok);
    EXPECT_EQ(core_->state().gameData.rngStates, baseline);
}

TEST_F(DiplomacySelfHealTxFixture, DispatchEnvelopeAndUnclaimedSegmentIds) {
    // 成功信封
    auto env = exec(1843, {{"stageKey", "warning_stage_1"}});
    ASSERT_EQ(env["status"], "success");
    EXPECT_EQ(env["data"]["stageCount"], 1);

    // 失败信封（空 key）
    auto bad = exec(1843, {{"stageKey", ""}});
    EXPECT_EQ(bad["status"], "failure");
    EXPECT_EQ(bad["code"], "INVALID_PARAMS");

    // 段内未实裁号（1840/1841/1842 登记不下沉）→ 端口不认领 → NOT_IMPLEMENTED
    auto unclaimed = exec(1840, {});
    EXPECT_EQ(unclaimed["status"], "failure");
    EXPECT_EQ(unclaimed["code"], "NOT_IMPLEMENTED");
}

}  // namespace
}  // namespace gamecore
