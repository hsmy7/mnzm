#include "gamecore/state/json_codec.h"

#include <cstdlib>

// ============================================================
// JSON snapshot codec (field names match Kotlin @Serializable properties)
//
// Macros: GC_TO write field (to_json); GC_FROM lenient read (from_json).
// int64_t is a plain number in nlohmann; Kotlin Long is a number in kotlinx
// JSON too - types align.
// Manual family: Kotlin classes share identical fields -> shared ManualBase
// codec helpers.
// Int-key maps (Map<Int, V>): kotlinx encodes as object with decimal-string
// keys; nlohmann's default std::map<int32_t,V> from_json does not apply
// (type_error 302), so explicit string<->int conversion is used.
// ============================================================

#define GC_TO(obj, j, f) (j)[#f] = (obj).f
#define GC_FROM(j, obj, f) readField((j), #f, (obj).f)

// ── Nullable fields (std::optional<T>) ───────────────────────────────
// This trimmed json.hpp build has no built-in optional support, so handle
// it manually: to_json: value -> scalar, nullopt -> null;
// from_json: null/missing -> nullopt, scalar -> value.
#define GC_TO_OPT(obj, j, f)                                        \
    if ((obj).f.has_value()) {                                      \
        (j)[#f] = *((obj).f);                                       \
    } else {                                                        \
        (j)[#f] = nullptr;                                          \
    }
#define GC_FROM_OPT(j, obj, f)                                      \
    if ((j).contains(#f)) {                                         \
        if ((j).at(#f).is_null()) {                                 \
            (obj).f = std::nullopt;                                 \
        } else {                                                    \
            (obj).f = (j).at(#f).get<                              \
                std::remove_reference_t<decltype(*((obj).f))>>();   \
        }                                                           \
    }

// ── Int-key map helpers ──────────────────────────────────────────────

template <typename V>
void writeIntKeyMap(nlohmann::json& j, const char* key, const std::map<int32_t, V>& m) {
    auto& obj = j[key] = nlohmann::json::object();
    for (const auto& [k, v] : m) {
        obj[std::to_string(k)] = v;
    }
}

template <typename V>
void readIntKeyMap(const nlohmann::json& j, const char* key, std::map<int32_t, V>& out) {
    if (!j.contains(key) || j.at(key).is_null() || !j.at(key).is_object()) return;
    out.clear();
    for (const auto& [k, v] : j.at(key).items()) {
        out[static_cast<int32_t>(std::strtol(k.c_str(), nullptr, 10))] = v.get<V>();
    }
}

namespace gamecore::state {

// ── Manual common fields ─────────────────────────────────────────────

static void manualBaseToJson(nlohmann::json& j, const ManualBase& v) {
    GC_TO(v, j, id); GC_TO(v, j, slotId); GC_TO(v, j, name);
    GC_TO(v, j, rarity); GC_TO(v, j, description);
    GC_TO(v, j, type); GC_TO(v, j, stats);
    GC_TO_OPT(v, j, skillName); GC_TO_OPT(v, j, skillDescription);
    GC_TO(v, j, skillType); GC_TO(v, j, skillDamageType);
    GC_TO(v, j, skillHits); GC_TO(v, j, skillDamageMultiplier);
    GC_TO(v, j, skillCooldown); GC_TO(v, j, skillMpCost);
    GC_TO(v, j, skillHealPercent); GC_TO(v, j, skillHealFixed);
    GC_TO(v, j, skillHealType); GC_TO_OPT(v, j, skillBuffType);
    GC_TO(v, j, skillBuffValue); GC_TO(v, j, skillBuffDuration);
    GC_TO(v, j, skillBuffsJson); GC_TO(v, j, skillIsAoe);
    GC_TO(v, j, skillTargetScope);
    GC_TO(v, j, skillShieldPercent); GC_TO(v, j, skillTurnAdvancePercent);
    GC_TO(v, j, skillDamageSharePercent); GC_TO(v, j, skillDamageLinkPercent);
    GC_TO(v, j, minRealm);
}

static void manualBaseFromJson(const nlohmann::json& j, ManualBase& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, slotId); GC_FROM(j, v, name);
    GC_FROM(j, v, rarity); GC_FROM(j, v, description);
    GC_FROM(j, v, type); GC_FROM(j, v, stats);
    GC_FROM_OPT(j, v, skillName); GC_FROM_OPT(j, v, skillDescription);
    GC_FROM(j, v, skillType); GC_FROM(j, v, skillDamageType);
    GC_FROM(j, v, skillHits); GC_FROM(j, v, skillDamageMultiplier);
    GC_FROM(j, v, skillCooldown); GC_FROM(j, v, skillMpCost);
    GC_FROM(j, v, skillHealPercent); GC_FROM(j, v, skillHealFixed);
    GC_FROM(j, v, skillHealType); GC_FROM_OPT(j, v, skillBuffType);
    GC_FROM(j, v, skillBuffValue); GC_FROM(j, v, skillBuffDuration);
    GC_FROM(j, v, skillBuffsJson); GC_FROM(j, v, skillIsAoe);
    GC_FROM(j, v, skillTargetScope);
    GC_FROM(j, v, skillShieldPercent); GC_FROM(j, v, skillTurnAdvancePercent);
    GC_FROM(j, v, skillDamageSharePercent); GC_FROM(j, v, skillDamageLinkPercent);
    GC_FROM(j, v, minRealm);
}

// ── Items ────────────────────────────────────────────────────────────

void to_json(nlohmann::json& j, const EquipmentStack& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, slotId); GC_TO(v, j, name);
    GC_TO(v, j, rarity); GC_TO(v, j, description);
    GC_TO(v, j, slot); GC_TO(v, j, physicalAttack); GC_TO(v, j, magicAttack);
    GC_TO(v, j, physicalDefense); GC_TO(v, j, magicDefense);
    GC_TO(v, j, speed); GC_TO(v, j, hp); GC_TO(v, j, mp);
    GC_TO(v, j, critChance); GC_TO(v, j, minRealm);
    GC_TO(v, j, quantity); GC_TO(v, j, isLocked);
}
void from_json(const nlohmann::json& j, EquipmentStack& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, slotId); GC_FROM(j, v, name);
    GC_FROM(j, v, rarity); GC_FROM(j, v, description);
    GC_FROM(j, v, slot); GC_FROM(j, v, physicalAttack); GC_FROM(j, v, magicAttack);
    GC_FROM(j, v, physicalDefense); GC_FROM(j, v, magicDefense);
    GC_FROM(j, v, speed); GC_FROM(j, v, hp); GC_FROM(j, v, mp);
    GC_FROM(j, v, critChance); GC_FROM(j, v, minRealm);
    GC_FROM(j, v, quantity); GC_FROM(j, v, isLocked);
}

void to_json(nlohmann::json& j, const EquipmentInstance& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, slotId); GC_TO(v, j, name);
    GC_TO(v, j, rarity); GC_TO(v, j, description);
    GC_TO(v, j, slot); GC_TO(v, j, physicalAttack); GC_TO(v, j, magicAttack);
    GC_TO(v, j, physicalDefense); GC_TO(v, j, magicDefense);
    GC_TO(v, j, speed); GC_TO(v, j, hp); GC_TO(v, j, mp);
    GC_TO(v, j, critChance);
    GC_TO(v, j, nurtureLevel); GC_TO(v, j, nurtureProgress);
    GC_TO(v, j, minRealm); GC_TO_OPT(v, j, ownerId); GC_TO(v, j, isEquipped);
}
void from_json(const nlohmann::json& j, EquipmentInstance& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, slotId); GC_FROM(j, v, name);
    GC_FROM(j, v, rarity); GC_FROM(j, v, description);
    GC_FROM(j, v, slot); GC_FROM(j, v, physicalAttack); GC_FROM(j, v, magicAttack);
    GC_FROM(j, v, physicalDefense); GC_FROM(j, v, magicDefense);
    GC_FROM(j, v, speed); GC_FROM(j, v, hp); GC_FROM(j, v, mp);
    GC_FROM(j, v, critChance);
    GC_FROM(j, v, nurtureLevel); GC_FROM(j, v, nurtureProgress);
    GC_FROM(j, v, minRealm); GC_FROM_OPT(j, v, ownerId); GC_FROM(j, v, isEquipped);
}

void to_json(nlohmann::json& j, const ManualStack& v) {
    j = nlohmann::json::object();
    manualBaseToJson(j, v);
    GC_TO(v, j, quantity); GC_TO(v, j, isLocked);
}
void from_json(const nlohmann::json& j, ManualStack& v) {
    manualBaseFromJson(j, v);
    GC_FROM(j, v, quantity); GC_FROM(j, v, isLocked);
}

void to_json(nlohmann::json& j, const ManualInstance& v) {
    j = nlohmann::json::object();
    manualBaseToJson(j, v);
    GC_TO_OPT(v, j, ownerId); GC_TO(v, j, isLearned);
}
void from_json(const nlohmann::json& j, ManualInstance& v) {
    manualBaseFromJson(j, v);
    GC_FROM_OPT(j, v, ownerId); GC_FROM(j, v, isLearned);
}

void to_json(nlohmann::json& j, const Pill& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, slotId); GC_TO(v, j, name);
    GC_TO(v, j, rarity); GC_TO(v, j, description);
    GC_TO(v, j, category); GC_TO(v, j, grade); GC_TO(v, j, pillType);
    GC_TO(v, j, minRealm); GC_TO(v, j, quantity); GC_TO(v, j, isLocked);
}
void from_json(const nlohmann::json& j, Pill& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, slotId); GC_FROM(j, v, name);
    GC_FROM(j, v, rarity); GC_FROM(j, v, description);
    GC_FROM(j, v, category); GC_FROM(j, v, grade); GC_FROM(j, v, pillType);
    GC_FROM(j, v, minRealm); GC_FROM(j, v, quantity); GC_FROM(j, v, isLocked);
}

