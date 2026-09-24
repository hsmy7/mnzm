// ============================================================
// disciple_lifecycle_tx.h — 弟子生命周期 UI 操作事务（拜师/婚姻批准/
// 婚姻拒绝/年俸开关）
//
// batch-14（ui-read-surface §4.1 弟子管理族"最大残余域"第二批下沉）。
// 等价移植（语义权威 = 各 Kotlin 源文件，判定序逐相复刻）：
//  - DiscipleMasterApprenticeService.apprenticeToMaster（三相校验：
//    存在性×2 → 同一性/存活×2 → 已有师父/名额<5（仅存活徒弟））
//  - GameEngine.approveMarriageProposal 的配对写段（防御检查：
//    任一方已有道侣 → 跳过配对仅清提议）
//  - GameEngine.rejectMarriageProposal 的拒绝事件直写段（零弟子表写入）
//  - DiscipleLifecycleManager.updateYearlySalaryEnabled（境界年俸
//    开关覆写，无校验）
//
// RNG 契约（对拍命门）——**全族四事务零 RNG**：校验链与写路径均无
// rng 抽取；GTest 以 rngStates 快照差分守护。
//
// 失败臂零写入：校验链先行完成后再落写，任一校验失败不触碰状态；
// 失败信封 errorType 与 AppError.Domain.Disciple 分型同名
// （NotFound/NotAlive/SlotInvalid）→ Kotlin 回退原路径重执行校验链
//（双实现并行契约，用户可见文案由 Kotlin 臂产出）。
//
// 已知范围边界（对拍约定，disciple_tx.h 同口径）：
//  - lifeEvents 为 Kotlin 类体属性（非协议字段），C++ 无该列——拜师
//    双侧日志以草稿行进信封（apprenticeLogLine/masterLogLine），由
//    Kotlin native 分支回写瞬态列（disciple_tx.h logLine 机制同族）。
//  - DiscipleAssignmentGate.release、Room 生产槽 Repository 同步为
//    Kotlin 分支职责（clearAllSlotsState 残差，幂等可重放）。
//  - 婚姻提议列表 pendingMarriageProposals 为 GameStateStore 层字段
//    （非快照协议），提议移除留 Kotlin；批准/拒绝的 MARRIAGE 消息栏
//    事件经 settle_util::recordGameEvent C++ 直写（ai_beast_hunt 先例）。
//  - 婚姻批准对已不存在弟子（提议残留 + 弟子已亡边界）Kotlin 原路径
//    写幽灵列条目，SoA 行式存储无法表达 → 本事务 NotFound 信封回退
//    Kotlin 原路径（行为零变更）。
//  - 复用 slot_cleanup.h（12 类槽位纯数据变换）—— 不重写。
// ============================================================
#pragma once

#include <cstdint>
#include <string>

#include "gamecore/state/models.h"
#include "gamecore/system/settlement_detail.h"  // settle_util::toIntOrNull / recordGameEvent
#include "gamecore/system/slot_cleanup.h"       // clearAllSlotsDataOnly（12 类槽位）

namespace gamecore::system::disciple_lifecycle_tx {

namespace detail {

using gamecore::state::DiscipleStore;
using gamecore::state::GameState;
namespace settle_util = gamecore::system::settle_util;

/// 师父徒弟数上限（DiscipleStatCalculator.MAX_APPRENTICES_PER_MASTER）
inline constexpr int32_t kMaxApprenticesPerMaster = 5;

/// 12 类槽位清理（clearAllSlotsDataOnly 的 GameState 打包/回写壳；
/// includeResidence=true——住所一并清理，DiscipleSlotManager
/// .clearDiscipleFromAllSlots 默认参一致。disciple_tx.h 同款壳为其
/// includeResidence=false 变体，此处独立提供不共用）
inline void clearAllDiscipleSlotsForRemoval(GameState& state,
                                            const std::string& discipleId) {
    gamecore::system::SlotCleanupInput in;
    in.spiritMineSlots = state.gameData.spiritMineSlots;
    in.librarySlots = state.gameData.librarySlots;
    in.elderSlots = state.gameData.elderSlots;
    in.residenceSlots = state.gameData.residenceSlots;
    in.activeBloodRefinements = state.gameData.activeBloodRefinements;
    in.patrolSlots = state.gameData.patrolSlots;
    in.battleTeams = state.gameData.battleTeams;
    in.worldMapSects = state.gameData.worldMapSects;
    in.productionSlots = state.gameData.productionSlots;
    in.caveExplorationTeams = state.gameData.caveExplorationTeams;
    // S5 起完整 ActiveMission——清理 op 协议仍为 Lite（month_settlement.h 同壳）
    in.activeMissions =
        gamecore::system::toMissionLiteList(state.gameData.activeMissions);
    const auto out =
        gamecore::system::clearAllSlotsDataOnly(in, discipleId, /*includeResidence=*/true);
    state.gameData.spiritMineSlots = out.spiritMineSlots;
    state.gameData.librarySlots = out.librarySlots;
    state.gameData.elderSlots = out.elderSlots;
    state.gameData.residenceSlots = out.residenceSlots;
    state.gameData.activeBloodRefinements = out.activeBloodRefinements;
    state.gameData.patrolSlots = out.patrolSlots;
    state.gameData.battleTeams = out.battleTeams;
    state.gameData.worldMapSects = out.worldMapSects;
    state.gameData.productionSlots = out.productionSlots;
    state.gameData.caveExplorationTeams = out.caveExplorationTeams;
    state.gameData.activeMissions =
        gamecore::system::mergeMissionLiteList(state.gameData.activeMissions,
                                               out.activeMissions);
}

/// 存活徒弟计数（拜师名额校验用：其他弟子 && 存活 && masterIds == masterId）
inline int32_t countAliveApprentices(const DiscipleStore& ds, int32_t did,
                                     const std::string& masterId) {
    int32_t count = 0;
    for (std::size_t row = 0; row < ds.ids.size(); ++row) {
        const auto otherId = settle_util::toIntOrNull(ds.ids[row]);
        if (!otherId.has_value() || *otherId == did) continue;
        if (ds.isAlive[row] != 1) continue;
        if (ds.masterIds[row] != masterId) continue;
        ++count;
    }
    return count;
}

}  // namespace detail

// using 声明（disciple_tx.h 同款——事务函数作用域非限定名解析）
using gamecore::state::DiscipleStore;
using gamecore::state::GameState;
namespace settle_util = gamecore::system::settle_util;

// ── 结果信封（errorType 与 Kotlin AppError.Domain.Disciple 分型同名）────

/// 事务结果基型
struct LifecycleTxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
};

