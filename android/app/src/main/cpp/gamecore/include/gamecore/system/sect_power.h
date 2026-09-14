#pragma once

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/java_hash.h"

// ============================================================
// 宗门战力计算器
//
// 等价移植 Kotlin SectCombatPowerCalculator（纯公式，玩家与 AI 同一口径）：
//   - calculateDiscipleCombatPower：战力 = (物攻+法攻)×5 + 气血×4 + (物防+法防)×3 + 速度×2
//   - calculateBeastCombatPower：同公式（各字段 coerceAtLeast(0) 防篡改负值）
//   - computeFingerprint：永久基础属性缓存指纹（Java hashCode 语义，见 java_hash.h）
//
// 说明：calculateDisciplePower / calculateSectPower 依赖 DiscipleStatCalculator
// 的永久基础属性乘区计算（C++ disciple_stats.h 已有对应列直读函数），本文件
// 提供纯公式核心供调用方按列直读入参调用。
// ============================================================
namespace gamecore::system {

/// 弟子战力（Kotlin calculateDiscipleCombatPower；stats 为永久基础属性）
inline int64_t discipleCombatPower(int32_t physicalAttack, int32_t magicAttack,
                                   int32_t maxHp, int32_t physicalDefense,
                                   int32_t magicDefense, int32_t speed) {
    return (static_cast<int64_t>(physicalAttack) + static_cast<int64_t>(magicAttack)) * 5 +
           static_cast<int64_t>(maxHp) * 4 +
           (static_cast<int64_t>(physicalDefense) + static_cast<int64_t>(magicDefense)) * 3 +
           static_cast<int64_t>(speed) * 2;
}

/// 妖兽战力（Kotlin calculateBeastCombatPower；含 coerceAtLeast(0) 防篡改）
inline int64_t beastCombatPower(int32_t maxHp, int32_t physicalAttack, int32_t magicAttack,
                                int32_t physicalDefense, int32_t magicDefense, int32_t speed) {
    const int32_t hp = std::max(maxHp, 0);
    const int32_t patk = std::max(physicalAttack, 0);
    const int32_t matk = std::max(magicAttack, 0);
    const int32_t pdef = std::max(physicalDefense, 0);
    const int32_t mdef = std::max(magicDefense, 0);
    const int32_t spd = std::max(speed, 0);
    return (static_cast<int64_t>(patk) + static_cast<int64_t>(matk)) * 5 +
           static_cast<int64_t>(hp) * 4 +
           (static_cast<int64_t>(pdef) + static_cast<int64_t>(mdef)) * 3 +
           static_cast<int64_t>(spd) * 2;
}

/// 血炼百分比累计（Kotlin BloodRefinementPctTotal 的 6 个百分比字段，顺序同 data class）
struct BloodRefinementPctTotalCpp {
    double hpBonusPct = 0.0;
    double physicalAttackBonusPct = 0.0;
    double magicAttackBonusPct = 0.0;
    double physicalDefenseBonusPct = 0.0;
    double magicDefenseBonusPct = 0.0;
    double speedBonusPct = 0.0;
};

/// 永久基础属性缓存指纹（Kotlin computeFingerprint；Java hashCode 语义）
/// bloodPct 传 nullptr 表示无血炼（AI 弟子）。
inline int32_t sectPowerFingerprint(int32_t realm, int32_t realmLayer, int32_t hpVariance,
                                    int32_t physicalAttackVariance, int32_t magicAttackVariance,
                                    int32_t physicalDefenseVariance, int32_t magicDefenseVariance,
                                    int32_t speedVariance,
                                    const std::vector<std::string>& talentIds,
                                    const BloodRefinementPctTotalCpp* bloodPct) {
    uint32_t result = 1;
    auto mix = [&result](int32_t v) { result = result * 31u + static_cast<uint32_t>(v); };
    mix(realm);
    mix(realmLayer);
    mix(hpVariance);
    mix(physicalAttackVariance);
    mix(magicAttackVariance);
    mix(physicalDefenseVariance);
    mix(magicDefenseVariance);
    mix(speedVariance);
    // aggregate.talentIds.hashCode()：List<String>.hashCode（初始 1）
    const int32_t talentHash = javaListHashCode(talentIds);
    mix(talentHash);
    if (bloodPct != nullptr) {
        // BloodRefinementPctTotal.hashCode()（data class：31*result + 字段.hashCode()）
        const double fields[6] = {
            bloodPct->hpBonusPct, bloodPct->physicalAttackBonusPct,
            bloodPct->magicAttackBonusPct, bloodPct->physicalDefenseBonusPct,
            bloodPct->magicDefenseBonusPct, bloodPct->speedBonusPct,
        };
        for (double f : fields) {
            mix(javaDoubleHashCode(f));
        }
    }
    return static_cast<int32_t>(result);
}

}  // namespace gamecore::system