void to_json(nlohmann::json& j, const Material& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, slotId); GC_TO(v, j, name);
    GC_TO(v, j, rarity); GC_TO(v, j, description);
    GC_TO(v, j, category); GC_TO(v, j, quantity); GC_TO(v, j, isLocked);
}
void from_json(const nlohmann::json& j, Material& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, slotId); GC_FROM(j, v, name);
    GC_FROM(j, v, rarity); GC_FROM(j, v, description);
    GC_FROM(j, v, category); GC_FROM(j, v, quantity); GC_FROM(j, v, isLocked);
}

void to_json(nlohmann::json& j, const Herb& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, slotId); GC_TO(v, j, name);
    GC_TO(v, j, rarity); GC_TO(v, j, description);
    GC_TO(v, j, category); GC_TO(v, j, quantity); GC_TO(v, j, isLocked);
}
void from_json(const nlohmann::json& j, Herb& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, slotId); GC_FROM(j, v, name);
    GC_FROM(j, v, rarity); GC_FROM(j, v, description);
    GC_FROM(j, v, category); GC_FROM(j, v, quantity); GC_FROM(j, v, isLocked);
}

void to_json(nlohmann::json& j, const Seed& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, slotId); GC_TO(v, j, name);
    GC_TO(v, j, rarity); GC_TO(v, j, description);
    GC_TO(v, j, growTime); GC_TO(v, j, yield);
    GC_TO(v, j, quantity); GC_TO(v, j, isLocked);
}
void from_json(const nlohmann::json& j, Seed& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, slotId); GC_FROM(j, v, name);
    GC_FROM(j, v, rarity); GC_FROM(j, v, description);
    GC_FROM(j, v, growTime); GC_FROM(j, v, yield);
    GC_FROM(j, v, quantity); GC_FROM(j, v, isLocked);
}

void to_json(nlohmann::json& j, const StorageBag& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, slotId); GC_TO(v, j, name);
    GC_TO(v, j, rarity); GC_TO(v, j, description);
    GC_TO(v, j, quantity); GC_TO(v, j, isLocked);
}
void from_json(const nlohmann::json& j, StorageBag& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, slotId); GC_FROM(j, v, name);
    GC_FROM(j, v, rarity); GC_FROM(j, v, description);
    GC_FROM(j, v, quantity); GC_FROM(j, v, isLocked);
}

