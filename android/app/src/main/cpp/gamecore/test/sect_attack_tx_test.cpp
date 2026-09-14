// ============================================================
// sect_attack_tx_test — 攻宗确定性写回事务守护（batch-20b）
//
// 守护目标：sect_attack_tx.h 两事务与 Kotlin 源语义逐位一致——
//   - removeDeadDefendersTx：**仅目标池**过滤阵亡者（其余池原样）+
//     **仅目标宗门**驻军槽清空（保留 index、展示字段全清）+ 空阵亡集无操作
//   - grantWarSoulPowersTx：id 集 ∧ 存活 双重过滤 + 逐行 +1 +
//     非存活/不在表静默跳过 + 空集无操作
//   - **零 RNG 全分区快照差分** + **双运行全状态逐位一致**
//   - 信封级：execute 通道 status/data 面 + 失败/未知动作信封
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
#include "gamecore/system/sect_attack_tx.h"

namespace gamecore {
namespace {

namespace sect_attack_tx = gamecore::system::sect_attack_tx;

using gamecore::state::Disciple;
using gamecore::state::GarrisonSlot;
using gamecore::state::WorldSect;

class SectAttackTxFixture : public ::testing::Test {
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

    /// 挂一个存活弟子（列直访用），返回行号
    std::size_t addDisciple(const std::string& id, int32_t realm = 9, bool alive = true) {
        Disciple d;
        d.id = id;
        d.name = "弟子" + id;
        d.realm = realm;
        d.realmLayer = 1;
        d.isAlive = alive;
        d.spiritRootType = "metal";
        d.age = 20;
        d.lifespan = 80;
        d.status = "IDLE";
        core_->state().disciples.appendDisciple(d);
        return *core_->state().disciples.rowOf(id);
    }

    /// AI 宗门弟子池条目
    static Disciple aiDisciple(const std::string& id) {
        Disciple d;
        d.id = id;
        d.name = "AI" + id;
        d.realm = 8;
        d.realmLayer = 1;
        d.isAlive = true;
        d.status = "IDLE";
        return d;
    }

    /// 世界宗门（含驻军槽）
    void addSect(const std::string& id, bool isPlayer,
                 const std::vector<std::pair<int32_t, std::string>>& garrison) {
        WorldSect s;
        s.id = id;
        s.name = "宗门" + id;
        s.isPlayerSect = isPlayer;
        for (const auto& [index, discipleId] : garrison) {
            GarrisonSlot gs;
            gs.index = index;
            gs.discipleId = discipleId;
            gs.discipleName = discipleId.empty() ? "" : ("弟子" + discipleId);
            gs.discipleRealm = "筑基1层";
            gs.portraitRes = "portrait_" + discipleId;
            s.garrisonSlots.push_back(std::move(gs));
        }
        core_->state().gameData.worldMapSects.push_back(std::move(s));
    }

    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    const std::vector<Disciple>& pool(const std::string& sectId) {
        return core_->state().aiSectDisciples[sectId];
    }

    const WorldSect& sect(const std::string& id) {
        for (const auto& s : core_->state().gameData.worldMapSects) {
            if (s.id == id) return s;
        }
        static const WorldSect kEmpty;
        return kEmpty;
    }

