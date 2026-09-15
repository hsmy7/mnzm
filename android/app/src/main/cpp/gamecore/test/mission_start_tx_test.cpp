// ============================================================
// mission_start_tx_test — 任务派遣事务守护（W4-D 续批·任务域收口，
// MISSION_START_TX=1861）
//
// 守护目标：mission_start_tx.h 与 Kotlin startMission 事务体语义逐位一致——
//   - 成功路径：availableMissions 模板快照 → ActiveMission 全字段
//     （含队员 id/name/realm 快照与 startYear/Month）
//   - 全槽位清理（保留住所——includeResidence=false）+ 状态重置 IDLE
//     （REFLECTING 剥离 reflection 键 / REFINING 剥离 buildingId）
//   - 模板不存在 = MISSION_NOT_FOUND 失败信封（零写入）
//   - 缺行队员静默跳过清理但保留 ActiveMission 成员位（Kotlin 同语义）
//   - 抽取序零扰动（红线 1：事务零 RNG，rngStates 快照差分恒零）
//   - 端口分派形状：execute(1861) 走 W4-D 端口 + 缺参 INVALID_PARAMS
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
#include "gamecore/system/mission_start_tx.h"

namespace gamecore {
namespace {

using gamecore::state::Disciple;
using gamecore::state::Mission;

class MissionStartTxFixture : public ::testing::Test {
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
        // 时间线固定（y3 m5）——startYear/startMonth 快照断言用
        core->state().gameData.gameYear = 3;
        core->state().gameData.gameMonth = 5;
        clocks_.push_back(std::move(clock));
        loggers_.push_back(std::move(logger));
        return core;
    }

    std::size_t addDisciple(const std::string& id, const std::string& status = "IDLE") {
        Disciple d;
        d.id = id;
        d.name = "弟子" + id;
        d.realm = 9;  // 炼气
        d.isAlive = true;
        d.status = status;
        d.currentHp = 100;
        d.currentMp = 50;
        if (status == "REFLECTING") {
            d.statusData["reflectionStartYear"] = "3";
            d.statusData["reflectionEndYear"] = "3";
        } else if (status == "REFINING") {
            d.statusData["buildingId"] = "bld-1";
        }
        core_->state().disciples.appendDisciple(d);
        return *core_->state().disciples.rowOf(id);
    }

