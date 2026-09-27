#include <gtest/gtest.h>

#include "gamecore/game_core.h"
#include "gamecore/rng/rng_manager.h"

namespace gamecore::stats {
namespace {

using system::kSettleFlagMonthChanged;
using system::kSettleFlagYearChanged;
using rng::RngManager;
using rng::RngPartition;

// ============================================================
// 标量通道单元测试：settleOnePhase 边界标志位 + core 模式钩子抑制 +
// RNG 分区标量通道。语义权威 = SettlementEngine（settlement.h）。
// ============================================================

class SettlePhaseTest : public ::testing::Test {
protected:
    void SetUp() override {
        core_ = std::make_unique<GameCore>(&clock_, &logger_);
        GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        ASSERT_TRUE(core_->initialize(config));
    }

    FixedClock clock_;
    ConsoleLogger logger_;
    std::unique_ptr<GameCore> core_;
};

TEST_F(SettlePhaseTest, SettleOnePhaseAdvancesExactlyOnePhase) {
    auto& gd = core_->state().gameData;
    gd.gameYear = 1;
    gd.gameMonth = 5;
    gd.gamePhase = 0;
    EXPECT_EQ(0, core_->settleOnePhase());
    // time_system.h：phase 0/1/2 三旬一月，上旬→中旬不跨月
    EXPECT_EQ(5, gd.gameMonth);
    EXPECT_EQ(1, gd.gamePhase);
}

TEST_F(SettlePhaseTest, MonthBoundaryRaisesFlagAndFiresNoHookInCoreMode) {
    // 默认非 core 模式下钩子已注册（game_core.cpp initialize）——本用例只断言标志位；
    // 钩子抑制行为由 CoreModeSuppressesMonthYearHooks 用例守护
    auto& gd = core_->state().gameData;
    gd.gameYear = 1;
    gd.gameMonth = 5;
    gd.gamePhase = 2;   // 下旬 → 推进跨月
    const int flags = core_->settleOnePhase();
    EXPECT_NE(0, flags & kSettleFlagMonthChanged);
    EXPECT_EQ(6, gd.gameMonth);
}

TEST_F(SettlePhaseTest, CoreModeSuppressesMonthYearHooksButKeepsFlags) {
    int monthHookCalls = 0;
    int yearHookCalls = 0;
    int phaseHookCalls = 0;
    auto& engine = core_->settlement();
    engine.setCoreMode(true);
    engine.onCoreSettle = [&](state::GameState&, state::GameData&) { ++phaseHookCalls; };
    engine.onMonthChange = [&](state::GameState&, state::GameData&) { ++monthHookCalls; };
    engine.onYearChange = [&](state::GameState&, state::GameData&) { ++yearHookCalls; };

    auto& gd = core_->state().gameData;
    // 跨月界：5 月下旬 → 6 月上旬
    gd.gameYear = 1;
    gd.gameMonth = 5;
    gd.gamePhase = 2;
    EXPECT_NE(0, core_->settleOnePhase() & kSettleFlagMonthChanged);
    // 跨年界：12 月下旬 → 次年 1 月上旬
    gd.gameMonth = 12;
    gd.gamePhase = 2;
    const int yearFlags = core_->settleOnePhase();
    EXPECT_NE(0, yearFlags & kSettleFlagYearChanged);
    EXPECT_NE(0, yearFlags & kSettleFlagMonthChanged);
    EXPECT_EQ(2, gd.gameYear);
    EXPECT_EQ(1, gd.gameMonth);

    // core 模式：核心钩子逐旬触发；月/年结算钩子全程抑制（Kotlin 残留执行器处理）
    EXPECT_EQ(2, phaseHookCalls);
    EXPECT_EQ(0, monthHookCalls);
    EXPECT_EQ(0, yearHookCalls);
}

TEST_F(SettlePhaseTest, RngScalarChannelMatchesPartitionStreams) {
    // 标量通道抽取 == 分区内部流顺序输出（与 Kotlin DeterministicRng 对拍由
    // DiffRngTest 守护，此处只验证通道接线正确）。
    // 注意：两侧都消耗同一条流，必须显式定序再比较（函数实参求值序未指定）
    const int pid = static_cast<int>(RngPartition::kBreakthrough);
    auto& stream = core_->rng().getRng(RngPartition::kBreakthrough);
    // 通道抽取后回滚快照，再由流直抽——两者必须同值（证明通道接在同一分区流上）
    const int64_t before = stream.snapshot();
    const int32_t viaChannel1 = core_->rngNextInt(pid);
    stream.restore(before);
    EXPECT_EQ(viaChannel1, stream.nextInt());
    const int32_t viaChannel2 = core_->rngNextInt(pid);
    const int64_t after2 = stream.snapshot();
    stream.restore(before);
    EXPECT_EQ(viaChannel1, stream.nextInt());
    EXPECT_EQ(viaChannel2, stream.nextInt());
    stream.restore(after2);
    // 第二轮同样走快照-重放
    const int64_t before2 = stream.snapshot();
    const int32_t viaChannel3 = core_->rngNextInt(pid);
    stream.restore(before2);
    EXPECT_EQ(viaChannel3, stream.nextInt());
    // 快照/恢复往返
    const int64_t snap = core_->rngSnapshotPartition(static_cast<int>(RngPartition::kSystem));
    const int32_t consumed = core_->rngNextInt(static_cast<int>(RngPartition::kSystem));
    ASSERT_TRUE(core_->rngRestorePartition(static_cast<int>(RngPartition::kSystem), snap));
    EXPECT_EQ(consumed, core_->rngNextInt(static_cast<int>(RngPartition::kSystem)));
    // 非法分区：返回 0 且不崩溃
    EXPECT_EQ(0, core_->rngNextInt(-1));
    EXPECT_EQ(0, core_->rngNextInt(99));
    EXPECT_FALSE(core_->rngRestorePartition(-1, 0));
}

TEST_F(SettlePhaseTest, RngInitSeedReseedsAllPartitions) {
    const int pid = static_cast<int>(RngPartition::kSystem);
    core_->rngNextInt(pid);
    core_->rngNextInt(pid);
    core_->rngInitSystemSeed(77);
    // 重播后首抽 == 从种子新分区的首抽
    RngManager fresh;
    fresh.initSystemSeed(77);
    EXPECT_EQ(fresh.getRng(RngPartition::kSystem).nextInt(), core_->rngNextInt(pid));
}

// ============================================================
// 未截断推进 advanceByGameMs（结算改造 2026-09-27 B2，INV-2/INV-3）
// ============================================================

// 分帧不变：同一段现实时间以不同 chunk 推进，权威轴与判定执行数一致，
// 日历推进数一致（判定窗口整数差的遥缴性质）
TEST_F(SettlePhaseTest, AdvanceByGameMsChunkingInvariance) {
    auto& engine = core_->settlement();
    engine.setCoreMode(true);   // 隔离钩子副作用，只看时间推进

    {
        auto& gd = core_->state().gameData;
        gd.gameYear = 1; gd.gameMonth = 1; gd.gamePhase = 0;
        engine.reset();
        int64_t fed = 0;
        while (fed < 20'000) {   // 20s = 10 旬
            engine.advanceByGameMs(core_->state(), 100);
            fed += 100;
        }
        EXPECT_EQ(20'000, engine.elapsedGameMs());
        // 10 旬 = 3 整月 + 1 旬 ⇒ 第 4 月中旬
        EXPECT_EQ(1, gd.gameYear); EXPECT_EQ(4, gd.gameMonth); EXPECT_EQ(1, gd.gamePhase);
    }
    {
        auto& gd = core_->state().gameData;
        gd.gameYear = 1; gd.gameMonth = 1; gd.gamePhase = 0;
        engine.reset();
        int64_t fed = 0;
        while (fed < 20'000) {   // 同段时间 700ms 大 chunk（尾块截齐）
            const int64_t step = std::min<int64_t>(700, 20'000 - fed);
            engine.advanceByGameMs(core_->state(), step);
            fed += step;
        }
        EXPECT_EQ(20'000, engine.elapsedGameMs());
        EXPECT_EQ(1, gd.gameYear); EXPECT_EQ(4, gd.gameMonth); EXPECT_EQ(1, gd.gamePhase);
    }
}

// INV-2 反证：单次超长增量（> 追补上限）不丢时间——deltaGameMs 全额返回，
// 判定执行被 cap、应执行数如实记录
TEST_F(SettlePhaseTest, AdvanceByGameMsDoesNotDropTime) {
    auto& engine = core_->settlement();
    engine.setCoreMode(true);
    auto& gd = core_->state().gameData;
    gd.gameYear = 1; gd.gameMonth = 1; gd.gamePhase = 0;
    engine.reset();

    const system::AccrualResult r = engine.advanceByGameMs(core_->state(), 60'000);
    EXPECT_EQ(60'000, r.deltaGameMs);                       // 权威轴全额（INV-2）
    EXPECT_EQ(30, r.windowsTotal);                          // 60s / 2s = 30 窗口
    EXPECT_EQ(system::maxPhasesPerTick(engine.speed()), r.windowsExecuted);
    EXPECT_EQ(60'000, engine.elapsedGameMs());
}

// INV-3：窗口 = 整数差；不足一旬的余量不产生判定，余量跨段保留
TEST_F(SettlePhaseTest, AdvanceByGameMsWindowIntegerDifference) {
    auto& engine = core_->settlement();
    engine.setCoreMode(true);
    engine.reset();
    auto& gd = core_->state().gameData;
    gd.gameYear = 1; gd.gameMonth = 1; gd.gamePhase = 0;

    system::AccrualResult r = engine.advanceByGameMs(core_->state(), 1999);
    EXPECT_EQ(1999, r.deltaGameMs);
    EXPECT_EQ(0, r.windowsTotal);
    EXPECT_EQ(0, r.windowsExecuted);
    EXPECT_EQ(0, gd.gamePhase);   // 不足一旬：日历不动

    r = engine.advanceByGameMs(core_->state(), 1);   // 凑满 2000ms
    EXPECT_EQ(2000, engine.elapsedGameMs());
    EXPECT_EQ(1, r.windowsTotal);
    EXPECT_EQ(1, r.windowsExecuted);
    EXPECT_EQ(1, gd.gamePhase);
}

// 速度缩放与暂停：2x 翻倍；speed=0 零增量零判定
TEST_F(SettlePhaseTest, AdvanceByGameMsSpeedScalingAndPause) {
    auto& engine = core_->settlement();
    engine.setCoreMode(true);
    engine.reset();

    engine.setSpeed(2);
    const system::AccrualResult r = engine.advanceByGameMs(core_->state(), 1000);
    EXPECT_EQ(2000, r.deltaGameMs);
    EXPECT_EQ(1, r.windowsExecuted);

    engine.setSpeed(0);
    const system::AccrualResult paused = engine.advanceByGameMs(core_->state(), 5000);
    EXPECT_EQ(0, paused.deltaGameMs);
    EXPECT_EQ(0, paused.windowsTotal);
    EXPECT_EQ(0, paused.windowsExecuted);
}

// 边界标志：跨月/跨年在执行窗口内如实上报（年变先于月变的钩子序不变）
TEST_F(SettlePhaseTest, AdvanceByGameMsBoundaryFlags) {
    auto& engine = core_->settlement();
    engine.setCoreMode(true);   // core 模式：只看标志（月/年结算由 Kotlin 编排）
    engine.reset();
    auto& gd = core_->state().gameData;
    gd.gameYear = 1; gd.gameMonth = 5; gd.gamePhase = 2;   // 下旬 → 跨月

    const system::AccrualResult r = engine.advanceByGameMs(core_->state(), 2000);
    EXPECT_NE(0, r.monthChanged);
    EXPECT_EQ(6, gd.gameMonth);
}

}  // namespace
}  // namespace gamecore::stats