// ── Disciple ─────────────────────────────────────────────────────────

void to_json(nlohmann::json& j, const Disciple& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, name); GC_TO(v, j, surname);
    GC_TO(v, j, realm); GC_TO(v, j, realmLayer); GC_TO(v, j, cultivation);
    GC_TO(v, j, cultivationCheckpoint); GC_TO(v, j, cultivationCheckpointGameMonth);
    GC_TO(v, j, spiritRootType); GC_TO(v, j, age); GC_TO(v, j, lifespan);
    GC_TO(v, j, isAlive); GC_TO(v, j, gender); GC_TO(v, j, portraitRes);
    GC_TO(v, j, manualIds); GC_TO(v, j, talentIds); GC_TO(v, j, physiqueIds);
    GC_TO(v, j, affixIds); GC_TO(v, j, manualMasteries);
    GC_TO(v, j, status); GC_TO(v, j, statusData);
    GC_TO(v, j, cultivationSpeedBonus); GC_TO(v, j, cultivationSpeedDuration);
    GC_TO(v, j, discipleType); GC_TO(v, j, soulPower);
    GC_TO(v, j, cultivationCompletionMonth); GC_TO(v, j, cultivationCompletionPhase);
    GC_TO(v, j, manualCompletionMonth); GC_TO(v, j, manualCompletionPhase);
    GC_TO(v, j, equipmentNurturingCompletionMonth);
    GC_TO(v, j, equipmentNurturingCompletionPhase);
    
}
void from_json(const nlohmann::json& j, Disciple& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, name); GC_FROM(j, v, surname);
    GC_FROM(j, v, realm); GC_FROM(j, v, realmLayer); GC_FROM(j, v, cultivation);
    GC_FROM(j, v, cultivationCheckpoint); GC_FROM(j, v, cultivationCheckpointGameMonth);
    GC_FROM(j, v, spiritRootType); GC_FROM(j, v, age); GC_FROM(j, v, lifespan);
    GC_FROM(j, v, isAlive); GC_FROM(j, v, gender); GC_FROM(j, v, portraitRes);
    GC_FROM(j, v, manualIds); GC_FROM(j, v, talentIds); GC_FROM(j, v, physiqueIds);
    GC_FROM(j, v, affixIds); GC_FROM(j, v, manualMasteries);
    GC_FROM(j, v, status); GC_FROM(j, v, statusData);
    GC_FROM(j, v, cultivationSpeedBonus); GC_FROM(j, v, cultivationSpeedDuration);
    GC_FROM(j, v, discipleType); GC_FROM(j, v, soulPower);
    GC_FROM(j, v, cultivationCompletionMonth); GC_FROM(j, v, cultivationCompletionPhase);
    GC_FROM(j, v, manualCompletionMonth); GC_FROM(j, v, manualCompletionPhase);
    GC_FROM(j, v, equipmentNurturingCompletionMonth);
    GC_FROM(j, v, equipmentNurturingCompletionPhase);
    
}

// ── 嵌套类型（批次 1 第二子步） ─────────────────────────────────────

void to_json(nlohmann::json& j, const DirectDiscipleSlot& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, index); GC_TO(v, j, discipleId); GC_TO(v, j, discipleName);
    GC_TO(v, j, discipleRealm); GC_TO(v, j, discipleSpiritRootColor); GC_TO(v, j, sectId);
}
void from_json(const nlohmann::json& j, DirectDiscipleSlot& v) {
    GC_FROM(j, v, index); GC_FROM(j, v, discipleId); GC_FROM(j, v, discipleName);
    GC_FROM(j, v, discipleRealm); GC_FROM(j, v, discipleSpiritRootColor); GC_FROM(j, v, sectId);
}

void to_json(nlohmann::json& j, const ElderSlots& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, viceSectMaster); GC_TO(v, j, herbGardenElder);
    GC_TO(v, j, alchemyElder); GC_TO(v, j, forgeElder); GC_TO(v, j, outerElder);
    GC_TO(v, j, preachingElder); GC_TO(v, j, preachingMasters);
    GC_TO(v, j, lawEnforcementElder); GC_TO(v, j, lawEnforcementDisciples);
    GC_TO(v, j, innerElder); GC_TO(v, j, qingyunPreachingElder);
    GC_TO(v, j, qingyunPreachingMasters); GC_TO(v, j, herbGardenDisciples);
    GC_TO(v, j, alchemyDisciples); GC_TO(v, j, forgeDisciples);
    GC_TO(v, j, spiritMineDeaconDisciples); GC_TO(v, j, recruitingElder);
}
void from_json(const nlohmann::json& j, ElderSlots& v) {
    GC_FROM(j, v, viceSectMaster); GC_FROM(j, v, herbGardenElder);
    GC_FROM(j, v, alchemyElder); GC_FROM(j, v, forgeElder); GC_FROM(j, v, outerElder);
    GC_FROM(j, v, preachingElder); GC_FROM(j, v, preachingMasters);
    GC_FROM(j, v, lawEnforcementElder); GC_FROM(j, v, lawEnforcementDisciples);
    GC_FROM(j, v, innerElder); GC_FROM(j, v, qingyunPreachingElder);
    GC_FROM(j, v, qingyunPreachingMasters); GC_FROM(j, v, herbGardenDisciples);
    GC_FROM(j, v, alchemyDisciples); GC_FROM(j, v, forgeDisciples);
    GC_FROM(j, v, spiritMineDeaconDisciples); GC_FROM(j, v, recruitingElder);
}

