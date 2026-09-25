// ============================================================
// disciple_lifecycle_tx_test — 弟子生命周期 UI 操作事务守护（batch-14）
//
// 守护目标：disciple_lifecycle_tx.h 年俸开关事务与 Kotlin 源语义逐位一致——
//   - 年俸开关（盲写覆写）
//   - RNG 零消费审计（全族 rngStates 快照差分——对拍命门）
// ============================================================

#include "gtest/gtest.h"

#include <map>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/system/disciple_lifecycle_tx.h"

namespace gamecore {
namespace {

namespace lifecycle_tx = gamecore::system::disciple_lifecycle_tx;

class DiscipleLifecycleTxFixture : public ::testing::Test {
protected:
    void SetUp() override {
        core_ = std::make_unique<GameCore>(&clock_, &logger_);
        GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core_->initialize(config);
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result = core_->execute(
            actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    /// rngStates 快照（零消费审计基准）
    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    FixedClock clock_;
    ConsoleLogger logger_;
    std::unique_ptr<GameCore> core_;
};

// ── 年俸开关 ─────────────────────────────────────────────────

TEST_F(DiscipleLifecycleTxFixture, SalaryToggleTx_盲写覆写) {
    const auto before = rngSnapshot();

    auto r = exec(action::DISCIPLE_LIFECYCLE_SALARY_TOGGLE,
                  {{"realm", 3}, {"enabled", true}});
    ASSERT_EQ(r["status"], "success");
    ASSERT_TRUE(core_->state().gameData.yearlySalaryEnabled[3]);

    r = exec(action::DISCIPLE_LIFECYCLE_SALARY_TOGGLE,
             {{"realm", 3}, {"enabled", false}});
    ASSERT_EQ(r["status"], "success");
    EXPECT_FALSE(core_->state().gameData.yearlySalaryEnabled[3]);

    EXPECT_EQ(rngSnapshot(), before);
}

}  // namespace
}  // namespace gamecore
