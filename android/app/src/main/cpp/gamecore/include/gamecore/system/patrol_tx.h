// ============================================================
// patrol_tx.h — 巡逻 / 住所 / 矿场 / 年俸 UI 操作面事务（batch-12）
//
// ui-read-surface §4.1「巡逻/探索」+「生产（矿场自愈）」+「设置（年俸）」
// UI 操作面写者下沉（语义权威 = GameEngineAtomicAssign.kt /
// GameEnginePatrolOps.kt，判定序与写入序逐字对齐）。ActionId 1550–1559。
//
// 九入口（写者审计实测结论，见 handover §2.43）：
//  1. assignToResidenceAtomic      → 住所分配（释放原 occupant + 跨住所搬迁）
//  2. removeFromResidenceAtomic    → 住所移除
//  3. assignPatrolAtomic           → 巡逻分配（释放原 occupant + 清新弟子其它槽位）
//  4. removePatrolAtomic           → 巡逻移除
//  5. swapPatrolAtomic             → 巡逻交换
//  6. autoAssignPatrolAtomic       → 批量自动分配
//  7. updatePatrolConfig(s)        → 巡视配置覆写（PatrolTowerViewModel:177 活写者）
//  8. updateSpiritMineSlots        → 矿场槽位整表覆写（活 API，防死 API 复活绕过）
//  9. validateAndFixSpiritMineData → 矿场槽位自愈重建（SpiritMineViewModel:104 活写者）
// 10. updateYearlySalary           → 年俸覆写（SettingsDelegate:60 活写者）
// 登记不下沉：updatePatrolSlots（实测生产零调用——死 API，不为其扩协议）。
//
// RNG 契约（对拍命门）：**全链零抽取**——本头所有函数签名均不接受
// rng::RngManager&，无 nextInt/nextDouble 调用面。由 GTest「双运行全状态
// JSON 逐位一致」+「全分区 rngStates 快照差分」双重证明。
//
// 失败零写入：判定链先于全部写段；任一校验失败 → ok=false + errorType
// + 零状态变更。Kotlin 回退臂重执行校验链并产出用户可见文案
// （双实现并行契约，production.h / disciple_tx.h 同模式）。
//
// 事务外残差（保留 Kotlin，不在本头）：DiscipleAssignmentGate 的
// release/confirmAssign、Room 生产槽 Repository 清理
// （clearDiscipleFromProductionRepository）、弟子状态同步
// （syncSingleDiscipleStatus / syncAllDiscipleStatuses）——信封以
// releasedIds / confirmedIds 回传供 Kotlin 执行（pendingReleases 模式）。
// ============================================================
#pragma once

#include <algorithm>
#include <cstdint>
#include <map>
#include <string>
#include <utility>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/disciple.h"     // disciple::realmConfig（境界显示名）
#include "gamecore/system/slot_cleanup.h" // clearAllSlotsDataOnly

namespace gamecore::system::patrol_tx {

using gamecore::state::GameState;
using gamecore::state::PatrolConfig;
using gamecore::state::PatrolSlot;
using gamecore::state::ResidenceSlot;
using gamecore::state::SpiritMineSlot;

// ── 结果信封（失败零写入；failure → Kotlin 回退原路径重执行校验链）──────

/// 事务结果基型：errorType 与 Kotlin AppError.Domain.GameLoop 分型同名
struct TxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
};

/// 分配结果：changed=false 表示幂等无操作（重复分配/空槽无操作）成功返回
struct AssignOutcome {
    TxResult base;
    bool changed = false;
    std::string releasedOccupantId;  // 被覆盖/释放的原 occupant（空串 = 无）
};

/// 移除结果：removedDiscipleId 空串 = 槽位原本为空（无操作成功）
struct RemoveOutcome {
    TxResult base;
    std::string removedDiscipleId;
};

/// 交换结果：fromDiscipleId / toDiscipleId 为交换**前**两侧 occupant（gate 操作用）
struct SwapOutcome {
    TxResult base;
    bool changed = false;
    std::string fromDiscipleId;
    std::string toDiscipleId;
};

