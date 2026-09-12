// ============================================================
// sect_attack_tx.h — 攻宗 UI 操作面确定性写回事务（batch-20b）
//
// ui-read-surface §4.1「aiSectDisciples 段」+「战斗域」写者下沉。
// 语义权威 = GameEngineBattleOps.kt（removeDeadDefenders / grantWarSoulPowers）。
//
// **写者审计结论（batch-20b 第 1 步，见 handover §2.51b）**：
// `attackSect` 的**战斗执行覆盖面已在 C++**——`tryExecuteUnifiedNative`
// 经 `executeAiBattle`（sect_battle.h）、占领判定经
// `sect_attack_decision.h computeCanOccupy`、攻击条件经
// `nativeCheckAttackConditions`；**战斗组装**经
// `mission_completion.h discipleToCombatant`（实例表语义，exploration_tx.h
// 同源先例）。本头只承 **UI 触发面仍 Kotlin 独占的零 RNG 写段**：
//   - removeDeadDefendersTx（1711）：AI 阵亡守军清理（aiSectDisciples 段 +
//     目标宗门驻军槽清空保留索引）
//   - grantWarSoulPowersTx（1712）：胜方存活玩家弟子魂魄 +1
//
// **登记不下沉（RNG 红线 / 协议形状，见 §2.51b）**：
//   - **战利品生成族**（`generateWarRewards` + 六类 `addWar*`）：模板抽取走
//     `templates.random(random)` 且 `random` 形参默认 `kotlin.random.Random`
//     ——`AISectTeamComposer.kt:143/158/171/182/194/208` 六个调用点**均未传
//     random**，实际序列为 `Random.Default` **非游戏分区随机域**，无法在 C++
//     逐位复刻（batch-13 §2 同族判据）。`sectBattleRewardCount` 的
//     `teamComposerRng.nextInt(7)`（BATTLE 分区、可复刻）被夹在该非分区域
//     中间，拆分即产生"半吊子混合态"→ **整族留 Kotlin**。
//   - **occupySectRewards / crushSectRewards**：与 `grantWarRewardsInside`
//     同一 `stateStore.update` 事务（原子性不可拆）；奖励段不可复刻 ⇒ 整段留
//     Kotlin。其结构写段（worldMapSects 占领标记 / recruitList 俘虏 /
//     vassalContracts 清理 / sectDetails.isOwned）随该事务留 Kotlin。
//   - **recordSectBattleRecord**：与 `battleLogs`（Kotlin 显示域，**不入 C++
//     状态**——sect_defense_battle.h:33 同口径）同一事务；拆出 `sectBattleRecords`
//     会产生撕裂事务 ⇒ 不下沉。
//
// RNG 契约：本头**全链零抽取**（签名级无 rng::RngManager& 入参）——由 GTest
// 「全分区 rngStates 快照差分」+「双运行全状态 JSON 逐位一致」双重证明。
//
// 失败零写入：判定链先于写段；失败 → ok=false + errorType + 零状态变更。
// ============================================================
#pragma once

#include <algorithm>
#include <cstdint>
#include <string>
#include <unordered_set>
#include <vector>

#include "gamecore/state/models.h"

namespace gamecore::system::sect_attack_tx {

using gamecore::state::GameState;

// ── 结果信封（失败零写入；failure → Kotlin 回退原路径重执行校验链）──────

struct TxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
};

/// 阵亡守军清理结果：removedFromPool / clearedGarrisonSlots 供回执与断言
struct DefenderCleanupOutcome {
    TxResult base;
    int32_t removedFromPool = 0;
    int32_t clearedGarrisonSlots = 0;
};

/// 魂魄发放结果：granted 为实际 +1 的弟子数
struct SoulPowerOutcome {
    TxResult base;
    int32_t granted = 0;
};

