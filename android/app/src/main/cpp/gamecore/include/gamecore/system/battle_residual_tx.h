#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/death_handler.h"        // kDeadStatusName
#include "gamecore/system/disciple.h"             // realmConfig（境界显示名）
#include "gamecore/system/inventory.h"            // OverflowDraft / OverflowMailCollector
#include "gamecore/system/month_settlement.h"     // judgeSingleTheftCandidate（S2 偷盗钩子复用）
#include "gamecore/system/phase_settlement.h"     // performBreakthrough / isFullHpMp / indexById /
                                                  // computeMaxCultivation / recordGameEvent
#include "gamecore/system/relative_gift.h"        // processGiftsForBreakthrough（突破亲属赠送主入口）
#include "gamecore/system/secret_realm_session.h" // sr_session::detail::materializeDiscipleBagAndMarkDead
#include "gamecore/system/slot_cleanup.h"         // clearElderSlotsCpp（长老槽清理复用）

// ============================================================
// battle_residual_tx.h — 战斗域残差事务（W4-C · w3-06 战斗/探索残差下沉）
//
// 承接 Kotlin 三处稳态写者（判定序与写面逐字对齐，语义权威 = 各 Kotlin 源）：
//
//  ① settleBattleCasualtiesTx（1780 BATTLE_CASUALTY_SETTLE_TX）
//     = CombatService.processBattleCasualties 的**阶段 2 单事务写面**：
//     悲痛期（griefEndYears + 丧亲 lifeEvents 草稿）→ 标死统一入口
//     （袋物化回仓 + 清袋 + markDead 三列 + wasAlive 守卫年死亡计数）→
//     装备/功法/熟练度清理 → 长老/灵矿/藏经阁/生产槽清理 → 幸存者 HP/MP 回写。
//     阶段 1（DeathEvent 事件广播）与阶段 3（Room 生产槽 Repository）为
//     Kotlin 平台域，经信封 markedDeadIds 由 Kotlin native 臂补执行。
//
//  ② worldLevelVictoryTx（1781 WORLD_VICTORY_REWARDS_TX）
//     = GameEngineWorldBattleOps.applyWorldLevelVictoryTransaction 的状态写段：
//     TOCTOU 重查（关卡不存在/已击败 → 成功零写入，对齐 Kotlin return@update）
//     → 幸存者魂力 +1 → winBattleRandomAttrPlus 天赋确定性属性表（17 分支
//     id 散列，0-9 clamp 技能上限/忠诚 100，10-16 战斗属性不 clamp）→
//     分支 9 道德低于阈值时 judgeSingleTheftCandidate（Kotlin
//     processSingleDiscipleTheft 事务内版等价，SYSTEM 分区——month_settlement.h）。
//     🔴 **C++ 不写 defeated**（batch-13 TOCTOU 教训：defeated + battleLogs
//     残差留 Kotlin 臂——`applyWorldLevelVictoryTransaction(skipNativeDomainWrites
//     = true)` 只执行重查 + defeated + 战报落库）。
//
//  ③ battlePresettleTx（1782 BATTLE_PRESETTLE_TX）
//     = CultivationService.forceSettleDisciplesBeforeBattle →
//     DiscipleBreakthroughHandler.processRealtimeBreakthroughs：
//     候选 = **传入队伍 id 集**内（存活 ∧ realm>0 ∧ 修为满 ∧ 满血蓝——
//     phase_settlement::isFullHpMp 含装备/功法/血炼口径，映射由事务入口
//     一次构建传入）→ 逐候选
//     performBreakthrough（自动嗑丹/引导计数/检查点/完成预估/精准写回——
//     phase_settlement 已验证移植）→ 亲属赠送（SYSTEM 分区）→
//     大境界 lifeEvents 草稿 + 消息栏事件。
//     🔴 **抽取集不变红线**：候选迭代 = DiscipleStore 行序 ==
//     Kotlin discipleTables.ids 追加序（_ids 为 append 列表）；非队伍弟子
//     不进入候选 ⇒ 不产生任何 BREAKTHROUGH/SYSTEM 抽取（GTest 以全分区
//     rngStates 快照差分守护）。
//
// lifeEvents 契约（disciple_lifecycle_tx.h 同口径）：lifeEvents 为 Kotlin
// 类体属性（非协议字段），C++ 无该列 ⇒ 丧亲/突破日志以草稿行进信封
// （LifeEventDraft{id, line}），由 Kotlin native 分支回写瞬态列。
//
// 死亡计数边界（death_handler.h 同源）：sr_session 袋物化包装内部 markDead
// 为**无条件计数**（洞府预标记路径语义）；本事务战斗路径存在"1570 已标死
// → 1780 重入"流程（attackWorldLevelNative）⇒ 按 Kotlin
// InventorySystem.materializeDiscipleBagAndMarkDead 的 **wasAlive 守卫**计数：
// wasAlive=true → 包装函数（袋物化 + 列写 + 计数一次）；wasAlive=false →
// 仅三列幂等重写（该态下袋必已空——各战斗臂首达路径均已物化）。
//
// RNG 契约（对拍命门）：①③ 的分区消费恰为 {kBreakthrough, kSystem}
// （仅 ③ 且仅候选触发）或 ∅（①恒零抽取）；② 的分区消费恰为 {kSystem}
// （仅分支 9 偷盗钩子触发，与 Kotlin 事务内偷盗同区同序）。
// GTest 以全分区 rngStates 快照差分 + 双运行全状态 JSON 逐位一致守护。
//
// 失败臂零写入：①③ 无业务失败臂（尽力结算，Kotlin 同）；② 的"关卡不存在/
// 已击败"为**成功零写入**（applied=false，对齐 Kotlin return@update）。
// ============================================================
namespace gamecore::system {
namespace battle_residual_tx {

using gamecore::state::Disciple;
using gamecore::state::DiscipleStore;
using gamecore::state::GameState;

/// 技能属性上限（GameConfig.Disciple.SKILL_MAX——winAttr 本地副本，避免引入
/// factory 域依赖；disciple_factory.h kSkillMax 同值同源）
inline constexpr int32_t kWinAttrSkillMax = 200;
/// 忠诚上限（GameConfig.Disciple.MAX_LOYALTY）
inline constexpr int32_t kWinAttrMaxLoyalty = 100;

/// 悲痛/突破 lifeEvents 草稿（Kotlin 类体属性列的回写载体——Kotlin native
/// 分支按序 append 到 discipleTables.lifeEvents[id]）
struct LifeEventDraft {
    int32_t discipleId = 0;
    std::string line;   // "N岁：因<关系>X离世陷入悲痛，修炼速度降低50%" /
                        // "N岁：突破至<境界名>"
};

// ── ① 战斗伤亡残差（CombatService.processBattleCasualties 阶段 2）──────

struct BattleCasualtyOutcome {
    bool ok = false;    // 本事务无业务失败臂；恒 true（参数防御空集 = 成功零写入）
    std::vector<std::string> markedDeadIds;   // 实际标死的弟子 id（Kotlin DeathEvent 面）
    std::vector<LifeEventDraft> lifeEvents;   // 丧亲日志草稿（每新入悲痛者至多一条）
    std::vector<gamecore::system::OverflowDraft> overflowDrafts;  // 袋物化溢出邮件草稿
};

namespace casualty_detail {

/// 悲痛期传播（Kotlin collectGriefUpdates → DiscipleStatCalculator
/// .applyGriefToRelatives + applyGriefUpdatesToTables 等价）。
/// year_settlement.h applyGriefToRelativesStep 同式（griefEndYear =
/// max(既有, year+1)，新入悲痛者出草稿）——本地副本以裁剪战斗路径口径：
/// 🔴 每个新入悲痛者**至多一条**日志（Kotlin deadDisciples.firstOrNull
/// { areRelatives }——首位相关死者取行序首），与年结"逐死者逐亲属出草稿"
/// 不同（战斗路径单日志），故按行序遍历死者并按 griever 去重。
inline void applyGriefToRelativesBattle(DiscipleStore& ds,
                                        const std::vector<std::size_t>& deadRows,
                                        int32_t currentYear,
                                        std::vector<LifeEventDraft>& drafts) {
    constexpr int32_t kGriefYearNullSentinel = -1;  // DiscipleTables.GRIEF_YEAR_NULL_SENTINEL
    const int32_t griefEndYear = currentYear + 1;
    std::set<std::size_t> drafted;   // 已出草稿的 griever 行（每新入悲痛者至多一条）
    for (std::size_t deadRow : deadRows) {   // 行序 == Kotlin deadDisciples ids 序
        for (std::size_t row = 0; row < ds.size(); ++row) {
            if (row == deadRow) continue;
            if (ds.isAlive[row] != 1) continue;
            // isRelatives：道侣/双亲任一方向（year_settlement 同式；
            // Kotlin areRelatives 对称判定等价）
            const bool related =
                (!ds.partnerIds[row].empty() && ds.partnerIds[row] == ds.ids[deadRow]) ||
                (!ds.partnerIds[deadRow].empty() && ds.partnerIds[deadRow] == ds.ids[row]) ||
                (!ds.parentId1s[row].empty() && ds.parentId1s[row] == ds.ids[deadRow]) ||
                (!ds.parentId2s[row].empty() && ds.parentId2s[row] == ds.ids[deadRow]) ||
                (!ds.parentId1s[deadRow].empty() && ds.parentId1s[deadRow] == ds.ids[row]) ||
                (!ds.parentId2s[deadRow].empty() && ds.parentId2s[deadRow] == ds.ids[row]);
            if (!related) continue;
            const int32_t existing = ds.griefEndYears[row];
            const int32_t newEnd =
                (existing != kGriefYearNullSentinel && existing > griefEndYear)
                    ? existing
                    : griefEndYear;
            ds.griefEndYears[row] = newEnd;
            // 新陷入悲痛（原哨兵）→ 日志草稿（关系文本：道侣/父/母/亲属——
            // Kotlin 第 4 分支"子女"因对称不可达，输出"亲属"同注）
            if (existing == kGriefYearNullSentinel && drafted.insert(row).second) {
                const std::string& deadId = ds.ids[deadRow];
                const char* relationship =
                    (!ds.partnerIds[row].empty() && ds.partnerIds[row] == deadId) ? "道侣"
                    : (!ds.parentId1s[row].empty() && ds.parentId1s[row] == deadId) ? "父/母"
                    : (!ds.parentId2s[row].empty() && ds.parentId2s[row] == ds.ids[deadRow])
                        ? "父/母"
                    : "亲属";
                const auto gid = gamecore::system::settle_util::toIntOrNull(ds.ids[row]);
                if (gid.has_value()) {
                    LifeEventDraft d;
                    d.discipleId = *gid;
                    d.line = std::to_string(ds.ages[row]) + "岁：因" + relationship +
                             ds.names[deadRow] + "离世陷入悲痛，修炼速度降低50%";
                    drafts.push_back(std::move(d));
                }
            }
        }
    }
}

}  // namespace casualty_detail

inline BattleCasualtyOutcome settleBattleCasualtiesTx(
    GameState& state,
    const std::vector<std::string>& deadIds,
    const std::map<std::string, int32_t>& survivorHpMap,
    const std::map<std::string, int32_t>& survivorMpMap,
    bool isOutsideSect,
    gamecore::system::OverflowMailCollector& overflowMail) {
    BattleCasualtyOutcome out;
    auto& ds = state.disciples;
    auto& gd = state.gameData;
    out.ok = true;
    if (deadIds.empty()) return out;   // hasCasualtyEffects 空集 → 成功零写入

    const int32_t battleCurrentYear = gd.gameYear;

    // 收集在册死者行（Kotlin disciplesToKill：id 可解析 ∧ 在册）
    std::vector<std::size_t> deadRows;       // 行序（悲痛日志口径）
    std::set<std::string> deadSet;           // 槽位/物品清理口径（Kotlin deadMemberIds）
    std::set<std::string> proficiencyRemoveIds;
    std::set<std::string> equipIdsToUnequip;
    std::set<std::string> manualIdsToUnlearn;
    for (const auto& id : deadIds) {
        const auto rowOpt = ds.rowOf(id);
        if (!rowOpt.has_value()) continue;   // toIntOrNull/不在册 → 跳过（Kotlin 同）
        deadRows.push_back(*rowOpt);
        deadSet.insert(id);
        proficiencyRemoveIds.insert(id);     // 宗门内/外死亡均移除熟练度（Kotlin 同）
        if (!isOutsideSect) {
            // 宗门内阵亡回收（Kotlin collectInSectItemLoss）：四槽装备 +
            // 袋内装备/功法实例/堆叠 itemId + 弟子已学功法
            if (!ds.weaponIds[*rowOpt].empty()) equipIdsToUnequip.insert(ds.weaponIds[*rowOpt]);
            if (!ds.armorIds[*rowOpt].empty()) equipIdsToUnequip.insert(ds.armorIds[*rowOpt]);
            if (!ds.bootsIds[*rowOpt].empty()) equipIdsToUnequip.insert(ds.bootsIds[*rowOpt]);
            if (!ds.accessoryIds[*rowOpt].empty()) equipIdsToUnequip.insert(ds.accessoryIds[*rowOpt]);
            for (const auto& item : ds.storageBagItems[*rowOpt]) {
                if (item.itemType == "equipment_stack" || item.itemType == "equipment_instance") {
                    equipIdsToUnequip.insert(item.itemId);
                }
                if (item.itemType == "manual_stack" || item.itemType == "manual_instance") {
                    manualIdsToUnlearn.insert(item.itemId);
                }
            }
            for (const auto& mid : ds.manualIds[*rowOpt]) {
                manualIdsToUnlearn.insert(mid);
            }
        }
    }
    if (deadSet.empty()) return out;

    // 幸存者回写预计算（Kotlin computeSurvivorUpdates——**先于**标死读列：
    // 钳制上限经 assemble/stats::getMaxHpMp 含血炼口径；⚠ Kotlin 计算出的
    // updatedStatus（IN_TEAM/GARRISONING→IDLE）从未写回（applySurvivorHpMp
    // Updates 只写 HP/MP）——本事务同口径不写状态列）。
    const auto eqMap = gamecore::system::detail::equipmentMapOf(state.equipmentInstances);
    const auto mnMap = gamecore::system::detail::manualMapOf(state.manualInstances);
    struct SurvivorWrite { std::size_t row; int32_t hp; int32_t mp; };
    std::vector<SurvivorWrite> survivors;
    for (const auto& [memberId, hp] : survivorHpMap) {
        const auto idOpt = gamecore::system::settle_util::toIntOrNull(memberId);
        if (!idOpt.has_value()) continue;
        const auto rowOpt = ds.rowOf(memberId);
        if (!rowOpt.has_value()) continue;
        if (deadSet.count(memberId) > 0) continue;
        Disciple d = ds.materialize(*rowOpt);
        int32_t finalMaxHp = 0;
        int32_t finalMaxMp = 0;
        gamecore::stats::getMaxHpMp(
            d, gamecore::system::detail::findBloodRefinementPct(gd, memberId),
            eqMap, mnMap, gd.manualProficiencies, finalMaxHp, finalMaxMp);
        const auto mpIt = survivorMpMap.find(memberId);
        const int32_t mp = mpIt != survivorMpMap.end()
                               ? mpIt->second
                               : (ds.currentMps[*rowOpt]);
        survivors.push_back({*rowOpt,
                             std::min(std::max(hp, 0), finalMaxHp),
                             std::min(std::max(mp, 0), finalMaxMp)});
    }

    // A. 悲痛期（先于标死——Kotlin applyGriefUpdatesToTables 同序）
    casualty_detail::applyGriefToRelativesBattle(ds, deadRows, battleCurrentYear, out.lifeEvents);

    // B. 标死统一入口（Kotlin materializeDiscipleBagAndMarkDead：袋物化 +
    //    清袋 + markDead 三列 + wasAlive 守卫年死亡计数——见文件头边界注）
    for (const auto& id : deadIds) {
        const auto rowOpt = ds.rowOf(id);
        if (!rowOpt.has_value()) continue;
        const bool wasAlive = ds.isAlive[*rowOpt] == 1;
        if (wasAlive) {
            gamecore::system::sr_session::detail::materializeDiscipleBagAndMarkDead(
                state, id, battleCurrentYear, overflowMail);
        } else {
            // 重入（1570/侦察臂已标死）：三列幂等重写，不重复计数（该态下
            // 袋必已空——各战斗臂首达路径均已物化，文件头边界注）
            ds.isAlive[*rowOpt] = 0;
            ds.statuses[*rowOpt] = gamecore::system::kDeadStatusName;
            ds.deathYears[*rowOpt] = battleCurrentYear;
        }
        out.markedDeadIds.push_back(id);
    }

    // C. 装备/功法/熟练度清理（Kotlin removeCasualtyItems）
    if (!proficiencyRemoveIds.empty()) {
        for (const auto& pid : proficiencyRemoveIds) gd.manualProficiencies.erase(pid);
    }
    if (!equipIdsToUnequip.empty()) {
        state.equipmentInstances.erase(
            std::remove_if(state.equipmentInstances.begin(), state.equipmentInstances.end(),
                           [&](const gamecore::state::EquipmentInstance& e) {
                               return equipIdsToUnequip.count(e.id) > 0;
                           }),
            state.equipmentInstances.end());
    }
    if (!manualIdsToUnlearn.empty()) {
        state.manualInstances.erase(
            std::remove_if(state.manualInstances.begin(), state.manualInstances.end(),
                           [&](const gamecore::state::ManualInstance& m) {
                               return manualIdsToUnlearn.count(m.id) > 0;
                           }),
            state.manualInstances.end());
    }

    // D. 槽位清理（Kotlin computeElderSlotUpdates + spiritMine/library +
    //    productionSlots 镜像清理——战斗路径不含住所/巡逻/驻军等其余槽族）
    for (const auto& id : deadSet) {
        gd.elderSlots = gamecore::system::clearElderSlotsCpp(gd.elderSlots, id);
    }
    for (auto& slot : gd.spiritMineSlots) {
        if (deadSet.count(slot.discipleId) > 0) {
            slot.discipleId.clear();
            slot.discipleName.clear();
        }
    }
    for (auto& slot : gd.librarySlots) {
        if (deadSet.count(slot.discipleId) > 0) {
            slot.discipleId.clear();
            slot.discipleName.clear();
        }
    }
    for (auto& slot : gd.productionSlots) {
        if (slot.assignedDiscipleId.has_value() &&
            deadSet.count(*slot.assignedDiscipleId) > 0) {
            slot.assignedDiscipleId.reset();
            slot.assignedDiscipleName.clear();
        }
    }

    // E. 幸存者 HP/MP 回写（computeSurvivorUpdates 结果；顺序无关，逐 id 独立）
    for (const auto& su : survivors) {
        ds.currentHps[su.row] = su.hp;
        ds.currentMps[su.row] = su.mp;
    }

    out.overflowDrafts = overflowMail.takeAll();
    return out;
}

// ── ② 世界关卡胜利事务（applyWorldLevelVictoryTransaction 状态写段）──────

struct WorldVictoryOutcome {
    bool ok = false;        // 恒 true（重查失败 = 成功零写入 applied=false）
    bool applied = false;   // false = 关卡不存在/已击败（Kotlin return@update）
    int32_t soulPowerCount = 0;   // 魂力 +1 人次
    int32_t winAttrCount = 0;     // 确定性属性增长人次
    std::vector<std::string> theftCandidateIds;  // 分支 9 触发偷盗判定的弟子（日志面）
};

/// winBattleRandomAttrPlus 确定性属性增长（Kotlin applyDeterministicWinAttr
/// 逐分支对齐；须在关卡重查通过后调用）。
/// 确定性散列：r = |(id * 527 + 31) % 17|（id 散列代替随机——读档一致）。
/// 分支 0-9 clamp 技能上限（忠诚 100 例外）；10-16 战斗属性不 clamp；
/// 分支 9 道德低于阈值 → 事务内偷盗判定（judgeSingleTheftCandidate——
/// Kotlin processSingleDiscipleTheft(id, state) 等价，SYSTEM 分区）。
inline void applyDeterministicWinAttr(GameState& state, std::size_t row,
                                      int32_t id, int32_t currentMonth,
                                      rng::DeterministicRng& rngSystem,
                                      ecs::World& world,
                                      WorldVictoryOutcome& out) {
    auto& ds = state.disciples;
    const int32_t raw = (id * 527 + 31) % 17;
    const int32_t r = raw < 0 ? -raw : raw;   // Kotlin .let { if (it < 0) -it else it }
    switch (r) {
        case 0: ds.intelligences[row] = std::min(ds.intelligences[row] + 1, kWinAttrSkillMax); break;
        case 1: ds.comprehensions[row] = std::min(ds.comprehensions[row] + 1, kWinAttrSkillMax); break;
        case 2: ds.charms[row] = std::min(ds.charms[row] + 1, kWinAttrSkillMax); break;
        case 3: ds.loyalties[row] = std::min(ds.loyalties[row] + 1, kWinAttrMaxLoyalty); break;
        case 4: ds.artifactRefinings[row] = std::min(ds.artifactRefinings[row] + 1, kWinAttrSkillMax); break;
        case 5: ds.pillRefinings[row] = std::min(ds.pillRefinings[row] + 1, kWinAttrSkillMax); break;
        case 6: ds.spiritPlantings[row] = std::min(ds.spiritPlantings[row] + 1, kWinAttrSkillMax); break;
        case 7: ds.minings[row] = std::min(ds.minings[row] + 1, kWinAttrSkillMax); break;
        case 8: ds.teachings[row] = std::min(ds.teachings[row] + 1, kWinAttrSkillMax); break;
        case 9: {
            const int32_t newMoral = std::min(ds.moralities[row] + 1, kWinAttrSkillMax);
            ds.moralities[row] = newMoral;
            // 道德变化后即时触发偷盗判定（事务内版本——与 Kotlin 钩子同区
            // SYSTEM 分区；月度上限/年判定等共享状态随判定推进）
            if (newMoral < detail::lawMoralityThreshold()) {
                detail::judgeSingleTheftCandidate(state, id, currentMonth, rngSystem, world);
                out.theftCandidateIds.push_back(ds.ids[row]);
            }
            break;
        }
        case 10: ds.baseHps[row] = ds.baseHps[row] + 1; break;
        case 11: ds.baseMps[row] = ds.baseMps[row] + 1; break;
        case 12: ds.basePhysicalAttacks[row] = ds.basePhysicalAttacks[row] + 1; break;
        case 13: ds.baseMagicAttacks[row] = ds.baseMagicAttacks[row] + 1; break;
        case 14: ds.basePhysicalDefenses[row] = ds.basePhysicalDefenses[row] + 1; break;
        case 15: ds.baseMagicDefenses[row] = ds.baseMagicDefenses[row] + 1; break;
        case 16: ds.baseSpeeds[row] = ds.baseSpeeds[row] + 1; break;
        default: break;   // 0..16 穷举（% 17 非负），防御臂
    }
    ++out.winAttrCount;
}

/// 关卡胜利事务（🔴 不写 defeated——残差留 Kotlin 臂，文件头②注）。
/// @param survivorIds 幸存者 id 集（Kotlin 臂传 stringSet）
inline WorldVictoryOutcome worldLevelVictoryTx(
    GameState& state, rng::RngManager& rng, ecs::World& world,
    const std::string& levelId, const std::vector<std::string>& survivorIds) {
    WorldVictoryOutcome out;
    out.ok = true;
    auto& ds = state.disciples;
    auto& gd = state.gameData;

    // TOCTOU 重查（Kotlin 判定序：find → defeated → return@update）
    const gamecore::state::WorldLevel* level = nullptr;
    for (const auto& l : gd.worldLevels) {
        if (l.id == levelId) { level = &l; break; }
    }
    if (level == nullptr || level->defeated) return out;   // 成功零写入

    const int32_t currentMonth = gd.gameYear * 12 + gd.gameMonth;
    auto& rngSystem = rng.getRng(rng::RngPartition::kSystem);
    const std::set<std::string> survivors(survivorIds.begin(), survivorIds.end());
    // 行序 == Kotlin discipleTables.ids 迭代序（魂力/属性写独立于 id；
    // 偷盗判定的月度/年判定共享状态依赖该序）
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (survivors.count(ds.ids[row]) == 0) continue;
        if (ds.isAlive[row] != 1) continue;
        ds.soulPowers[row] = ds.soulPowers[row] + 1;
        ++out.soulPowerCount;
        // winBattleRandomAttrPlus：**仅查天赋**（Kotlin talentIds.any——
        // 词条不参与；talentEffectsFor 合并映射 containsKey 同义）
        const auto talentEffects = gamecore::stats::talentEffectsFor(ds.talentIds[row]);
        if (talentEffects.count("winBattleRandomAttrPlus") > 0) {
            const auto idOpt = gamecore::system::settle_util::toIntOrNull(ds.ids[row]);
            if (idOpt.has_value()) {
                applyDeterministicWinAttr(state, row, *idOpt, currentMonth, rngSystem, world, out);
            }
        }
    }
    out.applied = true;
    return out;
}

// ── ③ 战前突破结算（forceSettleDisciplesBeforeBattle）────────────────

struct BattlePresettleOutcome {
    bool ok = false;              // 恒 true（空候选 = 成功零写入）
    int32_t candidateCount = 0;   // 实际执行突破的候选数
    std::vector<LifeEventDraft> lifeEvents;  // 大境界突破日志草稿
};

/// 战前结算（限定队伍 id 集——w3-06 ExplorationNativeOps:134 与 w3-07
/// BattleOps:66 同一事务界面）。候选筛选与突破管线 =
/// phase_settlement::processBreakthroughs 的**队伍限定**变体：
/// 🔴 差异点 = 候选域为传入 id 集（月结为全量排除秘境成员）——非队伍弟子
/// 不产生任何抽取（RNG 抽取集不变红线）；其余（行序迭代/performBreakthrough
/// 管线/亲属赠送/日志）与已对拍锁定版本逐位一致。
inline BattlePresettleOutcome battlePresettleTx(
    GameState& state, rng::RngManager& rng,
    const std::vector<std::string>& discipleIds) {
    BattlePresettleOutcome out;
    out.ok = true;
    if (discipleIds.empty()) return out;
    DiscipleStore& ds = state.disciples;
    auto& gd = state.gameData;
    const std::set<std::string> inputSet(discipleIds.begin(), discipleIds.end());

    // 提交视图长老悟性（Kotlin stateStore.disciples.value——事务入口
    // 已提交视图；长老悟性读取源）。R1.2 去物化：同 phase_settlement
    // 入口口径——全量 D 弟子物化快照退役，长老位 ≤2 名 SoA 列直算
    // 入口时点悟性（committedElderComprehensionOf 语义注释见彼处）
    const auto committedElderComprehension =
        gamecore::system::detail::committedElderComprehensionOf(state);
    const auto idx = gamecore::system::settle_util::indexById(ds);

    // 步骤入口：装备/功法映射一次构建（战前突破在旬结算核心批次含孕养提交
    // 之后执行，映射已含当旬最新 nurtureLevel；本事务全程只读两表，
    // 候选筛选与逐候选突破循环共享——phase_settlement::processBreakthroughs
    // 同一模式）
    const auto eqMap = gamecore::system::detail::equipmentMapOf(
        state.equipmentInstances);
    const auto mnMap = gamecore::system::detail::manualMapOf(
        state.manualInstances);

    // 候选筛选（行序 == Kotlin _ids 追加序；存活 ∧ 队伍内 ∧ realm>0 ∧
    // 修为满 ∧ 满血蓝（入口映射共享——battleWritebackMaxHpMp 同源口径））
    std::vector<std::size_t> candidates;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] == 0) continue;
        if (inputSet.count(ds.ids[row]) == 0) continue;
        if (ds.realms[row] <= 0) continue;
        const double maxCult = computeMaxCultivation(
            ds.realms[row], ds.realmLayers[row], ds.cultivations[row]);
        if (ds.cultivations[row] < maxCult) continue;
        if (!gamecore::system::detail::isFullHpMp(ds, row, gd, eqMap, mnMap))
            continue;
        candidates.push_back(row);
    }
    if (candidates.empty()) return out;

    // 候选突破前境界/层数（亲属赠送与日志的比对基准）
    std::map<std::size_t, std::pair<int32_t, int32_t>> before;
    for (std::size_t row : candidates) {
        before[row] = {ds.realms[row], ds.realmLayers[row]};
    }

    // 逐候选突破（顺序 == 行序 → BREAKTHROUGH 抽取序逐位一致）
    for (std::size_t row : candidates) {
        Disciple live = ds.materialize(row);
        gamecore::system::detail::performBreakthrough(
            live, state, idx, committedElderComprehension, eqMap, mnMap, rng);
        ds.upsertDisciple(live);
        ++out.candidateCount;
    }

    // 亲属智能赠送（SYSTEM 分区——境界或层数变化触发，先于日志）+ 大境界
    // 日志草稿 + 消息栏事件（Kotlin notifyBreakthroughChanges 同序）
    auto& rngSystem = rng.getRng(rng::RngPartition::kSystem);
    for (std::size_t row : candidates) {
        const auto& oldVals = before[row];
        const bool realmChanged = oldVals.first != ds.realms[row];
        const bool layerChanged = oldVals.second != ds.realmLayers[row];
        const auto idOpt = gamecore::system::settle_util::toIntOrNull(ds.ids[row]);
        if ((realmChanged || layerChanged) && idOpt.has_value()) {
            gamecore::system::relative_gift::processGiftsForBreakthrough(
                state, *idOpt, rngSystem);
        }
        if (!realmChanged) continue;
        if (idOpt.has_value()) {
            const Disciple after = ds.materialize(row);
            LifeEventDraft d;
            d.discipleId = *idOpt;
            d.line = std::to_string(after.age) + "岁：突破至" +
                     gamecore::disciple::realmConfig(after.realm).name;
            out.lifeEvents.push_back(std::move(d));
            gamecore::system::detail::recordGameEvent(state, after,
                gamecore::disciple::realmConfig(after.realm).name);
        }
    }
    (void)gd;
    return out;
}

}  // namespace battle_residual_tx
}  // namespace gamecore::system
