/**
 * dispatch_gacha.cpp — **角色卡池域独占**的 ActionId 分派端口。
 *
 * ## 端口形态
 * 本域走**独立端口文件**，不并入 `execute_dispatch.cpp` 的分派链裸区间分支。
 * 后者「区间 + 域 handler」的写法有真实事故先例：1730（`BEAST_VIEW_LOCK_TX`）
 * 被写成 1520–1531 区间的一部分，于是吞进库存 handler、返回
 * `UNKNOWN_ACTION`（事故说明仍在 `execute_dispatch.cpp` 的
 * `STORAGE_BAG_OPEN_TX` 分支上方）。端口只在自己的预分配段内 switch，
 * 段外一律 `std::nullopt`，不存在跨域吞号面。
 *
 * ## 预分配段（scripts/action-catalog/gacha.mjs）
 *   1870–1889  角色卡池
 *   已实裁：1870 `GACHA_FRAGMENT_GRANT_TX` 碎片入账（system/gacha_fragment.h）
 *            1871 `GACHA_PULL_ONCE` 单抽（system/gacha_tx.h）
 *            1872 `GACHA_PULL_TEN` 十连（system/gacha_tx.h）
 *   段内未实裁号一律返回 `std::nullopt`（交 execute_dispatch 的
 *   NOT_IMPLEMENTED 兜底——未注册号不可达，与 dispatch_guard_test 判据一致）。
 *
 * ## 契约（与既有 W4 端口同形）
 * 认领 ⇒ 返回完整结果信封（`{"status":"success","data":{...}}` /
 * `{"status":"failure","code":...,"message":...}`）；不认领 ⇒ `std::nullopt`。
 * 端口不抛异常、**端口自身不取随机数**（抽卡掷点在 gacha_tx.h 内、只消费
 * GACHA 分区）、不读墙钟（业务失败以失败信封表达）。
 *
 * ## 接线
 * 分派链上的调用与前置声明登记在 `execute_dispatch.cpp`（与 `dispatchW4A`
 * 同位置同形制），本文件保持域内自洽。
 */

#include <cstdint>
#include <optional>
#include <string>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/game_core.h"
#include "gamecore/system/gacha_fragment.h"
#include "gamecore/system/gacha_tx.h"

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

/// 溢出草稿数组（与 execute_dispatch 的库存/商人事务同形制：Kotlin
/// `InventoryNativeForward` 按此数组重建最小模型并走同一投递通道落邮件。
/// **不回传即丢件**——仓库满时溢出部分只存在于草稿里，不在任何账本中）
nlohmann::json overflowDraftsJson(const std::vector<gamecore::system::OverflowDraft>& drafts) {
    nlohmann::json out = nlohmann::json::array();
    for (const auto& d : drafts) {
        out.push_back({
            {"source", d.source}, {"itemType", d.itemType}, {"itemName", d.itemName},
            {"itemId", d.itemId}, {"rarity", d.rarity}, {"quantity", d.quantity},
            {"category", d.category}, {"grade", d.grade}, {"slot", d.slot},
            {"type", d.type}, {"growTime", d.growTime}, {"yield", d.yield}
        });
    }
    return out;
}

/// 抽卡成功信封（rows 按抽取序，第 10 抽在末位；G11 结果页按下标呈现格序。
/// unlockedTemplateIds 是**描述符**——弟子实例化只可能在 Kotlin 发生）
nlohmann::json pullData(const gamecore::system::gacha_tx::PullOutcome& r) {
    nlohmann::json rows = nlohmann::json::array();
    for (const auto& row : r.rows) {
        rows.push_back({
            {"category", row.category}, {"templateId", row.templateId},
            {"itemId", row.itemId}, {"rarity", row.rarity},
            {"count", row.count}, {"isPity", row.isPity}
        });
    }
    return {{"poolId", r.poolId},
            {"pricePaid", r.pricePaid},
            {"spiritStonesAfter", r.spiritStonesAfter},
            {"pityAfter", r.pityAfter},
            {"rows", std::move(rows)},
            {"unlockedTemplateIds", r.unlockedTemplateIds},
            {"overflowDrafts", overflowDraftsJson(r.overflowDrafts)}};
}

}  // namespace

std::optional<nlohmann::json> dispatchGacha(GameCore& core, int32_t actionId,
                                            const nlohmann::json& params) {
    namespace gacha_fragment = gamecore::system::gacha_fragment;
    namespace gacha_tx = gamecore::system::gacha_tx;
    auto& state = core.state();

    // ── 角色卡池分派区（本区仅本域可写；预分配段 1870–1889）────────────
    switch (actionId) {
        // 碎片入账（零 RNG；账本写者唯一，见 gacha_fragment.h）
        case action::GACHA_FRAGMENT_GRANT_TX: {
            const auto r = gacha_fragment::grantFragmentsTransaction(state, params);
            if (!r.ok) return fail(r.errorType, r.message);
            // 成功即意味着 templateId 是非空字符串参数（空/缺失均落拒绝臂），
            // 故此处按存在读取用于回执，不会抛类型异常。
            return ok({{"granted", true},
                       {"templateId", params.at("templateId").get<std::string>()},
                       {"starBefore", r.starBefore},
                       {"starAfter", r.starAfter},
                       {"fragmentsAfter", r.fragmentsAfter}});
        }
        // 寻访单抽（消费 GACHA 分区；校验失败零消费零写入）
        case action::GACHA_PULL_ONCE: {
            const auto r = gacha_tx::pullOnceTransaction(state, core.rng(), params);
            if (!r.ok) return fail(r.errorType, r.message);
            return ok(pullData(r));
        }
        // 寻访十连（同一笔事务内顺序 10 次单抽语义，一次性扣费）
        case action::GACHA_PULL_TEN: {
            const auto r = gacha_tx::pullTenTransaction(state, core.rng(), params);
            if (!r.ok) return fail(r.errorType, r.message);
            return ok(pullData(r));
        }
        default:
            break;
    }
    // 段内余量（1873–1889）：本域尚无事务产出 ⇒ 不认领。
    return std::nullopt;
}

}  // namespace gamecore
