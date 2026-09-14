// ============================================================
// secret_realm_residual_tx_test — 秘境域残差事务守护（W4-C · w3-08 下沉）
//
// 守护目标：secret_realm_residual_tx.h 两事务与 Kotlin 源语义逐位一致——
//   - secretRealmStartReleaseTx（1800）：11 类槽位清理（含生产槽/长老单值）
//     + 状态重置（REFLECTING 清思过标记 / REFINING 清 buildingId /
//     其余 → IDLE）+ 不存在 id 静默跳过 + 零 RNG
//   - secretRealmExpiryGuardTx（1801）：未到期成功零写入（expired=false）+
//     到期关闭状态段（秘境/会话清场 + 冷却年）+ 关闭草稿（成员 + 背包快照
//     + slotId）+ 零 RNG
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

namespace gamecore {
namespace {

using gamecore::state::Disciple;
using gamecore::state::ProductionSlot;
using gamecore::state::WorldLevel;
using nlohmann::json;

class SecretRealmResidualTxFixture : public ::testing::Test {
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

    std::size_t addDisciple(const std::string& id) {
        Disciple d;
        d.id = id;
        d.name = "弟子" + id;
        d.realm = 3;
        d.realmLayer = 1;
        d.isAlive = true;
        d.spiritRootType = "metal";
        d.age = 20;
        d.lifespan = 80;
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

// ── 1800 出发换岗 ────────────────────────────────────────────────────

TEST_F(SecretRealmResidualTxFixture, StartReleaseClearsSlotsAndResetsStatus) {
    auto& ds = core_->state().disciples;
    auto& gd = core_->state().gameData;
    const std::size_t reflecting = addDisciple("401");
    const std::size_t refining = addDisciple("402");
    const std::size_t idle = addDisciple("403");
    ds.statuses[reflecting] = "REFLECTING";
    ds.statusData[reflecting]["reflectionStartYear"] = "3";
    ds.statusData[reflecting]["reflectionEndYear"] = "6";
    ds.statuses[refining] = "REFINING";
    ds.statusData[refining]["buildingId"] = "forge-1";
    // 槽位面：长老单值 + 灵矿 + 生产
    gd.elderSlots.preachingElder = "401";
    gd.spiritMineSlots.push_back({2, "401", "弟子401"});
    ProductionSlot slot;
    slot.buildingId = "alchemy-1";
    slot.assignedDiscipleId = "402";
    slot.assignedDiscipleName = "弟子402";
    gd.productionSlots.push_back(slot);

    const auto rngBefore = rngSnapshot();
    const json reply = exec(action::SECRET_REALM_START_RELEASE_TX, {
        {"memberIds", json::array({"401", "402", "403", "999"})},
    });
    ASSERT_EQ(reply["status"], "success");
    const json& data = reply["data"];
    ASSERT_EQ(data["releasedIds"], json::array({"401", "402", "403"}));

    // 思过 → 清标记 + IDLE
    EXPECT_EQ(ds.statuses[reflecting], "IDLE");
    EXPECT_EQ(ds.statusData[reflecting].count("reflectionStartYear"), 0u);
    EXPECT_EQ(ds.statusData[reflecting].count("reflectionEndYear"), 0u);
    // 血炼 → 清 buildingId + IDLE
    EXPECT_EQ(ds.statuses[refining], "IDLE");
    EXPECT_EQ(ds.statusData[refining].count("buildingId"), 0u);
    // IDLE 保持 IDLE
    EXPECT_EQ(ds.statuses[idle], "IDLE");
    // 槽位清理
    EXPECT_TRUE(gd.elderSlots.preachingElder.empty());
    EXPECT_TRUE(gd.spiritMineSlots[0].discipleId.empty());
    EXPECT_FALSE(gd.productionSlots[0].assignedDiscipleId.has_value());
    EXPECT_TRUE(gd.productionSlots[0].assignedDiscipleName.empty());
    // 零 RNG
    EXPECT_EQ(rngSnapshot(), rngBefore);
}

// ── 1801 到期兜底 ────────────────────────────────────────────────────

TEST_F(SecretRealmResidualTxFixture, ExpiryGuardSkipsWhenNotExpired) {
    auto& gd = core_->state().gameData;
    gd.secretRealmState.id = "sr-1";
    gd.secretRealmState.spawnYear = 10;
    gd.gameYear = 12;   // < spawnYear + 5

    const auto rngBefore = rngSnapshot();
    const json reply = exec(action::SECRET_REALM_EXPIRY_GUARD_TX, json::object());
    ASSERT_EQ(reply["status"], "success");
    EXPECT_EQ(reply["data"]["expired"], false);
    // 成功零写入
    EXPECT_EQ(gd.secretRealmState.id, "sr-1");
    EXPECT_EQ(rngSnapshot(), rngBefore);
}

TEST_F(SecretRealmResidualTxFixture, ExpiryGuardClosesExpiredRealmWithDraft) {
    auto& gd = core_->state().gameData;
    auto& ds = core_->state().disciples;
    addDisciple("411");
    gd.secretRealmState.id = "sr-2";
    gd.secretRealmState.spawnYear = 10;
    gd.gameYear = 20;   // ≥ spawnYear + 5
    gd.currentSlot = 2;
    gamecore::state::SecretRealmMemberState member;
    member.discipleId = "411";
    member.isDead = false;
    gd.secretRealmSession.members.push_back(member);
    gd.secretRealmSession.backpack.spiritStones = 120;

    const auto rngBefore = rngSnapshot();
    const json reply = exec(action::SECRET_REALM_EXPIRY_GUARD_TX, json::object());
    ASSERT_EQ(reply["status"], "success");
    const json& data = reply["data"];
    EXPECT_EQ(data["expired"], true);
    // 关闭状态段：秘境/会话清场 + 冷却年
    EXPECT_TRUE(gd.secretRealmState.id.empty());
    EXPECT_TRUE(gd.secretRealmSession.members.empty());
    EXPECT_EQ(gd.secretRealmCooldownYear, 20);
    // 背包清空（灵石入钱包——邮件通道留 Kotlin）
    EXPECT_EQ(gd.secretRealmSession.backpack.spiritStones, 0);
    // 草稿：成员 + slotId + 背包快照（清空前）
    ASSERT_EQ(data["memberIds"], json::array({"411"}));
    EXPECT_EQ(data["slotId"], 2);
    ASSERT_TRUE(data.contains("backpack"));
    EXPECT_EQ(data["backpack"]["spiritStones"], 120);
    // 零 RNG
    EXPECT_EQ(rngSnapshot(), rngBefore);
    (void)ds;
}

}  // namespace
}  // namespace gamecore
