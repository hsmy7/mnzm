#pragma once

// ============================================================
// sect_defense_battle.h — 子事件 6b：AI 攻玩家决策与防守战
//
// P2-18 决策项③（A 方案）Stage 1：Kotlin 休眠链
//   - PlayerDefenseProcessor.processPlayerDefenseBattles（预警收敛/到期
//     战书内联结算/新攻击决策/驻军填充）
//   - AttackWarningService（createImminentAttackWarning/normalize/
//     addWarningSync）
//   - AISectGarrisonManager.fillEmptyGarrisonSlots
// 的 AUTHORITATIVE 移植复活（月结单事务内新增子事件 6b——休眠的架构
// 原因是 Kotlin 2 参链在 AUTHORITATIVE 单镜像事务外无挂载点）。
//
// 包含契约：本文件**只能在 month_settlement.h 完整定义之后包含**
// （month_settlement.h 文件尾）——决策层 sect_attack_decision.h 依赖
// 本文件先前定义的 kAiMinDisciplesForAttack / favorLevelOrdinal /
// sectPowerOfDisciple，互包不可行。本文件自身仅包含 month_settlement-free 头。
//
// RNG 消耗（BATTLE 分区，月结核对表登记项）：
//   - decidePlayerAttack：每通过六道闸的攻击者恰 1 次 nextDouble
//     （闸前早退不消费——sect_attack_decision.h 契约）
//   - executePlayerDefenseBattle：battle::executeAiBattle 全回合抽取
//
// 与 Kotlin 休眠链的语义差异（登记，均为架构对齐而非行为漂移）：
//   - AI 攻方阵亡：Kotlin 从 aiSectDisciples **移除整行** → C++
//     aiMarkSideDead **标死**（P1-7「死亡不删除」新陈代谢口径，
//     deathYear=当前年进入 3 年尸体保留窗口）
//   - 战前突破（forceSettleDisciplesBeforeBattle）不随移植：C++ 玩家
//     突破唯一入口为旬结算（月结在月末相位边界之后执行，出战弟子
//     修炼状态即最新）；随移植需向月结钩子引入 BREAKTHROUGH 分区消耗
//     （新 RNG 基线）而收益≈0
//   - 战斗日志（Kotlin battleLogs 显示域：头像/境界名）不入 C++ 状态——
//     消息栏经 gameEventRecords（ai_beast_hunt 先例）
//   - warningId：Kotlin UUID → C++ 确定性串（attackerSectId:nowMonth）；
//     UI 消费面为 shownStageKey（attackerSectId+stage），不读 warningId
// ============================================================

#include <algorithm>
#include <cstdint>
#include <map>
#include <set>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/settlement_detail.h"   // recordGameEvent
#include "gamecore/system/death_handler.h"       // markAllDead（死亡统一入口）
#include "gamecore/system/slot_cleanup.h"        // clearAllSlotsDataOnly
#include "gamecore/system/sect_battle.h"         // executeAiBattle（第三战斗引擎）
#include "gamecore/system/ai_sect_ops.h"         // aiPrepareDisciplesForBattle / aiMarkSideDead
#include "gamecore/system/sect_attack_decision.h"  // decidePlayerAttack / aliveDisciplesOf

