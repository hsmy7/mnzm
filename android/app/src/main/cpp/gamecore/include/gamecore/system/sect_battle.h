// ============================================================
// sect_battle.h — AI 宗门战引擎（Kotlin→C++ 迁移战斗批次 D-3）
//
// 等价复刻 Kotlin `AISectAttackManager.executeUnifiedAIBattle` 及其
// 行动链（AISectAttackManager.kt 895-1474 行）——**第三战斗引擎**：
// AI vs AI 宗门战 / 洞天 AI 操作（S8 子事件 6）100% 经此入口
// （AISectBattleProcessor → executeSectBattle → executeUnifiedAIBattle）。
//
// 与 BattleSystem.executeBattle（批次 C battle_execution.h）的差异：
//   - 无 Battle 对象：攻击者/防御者列表直接原地修改
//   - **回合内逐行动后 filter 死亡**（列表压缩——Kotlin
//     `attackers = attackers.filter { !it.isDead }`），indexMap 每次行动重建
//   - 决策层复用 BattleAI（decideAction/selectAttackTarget——批次 B 产物），
//     与主引擎同一决策逻辑；RNG 同为 BATTLE 分区
//   - 普攻/技能后刷新攻击者（updateCombatantBuffsOnly/Cooldowns）语义独立
//   - 无拉条/无控制记录；支援 ally 随机选友方（rng.nextInt）
//   - 日志 type="normal"|"skill"|"support"，attackerType="attacker"|"defender"
//   - 胜负：防御方全灭 → ATTACKER；攻击方全灭 → DEFENDER；超时按存活数
//   - 超时检查（Kotlin System.currentTimeMillis，GameConfig.AI 常量）
// ============================================================
#pragma once

#include <algorithm>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/core/platform.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/battle_ai.h"
#include "gamecore/system/battle_calculator.h"
#include "gamecore/system/battle_execution.h"

