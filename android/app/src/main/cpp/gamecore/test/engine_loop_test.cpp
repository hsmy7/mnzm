#include <gtest/gtest.h>

#include "gamecore/core/platform.h"
#include "gamecore/system/engine_loop.h"

namespace gamecore {
namespace {

using system::EngineLoop;
using system::LoopFramePlan;
using system::PhaseClock;

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

// 7/8. phaseProgress / remainingPhaseMs
TEST_F(PhaseClockTest, PhaseProgressBoundsAndRemaining) {
    clock.start();
    EXPECT_NEAR(0.f, clock.phaseProgress(), 0.01f);
    EXPECT_EQ(system::kMsPerPhase, clock.remainingPhaseMs());

    simulateTick(1000);
    EXPECT_GE(clock.phaseProgress(), 0.f);
    EXPECT_LE(clock.phaseProgress(), 1.f);
    EXPECT_EQ(1000, clock.remainingPhaseMs());
}

// 11. 一次 tick 内累积多旬 → 4 旬超上限截断为 3
TEST_F(PhaseClockTest, MultiPhaseInOneTickCappedAt3) {
    EXPECT_EQ(system::kMaxPhasesPerTick, simulateTick(8000));
}

// 14/17/18. 冻结恢复截断 + 余量丢弃
TEST_F(PhaseClockTest, Freeze60sCappedTo3) {
    EXPECT_EQ(system::kMaxPhasesPerTick, simulateTick(60'000));
}

TEST_F(PhaseClockTest, CatchUpCapDiscardsRemainder) {
    EXPECT_EQ(system::kMaxPhasesPerTick, simulateTick(10'000));
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
    EXPECT_EQ(system::kMsPerPhase, clock.remainingPhaseMs());
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
    EXPECT_EQ(system::kMsPerPhase, clock.accumulatedGameMs());
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

// resetForTest（测试隔离专用）：累积/墙钟基准全部回到初始
TEST_F(PhaseClockTest, ResetForTestRestoresInitialState) {
    simulateTick(1000);          // 累积 1000ms
    clock.resetForTest();
    EXPECT_EQ(0, clock.accumulatedGameMs());  // 累积清零
    EXPECT_EQ(system::kMsPerPhase, clock.msPerPhase());
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

// resetForTest（测试隔离专用）：tick 计数/累积/帧状态/活跃基准全部归零。
// start() 保留 tickCount（生产语义：跨循环重启保留），resetForTest 才全清
TEST_F(EngineLoopTest, ResetForTestClearsEveryState) {
    loop.iterate(false, false);
    fakeTime.advanceMs(500);
    loop.iterate(false, false);          // 5 tick，tickTotal=5
    fakeTime.advanceMs(1000);
    loop.notifyUserActivity();          // 活跃基准已设

    loop.resetForTest();
    EXPECT_EQ(0, loop.tickCount());
    EXPECT_EQ(0, loop.time().accumulatedGameMs());
    // 重置后首帧：delta=0、idleNs=-1（活跃基准已清）
    const LoopFramePlan plan = loop.iterate(false, false);
    EXPECT_EQ(0, plan.frameDeltaNs);
    EXPECT_EQ(0, plan.tickCount);
    EXPECT_EQ(-1, plan.idleNs);
}

// 时间状态机经 EngineLoop::time() 通道（refund 按 msPerPhase 归还——
// Kotlin refundPhases 同语义）
TEST_F(EngineLoopTest, TimeStateMachineAccessible) {
    loop.time().refundPhases(1);
    EXPECT_EQ(system::kMsPerPhase, loop.time().accumulatedGameMs());
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

// ============================================================
// 未截断权威时间轴（结算改造 2026-09-27 B2，INV-2/INV-3）
//
// 对抗性审查要点（方案 §5.4-1/2）：同一段现实时间，在 30fps/60fps/120fps/
// 卡顿（单帧钳 5 步）下，权威轴累积量与判定窗口数必须一致（遥缴求和，
// 与分帧方式无关）；phaseCap 丢弃余量行为不得施加于权威轴。
// ============================================================

/// 按 [chunkMs] 分帧推进 totalMs 现实时间（首帧 delta=0 哨兵帧已含）
static int64_t runWallTime(EngineLoop& loop, FixedMonotonicClock& fakeTime,
                           int64_t totalMs, int64_t chunkMs) {
    int64_t fed = 0;
    int64_t lastElapsed = 0;
    loop.iterate(false, false);   // 哨兵帧
    while (fed < totalMs) {
        const int64_t step = std::min(chunkMs, totalMs - fed);
        fakeTime.advanceMs(step);
        fed += step;
        lastElapsed = loop.iterate(false, false).elapsedGameMs;
    }
    return lastElapsed;
}

TEST_F(EngineLoopTest, ElapsedGameMsFrameRateInvariance) {
    const int64_t total = 5000;
    // 120fps≈8ms / 60fps≈16ms / 30fps≈33ms / 常规 100ms / 卡顿 600ms（>500ms 钳制）
    // start() 重置权威轴与帧状态（时钟基准随当前 fakeTime 重锚）——各档位独立测量
    loop.start();
    const int64_t elapsed100 = runWallTime(loop, fakeTime, total, 100);
    loop.start();
    const int64_t elapsed16 = runWallTime(loop, fakeTime, total, 16);
    loop.start();
    const int64_t elapsed33 = runWallTime(loop, fakeTime, total, 33);
    loop.start();
    const int64_t elapsed600 = runWallTime(loop, fakeTime, total, 600);
    EXPECT_EQ(total, elapsed100);
    EXPECT_EQ(total, elapsed16);
    EXPECT_EQ(total, elapsed33);
    EXPECT_EQ(total, elapsed600);   // INV-2：500ms 帧钳制不截断权威轴
}

TEST_F(EngineLoopTest, ElapsedDeadZoneSkipsAccrual) {
    loop.iterate(false, false);
    // 暂停分支：不累积
    fakeTime.advanceMs(3000);
    const LoopFramePlan paused = loop.iterate(true, false);
    EXPECT_EQ(0, paused.elapsedGameMs);
    // isSaving 帧：死区不累积
    fakeTime.advanceMs(2000);
    const LoopFramePlan saving = loop.iterate(false, true);
    EXPECT_EQ(0, saving.elapsedGameMs);
    // 恢复 awake：正常累积
    fakeTime.advanceMs(2000);
    EXPECT_EQ(2000, loop.iterate(false, false).elapsedGameMs);
}

TEST_F(EngineLoopTest, ElapsedGameMsNotCappedByPhaseCap) {
    // 单帧 60s：旬推进被 cap 丢弃，但权威轴必须全额累积（INV-2 反证）
    loop.iterate(false, false);
    fakeTime.advanceMs(60'000);
    const LoopFramePlan plan = loop.iterate(false, false);
    EXPECT_EQ(system::kMaxPhasesPerTick, plan.tickPhases[0]);
    EXPECT_EQ(60'000, plan.elapsedGameMs);
}

TEST(PhaseWindowCountTest, IntegerFloorOfElapsedOverPhaseLength) {
    using system::PhaseClock;
    EXPECT_EQ(0, PhaseClock::phaseWindowCount(0));
    EXPECT_EQ(0, PhaseClock::phaseWindowCount(1999));
    EXPECT_EQ(1, PhaseClock::phaseWindowCount(2000));
    EXPECT_EQ(1, PhaseClock::phaseWindowCount(3999));
    EXPECT_EQ(36, PhaseClock::phaseWindowCount(72'000));   // 1 年
    EXPECT_EQ(0, PhaseClock::phaseWindowCount(-1));        // 防御：负输入按 0
}

}  // namespace
}  // namespace gamecore