namespace gamecore::system {
namespace defense_battle {

using gamecore::state::Disciple;
using gamecore::state::GameState;

/// 预警→正式进攻间隔（Kotlin GameConfig.AIAttack.WARNING_BEFORE_ATTACK_MONTHS；
/// 改值须与 Kotlin 同步）
constexpr int32_t kWarningLeadMonths = 1;

/// 攻击冷却（月）：预警生成与战斗结算双写点（Kotlin
/// GameConfig.AIAttack.ATTACK_COOLDOWN_MONTHS 同值——改值须双端同步；
/// Kotlin 原链仅有读点无写点（审计 P2-18 核实结论），本常量为复活新增）
constexpr int32_t kAiAttackCooldownMonths = 12;

/// 战书阶段名（WarningStage.WAR_DECLARATION.name——协议存 name）
constexpr const char* kWarDeclarationStage = "WAR_DECLARATION";

/// 哀悼期空哨兵（DiscipleStore.griefEndYears：-1 = 无哀悼；
/// 与 year_settlement.h kGriefYearNullSentinel 同值——互包不可共享）
constexpr int32_t kGriefEndYearNone = -1;

/// 防守不可出战状态集（Kotlin selectAndPrepareDefenders 排除集——
/// DiscipleStatus.name；巡逻 PATROLLING 不排除=可出战且优先）
inline bool isBattleBusyStatus(const std::string& status) {
    return status == "ON_MISSION" || status == "IN_TEAM" ||
           status == "SECRET_REALM" || status == "WAREHOUSE_GARRISON" ||
           status == "REFLECTING" || status == "GARRISONING" ||
           status == "REFINING";
}

/// 防守选人排序（Kotlin sortedByRealmForDefense：realm 升序、
/// 同境界 realmLayer 降序——layer 降序防止突破后 layer=1 的高境界
/// 弟子被挤出防守队）
inline bool defenseOrderLess(const Disciple& a, const Disciple& b) {
    if (a.realm != b.realm) return a.realm < b.realm;
    return a.realmLayer > b.realmLayer;
}

/// 灵根数 → 颜色（与 year_settlement.h spiritRootCountColor 同式——
/// 互包不可共享，本地同口径副本）
inline std::string spiritRootColorFor(const std::string& spiritRootType) {
    int32_t count = 1;
    if (!spiritRootType.empty()) {
        count = 1 + static_cast<int32_t>(static_cast<std::size_t>(
                     std::count(spiritRootType.begin(), spiritRootType.end(), ',')));
    }
    switch (count) {
        case 1: return "#E74C3C";
        case 2: return "#F39C12";
        case 3: return "#9B59B6";
        case 4: return "#27AE60";
        default: return "#95A5A6";
    }
}

/// 玩家宗门 id（无玩家宗门返回空串——调用方以空串早退）
inline std::string findPlayerSectId(const GameState& state) {
    for (const auto& sect : state.gameData.worldMapSects) {
        if (sect.isPlayerSect) return sect.id;
    }
    return "";
}

/// 玩家宗门名（无玩家宗门回退"玩家宗门"——Kotlin 战报同款兜底）
inline std::string findPlayerSectName(const GameState& state) {
    for (const auto& sect : state.gameData.worldMapSects) {
        if (sect.isPlayerSect) return sect.name;
    }
    return "玩家宗门";
}

/// 驻军槽构建（Kotlin AISectGarrisonManager.createGarrisonSlot——
/// display 域经 disciple 列直读）
inline state::GarrisonSlot makeGarrisonSlot(int32_t index, const Disciple& d) {
    state::GarrisonSlot slot;
    slot.index = index;
    slot.discipleId = d.id;
    slot.discipleName = d.name;
    slot.discipleRealm = realmName(d.realm);
    slot.discipleSpiritRootColor = spiritRootColorFor(d.spiritRootType);
    slot.portraitRes = d.portraitRes;
    return slot;
}

// ── 预警收敛（Kotlin AttackWarningService.normalizeImminentWarningsSync） ──

/// 旧档预警收敛：非战书阶段或 attackMonth 晚于目标（now+1）的预警统一为
/// "战书阶段、下月进攻"（幂等）；**已到期预警保持原值**（本批立即结算，
/// 不推迟一个月）。零 RNG。
inline void normalizeAttackWarnings(GameState& state) {
    auto& gd = state.gameData;
    const int32_t nowMonth = gd.gameYear * 12 + gd.gameMonth;
    const int32_t target = nowMonth + kWarningLeadMonths;
    for (auto& warning : gd.activeAttackWarnings) {
        if (warning.stage != kWarDeclarationStage ||
            warning.attackMonth > target) {
            warning.stage = kWarDeclarationStage;
            warning.attackMonth = target;
        }
    }
}

// ── 驻军填充（Kotlin AISectGarrisonManager.fillEmptyGarrisonSlots） ──

/// 收集占领者在全部被占领宗门中已驻守的弟子 id
inline std::map<std::string, const state::Disciple*> collectGarrisonedIds(
        const GameState& state, const std::string& occupierSectId) {
    std::map<std::string, const state::Disciple*> garrisoned;
    const auto poolIt = state.aiSectDisciples.find(occupierSectId);
    if (poolIt == state.aiSectDisciples.end()) return garrisoned;
    std::map<std::string, const Disciple*> byId;
    for (const auto& d : poolIt->second) byId[d.id] = &d;
    for (const auto& sect : state.gameData.worldMapSects) {
        if (sect.occupierSectId != occupierSectId) continue;
        for (const auto& slot : sect.garrisonSlots) {
            if (slot.discipleId.empty()) continue;
            const auto it = byId.find(slot.discipleId);
            if (it != byId.end()) garrisoned[slot.discipleId] = it->second;
        }
    }
    return garrisoned;
}

/// 月度驻军填充：AI 占领宗门（occupier 为 AI）的空驻军槽由占领者
/// 存活且未驻守弟子按 realm 升序补齐（强者优先）。零 RNG。
/// Kotlin isSlotVacant：槽位 discipleId 空，或指向弟子在占领者池中
/// 不存在/已死亡 → 视为空槽。
inline void fillEmptyGarrisonSlots(GameState& state) {
    auto& gd = state.gameData;
    std::string playerSectId;
    for (const auto& sect : gd.worldMapSects) {
        if (sect.isPlayerSect) { playerSectId = sect.id; break; }
    }
    if (playerSectId.empty()) return;

    // 按占领者分组被占领宗门（排除玩家自身与玩家占领的）
    std::map<std::string, std::vector<const state::WorldSect*>> grouped;
    for (const auto& sect : gd.worldMapSects) {
        if (!sect.isPlayerSect && !sect.occupierSectId.empty() &&
            sect.occupierSectId != playerSectId) {
            grouped[sect.occupierSectId].push_back(&sect);
        }
    }
    if (grouped.empty()) return;

    std::vector<state::WorldSect> updated = gd.worldMapSects;
    for (const auto& [occupierId, occupiedSects] : grouped) {
        const auto poolIt = state.aiSectDisciples.find(occupierId);
        if (poolIt == state.aiSectDisciples.end()) continue;
        // 存活映射 + 可用池（存活、未驻守；realm 升序强者优先）
        std::map<std::string, const Disciple*> aliveMap;
        for (const auto& d : poolIt->second) {
            if (d.isAlive) aliveMap[d.id] = &d;
        }
        if (aliveMap.empty()) continue;   // 空池/无存活（唯一 continue 出口）
        const auto garrisoned = collectGarrisonedIds(state, occupierId);
        std::vector<const Disciple*> pool;
        for (const auto& d : poolIt->second) {
            if (!d.isAlive) continue;
            if (garrisoned.count(d.id) > 0) continue;
            pool.push_back(&d);
        }
        if (pool.empty()) continue;
        std::stable_sort(pool.begin(), pool.end(),
                         [](const Disciple* a, const Disciple* b) {
                             return defenseOrderLess(*a, *b);
                         });
        for (const auto* sect : occupiedSects) {
            for (auto& s : updated) {
                if (s.id != sect->id) continue;
                for (auto& slot : s.garrisonSlots) {
                    if (pool.empty()) break;
                    // 空槽判定（Kotlin isSlotVacant）
                    const bool vacant = slot.discipleId.empty() ||
                                        aliveMap.count(slot.discipleId) == 0;
                    if (!vacant) continue;
                    slot = makeGarrisonSlot(slot.index, *pool.front());
                    pool.erase(pool.begin());
                }
                break;
            }
        }
    }
    gd.worldMapSects = std::move(updated);
}

// ── 防守选人（Kotlin PlayerDefenseProcessor.selectAndPrepareDefenders） ──

/// 防守队构建：巡逻槽弟子优先、其余次之，各按 realm 升序/layer 降序，
/// 取前 kAiTeamSize(10) 名——返回弟子 id（保序）。
inline std::vector<std::string> selectDefenseSquad(const GameState& state) {
    const auto& ds = state.disciples;
    std::vector<Disciple> eligible;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] != 1) continue;
        if (isBattleBusyStatus(ds.statuses[row])) continue;
        eligible.push_back(ds.materialize(row));
    }
    std::map<std::string, int32_t> patrolPids;
    for (const auto& slot : state.gameData.patrolSlots) {
        if (!slot.discipleId.empty()) patrolPids[slot.discipleId] = 1;
    }
    std::vector<Disciple> patrol;
    std::vector<Disciple> remaining;
    for (const auto& d : eligible) {
        (patrolPids.count(d.id) > 0 ? patrol : remaining).push_back(d);
    }
    std::stable_sort(patrol.begin(), patrol.end(), defenseOrderLess);
    std::stable_sort(remaining.begin(), remaining.end(), defenseOrderLess);

    std::vector<std::string> squad;
    squad.reserve(static_cast<std::size_t>(ai_ops::kAiTeamSize));
    for (const auto* list : {&patrol, &remaining}) {
        for (const auto& d : *list) {
            if (static_cast<int32_t>(squad.size()) >= ai_ops::kAiTeamSize) break;
            squad.push_back(d.id);
        }
        if (static_cast<int32_t>(squad.size()) >= ai_ops::kAiTeamSize) break;
    }
    return squad;
}

