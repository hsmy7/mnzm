/**
 * dispatch_w4a.cpp — **W4-A 批次独占**的 ActionId 分派端口（弟子与建设轴）。
 *
 * 🔴 本文件由 W4-00（并行前置批）建立骨架，此后**只有 W4-A 可写**。
 *    W4-B / W4-C 与任何其他工作一律不得改本文件。
 *
 * ## 预分配段（docs/parallel-batches-w4/README.md §6）
 *   1740–1749 / 1750–1759 / 1810–1819 / 1820–1829 / 1850–1854（条件段）
 *
 * ## 契约（include/gamecore/dispatch_w4.h）
 * 认领 ⇒ 返回完整结果信封（`{"status":"success","data":{...}}` /
 * `{"status":"failure","code":...,"message":...}`）；不认领 ⇒ `std::nullopt`。
 * 端口不抛异常；只处理本批预分配段内的 actionId。
 *
 * ## w3-01 事务族（1740–1748，disciple_tx.h）
 * 失败信封语义：静默守卫（Kotlin silent return）与业务失败均以 failure 信封
 * 回传 ⇒ Kotlin 回退臂重执行同义校验链（双实现并行契约，production.h 同模式）。
 *
 * ## w3-02 事务族（1750–1759，disciple_lifecycle_tx.h）
 * 婚姻拒绝（1750）为 C++ 事件直写事务（零弟子表写入/零 RNG/无失败臂）；
 * 婚姻批准走 batch-14 就绪地基 1592（handler 在 execute_dispatch，非本端口）。
 */

#include "gamecore/dispatch_w4.h"

#include <nlohmann/json.hpp>

#include <vector>

#include "gamecore/action_ids.h"
#include "gamecore/game_core.h"
#include "gamecore/system/building_residual_tx.h"
#include "gamecore/system/disciple_lifecycle_tx.h"
#include "gamecore/system/disciple_tx.h"