    Mission addTemplate(const std::string& id) {
        Mission m;
        m.id = id;
        m.template_ = "ESCORT_CARAVAN";
        m.name = "护送商队";
        m.difficulty = "SIMPLE";
        m.duration = 6;
        m.rewards.spiritStones = 500;
        m.missionType = "NO_COMBAT";
        m.enemyType = "BEAST";
        m.triggerChance = 0.5;
        core_->state().gameData.availableMissions.push_back(m);
        return core_->state().gameData.availableMissions.back();
    }

    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result = core_->execute(actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    std::vector<std::unique_ptr<FixedClock>> clocks_;
    std::vector<std::unique_ptr<ConsoleLogger>> loggers_;
    std::unique_ptr<GameCore> core_;
};

TEST_F(MissionStartTxFixture, SnapshotsTemplateAndMembersIntoActiveMissions) {
    addTemplate("gc-mission-1");
    addDisciple("11");
    addDisciple("12");

    const auto r = system::mission_tx::startMissionTx(
        core_->state(), "gc-mission-1", "am-uuid-1", {"11", "12"});
    ASSERT_TRUE(r.started);
    auto& gd = core_->state().gameData;
    ASSERT_EQ(gd.activeMissions.size(), 1u);
    const auto& am = gd.activeMissions[0];
    EXPECT_EQ(am.id, "am-uuid-1");
    EXPECT_EQ(am.missionId, "gc-mission-1");
    EXPECT_EQ(am.missionName, "护送商队");
    EXPECT_EQ(am.template_, "ESCORT_CARAVAN");
    EXPECT_EQ(am.difficulty, "SIMPLE");
    EXPECT_EQ(am.duration, 6);
    EXPECT_EQ(am.rewards.spiritStones, 500);
    EXPECT_EQ(am.missionType, "NO_COMBAT");
    EXPECT_EQ(am.enemyType, "BEAST");
    EXPECT_DOUBLE_EQ(am.triggerChance, 0.5);
    EXPECT_EQ(am.startYear, 3);
    EXPECT_EQ(am.startMonth, 5);
    ASSERT_EQ(am.discipleIds.size(), 2u);
    EXPECT_EQ(am.discipleIds[0], "11");
    EXPECT_EQ(am.discipleNames[0], "弟子11");
    EXPECT_EQ(am.discipleRealms[0], "炼气");
    EXPECT_EQ(am.discipleRealms[1], "炼气");
}

TEST_F(MissionStartTxFixture, ClearsWorkSlotsKeepsResidenceAndResetsStatus) {
    addTemplate("gc-mission-2");
    addDisciple("21", /*status*/ "REFINING");
    addDisciple("22", /*status*/ "REFLECTING");
    auto& gd = core_->state().gameData;
    // 21：巡逻槽占用 + 住所占用（住所保留）；22：思过（键剥离）
    gd.patrolSlots.push_back(state::PatrolSlot{0, "21", "弟子21", "炼气", "", ""});
    gd.residenceSlots.push_back(state::ResidenceSlot{"res-1", 0, "21", "弟子21"});

    const auto r = system::mission_tx::startMissionTx(
        core_->state(), "gc-mission-2", "am-uuid-2", {"21", "22"});
    ASSERT_TRUE(r.started);
    auto& store = core_->state().disciples;
    // 巡逻槽清理 / 住所保留
    EXPECT_TRUE(gd.patrolSlots[0].discipleId.empty());
    EXPECT_EQ(gd.residenceSlots[0].discipleId, "21");
    // 状态重置 + 键剥离
    EXPECT_EQ(store.statuses[*store.rowOf("21")], "IDLE");
    EXPECT_EQ(store.statusData[*store.rowOf("21")].count("buildingId"), 0u);
    EXPECT_EQ(store.statuses[*store.rowOf("22")], "IDLE");
    EXPECT_EQ(store.statusData[*store.rowOf("22")].count("reflectionStartYear"), 0u);
    EXPECT_EQ(store.statusData[*store.rowOf("22")].count("reflectionEndYear"), 0u);
}

TEST_F(MissionStartTxFixture, UnknownMissionFailsWithZeroWrites) {
    addTemplate("gc-mission-3");
    addDisciple("31");
    const auto r = system::mission_tx::startMissionTx(
        core_->state(), "gc-mission-NOPE", "am-uuid-3", {"31"});
    ASSERT_FALSE(r.started);
    EXPECT_STREQ(r.errorCode, "MISSION_NOT_FOUND");
    // 零写入：activeMissions 为空、弟子状态未被清理
    EXPECT_TRUE(core_->state().gameData.activeMissions.empty());
    auto& store = core_->state().disciples;
    const auto row = *store.rowOf("31");
    EXPECT_EQ(store.statusData[row].size(), 0u);
}

TEST_F(MissionStartTxFixture, MissingDiscipleRowSkipsCleanupButKeepsMemberSlot) {
    addTemplate("gc-mission-4");
    addDisciple("41");
    const auto r = system::mission_tx::startMissionTx(
        core_->state(), "gc-mission-4", "am-uuid-4", {"41", "999"});
    ASSERT_TRUE(r.started);
    const auto& am = core_->state().gameData.activeMissions[0];
    // Kotlin 同语义：缺行队员不参与清理，但 ActiveMission 保留全部传入 id
    ASSERT_EQ(am.discipleIds.size(), 2u);
    EXPECT_EQ(am.discipleIds[1], "999");
    EXPECT_EQ(am.discipleNames[1], "");
    EXPECT_EQ(am.discipleRealms[1], "");
    EXPECT_EQ(am.discipleNames[0], "弟子41");
}

TEST_F(MissionStartTxFixture, TransactionConsumesNoRngExtraction) {
    addTemplate("gc-mission-5");
    addDisciple("51");
    const auto before = rngSnapshot();
    system::mission_tx::startMissionTx(
        core_->state(), "gc-mission-5", "am-uuid-5", {"51"});
    // 红线 1：事务零抽取——全分区 rngStates 快照差分恒零
    EXPECT_EQ(rngSnapshot(), before);
}

TEST_F(MissionStartTxFixture, DispatchReturnsSuccessEnvelopeViaW4DPort) {
    addTemplate("gc-mission-6");
    addDisciple("61");
    const nlohmann::json resp = exec(action::MISSION_START_TX, {
        {"missionId", "gc-mission-6"},
        {"activeMissionId", "am-uuid-6"},
        {"discipleIds", nlohmann::json::array({"61"})},
    });
    EXPECT_EQ(resp["status"], "success");
    EXPECT_EQ(resp["data"]["started"], true);
    EXPECT_EQ(resp["data"]["activeMissionId"], "am-uuid-6");

    // 缺参 → INVALID_PARAMS 失败信封（Kotlin 回退臂接管）
    const nlohmann::json bad = exec(action::MISSION_START_TX, {{"missionId", "gc-mission-6"}});
    EXPECT_EQ(bad["status"], "failure");
    EXPECT_EQ(bad["code"], "INVALID_PARAMS");

    // 模板不存在 → MISSION_NOT_FOUND 失败信封
    const nlohmann::json nf = exec(action::MISSION_START_TX, {
        {"missionId", "gc-mission-NOPE"},
        {"activeMissionId", "am-uuid-x"},
        {"discipleIds", nlohmann::json::array({"61"})},
    });
    EXPECT_EQ(nf["status"], "failure");
    EXPECT_EQ(nf["code"], "MISSION_NOT_FOUND");
}

}  // namespace
}  // namespace gamecore
