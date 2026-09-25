// ============================================================
// disciple_lifecycle_tx.h — 弟子生命周期 UI 操作事务（境界年俸开关）
//
// batch-14（ui-read-surface §4.1 弟子管理族"最大残余域"第二批下沉）。
// 等价移植（语义权威 = Kotlin 源文件）：
//  - DiscipleLifecycleManager.updateYearlySalaryEnabled（境界年俸
//    开关覆写 yearlySalaryEnabled[realm]，无校验盲写）
//
// ActionId 1590（逐出）/1591（拜师）/1592（婚姻批准）/1593（释放思过）/1740（改名）/1750（婚姻拒绝）
// 已退役——编号禁复用，dispatch 侧不再认领。
//
// RNG 契约（对拍命门）——**零 RNG**：写路径无任何 rng 抽取；
// GTest 以 rngStates 快照差分守护。
//
// 结果信封（ok/errorType/message）与 AppError.Domain.Disciple 分型同名
// 口径一致；本事务无校验臂恒成功，失败臂为协议预留 → Kotlin 回退原路径
//（双实现并行契约，用户可见文案由 Kotlin 臂产出）。
// ============================================================
#pragma once

#include <cstdint>
#include <string>

#include "gamecore/state/models.h"

namespace gamecore::system::disciple_lifecycle_tx {

// using 声明（事务函数作用域非限定名解析）
using gamecore::state::GameState;

// ── 结果信封（errorType 与 Kotlin AppError.Domain.Disciple 分型同名）────

/// 事务结果基型
struct LifecycleTxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
};

// ── 事务：境界年俸开关（DiscipleLifecycleManager.updateYearlySalaryEnabled
//    等价）——覆写 yearlySalaryEnabled[realm]，无校验（Kotlin 原路径盲写）──
inline LifecycleTxResult salaryToggleTransaction(GameState& state, int32_t realm,
                                                 bool enabled) {
    LifecycleTxResult out;
    state.gameData.yearlySalaryEnabled[realm] = enabled;
    out.ok = true;
    return out;
}

}  // namespace gamecore::system::disciple_lifecycle_tx
