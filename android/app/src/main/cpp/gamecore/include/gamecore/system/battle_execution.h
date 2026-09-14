// ============================================================
// battle_execution.h — 战斗回合编排
//
// 等价复刻 Kotlin `BattleSystem` 的**回合编排核心**（BattleSystem.kt
// 1319 行中 executeBattleWithTimeout/executeTurnWithLog/
// executeCombatantTurn 及其辅助函数链）+ `BattleDamageApplier`
// （护盾吸收写回/伤害分摊/伤害链接的应用编排）+ `BattleCalculator`
// selectSkill/selectTarget（拉条立即行动的决策入口——与归入
// battle_ai.h 的 AI 决策层并存，拉条路径专用）。
//
// 模块边界：
//   - 计算管线（calculateCombatantDamage 等）→ battle_calculator.h
//   - AI 决策主入口（BattleAI.decideAction/selectAttackTarget）→
//     battle_ai.h
//   - 战斗组装（createBattle/convertDiscipleToCombatant/createBeast）保持
//     Kotlin（依赖 Kotlin 域对象/注册表/静态数据）——本文件从 Combatant
//     列表直接编排
//   - 战斗日志/描述（BattleDescriptionGenerator/recordTurnAction/
//     buildTurnMessage）保持 Kotlin 调用方层——本文件只做状态变换，
//     对拍 diff 面排除 message
//
// 与 Kotlin 语义对齐要点（逐条对照源码）：
//   - RNG 走调用方传入的 DeterministicRng（对拍 g_rng；生产 BATTLE 分区）
//   - 速度排序稳定降序 → std::stable_sort（对拍契约）
//   - 行动序 = 战斗开始时存活快照（值语义），回合内击杀不影响本轮行动序
//   - 技能斩杀（executeSkill）AttackResult.isInstantKill 恒 false（Kotlin
//     未传参默认）——走护盾吸收路径；普攻斩杀（executeAttack）透传 true
//   - minByOrNull/maxByOrNull 相等取第一个 → std::min/max_element
//   - 写回语义：indexMap 定位 + 元素整体替换（Kotlin copy 写回）
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

