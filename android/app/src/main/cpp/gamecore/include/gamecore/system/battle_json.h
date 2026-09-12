// ============================================================
// battle_json.h — 战斗 JSON 编解码（桌面对拍桥 + Android 生产桥共享）
//
// Combatant/CombatSkill/CombatBuff 的 JSON 双向编解码（对拍协议 + 生产
// 战斗执行通道共用）。对拍桥与生产桥执行通道复用同一实现，防双份
// 编解码漂移。
//
// 字段键与 Kotlin 侧 Diff 测试序列化（combatantJson/skillJson/buffJson）
// 逐键对应；解析宽松（默认值兜底），序列化仅输出模型字段。
// ============================================================
#pragma once

#include <nlohmann/json.hpp>

#include "gamecore/system/battle_calculator.h"
#include "gamecore/system/battle_execution.h"

namespace gamecore::battle {

/// CombatBuff → JSON（对拍输出；Kotlin CombatBuff 字段对应）
inline nlohmann::json buffToJson(const CombatBuff& b) {
    return {
        {"type", buffTypeName(b.type)},
        {"value", b.value}, {"remainingDuration", b.remainingDuration},
        {"sourceRealm", b.sourceRealm}, {"sourceRealmLayer", b.sourceRealmLayer},
    };
}

/// CombatSkill → JSON（对拍输出；C++ 模型无 skillDescription/manualName 展示字段）
inline nlohmann::json skillToJson(const CombatSkill& s) {
    nlohmann::json j = {
        {"name", s.name}, {"skillType", s.skillType == SkillType::kAttack ? "ATTACK"
                                                                          : "SUPPORT"},
        {"damageType", s.damageType == DamageType::kPhysical ? "PHYSICAL" : "MAGIC"},
        {"damageMultiplier", s.damageMultiplier}, {"mpCost", s.mpCost},
        {"cooldown", s.cooldown}, {"hits", s.hits}, {"healPercent", s.healPercent},
        {"healFixed", s.healFixed}, {"healType", s.healType == HealType::kHp ? "HP" : "MP"},
        {"buffValue", s.buffValue}, {"buffDuration", s.buffDuration},
        {"currentCooldown", s.currentCooldown}, {"isAoe", s.isAoe},
        {"targetScope", s.targetScope}, {"shieldPercent", s.shieldPercent},
        {"turnAdvancePercent", s.turnAdvancePercent},
        {"damageSharePercent", s.damageSharePercent},
        {"damageLinkPercent", s.damageLinkPercent},
    };
    if (s.buffType.has_value()) j["buffType"] = buffTypeName(*s.buffType);
    nlohmann::json buffs = nlohmann::json::array();
    for (const auto& [t, v, d] : s.buffs) {
        buffs.push_back({{"type", buffTypeName(t)}, {"value", v}, {"duration", d}});
    }
    j["buffs"] = buffs;
    return j;
}

/// Combatant → JSON（对拍输出；C++ 模型无 weaponName/armorName 等展示字段）
inline nlohmann::json combatantToJson(const Combatant& c) {
    nlohmann::json j = {
        {"id", c.id}, {"name", c.name},
        {"side", c.side == CombatantSide::kAttacker ? "ATTACKER" : "DEFENDER"},
        {"hp", c.hp}, {"maxHp", c.maxHp}, {"mp", c.mp}, {"maxMp", c.maxMp},
        {"physicalAttack", c.physicalAttack}, {"magicAttack", c.magicAttack},
        {"physicalDefense", c.physicalDefense}, {"magicDefense", c.magicDefense},
        {"speed", c.speed}, {"critRate", c.critRate},
        {"realm", c.realm}, {"realmLayer", c.realmLayer}, {"element", c.element},
        {"isBeast", c.isBeast},
    };
    nlohmann::json skills = nlohmann::json::array();
    for (const auto& s : c.skills) skills.push_back(skillToJson(s));
    j["skills"] = skills;
    nlohmann::json buffs = nlohmann::json::array();
    for (const auto& b : c.buffs) buffs.push_back(buffToJson(b));
    j["buffs"] = buffs;
    j["physique"] = {
        {"damageAmplification", c.physique.damageAmplification},
        {"critDamageBonus", c.physique.critDamageBonus},
        {"damageReduction", c.physique.damageReduction},
        {"defenseBonus", c.physique.defenseBonus},
    };
    j["affix"] = {
        {"damageAmplification", c.affix.damageAmplification},
        {"critDamageBonus", c.affix.critDamageBonus},
        {"damageReduction", c.affix.damageReduction},
        {"defenseBonus", c.affix.defenseBonus},
    };
    return j;
}

/// CombatBuff ← JSON（解析；未知 BuffType 回退 kHpBoost）
inline CombatBuff buffFromJson(const nlohmann::json& j) {
    CombatBuff b;
    b.type = buffTypeFromName(j.value("type", "HP_BOOST"));
    b.value = j.value("value", 0.0);
    b.remainingDuration = j.value("remainingDuration", 0);
    b.sourceRealm = j.value("sourceRealm", 9);
    b.sourceRealmLayer = j.value("sourceRealmLayer", 0);
    return b;
}

/// CombatSkill ← JSON（解析；宽松默认值兜底）
inline CombatSkill skillFromJson(const nlohmann::json& j) {
    CombatSkill s;
    s.name = j.value("name", "");
    s.skillType = j.value("skillType", "ATTACK") == "SUPPORT" ? SkillType::kSupport
                                                              : SkillType::kAttack;
    s.damageType = j.value("damageType", "PHYSICAL") == "MAGIC" ? DamageType::kMagic
                                                                : DamageType::kPhysical;
    s.damageMultiplier = j.value("damageMultiplier", 1.0);
    s.mpCost = j.value("mpCost", 0);
    s.cooldown = j.value("cooldown", 0);
    s.hits = j.value("hits", 1);
    s.healPercent = j.value("healPercent", 0.0);
    s.healFixed = j.value("healFixed", 0);
    s.healType = j.value("healType", "HP") == "MP" ? HealType::kMp : HealType::kHp;
    if (j.contains("buffType") && !j["buffType"].is_null()) {
        s.buffType = buffTypeFromName(j.at("buffType").get<std::string>());
    }
    s.buffValue = j.value("buffValue", 0.0);
    s.buffDuration = j.value("buffDuration", 0);
    if (j.contains("buffs") && j["buffs"].is_array()) {
        for (const auto& e : j.at("buffs")) {
            s.buffs.emplace_back(buffTypeFromName(e.value("type", "HP_BOOST")),
                                 e.value("value", 0.0), e.value("duration", 0));
        }
    }
    s.currentCooldown = j.value("currentCooldown", 0);
    s.isAoe = j.value("isAoe", false);
    s.targetScope = j.value("targetScope", "self");
    s.shieldPercent = j.value("shieldPercent", 0.0);
    s.turnAdvancePercent = j.value("turnAdvancePercent", 0.0);
    s.damageSharePercent = j.value("damageSharePercent", 0.0);
    s.damageLinkPercent = j.value("damageLinkPercent", 0.0);
    return s;
}

/// Combatant ← JSON（解析；宽松默认值兜底）
inline Combatant combatantFromJson(const nlohmann::json& j) {    Combatant c;
    c.id = j.value("id", "");
    c.name = j.value("name", "");
    c.side = j.value("side", "DEFENDER") == "ATTACKER" ? CombatantSide::kAttacker
                                                       : CombatantSide::kDefender;
    c.hp = j.value("hp", 0);
    c.maxHp = j.value("maxHp", 0);
    c.mp = j.value("mp", 0);
    c.maxMp = j.value("maxMp", 0);
    c.physicalAttack = j.value("physicalAttack", 0);
    c.magicAttack = j.value("magicAttack", 0);
    c.physicalDefense = j.value("physicalDefense", 0);
    c.magicDefense = j.value("magicDefense", 0);
    c.speed = j.value("speed", 0);
    c.critRate = j.value("critRate", 0.05);
    if (j.contains("skills") && j["skills"].is_array()) {
        for (const auto& s : j.at("skills")) c.skills.push_back(skillFromJson(s));
    }
    if (j.contains("buffs") && j["buffs"].is_array()) {
        for (const auto& b : j.at("buffs")) c.buffs.push_back(buffFromJson(b));
    }
    c.realm = j.value("realm", 9);
    c.realmLayer = j.value("realmLayer", 0);
    c.element = j.value("element", "");
    c.isBeast = j.value("isBeast", false);
    if (j.contains("physique")) {
        const auto& p = j.at("physique");
        c.physique.damageAmplification = p.value("damageAmplification", 0.0);
        c.physique.critDamageBonus = p.value("critDamageBonus", 0.0);
        c.physique.damageReduction = p.value("damageReduction", 0.0);
        c.physique.defenseBonus = p.value("defenseBonus", 0.0);
    }
    if (j.contains("affix")) {
        const auto& a = j.at("affix");
        c.affix.damageAmplification = a.value("damageAmplification", 0.0);
        c.affix.critDamageBonus = a.value("critDamageBonus", 0.0);
        c.affix.damageReduction = a.value("damageReduction", 0.0);
        c.affix.defenseBonus = a.value("defenseBonus", 0.0);
    }
    return c;
}

/// 单条动作记录 → JSON（对拍/生产输出；Kotlin BattleActionData 字段对应）
inline nlohmann::json actionRecordToJson(const BattleActionRecord& a) {
    nlohmann::json j = {
        {"type", a.type}, {"attacker", a.attacker}, {"attackerType", a.attackerType},
        {"target", a.target}, {"damage", a.damage}, {"damageType", a.damageType},
        {"isCrit", a.isCrit}, {"isKill", a.isKill}, {"isInstantKill", a.isInstantKill},
    };
    if (!a.skillName.empty()) j["skillName"] = a.skillName;
    return j;
}

/// 回合序列 → JSON（对拍/生产输出；Kotlin BattleRoundData 字段对应，message 排除）
inline nlohmann::json roundsToJson(const std::vector<BattleRound>& rounds) {
    nlohmann::json out = nlohmann::json::array();
    for (const auto& r : rounds) {
        nlohmann::json actions = nlohmann::json::array();
        for (const auto& a : r.actions) actions.push_back(actionRecordToJson(a));
        out.push_back({{"roundNumber", r.roundNumber}, {"actions", actions}});
    }
    return out;
}

}  // namespace gamecore::battle
