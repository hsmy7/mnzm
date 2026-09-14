/**
 * dispatch_w4c.cpp — **W4-C 批次独占**的 ActionId 分派端口（战斗与世界协议轴）。
 *
 * 🔴 本文件由 W4-00（并行前置批）建立骨架，此后**只有 W4-C 可写**。
 *    W4-A / W4-B 与任何其他工作一律不得改本文件。
 *
 * ## 已实裁段（docs/parallel-batches-w4/README.md §6）
 *   1780 BATTLE_CASUALTY_SETTLE_TX      战斗伤亡残差（battle_residual_tx.h ①）
 *   1781 WORLD_VICTORY_REWARDS_TX       关卡胜利事务（同头 ②；不写 defeated）
 *   1782 BATTLE_PRESETTLE_TX            战前突破结算（同头 ③）
 *   1800 SECRET_REALM_START_RELEASE_TX  秘境出发换岗（secret_realm_residual_tx.h ①）
 *   1801 SECRET_REALM_EXPIRY_GUARD_TX   秘境到期兜底（同头 ②）
 *
 *   1790–1799 未实裁（w3-07 三处登记不下沉——撕裂事务/原子性不可拆，见
 *   w4c.mjs 段注与 W4CChannelClosures 证据）；段内未实裁号一律返回
 *   std::nullopt（交 execute_dispatch NOT_IMPLEMENTED 兜底，与
 *   dispatch_guard_test 的"未注册号不可达"判据一致）。
 *   1855–1859 条件段退段维持空置（C7 走确定性化路线，handover §2.64.1）。
 *
 * ## 契约
 * 见 `include/gamecore/dispatch_w4.h`：认领 ⇒ 返回完整结果信封
 * {status:"success",data:{…}} / {status:"failure",code,message}；
 * 不认领 ⇒ `std::nullopt`。端口内禁取系统时间（墙钟一律参数传入）。
 */

#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/dispatch_w4.h"
#include "gamecore/action_ids.h"
#include "gamecore/game_core.h"   // GameCore 完整类型（dispatch_w4.h 仅前向声明）
#include "gamecore/state/json_codec.h"  // state::to_json（backpack 草稿序列化）
#include "gamecore/system/battle_residual_tx.h"
#include "gamecore/system/secret_realm_residual_tx.h"

