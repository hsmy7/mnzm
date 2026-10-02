#pragma once

// ============================================================
// mission_completion.h — 任务完成结算（对应 Kotlin 子事件 5）
//
// Kotlin 权威面（逐位对齐目标）：
//   - CultivationEventMissionOps.processCompletedMissionsLazy
//     （Phase 1 收集 + Phase 2 单事务写入——C++ 侧同状态段顺序）
//   - MissionSystem.processMissionCompletion（NO_COMBAT/COMBAT_REQUIRED/
//     COMBAT_RANDOM 三分支 + 奖励生成）
//   - MissionSystem.generateMaterialBatch / generatePills / generateEquipment /
//     generateManuals / rollSpiritStones
//   - BattleSystem.createBattle / convertDiscipleToCombatant / createBeast
//     （战斗组装首次入 C++——执行引擎 battle::executeBattle 已对拍）
//   - EnemyGenerator.generateHumanEnemies（HUMAN 敌人生成，ENEMY_GEN 分区）
//   - CultivationEventProcessor.applyMissionRewards（库存/灵石/状态/魂力）
//
// RNG 契约（逐条登记）：
//   - MISSION 分区：rollSpiritStones（max>0 才抽）/材料 count+逐条模板/
//     丹药 count+逐条模板/装备 chance gate + 品阶带 + 模板/功法 chance gate +
//     品阶带 + 模板/COMBAT_RANDOM 触发判定（先于战斗与奖励）
//   - BATTLE 分区：battle::executeBattle 内部消耗（与 Kotlin
//     BattleExecutionRouter→nativeBattleExecute 同分区同序）
//   - ENEMY_GEN 分区：HUMAN 敌人生成（Kotlin EnemyGenerator.enemyRng）
//
// RNG 口径说明：
//   - MissionSystem 丹药/装备/功法模板与 EnemyGenerator 装备/功法模板
//     经 MISSION/ENEMY_GEN 分区适配器选取（C++ 按 MISSION 分区口径实现；
//     AUTHORITATIVE 生产路径不执行 Kotlin 面板）
//   - 任务完成位于 C++ 子事件 5 位（与 Kotlin 月变编排位一致），MISSION
//     抽取序与该编排一致
//
// 边界（登记）：
//   - syncAllDiscipleStatuses（Phase 2 后的全量状态重推导）不下沉：任务成员
//     不变式（派遣要求 IDLE、任务期间不可再分配其他槽位）下，成员重推导为
//     幂等操作；C++ 写 IDLE 即全量语义（对拍 harness 侧该调用为 mock no-op）
//   - 战斗回放 log 不落状态（Kotlin 侧 log 仅 UI 消费）——幸存者集合经
//     battleResult 终态 isDead 重建，与 Kotlin router 重建口径一致
//   - MissionReward 里 spiritStones=0 的失败臂不触发钱包 add（amount<=0
//     直接返回语义，双端一致）
// ============================================================

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <optional>
#include <stdexcept>
#include <string>
#include <tuple>
#include <vector>

#include "gamecore/data/beast_config.h"
#include "gamecore/data/beast_material_db.h"
#include "gamecore/data/equipment_db.h"
#include "gamecore/data/manual_db.h"
#include "gamecore/system/equipment_factory.h"  // B3 装备唯一产出入口
#include "gamecore/data/recipe_db.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/auto_gear.h"            // buffsJsonOf（模板 skillBuffsJson）
#include "gamecore/system/battle_execution.h"
#include "gamecore/system/disciple_stats.h"
#include "gamecore/system/economy.h"
#include "gamecore/system/inventory.h"
#include "gamecore/system/mission_settlement.h"   // 难度/模板属性 + createRewardConfig

