#include <gtest/gtest.h>

#include "gamecore/system/watchdog.h"

namespace gamecore {
namespace {

using system::ProgressMonitor;
using system::ProgressSnapshot;
using system::StallVerdict;

// ============================================================
// 看门狗统一判据测试（判据迁 C++）
// 用例与 Kotlin GameTimeProgressMonitorTest 全分支矩阵逐条对齐
//（双端锚定判定语义；历史防御机制自身失效 3 次的教训——全分支覆盖）。
//
// snapshot 参数序：tickCount, totalPhases, accumulatedGameMs, loopActive,
// isPaused, isSaving, isLoading, speed, secretRealmPauseLock,
// secretRealmPauseRenewedAtMs, loopActiveAtMs, recordedAtMs
// ============================================================

ProgressSnapshot snapshot(
    int64_t tickCount = 10, int64_t totalPhases = 100, int64_t accumulatedGameMs = 100,
    bool loopActive = true, bool isPaused = false, bool isSaving = false, bool isLoading = false,
    int speed = 1, bool secretRealmPauseLock = false,
    int64_t secretRealmPauseRenewedAtMs = 0, int64_t loopActiveAtMs = 0,
    int64_t recordedAtMs = 0) {
    ProgressSnapshot s;
    s.tickCount = tickCount;
    s.totalPhases = totalPhases;
    s.accumulatedGameMs = accumulatedGameMs;
    s.loopActive = loopActive;
    s.isPaused = isPaused;
    s.isSaving = isSaving;
    s.isLoading = isLoading;
    s.speed = speed;
    s.secretRealmPauseLock = secretRealmPauseLock;
    s.secretRealmPauseRenewedAtMs = secretRealmPauseRenewedAtMs;
    s.loopActiveAtMs = loopActiveAtMs;
    s.recordedAtMs = recordedAtMs;
    return s;
}

/// 基准快照便捷构造（tickCount/totalPhases/accumulatedGameMs + recordedAtMs）
ProgressSnapshot base(int64_t tick, int64_t total, int64_t acc, int64_t recordedAtMs) {
    return snapshot(tick, total, acc, true, false, false, false, 1, false, 0, 0, recordedAtMs);
}

// ── 正常推进 ──

TEST(WatchdogTest, FirstCallRecordsBaselineReturnsHealthy) {
    ProgressMonitor monitor;
    EXPECT_EQ(StallVerdict::kHealthy, monitor.evaluate(base(10, 100, 100, 1'000)));
}

TEST(WatchdogTest, TickAndWorldTimeBothProgressReturnsHealthy) {
    ProgressMonitor monitor;
    monitor.evaluate(base(10, 100, 100, 1'000));
    EXPECT_EQ(StallVerdict::kHealthy, monitor.evaluate(base(11, 100, 200, 2'000)));
}

TEST(WatchdogTest, PhaseBoundaryAccumulatedWrapsButTotalPhasesAdvanced) {
    ProgressMonitor monitor;
    monitor.evaluate(base(10, 100, 1900, 1'000));
    EXPECT_EQ(StallVerdict::kHealthy, monitor.evaluate(base(11, 101, 100, 2'000)));
}

// ── tick 停滞 ──

TEST(WatchdogTest, TickCountStalledReturnsLoopStalled) {
    ProgressMonitor monitor;
    monitor.evaluate(base(10, 100, 100, 1'000));
    EXPECT_EQ(StallVerdict::kLoopStalled,
              monitor.evaluate(snapshot(10, 100, 100, true, false, false, false, 1, false, 0,
                                        0, 30'000)));
}

TEST(WatchdogTest, LoopDeadAndNotPausedReturnsLoopStalled) {
    ProgressMonitor monitor;
    monitor.evaluate(base(10, 100, 100, 1'000));
    EXPECT_EQ(StallVerdict::kLoopStalled,
              monitor.evaluate(snapshot(10, 100, 100, false, false, false, false, 1, false, 0,
                                        0, 2'000)));
}

// ── 假运行 ──

TEST(WatchdogTest, FakeRunWithinWindowHealthyThenBeyondWindowDetected) {
    ProgressMonitor monitor;
    monitor.evaluate(base(10, 100, 100, 1'000));
    EXPECT_EQ(StallVerdict::kHealthy, monitor.evaluate(base(200, 100, 100, 51'000)));
    EXPECT_EQ(StallVerdict::kFakeRunDetected,
              monitor.evaluate(base(300, 100, 100, 200'000)));
}

TEST(WatchdogTest, SpeedZeroNotPausedFakeRunImmediately) {
    ProgressMonitor monitor;
    monitor.evaluate(snapshot(10, 100, 100, true, false, false, false, 0, false, 0, 0, 1'000));
    EXPECT_EQ(StallVerdict::kFakeRunDetected,
              monitor.evaluate(snapshot(11, 100, 100, true, false, false, false, 0, false, 0,
                                        0, 2'000)));
}

TEST(WatchdogTest, FreezeRecoveryRefreshesBaseline) {
    ProgressMonitor monitor;
    monitor.evaluate(base(10, 100, 100, 1'000));
    monitor.evaluate(base(200, 100, 100, 51'000));
    EXPECT_EQ(StallVerdict::kHealthy, monitor.evaluate(base(300, 101, 200, 60'000)));
}

// ── 用户主动暂停（a63338f3 教训：永不自动恢复） ──

TEST(WatchdogTest, UserPausedWithoutSecretRealmLockReturnsPausedByOwner) {
    ProgressMonitor monitor;
    monitor.evaluate(base(10, 100, 100, 1'000));
    EXPECT_EQ(StallVerdict::kPausedByOwner,
              monitor.evaluate(snapshot(10, 100, 100, true, true, false, false, 1, false, 0,
                                        0, 30'000)));
}

// ── 秘境暂停租约 ──

TEST(WatchdogTest, SecretRealmPausedWithValidLeaseReturnsPausedByOwner) {
    ProgressMonitor monitor;
    monitor.evaluate(base(10, 100, 100, 5'000));
    EXPECT_EQ(StallVerdict::kPausedByOwner,
              monitor.evaluate(snapshot(10, 100, 100, true, true, false, false, 1, true,
                                        10'000, 30'000, 30'000)));
}

TEST(WatchdogTest, SecretRealmLeaseExpiredReturnsStalePauseDetected) {
    ProgressMonitor monitor;
    monitor.evaluate(base(10, 100, 100, 5'000));
    EXPECT_EQ(StallVerdict::kStalePauseDetected,
              monitor.evaluate(snapshot(10, 100, 100, true, true, false, false, 1, true,
                                        10'000, 70'000, 70'000)));
}

TEST(WatchdogTest, SecretRealmLockNeverRenewedReturnsStalePauseDetected) {
    ProgressMonitor monitor;
    monitor.evaluate(base(10, 100, 100, 5'000));
    EXPECT_EQ(StallVerdict::kStalePauseDetected,
              monitor.evaluate(snapshot(10, 100, 100, true, true, false, false, 1, true,
                                        0, 50'000, 50'000)));
}

// ── 保存/加载豁免 ──

TEST(WatchdogTest, SavingWithActiveLoopReturnsHealthy) {
    ProgressMonitor monitor;
    monitor.evaluate(snapshot(10, 100, 100, true, false, false, false, 1, false, 0, 5'000,
                              5'000));
    EXPECT_EQ(StallVerdict::kHealthy,
              monitor.evaluate(snapshot(11, 100, 100, true, false, true, false, 1, false, 0,
                                        19'000, 20'000)));
}

TEST(WatchdogTest, SavingButLoopAlsoStalledReturnsLoopStalled) {
    ProgressMonitor monitor;
    monitor.evaluate(snapshot(10, 100, 100, true, false, false, false, 1, false, 0, 5'000,
                              5'000));
    EXPECT_EQ(StallVerdict::kLoopStalled,
              monitor.evaluate(snapshot(11, 100, 100, true, false, true, false, 1, false, 0,
                                        5'000, 40'000)));
}

TEST(WatchdogTest, LoadingWithActiveLoopReturnsHealthy) {
    ProgressMonitor monitor;
    monitor.evaluate(snapshot(10, 100, 100, true, false, false, false, 1, false, 0, 5'000,
                              5'000));
    EXPECT_EQ(StallVerdict::kHealthy,
              monitor.evaluate(snapshot(12, 100, 100, true, false, false, true, 1, false, 0,
                                        19'000, 20'000)));
}

// ── 边界：恰在窗口边缘（严格大于语义） ──

TEST(WatchdogTest, FakeRunExactlyBeyondWindowBoundaryDetected) {
    ProgressMonitor monitor;
    monitor.evaluate(base(10, 100, 100, 1'000));
    monitor.evaluate(base(100, 100, 100, 2'000));
    EXPECT_EQ(StallVerdict::kFakeRunDetected,
              monitor.evaluate(base(200, 100, 100, 92'001)));
}

TEST(WatchdogTest, PauseLeaseExactlyAtTtlBoundaryStillValid) {
    ProgressMonitor monitor;
    monitor.evaluate(base(10, 100, 100, 5'000));
    EXPECT_EQ(StallVerdict::kPausedByOwner,
              monitor.evaluate(snapshot(10, 100, 100, true, true, false, false, 1, true,
                                        10'000, 0, 55'000)));
}

TEST(WatchdogTest, CustomMonitorParametersRespected) {
    ProgressMonitor monitor(10'000, 20'000);
    monitor.evaluate(base(10, 100, 100, 1'000));
    monitor.evaluate(base(11, 100, 100, 10'000));
    EXPECT_EQ(StallVerdict::kFakeRunDetected,
              monitor.evaluate(base(12, 100, 100, 35'000)));
}

// ── 判定路径回归 ──

TEST(WatchdogTest, S5FrozenWorldWithOscillatingAccumulatedStillDetected) {
    ProgressMonitor monitor;
    monitor.evaluate(base(10, 100, 0, 1'000));
    monitor.evaluate(base(30, 100, 2000, 10'000));
    EXPECT_EQ(StallVerdict::kHealthy, monitor.evaluate(base(50, 100, 0, 20'000)));
    EXPECT_EQ(StallVerdict::kFakeRunDetected,
              monitor.evaluate(base(200, 100, 2000, 200'000)));
}

TEST(WatchdogTest, S4TickStalledButHeartbeatFreshReturnsHealthy) {
    ProgressMonitor monitor;
    monitor.evaluate(snapshot(10, 100, 100, true, false, false, false, 1, false, 0, 1'000,
                              1'000));
    EXPECT_EQ(StallVerdict::kHealthy,
              monitor.evaluate(snapshot(10, 100, 100, true, false, false, false, 1, false, 0,
                                        4'000, 5'000)));
}

TEST(WatchdogTest, V1TickStalledAndHeartbeatStaleReturnsLoopStalled) {
    ProgressMonitor monitor;
    monitor.evaluate(snapshot(10, 100, 100, true, false, false, false, 1, false, 0, 1'000,
                              1'000));
    EXPECT_EQ(StallVerdict::kLoopStalled,
              monitor.evaluate(snapshot(10, 100, 100, true, false, false, false, 1, false, 0,
                                        1'000, 30'000)));
}

TEST(WatchdogTest, S1SavingWithLoopStoppedAndPausedReturnsHealthy) {
    ProgressMonitor monitor;
    monitor.evaluate(snapshot(10, 100, 100, true, false, false, false, 1, false, 0, 1'000,
                              1'000));
    EXPECT_EQ(StallVerdict::kHealthy,
              monitor.evaluate(snapshot(11, 100, 100, false, true, true, false, 1, false, 0,
                                        1'000, 30'000)));
}

TEST(WatchdogTest, V6SpeedZeroDetectedOnFirstEvaluation) {
    ProgressMonitor monitor;
    EXPECT_EQ(StallVerdict::kFakeRunDetected,
              monitor.evaluate(snapshot(10, 100, 100, true, false, false, false, 0, false, 0,
                                        0, 1'000)));
}

TEST(WatchdogTest, F2LeaseExpiredWithStalledLoopReturnsLoopStalled) {
    ProgressMonitor monitor;
    monitor.evaluate(snapshot(10, 100, 100, true, false, false, false, 1, false, 0, 1'000,
                              1'000));
    EXPECT_EQ(StallVerdict::kLoopStalled,
              monitor.evaluate(snapshot(10, 100, 100, true, true, false, false, 1, true,
                                        1'000, 1'000, 60'000)));
}

// 阈值常量与 Kotlin companion 逐位一致
TEST(WatchdogTest, ThresholdConstantsMatchKotlin) {
    EXPECT_EQ(45'000, system::kStalePauseTtlMs);
    EXPECT_EQ(90'000, system::kFakeRunWindowMs);
    EXPECT_EQ(20'000, system::kLoopActivityStaleMs);
}

}  // namespace
}  // namespace gamecore
