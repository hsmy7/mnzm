// ============================================================
// chat_effect_tx_test — 弟子交谈效果事务守护（W4-D 续批·弟子通道收口，
// DISCIPLE_CHAT_EFFECT_TX=1860）
//
// 守护目标：chat_effect_tx.h 与 Kotlin 写者（DiscipleDelegate
// applyConversationEffects → updateDisciple lambda）语义逐位一致——
//   - 成功路径：修为 max(0,+x)、道德/忠诚/悟性 (原值+增量) clamp [1,100]、
//     statusData["lastChatYear"] 冷却标记
//   - 边界 clamp：上界 100 / 下界 1 / 修为下限 0.0
//   - 弟子不存在 = 成功无操作（found=false，零写入——Kotlin return@update）
//   - 零增量仍写冷却标记（Kotlin lambda 恒写 statusData）
//   - 已故弟子行照常应用（Kotlin updateDisciple 无存活检查同语义）
//   - 抽取序零扰动（红线 1：事务零 RNG，rngStates 快照差分恒零）
//   - 端口分派形状：execute(1860) 走 W4-D 端口返回 success 信封
// ============================================================

#include "gtest/gtest.h"

#include <map>
#include <memory>
#include <string>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/system/chat_effect_tx.h"

namespace gamecore {
namespace {

using gamecore::state::Disciple;

class ChatEffectTxFixture : public ::testing::Test {
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

