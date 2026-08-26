#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "gamecore/state/models.h"

// ============================================================
// 血炼月度结算（计划 v2 阶段 2 / T2.2）
//
// 等价移植 Kotlin GameEngineCoordination.kt 的血炼完成检测段：
//   - MutableGameState.processBloodRefinementCompletions（到期遍历）
//   - settleSingleRefinement（单条结算：百分比累加 + 事件 + 状态清理）
//   - DiscipleStatCalculator.addPctToTotal（百分比乘区累加）
//   - TimeProgressUtil.calculateElapsedMonths（跨月差分）
//
// 与 Kotlin 语义对齐要点：
//   - elapsed = (curYear-startYear)*12 + (curMonth-startMonth)（Long 中间量
//     toInt 截断；游戏时间域内不溢出）
//   - elapsed < durationMonths → 保留；否则结算并从 activeBloodRefinements 移除
//   - bonusPercent NaN 防御：NaN 无法被 coerceAtLeast 拦下（比较恒 false），
//     先 isFinite 归零再 coerceAtLeast(0)
//   - 结算不清 status（仅移除 statusData["buildingId"]——Kotlin 怪癖保留）
//   - 无 RNG 消耗
//
// 已知范围边界：
//   - Kotlin activeBloodRefinements 为 LinkedHashMap（JSON 键序迭代），
//     C++ std::map 按键排序迭代——多条同月完成时 gameEventRecords 顺序可能
//     不同；对拍场景以"至多一条同月到期"规避（见 t2-2-report.md RNG/顺序核对表）
// ============================================================
namespace gamecore::system {

/// 血炼属性 key → 显示名（Kotlin GameEngineCoordination.kt STAT_DISPLAY_NAMES；
/// 注意与 DiscipleStatCalculator.getStatDisplayName 的 "hp"→"气血" 不同源，
/// 本处遵循事件文案的实际来源映射）
inline const char* bloodRefinementStatDisplayName(const std::string& statKey) {
    static const std::map<std::string, const char*> kNames = {
        {"hp", "生命"},
        {"physicalAttack", "物攻"},
        {"magicAttack", "法攻"},
        {"physicalDefense", "物防"},
        {"magicDefense", "法防"},
        {"speed", "速度"},
    };
    const auto it = kNames.find(statKey);
    return (it != kNames.end()) ? it->second : statKey.c_str();
}

/// 跨月差分（Kotlin TimeProgressUtil.calculateElapsedMonths：
/// Long 中间量 *12+diff 后 toInt 截断）
inline int32_t calculateElapsedMonths(int32_t startYear, int32_t startMonth,
                                      int32_t currentYear, int32_t currentMonth) {
    const int64_t yearDiff = static_cast<int64_t>(currentYear) - startYear;
    const int64_t monthDiff = static_cast<int64_t>(currentMonth) - startMonth;
    return static_cast<int32_t>(yearDiff * 12 + monthDiff);
}

/// 百分比累加（Kotlin DiscipleStatCalculator.addPctToTotal：按 statKey 加到
/// 对应字段；未知 key 原样返回）
inline state::BloodRefinementPctTotal addPctToTotal(
        state::BloodRefinementPctTotal total, const std::string& statKey,
        double pct) {
    if (statKey == "speed") total.speedBonusPct += pct;
    else if (statKey == "hp") total.hpBonusPct += pct;
    else if (statKey == "physicalAttack") total.physicalAttackBonusPct += pct;
    else if (statKey == "magicAttack") total.magicAttackBonusPct += pct;
    else if (statKey == "physicalDefense") total.physicalDefenseBonusPct += pct;
    else if (statKey == "magicDefense") total.magicDefenseBonusPct += pct;
    return total;
}

}  // namespace gamecore::system
