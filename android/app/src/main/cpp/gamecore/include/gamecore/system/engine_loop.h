#pragma once

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>

#include "gamecore/core/logger.h"
#include "gamecore/core/platform.h"
#include "gamecore/system/settlement.h"

// ============================================================
// 引擎循环（游戏循环入 C++）
//
// PhaseClock —— Kotlin core/engine/system/GameTimeClock 逐位移植：
// 墙钟消费/速度/暂停/refundPhases 状态机。语义锚点（GameTimeClock.kt）：
//   - accumulatedGameMs += wallDeltaMs * speed（speed: 0/1/2）
//   - phases = accumulatedGameMs / msPerPhase（msPerPhase = 2000ms @1x / 1000 @2x）
//   - phaseCap = MAX_PHASES_PER_TICK(3) × speed：单 tick 追补上限，
//     超限**丢弃余量**（防 OEM 挂起/看门狗重启的爆炸式跳变）
//   - setSpeed 先按旧速度结算累积（切换零丢失）
//   - consumeDeadTime/forceConsumeOnePhase/refundPhases 原语义保留
//
// EngineLoop —— Kotlin GameEngineCore.gameLoopIteration 的帧迭代判据移植：
//   - deltaNs 钳制 kMaxAccumulatorNs（5 步）
//   - 暂停/加载分支：consumeDeadTime + accumulator 清零（对应 handlePausedIteration）
//   - 固定步长：accumulator >= LOGIC_DT_NS(100ms) 时每帧最多 5 个逻辑 tick
//   - isSaving 跳过 tick（对应 skipTickIfNeeded：不推进 tick 计数、消费死区）
//   - alpha 插值因子原始值（JitterSmoother 滤波留渲染侧）
//
// 线程契约：iterate/setSpeed/consumeDeadTime 等由引擎线程串行调用；
// setSpeed/accumulatedGameMs/tickCount/lastLoopActivityMs 为 atomic（看门狗
// 线程跨线程读——镜像 Kotlin @Volatile 语义）。帧等待（delay/antiFreeze
// 忙等）为平台线程机制，保留 Kotlin 驱动侧。
// ============================================================
namespace gamecore::system {

/// 逻辑步长 100ms（GameEngineCore.LOGIC_DT_NS；只控制 tick 调用频率）
constexpr int64_t kLogicDtNs = 100'000'000;
/// 帧累积上限 5 步（GameEngineCore.MAX_ACCUMULATOR_NS）
constexpr int64_t kMaxAccumulatorNs = kLogicDtNs * 5;
/// 单帧最多逻辑 tick 数（GameEngineCore.gameLoopIteration 步进上限）
constexpr int kMaxStepsPerFrame = 5;

/// 帧计划——nativeLoopFrame 返回给 Kotlin 驱动的单帧执行指令。
/// LongArray 传输协议（GameCoreBridge.kt nativeLoopFrame 注释同源）：
/// [0] paused · [1] tickCount · [2..6] tickKind(1=active/0=skipped) ·
/// [7..11] tickPhases · [12] alpha 位模式 · [13] frameDeltaNs ·
/// [14] idleNs(<0=从未活跃) · [15] tickTotal · [16] accumulatedGameMs
struct LoopFramePlan {
    /// 暂停/加载分支（本帧已 consumeDeadTime + accumulator 清零）
    bool paused = false;
    /// 本帧逻辑 tick 数（0..5）
    int tickCount = 0;
    /// 每 tick 类型：1=正常执行（推进 tick 计数）0=isSaving 跳过
    int tickKind[kMaxStepsPerFrame] = {0, 0, 0, 0, 0};
    /// 每 tick 应推进旬数（0 表示本 tick 时间不足一旬）
    int tickPhases[kMaxStepsPerFrame] = {0, 0, 0, 0, 0};
    /// 插值因子原始值 accumulator/LOGIC_DT（0..1；滤波在 Kotlin 渲染侧）
    float alpha = 0.f;
    /// 本帧实际间隔（钳制后；ADPF reportActualWorkDuration 输入）
    int64_t frameDeltaNs = 0;
    /// 距上次用户活跃纳秒（<0 = 从未活跃；输入端口 notifyUserActivity 维护）
    int64_t idleNs = -1;
    /// 累计逻辑 tick 计数（Kotlin _tickCount 镜像真相源）
    int64_t tickTotal = 0;
    /// 当前旬内累积游戏毫秒（GameTimeClock.accumulatedGameMs 镜像）
    int64_t accumulatedGameMs = 0;
};

/// 游戏时间时钟状态机（GameTimeClock 逐位移植）
class PhaseClock {
public:
    PhaseClock() = default;

