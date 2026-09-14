// ============================================================
// profession.h — 炼丹师/锻造师职业规则
//
// 等价移植 Kotlin core/domain/.../profession/ProfessionRules.kt：
//  - 5 级职业 + 无职业；等级 N 可炼最高品阶 tier = N+1
//  - 晋升三重门槛：成功次数（仅计当前解锁最高阶，低阶不充数）/
//    境界（realm 值 <= 门槛，数值越小境界越高）/ 属性（pillRefining/artifactRefining）
//  - 全满级（5=丹圣/器圣）封顶不再计数
//
// 消费方：production.h（完成结算 settleDiscipleProduction）。
// DiscipleStore 列直读直写（statuses/realms/alchemyLevels/...），
// 与月结/旬结既有列访问惯例一致。
// ============================================================
#pragma once

#include <cstdint>
#include <string>

#include "gamecore/state/disciple_store.h"

namespace gamecore::system::profession {

/// 最高职业等级（5 = 丹圣/器圣）
constexpr int32_t kMaxLevel = 5;
/// 最高可炼品阶（tier 6 = 天品）
constexpr int32_t kMaxTier = 6;

/// 晋升 N→N+1 的成功次数要求（索引 = 当前等级）
constexpr int32_t kPromotionSuccessCounts[kMaxLevel] = {1, 200, 500, 800, 800};
/// 晋升 N→N+1 的境界要求（realm 值越小境界越高）
constexpr int32_t kPromotionRealmRequirements[kMaxLevel] = {9, 7, 6, 5, 3};
/// 晋升 N→N+1 的炼丹/锻造属性要求
constexpr int32_t kPromotionSkillRequirements[kMaxLevel] = {40, 55, 70, 90, 110};

/// 职业成功率加成：每低一阶 +0.20（基础率组成）
constexpr double kProfessionZonePerTier = 0.20;
/// 炼丹/锻造属性成功率加成：属性基准（低于该值无加成）
constexpr int32_t kSkillZoneBaseline = 30;
/// 炼丹/锻造属性成功率加成：每点属性加成比例
constexpr double kSkillZoneRate = 0.006;
/// 炼丹/锻造属性成功率加成上限（clamp）
constexpr double kSkillZoneMax = 0.50;

/// 等级可炼制的最高品阶（Kotlin maxCraftableTier）
inline int32_t maxCraftableTier(int32_t level) {
    if (level < 0) level = 0;
    if (level > kMaxLevel) level = kMaxLevel;
    const int32_t t = level + 1;
    return t > kMaxTier ? kMaxTier : t;
}

/// 晋升 level→level+1 所需成功次数（level 5 封顶返回 INT32_MAX）
inline int32_t promotionSuccessRequirement(int32_t level) {
    if (level < 0) level = 0;
    if (level >= kMaxLevel) return INT32_MAX;
    return kPromotionSuccessCounts[level];
}

/// 晋升 level→level+1 所需境界（realm 值 <= 该值即满足）
inline int32_t promotionRealmRequirement(int32_t level) {
    if (level < 0) level = 0;
    if (level > kMaxLevel - 1) level = kMaxLevel - 1;
    return kPromotionRealmRequirements[level];
}

/// 晋升 level→level+1 所需炼丹/锻造属性
inline int32_t promotionSkillRequirement(int32_t level) {
    if (level < 0) level = 0;
    if (level > kMaxLevel - 1) level = kMaxLevel - 1;
    return kPromotionSkillRequirements[level];
}

/// 职业显示名（修仙题材；Kotlin ProfessionRules.displayName）
inline const char* displayName(int32_t level, bool isAlchemy) {
    if (level < 0) level = 0;
    if (level > kMaxLevel) level = kMaxLevel;
    if (level == 0) return "无职业";
    if (isAlchemy) {
        static constexpr const char* kNames[5] = {
            "炼丹师", "炼丹大师", "炼丹宗师", "炼丹大宗师", "丹圣"};
        return kNames[level - 1];
    }
    static constexpr const char* kNames[5] = {
        "炼器师", "炼器大师", "炼器宗师", "炼器大宗师", "器圣"};
    return kNames[level - 1];
}

/// 晋升进度结算结果（Kotlin PromotionProgress 的纯数据部分）
struct PromotionProgress {
    bool promoted = false;
    int32_t newLevel = 0;
};

/// 成功炼制后结算职业晋升进度（对 DiscipleStore 行原地生效）。
///
/// 规则（逐条对齐 Kotlin Disciple.applyPromotionProgress）：
/// - 封顶（level >= 5）或低阶不充数（recipeTier != maxCraftableTier(level)）→ 零变化
/// - 溢出防护：计数 coerceAtMost(INT32_MAX-1) 后 +1
/// - 三门槛全满足 → 等级 +1 并清零计数；否则仅累加计数
///
/// @param ds 弟子存储（行内 alchemy*/forge* 列原地更新）
/// @param row 目标弟子行
/// @param recipeTier 炼制配方品阶（1~6；无效配方传 0——低阶不充数规则下零进度）
/// @param isAlchemy true=炼丹职业，false=锻造（炼器）职业
/// @return promoted/newLevel（事件记录由调用方负责）
inline PromotionProgress applyPromotionProgress(state::DiscipleStore& ds,
                                                std::size_t row,
                                                int32_t recipeTier,
                                                bool isAlchemy) {
    const int32_t level = isAlchemy ? ds.alchemyLevels[row] : ds.forgeLevels[row];
    if (level >= kMaxLevel || recipeTier != maxCraftableTier(level)) {
        return {false, level};
    }
    const int32_t currentCount =
        isAlchemy ? ds.alchemyPromotionCounts[row] : ds.forgePromotionCounts[row];
    const int32_t capped = currentCount < INT32_MAX - 1 ? currentCount : INT32_MAX - 1;
    const int32_t newCount = capped + 1;
    const bool meetsCount = newCount >= promotionSuccessRequirement(level);
    const bool meetsRealm = ds.realms[row] <= promotionRealmRequirement(level);
    const int32_t skill = isAlchemy ? ds.pillRefinings[row] : ds.artifactRefinings[row];
    const bool meetsSkill = skill >= promotionSkillRequirement(level);
    if (meetsCount && meetsRealm && meetsSkill) {
        if (isAlchemy) {
            ds.alchemyLevels[row] = level + 1;
            ds.alchemyPromotionCounts[row] = 0;
        } else {
            ds.forgeLevels[row] = level + 1;
            ds.forgePromotionCounts[row] = 0;
        }
        return {true, level + 1};
    }
    if (isAlchemy) {
        ds.alchemyPromotionCounts[row] = newCount;
    } else {
        ds.forgePromotionCounts[row] = newCount;
    }
    return {false, level};
}

}  // namespace gamecore::system::profession
