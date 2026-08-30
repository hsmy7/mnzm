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
    GC_TO(v, j, effects); GC_TO(v, j, minRealm); GC_TO(v, j, quantity); GC_TO(v, j, isLocked);
}
void from_json(const nlohmann::json& j, Pill& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, slotId); GC_FROM(j, v, name);
    GC_FROM(j, v, rarity); GC_FROM(j, v, description);
    GC_FROM(j, v, category); GC_FROM(j, v, grade); GC_FROM(j, v, pillType);
    GC_FROM(j, v, effects); GC_FROM(j, v, minRealm); GC_FROM(j, v, quantity); GC_FROM(j, v, isLocked);
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
// 字段名与 Kotlin DiscipleSerializer.DiscipleSurrogate 的 JSON 键一致（平铺）：
// combat/pillEffects/equipment/social/skills/usage 六个 @Embedded 段在
// JSON 层均为顶层字段。""=null（社交字符串），-1/0=null 哨兵见 models.h 注释。

void to_json(nlohmann::json& j, const Disciple& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, name); GC_TO(v, j, surname);
    GC_TO(v, j, realm); GC_TO(v, j, realmLayer); GC_TO(v, j, cultivation);
    // cultivationCheckpoint：Kotlin 序列化为 Long（DiscipleSurrogate toLong 向零
    // 截断）——导出按同语义输出整数，防 kotlinx 流式解码器拒绝小数字面量；
    // 运行期两侧均为 double（DiscipleTables.cultivationCheckpoints 列），一致
    j["cultivationCheckpoint"] = static_cast<int64_t>(v.cultivationCheckpoint);
    GC_TO(v, j, cultivationCheckpointGameMonth);
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
    // CombatAttributes
    GC_TO(v, j, baseHp); GC_TO(v, j, baseMp);
    GC_TO(v, j, basePhysicalAttack); GC_TO(v, j, baseMagicAttack);
    GC_TO(v, j, basePhysicalDefense); GC_TO(v, j, baseMagicDefense);
    GC_TO(v, j, baseSpeed);
    GC_TO(v, j, hpVariance); GC_TO(v, j, mpVariance);
    GC_TO(v, j, physicalAttackVariance); GC_TO(v, j, magicAttackVariance);
    GC_TO(v, j, physicalDefenseVariance); GC_TO(v, j, magicDefenseVariance);
    GC_TO(v, j, speedVariance);
    GC_TO(v, j, totalCultivation);
    GC_TO(v, j, breakthroughCount); GC_TO(v, j, breakthroughFailCount);
    GC_TO(v, j, currentHp); GC_TO(v, j, currentMp);
    // PillEffects
    GC_TO(v, j, pillPhysicalAttackBonus); GC_TO(v, j, pillMagicAttackBonus);
    GC_TO(v, j, pillPhysicalDefenseBonus); GC_TO(v, j, pillMagicDefenseBonus);
    GC_TO(v, j, pillHpBonus); GC_TO(v, j, pillMpBonus);
    GC_TO(v, j, pillSpeedBonus);
    GC_TO(v, j, pillCritRateBonus); GC_TO(v, j, pillCritEffectBonus);
    GC_TO(v, j, pillCultivationSpeedBonus); GC_TO(v, j, pillSkillExpSpeedBonus);
    GC_TO(v, j, pillNurtureSpeedBonus); GC_TO(v, j, pillEffectDuration);
    GC_TO(v, j, activePillTypes); GC_TO(v, j, activePillCategory);
    // EquipmentSet
    GC_TO(v, j, weaponId); GC_TO(v, j, armorId);
    GC_TO(v, j, bootsId); GC_TO(v, j, accessoryId);
    GC_TO(v, j, weaponNurture); GC_TO(v, j, armorNurture);
    GC_TO(v, j, bootsNurture); GC_TO(v, j, accessoryNurture);
    GC_TO(v, j, storageBagItems); GC_TO(v, j, storageBagSpiritStones);
    GC_TO(v, j, spiritStones);
    // SocialData
    GC_TO(v, j, partnerId); GC_TO(v, j, partnerSectId);
    GC_TO(v, j, parentId1); GC_TO(v, j, parentId2);
    GC_TO(v, j, lastChildYear); GC_TO(v, j, childBirthMonth);
    GC_TO(v, j, griefEndYear); GC_TO(v, j, masterId);
    // SkillStats
    GC_TO(v, j, intelligence); GC_TO(v, j, charm); GC_TO(v, j, loyalty);
    GC_TO(v, j, comprehension); GC_TO(v, j, artifactRefining);
    GC_TO(v, j, pillRefining); GC_TO(v, j, spiritPlanting);
    GC_TO(v, j, mining); GC_TO(v, j, teaching); GC_TO(v, j, morality);
    GC_TO(v, j, aptitude);
    GC_TO(v, j, salaryPaidCount); GC_TO(v, j, salaryMissedCount);
    GC_TO(v, j, alchemyLevel); GC_TO(v, j, alchemyPromotionCount);
    GC_TO(v, j, forgeLevel); GC_TO(v, j, forgePromotionCount);
    // UsageTracking
    GC_TO(v, j, usedPermanentPillKeys); GC_TO(v, j, usedExtendLifePillTypes);
    GC_TO(v, j, usedFunctionalPillTypes); GC_TO(v, j, usedExtendLifePillIds);
    GC_TO(v, j, recruitedMonth);
    GC_TO(v, j, hasReviveEffect); GC_TO(v, j, hasClearAllEffect);
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
    // CombatAttributes
    GC_FROM(j, v, baseHp); GC_FROM(j, v, baseMp);
    GC_FROM(j, v, basePhysicalAttack); GC_FROM(j, v, baseMagicAttack);
    GC_FROM(j, v, basePhysicalDefense); GC_FROM(j, v, baseMagicDefense);
    GC_FROM(j, v, baseSpeed);
    GC_FROM(j, v, hpVariance); GC_FROM(j, v, mpVariance);
    GC_FROM(j, v, physicalAttackVariance); GC_FROM(j, v, magicAttackVariance);
    GC_FROM(j, v, physicalDefenseVariance); GC_FROM(j, v, magicDefenseVariance);
    GC_FROM(j, v, speedVariance);
    GC_FROM(j, v, totalCultivation);
    GC_FROM(j, v, breakthroughCount); GC_FROM(j, v, breakthroughFailCount);
    GC_FROM(j, v, currentHp); GC_FROM(j, v, currentMp);
    // PillEffects
    GC_FROM(j, v, pillPhysicalAttackBonus); GC_FROM(j, v, pillMagicAttackBonus);
    GC_FROM(j, v, pillPhysicalDefenseBonus); GC_FROM(j, v, pillMagicDefenseBonus);
    GC_FROM(j, v, pillHpBonus); GC_FROM(j, v, pillMpBonus);
    GC_FROM(j, v, pillSpeedBonus);
    GC_FROM(j, v, pillCritRateBonus); GC_FROM(j, v, pillCritEffectBonus);
    GC_FROM(j, v, pillCultivationSpeedBonus); GC_FROM(j, v, pillSkillExpSpeedBonus);
    GC_FROM(j, v, pillNurtureSpeedBonus); GC_FROM(j, v, pillEffectDuration);
    GC_FROM(j, v, activePillTypes); GC_FROM(j, v, activePillCategory);
    // EquipmentSet
    GC_FROM(j, v, weaponId); GC_FROM(j, v, armorId);
    GC_FROM(j, v, bootsId); GC_FROM(j, v, accessoryId);
    GC_FROM(j, v, weaponNurture); GC_FROM(j, v, armorNurture);
    GC_FROM(j, v, bootsNurture); GC_FROM(j, v, accessoryNurture);
    GC_FROM(j, v, storageBagItems); GC_FROM(j, v, storageBagSpiritStones);
    GC_FROM(j, v, spiritStones);
    // SocialData
    GC_FROM(j, v, partnerId); GC_FROM(j, v, partnerSectId);
    GC_FROM(j, v, parentId1); GC_FROM(j, v, parentId2);
    GC_FROM(j, v, lastChildYear); GC_FROM(j, v, childBirthMonth);
    GC_FROM(j, v, griefEndYear); GC_FROM(j, v, masterId);
    // SkillStats
    GC_FROM(j, v, intelligence); GC_FROM(j, v, charm); GC_FROM(j, v, loyalty);
    GC_FROM(j, v, comprehension); GC_FROM(j, v, artifactRefining);
    GC_FROM(j, v, pillRefining); GC_FROM(j, v, spiritPlanting);
    GC_FROM(j, v, mining); GC_FROM(j, v, teaching); GC_FROM(j, v, morality);
    GC_FROM(j, v, aptitude);
    GC_FROM(j, v, salaryPaidCount); GC_FROM(j, v, salaryMissedCount);
    GC_FROM(j, v, alchemyLevel); GC_FROM(j, v, alchemyPromotionCount);
    GC_FROM(j, v, forgeLevel); GC_FROM(j, v, forgePromotionCount);
    // UsageTracking
    GC_FROM(j, v, usedPermanentPillKeys); GC_FROM(j, v, usedExtendLifePillTypes);
    GC_FROM(j, v, usedFunctionalPillTypes); GC_FROM(j, v, usedExtendLifePillIds);
    GC_FROM(j, v, recruitedMonth);
    GC_FROM(j, v, hasReviveEffect); GC_FROM(j, v, hasClearAllEffect);
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
    GC_TO(v, j, id); GC_TO(v, j, name); GC_TO(v, j, type); GC_TO(v, j, itemId);
    GC_TO(v, j, rarity); GC_TO(v, j, price); GC_TO(v, j, quantity); GC_TO(v, j, description);
    GC_TO(v, j, obtainedYear); GC_TO(v, j, obtainedMonth); GC_TO_OPT(v, j, grade);
}
void from_json(const nlohmann::json& j, MerchantItem& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, name); GC_FROM(j, v, type); GC_FROM(j, v, itemId);
    GC_FROM(j, v, rarity); GC_FROM(j, v, price); GC_FROM(j, v, quantity); GC_FROM(j, v, description);
    GC_FROM(j, v, obtainedYear); GC_FROM(j, v, obtainedMonth); GC_FROM_OPT(j, v, grade);
}

