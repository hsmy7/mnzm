#include <gtest/gtest.h>

#include "gamecore/core/platform.h"
#include "gamecore/system/engine_loop.h"

namespace gamecore {
namespace {

using system::EngineLoop;
using system::LoopFramePlan;
using system::PhaseClock;

// 追补公式双端对拍：本用例锁定 C++ 侧公式
// maxPhasesPerTick(speed)=3×max(speed,1)（settlement.h 单一来源）；
// Kotlin 侧对应 GameTimeClockPhaseCapParityTest（Kotlin core/engine tests）
// 以同公式同常量锁定——两测互为锚点，改值须双端同步。
TEST(PhaseCapParityTest, FormulaMatchesDocumentedConstant) {
    EXPECT_EQ(system::maxPhasesPerTick(0), 3);
    EXPECT_EQ(system::maxPhasesPerTick(1), 3);
    EXPECT_EQ(system::maxPhasesPerTick(2), 6);
    EXPECT_EQ(system::maxPhasesPerTick(-1), 3);  // 负速度消毒为 1x 档
}

// ============================================================
// 引擎循环测试
// PhaseClock 用例与 Kotlin GameTimeClockTest 逐条对齐（双端锚定
// GameTimeClock 语义）；EngineLoop 用例锚定 GameEngineCore
// gameLoopIteration 帧迭代判据。
// ============================================================

class PhaseClockTest : public ::testing::Test {
protected:
    FixedMonotonicClock fakeTime;
    PhaseClock clock;

    void SetUp() override {
        clock.setMonotonicClock(&fakeTime);
        clock.start();
    }

