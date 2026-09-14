#pragma once

#include <algorithm>
#include <cstdint>
#include <limits>
#include <map>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/data/beast_config.h"
#include "gamecore/system/ai_sect_ops.h"        // aiPrepareDisciplesForBattle（侦察 AI 侧组装）
#include "gamecore/system/battle_execution.h"   // battle::executeBattle
#include "gamecore/system/inventory.h"          // OverflowDraft / OverflowMailCollector
#include "gamecore/system/mission_completion.h" // discipleToCombatant / createBeast（S5 先例）
#include "gamecore/system/secret_realm_session.h" // sr_session::detail 袋物化/熟练度助手复用
#include "gamecore/system/slot_cleanup.h"       // clearAllSlotsDataOnly（分舵驻守全槽清理）

// ============================================================
// exploration_tx.h — 探索域 UI 操作事务（batch-13 下沉）
//
// 承接 Kotlin GameEngineWorldBattleOps / GameEngineScoutOps /
// GameEngineGarrisonOps 的写者语义（判定序、组装口径与 Kotlin 原实现
// 逐字对齐；战斗执行走 S5/S6 已对拍锁定的 battle::executeBattle）：
//   attackWorldLevelTx   世界关卡战斗：校验链 → 战斗组装（pregen/基础值
//                        双分支）→ BATTLE 分区执行 → 伤亡写回（幸存者
//                        HP/MP 钳制回写 + 阵亡袋物化标死）
//   scoutSectTx          宗门侦察：AI 守卫选取（alive ∧ realm 7..9 取前 8）
//                        → PvP 组装（玩家 DEFENDER 现血 / AI ATTACKER 满血，
//                        maxTurns=INT32_MAX）→ 执行 → 伤亡写回
//   assignGarrisonTx     分舵驻守分配：存在/存活校验 → 同宗已驻静默跳过 →
//                        旧 occupant 捕获 → 11 类槽位清理（DataOnly）→
//                        槽位字段写（含灵根色/境界显示名）
//   removeGarrisonTx     分舵驻守移除：occupant 捕获 → 槽位清空（保留索引）
//
// 路线声明（写者审计结论，batch-13 §2.44 登记残差）：
//   C++ 承担「关卡校验 + 战斗执行 + 伤亡写回 + 关卡/槽位状态段」；
//   以下留 Kotlin（S5/S6 口径，双臂同形）：
//   - 遭遇分支：aiBeastEncounterTargets 为 @Transient Kotlin 域字段（不入
//     镜像协议）——Kotlin 臂先行判定并整臂接管
//   - forceSettleDisciplesBeforeBattle（修炼结算域，Kotlin 原样前置执行）
//   - 胜利事务原子块（soulPowers/winBattleRandomAttrPlus/defeated TOCTOU
//     重查；talent effects + lawEnforcement 偷盗判定耦合）——C++ 不写
//     defeated，Kotlin applyWorldLevelVictoryTransaction 原函数执行
//   - 奖励生成（妖兽材料 BeastMaterialDatabase.getRandomMaterialByBeastType
//     硬编码 Random.nextDouble + 洞府三库 generateRandom Random.Default +
//     UUID id——非分区非存档确定性随机域，双臂同形不改抽取集）
//   - processBattleCasualties（悲痛期/卸装/槽位清理编排，C++ 无统一入口；
//     袋已清空幂等 + wasAlive 双计防线，S6 同款事务外重跑）
//   - 战报重建/奖励卡片/gate/Room 生产仓/状态同步（平台效应）
//
// RNG 契约（对拍命门）：
//   - attack/scout 战斗：BATTLE 分区（battle::executeBattle 全部抽取——
//     与 Kotlin BattleSystem.executeBattleWithTimeout 同区同序，S5/S6
//     同款交换；Kotlin 臂以同参数透传 playerDamageModifier）
//   - 战斗组装零抽取：妖兽属性 pregen 分支（beastMaxHp>0，钳制 [1,1e7]）
//     或基础值公式分支（resolveBeastStats 向后兼容，rl=5）均无 ENEMY_GEN
//     消费（Kotlin 同）；convertDiscipleToCombatant/createBattle 组装零抽取
//   - 分舵驻守两事务：零抽取（纯确定性状态变换）
//   - 本头 API 消费的分区恰为 {kBATTLE}（侦察/关卡两入口）或 ∅（驻守两入口）
//
// 弟子实例 id（袋物化 nextItemId 占位）与溢出草稿协议沿用 S6 契约——
// id 为 Kotlin UUID 非确定性镜像字段，对拍 diff 面排除（新生儿 id 契约同族）。
// ============================================================
namespace gamecore::system::exploration_tx {

using gamecore::state::Disciple;
using gamecore::state::GameState;

/// 灵根数 → 颜色（Kotlin SpiritRoot.countColor：1..5 固定色，其余兜底灰；
/// year_settlement.h spiritRootCountColor 同式，本地副本避免引入年结域依赖）
inline std::string spiritRootCountColor(const std::string& spiritRootType) {
    int32_t count = 1;
    if (!spiritRootType.empty()) {
        count = 1 + static_cast<int32_t>(std::count(
            spiritRootType.begin(), spiritRootType.end(), ','));
    }
    switch (count) {
        case 1: return "#E74C3C";
        case 2: return "#F39C12";
        case 3: return "#9B59B6";
        case 4: return "#27AE60";
        default: return "#95A5A6";
    }
}

namespace detail {

/// 熟练度嵌套 map（sr_session::detail::proficienciesByDisciple 同式——
/// S5/S6 对拍锁定口径，mission_completion 注释同款）
inline std::map<std::string, std::map<std::string, gamecore::state::ManualProficiencyData>>
proficienciesByDisciple(const GameState& state) {
    std::map<std::string, std::map<std::string, gamecore::state::ManualProficiencyData>>
        proficiencies;
    for (const auto& [discipleId, list] : state.gameData.manualProficiencies) {
        std::map<std::string, gamecore::state::ManualProficiencyData> byManualId;
        for (const auto& p : list) {
            byManualId[p.manualId] = p;
        }
        proficiencies.emplace(discipleId, std::move(byManualId));
    }
    return proficiencies;
}

/// 妖兽战斗体组装——pregen 分支（Kotlin createBeast → resolveBeastStats
/// preGenStats 臂：钳制 [1,1e7] / critRate=0.05+realm×0.01 / realmLayer 取
/// 关卡预生成层；类型按 TYPES[clamped beastType] 直取——Kotlin getType(index)
/// → createBeast(beastType=name) → TYPES.find{name} 同一解析结果）
inline gamecore::battle::Combatant worldLevelBeastPreGen(
    int32_t index, const gamecore::data::BeastTypeSpec& type,
    const gamecore::state::WorldLevel& level) {
    const int32_t realmIndex = std::min(std::max(level.realm, 0), 9);
    gamecore::battle::Combatant b;
    b.id = "beast_" + std::to_string(index);
    b.name = std::string(type.prefix) + type.name;
    b.side = gamecore::battle::CombatantSide::kAttacker;
    b.hp = std::min(std::max(level.beastMaxHp, 1), 10000000);
    b.maxHp = b.hp;
    b.mp = std::max(level.beastMaxMp, 0);
    b.maxMp = b.mp;
    b.physicalAttack = std::max(level.beastPhysicalAttack, 0);
    b.magicAttack = std::max(level.beastMagicAttack, 0);
    b.physicalDefense = std::max(level.beastPhysicalDefense, 0);
    b.magicDefense = std::max(level.beastMagicDefense, 0);
    b.speed = std::max(level.beastSpeed, 0);
    b.critRate = 0.05 + realmIndex * 0.01;
    b.realm = realmIndex;
    b.realmLayer = level.realmLayer;
    b.element = type.element;
    b.isBeast = true;
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
        s.buffType = sc.buffType;
        s.buffValue = sc.buffValue;
        s.buffDuration = sc.buffDuration;
        s.buffs = sc.buffs;
        s.isAoe = sc.isAoe;
        s.targetScope = sc.targetScope;
        s.shieldPercent = sc.shieldPercent;
        s.turnAdvancePercent = sc.turnAdvancePercent;
        b.skills.push_back(s);
    }
    return b;
}

/// 伤亡写回（Kotlin applyWorldLevelCasualties / applyScoutCasualties 等价）：
/// 幸存者 HP/MP 钳制回写（战斗口径 maxHp/maxMp——Kotlin
/// battleWritebackMaxHpMp 现场装配与战斗装配在同步战斗期间等值，S6 先例）；
/// 阵亡 → 袋物化回仓（tracking source "disciple_death"——Kotlin
/// SOURCE_DISCIPLE_DEATH 同值）+ markDead。死亡原因串不落库（DeathRecord
/// 已删，isAlive/status/deathYears 三字段承载），battle/scout 仅为 Kotlin
/// 侧 markDead cause 校验域标签。
struct CasualtyWriteback {
    std::vector<std::string> survivorIds;
    std::vector<std::string> deadIds;
};

inline CasualtyWriteback writeBackExplorationCasualties(
    GameState& state, const gamecore::battle::BattleResult& result,
    gamecore::system::OverflowMailCollector& overflowMail) {
    CasualtyWriteback out;
    auto& ds = state.disciples;
    const int32_t year = state.gameData.gameYear;
    for (const auto& c : result.team) {
        const auto rowOpt = ds.rowOf(c.id);
        if (!rowOpt.has_value()) continue;
        if (!c.isDead()) {
            ds.currentHps[*rowOpt] = std::min(std::max(c.hp, 0), c.maxHp);
            ds.currentMps[*rowOpt] = std::min(std::max(c.mp, 0), c.maxMp);
            out.survivorIds.push_back(c.id);
        } else {
            sr_session::detail::materializeDiscipleBagAndMarkDead(
                state, c.id, year, overflowMail);
            out.deadIds.push_back(c.id);
        }
    }
    return out;
}

}  // namespace detail