// ── 批 11-3：自动购买条目 ──

void to_json(nlohmann::json& j, const AutoBuyEntry& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, itemName); GC_TO(v, j, itemType); GC_TO(v, j, rarity);
}
void from_json(const nlohmann::json& j, AutoBuyEntry& v) {
    GC_FROM(j, v, itemName); GC_FROM(j, v, itemType); GC_FROM(j, v, rarity);
}

// ── 批 12-2：任务域（S8 子事件 14 任务刷新下沉） ──

void to_json(nlohmann::json& j, const MissionRewardConfig& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, spiritStones); GC_TO(v, j, spiritStonesMax);
    GC_TO(v, j, materialCountMin); GC_TO(v, j, materialCountMax);
    GC_TO(v, j, materialMinRarity); GC_TO(v, j, materialMaxRarity);
    GC_TO(v, j, pillCountMin); GC_TO(v, j, pillCountMax);
    GC_TO(v, j, pillMinRarity); GC_TO(v, j, pillMaxRarity);
    GC_TO(v, j, equipmentChance);
    GC_TO(v, j, equipmentMinRarity); GC_TO(v, j, equipmentMaxRarity);
    GC_TO(v, j, manualChance);
    GC_TO(v, j, manualMinRarity); GC_TO(v, j, manualMaxRarity);
    GC_TO(v, j, baseSpiritStones);
    GC_TO(v, j, baseMaterialCountMin); GC_TO(v, j, baseMaterialCountMax);
    GC_TO(v, j, baseMaterialMinRarity); GC_TO(v, j, baseMaterialMaxRarity);
}
void from_json(const nlohmann::json& j, MissionRewardConfig& v) {
    GC_FROM(j, v, spiritStones); GC_FROM(j, v, spiritStonesMax);
    GC_FROM(j, v, materialCountMin); GC_FROM(j, v, materialCountMax);
    GC_FROM(j, v, materialMinRarity); GC_FROM(j, v, materialMaxRarity);
    GC_FROM(j, v, pillCountMin); GC_FROM(j, v, pillCountMax);
    GC_FROM(j, v, pillMinRarity); GC_FROM(j, v, pillMaxRarity);
    GC_FROM(j, v, equipmentChance);
    GC_FROM(j, v, equipmentMinRarity); GC_FROM(j, v, equipmentMaxRarity);
    GC_FROM(j, v, manualChance);
    GC_FROM(j, v, manualMinRarity); GC_FROM(j, v, manualMaxRarity);
    GC_FROM(j, v, baseSpiritStones);
    GC_FROM(j, v, baseMaterialCountMin); GC_FROM(j, v, baseMaterialCountMax);
    GC_FROM(j, v, baseMaterialMinRarity); GC_FROM(j, v, baseMaterialMaxRarity);
}

void to_json(nlohmann::json& j, const Mission& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); j["template"] = v.template_;
    GC_TO(v, j, name); GC_TO(v, j, description);
    GC_TO(v, j, difficulty); GC_TO(v, j, duration); GC_TO(v, j, rewards);
    GC_TO(v, j, missionType); GC_TO(v, j, enemyType); GC_TO(v, j, triggerChance);
    GC_TO(v, j, createdYear); GC_TO(v, j, createdMonth);
}
void from_json(const nlohmann::json& j, Mission& v) {
    GC_FROM(j, v, id); v.template_ = j.value("template", std::string());
    GC_FROM(j, v, name); GC_FROM(j, v, description);
    GC_FROM(j, v, difficulty); GC_FROM(j, v, duration); GC_FROM(j, v, rewards);
    GC_FROM(j, v, missionType); GC_FROM(j, v, enemyType); GC_FROM(j, v, triggerChance);
    GC_FROM(j, v, createdYear); GC_FROM(j, v, createdMonth);
}

void to_json(nlohmann::json& j, const Alliance& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, sectIds); GC_TO(v, j, startYear); GC_TO(v, j, initiatorId);
}
void from_json(const nlohmann::json& j, Alliance& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, sectIds); GC_FROM(j, v, startYear); GC_FROM(j, v, initiatorId);
}

