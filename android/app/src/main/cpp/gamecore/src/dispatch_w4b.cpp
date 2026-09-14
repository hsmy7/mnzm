/**
 * dispatch_w4b.cpp — **W4-B 批次独占**的 ActionId 分派端口（内政与经济运营轴）。
 *
 * 🔴 本文件由 W4-00（并行前置批）建立骨架，此后**只有 W4-B 可写**。
 *    W4-A / W4-C 与任何其他工作一律不得改本文件。
 *
 * ## 预分配段（docs/parallel-batches-w4/README.md §6）
 *   1760–1765  w3-03 巡逻/住所/矿场自愈 —— **整段留空**（B1 复用 batch-12 十事务，
 *              零新增 ActionId，见 scripts/action-catalog/w4b.mjs 段注释）
 *   1766–1769  w3-04 玉符运行时（B2：settle / dayReset / checkpoint / 广告落账）
 *   1770–1779  w3-05 邮件附件 + 行商刷新（B3）
 *   1840–1849  w3-12 外交/自愈/运行态（B4）
 *
 * ## 🔴 结构性红线（平台效应回执化）
 * 墙钟 / 邮箱投递 / 内存压力 / 存档时机都是**平台读数**：只能把它们作为**参数**
 * 传入本端口（本文件内禁止取系统时间或发起平台调用），端口只签发
 * 「结果 + 回执」，副作用仍由 Kotlin 侧执行。见 batch-W4B-court-economy.md §2.2。
 *
 * ## 契约
 * 见 `include/gamecore/dispatch_w4.h`：认领 ⇒ 返回完整结果信封
 * （`{"status":"success","data":{...}}` / `{"status":"failure","code":...}`）；
 * 不认领 ⇒ `std::nullopt`。
 */

#include "gamecore/dispatch_w4.h"

#include <gamecore/game_core.h>
#include <gamecore/state/json_codec.h>
#include <gamecore/system/diplomacy_selfheal_tx.h>
#include <gamecore/system/jade_tx.h>
#include <gamecore/system/merchant_tx.h>

namespace gamecore {

namespace {

/// 成功信封（与 execute_dispatch.cpp 的 ok/fail 同形；该两 helper 为其匿名
/// 命名空间文件局部，不可跨 TU 复用——本文件自带同形定义）
nlohmann::json ok(nlohmann::json data) {
    return {{"status", "success"}, {"data", std::move(data)}};
}

nlohmann::json fail(const std::string& code, const std::string& message) {
    return {{"status", "failure"}, {"code", code}, {"message", message}};
}

/// 参数读取助手：缺失/类型不符返回 false（→ INVALID_PARAMS failure 信封）
bool getInt(const nlohmann::json& j, const char* key, int32_t* out) {
    const auto it = j.find(key);
    if (it == j.end() || !it->is_number_integer()) return false;
    *out = it->get<int32_t>();
    return true;
}

bool getInt64(const nlohmann::json& j, const char* key, int64_t* out) {
    const auto it = j.find(key);
    if (it == j.end() || !it->is_number()) return false;
    *out = it->get<int64_t>();
    return true;
}

nlohmann::json invalidParams(const char* detail) {
    return {{"status", "failure"},
            {"code", "INVALID_PARAMS"},
            {"message", detail}};
}

/// 事务 5–8（玉符运行时，B2/w3-04）：语义见 jade_tx.h 事务 5–8 注。
nlohmann::json handleJadeRuntimeTx(GameCore& core, int32_t actionId,
                                   const nlohmann::json& p) {
    using namespace gamecore::system::jade_tx;
    auto& st = core.state();
    switch (actionId) {
        case 1766: {  // JADE_RUNTIME_SETTLE_TX
            int32_t total = 0, today = 0;
            int64_t accumMs = 0;
            if (!getInt(p, "total", &total) || !getInt(p, "today", &today) ||
                !getInt64(p, "accumMs", &accumMs)) {
                return invalidParams("settle requires total/today/accumMs");
            }
            const auto r = settleJadeGrantsTx(st, total, today, accumMs);
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"total", r.total},
                       {"today", r.today},
                       {"accumMs", r.accumMs},
                       {"frozen", r.frozen}});
        }
        case 1767: {  // JADE_RUNTIME_DAY_RESET_TX
            int32_t today = 0;
            int64_t todayMidnightMs = 0, accumMs = 0;
            if (!getInt64(p, "todayMidnightMs", &todayMidnightMs) ||
                !getInt(p, "today", &today) || !getInt64(p, "accumMs", &accumMs)) {
                return invalidParams("dayReset requires todayMidnightMs/today/accumMs");
            }
            const auto r = jadeDayResetTx(st, todayMidnightMs, today, accumMs);
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"changed", r.changed},
                       {"crossedDay", r.crossedDay},
                       {"today", r.today},
                       {"accumMs", r.accumMs},
                       {"dayAnchorMs", r.dayAnchorMs}});
        }
        case 1768: {  // JADE_RUNTIME_CHECKPOINT_TX
            int32_t total = 0, today = 0;
            int64_t accumMs = 0, dayAnchorMs = 0;
            if (!getInt(p, "total", &total) || !getInt(p, "today", &today) ||
                !getInt64(p, "accumMs", &accumMs) ||
                !getInt64(p, "dayAnchorMs", &dayAnchorMs)) {
                return invalidParams("checkpoint requires total/today/accumMs/dayAnchorMs");
            }
            const auto r = jadeCheckpointTx(st, total, today, accumMs, dayAnchorMs);
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"total", r.total},
                       {"today", r.today},
                       {"accumMs", r.accumMs},
                       {"dayAnchorMs", r.dayAnchorMs}});
        }
        case 1769: {  // JADE_RUNTIME_GRANT_AD_TX
            int32_t amount = 0, totalBefore = 0;
            if (!getInt(p, "amount", &amount) || !getInt(p, "totalBefore", &totalBefore)) {
                return invalidParams("grantAd requires amount/totalBefore");
            }
            const auto r = grantJadeFromAdTx(st, amount, totalBefore);
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"total", r.total}});
        }
        default:
            return fail("UNKNOWN_ACTION", "unhandled jade runtime action");
    }
}