namespace gamecore::system::mission_settle {

using gamecore::state::ActiveMission;
using gamecore::state::Disciple;
using gamecore::state::EquipmentInstance;
using gamecore::state::EquipmentInstance;
using gamecore::state::GameState;
using gamecore::state::ManualInstance;
using gamecore::state::ManualProficiencyData;
using gamecore::state::ManualStack;
using gamecore::state::Material;
using gamecore::state::MissionRewardConfig;
using gamecore::state::Pill;

namespace detail {

using gamecore::battle::CombatSkill;
using gamecore::battle::BuffType;
using gamecore::battle::DamageType;
using gamecore::battle::HealType;
using gamecore::battle::SkillType;

// ── buff 名解析（Kotlin parseBuffType——snake_case 键，ManualInstance
//    skillBuffType/skillBuffsJson 口径；未知回退 nullopt/null） ──────
inline std::optional<BuffType> buffTypeFromSnake(const std::string& bt) {
    static const std::map<std::string, BuffType> kMap = {
        {"physical_attack", BuffType::kPhysicalAttackBoost},
        {"magic_attack", BuffType::kMagicAttackBoost},
        {"physical_defense", BuffType::kPhysicalDefenseBoost},
        {"magic_defense", BuffType::kMagicDefenseBoost},
        {"hp", BuffType::kHpBoost},
        {"mp", BuffType::kMpBoost},
        {"speed", BuffType::kSpeedBoost},
        {"crit_rate", BuffType::kCritRateBoost},
        {"physical_attack_reduce", BuffType::kPhysicalAttackReduce},
        {"magic_attack_reduce", BuffType::kMagicAttackReduce},
        {"physical_defense_reduce", BuffType::kPhysicalDefenseReduce},
        {"magic_defense_reduce", BuffType::kMagicDefenseReduce},
        {"speed_reduce", BuffType::kSpeedReduce},
        {"crit_rate_reduce", BuffType::kCritRateReduce},
        {"poison", BuffType::kPoison},
        {"burn", BuffType::kBurn},
        {"stun", BuffType::kStun},
        {"freeze", BuffType::kFreeze},
        {"silence", BuffType::kSilence},
        {"taunt", BuffType::kTaunt},
        {"damage_boost", BuffType::kDamageBoost},
        {"damage_reduction", BuffType::kDamageReduction},
        {"shield", BuffType::kShield},
        {"damage_share", BuffType::kDamageShare},
        {"damage_link", BuffType::kDamageLink},
        {"turn_advance", BuffType::kTurnAdvance},
    };
    const auto it = kMap.find(bt);
    return it == kMap.end() ? std::nullopt : std::optional<BuffType>(it->second);
}

/// skillBuffsJson 解析（Kotlin parseBuffsJson——"type,value,duration" 以
/// "|" 分隔；段数 != 3 或任一字段解析失败即丢弃该段）
inline std::vector<std::tuple<BuffType, double, int32_t>> parseBuffsJson(
        const std::string& json) {
    std::vector<std::tuple<BuffType, double, int32_t>> out;
    if (json.empty() ||
        json.find_first_not_of(" \t\r\n") == std::string::npos) {
        return out;
    }
    std::size_t start = 0;
    while (true) {
        const std::size_t pipe = json.find('|', start);
        const std::string seg = json.substr(
            start, pipe == std::string::npos ? std::string::npos : pipe - start);
        // 段内按 ',' 切三段（Kotlin split(",") 恰 3 段才有效）
        {
            const std::size_t c1 = seg.find(',');
            const std::size_t c2 = c1 == std::string::npos
                                       ? std::string::npos : seg.find(',', c1 + 1);
            const std::size_t c3 = c2 == std::string::npos
                                       ? std::string::npos : seg.find(',', c2 + 1);
            if (c1 != std::string::npos && c2 != std::string::npos &&
                c3 == std::string::npos) {
                const auto type = buffTypeFromSnake(seg.substr(0, c1));
                try {
                    const double value = std::stod(seg.substr(c1 + 1, c2 - c1 - 1));
                    const int32_t duration = std::stoi(seg.substr(c2 + 1));
                    if (type.has_value()) {
                        out.emplace_back(*type, value, duration);
                    }
                } catch (const std::exception&) {
                    // toDoubleOrNull/toIntOrNull 失败 → 丢弃该段
                }
            }
        }
        if (pipe == std::string::npos) break;
        start = pipe + 1;
    }
    return out;
}

// ── 功法 → 战斗技能（Kotlin ManualInstance.skill + toCombatSkill 合流） ──

inline CombatSkill manualCombatSkill(const gamecore::state::ManualBase& manual) {
    CombatSkill s;
    s.name = manual.skillName.value_or("");
    s.skillType = manual.skillType == "support" ? SkillType::kSupport : SkillType::kAttack;
    // 功法自带元素（五行属性伤害系统 P3，静态表值域小写）；未知与退役段（"magic"）兜底物理
    s.damageType = manual.skillDamageType == "metal" ? DamageType::kMetal
        : manual.skillDamageType == "wood" ? DamageType::kWood
        : manual.skillDamageType == "water" ? DamageType::kWater
        : manual.skillDamageType == "fire" ? DamageType::kFire
        : manual.skillDamageType == "earth" ? DamageType::kEarth
        : DamageType::kPhysical;
    s.damageMultiplier = manual.skillDamageMultiplier;
    s.mpCost = manual.skillMpCost;
    s.cooldown = manual.skillCooldown;
    s.hits = manual.skillHits;
    s.healPercent = manual.skillHealPercent;
    s.healFixed = manual.skillHealFixed;
    s.healType = manual.skillHealType == "mp" ? HealType::kMp : HealType::kHp;
    if (manual.skillBuffType.has_value()) {
        s.buffType = buffTypeFromSnake(*manual.skillBuffType);
    }
    s.buffValue = manual.skillBuffValue;
    s.buffDuration = manual.skillBuffDuration;
    s.buffs = parseBuffsJson(manual.skillBuffsJson);
    s.isAoe = manual.skillIsAoe;
    s.targetScope = manual.skillTargetScope;
    s.shieldPercent = manual.skillShieldPercent;
    s.turnAdvancePercent = manual.skillTurnAdvancePercent;
    s.damageSharePercent = manual.skillDamageSharePercent;
    s.damageLinkPercent = manual.skillDamageLinkPercent;
    return s;
}

/// 熟练度伤害倍率（Kotlin calculateSkillDamageMultiplier）
inline double skillDamageMultiplier(double baseMultiplier, int32_t masteryLevel) {
    return baseMultiplier * gamecore::stats::masteryLevelBonus(masteryLevel);
}

// ── java.util.Random（EnemyGenerator 装备槽洗牌——Kotlin
//    list.shuffled(java.util.Random(seed)) 的 LCG 语义复刻） ────────

class JavaRandom {
public:
    explicit JavaRandom(int64_t seed) { setSeed(seed); }

    void setSeed(int64_t seed) {
        seed_ = static_cast<uint64_t>(seed ^ 0x5DEECE66DLL) & kMask;
    }

    int32_t next(int32_t bits) {
        seed_ = (seed_ * kMultiplier + kAddend) & kMask;
        return static_cast<int32_t>(seed_ >> (48 - bits));
    }