void to_json(nlohmann::json& j, const SectPolicies& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, spiritMineBoost); GC_TO(v, j, enhancedSecurity);
    GC_TO(v, j, alchemyIncentive); GC_TO(v, j, forgeIncentive);
    GC_TO(v, j, herbCultivation); GC_TO(v, j, cultivationSubsidy);
    GC_TO(v, j, manualResearch);
    GC_TO(v, j, autoPlant); GC_TO(v, j, autoAlchemy); GC_TO(v, j, autoForge);
    GC_TO(v, j, autoMineFocused); GC_TO(v, j, autoMineRootCounts); GC_TO(v, j, autoMineThreshold);
    GC_TO(v, j, autoPlantFocused); GC_TO(v, j, autoPlantRootCounts); GC_TO(v, j, autoPlantThreshold);
    GC_TO(v, j, autoAlchemyFocused); GC_TO(v, j, autoAlchemyRootCounts); GC_TO(v, j, autoAlchemyThreshold);
    GC_TO(v, j, autoForgeFocused); GC_TO(v, j, autoForgeRootCounts); GC_TO(v, j, autoForgeThreshold);
    GC_TO(v, j, autoSingleResidenceFocused); GC_TO(v, j, autoSingleResidenceRootCounts); GC_TO(v, j, autoSingleResidenceThreshold);
    GC_TO(v, j, autoMultiResidenceFocused); GC_TO(v, j, autoMultiResidenceRootCounts); GC_TO(v, j, autoMultiResidenceThreshold);
    GC_TO(v, j, openRecruitment); GC_TO(v, j, asceticTraining); GC_TO(v, j, curfew);
    GC_TO(v, j, rewardPunish); GC_TO(v, j, strictTraining); GC_TO(v, j, relaxedMgmt);
    GC_TO(v, j, spiritSpring); GC_TO(v, j, frugality); GC_TO(v, j, moralEducation);
    GC_TO(v, j, benevolentGovernance);
}
void from_json(const nlohmann::json& j, SectPolicies& v) {
    GC_FROM(j, v, spiritMineBoost); GC_FROM(j, v, enhancedSecurity);
    GC_FROM(j, v, alchemyIncentive); GC_FROM(j, v, forgeIncentive);
    GC_FROM(j, v, herbCultivation); GC_FROM(j, v, cultivationSubsidy);
    GC_FROM(j, v, manualResearch);
    GC_FROM(j, v, autoPlant); GC_FROM(j, v, autoAlchemy); GC_FROM(j, v, autoForge);
    GC_FROM(j, v, autoMineFocused); GC_FROM(j, v, autoMineRootCounts); GC_FROM(j, v, autoMineThreshold);
    GC_FROM(j, v, autoPlantFocused); GC_FROM(j, v, autoPlantRootCounts); GC_FROM(j, v, autoPlantThreshold);
    GC_FROM(j, v, autoAlchemyFocused); GC_FROM(j, v, autoAlchemyRootCounts); GC_FROM(j, v, autoAlchemyThreshold);
    GC_FROM(j, v, autoForgeFocused); GC_FROM(j, v, autoForgeRootCounts); GC_FROM(j, v, autoForgeThreshold);
    GC_FROM(j, v, autoSingleResidenceFocused); GC_FROM(j, v, autoSingleResidenceRootCounts); GC_FROM(j, v, autoSingleResidenceThreshold);
    GC_FROM(j, v, autoMultiResidenceFocused); GC_FROM(j, v, autoMultiResidenceRootCounts); GC_FROM(j, v, autoMultiResidenceThreshold);
    GC_FROM(j, v, openRecruitment); GC_FROM(j, v, asceticTraining); GC_FROM(j, v, curfew);
    GC_FROM(j, v, rewardPunish); GC_FROM(j, v, strictTraining); GC_FROM(j, v, relaxedMgmt);
    GC_FROM(j, v, spiritSpring); GC_FROM(j, v, frugality); GC_FROM(j, v, moralEducation);
    GC_FROM(j, v, benevolentGovernance);
}

void to_json(nlohmann::json& j, const ProductionSlot& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, slotIndex);
    GC_TO(v, j, buildingType); GC_TO(v, j, buildingId); GC_TO(v, j, status);
    GC_TO_OPT(v, j, recipeId); GC_TO(v, j, recipeName);
    GC_TO(v, j, startYear); GC_TO(v, j, startMonth);
    GC_TO(v, j, duration); GC_TO(v, j, baseDuration);
    GC_TO_OPT(v, j, assignedDiscipleId); GC_TO(v, j, assignedDiscipleName);
    GC_TO(v, j, successRate);
    GC_TO_OPT(v, j, outputItemId); GC_TO(v, j, outputItemName);
    GC_TO(v, j, outputItemRarity); GC_TO(v, j, outputItemSlot);
    GC_TO(v, j, expectedYield); GC_TO(v, j, autoRestartEnabled);
    GC_TO(v, j, completionMonth); GC_TO(v, j, completionPhase);
}
void from_json(const nlohmann::json& j, ProductionSlot& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, slotIndex);
    GC_FROM(j, v, buildingType); GC_FROM(j, v, buildingId); GC_FROM(j, v, status);
    GC_FROM_OPT(j, v, recipeId); GC_FROM(j, v, recipeName);
    GC_FROM(j, v, startYear); GC_FROM(j, v, startMonth);
    GC_FROM(j, v, duration); GC_FROM(j, v, baseDuration);
    GC_FROM_OPT(j, v, assignedDiscipleId); GC_FROM(j, v, assignedDiscipleName);
    GC_FROM(j, v, successRate);
    GC_FROM_OPT(j, v, outputItemId); GC_FROM(j, v, outputItemName);
    GC_FROM(j, v, outputItemRarity); GC_FROM(j, v, outputItemSlot);
    GC_FROM(j, v, expectedYield); GC_FROM(j, v, autoRestartEnabled);
    GC_FROM(j, v, completionMonth); GC_FROM(j, v, completionPhase);
}

void to_json(nlohmann::json& j, const GridBuildingData& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, buildingId); GC_TO(v, j, displayName);
    GC_TO(v, j, gridX); GC_TO(v, j, gridY); GC_TO(v, j, width); GC_TO(v, j, height);
    GC_TO(v, j, instanceId);
}
void from_json(const nlohmann::json& j, GridBuildingData& v) {
    GC_FROM(j, v, buildingId); GC_FROM(j, v, displayName);
    GC_FROM(j, v, gridX); GC_FROM(j, v, gridY); GC_FROM(j, v, width); GC_FROM(j, v, height);
    GC_FROM(j, v, instanceId);
}