/// 事务 1–4（行商刷新族，B3/w3-05）：语义见 merchant_tx.h 头注。
/// 池生成留 Kotlin（C++ 无物品生成器）；items 整表经 MerchantItem
/// json_codec 序列化传入（键名与 GC_TO/GC_FROM 一致）。
nlohmann::json handleMerchantTx(GameCore& core, int32_t actionId,
                                const nlohmann::json& p) {
    using namespace gamecore::system::merchant_tx;
    auto& st = core.state();
    switch (actionId) {
        case 1770: {  // MERCHANT_CHANCE_GRANT_TX
            int32_t year = 0, maxChances = 0, intervalYears = 0;
            if (!getInt(p, "year", &year) || !getInt(p, "maxChances", &maxChances) ||
                !getInt(p, "intervalYears", &intervalYears)) {
                return invalidParams("grant requires year/maxChances/intervalYears");
            }
            const auto r = grantMerchantRefreshChanceTx(st, year, maxChances, intervalYears);
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"granted", r.granted},
                       {"chances", r.chances},
                       {"grantYear", r.grantYear}});
        }
        case 1771: {  // MERCHANT_ACQUISITION_REFRESH_TX
            int32_t year = 0;
            if (!getInt(p, "year", &year) || !p.contains("items")) {
                return invalidParams("acquisition refresh requires year/items");
            }
            const auto r = refreshMerchantAcquisitionTx(
                st, p.at("items").get<std::vector<gamecore::state::MerchantItem>>(), year);
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"itemCount", r.itemCount}});
        }
        case 1772: {  // MERCHANT_TRAVELING_REFRESH_TX
            int32_t year = 0, newRefreshCount = 0;
            if (!getInt(p, "year", &year) || !getInt(p, "newRefreshCount", &newRefreshCount) ||
                !p.contains("items")) {
                return invalidParams("traveling refresh requires year/newRefreshCount/items");
            }
            const auto r = refreshTravelingMerchantTx(
                st, p.at("items").get<std::vector<gamecore::state::MerchantItem>>(),
                year, newRefreshCount);
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"itemCount", r.itemCount}});
        }
        case 1773: {  // MERCHANT_MANUAL_REFRESH_TX
            int32_t year = 0, newRefreshCount = 0;
            if (!getInt(p, "year", &year) || !getInt(p, "newRefreshCount", &newRefreshCount) ||
                !p.contains("items")) {
                return invalidParams("manual refresh requires year/newRefreshCount/items");
            }
            const auto r = refreshTravelingMerchantManualTx(
                st, p.at("items").get<std::vector<gamecore::state::MerchantItem>>(),
                year, newRefreshCount);
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"chances", r.chances},
                       {"itemCount", r.itemCount}});
        }
        default:
            return fail("UNKNOWN_ACTION", "unhandled merchant action");
    }
}

/// 事务（B4/w3-12 外交/自愈/运行态）：1840–1849。
/// 实裁范围见 diplomacy_selfheal_tx.h 头注（本批仅 1843；其余登记 W4-D）。
/// 返回 nullopt = 本批不认领该号（段内未实裁号交由 execute NOT_IMPLEMENTED 兜底）。
std::optional<nlohmann::json> handleSelfHealTx(GameCore& core, int32_t actionId,
                                               const nlohmann::json& p) {
    using namespace gamecore::system::diplomacy_selfheal_tx;
    if (actionId != 1843) {  // DIPLOMACY_WARNING_STAGE_TX
        return std::nullopt;
    }
    const auto it = p.find("stageKey");
    if (it == p.end() || !it->is_string()) {
        return invalidParams("warning stage requires stageKey");
    }
    const auto r = markWarningStageShownTx(core.state(), it->get<std::string>());
    if (!r.base.ok) return fail(r.base.errorType, r.base.message);
    return ok({{"stageCount", r.stageCount}});
}

}  // namespace

std::optional<nlohmann::json> dispatchW4B(GameCore& core, int32_t actionId,
                                          const nlohmann::json& params) {
    // ── W4-B 分派区（本区仅 W4-B 可写；预分配段 1760–1765 / 1766–1769 /
    //    1770–1779 / 1840–1849）────────────────────────────────────────────
    if (actionId >= 1766 && actionId <= 1769) {
        return handleJadeRuntimeTx(core, actionId, params);
    }
    if (actionId >= 1770 && actionId <= 1779) {
        return handleMerchantTx(core, actionId, params);
    }
    if (actionId >= 1840 && actionId <= 1849) {
        return handleSelfHealTx(core, actionId, params);
    }    // 1760–1765（w3-03）整段留空：复用 batch-12 既有事务面，零新增 ActionId。
    return std::nullopt;
}

}  // namespace gamecore