// ── 批 10-1：宗门详情域（S8 侦察过期清理子事件协议扩容） ──

void to_json(nlohmann::json& j, const MineSlot& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, index); GC_TO(v, j, discipleId); GC_TO(v, j, discipleName);
    GC_TO(v, j, output); GC_TO(v, j, efficiency); GC_TO(v, j, isActive);
}
void from_json(const nlohmann::json& j, MineSlot& v) {
    GC_FROM(j, v, index); GC_FROM(j, v, discipleId); GC_FROM(j, v, discipleName);
    GC_FROM(j, v, output); GC_FROM(j, v, efficiency); GC_FROM(j, v, isActive);
}

void to_json(nlohmann::json& j, const WarehouseItem& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, itemId); GC_TO(v, j, itemName); GC_TO(v, j, itemType);
    GC_TO(v, j, rarity); GC_TO(v, j, quantity);
}
void from_json(const nlohmann::json& j, WarehouseItem& v) {
    GC_FROM(j, v, itemId); GC_FROM(j, v, itemName); GC_FROM(j, v, itemType);
    GC_FROM(j, v, rarity); GC_FROM(j, v, quantity);
}

void to_json(nlohmann::json& j, const SectWarehouse& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, items); GC_TO(v, j, spiritStones);
    GC_TO(v, j, midGradeSpiritStones); GC_TO(v, j, highGradeSpiritStones);
}
void from_json(const nlohmann::json& j, SectWarehouse& v) {
    GC_FROM(j, v, items); GC_FROM(j, v, spiritStones);
    GC_FROM(j, v, midGradeSpiritStones); GC_FROM(j, v, highGradeSpiritStones);
}

void to_json(nlohmann::json& j, const SectScoutInfo& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, sectId); GC_TO(v, j, sectName);
    GC_TO(v, j, scoutYear); GC_TO(v, j, scoutMonth);
    GC_TO(v, j, discipleCount); GC_TO(v, j, maxRealm);
    GC_TO(v, j, resources); GC_TO(v, j, isKnown); GC_TO(v, j, disciples);
    GC_TO(v, j, expiryYear); GC_TO(v, j, expiryMonth);
}
void from_json(const nlohmann::json& j, SectScoutInfo& v) {
    GC_FROM(j, v, sectId); GC_FROM(j, v, sectName);
    GC_FROM(j, v, scoutYear); GC_FROM(j, v, scoutMonth);
    GC_FROM(j, v, discipleCount); GC_FROM(j, v, maxRealm);
    GC_FROM(j, v, resources); GC_FROM(j, v, isKnown); GC_FROM(j, v, disciples);
    GC_FROM(j, v, expiryYear); GC_FROM(j, v, expiryMonth);
}

void to_json(nlohmann::json& j, const SectDetail& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, sectId); GC_TO(v, j, mineSlots); GC_TO(v, j, occupationTime);
    GC_TO(v, j, isOwned); GC_TO(v, j, expiryYear); GC_TO(v, j, expiryMonth);
    GC_TO(v, j, scoutInfo); GC_TO(v, j, tradeItems);
    GC_TO(v, j, tradeLastRefreshYear); GC_TO(v, j, lastGiftYear);
    GC_TO(v, j, warehouse); GC_TO(v, j, giftPreference); GC_TO(v, j, portraitRes);
}
void from_json(const nlohmann::json& j, SectDetail& v) {
    GC_FROM(j, v, sectId); GC_FROM(j, v, mineSlots); GC_FROM(j, v, occupationTime);
    GC_FROM(j, v, isOwned); GC_FROM(j, v, expiryYear); GC_FROM(j, v, expiryMonth);
    GC_FROM(j, v, scoutInfo); GC_FROM(j, v, tradeItems);
    GC_FROM(j, v, tradeLastRefreshYear); GC_FROM(j, v, lastGiftYear);
    GC_FROM(j, v, warehouse); GC_FROM(j, v, giftPreference); GC_FROM(j, v, portraitRes);
}

void to_json(nlohmann::json& j, const VassalContract& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, vassalSectId); GC_TO(v, j, establishedYear); GC_TO(v, j, lastTributeYear);
}
void from_json(const nlohmann::json& j, VassalContract& v) {
    GC_FROM(j, v, vassalSectId); GC_FROM(j, v, establishedYear); GC_FROM(j, v, lastTributeYear);
}

void to_json(nlohmann::json& j, const SectRelation& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, sectId1); GC_TO(v, j, sectId2);
    GC_TO(v, j, favor); GC_TO(v, j, lastInteractionYear); GC_TO(v, j, noGiftYears);
    GC_TO(v, j, acquainted);
}
void from_json(const nlohmann::json& j, SectRelation& v) {
    GC_FROM(j, v, sectId1); GC_FROM(j, v, sectId2);
    GC_FROM(j, v, favor); GC_FROM(j, v, lastInteractionYear); GC_FROM(j, v, noGiftYears);
    GC_FROM(j, v, acquainted);
}

// ── 批 10-4：SectBattleRecord（宗门战报；SectBattleType 存 name） ──
void to_json(nlohmann::json& j, const SectBattleRecord& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, year); GC_TO(v, j, type);
}
void from_json(const nlohmann::json& j, SectBattleRecord& v) {
    GC_FROM(j, v, year); GC_FROM(j, v, type);
}

// ── 批 4-5：槽位清理补充模型（定义于 WorldSect 前，WorldSect 引用） ──

void to_json(nlohmann::json& j, const GarrisonSlot& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, index); GC_TO(v, j, discipleId); GC_TO(v, j, discipleName);
    GC_TO(v, j, discipleRealm); GC_TO(v, j, discipleSpiritRootColor); GC_TO(v, j, portraitRes);
}
void from_json(const nlohmann::json& j, GarrisonSlot& v) {
    GC_FROM(j, v, index); GC_FROM(j, v, discipleId); GC_FROM(j, v, discipleName);
    GC_FROM(j, v, discipleRealm); GC_FROM(j, v, discipleSpiritRootColor); GC_FROM(j, v, portraitRes);
}

void to_json(nlohmann::json& j, const BattleTeamSlot& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, index); GC_TO(v, j, discipleId); GC_TO(v, j, discipleName);
    GC_TO(v, j, discipleRealm); GC_TO(v, j, slotType); GC_TO(v, j, isAlive);
}
void from_json(const nlohmann::json& j, BattleTeamSlot& v) {
    GC_FROM(j, v, index); GC_FROM(j, v, discipleId); GC_FROM(j, v, discipleName);
    GC_FROM(j, v, discipleRealm); GC_FROM(j, v, slotType); GC_FROM(j, v, isAlive);
}