// ── 世界关卡战斗（Kotlin attackWorldLevel 标准分支）──────────────

struct ExplorationBattleOutcome {
    bool ok = false;              // false = 校验失败（信封 failure → Kotlin 回退）
    std::string errorType;        // "NOT_FOUND" / "ALREADY_DEFEATED" / "NO_COMBATANTS"
    std::string message;
    bool victory = false;
    std::vector<std::string> survivorIds;
    std::vector<std::string> deadIds;
    std::map<std::string, int32_t> rewards;   // 战斗奖励段（spiritStones 由 Kotlin 入账）
    gamecore::battle::BattleResult battle;    // 终态 + rounds（战报由 Kotlin 重建）
    // 侦察战 AI 守卫战前展示段（portrait/realmName 展示域不入 C++ 战斗状态，
    // 战报重建需要——仅 scoutSectTx 填充）
    struct DefenderView {
        std::string id;
        std::string realmName;
        int32_t realm = 0;
        int32_t realmLayer = 0;
        int32_t maxHp = 0;
        std::string portraitRes;
    };
    std::vector<DefenderView> defenderViews;
    std::vector<gamecore::system::OverflowDraft> overflowDrafts;
};

/// 战斗校验链前置（attack/scout 共用）：按传入 id 序收集存活弟子（Kotlin
/// validIds.mapNotNull 同序）。空 → NO_COMBATANTS（Kotlin buildWorldLevelBattle
/// null 臂静默返回——Kotlin 臂先行预检，此为篡改档防御）。
inline bool collectCombatDisciples(const GameState& state,
                                   const std::vector<std::string>& memberIds,
                                   std::vector<Disciple>& out) {
    auto& ds = state.disciples;
    for (const auto& id : memberIds) {
        const auto rowOpt = ds.rowOf(id);
        if (!rowOpt.has_value() || ds.isAlive[*rowOpt] != 1) continue;
        out.push_back(ds.materialize(*rowOpt));
    }
    return !out.empty();
}

