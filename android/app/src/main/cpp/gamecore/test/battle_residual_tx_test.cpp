// ============================================================
// battle_residual_tx_test — 战斗域残差事务守护（W4-C · w3-06 下沉）
//
// 守护目标：battle_residual_tx.h 三事务与 Kotlin 源语义逐位一致——
//   - settleBattleCasualtiesTx（1780）：标死三列 + wasAlive 守卫年死亡计数
//     （重入不双计）+ 悲痛列与日志草稿（道侣关系文本）+ 槽位/熟练度清理 +
//     幸存者 HP/MP 钳制回写 + 零 RNG
//   - worldLevelVictoryTx（1781）：TOCTOU 重查零写入 + applied 回执 +
//     🔴 defeated 不写（batch-13 口径）
//   - battlePresettleTx（1782）：候选 = 传入队伍 id 集（非队伍满修为弟子
//     **零抽取**——抽取集不变红线）+ 空集零写入 + 突破字段写回
//   - 信封级：execute 通道 status/data 面 + 段内未实裁号 NOT_IMPLEMENTED
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
#include "gamecore/state/models.h"
#include "gamecore/system/battle_residual_tx.h"

namespace gamecore {
namespace {

using gamecore::state::Disciple;
using gamecore::state::ProductionSlot;
using gamecore::state::WorldLevel;
using nlohmann::json;

class BattleResidualTxFixture : public ::testing::Test {
protected:
    void SetUp() override {
        clock_ = std::make_unique<FixedClock>();
        logger_ = std::make_unique<ConsoleLogger>();
        core_ = std::make_unique<GameCore>(clock_.get(), logger_.get());
        GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core_->initialize(config);
    }
    void TearDown() override {
        core_->shutdown();
        core_.reset();
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result = core_->execute(actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    std::size_t addDisciple(const std::string& id, int32_t realm = 5,
                            bool alive = true) {
        Disciple d;
        d.id = id;
        d.name = "弟子" + id;
        d.realm = realm;
        d.realmLayer = 1;
        d.isAlive = alive;
        d.spiritRootType = "metal";
        d.status = "IDLE";
        core_->state().disciples.appendDisciple(d);
        return *core_->state().disciples.rowOf(id);
    }

    // RNG 快照经导出链取（syncRngStates 为私有——gameData.rngStates 平时滞后）
    std::map<int32_t, int64_t> rngSnapshot() const {
        const json j = json::parse(core_->exportStateJson());
        std::map<int32_t, int64_t> out;
        for (auto it = j.at("gameData").at("rngStates").begin();
             it != j.at("gameData").at("rngStates").end(); ++it) {
            out[std::stoi(it.key())] = it.value().get<int64_t>();
        }
        return out;
    }

    std::unique_ptr<FixedClock> clock_;
    std::unique_ptr<ConsoleLogger> logger_;
    std::unique_ptr<GameCore> core_;
};

// ── 1780 伤亡残差 ────────────────────────────────────────────────────

TEST_F(BattleResidualTxFixture, CasualtySettleWritesDeathColumnsGriefAndSlots) {
    auto& ds = core_->state().disciples;
    auto& gd = core_->state().gameData;
    const std::size_t doomed = addDisciple("101");
    const std::size_t partner = addDisciple("102");
    const std::size_t survivor = addDisciple("103");
    ds.partnerIds[partner] = "101";
    ds.currentHps[doomed] = 100;
    ds.currentHps[survivor] = 100;
    ds.currentMps[survivor] = 100;
    // 槽位与熟练度（G07 重伤路径不清槽/不清熟练度）
    gd.elderSlots.lawEnforcementElder = "101";
    gd.manualProficiencies["101"] = {};
    gd.spiritMineSlots.push_back({1, "101", "弟子101"});
    const int32_t yearBefore = gd.gameYear;

    const json reply = exec(action::BATTLE_CASUALTY_SETTLE_TX, {
        {"deadIds", json::array({"101"})},
        {"survivorHp", json{{"103", 50}}},
        {"survivorMp", json::object()},
    });
    ASSERT_EQ(reply["status"], "success");
    const json& data = reply["data"];
    ASSERT_EQ(data["markedDeadIds"], json::array({"101"}));

    // G07 重伤：HP=1 存活，不写死亡三元组、不计年报死亡
    EXPECT_EQ(ds.currentHps[doomed], 1);
    EXPECT_EQ(ds.isAlive[doomed], 1);
    EXPECT_NE(ds.statuses[doomed], "DEAD");
    EXPECT_EQ(gd.annualDeceasedDisciples, 0);
    // 不触发悲痛（非死亡）：griefEndYears 保持 -1 哨兵
    EXPECT_EQ(ds.griefEndYears[partner], -1);
    ASSERT_TRUE(data["lifeEventDrafts"].empty());
    // 幸存者 HP/MP 回写
    EXPECT_EQ(ds.currentHps[survivor], 50);
    // G07 重伤保留槽位与熟练度
    EXPECT_EQ(gd.elderSlots.lawEnforcementElder, "101");
    EXPECT_EQ(gd.spiritMineSlots[0].discipleId, "101");
    EXPECT_EQ(gd.manualProficiencies.count("101"), 1u);
    EXPECT_TRUE(data["overflowDrafts"].empty());
}

TEST_F(BattleResidualTxFixture, CasualtySettleReentryDoesNotDoubleCount) {
    auto& ds = core_->state().disciples;
    const std::size_t doomed = addDisciple("111");
    ds.currentHps[doomed] = 100;
    const json params = {
        {"deadIds", json::array({"111"})},
        {"survivorHp", json::object()},
        {"survivorMp", json::object()},
    };
    ASSERT_EQ(exec(action::BATTLE_CASUALTY_SETTLE_TX, params)["status"], "success");
    EXPECT_EQ(core_->state().gameData.annualDeceasedDisciples, 0);
    EXPECT_EQ(ds.currentHps[doomed], 1);

    // 重入：仍为重伤语义（HP=1、存活、不计年报死亡）
    const json reply = exec(action::BATTLE_CASUALTY_SETTLE_TX, params);
    ASSERT_EQ(reply["status"], "success");
    EXPECT_EQ(ds.isAlive[doomed], 1);
    EXPECT_EQ(ds.currentHps[doomed], 1);
    EXPECT_EQ(core_->state().gameData.annualDeceasedDisciples, 0);
}

TEST_F(BattleResidualTxFixture, CasualtySettleConsumesZeroRng) {
    addDisciple("121");
    const auto before = rngSnapshot();
    exec(action::BATTLE_CASUALTY_SETTLE_TX, {
        {"deadIds", json::array({"121"})},
        {"survivorHp", json::object()},
        {"survivorMp", json::object()},
    });
    EXPECT_EQ(rngSnapshot(), before);
}

// ── 1781 关卡胜利事务 ────────────────────────────────────────────────

TEST_F(BattleResidualTxFixture, VictoryTxAppliesWithoutDefeated) {
    auto& ds = core_->state().disciples;
    auto& gd = core_->state().gameData;
    addDisciple("201");
    addDisciple("202");
    const std::size_t dead = addDisciple("203", 5, false);

    WorldLevel level;
    level.id = "L1";

    level.defeated = false;
    gd.worldLevels.push_back(level);

    const json reply = exec(action::WORLD_VICTORY_REWARDS_TX, {
        {"levelId", "L1"},
        {"survivorIds", json::array({"201", "202", "203"})},
    });
    ASSERT_EQ(reply["status"], "success");
    EXPECT_EQ(reply["data"]["applied"], true);
    // 🔴 C++ 不写 defeated（batch-13 TOCTOU 口径——残差留 Kotlin 臂）
    EXPECT_FALSE(gd.worldLevels[0].defeated);
    EXPECT_EQ(ds.isAlive[dead], 0);
}

TEST_F(BattleResidualTxFixture, VictoryTxRecheckSkipsAlreadyDefeatedLevel) {
    auto& gd = core_->state().gameData;
    addDisciple("211");
    WorldLevel level;
    level.id = "L2";
    level.defeated = true;   // TOCTOU：已击败
    gd.worldLevels.push_back(level);

    const auto rngBefore = rngSnapshot();
    const json reply = exec(action::WORLD_VICTORY_REWARDS_TX, {
        {"levelId", "L2"},
        {"survivorIds", json::array({"211"})},
    });
    ASSERT_EQ(reply["status"], "success");
    EXPECT_EQ(reply["data"]["applied"], false);
    EXPECT_EQ(rngSnapshot(), rngBefore);   // 零抽取
}

TEST_F(BattleResidualTxFixture, VictoryTxUnknownLevelIsSuccessZeroWrite) {
    const json reply = exec(action::WORLD_VICTORY_REWARDS_TX, {
        {"levelId", "NOPE"},
        {"survivorIds", json::array({"221"})},
    });
    ASSERT_EQ(reply["status"], "success");
    EXPECT_EQ(reply["data"]["applied"], false);
}

// ── 1782 战前突破结算 ────────────────────────────────────────────────

/// 满修为满血蓝候选（realm>0 ∧ cultivation≥max ∧ currentHp/Mp=-1 满语义）
void makeCandidate(GameCore& core, const std::string& id) {
    Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.realm = 2;
    d.realmLayer = 1;
    d.isAlive = true;
    d.spiritRootType = "metal";
    d.status = "IDLE";
    d.cultivation = 1.0e7;   // 远超该境界满修为
    d.currentHp = -1;
    d.currentMp = -1;
    core.state().disciples.appendDisciple(d);
}

TEST_F(BattleResidualTxFixture, PresettleBreaksThroughCandidateOnly) {
    auto& ds = core_->state().disciples;
    makeCandidate(*core_, "301");
    // 未满修为：不进候选、零抽取
    const std::size_t notFull = addDisciple("302");
    // 队伍外满修为：不进候选、零抽取（抽取集不变红线）
    makeCandidate(*core_, "303");

    const auto rngBefore = rngSnapshot();
    const json reply = exec(action::BATTLE_PRESETTLE_TX, {
        {"discipleIds", json::array({"301", "302"})},
    });
    ASSERT_EQ(reply["status"], "success");
    EXPECT_EQ(reply["data"]["candidateCount"], 1);
    // 抽取面：仅候选触发（302/303 均未进入候选）
    EXPECT_NE(rngSnapshot(), rngBefore);   // 301 消耗恰 1 次 BREAKTHROUGH 抽取
    // 302/303 状态零变化
    EXPECT_EQ(ds.cultivations[notFull], 0.0);
    // 301 突破成功或失败二选一，且与字段面自洽
    const double cult = ds.cultivations[ds.rowOf("301").value()];
    const int32_t layer = ds.realmLayers[ds.rowOf("301").value()];
    if (layer == 2) {
        EXPECT_EQ(cult, 0.0);   // 成功：层数+1、修为清零
    } else {
        EXPECT_EQ(layer, 1);
        EXPECT_EQ(cult, 0.0);   // 失败：修为清零 + HP/MP 折减
    }
}

TEST_F(BattleResidualTxFixture, PresettleExcludesNonTeamMembersFromRngSet) {
    makeCandidate(*core_, "311");   // 满修为但不在传入 id 集
    const auto rngBefore = rngSnapshot();
    const json reply = exec(action::BATTLE_PRESETTLE_TX, {
        {"discipleIds", json::array({"999-not-a-candidate"})},
    });
    ASSERT_EQ(reply["status"], "success");
    EXPECT_EQ(reply["data"]["candidateCount"], 0);
    EXPECT_EQ(rngSnapshot(), rngBefore);   // 🔴 零抽取
}

TEST_F(BattleResidualTxFixture, PresettleEmptyInputIsSuccessZeroWrite) {
    const auto rngBefore = rngSnapshot();
    const json reply = exec(action::BATTLE_PRESETTLE_TX, {
        {"discipleIds", json::array()},
    });
    ASSERT_EQ(reply["status"], "success");
    EXPECT_EQ(reply["data"]["candidateCount"], 0);
    EXPECT_EQ(rngSnapshot(), rngBefore);
}

// ── 信封级：段内未实裁号 → NOT_IMPLEMENTED 兜底 ─────────────────────

TEST_F(BattleResidualTxFixture, UnclaimedIdInRangeFallsThroughToNotImplemented) {
    // 1790–1799（w3-07 登记不下沉）与段内空洞号不认领 → execute 兜底信封
    const json reply = exec(1785, json::object());
    EXPECT_EQ(reply["status"], "failure");
}

}  // namespace
}  // namespace gamecore