void to_json(nlohmann::json& j, const BattleTeam& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, name); GC_TO(v, j, teamNumber); GC_TO(v, j, slots);
    GC_TO(v, j, isAtSect); GC_TO(v, j, currentX); GC_TO(v, j, currentY);
    GC_TO(v, j, targetX); GC_TO(v, j, targetY); GC_TO(v, j, status);
    GC_TO(v, j, targetSectId); GC_TO(v, j, originSectId); GC_TO(v, j, route);
    GC_TO(v, j, currentRouteIndex); GC_TO(v, j, moveProgress);
    GC_TO(v, j, isOccupying); GC_TO(v, j, occupiedSectId); GC_TO(v, j, isReturning);
}
void from_json(const nlohmann::json& j, BattleTeam& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, name); GC_FROM(j, v, teamNumber); GC_FROM(j, v, slots);
    GC_FROM(j, v, isAtSect); GC_FROM(j, v, currentX); GC_FROM(j, v, currentY);
    GC_FROM(j, v, targetX); GC_FROM(j, v, targetY); GC_FROM(j, v, status);
    GC_FROM(j, v, targetSectId); GC_FROM(j, v, originSectId); GC_FROM(j, v, route);
    GC_FROM(j, v, currentRouteIndex); GC_FROM(j, v, moveProgress);
    GC_FROM(j, v, isOccupying); GC_FROM(j, v, occupiedSectId); GC_FROM(j, v, isReturning);
}

void to_json(nlohmann::json& j, const WarehouseGarrisonSlot& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, buildingInstanceId); GC_TO(v, j, discipleId); GC_TO(v, j, discipleName);
    GC_TO(v, j, sectId); GC_TO(v, j, slotIndex);
}
void from_json(const nlohmann::json& j, WarehouseGarrisonSlot& v) {
    GC_FROM(j, v, buildingInstanceId); GC_FROM(j, v, discipleId); GC_FROM(j, v, discipleName);
    GC_FROM(j, v, sectId); GC_FROM(j, v, slotIndex);
}

void to_json(nlohmann::json& j, const CaveExplorationTeam& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, caveId); GC_TO(v, j, caveName);
    GC_TO(v, j, memberIds); GC_TO(v, j, memberNames);
    GC_TO(v, j, startYear); GC_TO(v, j, startMonth); GC_TO(v, j, duration); GC_TO(v, j, status);
    GC_TO(v, j, startX); GC_TO(v, j, startY); GC_TO(v, j, targetX); GC_TO(v, j, targetY);
    GC_TO(v, j, currentX); GC_TO(v, j, currentY); GC_TO(v, j, moveProgress);
}
void from_json(const nlohmann::json& j, CaveExplorationTeam& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, caveId); GC_FROM(j, v, caveName);
    GC_FROM(j, v, memberIds); GC_FROM(j, v, memberNames);
    GC_FROM(j, v, startYear); GC_FROM(j, v, startMonth); GC_FROM(j, v, duration); GC_FROM(j, v, status);
    GC_FROM(j, v, startX); GC_FROM(j, v, startY); GC_FROM(j, v, targetX); GC_FROM(j, v, targetY);
    GC_FROM(j, v, currentX); GC_FROM(j, v, currentY); GC_FROM(j, v, moveProgress);
}

void to_json(nlohmann::json& j, const ActiveMissionLite& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, discipleIds); GC_TO(v, j, discipleNames);
}
void from_json(const nlohmann::json& j, ActiveMissionLite& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, discipleIds); GC_FROM(j, v, discipleNames);
}

void to_json(nlohmann::json& j, const MailAttachment& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, type); GC_TO(v, j, name); GC_TO(v, j, quantity); GC_TO(v, j, rarity);
    GC_TO_OPT(v, j, itemId);
    GC_TO(v, j, extra);
}
void from_json(const nlohmann::json& j, MailAttachment& v) {
    GC_FROM(j, v, type); GC_FROM(j, v, name); GC_FROM(j, v, quantity); GC_FROM(j, v, rarity);
    GC_FROM_OPT(j, v, itemId);
    GC_FROM(j, v, extra);
}

void to_json(nlohmann::json& j, const WorldSect& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id);
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
    GC_TO(v, j, garrisonSlots);
}
void from_json(const nlohmann::json& j, WorldSect& v) {
    GC_FROM(j, v, id);
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
    GC_FROM(j, v, garrisonSlots);
}

void to_json(nlohmann::json& j, const ResidenceSlot& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, buildingInstanceId); GC_TO(v, j, slotIndex);
    GC_TO(v, j, discipleId); GC_TO(v, j, discipleName);
}
void from_json(const nlohmann::json& j, ResidenceSlot& v) {
    GC_FROM(j, v, buildingInstanceId); GC_FROM(j, v, slotIndex);
    GC_FROM(j, v, discipleId); GC_FROM(j, v, discipleName);
}

void to_json(nlohmann::json& j, const SpiritFieldPlant& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, buildingInstanceId); GC_TO(v, j, seedId); GC_TO(v, j, seedName);
    GC_TO(v, j, growTime); GC_TO(v, j, expectedYield);
    GC_TO(v, j, plantYear); GC_TO(v, j, plantMonth); GC_TO(v, j, sectId);
    GC_TO(v, j, completionMonth); GC_TO(v, j, completionPhase);
}
void from_json(const nlohmann::json& j, SpiritFieldPlant& v) {
    GC_FROM(j, v, buildingInstanceId); GC_FROM(j, v, seedId); GC_FROM(j, v, seedName);
    GC_FROM(j, v, growTime); GC_FROM(j, v, expectedYield);
    GC_FROM(j, v, plantYear); GC_FROM(j, v, plantMonth); GC_FROM(j, v, sectId);
    GC_FROM(j, v, completionMonth); GC_FROM(j, v, completionPhase);
}

void to_json(nlohmann::json& j, const PatrolConfig& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, targetRealms); GC_TO(v, j, maxBeastCount); GC_TO(v, j, requireFullStatus);
}
void from_json(const nlohmann::json& j, PatrolConfig& v) {
    GC_FROM(j, v, targetRealms); GC_FROM(j, v, maxBeastCount); GC_FROM(j, v, requireFullStatus);
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
    GC_TO(v, j, beastSpeed);
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
    GC_FROM(j, v, beastSpeed);
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

// ── 批次 1 剩余：低频嵌套类型 ─────────────────────────────────────

void to_json(nlohmann::json& j, const BloodRefinementProgress& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, discipleId); GC_TO(v, j, discipleName);
    GC_TO(v, j, materialId); GC_TO(v, j, materialName);
    GC_TO(v, j, startYear); GC_TO(v, j, startMonth);
    GC_TO(v, j, durationMonths); GC_TO(v, j, selectedStat);
    GC_TO(v, j, bonusPercent);
}
void from_json(const nlohmann::json& j, BloodRefinementProgress& v) {
    GC_FROM(j, v, discipleId); GC_FROM(j, v, discipleName);
    GC_FROM(j, v, materialId); GC_FROM(j, v, materialName);
    GC_FROM(j, v, startYear); GC_FROM(j, v, startMonth);
    GC_FROM(j, v, durationMonths); GC_FROM(j, v, selectedStat);
    GC_FROM(j, v, bonusPercent);
}

void to_json(nlohmann::json& j, const BloodRefinementBonusTotal& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, discipleId); GC_TO(v, j, hpBonus);
    GC_TO(v, j, physicalAttackBonus); GC_TO(v, j, magicAttackBonus);
    GC_TO(v, j, physicalDefenseBonus); GC_TO(v, j, magicDefenseBonus);
    GC_TO(v, j, speedBonus);
}
void from_json(const nlohmann::json& j, BloodRefinementBonusTotal& v) {
    GC_FROM(j, v, discipleId); GC_FROM(j, v, hpBonus);
    GC_FROM(j, v, physicalAttackBonus); GC_FROM(j, v, magicAttackBonus);
    GC_FROM(j, v, physicalDefenseBonus); GC_FROM(j, v, magicDefenseBonus);
    GC_FROM(j, v, speedBonus);
}