namespace gamecore::battle {

// ============================================================
// 常量（GameConfig.AI 对齐）
// ============================================================

inline constexpr int32_t kAiMaxBattleTurns = 200;      // MAX_BATTLE_TURNS
inline constexpr int64_t kAiMaxBattleDurationMs = 5000; // MAX_AI_BATTLE_DURATION_MS

// ============================================================
// 结果类型（Kotlin UnifiedAIBattleResult / AIBattleWinner）
// ============================================================

/// AI 宗门战胜者（Kotlin AIBattleWinner）
enum class AiBattleWinner : int32_t { kAttacker = 0, kDefender = 1, kDraw = 2 };

/// AI 宗门战结果（Kotlin UnifiedAIBattleResult；rounds 复用 BattleRound）
struct AiBattleResult {
    std::vector<Combatant> attackers;
    std::vector<Combatant> defenders;
    AiBattleWinner winner = AiBattleWinner::kDraw;
    int32_t turns = 0;
    std::vector<BattleRound> rounds;
};

// ============================================================
// 行动执行（Kotlin executeNormalAttackAction / executeSingleAttackAction /
// executeAoeAttackAction / executeSupportAction 及辅助）
// ============================================================

/// 按 id 在列表中查找（写回辅助）
inline Combatant* findAiById(std::vector<Combatant>& list, const std::string& id) {
    for (auto& c : list) {
        if (c.id == id) return &c;
    }
    return nullptr;
}

/// 写回（Kotlin writeBackToLists：先 allies 后 enemies）
inline void aiWriteBack(const std::string& id, const Combatant& updated,
                        std::vector<Combatant>& allies,
                        std::vector<Combatant>& enemies) {
    Combatant* found = findAiById(allies, id);
    if (found != nullptr) {
        *found = updated;
    } else {
        Combatant* inEnemies = findAiById(enemies, id);
        if (inEnemies != nullptr) *inEnemies = updated;
    }
}

/// 伤害链接 debuff 附加（Kotlin applyLinkDebuff：清旧链接再附加）
inline Combatant aiApplyLinkDebuff(const Combatant& attacker, const Combatant& target,
                                   const CombatSkill& skill) {
    if (skill.damageLinkPercent <= 0 || skill.buffDuration <= 0) return target;
    std::vector<CombatBuff> cleaned;
    for (const auto& b : target.buffs) {
        if (b.type != BuffType::kDamageLink) cleaned.push_back(b);
    }
    CombatBuff link;
    link.type = BuffType::kDamageLink;
    link.value = skill.damageLinkPercent;
    link.remainingDuration = skill.buffDuration;
    link.sourceRealm = attacker.realm;
    link.sourceRealmLayer = attacker.realmLayer;
    cleaned.push_back(link);
    Combatant updated = target;
    updated.buffs = cleaned;
    return updated;
}

/// 伤害分摊/链接应用（Kotlin applyShareAndLink：team=DEFENDER / beasts=ATTACKER）
inline void aiApplyShareAndLink(const Combatant& attacker, const Combatant& target,
                                int32_t damage, std::vector<Combatant>& allies,
                                std::vector<Combatant>& enemies) {
    const bool attackerIsDefender = attacker.side == CombatantSide::kDefender;
    auto& team = attackerIsDefender ? allies : enemies;
    auto& beasts = attackerIsDefender ? enemies : allies;
    const auto shared = calculateDamageShare(target.id, target.side, damage, team, beasts);
    for (const auto& [id, shareDamage] : shared) {
        Combatant* found = findAiById(allies, id);
        if (found == nullptr) found = findAiById(enemies, id);
        if (found != nullptr) {
            const Combatant updated = applyDamageToTarget(*found, shareDamage);
            aiWriteBack(id, updated, allies, enemies);
        }
    }
    const auto linked = calculateLinkedDamage(attacker, target, damage, beasts, team);
    for (const auto& [id, linkDamage] : linked) {
        Combatant* found = findAiById(allies, id);
        if (found == nullptr) found = findAiById(enemies, id);
        if (found != nullptr) {
            const Combatant updated = applyDamageToTarget(*found, linkDamage);
            aiWriteBack(id, updated, allies, enemies);
        }
    }
}

/// 普攻（Kotlin executeNormalAttackAction 全链）
inline void aiExecuteNormalAttack(Combatant& attacker, const Combatant& target,
                                  std::vector<Combatant>& allies,
                                  std::vector<Combatant>& enemies,
                                  std::vector<BattleActionRecord>& roundActions,
                                  rng::DeterministicRng& rng) {
    const auto result = calculateCombatantDamage(rng, attacker, target, nullptr, 1.0,
                                                 nullptr, true);
    const std::string attackerType =
        attacker.side == CombatantSide::kAttacker ? "attacker" : "defender";
    const int32_t allyIdx = [&] {
        for (size_t i = 0; i < allies.size(); ++i) {
            if (allies[i].id == attacker.id) return static_cast<int32_t>(i);
        }
        return -1;
    }();
    if (result.isInstantKill) {
        Combatant* targetPtr = findAiById(enemies, target.id);
        if (targetPtr != nullptr) targetPtr->hp = 0;
        if (allyIdx >= 0 && allyIdx < static_cast<int32_t>(allies.size())) {
            allies[allyIdx] = updateCombatantBuffsOnly(attacker);
        }
        BattleActionRecord rec;
        rec.type = "normal";
        rec.attacker = attacker.name;
        rec.attackerType = attackerType;
        rec.target = target.name;
        rec.damage = target.maxHp;
        rec.isKill = true;
        roundActions.push_back(std::move(rec));
        return;
    }
    if (result.isDodged) {
        if (allyIdx >= 0 && allyIdx < static_cast<int32_t>(allies.size())) {
            allies[allyIdx] = updateCombatantBuffsOnly(attacker);
        }
        BattleActionRecord rec;
        rec.type = "normal";
        rec.attacker = attacker.name;
        rec.attackerType = attackerType;
        rec.target = target.name;
        rec.damage = 0;
        roundActions.push_back(std::move(rec));
        return;
    }
    // 正常伤害：护盾吸收 + 扣血 + 分摊/链接
    int32_t newHp = target.hp;
    Combatant* targetPtr = findAiById(enemies, target.id);
    if (targetPtr != nullptr) {
        const Combatant updated = applyDamageToTarget(*targetPtr, result.damage);
        *targetPtr = updated;
        newHp = updated.hp;
        aiApplyShareAndLink(attacker, updated, result.damage, allies, enemies);
    }
    if (allyIdx >= 0 && allyIdx < static_cast<int32_t>(allies.size())) {
        allies[allyIdx] = updateCombatantBuffsOnly(attacker);
    }
    BattleActionRecord rec;
    rec.type = "normal";
    rec.attacker = attacker.name;
    rec.attackerType = attackerType;
    rec.target = target.name;
    rec.damage = result.damage;
    rec.isCrit = result.isCrit;
    rec.isKill = newHp == 0;
    roundActions.push_back(std::move(rec));
}

/// 单体技能（Kotlin executeSingleAttackAction 全链）
inline void aiExecuteSingleSkill(Combatant& attacker, const Combatant& target,
                                 const CombatSkill& skill,
                                 std::vector<Combatant>& allies,
                                 std::vector<Combatant>& enemies,
                                 std::vector<BattleActionRecord>& roundActions,
                                 rng::DeterministicRng& rng) {
    const auto result =
        calculateCombatantDamage(rng, attacker, target, &skill, 1.0, nullptr, true);
    const std::string attackerType =
        attacker.side == CombatantSide::kAttacker ? "attacker" : "defender";
    const int32_t allyIdx = [&] {
        for (size_t i = 0; i < allies.size(); ++i) {
            if (allies[i].id == attacker.id) return static_cast<int32_t>(i);
        }
        return -1;
    }();
    if (result.isInstantKill) {
        Combatant* targetPtr = findAiById(enemies, target.id);
        if (targetPtr != nullptr) targetPtr->hp = 0;
        if (allyIdx >= 0 && allyIdx < static_cast<int32_t>(allies.size())) {
            allies[allyIdx] = updateCombatantCooldowns(attacker, skill);
        }
        BattleActionRecord rec;
        rec.type = "skill";
        rec.attacker = attacker.name;
        rec.attackerType = attackerType;
        rec.target = target.name;
        rec.damage = target.maxHp;
        rec.isKill = true;
        rec.skillName = skill.name;
        roundActions.push_back(std::move(rec));
        return;
    }
    if (result.isDodged) {
        if (allyIdx >= 0 && allyIdx < static_cast<int32_t>(allies.size())) {
            allies[allyIdx] = updateCombatantCooldowns(attacker, skill);
        }
        BattleActionRecord rec;
        rec.type = "skill";
        rec.attacker = attacker.name;
        rec.attackerType = attackerType;
        rec.target = target.name;
        rec.damage = 0;
        rec.skillName = skill.name;
        roundActions.push_back(std::move(rec));
        return;
    }
    // 正常伤害：护盾 + debuff + 链接 debuff + 分摊/链接
    int32_t newHp = target.hp;
    Combatant* targetPtr = findAiById(enemies, target.id);
    if (targetPtr != nullptr) {
        Combatant updatedTarget = applyDamageToTarget(*targetPtr, result.damage);
        newHp = updatedTarget.hp;
        if (skill.buffType.has_value() && skill.buffDuration > 0) {
            CombatBuff debuff;
            debuff.type = *skill.buffType;
            debuff.value = skill.buffValue;
            debuff.remainingDuration = skill.buffDuration;
            debuff.sourceRealm = attacker.realm;
            debuff.sourceRealmLayer = attacker.realmLayer;
            updatedTarget.buffs.push_back(debuff);
        }
        updatedTarget = aiApplyLinkDebuff(attacker, updatedTarget, skill);
        *targetPtr = updatedTarget;
        aiApplyShareAndLink(attacker, updatedTarget, result.damage, allies, enemies);
    }
    if (allyIdx >= 0 && allyIdx < static_cast<int32_t>(allies.size())) {
        allies[allyIdx] = updateCombatantCooldowns(attacker, skill);
    }
    BattleActionRecord rec;
    rec.type = "skill";
    rec.attacker = attacker.name;
    rec.attackerType = attackerType;
    rec.target = target.name;
    rec.damage = result.damage;
    rec.skillName = skill.name;
    rec.isCrit = result.isCrit;
    rec.isKill = newHp == 0;
    roundActions.push_back(std::move(rec));
}

/// AOE 单目标伤害（Kotlin applyAoeSingleTarget）
inline void aiApplyAoeSingleTarget(Combatant& attacker, const Combatant& target,
                                   const CombatSkill& skill,
                                   std::vector<Combatant>& allies,
                                   std::vector<Combatant>& enemies,
                                   std::vector<BattleActionRecord>& roundActions,
                                   rng::DeterministicRng& rng) {
    const auto result =
        calculateCombatantDamage(rng, attacker, target, &skill, 1.0, nullptr, true);
    const std::string attackerType =
        attacker.side == CombatantSide::kAttacker ? "attacker" : "defender";
    if (result.isInstantKill) {
        Combatant* targetPtr = findAiById(enemies, target.id);
        if (targetPtr != nullptr) targetPtr->hp = 0;
        BattleActionRecord rec;
        rec.type = "skill";
        rec.attacker = attacker.name;
        rec.attackerType = attackerType;
        rec.target = target.name;
        rec.damage = target.maxHp;
        rec.isKill = true;
        rec.skillName = skill.name;
        roundActions.push_back(std::move(rec));
        return;
    }
    if (result.isDodged) {
        BattleActionRecord rec;
        rec.type = "skill";
        rec.attacker = attacker.name;
        rec.attackerType = attackerType;
        rec.target = target.name;
        rec.damage = 0;
        rec.skillName = skill.name;
        roundActions.push_back(std::move(rec));
        return;
    }
    int32_t newHp = target.hp;
    Combatant* targetPtr = findAiById(enemies, target.id);
    if (targetPtr != nullptr) {
        Combatant updatedTarget = applyDamageToTarget(*targetPtr, result.damage);
        newHp = updatedTarget.hp;
        if (skill.buffType.has_value() && skill.buffDuration > 0) {
            CombatBuff debuff;
            debuff.type = *skill.buffType;
            debuff.value = skill.buffValue;
            debuff.remainingDuration = skill.buffDuration;
            debuff.sourceRealm = attacker.realm;
            debuff.sourceRealmLayer = attacker.realmLayer;
            updatedTarget.buffs.push_back(debuff);
        }
        updatedTarget = aiApplyLinkDebuff(attacker, updatedTarget, skill);
        *targetPtr = updatedTarget;
        aiApplyShareAndLink(attacker, updatedTarget, result.damage, allies, enemies);
    }
    BattleActionRecord rec;
    rec.type = "skill";
    rec.attacker = attacker.name;
    rec.attackerType = attackerType;
    rec.target = target.name;
    rec.damage = result.damage;
    rec.skillName = skill.name;
    rec.isCrit = result.isCrit;
    rec.isKill = newHp == 0;
    roundActions.push_back(std::move(rec));
}

/// AOE 技能（Kotlin executeAoeAttackAction：逐目标 + 冷却仅结算一次）
inline void aiExecuteAoe(Combatant& attacker, const std::vector<const Combatant*>& targets,
                         const CombatSkill& skill, std::vector<Combatant>& allies,
                         std::vector<Combatant>& enemies,
                         std::vector<BattleActionRecord>& roundActions,
                         rng::DeterministicRng& rng) {
    for (const auto* t : targets) {
        if (t->isDead()) continue;
        aiApplyAoeSingleTarget(attacker, *t, skill, allies, enemies, roundActions, rng);
    }
    const int32_t allyIdx = [&] {
        for (size_t i = 0; i < allies.size(); ++i) {
            if (allies[i].id == attacker.id) return static_cast<int32_t>(i);
        }
        return -1;
    }();
    if (allyIdx >= 0 && allyIdx < static_cast<int32_t>(allies.size())) {
        allies[allyIdx] = updateCombatantCooldowns(attacker, skill);
    }
}

/// 支援行动（Kotlin executeSupportAction 全链）
inline void aiExecuteSupport(Combatant& caster, const std::vector<const Combatant*>& allies,
                             const CombatSkill& skill, std::vector<Combatant>& alliesList,
                             std::vector<BattleActionRecord>& roundActions,
                             rng::DeterministicRng& rng) {
    // 施放者旧状态快照（Kotlin 语义：updateSupportCooldown 用 applySupportTeamBuffs
    // **之前**的 caster 值覆盖——支援后新加的 buff 丢失，C++ 引用语义需显式复刻）
    const Combatant casterSnapshot = caster;
    // 支援目标解析（Kotlin resolveSupportTargets：ally → rng.nextInt 随机选友方）
    std::vector<Combatant> supportAllies;
    if (skill.targetScope == "ally") {
        std::vector<const Combatant*> valid;
        for (const auto* a : allies) {
            if (!a->isDead() && a->id != caster.id) valid.push_back(a);
        }
        if (!valid.empty()) {
            supportAllies.push_back(*valid[static_cast<size_t>(
                rng.nextInt(static_cast<int32_t>(valid.size())))]);
        }
    } else {
        for (const auto* a : allies) supportAllies.push_back(*a);
    }
    const auto support = executeSupportSkill(caster, supportAllies, skill);

    // 治疗写回（healAmount > 0 才写）
    if (support.healAmount > 0) {
        for (const auto& healedId : support.healedIds) {
            Combatant* found = findAiById(alliesList, healedId);
            if (found == nullptr) continue;
            if (skill.healType == HealType::kMp) {
                found->mp = std::min(found->mp + support.healAmount, found->maxMp);
            } else {
                found->hp = std::min(found->hp + support.healAmount, found->maxHp);
            }
        }
    }
    // 团队 BUFF 写回
    for (const auto& [memberId, buffs] : support.teamBuffs) {
        Combatant* found = findAiById(alliesList, memberId);
        if (found == nullptr) continue;
        found->buffs.insert(found->buffs.end(), buffs.begin(), buffs.end());
    }
    // 施放者冷却更新（Kotlin 用旧 caster 覆盖——对齐"支援后自身新 buff 丢失"语义）
    const int32_t casterIdx = [&] {
        for (size_t i = 0; i < alliesList.size(); ++i) {
            if (alliesList[i].id == caster.id) return static_cast<int32_t>(i);
        }
        return -1;
    }();
    if (casterIdx >= 0 && casterIdx < static_cast<int32_t>(alliesList.size())) {
        alliesList[casterIdx] = updateCombatantCooldowns(casterSnapshot, skill);
    }
    // 日志（Kotlin buildSupportActionLog：target = **全体 allies 名连接**——
    // 非实际目标；damage = healAmount）
    BattleActionRecord rec;
    rec.type = "support";
    rec.attacker = caster.name;
    rec.attackerType = caster.side == CombatantSide::kAttacker ? "attacker" : "defender";
    std::string targetNames;
    for (size_t i = 0; i < allies.size(); ++i) {
        if (i > 0) targetNames += "、";
        targetNames += allies[i]->name;
    }
    rec.target = targetNames;
    rec.damage = support.healAmount;
    rec.skillName = skill.name;
    roundActions.push_back(std::move(rec));
}

// ============================================================
// 单参战者回合（Kotlin executeAiCombatantTurn）
// ============================================================

/// 单参战者行动（Kotlin executeAiCombatantTurn：控制跳过 / 支援 / AOE /
/// 单体技能 / 普攻四分支；决策复用 BattleAI）
inline void aiExecuteCombatantTurn(std::vector<Combatant>& currentAttackers,
                                   std::vector<Combatant>& currentDefenders,
                                   const Combatant& combatant,
                                   std::vector<BattleActionRecord>& roundActions,
                                   rng::DeterministicRng& rng) {
    const bool isAttacker = combatant.side == CombatantSide::kAttacker;
    auto& allies = isAttacker ? currentAttackers : currentDefenders;
    auto& enemies = isAttacker ? currentDefenders : currentAttackers;

    std::map<std::string, int32_t> alliesIndexMap;
    std::map<std::string, int32_t> enemiesIndexMap;
    for (size_t i = 0; i < allies.size(); ++i) alliesIndexMap[allies[i].id] = static_cast<int32_t>(i);
    for (size_t i = 0; i < enemies.size(); ++i) enemiesIndexMap[enemies[i].id] = static_cast<int32_t>(i);

    std::vector<const Combatant*> aliveEnemies;
    for (const auto& e : enemies) {
        if (!e.isDead()) aliveEnemies.push_back(&e);
    }
    if (aliveEnemies.empty()) return;

    const auto it = alliesIndexMap.find(combatant.id);
    if (it == alliesIndexMap.end()) return;
    const int32_t combatantIdx = it->second;
    const Combatant currentCombatant = allies[combatantIdx];

    if (currentCombatant.hasControlEffect()) {
        allies[combatantIdx] = updateCombatantBuffsOnly(currentCombatant);
        return;
    }

    bool silenced = false;
    for (const auto& b : currentCombatant.buffs) {
        if (b.type == BuffType::kSilence && b.remainingDuration > 0) { silenced = true; break; }
    }

    // 决策：BattleAI.decideAction（allies/enemies 含死——Kotlin 传原列表）
    AIAction aiAction = AIAction::none();
    const CombatSkill* availableSkill = nullptr;
    if (!silenced) {
        aiAction = decideAction(currentCombatant, allies, enemies, rng);
        if (aiAction.skill.has_value()) availableSkill = &*aiAction.skill;
    }

    const bool isSupportSkill =
        availableSkill != nullptr && availableSkill->skillType == SkillType::kSupport;
    const bool isAoeSkill = availableSkill != nullptr && availableSkill->isAoe && !isSupportSkill;

    if (availableSkill != nullptr && isSupportSkill) {
        std::vector<const Combatant*> aliveAllies;
        for (const auto& a : allies) {
            if (!a.isDead()) aliveAllies.push_back(&a);
        }
        aiExecuteSupport(allies[combatantIdx], aliveAllies, *availableSkill, allies,
                         roundActions, rng);
    } else if (availableSkill != nullptr && isAoeSkill) {
        aiExecuteAoe(allies[combatantIdx], aliveEnemies, *availableSkill, allies, enemies,
                     roundActions, rng);
    } else {
        // 目标选择（Kotlin selectAITarget：AI 目标优先，否则 BattleAI.selectAttackTarget）
        const Combatant* target = nullptr;
        if (aiAction.targetId.has_value()) {
            for (const auto* t : aliveEnemies) {
                if (t->id == *aiAction.targetId) { target = t; break; }
            }
        }
        if (target == nullptr) {
            target = selectAttackTarget(allies[combatantIdx], aliveEnemies, nullptr, rng);
        }
        if (target == nullptr) return;
        if (availableSkill != nullptr) {
            aiExecuteSingleSkill(allies[combatantIdx], *target, *availableSkill, allies,
                                 enemies, roundActions, rng);
        } else {
            aiExecuteNormalAttack(allies[combatantIdx], *target, allies, enemies,
                                  roundActions, rng);
        }
    }
}

// ============================================================
// 单回合 + 主循环（Kotlin executeAiRound / executeUnifiedAIBattle）
// ============================================================

/// AI 宗门战单回合（Kotlin executeAiRound：超时检查 + 速度序 + 逐行动 +
/// 每步后 filter 死亡 + DoT + 结束判定）
/// @return true 表示超时（主循环终止）
inline bool aiExecuteRound(std::vector<Combatant>& attackers,
                           std::vector<Combatant>& defenders,
                           int32_t roundNumber,
                           std::vector<BattleRound>& rounds,
                           int64_t startMs, int64_t timeoutMs, MonotonicClock* clock,
                           rng::DeterministicRng& rng) {
    if (clock != nullptr && timeoutMs > 0 && clock->nowMs() - startMs > timeoutMs) {
        return true;  // timedOut
    }
    // 回合开始时存活快照 + 稳定降序速度排序
    struct Entry {
        std::string id;
        int32_t effectiveSpeed = 0;
    };
    std::vector<Entry> order;
    for (const auto& c : attackers) {
        if (!c.isDead()) order.push_back({c.id, c.effectiveSpeed()});
    }
    for (const auto& c : defenders) {
        if (!c.isDead()) order.push_back({c.id, c.effectiveSpeed()});
    }
    std::stable_sort(order.begin(), order.end(),
                     [](const Entry& a, const Entry& b) {
                         return a.effectiveSpeed > b.effectiveSpeed;
                     });

    std::vector<BattleActionRecord> roundActions;
    for (const auto& entry : order) {
        // 快照判死（回合开始时的 isDead 语义）
        const Combatant* snapshot = nullptr;
        for (const auto& c : attackers) {
            if (c.id == entry.id) { snapshot = &c; break; }
        }
        if (snapshot == nullptr) {
            for (const auto& c : defenders) {
                if (c.id == entry.id) { snapshot = &c; break; }
            }
        }
        if (snapshot == nullptr || snapshot->isDead()) continue;

        // 行动（Kotlin 传快照副本，内部按 id 从当前列表定位）
        aiExecuteCombatantTurn(attackers, defenders, *snapshot, roundActions, rng);

        // 每步后 filter 死亡（列表压缩——Kotlin 同语义）
        attackers.erase(std::remove_if(attackers.begin(), attackers.end(),
                                       [](const Combatant& c) { return c.isDead(); }),
                        attackers.end());
        defenders.erase(std::remove_if(defenders.begin(), defenders.end(),
                                       [](const Combatant& c) { return c.isDead(); }),
                        defenders.end());
    }

    // DoT（Kotlin processDotEffects：按 side 写回）
    {
        std::vector<Combatant> allCombatants;
        for (const auto& c : attackers) {
            if (!c.isDead()) allCombatants.push_back(c);
        }
        for (const auto& c : defenders) {
            if (!c.isDead()) allCombatants.push_back(c);
        }
        const auto dotResults = processDotEffects(allCombatants);
        for (const auto& result : dotResults) {
            const bool isAttacker = [&] {
                for (const auto& c : attackers) {
                    if (c.id == result.combatantId) return true;
                }
                return false;
            }();
            auto& list = isAttacker ? attackers : defenders;
            Combatant* found = findAiById(list, result.combatantId);
            if (found != nullptr) found->hp = result.newHp;
        }
    }

    BattleRound round;
    round.roundNumber = roundNumber;
    round.actions = std::move(roundActions);
    rounds.push_back(std::move(round));
    return false;  // not timed out
}

/// AI 宗门战胜者判定（Kotlin resolveAiWinner）
inline AiBattleWinner resolveAiWinner(const std::vector<Combatant>& attackers,
                                      const std::vector<Combatant>& defenders,
                                      bool timedOut) {
    if (defenders.empty()) return AiBattleWinner::kAttacker;
    if (attackers.empty()) return AiBattleWinner::kDefender;
    if (timedOut && attackers.size() != defenders.size()) {
        return attackers.size() > defenders.size() ? AiBattleWinner::kAttacker
                                                   : AiBattleWinner::kDefender;
    }
    return AiBattleWinner::kDraw;
}

/// AI 宗门战主循环（Kotlin executeUnifiedAIBattle）
inline AiBattleResult executeAiBattle(std::vector<Combatant> attackers,
                                      std::vector<Combatant> defenders,
                                      rng::DeterministicRng& rng,
                                      int64_t timeoutMs = -1,
                                      MonotonicClock* clock = nullptr) {
    const int64_t startMs = clock != nullptr ? clock->nowMs() : 0;
    int32_t turn = 0;
    bool timedOut = false;
    bool ended = false;
    std::vector<BattleRound> rounds;

    while (turn < kAiMaxBattleTurns && !timedOut && !ended) {
        if (aiExecuteRound(attackers, defenders, turn + 1, rounds,
                           startMs, timeoutMs, clock, rng)) {
            timedOut = true;
        } else {
            turn++;
            ended = attackers.empty() || defenders.empty();
        }
    }

    AiBattleResult out;
    out.attackers = std::move(attackers);
    out.defenders = std::move(defenders);
    out.winner = resolveAiWinner(out.attackers, out.defenders, timedOut);
    out.turns = turn;
    out.rounds = std::move(rounds);
    return out;
}

}  // namespace gamecore::battle