/// 批量分配结果：releasedIds / confirmedIds 逐序回传（gate 事务外批量执行）
struct AutoAssignOutcome {
    TxResult base;
    bool changed = false;
    std::vector<std::string> releasedIds;
    std::vector<std::string> confirmedIds;
    std::vector<int32_t> confirmedIndexes;  // 与 confirmedIds 同序（Kotlin 建 SlotRef 用）
};

/// 覆写类结果：changed = 内容是否发生变化
struct OverwriteOutcome {
    TxResult base;
    bool changed = false;
};

/// 矿场自愈结果：alignedCount = sectId 对齐修正计数（Kotlin 日志口径）
struct SpiritMineFixOutcome {
    TxResult base;
    bool changed = false;
    int32_t alignedCount = 0;
};

namespace detail {

/// 矿场建筑显示名（BuildingFeatureBoot.resourceBuildingFeatures 的
/// BuildingType.MINING 条目镜像——key "spirit_mine" / displayName "灵矿场"）。
/// 漂移即 validateAndFixSpiritMineData 认不出矿场（槽位不再重建）——
/// 由 Kotlin 侧 PatrolTxBuildingRegistryGuardTest 守卫锁死。
inline constexpr const char* kMiningBuildingDisplayName = "灵矿场";

/// 单个灵矿场的槽位数（BuildingFeature.kt SlotGroup.SpiritMine.slotsPerInstance）
inline constexpr int32_t kSpiritMineSlotsPerInstance = 3;

/// 弟子存在 + 存活（Kotlin `id in discipleTables.ids` + `isAlive[id] != 0`）
inline bool isDiscipleAlive(const gamecore::state::DiscipleStore& ds,
                            const std::string& discipleId) {
    if (!ds.contains(discipleId)) return false;
    const auto row = ds.rowOf(discipleId);
    return row.has_value() && (*row < ds.isAlive.size()) && ds.isAlive[*row] != 0;
}

/// 展示字段打包（name / realmName / portraitRes——C++ 从 DiscipleStore 直读，
/// 免一次跨语言字段搬运；Disciple.realmName 语义经 disciple::realmConfig 复刻）
struct SlotDisplayFields {
    std::string name;
    std::string realmName;
    std::string portraitRes;
};

inline SlotDisplayFields displayFieldsOf(const gamecore::state::DiscipleStore& ds,
                                         const std::string& discipleId) {
    SlotDisplayFields out;
    const auto row = ds.rowOf(discipleId);
    if (!row.has_value()) return out;
    const std::size_t i = *row;
    if (i < ds.names.size()) out.name = ds.names[i];
    if (i < ds.portraitRes.size()) out.portraitRes = ds.portraitRes[i];
    // Disciple.realmName 计算属性（含 age<5 / realmLayer==0 → "无境界" 特例）
    if (i < ds.realms.size() && i < ds.realmLayers.size() && i < ds.ages.size()) {
        const int32_t realm = ds.realms[i];
        const int32_t layer = ds.realmLayers[i];
        const int32_t age = ds.ages[i];
        if (age < 5 || layer == 0) {
            out.realmName = "无境界";
        } else if (realm == 0) {
            out.realmName = disciple::realmConfig(0).name;
        } else {
            out.realmName =
                disciple::realmConfig(realm).name + std::to_string(layer) + "层";
        }
    }
    return out;
}

/// 11 类槽位清理（clearAllSlotsDataOnly 的 GameState 打包/回写壳；
/// includeResidence=false——工作分配保留住所语义，与 Kotlin
/// DiscipleSlotCleanup.clearAllSlotsDataOnly 默认参一致）
inline void clearAllDiscipleSlots(GameState& state, const std::string& discipleId) {
    SlotCleanupInput in;
    in.spiritMineSlots = state.gameData.spiritMineSlots;
    in.librarySlots = state.gameData.librarySlots;
    in.elderSlots = state.gameData.elderSlots;
    in.residenceSlots = state.gameData.residenceSlots;
    in.activeBloodRefinements = state.gameData.activeBloodRefinements;
    in.patrolSlots = state.gameData.patrolSlots;
    in.warehouseGarrisons = state.gameData.warehouseGarrisons;
    in.battleTeams = state.gameData.battleTeams;
    in.worldMapSects = state.gameData.worldMapSects;
    in.productionSlots = state.gameData.productionSlots;
    in.caveExplorationTeams = state.gameData.caveExplorationTeams;
    // S5 起完整 ActiveMission——清理 op 协议仍为 Lite（month_settlement.h 同壳）
    in.activeMissions = toMissionLiteList(state.gameData.activeMissions);
    const auto out = clearAllSlotsDataOnly(in, discipleId, /*includeResidence=*/false);
    state.gameData.spiritMineSlots = out.spiritMineSlots;
    state.gameData.librarySlots = out.librarySlots;
    state.gameData.elderSlots = out.elderSlots;
    state.gameData.residenceSlots = out.residenceSlots;
    state.gameData.activeBloodRefinements = out.activeBloodRefinements;
    state.gameData.patrolSlots = out.patrolSlots;
    state.gameData.warehouseGarrisons = out.warehouseGarrisons;
    state.gameData.battleTeams = out.battleTeams;
    state.gameData.worldMapSects = out.worldMapSects;
    state.gameData.productionSlots = out.productionSlots;
    state.gameData.caveExplorationTeams = out.caveExplorationTeams;
    state.gameData.activeMissions =
        mergeMissionLiteList(state.gameData.activeMissions, out.activeMissions);
}

/// 住所槽位定位（buildingInstanceId + slotIndex 双键——Kotlin indexOfFirst）
inline int32_t findResidenceSlotIndex(const std::vector<ResidenceSlot>& slots,
                                      const std::string& buildingInstanceId,
                                      int32_t slotIndex) {
    for (std::size_t i = 0; i < slots.size(); ++i) {
        if (slots[i].buildingInstanceId == buildingInstanceId &&
            slots[i].slotIndex == slotIndex) {
            return static_cast<int32_t>(i);
        }
    }
    return -1;
}

}  // namespace detail

