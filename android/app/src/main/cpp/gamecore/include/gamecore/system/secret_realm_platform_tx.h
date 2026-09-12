// ============================================================
// secret_realm_platform_tx.h — 秘境平台段读档恢复事务（batch-20a）
//
// 下沉 Kotlin GameEngine.continueSecretRealmExploration 的**会话域判定段**
//（写者审计收窄批面：start/choose/end 已于 S6 下沉——SECRET_REALM_START/
// CHOOSE/END；autoAssignSecretRealmTeam 为纯只读选择器（无写者）；
// pauseForSecretRealm / resumeFromSecretRealm / renewSecretRealmPauseLease
// 为运行时时钟平台残差（S5/S6 口径留 Kotlin））。
//
// 判定序与 Kotlin continueSecretRealmExploration 逐位一致：
//   ① 到期守卫：秘境存在且 gameYear >= spawnYear + OPEN_YEARS(=5) →
//      closeSecretRealmByExpiry（状态段复用 secret_realm_settlement.h——
//      灵石入钱包 + 背包清空 + 会话/秘境/AI 队伍清场 + 冷却年 + 事件）；
//      关闭草稿（memberIds + 背包快照）经信封回传 Kotlin 邮件/gate 段
//      （与月变 applyExpiryCloseDraft 通道同构）→ canContinue=false；
//   ② 死局防御：会话不 active（members 空）/ 秘境不存在 / secretRealmId
//      不匹配 → 会话 active 时 endSession(EXPLORER_END)（背包结算入仓 +
//      溢出草稿回传——Kotlin secretRealmService.endSession 默认 reason
//      同为 EXPLORER_END）→ canContinue=false；
//   ③ 成员净化：aliveIds = DiscipleStore(ids ∧ isAlive) 集合；
//      validMembers = members 过滤 (!isDead && discipleId ∈ aliveIds)；
//      空 → endSession(EXPLORER_END) → canContinue=false（RESET）；
//      少于原成员 → 写回 members → canContinue=true（PURIFIED）；
//   ④ 否则 canContinue=true（NONE）。
//
// 平台残差（保留 Kotlin，信封/快照输入）：assignmentGate.confirmAssign /
// release（纯内存注册表）、溢出邮件投递（deliverOverflowDrafts）、
// 关闭邮件重建（buildExpiryCloseMail + sendDirectMail）、状态同步。
//
// RNG 契约：全程零 RNG 抽取（到期/死局/净化均为确定性变换）——
// GTest 以全分区快照差分锁定（rng.exportStates() 前后逐位一致）。
//
// 失败零写入：本事务无失败臂（判定即行动作），任一分支未触发的状态段
// 不被触碰；①②③的关闭/结算/净化均为 Kotlin 原路径同序同语义执行。
// ============================================================
#pragma once

#include <cstdint>
#include <optional>
#include <set>
#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/inventory.h"                  // OverflowMailCollector / OverflowDraft
#include "gamecore/system/secret_realm.h"               // kOpenYears（=5 权威值）
#include "gamecore/system/secret_realm_session.h"       // endSession / kEndExplorerEnd
#include "gamecore/system/secret_realm_settlement.h"    // closeSecretRealmByExpiry / SecretRealmCloseDraft