    /// 注入单调时钟（null → SteadyMonotonicClock 兜底）
    void setMonotonicClock(MonotonicClock* mono) { mono_ = mono; }
    void setLogger(Logger* logger) { logger_ = logger; }

    /// 启动/重置时钟（游戏循环开始时调用）
    void start() {
        lastWallMs_ = mono()->nowMs();
        accumulatedGameMs_.store(0, std::memory_order_relaxed);
    }

    /// 切换速度：先按旧速度结算累积（切换零丢失）；0/1/2 钳制
    void setSpeed(int newSpeed) {
        const int64_t now = mono()->nowMs();
        const int old = speed_.load(std::memory_order_relaxed);
        if (old > 0) {
            accumulatedGameMs_.fetch_add((now - lastWallMs_) * old, std::memory_order_relaxed);
        }
        lastWallMs_ = now;
        const int clamped = newSpeed < 0 ? 0 : (newSpeed > 2 ? 2 : newSpeed);
        speed_.store(clamped, std::memory_order_relaxed);
    }

    int speed() const { return speed_.load(std::memory_order_relaxed); }

    /// 当前旬游戏时间毫秒（speed=0 → MAX；UI 进度显示）
    int64_t msPerPhase() const {
        switch (speed_.load(std::memory_order_relaxed)) {
            case 0: return INT64_MAX;
            case 2: return kMsPerPhase1x / 2;
            default: return kMsPerPhase1x;
        }
    }

    /// 当前旬进度 0.0~1.0
    float phaseProgress() const {
        const int s = speed();
        if (s == 0) return 0.f;
        const float denom = static_cast<float>(msPerPhase());
        if (denom <= 0.f) return 0.f;
        const float acc = static_cast<float>(accumulatedGameMs_.load(std::memory_order_relaxed));
        return std::clamp(acc / denom, 0.f, 1.f);
    }

    /// 当前旬剩余毫秒
    int64_t remainingPhaseMs() const {
        return std::max<int64_t>(0, msPerPhase() - accumulatedGameMs_.load(std::memory_order_relaxed));
    }

    /// 当旬已累积游戏毫秒（进度监控快照输入；speed>0 时单调增长）
    int64_t accumulatedGameMs() const {
        return accumulatedGameMs_.load(std::memory_order_relaxed);
    }

    /// 单调时钟当前毫秒（租约/快照基准——与内部累积同一时钟源）
    int64_t nowMs() const { return mono()->nowMs(); }

    /// 每 tick 消费墙钟 → 旬数（delta 不做单次裁剪；防爆炸跳变由
    /// phaseCap 按速度缩放承担，超限丢弃余量）
    int tick() {
        const int64_t now = mono()->nowMs();
        const int64_t rawDelta = now - lastWallMs_;
        lastWallMs_ = now;
        const int s = speed_.load(std::memory_order_relaxed);
        if (s > 0) {
            accumulatedGameMs_.fetch_add(rawDelta * s, std::memory_order_relaxed);
        }
        const int64_t perPhase = msPerPhase();
        int phases = static_cast<int>(accumulatedGameMs_.load(std::memory_order_relaxed) / perPhase);
        const int phaseCap = maxPhasesPerTick(s);  // 单一来源
        if (phases > phaseCap) {
            if (logger_) {
                logger_->log(LogLevel::kWarn, "EngineLoop",
                             "tick catch-up capped at " + std::to_string(phaseCap) +
                                 " phases, dropped " + std::to_string(phases - phaseCap));
            }
            phases = phaseCap;
            accumulatedGameMs_.store(0, std::memory_order_relaxed);
        } else if (phases > 0) {
            accumulatedGameMs_.fetch_sub(static_cast<int64_t>(phases) * perPhase,
                                         std::memory_order_relaxed);
        }
        return phases;
    }

    /// 消耗死区时间：刷新基准不累积（暂停/保存/加载阻塞期间防虚高 delta）
    void consumeDeadTime() { lastWallMs_ = mono()->nowMs(); }

    /// 强制消费 1 旬游戏时间（不推进世界时间）
    void forceConsumeOnePhase() {
        const int64_t perPhase = msPerPhase();
        const int64_t acc = accumulatedGameMs_.load(std::memory_order_relaxed);
        accumulatedGameMs_.store(std::max<int64_t>(0, acc - perPhase), std::memory_order_relaxed);
    }

