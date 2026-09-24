// ============================================================
// disciple_lifecycle_tx_test — 弟子生命周期 UI 操作事务守护（batch-14）
//
// 守护目标：disciple_lifecycle_tx.h 两事务与 Kotlin 源语义逐位一致——
//   - 拜师（三相校验判定序 / masterIds 落表 / 双侧日志草稿）
//   - 年俸开关（盲写覆写）
//   - 失败臂零写入（校验链先行，任一臂失败不触碰状态）
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

using gamecore::state::Disciple;

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

    /// 挂一个最小弟子（存活、IDLE、20 岁），返回行号（列直访用）
    std::size_t addDisciple(const std::string& id, bool alive = true,
                            const std::string& status = "IDLE") {
        Disciple d;
        d.id = id;
        d.name = "弟子" + id;
        d.realm = 9;
        d.realmLayer = 1;
        d.isAlive = alive;
        d.spiritRootType = "metal";
        d.status = status;
        d.currentHp = 100;
        d.currentMp = 50;
        core_->state().disciples.appendDisciple(d);
        return *core_->state().disciples.rowOf(id);
    }

    /// rngStates 快照（零消费审计基准）
    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    FixedClock clock_;
    ConsoleLogger logger_;
    std::unique_ptr<GameCore> core_;
};

// ── 拜师 ─────────────────────────────────────────────────────

TEST_F(DiscipleLifecycleTxFixture, ApprenticeTx_Happy_落表与双侧日志草稿) {
    addDisciple("1");
    addDisciple("2");
    const auto before = rngSnapshot();

    const auto r = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                        {{"discipleId", "1"}, {"masterId", "2"}});
    ASSERT_EQ(r["status"], "success");
    ASSERT_TRUE(r["data"]["apprenticed"].get<bool>());
    EXPECT_EQ(r["data"]["apprenticeLogLine"], "拜弟子2为师");
    EXPECT_EQ(r["data"]["masterLogLine"], "收弟子1为徒");

    const auto row = *core_->state().disciples.rowOf("1");
    EXPECT_EQ(core_->state().disciples.masterIds[row], "2");
    EXPECT_EQ(rngSnapshot(), before);
}

TEST_F(DiscipleLifecycleTxFixture, ApprenticeTx_FailureArms_三相校验零写入) {
    addDisciple("1");
    addDisciple("2");
    addDisciple("3", /*alive=*/false);
    const auto before = rngSnapshot();

    // 相 1：徒弟不存在 / 师父不存在
    auto r1 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "999"}, {"masterId", "2"}});
    EXPECT_EQ(r1["code"], "NotFound");
    auto r2 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "1"}, {"masterId", "999"}});
    EXPECT_EQ(r2["code"], "NotFound");

    // 相 2：自拜 / 徒弟死亡 / 师父死亡
    auto r3 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "1"}, {"masterId", "1"}});
    EXPECT_EQ(r3["code"], "SlotInvalid");
    auto r4 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "3"}, {"masterId", "2"}});
    EXPECT_EQ(r4["code"], "NotAlive");
    auto r5 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "1"}, {"masterId", "3"}});
    EXPECT_EQ(r5["code"], "NotAlive");

    // 零写入
    for (std::size_t row = 0; row < core_->state().disciples.ids.size(); ++row) {
        EXPECT_TRUE(core_->state().disciples.masterIds[row].empty());
    }
    EXPECT_EQ(rngSnapshot(), before);
}

TEST_F(DiscipleLifecycleTxFixture, ApprenticeTx_Capacity_已有师父与名额满与死亡不计数) {
    addDisciple("1");
    addDisciple("2");  // 师父
    addDisciple("10");
    addDisciple("11");
    addDisciple("12");
    addDisciple("13");
    addDisciple("14");
    addDisciple("15", /*alive=*/false);  // 已死亡徒弟：不占名额
    addDisciple("16");
    const auto before = rngSnapshot();

    auto rowOf = [&](const std::string& id) {
        return *core_->state().disciples.rowOf(id);
    };
    auto& masterIds = core_->state().disciples.masterIds;
    // 弟子 1 已有师父（师父 9 非在册弟子字符串——名额相命中即回退，
    // 相 1 存在性校验的师父存在性另由 FailureArms 臂覆盖）
    masterIds[rowOf("1")] = "9";
    // 师父 2 已收 4 名存活徒弟 + 1 名已死亡徒弟（15——不计入名额）
    for (const std::string& id : {"10", "11", "12", "13"}) {
        masterIds[rowOf(id)] = "2";
    }
    masterIds[rowOf("15")] = "2";

    // 臂：弟子已有师父（不可更改）
    auto r1 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "1"}, {"masterId", "2"}});
    EXPECT_EQ(r1["code"], "SlotInvalid");

    // 臂：已死亡弟子不可拜师（相 2 早退先于名额相）
    auto r2 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "15"}, {"masterId", "2"}});
    EXPECT_EQ(r2["code"], "NotAlive");

    // 死亡徒弟不占名额：4 存活 + 1 死亡 → 第 5 名存活徒弟可拜（成功）
    auto r3 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "16"}, {"masterId", "2"}});
    ASSERT_EQ(r3["status"], "success") << r3["code"];
    EXPECT_EQ(masterIds[rowOf("16")], "2");

    // 名额满：5 名存活徒弟后再拜 → SlotInvalid（最多5名）
    auto r4 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "14"}, {"masterId", "2"}});
    EXPECT_EQ(r4["code"], "SlotInvalid");
    EXPECT_NE(r4["message"].get<std::string>().find("5"), std::string::npos);

    EXPECT_EQ(rngSnapshot(), before);
}

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