namespace gamecore::battle {

// ============================================================
// 战斗常量（GameConfig.Battle 对齐）
// ============================================================

inline constexpr int32_t kMaxTurns = 25;            // MAX_TURNS
inline constexpr int32_t kMinBeastCount = 3;        // MIN_BEAST_COUNT
inline constexpr int64_t kMaxBattleDurationMs = 5000;  // MAX_BATTLE_DURATION_MS
inline constexpr int32_t kRewardSpiritStonesPerBeast = 100;  // generateRewards

// BattleCalculator.selectSkill/selectTarget 常量（Kotlin 私有 const）
inline constexpr double kCalcProbSupportLowHp = 0.80;
inline constexpr double kCalcProbControlUncontrolled = 0.60;
inline constexpr double kCalcProbAoeManyEnemies = 0.70;
inline constexpr double kCalcProbTargetLowHp = 0.70;
inline constexpr double kCalcProbTargetHighThreat = 0.50;
inline constexpr double kCalcProbTargetLowDefense = 0.40;
inline constexpr double kCalcLowHpThreshold = 0.30;
inline constexpr double kCalcLowMpThreshold = 0.30;
inline constexpr int32_t kCalcAoeMinEnemies = 3;

// ============================================================
// 战斗结果（Kotlin BattleWinner / BattleSystemResult 状态段）
// ============================================================

/// 战斗胜者（Kotlin BattleWinner）
enum class BattleWinner : int32_t { kTeam = 0, kBeasts = 1, kDraw = 2 };

/// 战斗状态（Kotlin Battle 状态段：team/beasts/turn/isFinished/winner/maxTurns）
struct BattleState {
    std::vector<Combatant> team;
    std::vector<Combatant> beasts;
    int32_t turn = 0;
    bool isFinished = false;
    BattleWinner winner = BattleWinner::kDraw;
    int32_t maxTurns = kMaxTurns;
};

/// 单条行动记录（Kotlin BattleActionData 确定性字段；message 保持 Kotlin 生成）
struct BattleActionRecord {
    std::string type;           // "support"|"skill"|"attack"|"control"|"dot"
    std::string attacker;       // 行动者 name
    std::string attackerType;   // "disciple"|"beast"
    std::string target;         // 目标名/"全体敌人"/"ctx.team"
    int32_t damage = 0;
    std::string damageType;     // "必杀"|"support"|"闪避"|"物理"|"法术"|"眩晕"|"冰冻"|"持续伤害"
    bool isCrit = false;
    bool isKill = false;
    bool isInstantKill = false;
    std::string skillName;
};

/// 单回合记录（Kotlin BattleRoundData 确定性字段）
struct BattleRound {
    int32_t roundNumber = 0;
    std::vector<BattleActionRecord> actions;
};

/// 战斗结果（Kotlin BattleSystemResult 状态段 + 奖励 + 回合动作序列；
/// message 文本保持 Kotlin 调用方层）
struct BattleResult {
    std::vector<Combatant> team;
    std::vector<Combatant> beasts;
    int32_t turn = 0;
    bool isFinished = true;
    bool timedOut = false;
    BattleWinner winner = BattleWinner::kDraw;
    std::map<std::string, int32_t> rewards;
    std::vector<BattleRound> rounds;
};

/// 单次行动结果（Kotlin AttackResult 状态段；message/newBuffs 保持 Kotlin）
struct AttackResult {
    std::string targetId;
    int32_t targetHp = 0;  // 攻击时目标 hp（isKill 判定用——Kotlin r.target.hp 快照）
    int32_t damage = 0;
    bool isCrit = false;
    bool isPhysical = false;
    bool isDodged = false;
    bool isInstantKill = false;
    std::optional<std::string> skillName;
    int32_t hits = 1;
    bool isSupport = false;
    double healPercent = 0.0;
    HealType healType = HealType::kHp;
    int32_t healAmount = 0;
    std::vector<std::string> healedIds;
    std::map<std::string, std::vector<CombatBuff>> teamBuffs;
    double turnAdvancePercent = 0.0;
};

// ============================================================
// BattleCalculator.selectSkill / selectTarget（拉条路径决策）
// ============================================================

/// 拉条立即行动技能决策（Kotlin BattleCalculator.selectSkill——与
/// BattleAI.decideAction 不同的独立概率分支决策）
inline const CombatSkill* calcSelectSkill(const Combatant& combatant,
                                          const std::vector<const Combatant*>& enemies,
                                          const std::vector<const Combatant*>& allies,
                                          bool isSilenced,
                                          rng::DeterministicRng& rng) {
    if (isSilenced) return nullptr;
    if (combatant.skills.empty()) return nullptr;

    std::vector<const CombatSkill*> availableSkills;
    for (const auto& s : combatant.skills) {
        if (s.currentCooldown == 0 && combatant.mp >= s.mpCost) {
            availableSkills.push_back(&s);
        }
    }
    if (availableSkills.empty()) return nullptr;

    std::vector<const CombatSkill*> supportSkills;
    std::vector<const CombatSkill*> attackSkills;
    for (const auto* s : availableSkills) {
        if (s->skillType == SkillType::kSupport) {
            supportSkills.push_back(s);
        } else {
            attackSkills.push_back(s);
        }
    }

    std::vector<const Combatant*> lowHpAllies;
    for (const auto* a : allies) {
        if (a->hpPercent() < kCalcLowHpThreshold) lowHpAllies.push_back(a);
    }
    if (!lowHpAllies.empty() && !supportSkills.empty() &&
        rng.nextDouble() < kCalcProbSupportLowHp) {
        return supportSkills.front();
    }

    std::vector<const CombatSkill*> controlSkills;
    for (const auto* s : attackSkills) {
        if (s->buffType.has_value() && s->buffDuration > 0 &&
            isDebuff(*s->buffType) &&
            (*s->buffType == BuffType::kStun || *s->buffType == BuffType::kFreeze ||
             *s->buffType == BuffType::kSilence || *s->buffType == BuffType::kTaunt)) {
            controlSkills.push_back(s);
        }
    }
    std::vector<const Combatant*> uncontrolledEnemies;
    for (const auto* e : enemies) {
        if (!e->hasControlEffect()) uncontrolledEnemies.push_back(e);
    }
    if (!uncontrolledEnemies.empty() && !controlSkills.empty() &&
        rng.nextDouble() < kCalcProbControlUncontrolled) {
        return controlSkills.front();
    }

    std::vector<const CombatSkill*> aoeSkills;
    for (const auto* s : attackSkills) {
        if (s->isAoe) aoeSkills.push_back(s);
    }
    if (static_cast<int32_t>(enemies.size()) >= kCalcAoeMinEnemies &&
        !aoeSkills.empty() && rng.nextDouble() < kCalcProbAoeManyEnemies) {
        return *std::max_element(
            aoeSkills.begin(), aoeSkills.end(),
            [](const CombatSkill* a, const CombatSkill* b) {
                return a->damageMultiplier < b->damageMultiplier;
            });
    }

    if (combatant.mpPercent() < kCalcLowMpThreshold && !attackSkills.empty()) {
        const CombatSkill* cheapSkill = *std::min_element(
            attackSkills.begin(), attackSkills.end(),
            [](const CombatSkill* a, const CombatSkill* b) { return a->mpCost < b->mpCost; });
        if (combatant.mp >= cheapSkill->mpCost * 2) {
            return cheapSkill;
        }
        return nullptr;
    }

    if (!attackSkills.empty()) {
        return *std::max_element(
            attackSkills.begin(), attackSkills.end(),
            [](const CombatSkill* a, const CombatSkill* b) {
                return a->damageMultiplier /
                           static_cast<double>(std::max(1, a->mpCost)) <
                       b->damageMultiplier /
                           static_cast<double>(std::max(1, b->mpCost));
            });
    }
    return availableSkills.front();
}

/// 拉条立即行动目标选择（Kotlin BattleCalculator.selectTarget——nextInt
/// 随机选中语义）
inline const Combatant* calcSelectTarget(const Combatant& attacker,
                                         const std::vector<const Combatant*>& targets,
                                         rng::DeterministicRng& rng) {
    std::vector<const Combatant*> lowHpTargets;
    for (const auto* t : targets) {
        if (t->hpPercent() < kCalcLowHpThreshold) lowHpTargets.push_back(t);
    }
    if (!lowHpTargets.empty() && rng.nextDouble() < kCalcProbTargetLowHp) {
        return lowHpTargets[static_cast<size_t>(rng.nextInt(
            static_cast<int32_t>(lowHpTargets.size())))];
    }

    std::vector<const Combatant*> highThreatTargets;
    for (const auto* t : targets) {
        if (!t->skills.empty() &&
            t->effectivePhysicalAttack() > attacker.effectivePhysicalDefense()) {
            highThreatTargets.push_back(t);
        }
    }
    if (!highThreatTargets.empty() && rng.nextDouble() < kCalcProbTargetHighThreat) {
        return highThreatTargets[static_cast<size_t>(rng.nextInt(
            static_cast<int32_t>(highThreatTargets.size())))];
    }

    std::vector<const Combatant*> lowDefenseTargets;
    for (const auto* t : targets) {
        const double avgDefense =
            (t->effectivePhysicalDefense() + t->effectiveMagicDefense()) / 2.0;
        if (avgDefense < attacker.effectivePhysicalAttack() * 0.5) {
            lowDefenseTargets.push_back(t);
        }
    }
    if (!lowDefenseTargets.empty() && rng.nextDouble() < kCalcProbTargetLowDefense) {
        return lowDefenseTargets[static_cast<size_t>(rng.nextInt(
            static_cast<int32_t>(lowDefenseTargets.size())))];
    }
    return targets[static_cast<size_t>(rng.nextInt(
        static_cast<int32_t>(targets.size())))];
}

// ============================================================
// BattleDamageApplier（护盾吸收写回/伤害分摊/伤害链接应用编排）
// ============================================================

/// 对目标应用一次伤害：护盾吸收 → 扣血 → 护盾余量按 value 匹配写回
/// （Kotlin BattleDamageApplier.applyDamageToTarget）
inline Combatant applyDamageToTarget(Combatant target, int32_t damage) {
    // 取最大 value 的活跃护盾（Kotlin calculateShieldAbsorption(Combatant) 提取）
    const CombatBuff* shieldBuff = nullptr;
    for (const auto& b : target.buffs) {
        if (b.type == BuffType::kShield && b.remainingDuration > 0 &&
            (shieldBuff == nullptr || b.value > shieldBuff->value)) {
            shieldBuff = &b;
        }
    }
    if (shieldBuff == nullptr) {
        target.hp = std::max(0, target.hp - damage);
        return target;
    }
    const double safeValue = clamp(shieldBuff->value, 0.0, 1.0);
    const int32_t shieldValue = std::max(static_cast<int32_t>(target.maxHp * safeValue), 0);
    const int32_t absorbed = std::min(shieldValue, damage);
    const int32_t remaining = damage - absorbed;
    const int32_t newShieldValue = std::max(shieldValue - absorbed, 0);
    target.hp = std::max(0, target.hp - remaining);
    const double matchedValue = shieldBuff->value;
    std::vector<CombatBuff> newBuffs;
    for (const auto& b : target.buffs) {
        if (b.type == BuffType::kShield && b.value == matchedValue) {
            CombatBuff updated = b;
            updated.value = static_cast<double>(newShieldValue) /
                std::max(target.maxHp, 1);
            newBuffs.push_back(updated);
        } else {
            newBuffs.push_back(b);
        }
    }
    target.buffs = newBuffs;
    return target;
}

// ============================================================
// 回合编排（Kotlin BattleSystem 核心）
// ============================================================

/// 回合执行上下文（Kotlin TurnContext——引用 team/beasts 原地修改 +
/// 初始 indexMap；actions 为确定性动作记录（日志 message 保持 Kotlin））
struct TurnContext {
    std::vector<Combatant>& team;
    std::vector<Combatant>& beasts;
    std::map<std::string, int32_t> teamIndexMap;
    std::map<std::string, int32_t> beastsIndexMap;
    std::vector<BattleActionRecord> actions;

