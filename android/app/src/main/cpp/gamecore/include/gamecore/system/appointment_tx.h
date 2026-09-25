// ============================================================
// appointment_tx.h — 长老任命/卸任事务
//
// batch-15（ui-read-surface §4.1 弟子管理·长老单值槽写者下沉；
// 语义权威 = Kotlin ElderManagementUseCase，判定序逐字对齐）：
//  - ElderManagementUseCase.assignElder/removeElder
//    （usecase 编排域——batch-08 登记留 W3 的长老单值槽任命；本头只承
//     elderSlots 数据写段 + 全槽清理数据段，Gate/checkpoint/状态同步残差
//     留 Kotlin）
//
// RNG 契约（对拍约定）：任命/卸任**零抽取**（签名级：API 不接受 rng 参数）。
//
// 已知范围边界（对拍约定，disciple_tx.h / patrol_tx.h 同口径）：
//  - DiscipleAssignmentGate 登记/释放、syncSingleDiscipleStatus、
//    checkpointAllProduction/checkpointAllDisciples、Room 生产槽回放、
//    releaseDiscipleFromAllSlotsAtomic（状态重置域）为 Kotlin 运行态残差，
//    native 成功后照原序执行。
// ============================================================
#pragma once

#include <optional>
#include <string>
#include <vector>

#include "gamecore/state/disciple_store.h"
#include "gamecore/state/models.h"
#include "gamecore/system/slot_cleanup.h"  // clearAllSlotsDataOnly

namespace gamecore::system::appointment_tx {

/// 事务形参类型别名（detail 内另有 using，本层供事务签名使用）
using gamecore::state::GameState;

namespace detail {

using gamecore::state::DirectDiscipleSlot;
using gamecore::state::ElderSlots;
using gamecore::state::GameState;

/// 10 类槽位清理（disciple_tx.h / patrol_tx.h detail 同族——本头文件独立
/// 提供，保持各 tx 头自包含；includeResidence=false 工作分配语义）
inline void clearAllDiscipleSlots(GameState& state, const std::string& discipleId) {
    gamecore::system::SlotCleanupInput in;
    in.spiritMineSlots = state.gameData.spiritMineSlots;
    in.librarySlots = state.gameData.librarySlots;
    in.elderSlots = state.gameData.elderSlots;
    in.residenceSlots = state.gameData.residenceSlots;
    in.patrolSlots = state.gameData.patrolSlots;
    in.battleTeams = state.gameData.battleTeams;
    in.worldMapSects = state.gameData.worldMapSects;
    in.productionSlots = state.gameData.productionSlots;
    in.caveExplorationTeams = state.gameData.caveExplorationTeams;
    in.activeMissions =
        gamecore::system::toMissionLiteList(state.gameData.activeMissions);
    const auto out =
        gamecore::system::clearAllSlotsDataOnly(in, discipleId, /*includeResidence=*/false);
    state.gameData.spiritMineSlots = out.spiritMineSlots;
    state.gameData.librarySlots = out.librarySlots;
    state.gameData.elderSlots = out.elderSlots;
    state.gameData.residenceSlots = out.residenceSlots;
    state.gameData.patrolSlots = out.patrolSlots;
    state.gameData.battleTeams = out.battleTeams;
    state.gameData.worldMapSects = out.worldMapSects;
    state.gameData.productionSlots = out.productionSlots;
    state.gameData.caveExplorationTeams = out.caveExplorationTeams;
    state.gameData.activeMissions =
        gamecore::system::mergeMissionLiteList(state.gameData.activeMissions,
                                               out.activeMissions);
}

// ── 长老单值槽族（ElderManagementUseCase 的 10 字段穷举）────────────────

/// ElderSlotType.name → 槽位字段指针（未知类型 nullptr——协议违规防御臂）
inline std::string* elderFieldOf(ElderSlots& slots, const std::string& slotType) {
    if (slotType == "VICE_SECT_MASTER") return &slots.viceSectMaster;
    if (slotType == "HERB_GARDEN") return &slots.herbGardenElder;
    if (slotType == "ALCHEMY") return &slots.alchemyElder;
    if (slotType == "FORGE") return &slots.forgeElder;
    if (slotType == "OUTER_ELDER") return &slots.outerElder;
    if (slotType == "PREACHING") return &slots.preachingElder;
    if (slotType == "LAW_ENFORCEMENT") return &slots.lawEnforcementElder;
    if (slotType == "INNER_ELDER") return &slots.innerElder;
    if (slotType == "RECRUITING") return &slots.recruitingElder;
    if (slotType == "CLOUD_PREACHING") return &slots.qingyunPreachingElder;
    return nullptr;
}

/// 任命时被清空的亲传列表（ElderManagementUseCase
/// SLOT_TYPES_CLEARING_DIRECT_DISCIPLES 六类；其余类型 nullptr = 不清列表）。
/// spiritMineDeaconDisciples（第 7 列表）不在任命清空族——仅全槽清理触达。
inline std::vector<DirectDiscipleSlot>* elderClearedListOf(
    ElderSlots& slots, const std::string& slotType) {
    if (slotType == "HERB_GARDEN") return &slots.herbGardenDisciples;
    if (slotType == "ALCHEMY") return &slots.alchemyDisciples;
    if (slotType == "FORGE") return &slots.forgeDisciples;
    if (slotType == "PREACHING") return &slots.preachingMasters;
    if (slotType == "LAW_ENFORCEMENT") return &slots.lawEnforcementDisciples;
    if (slotType == "CLOUD_PREACHING") return &slots.qingyunPreachingMasters;
    return nullptr;
}

/// 亲传列表在岗弟子 id（Kotlin getClearedDirectList：discipleId.ifEmpty 跳过）
inline std::vector<std::string> directListIds(
    const std::vector<DirectDiscipleSlot>& list) {
    std::vector<std::string> ids;
    for (const auto& slot : list) {
        if (!slot.discipleId.empty()) ids.push_back(slot.discipleId);
    }
    return ids;
}

}  // namespace detail

// ── 结果信封（失败零写入；failure → Kotlin 回退原路径重执行校验链）────────

struct TxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
};