// ── 事务 1：住所分配（GameEngine.assignToResidenceAtomic）────────────────
//
// 判定序（逐字对齐 Kotlin）：弟子存在 → 存活 → 建筑存在 → slotIndex>=0
// → 槽位存在。写段：释放目标槽原 occupant（重复分配跳过）→ 清新弟子旧
// 住所槽（跨住所搬迁）→ 写新槽位（name 展示字段）。
inline AssignOutcome assignToResidenceTx(GameState& state,
                                         const std::string& buildingInstanceId,
                                         int32_t slotIndex,
                                         const std::string& discipleId) {
    using namespace detail;
    AssignOutcome out;
    auto& data = state.gameData;

    // 1. 弟子存在 + 存活
    if (!isDiscipleAlive(state.disciples, discipleId)) {
        out.base.errorType = "NotFound";
        out.base.message = "弟子不存在或已死亡: " + discipleId;
        return out;
    }
    // 2. 建筑存在
    const bool buildingExists = std::any_of(
        data.placedBuildings.begin(), data.placedBuildings.end(),
        [&](const gamecore::state::GridBuildingData& b) {
            return b.instanceId == buildingInstanceId;
        });
    if (!buildingExists) {
        out.base.errorType = "NotFound";
        out.base.message = "建筑物不存在: " + buildingInstanceId;
        return out;
    }
    // 3. slotIndex 合法性 + 槽位存在
    if (slotIndex < 0) {
        out.base.errorType = "SlotInvalid";
        out.base.message = "slotIndex 不能为负数: " + std::to_string(slotIndex);
        return out;
    }
    const int32_t slotIdx =
        findResidenceSlotIndex(data.residenceSlots, buildingInstanceId, slotIndex);
    if (slotIdx < 0) {
        out.base.errorType = "SlotInvalid";
        out.base.message = "住所槽位不存在: building=" + buildingInstanceId;
        return out;
    }

    out.base.ok = true;
    auto& slots = data.residenceSlots;
    auto& target = slots[static_cast<std::size_t>(slotIdx)];
    const bool isSameDisciple = target.discipleId == discipleId;

    // 写段 1：释放目标槽原 occupant（重复分配跳过）
    if (!isSameDisciple && !target.discipleId.empty()) {
        out.releasedOccupantId = target.discipleId;
        target.discipleId.clear();
        target.discipleName.clear();
        out.changed = true;
    }

    // 写段 2：跨住所搬迁清理旧槽位（只清旧槽，不改变原 occupant 状态或工作分配）
    if (!isSameDisciple) {
        for (auto& slot : slots) {
            if (slot.discipleId == discipleId) {
                slot.discipleId.clear();
                slot.discipleName.clear();
                out.changed = true;
            }
        }
    }

    // 写段 3：写入新槽位（住所不改弟子状态、不注册门卫、不影响其它槽位）
    if (!isSameDisciple) {
        auto& fresh = slots[static_cast<std::size_t>(slotIdx)];
        fresh.discipleId = discipleId;
        fresh.discipleName = displayFieldsOf(state.disciples, discipleId).name;
        out.changed = true;
    }
    return out;
}

