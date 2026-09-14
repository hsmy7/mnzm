// ============================================================
// secret_realm_session_test.cpp — 秘境交互会话域黄金用例
//
// 守护目标：固定种子 + 固定状态 → sr_session::startSession / chooseOption /
// endSession / yearlySpawn 逐域字段值 + RNG 消费（snapshot 差分）符合手算期望。
// Kotlin 语义权威 = SecretRealmService 交互会话域（端到端对拍由真机
// 对拍框架覆盖——AI 侧依赖平台态的同族口径）。
// ============================================================
#include <gtest/gtest.h>

#include <map>
#include <set>
#include <string>
#include <vector>

#include "gamecore/game_core.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/inventory.h"
#include "gamecore/system/secret_realm.h"
#include "gamecore/system/secret_realm_session.h"

namespace {

using gamecore::rng::RngManager;
using gamecore::rng::RngPartition;
using gamecore::state::Disciple;
using gamecore::state::GameState;
using gamecore::state::SecretRealmState;

/// 空世界 GameState（无宗门/无事件）
GameState emptyState(int32_t year = 1, int32_t month = 1) {
    GameState st;
    st.gameData.gameYear = year;
    st.gameData.gameMonth = month;
    return st;
}

/// 基础弟子（炼气 realm=9 底层；成年）
Disciple baseDisciple(const std::string& id) {
    Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.realm = 9;
    d.realmLayer = 1;
    d.isAlive = true;
    d.spiritRootType = "metal";
    d.age = 20;
    d.lifespan = 80;
    d.portraitRes = "p" + id;
    return d;
}

/// 已现世秘境 + 4 名存活弟子的状态
GameState spawnReadyState() {
    GameState st = emptyState();
    st.gameData.secretRealmState = SecretRealmState{};
    st.gameData.secretRealmState.id = "realm-1";
    st.gameData.secretRealmState.spawnYear = 1;
    st.gameData.secretRealmState.spawnMonth = 1;
    for (const char* id : {"1", "2", "3", "4"}) {
        st.disciples.appendDisciple(baseDisciple(id));
    }
    return st;
}

const std::vector<std::string> kTeam = {"1", "2", "3", "4"};

// ── startSession 校验链 ────────────────────────────────────────

TEST(SecretRealmSessionTest, StartRejectsMissingRealm) {
    auto st = emptyState();
    RngManager rng;
    rng.initSystemSeed(42);
    const auto r = gamecore::system::sr_session::startSession(st, kTeam, rng);
    EXPECT_FALSE(r.ok);
    EXPECT_EQ("NotFound", r.errorType);
    EXPECT_EQ("远古秘境已消失", r.message);
}

TEST(SecretRealmSessionTest, StartRejectsActiveSessionAndBadTeam) {
    auto st = spawnReadyState();
    RngManager rng;
    rng.initSystemSeed(42);
    // 首次出发成功 → 会话激活
    EXPECT_TRUE(gamecore::system::sr_session::startSession(st, kTeam, rng).ok);
    // 已激活
    const auto dup = gamecore::system::sr_session::startSession(st, kTeam, rng);
    EXPECT_FALSE(dup.ok);
    EXPECT_EQ("InvalidState", dup.errorType);
    // 会话清场后再试人数/重复 id
    st.gameData.secretRealmSession =
        gamecore::state::SecretRealmExplorationSession{};
    const auto bad1 =
        gamecore::system::sr_session::startSession(st, {"1", "2", "3"}, rng);
    EXPECT_EQ("InvalidInput", bad1.errorType);
    EXPECT_EQ("需要 4 名不同的弟子组成探索队伍", bad1.message);
    const auto bad2 =
        gamecore::system::sr_session::startSession(st, {"1", "1", "2", "3"}, rng);
    EXPECT_EQ("InvalidInput", bad2.errorType);
    // 弟子不存在
    const auto bad3 =
        gamecore::system::sr_session::startSession(st, {"1", "2", "3", "99"}, rng);
    EXPECT_EQ("NotFound", bad3.errorType);
    EXPECT_EQ("弟子不存在", bad3.message);
    // 死亡弟子
    st.disciples.appendDisciple(baseDisciple("5"));
    st.disciples.isAlive[4] = 0;
    const auto bad4 =
        gamecore::system::sr_session::startSession(st, {"1", "2", "3", "5"}, rng);
    EXPECT_EQ("InvalidInput", bad4.errorType);
    EXPECT_EQ("弟子「弟子5」已死亡", bad4.message);
}

TEST(SecretRealmSessionTest, StartWritesSessionAndConsumesSecretRealmRng) {
    auto st = spawnReadyState();
    RngManager rng;
    rng.initSystemSeed(42);
    const int64_t srBefore = rng.getRng(RngPartition::kSecretRealm).snapshot();
    const int64_t battleBefore = rng.getRng(RngPartition::kBattle).snapshot();
    const auto r = gamecore::system::sr_session::startSession(st, kTeam, rng);
    EXPECT_TRUE(r.ok);
    // 初始妖兽事件（SECRET_REALM 分区内部消费；BATTLE 零消费）
    EXPECT_NE(rng.getRng(RngPartition::kSecretRealm).snapshot(), srBefore);
    EXPECT_EQ(rng.getRng(RngPartition::kBattle).snapshot(), battleBefore);
    const auto& session = st.gameData.secretRealmSession;
    EXPECT_EQ("realm-1", session.secretRealmId);
    EXPECT_EQ(4u, session.members.size());
    EXPECT_EQ("1", session.members[0].discipleId);
    EXPECT_EQ("弟子1", session.members[0].name);
    EXPECT_EQ(20, session.stamina);   // STAMINA_MAX
    EXPECT_TRUE(session.currentEvent.has_value());
    EXPECT_EQ(-1, session.currentEvent->chosenOptionIndex);
    EXPECT_EQ(1, session.startYear);
    EXPECT_EQ(1, session.startMonth);
}

// ── chooseOption 校验链 ────────────────────────────────────────

TEST(SecretRealmSessionTest, ChooseValidationChain) {
    auto st = spawnReadyState();
    RngManager rng;
    rng.initSystemSeed(42);
    // 无会话
    auto out = gamecore::system::sr_session::chooseOption(st, 1, rng);
    EXPECT_FALSE(out.ok);
    EXPECT_EQ("探索会话不存在", out.errorText);
    // 正常出发 → 事件在
    ASSERT_TRUE(gamecore::system::sr_session::startSession(st, kTeam, rng).ok);
    // 非法选项
    out = gamecore::system::sr_session::chooseOption(st, 9, rng);
    EXPECT_EQ("无效的选项", out.errorText);
    // 重复选择同一事件
    st.gameData.secretRealmSession.currentEvent->chosenOptionIndex = 0;
    out = gamecore::system::sr_session::chooseOption(st, 1, rng);
    EXPECT_EQ("事件已处理，请勿重复选择", out.errorText);
    // 体力耗尽（篡改档防御 M1）
    st.gameData.secretRealmSession.currentEvent->chosenOptionIndex = -1;
    st.gameData.secretRealmSession.stamina = 0;
    out = gamecore::system::sr_session::chooseOption(st, 1, rng);
    EXPECT_EQ("体力已耗尽，探索结束", out.errorText);
}

// ── 妖兽战斗：发起战斗分支（RNG 审计 + 会话推进） ──────────────

TEST(SecretRealmSessionTest, ChooseFightBeastAdvancesSessionAndConsumesRng) {
    auto st = spawnReadyState();
    RngManager rng;
    rng.initSystemSeed(20260906);
    ASSERT_TRUE(gamecore::system::sr_session::startSession(st, kTeam, rng).ok);
    ASSERT_TRUE(st.gameData.secretRealmSession.currentEvent.has_value());
    ASSERT_EQ("BEAST_ENCOUNTER",
              st.gameData.secretRealmSession.currentEvent->eventType);
    const int32_t staminaBefore = st.gameData.secretRealmSession.stamina;
    const int64_t srBefore = rng.getRng(RngPartition::kSecretRealm).snapshot();
    const int64_t battleBefore = rng.getRng(RngPartition::kBattle).snapshot();

    const auto out = gamecore::system::sr_session::chooseOption(st, 1, rng);
    EXPECT_TRUE(out.ok);
    EXPECT_TRUE(out.resolution.enteredCombat);
    // BATTLE 分区消费（战斗执行）；SECRET_REALM 分区消费（preGen 属性 + loot）
    EXPECT_NE(rng.getRng(RngPartition::kBattle).snapshot(), battleBefore);
    EXPECT_NE(rng.getRng(RngPartition::kSecretRealm).snapshot(), srBefore);
    // 体力 -1 + 事件推进（战斗 → 方向事件）+ 历史记录
    EXPECT_EQ(staminaBefore - 1, st.gameData.secretRealmSession.stamina);
    EXPECT_TRUE(st.gameData.secretRealmSession.currentEvent.has_value());
    EXPECT_EQ("DIRECTION_CHOICE",
              st.gameData.secretRealmSession.currentEvent->eventType);
    ASSERT_EQ(1u, st.gameData.secretRealmSession.eventHistory.size());
    EXPECT_EQ(1, st.gameData.secretRealmSession.eventHistory[0].chosenOptionIndex);
    EXPECT_FALSE(out.resolution.resultText.empty());
}

TEST(SecretRealmSessionTest, ChooseFleeSuccessAvoidsBattle) {
    // 扫描种子：存在"远离成功"（首抽 >= FLEE_DETECT_CHANCE=0.30，无战斗）
    // 的种子——语义锁定：不进入战斗 + 固定文案 + 推进方向事件。
    for (int64_t seed = 1; seed < 5000; ++seed) {
        auto st = spawnReadyState();
        RngManager rng;
        rng.initSystemSeed(seed);
        ASSERT_TRUE(gamecore::system::sr_session::startSession(st, kTeam, rng).ok);
        const auto out = gamecore::system::sr_session::chooseOption(st, 0, rng);
        if (out.resolution.enteredCombat) continue;   // 被察觉种子 → 换下一个
        EXPECT_TRUE(out.ok);
        EXPECT_EQ("你方悄然绕行，成功避开了妖兽的注意", out.resolution.resultText);
        EXPECT_EQ("DIRECTION_CHOICE",
                  st.gameData.secretRealmSession.currentEvent->eventType);
        return;
    }
    FAIL() << "no flee-success seed found";
}

// ── 休整 + 方向 + 遗迹 ─────────────────────────────────────────

TEST(SecretRealmSessionTest, RestAreaRecoversFortyPercent) {
    auto st = spawnReadyState();
    RngManager rng;
    rng.initSystemSeed(42);
    ASSERT_TRUE(gamecore::system::sr_session::startSession(st, kTeam, rng).ok);
    // 强制事件为空地（休整），成员 1 半血
    auto& session = st.gameData.secretRealmSession;
    session.currentEvent = gamecore::state::SecretRealmEventRecord{};
    session.currentEvent->eventType = "REST_AREA";
    session.currentEvent->options = {{"休整", "", 1}, {"继续", "", 1}};
    const auto rowOpt = st.disciples.rowOf("1");
    ASSERT_TRUE(rowOpt.has_value());
    st.disciples.currentHps[*rowOpt] = 50;
    // 战斗口径 maxHp 预置（写回维护）→ 休整恢复 40%
    session.members[0].maxHp = 100;
    session.members[0].currentHp = 50;

    const auto out = gamecore::system::sr_session::chooseOption(st, 0, rng);
    EXPECT_TRUE(out.ok);
    EXPECT_EQ("你方原地休整，全队恢复生命状态", out.resolution.resultText);
    EXPECT_EQ(90, out.resolution.members[0].currentHp);   // 50 + 100×0.4
    EXPECT_EQ("DIRECTION_CHOICE", session.currentEvent->eventType);
}

TEST(SecretRealmSessionTest, DirectionChoiceRollsNextEvent) {
    auto st = spawnReadyState();
    RngManager rng;
    rng.initSystemSeed(7);
    ASSERT_TRUE(gamecore::system::sr_session::startSession(st, kTeam, rng).ok);
    auto& session = st.gameData.secretRealmSession;
    session.currentEvent = gamecore::state::SecretRealmEventRecord{};
    session.currentEvent->eventType = "DIRECTION_CHOICE";
    session.currentEvent->options = {{"左", "", 1}, {"中", "", 1}, {"右", "", 1}};
    const int64_t srBefore = rng.getRng(RngPartition::kSecretRealm).snapshot();
    const auto out = gamecore::system::sr_session::chooseOption(st, 1, rng);
    EXPECT_TRUE(out.ok);
    EXPECT_EQ("你方沿中路继续前行", out.resolution.resultText);
    // 方向选择时消费 1×nextDouble（rollNextEvent 四分段）
    EXPECT_NE(rng.getRng(RngPartition::kSecretRealm).snapshot(), srBefore);
    EXPECT_TRUE(session.currentEvent.has_value());
    EXPECT_NE("DIRECTION_CHOICE", session.currentEvent->eventType);   // 真实事件
}

// ── 体力耗尽自动结束（背包结算入仓 + 秘境清场） ────────────────

TEST(SecretRealmSessionTest, StaminaExhaustionEndsSessionAndSettlesBackpack) {
    auto st = spawnReadyState();
    RngManager rng;
    rng.initSystemSeed(11);
    ASSERT_TRUE(gamecore::system::sr_session::startSession(st, kTeam, rng).ok);
    auto& session = st.gameData.secretRealmSession;
    // 背包战利品 + 体力 1（选项消耗 1 → 触发耗尽结束）
    session.backpack.spiritStones = 1234;
    gamecore::state::Material m;
    m.id = "m1";
    m.name = "妖兽皮";
    m.rarity = 2;
    m.quantity = 3;
    session.backpack.materials.push_back(m);
    session.currentEvent = gamecore::state::SecretRealmEventRecord{};
    session.currentEvent->eventType = "DIRECTION_CHOICE";
    session.currentEvent->options = {{"左", "", 1}, {"中", "", 1}, {"右", "", 1}};
    session.stamina = 1;

    const auto out = gamecore::system::sr_session::chooseOption(st, 1, rng);
    EXPECT_TRUE(out.ok);
    EXPECT_TRUE(out.sessionEnded);
    EXPECT_EQ("体力耗尽，被传送出秘境", out.message);
    // gate 释放面 = 全员
    EXPECT_EQ(4u, out.releasedMemberIds.size());
    // 背包结算入仓（灵石 LOW + 材料）——断言增量（开档默认 LOW 余额 1000）
    EXPECT_EQ(1000 + 1234, gamecore::system::spiritStoneCount(
                        st.gameData, gamecore::system::SpiritStoneGrade::LOW));
    bool materialFound = false;
    for (const auto& it : st.materials) {
        if (it.name == "妖兽皮") materialFound = true;
    }
    EXPECT_TRUE(materialFound);
    // 秘境清场 + 冷却锚定
    EXPECT_TRUE(st.gameData.secretRealmSession.members.empty());
    EXPECT_TRUE(st.gameData.secretRealmState.id.empty());
    EXPECT_EQ(1, st.gameData.secretRealmCooldownYear);
}

// ── AI 遭遇：避让零 RNG / 交战标记 ─────────────────────────────

TEST(SecretRealmSessionTest, AiEncounterAvoidConsumesNoRng) {
    auto st = spawnReadyState();
    RngManager rng;
    rng.initSystemSeed(21);
    ASSERT_TRUE(gamecore::system::sr_session::startSession(st, kTeam, rng).ok);
    auto& session = st.gameData.secretRealmSession;
    session.currentEvent = gamecore::state::SecretRealmEventRecord{};
    session.currentEvent->eventType = "AI_SECT_ENCOUNTER";
    session.currentEvent->params.aiSectName = "青云宗";
    session.currentEvent->options = {{"左", "", 1}, {"战", "", 1}, {"右", "", 1}};
    const int64_t srBefore = rng.getRng(RngPartition::kSecretRealm).snapshot();
    const auto out = gamecore::system::sr_session::chooseOption(st, 2, rng);
    EXPECT_TRUE(out.ok);
    EXPECT_EQ("你方悄然向右避让，与青云宗的探索队伍擦肩而过",
              out.resolution.resultText);
    EXPECT_EQ(rng.getRng(RngPartition::kSecretRealm).snapshot(), srBefore);   // 零消费
}

TEST(SecretRealmSessionTest, AiEncounterFightWithEmptySectSkipsBattle) {
    auto st = spawnReadyState();
    RngManager rng;
    rng.initSystemSeed(31);
    ASSERT_TRUE(gamecore::system::sr_session::startSession(st, kTeam, rng).ok);
    auto& session = st.gameData.secretRealmSession;
    session.currentEvent = gamecore::state::SecretRealmEventRecord{};
    session.currentEvent->eventType = "AI_SECT_ENCOUNTER";
    session.currentEvent->params.aiSectId = "sectA";
    session.currentEvent->params.aiSectName = "青云宗";
    session.currentEvent->options = {{"左", "", 1}, {"战", "", 1}, {"右", "", 1}};
    // 对方宗门不在 aiSectDisciples → 无力应战直通（零 BATTLE 消费）
    const int64_t battleBefore = rng.getRng(RngPartition::kBattle).snapshot();
    const auto out = gamecore::system::sr_session::chooseOption(st, 1, rng);
    EXPECT_TRUE(out.ok);
    // Kotlin toResolution 恒置 enteredCombat=true（无战斗以 combatLog==null 区分——
    // C++ 对应 hasBattle=false）
    EXPECT_TRUE(out.resolution.enteredCombat);
    ASSERT_TRUE(out.resolution.battle.has_value());
    EXPECT_FALSE(out.resolution.battle->hasBattle);
    EXPECT_EQ("对方的探索队伍已无力应战，你方绕过继续前行",
              out.resolution.resultText);
    EXPECT_EQ(rng.getRng(RngPartition::kBattle).snapshot(), battleBefore);
}

// ── endSession 幂等 ───────────────────────────────────────────

TEST(SecretRealmSessionTest, EndSessionIdempotentOnEmptyState) {
    auto st = emptyState();
    gamecore::system::OverflowMailCollector mail;
    const auto released =
        gamecore::system::sr_session::endSession(
            st, gamecore::system::sr_session::kEndExplorerEnd, mail);
    EXPECT_TRUE(released.empty());
}

}  // namespace
