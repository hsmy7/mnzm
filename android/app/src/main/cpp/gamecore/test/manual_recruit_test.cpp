#include <gtest/gtest.h>

#include "gamecore/game_core.h"
#include "gamecore/system/recruit_settlement.h"
#include "nlohmann/json.hpp"

// ============================================================
// manualRecruitFromList / manualRecruitAll 测试
// （Kotlin DiscipleFacadeImpl.recruitDiscipleFromList +
//   GameEngine.recruitAllFromList 等价下沉：AUTHORITATIVE 单真相源）
//
// 覆盖：成功（列表移除+入宗+计数/年报+1）/ 月度上限 / 不存在 /
// 损坏条目同事务移除 / 同人双胞胎净化 / 一键净化+上限 / GameCore 信封。
// 零 RNG 契约与 processAutoRecruit 同族（allocateAndInsert 资质补算为
// id 确定性散列；手动招募不消费任何分区）。
// ============================================================

namespace gamecore {
namespace {

using gamecore::state::Disciple;
using gamecore::system::recruit_settle::ManualRecruitReason;
using gamecore::system::recruit_settle::manualRecruitAll;
using gamecore::system::recruit_settle::manualRecruitFromList;

/// 构建已初始化 GameCore（钩子已注册），种子固定
static std::unique_ptr<GameCore> makeCore(int64_t seed) {
    auto core = std::unique_ptr<GameCore>(new GameCore(nullptr, nullptr));
    GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = seed;
    EXPECT_TRUE(core->initialize(config));
    return core;
}

/// 最小存活弟子（炼气一层；与 month_settlement_test 同语义）
static Disciple baseDisciple(const std::string& id) {
    Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.realm = 9;
    d.realmLayer = 1;
    d.isAlive = true;
    d.spiritRootType = "metal";
    d.age = 16;
    d.lifespan = 80;
    return d;
}

/// 招募候选弟子（无装备/功法——俘虏落库 no-op；资质缺省 50 触发散列补算）
static Disciple recruitCandidate(const std::string& id, const char* roots) {
    Disciple d = baseDisciple(id);
    d.name = "候选" + id;
    d.age = 16;
    d.gender = "male";
    d.spiritRootType = roots;
    d.currentHp = -1;
    d.currentMp = -1;
    return d;
}

// ── 单招：成功 ────────────────────────────────────────────────

TEST(ManualRecruit, SingleSuccessMovesDiscipleIntoStore) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 2;
    st.gameData.recruitList = {
        recruitCandidate("r1", "metal"),
        recruitCandidate("r2", "metal,fire")
    };
    st.disciples.appendDisciple(baseDisciple("11"));

    const auto result = manualRecruitFromList(st, "r1");

    EXPECT_EQ(ManualRecruitReason::kSuccess, result.reason);
    EXPECT_EQ("12", result.newId);              // max+1 = 12
    ASSERT_EQ(2u, st.disciples.size());
    const auto& d = st.disciples.materialize(1);
    EXPECT_EQ("12", d.id);
    EXPECT_EQ(14, d.recruitedMonth);            // 1*12 + 2
    // 资质 50 → 80 + floorMod(12*527+31, 21)（id 确定性散列补算）
    const int64_t roll = gamecore::system::recruit_settle::floorMod(
        12LL * 527 + 31, 21);
    EXPECT_EQ(80 + static_cast<int32_t>(roll), d.aptitude);
    // 列表仅剩未被招募的 r2
    ASSERT_EQ(1u, st.gameData.recruitList.size());
    EXPECT_EQ("r2", st.gameData.recruitList[0].id);
    EXPECT_EQ(1, st.gameData.recruitCountThisMonth);
    EXPECT_EQ(1, st.gameData.annualNewDisciples);
}

// ── 单招：月度上限 ────────────────────────────────────────────

TEST(ManualRecruit, SingleMonthlyLimitBlocks) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.recruitList = { recruitCandidate("r1", "metal") };
    st.gameData.recruitCountThisMonth = 30;    // kRecruitMonthlyLimit

    const auto result = manualRecruitFromList(st, "r1");

    EXPECT_EQ(ManualRecruitReason::kMonthlyLimit, result.reason);
    EXPECT_EQ("", result.newId);
    ASSERT_EQ(1u, st.gameData.recruitList.size());   // 列表不变
    EXPECT_EQ(0u, st.disciples.size());
    EXPECT_EQ(30, st.gameData.recruitCountThisMonth);
}

// ── 单招：不存在 ────────────────────────────────────────────

TEST(ManualRecruit, SingleNotFoundKeepsList) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.recruitList = { recruitCandidate("r1", "metal") };

    const auto result = manualRecruitFromList(st, "ghost");

    EXPECT_EQ(ManualRecruitReason::kNotFound, result.reason);
    ASSERT_EQ(1u, st.gameData.recruitList.size());
    EXPECT_EQ(0u, st.disciples.size());
}