// ── 事务 2：住所移除（GameEngine.removeFromResidenceAtomic）───────────────

inline RemoveOutcome removeFromResidenceTx(GameState& state,
                                           const std::string& buildingInstanceId,
                                           int32_t slotIndex) {
    using namespace detail;
    RemoveOutcome out;
    auto& data = state.gameData;

    if (slotIndex < 0) {
        out.base.errorType = "SlotInvalid";
        out.base.message = "slotIndex 不能为负数: " + std::to_string(slotIndex);
        return out;
    }
    const int32_t slotIdx =
        findResidenceSlotIndex(data.residenceSlots, buildingInstanceId, slotIndex);
    if (slotIdx < 0) {
        out.base.errorType = "SlotInvalid";
        out.base.message = "住所槽位不存在: building=" + buildingInstanceId;
        return out;
    }
    out.base.ok = true;

    auto& slot = data.residenceSlots[static_cast<std::size_t>(slotIdx)];
    if (slot.discipleId.empty()) return out;  // 已为空槽位：无操作成功

    out.removedDiscipleId = slot.discipleId;
    slot.discipleId.clear();
    slot.discipleName.clear();
    return out;
}

// ── 事务 3：巡逻分配（GameEngine.assignPatrolAtomic）─────────────────────
//
// 判定序：弟子存在 → 存活 → 槽位越界。写段：释放目标槽原 occupant
// （**保留 buildingInstanceId**）→ 清新弟子其它槽位（clearAllSlotsDataOnly）
// → 写新槽（name/realmName/portraitRes 展示字段）。
inline AssignOutcome assignPatrolTx(GameState& state,
                                    const std::string& discipleId,
                                    int32_t globalIndex) {
    using namespace detail;
    AssignOutcome out;
    auto& data = state.gameData;

    if (!isDiscipleAlive(state.disciples, discipleId)) {
        out.base.errorType = "NotFound";
        out.base.message = "弟子不存在或已死亡: " + discipleId;
        return out;
    }
    if (globalIndex < 0 ||
        static_cast<std::size_t>(globalIndex) >= data.patrolSlots.size()) {
        out.base.errorType = "SlotInvalid";
        out.base.message = "巡视槽位越界: index=" + std::to_string(globalIndex) +
                           " size=" + std::to_string(data.patrolSlots.size());
        return out;
    }
    out.base.ok = true;

    const std::size_t idx = static_cast<std::size_t>(globalIndex);
    const std::string buildingInstanceId = data.patrolSlots[idx].buildingInstanceId;
    const bool isSameDisciple = data.patrolSlots[idx].discipleId == discipleId;

    // 写段 1：释放目标槽原 occupant（仅清巡逻槽位；gate.release 在事务外执行）
    if (!isSameDisciple && !data.patrolSlots[idx].discipleId.empty()) {
        out.releasedOccupantId = data.patrolSlots[idx].discipleId;
        PatrolSlot fresh;
        fresh.index = globalIndex;
        fresh.buildingInstanceId = buildingInstanceId;
        data.patrolSlots[idx] = std::move(fresh);
        out.changed = true;
    }

    if (!isSameDisciple) {
        // 写段 2：清理新弟子的旧槽位（不含 gate 操作——事务外执行 release）
        clearAllDiscipleSlots(state, discipleId);

        // 写段 3：写入新槽位（不含 gate 操作——事务外执行 confirmAssign）
        const auto display = displayFieldsOf(state.disciples, discipleId);
        PatrolSlot fresh;
        fresh.index = globalIndex;
        fresh.discipleId = discipleId;
        fresh.discipleName = display.name;
        fresh.discipleRealm = display.realmName;
        fresh.portraitRes = display.portraitRes;
        fresh.buildingInstanceId = buildingInstanceId;
        data.patrolSlots[idx] = std::move(fresh);
        out.changed = true;
    }
    return out;
}

