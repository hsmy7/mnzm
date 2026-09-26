// ============================================================
// star_zone.h — 角色星级乘区（口径 A，三端同名同参）
//
// 口径（用户 2026-09-26 拍板 P-1，见 docs/design/gacha-batches/TASKBOOK-G09.md §3.8）：
//   starMult = 1 + (star - 1) × pctPerStar，其中
//     战斗 pctPerStar = kStarBattlePctPerStar = 0.08
//     修炼 pctPerStar = kStarCultPctPerStar  = 0.05
//   ⇒ 1★ 是**基线 ×1.00**（开局送的周明不凭空拔高名册），5★ 战斗 +32% / 修炼 +20%。
//
// 🔴 `star <= 1` 必须逐位得 ×1.00，且 `star == 0` 不得退化成 `1 + (0-1)×pct`（那会
//    变成 ×0.92/×0.95，凭空削低下限）。本文件的 [starMultiplier] 是该判据的唯一点，
//    战斗与修炼两条链都调它，禁止在调用方散落 `if (star > 1)`。
//
// 星级从哪来：`GameData.gachaStarMap[templateId]`（**稀疏**——0 星无键）。读法沿用
// `gacha_fragment.h` 的 `find + 三元`（禁止 `operator[]`：它会插入键，既破坏
// "0 星等价于无键"的账本不变量，又让 std::map 迭代序受影响）。存量旧弟子
// `templateId` 为空串 ⇒ 查不到 ⇒ star=0 ⇒ ×1.00（天然兼容，无需迁移）。
//
// 单一真源：两个 pct 常量与 Kotlin `GameConfig.Gacha.STAR_*_PCT_PER_STAR`、配置表
// `gachaDefaults.starBattlePctPerStar/starCultPctPerStar` **三向一致**，由
// `CharacterTemplateGuardTest` 看护（改一处即判红）。
// ============================================================
#pragma once

#include <cstdint>
#include <map>
#include <string>

#include "gamecore/state/models.h"

namespace gamecore::system {

/// 战斗侧每星加成（1★ 基线 ⇒ 每多一星 +8%）
inline constexpr double kStarBattlePctPerStar = 0.08;
/// 修炼侧每星加成（1★ 基线 ⇒ 每多一星 +5%）
inline constexpr double kStarCultPctPerStar = 0.05;
/// 解锁即 1 星：星级下限与"无加成"的分界
inline constexpr int32_t kBaseStar = 1;

/// 星级乘区（同名同参见 Kotlin `StarZone`）
struct StarZone {
    /// 原始星级（0 = 未解锁/存量旧弟子；1 = 基线）
    int32_t star = 0;
    /// 战斗属性/战力乘数
    double battleMult = 1.0;
    /// 修炼速度乘数（以"加成量"表达，与其余乘区的 `x + bonus` 形态同构）
    double cultivationBonus = 0.0;
};

/// 由星级算乘区（口径 A 的唯一实现点）
inline StarZone starZoneOf(int32_t star) {
    StarZone zone;
    zone.star = star;
    // star <= 1 一律 ×1.00：既是口径 A 的定义，也保证开局名册与既有期望表零扰动
    const double extra = star > kBaseStar ? static_cast<double>(star - kBaseStar) : 0.0;
    zone.battleMult = 1.0 + extra * kStarBattlePctPerStar;
    zone.cultivationBonus = extra * kStarCultPctPerStar;
    return zone;
}

/**
 * 从权威账本反查星级（稀疏读法）。
 *
 * @param gameData 含 `gachaStarMap` 的权威状态
 * @param templateId 弟子的角色模板 id；空串（存量旧弟子）⇒ 0 星
 */
inline int32_t resolveStar(const state::GameData& gameData, const std::string& templateId) {
    if (templateId.empty()) return 0;
    const auto it = gameData.gachaStarMap.find(templateId);
    return it == gameData.gachaStarMap.end() ? 0 : it->second;
}

/// 战斗侧快捷：某模板弟子的战力乘数
inline double battleStarMult(const state::GameData& gameData, const std::string& templateId) {
    return starZoneOf(resolveStar(gameData, templateId)).battleMult;
}

/// 修炼侧快捷：某模板弟子的修炼加成
inline double cultivationStarBonus(const state::GameData& gameData,
                                   const std::string& templateId) {
    return starZoneOf(resolveStar(gameData, templateId)).cultivationBonus;
}

}  // namespace gamecore::system
