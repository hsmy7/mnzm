// ============================================================
// battle_ai.h — 统一战斗 AI 决策层（Kotlin→C++ 迁移战斗批次 B）
//
// 等价复刻 Kotlin `BattleAI`（core/engine/src/main/java/com/xianxia/
// sect/core/engine/domain/battle/BattleAI.kt 600 行）的**决策层**：
//   - AIActionType 枚举（10 值）+ AIAction（skill/targetId/actionType）
//   - decideAction 主决策（8 层级联优先级 + 概率衰减：被控检查 →
//     Tier2 保命 → Tier3 斩杀 → Tier4 支援盟友 → Tier5 团队 Buff →
//     Tier6 控制 → Tier7 AOE → Tier8-10 攻击决策（省蓝/最优单体/普攻））
//   - selectAttackTarget（威胁排序三概率分支）/ selectSupportTarget
//   - 私有辅助：findSelfPreservation / findExecuteTarget / findAllySupport /
//     findBuffOpportunity / findControlAction / decideAttackAction
//
// 与 Kotlin 语义对齐要点（逐条对照源码）：
//   - RNG 走调用方传入的 DeterministicRng（对拍通道 g_rng 随机源；生产
//     调用方按系统分区传入）
//   - 短路求值逐位一致：`unit.hpPercent < 0.25 && rng.nextDouble() < 0.90`
//     仅在血量低于阈值时消费；`rng.nextDouble() < 0.85 && attackSkills
//     .isNotEmpty()` 无条件消费后再判空——C++ `&&` 与 Kotlin `&&` 同
//     从左到右短路
//   - minByOrNull/maxByOrNull 相等时保留第一个 → std::min_element/
//     std::max_element 同语义
//   - sortedByDescending 稳定排序 → std::stable_sort（C-11 契约）
//   - estimateDamage 委托 battle_calculator.h（批次 A 产物，确定性无 RNG）
//   - 集合以指针 vector 承载（与 Kotlin 对象引用语义一致，零拷贝）；
//     目标以 id 承载（AIAction.targetId），对拍输出 id，批次 C 回合编排
//     在 C++ 侧按 id 回查 Combatant
// ============================================================
#pragma once

#include <algorithm>
#include <cstdint>
#include <optional>
#include <string>
#include <tuple>
#include <vector>

#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/battle_calculator.h"

namespace gamecore::battle {

// ============================================================
// 可调阈值与概率（Kotlin BattleAI 顶层常量）
// ============================================================

/// HP 低于此比例触发保命机制
inline constexpr double kSelfPreserveHp = 0.25;
/// 敌人 HP 低于此比例触发斩杀判断
inline constexpr double kExecuteHp = 0.30;
/// 盟友 HP 低于此比例触发支援
inline constexpr double kSupportAllyHp = 0.40;
/// MP 低于此比例进入省蓝模式
inline constexpr double kMpSave = 0.30;
/// 存活敌人数 ≥ 此值才考虑 AOE
inline constexpr int32_t kAoeMinTargets = 3;

// 各层概率（0.0 ~ 1.0）
inline constexpr double kProbSelfPreserve = 0.90;
inline constexpr double kProbExecute = 0.85;
inline constexpr double kProbSupportAlly = 0.80;
inline constexpr double kProbTeamBuff = 0.60;
inline constexpr double kProbControl = 0.60;
inline constexpr double kProbAoe = 0.70;

// 目标选择概率
inline constexpr double kProbTargetLowHp = 0.70;
inline constexpr double kProbTargetHighThreat = 0.50;
inline constexpr double kProbTargetLowDef = 0.40;

// ============================================================
// 辅助谓词（Kotlin buffs.any{ it.first == type } 等价）
// ============================================================

/// 技能 buffs（List<Triple<BuffType, Double, Int>>）是否含指定类型
inline bool anyBuffType(const std::vector<std::tuple<BuffType, double, int32_t>>& buffs,
                        BuffType t) {
    for (const auto& b : buffs) {
        if (std::get<0>(b) == t) return true;
    }
    return false;
}

/// 单位 buffs（List<CombatBuff>）是否含指定类型
inline bool anyBuffType(const std::vector<CombatBuff>& buffs, BuffType t) {
    for (const auto& b : buffs) {
        if (b.type == t) return true;
    }
    return false;
}

// ============================================================
// 行动类型与行动（Kotlin AIActionType / AIAction）
// ============================================================

/// 行动类型（Kotlin BattleAI.AIActionType——顺序与 name 对应）
enum class AIActionType : int32_t {
    kSkillAttackSingle = 0,
    kSkillAttackAoe,
    kSkillHealSelf,
    kSkillHealAlly,
    kSkillHealTeam,
    kSkillBuffSelf,
    kSkillBuffAlly,
    kSkillBuffTeam,
    kNormalAttack,
    kNone,
};

/// AIActionType → name 字符串（对拍 JSON 输出，Kotlin 枚举 name 对应）
inline std::string aiActionTypeName(AIActionType t) {
    switch (t) {
        case AIActionType::kSkillAttackSingle: return "SKILL_ATTACK_SINGLE";
        case AIActionType::kSkillAttackAoe: return "SKILL_ATTACK_AOE";
        case AIActionType::kSkillHealSelf: return "SKILL_HEAL_SELF";
        case AIActionType::kSkillHealAlly: return "SKILL_HEAL_ALLY";
        case AIActionType::kSkillHealTeam: return "SKILL_HEAL_TEAM";
        case AIActionType::kSkillBuffSelf: return "SKILL_BUFF_SELF";
        case AIActionType::kSkillBuffAlly: return "SKILL_BUFF_ALLY";
        case AIActionType::kSkillBuffTeam: return "SKILL_BUFF_TEAM";
        case AIActionType::kNormalAttack: return "NORMAL_ATTACK";
        case AIActionType::kNone: return "NONE";
    }
    return "NONE";
}

/// 统一战斗行动（Kotlin AIAction——skill 持有副本，target 以 id 承载）
struct AIAction {
    std::optional<CombatSkill> skill;
    std::optional<std::string> targetId;
    AIActionType actionType = AIActionType::kNone;

