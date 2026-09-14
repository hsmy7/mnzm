// ============================================================
// secret_realm_platform_tx_test — 秘境平台段读档恢复事务守护（batch-20a）
//
// 守护目标：secret_realm_platform_tx.h continueSessionTx 与 Kotlin
// GameEngine.continueSecretRealmExploration 会话域判定段逐位一致——
//   - 判定序：①到期守卫（spawnYear + OPEN_YEARS=5）→ ②死局防御
//     （会话不 active / 秘境不存在 / secretRealmId 不匹配 →
//     endSession(EXPLORER_END)）→ ③成员净化（isDead ∨ 不在弟子表移除；
//     空 → RESET；减少 → PURIFIED 写回）→ ④NONE
//   - 到期关闭 = closeSecretRealmByExpiry 状态段（灵石入钱包 + 背包清空 +
//     会话/秘境/AI 队伍清场 + 冷却年）+ closeDraft 草稿回传
//   - 零 RNG：全分支全分区快照差分（rng.exportStates() 前后逐位一致）
//   - 信封级：execute 通道 success data 面（canContinue/action/
//     releasedMemberIds/overflowDrafts/secretRealmClose）
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
#include "gamecore/system/inventory.h"
#include "gamecore/system/secret_realm.h"
#include "gamecore/system/secret_realm_platform_tx.h"
#include "gamecore/system/secret_realm_session.h"

namespace gamecore {
namespace {

namespace sr_platform = gamecore::system::sr_platform;
namespace sr_session = gamecore::system::sr_session;

using gamecore::state::Disciple;
using gamecore::state::Material;
using gamecore::state::SecretRealmMemberState;

class SecretRealmPlatformTxFixture : public ::testing::Test {
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
        // clock/logger 由 core 裸指针引用——fixture 成员保活（单测试单 core）
        clock_ = std::move(clock);
        logger_ = std::move(logger);
        return core;
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result = core_->execute(actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    /// 弟子入表（SoA appendDisciple——isAlive 列语义与 Kotlin assembleAll 同源）
    void addDisciple(const std::string& id, bool alive) {
        Disciple d;
        d.id = id;
        d.name = "弟子" + id;
        d.isAlive = alive;
        core_->state().disciples.appendDisciple(d);
    }

    /// 秘境现世 + 会话挂靠（id 匹配——continue 判定序的"正常"前提）
    void seedRealmAndSession(const std::string& realmId, int32_t spawnYear,
                             int32_t gameYear) {
        auto& gd = core_->state().gameData;
        gd.gameYear = gameYear;
        gd.secretRealmState.id = realmId;
        gd.secretRealmState.spawnYear = spawnYear;
        gd.secretRealmSession.secretRealmId = realmId;
    }

    void addSessionMember(const std::string& discipleId, bool dead = false) {
        SecretRealmMemberState m;
        m.discipleId = discipleId;
        m.name = "成员" + discipleId;
        m.isDead = dead;
        core_->state().gameData.secretRealmSession.members.push_back(m);
    }

    /// 背包灌入（RESET 死局重置路径的 endSession 结算断言用）
    void seedBackpack(int64_t spiritStones, const std::string& materialId) {
        auto& backpack = core_->state().gameData.secretRealmSession.backpack;
        backpack.spiritStones = spiritStones;
        Material m;
        m.id = materialId;
        m.name = "秘境材料";
        m.rarity = 1;
        m.quantity = 2;
        backpack.materials.push_back(m);
    }

    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->rng().exportStates();
    }

