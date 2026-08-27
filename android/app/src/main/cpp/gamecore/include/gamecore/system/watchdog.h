#pragma once

#include <cstdint>
#include <mutex>
#include <optional>

// ============================================================
// 游戏时间推进监控 — 看门狗统一判据（计划 v2 阶段 5：判据迁 C++）
//
// Kotlin core/engine/monitor/GameTimeProgressMonitor 逐位移植：
//   - 判据 = tickCount + totalPhases + accumulatedGameMs 三元组 + flags
//     （loopActive/isPaused/isSaving/isLoading/speed/秘境租约/循环心跳）
//   - 五种判定（StallVerdict）：Healthy / LoopStalled / FakeRunDetected /
//     PausedByOwner / StalePauseDetected
//   - 三阈值：STALE_PAUSE_TTL_MS=45s / FAKE_RUN_WINDOW_MS=90s /
//     LOOP_ACTIVITY_STALE_MS=20s
//   - evaluate 维护 prev 基准 + lastPhaseProgressedAtMs（S5 假运行时间窗基准）
//   - 历史教训与对抗性审查修复（S1/S4/S5/F2/V1/V6）全部随分支移植，
//     分支结构与 Kotlin 逐行对应（禁止 else-if 分叉与手写分支）
//
// 线程安全：引擎循环（采样）与看门狗线程（evaluate）并发——内部
// std::mutex 串行化（对应 Kotlin synchronized(this)）。
// ============================================================
namespace gamecore::system {

/// 进度快照（GameTimeProgressSnapshot 镜像）
struct ProgressSnapshot {
    /// 循环 tick 计数（假运行时也递增，不能单独作为推进判据）
    int64_t tickCount = 0;
    /// 世界时间绝对旬数（TimeSystem.getTotalPhases）— 时间推进真相源
    int64_t totalPhases = 0;
    /// 当旬累积游戏毫秒（旬内细分，不单独作为推进判据）
    int64_t accumulatedGameMs = 0;
    bool loopActive = false;
    bool isPaused = false;
    bool isSaving = false;
    bool isLoading = false;
    /// 游戏速度（0 = 时钟暂停）
    int speed = 1;
    /// 秘境暂停锁（secretRealmPauseLock）
    bool secretRealmPauseLock = false;
    /// 秘境暂停租约最后续约墙钟（elapsedRealtime）
    int64_t secretRealmPauseRenewedAtMs = 0;
    /// 游戏循环体最近活动墙钟（每次迭代更新，含暂停/保存跳过路径）
    int64_t loopActiveAtMs = 0;
    /// 采样墙钟（elapsedRealtime）
    int64_t recordedAtMs = 0;
};

/// 停滞判定结果（StallVerdict 镜像；数值即 JNI 传输码）
enum class StallVerdict : int {
    kHealthy = 0,
    kLoopStalled = 1,
    kFakeRunDetected = 2,
    kPausedByOwner = 3,
    kStalePauseDetected = 4,
};

/// 秘境暂停租约过期阈值（3 × 续约间隔 15s）
constexpr int64_t kStalePauseTtlMs = 45'000;
/// 假运行判定窗口：世界时间冻结持续超过此时长触发恢复
constexpr int64_t kFakeRunWindowMs = 90'000;
/// 循环活动停滞阈值：最后一次循环活动距今超过此时长视为循环停滞
constexpr int64_t kLoopActivityStaleMs = 20'000;

/// 停滞判定器——三层看门狗（引擎内/Alarm 兜底/跨语言对拍）统一判据
class ProgressMonitor {
public:
    explicit ProgressMonitor(int64_t stalePauseTtlMs = kStalePauseTtlMs,
                             int64_t fakeRunWindowMs = kFakeRunWindowMs)
        : stalePauseTtlMs_(stalePauseTtlMs), fakeRunWindowMs_(fakeRunWindowMs) {}

    /// 判定当前引擎状态。每次调用更新内部基准（恢复判定后基准随之刷新）。
    /// 首次调用（无基准）也能给出不依赖 prev 的判定：暂停类判定与
    /// speed=0 假运行——仅"循环停滞/世界时间冻结"类需要基准的判定首次豁免。
    StallVerdict evaluate(const ProgressSnapshot& current) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!prev_.has_value()) {
            const std::optional<StallVerdict> flagVerdict = classifyFlags(current);
            if (flagVerdict.has_value()) {
                prev_ = current;
                return *flagVerdict;
            }
            // V6：speed=0 假运行无需基准，首调即判（不延迟一个评估周期）
            if (current.speed == 0) {
                prev_ = current;
                return StallVerdict::kFakeRunDetected;
            }
            prev_ = current;
            return StallVerdict::kHealthy;
        }
        const StallVerdict verdict = classify(current, *prev_);
        prev_ = current;
        return verdict;
    }

