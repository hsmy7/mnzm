#pragma once

#include <algorithm>
#include <cstdint>
#include <functional>

#include "gamecore/state/models.h"
#include "gamecore/system/time_system.h"

// ============================================================
// 惰性结算引擎（Kotlin→C++ 迁移批次 3）
//
// 等价移植 Kotlin GameTimeClock.tick + GameEngineCore.processTickPhases 的时间
// 推进语义（对抗性审查 2026-08-22 对齐）：
//
//   - 时间源 = 墙钟毫秒（GameTimeClock 用 elapsedRealtime；C++ 侧由桥层传入
//     nowMs 差值，对拍可控）
//   - accumulatedGameMs += wallDeltaMs * speed（speed: 0/1/2）
//   - phases = accumulatedGameMs / msPerPhase（msPerPhase = 2000ms @1x）
//   - phaseCap = MAX_PHASES_PER_TICK(3) * speed：单 tick 追补上限，超限**丢弃余量**
//     （防 OEM 挂起/看门狗重启的爆炸式跳变；与 Kotlin 语义一致）
//   - 每旬：advancePhase（时间推进）+ onPhaseSettle 钩子
//   - 月变/年变：边界检测 + 结算钩子（批次 4+ 填充具体系统）
//
// 注：帧循环的 LOGIC_DT_NS=100ms 只控制"tick 调用频率"，不参与时间推进换算——
// 游戏时间由墙钟差驱动。advancePhases(n) 为直接推进（对拍/测试用），
// 对应 Kotlin 逐旬调用 TimeSystem.onPhaseTick。
// ============================================================
namespace gamecore::system {

constexpr int64_t kMsPerPhase1x = 2000;      // GameTimeClock.MS_PER_PHASE_1X
constexpr int kMaxPhasesPerTick = 3;         // GameTimeClock.MAX_PHASES_PER_TICK

/// 单次 advance 的结果
struct TickResult {
    int phasesAdvanced = 0;   // 本 tick 实际推进的旬数
    bool monthChanged = false;
    bool yearChanged = false;
};

/// settleOnePhase 返回的边界标志位（T2.4 AUTHORITATIVE tick 标量通道）
constexpr int kSettleFlagNone = 0;
constexpr int kSettleFlagMonthChanged = 1;
constexpr int kSettleFlagYearChanged = 2;

class SettlementEngine {
public:
    /// 结算钩子（可注入；默认空实现——批次 4+ 按子系统注册）
    std::function<void(state::GameState&, state::GameData&)> onPhaseSettle;
    std::function<void(state::GameState&, state::GameData&)> onMonthChange;
    std::function<void(state::GameState&, state::GameData&)> onYearChange;
    /// 核心每旬结算钩子（T2.4 core 模式专用：零 RNG 步骤 1-5 批量；
    /// 月/年边界结算由 Kotlin 残留执行器按 settleOnePhase 标志处理）
    std::function<void(state::GameState&, state::GameData&)> onCoreSettle;

    /// core 模式（T2.4 AUTHORITATIVE 过渡语义）：每旬只跑时间推进 +
    /// onCoreSettle；月/年结算钩子不触发（标志仍记录，供标量通道返回）。
    /// 常规模式（shadow 对拍/diff 测试）行为与批次 3 起逐位一致。
    void setCoreMode(bool on) { coreMode_ = on; }
    bool coreMode() const { return coreMode_; }

    /// 推进墙钟增量（等价 GameTimeClock.tick + processTickPhases 时间部分）
    /// wallDeltaMs：自上次 tick 的墙钟毫秒增量（由桥层传入，保证对拍可控）
    TickResult advance(state::GameState& state, int64_t wallDeltaMs) {
        if (speed_ > 0) {
            accumulatedGameMs_ += wallDeltaMs * speed_;
        }
        int phases = static_cast<int>(accumulatedGameMs_ / kMsPerPhase1x);
        const int phaseCap = kMaxPhasesPerTick * std::max(speed_, 1);
        if (phases > phaseCap) {
            // 超限丢弃余量（Kotlin: accumulatedGameMsInternal = 0）
            phases = phaseCap;
            accumulatedGameMs_ = 0;
        } else if (phases > 0) {
            accumulatedGameMs_ -= static_cast<int64_t>(phases) * kMsPerPhase1x;
        }
        return advancePhases(state, phases);
    }

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

    /// 单旬推进（T2.4 AUTHORITATIVE tick 标量通道）：恰好一次
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

    /// 设置游戏速度（0=暂停 1=正常 2=双倍；等价 GameTimeClock.setSpeed）
    void setSpeed(int speed) { speed_ = speed < 0 ? 0 : (speed > 2 ? 2 : speed); }
    int speed() const { return speed_; }

    /// 复位（新档/读档时清除累积——对抗性审查 A1：读档后残留累积会导致下一
    /// tick 多推进；GameCore.importStateJson 成功后必须调用）
    void reset() {
        accumulatedGameMs_ = 0;
        monthChanged_ = false;
        yearChanged_ = false;
    }

private:
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
            // T2.4 过渡语义：只跑核心每旬结算；月/年结算钩子不触发
            //（Kotlin 残留执行器按 settleOnePhase 标志处理，保证未迁移
            // 系统（偷盗钩子/AI 预计算/七系统扇出/十三月度子事件等）
            // 行为零丢失）
            if (onCoreSettle) onCoreSettle(state, gd);
            return;
        }
        if (onPhaseSettle) onPhaseSettle(state, gd);  // 旬结算钩子（批次 4+）
        if (gd.gameYear != prevYear) {
            if (onYearChange) onYearChange(state, gd);    // 年变钩子（先）
        }
        if (gd.gameMonth != prevMonth) {
            if (onMonthChange) onMonthChange(state, gd);  // 月变钩子（后）
        }
    }

    int64_t accumulatedGameMs_ = 0;
    int speed_ = 1;
    bool monthChanged_ = false;
    bool yearChanged_ = false;
    bool coreMode_ = false;
};

}  // namespace gamecore::system
