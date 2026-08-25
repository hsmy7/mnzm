#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/disciple.h"

// ============================================================
// 弟子生命周期（Kotlin→C++ 迁移批次 5d）
//
// 等价移植 Kotlin DiscipleAgePolicy.computeMaxAge +
// DiscipleLifecycleProcessor.computeAgedDeathData / applyAliveUpdates 的
// **纯公式**部分：
//
//   - 最大寿元：max(lifespan, realmMaxAge, realmMaxAge×(1+寿命加成))，
//     上限 ABSOLUTE_MAX_AGE_CEILING=20000
//   - 老化：年龄+1；5 岁且 realmLayer==0 → 回正为 1（IDLE）
//   - 寿元耗尽判定：age >= maxAge → 死亡候选
//
// 与 Kotlin 语义对齐要点：
//   - (realmMaxAge × (1+bonus)).toInt() 截断；coerceAtLeast(1)
//   - lifespanBonus = 天赋 effects["lifespan"] + 词条 effects["lifespan"]
//     （由调用方传入已合并的效果 map——Talent/Affix Registry 批次 2 补齐后接线）
// ============================================================
namespace gamecore::system {

/// 寿元绝对硬上限（Kotlin ABSOLUTE_MAX_AGE_CEILING）
constexpr int32_t kAbsoluteMaxAgeCeiling = 20000;

/// 计算弟子最大寿元（Kotlin Disciple.computeMaxAge）
/// @param lifespan 个人寿命上限
/// @param realmMaxAge 当前境界寿元上限
/// @param lifespanBonus 天赋+词条寿命加成（0.0 = 无加成）
inline int32_t computeMaxAge(int32_t lifespan, int32_t realmMaxAge,
                             double lifespanBonus) {
    const int32_t traitLifespan =
        std::max(static_cast<int32_t>(realmMaxAge * (1.0 + lifespanBonus)), 1);
    const int32_t raw = std::max({lifespan, realmMaxAge, traitLifespan});
    return std::min(raw, kAbsoluteMaxAgeCeiling);
}

/// 老化结果（Kotlin AgedDeathData 元素）
struct AgingOutcome {
    bool dead = false;              // 寿元耗尽
    int32_t age = 0;                // 老化后年龄
    int32_t realmLayer = 0;         // 老化后层数
};

/// 单弟子老化判定（Kotlin computeAgedDeathData 的单人逻辑）
/// @param age 当前年龄
/// @param realmLayer 当前层数
/// @param maxAge 该弟子的最大寿元（调用方已计算）
inline AgingOutcome ageDisciple(int32_t age, int32_t realmLayer, int32_t maxAge) {
    AgingOutcome out;
    out.age = age + 1;
    out.realmLayer = realmLayer;
    // 5 岁境界层回正（realmLayer==0 → 1）
    if (out.age == 5 && out.realmLayer == 0) {
        out.realmLayer = 1;
    }
    if (out.age >= maxAge) {
        out.dead = true;
    }
    return out;
}

/// 活弟子老化字段更新（Kotlin applyAliveUpdates 的单人逻辑）
/// @return 老化后年龄（跳过死亡）
inline int32_t ageAliveDisciple(int32_t age, int32_t realmLayer, int32_t& outRealmLayer) {
    outRealmLayer = realmLayer;
    const int32_t agedAge = age + 1;
    if (agedAge == 5 && outRealmLayer == 0) {
        outRealmLayer = 1;
    }
    return agedAge;
}

}  // namespace gamecore::system