// ── 事务 1：AI 阵亡守军清理（GameEngine.removeDeadDefenders）────────────
//
// Kotlin 语义（GameEngineBattleOps.kt:172）：
//   gameData.aiSectDisciples.mapValues { (sId, d) ->
//       if (sId == defenderPoolSectId) d.filter { it.id !in deadDefenderIds } else d }
//   gameData.worldMapSects.map { sect ->
//       if (sect.id == sectId) sect.copy(garrisonSlots = sect.garrisonSlots.map { slot ->
//           if (slot.discipleId in deadDefenderIds) GarrisonSlot(index = slot.index) else slot })
//       else sect }
//
// **仅目标池宗门过滤**（`defenderPoolSectId`），**仅目标宗门**驻军槽清理；
// 其余宗门/池原样保留（mapValues/map 的 identity 分支）。清空槽保留 index
// （GarrisonSlot(index) 全字段默认 + index），discipleName/discipleRealm/
// portraitRes/色 全部清空——与 slot_cleanup.h 的驻军清理同形。
//
// 零 RNG；映射遍历序与 Kotlin 的 mapValues/map 迭代序一致（both 保序），
// 且全部为覆盖写 → 结果与遍历序无关。
inline DefenderCleanupOutcome removeDeadDefendersTx(
    GameState& state,
    const std::string& sectId,
    const std::string& defenderPoolSectId,
    const std::vector<std::string>& deadDefenderIds) {
    DefenderCleanupOutcome out;
    out.base.ok = true;
    if (deadDefenderIds.empty()) return out;

    const std::unordered_set<std::string> dead(deadDefenderIds.begin(),
                                               deadDefenderIds.end());
    auto& data = state.gameData;

    // 写段 1：AI 弟子池——仅目标池过滤阵亡者（其余池原样）。
    // 注：aiSectDisciples 为 NativeGameState **顶层**段（非 gameData 内）——
    // Kotlin 侧为 @Transient，不入 kotlinx gameData JSON（S-15 独立全量段）。
    const auto poolIt = state.aiSectDisciples.find(defenderPoolSectId);
    if (poolIt != state.aiSectDisciples.end()) {
        std::vector<gamecore::state::Disciple> kept;
        kept.reserve(poolIt->second.size());
        for (const auto& d : poolIt->second) {
            if (dead.count(d.id) != 0) continue;
            kept.push_back(d);
        }
        out.removedFromPool =
            static_cast<int32_t>(poolIt->second.size() - kept.size());
        poolIt->second = std::move(kept);
    }

    // 写段 2：目标宗门驻军槽——阵亡者清空（保留 index）
    for (auto& sect : data.worldMapSects) {
        if (sect.id != sectId) continue;
        for (auto& slot : sect.garrisonSlots) {
            if (dead.count(slot.discipleId) == 0) continue;
            gamecore::state::GarrisonSlot cleared;
            cleared.index = slot.index;
            slot = std::move(cleared);
            ++out.clearedGarrisonSlots;
        }
    }
    return out;
}

// ── 事务 2：胜方存活弟子魂魄 +1（GameEngine.grantWarSoulPowers）─────────
//
// Kotlin 语义（GameEngineBattleOps.kt:292）：
//   discipleTables.ids.filter { it.toString() in sectSurvivorIds && isAlive[it] == 1 }
//       .forEach { id -> soulPowers[id] = soulPowers[id] + 1 }
//
// **行序遍历 + 存活性过滤**（行序与结果无关：逐行独立自增，无跨行依赖、
// 无 RNG、无早退），`sectSurvivorIds` 为 id 字符串集。**非存活弟子不自增**；
// 不在弟子表的 id 静默跳过（Kotlin `ids.filter` 同义）。
inline SoulPowerOutcome grantWarSoulPowersTx(
    GameState& state, const std::vector<std::string>& sectSurvivorIds) {
    SoulPowerOutcome out;
    out.base.ok = true;
    if (sectSurvivorIds.empty()) return out;

    const std::unordered_set<std::string> survivors(sectSurvivorIds.begin(),
                                                    sectSurvivorIds.end());
    auto& ds = state.disciples;
    for (std::size_t row = 0; row < ds.ids.size(); ++row) {
        if (survivors.count(ds.ids[row]) == 0) continue;
        if (row >= ds.isAlive.size() || ds.isAlive[row] == 0) continue;
        if (row >= ds.soulPowers.size()) continue;
        ds.soulPowers[row] += 1;
        ++out.granted;
    }
    return out;
}

}  // namespace gamecore::system::sect_attack_tx
