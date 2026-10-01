// ============================================================
// jade_runtime_tx_test — 玉符运行时事务守护（W4-B/B2，w3-04，1766–1769）
//
// 守护目标：jade_tx.h 事务 5–8 与 Kotlin JadeSymbolService 语义逐位一致——
//   - settleJadeGrantsTx（1766）：grants<=0 零写入；整除发放保留余量；
//     headroom 钳制；拿满冻结 accum=0；发放走账本（GRANT_TIME 条目 +
//     派生缓存同事务双写）；非法参数零写入
//   - jadeDayResetTx（1767）：首锚只锚定；真跨天归零计数与累计（余额零
//     变化不落账）；同一天/墙钟回拨零写入；**同一 todayMidnight 重复调用
//     幂等**（同 tick 幂等红线，ADR 盲区 3 兜底）；派生校验（drift 重锚）
//   - jadeCheckpointTx（1768）：写 today/accum/anchor 三字段，余额不做
//     覆盖写（真源在账本）；派生校验（drift 重锚）
//   - grantJadeFromAdTx（1769）：账本落 GRANT_AD 条目 + 派生双写、不动
//     todayCount（白名单直发同 reason 如实记录来源）
//   - 账本不变式：派生余额 == 期初 + Σdelta == 末条 balance_after（跨
//     事务序列）
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

    // ── 玉符账本测试面（SS9）──────────────────────────────────────────

    /// 开账：以 openingBalance 为期初余额写 OPENING_BALANCE 期初条目
    /// （delta = balanceAfter = 期初余额，自含锚点；与 Kotlin
    /// withStartupLedger 同语义）
    void openLedger(int32_t openingBalance) {
        auto& gd = core_->state().gameData;
        gd.jadeSymbols = openingBalance;
        const int32_t after = jade_tx::openJadeLedger(gd, 1'700'000'000'000LL);
        ASSERT_EQ(openingBalance, after);
        ASSERT_EQ(1u, gd.jadeLedger.size());
        ASSERT_EQ(jade_tx::kJadeReasonOpeningBalance, gd.jadeLedger[0].reason);
    }

    const std::vector<gamecore::state::JadeLedgerEntry>& ledger() const {
        return core_->state().gameData.jadeLedger;
    }

    /// 账本不变式：期初 + Σdelta == 末条 balance_after == 派生缓存
    void expectLedgerInvariant() {
        const auto& ld = ledger();
        ASSERT_FALSE(ld.empty());
        int32_t sum = 0;
        for (const auto& e : ld) sum += e.delta;
        EXPECT_EQ(sum, ld.back().balanceAfter)
            << "Σdelta != 末条 balance_after（账本条目冗余失真）";
        EXPECT_EQ(ld.back().balanceAfter, core_->state().gameData.jadeSymbols)
            << "派生缓存 != 账本余额（同事务双写断裂）";
    }

    std::vector<std::unique_ptr<FixedClock>> clocks_;
    std::vector<std::unique_ptr<ConsoleLogger>> loggers_;
    std::unique_ptr<GameCore> core_;
};

// ── 事务 5：settleJadeGrantsTx（1766）────────────────────────────────────

