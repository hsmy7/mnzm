#pragma once

#include <nlohmann/json.hpp>

#include "gamecore/data/beast_material_db.h"
#include "gamecore/data/equipment_db.h"
#include "gamecore/data/herb_db.h"
#include "gamecore/data/manual_db.h"
#include "gamecore/data/recipe_db.h"
#include "gamecore/data/trait_db.h"

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

// ── EquipmentTemplate ────────────────────────────────────────
inline void from_json(const nlohmann::json& j, EquipmentTemplate& v) {
    jread(j, "id", v.id);
    jread(j, "name", v.name);
    jread(j, "slot", v.slot);
    jread(j, "rarity", v.rarity);
    jread(j, "physicalAttack", v.physicalAttack);
    jread(j, "magicAttack", v.magicAttack);
    jread(j, "physicalDefense", v.physicalDefense);
    jread(j, "magicDefense", v.magicDefense);
    jread(j, "speed", v.speed);
    jread(j, "hp", v.hp);
    jread(j, "mp", v.mp);
    jread(j, "critChance", v.critChance);
    jread(j, "description", v.description);
    jread(j, "price", v.price);
}
inline void to_json(nlohmann::json& j, const EquipmentTemplate& v) {
    j = nlohmann::json{{"id", v.id},
                       {"name", v.name},
                       {"slot", v.slot},
                       {"rarity", v.rarity},
                       {"physicalAttack", v.physicalAttack},
                       {"magicAttack", v.magicAttack},
                       {"physicalDefense", v.physicalDefense},
                       {"magicDefense", v.magicDefense},
                       {"speed", v.speed},
                       {"hp", v.hp},
                       {"mp", v.mp},
                       {"critChance", v.critChance},
                       {"description", v.description},
                       {"price", v.price}};
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
    jread(j, "nurtureSpeedPercent", v.nurtureSpeedPercent);
    jread(j, "cultivationAdd", v.cultivationAdd);
    jread(j, "skillExpAdd", v.skillExpAdd);
    jread(j, "nurtureAdd", v.nurtureAdd);
    jread(j, "physicalAttackAdd", v.physicalAttackAdd);
    jread(j, "magicAttackAdd", v.magicAttackAdd);
    jread(j, "physicalDefenseAdd", v.physicalDefenseAdd);
    jread(j, "magicDefenseAdd", v.magicDefenseAdd);
    jread(j, "hpAdd", v.hpAdd);
    jread(j, "mpAdd", v.mpAdd);
    jread(j, "speedAdd", v.speedAdd);
    jread(j, "critRateAdd", v.critRateAdd);
    jread(j, "critEffectAdd", v.critEffectAdd);
    jread(j, "extendLife", v.extendLife);
    jread(j, "intelligenceAdd", v.intelligenceAdd);
    jread(j, "charmAdd", v.charmAdd);
    jread(j, "loyaltyAdd", v.loyaltyAdd);
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
                       {"nurtureSpeedPercent", v.nurtureSpeedPercent},
                       {"cultivationAdd", v.cultivationAdd},
                       {"skillExpAdd", v.skillExpAdd},
                       {"nurtureAdd", v.nurtureAdd},
                       {"physicalAttackAdd", v.physicalAttackAdd},
                       {"magicAttackAdd", v.magicAttackAdd},
                       {"physicalDefenseAdd", v.physicalDefenseAdd},
                       {"magicDefenseAdd", v.magicDefenseAdd},
                       {"hpAdd", v.hpAdd},
                       {"mpAdd", v.mpAdd},
                       {"speedAdd", v.speedAdd},
                       {"critRateAdd", v.critRateAdd},
                       {"critEffectAdd", v.critEffectAdd},
                       {"extendLife", v.extendLife},
                       {"intelligenceAdd", v.intelligenceAdd},
                       {"charmAdd", v.charmAdd},
                       {"loyaltyAdd", v.loyaltyAdd},
                       {"comprehensionAdd", v.comprehensionAdd},
                       {"artifactRefiningAdd", v.artifactRefiningAdd},
                       {"pillRefiningAdd", v.pillRefiningAdd},
                       {"spiritPlantingAdd", v.spiritPlantingAdd},
                       {"teachingAdd", v.teachingAdd},
                       {"moralityAdd", v.moralityAdd},
                       {"miningAdd", v.miningAdd}};
}

