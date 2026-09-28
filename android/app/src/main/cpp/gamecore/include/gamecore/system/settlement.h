#pragma once

#include <algorithm>
#include <cstdint>
#include <functional>
#include <map>

#include "gamecore/state/models.h"
#include "gamecore/system/time_system.h"

// ============================================================
// 惰性结算引擎
//
// 时间推进语义（结算改造 2026-09-27 B2 起的权威口径）：
//
//   - 权威游戏毫秒轴 elapsedGameMs += rawWallDeltaMs（单一时速，无倍率），
//     **不截断不丢弃**（INV-2：追补上限只作用于判定轨，时间零丢失）；
//   - 判定窗口数 = elapsedGameMs / kGameMsPerPhase 的整数差（INV-3：
//     帧率/分帧无关）；phaseCap = MAX_PHASES_PER_TICK(3)
//     只限制单段判定执行次数，超限窗口的时间仍全额入轴；
//   - 每旬：advancePhase（日历推进）+ 结算钩子（core 模式只跑 onCoreSettle，
//     shadow 模式跑 onPhaseSettle + 月/年边界钩子）；
//   - 月变/年变：边界检测 + 结算钩子。
//
// 墙钟入口唯一化（方案 §3.4「两套时基」缺陷修复，B9）：旧 phaseCap 丢弃式
// 累积器 advance(wallDeltaMs) 已删除——shadow/对拍臂与生产臂（PhaseClock +
// EngineLoop.iterate）统一走 advanceByGameMs 同一语义，两臂时基不再漂移。
// advancePhases(n) 为直接推进（对拍/测试用），对应 Kotlin 逐旬
// TimeSystem.onPhaseTick。
// ============================================================
namespace gamecore::system {

constexpr int64_t kMsPerPhase = 2000;        // GameTimeClock.MS_PER_PHASE（单一时速）
constexpr int kMaxPhasesPerTick = 3;         // GameTimeClock.MAX_PHASES_PER_TICK

/// 单 tick 判定执行上限（双端单一来源 = 本常量；Kotlin 锚点
/// GameTimeClock.MAX_PHASES_PER_TICK 常量断言 + Diff 对拍锁定，改值须双端同步）。

/// 单次 advance 的结果
struct TickResult {
    int phasesAdvanced = 0;   // 本 tick 实际推进的旬数
    bool monthChanged = false;
    bool yearChanged = false;
};

/// HP/MP 恢复小数进位（B4 连续积分轨；键 = 弟子数值 id，运行态不入档）
using RecoveryCarry = std::map<int32_t, std::pair<double, double>>;

/// advanceByGameMs 的结果（结算改造 2026-09-27 §2.4：未截断 Δt + 判定次数单独计算）
struct AccrualResult {
    /// 未截断游戏毫秒增量（INV-2：不受追补上限影响）
    int64_t deltaGameMs = 0;
    /// 本段应执行的判定窗口数（INV-3：权威时间轴整数差，帧率无关）
    int windowsTotal = 0;
    /// 追补上限后实际执行的窗口数（phaseCap 只作用于判定轨）
    int windowsExecuted = 0;
    bool monthChanged = false;
    bool yearChanged = false;
};

/// settleOnePhase 返回的边界标志位（AUTHORITATIVE tick 标量通道）
constexpr int kSettleFlagNone = 0;
constexpr int kSettleFlagMonthChanged = 1;
constexpr int kSettleFlagYearChanged = 2;

class SettlementEngine {
public:
    /// 结算钩子（可注入；由 GameCore 按子系统注册）
    std::function<void(state::GameState&, state::GameData&)> onPhaseSettle;
    std::function<void(state::GameState&, state::GameData&)> onMonthChange;
    std::function<void(state::GameState&, state::GameData&)> onYearChange;
    /// 核心每旬结算钩子（core 模式专用：零 RNG 步骤 1-5 批量；
    /// 月/年边界结算由 Kotlin 残留执行器按 settleOnePhase 标志处理）
    std::function<void(state::GameState&, state::GameData&)> onCoreSettle;