// ── 事务 4：巡逻移除（GameEngine.removePatrolAtomic）──────────────────────

inline RemoveOutcome removePatrolTx(GameState& state, int32_t globalIndex) {
    RemoveOutcome out;
    auto& data = state.gameData;

    if (globalIndex < 0 ||
        static_cast<std::size_t>(globalIndex) >= data.patrolSlots.size()) {
        out.base.errorType = "SlotInvalid";
        out.base.message = "巡视槽位越界: index=" + std::to_string(globalIndex) +
                           " size=" + std::to_string(data.patrolSlots.size());
        return out;
    }
    out.base.ok = true;

    const std::size_t idx = static_cast<std::size_t>(globalIndex);
    if (data.patrolSlots[idx].discipleId.empty()) return out;  // 已为空槽位：无操作

    out.removedDiscipleId = data.patrolSlots[idx].discipleId;
    PatrolSlot fresh;
    fresh.index = globalIndex;
    fresh.buildingInstanceId = data.patrolSlots[idx].buildingInstanceId;
    data.patrolSlots[idx] = std::move(fresh);
    return out;
}

// ── 事务 5：巡逻交换（GameEngine.swapPatrolAtomic）───────────────────────
//
// 两者均为空无操作；一方为空相当于移动。两侧 buildingInstanceId 各自保留
// 原槽值（Kotlin fromSlot/toSlot.buildingInstanceId 取法）。
inline SwapOutcome swapPatrolTx(GameState& state, int32_t fromGlobalIndex,
                                int32_t toGlobalIndex) {
    using namespace detail;
    SwapOutcome out;
    auto& data = state.gameData;
    const auto size = data.patrolSlots.size();

    if (fromGlobalIndex < 0 || static_cast<std::size_t>(fromGlobalIndex) >= size) {
        out.base.errorType = "SlotInvalid";
        out.base.message = "来源槽位越界: from=" + std::to_string(fromGlobalIndex);
        return out;
    }
    if (toGlobalIndex < 0 || static_cast<std::size_t>(toGlobalIndex) >= size) {
        out.base.errorType = "SlotInvalid";
        out.base.message = "目标槽位越界: to=" + std::to_string(toGlobalIndex);
        return out;
    }
    out.base.ok = true;
    if (fromGlobalIndex == toGlobalIndex) return out;  // 同索引无操作

    const std::size_t fi = static_cast<std::size_t>(fromGlobalIndex);
    const std::size_t ti = static_cast<std::size_t>(toGlobalIndex);
    const std::string fromBuilding = data.patrolSlots[fi].buildingInstanceId;
    const std::string toBuilding = data.patrolSlots[ti].buildingInstanceId;
    out.fromDiscipleId = data.patrolSlots[fi].discipleId;
    out.toDiscipleId = data.patrolSlots[ti].discipleId;

    // 写段 1：仅清理游戏数据槽位引用（gate 操作在事务外执行）
    if (!out.fromDiscipleId.empty()) clearAllDiscipleSlots(state, out.fromDiscipleId);
    if (!out.toDiscipleId.empty()) clearAllDiscipleSlots(state, out.toDiscipleId);

    // 写段 2：互换 occupant（展示字段经弟子表重建）
    const auto fromDisplay = out.fromDiscipleId.empty()
                                 ? SlotDisplayFields{}
                                 : displayFieldsOf(state.disciples, out.fromDiscipleId);
    const auto toDisplay = out.toDiscipleId.empty()
                               ? SlotDisplayFields{}
                               : displayFieldsOf(state.disciples, out.toDiscipleId);

    PatrolSlot fromFresh;
    fromFresh.index = fromGlobalIndex;
    fromFresh.buildingInstanceId = fromBuilding;
    if (!out.toDiscipleId.empty()) {
        fromFresh.discipleId = out.toDiscipleId;
        fromFresh.discipleName = toDisplay.name;
        fromFresh.discipleRealm = toDisplay.realmName;
        fromFresh.portraitRes = toDisplay.portraitRes;
    }
    data.patrolSlots[fi] = std::move(fromFresh);

    PatrolSlot toFresh;
    toFresh.index = toGlobalIndex;
    toFresh.buildingInstanceId = toBuilding;
    if (!out.fromDiscipleId.empty()) {
        toFresh.discipleId = out.fromDiscipleId;
        toFresh.discipleName = fromDisplay.name;
        toFresh.discipleRealm = fromDisplay.realmName;
        toFresh.portraitRes = fromDisplay.portraitRes;
    }
    data.patrolSlots[ti] = std::move(toFresh);

    out.changed = true;
    return out;
}