void to_json(nlohmann::json& j, const MerchantItem& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, name); GC_TO(v, j, itemId); GC_TO(v, j, rarity);
    GC_TO(v, j, price); GC_TO(v, j, quantity); GC_TO(v, j, description);
    GC_TO(v, j, obtainedYear); GC_TO(v, j, obtainedMonth);
}
void from_json(const nlohmann::json& j, MerchantItem& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, name); GC_FROM(j, v, itemId); GC_FROM(j, v, rarity);
    GC_FROM(j, v, price); GC_FROM(j, v, quantity); GC_FROM(j, v, description);
    GC_FROM(j, v, obtainedYear); GC_FROM(j, v, obtainedMonth);
}

void to_json(nlohmann::json& j, const Alliance& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, sectIds); GC_TO(v, j, startYear); GC_TO(v, j, initiatorId);
}
void from_json(const nlohmann::json& j, Alliance& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, sectIds); GC_FROM(j, v, startYear); GC_FROM(j, v, initiatorId);
}

void to_json(nlohmann::json& j, const VassalContract& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, index); GC_TO(v, j, discipleId); GC_TO(v, j, discipleName);
    GC_TO(v, j, discipleRealm); GC_TO(v, j, discipleSpiritRootColor);
}
void from_json(const nlohmann::json& j, VassalContract& v) {
    GC_FROM(j, v, index); GC_FROM(j, v, discipleId); GC_FROM(j, v, discipleName);
    GC_FROM(j, v, discipleRealm); GC_FROM(j, v, discipleSpiritRootColor);
}

void to_json(nlohmann::json& j, const SectRelation& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, sectId1); GC_TO(v, j, sectId2);
    GC_TO(v, j, favor); GC_TO(v, j, lastInteractionYear); GC_TO(v, j, noGiftYears);
}
void from_json(const nlohmann::json& j, SectRelation& v) {
    GC_FROM(j, v, sectId1); GC_FROM(j, v, sectId2);
    GC_FROM(j, v, favor); GC_FROM(j, v, lastInteractionYear); GC_FROM(j, v, noGiftYears);
}

void to_json(nlohmann::json& j, const WorldSect& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, name); GC_TO(v, j, level); GC_TO(v, j, levelName);
    GC_TO(v, j, x); GC_TO(v, j, y); GC_TO(v, j, distance);
    GC_TO(v, j, isPlayerSect); GC_TO(v, j, discovered); GC_TO(v, j, isKnown);
    GC_TO(v, j, relation);
    writeIntKeyMap(j, "disciples", v.disciples);
    GC_TO(v, j, maxRealm); GC_TO(v, j, isOccupied);
    GC_TO(v, j, occupierTeamId); GC_TO(v, j, occupierTeamName);
    GC_TO(v, j, allianceId); GC_TO(v, j, allianceStartYear);
    GC_TO(v, j, isRighteous); GC_TO(v, j, isPlayerOccupied); GC_TO(v, j, isUnderAttack);
    GC_TO(v, j, attackerSectId); GC_TO(v, j, occupierSectId);
}
void from_json(const nlohmann::json& j, WorldSect& v) {
    GC_FROM(j, v, name); GC_FROM(j, v, level); GC_FROM(j, v, levelName);
    GC_FROM(j, v, x); GC_FROM(j, v, y); GC_FROM(j, v, distance);
    GC_FROM(j, v, isPlayerSect); GC_FROM(j, v, discovered); GC_FROM(j, v, isKnown);
    GC_FROM(j, v, relation);
    readIntKeyMap(j, "disciples", v.disciples);
    GC_FROM(j, v, maxRealm); GC_FROM(j, v, isOccupied);
    GC_FROM(j, v, occupierTeamId); GC_FROM(j, v, occupierTeamName);
    GC_FROM(j, v, allianceId); GC_FROM(j, v, allianceStartYear);
    GC_FROM(j, v, isRighteous); GC_FROM(j, v, isPlayerOccupied); GC_FROM(j, v, isUnderAttack);
    GC_FROM(j, v, attackerSectId); GC_FROM(j, v, occupierSectId);
}

void to_json(nlohmann::json& j, const ResidenceSlot& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, buildingInstanceId); GC_TO(v, j, discipleId);
    GC_TO(v, j, discipleName); GC_TO(v, j, sectId);
}
void from_json(const nlohmann::json& j, ResidenceSlot& v) {
    GC_FROM(j, v, buildingInstanceId); GC_FROM(j, v, discipleId);
    GC_FROM(j, v, discipleName); GC_FROM(j, v, sectId);
}

void to_json(nlohmann::json& j, const SpiritFieldPlant& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, buildingInstanceId); GC_TO(v, j, seedId); GC_TO(v, j, seedName);
    GC_TO(v, j, growTime); GC_TO(v, j, expectedYield);
    GC_TO(v, j, plantYear); GC_TO(v, j, plantMonth); GC_TO(v, j, sectId);
    GC_TO(v, j, completionMonth);
}
void from_json(const nlohmann::json& j, SpiritFieldPlant& v) {
    GC_FROM(j, v, buildingInstanceId); GC_FROM(j, v, seedId); GC_FROM(j, v, seedName);
    GC_FROM(j, v, growTime); GC_FROM(j, v, expectedYield);
    GC_FROM(j, v, plantYear); GC_FROM(j, v, plantMonth); GC_FROM(j, v, sectId);
    GC_FROM(j, v, completionMonth);
}

void to_json(nlohmann::json& j, const PatrolConfig& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, targetRealms); GC_TO(v, j, maxBeastCount);
}
void from_json(const nlohmann::json& j, PatrolConfig& v) {
    GC_FROM(j, v, targetRealms); GC_FROM(j, v, maxBeastCount);
}