    explicit TurnContext(std::vector<Combatant>& teamRef,
                         std::vector<Combatant>& beastsRef)
        : team(teamRef), beasts(beastsRef) {
        for (size_t i = 0; i < team.size(); ++i) teamIndexMap[team[i].id] = static_cast<int32_t>(i);
        for (size_t i = 0; i < beasts.size(); ++i) beastsIndexMap[beasts[i].id] = static_cast<int32_t>(i);
    }
};

/// 行动序条目（Kotlin allCombatants 快照——构建时已过滤存活）
struct TurnOrderEntry {
    std::string id;
    CombatantSide side;
    int32_t effectiveSpeed = 0;
};

/// 按 id 在列表中查找（Kotlin firstOrNull { it.id == id }）
inline Combatant* findById(std::vector<Combatant>& list, const std::string& id) {
    for (auto& c : list) {
        if (c.id == id) return &c;
    }
    return nullptr;
}

/// 按 id 在列表中查找（const 版）
inline const Combatant* findById(const std::vector<Combatant>& list, const std::string& id) {
    for (const auto& c : list) {
        if (c.id == id) return &c;
    }
    return nullptr;
}

/// 按 id 查名称（行动记录 target 用；找不到返回 id 原样）
inline std::string nameById(const TurnContext& ctx, const std::string& id) {
    const Combatant* found = findById(ctx.team, id);
    if (found == nullptr) found = findById(ctx.beasts, id);
    return found != nullptr ? found->name : id;
}

// 前置声明（辅助函数相互调用）
struct SkillActionCtx;

inline std::vector<AttackResult> executeSkillAction(TurnContext& ctx,
                                                    const SkillActionCtx& sac,
                                                    const CombatSkill* availableSkill,
                                                    bool isSupportSkill,
                                                    bool isAoeSkill,
                                                    const AIAction* aiAction,
                                                    rng::DeterministicRng& rng);

/// 技能行动上下文（Kotlin SkillActionContext）
struct SkillActionCtx {
    const Combatant* currentCombatant = nullptr;
    std::vector<const Combatant*> aliveEnemies;
    std::vector<Combatant>& allies;
    bool isTeamMember = false;
    double playerDamageModifier = 1.0;

