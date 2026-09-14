#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/secret_realm_settlement.h"  // closeSecretRealmByExpiry /
                                                      // SecretRealmCloseDraft / kOpenYears
#include "gamecore/system/slot_cleanup.h"             // clearAllSlotsDataOnly（11 类槽位）

// ============================================================
// secret_realm_residual_tx.h — 秘境域残差事务（W4-C · w3-08 秘境残差下沉）
//
// 承接 Kotlin 两处稳态写者（语义权威 = 各 Kotlin 源，判定序逐字对齐）：
//
//  ① secretRealmStartReleaseTx（1800 SECRET_REALM_START_RELEASE_TX）
//     = GameEngineAtomicAssign.releaseDiscipleToIdleInside 的 GameData 写段
//     （startSecretRealmExploration native 臂的换岗清理残差）：
//     11 类槽位清理（clearAllSlotsDataOnly，includeResidence=false——住所与
//     工作共存语义）+ 状态重置（REFLECTING → 清思过标记 / REFINING →
//     清 buildingId（视为放弃血炼不返还材料）/ 其余 → IDLE）。
//     DiscipleAssignmentGate.release 与 Room 生产槽清槽为 Kotlin 平台域
//     （finalizeSecretRealmTeam 既有收尾，不纳入本事务）。
//
//  ② secretRealmExpiryGuardTx（1801 SECRET_REALM_EXPIRY_GUARD_TX）
//     = GameEngineSecretRealmNativeOps.rejectIfSecretRealmExpired 的快照判定
//     + SecretRealmService.closeSecretRealmByExpiry 的状态段：
//     未到期（秘境不存在 ∨ year < spawnYear + kOpenYears）→ expired=false
//     成功零写入；到期 → secret_realm_settle::closeSecretRealmByExpiry
//     （灵石入钱包 + 背包清空 + 会话/秘境/AI 队伍清场 + 冷却年 + SECT 事件）
//     + 关闭草稿（memberIds + 背包清空前快照 + slotId）。
//     关闭邮件重建（applyExpiryCloseDraft——buildExpiryCloseMail +
//     sendDirectMail）与 gate 释放留 Kotlin（SECRET_REALM_CONTINUE_TX 的
//     EXPIRED 行动扇出同通道）。
//
//  ③ recordSecretRealmBattleReport（:263 战报写回）**登记不下沉**：
//     battleLogs 为 Kotlin 显示域（batch-20b/S6 决策③既有口径），
//     rebuildBattleLogData 重建面不入 C++ 状态。
//
// RNG 契约（对拍命门）：两事务**全族零 RNG**——校验链/写路径均无抽取；
// GTest 以全分区 rngStates 快照差分守护。
// 失败臂零写入：① 无失败臂（不存在/不可解析 id 静默跳过——Kotlin 同）；
// ② 无失败臂（未到期 = 成功零写入 expired=false）。
// ============================================================
namespace gamecore::system {
namespace secret_realm_residual_tx {

using gamecore::state::GameState;

/// DiscipleStatus.name 字面量（statuses 列存 .name 字符串）
inline constexpr const char* kIdleStatusName = "IDLE";
inline constexpr const char* kReflectingStatusName = "REFLECTING";
inline constexpr const char* kRefiningStatusName = "REFINING";

/// 思过/血炼 statusData key（DiscipleStatusData 单一来源同名键）
inline constexpr const char* kReflectionStartYearKey = "reflectionStartYear";
inline constexpr const char* kReflectionEndYearKey = "reflectionEndYear";
inline constexpr const char* kRefiningBuildingIdKey = "buildingId";

// ── ① 出发换岗（releaseDiscipleToIdleInside 的 GameData 段）──────────

struct SecretRealmStartReleaseOutcome {
    bool ok = false;    // 恒 true（静默跳过臂同 Kotlin）
    std::vector<std::string> releasedIds;   // 实际清理的弟子（日志面）
};

inline SecretRealmStartReleaseOutcome secretRealmStartReleaseTx(
    GameState& state, const std::vector<std::string>& memberIds) {
    SecretRealmStartReleaseOutcome out;
    out.ok = true;
    auto& gd = state.gameData;
    auto& ds = state.disciples;
    for (const auto& memberId : memberIds) {
        // Kotlin：toIntOrNull 失败 / 不在册 → 静默返回
        const auto rowOpt = ds.rowOf(memberId);
        if (!rowOpt.has_value()) continue;

        // 11 类槽位清理（DiscipleSlotCleanup.clearAllSlotsDataOnly——
        // includeResidence=false：住所与工作共存是有意设计）
        gamecore::system::SlotCleanupInput in;
        in.spiritMineSlots = gd.spiritMineSlots;
        in.librarySlots = gd.librarySlots;
        in.elderSlots = gd.elderSlots;
        in.residenceSlots = gd.residenceSlots;
        in.activeBloodRefinements = gd.activeBloodRefinements;
        in.patrolSlots = gd.patrolSlots;
        in.warehouseGarrisons = gd.warehouseGarrisons;
        in.battleTeams = gd.battleTeams;
        in.worldMapSects = gd.worldMapSects;
        in.productionSlots = gd.productionSlots;
        in.caveExplorationTeams = gd.caveExplorationTeams;
        in.activeMissions = gamecore::system::toMissionLiteList(gd.activeMissions);
        const auto cleaned =
            gamecore::system::clearAllSlotsDataOnly(in, memberId, false);
        gd.spiritMineSlots = std::move(cleaned.spiritMineSlots);
        gd.librarySlots = std::move(cleaned.librarySlots);
        gd.elderSlots = cleaned.elderSlots;
        gd.residenceSlots = std::move(cleaned.residenceSlots);
        gd.activeBloodRefinements = std::move(cleaned.activeBloodRefinements);
        gd.patrolSlots = std::move(cleaned.patrolSlots);
        gd.warehouseGarrisons = std::move(cleaned.warehouseGarrisons);
        gd.battleTeams = std::move(cleaned.battleTeams);
        gd.productionSlots = std::move(cleaned.productionSlots);
        gd.caveExplorationTeams = std::move(cleaned.caveExplorationTeams);
        gd.activeMissions = gamecore::system::mergeMissionLiteList(
            gd.activeMissions, cleaned.activeMissions);
        gd.worldMapSects = std::move(cleaned.worldMapSects);

        // 状态重置（Kotlin releaseDiscipleToIdleInside 的 when 分支）
        const std::size_t row = *rowOpt;
        if (ds.statuses[row] == kReflectingStatusName) {
            ds.statusData[row].erase(kReflectionStartYearKey);
            ds.statusData[row].erase(kReflectionEndYearKey);
            ds.statuses[row] = kIdleStatusName;
        } else if (ds.statuses[row] == kRefiningStatusName) {
            ds.statusData[row].erase(kRefiningBuildingIdKey);
            ds.statuses[row] = kIdleStatusName;
        } else {
            ds.statuses[row] = kIdleStatusName;
        }
        out.releasedIds.push_back(memberId);
    }
    return out;
}

// ── ② 到期兜底（rejectIfSecretRealmExpired + closeSecretRealmByExpiry）──

struct SecretRealmExpiryGuardOutcome {
    bool ok = false;      // 恒 true（本事务无失败臂）
    bool expired = false; // false = 未到期（成功零写入——Kotlin 返回 null 放行）
    int32_t slotId = 0;   // 关闭邮件归属存档槽位（GameData.currentSlot）
    std::vector<std::string> memberIds;                    // gate release 面
    gamecore::state::SecretRealmBackpack backpack;         // 关闭邮件附件（清空前快照）
};

inline SecretRealmExpiryGuardOutcome secretRealmExpiryGuardTx(GameState& state) {
    SecretRealmExpiryGuardOutcome out;
    out.ok = true;
    auto& gd = state.gameData;
    // 快照判定（Kotlin rejectIfSecretRealmExpired 前置：!exists ∨ 未到期 → null）
    const bool exists = !gd.secretRealmState.id.empty();
    if (!exists || gd.gameYear < gd.secretRealmState.spawnYear +
                       secret_realm_settle::kOpenYears) {
        return out;   // expired=false，零写入
    }
    // 到期关闭（secret_realm_settle 状态段复用——月变 processMonthlyExpiryCheck
    // 同源：灵石入钱包/背包清空/会话清场/冷却年/SECT 事件）
    secret_realm_settle::SecretRealmCloseDraft draft;
    secret_realm_settle::closeSecretRealmByExpiry(state, &draft);
    out.expired = true;
    out.slotId = gd.currentSlot;
    out.memberIds = std::move(draft.memberIds);
    out.backpack = std::move(draft.backpack);
    return out;
}

}  // namespace secret_realm_residual_tx
}  // namespace gamecore::system