// ── 事务 6：批量自动分配（GameEngine.autoAssignPatrolAtomic）─────────────
//
// 判定序（逐字对齐 Kotlin）：事务外前置校验（重复槽索引 / 同弟子多槽）→
// 锁内全量预检（槽位边界 + 弟子存在性 + 存活）→ 逐槽处理。
//
// **顺序敏感性**（RNG 无关但语义关键）：Kotlin 逐槽就地处理时，每槽
// `clearAllSlotsDataOnly` 会清掉该弟子在**本批早先槽位**的写入——批次内
// 同一终态由"先构建最终列表，再整体覆盖"等价达成（终态逐位一致，且
// 避免中间态）。buildingInstanceId 一律取**批首快照**（Kotlin `current`
// 快照同源）。**稀疏槽位的 index 字段一律保留批首值**（Kotlin 仅在清空
// 分支显式重建 index，且该分支的 index 与批首同值）。
inline AutoAssignOutcome autoAssignPatrolTx(
    GameState& state,
    const std::vector<std::pair<int32_t, std::string>>& assignments) {
    using namespace detail;
    AutoAssignOutcome out;
    auto& data = state.gameData;

    // 前置校验 1：重复槽位索引（Kotlin validateAutoAssignPatrol 事务外前置）
    for (std::size_t i = 0; i < assignments.size(); ++i) {
        for (std::size_t j = i + 1; j < assignments.size(); ++j) {
            if (assignments[i].first == assignments[j].first) {
                out.base.errorType = "SlotInvalid";
                out.base.message = "重复的槽位索引: " + std::to_string(assignments[i].first);
                return out;
            }
        }
    }
    // 前置校验 2：同一弟子分配到多个槽位（空 id 不参与）
    for (std::size_t i = 0; i < assignments.size(); ++i) {
        if (assignments[i].second.empty()) continue;
        for (std::size_t j = i + 1; j < assignments.size(); ++j) {
            if (assignments[j].second.empty()) continue;
            if (assignments[i].second == assignments[j].second) {
                out.base.errorType = "SlotInvalid";
                out.base.message =
                    "同一弟子分配到多个槽位: " + assignments[i].second;
                return out;
            }
        }
    }
    // 锁内预检 3：槽位边界 + 弟子存在性 + 存活（全量先行——失败零写入）
    for (const auto& [globalIndex, did] : assignments) {
        if (globalIndex < 0 ||
            static_cast<std::size_t>(globalIndex) >= data.patrolSlots.size()) {
            out.base.errorType = "SlotInvalid";
            out.base.message = "巡视槽位越界: index=" + std::to_string(globalIndex) +
                               " size=" + std::to_string(data.patrolSlots.size());
            return out;
        }
        if (!did.empty() && !isDiscipleAlive(state.disciples, did)) {
            out.base.errorType = "NotFound";
            out.base.message = "弟子不存在或已死亡: " + did;
            return out;
        }
    }

    out.base.ok = true;
    // 批首快照（buildingInstanceId 取法同 Kotlin `current`）
    const std::vector<PatrolSlot> snapshot = data.patrolSlots;

    // 写段 1：清空被指派弟子的全部槽位（11 类；含批首快照中的巡逻槽位）。
    // releasedIds 逐槽按 assignments 序、非空 id、**不去重**（Kotlin
    // pendingReleases 原始序；`distinct()` 由 Kotlin 回执侧执行）。
    for (const auto& [globalIndex, did] : assignments) {
        (void)globalIndex;
        if (did.empty()) continue;
        out.releasedIds.push_back(did);
        clearAllDiscipleSlots(state, did);
    }
    // 写段 2：逐槽处理（**顺序敏感**——按 assignments 序；必须在写段 1 之后，
    // 与 Kotlin 同序。被指派弟子的槽位引用已在写段 1 被清空，故 isSame 判定
    // 读**批首快照** occupant——与 Kotlin 首次进入该槽时的 `current` 等价，
    // 不受写段 1 已清空的干扰）。
    for (const auto& [globalIndex, did] : assignments) {
        const std::size_t idx = static_cast<std::size_t>(globalIndex);
        if (did.empty()) {
            // 清空槽位（原 occupant 的 gate.release 在此登记——快照读）
            if (!snapshot[idx].discipleId.empty()) {
                out.releasedIds.push_back(snapshot[idx].discipleId);
            }
            PatrolSlot fresh;
            fresh.index = globalIndex;
            fresh.buildingInstanceId = snapshot[idx].buildingInstanceId;
            data.patrolSlots[idx] = std::move(fresh);
            out.changed = true;
            continue;
        }
        const bool isSame = snapshot[idx].discipleId == did;
        // 释放原 occupant（仅清空巡逻槽位数据；写段 1 未覆盖该路径）
        if (!isSame && !snapshot[idx].discipleId.empty()) {
            out.releasedIds.push_back(snapshot[idx].discipleId);
        }

        PatrolSlot fresh;
        fresh.index = globalIndex;
        fresh.buildingInstanceId = snapshot[idx].buildingInstanceId;
        if (!isSame) {
            const auto display = displayFieldsOf(state.disciples, did);
            fresh.discipleId = did;
            fresh.discipleName = display.name;
            fresh.discipleRealm = display.realmName;
            fresh.portraitRes = display.portraitRes;
            out.confirmedIds.push_back(did);
            out.confirmedIndexes.push_back(globalIndex);
            out.changed = true;
        } else {
            // 同一弟子重复分配：写回批首快照值（Kotlin `if (!isSame)` 双守卫
            // 等价——槽位内容不变，仅保证写段 1 的清理被复原）
            fresh.discipleId = snapshot[idx].discipleId;
            fresh.discipleName = snapshot[idx].discipleName;
            fresh.discipleRealm = snapshot[idx].discipleRealm;
            fresh.portraitRes = snapshot[idx].portraitRes;
        }
        data.patrolSlots[idx] = std::move(fresh);
    }
    return out;
}

