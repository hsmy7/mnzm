// ============================================================
// disciple_lifecycle_tx.h — 弟子生命周期 UI 操作事务（逐出/拜师/
// 婚姻批准/释放思过/年俸开关）
//
// batch-14（ui-read-surface §4.1 弟子管理族"最大残余域"第二批下沉）。
// 等价移植（语义权威 = 各 Kotlin 源文件，判定序逐相复刻）：
//  - DiscipleService.expelDisciple（DiscipleService.kt——校验链：
//    存在 → 存活 → 非 REFINING；写段：12 类槽位清理（含住所）→
//    穿戴装备/功法实例销毁 → 派生 map 收口 → 行删除 → 年报脱离计数）
//  - DiscipleMasterApprenticeService.apprenticeToMaster（三相校验：
//    存在性×2 → 同一性/存活×2 → 已有师父/名额<5（仅存活徒弟））
//  - GameEngine.approveMarriageProposal 的配对写段（防御检查：
//    任一方已有道侣 → 跳过配对仅清提议）
//  - DiscipleFacadeImpl.releaseReflectionDisciple（思过标记清除 +
//    状态回 IDLE；解析失败/不存在/已死亡为静默 no-op）
//  - DiscipleLifecycleManager.updateYearlySalaryEnabled（境界年俸
//    开关覆写，无校验）
//
// RNG 契约（对拍命门）——**全族五事务零 RNG**：校验链与写路径均无
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
//  - 逐出袋物品物化（materializeBagItemsToWarehouse——开袋族，归
//    batch-11 库存收官）为 Kotlin 运行态域：信封回传 bagItems 草稿，
//    Kotlin 在镜像回读后原序物化（溢出转邮件）。袋内灵石
//    （storageBagSpiritStones）Kotlin 原路径同样不物化、随行删除。
//  - DiscipleAssignmentGate.release、Room 生产槽 Repository 同步为
//    Kotlin 分支职责（clearAllSlotsState 残差，幂等可重放）。
//  - 婚姻提议列表 pendingMarriageProposals 为 GameStateStore 层字段
//    （非快照协议），提议移除留 Kotlin；批准/拒绝的 MARRIAGE 消息栏
//    事件经 settle_util::recordGameEvent C++ 直写（ai_beast_hunt 先例）。
//  - 婚姻批准对已不存在弟子（提议残留 + 弟子已亡/被逐边界）Kotlin 原路径
//    写幽灵列条目，SoA 行式存储无法表达 → 本事务 NotFound 信封回退
//    Kotlin 原路径（行为零变更）。
//  - 复用 slot_cleanup.h（12 类槽位纯数据变换）与
//    blood_refinement.h::eraseDiscipleDerivedMaps（派生 map 唯一收口点）
//    —— 不重写。
// ============================================================
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/blood_refinement.h"   // eraseDiscipleDerivedMaps（唯一收口点）
#include "gamecore/system/settlement_detail.h"  // settle_util::toIntOrNull / recordGameEvent
#include "gamecore/system/slot_cleanup.h"       // clearAllSlotsDataOnly（12 类槽位）

