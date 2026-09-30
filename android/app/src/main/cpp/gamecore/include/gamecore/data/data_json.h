#pragma once

#include <nlohmann/json.hpp>

#include "gamecore/data/beast_material_db.h"
#include "gamecore/data/equip_affix_db.h"
#include "gamecore/data/equip_main_stat_db.h"
#include "gamecore/data/equip_set_db.h"
#include "gamecore/data/equipment_db.h"
#include "gamecore/data/gacha_pool_db.h"
#include "gamecore/data/herb_db.h"
#include "gamecore/data/manual_db.h"
#include "gamecore/data/recipe_db.h"

// ============================================================
// 数据文件 ↔ C++ DB 结构体 的 nlohmann 序列化适配（B16 / R6.2）
//
// 为什么单独一个头：`data/*_db.h` 是**纯数据头**（历史上零第三方依赖，
// 只 include <string>/<vector>/<cstdint>），把 nlohmann 拖进去会让每个
// 消费 TU 都编译 2 万行 JSON 模板。适配放在**只有注入路径包含**的独立
// 头里，消费点零负担。
//
// 字段名约定：JSON 键 = C++ 字段名（逐字段同名，降低映射错位风险）。
// 缺失字段用 `readField` 语义（保持结构体默认值）——与
// `state/json_codec.h::readField` 的宽松口径一致。
//
// 容错口径：`from_json` **宽松**（缺字段用默认值）；"段缺失/类型错/空数组"
// 的**硬校验**在 `data_inject.h` 做（显式失败优于静默半注入）。
// ============================================================
namespace gamecore::data {

/// 宽松读取（缺字段/null 时保持目标默认值）
template <typename T>
inline void jread(const nlohmann::json& j, const char* key, T& out) {
    if (j.contains(key) && !j.at(key).is_null()) {
        out = j.at(key).get<T>();
    }
}

// ── 装备四表（B3 复合结构：setPieces/sets/mainStatPools/subAffixes；
//    mainStatBase 是 stat 键映射，在 data_inject.h 专门展开） ────────
inline void from_json(const nlohmann::json& j, EquipStatValueDef& v) {
    jread(j, "stat", v.stat);
    jread(j, "value", v.value);
}
inline void to_json(nlohmann::json& j, const EquipStatValueDef& v) {
    j = nlohmann::json{{"stat", v.stat}, {"value", v.value}};
}

namespace detail {
/// 定长 C 数组字段 ← JSON 数组（宽松：缺段/短段保持默认值）
template <typename T, std::size_t N>
inline void jreadArray(const nlohmann::json& j, const char* key, T (&out)[N]) {
    if (j.contains(key) && j.at(key).is_array()) {
        const auto& arr = j.at(key);
        for (std::size_t i = 0; i < N && i < arr.size(); ++i) {
            out[i] = arr[i].get<T>();
        }
    }
}
template <typename T, std::size_t N>
inline void jwriteArray(nlohmann::json& j, const char* key, const T (&in)[N]) {
    nlohmann::json arr = nlohmann::json::array();
    for (std::size_t i = 0; i < N; ++i) arr.push_back(in[i]);
    j[key] = std::move(arr);
}
}  // namespace detail

inline void from_json(const nlohmann::json& j, SetPieceTemplate& v) {
    jread(j, "id", v.id);
    jread(j, "setId", v.setId);
    jread(j, "part", v.part);
    jread(j, "name", v.name);
    jread(j, "description", v.description);
    detail::jreadArray(j, "priceByRarity", v.priceByRarity);
    detail::jreadArray(j, "minRealmByRarity", v.minRealmByRarity);
}
inline void to_json(nlohmann::json& j, const SetPieceTemplate& v) {
    j = nlohmann::json{{"id", v.id},
                       {"setId", v.setId},
                       {"part", v.part},
                       {"name", v.name},
                       {"description", v.description}};
    detail::jwriteArray(j, "priceByRarity", v.priceByRarity);
    detail::jwriteArray(j, "minRealmByRarity", v.minRealmByRarity);
}

inline void from_json(const nlohmann::json& j, EquipmentSetDef& v) {
    jread(j, "id", v.id);
    jread(j, "name", v.name);
    jread(j, "school", v.school);
    jread(j, "bonus2", v.bonus2);
    jread(j, "bonus4", v.bonus4);
    jread(j, "bonus6", v.bonus6);
}
inline void to_json(nlohmann::json& j, const EquipmentSetDef& v) {
    j = nlohmann::json{{"id", v.id},    {"name", v.name},
                       {"school", v.school}, {"bonus2", v.bonus2},
                       {"bonus4", v.bonus4}, {"bonus6", v.bonus6}};
}

inline void from_json(const nlohmann::json& j, MainStatPoolDef& v) {
    jread(j, "part", v.part);
    jread(j, "stats", v.stats);
    jread(j, "coefficient", v.coefficient);
}
inline void to_json(nlohmann::json& j, const MainStatPoolDef& v) {
    j = nlohmann::json{{"part", v.part},
                       {"stats", v.stats},
                       {"coefficient", v.coefficient}};
}

inline void from_json(const nlohmann::json& j, EquipAffixDef& v) {
    jread(j, "stat", v.stat);
    jread(j, "weight", v.weight);
    detail::jreadArray(j, "tierValues", v.tierValues);
}
inline void to_json(nlohmann::json& j, const EquipAffixDef& v) {
    j = nlohmann::json{{"stat", v.stat}, {"weight", v.weight}};
    detail::jwriteArray(j, "tierValues", v.tierValues);
}

// ── HerbTemplate / SeedTemplate ──────────────────────────────
inline void from_json(const nlohmann::json& j, HerbTemplate& v) {
    jread(j, "id", v.id);
    jread(j, "name", v.name);
    jread(j, "tier", v.tier);
    jread(j, "rarity", v.rarity);
    jread(j, "category", v.category);
    jread(j, "description", v.description);
}
inline void to_json(nlohmann::json& j, const HerbTemplate& v) {
    j = nlohmann::json{{"id", v.id},          {"name", v.name},
                       {"tier", v.tier},      {"rarity", v.rarity},
                       {"category", v.category}, {"description", v.description}};
}

inline void from_json(const nlohmann::json& j, SeedTemplate& v) {
    jread(j, "id", v.id);
    jread(j, "name", v.name);
    jread(j, "tier", v.tier);
    jread(j, "rarity", v.rarity);
    jread(j, "growTime", v.growTime);
    jread(j, "yield", v.yield);
    jread(j, "description", v.description);
}
inline void to_json(nlohmann::json& j, const SeedTemplate& v) {
    j = nlohmann::json{{"id", v.id},        {"name", v.name},
                       {"tier", v.tier},    {"rarity", v.rarity},
                       {"growTime", v.growTime}, {"yield", v.yield},
                       {"description", v.description}};
}

// ── BeastMaterialTemplate ────────────────────────────────────
inline void from_json(const nlohmann::json& j, BeastMaterialTemplate& v) {
    jread(j, "id", v.id);
    jread(j, "name", v.name);
    jread(j, "tier", v.tier);
    jread(j, "rarity", v.rarity);
    jread(j, "category", v.category);
    jread(j, "description", v.description);
    jread(j, "icon", v.icon);
    jread(j, "dropWeight", v.dropWeight);
    jread(j, "price", v.price);
    jread(j, "materialCategory", v.materialCategory);
}
inline void to_json(nlohmann::json& j, const BeastMaterialTemplate& v) {
    j = nlohmann::json{{"id", v.id},
                       {"name", v.name},
                       {"tier", v.tier},
                       {"rarity", v.rarity},
                       {"category", v.category},
                       {"description", v.description},
                       {"icon", v.icon},
                       {"dropWeight", v.dropWeight},
                       {"price", v.price},
                       {"materialCategory", v.materialCategory}};
}

// ── ManualBuffInfo / ManualTemplate ──────────────────────────
inline void from_json(const nlohmann::json& j, ManualBuffInfo& v) {
    jread(j, "type", v.type);
    jread(j, "value", v.value);
    jread(j, "duration", v.duration);
}
inline void to_json(nlohmann::json& j, const ManualBuffInfo& v) {
    j = nlohmann::json{{"type", v.type}, {"value", v.value}, {"duration", v.duration}};
}

inline void from_json(const nlohmann::json& j, ManualTemplate& v) {
    jread(j, "id", v.id);
    jread(j, "name", v.name);
    jread(j, "type", v.type);
    jread(j, "rarity", v.rarity);
    jread(j, "description", v.description);
    jread(j, "stats", v.stats);
    jread(j, "skillName", v.skillName);
    jread(j, "skillDescription", v.skillDescription);
    jread(j, "skillType", v.skillType);
    jread(j, "skillDamageType", v.skillDamageType);
    jread(j, "skillHits", v.skillHits);
    jread(j, "skillDamageMultiplier", v.skillDamageMultiplier);
    jread(j, "skillCooldown", v.skillCooldown);
    jread(j, "skillMpCost", v.skillMpCost);
    jread(j, "skillHealPercent", v.skillHealPercent);
    jread(j, "skillHealFixed", v.skillHealFixed);
    jread(j, "skillHealType", v.skillHealType);
    jread(j, "skillBuffType", v.skillBuffType);
    jread(j, "skillBuffValue", v.skillBuffValue);
    jread(j, "skillBuffDuration", v.skillBuffDuration);
    jread(j, "skillBuffs", v.skillBuffs);
    jread(j, "price", v.price);
    jread(j, "minRealm", v.minRealm);
    jread(j, "skillIsAoe", v.skillIsAoe);
    jread(j, "skillTargetScope", v.skillTargetScope);
    jread(j, "skillShieldPercent", v.skillShieldPercent);
    jread(j, "skillTurnAdvancePercent", v.skillTurnAdvancePercent);
    jread(j, "skillDamageSharePercent", v.skillDamageSharePercent);
    jread(j, "skillDamageLinkPercent", v.skillDamageLinkPercent);
}
inline void to_json(nlohmann::json& j, const ManualTemplate& v) {
    j = nlohmann::json{
        {"id", v.id},
        {"name", v.name},
        {"type", v.type},
        {"rarity", v.rarity},
        {"description", v.description},
        {"stats", v.stats},
        {"skillName", v.skillName},
        {"skillDescription", v.skillDescription},
        {"skillType", v.skillType},
        {"skillDamageType", v.skillDamageType},
        {"skillHits", v.skillHits},
        {"skillDamageMultiplier", v.skillDamageMultiplier},
        {"skillCooldown", v.skillCooldown},
        {"skillMpCost", v.skillMpCost},
        {"skillHealPercent", v.skillHealPercent},
        {"skillHealFixed", v.skillHealFixed},
        {"skillHealType", v.skillHealType},
        {"skillBuffType", v.skillBuffType},
        {"skillBuffValue", v.skillBuffValue},
        {"skillBuffDuration", v.skillBuffDuration},
        {"skillBuffs", v.skillBuffs},
        {"price", v.price},
        {"minRealm", v.minRealm},
        {"skillIsAoe", v.skillIsAoe},
        {"skillTargetScope", v.skillTargetScope},
        {"skillShieldPercent", v.skillShieldPercent},
        {"skillTurnAdvancePercent", v.skillTurnAdvancePercent},
        {"skillDamageSharePercent", v.skillDamageSharePercent},
        {"skillDamageLinkPercent", v.skillDamageLinkPercent}};
}

// ── ForgeRecipeTemplate ──────────────────────────────────────
inline void from_json(const nlohmann::json& j, ForgeRecipeTemplate& v) {
    jread(j, "id", v.id);
    jread(j, "name", v.name);
    jread(j, "type", v.type);
    jread(j, "tier", v.tier);
    jread(j, "rarity", v.rarity);
    jread(j, "description", v.description);
    jread(j, "materials", v.materials);
    jread(j, "duration", v.duration);
    jread(j, "successRate", v.successRate);
}
inline void to_json(nlohmann::json& j, const ForgeRecipeTemplate& v) {
    j = nlohmann::json{{"id", v.id},
                       {"name", v.name},
                       {"type", v.type},
                       {"tier", v.tier},
                       {"rarity", v.rarity},
                       {"description", v.description},
                       {"materials", v.materials},
                       {"duration", v.duration},
                       {"successRate", v.successRate}};
}

// ── PillRecipeTemplate ───────────────────────────────────────
// 注：`price` 为派生字段（数据文件不含该键）——注入后由 data_inject.h 按
// recipe_db.h 的 C++ 同一公式回填，故此处不读不写 price。
inline void from_json(const nlohmann::json& j, PillRecipeTemplate& v) {
    jread(j, "id", v.id);
    jread(j, "name", v.name);
    jread(j, "tier", v.tier);
    jread(j, "rarity", v.rarity);
    jread(j, "category", v.category);
    jread(j, "grade", v.grade);
    jread(j, "pillType", v.pillType);
    jread(j, "description", v.description);
    jread(j, "materials", v.materials);
    jread(j, "duration", v.duration);
    jread(j, "successRate", v.successRate);
    jread(j, "breakthroughChance", v.breakthroughChance);
    jread(j, "targetRealm", v.targetRealm);
    jread(j, "cultivationSpeedPercent", v.cultivationSpeedPercent);
    jread(j, "skillExpSpeedPercent", v.skillExpSpeedPercent);
    jread(j, "cultivationAdd", v.cultivationAdd);
    jread(j, "skillExpAdd", v.skillExpAdd);
    jread(j, "physicalAttackAdd", v.physicalAttackAdd);
    jread(j, "attackAdd", v.attackAdd);
    jread(j, "defenseAdd", v.defenseAdd);
    jread(j, "magicAttackAdd", v.magicAttackAdd);
    jread(j, "physicalDefenseAdd", v.physicalDefenseAdd);
    jread(j, "magicDefenseAdd", v.magicDefenseAdd);
    jread(j, "hpAdd", v.hpAdd);
    jread(j, "mpAdd", v.mpAdd);
    jread(j, "speedAdd", v.speedAdd);
    jread(j, "critRateAdd", v.critRateAdd);
    jread(j, "critEffectAdd", v.critEffectAdd);
    jread(j, "intelligenceAdd", v.intelligenceAdd);
    jread(j, "charmAdd", v.charmAdd);
    jread(j, "comprehensionAdd", v.comprehensionAdd);
    jread(j, "artifactRefiningAdd", v.artifactRefiningAdd);
    jread(j, "pillRefiningAdd", v.pillRefiningAdd);
    jread(j, "spiritPlantingAdd", v.spiritPlantingAdd);
    jread(j, "teachingAdd", v.teachingAdd);
    jread(j, "moralityAdd", v.moralityAdd);
    jread(j, "miningAdd", v.miningAdd);
}
inline void to_json(nlohmann::json& j, const PillRecipeTemplate& v) {
    j = nlohmann::json{{"id", v.id},
                       {"name", v.name},
                       {"tier", v.tier},
                       {"rarity", v.rarity},
                       {"category", v.category},
                       {"grade", v.grade},
                       {"pillType", v.pillType},
                       {"description", v.description},
                       {"materials", v.materials},
                       {"duration", v.duration},
                       {"successRate", v.successRate},
                       {"breakthroughChance", v.breakthroughChance},
                       {"targetRealm", v.targetRealm},
                       {"cultivationSpeedPercent", v.cultivationSpeedPercent},
                       {"skillExpSpeedPercent", v.skillExpSpeedPercent},
                       {"cultivationAdd", v.cultivationAdd},
                       {"skillExpAdd", v.skillExpAdd},
                       {"physicalAttackAdd", v.physicalAttackAdd}, {"attackAdd", v.attackAdd},
                       {"defenseAdd", v.defenseAdd},
                       {"magicAttackAdd", v.magicAttackAdd},
                       {"physicalDefenseAdd", v.physicalDefenseAdd},
                       {"magicDefenseAdd", v.magicDefenseAdd},
                       {"hpAdd", v.hpAdd},
                       {"mpAdd", v.mpAdd},
                       {"speedAdd", v.speedAdd},
                       {"critRateAdd", v.critRateAdd},
                       {"critEffectAdd", v.critEffectAdd},
                       {"intelligenceAdd", v.intelligenceAdd},
                       {"charmAdd", v.charmAdd},
                       {"comprehensionAdd", v.comprehensionAdd},
                       {"artifactRefiningAdd", v.artifactRefiningAdd},
                       {"pillRefiningAdd", v.pillRefiningAdd},
                       {"spiritPlantingAdd", v.spiritPlantingAdd},
                       {"teachingAdd", v.teachingAdd},
                       {"moralityAdd", v.moralityAdd},
                       {"miningAdd", v.miningAdd}};
}

// ── 卡池 / 角色模板（G09 数据面；嵌套段先声明，外层依赖内层重载）──
inline void from_json(const nlohmann::json& j, GachaPityConfig& v) {
    jread(j, "pullThreshold", v.pullThreshold);
    jread(j, "fragmentCount", v.fragmentCount);
    jread(j, "pickMode", v.pickMode);
}
inline void to_json(nlohmann::json& j, const GachaPityConfig& v) {
    j = nlohmann::json{{"pullThreshold", v.pullThreshold},
                       {"fragmentCount", v.fragmentCount},
                       {"pickMode", v.pickMode}};
}

inline void from_json(const nlohmann::json& j, GachaRarityWeight& v) {
    jread(j, "rarity", v.rarity);
    jread(j, "weightPct", v.weightPct);
}
inline void to_json(nlohmann::json& j, const GachaRarityWeight& v) {
    j = nlohmann::json{{"rarity", v.rarity}, {"weightPct", v.weightPct}};
}

inline void from_json(const nlohmann::json& j, GachaCategory& v) {
    jread(j, "kind", v.kind);
    jread(j, "weightPct", v.weightPct);
    jread(j, "templateIds", v.templateIds);
    jread(j, "itemSource", v.itemSource);
    jread(j, "maxRarity", v.maxRarity);
}
inline void to_json(nlohmann::json& j, const GachaCategory& v) {
    j = nlohmann::json{{"kind", v.kind},
                       {"weightPct", v.weightPct},
                       {"templateIds", v.templateIds},
                       {"itemSource", v.itemSource},
                       {"maxRarity", v.maxRarity}};
}

inline void from_json(const nlohmann::json& j, GachaPoolTemplate& v) {
    jread(j, "poolId", v.poolId);
    jread(j, "enabled", v.enabled);
    jread(j, "pricePerPull", v.pricePerPull);
    jread(j, "categories", v.categories);
    jread(j, "itemRarityWeights", v.itemRarityWeights);
    jread(j, "fragmentCountWeights", v.fragmentCountWeights);
    jread(j, "itemCountWeights", v.itemCountWeights);
    jread(j, "pity", v.pity);
    jread(j, "fragmentsPerStar", v.fragmentsPerStar);
    jread(j, "maxStar", v.maxStar);
}
inline void to_json(nlohmann::json& j, const GachaPoolTemplate& v) {
    j = nlohmann::json{{"poolId", v.poolId},
                       {"enabled", v.enabled},
                       {"pricePerPull", v.pricePerPull},
                       {"categories", v.categories},
                       {"itemRarityWeights", v.itemRarityWeights},
                       {"fragmentCountWeights", v.fragmentCountWeights},
                       {"itemCountWeights", v.itemCountWeights},
                       {"pity", v.pity},
                       {"fragmentsPerStar", v.fragmentsPerStar},
                       {"maxStar", v.maxStar}};
}

inline void from_json(const nlohmann::json& j, CharacterTemplate& v) {
    jread(j, "id", v.id);
    jread(j, "name", v.name);
    jread(j, "gender", v.gender);
    jread(j, "avatarKey", v.avatarKey);
    jread(j, "portraitKey", v.portraitKey);
    jread(j, "spiritRoots", v.spiritRoots);
    jread(j, "innateDamageType", v.innateDamageType);
}
inline void to_json(nlohmann::json& j, const CharacterTemplate& v) {
    j = nlohmann::json{{"id", v.id},
                       {"name", v.name},
                       {"gender", v.gender},
                       {"avatarKey", v.avatarKey},
                       {"portraitKey", v.portraitKey},
                       {"spiritRoots", v.spiritRoots},
                       {"innateDamageType", v.innateDamageType}};
}

}  // namespace gamecore::data