void to_json(nlohmann::json& j, const WorldLevel& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, type); GC_TO_OPT(v, j, beastType);
    GC_TO(v, j, realm); GC_TO(v, j, realmLayer);
    GC_TO(v, j, beastName); GC_TO(v, j, guardianName); GC_TO(v, j, caveName);
    GC_TO(v, j, x); GC_TO(v, j, y);
    GC_TO(v, j, spawnYear); GC_TO(v, j, spawnMonth);
    GC_TO(v, j, expiryYear); GC_TO(v, j, expiryMonth);
    GC_TO(v, j, count); GC_TO(v, j, caveImageIndex); GC_TO(v, j, defeated);
    GC_TO(v, j, beastMaxHp); GC_TO(v, j, beastMaxMp);
    GC_TO(v, j, beastPhysicalAttack); GC_TO(v, j, beastMagicAttack);
    GC_TO(v, j, beastPhysicalDefense); GC_TO(v, j, beastMagicDefense);
}
void from_json(const nlohmann::json& j, WorldLevel& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, type); GC_FROM_OPT(j, v, beastType);
    GC_FROM(j, v, realm); GC_FROM(j, v, realmLayer);
    GC_FROM(j, v, beastName); GC_FROM(j, v, guardianName); GC_FROM(j, v, caveName);
    GC_FROM(j, v, x); GC_FROM(j, v, y);
    GC_FROM(j, v, spawnYear); GC_FROM(j, v, spawnMonth);
    GC_FROM(j, v, expiryYear); GC_FROM(j, v, expiryMonth);
    GC_FROM(j, v, count); GC_FROM(j, v, caveImageIndex); GC_FROM(j, v, defeated);
    GC_FROM(j, v, beastMaxHp); GC_FROM(j, v, beastMaxMp);
    GC_FROM(j, v, beastPhysicalAttack); GC_FROM(j, v, beastMagicAttack);
    GC_FROM(j, v, beastPhysicalDefense); GC_FROM(j, v, beastMagicDefense);
}

void to_json(nlohmann::json& j, const MailClaimRecord& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, mailId); GC_TO(v, j, claimedAt); GC_TO(v, j, source);
}
void from_json(const nlohmann::json& j, MailClaimRecord& v) {
    GC_FROM(j, v, mailId); GC_FROM(j, v, claimedAt); GC_FROM(j, v, source);
}

void to_json(nlohmann::json& j, const SectLevelClaimRecord& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, level); GC_TO(v, j, claimedAtEpochMs);
}
void from_json(const nlohmann::json& j, SectLevelClaimRecord& v) {
    GC_FROM(j, v, level); GC_FROM(j, v, claimedAtEpochMs);
}

void to_json(nlohmann::json& j, const YearlyReport& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, year); GC_TO(v, j, totalIncome); GC_TO(v, j, totalExpenditure);
    GC_TO(v, j, incomeBySource); GC_TO(v, j, expenditureByReason);
    GC_TO(v, j, forgeCompleted); GC_TO(v, j, alchemyCompleted); GC_TO(v, j, herbsHarvested);
    GC_TO(v, j, equipmentBySource); GC_TO(v, j, pillBySource); GC_TO(v, j, herbBySource);
    GC_TO(v, j, newDisciples); GC_TO(v, j, deceasedDisciples); GC_TO(v, j, desertedDisciples);
}
void from_json(const nlohmann::json& j, YearlyReport& v) {
    GC_FROM(j, v, year); GC_FROM(j, v, totalIncome); GC_FROM(j, v, totalExpenditure);
    GC_FROM(j, v, incomeBySource); GC_FROM(j, v, expenditureByReason);
    GC_FROM(j, v, forgeCompleted); GC_FROM(j, v, alchemyCompleted); GC_FROM(j, v, herbsHarvested);
    GC_FROM(j, v, equipmentBySource); GC_FROM(j, v, pillBySource); GC_FROM(j, v, herbBySource);
    GC_FROM(j, v, newDisciples); GC_FROM(j, v, deceasedDisciples); GC_FROM(j, v, desertedDisciples);
}

void to_json(nlohmann::json& j, const PendingTraitAdd& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, discipleId); GC_TO(v, j, type); GC_TO(v, j, traitId);
}
void from_json(const nlohmann::json& j, PendingTraitAdd& v) {
    GC_FROM(j, v, discipleId); GC_FROM(j, v, type); GC_FROM(j, v, traitId);
}

// ── GameData ─────────────────────────────────────────────────────────