// ── PositionBonus / Talent / Physique / Affix ────────────────
// 注：vendored nlohmann 3.11.3 单头不含 std::optional 适配器（全文件零
// "std::optional" 字样）⇒ positionBonus 手工解析（对象→值，缺失/null→nullopt）。
inline void from_json(const nlohmann::json& j, PositionBonus& v) {
    jread(j, "slotType", v.slotType);
    jread(j, "effectBonus", v.effectBonus);
}
inline void to_json(nlohmann::json& j, const PositionBonus& v) {
    j = nlohmann::json{{"slotType", v.slotType}, {"effectBonus", v.effectBonus}};
}

namespace detail {

/// `positionBonus` 键的手工解析（std::optional 无 nlohmann 适配器的替代）
inline void readPositionBonus(const nlohmann::json& j, const char* key,
                              std::optional<PositionBonus>& out) {
    if (!j.contains(key) || j.at(key).is_null()) {
        out = std::nullopt;
        return;
    }
    out = j.at(key).get<PositionBonus>();
}

inline void writePositionBonus(nlohmann::json& j, const char* key,
                               const std::optional<PositionBonus>& v) {
    j[key] = v.has_value() ? nlohmann::json(*v) : nlohmann::json(nullptr);
}

}  // namespace detail

inline void from_json(const nlohmann::json& j, TalentTemplate& v) {
    jread(j, "id", v.id);
    jread(j, "name", v.name);
    jread(j, "description", v.description);
    jread(j, "rarity", v.rarity);
    jread(j, "effects", v.effects);
    jread(j, "isNegative", v.isNegative);
    jread(j, "type", v.type);
    jread(j, "template", v.tmpl);  // Kotlin 字段名 template；C++ 改名 tmpl
    detail::readPositionBonus(j, "positionBonus", v.positionBonus);
}
inline void to_json(nlohmann::json& j, const TalentTemplate& v) {
    j = nlohmann::json{{"id", v.id},
                       {"name", v.name},
                       {"description", v.description},
                       {"rarity", v.rarity},
                       {"effects", v.effects},
                       {"isNegative", v.isNegative},
                       {"type", v.type},
                       {"template", v.tmpl}};
    detail::writePositionBonus(j, "positionBonus", v.positionBonus);
}

inline void from_json(const nlohmann::json& j, PhysiqueTemplate& v) {
    jread(j, "id", v.id);
    jread(j, "name", v.name);
    jread(j, "description", v.description);
    jread(j, "rarity", v.rarity);
    jread(j, "cultivationSpeedBonus", v.cultivationSpeedBonus);
    jread(j, "damageAmplification", v.damageAmplification);
    jread(j, "damageReduction", v.damageReduction);
    jread(j, "critDamageBonus", v.critDamageBonus);
    jread(j, "defenseBonus", v.defenseBonus);
    jread(j, "isNegative", v.isNegative);
    jread(j, "type", v.type);
    jread(j, "template", v.tmpl);
}
inline void to_json(nlohmann::json& j, const PhysiqueTemplate& v) {
    j = nlohmann::json{{"id", v.id},
                       {"name", v.name},
                       {"description", v.description},
                       {"rarity", v.rarity},
                       {"cultivationSpeedBonus", v.cultivationSpeedBonus},
                       {"damageAmplification", v.damageAmplification},
                       {"damageReduction", v.damageReduction},
                       {"critDamageBonus", v.critDamageBonus},
                       {"defenseBonus", v.defenseBonus},
                       {"isNegative", v.isNegative},
                       {"type", v.type},
                       {"template", v.tmpl}};
}

inline void from_json(const nlohmann::json& j, AffixTemplate& v) {
    jread(j, "id", v.id);
    jread(j, "name", v.name);
    jread(j, "description", v.description);
    jread(j, "rarity", v.rarity);
    jread(j, "effects", v.effects);
    jread(j, "isNegative", v.isNegative);
    jread(j, "type", v.type);
    jread(j, "template", v.tmpl);
    detail::readPositionBonus(j, "positionBonus", v.positionBonus);
}
inline void to_json(nlohmann::json& j, const AffixTemplate& v) {
    j = nlohmann::json{{"id", v.id},
                       {"name", v.name},
                       {"description", v.description},
                       {"rarity", v.rarity},
                       {"effects", v.effects},
                       {"isNegative", v.isNegative},
                       {"type", v.type},
                       {"template", v.tmpl}};
    detail::writePositionBonus(j, "positionBonus", v.positionBonus);
}

}  // namespace gamecore::data
