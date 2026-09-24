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
#include "gamecore/system/death_handler.h"        // markDead（玩家侧战斗败北重伤统一入口）
#include "gamecore/system/disciple.h"             // realmConfig（境界显示名）
#include "gamecore/system/inventory.h"            // OverflowDraft / OverflowMailCollector
#include "gamecore/system/phase_settlement.h"     // performBreakthrough / isFullHpMp / indexById /
                                                  // computeMaxCultivation / recordGameEvent
#include "gamecore/system/relative_gift.h"        // processGiftsForBreakthrough（突破亲属赠送主入口）

// ============================================================
// battle_residual_tx.h — 战斗域残差事务（W4-C · w3-06 战斗/探索残差下沉）
//
// 承接 Kotlin 三处稳态写者（判定序与写面逐字对齐，语义权威 = 各 Kotlin 源）：
//
//  ① settleBattleCasualtiesTx（1780 BATTLE_CASUALTY_SETTLE_TX）
//     = CombatService.processBattleCasualties 的**阶段 2 单事务写面**：
//     标死统一入口（markDead：气血钳到重伤值、isAlive 保持 1）→
//     幸存者 HP/MP 回写。
//     阶段 1（DeathEvent 事件广播）与阶段 3（Room 生产槽 Repository）为
//     Kotlin 平台域，经信封 markedDeadIds 由 Kotlin native 臂补执行。
//
//  ② worldLevelVictoryTx（1781 WORLD_VICTORY_REWARDS_TX）
//     = GameEngineWorldBattleOps.applyWorldLevelVictoryTransaction 的状态写段：
//     TOCTOU 重查（关卡不存在/已击败 → 成功零写入，对齐 Kotlin return@update）。
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
// 类体属性（非协议字段），C++ 无该列 ⇒ 突破日志以草稿行进信封
// （LifeEventDraft{id, line}），由 Kotlin native 分支回写瞬态列。
//
// RNG 契约（对拍命门）：① 恒零抽取；② 恒零抽取；③ 的分区消费恰为
// {kBreakthrough, kSystem}（仅候选触发）。
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

/// 突破 lifeEvents 草稿（Kotlin 类体属性列的回写载体——Kotlin native
/// 分支按序 append 到 discipleTables.lifeEvents[id]）
struct LifeEventDraft {
    int32_t discipleId = 0;
    std::string line;   // "突破至<境界名>"
};

// ── ① 战斗伤亡残差（CombatService.processBattleCasualties 阶段 2）──────

struct BattleCasualtyOutcome {
    bool ok = false;    // 本事务无业务失败臂；恒 true（参数防御空集 = 成功零写入）
    std::vector<std::string> markedDeadIds;   // 实际标为重伤的弟子 id（Kotlin DeathEvent 面）
    std::vector<LifeEventDraft> lifeEvents;   // 突破日志草稿（本事务无填充点，信封契约保留）
    std::vector<gamecore::system::OverflowDraft> overflowDrafts;  // 溢出邮件草稿
};