// ── 单招：损坏条目同事务移除（purge） ──────────────────────

TEST(ManualRecruit, SingleCorruptedPurgedWithName) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    Disciple corrupt = recruitCandidate("r1", "metal");
    corrupt.name = "";                           // 损坏：空白名
    st.gameData.recruitList = {
        corrupt,
        recruitCandidate("r2", "metal")
    };

    const auto result = manualRecruitFromList(st, "r1");

    EXPECT_EQ(ManualRecruitReason::kCorrupted, result.reason);
    EXPECT_EQ("", result.name);                 // 损坏条目的 name 本身为空
    ASSERT_EQ(1u, st.gameData.recruitList.size());   // 损坏条目已移除
    EXPECT_EQ("r2", st.gameData.recruitList[0].id);
    EXPECT_EQ(0u, st.disciples.size());
}

// ── 单招：同人双胞胎净化（Kotlin filter isSamePerson 对齐） ────

TEST(ManualRecruit, SinglePurgesSamePersonTwin) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    Disciple twin = recruitCandidate("twin", "metal");
    twin.name = "候选r1";                       // 同签名（name/灵根/性别一致）
    twin.age = 17;                              // 年龄差 1（容差 2 内）→ 同人
    st.gameData.recruitList = {
        recruitCandidate("r1", "metal"),
        twin
    };

    const auto result = manualRecruitFromList(st, "r1");

    EXPECT_EQ(ManualRecruitReason::kSuccess, result.reason);
    EXPECT_EQ("1", result.newId);               // 弟子表空 → max=0 → 1
    EXPECT_TRUE(st.gameData.recruitList.empty());   // 本体 + 同人双胞胎全部移除
    EXPECT_EQ(1, st.gameData.recruitCountThisMonth);
}

// ── 一键：成功 + 净化（损坏/去重/跨表残留） ──────────────────

TEST(ManualRecruit, AllSuccessWithSanitize) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 5;
    Disciple dupContent = recruitCandidate("r3", "metal,fire");
    dupContent.name = "候选r2";                 // 与 r2 同内容（全字段）→ 去重
    st.gameData.recruitList = {
        recruitCandidate("r1", "metal"),        // 正常 → 招募
        recruitCandidate("r2", "metal,fire"),   // 正常 → 招募
        recruitCandidate("r1", "metal"),        // 同 id 重复（保留首个）→ 净化
        dupContent,                             // 同内容（去重，丢弃 id）→ 净化
    };
    st.gameData.recruitCountThisMonth = 5;
    st.disciples.appendDisciple(baseDisciple("9"));

    ManualRecruitReason reason = ManualRecruitReason::kSuccess;
    const int32_t count = manualRecruitAll(st, reason);

    EXPECT_EQ(ManualRecruitReason::kSuccess, reason);
    EXPECT_EQ(2, count);
    // 净化后无候选残留：r1/r2 已入宗，其余重复被净化
    EXPECT_TRUE(st.gameData.recruitList.empty());
    EXPECT_EQ(7, st.gameData.recruitCountThisMonth);   // 5 + 2
    EXPECT_EQ(2, st.gameData.annualNewDisciples);
    ASSERT_EQ(3u, st.disciples.size());
    EXPECT_EQ("10", st.disciples.materialize(1).id);
    EXPECT_EQ("11", st.disciples.materialize(2).id);
}

TEST(ManualRecruit, AllRemovesInSectResidual) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    // 残留条目：与宗内弟子同签名 + 年龄容差 2 内（招募成功但未从列表移除）
    Disciple inSect = baseDisciple("7");
    inSect.name = "候选x";
    inSect.age = 20;
    inSect.gender = "female";
    st.disciples.appendDisciple(inSect);
    Disciple residual = baseDisciple("r9");
    residual.name = "候选x";
    residual.age = 21;                          // 差 1（容差 2 内）
    residual.gender = "female";
    st.gameData.recruitList = { residual, recruitCandidate("r2", "metal") };

    ManualRecruitReason reason = ManualRecruitReason::kSuccess;
    const int32_t count = manualRecruitAll(st, reason);

    EXPECT_EQ(ManualRecruitReason::kSuccess, reason);
    EXPECT_EQ(1, count);
    // 残留净化 + r2 入宗 → 列表空
    EXPECT_TRUE(st.gameData.recruitList.empty());
    EXPECT_EQ("8", st.disciples.materialize(1).id);   // max=7 → 8
}

// ── 一键：月度上限 ──────────────────────────────────────────

