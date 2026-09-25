// ============================================================
// gacha_fragment.h — 角色碎片入账（碎片账本与星级账本的唯一写者）
//
// 职责：把 templateId 的碎片数累加进 GameData.gachaFragmentCounts
//      （语义是"当前星级内进度"），每满 kFragmentsPerStar 进一星并写
//      GameData.gachaStarMap。抽卡 / 兑换码 / 邮件 / 活动四类发放渠道
//      一律经此入账，禁止在别处散落 `fragment[key]++` 式直写。
//      业务口径权威：docs/character-gacha-redesign-2026-09-23.md §15.6。
//
// Kotlin 回退臂与本文件逐字同式（双实现并行契约，同一组 golden 向量
// 两侧各跑一遍）；满星后进度继续累加——不截断、不折算成其它货币，
// 碎片不占仓库容量，故无溢出/转邮件语义。
//
// RNG 契约（对拍命门）——**零 RNG**：入账路径不取随机数、不触碰
// GameData.rngStates；GTest 以 rngStates 快照差分守护。
// ============================================================
#pragma once

#include <cstdint>
#include <string>

#include <nlohmann/json.hpp>

#include "gamecore/state/models.h"

namespace gamecore::system::gacha_fragment {

// using 声明（事务函数作用域非限定名解析）
using gamecore::state::GameData;
using gamecore::state::GameState;

// ── 星级换算常量 ───────────────────────────────────────────────────────
// 与 Kotlin `GameConfig.Gacha.FRAGMENTS_PER_STAR` / `MAX_STAR`（:core:domain）
// 及配置表 `db.gachaPools[0].fragmentsPerStar` / `maxStar`
//（android/app/src/main/assets/data/game-data.json）三向一致，
// 由常量三向比对守卫测试看护——单点改动即判红。
inline constexpr int32_t kFragmentsPerStar = 100;
inline constexpr int32_t kMaxStar = 5;

/// 入账结果（errorType 与 Kotlin 侧 AppError 分型同名口径一致）
struct GrantResult {
    bool ok = false;
    std::string errorType;
    std::string message;
    int32_t starBefore = 0;
    int32_t starAfter = 0;
    int32_t fragmentsAfter = 0;
};

/**
 * 碎片入账 + 升星（账本唯一写者）。
 *
 * 拒绝臂（templateId 为空 / count 非正）不产生任何写入，也不为被读的键
 * 建空条目——账本零改动。成功臂对同一 templateId 可加：连续两次入账与
 * 一次合并入账的 (star, progress) 结果相同。
 *
 * @param gameData 权威账本（就地写 gachaFragmentCounts / gachaStarMap）
 * @param templateId 角色模板 id；空 ⇒ 拒绝
 * @param count 本次入账的碎片数；<= 0 ⇒ 拒绝
 * @return ok=true 时带 starBefore / starAfter / fragmentsAfter；
 *         ok=false 时 errorType=InvalidGrant 且账本未变
 */
inline GrantResult addFragment(GameData& gameData, const std::string& templateId,
                               int32_t count) {
    GrantResult out;
    if (templateId.empty() || count <= 0) {
        out.errorType = "InvalidGrant";
        out.message = "fragment grant requires non-empty templateId and count > 0";
        return out;
    }

    const auto starIt = gameData.gachaStarMap.find(templateId);
    const int32_t starBefore =
        starIt == gameData.gachaStarMap.end() ? 0 : starIt->second;
    const auto fragIt = gameData.gachaFragmentCounts.find(templateId);
    int32_t progress =
        fragIt == gameData.gachaFragmentCounts.end() ? 0 : fragIt->second;

    progress += count;
    int32_t star = starBefore;
    while (star < kMaxStar && progress >= kFragmentsPerStar) {
        progress -= kFragmentsPerStar;
        star += 1;
    }

    gameData.gachaFragmentCounts[templateId] = progress;
    // 星级账本保持稀疏：0 星等价于无键，未解锁角色不得占位（与 Kotlin 账本同式）
    if (star > 0) {
        gameData.gachaStarMap[templateId] = star;
    }

    out.ok = true;
    out.starBefore = starBefore;
    out.starAfter = star;
    out.fragmentsAfter = progress;
    return out;
}

/**
 * native 事务入口（ActionId GACHA_FRAGMENT_GRANT_TX 的落点）。
 *
 * 零校验盲写：params 的 templateId(string) / count(int32) 缺失或类型不符时
 * 按空串 / 0 处理，与 addFragment 的拒绝臂同义（账本零改动、不抛异常）。
 *
 * @param state 引擎权威状态（写 state.gameData 的两张碎片账本）
 * @param params 已解析的参数对象（非 null）
 * @return 入账结果，由分派端口转成结果信封
 */
inline GrantResult grantFragmentsTransaction(GameState& state,
                                             const nlohmann::json& params) {
    std::string templateId;
    const auto tidIt = params.find("templateId");
    if (tidIt != params.end() && tidIt->is_string()) {
        templateId = tidIt->get<std::string>();
    }
    int32_t count = 0;
    const auto countIt = params.find("count");
    if (countIt != params.end() && countIt->is_number_integer()) {
        count = countIt->get<int32_t>();
    }
    return addFragment(state.gameData, templateId, count);
}

}  // namespace gamecore::system::gacha_fragment