    /// 归还已消费旬数（整批事务回滚时；P1-A F2 语义）
    void refundPhases(int count) {
        if (count <= 0) return;
        accumulatedGameMs_.fetch_add(static_cast<int64_t>(count) * msPerPhase(),
                                     std::memory_order_relaxed);
    }

    /// 测试隔离专用：完全重置时钟状态（速度/累积/墙钟基准）。
    /// 生产路径不调用——引擎实例生命周期内 speed/累积不重置（与 Kotlin
    /// GameTimeClock 单例一致）；仅桌面对拍桥跨用例重建基准用。
    void resetForTest() {
        lastWallMs_ = 0;
        accumulatedGameMs_.store(0, std::memory_order_relaxed);
        speed_.store(1, std::memory_order_relaxed);
    }

private:
    MonotonicClock* mono() const {
        if (mono_) return mono_;
        static SteadyMonotonicClock fallback;
        return &fallback;
    }

    MonotonicClock* mono_ = nullptr;   // 注入（不持有）
    Logger* logger_ = nullptr;         // 注入（不持有；cap 丢弃告警）
    std::atomic<int64_t> accumulatedGameMs_{0};
    std::atomic<int> speed_{1};
    int64_t lastWallMs_ = 0;           // 引擎线程独占（Kotlin 同——非 volatile）
};

/// 引擎循环——帧迭代判据真相源（AUTHORITATIVE 模式由 Kotlin 驱动线程
/// 每帧调一次 iterate，按计划执行；线程本体/delay/忙等保留平台侧）
class EngineLoop {
public:
    EngineLoop() = default;

    void setMonotonicClock(MonotonicClock* mono) { phaseClock_.setMonotonicClock(mono); }
    void setLogger(Logger* logger) { phaseClock_.setLogger(logger); }
    void setTelemetry(TelemetrySink* telemetry) { telemetry_ = telemetry; }
    void setThermalProvider(ThermalStatusProvider* thermal) { thermal_ = thermal; }

    /// 游戏时间状态机（setSpeed/refundPhases/镜像读取）
    PhaseClock& time() { return phaseClock_; }
    const PhaseClock& time() const { return phaseClock_; }

    /// 循环启动/重启（gameLoopMainLoop 入口语义）：帧累积清零、首帧 delta=0。
    /// tickCount 跨重启保留（Kotlin _tickCount 生命周期同引擎实例）。
    void start() {
        accumulatorNs_ = 0;
        lastFrameNs_ = 0;
        hasLastFrame_ = false;
        phaseClock_.start();
    }

    /// 单帧迭代（gameLoopIteration 判据移植）。
    /// pausedOrLoading：isPaused || isLoading（暂停分支）；isSaving：tick 级跳过。
    LoopFramePlan iterate(bool pausedOrLoading, bool isSaving) {
        LoopFramePlan plan;
        // 心跳：每次迭代更新（含暂停分支）——看门狗区分"正常慢保存/暂停"
        // 与"循环停滞/引擎死亡"的判据输入
        lastLoopActivityMs_.store(phaseClock_.nowMs(), std::memory_order_relaxed);

        const int64_t nowNs = phaseClock_.nowMs() * 1'000'000;
        int64_t deltaNs = 0;
        if (hasLastFrame_) {
            deltaNs = nowNs - lastFrameNs_;
        }
        hasLastFrame_ = true;
        lastFrameNs_ = nowNs;
        deltaNs = std::min(deltaNs, kMaxAccumulatorNs);
        plan.frameDeltaNs = deltaNs;
        plan.idleNs = (lastUserActivityNs_ == 0) ? -1 : (nowNs - lastUserActivityNs_);

        if (pausedOrLoading) {
            // 暂停分支（handlePausedIteration）：死区消费 + accumulator 清零
            phaseClock_.consumeDeadTime();
            accumulatorNs_ = 0;
            plan.paused = true;
            return plan;
        }

        accumulatorNs_ += deltaNs;
        int steps = 0;
        while (accumulatorNs_ >= kLogicDtNs && steps < kMaxStepsPerFrame) {
            if (isSaving) {
                // skipTickIfNeeded 语义：不推进 tick 计数、消费死区
                phaseClock_.consumeDeadTime();
                plan.tickKind[steps] = 0;
                plan.tickPhases[steps] = 0;
            } else {
                tickCount_.fetch_add(1, std::memory_order_relaxed);
                plan.tickKind[steps] = 1;
                plan.tickPhases[steps] = phaseClock_.tick();
            }
            accumulatorNs_ -= kLogicDtNs;
            ++steps;
        }
        plan.tickCount = steps;
        plan.tickTotal = tickCount_.load(std::memory_order_relaxed);
        plan.alpha = std::clamp(static_cast<float>(static_cast<double>(accumulatorNs_) /
                                                   static_cast<double>(kLogicDtNs)),
                                0.f, 1.f);
        plan.accumulatedGameMs = phaseClock_.accumulatedGameMs();
        reportThermalTelemetry();
        return plan;
    }

