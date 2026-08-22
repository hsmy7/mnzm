#pragma once

#include <cstdint>

// ============================================================
// 现实时间接口（跨平台 + 可测试）
//
// 对应 Kotlin 引擎内 ~40 处 System.currentTimeMillis() 的业务语义
// （邮件过期/在线时长结算/领取冷却/一次性种子等）。
// 约束：game-core 内**禁止**直接调系统时间，一律经 Clock 注入——
//   1. 差分对拍时注入固定时钟，保证 C++ 与 Kotlin 引擎输出逐字段一致
//   2. iOS 复用（iOS 侧实现 NSDate 即可）
//   3. 单测可控
// ============================================================
namespace gamecore {

class Clock {
public:
    virtual ~Clock() = default;

    /// 现实时间戳（毫秒，对应 System.currentTimeMillis()）
    virtual int64_t nowMs() = 0;
};

/// 默认实现：真实系统时钟（Android/iOS 由桥层提供更优实现，此为兜底）
class SystemClock final : public Clock {
public:
    int64_t nowMs() override;
};

/// 测试/对拍用固定时钟（构造后恒定，可手动推进）
class FixedClock final : public Clock {
public:
    explicit FixedClock(int64_t fixedMs = 0L) : now_(fixedMs) {}

    void setNowMs(int64_t ms) { now_ = ms; }
    /// 按测试脚本推进时间
    void advanceMs(int64_t delta) { now_ += delta; }

    int64_t nowMs() override { return now_; }

private:
    int64_t now_;
};

}  // namespace gamecore