TEST_F(JadeRuntimeTxFixture, SettleGrantsBelowIntervalIsZeroWriteEcho) {
    openLedger(7);
    auto& gd = core_->state().gameData;
    gd.jadeSymbolsToday = 3;
    gd.jadeAccumMs = 599'999;  // 不足 1 个周期

    const auto r = jade_tx::settleJadeGrantsTx(core_->state(), 3, 599'999,
                                               1'700'000'000'000LL);
    ASSERT_TRUE(r.base.ok);
    EXPECT_FALSE(r.frozen);
    EXPECT_FALSE(r.drift);
    // 零写入：状态保持原值（余额真源在账本，缓存不被触碰）
    EXPECT_EQ(gd.jadeSymbols, 7);
    EXPECT_EQ(gd.jadeSymbolsToday, 3);
    EXPECT_EQ(gd.jadeAccumMs, 599'999);
    // 回执回声（total = 账本余额）
    EXPECT_EQ(r.total, 7);
    EXPECT_EQ(r.today, 3);
    EXPECT_EQ(r.accumMs, 599'999);
    EXPECT_EQ(1u, ledger().size());  // 账本零新增
}

TEST_F(JadeRuntimeTxFixture, SettleGrantsIntervalsKeepRemainder) {
    openLedger(10);
    const int64_t interval = jade_tx::kJadeIntervalMs;
    // 2.5 个周期 → 发 2 枚、保留半个周期余量
    const auto r = jade_tx::settleJadeGrantsTx(core_->state(), 4,
                                               interval * 2 + interval / 2,
                                               1'700'000'000'000LL);
    ASSERT_TRUE(r.base.ok);
    EXPECT_FALSE(r.frozen);
    EXPECT_FALSE(r.drift);
    auto& gd = core_->state().gameData;
    EXPECT_EQ(gd.jadeSymbols, 12);
    EXPECT_EQ(gd.jadeSymbolsToday, 6);
    EXPECT_EQ(gd.jadeAccumMs, interval / 2);
    EXPECT_EQ(r.total, 12);
    EXPECT_EQ(r.today, 6);
    EXPECT_EQ(r.accumMs, interval / 2);
    // 账本落账：一条 GRANT_TIME（+2 / balance_after=12）
    ASSERT_EQ(2u, ledger().size());
    EXPECT_EQ(jade_tx::kJadeReasonGrantTime, ledger()[1].reason);
    EXPECT_EQ(2, ledger()[1].delta);
    EXPECT_EQ(12, ledger()[1].balanceAfter);
    expectLedgerInvariant();
}

TEST_F(JadeRuntimeTxFixture, SettleGrantsClampedByHeadroomDropsRemainder) {
    openLedger(5);
    const int64_t interval = jade_tx::kJadeIntervalMs;
    // today=19 → headroom=1；accum 攒 3 个周期 → 只发 1 枚、余量丢弃（非保留）
    const auto r = jade_tx::settleJadeGrantsTx(core_->state(), 19, interval * 3,
                                               1'700'000'000'000LL);
    ASSERT_TRUE(r.base.ok);
    EXPECT_FALSE(r.frozen);
    auto& gd = core_->state().gameData;
    EXPECT_EQ(gd.jadeSymbols, 6);
    EXPECT_EQ(gd.jadeSymbolsToday, 20);
    EXPECT_EQ(gd.jadeAccumMs, 0);  // toGrant != grants → 余量丢弃
    ASSERT_EQ(2u, ledger().size());
    EXPECT_EQ(1, ledger()[1].delta);
    EXPECT_EQ(6, ledger()[1].balanceAfter);
    expectLedgerInvariant();
}

TEST_F(JadeRuntimeTxFixture, SettleFrozenWhenDailyCapReached) {
    openLedger(30);
    auto& gd = core_->state().gameData;
    gd.jadeSymbolsToday = 20;
    const int64_t interval = jade_tx::kJadeIntervalMs;
    const auto r = jade_tx::settleJadeGrantsTx(core_->state(), 20, interval * 2,
                                               1'700'000'000'000LL);
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(r.frozen);
    // 冻结臂只写 jadeAccumMs（余额零变化不落账——账本保持期初一条）
    EXPECT_EQ(gd.jadeSymbols, 30);
    EXPECT_EQ(gd.jadeSymbolsToday, 20);
    EXPECT_EQ(gd.jadeAccumMs, 0);    // 冻结：accum 归零
    EXPECT_EQ(r.accumMs, 0);
    EXPECT_EQ(r.total, 30);
    EXPECT_EQ(1u, ledger().size());
}

TEST_F(JadeRuntimeTxFixture, SettleInvalidParamsZeroWriteFailure) {
    openLedger(5);
    const auto before = core_->exportStateJson();
    const auto r = jade_tx::settleJadeGrantsTx(core_->state(), 0, 1'000, -1);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "INVALID_PARAMS");
    EXPECT_EQ(core_->exportStateJson(), before);  // 失败零写入（全状态 JSON 逐位）
}

// ── 事务 6：jadeDayResetTx（1767）────────────────────────────────────────

TEST_F(JadeRuntimeTxFixture, DayResetFirstAnchorKeepsCounts) {
    openLedger(9);
    auto& gd = core_->state().gameData;
    gd.jadeSymbolsToday = 4;
    gd.jadeAccumMs = 123'456;
    ASSERT_EQ(gd.jadeDayAnchorMs, 0);  // 旧档未锚定
    const int64_t midnight = 1'700'000'000'000 / 86'400'000 * 86'400'000;

    const auto r = jade_tx::jadeDayResetTx(core_->state(), midnight, 4, 123'456);
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    EXPECT_FALSE(r.crossedDay);
    EXPECT_FALSE(r.drift);
    EXPECT_EQ(gd.jadeDayAnchorMs, midnight);
    EXPECT_EQ(gd.jadeSymbolsToday, 4);    // 首锚不动计数
    EXPECT_EQ(gd.jadeAccumMs, 123'456);
    EXPECT_EQ(1u, ledger().size());       // 余额零变化不落账
}

TEST_F(JadeRuntimeTxFixture, DayResetCrossedDayZeroesTodayAndAccum) {
    openLedger(9);
    auto& gd = core_->state().gameData;
    gd.jadeDayAnchorMs = 1'700'000'000'000 - 86'400'000;  // 昨日锚点
    gd.jadeSymbolsToday = 9;
    gd.jadeAccumMs = 300'000;
    const int64_t todayMidnight = 1'700'000'000'000;

    const auto r = jade_tx::jadeDayResetTx(core_->state(), todayMidnight, 9, 300'000);
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    EXPECT_TRUE(r.crossedDay);
    EXPECT_FALSE(r.drift);
    EXPECT_EQ(gd.jadeSymbolsToday, 0);
    EXPECT_EQ(gd.jadeAccumMs, 0);
    EXPECT_EQ(gd.jadeDayAnchorMs, todayMidnight);
    EXPECT_EQ(1u, ledger().size());       // 余额零变化不落账
    EXPECT_EQ(9, core_->state().gameData.jadeSymbols);  // 余额不动
}

TEST_F(JadeRuntimeTxFixture, DayResetSameDayAndRollbackAreZeroWrite) {
    openLedger(5);
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
    openLedger(9);
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

TEST_F(JadeRuntimeTxFixture, DayResetReanchorsDriftedCache) {
    openLedger(9);
    // 模拟残留覆盖写：派生缓存被独立改写（账本末条仍为 9）
    core_->state().gameData.jadeSymbols = 42;
    const int64_t midnight = 1'700'000'000'000;

    const auto r = jade_tx::jadeDayResetTx(core_->state(), midnight, 9, 300'000);
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(r.drift);
    EXPECT_EQ(core_->state().gameData.jadeSymbols, 9);  // 以账本为准重锚
    expectLedgerInvariant();
}

TEST_F(JadeRuntimeTxFixture, DayResetInvalidMidnightZeroWrite) {
    const auto before = core_->exportStateJson();
    const auto r = jade_tx::jadeDayResetTx(core_->state(), 0, 5, 100'000);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "INVALID_PARAMS");
    EXPECT_EQ(core_->exportStateJson(), before);
}

// ── 事务 7：jadeCheckpointTx（1768）──────────────────────────────────────

TEST_F(JadeRuntimeTxFixture, CheckpointWritesRuntimeFieldsNotBalance) {
    openLedger(11);
    auto& gd = core_->state().gameData;
    gd.jadeSymbolsToday = 1;
    gd.jadeAccumMs = 1;
    gd.jadeDayAnchorMs = 1;

    const auto r = jade_tx::jadeCheckpointTx(core_->state(), 7, 654'321,
                                             1'700'000'000'000);
    ASSERT_TRUE(r.base.ok);
    EXPECT_FALSE(r.drift);
    // 余额真源在账本：checkpoint 不做覆盖写，jadeSymbols 保持账本值
    EXPECT_EQ(gd.jadeSymbols, 11);
    EXPECT_EQ(gd.jadeSymbolsToday, 7);
    EXPECT_EQ(gd.jadeAccumMs, 654'321);
    EXPECT_EQ(gd.jadeDayAnchorMs, 1'700'000'000'000);
    EXPECT_EQ(r.total, 11);               // 回执 = 账本余额
    EXPECT_EQ(1u, ledger().size());       // 余额零变化不落账
}

TEST_F(JadeRuntimeTxFixture, CheckpointReanchorsDriftedCache) {
    openLedger(11);
    core_->state().gameData.jadeSymbols = 77;  // 模拟残留覆盖写

    const auto r = jade_tx::jadeCheckpointTx(core_->state(), 3, 400'000,
                                             1'700'000'000'000);
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(r.drift);
    EXPECT_EQ(core_->state().gameData.jadeSymbols, 11);  // 以账本为准重锚
    EXPECT_EQ(r.total, 11);
    expectLedgerInvariant();
}

TEST_F(JadeRuntimeTxFixture, CheckpointInvalidParamsZeroWrite) {
    const auto before = core_->exportStateJson();
    const auto r = jade_tx::jadeCheckpointTx(core_->state(), -1, 0, 0);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(core_->exportStateJson(), before);
}

// ── 事务 8：grantJadeFromAdTx（1769）─────────────────────────────────────

TEST_F(JadeRuntimeTxFixture, GrantAdAppendsLedgerNotToday) {
    openLedger(10);
    auto& gd = core_->state().gameData;
    gd.jadeSymbolsToday = 20;  // 时间渠道已满——广告渠道独立
    const int64_t accumBefore = gd.jadeAccumMs;
    const int64_t anchorBefore = gd.jadeDayAnchorMs;

    const auto r = jade_tx::grantJadeFromAdTx(core_->state(), 3,
                                              1'700'000'000'000LL);
    ASSERT_TRUE(r.base.ok);
    EXPECT_FALSE(r.drift);
    // 只落 GRANT_AD 条目 + 派生双写；today/accum/anchor 三字段不动
    EXPECT_EQ(gd.jadeSymbols, 13);
    EXPECT_EQ(gd.jadeSymbolsToday, 20);  // 不动 todayCount
    EXPECT_EQ(gd.jadeAccumMs, accumBefore);
    EXPECT_EQ(gd.jadeDayAnchorMs, anchorBefore);
    EXPECT_EQ(r.total, 13);
    ASSERT_EQ(2u, ledger().size());
    EXPECT_EQ(jade_tx::kJadeReasonGrantAd, ledger()[1].reason);
    EXPECT_EQ(3, ledger()[1].delta);
    EXPECT_EQ(13, ledger()[1].balanceAfter);
    expectLedgerInvariant();
}

TEST_F(JadeRuntimeTxFixture, GrantAdInvalidAmountZeroWrite) {
    openLedger(10);
    const auto before = core_->exportStateJson();
    EXPECT_FALSE(jade_tx::grantJadeFromAdTx(core_->state(), 0,
                                            1'700'000'000'000LL).base.ok);
    EXPECT_FALSE(jade_tx::grantJadeFromAdTx(core_->state(), -3,
                                            1'700'000'000'000LL).base.ok);
    EXPECT_EQ(core_->exportStateJson(), before);
    EXPECT_EQ(1u, ledger().size());       // 账本零新增
}

// ── 账本不变式（SS9）：跨事务序列 + 导入期初兜底 ─────────────────────────

TEST_F(JadeRuntimeTxFixture, LedgerSumInvariantAcrossRuntimeFamily) {
    // 验收⑤守卫断言：任意时点 派生余额 == 期初 + 流水合计
    openLedger(10);
    const int64_t interval = jade_tx::kJadeIntervalMs;
    ASSERT_TRUE(jade_tx::settleJadeGrantsTx(core_->state(), 0, interval * 2,
                                            1'700'000'000'000LL).base.ok);
    ASSERT_TRUE(jade_tx::grantJadeFromAdTx(core_->state(), 3,
                                           1'700'000'000'000LL).base.ok);
    ASSERT_TRUE(jade_tx::jadeCheckpointTx(core_->state(), 2, 0,
                                          1'700'000'000'000).base.ok);
    expectLedgerInvariant();  // 10 + 2 + 3 = 15（checkpoint 零条目）
    EXPECT_EQ(15, core_->state().gameData.jadeSymbols);
    EXPECT_EQ(15, core_->state().gameData.jadeLedger.back().balanceAfter);
}

// ── 家族级：零 RNG + 双运行逐位一致 ─────────────────────────────────────

TEST_F(JadeRuntimeTxFixture, ZeroRngFamilyLeavesRngStatesUntouched) {
    openLedger(0);
    const auto baseline = rngSnapshot();
    ASSERT_TRUE(jade_tx::settleJadeGrantsTx(core_->state(), 0, jade_tx::kJadeIntervalMs * 2,
                                            1'700'000'000'000LL).base.ok);
    ASSERT_TRUE(jade_tx::jadeDayResetTx(core_->state(), 1'700'000'000'000, 0, 0).base.ok);
    ASSERT_TRUE(jade_tx::jadeCheckpointTx(core_->state(), 1, 2, 3).base.ok);
    ASSERT_TRUE(jade_tx::grantJadeFromAdTx(core_->state(), 1,
                                           1'700'000'000'000LL).base.ok);
    // 失败臂同样零抽取
    ASSERT_FALSE(jade_tx::settleJadeGrantsTx(core_->state(), 0, -1, 0).base.ok);
    EXPECT_EQ(rngSnapshot(), baseline);
}

TEST_F(JadeRuntimeTxFixture, DoubleRunBitwiseIdentical) {
    auto run = [&]() {
        openLedger(0);
        jade_tx::settleJadeGrantsTx(core_->state(), 5, jade_tx::kJadeIntervalMs * 2 + 100,
                                    1'700'000'000'000LL);
        jade_tx::jadeDayResetTx(core_->state(), 1'700'000'000'000, 0, 0);
        jade_tx::jadeCheckpointTx(core_->state(), 3, 400'000, 1'700'000'000'000);
        jade_tx::grantJadeFromAdTx(core_->state(), 2, 1'700'000'000'000LL);
        return core_->exportStateJson();
    };
    const std::string first = run();
    SetUp();
    const std::string second = run();
    EXPECT_EQ(second, first);
}

// ── 信封级：execute 通道（dispatchW4B 端口，Kotlin 回退臂契约）──────────

TEST_F(JadeRuntimeTxFixture, DispatchEnvelopeSuccessAndFailure) {
    openLedger(0);
    // 成功信封（1766：有发放——账本落 GRANT_TIME + 派生双写）
    auto env = exec(1766, {{"today", 0},
                           {"accumMs", jade_tx::kJadeIntervalMs * 2},
                           {"nowMs", 1'700'000'000'000LL}});
    ASSERT_EQ(env["status"], "success");
    EXPECT_EQ(env["data"]["total"], 2);
    EXPECT_EQ(env["data"]["today"], 2);
    EXPECT_FALSE(env["data"]["drift"].get<bool>());
    EXPECT_EQ(2, core_->state().gameData.jadeSymbols);
    ASSERT_EQ(2u, ledger().size());
    EXPECT_EQ(jade_tx::kJadeReasonGrantTime, ledger()[1].reason);

    // 失败信封（参数非法 → failure → Kotlin 回退臂契约）
    auto bad = exec(1766, {{"today", 0}, {"accumMs", -1}, {"nowMs", 0}});
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