// ── 战前组装（Kotlin executePlayerAttack / executePlayerSectBattle） ──

/// 玩家侧实例查表三件套（装备/功法/熟练度——Kotlin equipmentMap/manualMap/
/// profMap，构建一次共享）
struct PlayerLoadoutMaps {
    std::map<std::string, state::EquipmentInstance> equipmentMap;
    std::map<std::string, state::ManualInstance> manualMap;
    std::map<std::string, std::map<std::string, state::ManualProficiencyData>>
        proficiencies;
};

inline PlayerLoadoutMaps buildPlayerLoadoutMaps(const GameState& state) {
    PlayerLoadoutMaps out;
    for (const auto& e : state.equipmentInstances) {
        out.equipmentMap.emplace(e.id, e);
    }
    for (const auto& m : state.manualInstances) {
        out.manualMap.emplace(m.id, m);
    }
    // Kotlin manualProficiencies.mapValues { associateBy manualId }；
    // 重复 manualId 后写覆盖——C++ 赋值同口径（mission_completion.h 先例）
    for (const auto& [discipleId, list] : state.gameData.manualProficiencies) {
        std::map<std::string, state::ManualProficiencyData> byManualId;
        for (const auto& p : list) byManualId[p.manualId] = p;
        out.proficiencies.emplace(discipleId, std::move(byManualId));
    }
    return out;
}