/// 长老任命结果：附被顶替者 id（原样追加序 = Kotlin collectReplacedIds：
/// 旧长老在前 + 被清空亲传列表成员在后；distinct 与 != appointee 过滤在
/// Kotlin 残差 releaseReplacedIds 应用）
struct ElderAppointOutcome {
    TxResult base;
    std::vector<std::string> replacedIds;
};

/// 长老卸任结果：附被卸任者 id（空串 = 槽原本无人）
struct ElderDismissOutcome {
    TxResult base;
    std::string removedId;
};

// ── 事务 1：长老单值槽任命（ElderManagementUseCase.assignElder 写段）──────
//
// 判定序（Kotlin 原序）：弟子存在 → 存活 →（Kotlin 残差已先行执行
// releaseDiscipleFromAllSlotsAtomic——本事务内自带同语义数据清理段，双臂
// 幂等）→ 全槽清理（10 类，includeResidence=false）→ 捕获被顶替者（清后、
// 覆写前）→ 写槽位字段 + 清空对应亲传列表（六类）。
inline ElderAppointOutcome elderAppointTx(gamecore::state::GameState& state,
                                          const std::string& slotType,
                                          const std::string& discipleId) {
    ElderAppointOutcome out;
    auto& ds = state.disciples;
    auto& gd = state.gameData;

    // 1. 校验链（Kotlin UseCase 预校验同序：存在 → 存活）。
    //    **原样字符串相等语义**——Kotlin `disciples.find { it.id == discipleId }`
    //    不做 toIntOrNull canonical 化，"0123" 类非 canonical 输入在此不存在。
    const auto row = ds.rowOf(discipleId);
    if (!row.has_value()) {
        out.base.errorType = "NotFound";
        out.base.message = "弟子不存在 " + discipleId;
        return out;
    }
    if (ds.isAlive[*row] == 0) {
        out.base.errorType = "NotAlive";
        out.base.message = "弟子已死亡 " + discipleId;
        return out;
    }
    // 2. 槽位类型解析（ElderSlotType 为十值枚举，未知 = 协议违规防御臂）
    std::string* field = detail::elderFieldOf(gd.elderSlots, slotType);
    if (field == nullptr) {
        out.base.errorType = "UnknownSlotType";
        out.base.message = "未知长老槽位 " + slotType;
        return out;
    }
    // 3. 全槽清理（appointee 旧槽位数据段——Kotlin releaseDiscipleFromAllSlots
    //    Atomic 数据面等价，原样字符串匹配；Gate/状态重置残差留 Kotlin）
    detail::clearAllDiscipleSlots(state, discipleId);
    // 4. 捕获被顶替者（清后、覆写前——Kotlin collectReplacedIds 快照序）
    if (!field->empty() && *field != discipleId) out.replacedIds.push_back(*field);
    if (std::vector<gamecore::state::DirectDiscipleSlot>* cleared =
            detail::elderClearedListOf(gd.elderSlots, slotType)) {
        for (const std::string& id : detail::directListIds(*cleared)) {
            out.replacedIds.push_back(id);
        }
    }
    // 5. 写段：槽位字段 + 清空对应亲传列表（buildElderSlotsWithAppointment
    //    等价——写入原样 discipleId，与 Kotlin 快照组装同源）
    *field = discipleId;
    if (std::vector<gamecore::state::DirectDiscipleSlot>* cleared =
            detail::elderClearedListOf(gd.elderSlots, slotType)) {
        cleared->clear();
    }
    out.base.ok = true;
    return out;
}

// ── 事务 2：长老单值槽卸任（ElderManagementUseCase.removeElder 写段）──────
//
// 写段：字段清空 + 对应亲传列表清空（六类）。**不**做全槽清理（Kotlin 原样）
// ——被卸任者的其他槽位由其自身分配流清理。
inline ElderDismissOutcome elderDismissTx(gamecore::state::GameState& state,
                                          const std::string& slotType) {
    ElderDismissOutcome out;
    auto& gd = state.gameData;
    std::string* field = detail::elderFieldOf(gd.elderSlots, slotType);
    if (field == nullptr) {
        out.base.errorType = "UnknownSlotType";
        out.base.message = "未知长老槽位 " + slotType;
        return out;
    }
    out.removedId = *field;
    field->clear();
    if (std::vector<gamecore::state::DirectDiscipleSlot>* cleared =
            detail::elderClearedListOf(gd.elderSlots, slotType)) {
        cleared->clear();
    }
    out.base.ok = true;
    return out;
}

}  // namespace gamecore::system::appointment_tx