    explicit SkillActionCtx(std::vector<Combatant>& alliesRef) : allies(alliesRef) {}
};

/// 普攻（Kotlin executeAttack）：普攻斩杀透传 isInstantKill
inline AttackResult executeAttack(const Combatant& attacker, const Combatant& defender,
                                  rng::DeterministicRng& rng, double damageModifier = 1.0) {
    const auto result = calculateCombatantDamage(rng, attacker, defender, nullptr,
                                                 damageModifier, nullptr, true);
    AttackResult r;
    r.targetId = defender.id;
    r.targetHp = defender.hp;
    r.damage = result.damage;
    r.isCrit = result.isCrit;
    r.isPhysical = result.isPhysical;
    r.isDodged = result.isDodged;
    r.isInstantKill = result.isInstantKill;
    r.hits = result.hits;
    return r;
}

/// 技能（Kotlin executeSkill）：技能斩杀 AttackResult.isInstantKill 恒 false
/// （Kotlin 未传参默认——斩杀伤害经护盾吸收路径）
inline AttackResult executeSkill(const Combatant& attacker, const Combatant& defender,
                                 const CombatSkill& skill, rng::DeterministicRng& rng,
                                 double damageModifier = 1.0) {
    const auto result = calculateCombatantDamage(rng, attacker, defender, &skill,
                                                 damageModifier, nullptr, true);
    AttackResult r;
    r.targetId = defender.id;
    r.targetHp = defender.hp;
    r.damage = result.damage;
    r.isCrit = result.isCrit;
    r.isPhysical = result.isPhysical;
    r.isDodged = result.isDodged;
    r.skillName = skill.name;
    r.hits = result.hits;
    return r;
}

/// 支援技能执行（Kotlin executeSupportSkill：BattleCalculator.executeSupportSkill
/// → AttackResult 包装，target = caster）
inline AttackResult executeSupportSkillResult(const Combatant& caster,
                                              const std::vector<Combatant>& allies,
                                              const CombatSkill& skill) {
    const auto support = executeSupportSkill(caster, allies, skill);
    AttackResult r;
    r.targetId = caster.id;
    r.damage = 0;
    r.isCrit = false;
    r.isPhysical = false;
    r.isDodged = false;
    r.skillName = skill.name;
    r.hits = 1;
    r.isSupport = true;
    r.healPercent = skill.healPercent;
    r.healType = skill.healType;
    r.healAmount = support.healAmount;
    r.healedIds = support.healedIds;
    r.teamBuffs = support.teamBuffs;
    r.turnAdvancePercent = support.turnAdvancePercent;
    return r;
}

/// 目标选择（Kotlin BattleSystem.selectTarget：AI 目标优先，否则
/// BattleAI.selectAttackTarget 兜底 targets.first()）
inline const Combatant* selectTargetFor(const Combatant& attacker,
                                        const std::vector<const Combatant*>& targets,
                                        const AIAction* aiAction,
                                        rng::DeterministicRng& rng) {
    if (aiAction != nullptr && aiAction->targetId.has_value()) {
        for (const auto* t : targets) {
            if (t->id == *aiAction->targetId) return t;
        }
    }
    const Combatant* selected = selectAttackTarget(attacker, targets, nullptr, rng);
    return selected != nullptr ? selected : targets.front();
}

/// 技能/攻击执行分发（Kotlin executeSkillAction 四分支）
inline std::vector<AttackResult> executeSkillAction(TurnContext& ctx,
                                                    const SkillActionCtx& sac,
                                                    const CombatSkill* availableSkill,
                                                    bool isSupportSkill,
                                                    bool isAoeSkill,
                                                    const AIAction* aiAction,
                                                    rng::DeterministicRng& rng) {
    std::vector<AttackResult> results;
    if (availableSkill == nullptr) {
        // 普攻：selectTarget + executeAttack
        const Combatant* target =
            selectTargetFor(*sac.currentCombatant, sac.aliveEnemies, aiAction, rng);
        results.push_back(executeAttack(*sac.currentCombatant, *target, rng,
                                        sac.isTeamMember ? sac.playerDamageModifier : 1.0));
        return results;
    }
    if (isSupportSkill) {
        if (availableSkill->targetScope == "ally") {
            // 友方单体支援：随机选一名存活友方（rng.nextInt）
            std::vector<const Combatant*> validAllies;
            for (const auto& a : sac.allies) {
                if (!a.isDead() && a.id != sac.currentCombatant->id) validAllies.push_back(&a);
            }
            if (!validAllies.empty()) {
                const Combatant* selectedAlly = validAllies[static_cast<size_t>(
                    rng.nextInt(static_cast<int32_t>(validAllies.size())))];
                AttackResult sup = executeSupportSkillResult(
                    *sac.currentCombatant, {*selectedAlly}, *availableSkill);
                if (sup.healAmount > 0) {
                    sup.healedIds = {selectedAlly->id};
                }
                if (!sup.teamBuffs.empty()) {
                    std::vector<CombatBuff> buffs =
                        sup.teamBuffs.empty() ? std::vector<CombatBuff>()
                                              : sup.teamBuffs.begin()->second;
                    sup.teamBuffs = {{selectedAlly->id, buffs}};
                }
                results.push_back(std::move(sup));
                return results;
            }
            results.push_back(executeSupportSkillResult(
                *sac.currentCombatant, {*sac.currentCombatant}, *availableSkill));
            return results;
        }
        // 团队支援：全体存活友方
        std::vector<Combatant> aliveAllies;
        for (const auto& a : sac.allies) {
            if (!a.isDead()) aliveAllies.push_back(a);
        }
        results.push_back(executeSupportSkillResult(
            *sac.currentCombatant, aliveAllies, *availableSkill));
        return results;
    }
    if (isAoeSkill) {
        // AOE：对每个存活敌人
        const double dmgMod = sac.isTeamMember ? sac.playerDamageModifier : 1.0;
        for (const auto* t : sac.aliveEnemies) {
            results.push_back(executeSkill(*sac.currentCombatant, *t, *availableSkill,
                                           rng, dmgMod));
        }
        return results;
    }
    // 单体技能：selectTarget + executeSkill
    const Combatant* target =
        selectTargetFor(*sac.currentCombatant, sac.aliveEnemies, aiAction, rng);
    results.push_back(executeSkill(*sac.currentCombatant, *target, *availableSkill, rng,
                                   sac.isTeamMember ? sac.playerDamageModifier : 1.0));
    return results;
}

/// 技能 debuff 附加（Kotlin applySkillDebuff：非 AOE 时对单体目标）
inline void applySkillDebuff(TurnContext& ctx, int32_t targetIndex, bool isTeamMember,
                             const Combatant& currentCombatant,
                             const CombatSkill* availableSkill, bool isAoeSkill) {
    if (availableSkill == nullptr || !availableSkill->buffType.has_value() ||
        availableSkill->buffDuration <= 0 || isAoeSkill) {
        return;
    }
    CombatBuff debuff;
    debuff.type = *availableSkill->buffType;
    debuff.value = availableSkill->buffValue;
    debuff.remainingDuration = availableSkill->buffDuration;
    debuff.sourceRealm = currentCombatant.realm;
    debuff.sourceRealmLayer = currentCombatant.realmLayer;
    auto& enemies = isTeamMember ? ctx.beasts : ctx.team;
    if (isTeamMember && targetIndex < static_cast<int32_t>(ctx.beasts.size())) {
        ctx.beasts[targetIndex].buffs.push_back(debuff);
    } else if (targetIndex < static_cast<int32_t>(ctx.team.size())) {
        ctx.team[targetIndex].buffs.push_back(debuff);
    }
}

/// 伤害链接 debuff（Kotlin applyDamageLinkDebuff：清理旧链接 + 附加新链接）
inline void applyDamageLinkDebuff(TurnContext& ctx, int32_t targetIndex, bool isTeamMember,
                                  const Combatant& currentCombatant,
                                  const CombatSkill* availableSkill) {
    if (availableSkill == nullptr || availableSkill->damageLinkPercent <= 0 ||
        availableSkill->buffDuration <= 0) {
        return;
    }
    CombatBuff linkDebuff;
    linkDebuff.type = BuffType::kDamageLink;
    linkDebuff.value = availableSkill->damageLinkPercent;
    linkDebuff.remainingDuration = availableSkill->buffDuration;
    linkDebuff.sourceRealm = currentCombatant.realm;
    linkDebuff.sourceRealmLayer = currentCombatant.realmLayer;
    auto& enemies = isTeamMember ? ctx.beasts : ctx.team;
    for (size_t idx = 0; idx < enemies.size(); ++idx) {
        auto& enemy = enemies[idx];
        bool hasLink = false;
        for (const auto& b : enemy.buffs) {
            if (b.type == BuffType::kDamageLink) { hasLink = true; break; }
        }
        if (hasLink) {
            std::vector<CombatBuff> cleaned;
            for (const auto& b : enemy.buffs) {
                if (b.type != BuffType::kDamageLink) cleaned.push_back(b);
            }
            enemy.buffs = cleaned;
        }
    }
    if (isTeamMember && targetIndex < static_cast<int32_t>(ctx.beasts.size())) {
        ctx.beasts[targetIndex].buffs.push_back(linkDebuff);
    } else if (targetIndex < static_cast<int32_t>(ctx.team.size())) {
        ctx.team[targetIndex].buffs.push_back(linkDebuff);
    }
}

/// AOE debuff（Kotlin applyAoeDebuff：对全部存活敌人）
inline void applyAoeDebuff(TurnContext& ctx,
                           const std::vector<const Combatant*>& aliveEnemies,
                           const std::map<std::string, int32_t>& enemiesIndexMap,
                           bool isTeamMember, const Combatant& currentCombatant,
                           const CombatSkill* availableSkill, bool isAoeSkill) {
    if (!isAoeSkill || availableSkill == nullptr ||
        !availableSkill->buffType.has_value() || availableSkill->buffDuration <= 0) {
        return;
    }
    CombatBuff debuff;
    debuff.type = *availableSkill->buffType;
    debuff.value = availableSkill->buffValue;
    debuff.remainingDuration = availableSkill->buffDuration;
    debuff.sourceRealm = currentCombatant.realm;
    debuff.sourceRealmLayer = currentCombatant.realmLayer;
    for (const auto* e : aliveEnemies) {
        const auto it = enemiesIndexMap.find(e->id);
        if (it == enemiesIndexMap.end()) continue;
        const int32_t idx = it->second;
        if (isTeamMember && idx < static_cast<int32_t>(ctx.beasts.size())) {
            ctx.beasts[idx].buffs.push_back(debuff);
        } else if (idx < static_cast<int32_t>(ctx.team.size())) {
            ctx.team[idx].buffs.push_back(debuff);
        }
    }
}

/// 按 id 写回更新后的 Combatant（Kotlin writeBack）
inline void writeBack(TurnContext& ctx, const std::string& id, const Combatant& updated) {
    for (size_t i = 0; i < ctx.team.size(); ++i) {
        if (ctx.team[i].id == id) {
            ctx.team[i] = updated;
            return;
        }
    }
    for (size_t i = 0; i < ctx.beasts.size(); ++i) {
        if (ctx.beasts[i].id == id) {
            ctx.beasts[i] = updated;
            return;
        }
    }
}

/// 非支援行动伤害效果应用（Kotlin applyDamageEffects）
inline void applyDamageEffects(TurnContext& ctx, const std::vector<AttackResult>& results,
                               const std::map<std::string, int32_t>& enemiesIndexMap,
                               bool isTeamMember, const Combatant& currentCombatant,
                               const CombatSkill* availableSkill, bool isAoeSkill,
                               const std::vector<const Combatant*>& aliveEnemies) {
    for (const auto& r : results) {
        if (r.isDodged) continue;
        const auto it = enemiesIndexMap.find(r.targetId);
        if (it == enemiesIndexMap.end()) continue;
        const int32_t targetIndex = it->second;
        const Combatant currentTarget = isTeamMember ? ctx.beasts[targetIndex]
                                                     : ctx.team[targetIndex];

        if (r.isInstantKill) {
            // 斩杀无视护盾直接击杀（与 AI 引擎语义一致）
            if (isTeamMember) {
                ctx.beasts[targetIndex].hp = 0;
            } else {
                ctx.team[targetIndex].hp = 0;
            }
            continue;
        }

        const Combatant updated =
            applyDamageToTarget(currentTarget, r.damage);
        if (isTeamMember) {
            ctx.beasts[targetIndex] = updated;
        } else {
            ctx.team[targetIndex] = updated;
        }
        // 伤害链接（attacker 视角敌方）+ 伤害分摊（target 视角友方）
        const auto linked = calculateLinkedDamage(currentCombatant, currentTarget,
                                                  r.damage, ctx.beasts, ctx.team);
        for (const auto& [id, updatedC] : linked) {
            // 先查 team 再查 beasts（Kotlin BattleDamageApplier 语义）
            Combatant* found = findById(ctx.team, id);
            if (found != nullptr) {
                *found = applyDamageToTarget(*found, updatedC);
            } else {
                Combatant* inBeasts = findById(ctx.beasts, id);
                if (inBeasts != nullptr) {
                    *inBeasts = applyDamageToTarget(*inBeasts, updatedC);
                }
            }
        }
        const auto shared = calculateDamageShare(r.targetId,
                                                 isTeamMember ? CombatantSide::kAttacker
                                                              : CombatantSide::kDefender,
                                                 r.damage, ctx.team, ctx.beasts);
        for (const auto& [id, shareDamage] : shared) {
            Combatant* found = findById(ctx.team, id);
            if (found != nullptr) {
                *found = applyDamageToTarget(*found, shareDamage);
            } else {
                Combatant* inBeasts = findById(ctx.beasts, id);
                if (inBeasts != nullptr) {
                    *inBeasts = applyDamageToTarget(*inBeasts, shareDamage);
                }
            }
        }

        applySkillDebuff(ctx, targetIndex, isTeamMember, currentCombatant,
                         availableSkill, isAoeSkill);
        applyDamageLinkDebuff(ctx, targetIndex, isTeamMember, currentCombatant,
                              availableSkill);
    }
    applyAoeDebuff(ctx, aliveEnemies, enemiesIndexMap, isTeamMember,
                   currentCombatant, availableSkill, isAoeSkill);
}

/// 技能冷却更新写回（Kotlin applyCooldownUpdate）
inline void applyCooldownUpdate(TurnContext& ctx,
                                const std::map<std::string, int32_t>& alliesIndexMap,
                                const Combatant& currentCombatant,
                                const CombatSkill& availableSkill, bool isTeamMember) {
    const auto it = alliesIndexMap.find(currentCombatant.id);
    if (it == alliesIndexMap.end()) return;
    const int32_t combatantIndex = it->second;
    const Combatant updated = updateCombatantCooldowns(currentCombatant, availableSkill);
    if (isTeamMember) {
        ctx.team[combatantIndex] = updated;
    } else {
        ctx.beasts[combatantIndex] = updated;
    }
}

/// 支援治疗写回（Kotlin applySupportHealing）
inline void applySupportHealing(TurnContext& ctx, const AttackResult& result,
                                std::vector<Combatant>& allies, bool isTeamMember) {
    if (result.healedIds.empty()) return;
    for (const auto& healedId : result.healedIds) {
        for (size_t i = 0; i < allies.size(); ++i) {
            if (allies[i].id != healedId) continue;
            Combatant healed = allies[i];
            if (result.healType == HealType::kMp) {
                healed.mp = std::min(healed.mp + result.healAmount, healed.maxMp);
            } else {
                healed.hp = std::min(healed.hp + result.healAmount, healed.maxHp);
            }
            if (isTeamMember) {
                ctx.team[i] = healed;
            } else {
                ctx.beasts[i] = healed;
            }
            break;
        }
    }
}

/// 团队 BUFF 写回（Kotlin applySupportTeamBuffs）
inline void applySupportTeamBuffs(TurnContext& ctx, const AttackResult& result,
                                  std::vector<Combatant>& allies, bool isTeamMember) {
    if (result.teamBuffs.empty()) return;
    for (const auto& [memberId, buffs] : result.teamBuffs) {
        for (size_t i = 0; i < allies.size(); ++i) {
            if (allies[i].id != memberId) continue;
            Combatant member = allies[i];
            std::vector<CombatBuff> existingBuffs;
            for (const auto& b : member.buffs) {
                if (b.remainingDuration > 0) existingBuffs.push_back(b);
            }
            existingBuffs.insert(existingBuffs.end(), buffs.begin(), buffs.end());
            member.buffs = existingBuffs;
            if (isTeamMember) {
                ctx.team[i] = member;
            } else {
                ctx.beasts[i] = member;
            }
            break;
        }
    }
}

// 前置声明（processTurnAdvance）
inline void processTurnAdvance(TurnContext& ctx, const AttackResult& result,
                               std::vector<Combatant>& allies,
                               const Combatant& currentCombatant, bool isTeamMember,
                               const std::map<std::string, int32_t>& enemiesIndexMap,
                               double playerDamageModifier, rng::DeterministicRng& rng);

/// 支援效果应用（Kotlin applySupportEffects：治疗写回 + 团队 BUFF + 拉条）
inline void applySupportEffects(TurnContext& ctx, const AttackResult& result,
                                std::vector<Combatant>& allies, bool isTeamMember,
                                const Combatant& currentCombatant,
                                double playerDamageModifier, rng::DeterministicRng& rng) {
    applySupportHealing(ctx, result, allies, isTeamMember);
    applySupportTeamBuffs(ctx, result, allies, isTeamMember);
    if (result.turnAdvancePercent > 0) {
        const auto& enemiesIndexMap = isTeamMember ? ctx.beastsIndexMap : ctx.teamIndexMap;
        processTurnAdvance(ctx, result, allies, currentCombatant, isTeamMember,
                           enemiesIndexMap, playerDamageModifier, rng);
    }
}

/// 拉条目标解析（Kotlin resolveAdvancedAlly）
inline const Combatant* resolveAdvancedAlly(const AttackResult& result,
                                            const std::vector<Combatant>& allies,
                                            const Combatant& currentCombatant) {
    std::string advancedId;
    if (!result.healedIds.empty()) {
        advancedId = result.healedIds.front();
    } else if (!result.teamBuffs.empty()) {
        advancedId = result.teamBuffs.begin()->first;
    } else {
        return nullptr;
    }
    for (const auto& a : allies) {
        if (a.id == advancedId && !a.isDead() && a.id != currentCombatant.id) {
            return &a;
        }
    }
    return nullptr;
}

/// 拉条行动执行（Kotlin executeAdvancedAction）
inline AttackResult executeAdvancedAction(const Combatant& advancedAlly,
                                          const Combatant& advTarget,
                                          const CombatSkill* advSkill,
                                          double advDmgMod, rng::DeterministicRng& rng) {
    if (advSkill != nullptr) {
        return executeSkill(advancedAlly, advTarget, *advSkill, rng, advDmgMod);
    }
    return executeAttack(advancedAlly, advTarget, rng, advDmgMod);
}

/// 拉条伤害结算（Kotlin applyAdvancedDamage：护盾吸收写回）
inline void applyAdvancedDamage(TurnContext& ctx, const AttackResult& advResult,
                                int32_t advDmg, bool isTeamMember,
                                const std::map<std::string, int32_t>& enemiesIndexMap) {
    const auto it = enemiesIndexMap.find(advResult.targetId);
    if (it == enemiesIndexMap.end()) return;
    const int32_t advTargetIdx = it->second;
    const Combatant currentTarget = isTeamMember ? ctx.beasts[advTargetIdx]
                                                 : ctx.team[advTargetIdx];
    const Combatant updated = applyDamageToTarget(currentTarget, advDmg);
    if (isTeamMember && advTargetIdx < static_cast<int32_t>(ctx.beasts.size())) {
        ctx.beasts[advTargetIdx] = updated;
    } else if (advTargetIdx < static_cast<int32_t>(ctx.team.size())) {
        ctx.team[advTargetIdx] = updated;
    }
}

/// 拉条行动冷却更新（Kotlin updateAdvancedCooldown）
inline void updateAdvancedCooldown(TurnContext& ctx, const CombatSkill& advSkill,
                                   const Combatant& advancedAlly, bool isTeamMember,
                                   int32_t advIdx) {
    const Combatant updated = updateCombatantCooldowns(advancedAlly, advSkill);
    if (isTeamMember) {
        ctx.team[advIdx] = updated;
    } else {
        ctx.beasts[advIdx] = updated;
    }
}

/// 拉条立即行动（Kotlin processTurnAdvance：RNG 序 selectSkill → selectTarget）
inline void processTurnAdvance(TurnContext& ctx, const AttackResult& result,
                               std::vector<Combatant>& allies,
                               const Combatant& currentCombatant, bool isTeamMember,
                               const std::map<std::string, int32_t>& enemiesIndexMap,
                               double playerDamageModifier, rng::DeterministicRng& rng) {
    const Combatant* advancedAlly = resolveAdvancedAlly(result, allies, currentCombatant);
    if (advancedAlly == nullptr) return;
    auto& advAllies = isTeamMember ? ctx.team : ctx.beasts;
    auto& advEnemies = isTeamMember ? ctx.beasts : ctx.team;
    std::vector<const Combatant*> advAliveEnemies;
    for (const auto& e : advEnemies) {
        if (!e.isDead()) advAliveEnemies.push_back(&e);
    }
    int32_t advIdx = -1;
    for (size_t i = 0; i < advAllies.size(); ++i) {
        if (advAllies[i].id == advancedAlly->id) { advIdx = static_cast<int32_t>(i); break; }
    }
    if (advIdx < 0 || advAliveEnemies.empty()) return;

    // decideAdvancedAction：BattleCalculator.selectSkill + selectTarget（存活盟友）
    std::vector<const Combatant*> advAliveAllies;
    for (const auto& a : advAllies) {
        if (!a.isDead()) advAliveAllies.push_back(&a);
    }
    const CombatSkill* advSkill =
        calcSelectSkill(*advancedAlly, advAliveEnemies, advAliveAllies, false, rng);
    const Combatant* advTarget = calcSelectTarget(*advancedAlly, advAliveEnemies, rng);

    const double advDmgMod =
        advancedAlly->side == CombatantSide::kDefender ? playerDamageModifier : 1.0;
    const AttackResult advResult =
        executeAdvancedAction(*advancedAlly, *advTarget, advSkill, advDmgMod, rng);
    const int32_t advDmg = advResult.isSupport ? 0 : advResult.damage;

    // 拉条行动记录（Kotlin buildAdvancedActionLog 确定性字段）
    {
        BattleActionRecord rec;
        rec.type = advSkill != nullptr ? "skill" : "attack";
        rec.attacker = advancedAlly->name;
        rec.attackerType = isTeamMember ? "disciple" : "beast";
        rec.target = nameById(ctx, advResult.targetId);
        rec.damage = advDmg;
        rec.damageType = advResult.isPhysical ? "物理" : "法术";
        rec.isCrit = advResult.isCrit;
        rec.isKill = advResult.targetHp - advDmg <= 0;
        if (advResult.skillName.has_value()) rec.skillName = *advResult.skillName;
        ctx.actions.push_back(std::move(rec));
    }

    if (!advResult.isSupport && !advResult.isDodged) {
        applyAdvancedDamage(ctx, advResult, advDmg, isTeamMember, enemiesIndexMap);
    }
    if (advSkill != nullptr && advIdx >= 0) {
        updateAdvancedCooldown(ctx, *advSkill, *advancedAlly, isTeamMember, advIdx);
    }
}

/// 仅 Buff 衰减（Kotlin updateCombatantBuffsOnly 写回）
inline void updateCombatantBuffs(TurnContext& ctx, const Combatant& combatant,
                                 std::vector<Combatant>& list,
                                 const std::map<std::string, int32_t>& indexMap) {
    const auto it = indexMap.find(combatant.id);
    if (it == indexMap.end()) return;
    const int32_t idx = it->second;
    if (idx >= static_cast<int32_t>(list.size())) return;
    list[idx] = updateCombatantBuffsOnly(combatant);
}

/// 控制效果处理（Kotlin applyControlEffects：眩晕/冰冻跳过行动 + BUFF 结算 +
/// control 动作记录）
inline bool applyControlEffects(TurnContext& ctx, const Combatant& currentCombatant,
                                bool isTeamMember, std::vector<Combatant>& allies,
                                const std::map<std::string, int32_t>& alliesIndexMap) {
    if (!currentCombatant.hasControlEffect()) return false;
    // control 动作记录（Kotlin applyControlEffects 日志段）
    bool isStun = false;
    for (const auto& b : currentCombatant.buffs) {
        if (b.type == BuffType::kStun) { isStun = true; break; }
    }
    BattleActionRecord rec;
    rec.type = "control";
    rec.attacker = currentCombatant.name;
    rec.attackerType = isTeamMember ? "disciple"
        : (currentCombatant.isBeast ? "beast" : "disciple");
    rec.target = currentCombatant.name;
    rec.damage = 0;
    rec.damageType = isStun ? "眩晕" : "冰冻";
    ctx.actions.push_back(std::move(rec));
    updateCombatantBuffs(ctx, currentCombatant, allies, alliesIndexMap);
    return true;
}

/// DoT 结算写回（Kotlin processDotEffects 状态段 + dot 动作记录）
inline void applyDotEffects(TurnContext& ctx, rng::DeterministicRng& rng) {
    std::vector<Combatant> allCombatants;
    for (const auto& c : ctx.team) {
        if (!c.isDead()) allCombatants.push_back(c);
    }
    for (const auto& c : ctx.beasts) {
        if (!c.isDead()) allCombatants.push_back(c);
    }
    const auto dotResults = processDotEffects(allCombatants);
    for (const auto& result : dotResults) {
        const std::string targetName = [&ctx, &result] {
            const Combatant* found = findById(ctx.team, result.combatantId);
            if (found == nullptr) found = findById(ctx.beasts, result.combatantId);
            return found != nullptr ? found->name : result.combatantId;
        }();
        // dot 动作记录（Kotlin processDotEffects 日志段）
        BattleActionRecord rec;
        rec.type = "dot";
        rec.attacker = "";
        rec.attackerType = "";
        rec.target = targetName;
        rec.damage = result.damage;
        rec.damageType = "持续伤害";
        rec.isKill = result.newHp <= 0;
        ctx.actions.push_back(std::move(rec));
        Combatant* found = findById(ctx.team, result.combatantId);
        if (found != nullptr) {
            found->hp = result.newHp;
        } else {
            Combatant* inBeasts = findById(ctx.beasts, result.combatantId);
            if (inBeasts != nullptr) inBeasts->hp = result.newHp;
        }
    }
}

/// 单参战者行动（Kotlin executeCombatantTurn 状态段；日志保持 Kotlin）
/// @return true 表示敌方全灭提前结束（EndBattle）
inline bool executeCombatantTurn(TurnContext& ctx, const TurnOrderEntry& entry,
                                 double playerDamageModifier, rng::DeterministicRng& rng) {
    const bool isTeamMember = entry.side == CombatantSide::kDefender;
    auto& allies = isTeamMember ? ctx.team : ctx.beasts;
    auto& enemies = isTeamMember ? ctx.beasts : ctx.team;

    std::vector<const Combatant*> aliveEnemies;
    for (const auto& e : enemies) {
        if (!e.isDead()) aliveEnemies.push_back(&e);
    }
    if (aliveEnemies.empty()) return true;  // EndBattle

    // 以 ctx 当前状态判死（回合内被击杀的单位不得继续出手）
    const Combatant* currentCombatant = findById(allies, entry.id);
    if (currentCombatant == nullptr || currentCombatant->isDead()) return false;

    // 控制效果（眩晕/冰冻）：跳过行动并结算 BUFF
    if (applyControlEffects(ctx, *currentCombatant, isTeamMember, allies,
                            isTeamMember ? ctx.teamIndexMap : ctx.beastsIndexMap)) {
        return false;
    }

    bool silenced = false;
    for (const auto& b : currentCombatant->buffs) {
        if (b.type == BuffType::kSilence && b.remainingDuration > 0) { silenced = true; break; }
    }

    // selectSkill：BattleAI.decideAction（allies 值拷贝——Kotlin 传完整 allies）
    const double dmgMod =
        currentCombatant->side == CombatantSide::kDefender ? playerDamageModifier : 1.0;
    std::vector<Combatant> alliesCopy = allies;
    std::vector<Combatant> enemiesCopy = enemies;
    AIAction aiAction = AIAction::none();
    const CombatSkill* availableSkill = nullptr;
    if (!silenced) {
        aiAction = decideAction(*currentCombatant, alliesCopy, enemiesCopy, rng, dmgMod);
        if (aiAction.skill.has_value()) availableSkill = &*aiAction.skill;
    }

    const bool isSupportSkill =
        availableSkill != nullptr && availableSkill->skillType == SkillType::kSupport;
    const bool isAoeSkill = availableSkill != nullptr && availableSkill->isAoe && !isSupportSkill;

    SkillActionCtx sac(allies);
    sac.currentCombatant = currentCombatant;
    sac.aliveEnemies = aliveEnemies;
    sac.isTeamMember = isTeamMember;
    sac.playerDamageModifier = playerDamageModifier;
    const auto results = executeSkillAction(ctx, sac, availableSkill, isSupportSkill,
                                            isAoeSkill, silenced ? nullptr : &aiAction, rng);

    const auto& first = results.front();
    const bool anyInstantKill = [&results] {
        for (const auto& r : results) {
            if (r.isInstantKill) return true;
        }
        return false;
    }();

    // 行动记录（Kotlin recordTurnAction 确定性字段；message 保持 Kotlin 生成）
    {
        BattleActionRecord rec;
        rec.type = isSupportSkill ? "support"
            : (availableSkill != nullptr ? "skill" : "attack");
        rec.attacker = currentCombatant->name;
        rec.attackerType = isTeamMember ? "disciple"
            : (currentCombatant->isBeast ? "beast" : "disciple");
        if (isSupportSkill) {
            rec.target = "ctx.team";
        } else if (isAoeSkill) {
            rec.target = "全体敌人";
        } else {
            rec.target = nameById(ctx, first.targetId);
        }
        if (isAoeSkill) {
            int64_t total = 0;
            for (const auto& r : results) total += static_cast<int64_t>(r.damage);
            total = std::clamp(total, static_cast<int64_t>(INT32_MIN),
                               static_cast<int64_t>(INT32_MAX));
            rec.damage = static_cast<int32_t>(total);
        } else {
            rec.damage = first.damage;
        }
        if (anyInstantKill) {
            rec.damageType = "必杀";
        } else if (first.isSupport) {
            rec.damageType = "support";
        } else if (first.isDodged) {
            rec.damageType = "闪避";
        } else if (first.isPhysical) {
            rec.damageType = "物理";
        } else {
            rec.damageType = "法术";
        }
        rec.isCrit = [&results] {
            for (const auto& r : results) {
                if (r.isCrit) return true;
            }
            return false;
        }();
        // isKill（Kotlin buildTurnMessage：斩杀 true / 支援 false / 技能按
        // 任意目标残血 / 普攻按单体）
        if (anyInstantKill) {
            rec.isKill = true;
        } else if (!first.isSupport) {
            if (availableSkill != nullptr) {
                for (const auto& r : results) {
                    if (r.targetHp - r.damage <= 0) { rec.isKill = true; break; }
                }
            } else {
                rec.isKill = first.targetHp - first.damage <= 0;
            }
        }
        rec.isInstantKill = anyInstantKill;
        if (first.skillName.has_value()) rec.skillName = *first.skillName;
        ctx.actions.push_back(std::move(rec));
    }

    if (!first.isSupport) {
        const auto& enemiesIndexMap = isTeamMember ? ctx.beastsIndexMap : ctx.teamIndexMap;
        applyDamageEffects(ctx, results, enemiesIndexMap, isTeamMember, *currentCombatant,
                           availableSkill, isAoeSkill, aliveEnemies);
    }

    if (availableSkill != nullptr) {
        const auto& alliesIndexMap = isTeamMember ? ctx.teamIndexMap : ctx.beastsIndexMap;
        applyCooldownUpdate(ctx, alliesIndexMap, *currentCombatant, *availableSkill,
                            isTeamMember);
        if (isSupportSkill) {
            applySupportEffects(ctx, first, allies, isTeamMember, *currentCombatant,
                                playerDamageModifier, rng);
        }
    } else {
        updateCombatantBuffs(ctx, *currentCombatant, allies,
                             isTeamMember ? ctx.teamIndexMap : ctx.beastsIndexMap);
    }
    return false;
}

/// 单回合执行（Kotlin executeTurnWithLog 状态段 + 回合动作记录）
inline BattleRound executeTurn(BattleState& state, double playerDamageModifier,
                               rng::DeterministicRng& rng) {
    // 行动序 = 战斗开始时存活快照（值语义）+ 稳定降序速度排序
    std::vector<TurnOrderEntry> order;
    for (const auto& c : state.team) {
        if (!c.isDead()) {
            order.push_back({c.id, c.side, c.effectiveSpeed()});
        }
    }
    for (const auto& c : state.beasts) {
        if (!c.isDead()) {
            order.push_back({c.id, c.side, c.effectiveSpeed()});
        }
    }
    std::stable_sort(order.begin(), order.end(),
                     [](const TurnOrderEntry& a, const TurnOrderEntry& b) {
                         return a.effectiveSpeed > b.effectiveSpeed;
                     });

    TurnContext ctx(state.team, state.beasts);
    for (const auto& entry : order) {
        if (executeCombatantTurn(ctx, entry, playerDamageModifier, rng)) {
            state.isFinished = true;
            break;  // EndBattle 早退：turn 不推进（Kotlin 同语义）
        }
    }
    if (!state.isFinished) {
        applyDotEffects(ctx, rng);
        state.turn += 1;
    }
    BattleRound round;
    round.roundNumber = state.turn + (state.isFinished ? 1 : 0);
    round.actions = std::move(ctx.actions);
    return round;
}

/// 胜者判定（Kotlin resolveBattleWinner）
inline BattleWinner resolveBattleWinner(bool timedOut, int32_t aliveTeam, int32_t aliveBeasts) {
    if (timedOut) {
        if (aliveTeam > aliveBeasts) return BattleWinner::kTeam;
        if (aliveBeasts > aliveTeam) return BattleWinner::kBeasts;
        return BattleWinner::kDraw;
    }
    if (aliveTeam == 0) return BattleWinner::kBeasts;
    if (aliveBeasts == 0) return BattleWinner::kTeam;
    return BattleWinner::kDraw;
}

/// 奖励（Kotlin generateRewards：初始 beasts 数量）
inline std::map<std::string, int32_t> generateRewards(int32_t beastCount) {
    std::map<std::string, int32_t> rewards;
    rewards["spiritStones"] = kRewardSpiritStonesPerBeast * beastCount;
    return rewards;
}

/// 战斗主循环（Kotlin executeBattleWithTimeout 状态段；日志/耗时保持 Kotlin）
/// @param timeoutMs <0 表示不检查超时（对拍用 FixedClock 语义）
/// @param clock 可选单调时钟（nullptr 时不检查超时）
inline BattleResult executeBattle(BattleState& state, double playerDamageModifier,
                                  rng::DeterministicRng& rng,
                                  int64_t timeoutMs = -1,
                                  MonotonicClock* clock = nullptr) {
    const int32_t initialBeastCount = static_cast<int32_t>(state.beasts.size());
    const int64_t startMs = clock != nullptr ? clock->nowMs() : 0;
    bool timedOut = false;
    std::vector<BattleRound> rounds;

    while (!state.isFinished && state.turn < state.maxTurns) {
        if (clock != nullptr && timeoutMs > 0) {
            const int64_t elapsed = clock->nowMs() - startMs;
            if (elapsed > timeoutMs) {
                timedOut = true;
                break;
            }
        }
        auto round = executeTurn(state, playerDamageModifier, rng);
        if (!round.actions.empty()) rounds.push_back(std::move(round));
    }

    const int32_t aliveTeam = [&state] {
        int32_t n = 0;
        for (const auto& c : state.team) {
            if (!c.isDead()) ++n;
        }
        return n;
    }();
    const int32_t aliveBeasts = [&state] {
        int32_t n = 0;
        for (const auto& c : state.beasts) {
            if (!c.isDead()) ++n;
        }
        return n;
    }();

    const BattleWinner winner = resolveBattleWinner(timedOut, aliveTeam, aliveBeasts);
    state.isFinished = true;
    state.winner = winner;

    BattleResult out;
    out.team = state.team;
    out.beasts = state.beasts;
    out.turn = state.turn;
    out.isFinished = true;
    out.timedOut = timedOut;
    out.winner = winner;
    out.rewards = winner == BattleWinner::kTeam ? generateRewards(initialBeastCount)
                                                : std::map<std::string, int32_t>();
    out.rounds = std::move(rounds);
    return out;
}

}  // namespace gamecore::battle