// ── 悲痛传播（Kotlin DiscipleStatCalculator.applyGriefToRelatives——
//      year_settlement.h 同式的无草稿版：互包不可共享） ──

/// 双弟子是否亲属（道侣/父母/兄弟姐妹——与 year_settlement.h isRelatives
/// 同式）
inline bool isRelativeOf(const state::DiscipleStore& ds, std::size_t a,
                         std::size_t b) {
    if (!ds.partnerIds[a].empty() && ds.partnerIds[a] == ds.ids[b]) return true;
    if (!ds.parentId1s[a].empty() && ds.parentId1s[a] == ds.ids[b]) return true;
    if (!ds.parentId2s[a].empty() && ds.parentId2s[a] == ds.ids[b]) return true;
    if (!ds.parentId1s[b].empty() && ds.parentId1s[b] == ds.ids[a]) return true;
    if (!ds.parentId2s[b].empty() && ds.parentId2s[b] == ds.ids[a]) return true;
    const std::string& a1 = ds.parentId1s[a];
    const std::string& a2 = ds.parentId2s[a];
    if (a1.empty() && a2.empty()) return false;
    return (!a1.empty() && (a1 == ds.parentId1s[b] || a1 == ds.parentId2s[b])) ||
           (!a2.empty() && (a2 == ds.parentId1s[b] || a2 == ds.parentId2s[b]));
}

