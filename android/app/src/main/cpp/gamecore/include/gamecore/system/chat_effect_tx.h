#pragma once

/**
 * chat_effect_tx.h — 弟子交谈效果事务（W4-D/D4 续·弟子通道收口批，ActionId
 * `DISCIPLE_CHAT_EFFECT_TX=1860`，段 1860–1869 首个实裁）。
 *
 * ## 承接的 Kotlin 写者（下沉前真源）
 * `DiscipleDelegate.applyConversationEffects`（交谈结算界面）→
 * `GameEngineCoordination.updateDisciple`（纯 Kotlin 事务，无 native 臂——
 * ui-read-surface §4.4 弟子通道最后一个"协议列数据丢失风险"写者；
 * W4-A·A5 证据明记"写入面留待弟子通道关闭决策（W4-D）"）：
 *   ① 弟子不存在（id 不在表内）⇒ 静默无操作（Kotlin `return@update` 同语义）；
 *   ② `cultivation = max(0.0, cultivation + cultivationDelta)`；
 *   ③ `skills.morality/loyalty/intelligence = (原值 + delta).coerceIn(1, 100)`；
 *   ④ `statusData["lastChatYear"] = currentYear.toString()`（交谈冷却标记，
 *      与增量是否为零无关——Kotlin lambda 恒写）。
 *
 * ## RNG 契约（红线 1）
 * **零抽取**。交谈决策类抽取（树/分支/效果增量随机化）已由 W4-A·A5 收敛到
 * 引擎侧 `RngPartition.CHAT`（GameEngineConversationDraw），增量数值作为事务
 * 参数传入——本事务对抽取序零扰动，双臂（flag ON/OFF）抽取增量恒 0。
 *
 * ## 与反向通道的关系（本批登记）
 * 交谈写面下沉后，AUTHORITATIVE 稳态下本写者不再依赖反向通道回导；
 * `DISCIPLE_CHANNEL` 的**整体关闭**仍被以下写者阻断（handover §2.75④ 完成路径
 * 已登记，属后续批）：任务派遣 `startMission`（`releaseDiscipleToIdleInside`
 * 写槽位/状态列）+ 月/年残留执行器的 lifeEvents 瞬态列投影（购买日志/丧亲
 * ——协议外列，通道关闭后需随"检测 AUTHORITATIVE 门控"一并裁决）。
 *
 * ## 零 Android 依赖 / 失败信封契约
 * 纯 C++20；参数缺失/类型不符由端口返回 INVALID_PARAMS 失败信封（Kotlin 回退
 * 臂按原语义抛出/忽略）；业务面（弟子不存在）按 Kotlin 语义为**成功无操作**，
 * 不产失败信封。
 */

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>

#include "gamecore/state/models.h"

namespace gamecore::system::chat_tx {

/// 交谈效果应用结果（found = 弟子行是否存在；不存在 = 成功无操作）。
struct ChatEffectResult {
    bool found;
};

/**
 * 应用交谈效果（参数化增量，零 RNG；语义与 Kotlin updateDisciple lambda 逐位一致）。
 *
 * @param state 游戏状态（GameState::disciples 列式存储）
 * @param discipleId 弟子 id（Kotlin String id 的整数形态）
 * @param currentYear 游戏年（lastChatYear 冷却标记值）
 * @param cultivationDelta 修为增量（可负；结果下限 0.0）
 * @param moralityDelta 道德增量（和 clamp 到 [1,100]）
 * @param loyaltyDelta 忠诚增量（同上）
 * @param intelligenceDelta 悟性增量（同上）
 */
inline ChatEffectResult applyChatEffectTx(state::GameState& state,
                                          int32_t discipleId,
                                          int32_t currentYear,
                                          double cultivationDelta,
                                          int32_t moralityDelta,
                                          int32_t loyaltyDelta,
                                          int32_t intelligenceDelta) {
    const std::string id = std::to_string(discipleId);
    state::DiscipleStore& store = state.disciples;
    const std::optional<std::size_t> row = store.rowOf(id);
    if (!row.has_value()) {
        return {false};  // Kotlin `id !in discipleTables.ids → return@update` 同语义
    }
    const std::size_t r = *row;
    store.cultivations[r] =
        store.cultivations[r] + cultivationDelta > 0.0
            ? store.cultivations[r] + cultivationDelta
            : 0.0;
    auto clampSkill = [](int32_t current, int32_t delta) -> int32_t {
        const int32_t summed = current + delta;
        return summed < 1 ? 1 : (summed > 100 ? 100 : summed);
    };
    store.moralities[r] = clampSkill(store.moralities[r], moralityDelta);
    store.loyalties[r] = clampSkill(store.loyalties[r], loyaltyDelta);
    store.intelligences[r] = clampSkill(store.intelligences[r], intelligenceDelta);
    store.statusData[r][std::string("lastChatYear")] = std::to_string(currentYear);
    return {true};
}

}  // namespace gamecore::system::chat_tx
