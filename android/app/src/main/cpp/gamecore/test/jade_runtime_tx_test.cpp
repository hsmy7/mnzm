// ============================================================
// jade_runtime_tx_test — 玉符运行时事务守护（W4-B/B2，w3-04，1766–1769）
//
// 守护目标：jade_tx.h 事务 5–8 与 Kotlin JadeSymbolService 语义逐位一致——
//   - settleJadeGrantsTx（1766）：grants<=0 零写入；整除发放保留余量；
//     headroom 钳制；拿满冻结 accum=0；非法参数零写入
//   - jadeDayResetTx（1767）：首锚只锚定；真跨天归零计数与累计；
//     同一天/墙钟回拨零写入；**同一 todayMidnight 重复调用幂等**（同 tick
//     幂等红线，ADR 盲区 3 兜底）
//   - jadeCheckpointTx（1768）：四字段绝对值覆盖写（拿满冻结等价形）
//   - grantJadeFromAdTx（1769）：绝对值写 jadeSymbols、不动 todayCount
//   - 零 RNG：四事务全族不动 rngStates（签名级：API 不收 RngManager）
//   - 双运行全状态 JSON 逐位一致
//   - 信封级：execute 通道（dispatchW4B 端口）success data 面 + failure 信封
// ============================================================

#include "gtest/gtest.h"

#include <map>
#include <memory>
#include <string>
#include <utility>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/system/jade_tx.h"