// ── 事务 7：巡视配置整表覆写（GameEngine.updatePatrolConfigs）──────────────
//
// Kotlin 活写者 PatrolTowerViewModel.updatePatrolConfig 的契约：读 patrolConfigs
// → 补足到 towerIndex 的默认 PatrolConfig → 就地覆写 → **整表写回**
// （`gameEngine.updatePatrolConfigs(configs)`）。补位由 Kotlin 构表时完成，
// 本事务承"整表覆写"一段——与 Kotlin 原覆写语义逐字等价。
inline OverwriteOutcome updatePatrolConfigsTx(GameState& state,
                                              std::vector<PatrolConfig> configs) {
    OverwriteOutcome out;
    out.base.ok = true;
    out.changed = (state.gameData.patrolConfigs != configs);
    state.gameData.patrolConfigs = std::move(configs);
    return out;
}

// ── 事务 8：矿场槽位整表覆写（GameEngine.updateSpiritMineSlots）──────────

inline OverwriteOutcome updateSpiritMineSlotsTx(GameState& state,
                                                std::vector<SpiritMineSlot> slots) {
    OverwriteOutcome out;
    out.base.ok = true;
    out.changed = (state.gameData.spiritMineSlots != slots);
    state.gameData.spiritMineSlots = std::move(slots);
    return out;
}

