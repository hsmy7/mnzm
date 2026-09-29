#pragma once

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/java_hash.h"
#include "gamecore/system/star_zone.h"

// ============================================================
// 宗门战力计算器
//
// 等价移植 Kotlin SectCombatPowerCalculator（纯公式，玩家与 AI 同一口径）：
//   - calculateDiscipleCombatPower：战力 = (物攻+法攻)×5 + 气血×4 + (物防+法防)×3 + 速度×2
//   - calculateBeastCombatPower：同公式（各字段 coerceAtLeast(0) 防篡改负值）
//   - computeFingerprint：永久基础属性缓存指纹（Java hashCode 语义，见 java_hash.h）
//
// 星级乘区（G09 §3.8 口径 A）：六维加权和**之后**整体乘 `battleMult` 再向零截断，
// 与 Kotlin `calculateDisciplePower` 逐位同式。[discipleCombatPower] 保持纯公式
// 不变（既有金标用例与妖兽共用），带星级的入口是 [discipleCombatPowerWithStar]。
//
// 说明：calculateDisciplePower / calculateSectPower 依赖 DiscipleStatCalculator
// 的永久基础属性乘区计算（C++ disciple_stats.h 已有对应列直读函数），本文件
// 提供纯公式核心供调用方按列直读入参调用。
// ============================================================
namespace gamecore::system {

/// 弟子战力（Kotlin calculateDiscipleCombatPower；stats 为永久基础属性；
/// 单列口径 B1——旧双列入参口径 = (物攻+法攻)，与单列 attack 线性等价，
/// 与 Kotlin SectCombatPowerCalculator.attack 单参版同式）
inline int64_t discipleCombatPower(int32_t attack, int32_t maxHp,
                                   int32_t defense, int32_t speed) {
    return static_cast<int64_t>(attack) * 5 +
           static_cast<int64_t>(maxHp) * 4 +
           static_cast<int64_t>(defense) * 3 +
           static_cast<int64_t>(speed) * 2;
}

/// 弟子战力（含星级乘区；先求加权和再乘、最后向零截断——Kotlin 同式）
inline int64_t discipleCombatPowerWithStar(int32_t attack, int32_t maxHp,
                                           int32_t defense, int32_t speed,
                                           int32_t star) {
    const int64_t base = discipleCombatPower(attack, maxHp, defense, speed);
    return static_cast<int64_t>(static_cast<double>(base) * starZoneOf(star).battleMult);
}

/// 妖兽战力（Kotlin calculateBeastCombatPower；含 coerceAtLeast(0) 防篡改）
inline int64_t beastCombatPower(int32_t maxHp, int32_t attack,
                                int32_t defense, int32_t speed) {
    const int32_t hp = std::max(maxHp, 0);
    const int32_t atk = std::max(attack, 0);
    const int32_t def = std::max(defense, 0);
    const int32_t spd = std::max(speed, 0);
    return static_cast<int64_t>(atk) * 5 +
           static_cast<int64_t>(hp) * 4 +
           static_cast<int64_t>(def) * 3 +
           static_cast<int64_t>(spd) * 2;
}

/// 永久基础属性缓存指纹（Kotlin computeFingerprint；Java hashCode 语义）
/// 战力缓存指纹（单列口径 B1：攻/防各一个方差，与 Kotlin computeFingerprint 同式）
inline int32_t sectPowerFingerprint(int32_t realm, int32_t realmLayer, int32_t hpVariance,
                                    int32_t attackVariance, int32_t defenseVariance,
                                    int32_t speedVariance) {
    uint32_t result = 1;
    auto mix = [&result](int32_t v) { result = result * 31u + static_cast<uint32_t>(v); };
    mix(realm);
    mix(realmLayer);
    mix(hpVariance);
    mix(attackVariance);
    mix(defenseVariance);
    mix(speedVariance);
    return static_cast<int32_t>(result);
}

}  // namespace gamecore::system
