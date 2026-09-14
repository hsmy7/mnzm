#pragma once

// ============================================================
// sect_conquest.h — 子事件 6b：AI-vs-AI 征伐环
//
// P2-18 决策项③（A 方案）Stage 2：Kotlin 休眠链
//   - AISectAttackManager.decideAttacks（攻击者循环 + 首个可攻击目标即停
//     + results 去重约束）
//   - resolveDefendersAndBattle / resolveDefenderSetup（守方组装分流：
//     玩家占领宗门驻军守卫 / AI 占领宗门占领者守卫 / 自有弟子池守卫）
//   - executeSectBattleCore（AI vs AI 战斗 + computeCanOccupy 占领判定）
//   - AISectOccupationResolver.applyAIAttackResult（伤亡分流/占领归属/
//     好感惩罚/玩家建筑没收草稿）
// 的 AUTHORITATIVE 移植复活。置于玩家防守环（子事件 6c，sect_defense_battle.h）
// 之前——与 Kotlin 休眠 2 参链内序一致（processAIVsAIBattles →
// processPlayerDefenseBattles）。
//
// 包含契约：同 sect_defense_battle.h——只能在 month_settlement.h 完整
// 定义之后包含（month_settlement.h 文件尾、sect_defense_battle.h 之后）。
//
// RNG 消耗（BATTLE 分区，月结核对表登记项）：
//   - checkAttackConditions：每对（攻方过前置门）恰 1 次 nextDouble
//   - executeAiBattle：战斗全回合抽取
//
// 与 Kotlin 休眠链的语义差异（登记，同 Stage 1 口径）：
//   - 阵亡 AI 弟子：Kotlin 整行移除 → C++ aiMarkSideDead 标死
//     （P1-7 新陈代谢口径；被占领方池清空/合并语义保持 Kotlin 同款）
//   - 幸存守军 HP 回写：Kotlin decideAttacks 组装的 AIAttackResult 恒带
//     空 defenderSurvivorHpMap（buildAIAttackResult 不填充）→ 本移植同构
//     跳过（对齐 Kotlin 休眠链实际行为）
//   - 玩家占领宗门被夺回 → placedBuildings 没收为**平台效应草稿**
//     （seizedSectBuildings 回传 Kotlin residual 执行器调
//     buildingFacade.seizeBuildingsOfSect——建筑特性注册表/Room 生产槽位
//     保留 Kotlin，反向通道回同步）
// ============================================================

#include <algorithm>
#include <cstdint>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/sect_attack_decision.h"  // checkAttackConditions / computeCanOccupy
#include "gamecore/system/sect_defense_battle.h"   // makeGarrisonSlot（包含序：本文件在其后）