    /// core 模式（AUTHORITATIVE 语义）：每旬只跑时间推进 +
    /// onCoreSettle；月/年结算钩子不触发（标志仍记录，供标量通道返回）。
    /// 常规模式（shadow 对拍/diff 测试）行为与 Kotlin 逐位一致。
    void setCoreMode(bool on) { coreMode_ = on; }
    bool coreMode() const { return coreMode_; }

    /// accrual 模式（结算改造 2026-09-27 B4 连续积分轨）：advanceOnePhase
    /// 只推进日历、不触发任何钩子（积分项由 accrueContinuous 连续承担，
    /// 判定轨 0/6/7 由 GameCore::accrue 的窗口循环显式驱动）。
    void setAccrualMode(bool on) { accrualMode_ = on; }
    bool accrualMode() const { return accrualMode_; }

    /// 直接推进 N 旬（绕过墙钟累积；对拍/测试用）
    TickResult advancePhases(state::GameState& state, int phaseCount) {
        monthChanged_ = false;
        yearChanged_ = false;
        for (int i = 0; i < phaseCount; ++i) {
            advanceOnePhase(state);
        }
        TickResult result;
        result.phasesAdvanced = phaseCount;
        result.monthChanged = monthChanged_;
        result.yearChanged = yearChanged_;
        return result;
    }

    /// 按墙钟增量做未截断推进（结算改造 2026-09-27 §2.4；shadow/对拍臂 +
    /// B4 连续结算的语义基准）：
    /// - 权威游戏毫秒按 rawWallDeltaMs 累积，**不截断不丢弃**（INV-2）；
    /// - 判定窗口数 = phaseWindowCount 的整数差（INV-3，与分帧方式无关）；
    /// - phaseCap（kMaxPhasesPerTick）只作用于判定执行次数，超限不丢时间。
    /// 与生产臂（PhaseClock.elapsedGameNs + EngineLoop.iterate）同一语义，
    /// 双向由 ContinuousAccrual 系列测试锁定。
    AccrualResult advanceByGameMs(state::GameState& state, int64_t rawWallDeltaMs) {
        AccrualResult result;
        if (rawWallDeltaMs < 0) rawWallDeltaMs = 0;   // 单调时钟不回拨；防御钳制
        const int64_t prevElapsed = elapsedGameMs_;
        elapsedGameMs_ += rawWallDeltaMs;
        result.deltaGameMs = elapsedGameMs_ - prevElapsed;
        const int64_t windows =
            PhaseWindow::count(elapsedGameMs_) - PhaseWindow::count(prevElapsed);
        result.windowsTotal = static_cast<int>(windows);
        int executed = result.windowsTotal;
        const int cap = kMaxPhasesPerTick;
        if (executed > cap) executed = cap;
        result.windowsExecuted = executed;
        const TickResult tr = advancePhases(state, executed);
        result.monthChanged = tr.monthChanged;
        result.yearChanged = tr.yearChanged;
        return result;
    }

    /// 权威游戏毫秒（advanceByGameMs 累积；shadow 臂观测用）
    int64_t elapsedGameMs() const { return elapsedGameMs_; }

    /// 已积分轴一次性推进（结算改造 2026-09-27 B7 离线注入）：
    /// 离线收益的积分由 GameCore::injectOfflineGameMs 显式结算，本方法只把
    /// 已积分轴同步跳到注入后位置——保证 shadow/对拍口径的
    /// elapsedGameMs_ 与 PhaseClock 真相轴、GameData 旬投影三轴一致。
    /// 不触发任何钩子/日历推进（那由注入编排显式承担）。
    void advanceGameMs(int64_t gameMs) {
        if (gameMs > 0) elapsedGameMs_ += gameMs;
    }