    /// java.util.Random.nextInt(bound)（2 的幂特判 + 有符号回绝循环）
    int32_t nextInt(int32_t bound) {
        if (bound <= 0) throw std::invalid_argument("bound must be positive");
        if ((bound & -bound) == bound) {
            return static_cast<int32_t>(
                (static_cast<int64_t>(bound) * static_cast<int64_t>(next(31))) >> 31);
        }
        int32_t bits = 0;
        int32_t val = 0;
        do {
            bits = next(31);
            val = bits % bound;
        } while (static_cast<int32_t>(bits - val + (bound - 1)) < 0);
        return val;
    }

private:
    static constexpr uint64_t kMultiplier = 0x5DEECE66DULL;
    static constexpr uint64_t kAddend = 0xBULL;
    static constexpr uint64_t kMask = (1ULL << 48) - 1;
    uint64_t seed_ = 0;
};

/// Kotlin Iterable.shuffled(java.util.Random)（JVM 桥 = Collections.shuffle
/// 语义：Fisher-Yates 降序，j = nextInt(i+1)）
template <typename T>
inline std::vector<T> javaShuffled(std::vector<T> list, JavaRandom& rnd) {
    for (std::size_t i = list.size(); i-- > 1;) {
        const std::size_t j = static_cast<std::size_t>(rnd.nextInt(static_cast<int32_t>(i) + 1));
        std::swap(list[i], list[j]);
    }
    return list;
}

// ── 装备/功法模板随机生成（B3：装备产出收敛 EquipmentFactory 唯一入口，
//    旧 equipmentTemplates 过滤辅助段删除；功法模板面不变） ──

inline std::vector<const gamecore::data::ManualTemplate*> manualByRarity(
        int32_t rarity) {
    std::vector<const gamecore::data::ManualTemplate*> out;
    for (const auto& t : gamecore::data::manualTemplates()) {
        if (t.rarity == rarity) out.push_back(&t);
    }
    return out;
}

inline std::vector<const gamecore::data::ManualTemplate*> manualByTypeRarity(
        const std::string& type, int32_t rarity) {
    std::vector<const gamecore::data::ManualTemplate*> out;
    for (const auto& t : gamecore::data::manualTemplates()) {
        if (t.type == type && t.rarity == rarity) out.push_back(&t);
    }
    return out;
}

inline int32_t realmMinForRarity(int32_t rarity) {
    switch (rarity) {
        case 1: return 9;
        case 2: return 7;
        case 3: return 6;
        case 4: return 5;
        case 5: return 4;
        case 6: return 2;
        default: return 9;
    }
}

inline int32_t realmMaxRarity(int32_t realm) {
    switch (realm) {
        case 9:
        case 8: return 1;
        case 7: return 2;
        case 6: return 3;
        case 5: return 4;
        case 4:
        case 3: return 5;
        case 2:
        case 1:
        case 0: return 6;
        default: return 1;
    }
}

/// 难度敌人境界界（Kotlin MissionDifficulty.enemyRealmMin/Max，战斗组装消费）
inline int32_t difficultyEnemyRealmMin(const std::string& difficulty) {
    if (difficulty == "SIMPLE") return 8;
    if (difficulty == "NORMAL") return 6;
    if (difficulty == "HARD") return 4;
    if (difficulty == "FORBIDDEN") return 2;
    return 8;
}

inline int32_t difficultyEnemyRealmMax(const std::string& difficulty) {
    if (difficulty == "SIMPLE") return 9;
    if (difficulty == "NORMAL") return 7;
    if (difficulty == "HARD") return 5;
    if (difficulty == "FORBIDDEN") return 3;
    return 9;
}

/// 材料类目名映射（Kotlin BeastMaterial.materialCategory——category 键 →
/// MaterialCategory.name；未知回退 BEAST_HIDE）
inline const char* materialCategoryName(const std::string& category) {
    if (category == "hide") return "BEAST_HIDE";
    if (category == "bone") return "BEAST_BONE";
    if (category == "tooth") return "BEAST_TOOTH";
    if (category == "core") return "BEAST_CORE";
    if (category == "claw") return "BEAST_CLAW";
    if (category == "feather") return "BEAST_FEATHER";
    if (category == "tail") return "BEAST_TAIL";
    if (category == "scale") return "BEAST_SCALE";
    if (category == "horn") return "BEAST_HORN";
    if (category == "shell") return "BEAST_SHELL";
    if (category == "blood") return "BEAST_BLOOD";
    return "BEAST_HIDE";
}

/// ManualTemplate → ManualStack（Kotlin createFromTemplate 全字段 +
/// skillBuffsJson joinToString；id 由调用方注入）
inline ManualStack manualStackFromTemplate(
        const gamecore::data::ManualTemplate& tpl) {
    ManualStack m;
    m.name = tpl.name;
    m.type = tpl.type;
    m.rarity = tpl.rarity;
    m.description = tpl.description;
    m.stats = tpl.stats;
    m.skillName = tpl.skillName;
    m.skillDescription = tpl.skillDescription;
    m.skillType = tpl.skillType;
    m.skillDamageType = tpl.skillDamageType;
    m.skillHits = tpl.skillHits;
    m.skillDamageMultiplier = tpl.skillDamageMultiplier;
    m.skillCooldown = tpl.skillCooldown;
    m.skillMpCost = tpl.skillMpCost;
    m.skillHealPercent = tpl.skillHealPercent;
    m.skillHealFixed = tpl.skillHealFixed;
    m.skillHealType = tpl.skillHealType;
    m.skillBuffType = tpl.skillBuffType.empty()
                          ? std::nullopt
                          : std::optional<std::string>(tpl.skillBuffType);
    m.skillBuffValue = tpl.skillBuffValue;
    m.skillBuffDuration = tpl.skillBuffDuration;
    m.skillIsAoe = tpl.skillIsAoe;
    m.skillTargetScope = tpl.skillTargetScope;
    m.skillShieldPercent = tpl.skillShieldPercent;
    m.skillTurnAdvancePercent = tpl.skillTurnAdvancePercent;
    m.skillDamageSharePercent = tpl.skillDamageSharePercent;
    m.skillDamageLinkPercent = tpl.skillDamageLinkPercent;
    m.skillBuffsJson = gamecore::system::detail::buffsJsonOf(tpl);
    m.minRealm = realmMinForRarity(tpl.rarity);
    m.quantity = 1;
    return m;
}

/// 装备品阶带（Kotlin EquipmentDatabase.generateRarity——min==max 不抽）
inline int32_t equipmentRarityRoll(int32_t min, int32_t max,
                                   rng::DeterministicRng& r) {
    if (min == max) return min;
    const double roll = r.nextDouble();
    auto coerce = [&](int32_t v) { return std::min(std::max(v, min), max); };
    if (roll < 0.5) return std::max(min, 1);
    if (roll < 0.75) return coerce(min + 1);
    if (roll < 0.9) return coerce(min + 2);
    if (roll < 0.97) return coerce(min + 3);
    if (roll < 0.99) return coerce(min + 4);
    return max;
}

/// 功法品阶带（Kotlin ManualDatabase.generateRarity——恒抽一注）
inline int32_t manualRarityRoll(int32_t min, int32_t max,
                                rng::DeterministicRng& r) {
    const double rand = r.nextDouble();
    auto coerceMax = [&](int32_t v) { return std::min(v, max); };
    if (rand < 0.5) return coerceMax(min);
    if (rand < 0.75) return coerceMax(min + 1);
    if (rand < 0.9) return coerceMax(min + 2);
    if (rand < 0.97) return coerceMax(min + 3);
    return max;
}

// ── 战斗组装（Kotlin BattleSystem.createBattle / createBeast /
//    convertDiscipleToCombatant / EnemyGenerator） ────────────────────

/// 妖兽战斗属性（Kotlin resolveBeastStats 向后兼容分支——任务战斗无
/// preGenStats，恒走基础值；rl=5 → layerMult=1.4）
inline gamecore::battle::Combatant createBeast(int32_t beastRealm, int32_t index,
                                               int32_t typeIndex) {
    const auto& types = gamecore::data::beastTypes();
    const auto& type = types[static_cast<std::size_t>(
        typeIndex < 0 || typeIndex >= static_cast<int32_t>(types.size()) ? 0 : typeIndex)];
    const int32_t realmIndex = std::min(std::max(beastRealm, 0), 9);
    const double layerMult = 1.0 + (5 - 1) * 0.1;
    const auto& rs = gamecore::data::detail::beastRealmStats(realmIndex);
    auto scaled = [&](int32_t base, double mod) {
        return static_cast<int32_t>(static_cast<double>(base) * layerMult * mod);
    };

    gamecore::battle::Combatant beast;
    beast.id = "beast_" + std::to_string(index);
    beast.name = std::string(type.prefix) + type.name;
    beast.side = gamecore::battle::CombatantSide::kAttacker;
    beast.hp = scaled(rs.hp, type.hpMod);
    beast.maxHp = beast.hp;
    beast.mp = scaled(rs.mp, type.hpMod);
    beast.maxMp = beast.mp;
    // 单列口径（B1）：物=法同源两半相加
    beast.attack = scaled(rs.attack, type.atkMod) * 2;
    beast.defense = scaled(rs.defense, type.defMod) * 2;
    beast.speed = scaled(rs.speed, type.speedMod);
    beast.critRate = 0.0;  // 敌人不暴击（暴击系统口径）
    // 普攻类型按 innateDamageType 配置（默认物理；当前设定全物理，可按兽种配置）
    beast.realm = realmIndex;
    beast.realmLayer = 5;
    beast.element = type.element;
    beast.isBeast = true;

    // 妖兽技能（Kotlin buildBeastSkills——skillType/damageType 均为枚举直映射）
    for (const auto& sc : *type.skills) {
        gamecore::battle::CombatSkill s;
        s.name = sc.name;
        s.skillType = sc.skillType;
        s.damageType = sc.damageType;
        s.damageMultiplier = sc.damageMultiplier;
        s.mpCost = sc.mpCost;
        s.cooldown = sc.cooldown;
        s.hits = sc.hits;
        s.healPercent = sc.healPercent;
        s.healFixed = 0;
        s.healType = HealType::kHp;
        s.buffType = sc.buffType;
        s.buffValue = sc.buffValue;
        s.buffDuration = sc.buffDuration;
        s.buffs = sc.buffs;
        s.isAoe = sc.isAoe;
        s.targetScope = sc.targetScope;
        s.shieldPercent = sc.shieldPercent;
        s.turnAdvancePercent = sc.turnAdvancePercent;
        beast.skills.push_back(s);
    }
    return beast;
}

/// 弟子 → 战斗体（Kotlin convertDiscipleToCombatant；display 域
/// realmName/weaponName/portraitRes 不入 C++ 战斗状态）
inline gamecore::battle::Combatant discipleToCombatant(
        const Disciple& d,
        const std::map<std::string, EquipmentInstance>& equipmentMap,
        const std::map<std::string, ManualInstance>& manualMap,
        const std::map<std::string, std::map<std::string, ManualProficiencyData>>& proficiencies) {
    // Kotlin：manualProficiencies[disciple.id] ?: emptyMap()（嵌套 map 按弟子 id 索引）
    static const std::map<std::string, ManualProficiencyData> kEmpty;
    const auto profIt = proficiencies.find(d.id);
    const std::map<std::string, ManualProficiencyData>& discipleProficiencies =
        profIt == proficiencies.end() ? kEmpty : profIt->second;

    // 装备/套装加成旁路回传（暴击伤害加成供 Combatant 装配消费）
    gamecore::stats::EquipBonus equipBonus;
    const auto stats = gamecore::stats::finalStats(
        d, equipmentMap, manualMap, discipleProficiencies, &equipBonus);

    // 技能：manualIds.mapNotNull { manual → manual.skill + 熟练度倍率 }
    std::vector<gamecore::battle::CombatSkill> skills;
    for (const std::string& manualId : d.manualIds) {
        const auto it = manualMap.find(manualId);
        if (it == manualMap.end()) continue;
        const ManualInstance& manual = it->second;
        if (!manual.skillName.has_value()) continue;
        int32_t masteryLevel = 0;
        const auto prof = discipleProficiencies.find(manualId);
        if (prof != discipleProficiencies.end()) {
            masteryLevel = prof->second.masteryLevel;
        }
        gamecore::battle::CombatSkill skill =
            manualCombatSkill(manual);
        skill.damageMultiplier = skillDamageMultiplier(skill.damageMultiplier, masteryLevel);
        skills.push_back(std::move(skill));
    }

    const int32_t effectiveHp = d.currentHp < 0
                                    ? stats.maxHp
                                    : std::min(d.currentHp, stats.maxHp);
    const int32_t effectiveMp = d.currentMp < 0
                                    ? stats.maxMp
                                    : std::min(d.currentMp, stats.maxMp);

    // element：灵根首类型（spiritRootType 逗号连接串首段；空串回退 metal）
    std::string element = d.spiritRootType.substr(0, d.spiritRootType.find(','));
    const std::size_t notSpace = element.find_first_not_of(" \t\r\n");
    if (notSpace == std::string::npos) {
        element = "metal";
    }

    gamecore::battle::Combatant c;
    c.id = d.id;
    c.name = d.name;
    c.side = gamecore::battle::CombatantSide::kDefender;
    c.hp = effectiveHp;
    c.maxHp = stats.maxHp;
    c.mp = effectiveMp;
    c.maxMp = stats.maxMp;
    c.attack = stats.attack;
    c.defense = stats.defense;
    c.speed = stats.speed;
    c.critRate = stats.critRate;
    // 暴击伤害加成 = 装备（含套装）+ 丹药暴击效果（加法序与 Kotlin 装配一致：装备先丹药后）
    c.critDamageBonus = equipBonus.critDamage +
        (d.pillEffectDuration > 0 ? d.pillCritEffectBonus : 0.0);
    c.skills = std::move(skills);
    c.realm = d.realm;
    c.realmLayer = d.realmLayer;
    c.element = element;

    return c;
}

/// 妖兽战斗组装（Kotlin createBattle——BEAST 分支；beastLevel 恒在 0..9，
/// calculateBeastRealm 回退分支任务域不可达，登记边界）
inline gamecore::battle::BattleState createBeastBattle(
        const std::vector<Disciple>& disciples,
        const std::map<std::string, EquipmentInstance>& equipmentMap,
        const std::map<std::string, ManualInstance>& manualMap,
        const std::map<std::string, std::map<std::string, ManualProficiencyData>>& proficiencies,
        int32_t beastLevel, int32_t beastCount) {
    gamecore::battle::BattleState state;
    for (const auto& d : disciples) {
        state.team.push_back(discipleToCombatant(
            d, equipmentMap, manualMap, proficiencies));
    }
    const int32_t beastRealm = std::min(std::max(beastLevel, 0), 9);
    const int32_t actualCount = std::max(beastCount, 1);
    // typeIndex=0（虎妖）——Kotlin createBattle beastType=null → getType(0)
    for (int32_t i = 1; i <= actualCount; ++i) {
        state.beasts.push_back(createBeast(beastRealm, i, 0));
    }
    state.maxTurns = gamecore::battle::kMaxTurns;
    return state;
}

/// HUMAN 敌人生成（Kotlin EnemyGenerator.generateHumanEnemies 全链——
/// ENEMY_GEN 分区；装备槽洗牌 java.util.Random LCG 语义）
inline std::vector<gamecore::battle::Combatant> generateHumanEnemies(
        int32_t realmMin, int32_t realmMax, int32_t count,
        rng::DeterministicRng& enemyRng) {
    std::vector<gamecore::battle::Combatant> enemies;
    for (int32_t index = 1; index <= count; ++index) {
        // T-C3：realmMin > realmMax 时退化为 realmMin（coerceAtLeast(1)）
        const int32_t realm =
            realmMin + enemyRng.nextInt(std::max(realmMax + 1 - realmMin, 1));
        const int32_t realmLayer = 1 + enemyRng.nextInt(9);
        const int32_t minRarity = realmMaxRarity(realm);
        const int32_t maxRarity = std::min(minRarity + 1, 6);

        // 装备生成（B3 套装部件制、四部位随机排列、敌随机穿其中若干件；实例经
        // EquipmentFactory.create 唯一产出（套装随机二选一）；属性按逐件
        // totalBonus 累加——孕养随机等级逻辑已随孕养系统删除）
        JavaRandom javaRnd(static_cast<int64_t>(enemyRng.nextInt()));
        std::vector<std::string> slots = {"HEAD", "BODY", "HANDS", "FEET"};
        slots = javaShuffled(slots, javaRnd);
        const int32_t equipmentCount = enemyRng.nextInt(5);

        // 装备属性累加器（Kotlin EquipmentStatsAccumulator B3 口径：
        // ATTACK/DEFENSE/HP 逐条 .toInt() 截断累加；敌人生成不消费暴击/乘区
        // 词条——敌人不暴击口径，跳过不崩；装备不提供速度/灵力 S14）
        int32_t eqHp = 0, eqPa = 0, eqPd = 0;
        for (int32_t i = 0; i < equipmentCount; ++i) {
            const auto& part = slots[static_cast<std::size_t>(i)];
            // 品阶沿用旧口径：在 [minRarity, maxRarity] 均匀抽取
            const int32_t rarity = minRarity + enemyRng.nextInt(maxRarity + 1 - minRarity);
            // 套装随机二选一（lietian 物理 / zifu 法术）
            const std::string setId =
                enemyRng.nextDouble() < 0.5 ? "lietian" : "zifu";
            auto instOpt = equipment_factory::create(setId, part, rarity, enemyRng);
            if (!instOpt.has_value()) continue;
            const EquipmentInstance& inst = *instOpt;
            // totalBonus（Lv1：主词条 ×(1+0.10×0)=1.0 + 副词条 ×强化次数）
            auto accumulate = [&](const std::string& stat, double value) {
                if (stat == "ATTACK") eqPa += static_cast<int32_t>(value);
                else if (stat == "DEFENSE") eqPd += static_cast<int32_t>(value);
                else if (stat == "HP") eqHp += static_cast<int32_t>(value);
            };
            {
                const double mult =
                    1.0 + 0.10 * (static_cast<double>(inst.growth.level) - 1.0);
                accumulate(inst.growth.affix.mainStat.stat,
                           inst.growth.affix.mainStat.value * mult);
                for (std::size_t k = 0; k < inst.growth.affix.subStats.size(); ++k) {
                    const int32_t rolls =
                        k < inst.growth.affix.subRolls.size()
                            ? inst.growth.affix.subRolls[k]
                            : 1;
                    accumulate(inst.growth.affix.subStats[k].stat,
                               inst.growth.affix.subStats[k].value * rolls);
                }
            }
        }

        // 功法：count + 逐条（类型 roll / 未用 rarity 抽取 / 模板生成 / 熟练度）
        int32_t mHp = 0, mMp = 0, mPa = 0, mPd = 0, mSpd = 0;
        std::vector<gamecore::battle::CombatSkill> skills;
        const int32_t manualCount = enemyRng.nextInt(6);
        bool hasMindManual = false;
        for (int32_t i = 0; i < manualCount; ++i) {
            std::string type;
            if (!hasMindManual && enemyRng.nextDouble() < 0.2) {
                type = "MIND";
            } else {
                static const char* kTypes[3] = {"ATTACK", "DEFENSE", "SUPPORT"};
                type = kTypes[static_cast<std::size_t>(enemyRng.nextInt(3))];
            }
            if (type == "MIND") hasMindManual = true;
            // Kotlin：rarity 计算值未消费（generateRandom 内部自抽品阶）——
            // 该 nextInt 抽取必须保留（抽取序红线）
            (void)(minRarity + enemyRng.nextInt(maxRarity + 1 - minRarity));
            // generateRandom(minRarity, maxRarity, type)：品阶带恒抽 + 模板抽取
            const int32_t tplRarity = manualRarityRoll(minRarity, maxRarity, enemyRng);
            auto templates = manualByTypeRarity(type, tplRarity);
            if (templates.empty()) {
                // Kotlin：templates[random.nextInt(0)] 抛 IllegalArgumentException
                //（不消耗）→ NoSuchElementException 语义等价 → catch continue
                continue;
            }
            const auto* tpl =
                templates[static_cast<std::size_t>(enemyRng.nextInt(
                    static_cast<int32_t>(templates.size())))];
            const ManualStack stack = manualStackFromTemplate(*tpl);
            const int32_t masteryLevel = enemyRng.nextInt(4);
            // 功法属性累加（Kotlin ManualStatsAccumulator.add）
            const double bonus = gamecore::stats::masteryLevelBonus(masteryLevel);
            const auto statOf = [&](const char* primary, const char* fallback) -> int32_t {
                auto s = stack.stats.find(primary);
                if (s != stack.stats.end()) return s->second;
                s = stack.stats.find(fallback);
                if (s != stack.stats.end()) return s->second;
                return 0;
            };
            const int32_t hpValue = statOf("hp", "maxHp");
            const int32_t mpValue = statOf("mp", "maxMp");
            mHp += static_cast<int32_t>(static_cast<double>(hpValue) * bonus);
            mMp += static_cast<int32_t>(static_cast<double>(mpValue) * bonus);
            // 单列口径（B1/Q2）：功法双列各自 round 后相加
            mPa += static_cast<int32_t>(
                static_cast<double>(statOf("physicalAttack", "")) * bonus) +
                static_cast<int32_t>(
                static_cast<double>(statOf("magicAttack", "")) * bonus);
            mPd += static_cast<int32_t>(
                static_cast<double>(statOf("physicalDefense", "")) * bonus) +
                static_cast<int32_t>(
                static_cast<double>(statOf("magicDefense", "")) * bonus);
            mSpd += static_cast<int32_t>(
                static_cast<double>(statOf("speed", "")) * bonus);
            // 技能（skillName 非空才生成 + 熟练度倍率调整）
            if (stack.skillName.has_value()) {
                gamecore::battle::CombatSkill skill = manualCombatSkill(stack);
                skill.damageMultiplier = skillDamageMultiplier(skill.damageMultiplier, masteryLevel);
                skills.push_back(std::move(skill));
            }
        }

        // 战斗体（Kotlin createHumanCombatant——境界基础 × 方差 × 层数 + 装备 + 功法）
        const auto& rc = gamecore::disciple::realmConfig(realm);
        const double layerMult = 1.0 + (realmLayer - 1) * 0.1;
        auto rngVar = [&enemyRng] {
            return 1.0 + (enemyRng.nextInt(61) - 30) / 100.0;
        };
        gamecore::battle::Combatant enemy;
        enemy.id = "human_enemy_" + std::to_string(index);
        static const char* kEnemyNames[6] = {"魔修", "邪修", "散修", "山匪", "暗杀者", "邪道修士"};
        enemy.name = std::string(kEnemyNames[static_cast<std::size_t>(
                        enemyRng.nextInt(6))]) + std::to_string(index);
        enemy.side = gamecore::battle::CombatantSide::kAttacker;
        enemy.hp = static_cast<int32_t>(
                       static_cast<double>(rc.baseHp) * rngVar() * layerMult) + eqHp + mHp;
        enemy.maxHp = enemy.hp;
        // B3（S14）：装备不提供速度/灵力——mp 只含功法段
        enemy.mp = static_cast<int32_t>(
                       static_cast<double>(rc.baseMp) * rngVar() * layerMult) + mMp;
        enemy.maxMp = enemy.mp;
        // 单列口径（B1）：物法两半各自 round 后相加（同一方差乘区）
        const double atkVarE = rngVar();
        enemy.attack = static_cast<int32_t>(
            static_cast<double>(rc.basePhysicalAttack) * atkVarE * layerMult) +
            static_cast<int32_t>(
            static_cast<double>(rc.baseMagicAttack) * atkVarE * layerMult) + eqPa + mPa;
        enemy.defense = static_cast<int32_t>(
            static_cast<double>(rc.basePhysicalDefense) * rngVar() * layerMult) +
            static_cast<int32_t>(
            static_cast<double>(rc.baseMagicDefense) * rngVar() * layerMult) + eqPd + mPd;
        enemy.speed = static_cast<int32_t>(
            static_cast<double>(rc.baseSpeed) * rngVar() * layerMult) + mSpd;
        enemy.critRate = 0.0;  // 敌人不暴击（暴击系统口径）
        enemy.skills = skills;
        if (enemy.skills.empty()) {
            gamecore::battle::CombatSkill def;
            def.name = "普通攻击";
            def.skillType = SkillType::kAttack;
            def.damageType = DamageType::kPhysical;
            def.damageMultiplier = 1.0;
            def.mpCost = 0;
            def.cooldown = 0;
            enemy.skills.push_back(std::move(def));
        }
        enemy.realm = realm;
        enemy.realmLayer = realmLayer;
        static const char* kElements[5] = {"metal", "wood", "water", "fire", "earth"};
        enemy.element = kElements[static_cast<std::size_t>(enemyRng.nextInt(5))];
        enemies.push_back(std::move(enemy));
    }
    return enemies;
}

// ── 奖励生成（Kotlin MissionSystem 奖励函数族；MISSION 分区抽取序
//    逐位对齐——Kotlin 回退路径经 RngRandomAdapter 同源消费） ──────────

/// rollSpiritStones（max>0 才抽——range = max - min + 1）
inline int32_t rollSpiritStones(const MissionRewardConfig& rewards,
                                rng::DeterministicRng& r) {
    if (rewards.spiritStonesMax > 0) {
        return rewards.spiritStones +
               r.nextInt(rewards.spiritStonesMax - rewards.spiritStones + 1);
    }
    return rewards.spiritStones;
}

/// generateMaterialBatch（count 恒抽一注 + 逐条 rarity 过滤非空才抽模板；
/// 材料模板序 = beast_material_db.h 向量序 = Kotlin allMaterials 序）
inline std::vector<Material> generateMaterialBatch(
        int32_t countMin, int32_t countMax, int32_t minRarity, int32_t maxRarity,
        rng::DeterministicRng& r) {
    std::vector<Material> out;
    if (countMin <= 0) return out;
    const int32_t count = countMin + r.nextInt(countMax - countMin + 1);
    for (int32_t i = 0; i < count; ++i) {
        std::vector<const gamecore::data::BeastMaterialTemplate*> eligible;
        for (const auto& t : gamecore::data::beastMaterialTemplates()) {
            if (t.rarity >= minRarity && t.rarity <= maxRarity) eligible.push_back(&t);
        }
        if (!eligible.empty()) {
            const auto* tpl = eligible[static_cast<std::size_t>(
                r.nextInt(static_cast<int32_t>(eligible.size())))];
            Material m;
            m.id = nextItemId("gc-mat");
            m.name = tpl->name;
            m.rarity = tpl->rarity;
            m.description = tpl->description;
            // materialCategory 映射（Kotlin BeastMaterial.materialCategory——
            // category 键 → MaterialCategory.name 大写直译）
            m.category = materialCategoryName(tpl->category);
            m.quantity = 1;
            out.push_back(std::move(m));
        }
    }
    return out;
}

/// generateMaterials / generateBaseMaterials（main 与 base 两段口径）
inline std::vector<Material> generateMaterials(const MissionRewardConfig& rewards,
                                               rng::DeterministicRng& r) {
    return generateMaterialBatch(rewards.materialCountMin, rewards.materialCountMax,
                                 rewards.materialMinRarity, rewards.materialMaxRarity, r);
}

inline std::vector<Material> generateBaseMaterials(const MissionRewardConfig& rewards,
                                                   rng::DeterministicRng& r) {
    return generateMaterialBatch(rewards.baseMaterialCountMin, rewards.baseMaterialCountMax,
                                 rewards.baseMaterialMinRarity, rewards.baseMaterialMaxRarity, r);
}

/// generatePills（pillCountMin<=0 不抽；count 一注 + 逐条模板一注；
/// 模板池 = pillTemplates() rarity 过滤——生成序 = Kotlin allPills 序）
inline std::vector<Pill> generatePills(const MissionRewardConfig& rewards,
                                       rng::DeterministicRng& r) {
    std::vector<Pill> out;
    if (rewards.pillCountMin <= 0) return out;
    const int32_t count = rewards.pillCountMin +
                          r.nextInt(rewards.pillCountMax - rewards.pillCountMin + 1);
    const auto pool = gamecore::data::detail::pillTemplatesByRarityRange(
        rewards.pillMinRarity, rewards.pillMaxRarity);
    for (int32_t i = 0; i < count; ++i) {
        // Kotlin pills.random(adapter) = nextInt(size)（空池不可达：模板表
        // 1..6 阶全覆盖，任务奖励 rarity 恒 1..6）
        const auto* tpl = pool[static_cast<std::size_t>(
            r.nextInt(static_cast<int32_t>(pool.size())))];
        out.push_back(gamecore::data::detail::pillFromSpec(*tpl, nextItemId("gc-pill")));
    }
    return out;
}

/// generateEquipment（B3：chance gate + EquipmentFactory 分层品阶口径
///（任务奖励无弟子境界上下文不钳制，收敛到配置品阶上限）+ 套装二选一
/// + create 实例产出（词条 kEquipment 分区 roll）。nextBoolean = Kotlin
/// Random.nextBoolean 默认臂 nextBits(1) != 0（适配器 nextBits(1) =
/// nextInt() 无符号右移 31 位——恰耗一注整 nextInt））
inline bool kotlinNextBoolean(rng::DeterministicRng& r) {
    return (static_cast<uint32_t>(r.nextInt()) >> 31) != 0;
}

inline std::vector<EquipmentInstance> generateEquipment(
        const MissionRewardConfig& rewards, rng::DeterministicRng& r) {
    std::vector<EquipmentInstance> out;
    if (rewards.equipmentChance <= 0.0) return out;
    if (r.nextDouble() >= rewards.equipmentChance) return out;
    const int32_t rarity = std::min(
        equipment_factory::pickRarity(rewards.equipmentMinRarity,
                                      equipment_factory::kRealmUnrestricted, r),
        rewards.equipmentMaxRarity);
    const std::string setId = kotlinNextBoolean(r) ? "lietian" : "zifu";
    auto inst = equipment_factory::create(
        setId, equipment_factory::pickPart(setId, r), rarity, r);
    if (inst.has_value()) out.push_back(std::move(*inst));
    return out;
}

/// generateManuals（chance gate + 品阶带恒抽 + 模板抽取；模板空表 = Kotlin
/// NoSuchElementException → catch → 空列表，抽取序逐位一致）
inline std::vector<ManualStack> generateManuals(const MissionRewardConfig& rewards,
                                                rng::DeterministicRng& r) {
    std::vector<ManualStack> out;
    if (rewards.manualChance <= 0.0) return out;
    if (r.nextDouble() >= rewards.manualChance) return out;
    try {
        const int32_t rarity =
            manualRarityRoll(rewards.manualMinRarity, rewards.manualMaxRarity, r);
        auto templates = manualByRarity(rarity);
        if (templates.empty()) {
            throw std::runtime_error("No manual templates found");
        }
        const auto* tpl = templates[static_cast<std::size_t>(
            r.nextInt(static_cast<int32_t>(templates.size())))];
        ManualStack m = manualStackFromTemplate(*tpl);
        m.id = nextItemId("gc-manual");
        out.push_back(std::move(m));
    } catch (const std::exception&) {
        // Kotlin catch (_: Exception) → emptyList
    }
    return out;
}

// ── 任务完成主流程（Kotlin processMissionCompletion 三分支） ─────────

/// 战斗执行（Kotlin executeMissionBattle——BEAST 组装 + executeBattle /
/// HUMAN generateHumanEnemies + Battle；victory = winner==TEAM）
struct MissionBattleOutcome {
    bool executed = false;          // battleSystem==null 恒 false（C++ 无此分支）
    bool victory = false;
    std::vector<std::string> survivorIds;   // 终态存活队员 id（log.teamMembers 口径）
};

inline MissionBattleOutcome executeMissionBattle(
        const ActiveMission& mission,
        const std::vector<Disciple>& disciples,
        const std::map<std::string, EquipmentInstance>& equipmentMap,
        const std::map<std::string, ManualInstance>& manualMap,
        const std::map<std::string, std::map<std::string, ManualProficiencyData>>& proficiencies,
        rng::DeterministicRng& missionRng,
        rng::DeterministicRng& battleRng,
        rng::DeterministicRng& enemyRng) {
    MissionBattleOutcome outcome;
    const int32_t realmMin = difficultyEnemyRealmMin(mission.difficulty);
    const int32_t realmMax = difficultyEnemyRealmMax(mission.difficulty);
    gamecore::battle::BattleState battle;
    if (mission.enemyType == "BEAST") {
        // beastCount = (first + last)/2 = (4+10)/2 = 7（模板区间常量）
        const int32_t beastCount = (4 + 10) / 2;
        const int32_t beastRealm = (realmMin + realmMax) / 2;
        battle = detail::createBeastBattle(
            disciples, equipmentMap, manualMap, proficiencies,
            beastRealm, beastCount);
    } else {
        // humanCount = range.first + MISSION nextInt(range 尺寸)——注意该抽取
        // 走 MISSION 分区（Kotlin rng 参数），敌人生成内部才走 ENEMY_GEN
        const int32_t humanCount =
            4 + missionRng.nextInt((8 - 4 + 1));
        auto enemies = detail::generateHumanEnemies(realmMin, realmMax, humanCount, enemyRng);
        battle.maxTurns = gamecore::battle::kMaxTurns;
        for (const auto& d : disciples) {
            battle.team.push_back(detail::discipleToCombatant(
                d, equipmentMap, manualMap, proficiencies));
        }
        battle.beasts = std::move(enemies);
    }
    const auto result = gamecore::battle::executeBattle(battle, 1.0, battleRng, -1, nullptr);
    outcome.executed = true;
    outcome.victory = result.winner == gamecore::battle::BattleWinner::kTeam;
    for (const auto& c : result.team) {
        if (!c.isDead()) outcome.survivorIds.push_back(c.id);
    }
    return outcome;
}

/// 完成判定（Kotlin TimeProgressUtil.calculateRemainingMonths <= 0——
/// elapsed = (curYear-startYear)*12 + (curMonth-startMonth)）
inline bool isMissionComplete(const ActiveMission& m, int32_t year, int32_t month) {
    const int32_t elapsed = (year - m.startYear) * 12 + (month - m.startMonth);
    const int32_t remaining = m.duration - elapsed;
    return remaining <= 0;
}

/// 单任务完成结算（Kotlin processMissionCompletion + Phase 1 收集段；
/// 异常等价 runCatching——任何异常保留任务到下次）
struct MissionCompletionOutcome {
    bool consumed = false;          // false = 保留 remainingActive（异常/无存活弟子）
    int32_t spiritStones = 0;
    std::vector<std::string> survivors;
    std::vector<std::string> discipleIdsConsumed;   // 原任务成员 id（状态重置遍历域）
    std::vector<Material> materials;
    std::vector<Pill> pills;
    std::vector<EquipmentInstance> equipmentInstances;
    std::vector<ManualStack> manualStacks;
};

inline MissionCompletionOutcome completeSingleMission(
        const ActiveMission& mission,
        const std::vector<Disciple>& aliveDisciples,
        const std::map<std::string, EquipmentInstance>& equipmentMap,
        const std::map<std::string, ManualInstance>& manualMap,
        const std::map<std::string, std::map<std::string, ManualProficiencyData>>& proficiencies,
        rng::DeterministicRng& missionRng,
        rng::DeterministicRng& battleRng,
        rng::DeterministicRng& enemyRng) {
    MissionCompletionOutcome out;
    // Kotlin runCatching：异常 → null → 任务保留（本次不消费任何抽取）
    if (aliveDisciples.empty()) return out;   // aliveDisciples.isEmpty() → null
    const auto& rewards = mission.rewards;
    if (mission.missionType == "NO_COMBAT") {
        out.spiritStones = rollSpiritStones(rewards, missionRng);
        out.materials = generateMaterials(rewards, missionRng);
        out.pills = generatePills(rewards, missionRng);
        out.consumed = true;
        return out;
    }
    if (mission.missionType == "COMBAT_REQUIRED") {
        const auto battle = executeMissionBattle(
            mission, aliveDisciples, equipmentMap, manualMap, proficiencies,
            missionRng, battleRng, enemyRng);
        if (!battle.victory) {
            // 失败臂：空奖励（Kotlin 失败臂 MissionResult(victory=false)
            // 也进 rewards 收集——任务消费、无幸存者、无物品/灵石）
            out.consumed = true;
            return out;
        }
        out.spiritStones = rollSpiritStones(rewards, missionRng);
        out.materials = generateMaterials(rewards, missionRng);
        out.pills = generatePills(rewards, missionRng);
        out.equipmentInstances = generateEquipment(rewards, missionRng);
        out.manualStacks = generateManuals(rewards, missionRng);
        out.survivors = battle.survivorIds;
        out.consumed = true;
        return out;
    }
    // COMBAT_RANDOM：触发判定先于战斗/奖励（MISSION 分区）
    const bool triggered = missionRng.nextDouble() < mission.triggerChance;
    if (!triggered) {
        out.spiritStones = rewards.baseSpiritStones;
        out.materials = generateBaseMaterials(rewards, missionRng);
        out.consumed = true;
        return out;
    }
    const auto battle = executeMissionBattle(
        mission, aliveDisciples, equipmentMap, manualMap, proficiencies,
        missionRng, battleRng, enemyRng);
    if (!battle.victory) {
        out.consumed = true;   // 失败臂消费（同 COMBAT_REQUIRED）
        return out;
    }
    out.spiritStones = rollSpiritStones(rewards, missionRng);
    out.materials = generateMaterials(rewards, missionRng);
    out.pills = generatePills(rewards, missionRng);
    out.equipmentInstances = generateEquipment(rewards, missionRng);
    out.manualStacks = generateManuals(rewards, missionRng);
    out.survivors = battle.survivorIds;
    out.consumed = true;
    return out;
}

}  // namespace detail

// ── 主入口（Kotlin processCompletedMissionsLazy——Phase 1 收集 + Phase 2
//    应用合流为单函数：C++ 结算为单线程状态段，Phase 边界无并发语义） ──

namespace detail {

/// Phase 2：发放任务奖励（Kotlin applyMissionRewards——物品/灵石/状态/魂力；
/// tracking source 与 Kotlin withTrackingSource 包裹口径逐位一致：
/// materials→"quest"，pills/equipment→"trial"，manuals→包裹外 "unknown"）
inline void applyMissionRewards(
        GameState& state,
        const std::vector<MissionCompletionOutcome>& rewards,
        OverflowMailCollector& overflowMail) {
    auto& ds = state.disciples;
    for (const auto& reward : rewards) {
        for (const auto& material : reward.materials) {
            addMaterial(state, material, overflowMail, "quest", false);
        }
        for (const auto& pill : reward.pills) {
            addPill(state, pill, overflowMail, "trial", false);
        }
        for (const auto& equip : reward.equipmentInstances) {
            // B3 单轨实例：无堆叠合并/溢出面（tracking source 随堆叠轨退役）
            (void)addEquipmentInstance(state, equip, "quest");
        }
        for (const auto& manual : reward.manualStacks) {
            addManualStack(state, manual, overflowMail, "unknown", false);
        }
        // 灵石（Kotlin wallet.add(LOW, Quest)；amount<=0 恒等语义）
        if (reward.spiritStones > 0) {
            gamecore::system::SpiritStoneWallet::add(
                state.gameData, reward.spiritStones,
                gamecore::system::SpiritStoneGrade::LOW, "Quest");
        }
        // 弟子状态（Kotlin applyMissionRewards 尾段——id 非数字跳过、死亡跳过）
        for (const auto& did : reward.discipleIdsConsumed) {
            const auto row = ds.rowOf(did);
            if (row.has_value() && ds.isAlive[*row] == 1) {
                ds.statuses[*row] = "IDLE";
            }
        }
    }
}

}  // namespace detail

/// 月结子事件 5：任务完成（Kotlin processCompletedMissionsLazy 等价）。
/// @param rng RngManager——MISSION/BATTLE/ENEMY_GEN 三分区消费（序见文件头）
inline void processCompletedMissions(GameState& state, rng::RngManager& rng) {
    const int32_t year = state.gameData.gameYear;
    const int32_t month = state.gameData.gameMonth;
    auto& missionRng = rng.getRng(rng::RngPartition::kMission);
    auto& battleRng = rng.getRng(rng::RngPartition::kBattle);
    auto& enemyRng = rng.getRng(rng::RngPartition::kEnemyGen);

    // 装备/功法实例查表 map（Kotlin equipmentMap/manualMap——构建一次共享）
    std::map<std::string, EquipmentInstance> equipmentMap;
    for (const auto& e : state.equipmentInstances) {
        equipmentMap.emplace(e.id, e);
    }
    std::map<std::string, ManualInstance> manualMap;
    for (const auto& m : state.manualInstances) {
        manualMap.emplace(m.id, m);
    }
    // 熟练度嵌套 map（Kotlin manualProficiencies.mapValues { associateBy manualId }；
    // associateBy 对重复 manualId 后写覆盖——C++ 赋值同口径）
    std::map<std::string, std::map<std::string, ManualProficiencyData>> proficiencies;
    for (const auto& [discipleId, list] : state.gameData.manualProficiencies) {
        std::map<std::string, ManualProficiencyData> byManualId;
        for (const auto& p : list) {
            byManualId[p.manualId] = p;
        }
        proficiencies.emplace(discipleId, std::move(byManualId));
    }

    auto& ds = state.disciples;
    std::vector<ActiveMission> remainingActive;
    std::vector<detail::MissionCompletionOutcome> rewardsToApply;
    for (const auto& activeMission : state.gameData.activeMissions) {
        if (!detail::isMissionComplete(activeMission, year, month)) {
            remainingActive.push_back(activeMission);
            continue;
        }
        // aliveDisciples = discipleIds mapNotNull { 按 id 找存活弟子 }
        std::vector<Disciple> aliveDisciples;
        for (const auto& did : activeMission.discipleIds) {
            const auto row = ds.rowOf(did);
            if (row.has_value() && ds.isAlive[*row] == 1) {
                aliveDisciples.push_back(ds.materialize(*row));
            }
        }
        try {
            auto outcome = detail::completeSingleMission(
                activeMission, aliveDisciples, equipmentMap, manualMap,
                proficiencies, missionRng, battleRng, enemyRng);
            if (outcome.consumed) {
                outcome.discipleIdsConsumed = activeMission.discipleIds;
                rewardsToApply.push_back(std::move(outcome));
            } else {
                remainingActive.push_back(activeMission);
            }
        } catch (const std::exception&) {
            // Kotlin runCatching：异常任务保留到下次
            remainingActive.push_back(activeMission);
        }
    }

    // Phase 2：单状态段应用（物品 + 灵石 + 弟子状态 + 任务清理）
    OverflowMailCollector overflowMail;
    detail::applyMissionRewards(state, rewardsToApply, overflowMail);
    state.gameData.activeMissions = std::move(remainingActive);
    // 溢出邮件草稿收集后丢弃（与 S4 生产步骤一致）
}

}  // namespace gamecore::system::mission_settle