namespace gamecore {
namespace {

namespace jade_tx = gamecore::system::jade_tx;

class JadeRuntimeTxFixture : public ::testing::Test {
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

    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    std::vector<std::unique_ptr<FixedClock>> clocks_;
    std::vector<std::unique_ptr<ConsoleLogger>> loggers_;
    std::unique_ptr<GameCore> core_;
};

// ── 事务 5：settleJadeGrantsTx（1766）────────────────────────────────────

TEST_F(JadeRuntimeTxFixture, SettleGrantsBelowIntervalIsZeroWriteEcho) {
    auto& gd = core_->state().gameData;
    gd.jadeSymbols = 7;
    gd.jadeSymbolsToday = 3;
    gd.jadeAccumMs = 599'999;  // 不足 1 个周期

    const auto r = jade_tx::settleJadeGrantsTx(core_->state(), 7, 3, 599'999);
    ASSERT_TRUE(r.base.ok);
    EXPECT_FALSE(r.frozen);
    // 零写入：状态保持原值
    EXPECT_EQ(gd.jadeSymbols, 7);
    EXPECT_EQ(gd.jadeSymbolsToday, 3);
    EXPECT_EQ(gd.jadeAccumMs, 599'999);
    // 回执回声
    EXPECT_EQ(r.total, 7);
    EXPECT_EQ(r.today, 3);
    EXPECT_EQ(r.accumMs, 599'999);
}

TEST_F(JadeRuntimeTxFixture, SettleGrantsIntervalsKeepRemainder) {
    const int64_t interval = jade_tx::kJadeIntervalMs;
    // 2.5 个周期 → 发 2 枚、保留半个周期余量
    const auto r = jade_tx::settleJadeGrantsTx(core_->state(), 10, 4,
                                               interval * 2 + interval / 2);
    ASSERT_TRUE(r.base.ok);
    EXPECT_FALSE(r.frozen);
    auto& gd = core_->state().gameData;
    EXPECT_EQ(gd.jadeSymbols, 12);
    EXPECT_EQ(gd.jadeSymbolsToday, 6);
    EXPECT_EQ(gd.jadeAccumMs, interval / 2);
    EXPECT_EQ(r.total, 12);
    EXPECT_EQ(r.today, 6);
    EXPECT_EQ(r.accumMs, interval / 2);
}

TEST_F(JadeRuntimeTxFixture, SettleGrantsClampedByHeadroomDropsRemainder) {
    const int64_t interval = jade_tx::kJadeIntervalMs;
    // today=19 → headroom=1；accum 攒 3 个周期 → 只发 1 枚、余量丢弃（非保留）
    const auto r = jade_tx::settleJadeGrantsTx(core_->state(), 5, 19, interval * 3);
    ASSERT_TRUE(r.base.ok);
    EXPECT_FALSE(r.frozen);
    auto& gd = core_->state().gameData;
    EXPECT_EQ(gd.jadeSymbols, 6);
    EXPECT_EQ(gd.jadeSymbolsToday, 20);
    EXPECT_EQ(gd.jadeAccumMs, 0);  // toGrant != grants → 余量丢弃
}

TEST_F(JadeRuntimeTxFixture, SettleFrozenWhenDailyCapReached) {
    auto& gd = core_->state().gameData;
    gd.jadeSymbols = 30;
    gd.jadeSymbolsToday = 20;
    const int64_t interval = jade_tx::kJadeIntervalMs;
    const auto r = jade_tx::settleJadeGrantsTx(core_->state(), 30, 20, interval * 2);
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(r.frozen);
    // Kotlin 冻结臂只写 jadeAccumMs（其余字段保持原值——运行时由回执承载）
    EXPECT_EQ(gd.jadeSymbols, 30);
    EXPECT_EQ(gd.jadeSymbolsToday, 20);
    EXPECT_EQ(gd.jadeAccumMs, 0);    // 冻结：accum 归零
    EXPECT_EQ(r.accumMs, 0);
}

TEST_F(JadeRuntimeTxFixture, SettleInvalidParamsZeroWriteFailure) {
    auto& gd = core_->state().gameData;
    gd.jadeSymbols = 5;
    const auto before = core_->exportStateJson();
    const auto r = jade_tx::settleJadeGrantsTx(core_->state(), -1, 0, 1'000);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "INVALID_PARAMS");
    EXPECT_EQ(core_->exportStateJson(), before);  // 失败零写入（全状态 JSON 逐位）
}

// ── 事务 6：jadeDayResetTx（1767）────────────────────────────────────────

TEST_F(JadeRuntimeTxFixture, DayResetFirstAnchorKeepsCounts) {
    auto& gd = core_->state().gameData;
    gd.jadeSymbolsToday = 4;
    gd.jadeAccumMs = 123'456;
    ASSERT_EQ(gd.jadeDayAnchorMs, 0);  // 旧档未锚定
    const int64_t midnight = 1'700'000'000'000 / 86'400'000 * 86'400'000;

    const auto r = jade_tx::jadeDayResetTx(core_->state(), midnight, 4, 123'456);
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    EXPECT_FALSE(r.crossedDay);
    EXPECT_EQ(gd.jadeDayAnchorMs, midnight);
    EXPECT_EQ(gd.jadeSymbolsToday, 4);    // 首锚不动计数
    EXPECT_EQ(gd.jadeAccumMs, 123'456);
}

TEST_F(JadeRuntimeTxFixture, DayResetCrossedDayZeroesTodayAndAccum) {
    auto& gd = core_->state().gameData;
    gd.jadeDayAnchorMs = 1'700'000'000'000 - 86'400'000;  // 昨日锚点
    gd.jadeSymbolsToday = 9;
    gd.jadeAccumMs = 300'000;
    const int64_t todayMidnight = 1'700'000'000'000;

    const auto r = jade_tx::jadeDayResetTx(core_->state(), todayMidnight, 9, 300'000);
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    EXPECT_TRUE(r.crossedDay);
    EXPECT_EQ(gd.jadeSymbolsToday, 0);
    EXPECT_EQ(gd.jadeAccumMs, 0);
    EXPECT_EQ(gd.jadeDayAnchorMs, todayMidnight);
}

TEST_F(JadeRuntimeTxFixture, DayResetSameDayAndRollbackAreZeroWrite) {
    auto& gd = core_->state().gameData;
    const int64_t anchor = 1'700'000'000'000;
    gd.jadeDayAnchorMs = anchor;
    gd.jadeSymbolsToday = 5;
    const auto before = core_->exportStateJson();

    // 同一天（midnight == anchor）
    auto same = jade_tx::jadeDayResetTx(core_->state(), anchor, 5, 100'000);
    ASSERT_TRUE(same.base.ok);
    EXPECT_FALSE(same.changed);
    // 墙钟回拨（midnight < anchor）
    auto rollback = jade_tx::jadeDayResetTx(core_->state(), anchor - 86'400'000, 5, 100'000);
    ASSERT_TRUE(rollback.base.ok);
    EXPECT_FALSE(rollback.changed);

    EXPECT_EQ(core_->exportStateJson(), before);  // 两臂均零写入（全状态 JSON 逐位）
}

TEST_F(JadeRuntimeTxFixture, DayResetSameCallWithinTickIsIdempotent) {
    const int64_t midnight = 1'700'000'000'000;
    const auto first = jade_tx::jadeDayResetTx(core_->state(), midnight, 9, 300'000);
    ASSERT_TRUE(first.base.ok);
    EXPECT_TRUE(first.changed);
    // 同一 tick 内以同一读数重复调用（红线 §5.2 第 1 条）：第二次必须零变更
    const auto second = jade_tx::jadeDayResetTx(core_->state(), midnight, 0, 0);
    ASSERT_TRUE(second.base.ok);
    EXPECT_FALSE(second.changed);
    EXPECT_EQ(second.dayAnchorMs, midnight);
    EXPECT_EQ(core_->state().gameData.jadeSymbolsToday, 0);
    EXPECT_EQ(core_->state().gameData.jadeAccumMs, 0);
    EXPECT_EQ(core_->state().gameData.jadeDayAnchorMs, midnight);
}

TEST_F(JadeRuntimeTxFixture, DayResetInvalidMidnightZeroWrite) {
    const auto before = core_->exportStateJson();
    const auto r = jade_tx::jadeDayResetTx(core_->state(), 0, 5, 100'000);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "INVALID_PARAMS");
    EXPECT_EQ(core_->exportStateJson(), before);
}

// ── 事务 7：jadeCheckpointTx（1768）──────────────────────────────────────

TEST_F(JadeRuntimeTxFixture, CheckpointOverwritesAllFourFields) {
    auto& gd = core_->state().gameData;
    gd.jadeSymbols = 1;
    gd.jadeSymbolsToday = 1;
    gd.jadeAccumMs = 1;
    gd.jadeDayAnchorMs = 1;

    const auto r = jade_tx::jadeCheckpointTx(core_->state(), 42, 7, 654'321, 1'700'000'000'000);
    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(gd.jadeSymbols, 42);
    EXPECT_EQ(gd.jadeSymbolsToday, 7);
    EXPECT_EQ(gd.jadeAccumMs, 654'321);
    EXPECT_EQ(gd.jadeDayAnchorMs, 1'700'000'000'000);
}

TEST_F(JadeRuntimeTxFixture, CheckpointInvalidParamsZeroWrite) {
    const auto before = core_->exportStateJson();
    const auto r = jade_tx::jadeCheckpointTx(core_->state(), -1, 0, 0, 0);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(core_->exportStateJson(), before);
}

// ── 事务 8：grantJadeFromAdTx（1769）─────────────────────────────────────

TEST_F(JadeRuntimeTxFixture, GrantAdWritesAbsoluteTotalNotToday) {
    auto& gd = core_->state().gameData;
    gd.jadeSymbols = 10;
    gd.jadeSymbolsToday = 20;  // 时间渠道已满——广告渠道独立
    const int64_t accumBefore = gd.jadeAccumMs;
    const int64_t anchorBefore = gd.jadeDayAnchorMs;

    const auto r = jade_tx::grantJadeFromAdTx(core_->state(), 3, 10);  // totalBefore=10
    ASSERT_TRUE(r.base.ok);
    // Kotlin grantFromAd 只写 jadeSymbols（绝对值）——其余三字段不动
    EXPECT_EQ(gd.jadeSymbols, 13);
    EXPECT_EQ(gd.jadeSymbolsToday, 20);  // 不动 todayCount
    EXPECT_EQ(gd.jadeAccumMs, accumBefore);
    EXPECT_EQ(gd.jadeDayAnchorMs, anchorBefore);
    EXPECT_EQ(r.total, 13);
}

TEST_F(JadeRuntimeTxFixture, GrantAdInvalidAmountZeroWrite) {
    const auto before = core_->exportStateJson();
    EXPECT_FALSE(jade_tx::grantJadeFromAdTx(core_->state(), 0, 10).base.ok);
    EXPECT_FALSE(jade_tx::grantJadeFromAdTx(core_->state(), -3, 10).base.ok);
    EXPECT_EQ(core_->exportStateJson(), before);
}

// ── 家族级：零 RNG + 双运行逐位一致 ─────────────────────────────────────

TEST_F(JadeRuntimeTxFixture, ZeroRngFamilyLeavesRngStatesUntouched) {
    const auto baseline = rngSnapshot();
    ASSERT_TRUE(jade_tx::settleJadeGrantsTx(core_->state(), 0, 0, jade_tx::kJadeIntervalMs * 2).base.ok);
    ASSERT_TRUE(jade_tx::jadeDayResetTx(core_->state(), 1'700'000'000'000, 0, 0).base.ok);
    ASSERT_TRUE(jade_tx::jadeCheckpointTx(core_->state(), 9, 1, 2, 3).base.ok);
    ASSERT_TRUE(jade_tx::grantJadeFromAdTx(core_->state(), 1, 9).base.ok);
    // 失败臂同样零抽取
    ASSERT_FALSE(jade_tx::settleJadeGrantsTx(core_->state(), -1, 0, 0).base.ok);
    EXPECT_EQ(rngSnapshot(), baseline);
}

TEST_F(JadeRuntimeTxFixture, DoubleRunBitwiseIdentical) {
    auto run = [&]() {
        jade_tx::settleJadeGrantsTx(core_->state(), 0, 5, jade_tx::kJadeIntervalMs * 2 + 100);
        jade_tx::jadeDayResetTx(core_->state(), 1'700'000'000'000, 0, 0);
        jade_tx::jadeCheckpointTx(core_->state(), 11, 3, 400'000, 1'700'000'000'000);
        jade_tx::grantJadeFromAdTx(core_->state(), 2, 11);
        return core_->exportStateJson();
    };
    const std::string first = run();
    SetUp();
    const std::string second = run();
    EXPECT_EQ(second, first);
}

// ── 信封级：execute 通道（dispatchW4B 端口，Kotlin 回退臂契约）──────────

TEST_F(JadeRuntimeTxFixture, DispatchEnvelopeSuccessAndFailure) {
    // 成功信封（1766：有发放）
    auto env = exec(1766, {{"total", 0}, {"today", 0},
                           {"accumMs", jade_tx::kJadeIntervalMs * 2}});
    ASSERT_EQ(env["status"], "success");
    EXPECT_EQ(env["data"]["total"], 2);
    EXPECT_EQ(env["data"]["today"], 2);

    // 失败信封（参数非法 → failure → Kotlin 回退臂契约）
    auto bad = exec(1766, {{"total", -1}, {"today", 0}, {"accumMs", 0}});
    EXPECT_EQ(bad["status"], "failure");
    EXPECT_EQ(bad["code"], "INVALID_PARAMS");
}

TEST_F(JadeRuntimeTxFixture, DispatchPortDoesNotClaimForeignSegments) {
    // 1760–1765（w3-03）整段留空：端口不认领 → execute 返回 NOT_IMPLEMENTED 兜底
    auto env = exec(1760, {});
    EXPECT_EQ(env["status"], "failure");
    EXPECT_EQ(env["code"], "NOT_IMPLEMENTED");
    // 未注册号（段外）同样不认领
    auto foreign = exec(1775, {});
    EXPECT_EQ(foreign["status"], "failure");
}

}  // namespace
}  // namespace gamecore
