#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/state/models.h"

// ============================================================
// AI 宗门决策引擎（Kotlin→C++ 迁移计划 v2 阶段 4 / 批 4-4）
//
// 等价移植 Kotlin IntelligentSectDecisionEngine（纯函数，无状态无注入）：
//   - calculateChance：四因素加权概率（战力差/占领丢失/胜负/好感度）
//     + NaN/Inf 防御 + 战力硬门槛 + 好感度等级拦截 + 个性修正 + maxChance 裁剪
//   - calculateBreakawayChance：附属脱离概率（与 calculateChance 逻辑反向）
//   - calculatePowerScore：战力差分档映射（5x/3x/2x/1.5x）+ 权重缩放
//
// 与 Kotlin 语义对齐要点：
//   - 枚举顺序：SectRelationLevel HOSTILE=0/ANTAGONISTIC=1/NORMAL=2/
//     FRIENDLY=3/INTIMATE=4；AISectPersonality AGGRESSIVE=0/BALANCED=1/
//     CONSERVATIVE=2/RECLUSIVE=3
//   - 配置（SectDecisionConfig）与 Kotlin 同源，见各 profile 常量
//   - 好感度等级分值 0.0 且权重 > 0 → 直接返回 0（该等级不可行）
//   - 纯数学计算，无 RNG（决策概率由调用方掷骰）
// ============================================================
namespace gamecore::system {

// ── 决策配置（Kotlin SectDecisionConfig 同源）─────────────────

namespace sect_decision_cfg {
// 战力分档阈值
inline constexpr double kPowerTier5x = 5.0;
inline constexpr double kPowerTier3x = 3.0;
inline constexpr double kPowerTier2x = 2.0;
inline constexpr double kPowerRatioMin = 1.5;
// 战力档位分值
inline constexpr double kPowerScore5x = 0.40;
inline constexpr double kPowerScore3x = 0.30;
inline constexpr double kPowerScore2x = 0.20;
inline constexpr double kPowerScoreMin = 0.10;
// 参考权重（Vassal.POWER_WEIGHT）——战力分档分数缩放基准
inline constexpr double kReferencePowerWeight = 0.40;
// 脱离基率（战力越弱越易脱离）
inline constexpr double kBreakawayBase5x = 0.0;
inline constexpr double kBreakawayBase3x = 0.05;
inline constexpr double kBreakawayBase2x = 0.12;
inline constexpr double kBreakawayBase1_5x = 0.20;
inline constexpr double kBreakawayBaseWeak = 0.35;
// 好感度等级分值（索引 0..4 = HOSTILE..INTIMATE）
inline const double kAttackFavorScore[5] = {1.0, 0.7, 0.3, 0.0, 0.0};
inline const double kAllianceFavorScore[5] = {0.0, 0.1, 0.4, 0.7, 1.0};
inline const double kVassalFavorScore[5] = {0.0, 0.1, 0.4, 0.7, 0.9};
inline const double kBreakawayFavorScore[5] = {1.0, 0.8, 0.4, 0.2, 0.0};
// 个性修正因子（索引 0..3 = AGGRESSIVE..RECLUSIVE）
inline const double kAggressiveAttack = 1.20;
inline const double kBalancedAttack = 1.00;
inline const double kConservativeAttack = 0.80;
inline const double kReclusiveAttack = 0.60;
inline const double kAggressiveAlliance = 0.90;
inline const double kBalancedAlliance = 1.00;
inline const double kConservativeAlliance = 1.15;
inline const double kReclusiveAlliance = 0.90;
}  // namespace sect_decision_cfg

/// 决策场景（personality 修正类别）：0=攻击 / 1=结盟 / 2=无修正（附属）
enum class SectDecisionKind : int32_t {
    kAttack = 0,
    kAlliance = 1,
    kVassal = 2,
};

/// 决策配置文件（Kotlin DecisionProfile；favorScore 5 档）
struct SectDecisionProfile {
    double powerWeight = 0.0;
    double occupyWeight = 0.0;
    double skirmishWeight = 0.0;
    double favorWeight = 0.0;
    double maxChance = 1.0;
    double powerHardThreshold = 0.0;
    double favorScore[5] = {0.0, 0.0, 0.0, 0.0, 0.0};
    SectDecisionKind kind = SectDecisionKind::kVassal;
};

/// 攻击判定配置（Kotlin ATTACK_PROFILE）
inline const SectDecisionProfile& attackDecisionProfile() {
    static const SectDecisionProfile kProfile = {
        0.40, 0.20, 0.25, 0.15, 0.95, 0.5,
        {1.0, 0.7, 0.3, 0.0, 0.0},
        SectDecisionKind::kAttack,
    };
    return kProfile;
}

/// 结盟判定配置（Kotlin ALLIANCE_PROFILE）
inline const SectDecisionProfile& allianceDecisionProfile() {
    static const SectDecisionProfile kProfile = {
        0.20, 0.15, 0.25, 0.40, 0.95, 0.6,
        {0.0, 0.1, 0.4, 0.7, 1.0},
        SectDecisionKind::kAlliance,
    };
    return kProfile;
}

/// 附属判定配置（Kotlin VASSAL_PROFILE；无个性修正）
inline const SectDecisionProfile& vassalDecisionProfile() {
    static const SectDecisionProfile kProfile = {
        0.40, 0.30, 0.15, 0.15, 0.95, 1.0,
        {0.0, 0.1, 0.4, 0.7, 0.9},
        SectDecisionKind::kVassal,
    };
    return kProfile;
}

/// 好感度等级分值安全读取（越界回退 0.0）
inline double favorScoreForLevel(const SectDecisionProfile& profile, int32_t favorLevel) {
    if (favorLevel >= 0 && favorLevel < 5) return profile.favorScore[favorLevel];
    return 0.0;
}

/// 战力差评分（Kotlin calculatePowerScore：分档映射 × 权重缩放）
inline double sectPowerScore(double powerRatio, double profilePowerWeight) {
    double tierScore = 0.0;
    if (powerRatio >= sect_decision_cfg::kPowerTier5x) {
        tierScore = sect_decision_cfg::kPowerScore5x;
    } else if (powerRatio >= sect_decision_cfg::kPowerTier3x) {
        tierScore = sect_decision_cfg::kPowerScore3x;
    } else if (powerRatio >= sect_decision_cfg::kPowerTier2x) {
        tierScore = sect_decision_cfg::kPowerScore2x;
    } else if (powerRatio >= sect_decision_cfg::kPowerRatioMin) {
        tierScore = sect_decision_cfg::kPowerScoreMin;
    }
    return tierScore * (profilePowerWeight / sect_decision_cfg::kReferencePowerWeight);
}

/// 个性修正因子（Kotlin personalityEffect；personality 0..3）
inline double sectPersonalityFactor(SectDecisionKind kind, int32_t personality) {
    using namespace sect_decision_cfg;
    switch (personality) {  // AGGRESSIVE=0 / BALANCED=1 / CONSERVATIVE=2 / RECLUSIVE=3
        case 0:
            return kind == SectDecisionKind::kAttack ? kAggressiveAttack : kAggressiveAlliance;
        case 1:
            return kind == SectDecisionKind::kAttack ? kBalancedAttack : kBalancedAlliance;
        case 2:
            return kind == SectDecisionKind::kAttack ? kConservativeAttack : kConservativeAlliance;
        default:
            return kind == SectDecisionKind::kAttack ? kReclusiveAttack : kReclusiveAlliance;
    }
}

/// 四因素加权判定概率（Kotlin IntelligentSectDecisionEngine.calculateChance）
/// personality 传 -1 表示无个性修正（null）。
inline double sectDecisionChance(const SectDecisionProfile& profile, double powerRatio,
                                 int32_t conquestCount, int32_t lostSectCount,
                                 int32_t battleWinCount, int32_t battleLossCount,
                                 int32_t favorLevel, int32_t personality) {
    // 1. NaN/Infinity 防御
    if (!std::isfinite(powerRatio)) return 0.0;
    // 2. 战力硬门槛
    if (powerRatio < profile.powerHardThreshold) return 0.0;
    // 3. 好感度等级拦截（分值为 0 且有权重 → 不可行）
    const double favorMultiplier = favorScoreForLevel(profile, favorLevel);
    if (favorMultiplier == 0.0 && profile.favorWeight > 0.0) return 0.0;
    // 4. 四因素加权评分
    const double powerScore = sectPowerScore(powerRatio, profile.powerWeight);
    const int32_t totalOccupy = std::max(conquestCount, 0) + std::max(lostSectCount, 0);
    const double occupyScore = totalOccupy > 0
                                   ? (static_cast<double>(std::max(conquestCount, 0)) /
                                      static_cast<double>(totalOccupy)) *
                                         profile.occupyWeight
                                   : 0.0;
    const int32_t totalSkirmish = std::max(battleWinCount, 0) + std::max(battleLossCount, 0);
    const double skirmishScore = totalSkirmish > 0
                                     ? (static_cast<double>(std::max(battleWinCount, 0)) /
                                        static_cast<double>(totalSkirmish)) *
                                           profile.skirmishWeight
                                     : 0.0;
    const double favorScore = favorMultiplier * profile.favorWeight;
    double finalChance =
        std::clamp(powerScore + occupyScore + skirmishScore + favorScore, 0.0, profile.maxChance);
    // 5. 个性修正（仅攻击/结盟场景）
    if (personality >= 0 && personality < 4 &&
        profile.kind != SectDecisionKind::kVassal) {
        finalChance = std::clamp(finalChance * sectPersonalityFactor(profile.kind, personality),
                                 0.0, profile.maxChance);
    }
    return finalChance;
}

/// 附属脱离概率（Kotlin calculateBreakawayChance；无个性修正——函数体未使用该参数）
inline double sectBreakawayChance(double powerRatio, int32_t conquestCount,
                                  int32_t lostSectCount, int32_t battleWinCount,
                                  int32_t battleLossCount, int32_t favorLevel) {
    // NaN/Infinity 防御
    if (!std::isfinite(powerRatio)) return 0.0;
    // 战力差（反向：玩家战力优势越小越易脱离）
    double powerScore;
    if (powerRatio >= sect_decision_cfg::kPowerTier5x) {
        powerScore = sect_decision_cfg::kBreakawayBase5x;
    } else if (powerRatio >= sect_decision_cfg::kPowerTier3x) {
        powerScore = sect_decision_cfg::kBreakawayBase3x;
    } else if (powerRatio >= sect_decision_cfg::kPowerTier2x) {
        powerScore = sect_decision_cfg::kBreakawayBase2x;
    } else if (powerRatio >= sect_decision_cfg::kPowerRatioMin) {
        powerScore = sect_decision_cfg::kBreakawayBase1_5x;
    } else {
        powerScore = sect_decision_cfg::kBreakawayBaseWeak;
    }
    // 占领丢失（反向）
    const int32_t totalOcc = std::max(conquestCount, 0) + std::max(lostSectCount, 0);
    const double occLoss = totalOcc > 0
                               ? static_cast<double>(std::max(lostSectCount, 0)) /
                                     static_cast<double>(totalOcc)
                               : 0.0;
    const double occupyScore = occLoss * 0.30;  // Vassal.OCCUPY_WEIGHT
    // 胜负（反向）
    const int32_t totalSk = std::max(battleWinCount, 0) + std::max(battleLossCount, 0);
    const double skLoss = totalSk > 0
                              ? static_cast<double>(std::max(battleLossCount, 0)) /
                                    static_cast<double>(totalSk)
                              : 0.0;
    const double skirmishScore = skLoss * 0.15;  // Vassal.SKIRMISH_WEIGHT
    // 好感度（脱离专用分值：值越高越易脱离）
    const double favorScore =
        sect_decision_cfg::kBreakawayFavorScore[std::clamp(favorLevel, 0, 4)] * 0.15;
    return std::clamp(powerScore + occupyScore + skirmishScore + favorScore, 0.0, 0.40);
}

}  // namespace gamecore::system
