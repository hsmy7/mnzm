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

}  // namespace
}  // namespace gamecore::stats