    static AIAction none() { return AIAction(); }
};

// ============================================================
// 目标选择（Kotlin selectAttackTarget / selectSupportTarget）
// ============================================================

/// 选择攻击目标（威胁排序）：低血量优先 → 高威胁优先 → 低防御优先 →
/// 兜底第一个存活；RNG 消费序：alive.size==1 零消费，否则至多 3 次
/// nextDouble（逐层短路）
inline const Combatant* selectAttackTarget(const Combatant& attacker,
                                           const std::vector<const Combatant*>& enemies,
                                           const CombatSkill* skill,
                                           rng::DeterministicRng& rng) {
    std::vector<const Combatant*> alive;
    for (const auto* e : enemies) {
        if (!e->isDead()) alive.push_back(e);
    }
    if (alive.empty()) return nullptr;
    if (alive.size() == 1) return alive.front();

    if (rng.nextDouble() < kProbTargetLowHp) {
        return *std::min_element(
            alive.begin(), alive.end(),
            [](const Combatant* a, const Combatant* b) { return a->hp < b->hp; });
    }
    if (rng.nextDouble() < kProbTargetHighThreat) {
        return *std::max_element(
            alive.begin(), alive.end(),
            [](const Combatant* a, const Combatant* b) {
                return a->effectivePhysicalAttack() + a->effectiveMagicAttack() <
                       b->effectivePhysicalAttack() + b->effectiveMagicAttack();
            });
    }
    if (rng.nextDouble() < kProbTargetLowDef) {
        if (skill && skill->damageType == DamageType::kMagic) {
            return *std::min_element(
                alive.begin(), alive.end(),
                [](const Combatant* a, const Combatant* b) {
                    return a->effectiveMagicDefense() < b->effectiveMagicDefense();
                });
        }
        if (skill && skill->damageType == DamageType::kPhysical) {
            return *std::min_element(
                alive.begin(), alive.end(),
                [](const Combatant* a, const Combatant* b) {
                    return a->effectivePhysicalDefense() < b->effectivePhysicalDefense();
                });
        }
        return *std::min_element(
            alive.begin(), alive.end(),
            [](const Combatant* a, const Combatant* b) {
                return a->effectivePhysicalDefense() + a->effectiveMagicDefense() <
                       b->effectivePhysicalDefense() + b->effectiveMagicDefense();
            });
    }
    return alive.front();
}

/// 选择支援目标（Kotlin selectSupportTarget）：排除施放者自身；治疗/护盾
/// 类选血量最低，否则选双防最低
inline const Combatant* selectSupportTarget(const Combatant& caster,
                                            const std::vector<const Combatant*>& allies,
                                            const CombatSkill& skill) {
    std::vector<const Combatant*> candidates;
    for (const auto* a : allies) {
        if (!a->isDead() && a->id != caster.id) candidates.push_back(a);
    }
    if (candidates.empty()) return nullptr;

    if (skill.healPercent > 0 || skill.healFixed > 0 ||
        skill.healType == HealType::kHp) {
        return *std::min_element(
            candidates.begin(), candidates.end(),
            [](const Combatant* a, const Combatant* b) {
                return a->hpPercent() < b->hpPercent();
            });
    }
    if (skill.shieldPercent > 0) {
        return *std::min_element(
            candidates.begin(), candidates.end(),
            [](const Combatant* a, const Combatant* b) {
                return a->hpPercent() < b->hpPercent();
            });
    }
    return *std::min_element(
        candidates.begin(), candidates.end(),
        [](const Combatant* a, const Combatant* b) {
            return a->effectivePhysicalDefense() + a->effectiveMagicDefense() <
                   b->effectivePhysicalDefense() + b->effectiveMagicDefense();
        });
}

// ============================================================
// 私有辅助（Kotlin 同名私有方法——层级判定与技能挑选）
// ============================================================

/// Tier 2 保命技能挑选（Kotlin findSelfPreservation）：护盾 > 治愈 > 减伤/加速
inline std::optional<AIAction> findSelfPreservation(
    const Combatant& unit, const std::vector<const CombatSkill*>& supportSkills) {
    if (supportSkills.empty()) return std::nullopt;

    for (const auto* s : supportSkills) {
        if (s->shieldPercent > 0 && (s->targetScope == "self" || s->isAoe)) {
            AIAction a;
            a.skill = *s;
            a.targetId = unit.id;
            a.actionType = AIActionType::kSkillBuffSelf;
            return a;
        }
    }
    for (const auto* s : supportSkills) {
        if ((s->healPercent > 0 || s->healFixed > 0) &&
            (s->targetScope == "self" || s->isAoe)) {
            AIAction a;
            a.skill = *s;
            a.targetId = unit.id;
            a.actionType = AIActionType::kSkillHealSelf;
            return a;
        }
    }
    for (const auto* s : supportSkills) {
        const bool isReduction =
            (s->buffType.has_value() && *s->buffType == BuffType::kDamageReduction) ||
            anyBuffType(s->buffs, BuffType::kDamageReduction);
        const bool isSpeed =
            (s->buffType.has_value() && *s->buffType == BuffType::kSpeedBoost) ||
            anyBuffType(s->buffs, BuffType::kSpeedBoost);
        if ((s->targetScope == "self" || s->isAoe) && (isReduction || isSpeed)) {
            AIAction a;
            a.skill = *s;
            a.targetId = unit.id;
            a.actionType = AIActionType::kSkillBuffSelf;
            return a;
        }
    }
    return std::nullopt;
}

/// Tier 3 斩杀目标挑选（Kotlin findExecuteTarget）：威胁降序遍历残血敌人，
/// 用确定性 estimateDamage 判断能否一击必杀（无 RNG 消耗）
inline std::optional<AIAction> findExecuteTarget(
    const Combatant& attacker, const std::vector<const Combatant*>& enemies,
    const std::vector<const CombatSkill*>& attackSkills, double playerDamageModifier) {
    std::vector<const Combatant*> lowHp;
    for (const auto* e : enemies) {
        if (!e->isDead() && e->hpPercent() < kExecuteHp) lowHp.push_back(e);
    }
    if (lowHp.empty()) return std::nullopt;

    // 按威胁降序（stable_sort——Kotlin sortedByDescending 稳定）
    std::stable_sort(lowHp.begin(), lowHp.end(),
                     [](const Combatant* a, const Combatant* b) {
                         return a->effectivePhysicalAttack() +
                                    a->effectiveMagicAttack() >
                                b->effectivePhysicalAttack() +
                                    b->effectiveMagicAttack();
                     });
    for (const auto* target : lowHp) {
        // 非 AOE 技能池在循环内重建（Kotlin for 循环内 filter 同语义）
        std::vector<const CombatSkill*> nonAoe;
        for (const auto* s : attackSkills) {
            if (!s->isAoe) nonAoe.push_back(s);
        }
        if (nonAoe.empty()) continue;
        const CombatSkill* bestSkill = *std::max_element(
            nonAoe.begin(), nonAoe.end(),
            [](const CombatSkill* a, const CombatSkill* b) {
                return a->damageMultiplier < b->damageMultiplier;
            });
        if (estimateDamage(attacker, *target, *bestSkill, nullptr,
                           playerDamageModifier) >= target->hp) {
            AIAction a;
            a.skill = *bestSkill;
            a.targetId = target->id;
            a.actionType = AIActionType::kSkillAttackSingle;
            return a;
        }
    }
    return std::nullopt;
}

/// Tier 4 盟友支援挑选（Kotlin findAllySupport）：治愈最低血量盟友 >
/// 给最低血量盟友上防御 Buff
inline std::optional<AIAction> findAllySupport(
    const Combatant& unit, const std::vector<const Combatant*>& allies,
    const std::vector<const CombatSkill*>& supportSkills) {
    std::vector<const Combatant*> woundedAllies;
    for (const auto* a : allies) {
        if (!a->isDead() && a->id != unit.id && a->hpPercent() < kSupportAllyHp) {
            woundedAllies.push_back(a);
        }
    }
    if (woundedAllies.empty()) return std::nullopt;

    std::vector<const CombatSkill*> healSkills;
    for (const auto* s : supportSkills) {
        if (s->healPercent > 0 || s->healFixed > 0) healSkills.push_back(s);
    }
    if (!healSkills.empty()) {
        const Combatant* target = *std::min_element(
            woundedAllies.begin(), woundedAllies.end(),
            [](const Combatant* a, const Combatant* b) {
                return a->hpPercent() < b->hpPercent();
            });
        const CombatSkill* bestHeal = *std::max_element(
            healSkills.begin(), healSkills.end(),
            [](const CombatSkill* a, const CombatSkill* b) {
                return a->healPercent + static_cast<double>(a->healFixed) / 100.0 <
                       b->healPercent + static_cast<double>(b->healFixed) / 100.0;
            });
        AIAction a;
        a.skill = *bestHeal;
        a.actionType = (bestHeal->targetScope == "team" || bestHeal->isAoe)
            ? AIActionType::kSkillHealTeam
            : AIActionType::kSkillHealAlly;
        if (a.actionType == AIActionType::kSkillHealAlly) a.targetId = target->id;
        return a;
    }

    std::vector<const CombatSkill*> defBuffSkills;
    for (const auto* s : supportSkills) {
        const bool hasDefBuff =
            s->shieldPercent > 0 ||
            (s->buffType.has_value() &&
             (*s->buffType == BuffType::kPhysicalDefenseBoost ||
              *s->buffType == BuffType::kMagicDefenseBoost)) ||
            anyBuffType(s->buffs, BuffType::kPhysicalDefenseBoost) ||
            anyBuffType(s->buffs, BuffType::kMagicDefenseBoost);
        if (hasDefBuff) defBuffSkills.push_back(s);
    }
    if (!defBuffSkills.empty()) {
        const Combatant* target = *std::min_element(
            woundedAllies.begin(), woundedAllies.end(),
            [](const Combatant* a, const Combatant* b) {
                return a->hpPercent() < b->hpPercent();
            });
        const CombatSkill* bestBuff = defBuffSkills.front();
        AIAction a;
        a.skill = *bestBuff;
        a.actionType = (bestBuff->targetScope == "team" || bestBuff->isAoe)
            ? AIActionType::kSkillBuffTeam
            : AIActionType::kSkillBuffAlly;
        if (a.actionType == AIActionType::kSkillBuffAlly) a.targetId = target->id;
        return a;
    }
    return std::nullopt;
}

/// Tier 5 团队 Buff 机会挑选（Kotlin findBuffOpportunity）：团队范围 >
/// 自身（非治疗）> 单体队友
inline std::optional<AIAction> findBuffOpportunity(
    const Combatant& unit, const std::vector<const Combatant*>& allies,
    const std::vector<const CombatSkill*>& supportSkills) {
    for (const auto* s : supportSkills) {
        if (s->targetScope == "team" || s->isAoe) {
            AIAction a;
            a.skill = *s;
            a.actionType = AIActionType::kSkillBuffTeam;
            return a;
        }
    }
    for (const auto* s : supportSkills) {
        if (s->targetScope == "self" && s->healPercent <= 0 && s->healFixed <= 0) {
            AIAction a;
            a.skill = *s;
            a.targetId = unit.id;
            a.actionType = AIActionType::kSkillBuffSelf;
            return a;
        }
    }
    for (const auto* s : supportSkills) {
        if (s->targetScope == "ally") {
            const Combatant* target = selectSupportTarget(unit, allies, *s);
            if (target == nullptr) return std::nullopt;
            AIAction a;
            a.skill = *s;
            a.targetId = target->id;
            a.actionType = AIActionType::kSkillBuffAlly;
            return a;
        }
    }
    return std::nullopt;
}

/// Tier 6 控制技能挑选（Kotlin findControlAction）：目标为最高威胁
/// 未受控敌人
inline std::optional<AIAction> findControlAction(
    const Combatant& unit, const std::vector<const Combatant*>& enemies,
    const std::vector<const CombatSkill*>& skills) {
    std::vector<const CombatSkill*> ccSkills;
    for (const auto* s : skills) {
        const bool isCc =
            (s->buffType.has_value() &&
             (*s->buffType == BuffType::kStun || *s->buffType == BuffType::kFreeze ||
              *s->buffType == BuffType::kSilence || *s->buffType == BuffType::kTaunt)) ||
            anyBuffType(s->buffs, BuffType::kStun) ||
            anyBuffType(s->buffs, BuffType::kFreeze) ||
            anyBuffType(s->buffs, BuffType::kSilence) ||
            anyBuffType(s->buffs, BuffType::kTaunt);
        if (isCc) ccSkills.push_back(s);
    }
    if (ccSkills.empty()) return std::nullopt;

    std::vector<const Combatant*> uncontrolled;
    for (const auto* e : enemies) {
        if (!e->isDead() && !e->hasControlEffect()) uncontrolled.push_back(e);
    }
    if (uncontrolled.empty()) return std::nullopt;

    const Combatant* target = *std::max_element(
        uncontrolled.begin(), uncontrolled.end(),
        [](const Combatant* a, const Combatant* b) {
            return a->effectivePhysicalAttack() + a->effectiveMagicAttack() <
                   b->effectivePhysicalAttack() + b->effectiveMagicAttack();
        });
    AIAction a;
    a.skill = *ccSkills.front();
    a.targetId = target->id;
    a.actionType = AIActionType::kSkillAttackSingle;
    return a;
}

/// Tier 8-10 攻击决策（Kotlin decideAttackAction）：省蓝模式 > 最优单体 >
/// 普通攻击兜底
inline AIAction decideAttackAction(const Combatant& unit,
                                   const std::vector<const Combatant*>& enemies,
                                   const std::vector<const CombatSkill*>& attackSkills,
                                   rng::DeterministicRng& rng) {
    // 省蓝模式
    if (unit.mpPercent() < kMpSave && !attackSkills.empty()) {
        const CombatSkill* cheap = *std::min_element(
            attackSkills.begin(), attackSkills.end(),
            [](const CombatSkill* a, const CombatSkill* b) {
                return a->mpCost < b->mpCost;
            });
        if (unit.mp >= cheap->mpCost) {
            const Combatant* target = selectAttackTarget(unit, enemies, cheap, rng);
            if (target == nullptr) return AIAction::none();
            AIAction a;
            a.skill = *cheap;
            a.targetId = target->id;
            a.actionType = AIActionType::kSkillAttackSingle;
            return a;
        }
    }

    // 最优单体攻击（伤害/蓝耗比最大，蓝耗下限 1）
    if (!attackSkills.empty()) {
        const CombatSkill* best = *std::max_element(
            attackSkills.begin(), attackSkills.end(),
            [](const CombatSkill* a, const CombatSkill* b) {
                return a->damageMultiplier /
                           static_cast<double>(std::max(1, a->mpCost)) <
                       b->damageMultiplier /
                           static_cast<double>(std::max(1, b->mpCost));
            });
        const Combatant* target = selectAttackTarget(unit, enemies, best, rng);
        if (target == nullptr) return AIAction::none();
        AIAction a;
        a.skill = *best;
        a.targetId = target->id;
        a.actionType = AIActionType::kSkillAttackSingle;
        return a;
    }

    // 兜底普通攻击
    const Combatant* target = selectAttackTarget(unit, enemies, nullptr, rng);
    if (target == nullptr) return AIAction::none();
    AIAction a;
    a.targetId = target->id;
    a.actionType = AIActionType::kNormalAttack;
    return a;
}

// ============================================================
// 主决策（Kotlin decideAction——8 层级联，RNG 消费序逐位一致）
// ============================================================

/// 统一战斗 AI 主决策入口：所有单位（不分阵营）调用此方法获取下一步行动。
/// 层叠结构（每层短路返回，RNG 消费序与 Kotlin 逐位一致）：
///   Tier 1 被控检查（零消费）→ Tier 2 保命（血量阈值内消费 1）→
///   Tier 3 斩杀（无条件消费 1）→ Tier 4 支援（无条件消费 1）→
///   Tier 5 团队 Buff（无条件消费 1）→ Tier 6 控制（无条件消费 1）→
///   Tier 7 AOE（3+ 敌人时消费 1）→ Tier 8-10 攻击决策（目标选择至多 3 抽）
inline AIAction decideAction(const Combatant& unit,
                             const std::vector<Combatant>& allies,
                             const std::vector<Combatant>& enemies,
                             rng::DeterministicRng& rng,
                             double playerDamageModifier = 1.0) {
    // 快速退出：已死亡
    if (unit.isDead()) return AIAction::none();

    std::vector<const Combatant*> aliveAllies;
    for (const auto& a : allies) {
        if (!a.isDead()) aliveAllies.push_back(&a);
    }
    std::vector<const Combatant*> aliveEnemies;
    for (const auto& e : enemies) {
        if (!e.isDead()) aliveEnemies.push_back(&e);
    }
    if (aliveEnemies.empty()) return AIAction::none();

    // ---- Tier 1: 被控检查 ----
    if (unit.hasControlEffect()) return AIAction::none();

    const bool isSilenced = anyBuffType(unit.buffs, BuffType::kSilence);

    // 筛选可用技能（沉默禁用全部；冷却归零 + 蓝量充足）
    std::vector<CombatSkill> usableSkills;
    if (!isSilenced) {
        for (const auto& s : unit.skills) {
            if (s.currentCooldown <= 0 && unit.mp >= s.mpCost) {
                usableSkills.push_back(s);
            }
        }
    }
    std::vector<const CombatSkill*> attackSkills;
    std::vector<const CombatSkill*> supportSkills;
    for (const auto& s : usableSkills) {
        if (s.damageMultiplier > 0) {
            attackSkills.push_back(&s);
        } else {
            supportSkills.push_back(&s);
        }
    }

    // ---- Tier 2-3: 紧急行动（保命/斩杀）----
    // Tier 2 保命：仅 hpPercent < 0.25 时消费 1 次 nextDouble（短路）
    if (unit.hpPercent() < kSelfPreserveHp && rng.nextDouble() < kProbSelfPreserve) {
        const auto selfAction = findSelfPreservation(unit, supportSkills);
        if (selfAction.has_value()) return *selfAction;
    }

    // Tier 3 斩杀：无条件消费 1 次 nextDouble 后再判攻击技能非空
    if (rng.nextDouble() < kProbExecute && !attackSkills.empty()) {
        const auto executeAction = findExecuteTarget(
            unit, aliveEnemies, attackSkills, playerDamageModifier);
        if (executeAction.has_value()) return *executeAction;
    }

    // ---- Tier 4-7: 机会行动（支援/团队Buff/控制/AOE）----
    // Tier 4 支援盟友：无条件消费 1 次
    if (rng.nextDouble() < kProbSupportAlly && !supportSkills.empty()) {
        const auto supportAction = findAllySupport(unit, aliveAllies, supportSkills);
        if (supportAction.has_value()) return *supportAction;
    }

    // Tier 5 团队 Buff：无条件消费 1 次
    if (rng.nextDouble() < kProbTeamBuff && !supportSkills.empty()) {
        const auto buffAction = findBuffOpportunity(unit, aliveAllies, supportSkills);
        if (buffAction.has_value()) return *buffAction;
    }

    // Tier 6 控制：无条件消费 1 次
    if (rng.nextDouble() < kProbControl) {
        std::vector<const CombatSkill*> allSkills;
        for (const auto& s : usableSkills) allSkills.push_back(&s);
        const auto ccAction = findControlAction(unit, aliveEnemies, allSkills);
        if (ccAction.has_value()) return *ccAction;
    }

    // Tier 7 AOE：仅存活敌人 ≥ 3 时消费 1 次
    if (static_cast<int32_t>(aliveEnemies.size()) >= kAoeMinTargets &&
        rng.nextDouble() < kProbAoe && !attackSkills.empty()) {
        const CombatSkill* bestAoe = nullptr;
        for (const auto* s : attackSkills) {
            if (s->isAoe &&
                (bestAoe == nullptr || s->damageMultiplier > bestAoe->damageMultiplier)) {
                bestAoe = s;
            }
        }
        if (bestAoe != nullptr) {
            AIAction a;
            a.skill = *bestAoe;
            a.actionType = AIActionType::kSkillAttackAoe;
            return a;
        }
    }

    // ---- Tier 8-10: 攻击决策 ----
    return decideAttackAction(unit, aliveEnemies, attackSkills, rng);
}

}  // namespace gamecore::battle