/// 哀悼传播（griefEndYear = max(既有, 当前年+1) 列写；只存活亲属。
/// Kotlin 在快照上先跑悲痛再标死——同战双亡的亲属互相进入哀悼，
/// 故本函数必须在 markAllDead **之前**逐死者调用）
inline void propagateGriefToRelatives(state::DiscipleStore& ds,
                                      std::size_t deadRow,
                                      int32_t currentYear) {
    const int32_t griefEndYear = currentYear + 1;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (row == deadRow) continue;
        if (ds.isAlive[row] != 1) continue;
        if (!isRelativeOf(ds, row, deadRow)) continue;
        const int32_t existing = ds.griefEndYears[row];
        const int32_t newEnd = (existing != kGriefEndYearNone && existing > griefEndYear)
                                   ? existing
                                   : griefEndYear;
        ds.griefEndYears[row] = newEnd;
    }
}

// ── 战果应用（Kotlin applyDefenseBattleResult + buildPostBattleGameData） ──

struct DefenseBattleOutcome {
    battle::AiBattleWinner winner = battle::AiBattleWinner::kDraw;
    std::vector<std::string> deadAttackerIds;
    std::vector<std::string> deadDefenderIds;
};

inline void applyDefenseBattleResult(GameState& state,
                                     const state::AttackWarning& warning,
                                     const DefenseBattleOutcome& outcome,
                                     const std::vector<gamecore::battle::Combatant>&
                                         defenderSurvivors,
                                     const PlayerLoadoutMaps& loadout) {
    auto& gd = state.gameData;
    auto& ds = state.disciples;
    const std::string& attackerSectId = warning.attackerSectId;
    const std::string playerSectId = findPlayerSectId(state);
    const int32_t nowMonth = gd.gameYear * 12 + gd.gameMonth;

    // 1. 移除到期战书 + 同步清 shownWarningStageIds 键（P2-9：键 =
    //    attackerSectId:stage，随预警生命周期有界）
    gd.activeAttackWarnings.erase(
        std::remove_if(gd.activeAttackWarnings.begin(),
                       gd.activeAttackWarnings.end(),
                       [&](const state::AttackWarning& w) {
                           return w.warningId == warning.warningId;
                       }),
        gd.activeAttackWarnings.end());
    {
        const std::string stageKey =
            attackerSectId + ":" + warning.stage;
        gd.shownWarningStageIds.erase(
            std::remove(gd.shownWarningStageIds.begin(),
                        gd.shownWarningStageIds.end(), stageKey),
            gd.shownWarningStageIds.end());
    }

    // 2. 冷却写入（写点②：战斗结算——P2-18 核实结论：原链全仓无写点）
    gd.sectAttackCooldowns[attackerSectId] = nowMonth + kAiAttackCooldownMonths;

    // 3. 悲痛传播（先于标死——Kotlin 快照序：双亡亲属互入哀悼）
    for (const auto& deadId : outcome.deadDefenderIds) {
        const auto rowOpt = ds.rowOf(deadId);
        if (rowOpt.has_value()) {
            propagateGriefToRelatives(ds, *rowOpt, gd.gameYear);
        }
    }

    // 4. 玩家侧伤亡（markDead 统一入口：isAlive/status/deathYears +
    //    annualDeceasedDisciples 计数）
    markAllDead(ds, outcome.deadDefenderIds, gd.gameYear,
                gd.annualDeceasedDisciples);

    // 5. 幸存守军 HP/MP 回写（clamp 上限含血炼口径——Kotlin
    //    battleWritebackMaxHpMp = getFinalStats().maxHp/Mp）
    for (const auto& c : defenderSurvivors) {
        const auto rowOpt = ds.rowOf(c.id);
        if (!rowOpt.has_value()) continue;
        const std::size_t row = *rowOpt;
        const Disciple snapshot = ds.materialize(row);
        const auto profIt = loadout.proficiencies.find(c.id);
        static const std::map<std::string, state::ManualProficiencyData> kEmpty;
        const std::map<std::string, state::ManualProficiencyData>& prof =
            profIt == loadout.proficiencies.end() ? kEmpty : profIt->second;
        state::BloodRefinementPctTotal blood;
        const auto brIt = gd.bloodRefinementPctTotals.find(c.id);
        const bool hasBlood = brIt != gd.bloodRefinementPctTotals.end();
        if (hasBlood) blood = brIt->second;
        const auto stats = gamecore::stats::finalStats(
            snapshot, loadout.equipmentMap, loadout.manualMap, prof,
            hasBlood ? &blood : nullptr);
        ds.currentHps[row] = std::min(std::max(c.hp, 0), stats.maxHp);
        ds.currentMps[row] = std::min(std::max(c.mp, 0), stats.maxMp);
    }

    // 6. 阵亡弟子 11 类槽位清理（Kotlin clearAllSlotsState
    //    includeResidence=true——含玩家宗门 garrisonSlots 死者槽清空；
    //    DiscipleAssignmentGate release 保留 Kotlin 平台侧）
    for (const auto& deadId : outcome.deadDefenderIds) {
        SlotCleanupInput in;
        in.spiritMineSlots = gd.spiritMineSlots;
        in.librarySlots = gd.librarySlots;
        in.elderSlots = gd.elderSlots;
        in.residenceSlots = gd.residenceSlots;
        in.activeBloodRefinements = gd.activeBloodRefinements;
        in.patrolSlots = gd.patrolSlots;
        in.warehouseGarrisons = gd.warehouseGarrisons;
        in.battleTeams = gd.battleTeams;
        in.worldMapSects = gd.worldMapSects;
        in.productionSlots = gd.productionSlots;
        in.caveExplorationTeams = gd.caveExplorationTeams;
        in.activeMissions = toMissionLiteList(gd.activeMissions);
        const auto out = clearAllSlotsDataOnly(in, deadId, /*includeResidence=*/true);
        gd.spiritMineSlots = out.spiritMineSlots;
        gd.librarySlots = out.librarySlots;
        gd.elderSlots = out.elderSlots;
        gd.residenceSlots = out.residenceSlots;
        gd.activeBloodRefinements = out.activeBloodRefinements;
        gd.patrolSlots = out.patrolSlots;
        gd.warehouseGarrisons = out.warehouseGarrisons;
        gd.battleTeams = out.battleTeams;
        gd.worldMapSects = out.worldMapSects;
        gd.productionSlots = out.productionSlots;
        gd.caveExplorationTeams = out.caveExplorationTeams;
        gd.activeMissions =
            mergeMissionLiteList(gd.activeMissions, out.activeMissions);
    }

    // 7. AI 攻方伤亡（aiMarkSideDead 标死——P1-7 口径；非 Kotlin 的整行
    //    移除，见文件头差异登记）
    ai_ops::aiMarkSideDead(state, attackerSectId, std::set<std::string>(
        outcome.deadAttackerIds.begin(), outcome.deadAttackerIds.end()));

    // 8. 好感惩罚 -15（Kotlin FavorConfig.MIN_FAVOR=0 / MAX_FAVOR=100 夹取）
    for (auto& r : gd.sectRelations) {
        const bool relevant = (r.sectId1 == attackerSectId &&
                               r.sectId2 == playerSectId) ||
                              (r.sectId1 == playerSectId &&
                               r.sectId2 == attackerSectId);
        if (!relevant) continue;
        r.favor = std::max(0, std::min(100, r.favor - 15));
    }

    // 9. 攻方胜 → 玩家宗门仓库掠夺（40% 灵石 + 每物品 max(1, 40%) 数量；
    //    Kotlin SectWarehouseManager.calculate/applyLootLoss——零 RNG）
    if (outcome.winner == battle::AiBattleWinner::kAttacker) {
        state::SectDetail detail;
        const auto detailIt = gd.sectDetails.find(playerSectId);
        if (detailIt != gd.sectDetails.end()) {
            detail = detailIt->second;
        } else {
            detail.sectId = playerSectId;
        }
        auto& warehouse = detail.warehouse;
        const int64_t lostStones = static_cast<int64_t>(
            static_cast<double>(warehouse.spiritStones) * 0.4);
        warehouse.spiritStones = std::max<int64_t>(
            0, warehouse.spiritStones - std::max<int64_t>(lostStones, 0));
        // Kotlin calculateWarehouseLootLoss 只对 quantity>0 条目计损
        //（40% 向下取整，至少 1）；applyLootLossToWarehouse 对无损失键条目
        // 原样保留——含既有 quantity<=0 条目（逐位对齐，不趁机清理）
        for (auto& item : warehouse.items) {
            if (item.quantity <= 0) continue;
            const int32_t loss = std::max(
                1, static_cast<int32_t>(static_cast<double>(item.quantity) * 0.4));
            item.quantity -= loss;
        }
        gd.sectDetails[playerSectId] = std::move(detail);
    }

    // 10. 消息栏事件（Kotlin battleLogs 为显示域不入 C++——ai_beast_hunt 先例）
    const char* verdict = outcome.winner == battle::AiBattleWinner::kAttacker
                              ? "防守失利"
                              : outcome.winner == battle::AiBattleWinner::kDefender
                                    ? "防守成功"
                                    : "不分胜负";
    settle_util::recordGameEvent(
        state, "SECT", "sect_defense_battle",
        warning.attackerSectName + " 进犯" + findPlayerSectName(state) + "，" +
            verdict);
}