void to_json(nlohmann::json& j, const GameData& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, sectName); GC_TO(v, j, currentSlot);
    GC_TO(v, j, gameYear); GC_TO(v, j, gameMonth); GC_TO(v, j, gamePhase);
    GC_TO(v, j, spiritStones); GC_TO(v, j, midGradeSpiritStones);
    GC_TO(v, j, highGradeSpiritStones); GC_TO(v, j, spiritHerbs);
    GC_TO(v, j, sectCultivation); GC_TO(v, j, autoSaveIntervalMonths);
    writeIntKeyMap(j, "yearlySalary", v.yearlySalary);
    writeIntKeyMap(j, "yearlySalaryEnabled", v.yearlySalaryEnabled);
    GC_TO(v, j, activeSectId);
    GC_TO(v, j, merchantLastRefreshYear); GC_TO(v, j, merchantRefreshCount);
    GC_TO(v, j, merchantRefreshChances); GC_TO(v, j, merchantLastRefreshChanceGrantYear);
    GC_TO(v, j, lastRecruitYear); GC_TO(v, j, lastAiSectRecruitYear);
    GC_TO(v, j, recruitCountThisMonth);
    GC_TO(v, j, jadeSymbols); GC_TO(v, j, jadeSymbolsToday);
    GC_TO(v, j, jadeDayAnchorMs); GC_TO(v, j, jadeAccumMs);
    GC_TO(v, j, worldLevelLastRefreshMonth);
    writeIntKeyMap(j, "rngStates", v.rngStates);
    GC_TO(v, j, unlockedRecipes); GC_TO(v, j, unlockedManuals);
    GC_TO(v, j, spiritMineExpansions); GC_TO(v, j, spiritMineLastSettledMonth);
    GC_TO(v, j, usedTeamNumbers); GC_TO(v, j, battleTeamsInitialized);
    GC_TO(v, j, lastSaveTime); GC_TO(v, j, saveVersion);
    GC_TO(v, j, playerProtectionEnabled); GC_TO(v, j, playerProtectionStartYear);
    GC_TO(v, j, playerHasAttackedAI);
    GC_TO(v, j, playerAllianceSlots); GC_TO(v, j, openRecruitmentLastPaidMonth);
    GC_TO(v, j, autoRecruitSpiritRootFilter); GC_TO(v, j, prisonerSpiritRootFilter);
    GC_TO(v, j, autoRejectSpiritRootFilter);
    GC_TO(v, j, breakthroughAutoPillRootCounts);
    GC_TO(v, j, autoEquipFromWarehouseRootCounts);
    GC_TO(v, j, autoLearnFromWarehouseRootCounts);
    GC_TO(v, j, daoCompanionBannedRootCounts); GC_TO(v, j, guideClaimedRewardIds);
    GC_TO(v, j, daoCompanionConsentRequired); GC_TO(v, j, patrolBattleResultPopup);
    GC_TO(v, j, autoSellMidGradeForPurchase); GC_TO(v, j, autoSellHighGradeForPurchase);
    GC_TO(v, j, showAllAvailableDisciples);
    GC_TO(v, j, breakthroughAutoPillFocused);
    GC_TO(v, j, autoEquipFromWarehouseFocused);
    GC_TO(v, j, autoLearnFromWarehouseFocused);
    GC_TO(v, j, isGameOver); GC_TO(v, j, soundEnabled); GC_TO(v, j, musicEnabled);
    GC_TO(v, j, usedRedeemCodes); GC_TO(v, j, watchedItemIds);
    GC_TO(v, j, shownWarningStageIds); GC_TO(v, j, secretRealmCooldownYear);
    GC_TO(v, j, suzerainSectId); GC_TO(v, j, lastYearSpiritStoneIncome);
    GC_TO(v, j, mapSeed);
    GC_TO(v, j, sectAttackCooldowns); GC_TO(v, j, guideCounters);
    GC_TO(v, j, annualIncomeBySource); GC_TO(v, j, annualExpenditureByReason);
    GC_TO(v, j, annualTotalIncome); GC_TO(v, j, annualTotalExpenditure);
    GC_TO(v, j, annualAlchemyCount); GC_TO(v, j, annualForgeCount);
    GC_TO(v, j, annualHerbCount); GC_TO(v, j, annualNewDisciples);
    GC_TO(v, j, annualDeceasedDisciples); GC_TO(v, j, annualDesertedDisciples);
    GC_TO(v, j, annualTheftCount); GC_TO(v, j, theftJudgementsThisMonth);
    GC_TO(v, j, annualEquipmentBySource); GC_TO(v, j, annualPillBySource);
    GC_TO(v, j, annualHerbBySource);
    // 嵌套对象字段（批次 1 第二子步）
    GC_TO(v, j, worldMapSects);
    GC_TO(v, j, travelingMerchantItems); GC_TO(v, j, playerListedItems);
    GC_TO(v, j, merchantAcquisitionItems);
    GC_TO(v, j, recruitList); GC_TO(v, j, worldLevels);
    GC_TO(v, j, elderSlots); GC_TO(v, j, productionSlots);
    GC_TO(v, j, placedBuildings); GC_TO(v, j, spiritFieldPlants);
    GC_TO(v, j, residenceSlots); GC_TO(v, j, patrolConfig); GC_TO(v, j, patrolConfigs);
    GC_TO(v, j, alliances); GC_TO(v, j, vassalContracts); GC_TO(v, j, sectRelations);
    GC_TO(v, j, sectPolicies);
    GC_TO(v, j, mailRecords); GC_TO(v, j, sectLevelClaimRecords);
    GC_TO(v, j, bloodRefinements);
    GC_TO(v, j, yearlyReports); GC_TO(v, j, pendingTraitAdds);
}
void from_json(const nlohmann::json& j, GameData& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, sectName); GC_FROM(j, v, currentSlot);
    GC_FROM(j, v, gameYear); GC_FROM(j, v, gameMonth); GC_FROM(j, v, gamePhase);
    GC_FROM(j, v, spiritStones); GC_FROM(j, v, midGradeSpiritStones);
    GC_FROM(j, v, highGradeSpiritStones); GC_FROM(j, v, spiritHerbs);
    GC_FROM(j, v, sectCultivation); GC_FROM(j, v, autoSaveIntervalMonths);
    readIntKeyMap(j, "yearlySalary", v.yearlySalary);
    readIntKeyMap(j, "yearlySalaryEnabled", v.yearlySalaryEnabled);
    GC_FROM(j, v, activeSectId);
    GC_FROM(j, v, merchantLastRefreshYear); GC_FROM(j, v, merchantRefreshCount);
    GC_FROM(j, v, merchantRefreshChances); GC_FROM(j, v, merchantLastRefreshChanceGrantYear);
    GC_FROM(j, v, lastRecruitYear); GC_FROM(j, v, lastAiSectRecruitYear);
    GC_FROM(j, v, recruitCountThisMonth);
    GC_FROM(j, v, jadeSymbols); GC_FROM(j, v, jadeSymbolsToday);
    GC_FROM(j, v, jadeDayAnchorMs); GC_FROM(j, v, jadeAccumMs);
    GC_FROM(j, v, worldLevelLastRefreshMonth);
    readIntKeyMap(j, "rngStates", v.rngStates);
    GC_FROM(j, v, unlockedRecipes); GC_FROM(j, v, unlockedManuals);
    GC_FROM(j, v, spiritMineExpansions); GC_FROM(j, v, spiritMineLastSettledMonth);
    GC_FROM(j, v, usedTeamNumbers); GC_FROM(j, v, battleTeamsInitialized);
    GC_FROM(j, v, lastSaveTime); GC_FROM(j, v, saveVersion);
    GC_FROM(j, v, playerProtectionEnabled); GC_FROM(j, v, playerProtectionStartYear);
    GC_FROM(j, v, playerHasAttackedAI);
    GC_FROM(j, v, playerAllianceSlots); GC_FROM(j, v, openRecruitmentLastPaidMonth);
    GC_FROM(j, v, autoRecruitSpiritRootFilter); GC_FROM(j, v, prisonerSpiritRootFilter);
    GC_FROM(j, v, autoRejectSpiritRootFilter);
    GC_FROM(j, v, breakthroughAutoPillRootCounts);
    GC_FROM(j, v, autoEquipFromWarehouseRootCounts);
    GC_FROM(j, v, autoLearnFromWarehouseRootCounts);
    GC_FROM(j, v, daoCompanionBannedRootCounts); GC_FROM(j, v, guideClaimedRewardIds);
    GC_FROM(j, v, daoCompanionConsentRequired); GC_FROM(j, v, patrolBattleResultPopup);
    GC_FROM(j, v, autoSellMidGradeForPurchase); GC_FROM(j, v, autoSellHighGradeForPurchase);
    GC_FROM(j, v, showAllAvailableDisciples);
    GC_FROM(j, v, breakthroughAutoPillFocused);
    GC_FROM(j, v, autoEquipFromWarehouseFocused);
    GC_FROM(j, v, autoLearnFromWarehouseFocused);
    GC_FROM(j, v, isGameOver); GC_FROM(j, v, soundEnabled); GC_FROM(j, v, musicEnabled);
    GC_FROM(j, v, usedRedeemCodes); GC_FROM(j, v, watchedItemIds);
    GC_FROM(j, v, shownWarningStageIds); GC_FROM(j, v, secretRealmCooldownYear);
    GC_FROM(j, v, suzerainSectId); GC_FROM(j, v, lastYearSpiritStoneIncome);
    GC_FROM(j, v, mapSeed);
    GC_FROM(j, v, sectAttackCooldowns); GC_FROM(j, v, guideCounters);
    GC_FROM(j, v, annualIncomeBySource); GC_FROM(j, v, annualExpenditureByReason);
    GC_FROM(j, v, annualTotalIncome); GC_FROM(j, v, annualTotalExpenditure);
    GC_FROM(j, v, annualAlchemyCount); GC_FROM(j, v, annualForgeCount);
    GC_FROM(j, v, annualHerbCount); GC_FROM(j, v, annualNewDisciples);
    GC_FROM(j, v, annualDeceasedDisciples); GC_FROM(j, v, annualDesertedDisciples);
    GC_FROM(j, v, annualTheftCount); GC_FROM(j, v, theftJudgementsThisMonth);
    GC_FROM(j, v, annualEquipmentBySource); GC_FROM(j, v, annualPillBySource);
    GC_FROM(j, v, annualHerbBySource);
    // 嵌套对象字段（批次 1 第二子步）
    GC_FROM(j, v, worldMapSects);
    GC_FROM(j, v, travelingMerchantItems); GC_FROM(j, v, playerListedItems);
    GC_FROM(j, v, merchantAcquisitionItems);
    GC_FROM(j, v, recruitList); GC_FROM(j, v, worldLevels);
    GC_FROM(j, v, elderSlots); GC_FROM(j, v, productionSlots);
    GC_FROM(j, v, placedBuildings); GC_FROM(j, v, spiritFieldPlants);
    GC_FROM(j, v, residenceSlots); GC_FROM(j, v, patrolConfig); GC_FROM(j, v, patrolConfigs);
    GC_FROM(j, v, alliances); GC_FROM(j, v, vassalContracts); GC_FROM(j, v, sectRelations);
    GC_FROM(j, v, sectPolicies);
    GC_FROM(j, v, mailRecords); GC_FROM(j, v, sectLevelClaimRecords);
    GC_FROM(j, v, bloodRefinements);
    GC_FROM(j, v, yearlyReports); GC_FROM(j, v, pendingTraitAdds);
}