void to_json(nlohmann::json& j, const BloodRefinementPctTotal& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, discipleId); GC_TO(v, j, hpBonusPct);
    GC_TO(v, j, physicalAttackBonusPct); GC_TO(v, j, magicAttackBonusPct);
    GC_TO(v, j, physicalDefenseBonusPct); GC_TO(v, j, magicDefenseBonusPct);
    GC_TO(v, j, speedBonusPct);
}
void from_json(const nlohmann::json& j, BloodRefinementPctTotal& v) {
    GC_FROM(j, v, discipleId); GC_FROM(j, v, hpBonusPct);
    GC_FROM(j, v, physicalAttackBonusPct); GC_FROM(j, v, magicAttackBonusPct);
    GC_FROM(j, v, physicalDefenseBonusPct); GC_FROM(j, v, magicDefenseBonusPct);
    GC_FROM(j, v, speedBonusPct);
}

void to_json(nlohmann::json& j, const ManualProficiencyData& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, manualId); GC_TO(v, j, manualName);
    GC_TO(v, j, proficiency); GC_TO(v, j, maxProficiency);
    GC_TO(v, j, level); GC_TO(v, j, masteryLevel);
}
void from_json(const nlohmann::json& j, ManualProficiencyData& v) {
    GC_FROM(j, v, manualId); GC_FROM(j, v, manualName);
    GC_FROM(j, v, proficiency); GC_FROM(j, v, maxProficiency);
    GC_FROM(j, v, level); GC_FROM(j, v, masteryLevel);
}

void to_json(nlohmann::json& j, const SpiritMineSlot& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, index); GC_TO(v, j, discipleId); GC_TO(v, j, discipleName);
    GC_TO(v, j, output); GC_TO(v, j, sectId);
    GC_TO(v, j, consecutiveMiningMonths); GC_TO(v, j, buildingInstanceId);
}
void from_json(const nlohmann::json& j, SpiritMineSlot& v) {
    GC_FROM(j, v, index); GC_FROM(j, v, discipleId); GC_FROM(j, v, discipleName);
    GC_FROM(j, v, output); GC_FROM(j, v, sectId);
    GC_FROM(j, v, consecutiveMiningMonths); GC_FROM(j, v, buildingInstanceId);
}

void to_json(nlohmann::json& j, const PatrolSlot& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, index); GC_TO(v, j, discipleId); GC_TO(v, j, discipleName);
    GC_TO(v, j, discipleRealm); GC_TO(v, j, portraitRes); GC_TO(v, j, buildingInstanceId);
}
void from_json(const nlohmann::json& j, PatrolSlot& v) {
    GC_FROM(j, v, index); GC_FROM(j, v, discipleId); GC_FROM(j, v, discipleName);
    GC_FROM(j, v, discipleRealm); GC_FROM(j, v, portraitRes); GC_FROM(j, v, buildingInstanceId);
}

// ── SecretRealm 状态机（批次 1 剩余）─────────────────────────────────

void to_json(nlohmann::json& j, const SecretRealmState& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, name); GC_TO(v, j, x); GC_TO(v, j, y);
    GC_TO(v, j, spawnYear); GC_TO(v, j, spawnMonth); GC_TO(v, j, spriteIndex);
}
void from_json(const nlohmann::json& j, SecretRealmState& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, name); GC_FROM(j, v, x); GC_FROM(j, v, y);
    GC_FROM(j, v, spawnYear); GC_FROM(j, v, spawnMonth); GC_FROM(j, v, spriteIndex);
}

void to_json(nlohmann::json& j, const SecretRealmMemberState& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, discipleId); GC_TO(v, j, name); GC_TO(v, j, portraitRes);
    GC_TO(v, j, realm); GC_TO(v, j, realmName); GC_TO(v, j, currentHp);
    GC_TO(v, j, isDying); GC_TO(v, j, isDead); GC_TO(v, j, maxHp);
}
void from_json(const nlohmann::json& j, SecretRealmMemberState& v) {
    GC_FROM(j, v, discipleId); GC_FROM(j, v, name); GC_FROM(j, v, portraitRes);
    GC_FROM(j, v, realm); GC_FROM(j, v, realmName); GC_FROM(j, v, currentHp);
    GC_FROM(j, v, isDying); GC_FROM(j, v, isDead); GC_FROM(j, v, maxHp);
}

void to_json(nlohmann::json& j, const SecretRealmOption& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, label); GC_TO(v, j, description); GC_TO(v, j, staminaCost);
}
void from_json(const nlohmann::json& j, SecretRealmOption& v) {
    GC_FROM(j, v, label); GC_FROM(j, v, description); GC_FROM(j, v, staminaCost);
}

void to_json(nlohmann::json& j, const SecretRealmRewardItem& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, type); GC_TO(v, j, itemId); GC_TO(v, j, name);
    GC_TO(v, j, rarity); GC_TO(v, j, quantity);
}
void from_json(const nlohmann::json& j, SecretRealmRewardItem& v) {
    GC_FROM(j, v, type); GC_FROM(j, v, itemId); GC_FROM(j, v, name);
    GC_FROM(j, v, rarity); GC_FROM(j, v, quantity);
}

void to_json(nlohmann::json& j, const SecretRealmAIMember& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, discipleId); GC_TO(v, j, name); GC_TO(v, j, portraitRes); GC_TO(v, j, realm);
}
void from_json(const nlohmann::json& j, SecretRealmAIMember& v) {
    GC_FROM(j, v, discipleId); GC_FROM(j, v, name); GC_FROM(j, v, portraitRes); GC_FROM(j, v, realm);
}

void to_json(nlohmann::json& j, const SecretRealmEventParams& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, beastTypeName); GC_TO(v, j, beastRealm); GC_TO(v, j, beastCount);
    GC_TO(v, j, ambushSucceeded); GC_TO(v, j, beastLayer); GC_TO(v, j, lostItemCount);
    GC_TO(v, j, spiritStones); GC_TO(v, j, itemRewards);
    GC_TO(v, j, aiSectId); GC_TO(v, j, aiSectName); GC_TO(v, j, aiSectLevel);
    GC_TO(v, j, aiMembers);
}
void from_json(const nlohmann::json& j, SecretRealmEventParams& v) {
    GC_FROM(j, v, beastTypeName); GC_FROM(j, v, beastRealm); GC_FROM(j, v, beastCount);
    GC_FROM(j, v, ambushSucceeded); GC_FROM(j, v, beastLayer); GC_FROM(j, v, lostItemCount);
    GC_FROM(j, v, spiritStones); GC_FROM(j, v, itemRewards);
    GC_FROM(j, v, aiSectId); GC_FROM(j, v, aiSectName); GC_FROM(j, v, aiSectLevel);
    GC_FROM(j, v, aiMembers);
}