// ── 事务 9：矿场槽位自愈（GameEngine.validateAndFixSpiritMineData）───────
//
// 逐字对齐 Kotlin：按 placedBuildings 中的灵矿场（displayName 匹配
// BuildingFeatureBoot 的 MINING 条目）顺序，每矿重建 3 槽——
// ①孤儿弟子引用清空（existing.discipleId 非空且不在弟子表）
// ②index 重排为重建序 ③buildingInstanceId 重锚到所属矿场
// ④sectId 对齐矿场 sectId（失配计数 → 日志）。
// 前 3 项不改变槽位数（重建序 == 原序），故 updated 与 original 的
// 分叉仅为字段差异；changed 判定按整体相等性（Kotlin `finalSlots != data.spiritMineSlots`）。
inline SpiritMineFixOutcome validateAndFixSpiritMineDataTx(GameState& state) {
    using namespace detail;
    SpiritMineFixOutcome out;
    auto& data = state.gameData;
    out.base.ok = true;

    const std::vector<SpiritMineSlot> original = data.spiritMineSlots;
    std::vector<SpiritMineSlot> rebuilt;
    int32_t slotIdx = 0;
    int32_t alignedCount = 0;

    for (const auto& building : data.placedBuildings) {
        if (building.displayName != kMiningBuildingDisplayName) continue;
        for (int32_t offset = 0; offset < kSpiritMineSlotsPerInstance; ++offset) {
            const int32_t newIndex = static_cast<int32_t>(rebuilt.size());
            const std::size_t cursor = static_cast<std::size_t>(slotIdx + offset);
            const SpiritMineSlot* existing =
                (cursor < original.size()) ? &original[cursor] : nullptr;

            SpiritMineSlot slot;
            if (existing != nullptr) {
                slot = *existing;
                // ①孤儿引用清空（Kotlin: discipleId 非空且不在 discipleMap）
                if (!slot.discipleId.empty() &&
                    !state.disciples.contains(slot.discipleId)) {
                    slot.discipleId.clear();
                    slot.discipleName.clear();
                }
                // ②index 重排 + ③buildingInstanceId 重锚
                slot.index = newIndex;
                slot.buildingInstanceId = building.instanceId;
            } else {
                slot.index = newIndex;
                slot.sectId = building.sectId;
                slot.buildingInstanceId = building.instanceId;
            }
            // ④sectId 对齐矿场（失配计数）
            if (slot.sectId != building.sectId) {
                slot.sectId = building.sectId;
                ++alignedCount;
            }
            rebuilt.push_back(std::move(slot));
        }
        slotIdx += kSpiritMineSlotsPerInstance;
    }

    out.alignedCount = alignedCount;
    out.changed = (rebuilt != original);
    if (out.changed) data.spiritMineSlots = std::move(rebuilt);
    return out;
}

// ── 事务 10：年俸覆写（GameEngine.updateYearlySalary）───────────────────

inline OverwriteOutcome updateYearlySalaryTx(
    GameState& state, std::map<int32_t, int32_t> salary) {
    OverwriteOutcome out;
    out.base.ok = true;
    out.changed = (state.gameData.yearlySalary != salary);
    state.gameData.yearlySalary = std::move(salary);
    return out;
}

}  // namespace gamecore::system::patrol_tx
