#pragma once

// ============================================================
// nurture_constants.h — 熟练度/孕养数值常量与曲线（S8 循环包含断链）
//
// 自 phase_settlement.h 上移：ai_sect_ops.h（S8 AI 修炼/孕养）与
// phase_settlement.h（每旬玩家结算）共用同一组常量/曲线，但
// phase_settlement.h ↔ month_settlement.h（→ ai_sect_ops.h）存在包含环，
// 本头文件为无依赖叶子（仅cstdint/cmath），两侧共同包含。
//
// 数值来源（Kotlin 同名字段）：
//   - ManualProficiencySystem.BASE_PROFICIENCY_RATE / MAX_PROFICIENCY /
//     LIBRARY_PROFICIENCY_BONUS_RATE
//   - EquipmentNurtureSystem.NURTURE_GAIN_PER_PHASE / getMaxNurtureLevel /
//     getExpRequiredForLevelUp
// ============================================================

#include <cmath>
#include <cstdint>

namespace gamecore::system {

/// 每旬熟练度基础增长参数（ManualProficiencySystem：6/s × (1+藏经阁0.5) × 2000ms）
constexpr double kBaseProficiencyRate = 6.0;
constexpr double kLibraryProficiencyBonusRate = 0.5;
constexpr int32_t kMaxProficiency = 30000;

/// 每旬装备孕养经验（EquipmentNurtureSystem.NURTURE_GAIN_PER_PHASE =
/// 5.0 × MS_PER_PHASE_1X / 1000 = 10.0）
constexpr double kNurtureGainPerPhase = 10.0;

namespace detail {

/// 孕养等级上限表（rarity 1..6 → getMaxNurtureLevel；单一定义供两处共用）
inline constexpr int32_t kNurtureMaxLevels[] = {5, 9, 13, 17, 21, 25};

inline int32_t nurtureMaxLevel(int32_t rarity) {
    return (rarity >= 1 && rarity <= 6) ? kNurtureMaxLevels[rarity - 1] : 5;
}

/// 孕养升级所需经验（getExpRequiredForLevelUp；满级返回 +inf）
inline double expRequiredForLevelUp(int32_t level, int32_t rarity) {
    const int32_t maxLevel = nurtureMaxLevel(rarity);
    if (level >= maxLevel) return HUGE_VAL;
    const double baseExp = 100.0 * (level + 1);
    double rarityMultiplier;
    switch (rarity) {
        case 2: rarityMultiplier = 1.5; break;
        case 3: rarityMultiplier = 2.0; break;
        case 4: rarityMultiplier = 3.0; break;
        case 5: rarityMultiplier = 4.5; break;
        case 6: rarityMultiplier = 6.0; break;
        default: rarityMultiplier = 1.0; break;
    }
    return baseExp * rarityMultiplier;
}

}  // namespace detail
}  // namespace gamecore::system