void to_json(nlohmann::json& j, const SecretRealmEventRecord& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, eventType); GC_TO(v, j, title); GC_TO(v, j, description);
    GC_TO(v, j, options); GC_TO(v, j, chosenOptionIndex); GC_TO(v, j, resultText);
    GC_TO(v, j, params); GC_TO(v, j, absoluteMonth);
}
void from_json(const nlohmann::json& j, SecretRealmEventRecord& v) {
    GC_FROM(j, v, eventType); GC_FROM(j, v, title); GC_FROM(j, v, description);
    GC_FROM(j, v, options); GC_FROM(j, v, chosenOptionIndex); GC_FROM(j, v, resultText);
    GC_FROM(j, v, params); GC_FROM(j, v, absoluteMonth);
}

void to_json(nlohmann::json& j, const SecretRealmBackpack& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, spiritStones); GC_TO(v, j, equipment); GC_TO(v, j, manuals);
    GC_TO(v, j, pills); GC_TO(v, j, materials); GC_TO(v, j, herbs); GC_TO(v, j, seeds);
}
void from_json(const nlohmann::json& j, SecretRealmBackpack& v) {
    GC_FROM(j, v, spiritStones); GC_FROM(j, v, equipment); GC_FROM(j, v, manuals);
    GC_FROM(j, v, pills); GC_FROM(j, v, materials); GC_FROM(j, v, herbs); GC_FROM(j, v, seeds);
}

void to_json(nlohmann::json& j, const SecretRealmExplorationSession& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, secretRealmId); GC_TO(v, j, members); GC_TO(v, j, stamina);
    GC_TO(v, j, backpack); GC_TO_OPT(v, j, currentEvent); GC_TO(v, j, eventHistory);
    GC_TO(v, j, startYear); GC_TO(v, j, startMonth); GC_TO(v, j, resultMessage);
}
void from_json(const nlohmann::json& j, SecretRealmExplorationSession& v) {
    GC_FROM(j, v, secretRealmId); GC_FROM(j, v, members); GC_FROM(j, v, stamina);
    GC_FROM(j, v, backpack); GC_FROM_OPT(j, v, currentEvent); GC_FROM(j, v, eventHistory);
    GC_FROM(j, v, startYear); GC_FROM(j, v, startMonth); GC_FROM(j, v, resultMessage);
}

void to_json(nlohmann::json& j, const SecretRealmAITeam& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, id); GC_TO(v, j, sectId); GC_TO(v, j, sectName);
    GC_TO(v, j, members); GC_TO(v, j, sectLevel);
}
void from_json(const nlohmann::json& j, SecretRealmAITeam& v) {
    GC_FROM(j, v, id); GC_FROM(j, v, sectId); GC_FROM(j, v, sectName);
    GC_FROM(j, v, members); GC_FROM(j, v, sectLevel);
}

// ── T2.1：储物袋条目体系 ──────────────────────────────────────────────

void to_json(nlohmann::json& j, const EquipmentNurtureData& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, equipmentId); GC_TO(v, j, rarity);
    GC_TO(v, j, nurtureLevel); GC_TO(v, j, nurtureProgress);
}
void from_json(const nlohmann::json& j, EquipmentNurtureData& v) {
    GC_FROM(j, v, equipmentId); GC_FROM(j, v, rarity);
    GC_FROM(j, v, nurtureLevel); GC_FROM(j, v, nurtureProgress);
}

void to_json(nlohmann::json& j, const BagStackedData& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, minRealm); GC_TO(v, j, slot); GC_TO(v, j, manualType);
}
void from_json(const nlohmann::json& j, BagStackedData& v) {
    GC_FROM(j, v, minRealm); GC_FROM(j, v, slot); GC_FROM(j, v, manualType);
}

void to_json(nlohmann::json& j, const ItemEffect& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, tier);
    GC_TO(v, j, cultivationSpeedPercent); GC_TO(v, j, skillExpSpeedPercent);
    GC_TO(v, j, nurtureSpeedPercent); GC_TO(v, j, breakthroughChance);
    GC_TO(v, j, targetRealm);
    GC_TO(v, j, cultivationAdd); GC_TO(v, j, skillExpAdd); GC_TO(v, j, nurtureAdd);
    GC_TO(v, j, healMaxHpPercent); GC_TO(v, j, mpRecoverMaxMpPercent);
    GC_TO(v, j, hpAdd); GC_TO(v, j, mpAdd); GC_TO(v, j, extendLife);
    GC_TO(v, j, physicalAttackAdd); GC_TO(v, j, magicAttackAdd);
    GC_TO(v, j, physicalDefenseAdd); GC_TO(v, j, magicDefenseAdd);
    GC_TO(v, j, speedAdd); GC_TO(v, j, critRateAdd); GC_TO(v, j, critEffectAdd);
    GC_TO(v, j, intelligenceAdd); GC_TO(v, j, charmAdd); GC_TO(v, j, loyaltyAdd);
    GC_TO(v, j, comprehensionAdd); GC_TO(v, j, artifactRefiningAdd);
    GC_TO(v, j, pillRefiningAdd); GC_TO(v, j, spiritPlantingAdd);
    GC_TO(v, j, teachingAdd); GC_TO(v, j, moralityAdd); GC_TO(v, j, miningAdd);
    GC_TO(v, j, revive); GC_TO(v, j, clearAll); GC_TO(v, j, isAscension);
    GC_TO(v, j, duration); GC_TO(v, j, cannotStack); GC_TO(v, j, minRealm);
    GC_TO(v, j, pillCategory); GC_TO(v, j, pillType);
}
void from_json(const nlohmann::json& j, ItemEffect& v) {
    GC_FROM(j, v, tier);
    GC_FROM(j, v, cultivationSpeedPercent); GC_FROM(j, v, skillExpSpeedPercent);
    GC_FROM(j, v, nurtureSpeedPercent); GC_FROM(j, v, breakthroughChance);
    GC_FROM(j, v, targetRealm);
    GC_FROM(j, v, cultivationAdd); GC_FROM(j, v, skillExpAdd); GC_FROM(j, v, nurtureAdd);
    GC_FROM(j, v, healMaxHpPercent); GC_FROM(j, v, mpRecoverMaxMpPercent);
    GC_FROM(j, v, hpAdd); GC_FROM(j, v, mpAdd); GC_FROM(j, v, extendLife);
    GC_FROM(j, v, physicalAttackAdd); GC_FROM(j, v, magicAttackAdd);
    GC_FROM(j, v, physicalDefenseAdd); GC_FROM(j, v, magicDefenseAdd);
    GC_FROM(j, v, speedAdd); GC_FROM(j, v, critRateAdd); GC_FROM(j, v, critEffectAdd);
    GC_FROM(j, v, intelligenceAdd); GC_FROM(j, v, charmAdd); GC_FROM(j, v, loyaltyAdd);
    GC_FROM(j, v, comprehensionAdd); GC_FROM(j, v, artifactRefiningAdd);
    GC_FROM(j, v, pillRefiningAdd); GC_FROM(j, v, spiritPlantingAdd);
    GC_FROM(j, v, teachingAdd); GC_FROM(j, v, moralityAdd); GC_FROM(j, v, miningAdd);
    GC_FROM(j, v, revive); GC_FROM(j, v, clearAll); GC_FROM(j, v, isAscension);
    GC_FROM(j, v, duration); GC_FROM(j, v, cannotStack); GC_FROM(j, v, minRealm);
    GC_FROM(j, v, pillCategory); GC_FROM(j, v, pillType);
}