/// 拜师结果：附徒/师两侧日志草稿（Kotlin lifeEvents 瞬态列回写）
struct ApprenticeResult {
    bool ok = false;
    std::string errorType;
    std::string message;
    std::string apprenticeLogLine;  // "${age}岁：拜${masterName}为师"
    std::string masterLogLine;      // "${age}岁：收${discipleName}为徒"
};

/// 婚姻批准结果：paired=false 表示防御检查命中（任一方已有道侣）——
/// 零写入，Kotlin 仅移除提议不记事件；paired=true 已双向绑定，
/// Kotlin 移除提议（MARRIAGE 事件已由 C++ 直写消息栏）
struct MarriageApproveResult {
    bool ok = false;
    std::string errorType;
    std::string message;
    bool paired = false;
};

// ── 事务 2：拜师（DiscipleMasterApprenticeService.apprenticeToMaster 等价）──
//
// 三相校验（判定序逐相复刻）：① 存在性（徒弟 → 师父）② 同一性/存活
//（不可自拜 → 徒弟存活 → 师父存活）③ 名额（弟子无既有师父 → 师父存活
// 徒弟数 < 5）。写段：masterIds 落表 + 双侧日志草稿（Kotlin 回写 lifeEvents）。
// 师徒关系仅死亡解绑（DiscipleLifecycleProcessor 域，非本事务）。
inline ApprenticeResult apprenticeTransaction(GameState& state,
                                              const std::string& discipleId,
                                              const std::string& masterId) {
    ApprenticeResult out;
    DiscipleStore& ds = state.disciples;

    // 相 1：存在性（徒弟先行——Kotlin checkApprenticeExists 判定序）
    const auto did = settle_util::toIntOrNull(discipleId);
    if (!did.has_value() || !ds.contains(discipleId)) {
        out.errorType = "NotFound";
        out.message = "弟子不存在 " + discipleId;
        return out;
    }
    const auto mid = settle_util::toIntOrNull(masterId);
    if (!mid.has_value() || !ds.contains(masterId)) {
        out.errorType = "NotFound";
        out.message = "弟子不存在 " + masterId;
        return out;
    }
    const std::size_t dRow = *ds.rowOf(discipleId);
    const std::size_t mRow = *ds.rowOf(masterId);

    // 相 2：同一性 + 双方存活
    if (*did == *mid) {
        out.errorType = "SlotInvalid";
        out.message = "不能拜自己为师";
        return out;
    }
    if (ds.isAlive[dRow] != 1) {
        out.errorType = "NotAlive";
        out.message = "弟子已死亡 " + discipleId;
        return out;
    }
    if (ds.isAlive[mRow] != 1) {
        out.errorType = "NotAlive";
        out.message = "弟子已死亡 " + masterId;
        return out;
    }

    // 相 3：名额（弟子无既有师父；师父存活徒弟数 < 5——仅统计存活徒弟）
    if (!ds.masterIds[dRow].empty()) {
        out.errorType = "SlotInvalid";
        out.message = "弟子已有师父，师徒关系不可更改";
        return out;
    }
    if (detail::countAliveApprentices(ds, *did, masterId) >=
        detail::kMaxApprenticesPerMaster) {
        out.errorType = "SlotInvalid";
        out.message = "师父徒弟已满（最多5名）";
        return out;
    }

    // 写段：masterIds 落表（永久师徒关系，仅死亡解绑）
    ds.masterIds[dRow] = masterId;

    // 双侧日志草稿（Kotlin lifeEvents 瞬态列回写；缺失名兜底"未知"——
    // SoA 列恒有值，兜底仅协议防御）
    const std::string masterName =
        ds.names[mRow].empty() ? "未知" : ds.names[mRow];
    const std::string discipleName =
        ds.names[dRow].empty() ? "未知" : ds.names[dRow];
    out.apprenticeLogLine = "拜" + masterName + "为师";
    out.masterLogLine = "收" + discipleName + "为徒";
    out.ok = true;
    return out;
}