namespace gamecore {

namespace {

/// 成功信封（execute_dispatch 匿名空间同款——端口文件独立提供）
nlohmann::json ok(nlohmann::json data) {
    return {{"status", "success"}, {"data", std::move(data)}};
}

/// 失败信封
nlohmann::json fail(const std::string& code, const std::string& message) {
    return {{"status", "failure"}, {"code", code}, {"message", message}};
}

}  // namespace

std::optional<nlohmann::json> dispatchW4A(GameCore& core, int32_t actionId,
                                          const nlohmann::json& params) {
    namespace disciple_tx = gamecore::system::disciple_tx;
    namespace disciple_lifecycle_tx = gamecore::system::disciple_lifecycle_tx;
    auto& state = core.state();

    // ── W4-A 分派区（本区仅 W4-A 可写；预分配段 1740–1749 / 1750–1759 /
    //    1810–1819 / 1820–1829 / 1850–1854）────────────────────────────────
    switch (actionId) {
        // ── w3-01 弟子操作面（1740–1748） ──
        case action::DISCIPLE_OP_RENAME: {
            const auto r = disciple_tx::renameDiscipleTx(
                state, params.at("discipleId").get<std::string>(),
                params.at("newName").get<std::string>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"renamed", true}});
        }
        case action::DISCIPLE_OP_CHANGE_TYPE: {
            const auto r = disciple_tx::changeDiscipleTypeTx(
                state, params.at("discipleId").get<std::string>(),
                params.at("newType").get<std::string>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"changed", true}});
        }
        case action::DISCIPLE_OP_TOGGLE_FOLLOW: {
            const auto r = disciple_tx::toggleFollowTx(
                state, params.at("discipleId").get<std::string>());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"toggled", true},
                       {"followedAfter", r.followedAfter}});
        }
        case action::DISCIPLE_OP_REWARD_ITEM: {
            const auto r = disciple_tx::rewardItemTx(
                state, params.at("discipleId").get<std::string>(),
                params.at("itemType").get<std::string>(),
                params.at("itemId").get<std::string>(),
                params.value("quantity", 1),
                params.value("itemName", ""),
                params.value("itemRarity", 0));
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"rewarded", true},
                       {"theftCandidate", r.theftCandidate},
                       {"moralityAfter", r.moralityAfter}});
        }
        case action::DISCIPLE_OP_USE_PILL: {
            const auto r = disciple_tx::usePillTx(
                state, params.at("discipleId").get<std::string>(),
                params.at("pillId").get<std::string>());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"used", true},
                       {"logLine", r.logLine},
                       {"theftCandidate", r.base.theftCandidate},
                       {"moralityAfter", r.moralityAfter}});
        }
        case action::DISCIPLE_OP_REPLACE_MANUAL: {
            const auto r = disciple_tx::replaceManualTx(
                state, params.at("discipleId").get<std::string>(),
                params.at("oldInstanceId").get<std::string>(),
                params.at("newStackId").get<std::string>());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"replaced", true}, {"logLine", r.logLine}});
        }
        case action::DISCIPLE_OP_START_BLOOD_REFINEMENT: {
            disciple_tx::BloodRefinementStartParams p;
            p.materialName = params.at("materialName").get<std::string>();
            p.materialRarity = params.value("materialRarity", 0);
            p.materialCount = params.value("materialCount", 0);
            p.buildingInstanceId =
                params.at("buildingInstanceId").get<std::string>();
            p.requiredSpiritStones = params.at("requiredSpiritStones").get<int64_t>();
            p.discipleId = params.at("discipleId").get<std::string>();
            p.discipleName = params.value("discipleName", "");
            p.materialId = params.value("materialId", "");
            p.selectedStat = params.value("selectedStat", "");
            p.bonusPercent = params.value("bonusPercent", 0.0);
            p.durationMonths = params.value("durationMonths", 0);
            const auto r = disciple_tx::startBloodRefinementTx(state, p);
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"started", true}});
        }
        case action::DISCIPLE_OP_SYNC_STATUS: {
            const auto r = disciple_tx::syncDiscipleStatusTx(
                state, params.at("discipleId").get<std::string>());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"synced", true}, {"status", r.statusAfter}});
        }
        case action::DISCIPLE_OP_SYNC_ALL_STATUSES: {
            const auto r = disciple_tx::syncAllDiscipleStatusesTx(state);
            return ok({{"synced", true}, {"count", r.syncedCount}});
        }

        // ── w3-02 弟子生命周期第二波（1750–1759） ──
        // 婚姻批准（1592）的 C++ 事务与 handler 为 batch-14 就绪地基
        //（execute_dispatch::handleDiscipleLifecycleTx），本批只做 Kotlin 接线。
        case action::DISCIPLE_LIFECYCLE_MARRY_REJECT: {
            const auto r = disciple_lifecycle_tx::rejectMarriageTransaction(
                state, params.at("maleId").get<std::string>(),
                params.at("femaleId").get<std::string>(),
                params.value("maleName", ""),
                params.value("femaleName", ""));
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"rejected", true}});
        }

        // ── w3-09 建筑/道路残差（1810–1819，building_residual_tx.h） ──
        // 槽组种类/特例标志/生产槽 id 由 Kotlin 门面组装（单一事实源留
        // Kotlin 注册表）；未知组名静默跳过（清扫臂——与 Kotlin "无该槽组
        // 即无行可清"同义）。
        case action::BUILDING_RESIDUAL_CLEAR: {
            namespace residual_tx = gamecore::system::building_residual_tx;
            std::vector<residual_tx::ResidualTarget> targets;
            for (const auto& t : params.at("targets")) {
                residual_tx::ResidualTarget target;
                target.instanceId = t.at("instanceId").get<std::string>();
                target.displayName = t.value("displayName", "");
                target.isMissionHall = t.value("isMissionHall", false);
                target.isReflectionCliff = t.value("isReflectionCliff", false);
                for (const auto& g : t.at("groups")) {
                    residual_tx::SlotGroupKind kind;
                    if (residual_tx::parseSlotGroupKind(g.get<std::string>(), kind)) {
                        target.groups.push_back(kind);
                    }
                }
                if (t.contains("discipleIds")) {
                    for (const auto& d : t.at("discipleIds")) {
                        target.discipleIds.push_back(d.get<std::string>());
                    }
                }
                targets.push_back(std::move(target));
            }
            const auto r = residual_tx::clearResidualTransaction(state, targets);
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"cleared", r.clearedTargets}});
        }
        case action::BUILDING_PLACE_SLOTS: {
            namespace residual_tx = gamecore::system::building_residual_tx;
            residual_tx::PlaceSlotsParams p;
            p.instanceId = params.at("instanceId").get<std::string>();
            p.activeSectId = params.value("sectId", "");
            for (const auto& g : params.at("groups")) {
                residual_tx::SlotGroupKind kind;
                if (!residual_tx::parseSlotGroupKind(
                        g.at("kind").get<std::string>(), kind)) {
                    return fail("Validation",
                                "未知槽组 " + g.at("kind").get<std::string>());
                }
                p.groups.emplace_back(kind, g.value("count", 0));
            }
            const auto r = residual_tx::placeSlotsTransaction(state, p);
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"created", r.createdSlots}});
        }
        default:
            break;
    }
    // 骨架段余量（1751–1759 / 1812–1819 / 1820–1829 / 1850–1854）：
    // 本批尚未产出事务 ⇒ 不认领，交由后续端口或 NOT_IMPLEMENTED 兜底。
    return std::nullopt;
}

}  // namespace gamecore