inline ExplorationBattleOutcome attackWorldLevelTx(
    GameState& state, rng::RngManager& rng, const std::string& levelId,
    const std::vector<std::string>& discipleIds, double playerDamageModifier,
    gamecore::system::OverflowMailCollector& overflowMail) {
    ExplorationBattleOutcome out;
    auto& gd = state.gameData;

    // 校验链（Kotlin 判定序：find → defeated → validIds 非空）
    const gamecore::state::WorldLevel* level = nullptr;
    for (const auto& l : gd.worldLevels) {
        if (l.id == levelId) { level = &l; break; }
    }
    if (level == nullptr) {
        out.errorType = "NOT_FOUND";
        out.message = "关卡不存在";
        return out;
    }
    if (level->defeated) {
        out.errorType = "ALREADY_DEFEATED";
        out.message = "关卡已被击败";
        return out;
    }
    std::vector<Disciple> combatDisciples;
    if (!collectCombatDisciples(state, discipleIds, combatDisciples)) {
        out.errorType = "NO_COMBATANTS";
        out.message = "无可用战斗弟子";
        return out;
    }

    // 战斗组装（Kotlin buildWorldLevelBattle → BattleSystem.createBattle；
    // 组装零 RNG）
    std::map<std::string, gamecore::state::EquipmentInstance> equipmentMap;
    for (const auto& inst : state.equipmentInstances) equipmentMap[inst.id] = inst;
    std::map<std::string, gamecore::state::ManualInstance> manualMap;
    for (const auto& inst : state.manualInstances) manualMap[inst.id] = inst;
    const auto proficiencies = detail::proficienciesByDisciple(state);

    gamecore::battle::BattleState battle;
    battle.maxTurns = gamecore::battle::kMaxTurns;
    for (const auto& d : combatDisciples) {
        const auto brIt = gd.bloodRefinementPctTotals.find(d.id);
        battle.team.push_back(mission_settle::detail::discipleToCombatant(
            d, equipmentMap, manualMap, proficiencies,
            brIt == gd.bloodRefinementPctTotals.end() ? nullptr : &brIt->second));
    }
    // 妖兽阵营：BEAST 关 + 预生成属性 → pregen 分支（零抽取）；BEAST 关无
    // pregen → 基础值公式（resolveBeastStats 向后兼容，rl=5，零抽取）；
    // 洞府/世界关 → beastType=null → getType(0) 基础值（Kotlin 同）。
    // beastLevel 恒在 0..9（关卡生成域约束），calculateBeastRealm 回退分支
    // 不可达（mission_completion createBeastBattle 同口径登记边界）。
    const bool isBeast = level->type == "BEAST";
    const int32_t beastRealm = std::min(std::max(level->realm, 0), 9);
    const int32_t actualCount = std::max(level->count, 1);
    const auto& beastTypes = gamecore::data::beastTypes();
    const int32_t typeIdx = isBeast && level->beastType.has_value()
                                ? std::min(std::max(*level->beastType, 0),
                                           static_cast<int32_t>(beastTypes.size()) - 1)
                                : 0;
    const gamecore::data::BeastTypeSpec& type = beastTypes[static_cast<std::size_t>(typeIdx)];
    for (int32_t i = 1; i <= actualCount; ++i) {
        if (isBeast && level->beastMaxHp > 0) {
            battle.beasts.push_back(detail::worldLevelBeastPreGen(i, type, *level));
        } else {
            battle.beasts.push_back(
                mission_settle::detail::createBeast(beastRealm, i, typeIdx));
        }
    }

    // 战斗执行（BATTLE 分区——对拍命门）
    auto& battleRng = rng.getRng(rng::RngPartition::kBattle);
    out.battle = gamecore::battle::executeBattle(battle, playerDamageModifier,
                                                 battleRng, -1, nullptr);
    out.victory = out.battle.winner == gamecore::battle::BattleWinner::kTeam;
    out.rewards = out.battle.rewards;

    // 伤亡写回（袋物化溢出转邮件草稿）
    const auto written =
        detail::writeBackExplorationCasualties(state, out.battle, overflowMail);
    out.survivorIds = std::move(written.survivorIds);
    out.deadIds = std::move(written.deadIds);
    out.overflowDrafts = overflowMail.takeAll();

    out.ok = true;
    return out;
}

