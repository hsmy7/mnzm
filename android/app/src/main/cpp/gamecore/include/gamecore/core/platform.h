#pragma once

#include <atomic>
#include <cstdint>
#include <string>

// ============================================================
// 平台能力端口（计划 v2 阶段 5——Clock/Logger 注入先例扩展）
//
// 阶段 5「游戏循环入 C++」需要引擎循环消费平台能力。参照 core/clock.h
// 与 core/logger.h 的注入先例：game-core 本体只定义纯虚接口 + 兜底/测试
// 实现，真实平台实现由桥层注入（Android：GameCoreBridge.cpp；对拍/桌面：
// Fixed/Null 实现）。
//
// 端口清单（docs/cpp-engine.md §7 阶段 5）：
//   - MonotonicClock   单调时钟（elapsedRealtime 语义；引擎循环时间源）
//   - TelemetrySink    遥测事件（循环/看门狗事件上报通道）
//   - ThermalStatusProvider  热控状态（帧率降级输入；判据消费者阶段 6+ 迁入）
//   - BatteryStatusProvider  电量状态（fpsCap/热阈偏移；同上）
//   - Input            用户活跃通知（EngineLoop::notifyUserActivity 显式调用，
//                      非 pull 接口——输入事件天然由平台层推送）
//
// 约束：game-core 内禁止直接依赖平台 API（Android/iOS），一律经本文件
// 端口注入；测试注入 Fixed/Null 实现保证确定性。
// ============================================================
namespace gamecore {

// ── 单调时钟（elapsedRealtime 语义） ─────────────────────────────

/// 单调时钟端口——引擎循环唯一时间源（对应 Android SystemClock.elapsedRealtime /
/// Kotlin TimeSource）。单调递增、不受墙上时钟（NTP/手动改时间）影响。
class MonotonicClock {
public:
    virtual ~MonotonicClock() = default;

    /// 单调递增毫秒（elapsedRealtime 语义）
    virtual int64_t nowMs() = 0;
};

/// 默认实现：chrono steady_clock（兜底；Android 桥层注入 CLOCK_BOOTTIME 精确实现）
class SteadyMonotonicClock final : public MonotonicClock {
public:
    int64_t nowMs() override;
};

/// 测试/对拍用固定单调时钟（可手动推进）
class FixedMonotonicClock final : public MonotonicClock {
public:
    explicit FixedMonotonicClock(int64_t fixedMs = 0) : now_(fixedMs) {}

    void setNowMs(int64_t ms) { now_ = ms; }
    /// 按测试脚本推进时间
    void advanceMs(int64_t delta) { now_ += delta; }

    int64_t nowMs() override { return now_; }

private:
    int64_t now_ = 0;
};

// ── 遥测事件 ──────────────────────────────────────────────────────

/// 遥测端口——引擎循环/看门狗事件上报（对应 Kotlin GameMonitorManager/Bugly 通道）
class TelemetrySink {
public:
    virtual ~TelemetrySink() = default;

    /// 上报一条遥测事件（name 事件名，propsJson 事件属性 JSON）
    virtual void event(const std::string& name, const std::string& propsJson) = 0;
};

/// 静默实现（默认/测试）
class NullTelemetrySink final : public TelemetrySink {
public:
    void event(const std::string&, const std::string&) override {}
};

// ── 热控状态（Kotlin core/perf/ThermalMonitor 对应端口） ──────────

/// 热状态档位（镜像 Kotlin ThermalState；数值即 JNI 传输码）
enum class ThermalState : int {
    kNone = 0,
    kLight = 1,
    kModerate = 2,
    kSevere = 3,
    kCritical = 4,
    kEmergency = 5,
};

/// 热控状态端口——Android 侧 PowerManager 热状态（桥层 Settable 实现由
/// Kotlin ThermalMonitor 轮询推送）。阶段 5 注入并记录遥测；帧率降级判据
/// 消费者（ThermalController 语义）随阶段 6 渲染统一迁入。
class ThermalStatusProvider {
public:
    virtual ~ThermalStatusProvider() = default;

    virtual ThermalState currentState() = 0;
};

/// 可设值实现（桥层用：Kotlin 平台层推送，引擎线程/推送线程并发安全）
class SettableThermalStatusProvider final : public ThermalStatusProvider {
public:
    void set(ThermalState state) { state_.store(static_cast<int>(state)); }
    ThermalState currentState() override {
        return static_cast<ThermalState>(state_.load(std::memory_order_relaxed));
    }

private:
    std::atomic<int> state_{static_cast<int>(ThermalState::kNone)};
};

// ── 电量状态（Kotlin core/thermal/BatteryStatusProvider 镜像端口） ─

/// 电量状态快照（镜像 Kotlin BatteryStatusProvider 四字段）
struct BatteryStatus {
    bool isLowBattery = false;            // ≤20% 且未充电
    bool isPowerSaveMode = false;         // 系统省电模式
    int fpsCap = 60;                      // 帧率上限（低电量 45 / 省电 30 / 正常 60）
    float thermalThresholdOffsetC = 0.f;  // 热控阈值偏移（低电量 -2 提前降载）
};

/// 电量状态端口——桥层 Settable 实现由 Kotlin BatteryAwareController 推送
class BatteryStatusProvider {
public:
    virtual ~BatteryStatusProvider() = default;

    virtual BatteryStatus current() = 0;
};

/// 可设值实现（桥层用）
class SettableBatteryStatusProvider final : public BatteryStatusProvider {
public:
    void set(bool lowBattery, bool powerSave, int fpsCap, float offsetC) {
        low_.store(lowBattery, std::memory_order_relaxed);
        save_.store(powerSave, std::memory_order_relaxed);
        fpsCap_.store(fpsCap, std::memory_order_relaxed);
        offsetMilliC_.store(static_cast<int>(offsetC * 1000.0f), std::memory_order_relaxed);
    }

    BatteryStatus current() override {
        BatteryStatus s;
        s.isLowBattery = low_.load(std::memory_order_relaxed);
        s.isPowerSaveMode = save_.load(std::memory_order_relaxed);
        s.fpsCap = fpsCap_.load(std::memory_order_relaxed);
        s.thermalThresholdOffsetC =
            static_cast<float>(offsetMilliC_.load(std::memory_order_relaxed)) / 1000.0f;
        return s;
    }

private:
    std::atomic<bool> low_{false};
    std::atomic<bool> save_{false};
    std::atomic<int> fpsCap_{60};
    std::atomic<int> offsetMilliC_{0};
};

}  // namespace gamecore