    /// 推进真实时间并 tick（返回 phasesToAdvance）
    int simulateTick(int64_t elapsedMs) {
        fakeTime.advanceMs(elapsedMs);
        return clock.tick();
    }
};

// 1. 1x 速度下 2000ms → 恰好 1 旬
TEST_F(PhaseClockTest, Speed1x2000msAdvances1Phase) {
    EXPECT_EQ(1, simulateTick(2000));
}

// 2. 1x 速度下 6000ms → 恰好 3 旬（1 月）
TEST_F(PhaseClockTest, Speed1x6000msAdvances3Phases) {
    EXPECT_EQ(3, simulateTick(6000));
}

// 3. 2x 速度下 1000ms 真实时间 = 2000ms 游戏时间 → 2 旬
TEST_F(PhaseClockTest, Speed2x1000msAdvances2Phases) {
    clock.setSpeed(2);
    clock.start();
    EXPECT_EQ(2, simulateTick(1000));
}

// 4. 2x 速度下 3000ms → 6 旬 = 缩放后上限（3×2）→ 恰好不截断
TEST_F(PhaseClockTest, Speed2x3000msAtScaledCap) {
    clock.setSpeed(2);
    clock.start();
    EXPECT_EQ(system::kMaxPhasesPerTick * 2, simulateTick(3000));
}

// 5. 暂停(speed=0) → 不推进任何旬
TEST_F(PhaseClockTest, Speed0DoesNotAdvance) {
    clock.setSpeed(0);
    EXPECT_EQ(0, simulateTick(5000));
}

// 6. 速度切换中保存累积量（setSpeed 旧速度结算语义）
TEST_F(PhaseClockTest, SpeedSwitchPreservesAccumulation) {
    EXPECT_EQ(0, simulateTick(1500));
    clock.setSpeed(2);
    EXPECT_EQ(2, simulateTick(500));
}

// 7/8. phaseProgress / remainingPhaseMs
TEST_F(PhaseClockTest, PhaseProgressBoundsAndRemaining) {
    clock.start();
    EXPECT_NEAR(0.f, clock.phaseProgress(), 0.01f);
    EXPECT_EQ(system::kMsPerPhase1x, clock.remainingPhaseMs());

    simulateTick(1000);
    EXPECT_GE(clock.phaseProgress(), 0.f);
    EXPECT_LE(clock.phaseProgress(), 1.f);
    EXPECT_EQ(1000, clock.remainingPhaseMs());
}

// 11. 一次 tick 内累积多旬 → 4 旬超上限截断为 3
TEST_F(PhaseClockTest, MultiPhaseInOneTickCappedAt3) {
    EXPECT_EQ(system::kMaxPhasesPerTick, simulateTick(8000));
}

// 12. 超大 delta 由缩放上限约束
TEST_F(PhaseClockTest, LargeDeltaCappedByScaledCap) {
    clock.setSpeed(2);
    EXPECT_EQ(system::kMaxPhasesPerTick * 2, simulateTick(100'000));
}

// 13. 暂停后恢复：累积量不丢
TEST_F(PhaseClockTest, PauseResumePreservesState) {
    simulateTick(1000);
    clock.setSpeed(0);
    simulateTick(5000);
    clock.setSpeed(1);
    EXPECT_EQ(1, simulateTick(1000));
}

// 14/17/18. 冻结恢复截断 + 余量丢弃
TEST_F(PhaseClockTest, Freeze20sCappedAtScaledMaxPhases) {
    clock.setSpeed(2);
    clock.start();
    EXPECT_EQ(system::kMaxPhasesPerTick * 2, simulateTick(20'000));
}

TEST_F(PhaseClockTest, Freeze60sCappedTo3) {
    EXPECT_EQ(system::kMaxPhasesPerTick, simulateTick(60'000));
}

TEST_F(PhaseClockTest, CatchUpCapDiscardsRemainder) {
    clock.setSpeed(2);
    EXPECT_EQ(system::kMaxPhasesPerTick * 2, simulateTick(10'000));
    EXPECT_EQ(0, simulateTick(100));
}

// 19. 未触发上限时余量保留
TEST_F(PhaseClockTest, UnderCapPreservesRemainder) {
    EXPECT_EQ(3, simulateTick(7000));
    EXPECT_EQ(1, simulateTick(1000));
}

// 15. forceConsumeOnePhase 正确扣除
TEST_F(PhaseClockTest, ForceConsumeOnePhaseDeducts) {
    clock.start();
    simulateTick(2500);
    EXPECT_EQ(1500, clock.remainingPhaseMs());

    clock.forceConsumeOnePhase();
    EXPECT_EQ(system::kMsPerPhase1x, clock.remainingPhaseMs());
}

// 20/21/22. accumulatedGameMs 暴露语义
TEST_F(PhaseClockTest, AccumulatedGameMsGrowsWhileRunning) {
    clock.start();
    EXPECT_EQ(0, clock.accumulatedGameMs());
    simulateTick(500);
    EXPECT_EQ(500, clock.accumulatedGameMs());
    simulateTick(500);
    EXPECT_EQ(1000, clock.accumulatedGameMs());
}

TEST_F(PhaseClockTest, AccumulatedGameMsFrozenAtSpeedZero) {
    clock.setSpeed(0);
    clock.start();
    simulateTick(5000);
    EXPECT_EQ(0, clock.accumulatedGameMs());
}

TEST_F(PhaseClockTest, AccumulatedGameMsWrapsAfterPhaseConsumption) {
    clock.start();
    simulateTick(2000);
    EXPECT_EQ(0, clock.accumulatedGameMs());
    simulateTick(3000);
    EXPECT_EQ(1000, clock.accumulatedGameMs());
}

// 23. nowMs 跟随时钟源
TEST_F(PhaseClockTest, NowMsTracksTimeSource) {
    const int64_t before = clock.nowMs();
    fakeTime.advanceMs(1234);
    EXPECT_EQ(before + 1234, clock.nowMs());
}

// refundPhases：整批回滚归还（整批回滚语义）
TEST_F(PhaseClockTest, RefundPhasesRestoresAccumulation) {
    clock.start();
    EXPECT_EQ(1, simulateTick(2000));
    EXPECT_EQ(0, clock.accumulatedGameMs());
    clock.refundPhases(1);
    EXPECT_EQ(system::kMsPerPhase1x, clock.accumulatedGameMs());
    // 下个 tick 重新推进（累积消费模式无自动追补 → 归还后自然推进）
    EXPECT_EQ(1, simulateTick(100));
}

// consumeDeadTime：阻塞期间不产生游戏时间
TEST_F(PhaseClockTest, ConsumeDeadTimeSkipsAccumulation) {
    clock.start();
    clock.consumeDeadTime();
    fakeTime.advanceMs(3000);
    clock.consumeDeadTime();
    fakeTime.advanceMs(100);
    EXPECT_EQ(0, clock.tick());  // 3000ms 已作死区消费，仅 100ms 累积
}

// resetForTest（测试隔离专用）：速度/累积/墙钟基准全部回到初始
TEST_F(PhaseClockTest, ResetForTestRestoresInitialState) {
    simulateTick(1000);          // 累积 1000ms
    clock.setSpeed(2);           // 速度 2
    clock.resetForTest();
    EXPECT_EQ(1, clock.speed());            // 速度回 1
    EXPECT_EQ(0, clock.accumulatedGameMs());  // 累积清零
    EXPECT_EQ(system::kMsPerPhase1x, clock.msPerPhase());
    // 墙钟基准归零：推进 2000ms → 恰好 1 旬（不补旧基准）
    fakeTime.setNowMs(2000);
    EXPECT_EQ(1, clock.tick());
}

// ============================================================
// EngineLoop 帧迭代（gameLoopIteration 判据）
// ============================================================

class EngineLoopTest : public ::testing::Test {
protected:
    FixedMonotonicClock fakeTime;
    EngineLoop loop;

    void SetUp() override {
        loop.setMonotonicClock(&fakeTime);
        loop.start();
    }
};

// 首帧 delta=0（lastFrameNs 哨兵）→ 0 tick
TEST_F(EngineLoopTest, FirstFrameHasZeroDelta) {
    const LoopFramePlan plan = loop.iterate(false, false);
    EXPECT_FALSE(plan.paused);
    EXPECT_EQ(0, plan.tickCount);
    EXPECT_EQ(0, plan.frameDeltaNs);
}

// 100ms 帧 → 恰 1 个逻辑 tick（时间不足一旬 → phases=0）
TEST_F(EngineLoopTest, Frame100msRunsOneLogicTick) {
    loop.iterate(false, false);
    fakeTime.advanceMs(100);
    const LoopFramePlan plan = loop.iterate(false, false);
    EXPECT_EQ(1, plan.tickCount);
    EXPECT_EQ(1, plan.tickKind[0]);
    EXPECT_EQ(0, plan.tickPhases[0]);  // 100ms < 2000ms
    EXPECT_EQ(1, plan.tickTotal);
    EXPECT_NEAR(0.f, plan.alpha, 1e-6f);
}

// 2000ms 一帧：帧累积钳制 500ms → 5 tick；首 tick 消费全部墙钟 → 1 旬
TEST_F(EngineLoopTest, Frame2000msClampedToFiveSteps) {
    loop.iterate(false, false);
    fakeTime.advanceMs(2000);
    const LoopFramePlan plan = loop.iterate(false, false);
    EXPECT_EQ(5, plan.tickCount);
    EXPECT_EQ(1, plan.tickPhases[0]);  // 首 tick 消费 2000ms 墙钟 → 1 旬
    for (int i = 1; i < 5; ++i) {
        EXPECT_EQ(0, plan.tickPhases[i]) << "后续 tick 墙钟 delta=0";
    }
    EXPECT_EQ(system::kMaxAccumulatorNs, plan.frameDeltaNs);
}

// 暂停/加载分支：consumeDeadTime + accumulator 清零（handlePausedIteration）
TEST_F(EngineLoopTest, PausedBranchConsumesDeadTimeAndClearsAccumulator) {
    loop.iterate(false, false);
    fakeTime.advanceMs(300);
    const LoopFramePlan plan = loop.iterate(true, false);
    EXPECT_TRUE(plan.paused);
    EXPECT_EQ(0, plan.tickCount);
    // 恢复后下一帧不产生虚高 delta（死区已消费）
    fakeTime.advanceMs(100);
    const LoopFramePlan resumed = loop.iterate(false, false);
    EXPECT_EQ(1, resumed.tickCount);
    EXPECT_EQ(0, resumed.tickPhases[0]);
}

// isSaving 跳过 tick（skipTickIfNeeded）：不推进计数、消费死区
TEST_F(EngineLoopTest, SavingSkipsTicksWithoutConsumingPhases) {
    loop.iterate(false, false);
    fakeTime.advanceMs(500);  // 5 步满额
    const LoopFramePlan plan = loop.iterate(false, true);
    EXPECT_EQ(5, plan.tickCount);
    for (int i = 0; i < 5; ++i) {
        EXPECT_EQ(0, plan.tickKind[i]);
        EXPECT_EQ(0, plan.tickPhases[i]);
    }
    EXPECT_EQ(0, plan.tickTotal);  // tick 计数不推进
    // 死区已消费：恢复后的 tick 不补 500ms（100ms → 不足一旬）
    fakeTime.advanceMs(100);
    const LoopFramePlan resumed = loop.iterate(false, false);
    EXPECT_EQ(1, resumed.tickCount);
    EXPECT_EQ(0, resumed.tickPhases[0]);
    EXPECT_EQ(1, resumed.tickTotal);
}

// alpha 插值因子 = accumulator/LOGIC_DT（0..1）
TEST_F(EngineLoopTest, AlphaIsAccumulatorRatio) {
    loop.iterate(false, false);
    fakeTime.advanceMs(150);  // 1 tick 消耗 100ms，余 50ms
    const LoopFramePlan plan = loop.iterate(false, false);
    EXPECT_EQ(1, plan.tickCount);
    EXPECT_NEAR(0.5f, plan.alpha, 1e-6f);
}

// 输入端口：用户活跃通知维护 idleNs（未活跃 = -1）
TEST_F(EngineLoopTest, UserActivityNotifyMaintainsIdleNs) {
    LoopFramePlan plan = loop.iterate(false, false);
    EXPECT_EQ(-1, plan.idleNs);

    fakeTime.advanceMs(1000);
    loop.notifyUserActivity();
    fakeTime.advanceMs(2000);
    plan = loop.iterate(false, false);
    EXPECT_EQ(2'000'000'000, plan.idleNs);  // 2000ms → ns
}

// 心跳：每次迭代更新 lastLoopActivityMs（含暂停分支）
TEST_F(EngineLoopTest, HeartbeatUpdatesOnEveryIteration) {
    loop.iterate(false, false);
    EXPECT_EQ(0, loop.lastLoopActivityMs());
    fakeTime.advanceMs(120);
    loop.iterate(true, false);
    EXPECT_EQ(120, loop.lastLoopActivityMs());
}

// 循环重启（紧急重启换线程）：帧状态清零
TEST_F(EngineLoopTest, LoopRestartClearsFrameState) {
    loop.iterate(false, false);
    fakeTime.advanceMs(300);
    loop.iterate(false, false);
    loop.onLoopRestart();
    const LoopFramePlan plan = loop.iterate(false, false);
    EXPECT_EQ(0, plan.frameDeltaNs);
    EXPECT_EQ(0, plan.tickCount);
}

// owner 重锚标志：紧急重启（onLoopRestart 换线程）置位，
// 桥层消费一次即清除——新驱动线程首个 nativeLoopFrame 完成重锚
TEST_F(EngineLoopTest, LoopRestartArmsOwnerRebaseConsumedOnce) {
    EXPECT_FALSE(loop.consumeOwnerRebasePending());
    loop.onLoopRestart();
    EXPECT_TRUE(loop.consumeOwnerRebasePending());
    EXPECT_FALSE(loop.consumeOwnerRebasePending());
}

// 正常启动（start，驱动线程不变）不置位重锚标志
TEST_F(EngineLoopTest, StartDoesNotArmOwnerRebase) {
    loop.onLoopRestart();        // 先置位
    EXPECT_TRUE(loop.consumeOwnerRebasePending());  // 消费
    loop.start();                // 正常启动不得重新置位
    EXPECT_FALSE(loop.consumeOwnerRebasePending());
}

// resetForTest（测试隔离）清除重锚标志
TEST_F(EngineLoopTest, ResetForTestClearsOwnerRebase) {
    loop.onLoopRestart();
    loop.resetForTest();
    EXPECT_FALSE(loop.consumeOwnerRebasePending());
}

// resetForTest（测试隔离专用）：tick 计数/速度/累积/帧状态/活跃基准全部归零。
// start() 保留 tickCount（生产语义：跨循环重启保留），resetForTest 才全清
TEST_F(EngineLoopTest, ResetForTestClearsEveryState) {
    loop.time().setSpeed(2);
    loop.iterate(false, false);
    fakeTime.advanceMs(500);
    loop.iterate(false, false);          // 5 tick，tickTotal=5
    fakeTime.advanceMs(1000);
    loop.notifyUserActivity();          // 活跃基准已设

    loop.resetForTest();
    EXPECT_EQ(0, loop.tickCount());
    EXPECT_EQ(1, loop.time().speed());          // 速度回 1
    EXPECT_EQ(0, loop.time().accumulatedGameMs());
    // 重置后首帧：delta=0、idleNs=-1（活跃基准已清）
    const LoopFramePlan plan = loop.iterate(false, false);
    EXPECT_EQ(0, plan.frameDeltaNs);
    EXPECT_EQ(0, plan.tickCount);
    EXPECT_EQ(-1, plan.idleNs);
}

// 时间状态机经 EngineLoop::time() 通道（setSpeed/refund；refund 按当前速度
// 的 msPerPhase 归还——Kotlin refundPhases 同语义）
TEST_F(EngineLoopTest, TimeStateMachineAccessible) {
    loop.time().setSpeed(2);
    EXPECT_EQ(2, loop.time().speed());
    loop.time().refundPhases(1);
    EXPECT_EQ(system::kMsPerPhase1x / 2, loop.time().accumulatedGameMs());
}

// 平台端口默认实现：SteadyMonotonicClock 单调可用
TEST(PlatformPortTest, SteadyMonotonicClockIsUsable) {
    SteadyMonotonicClock clock;
    const int64_t a = clock.nowMs();
    EXPECT_GE(a, 0);
    EXPECT_GE(clock.nowMs(), a);
}

TEST(PlatformPortTest, SettableProvidersRoundTrip) {
    SettableThermalStatusProvider thermal;
    EXPECT_EQ(ThermalState::kNone, thermal.currentState());
    thermal.set(ThermalState::kSevere);
    EXPECT_EQ(ThermalState::kSevere, thermal.currentState());

    SettableBatteryStatusProvider battery;
    battery.set(true, false, 45, -2.f);
    const BatteryStatus s = battery.current();
    EXPECT_TRUE(s.isLowBattery);
    EXPECT_FALSE(s.isPowerSaveMode);
    EXPECT_EQ(45, s.fpsCap);
    EXPECT_NEAR(-2.f, s.thermalThresholdOffsetC, 1e-3f);
}

}  // namespace
}  // namespace gamecore