// ── 宗门侦察（Kotlin scoutSect 战斗段）──────────────────────────

inline ExplorationBattleOutcome scoutSectTx(
    GameState& state, rng::RngManager& rng, const std::string& sectId,
    const std::vector<std::string>& memberIds, double playerDamageModifier,
    gamecore::system::OverflowMailCollector& overflowMail) {
    ExplorationBattleOutcome out;
    auto& gd = state.gameData;

    // 校验链（Kotlin 判定序：find sect → 成员非空）
    const gamecore::state::WorldSect* targetSect = nullptr;
    for (const auto& s : gd.worldMapSects) {
        if (s.id == sectId) { targetSect = &s; break; }
    }
    if (targetSect == nullptr) {
        out.errorType = "NOT_FOUND";
        out.message = "宗门不存在";
        return out;
    }
    std::vector<Disciple> combatDisciples;
    if (!collectCombatDisciples(state, memberIds, combatDisciples)) {
        out.errorType = "NO_COMBATANTS";
        out.message = "无可用探查弟子";
        return out;
    }

    // AI 守卫选取（Kotlin：aiSectDisciples[sectId].filter { alive && realm 7..9 }.take(8)
    // ——保序截取，零抽取）
    std::vector<Disciple> aiDefenders;
    const auto sectIt = state.aiSectDisciples.find(sectId);
    if (sectIt != state.aiSectDisciples.end()) {
        for (const auto& d : sectIt->second) {
            if (aiDefenders.size() >= 8) break;
            if (d.isAlive && d.realm >= 7 && d.realm <= 9) {
                aiDefenders.push_back(d);
            }
        }
    }

    // 组装：玩家 DEFENDER 现血（discipleToCombatant 默认侧）+ AI ATTACKER
    // 满血（aiPrepareDisciplesForBattle 模板 id 语义——Kotlin
    // AISectAttackManager.convertToCombatant 同源原语，S6 runAISectBattle 先例）
    std::map<std::string, gamecore::state::EquipmentInstance> equipmentMap;
    for (const auto& inst : state.equipmentInstances) equipmentMap[inst.id] = inst;
    std::map<std::string, gamecore::state::ManualInstance> manualMap;
    for (const auto& inst : state.manualInstances) manualMap[inst.id] = inst;
    const auto proficiencies = detail::proficienciesByDisciple(state);

    gamecore::battle::BattleState battle;
    battle.maxTurns = std::numeric_limits<int32_t>::max();   // Kotlin Int.MAX_VALUE
    for (const auto& d : combatDisciples) {
        const auto brIt = gd.bloodRefinementPctTotals.find(d.id);
        battle.team.push_back(mission_settle::detail::discipleToCombatant(
            d, equipmentMap, manualMap, proficiencies,
            brIt == gd.bloodRefinementPctTotals.end() ? nullptr : &brIt->second));
    }
    const auto prepared = ai_ops::aiPrepareDisciplesForBattle(aiDefenders);
    for (const auto& d : prepared.disciples) {
        static const std::map<std::string, gamecore::state::EquipmentInstance> kEmpty;
        const auto eqIt = prepared.equipmentMapByDisciple.find(d.id);
        const std::map<std::string, gamecore::state::EquipmentInstance>& eq =
            eqIt != prepared.equipmentMapByDisciple.end() ? eqIt->second : kEmpty;
        gamecore::battle::Combatant c = mission_settle::detail::discipleToCombatant(
            d, eq, prepared.manualMap, prepared.proficiencies, nullptr);
        c.hp = c.maxHp;
        c.mp = c.maxMp;
        c.side = gamecore::battle::CombatantSide::kAttacker;
        battle.beasts.push_back(std::move(c));
    }
    for (const auto& d : aiDefenders) {
        ExplorationBattleOutcome::DefenderView v;
        v.id = d.id;
        v.realmName = sr_session::detail::discipleRealmName(d);
        v.realm = d.realm;
        v.realmLayer = d.realmLayer;
        v.maxHp = 0;   // 战斗组装后回填（下方循环）
        v.portraitRes = d.portraitRes;
        out.defenderViews.push_back(std::move(v));
    }
    for (std::size_t i = 0; i < battle.beasts.size() && i < out.defenderViews.size(); ++i) {
        out.defenderViews[i].maxHp = battle.beasts[i].maxHp;
    }

    // 战斗执行（BATTLE 分区——对拍命门）
    auto& battleRng = rng.getRng(rng::RngPartition::kBattle);
    out.battle = gamecore::battle::executeBattle(battle, playerDamageModifier,
                                                 battleRng, -1, nullptr);
    out.victory = out.battle.winner == gamecore::battle::BattleWinner::kTeam;
    out.rewards = out.battle.rewards;

    // 伤亡写回（AI 弟子不落库——Kotlin 侦察路径同样不持久化 AI 伤亡）
    const auto written =
        detail::writeBackExplorationCasualties(state, out.battle, overflowMail);
    out.survivorIds = std::move(written.survivorIds);
    out.deadIds = std::move(written.deadIds);
    out.overflowDrafts = overflowMail.takeAll();

    out.ok = true;
    return out;
}