// ── 事务 3：婚姻批准配对写段（GameEngine.approveMarriageProposal 等价）────
//
// 防御检查（逐字对齐）：任一方已有道侣 → paired=false 零写入（Kotlin 仅
// 移除提议不记事件）。写段：partnerIds 双向绑定 + MARRIAGE 消息栏事件
//（settle_util::recordGameEvent——守卫/序号/裁剪完整对齐）。
// 提议存在性为 Kotlin 侧前置（pendingMarriageProposals 非协议字段）；
// 双方 id 无法解析或弟子行不存在（提议残留边界）→ 失败信封回退 Kotlin。
inline MarriageApproveResult approveMarriageTransaction(GameState& state,
                                                        const std::string& maleId,
                                                        const std::string& femaleId,
                                                        const std::string& maleName,
                                                        const std::string& femaleName) {
    MarriageApproveResult out;
    DiscipleStore& ds = state.disciples;

    // 解析 + 行存在（Kotlin 原路径对不存在行写幽灵列条目，SoA 无法表达
    // ——失败信封回退 Kotlin 原路径保行为）
    const auto maleInt = settle_util::toIntOrNull(maleId);
    const auto femaleInt = settle_util::toIntOrNull(femaleId);
    if (!maleInt.has_value() || !femaleInt.has_value() ||
        !ds.contains(maleId) || !ds.contains(femaleId)) {
        out.errorType = "NotFound";
        out.message = "婚姻提议弟子不存在 " + maleId + "/" + femaleId;
        return out;
    }
    const std::size_t maleRow = *ds.rowOf(maleId);
    const std::size_t femaleRow = *ds.rowOf(femaleId);

    // 防御检查：任一方已有道侣 → 跳过配对（零写入，仅清提议）
    if (!ds.partnerIds[maleRow].empty() || !ds.partnerIds[femaleRow].empty()) {
        out.ok = true;
        out.paired = false;
        return out;
    }

    // 写段：双向绑定
    ds.partnerIds[maleRow] = femaleId;
    ds.partnerIds[femaleRow] = maleId;

    // 消息栏事件（recordGameEvent 完整守卫对齐：长度上限/序号/裁剪）
    settle_util::recordGameEvent(
        state, "SECT", "MARRIAGE",
        "弟子" + maleName + "与弟子" + femaleName + "结为道侣",
        maleId, maleName);

    out.ok = true;
    out.paired = true;
    return out;
}

// ── 事务 3'：婚姻拒绝（GameEngine.rejectMarriageProposal 等价）────────────
//
// 拒绝 = 仅消息栏 MARRIAGE 事件直写（"拒绝与…结为道侣"），零弟子表写入、
// 零 RNG；提议移除留 Kotlin（pendingMarriageProposals 运行态字段）。
// 提议存在性为 Kotlin 侧前置（同事务 3）；事件直写无失败臂——信封恒成功，
// Kotlin native 分支照原序移除提议。
inline LifecycleTxResult rejectMarriageTransaction(GameState& state,
                                                   const std::string& maleId,
                                                   const std::string& femaleId,
                                                   const std::string& maleName,
                                                   const std::string& femaleName) {
    LifecycleTxResult out;
    settle_util::recordGameEvent(
        state, "SECT", "MARRIAGE",
        "弟子" + maleName + "拒绝与弟子" + femaleName + "结为道侣",
        maleId, maleName);
    out.ok = true;
    return out;
}

// ── 事务 5：境界年俸开关（DiscipleLifecycleManager.updateYearlySalaryEnabled
//    等价）——覆写 yearlySalaryEnabled[realm]，无校验（Kotlin 原路径盲写）──
inline LifecycleTxResult salaryToggleTransaction(GameState& state, int32_t realm,
                                                 bool enabled) {
    LifecycleTxResult out;
    state.gameData.yearlySalaryEnabled[realm] = enabled;
    out.ok = true;
    return out;
}

}  // namespace gamecore::system::disciple_lifecycle_tx
