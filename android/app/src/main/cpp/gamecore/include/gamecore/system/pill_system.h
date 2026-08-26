#pragma once

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/cultivation.h"
#include "gamecore/system/disciple.h"
#include "gamecore/system/disciple_stats.h"

// ============================================================
// 丹药系统（计划 v2 阶段 2 / T2.1：每旬自动服药）
//
// 等价移植 Kotlin DisciplePillManager / PillEffectApplier / StorageBagUtils
// 的**纯逻辑**部分：
//   - PillRule 分类与优先级排序
//   - canUsePill 资格检查（境界要求 / 永久丹去重 / 同类型生效中）
//   - applyToDisciple 效果应用（修为/延寿/永久属性/使用追踪/战斗临时/治疗/清空）
//   - decreaseItemQuantity 储物袋扣减
//
// 与 Kotlin 语义对齐要点：
//   - classify 按 pillType 字符串分派，兜底分支顺序固定
//   - 排序：priority 降序 + rarity 降序的稳定排序（Kotlin sortedWith 稳定）
//   - cultivationAdd clamp 到当前 maxCultivation；skillExpAdd clamp 10000
//   - 属性 clamp：SKILL_MAX=200、MAX_LOYALTY=100
//   - 治疗口径 maxHp/maxMp = getBaseStats()（基础属性，无装备段）
//   - maxOf(duration, pillEffectDuration) 语义保留
// ============================================================
namespace gamecore::pill {

using gamecore::state::Disciple;
using gamecore::state::ItemEffect;
using gamecore::state::StorageBagItem;

constexpr int32_t kSkillCap = 200;    // GameConfig.Disciple.SKILL_MAX
constexpr int32_t kLoyaltyCap = 100;  // GameConfig.Disciple.MAX_LOYALTY

/// 丹药分类规则（Kotlin PillRule；priority 越大越先服用）
enum class PillRule : int32_t {
    kBreakthrough = 0,        // BREAKTHROUGH(0)
    kTemporaryBattle = 1,     // TEMPORARY_BATTLE(1)
    kSustainedSpeed = 2,      // SUSTAINED_SPEED(2)
    kInstantCultivation = 3,  // INSTANT_CULTIVATION(3)
    kPermanentLife = 4,       // PERMANENT_LIFE(4)
    kPermanentBattle = 5,     // PERMANENT_BATTLE(4)——同优先级，枚举值仅内部区分
    kPermanentBaseAttr = 6,   // PERMANENT_BASE_ATTR(4)——同优先级
};

inline int32_t rulePriority(PillRule rule) {
    switch (rule) {
        case PillRule::kPermanentBaseAttr:
        case PillRule::kPermanentBattle:
        case PillRule::kPermanentLife: return 4;
        case PillRule::kInstantCultivation: return 3;
        case PillRule::kSustainedSpeed: return 2;
        case PillRule::kTemporaryBattle: return 1;
        case PillRule::kBreakthrough: return 0;
    }
    return 0;
}

// ── 分类谓词（DisciplePillManager companion） ────────────────────────

inline bool hasAnyBaseAttrAdd(const ItemEffect& e) {
    return e.intelligenceAdd > 0 || e.charmAdd > 0 || e.loyaltyAdd > 0 ||
           e.comprehensionAdd > 0 || e.artifactRefiningAdd > 0 ||
           e.pillRefiningAdd > 0 || e.spiritPlantingAdd > 0 ||
           e.teachingAdd > 0 || e.moralityAdd > 0 || e.miningAdd > 0;
}

inline bool hasAnyBattleAttrAdd(const ItemEffect& e) {
    return e.physicalAttackAdd > 0 || e.magicAttackAdd > 0 ||
           e.physicalDefenseAdd > 0 || e.magicDefenseAdd > 0 ||
           e.hpAdd > 0 || e.mpAdd > 0 || e.speedAdd > 0 ||
           e.critRateAdd > 0 || e.critEffectAdd > 0;
}

inline bool hasAnyHealingEffect(const ItemEffect& e) {
    return e.healMaxHpPercent > 0.0 || e.mpRecoverMaxMpPercent > 0.0 ||
           e.revive || e.clearAll;
}

/// 丹药分类（classify：pillType 分派 + 兜底分支，顺序与 Kotlin 一致）
inline PillRule classify(const ItemEffect& e) {
    const std::string& t = e.pillType;
    if (t == "extendLife") return PillRule::kPermanentLife;
    if (t == "cultivationAdd" || t == "skillExpAdd" || t == "nurtureAdd") {
        return PillRule::kInstantCultivation;
    }
    if (t == "cultivationSpeed" || t == "skillExpSpeed" || t == "nurtureSpeed") {
        return PillRule::kSustainedSpeed;
    }
    if (t == "breakthrough") return PillRule::kBreakthrough;
    if (hasAnyBaseAttrAdd(e)) return PillRule::kPermanentBaseAttr;
    if (hasAnyBattleAttrAdd(e)) return PillRule::kTemporaryBattle;
    if (hasAnyHealingEffect(e)) return PillRule::kInstantCultivation;
    return PillRule::kInstantCultivation;  // 未分类降级为可重复服用
}

/// 永久属性丹去重 key 集合（"tier#field"，字段序与 Kotlin 一致）
inline std::vector<std::string> buildUsedKeys(const ItemEffect& e, int32_t tier) {
    std::vector<std::string> fields;
    if (e.intelligenceAdd > 0) fields.push_back("intelligence");
    if (e.charmAdd > 0) fields.push_back("charm");
    if (e.loyaltyAdd > 0) fields.push_back("loyalty");
    if (e.comprehensionAdd > 0) fields.push_back("comprehension");
    if (e.artifactRefiningAdd > 0) fields.push_back("artifactRefining");
    if (e.pillRefiningAdd > 0) fields.push_back("pillRefining");
    if (e.spiritPlantingAdd > 0) fields.push_back("spiritPlanting");
    if (e.teachingAdd > 0) fields.push_back("teaching");
    if (e.moralityAdd > 0) fields.push_back("morality");
    if (e.miningAdd > 0) fields.push_back("mining");
    std::vector<std::string> keys;
    keys.reserve(fields.size());
    for (const std::string& f : fields) {
        keys.push_back(std::to_string(tier) + "#" + f);
    }
    return keys;
}

inline bool contains(const std::vector<std::string>& list, const std::string& v) {
    return std::find(list.begin(), list.end(), v) != list.end();
}

/// 境界要求（GameConfig.Realm.meetsRealmRequirement：discipleRealm <= minRealm）
inline bool meetsRealmRequirement(int32_t discipleRealm, int32_t minRealm) {
    return discipleRealm <= minRealm;
}

/// 服用资格检查（canUsePill）
inline bool canUsePill(const Disciple& d, const ItemEffect& effect) {
    if (!meetsRealmRequirement(d.realm, effect.minRealm)) return false;
    switch (classify(effect)) {
        case PillRule::kPermanentBaseAttr: {
            const auto keys = buildUsedKeys(effect, effect.tier);
            for (const auto& k : keys) {
                if (contains(d.usedPermanentPillKeys, k)) return false;
            }
            return true;
        }
        case PillRule::kPermanentLife:
            return !contains(d.usedExtendLifePillTypes, effect.pillType);
        case PillRule::kSustainedSpeed:
        case PillRule::kTemporaryBattle:
            return !contains(d.activePillTypes, effect.pillType);
        default:
            return true;
    }
}

// ── 储物袋扣减（StorageBagUtils.decreaseItemQuantity） ───────────────

inline std::vector<StorageBagItem> decreaseItemQuantity(
        const std::vector<StorageBagItem>& items,
        const std::string& itemId, int32_t amount = 1) {
    std::vector<StorageBagItem> out = items;
    const auto it = std::find_if(out.begin(), out.end(),
        [&](const StorageBagItem& i) { return i.itemId == itemId; });
    if (it == out.end()) return items;   // 未找到返回原列表
    it->quantity -= amount;
    if (it->quantity <= 0) out.erase(it);
    return out;
}

// ── 效果应用（PillEffectApplier 各段，顺序与 Kotlin 一致） ────────────

namespace detail {

/// 直接修为 / 功法经验加成（applyCultivationEffect）
inline void applyCultivationEffect(Disciple& d, const ItemEffect& e) {
    if (e.cultivationAdd > 0) {
        const double maxCult = gamecore::system::computeMaxCultivation(
            d.realm, d.realmLayer, d.cultivation);
        d.cultivation = gamecore::disciple::coerceIn(
            d.cultivation + static_cast<double>(e.cultivationAdd), 0.0, maxCult);
    }
    if (e.skillExpAdd > 0) {
        for (auto& entry : d.manualMasteries) {
            entry.second = std::min(entry.second + e.skillExpAdd,
                                    static_cast<int32_t>(10000));
        }
    }
}

/// 延寿效果（applyLifeExtend）
inline void applyLifeExtend(Disciple& d, const ItemEffect& e) {
    if (e.extendLife <= 0) return;
    d.lifespan += e.extendLife;
    if (!e.pillType.empty() && !contains(d.usedExtendLifePillTypes, e.pillType)) {
        d.usedExtendLifePillTypes.push_back(e.pillType);
    }
}

/// 永久基础属性加成（applyPermanentBaseAttr；clamp 与 Kotlin 一致）
inline void applyPermanentBaseAttr(Disciple& d, const ItemEffect& e) {
    if (!hasAnyBaseAttrAdd(e)) return;
    auto clampSkill = [](int32_t v) {
        return std::clamp(v, 0, static_cast<int32_t>(kSkillCap));
    };
    d.intelligence = clampSkill(d.intelligence + e.intelligenceAdd);
    d.charm = clampSkill(d.charm + e.charmAdd);
    d.loyalty = std::clamp(d.loyalty + e.loyaltyAdd, 0, static_cast<int32_t>(kLoyaltyCap));
    d.comprehension = clampSkill(d.comprehension + e.comprehensionAdd);
    d.artifactRefining = clampSkill(d.artifactRefining + e.artifactRefiningAdd);
    d.pillRefining = clampSkill(d.pillRefining + e.pillRefiningAdd);
    d.spiritPlanting = clampSkill(d.spiritPlanting + e.spiritPlantingAdd);
    d.teaching = clampSkill(d.teaching + e.teachingAdd);
    d.morality = clampSkill(d.morality + e.moralityAdd);
    d.mining = clampSkill(d.mining + e.miningAdd);
}

/// 使用追踪（applyUsageTracking：仅永久属性丹记录去重 key）
inline void applyUsageTracking(Disciple& d, const ItemEffect& e, PillRule rule) {
    if (rule != PillRule::kPermanentBaseAttr) return;
    for (const auto& k : buildUsedKeys(e, e.tier)) {
        if (!contains(d.usedPermanentPillKeys, k)) d.usedPermanentPillKeys.push_back(k);
    }
}

/// 战斗临时/持续加成（applyBattleAttrAndTemp；整体覆盖写语义保留）
inline void applyBattleAttrAndTemp(Disciple& d, const ItemEffect& e, PillRule rule) {
    if (!hasAnyBattleAttrAdd(e) && e.cultivationSpeedPercent <= 0 &&
        e.skillExpSpeedPercent <= 0 && e.nurtureSpeedPercent <= 0) {
        return;
    }
    d.pillPhysicalAttackBonus = e.physicalAttackAdd;
    d.pillMagicAttackBonus = e.magicAttackAdd;
    d.pillPhysicalDefenseBonus = e.physicalDefenseAdd;
    d.pillMagicDefenseBonus = e.magicDefenseAdd;
    d.pillHpBonus = e.hpAdd;
    d.pillMpBonus = e.mpAdd;
    d.pillSpeedBonus = e.speedAdd;
    d.pillCritRateBonus = e.critRateAdd;
    d.pillCritEffectBonus = e.critEffectAdd;
    d.pillCultivationSpeedBonus = e.cultivationSpeedPercent;
    d.pillSkillExpSpeedBonus = e.skillExpSpeedPercent;
    d.pillNurtureSpeedBonus = e.nurtureSpeedPercent;
    d.pillEffectDuration = e.duration > 0
        ? std::max(d.pillEffectDuration, e.duration)
        : d.pillEffectDuration;
    const bool isStackingRule = rule == PillRule::kSustainedSpeed ||
        rule == PillRule::kTemporaryBattle;
    if (isStackingRule && !contains(d.activePillTypes, e.pillType)) {
        d.activePillTypes.push_back(e.pillType);
    }
}

}  // namespace detail

/// 治疗 HP / 恢复 MP（applyHealAndRecover；maxHp/maxMp 为 getBaseStats 口径：
/// 基础 × 方差 × 层数 × (1+天赋%)，不含装备段与血炼累计）
inline void applyHealAndRecover(Disciple& d, const ItemEffect& e) {
    const auto effects = gamecore::stats::mergeEffects(
        gamecore::stats::talentEffectsFor(d.talentIds),
        gamecore::stats::affixEffectsFor(d.affixIds));
    if (e.healMaxHpPercent > 0.0) {
        int32_t maxHp = 0, maxMp = 0;
        gamecore::stats::computeBaseHpMp(d.realm, d.realmLayer, d.hpVariance,
                                         d.mpVariance, effects, nullptr, maxHp, maxMp);
        const int32_t currentHp = d.currentHp < 0 ? maxHp : d.currentHp;
        const int32_t healAmount = std::max(
            static_cast<int32_t>(static_cast<double>(maxHp) * e.healMaxHpPercent), 1);
        d.currentHp = std::min(currentHp + healAmount, maxHp);
    }
    if (e.mpRecoverMaxMpPercent > 0.0) {
        int32_t maxHp = 0, maxMp = 0;
        gamecore::stats::computeBaseHpMp(d.realm, d.realmLayer, d.hpVariance,
                                         d.mpVariance, effects, nullptr, maxHp, maxMp);
        const int32_t currentMp = d.currentMp < 0 ? maxMp : d.currentMp;
        const int32_t recoverAmount = std::max(
            static_cast<int32_t>(static_cast<double>(maxMp) * e.mpRecoverMaxMpPercent), 1);
        d.currentMp = std::min(currentMp + recoverAmount, maxMp);
    }
}

/// 清除所有临时效果（applyClearAll → 重置为 PillEffects() 默认值；
/// activePillCategory 为旧存档字段，Kotlin PillEffects() 默认 ""）
inline void applyClearAll(Disciple& d, const ItemEffect& e) {
    if (!e.clearAll) return;
    d.pillPhysicalAttackBonus = 0;
    d.pillMagicAttackBonus = 0;
    d.pillPhysicalDefenseBonus = 0;
    d.pillMagicDefenseBonus = 0;
    d.pillHpBonus = 0;
    d.pillMpBonus = 0;
    d.pillSpeedBonus = 0;
    d.pillCritRateBonus = 0.0;
    d.pillCritEffectBonus = 0.0;
    d.pillCultivationSpeedBonus = 0.0;
    d.pillSkillExpSpeedBonus = 0.0;
    d.pillNurtureSpeedBonus = 0.0;
    d.pillEffectDuration = 0;
    d.activePillTypes.clear();
    d.activePillCategory = "";
}

/// 丹药效果应用总入口（applyToDisciple：调用前须通过 canUsePill，
/// 调用后由调用方扣减储物袋并写回状态层）
inline void applyToDisciple(Disciple& d, const StorageBagItem& item) {
    if (!item.effect.has_value()) return;
    const ItemEffect& e = *item.effect;
    const PillRule rule = classify(e);
    detail::applyCultivationEffect(d, e);
    detail::applyLifeExtend(d, e);
    detail::applyPermanentBaseAttr(d, e);
    detail::applyUsageTracking(d, e, rule);
    detail::applyBattleAttrAndTemp(d, e, rule);
    applyHealAndRecover(d, e);
    applyClearAll(d, e);
}

}  // namespace gamecore::pill