void to_json(nlohmann::json& j, const StorageBagItem& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, itemId); GC_TO(v, j, itemType); GC_TO(v, j, name);
    GC_TO(v, j, rarity); GC_TO(v, j, quantity);
    GC_TO(v, j, obtainedYear); GC_TO(v, j, obtainedMonth);
    GC_TO_OPT(v, j, effect); GC_TO_OPT(v, j, grade);
    GC_TO_OPT(v, j, forgetYear); GC_TO_OPT(v, j, forgetMonth); GC_TO_OPT(v, j, forgetPhase);
    GC_TO_OPT(v, j, equipmentInstance); GC_TO_OPT(v, j, stackedData);
    GC_TO_OPT(v, j, manualInstance);
}
void from_json(const nlohmann::json& j, StorageBagItem& v) {
    GC_FROM(j, v, itemId); GC_FROM(j, v, itemType); GC_FROM(j, v, name);
    GC_FROM(j, v, rarity); GC_FROM(j, v, quantity);
    GC_FROM(j, v, obtainedYear); GC_FROM(j, v, obtainedMonth);
    GC_FROM_OPT(j, v, effect); GC_FROM_OPT(j, v, grade);
    GC_FROM_OPT(j, v, forgetYear); GC_FROM_OPT(j, v, forgetMonth); GC_FROM_OPT(j, v, forgetPhase);
    GC_FROM_OPT(j, v, equipmentInstance); GC_FROM_OPT(j, v, stackedData);
    GC_FROM_OPT(j, v, manualInstance);
}

void to_json(nlohmann::json& j, const PillEffect& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, breakthroughChance); GC_TO(v, j, targetRealm); GC_TO(v, j, isAscension);
    GC_TO(v, j, cultivationSpeedPercent); GC_TO(v, j, skillExpSpeedPercent);
    GC_TO(v, j, nurtureSpeedPercent);
    GC_TO(v, j, cultivationAdd); GC_TO(v, j, skillExpAdd); GC_TO(v, j, nurtureAdd);
    GC_TO(v, j, duration); GC_TO(v, j, cannotStack);
    GC_TO(v, j, physicalAttackAdd); GC_TO(v, j, magicAttackAdd);
    GC_TO(v, j, physicalDefenseAdd); GC_TO(v, j, magicDefenseAdd);
    GC_TO(v, j, hpAdd); GC_TO(v, j, mpAdd); GC_TO(v, j, speedAdd);
    GC_TO(v, j, critRateAdd); GC_TO(v, j, critEffectAdd); GC_TO(v, j, extendLife);
    GC_TO(v, j, intelligenceAdd); GC_TO(v, j, charmAdd); GC_TO(v, j, loyaltyAdd);
    GC_TO(v, j, comprehensionAdd); GC_TO(v, j, artifactRefiningAdd);
    GC_TO(v, j, pillRefiningAdd); GC_TO(v, j, spiritPlantingAdd);
    GC_TO(v, j, teachingAdd); GC_TO(v, j, moralityAdd); GC_TO(v, j, miningAdd);
    GC_TO(v, j, healMaxHpPercent); GC_TO(v, j, mpRecoverMaxMpPercent);
    GC_TO(v, j, revive); GC_TO(v, j, clearAll);
}
void from_json(const nlohmann::json& j, PillEffect& v) {
    GC_FROM(j, v, breakthroughChance); GC_FROM(j, v, targetRealm); GC_FROM(j, v, isAscension);
    GC_FROM(j, v, cultivationSpeedPercent); GC_FROM(j, v, skillExpSpeedPercent);
    GC_FROM(j, v, nurtureSpeedPercent);
    GC_FROM(j, v, cultivationAdd); GC_FROM(j, v, skillExpAdd); GC_FROM(j, v, nurtureAdd);
    GC_FROM(j, v, duration); GC_FROM(j, v, cannotStack);
    GC_FROM(j, v, physicalAttackAdd); GC_FROM(j, v, magicAttackAdd);
    GC_FROM(j, v, physicalDefenseAdd); GC_FROM(j, v, magicDefenseAdd);
    GC_FROM(j, v, hpAdd); GC_FROM(j, v, mpAdd); GC_FROM(j, v, speedAdd);
    GC_FROM(j, v, critRateAdd); GC_FROM(j, v, critEffectAdd); GC_FROM(j, v, extendLife);
    GC_FROM(j, v, intelligenceAdd); GC_FROM(j, v, charmAdd); GC_FROM(j, v, loyaltyAdd);
    GC_FROM(j, v, comprehensionAdd); GC_FROM(j, v, artifactRefiningAdd);
    GC_FROM(j, v, pillRefiningAdd); GC_FROM(j, v, spiritPlantingAdd);
    GC_FROM(j, v, teachingAdd); GC_FROM(j, v, moralityAdd); GC_FROM(j, v, miningAdd);
    GC_FROM(j, v, healMaxHpPercent); GC_FROM(j, v, mpRecoverMaxMpPercent);
    GC_FROM(j, v, revive); GC_FROM(j, v, clearAll);
}

void to_json(nlohmann::json& j, const LibrarySlot& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, index); GC_TO(v, j, buildingInstanceId);
    GC_TO(v, j, discipleId); GC_TO(v, j, discipleName);
}
void from_json(const nlohmann::json& j, LibrarySlot& v) {
    GC_FROM(j, v, index); GC_FROM(j, v, buildingInstanceId);
    GC_FROM(j, v, discipleId); GC_FROM(j, v, discipleName);
}