// ── Full snapshot ────────────────────────────────────────────────────

void to_json(nlohmann::json& j, const GameState& v) {
    j = nlohmann::json::object();
    j["gameData"] = v.gameData;
    j["disciples"] = v.disciples;
    j["equipmentStacks"] = v.equipmentStacks;
    j["equipmentInstances"] = v.equipmentInstances;
    j["manualStacks"] = v.manualStacks;
    j["manualInstances"] = v.manualInstances;
    j["pills"] = v.pills;
    j["materials"] = v.materials;
    j["herbs"] = v.herbs;
    j["seeds"] = v.seeds;
    j["storageBags"] = v.storageBags;
}
void from_json(const nlohmann::json& j, GameState& v) {
    if (j.contains("gameData")) j.at("gameData").get_to(v.gameData);
    if (j.contains("disciples")) j.at("disciples").get_to(v.disciples);
    if (j.contains("equipmentStacks")) j.at("equipmentStacks").get_to(v.equipmentStacks);
    if (j.contains("equipmentInstances")) j.at("equipmentInstances").get_to(v.equipmentInstances);
    if (j.contains("manualStacks")) j.at("manualStacks").get_to(v.manualStacks);
    if (j.contains("manualInstances")) j.at("manualInstances").get_to(v.manualInstances);
    if (j.contains("pills")) j.at("pills").get_to(v.pills);
    if (j.contains("materials")) j.at("materials").get_to(v.materials);
    if (j.contains("herbs")) j.at("herbs").get_to(v.herbs);
    if (j.contains("seeds")) j.at("seeds").get_to(v.seeds);
    if (j.contains("storageBags")) j.at("storageBags").get_to(v.storageBags);
}

// ── Float normalization for export ───────────────────────────────────
// kotlinx stream decoder (1.7.x) rejects "N.0" trailing-dot-zero numbers
// ("Unexpected symbol '.' in numeric literal"). Integral-valued doubles are
// emitted as plain integers (12000), which Kotlin decodes into Double fine;
// non-integral values (12345.6) keep the decimal point.

static void normalizeIntegralFloats(nlohmann::json& j) {
    if (j.is_object()) {
        for (auto it = j.begin(); it != j.end(); ++it) normalizeIntegralFloats(it.value());
    } else if (j.is_array()) {
        for (auto& v : j) normalizeIntegralFloats(v);
    } else if (j.is_number_float()) {
        const double d = j.get<double>();
        if (d == std::floor(d) && std::abs(d) < 9.2e18) {
            j = static_cast<int64_t>(d);
        }
    }
}

std::string dumpStateJson(const GameState& v) {
    nlohmann::json j = v;
    normalizeIntegralFloats(j);
    return j.dump();
}

}  // namespace gamecore::state