// ── 单场防守战（Kotlin executePlayerDefenseBattle + executePlayerAttack） ──

/// 执行一场到期战书的防守战。攻方不足/守方为空/宗门缺失 → 纯早退
/// 返回 false（预警保留——Kotlin 同款：下月重试，同时 hasWarning 闸
/// 阻止新预警）。战斗经 executeAiBattle（BATTLE 分区）。
inline bool executePlayerDefenseBattle(GameState& state,
                                       const state::AttackWarning& warning,
                                       rng::DeterministicRng& battleRng) {
    auto& gd = state.gameData;

    const state::WorldSect* playerSect = nullptr;
    const state::WorldSect* attackerSect = nullptr;
    for (const auto& sect : gd.worldMapSects) {
        if (sect.isPlayerSect && playerSect == nullptr) playerSect = &sect;
        if (sect.id == warning.attackerSectId && attackerSect == nullptr) {
            attackerSect = &sect;
        }
    }
    if (playerSect == nullptr || attackerSect == nullptr) return false;

    // 攻方：AI 活弟子 realm 升序取 kAiTeamSize(10)（Kotlin executePlayerAttack）
    std::vector<Disciple> attackers =
        detail::aliveDisciplesOf(state.aiSectDisciples, attackerSect->id);
    std::stable_sort(attackers.begin(), attackers.end(),
                     [](const Disciple& a, const Disciple& b) {
                         return a.realm < b.realm;
                     });
    if (static_cast<int32_t>(attackers.size()) > ai_ops::kAiTeamSize) {
        attackers.resize(static_cast<std::size_t>(ai_ops::kAiTeamSize));
    }
    if (static_cast<int32_t>(attackers.size()) <
        detail::kAiMinDisciplesForAttack) {
        return false;
    }

    // 守方选人（巡逻优先）——空队纯早退（Kotlin selectAndPrepareDefenders
    // null → 事务中止，预警保留）
    const std::vector<std::string> defenderIds = selectDefenseSquad(state);
    if (defenderIds.empty()) return false;

    // 组装：攻方 AI 模板语义（满血入战——Kotlin convertToCombatant
    // hp=stats.maxHp）；守方玩家实例语义（当前血量——Kotlin
    // BattleSystem.convertDiscipleToCombatant）
    const auto prepared = ai_ops::aiPrepareDisciplesForBattle(attackers);
    const auto loadout = buildPlayerLoadoutMaps(state);

    std::vector<gamecore::battle::Combatant> attackCombatants;
    attackCombatants.reserve(attackers.size());
    for (const auto& d : prepared.disciples) {
        gamecore::battle::Combatant c =
            mission_settle::detail::discipleToCombatant(
                d, prepared.equipmentMapByDisciple.at(d.id),
                prepared.manualMap, prepared.proficiencies, nullptr);
        c.hp = c.maxHp;
        c.mp = c.maxMp;
        c.side = gamecore::battle::CombatantSide::kAttacker;
        attackCombatants.push_back(std::move(c));
    }
    std::vector<gamecore::battle::Combatant> defendCombatants;
    defendCombatants.reserve(defenderIds.size());
    for (const auto& id : defenderIds) {
        const auto rowOpt = state.disciples.rowOf(id);
        if (!rowOpt.has_value()) continue;
        const Disciple d = state.disciples.materialize(*rowOpt);
        defendCombatants.push_back(mission_settle::detail::discipleToCombatant(
            d, loadout.equipmentMap, loadout.manualMap, loadout.proficiencies,
            nullptr));
    }
    if (defendCombatants.empty()) return false;

    // 战斗（第三战斗引擎；BATTLE 分区；桌面确定性——无超时时钟）
    const gamecore::battle::AiBattleResult result = gamecore::battle::executeAiBattle(
        attackCombatants, defendCombatants, battleRng);

    // 死亡集合（Kotlin executePlayerSectBattle 口径：原始名单 \ 引擎幸存者）
    DefenseBattleOutcome outcome;
    outcome.winner = result.winner;
    for (const auto& d : attackers) {
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

    applyDefenseBattleResult(state, warning, outcome, result.defenders, loadout);
    return true;
}

}  // namespace defense_battle