    std::unique_ptr<FixedClock> clock_;
    std::unique_ptr<ConsoleLogger> logger_;
    std::unique_ptr<GameCore> core_;
};

// ── ④ NONE：正常会话零变更直接继续 ────────────────────────────────

TEST_F(SecretRealmPlatformTxFixture, NonePathContinuesWithoutStateChange) {
    seedRealmAndSession("realm-1", /*spawnYear=*/1, /*gameYear=*/3);
    addDisciple("d1", true);
    addDisciple("d2", true);
    addSessionMember("d1");
    addSessionMember("d2");

    const auto before = rngSnapshot();
    const auto out = sr_platform::continueSessionTx(core_->state());

    EXPECT_TRUE(out.canContinue);
    EXPECT_STREQ(out.action, sr_platform::kContinueActionNone);
    EXPECT_TRUE(out.releasedMemberIds.empty());
    EXPECT_TRUE(out.overflowDrafts.empty());
    EXPECT_FALSE(out.closeDraft.has_value());
    // 成员零净化（size 相同不写回）
    EXPECT_EQ(core_->state().gameData.secretRealmSession.members.size(), 2u);
    // 零 RNG 全分区快照差分
    EXPECT_EQ(core_->rng().exportStates(), before);
}

// ── ① EXPIRED：到期守卫（OPEN_YEARS = 5——Kotlin 权威值）─────────

TEST_F(SecretRealmPlatformTxFixture, ExpiredGuardClosesRealmAndReturnsDraft) {
    // spawnYear=1, gameYear=6 → 满期（spawnYear + 5 <= gameYear）
    seedRealmAndSession("realm-1", /*spawnYear=*/1, /*gameYear=*/6);
    addDisciple("d1", true);
    addSessionMember("d1");
    seedBackpack(/*spiritStones=*/100, "m-1");

    const auto before = rngSnapshot();
    const auto out = sr_platform::continueSessionTx(core_->state());

    EXPECT_FALSE(out.canContinue);
    EXPECT_STREQ(out.action, sr_platform::kContinueActionExpired);
    // closeDraft：memberIds + 背包清空前快照（Kotlin 关闭邮件附件来源）
    ASSERT_TRUE(out.closeDraft.has_value());
    EXPECT_TRUE(out.closeDraft->closed);
    EXPECT_EQ(out.closeDraft->memberIds, std::vector<std::string>({"d1"}));
    EXPECT_EQ(out.closeDraft->backpack.spiritStones, 100);
    EXPECT_EQ(out.closeDraft->backpack.materials.size(), 1u);
    EXPECT_EQ(out.releasedMemberIds, std::set<std::string>({"d1"}));
    // 状态段：灵石入钱包 + 会话/秘境/AI 队伍清场 + 冷却年
    auto& gd = core_->state().gameData;
    EXPECT_EQ(gd.spiritStones, 1100);  // 初始 1000 + 100 入账
    EXPECT_TRUE(gd.secretRealmState.id.empty());
    EXPECT_TRUE(gd.secretRealmSession.members.empty());
    EXPECT_EQ(gd.secretRealmSession.backpack.spiritStones, 0);
    EXPECT_TRUE(gd.secretRealmSession.backpack.materials.empty());
    EXPECT_EQ(gd.secretRealmCooldownYear, 6);
    // 零 RNG 全分区快照差分
    EXPECT_EQ(core_->rng().exportStates(), before);
}

TEST_F(SecretRealmPlatformTxFixture, ExpiryBoundaryExactlyFiveYearsTriggers) {
    // spawnYear=2, gameYear=7（= spawnYear + OPEN_YEARS，恰好现世第 5 年整）→ 期满
    seedRealmAndSession("realm-1", /*spawnYear=*/2, /*gameYear=*/7);
    addDisciple("d1", true);
    addSessionMember("d1");

    const auto out = sr_platform::continueSessionTx(core_->state());
    EXPECT_FALSE(out.canContinue);
    EXPECT_STREQ(out.action, sr_platform::kContinueActionExpired);
}

TEST_F(SecretRealmPlatformTxFixture, BeforeExpiryDoesNotTrigger) {
    // spawnYear=1, gameYear=4（< spawnYear + 5）→ 未到期
    seedRealmAndSession("realm-1", /*spawnYear=*/1, /*gameYear=*/4);
    addDisciple("d1", true);
    addSessionMember("d1");

    const auto out = sr_platform::continueSessionTx(core_->state());
    EXPECT_TRUE(out.canContinue);
    EXPECT_STREQ(out.action, sr_platform::kContinueActionNone);
    EXPECT_FALSE(core_->state().gameData.secretRealmState.id.empty());
}

// ── ② RESET：死局防御（id 不匹配 / 秘境不存在 / 会话空挂）────────

TEST_F(SecretRealmPlatformTxFixture, RealmIdMismatchSettlesSessionAndResets) {
    seedRealmAndSession("realm-1", /*spawnYear=*/1, /*gameYear=*/3);
    core_->state().gameData.secretRealmSession.secretRealmId = "realm-old";
    addDisciple("d1", true);
    addSessionMember("d1");
    seedBackpack(/*spiritStones=*/50, "m-1");

    const auto before = rngSnapshot();
    const auto out = sr_platform::continueSessionTx(core_->state());

    EXPECT_FALSE(out.canContinue);
    EXPECT_STREQ(out.action, sr_platform::kContinueActionReset);
    // endSession(EXPLORER_END)：背包结算入仓（灵石入钱包 + 材料入仓）+
    // 释放面 = 全体成员 + 会话/秘境清场
    EXPECT_EQ(out.releasedMemberIds, std::set<std::string>({"d1"}));
    auto& gd = core_->state().gameData;
    EXPECT_EQ(gd.spiritStones, 1050);  // 初始 1000 + 50 入账
    // 仓库六类容器在 GameState 顶层（与 jade_tx_test fillWarehouse 同源）
    ASSERT_EQ(core_->state().materials.size(), 1u);
    EXPECT_EQ(core_->state().materials[0].id, "m-1");
    EXPECT_TRUE(gd.secretRealmState.id.empty());
    EXPECT_TRUE(gd.secretRealmSession.members.empty());
    EXPECT_EQ(gd.secretRealmCooldownYear, 3);
    // 零 RNG 全分区快照差分
    EXPECT_EQ(core_->rng().exportStates(), before);
}

TEST_F(SecretRealmPlatformTxFixture, RealmMissingWithActiveSessionResets) {
    // 会话 active 但秘境不存在（残留会话死局）
    core_->state().gameData.gameYear = 3;
    core_->state().gameData.secretRealmSession.secretRealmId = "realm-gone";
    addDisciple("d1", true);
    addSessionMember("d1");

    const auto out = sr_platform::continueSessionTx(core_->state());
    EXPECT_FALSE(out.canContinue);
    EXPECT_STREQ(out.action, sr_platform::kContinueActionReset);
    EXPECT_EQ(out.releasedMemberIds, std::set<std::string>({"d1"}));
    EXPECT_TRUE(core_->state().gameData.secretRealmSession.members.empty());
}

TEST_F(SecretRealmPlatformTxFixture, IdleNoSessionContinuesAsResetWithoutEnd) {
    // 会话空挂（members 空 → 会话不 active）：无 endSession 可执行 → RESET
    seedRealmAndSession("realm-1", /*spawnYear=*/1, /*gameYear=*/3);
    addDisciple("d1", true);

    const auto out = sr_platform::continueSessionTx(core_->state());
    EXPECT_FALSE(out.canContinue);
    EXPECT_STREQ(out.action, sr_platform::kContinueActionReset);
    EXPECT_TRUE(out.releasedMemberIds.empty());
}

// ── ③ PURIFIED：成员净化写回 ──────────────────────────────────────

TEST_F(SecretRealmPlatformTxFixture, PurifiesDeadAndMissingMembers) {
    seedRealmAndSession("realm-1", /*spawnYear=*/1, /*gameYear=*/3);
    addDisciple("d1", true);   // 存活留队
    addDisciple("d2", false);  // 已死亡 → 移除
    // d3 不入弟子表（已不存在）→ 移除
    addSessionMember("d1");
    addSessionMember("d2");
    addSessionMember("d3");

    const auto before = rngSnapshot();
    const auto out = sr_platform::continueSessionTx(core_->state());

    EXPECT_TRUE(out.canContinue);
    EXPECT_STREQ(out.action, sr_platform::kContinueActionPurified);
    auto& members = core_->state().gameData.secretRealmSession.members;
    ASSERT_EQ(members.size(), 1u);
    EXPECT_EQ(members[0].discipleId, "d1");
    // 零 RNG 全分区快照差分
    EXPECT_EQ(core_->rng().exportStates(), before);
}

TEST_F(SecretRealmPlatformTxFixture, AllPurifiedSettlesSessionAndResets) {
    seedRealmAndSession("realm-1", /*spawnYear=*/1, /*gameYear=*/3);
    addDisciple("d1", false);  // 唯一成员已死亡
    addSessionMember("d1");
    seedBackpack(/*spiritStones=*/10, "m-9");

    const auto out = sr_platform::continueSessionTx(core_->state());

    EXPECT_FALSE(out.canContinue);
    EXPECT_STREQ(out.action, sr_platform::kContinueActionReset);
    EXPECT_EQ(out.releasedMemberIds, std::set<std::string>({"d1"}));
    // 净化空 → endSession 结算（灵石入钱包）+ 清场
    EXPECT_EQ(core_->state().gameData.spiritStones, 1010);  // 初始 1000 + 10
    EXPECT_TRUE(core_->state().gameData.secretRealmSession.members.empty());
}

// ── 信封级：execute 通道（Kotlin 门控转发契约面）─────────────────

TEST_F(SecretRealmPlatformTxFixture, ExecuteEnvelopeNonePath) {
    seedRealmAndSession("realm-1", /*spawnYear=*/1, /*gameYear=*/3);
    addDisciple("d1", true);
    addSessionMember("d1");

    const auto r = exec(action::SECRET_REALM_CONTINUE_TX, nlohmann::json::object());
    ASSERT_EQ(r["status"], "success");
    const auto& data = r["data"];
    EXPECT_EQ(data["canContinue"], true);
    EXPECT_EQ(data["action"], "NONE");
    EXPECT_TRUE(data["releasedMemberIds"].empty());
    EXPECT_FALSE(data.contains("overflowDrafts"));
    EXPECT_FALSE(data.contains("secretRealmClose"));
}

TEST_F(SecretRealmPlatformTxFixture, ExecuteEnvelopeExpiredPathWithCloseDraft) {
    seedRealmAndSession("realm-1", /*spawnYear=*/1, /*gameYear=*/6);
    addDisciple("d1", true);
    addSessionMember("d1");
    seedBackpack(/*spiritStones=*/100, "m-1");

    const auto r = exec(action::SECRET_REALM_CONTINUE_TX, nlohmann::json::object());
    ASSERT_EQ(r["status"], "success");
    const auto& data = r["data"];
    EXPECT_EQ(data["canContinue"], false);
    EXPECT_EQ(data["action"], "EXPIRED");
    ASSERT_TRUE(data.contains("secretRealmClose"));
    EXPECT_EQ(data["secretRealmClose"]["closed"], true);
    EXPECT_EQ(data["secretRealmClose"]["slotId"], 0);
    EXPECT_EQ(data["secretRealmClose"]["memberIds"], nlohmann::json::array({"d1"}));
    // 背包快照六类协议面（Kotlin Json.decodeFromJsonElement<SecretRealmBackpack>）
    EXPECT_EQ(data["secretRealmClose"]["backpack"]["spiritStones"], 100);
    EXPECT_EQ(data["secretRealmClose"]["backpack"]["materials"].size(), 1u);
    EXPECT_EQ(data["releasedMemberIds"], nlohmann::json::array({"d1"}));
}

TEST_F(SecretRealmPlatformTxFixture, ExecuteEnvelopeResetPathWithOverflow) {
    // 背包溢出 → 溢出草稿回传（deliverOverflowDrafts 投递契约面）
    seedRealmAndSession("realm-1", /*spawnYear=*/1, /*gameYear=*/3);
    core_->state().gameData.secretRealmSession.secretRealmId = "realm-old";
    addDisciple("d1", true);
    addSessionMember("d1");
    // 仓库灌满（baseCapacity=50）→ endSession 材料入仓溢出转草稿
    for (int32_t i = 0; i < 50; ++i) {
        Material m;
        m.id = "fill-" + std::to_string(i);
        m.name = "填充材料" + std::to_string(i);
        m.rarity = 1;
        m.quantity = 1;
        core_->state().materials.push_back(m);
    }
    seedBackpack(/*spiritStones=*/0, "overflow-m");

    const auto r = exec(action::SECRET_REALM_CONTINUE_TX, nlohmann::json::object());
    ASSERT_EQ(r["status"], "success");
    const auto& data = r["data"];
    EXPECT_EQ(data["canContinue"], false);
    EXPECT_EQ(data["action"], "RESET");
    ASSERT_TRUE(data.contains("overflowDrafts"));
    ASSERT_EQ(data["overflowDrafts"].size(), 1u);
    // itemId 恒为 ""（模板 id 反查在 Kotlin wrapper 侧——inventory.h 契约）
    EXPECT_EQ(data["overflowDrafts"][0]["itemId"], "");
    EXPECT_EQ(data["overflowDrafts"][0]["itemName"], "秘境材料");
    EXPECT_EQ(data["overflowDrafts"][0]["source"], "secret_realm");
    EXPECT_EQ(data["releasedMemberIds"], nlohmann::json::array({"d1"}));
}

}  // namespace
}  // namespace gamecore