namespace gamecore::system::sr_platform {

using gamecore::state::GameState;

/// 行动作（Kotlin 侧据 action 决定平台段扇出）
inline constexpr const char* kContinueActionNone = "NONE";            // ④ 无会话变更直接继续
inline constexpr const char* kContinueActionPurified = "PURIFIED";    // ③ 成员净化已写回
inline constexpr const char* kContinueActionReset = "RESET";          // ②③ 死局/空净化 → 会话已结束
inline constexpr const char* kContinueActionExpired = "EXPIRED";      // ① 到期关闭

/// 读档恢复会话域判定结果（nativeExecute 信封输入）
struct SecretRealmContinueOutcome {
    bool canContinue = false;
    const char* action = kContinueActionNone;
    /// RESET/EXPIRED：gate 释放面（Kotlin assignmentGate.release）
    std::set<std::string> releasedMemberIds;
    /// RESET：endSession 背包结算溢出草稿（Kotlin deliverOverflowDrafts 投递）
    std::vector<gamecore::system::OverflowDraft> overflowDrafts;
    /// EXPIRED：关闭草稿（memberIds → gate release；backpack → 关闭邮件附件——
    /// 与月变 nativeSettleMonth 信封 secretRealmClose 段同构）
    std::optional<secret_realm_settle::SecretRealmCloseDraft> closeDraft;
};

/// 读档恢复会话域判定（Kotlin GameEngine.continueSecretRealmExploration
/// 会话域判定段等价移植；零 RNG）。
inline SecretRealmContinueOutcome continueSessionTx(GameState& state) {
    SecretRealmContinueOutcome out;
    auto& gd = state.gameData;
    const bool realmExists = !gd.secretRealmState.id.empty();
    const bool sessionActive = !gd.secretRealmSession.members.empty();

    // ① 到期守卫（Kotlin 入口防御前置——月结被绕过的极端兜底）：现世期满
    //    （spawnYear + OPEN_YEARS）→ 关闭秘境并拒绝继续
    if (realmExists &&
        gd.gameYear >= gd.secretRealmState.spawnYear +
                           gamecore::system::secret_realm_cfg::kOpenYears) {
        out.closeDraft = secret_realm_settle::SecretRealmCloseDraft{};
        secret_realm_settle::closeSecretRealmByExpiry(state, &*out.closeDraft);
        out.action = kContinueActionExpired;
        out.releasedMemberIds.insert(out.closeDraft->memberIds.begin(),
                                     out.closeDraft->memberIds.end());
        return out;
    }

    // ② 死局防御：会话不 active / 秘境不存在 / secretRealmId 不匹配 →
    //    残留会话结算清空（EXPLORER_END 语义——与 Kotlin endSession 默认
    //    reason 一致），避免永久无法再探索
    if (!sessionActive || !realmExists ||
        gd.secretRealmSession.secretRealmId != gd.secretRealmState.id) {
        if (sessionActive) {
            gamecore::system::OverflowMailCollector overflowMail;
            out.releasedMemberIds = sr_session::endSession(
                state, sr_session::kEndExplorerEnd, overflowMail);
            out.overflowDrafts = overflowMail.all();
        }
        out.action = kContinueActionReset;
        return out;
    }

    // ③ 成员净化：aliveIds = 弟子表 isAlive 集合（DiscipleStore SoA——
    //    ids ∧ isAlive 平行列；Kotlin discipleTables.assembleAll().filter
    //    { it.isAlive } 同源）；移除已永久死亡/已不存在的成员
    std::set<std::string> aliveIds;
    const auto& store = state.disciples;
    for (std::size_t i = 0; i < store.ids.size(); ++i) {
        if (store.isAlive[i] != 0) aliveIds.insert(store.ids[i]);
    }
    std::vector<state::SecretRealmMemberState> validMembers;
    for (const auto& m : gd.secretRealmSession.members) {
        if (!m.isDead && aliveIds.count(m.discipleId) != 0) {
            validMembers.push_back(m);
        }
    }
    if (validMembers.empty()) {
        // 净化后为空 → 自动结算结束
        gamecore::system::OverflowMailCollector overflowMail;
        out.releasedMemberIds = sr_session::endSession(
            state, sr_session::kEndExplorerEnd, overflowMail);
        out.overflowDrafts = overflowMail.all();
        out.action = kContinueActionReset;
        return out;
    }
    if (validMembers.size() != gd.secretRealmSession.members.size()) {
        // 写回净化后成员（镜像回写经 dirty tracker 增量通道）
        gd.secretRealmSession.members = std::move(validMembers);
        out.action = kContinueActionPurified;
    }

    // ④ 可继续（NONE：无会话变更 / PURIFIED：净化已写回）
    out.canContinue = true;
    return out;
}

}  // namespace gamecore::system::sr_platform