namespace gamecore::system {
namespace conquest {

using gamecore::state::Disciple;
using gamecore::state::GameState;

/// 单次 AI 进攻结果（Kotlin AISectAttackManager.AIAttackResult 等价——
/// 去除显示域；defenderSurvivorHp/MpMap 恒空，见文件头差异登记）
struct AIAttackOutcome {
    std::string attackerSectId;
    std::string defenderSectId;
    std::string attackerSectName;
    std::string defenderSectName;
    battle::AiBattleWinner winner = battle::AiBattleWinner::kDraw;
    std::vector<std::string> deadAttackerIds;
    std::vector<std::string> deadDefenderIds;
    bool canOccupy = false;
    std::vector<Disciple> survivingAttackers;
};

/// 驻军槽位数（与 year_settlement.h kGarrisonSlotCount 同值——互包不可
/// 共享，本地同口径副本；Kotlin GARRISON_SLOT_COUNT=10）
constexpr int32_t kConquestGarrisonSlots = 10;

/// 玩家占领宗门驻军（Kotlin PlayerOccupiedDefenseInfo 等价——驻军弟子 +
/// 实例语义战斗体）
struct PlayerGarrisonDefense {
    std::vector<Disciple> disciples;
    std::vector<gamecore::battle::Combatant> combatants;
};

/// 玩家占领宗门驻军信息（Kotlin buildPlayerDefenseInfo——宗门 id → 驻军）。
/// 驻军槽位非空 id → DiscipleStore 存活弟子；战斗体经玩家实例语义组装
///（真实装备/功法实例 + 当前血量 + 血炼加成）。
inline std::map<std::string, PlayerGarrisonDefense> buildPlayerGarrisonDefenses(
        const GameState& state) {
    const auto& ds = state.disciples;
    const auto& gd = state.gameData;
    // 玩家占领宗门（isPlayerOccupied 且占领者=玩家宗门）
    std::string playerSectId;
    for (const auto& sect : gd.worldMapSects) {
        if (sect.isPlayerSect) { playerSectId = sect.id; break; }
    }
    if (playerSectId.empty()) return {};
    std::map<std::string, PlayerGarrisonDefense> out;
    for (const auto& sect : gd.worldMapSects) {
        if (!sect.isPlayerOccupied || sect.occupierSectId != playerSectId) {
            continue;
        }
        PlayerGarrisonDefense defense;
        for (const auto& slot : sect.garrisonSlots) {
            if (slot.discipleId.empty()) continue;
            const auto rowOpt = ds.rowOf(slot.discipleId);
            if (!rowOpt.has_value()) continue;
            if (ds.isAlive[*rowOpt] != 1) continue;
            defense.disciples.push_back(ds.materialize(*rowOpt));
        }
        if (defense.disciples.empty()) { out[sect.id] = std::move(defense); continue; }
        // 实例语义三件套（Kotlin equipmentInstancesSnapshot/manualInstancesSnapshot/
        // manualProficiencies associateBy）
        std::map<std::string, state::EquipmentInstance> equipmentMap;
        for (const auto& e : state.equipmentInstances) equipmentMap.emplace(e.id, e);
        std::map<std::string, state::ManualInstance> manualMap;
        for (const auto& m : state.manualInstances) manualMap.emplace(m.id, m);
        std::map<std::string, std::map<std::string, state::ManualProficiencyData>>
            proficiencies;
        for (const auto& [discipleId, list] : gd.manualProficiencies) {
            std::map<std::string, state::ManualProficiencyData> byManualId;
            for (const auto& p : list) byManualId[p.manualId] = p;
            proficiencies.emplace(discipleId, std::move(byManualId));
        }
        for (const auto& d : defense.disciples) {
            const auto profIt = proficiencies.find(d.id);
            static const std::map<std::string, state::ManualProficiencyData> kEmpty;
            const std::map<std::string, state::ManualProficiencyData>& prof =
                profIt == proficiencies.end() ? kEmpty : profIt->second;
            state::BloodRefinementPctTotal blood;
            const auto brIt = gd.bloodRefinementPctTotals.find(d.id);
            const bool hasBlood = brIt != gd.bloodRefinementPctTotals.end();
            if (hasBlood) blood = brIt->second;
            gamecore::battle::Combatant c = mission_settle::detail::discipleToCombatant(
                d, equipmentMap, manualMap, proficiencies,
                hasBlood ? &blood : nullptr);
            c.side = gamecore::battle::CombatantSide::kDefender;
            defense.combatants.push_back(std::move(c));
        }
        out[sect.id] = std::move(defense);
    }
    return out;
}

/// 守方组装（Kotlin resolveDefenderSetup）：占领判定分流 + 守军构建 +
/// 全守军池（占领判定消费面）；防守者为空返回 nullopt。
struct DefenderSetup {
    std::vector<Disciple> garrisonDisciples;    // 占领者驻军（非空 → 驻军守卫）
    std::vector<Disciple> defenderDisciples;    // 实际出战守军
    std::vector<Disciple> allDefenderPool;      // 占领判定消费面
    bool isPlayerOccupied = false;
    bool isAiOccupied = false;
};

inline std::optional<DefenderSetup> resolveDefenderSetup(
        const GameState& state, const state::WorldSect& defender,
        const state::WorldSect& attacker,
        const std::map<std::string, PlayerGarrisonDefense>& playerGarrisons) {
    DefenderSetup setup;
    // 占领判定（Kotlin 同式：occupier 非空且非攻方 = AI 占领；
    // isPlayerOccupied = 玩家占领标记）
    setup.isAiOccupied = !defender.occupierSectId.empty() &&
                         defender.occupierSectId != attacker.id;
    setup.isPlayerOccupied = defender.isPlayerOccupied;
    if (setup.isAiOccupied) {
        if (setup.isPlayerOccupied) {
            const auto it = playerGarrisons.find(defender.id);
            if (it != playerGarrisons.end()) {
                setup.garrisonDisciples = it->second.disciples;
            }
        } else {
            // 占领者池 ∩ 驻军槽位（存活）
            const auto poolIt = state.aiSectDisciples.find(defender.occupierSectId);
            if (poolIt != state.aiSectDisciples.end()) {
                for (const auto& slot : defender.garrisonSlots) {
                    if (slot.discipleId.empty()) continue;
                    for (const auto& d : poolIt->second) {
                        if (d.id == slot.discipleId && d.isAlive) {
                            setup.garrisonDisciples.push_back(d);
                            break;
                        }
                    }
                }
            }
        }
    }
    // 守方自有池
    const auto poolIt = state.aiSectDisciples.find(defender.id);
    std::vector<Disciple> defenderPool;
    if (poolIt != state.aiSectDisciples.end()) defenderPool = poolIt->second;
    // 出战守军：驻军优先，否则自有池存活 realm 升序取 kAiTeamSize
    if (!setup.garrisonDisciples.empty()) {
        setup.defenderDisciples = setup.garrisonDisciples;
    } else {
        std::vector<Disciple> alive;
        for (const auto& d : defenderPool) {
            if (d.isAlive) alive.push_back(d);
        }
        std::stable_sort(alive.begin(), alive.end(),
                         [](const Disciple& a, const Disciple& b) {
                             return a.realm < b.realm;
                         });
        if (static_cast<int32_t>(alive.size()) > ai_ops::kAiTeamSize) {
            alive.resize(static_cast<std::size_t>(ai_ops::kAiTeamSize));
        }
        setup.defenderDisciples = std::move(alive);
    }
    if (setup.defenderDisciples.empty()) return std::nullopt;
    // 全守军池（占领判定消费面）
    if (!setup.garrisonDisciples.empty()) {
        setup.allDefenderPool = setup.isPlayerOccupied
            ? setup.garrisonDisciples
            : [&] {
                const auto it = state.aiSectDisciples.find(defender.occupierSectId);
                return it == state.aiSectDisciples.end()
                           ? std::vector<Disciple>{} : it->second;
              }();
    } else {
        setup.allDefenderPool = defenderPool;
    }
    return setup;
}

/// 攻方出战队（Kotlin tryDecideAttack：存活 realm 升序取 kAiTeamSize，
/// 不足 kAiMinDisciplesForAttack 返回空）
inline std::vector<Disciple> selectAttackSquad(
        const std::vector<Disciple>& availableAttackers) {
    std::vector<Disciple> selected = availableAttackers;
    std::stable_sort(selected.begin(), selected.end(),
                     [](const Disciple& a, const Disciple& b) {
                         return a.realm < b.realm;
                     });
    if (static_cast<int32_t>(selected.size()) > ai_ops::kAiTeamSize) {
        selected.resize(static_cast<std::size_t>(ai_ops::kAiTeamSize));
    }
    if (static_cast<int32_t>(selected.size()) <
        detail::kAiMinDisciplesForAttack) {
        return {};
    }
    return selected;
}

/// AI 弟子 → 战斗体（AI 模板语义满血入战——Kotlin convertToCombatant
/// hp=stats.maxHp；攻守共用）
inline gamecore::battle::Combatant aiDiscipleToCombatant(
        const Disciple& d, const ai_ops::AiPreparedBattle& prepared,
        gamecore::battle::CombatantSide side) {
    gamecore::battle::Combatant c = mission_settle::detail::discipleToCombatant(
        d, prepared.equipmentMapByDisciple.at(d.id), prepared.manualMap,
        prepared.proficiencies, nullptr);
    c.hp = c.maxHp;
    c.mp = c.maxMp;
    c.side = side;
    return c;
}

// ── 战斗执行（Kotlin executeSectBattleCore / executePlayerSectBattle） ──

/// 单次进攻的完整解析：守方组装 → 战斗 → 结果构造。
/// 条件不满足（攻方不足/守方为空）返回 nullopt。
inline std::optional<AIAttackOutcome> tryResolveAttack(
        GameState& state, const state::WorldSect& attacker,
        const state::WorldSect& defender,
        const std::map<std::string, PlayerGarrisonDefense>& playerGarrisons,
        const std::map<std::string, std::vector<Disciple>>& garrisonMap,
        rng::RngManager& rng) {
    // 攻击条件（C++ checkAttackConditions——前置门早退不消费，门通过
    // 恰抽 1 次 BATTLE nextDouble；守军战力：玩家占领宗门取驻军弟子面
    // garrisonMap——Kotlin decideAttacks 的 playerGarrisonMap 同源）
    if (!detail::checkAttackConditions(state, attacker, defender,
                                       garrisonMap, rng)) {
        return std::nullopt;
    }
    auto& battleRng = rng.getRng(rng::RngPartition::kBattle);

    // 攻方组队
    const auto poolIt = state.aiSectDisciples.find(attacker.id);
    std::vector<Disciple> availableAttackers;
    if (poolIt != state.aiSectDisciples.end()) {
        for (const auto& d : poolIt->second) {
            if (d.isAlive) availableAttackers.push_back(d);
        }
    }
    const std::vector<Disciple> selected = selectAttackSquad(availableAttackers);
    if (selected.empty()) return std::nullopt;

    // 守方组装
    const auto setupOpt = resolveDefenderSetup(state, defender, attacker,
                                               playerGarrisons);
    if (!setupOpt.has_value()) return std::nullopt;
    const DefenderSetup& setup = *setupOpt;

    // 组装 + 战斗（BATTLE 分区；桌面确定性——无超时时钟）
    const auto prepared = ai_ops::aiPrepareDisciplesForBattle(selected);
    std::vector<gamecore::battle::Combatant> attackCombatants;
    attackCombatants.reserve(selected.size());
    for (const auto& d : selected) {
        attackCombatants.push_back(
            aiDiscipleToCombatant(d, prepared, gamecore::battle::CombatantSide::kAttacker));
    }
    std::vector<gamecore::battle::Combatant> defendCombatants;
    std::vector<Disciple> defenseTeam;
    if (setup.isPlayerOccupied && !setup.garrisonDisciples.empty()) {
        // 玩家占领宗门驻军守卫（实例语义战斗体——驻军构建期已组装）
        const auto it = playerGarrisons.find(defender.id);
        std::vector<gamecore::battle::Combatant> garrison = it == playerGarrisons.end()
                                                                ? std::vector<gamecore::battle::Combatant>{}
                                                                : it->second.combatants;
        // Kotlin executePlayerSectBattle：side==DEFENDER 过滤 + take(kAiTeamSize)
        std::vector<gamecore::battle::Combatant> filtered;
        for (auto& c : garrison) {
            if (c.side == gamecore::battle::CombatantSide::kDefender) filtered.push_back(c);
            if (static_cast<int32_t>(filtered.size()) >= ai_ops::kAiTeamSize) break;
        }
        defendCombatants = std::move(filtered);
    } else {
        // AI 守卫（Kotlin createDefenseTeam：存活 realm 升序取 kAiTeamSize，
        // convertToCombatant 模板语义满血）
        std::vector<Disciple> team = setup.defenderDisciples;
        std::vector<Disciple> aliveTeam;
        for (auto& d : team) {
            if (d.isAlive) aliveTeam.push_back(d);
        }
        std::stable_sort(aliveTeam.begin(), aliveTeam.end(),
                         [](const Disciple& a, const Disciple& b) {
                             return a.realm < b.realm;
                         });
        if (static_cast<int32_t>(aliveTeam.size()) > ai_ops::kAiTeamSize) {
            aliveTeam.resize(static_cast<std::size_t>(ai_ops::kAiTeamSize));
        }
        defenseTeam = std::move(aliveTeam);
        const auto preparedDefenders = ai_ops::aiPrepareDisciplesForBattle(defenseTeam);
        for (const auto& d : defenseTeam) {
            defendCombatants.push_back(aiDiscipleToCombatant(
                d, preparedDefenders, gamecore::battle::CombatantSide::kDefender));
        }
    }
    if (defendCombatants.empty()) return std::nullopt;

    const gamecore::battle::AiBattleResult result =
        gamecore::battle::executeAiBattle(attackCombatants, defendCombatants,
                                          battleRng);

    // 死亡集合（Kotlin 口径：原始名单 \ 引擎幸存者）
    AIAttackOutcome outcome;
    outcome.attackerSectId = attacker.id;
    outcome.defenderSectId = defender.id;
    outcome.attackerSectName = attacker.name;
    outcome.defenderSectName = defender.name;
    outcome.winner = result.winner;
    for (const auto& d : selected) {
        const bool survived = std::any_of(
            result.attackers.begin(), result.attackers.end(),
            [&](const gamecore::battle::Combatant& c) { return c.id == d.id; });
        if (!survived) outcome.deadAttackerIds.push_back(d.id);
    }
    for (const auto& c : defendCombatants) {
        const bool survived = std::any_of(
            result.defenders.begin(), result.defenders.end(),
            [&](const gamecore::battle::Combatant& s) { return s.id == c.id; });
        if (!survived) outcome.deadDefenderIds.push_back(c.id);
    }
    // 占领判定：玩家占领驻军战 = 攻方胜即占（Kotlin executePlayerSectBattle
    // 无高阶门槛）；AI 守卫战 = 高阶全灭门槛（computeCanOccupy）
    if (setup.isPlayerOccupied && !setup.garrisonDisciples.empty()) {
        outcome.canOccupy = result.winner == battle::AiBattleWinner::kAttacker;
    } else {
        outcome.canOccupy = detail::computeCanOccupy(
            result.winner == battle::AiBattleWinner::kAttacker,
            setup.allDefenderPool, outcome.deadDefenderIds);
    }
    for (const auto& d : selected) {
        if (std::find(outcome.deadAttackerIds.begin(),
                      outcome.deadAttackerIds.end(), d.id) ==
            outcome.deadAttackerIds.end()) {
            outcome.survivingAttackers.push_back(d);
        }
    }
    return outcome;
}

// ── 结果应用（Kotlin AISectOccupationResolver.applyAIAttackResult） ──

/// 应用单次进攻结果：伤亡分流（攻/守/占领者 + 驻军清理）→ 好感惩罚
/// → 占领归属变更（玩家建筑没收 → 平台效应草稿）。零 RNG。
inline void applyAttackOutcome(GameState& state, const AIAttackOutcome& outcome,
                               std::vector<std::string>& seizedSectBuildings) {
    auto& gd = state.gameData;
    auto& ds = state.disciples;
    const state::WorldSect* defenderSect = nullptr;
    for (const auto& s : gd.worldMapSects) {
        if (s.id == outcome.defenderSectId) { defenderSect = &s; break; }
    }
    const bool isPlayerOccupied =
        defenderSect != nullptr && defenderSect->isPlayerOccupied;
    const bool isAiOccupied = defenderSect != nullptr &&
                              !defenderSect->occupierSectId.empty() &&
                              !isPlayerOccupied;
    const std::string occupierId =
        defenderSect != nullptr ? defenderSect->occupierSectId : "";

    // 1. 玩家占领宗门驻军伤亡（玩家弟子 = DiscipleStore 权威面——
    //    markDead 统一入口；幸存者 HP 回写跳过：Kotlin decideAttacks 组装的
    //    AIAttackResult 恒带空 defenderSurvivorHpMap，见文件头差异登记）
    if (isPlayerOccupied && !outcome.deadDefenderIds.empty()) {
        markAllDead(ds, outcome.deadDefenderIds, gd.gameYear,
                    gd.annualDeceasedDisciples);
    }

    // 2. 攻方阵亡（aiMarkSideDead 标死——P1-7 口径，非 Kotlin 整行移除）
    ai_ops::aiMarkSideDead(state, outcome.attackerSectId,
                           std::set<std::string>(outcome.deadAttackerIds.begin(),
                                                 outcome.deadAttackerIds.end()));

    // 3. 守方伤亡分流（Kotlin computeCasualtyUpdates）
    if (!isPlayerOccupied) {
        if (isAiOccupied && !outcome.deadDefenderIds.empty()) {
            // 死者为占领者驻军 → 占领者池标死 + 被占领宗门驻军槽清空；
            // 被占领方自有池不动
            ai_ops::aiMarkSideDead(state, occupierId,
                                   std::set<std::string>(
                                       outcome.deadDefenderIds.begin(),
                                       outcome.deadDefenderIds.end()));
            for (auto& s : gd.worldMapSects) {
                if (s.id != outcome.defenderSectId) continue;
                for (auto& slot : s.garrisonSlots) {
                    if (std::find(outcome.deadDefenderIds.begin(),
                                  outcome.deadDefenderIds.end(),
                                  slot.discipleId) !=
                        outcome.deadDefenderIds.end()) {
                        const int32_t index = slot.index;
                        slot = state::GarrisonSlot();
                        slot.index = index;
                    }
                }
                break;
            }
        } else if (!outcome.deadDefenderIds.empty()) {
            // 自有弟子池守卫战 → 守方池标死（Kotlin 过滤移除——标死差异
            // 见文件头）
            ai_ops::aiMarkSideDead(state, outcome.defenderSectId,
                                   std::set<std::string>(
                                       outcome.deadDefenderIds.begin(),
                                       outcome.deadDefenderIds.end()));
        }
    }

    // 4. 好感惩罚 -10（Kotlin FavorConfig 0..100 夹取）
    for (auto& r : gd.sectRelations) {
        const bool relevant =
            (r.sectId1 == outcome.attackerSectId &&
             r.sectId2 == outcome.defenderSectId) ||
            (r.sectId1 == outcome.defenderSectId &&
             r.sectId2 == outcome.attackerSectId);
        if (!relevant) continue;
        r.favor = std::max(0, std::min(100, r.favor - 10));
    }

    // 5. 占领处理（Kotlin applyAIOccupation：winner==ATTACKER && canOccupy）
    if (!(outcome.winner == battle::AiBattleWinner::kAttacker &&
          outcome.canOccupy)) {
        return;
    }
    // 新驻军槽（幸存攻方前 10 名；Kotlin buildGarrSlots）
    auto buildGarrisonSlots = [&]() {
        std::vector<state::GarrisonSlot> slots;
        slots.reserve(static_cast<std::size_t>(kConquestGarrisonSlots));
        for (int32_t i = 0; i < kConquestGarrisonSlots; ++i) {
            const std::size_t idx = static_cast<std::size_t>(i);
            if (idx < outcome.survivingAttackers.size()) {
                slots.push_back(defense_battle::makeGarrisonSlot(
                    i, outcome.survivingAttackers[idx]));
            } else {
                state::GarrisonSlot slot;
                slot.index = i;
                slots.push_back(std::move(slot));
            }
        }
        return slots;
    };
    if (isPlayerOccupied) {
        // 玩家占领被 AI 夺回：归属转移 + isOwned 权威标记清除 + 建筑没收
        //（平台效应草稿——特性注册表/Room 生产槽位保留 Kotlin 残留执行器）
        for (auto& s : gd.worldMapSects) {
            if (s.id != outcome.defenderSectId) continue;
            s.isPlayerOccupied = false;
            s.occupierSectId = outcome.attackerSectId;
            s.garrisonSlots = buildGarrisonSlots();
            break;
        }
        {
            state::SectDetail detail;
            const auto it = gd.sectDetails.find(outcome.defenderSectId);
            if (it != gd.sectDetails.end()) {
                detail = it->second;
            } else {
                detail.sectId = outcome.defenderSectId;
            }
            detail.isOwned = false;
            gd.sectDetails[outcome.defenderSectId] = std::move(detail);
        }
        seizedSectBuildings.push_back(outcome.defenderSectId);
    } else {
        // AI 占领：归属转移 + 攻方池并入守方幸存者 + 守方池清空
        for (auto& s : gd.worldMapSects) {
            if (s.id != outcome.defenderSectId) continue;
            s.occupierSectId = outcome.attackerSectId;
            s.garrisonSlots = buildGarrisonSlots();
            break;
        }
        auto& attackerPool = state.aiSectDisciples[outcome.attackerSectId];
        const auto defenderPoolIt = state.aiSectDisciples.find(outcome.defenderSectId);
        if (defenderPoolIt != state.aiSectDisciples.end()) {
            for (const auto& d : defenderPoolIt->second) {
                if (d.isAlive) attackerPool.push_back(d);
            }
        }
        state.aiSectDisciples[outcome.defenderSectId] = {};
    }
}

// ── 主编排（Kotlin decideAttacks） ─────────────────────────────────

/// AI-vs-AI 征伐决策：攻击者循环 + 首个可攻击目标即停 + results 去重
///（目标已被他人攻击 → 跳过）。**纯决策**——战斗在 Combatant 副本上
/// 执行，不修改状态；调用方收齐全部 results 后统一应用
///（Kotlin decideAttacks 纯决策 + 外层逐条 applyAIAttackResult 两段式
/// 同构：后攻方所见守方池为战前快照）。
inline std::vector<AIAttackOutcome> decideAttacks(GameState& state,
                                                  rng::RngManager& rng) {
    auto& gd = state.gameData;
    std::vector<AIAttackOutcome> results;
    // 驻军信息构建一次（Kotlin buildPlayerDefenseInfo 决策前单次构建）
    const auto playerGarrisons = buildPlayerGarrisonDefenses(state);
    // garrisonMap = 攻击条件判定的守军战力面（Kotlin playerGarrisonMap 同源）
    std::map<std::string, std::vector<Disciple>> garrisonMap;
    for (const auto& [sectId, defense] : playerGarrisons) {
        garrisonMap[sectId] = defense.disciples;
    }

    for (const auto& attacker : gd.worldMapSects) {
        if (attacker.isPlayerSect) continue;
        const auto poolIt = state.aiSectDisciples.find(attacker.id);
        int32_t aliveCount = 0;
        if (poolIt != state.aiSectDisciples.end()) {
            for (const auto& d : poolIt->second) {
                if (d.isAlive) ++aliveCount;
            }
        }
        if (aliveCount < detail::kAiMinDisciplesForAttack) continue;

        for (const auto& defender : gd.worldMapSects) {
            if (defender.id == attacker.id) continue;
            if (defender.occupierSectId == attacker.id) continue;
            // 去重：目标已被攻击（或攻方已攻击——循环结构上不可能，防御性保持）
            bool dup = false;
            for (const auto& r : results) {
                if (r.defenderSectId == defender.id ||
                    r.attackerSectId == attacker.id) {
                    dup = true;
                    break;
                }
            }
            if (dup) continue;
            // 首个可攻击目标即停
            auto outcome = tryResolveAttack(state, attacker, defender,
                                            playerGarrisons, garrisonMap, rng);
            if (outcome.has_value()) {
                results.push_back(std::move(*outcome));
                break;
            }
        }
    }
    return results;
}

}  // namespace conquest

namespace detail {

/// 子事件 6b：AI-vs-AI 征伐环（P2-18 决策项③ Stage 2）。
///
/// 两段式（Kotlin decideAttacks 纯决策 + 外层逐条 applyAIAttackResult）：
/// 决策在战前快照上收齐全部战果 → 统一应用；占领产生的建筑没收 sectId
/// 收集于 seizedSectBuildings（MonthSettlementResult 平台效应草稿）。
/// 定义于本文件（month_settlement.h 文件尾包含，声明在其子事件分发段）。
inline void processAiConquestSubEvent(
        gamecore::state::GameState& state, rng::RngManager& rng,
        std::vector<std::string>& seizedSectBuildings) {
    const auto results = conquest::decideAttacks(state, rng);
    for (const auto& outcome : results) {
        conquest::applyAttackOutcome(state, outcome, seizedSectBuildings);
    }
}

}  // namespace detail

}  // namespace gamecore::system