TEST(ManualRecruit, AllMonthlyLimitBlocks) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.recruitList = { recruitCandidate("r1", "metal") };
    st.gameData.recruitCountThisMonth = 30;

    ManualRecruitReason reason = ManualRecruitReason::kSuccess;
    const int32_t count = manualRecruitAll(st, reason);

    EXPECT_EQ(ManualRecruitReason::kMonthlyLimit, reason);
    EXPECT_EQ(0, count);
    ASSERT_EQ(1u, st.gameData.recruitList.size());   // 列表不变
    EXPECT_EQ(0u, st.disciples.size());
}

// ── 一键：空列表（净化后无候选）无 reason 失败 ──────────────

TEST(ManualRecruit, AllEmptyListIsNoOpSuccess) {
    auto core = makeCore(20260901);
    auto& st = core->state();

    ManualRecruitReason reason = ManualRecruitReason::kSuccess;
    const int32_t count = manualRecruitAll(st, reason);

    EXPECT_EQ(ManualRecruitReason::kSuccess, reason);
    EXPECT_EQ(0, count);
}

// ── 一键：上限截断（超过剩余配额只招募部分） ────────────────

TEST(ManualRecruit, AllRespectsRemainingQuota) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.recruitList = {
        recruitCandidate("r1", "metal"),
        recruitCandidate("r2", "metal")
    };
    st.gameData.recruitCountThisMonth = 29;    // 剩余 1 配额

    ManualRecruitReason reason = ManualRecruitReason::kSuccess;
    const int32_t count = manualRecruitAll(st, reason);

    EXPECT_EQ(ManualRecruitReason::kSuccess, reason);
    EXPECT_EQ(1, count);
    ASSERT_EQ(1u, st.gameData.recruitList.size());
    EXPECT_EQ("r2", st.gameData.recruitList[0].id);    // 超额保留
    EXPECT_EQ(30, st.gameData.recruitCountThisMonth);
}

// ── GameCore 层信封 ─────────────────────────────────────────

TEST(ManualRecruit, GameCoreEnvelopeSuccess) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 3;
    st.gameData.recruitList = { recruitCandidate("r1", "metal") };

    const nlohmann::json j = nlohmann::json::parse(core->manualRecruitFromList("r1"));

    EXPECT_TRUE(j.at("ok").get<bool>());
    EXPECT_EQ("1", j.at("newId").get<std::string>());
    EXPECT_EQ(16, j.at("age").get<int32_t>());
    EXPECT_EQ("候选r1", j.at("name").get<std::string>());
    EXPECT_EQ("SUCCESS", j.at("reason").get<std::string>());
    EXPECT_EQ(27, st.disciples.materialize(0).recruitedMonth);   // 2*12+3
}

TEST(ManualRecruit, GameCoreEnvelopeMonthlyLimit) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.recruitList = { recruitCandidate("r1", "metal") };
    st.gameData.recruitCountThisMonth = 30;

    const nlohmann::json j = nlohmann::json::parse(core->manualRecruitFromList("r1"));

    EXPECT_FALSE(j.at("ok").get<bool>());
    EXPECT_EQ("", j.at("newId").get<std::string>());
    EXPECT_EQ("MONTHLY_LIMIT", j.at("reason").get<std::string>());
}

TEST(ManualRecruit, GameCoreEnvelopeUnknownWhenNotInitialized) {
    GameCore core(nullptr, nullptr);
    const nlohmann::json j =
        nlohmann::json::parse(core.manualRecruitFromList("r1"));
    EXPECT_FALSE(j.at("ok").get<bool>());
    EXPECT_EQ("UNKNOWN", j.at("reason").get<std::string>());
}

TEST(ManualRecruit, GameCoreEnvelopeRecruitAll) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.recruitList = {
        recruitCandidate("r1", "metal"),
        recruitCandidate("r2", "metal,fire")
    };
    st.gameData.recruitCountThisMonth = 20;

    const nlohmann::json j = nlohmann::json::parse(core->manualRecruitAll());

    EXPECT_TRUE(j.at("ok").get<bool>());
    EXPECT_EQ(2, j.at("count").get<int32_t>());
    EXPECT_EQ("SUCCESS", j.at("reason").get<std::string>());
    EXPECT_EQ(22, st.gameData.recruitCountThisMonth);
}

// ── 零 RNG 契约：手动招募不消费任何分区 ──────────────────────

TEST(ManualRecruit, NoRngConsumedOnManualRecruit) {
    auto core = makeCore(20260901);
    auto& st = core->state();
    st.gameData.recruitList = { recruitCandidate("r1", "metal") };
    st.disciples.appendDisciple(baseDisciple("11"));

    const auto sysBefore = core->rng().exportStates();
    const auto result = manualRecruitFromList(st, "r1");
    const auto sysAfter = core->rng().exportStates();

    EXPECT_EQ(ManualRecruitReason::kSuccess, result.reason);
    EXPECT_EQ(sysBefore, sysAfter);
}

}  // namespace
}  // namespace gamecore