private:
    /// 纯判定逻辑（与基准维护分离，便于测试与推理）
    StallVerdict classify(const ProgressSnapshot& current, const ProgressSnapshot& last) {
        // 1. 保存/加载豁免（S1）：设计性停循环（isPaused + 循环停）合法慢保存
        //    窗口豁免；循环异常死亡或引擎挂起 → 判停滞
        if (current.isSaving || current.isLoading) {
            if (!current.loopActive && current.isPaused) return StallVerdict::kHealthy;
            const int64_t loopStaleMs = current.recordedAtMs - current.loopActiveAtMs;
            return (loopStaleMs <= kLoopActivityStaleMs) ? StallVerdict::kHealthy
                                                         : StallVerdict::kLoopStalled;
        }

        // 2. 暂停判定（历史教训 a63338f3：用户主动暂停永不自动恢复）
        const std::optional<StallVerdict> flagVerdict = classifyFlags(current);
        if (flagVerdict.has_value()) return *flagVerdict;

        // 3. 循环死亡：非暂停但循环 Job 已不活跃
        if (!current.loopActive) return StallVerdict::kLoopStalled;

        // 4. tick 停滞：循环活跃但 tickCount 不推进。叠加循环活动心跳判据
        //    （S4/V1）：刚恢复/超长单 tick 期间 tickCount 暂不递增但心跳新鲜
        //    ——只有心跳也停滞（OEM 挂起/循环卡死）才判 LoopStalled
        if (current.tickCount == last.tickCount) {
            const int64_t loopStaleMs = current.recordedAtMs - current.loopActiveAtMs;
            return (loopStaleMs <= kLoopActivityStaleMs) ? StallVerdict::kHealthy
                                                         : StallVerdict::kLoopStalled;
        }

        // 5. 假运行检测：tick 在跑但世界时间（totalPhases）在窗口内未推进。
        //    以"最近推进时间"为窗（S5）——不依赖 accumulatedGameMs 逐采样
        //    增量比较（持续抛异常的世界冻结下它 0→2000→0 振荡绕过累积判据）
        if (current.speed == 0) {
            // speed=0 等价假运行（UI 已封死 0，出现即异常，立即自愈不等窗口）
            return StallVerdict::kFakeRunDetected;
        }
        if (current.totalPhases != last.totalPhases) {
            lastPhaseProgressedAtMs_ = current.recordedAtMs;
            return StallVerdict::kHealthy;
        }
        const int64_t progressStaleMs = (lastPhaseProgressedAtMs_ == 0)
                                            ? (current.recordedAtMs - last.recordedAtMs)
                                            : (current.recordedAtMs - lastPhaseProgressedAtMs_);
        return (progressStaleMs > fakeRunWindowMs_) ? StallVerdict::kFakeRunDetected
                                                    : StallVerdict::kHealthy;
    }

    /// 不依赖 prev 基准的 flag 判定（保存豁免 + 暂停语义）
    std::optional<StallVerdict> classifyFlags(const ProgressSnapshot& current) {
        if (current.isSaving || current.isLoading) return StallVerdict::kHealthy;
        if (current.isPaused) {
            if (!current.secretRealmPauseLock) return StallVerdict::kPausedByOwner;
            const bool leaseExpired =
                current.secretRealmPauseRenewedAtMs == 0 ||
                current.recordedAtMs - current.secretRealmPauseRenewedAtMs > stalePauseTtlMs_;
            if (leaseExpired) {
                // F2 修复：租约过期且循环本身也停滞（引擎被 OEM 挂起 → 续约
                // dispatch 排队不执行）→ 优先判 LoopStalled 走换线程恢复；
                // 否则清锁自愈。loopActiveAtMs==0（从未采样/循环未启动）
                // 视为无法判定停滞 → 走自愈
                const int64_t loopStaleMs = current.recordedAtMs - current.loopActiveAtMs;
                return (current.loopActiveAtMs != 0 && loopStaleMs > kLoopActivityStaleMs)
                           ? StallVerdict::kLoopStalled
                           : StallVerdict::kStalePauseDetected;
            }
            return StallVerdict::kPausedByOwner;
        }
        return std::nullopt;
    }

    std::mutex mutex_;
    std::optional<ProgressSnapshot> prev_;
    /// 世界时间（totalPhases）最近一次推进的墙钟（假运行时间窗判定基准）
    int64_t lastPhaseProgressedAtMs_ = 0;
    const int64_t stalePauseTtlMs_;
    const int64_t fakeRunWindowMs_;
};

}  // namespace gamecore::system
