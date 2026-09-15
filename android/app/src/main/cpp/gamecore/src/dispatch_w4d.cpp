/**
 * dispatch_w4d.cpp — **W4-D 汇流波（串行收口）**的 ActionId 分派端口。
 *
 * ## 建立口径
 * W4-00 只为三个**并行**批次（A/B/C）建立了端口骨架（`execute_dispatch.cpp`
 * 三行调用 + 冻结）。W4-D 是三批合入后的**串行**汇流波——本文件与其一行
 * execute_dispatch 接线、`w4d.mjs` 清单、`w4d_tests.cmake` 一样，属 W4-D
 * 对并行切分结构的**一次性延续**（同一模式追加第四端口），非三批越界。
 *
 * ## 已实裁段（docs/parallel-batches-w4/README.md §6）
 *   1830 GUIDE_REWARD_CLAIM_TX  引导领奖事务（guide_reward_tx.h——w3-11）
 *
 *   1860–1869 机动段未实裁；1831–1839 余项判定结论（登记不下沉/已在位）：
 *   - 附庸年贡/附属年贡/月度脱离：C++ 已在位（year_settlement.h T1 #1/#2、
 *     month_settlement.h 子事件 12）⇒ 开臂即双重执行，不占号；
 *   - 兑换码 `RedeemCodeService`：登记不下沉（C++ 无物品随机生成器，RNG 红线，
 *     §2.50/B3 同先例）⇒ 不占号。
 *   段内未实裁号一律返回 `std::nullopt`（交 execute_dispatch
 *   NOT_IMPLEMENTED 兜底，与 dispatch_guard_test 的"未注册号不可达"判据一致）。
 *
 * ## 契约
 * 见 `include/gamecore/dispatch_w4.h`：认领 ⇒ 返回完整结果信封
 * {status:"success",data:{…}} / {status:"failure",code,message}；
 * 不认领 ⇒ `std::nullopt`。端口内禁取系统时间（墙钟一律参数传入）。
 */

#include <cstdint>
#include <optional>
#include <string>

#include <nlohmann/json.hpp>

#include "gamecore/dispatch_w4.h"
#include "gamecore/action_ids.h"
#include "gamecore/game_core.h"   // GameCore 完整类型（dispatch_w4.h 仅前向声明）
#include "gamecore/system/guide_reward_tx.h"
#include "gamecore/system/chat_effect_tx.h"

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

// ── 1830–1839 · w3-11 月年编排残差（guide_reward_tx.h）────────────────

nlohmann::json handleGuideRewardTx(GameCore& core, int32_t actionId,
                                   const nlohmann::json& p) {
    (void)actionId;  // 本段现仅 1830 一事务；段内扩展时再分派
    const auto idIt = p.find("taskId");
    if (idIt == p.end() || !idIt->is_number_integer()) {
        return invalidParams("guide reward claim requires taskId");
    }
    const auto r = gamecore::system::guide_tx::claimGuideRewardTx(
        core.state(), core.rng().getRng(rng::RngPartition::kSystem),
        idIt->get<int32_t>());
    if (!r.claimed) {
        return {{"status", "failure"},
                {"code", r.errorCode},
                {"message", "guide reward claim rejected"}};
    }
    return ok({{"claimed", true}, {"itemId", r.itemId}});
}

// ── 1860–1869 · W4-D 机动（chat_effect_tx.h）────────────────────────

nlohmann::json handleChatEffectTx(GameCore& core, const nlohmann::json& p) {
    const auto idIt = p.find("discipleId");
    const auto yearIt = p.find("currentYear");
    const auto cultIt = p.find("cultivationDelta");
    const auto morIt = p.find("moralityDelta");
    const auto loyIt = p.find("loyaltyDelta");
    const auto intIt = p.find("intelligenceDelta");
    if (idIt == p.end() || !idIt->is_number_integer() ||
        yearIt == p.end() || !yearIt->is_number_integer() ||
        cultIt == p.end() || !cultIt->is_number() ||
        morIt == p.end() || !morIt->is_number_integer() ||
        loyIt == p.end() || !loyIt->is_number_integer() ||
        intIt == p.end() || !intIt->is_number_integer()) {
        return invalidParams(
            "chat effect requires discipleId/currentYear(int), "
            "cultivationDelta(number), morality/loyalty/intelligenceDelta(int)");
    }
    const auto r = gamecore::system::chat_tx::applyChatEffectTx(
        core.state(), idIt->get<int32_t>(), yearIt->get<int32_t>(),
        cultIt->get<double>(), morIt->get<int32_t>(),
        loyIt->get<int32_t>(), intIt->get<int32_t>());
    // 弟子不存在 = 成功无操作（Kotlin `return@update` 同语义，非失败信封）
    return ok({{"applied", true}, {"found", r.found}});
}

}  // namespace

std::optional<nlohmann::json> dispatchW4D(GameCore& core, int32_t actionId,
                                          const nlohmann::json& params) {
    // ── W4-D 分派区（预分配段 1830–1839 / 1860–1869）────────────────────
    if (actionId >= action::GUIDE_REWARD_CLAIM_TX &&
        actionId <= action::GUIDE_REWARD_CLAIM_TX) {
        return handleGuideRewardTx(core, actionId, params);
    }
    if (actionId >= action::DISCIPLE_CHAT_EFFECT_TX &&
        actionId <= action::DISCIPLE_CHAT_EFFECT_TX) {
        return handleChatEffectTx(core, params);
    }
    // 1831–1839（w3-11 余项：C++ 已在位/登记不下沉）与 1861–1869（机动余量）不认领。
    return std::nullopt;
}

}  // namespace gamecore