namespace gamecore {

namespace {

nlohmann::json ok(nlohmann::json data) {
    return {{"status", "success"}, {"data", std::move(data)}};
}

nlohmann::json invalidParams(const char* detail) {
    return {{"status", "failure"},
            {"code", "INVALID_PARAMS"},
            {"message", detail}};
}

bool getStringArray(const nlohmann::json& j, const char* key,
                    std::vector<std::string>* out) {
    const auto it = j.find(key);
    if (it == j.end() || !it->is_array()) return false;
    for (const auto& v : *it) {
        if (!v.is_string()) return false;
        out->push_back(v.get<std::string>());
    }
    return true;
}

/// {"id": hp} 对象 → map（Kotlin survivorHpMap/MpMap 同形；缺省 = 空表）
bool getIntMap(const nlohmann::json& j, const char* key,
               std::map<std::string, int32_t>* out) {
    const auto it = j.find(key);
    if (it == j.end()) return true;
    if (!it->is_object()) return false;
    for (auto entry = it->begin(); entry != it->end(); ++entry) {
        if (!entry.value().is_number_integer()) return false;
        (*out)[entry.key()] = entry.value().get<int32_t>();
    }
    return true;
}

nlohmann::json lifeEventDraftsJson(
    const std::vector<gamecore::system::battle_residual_tx::LifeEventDraft>& ds) {
    nlohmann::json arr = nlohmann::json::array();
    for (const auto& d : ds) {
        arr.push_back({{"id", d.discipleId}, {"line", d.line}});
    }
    return arr;
}

nlohmann::json overflowDraftsJson(
    const std::vector<gamecore::system::OverflowDraft>& ds) {
    nlohmann::json arr = nlohmann::json::array();
    for (const auto& d : ds) {
        arr.push_back({{"itemType", d.itemType},
                       {"itemName", d.itemName},
                       {"itemId", d.itemId},
                       {"rarity", d.rarity},
                       {"quantity", d.quantity},
                       {"source", d.source}});
    }
    return arr;
}

nlohmann::json idArrayJson(const std::vector<std::string>& ids) {
    nlohmann::json arr = nlohmann::json::array();
    for (const auto& id : ids) arr.push_back(id);
    return arr;
}

// ── 1780–1789 · w3-06 战斗/探索残差（battle_residual_tx.h）─────────────

nlohmann::json handleBattleResidualTx(GameCore& core, int32_t actionId,
                                      const nlohmann::json& p) {
    using namespace gamecore::system::battle_residual_tx;
    auto& state = core.state();
    switch (actionId) {
        case action::BATTLE_CASUALTY_SETTLE_TX: {
            std::vector<std::string> deadIds;
            std::map<std::string, int32_t> survivorHp;
            std::map<std::string, int32_t> survivorMp;
            if (!getStringArray(p, "deadIds", &deadIds) ||
                !getIntMap(p, "survivorHp", &survivorHp) ||
                !getIntMap(p, "survivorMp", &survivorMp)) {
                return invalidParams(
                    "casualty settle requires deadIds/survivorHp/survivorMp");
            }
            const bool isOutsideSect = p.value("isOutsideSect", true);
            gamecore::system::OverflowMailCollector overflowMail;
            const auto r = settleBattleCasualtiesTx(
                state, deadIds, survivorHp, survivorMp, isOutsideSect, overflowMail);
            (void)r.ok;   // 无失败臂；恒成功
            return ok({{"markedDeadIds", idArrayJson(r.markedDeadIds)},
                       {"lifeEventDrafts", lifeEventDraftsJson(r.lifeEvents)},
                       {"overflowDrafts", overflowDraftsJson(r.overflowDrafts)}});
        }
        case action::WORLD_VICTORY_REWARDS_TX: {
            std::vector<std::string> survivorIds;
            const auto levelIt = p.find("levelId");
            if (levelIt == p.end() || !levelIt->is_string() ||
                !getStringArray(p, "survivorIds", &survivorIds)) {
                return invalidParams("victory requires levelId/survivorIds");
            }
            const auto r = worldLevelVictoryTx(state, core.rng(), core.ecsWorld(),
                                               levelIt->get<std::string>(),
                                               survivorIds);
            return ok({{"applied", r.applied},
                       {"soulPowerCount", r.soulPowerCount},
                       {"winAttrCount", r.winAttrCount}});
        }
        case action::BATTLE_PRESETTLE_TX: {
            std::vector<std::string> discipleIds;
            if (!getStringArray(p, "discipleIds", &discipleIds)) {
                return invalidParams("presettle requires discipleIds");
            }
            const auto r = battlePresettleTx(state, core.rng(), discipleIds);
            return ok({{"candidateCount", r.candidateCount},
                       {"lifeEventDrafts", lifeEventDraftsJson(r.lifeEvents)}});
        }
        default:
            return {{"status", "failure"},
                    {"code", "UNKNOWN_ACTION"},
                    {"message", "unhandled battle residual action"}};
    }
}

// ── 1800–1809 · w3-08 秘境残差（secret_realm_residual_tx.h）───────────

nlohmann::json handleSecretRealmResidualTx(GameCore& core, int32_t actionId,
                                           const nlohmann::json& p) {
    using namespace gamecore::system::secret_realm_residual_tx;
    auto& state = core.state();
    switch (actionId) {
        case action::SECRET_REALM_START_RELEASE_TX: {
            std::vector<std::string> memberIds;
            if (!getStringArray(p, "memberIds", &memberIds)) {
                return invalidParams("start release requires memberIds");
            }
            const auto r = secretRealmStartReleaseTx(state, memberIds);
            return ok({{"releasedIds", idArrayJson(r.releasedIds)}});
        }
        case action::SECRET_REALM_EXPIRY_GUARD_TX: {
            const auto r = secretRealmExpiryGuardTx(state);
            nlohmann::json data = {{"expired", r.expired}, {"slotId", r.slotId}};
            data["memberIds"] = idArrayJson(r.memberIds);
            if (r.expired) {
                nlohmann::json bp;
                gamecore::state::to_json(bp, r.backpack);
                data["backpack"] = std::move(bp);
            }
            return ok(std::move(data));
        }
        default:
            return {{"status", "failure"},
                    {"code", "UNKNOWN_ACTION"},
                    {"message", "unhandled secret realm residual action"}};
    }
}

}  // namespace

std::optional<nlohmann::json> dispatchW4C(GameCore& core, int32_t actionId,
                                          const nlohmann::json& params) {
    // ── W4-C 分派区（本区仅 W4-C 可写；预分配段 1780–1789 / 1790–1799 /
    //    1800–1809 / 1855–1859）────────────────────────────────────────────
    if (actionId >= 1780 && actionId <= 1789) {
        return handleBattleResidualTx(core, actionId, params);
    }
    if (actionId >= 1800 && actionId <= 1809) {
        return handleSecretRealmResidualTx(core, actionId, params);
    }
    // 1790–1799（w3-07 登记不下沉）与 1855–1859（条件段退段）不认领。
    return std::nullopt;
}

}  // namespace gamecore