    /// 用户活跃通知（输入端口：Kotlin onUserActivity → JNI）
    void notifyUserActivity() { lastUserActivityNs_ = phaseClock_.nowMs() * 1'000'000; }

    /// 循环紧急重启（换线程）：帧状态清零（Kotlin LoopIterationState(0, now)）。
    /// 同时置位 owner 重锚标志（桥层守卫专用）：Kotlin 紧急重启会用
    /// 全新 GameDispatcher 线程驱动循环（recreateGameDispatcher），桥层
    /// "owner 线程"必须重锚到新驱动线程，否则 debug 守卫会把新引擎线程
    /// 误判为违规进入而 abort。
    void onLoopRestart() {
        accumulatorNs_ = 0;
        lastFrameNs_ = 0;
        hasLastFrame_ = false;
        ownerRebasePending_.store(true, std::memory_order_relaxed);
    }

    /// 消费 owner 待重锚标志（读后清除）；标志置位后的首次调用返回 true。
    /// 桥层在**新驱动线程的首个 nativeLoopFrame** 进入时调用（该入口是
    /// 循环启动的必然首站，且只在驱动线程运行）——消费后把 owner 重锚到
    /// 当前线程，再进入常规守卫校验。release 构建守卫擦除，本通道零语义。
    bool consumeOwnerRebasePending() {
        return ownerRebasePending_.exchange(false, std::memory_order_relaxed);
    }

    /// 测试隔离专用：完全重置循环状态（tick 计数/速度/累积/帧状态/活跃基准）。
    /// 生产路径不调用（引擎实例生命周期内 tickCount/speed 不重置——与 Kotlin
    /// 单例语义一致，见 [start] 注释）；仅桌面对拍桥跨 JUnit 用例重建基准用。
    void resetForTest() {
        tickCount_.store(0, std::memory_order_relaxed);
        lastLoopActivityMs_.store(0, std::memory_order_relaxed);
        accumulatorNs_ = 0;
        lastFrameNs_ = 0;
        hasLastFrame_ = false;
        lastUserActivityNs_ = 0;
        lastThermalState_ = static_cast<int>(ThermalState::kNone);
        ownerRebasePending_.store(false, std::memory_order_relaxed);
        phaseClock_.resetForTest();
    }

    /// 单调时钟毫秒（Kotlin gameClock.nowMs 对应）
    int64_t nowMs() const { return phaseClock_.nowMs(); }

    // ── 看门狗判据输入（atomic：看门狗线程跨线程读） ──
    int64_t tickCount() const { return tickCount_.load(std::memory_order_relaxed); }
    int64_t lastLoopActivityMs() const {
        return lastLoopActivityMs_.load(std::memory_order_relaxed);
    }

private:
    /// 热状态变化遥测（端口消费点：帧率降级判据留 Kotlin）
    void reportThermalTelemetry() {
        if (!telemetry_ || !thermal_) return;
        const int state = static_cast<int>(thermal_->currentState());
        if (state != lastThermalState_ && state != static_cast<int>(ThermalState::kNone)) {
            lastThermalState_ = state;
            telemetry_->event("engine_thermal_state",
                              "{\"state\":" + std::to_string(state) + "}");
        }
    }

    PhaseClock phaseClock_;
    TelemetrySink* telemetry_ = nullptr;        // 注入（不持有）
    ThermalStatusProvider* thermal_ = nullptr;  // 注入（不持有；setPlatformProviders 接线）
    std::atomic<int64_t> tickCount_{0};
    std::atomic<int64_t> lastLoopActivityMs_{0};
    int64_t accumulatorNs_ = 0;
    int64_t lastFrameNs_ = 0;
    bool hasLastFrame_ = false;
    int64_t lastUserActivityNs_ = 0;
    int lastThermalState_ = static_cast<int>(ThermalState::kNone);
    /// owner 待重锚标志（onLoopRestart 置位；桥层新驱动线程首帧消费）
    std::atomic<bool> ownerRebasePending_{false};
};

}  // namespace gamecore::system