    /// 单旬推进（AUTHORITATIVE tick 标量通道）：恰好一次
    /// advanceOnePhase，返回本旬边界标志位（kSettleFlag* 位组合）。
    int settleOnePhase(state::GameState& state) {
        monthChanged_ = false;
        yearChanged_ = false;
        advanceOnePhase(state);
        int flags = kSettleFlagNone;
        if (monthChanged_) flags |= kSettleFlagMonthChanged;
        if (yearChanged_) flags |= kSettleFlagYearChanged;
        return flags;
    }

    // ── 连续积分轨支撑（结算改造 2026-09-27 B4）──────────────────────

    /// 折叠墙钟增量进权威时间轴并返回本段判定窗口总数
    ///（INV-3 整数差，未 cap，不截断）。
    int64_t foldAndWindows(int64_t rawWallDeltaMs) {
        if (rawWallDeltaMs < 0) rawWallDeltaMs = 0;
        const int64_t prev = elapsedGameMs_;
        elapsedGameMs_ += rawWallDeltaMs;
        return PhaseWindow::count(elapsedGameMs_) - PhaseWindow::count(prev);
    }

    /// accrual 模式窗口推进：单旬日历推进（零钩子）+ 边界标志位。
    /// 仅在 accrualMode_ 下合法（普通模式的窗口推进走 advancePhases）。
    int advanceOnePhaseAccrual(state::GameState& state) {
        monthChanged_ = false;
        yearChanged_ = false;
        auto& gd = state.gameData;
        const int prevMonth = gd.gameMonth;
        const int prevYear = gd.gameYear;
        advancePhase(gd);
        if (gd.gameYear != prevYear) yearChanged_ = true;
        if (gd.gameMonth != prevMonth) monthChanged_ = true;
        int flags = kSettleFlagNone;
        if (monthChanged_) flags |= kSettleFlagMonthChanged;
        if (yearChanged_) flags |= kSettleFlagYearChanged;
        return flags;
    }

    /// 复位（新档/读档时清除累积——读档后残留累积会导致下一
    /// tick 多推进；GameCore.importStateJson 成功后必须调用）
    void reset() {
        elapsedGameMs_ = 0;
        monthChanged_ = false;
        yearChanged_ = false;
    }

private:
    /// 判定窗口计数域（INV-3 唯一口径；PhaseClock::phaseWindowCount 同式）
    struct PhaseWindow {
        static int64_t count(int64_t elapsedGameMsValue) {
            return elapsedGameMsValue < 0 ? 0
                                          : elapsedGameMsValue / kGameMsPerPhase;
        }
    };

    void advanceOnePhase(state::GameState& state) {
        auto& gd = state.gameData;
        const int prevMonth = gd.gameMonth;
        const int prevYear = gd.gameYear;
        advancePhase(gd);                       // 时间推进（time_system.h）
        if (gd.gameYear != prevYear) yearChanged_ = true;
        if (gd.gameMonth != prevMonth) monthChanged_ = true;
        // 年月同界（12 月下旬 → 新年 1 月）时钩子序对齐 Kotlin
        // processMonthYearChange：年变分支先于月变分支执行
        if (coreMode_) {
            // core 模式语义：只跑核心每旬结算；月/年结算钩子不触发
            //（Kotlin 残留执行器按 settleOnePhase 标志消费月/年边界，
            // 结算行为零丢失）
            if (onCoreSettle) onCoreSettle(state, gd);
            return;
        }
        if (onPhaseSettle) onPhaseSettle(state, gd);  // 旬结算钩子
        if (gd.gameYear != prevYear) {
            if (onYearChange) onYearChange(state, gd);    // 年变钩子（先）
        }
        if (gd.gameMonth != prevMonth) {
            if (onMonthChange) onMonthChange(state, gd);  // 月变钩子（后）
        }
    }

    /// 未截断权威游戏毫秒（advanceByGameMs 累积；INV-2）
    int64_t elapsedGameMs_ = 0;
    bool monthChanged_ = false;
    bool yearChanged_ = false;
    bool coreMode_ = false;
    bool accrualMode_ = false;
};

}  // namespace gamecore::system
