#pragma once

// ============================================================
// 秘境域月结下沉（S8 子事件 15/16：secretRealmExpiry + secretRealmAiTeams）
//
//   - 子事件 16 secretRealmAiTeams：Kotlin SecretRealmAIProcessor.
//     processMonthlyAiTeams 等价移植（纯数据变换，零 RNG）
//   - 子事件 15 secretRealmExpiry：Kotlin SecretRealmService.
//     processMonthlyExpiryCheck 的**可移植子集**（到期判定 + 灵石入钱包 +
//     背包清空 + 会话结束状态段）——邮件投递（异步落库）与 assignment gate
//     （纯内存注册表）保留 Kotlin
//
// RNG 契约：两者均零 RNG 抽取。
//
// 已知边界：
// - aiSectDisciples 迭代序：Kotlin LinkedHashMap（插入序）vs C++ std::map
//   （键升序）——队伍生成序可能不同；对拍场景以键升序构造规避，生产镜像
//   侧以 C++ 导出为准（顺序归一化，无数据丢失）
// - 队伍 id 用确定性自增（Kotlin UUID，语义等价——同 inventory.h generateNewId）
// - secretRealmExpiry 的邮件（buildExpiryCloseMail + sendDirectMail）与
//   assignmentGate.release（纯内存注册表）保留 Kotlin；C++ 侧到期关闭只做
//   状态段（钱包/背包/会话）——月变真相源切换时邮件经草稿通道接线
// ============================================================

#include <algorithm>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/system/inventory.h"  // nextItemIdCounter（id 注册表）
#include "gamecore/state/models.h"
#include "gamecore/system/economy.h"
#include "gamecore/system/settlement_detail.h"