// ── 分舵驻守（Kotlin assignGarrisonDisciple / removeGarrisonDisciple）──

struct GarrisonAssignOutcome {
    bool ok = false;              // false = 校验失败（Kotlin 回退重执行 require 链）
    std::string errorType;
    std::string message;
    bool written = false;         // false = 静默跳过（Kotlin return@update 同义）
    std::string oldOccupantId;
};

inline GarrisonAssignOutcome assignGarrisonTx(GameState& state,
                                              const std::string& sectId,
                                              int32_t slotIndex,
                                              const std::string& discipleId) {
    GarrisonAssignOutcome out;
    auto& gd = state.gameData;
    auto& ds = state.disciples;

    // 校验链（Kotlin require 序：存在 → 存活；失败信封 → Kotlin 回退臂
    // 重执行 require 抛出同款异常——用户可见文案由 Kotlin 产出）
    const auto rowOpt = ds.rowOf(discipleId);
    if (!rowOpt.has_value()) {
        out.errorType = "DISCIPLE_INVALID";
        out.message = "弟子不存在: " + discipleId;
        return out;
    }
    if (ds.isAlive[*rowOpt] == 0) {
        out.errorType = "DISCIPLE_INVALID";
        out.message = "弟子已死亡: " + discipleId;
        return out;
    }

    // 目标宗门查找（不存在 → 静默跳过）
    std::size_t sectPos = gd.worldMapSects.size();
    for (std::size_t i = 0; i < gd.worldMapSects.size(); ++i) {
        if (gd.worldMapSects[i].id == sectId) { sectPos = i; break; }
    }
    if (sectPos == gd.worldMapSects.size()) {
        out.ok = true;
        out.written = false;
        return out;
    }
    const auto& targetSect = gd.worldMapSects[sectPos];

    // 同宗已驻守 → 静默跳过（Kotlin any { it.discipleId == discipleId }）
    for (const auto& slot : targetSect.garrisonSlots) {
        if (slot.discipleId == discipleId) {
            out.ok = true;
            out.written = false;
            return out;
        }
    }

    // 覆写前捕获目标槽旧 occupant（清理前语义——Kotlin 同序）
    for (const auto& slot : targetSect.garrisonSlots) {
        if (slot.index == slotIndex) {
            out.oldOccupantId = slot.discipleId;
            break;
        }
    }

    // 11 类槽位清理（Kotlin DiscipleSlotCleanup.clearAllSlotsDataOnly——
    // includeResidence=false 默认；纯数据变换，gate 释放留 Kotlin）
    gamecore::system::SlotCleanupInput in;
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
    in.activeMissions = gamecore::system::toMissionLiteList(gd.activeMissions);
    const auto cleaned =
        gamecore::system::clearAllSlotsDataOnly(in, discipleId, false);
    gd.spiritMineSlots = std::move(cleaned.spiritMineSlots);
    gd.librarySlots = std::move(cleaned.librarySlots);
    gd.elderSlots = cleaned.elderSlots;
    gd.residenceSlots = std::move(cleaned.residenceSlots);
    gd.activeBloodRefinements = std::move(cleaned.activeBloodRefinements);
    gd.patrolSlots = std::move(cleaned.patrolSlots);
    gd.warehouseGarrisons = std::move(cleaned.warehouseGarrisons);
    gd.battleTeams = std::move(cleaned.battleTeams);
    gd.productionSlots = std::move(cleaned.productionSlots);
    gd.caveExplorationTeams = std::move(cleaned.caveExplorationTeams);
    gd.activeMissions = gamecore::system::mergeMissionLiteList(
        gd.activeMissions, cleaned.activeMissions);
    gd.worldMapSects = std::move(cleaned.worldMapSects);

    // 槽位字段写（Kotlin aggregate 展示字段口径：name/realmName/
    // spiritRoot.countColor/portraitRes）
    const Disciple d = ds.materialize(*rowOpt);
    for (auto& sect : gd.worldMapSects) {
        if (sect.id != sectId) continue;
        for (auto& slot : sect.garrisonSlots) {
            if (slot.index != slotIndex) continue;
            slot.discipleId = discipleId;
            slot.discipleName = d.name;
            slot.discipleRealm = sr_session::detail::discipleRealmName(d);
            slot.discipleSpiritRootColor = spiritRootCountColor(d.spiritRootType);
            slot.portraitRes = d.portraitRes;
        }
    }
    out.ok = true;
    out.written = true;
    return out;
}

struct GarrisonRemoveOutcome {
    std::string currentDiscipleId;   // 清空前 occupant（"" = 无/宗门不存在）
};

inline GarrisonRemoveOutcome removeGarrisonTx(GameState& state,
                                              const std::string& sectId,
                                              int32_t slotIndex) {
    GarrisonRemoveOutcome out;
    auto& gd = state.gameData;
    // occupant 捕获（Kotlin 从快照读取同序）
    for (const auto& sect : gd.worldMapSects) {
        if (sect.id != sectId) continue;
        for (const auto& slot : sect.garrisonSlots) {
            if (slot.index == slotIndex) {
                out.currentDiscipleId = slot.discipleId;
                break;
            }
        }
        break;
    }
    // 槽位清空（GarrisonSlot(index) 保留索引——默认 spiritRootColor 同 Kotlin）
    for (auto& sect : gd.worldMapSects) {
        if (sect.id != sectId) continue;
        for (auto& slot : sect.garrisonSlots) {
            if (slot.index == slotIndex) {
                gamecore::state::GarrisonSlot cleared;
                cleared.index = slotIndex;
                slot = std::move(cleared);
            }
        }
        break;
    }
    return out;
}

}  // namespace gamecore::system::exploration_tx