    /// 挂一个弟子并设定交谈相关列初值，返回行号
    std::size_t addDisciple(const std::string& id,
                            double cultivation,
                            int32_t morality,
                            int32_t loyalty,
                            int32_t intelligence) {
        Disciple d;
        d.id = id;
        d.name = "弟子" + id;
        d.cultivation = cultivation;
        d.morality = morality;
        d.loyalty = loyalty;
        d.intelligence = intelligence;
        d.isAlive = true;
        d.status = "IDLE";
        core_->state().disciples.appendDisciple(d);
        return *core_->state().disciples.rowOf(id);
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

TEST_F(ChatEffectTxFixture, AppliesDeltasAndCooldownMarkVerbatim) {
    const std::size_t row = addDisciple("1", /*cult*/ 100.5, /*mor*/ 50, /*loy*/ 60, /*int*/ 70);
    const auto r = system::chat_tx::applyChatEffectTx(
        core_->state(), 1, /*year*/ 3,
        /*cultivationDelta*/ 0.25, /*moralityDelta*/ 5, /*loyaltyDelta*/ 3,
        /*intelligenceDelta*/ 2);
    ASSERT_TRUE(r.found);
    auto& store = core_->state().disciples;
    EXPECT_DOUBLE_EQ(store.cultivations[row], 100.75);
    EXPECT_EQ(store.moralities[row], 55);
    EXPECT_EQ(store.loyalties[row], 63);
    EXPECT_EQ(store.intelligences[row], 72);
    ASSERT_NE(store.statusData[row].find("lastChatYear"), store.statusData[row].end());
    EXPECT_EQ(store.statusData[row].at("lastChatYear"), "3");
}

TEST_F(ChatEffectTxFixture, ClampsSkillsToRangeAndCultivationToZeroFloor) {
    const std::size_t row = addDisciple("2", /*cult*/ 0.5, /*mor*/ 98, /*loy*/ 3, /*int*/ 1);
    system::chat_tx::applyChatEffectTx(
        core_->state(), 2, /*year*/ 5,
        /*cultivationDelta*/ -1.0, /*moralityDelta*/ 10, /*loyaltyDelta*/ -5,
        /*intelligenceDelta*/ -10);
    auto& store = core_->state().disciples;
    // Kotlin maxOf(0.0, 0.5 + (-1.0)) = 0.0
    EXPECT_DOUBLE_EQ(store.cultivations[row], 0.0);
    // Kotlin coerceIn(1, 100)：98+10 → 100；3-5 → 1；1-10 → 1
    EXPECT_EQ(store.moralities[row], 100);
    EXPECT_EQ(store.loyalties[row], 1);
    EXPECT_EQ(store.intelligences[row], 1);
}

TEST_F(ChatEffectTxFixture, MissingDiscipleIsSuccessfulNoOpWithZeroWrites) {
    addDisciple("3", 10.0, 50, 50, 50);
    auto& store = core_->state().disciples;
    const double cultBefore = store.cultivations[*store.rowOf("3")];
    const auto r = system::chat_tx::applyChatEffectTx(
        core_->state(), /*missing*/ 999, 3, 1.0, 5, 5, 5);
    // Kotlin `id !in discipleTables.ids → return@update` 同语义：成功无操作
    EXPECT_FALSE(r.found);
    EXPECT_DOUBLE_EQ(store.cultivations[*store.rowOf("3")], cultBefore);
    EXPECT_TRUE(store.statusData[*store.rowOf("3")].empty());
}

TEST_F(ChatEffectTxFixture, ZeroDeltasStillWriteCooldownMark) {
    const std::size_t row = addDisciple("4", 10.0, 50, 50, 50);
    system::chat_tx::applyChatEffectTx(
        core_->state(), 4, /*year*/ 7, 0.0, 0, 0, 0);
    // Kotlin lambda 恒写 statusData（与增量是否为零无关）
    ASSERT_NE(core_->state().disciples.statusData[row].find("lastChatYear"),
              core_->state().disciples.statusData[row].end());
    EXPECT_EQ(core_->state().disciples.statusData[row].at("lastChatYear"), "7");
    EXPECT_DOUBLE_EQ(core_->state().disciples.cultivations[row], 10.0);
}

TEST_F(ChatEffectTxFixture, DeadDiscipleRowIsAppliedLikeKotlinFallback) {
    const std::size_t row = addDisciple("5", 10.0, 50, 50, 50);
    core_->state().disciples.isAlive[row] = 0;
    // Kotlin updateDisciple 无存活检查——同语义照常应用
    const auto r = system::chat_tx::applyChatEffectTx(
        core_->state(), 5, 4, 0.1, 1, 1, 1);
    EXPECT_TRUE(r.found);
    EXPECT_DOUBLE_EQ(core_->state().disciples.cultivations[row], 10.1);
}

TEST_F(ChatEffectTxFixture, TransactionConsumesNoRngExtraction) {
    addDisciple("6", 10.0, 50, 50, 50);
    const auto before = rngSnapshot();
    system::chat_tx::applyChatEffectTx(core_->state(), 6, 3, 0.2, 2, 2, 2);
    // 红线 1：事务零抽取——全分区 rngStates 快照差分恒零
    EXPECT_EQ(rngSnapshot(), before);
}

TEST_F(ChatEffectTxFixture, DispatchReturnsSuccessEnvelopeViaW4DPort) {
    addDisciple("7", 10.0, 50, 50, 50);
    const nlohmann::json resp = exec(action::DISCIPLE_CHAT_EFFECT_TX, {
        {"discipleId", 7}, {"currentYear", 3},
        {"cultivationDelta", 0.25}, {"moralityDelta", 5},
        {"loyaltyDelta", 3}, {"intelligenceDelta", 2},
    });
    EXPECT_EQ(resp["status"], "success");
    EXPECT_EQ(resp["data"]["applied"], true);
    EXPECT_EQ(resp["data"]["found"], true);
    // 缺参 → INVALID_PARAMS 失败信封（Kotlin 回退臂接管）
    const nlohmann::json bad = exec(action::DISCIPLE_CHAT_EFFECT_TX, {{"discipleId", 7}});
    EXPECT_EQ(bad["status"], "failure");
    EXPECT_EQ(bad["code"], "INVALID_PARAMS");
}

}  // namespace
}  // namespace gamecore