    std::vector<std::unique_ptr<FixedClock>> clocks_;
    std::vector<std::unique_ptr<ConsoleLogger>> loggers_;
    std::unique_ptr<GameCore> core_;
};

// ── 阵亡守军清理 ────────────────────────────────────────────────────────

TEST_F(SectAttackTxFixture, RemoveDeadDefendersOnlyTouchesTargetPoolAndSect) {
    core_->state().aiSectDisciples["ai_1"] = {
        aiDisciple("d1"), aiDisciple("d2"), aiDisciple("d3")};
    core_->state().aiSectDisciples["ai_2"] = {
        aiDisciple("d1"), aiDisciple("d9")};  // 同 id 不同池——不得被误删
    addSect("sect_a", false, {{0, "d1"}, {1, "d2"}, {2, "live"}});
    addSect("sect_b", false, {{0, "d1"}});  // 非目标宗门

    const auto r = sect_attack_tx::removeDeadDefendersTx(
        core_->state(), "sect_a", "ai_1", {"d1", "d3"});
    EXPECT_TRUE(r.base.ok);
    EXPECT_EQ(r.removedFromPool, 2);

    // 目标池：仅 d2 存活保留
    ASSERT_EQ(pool("ai_1").size(), 1u);
    EXPECT_EQ(pool("ai_1")[0].id, "d2");
    // 非目标池：原样（含同 id "d1"）
    ASSERT_EQ(pool("ai_2").size(), 2u);
    EXPECT_EQ(pool("ai_2")[0].id, "d1");

    // 目标宗门：槽 0 清空保留 index，槽 1 原样，槽 2 原样
    const auto& a = sect("sect_a");
    ASSERT_EQ(a.garrisonSlots.size(), 3u);
    EXPECT_EQ(a.garrisonSlots[0].index, 0);
    EXPECT_TRUE(a.garrisonSlots[0].discipleId.empty());
    EXPECT_TRUE(a.garrisonSlots[0].discipleName.empty());
    EXPECT_TRUE(a.garrisonSlots[0].discipleRealm.empty());
    EXPECT_TRUE(a.garrisonSlots[0].portraitRes.empty());
    EXPECT_EQ(a.garrisonSlots[1].discipleId, "d2");
    EXPECT_EQ(a.garrisonSlots[2].discipleId, "live");
    EXPECT_EQ(r.clearedGarrisonSlots, 1);

    // 非目标宗门：原样
    EXPECT_EQ(sect("sect_b").garrisonSlots[0].discipleId, "d1");
}

TEST_F(SectAttackTxFixture, RemoveDeadDefendersEmptySetIsNoop) {
    core_->state().aiSectDisciples["ai_1"] = {aiDisciple("d1")};
    addSect("sect_a", false, {{0, "d1"}});
    const auto before = core_->exportStateJson();

    const auto r = sect_attack_tx::removeDeadDefendersTx(core_->state(), "sect_a",
                                                        "ai_1", {});
    EXPECT_TRUE(r.base.ok);
    EXPECT_EQ(r.removedFromPool, 0);
    EXPECT_EQ(r.clearedGarrisonSlots, 0);
    EXPECT_EQ(core_->exportStateJson(), before);
}

TEST_F(SectAttackTxFixture, RemoveDeadDefendersMissingPoolAndSectTolerant) {
    // 池与宗门均不存在：不崩、零计数（Kotlin mapValues/map identity 分支同义）
    const auto r = sect_attack_tx::removeDeadDefendersTx(
        core_->state(), "no_such_sect", "no_such_pool", {"d1"});
    EXPECT_TRUE(r.base.ok);
    EXPECT_EQ(r.removedFromPool, 0);
    EXPECT_EQ(r.clearedGarrisonSlots, 0);
}

// ── 魂魄发放 ────────────────────────────────────────────────────────────

TEST_F(SectAttackTxFixture, GrantSoulPowersFiltersAliveAndUnknown) {
    const std::size_t r1 = addDisciple("1");
    addDisciple("2", 9, /*alive=*/false);
    const std::size_t r3 = addDisciple("3");

    const auto r = sect_attack_tx::grantWarSoulPowersTx(core_->state(),
                                                        {"1", "2", "404"});
    EXPECT_TRUE(r.base.ok);
    EXPECT_EQ(r.granted, 1);  // 仅存活且在表者

    auto& ds = core_->state().disciples;
    EXPECT_EQ(ds.soulPowers[r1], 1);
    EXPECT_EQ(ds.soulPowers[ds.rowOf("2").value()], 0);  // 已故不自增
    EXPECT_EQ(ds.soulPowers[r3], 0);                     // 未在集合内
}

TEST_F(SectAttackTxFixture, GrantSoulPowersAccumulatesAndEmptySetIsNoop) {
    const std::size_t row = addDisciple("1");
    auto& ds = core_->state().disciples;
    ds.soulPowers[row] = 5;

    ASSERT_TRUE(sect_attack_tx::grantWarSoulPowersTx(core_->state(), {"1"}).base.ok);
    EXPECT_EQ(ds.soulPowers[row], 6);

    const auto before = core_->exportStateJson();
    const auto empty = sect_attack_tx::grantWarSoulPowersTx(core_->state(), {});
    EXPECT_TRUE(empty.base.ok);
    EXPECT_EQ(empty.granted, 0);
    EXPECT_EQ(core_->exportStateJson(), before);
}

// ── RNG 红线 ────────────────────────────────────────────────────────────

TEST_F(SectAttackTxFixture, ZeroRngFamilyLeavesRngStatesUntouched) {
    addDisciple("1");
    addDisciple("2");
    core_->state().aiSectDisciples["ai_1"] = {
        aiDisciple("d1"), aiDisciple("d2")};
    addSect("sect_a", false, {{0, "d1"}});
    const auto baseline = rngSnapshot();

    ASSERT_TRUE(sect_attack_tx::removeDeadDefendersTx(core_->state(), "sect_a",
                                                      "ai_1", {"d1"}).base.ok);
    ASSERT_TRUE(sect_attack_tx::grantWarSoulPowersTx(core_->state(), {"1"}).base.ok);

    EXPECT_EQ(rngSnapshot(), baseline);
}

TEST_F(SectAttackTxFixture, DoubleRunBitwiseIdentical) {
    auto run = [&]() {
        addDisciple("1");
        addDisciple("2");
        core_->state().aiSectDisciples["ai_1"] = {
            aiDisciple("d1"), aiDisciple("d2"), aiDisciple("d3")};
        core_->state().aiSectDisciples["ai_2"] = {aiDisciple("d1")};
        addSect("sect_a", false, {{0, "d1"}, {1, "d2"}});
        addSect("sect_b", true, {{0, "1"}});
        sect_attack_tx::removeDeadDefendersTx(core_->state(), "sect_a", "ai_1",
                                              {"d1", "d3"});
        sect_attack_tx::grantWarSoulPowersTx(core_->state(), {"1"});
        return core_->exportStateJson();
    };

    const std::string first = run();
    SetUp();
    const std::string second = run();
    EXPECT_EQ(second, first);
}

// ── 信封级（Kotlin 回退臂契约）──────────────────────────────────────────

TEST_F(SectAttackTxFixture, DispatchEnvelopeSuccess) {
    addDisciple("1");
    core_->state().aiSectDisciples["ai_1"] = {aiDisciple("d1")};
    addSect("sect_a", false, {{0, "d1"}});

    auto env = exec(action::SECT_ATTACK_REMOVE_DEAD_DEFENDERS_TX,
                    {{"sectId", "sect_a"},
                     {"defenderPoolSectId", "ai_1"},
                     {"deadDefenderIds", {"d1"}}});
    ASSERT_TRUE(env.contains("status")) << env.dump();
    EXPECT_EQ(env.at("status").get<std::string>(), "success") << env.dump();
    EXPECT_EQ(env.at("data").at("removedFromPool").get<int32_t>(), 1);
    EXPECT_EQ(env.at("data").at("clearedGarrisonSlots").get<int32_t>(), 1);
    EXPECT_TRUE(pool("ai_1").empty());

    env = exec(action::SECT_ATTACK_GRANT_SOUL_POWERS_TX,
               {{"sectSurvivorIds", {"1"}}});
    EXPECT_EQ(env.at("status").get<std::string>(), "success") << env.dump();
    EXPECT_EQ(env.at("data").at("granted").get<int32_t>(), 1);
}

TEST_F(SectAttackTxFixture, DispatchEnvelopeUnknownActionFails) {
    // 段内未注册动作（1713）→ NOT_IMPLEMENTED（前向兼容：Kotlin 侧不注册即不可达）
    const std::string result = core_->execute(1713, "{}", 1000);
    const auto env = nlohmann::json::parse(result);
    EXPECT_EQ(env.at("status").get<std::string>(), "failure") << env.dump();
    EXPECT_EQ(env.at("code").get<std::string>(), "NOT_IMPLEMENTED") << env.dump();
}

}  // namespace
}  // namespace gamecore