void to_json(nlohmann::json& j, const GameEventRecord& v) {
    j = nlohmann::json::object();
    GC_TO(v, j, timestamp); GC_TO(v, j, year); GC_TO(v, j, month); GC_TO(v, j, phase);
    GC_TO(v, j, category); GC_TO(v, j, eventType); GC_TO(v, j, summary);
    GC_TO(v, j, relatedEntityId); GC_TO(v, j, relatedEntityName); GC_TO(v, j, sequenceId);
}
void from_json(const nlohmann::json& j, GameEventRecord& v) {
    GC_FROM(j, v, timestamp); GC_FROM(j, v, year); GC_FROM(j, v, month); GC_FROM(j, v, phase);
    GC_FROM(j, v, category); GC_FROM(j, v, eventType); GC_FROM(j, v, summary);
    GC_FROM(j, v, relatedEntityId); GC_FROM(j, v, relatedEntityName); GC_FROM(j, v, sequenceId);
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
    // 批次 1 剩余：远古秘境状态机
    GC_TO(v, j, secretRealmState); GC_TO(v, j, secretRealmSession);
    GC_TO(v, j, secretRealmAITeams);
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
    GC_TO(v, j, merchantAcquisitionItems); GC_TO(v, j, autoBuyList);
    GC_TO(v, j, recruitList); GC_TO(v, j, worldLevels);
    GC_TO(v, j, elderSlots); GC_TO(v, j, productionSlots);
    GC_TO(v, j, placedBuildings); GC_TO(v, j, spiritFieldPlants);
    GC_TO(v, j, residenceSlots); GC_TO(v, j, patrolConfig); GC_TO(v, j, patrolConfigs);
    GC_TO(v, j, alliances); GC_TO(v, j, vassalContracts); GC_TO(v, j, sectRelations);
    // 批 10-4：宗门战报（附庸脱离近 3 年计数消费）
    GC_TO(v, j, sectBattleRecords);
    GC_TO(v, j, sectPolicies);
    // 批 10-1：宗门详情域（S8 侦察过期清理子事件）
    GC_TO(v, j, sectDetails); GC_TO(v, j, scoutInfo);
    GC_TO(v, j, mailRecords); GC_TO(v, j, sectLevelClaimRecords);
    GC_TO(v, j, bloodRefinements);
    GC_TO(v, j, yearlyReports); GC_TO(v, j, pendingTraitAdds);
    // 批次 1 剩余：低频嵌套类型字段
    GC_TO(v, j, manualProficiencies); GC_TO(v, j, spiritMineSlots);
    GC_TO(v, j, bloodRefinementBonusTotals); GC_TO(v, j, bloodRefinementPctTotals);
    GC_TO(v, j, activeBloodRefinements); GC_TO(v, j, patrolSlots);
    // T2.1：每旬结算依赖字段
    GC_TO(v, j, librarySlots); GC_TO(v, j, gameEventRecords);
    // 批 12-2：任务域（S8 子事件 14 任务刷新下沉）
    GC_TO(v, j, availableMissions);
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
    // 批次 1 剩余：远古秘境状态机
    GC_FROM(j, v, secretRealmState); GC_FROM(j, v, secretRealmSession);
    GC_FROM(j, v, secretRealmAITeams);
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
    GC_FROM(j, v, merchantAcquisitionItems); GC_FROM(j, v, autoBuyList);
    GC_FROM(j, v, recruitList); GC_FROM(j, v, worldLevels);
    GC_FROM(j, v, elderSlots); GC_FROM(j, v, productionSlots);
    GC_FROM(j, v, placedBuildings); GC_FROM(j, v, spiritFieldPlants);
    GC_FROM(j, v, residenceSlots); GC_FROM(j, v, patrolConfig); GC_FROM(j, v, patrolConfigs);
    GC_FROM(j, v, alliances); GC_FROM(j, v, vassalContracts); GC_FROM(j, v, sectRelations);
    // 批 10-4：宗门战报（附庸脱离近 3 年计数消费）
    GC_FROM(j, v, sectBattleRecords);
    GC_FROM(j, v, sectPolicies);
    // 批 10-1：宗门详情域（S8 侦察过期清理子事件）
    GC_FROM(j, v, sectDetails); GC_FROM(j, v, scoutInfo);
    GC_FROM(j, v, mailRecords); GC_FROM(j, v, sectLevelClaimRecords);
    GC_FROM(j, v, bloodRefinements);
    GC_FROM(j, v, yearlyReports); GC_FROM(j, v, pendingTraitAdds);
    // 批次 1 剩余：低频嵌套类型字段
    GC_FROM(j, v, manualProficiencies); GC_FROM(j, v, spiritMineSlots);
    GC_FROM(j, v, bloodRefinementBonusTotals); GC_FROM(j, v, bloodRefinementPctTotals);
    GC_FROM(j, v, activeBloodRefinements); GC_FROM(j, v, patrolSlots);
    // T2.1：每旬结算依赖字段
    GC_FROM(j, v, librarySlots); GC_FROM(j, v, gameEventRecords);
    // 批 4-5：槽位清理补充字段
    GC_FROM(j, v, battleTeams); GC_FROM(j, v, warehouseGarrisons);
    GC_FROM(j, v, caveExplorationTeams); GC_FROM(j, v, activeMissions);
    // 批 12-2：任务域（S8 子事件 14 任务刷新下沉）
    GC_FROM(j, v, availableMissions);
}

// ── Full snapshot ────────────────────────────────────────────────────

void to_json(nlohmann::json& j, const GameState& v) {
    j = nlohmann::json::object();
    j["gameData"] = v.gameData;
    // 批 10-4：AI 宗门弟子池（顶层字段——Kotlin GameData.aiSectDisciples
    // @Transient 不入 gameData 序列化，见 models.h GameState 注释）。
    // 空表不导出该键：与 Kotlin NativeGameState.aiSectDisciples 可空语义
    // 对称（null/未携带 ↔ 空表），null 往返保持 null，镜像空表不覆盖
    if (!v.aiSectDisciples.empty()) {
        j["aiSectDisciples"] = v.aiSectDisciples;
    }
    // 批 13-1：AI 宗门妖兽攻击域（Kotlin GameData 同名字段 @Transient 不入
    // gameData 序列化，快照协议顶层承载——空表不导出键，与 Kotlin
    // NativeGameState 可空字段对称，镜像永不主动清空）
    if (!v.aiSectBeastDirectTargets.empty()) {
        j["aiSectBeastDirectTargets"] = v.aiSectBeastDirectTargets;
    }
    if (!v.aiSectBeastSkipCooldowns.empty()) {
        j["aiSectBeastSkipCooldowns"] = v.aiSectBeastSkipCooldowns;
    }
    if (!v.lockedBeastIds.empty()) {
        j["lockedBeastIds"] = v.lockedBeastIds;
    }
    // 弟子：SoA 列存储 → 平铺对象数组（协议零变更；行序 == 数组序）
    nlohmann::json disciplesArr = nlohmann::json::array();
    for (std::size_t i = 0; i < v.disciples.size(); ++i) {
        disciplesArr.push_back(v.disciples.materialize(i));
    }
    j["disciples"] = std::move(disciplesArr);
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
    // 批 10-4：顶层可空字段——Kotlin encodeDefaults=true 下 null 会显式编码，
    // null/缺失一律宽松跳过（保持默认空表；旧 .so 导出/旧快照兼容）
    if (j.contains("aiSectDisciples") && !j.at("aiSectDisciples").is_null()) {
        j.at("aiSectDisciples").get_to(v.aiSectDisciples);
    }
    // 批 13-1：AI 宗门妖兽攻击域顶层字段——缺失/null 一律宽松跳过（保持默认空，
    // 旧 .so 导出/旧快照兼容；Kotlin 侧可空语义对称）
    if (j.contains("aiSectBeastDirectTargets") && !j.at("aiSectBeastDirectTargets").is_null()) {
        j.at("aiSectBeastDirectTargets").get_to(v.aiSectBeastDirectTargets);
    }
    if (j.contains("aiSectBeastSkipCooldowns") && !j.at("aiSectBeastSkipCooldowns").is_null()) {
        j.at("aiSectBeastSkipCooldowns").get_to(v.aiSectBeastSkipCooldowns);
    }
    if (j.contains("lockedBeastIds") && !j.at("lockedBeastIds").is_null()) {
        j.at("lockedBeastIds").get_to(v.lockedBeastIds);
    }
    if (j.contains("disciples") && j.at("disciples").is_array()) {
        std::vector<Disciple> tmp;
        j.at("disciples").get_to(tmp);
        v.disciples.loadFromVector(tmp);
    }
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

void normalizeIntegralFloats(nlohmann::json& j) {
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