inline BattleCasualtyOutcome settleBattleCasualtiesTx(
    GameState& state,
    const std::vector<std::string>& deadIds,
    const std::map<std::string, int32_t>& survivorHpMap,
    const std::map<std::string, int32_t>& survivorMpMap,
    gamecore::system::OverflowMailCollector& overflowMail) {
    BattleCasualtyOutcome out;
    auto& ds = state.disciples;
    auto& gd = state.gameData;
    out.ok = true;
    if (deadIds.empty()) return out;   // hasCasualtyEffects 空集 → 成功零写入

    const int32_t battleCurrentYear = gd.gameYear;

    // 收集在册死者行（Kotlin disciplesToKill：id 可解析 ∧ 在册）
    std::set<std::string> deadSet;           // 幸存者排除口径（Kotlin deadMemberIds）
    for (const auto& id : deadIds) {
        const auto rowOpt = ds.rowOf(id);
        if (!rowOpt.has_value()) continue;   // toIntOrNull/不在册 → 跳过（Kotlin 同）
        deadSet.insert(id);
    }
    if (deadSet.empty()) return out;

    // 幸存者回写预计算（Kotlin computeSurvivorUpdates——**先于**重伤写读列：
    // 钳制上限经 assemble/stats::getMaxHpMp 含血炼口径；⚠ Kotlin 计算出的
    // updatedStatus（IN_TEAM/GARRISONING→IDLE）从未写回（applySurvivorHpMp
    // Updates 只写 HP/MP）——本事务同口径不写状态列）。R1.3 第二步：实例
    // 映射 = owner 行索引桶视图（免 id 键全量深拷贝）。
    const auto eqBuckets = gamecore::system::instance_bucket::makeInstanceBuckets(
        ds, state.equipmentInstances);
    const auto mnBuckets = gamecore::system::instance_bucket::makeInstanceBuckets(
        ds, state.manualInstances);
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
            d, *rowOpt,
            gamecore::system::detail::findBloodRefinementPct(gd, memberId),
            eqBuckets, mnBuckets, gd.manualProficiencies, finalMaxHp,
            finalMaxMp);
        const auto mpIt = survivorMpMap.find(memberId);
        const int32_t mp = mpIt != survivorMpMap.end()
                               ? mpIt->second
                               : (ds.currentMps[*rowOpt]);
        survivors.push_back({*rowOpt,
                             std::min(std::max(hp, 0), finalMaxHp),
                             std::min(std::max(mp, 0), finalMaxMp)});
    }

    // A. 玩家战斗败北 → 重伤：HP=1 存活；不清槽位/装备/行囊
    for (const auto& id : deadIds) {
        int32_t unused = 0;
        gamecore::system::markDead(ds, id, battleCurrentYear, unused);
        (void)unused;
        out.markedDeadIds.push_back(id);
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
};

/// 关卡胜利事务（🔴 不写 defeated——残差留 Kotlin 臂，文件头②注）。
/// @param levelId 关卡 id
inline WorldVictoryOutcome worldLevelVictoryTx(
    GameState& state, const std::string& levelId) {
    WorldVictoryOutcome out;
    out.ok = true;
    auto& gd = state.gameData;

    // TOCTOU 重查（Kotlin 判定序：find → defeated → return@update）
    const gamecore::state::WorldLevel* level = nullptr;
    for (const auto& l : gd.worldLevels) {
        if (l.id == levelId) { level = &l; break; }
    }
    if (level == nullptr || level->defeated) return out;   // 成功零写入
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

    // 步骤入口：装备/功法**桶视图**一次构建（战前突破在旬结算核心批次含
    // 孕养提交之后执行，桶已含当旬最新 nurtureLevel；本事务全程只读两表，
    // 候选筛选与逐候选突破循环共享——phase_settlement::processBreakthroughs
    // 同一模式；R1.3 第二步：免 id 键全量深拷贝）
    const auto eqBuckets = gamecore::system::instance_bucket::makeInstanceBuckets(
        ds, state.equipmentInstances);
    const auto mnBuckets = gamecore::system::instance_bucket::makeInstanceBuckets(
        ds, state.manualInstances);

    // 候选筛选（行序 == Kotlin _ids 追加序；存活 ∧ 队伍内 ∧ realm>0 ∧
    // 修为满 ∧ 满血蓝（入口桶共享——battleWritebackMaxHpMp 同源口径））
    std::vector<std::size_t> candidates;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] == 0) continue;
        if (inputSet.count(ds.ids[row]) == 0) continue;
        if (ds.realms[row] <= 0) continue;
        const double maxCult = computeMaxCultivation(
            ds.realms[row], ds.realmLayers[row], ds.cultivations[row]);
        if (ds.cultivations[row] < maxCult) continue;
        if (!gamecore::system::detail::isFullHpMp(ds, row, gd, eqBuckets,
                                                  mnBuckets))
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
            live, state, row, idx, committedElderComprehension, eqBuckets,
            mnBuckets, rng);
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
        const auto idOpt = ds.numericIdAt(row);
        if ((realmChanged || layerChanged) && idOpt.has_value()) {
            gamecore::system::relative_gift::processGiftsForBreakthrough(
                state, *idOpt, rngSystem);
        }
        if (!realmChanged) continue;
        if (idOpt.has_value()) {
            const Disciple after = ds.materialize(row);
            LifeEventDraft d;
            d.discipleId = *idOpt;
            d.line = std::string("突破至") +
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