namespace gamecore::system::disciple_lifecycle_tx {

namespace detail {

using gamecore::state::DiscipleStore;
using gamecore::state::GameState;
namespace settle_util = gamecore::system::settle_util;

/// 师父徒弟数上限（DiscipleStatCalculator.MAX_APPRENTICES_PER_MASTER）
inline constexpr int32_t kMaxApprenticesPerMaster = 5;

/// DiscipleStatus.name 守卫用字面量（statuses 列存 .name 字符串）
inline constexpr const char* kRefiningStatusName = "REFINING";
inline constexpr const char* kIdleStatusName = "IDLE";

/// 思过标记 statusData key（DiscipleStatusData 单一来源同名键）
inline constexpr const char* kReflectionStartYearKey = "reflectionStartYear";
inline constexpr const char* kReflectionEndYearKey = "reflectionEndYear";

/// 12 类槽位清理（clearAllSlotsDataOnly 的 GameState 打包/回写壳；
/// includeResidence=true——死亡/逐出清住所语义，DiscipleSlotManager
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
    in.warehouseGarrisons = state.gameData.warehouseGarrisons;
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
    state.gameData.warehouseGarrisons = out.warehouseGarrisons;
    state.gameData.battleTeams = out.battleTeams;
    state.gameData.worldMapSects = out.worldMapSects;
    state.gameData.productionSlots = out.productionSlots;
    state.gameData.caveExplorationTeams = out.caveExplorationTeams;
    state.gameData.activeMissions =
        gamecore::system::mergeMissionLiteList(state.gameData.activeMissions,
                                               out.activeMissions);
}

/// 穿戴装备/功法实例销毁（expelDisciple"仅清除穿着的所有权，不返还仓库"
/// 分支：四装备位 + manualIds 指向的实例自实例表移除；实例表规模小，
/// 逐 id 线性过滤与 Kotlin filter 同义）
inline void destroyWornInstances(GameState& state, const DiscipleStore& ds,
                                 std::size_t row) {
    std::vector<std::string> equipIds;
    if (!ds.weaponIds[row].empty()) equipIds.push_back(ds.weaponIds[row]);
    if (!ds.armorIds[row].empty()) equipIds.push_back(ds.armorIds[row]);
    if (!ds.bootsIds[row].empty()) equipIds.push_back(ds.bootsIds[row]);
    if (!ds.accessoryIds[row].empty()) equipIds.push_back(ds.accessoryIds[row]);
    const std::vector<std::string> manualIds = ds.manualIds[row];

    auto& equipment = state.equipmentInstances;
    equipment.erase(std::remove_if(equipment.begin(), equipment.end(),
                                   [&](const gamecore::state::EquipmentInstance& x) {
                                       for (const auto& id : equipIds) {
                                           if (x.id == id) return true;
                                       }
                                       return false;
                                   }),
                    equipment.end());
    auto& manuals = state.manualInstances;
    manuals.erase(std::remove_if(manuals.begin(), manuals.end(),
                                 [&](const gamecore::state::ManualInstance& x) {
                                     for (const auto& id : manualIds) {
                                         if (x.id == id) return true;
                                     }
                                     return false;
                                 }),
                  manuals.end());
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
using gamecore::state::StorageBagItem;
namespace settle_util = gamecore::system::settle_util;

// ── 结果信封（errorType 与 Kotlin AppError.Domain.Disciple 分型同名）────

/// 事务结果基型
struct LifecycleTxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
};

/// 逐出结果：附袋物品草稿（Kotlin 物化回仓库 + 溢出转邮件；
/// 随行删除的袋内灵石不回传——与 Kotlin 原路径同口径）
struct ExpelResult {
    bool ok = false;
    std::string errorType;
    std::string message;
    std::vector<StorageBagItem> bagItems;
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

/// 释放思过结果：written=false 表示静默 no-op（解析失败/不存在/已死亡——
/// Kotlin 原路径同分支）；true 已清标记，Kotlin 照原序 syncSingle
struct ReleaseReflectionResult {
    bool ok = false;
    std::string errorType;
    std::string message;
    bool written = false;
};

// ── 事务 1：逐出弟子（DiscipleService.expelDisciple 等价）────────────────
//
// 校验链（逐字对齐 Kotlin 判定序）：弟子存在 → 存活（已死亡 NotAlive）→
// 非 REFINING（血炼中 SlotInvalid）。写段：袋物品捕获（信封回传）→
// 12 类槽位清理（含住所）→ 穿戴装备/功法实例销毁 → 派生 map 收口
// （血炼三 map + 功法熟练度）→ 行删除 → 年报脱离弟子计数。
// 死亡标记红线（CLAUDE.md 13.3）：本事务**不写** isAlive/status=DEAD——
// 逐出是行删除而非死亡（GTest 断言行移除而非死亡标记，markDead 路径
// 不经本事务）。
inline ExpelResult expelTransaction(GameState& state, const std::string& discipleId) {
    ExpelResult out;
    DiscipleStore& ds = state.disciples;

    // 1. 弟子存在（Kotlin toIntOrNull + ids.contains）
    const auto intId = settle_util::toIntOrNull(discipleId);
    if (!intId.has_value() || !ds.contains(discipleId)) {
        out.errorType = "NotFound";
        out.message = "弟子不存在 " + discipleId;
        return out;
    }
    const std::size_t row = *ds.rowOf(discipleId);

    // 2. 存活校验（Kotlin isAlive != 1 → NotAlive）
    if (ds.isAlive[row] != 1) {
        out.errorType = "NotAlive";
        out.message = "弟子已死亡 " + discipleId;
        return out;
    }

    // 3. 血炼中不可驱逐（Kotlin statuses == REFINING → SlotInvalid）
    if (ds.statuses[row] == detail::kRefiningStatusName) {
        out.errorType = "SlotInvalid";
        out.message = "弟子正在血炼中，无法驱逐";
        return out;
    }

    // ── 写段（校验链全通过）──
    // 4. 袋物品捕获（removeById 前拷贝——信封回传 Kotlin 物化）
    out.bagItems = ds.storageBagItems[row];

    // 5. 12 类槽位清理（含住所——死亡/逐出语义）
    detail::clearAllDiscipleSlotsForRemoval(state, discipleId);

    // 6. 穿戴装备/功法实例销毁（不返还仓库——Kotlin 同分支）
    detail::destroyWornInstances(state, ds, row);

    // 7. 派生 map 统一收口（血炼三 map + 功法熟练度——P2-7/P3-4 收口点）
    gamecore::system::eraseDiscipleDerivedMaps(state.gameData, discipleId);

    // 8. 行删除（其余行序保留——RNG 红线：removeById 原位 erase）
    ds.removeById(discipleId);

    // 9. 年报脱离弟子计数（Kotlin annualDesertedDisciples + 1）
    state.gameData.annualDesertedDisciples += 1;

    out.ok = true;
    return out;
}

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
    out.apprenticeLogLine = std::to_string(ds.ages[dRow]) + "岁：拜" + masterName + "为师";
    out.masterLogLine = std::to_string(ds.ages[mRow]) + "岁：收" + discipleName + "为徒";
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

// ── 事务 4：释放思过（DiscipleFacadeImpl.releaseReflectionDisciple 等价）──
//
// 静默 no-op 分支（解析失败/不存在/已死亡）与 Kotlin 早退同义（written=
// false）；写段：statusData 思过双键定向移除（保留血炼 buildingId 等
// 其余 key）+ 状态回 IDLE（使 deriveDiscipleStatus 重新推导）。
// syncSingleDiscipleStatus 状态推导为 Kotlin 运行态域，事务后照原序执行。
inline ReleaseReflectionResult releaseReflectionTransaction(GameState& state,
                                                            const std::string& discipleId) {
    ReleaseReflectionResult out;
    DiscipleStore& ds = state.disciples;

    const auto intId = settle_util::toIntOrNull(discipleId);
    if (!intId.has_value() || !ds.contains(discipleId)) {
        out.ok = true;  // Kotlin 解析失败/不存在 → 静默 return（非失败）
        return out;
    }
    const std::size_t row = *ds.rowOf(discipleId);
    if (ds.isAlive[row] != 1) {
        out.ok = true;  // Kotlin 已死亡 → 静默 return
        return out;
    }

    // 定向移除思过双键（Kotlin `- key` 语义——禁止整体覆盖 statusData）
    auto& statusData = ds.statusData[row];
    statusData.erase(detail::kReflectionStartYearKey);
    statusData.erase(detail::kReflectionEndYearKey);
    // 清除受保护状态标记，使 deriveDiscipleStatus 可以重新推导
    //（否则 REFLECTING 受保护检查会锁定状态）
    ds.statuses[row] = detail::kIdleStatusName;

    out.ok = true;
    out.written = true;
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