namespace detail {

/// 子事件 6b：AI 攻玩家决策与防守战（P2-18 决策项③ Stage 1）。
///
/// 内序对齐 Kotlin processPlayerDefenseBattles：①旧档预警收敛 →
/// ②到期战书内联结算 → ③新攻击决策（生成预警 + 冷却写点①）→
/// ④AI 占领宗门驻军填充。定义于本文件（month_settlement.h 文件尾包含，
/// 声明在其子事件分发段的 detail 命名空间）。
inline void processAiPlayerDefenseSubEvent(gamecore::state::GameState& state,
                                           rng::RngManager& rng) {
    auto& gd = state.gameData;
    defense_battle::normalizeAttackWarnings(state);

    // 到期战书逐场结算（按列表序；战斗会增删预警列表——快照上迭代）
    const int32_t nowMonth = gd.gameYear * 12 + gd.gameMonth;
    std::vector<state::AttackWarning> expired;
    for (const auto& w : gd.activeAttackWarnings) {
        if (w.stage == defense_battle::kWarDeclarationStage &&
            nowMonth >= w.attackMonth) {
            expired.push_back(w);
        }
    }
    for (const auto& w : expired) {
        defense_battle::executePlayerDefenseBattle(
            state, w, rng.getRng(rng::RngPartition::kBattle));
    }

    // 新攻击决策 → 生成"即将进攻"预警（下月进攻；冷却写点①）
    const detail::PlayerAttackDecision decision =
        detail::decidePlayerAttack(state, rng);
    if (decision.type == detail::PlayerAttackDecisionType::kGenerateWarning) {
        state::AttackWarning warning;
        warning.warningId = "warn_" + decision.attackerSectId + "_" +
                            std::to_string(nowMonth);
        warning.attackerSectId = decision.attackerSectId;
        warning.attackerSectName = decision.attackerSectName;
        warning.stage = defense_battle::kWarDeclarationStage;
        warning.attackMonth =
            nowMonth + defense_battle::kWarningLeadMonths;
        warning.createdAtMonth = nowMonth;
        gd.activeAttackWarnings.push_back(warning);
        gd.sectAttackCooldowns[decision.attackerSectId] =
            nowMonth + defense_battle::kAiAttackCooldownMonths;
    }

    // AI 占领宗门驻军填充（Kotlin fillEmptyGarrisonSlots；零 RNG）
    defense_battle::fillEmptyGarrisonSlots(state);
}

}  // namespace detail

}  // namespace gamecore::system

// 解析完成标记（month_settlement.h 文件尾据以判定防守符号是否就绪，
// 决定是否包含 sect_conquest.h——详见其文件尾注释）
#define GAMECORE_SYSTEM_SECT_DEFENSE_BATTLE_COMPLETED_