namespace gamecore::system::secret_realm_settle {

using gamecore::state::Disciple;
using gamecore::state::GameData;
using gamecore::state::GameState;
using gamecore::state::SecretRealmAIMember;
using gamecore::state::SecretRealmAITeam;
namespace settle_util = gamecore::system::settle_util;

/// AI 队伍人数上限（GameConfig.SecretRealm.AI_TEAM_SIZE）
constexpr int32_t kAiTeamSize = 4;
/// 秘境现世年数（GameConfig.SecretRealm.OPEN_YEARS）
/// 勘误（batch-20a）：原值 50 误取 COOLDOWN_YEARS（开启周期）——Kotlin
/// 权威值为 OPEN_YEARS = 5（GameConfig.kt）；到期判定应与 secret_realm.h
/// kOpenYears = 5 同源。此前 AUTHORITATIVE 模式月结到期检查晚关 45 年。
constexpr int32_t kOpenYears = 5;

/// 秘境到期关闭草稿（月变真相源切换后 Kotlin 侧不再执行
/// processMonthlyExpiryCheck——本结构承载 C++ 侧关闭时的平台效应输入：
/// 关闭邮件附件（背包清空前快照）与 assignmentGate.release 的 memberIds）。
/// 定义于本属主文件（closeSecretRealmByExpiry 内部填充），
/// month_settlement.h 的 MonthSettlementResult 引用它。
struct SecretRealmCloseDraft {
    bool closed = false;                   // 本次月变是否实际发生秘境到期关闭
    std::vector<std::string> memberIds;    // gate release（Kotlin 纯内存注册表）
    state::SecretRealmBackpack backpack;   // 关闭邮件附件（清空前快照）
    int32_t slotId = 0;                    // 邮件归属存档槽位（GameData.currentSlot）
};

/// 队伍 id 生成（确定性自增——Kotlin UUID，语义等价：仅保证唯一）
inline std::string nextTeamId() {
    // 进程级 static 计数器收敛到 inventory.h 注册表
    return "gc-sr-team-" + std::to_string(nextItemIdCounter("gc-sr-team"));
}

/// 子事件 16：秘境 AI 队伍月度派遣（Kotlin SecretRealmAIProcessor.
/// processMonthlyAiTeams 等价移植；幂等去重——已派遣宗门不重复派遣）
inline void processMonthlyAiTeams(GameState& state) {
    auto& gd = state.gameData;
    const bool exists = !gd.secretRealmState.id.empty();
    if (!exists) return;

    std::set<std::string> existingSectIds;
    for (const auto& t : gd.secretRealmAITeams) existingSectIds.insert(t.sectId);

    // 待派遣宗门（未派遣 + 有存活弟子）；aiSectDisciples 为 GameState 顶层
    // std::map 键升序（Kotlin LinkedHashMap 插入序——顺序归一化，见文件头边界）
    std::vector<std::string> pendingSects;
    for (const auto& kv : state.aiSectDisciples) {
        if (existingSectIds.count(kv.first) != 0) continue;
        bool anyAlive = false;
        for (const auto& d : kv.second) {
            if (d.isAlive) { anyAlive = true; break; }
        }
        if (anyAlive) pendingSects.push_back(kv.first);
    }
    if (pendingSects.empty()) return;

    std::vector<SecretRealmAITeam> newTeams;
    for (const auto& sectId : pendingSects) {
        const auto& disciples = state.aiSectDisciples.at(sectId);
        // 找宗门（可能不存在 → sectName/sectLevel 回退）
        std::string sectName = sectId;
        int32_t sectLevel = 0;
        for (const auto& s : gd.worldMapSects) {
            if (s.id == sectId) {
                sectName = s.name;
                sectLevel = s.level;
                break;
            }
        }
        // 存活弟子按境界升序（Kotlin sortedBy 稳定）取前 4
        std::vector<const Disciple*> alive;
        for (const auto& d : disciples) {
            if (d.isAlive) alive.push_back(&d);
        }
        std::stable_sort(alive.begin(), alive.end(),
                         [](const Disciple* a, const Disciple* b) {
                             return a->realm < b->realm;
                         });
        SecretRealmAITeam team;
        team.id = nextTeamId();
        team.sectId = sectId;
        team.sectName = sectName;
        team.sectLevel = sectLevel;
        const std::size_t count = std::min<std::size_t>(alive.size(),
                                                        static_cast<std::size_t>(kAiTeamSize));
        for (std::size_t i = 0; i < count; ++i) {
            SecretRealmAIMember m;
            m.discipleId = alive[i]->id;
            m.name = alive[i]->name;
            m.portraitRes = alive[i]->portraitRes;
            m.realm = alive[i]->realm;
            team.members.push_back(std::move(m));
        }
        newTeams.push_back(std::move(team));
    }
    if (!newTeams.empty()) {
        gd.secretRealmAITeams.insert(gd.secretRealmAITeams.end(),
                                     newTeams.begin(), newTeams.end());
    }
}

/// 到期关闭（closeSecretRealmByExpiry 状态段）：
/// ① 幂等守卫（秘境与会话均不存在 → 直接返回）；
/// ② 背包灵石入钱包（LOW/SecretRealm，>0 才 add）；
/// ③ 清空会话背包（先清空再 endSession——settleBackpack 对空背包 no-op，
///    杜绝邮件+入仓双发放）；
/// ④ endSession(EXPIRED)：会话 active → settleBackpack（已空 → no-op）；
///    清空秘境/冷却年=year/会话/AI 队伍 + SECT secret_realm 事件
///
/// @param closeDraft 关闭草稿（可为 null）：实际关闭时填充 memberIds（会话
///   成员 id，供 Kotlin assignmentGate.release）与背包快照（清空前六类物品，
///   供 Kotlin buildExpiryCloseMail 邮件附件）并置 closed=true。
inline void closeSecretRealmByExpiry(GameState& state,
                                     SecretRealmCloseDraft* closeDraft) {
    auto& gd = state.gameData;
    const bool exists = !gd.secretRealmState.id.empty();
    const bool sessionActive = !gd.secretRealmSession.members.empty();
    if (!exists && !sessionActive) return;

    if (closeDraft != nullptr) {
        closeDraft->closed = true;
        for (const auto& m : gd.secretRealmSession.members) {
            closeDraft->memberIds.push_back(m.discipleId);
        }
        // 背包快照（清空前——邮件附件按六类物品逐条）
        closeDraft->backpack = gd.secretRealmSession.backpack;
    }

    if (gd.secretRealmSession.backpack.spiritStones > 0) {
        gamecore::system::SpiritStoneWallet::add(
            gd, gd.secretRealmSession.backpack.spiritStones,
            SpiritStoneGrade::LOW, "SecretRealm");
    }
    // 清空会话背包（邮件通道保留 Kotlin——物品不重复入仓）
    gd.secretRealmSession.backpack = gamecore::state::SecretRealmBackpack{};
    // endSession(EXPIRED)：settleBackpack 空背包 no-op（C++ 跳过）
    const int32_t year = gd.gameYear;
    gd.secretRealmState = gamecore::state::SecretRealmState{};
    gd.secretRealmCooldownYear = year;
    gd.secretRealmSession = gamecore::state::SecretRealmExplorationSession{};
    gd.secretRealmAITeams.clear();
    settle_util::recordGameEvent(
        state, "SECT", "secret_realm",
        "远古秘境现世期满，已自动关闭，探索所得已通过邮件送回");
}

/// 子事件 15：秘境现世期满自动关闭（Kotlin SecretRealmService.
/// processMonthlyExpiryCheck → closeSecretRealmByExpiry 的**可移植状态段**等价移植；
/// 零 RNG）。边界：关闭邮件（buildExpiryCloseMail + sendDirectMail
/// 异步落库）与 assignmentGate.release（纯内存注册表）保留 Kotlin——背包物品
/// 走邮件不回宗门仓库，C++ 侧只做状态段（灵石入钱包 + 背包清空 + 会话/秘境/
/// AI 队伍清场 + 冷却年 + SECT 事件）；草稿经 [closeDraft] 回传，随
/// 月变真相源切换接线（nativeSettleMonth 信封）。
inline void processMonthlyExpiryCheck(GameState& state, int32_t year,
                                      SecretRealmCloseDraft* closeDraft = nullptr) {
    auto& gd = state.gameData;
    const auto& realm = gd.secretRealmState;
    const bool exists = !realm.id.empty();
    if (!exists) return;
    if (year < realm.spawnYear + kOpenYears) return;
    closeSecretRealmByExpiry(state, closeDraft);
}

}  // namespace gamecore::system::secret_realm_settle
