// ============================================================
// building_residual_tx.h — 建筑放置/拆除的槽位残差事务（W4-A·w3-09）
//
// 根因消除（ADR reverse-channel-elimination w3-09 备选路线落地）：
// batch-06 的建筑五事务（building_tx.h）不触碰槽位——当时判定
// "GridBuildingData 无槽位字段 ⇒ 槽位/弟子派生清理由 Kotlin 门面残差
// 承担"。但槽位本就是 gameData 协议集合（spiritMineSlots/productionSlots/
// residenceSlots 等十类，C++ 全量持有），"模型缺字段"不成立 ⇒ 残差写者
// 可以下沉。本头以**两个独立事务**承接（building_tx.h 冻结不改）：
//
//   clearResidualTransaction（1810 BUILDING_RESIDUAL_CLEAR）
//     拆除/没收后的槽位清扫 + 弟子状态破除——Kotlin
//     cleanupBuildingSlotsResidual / cleanupBuildingSlots 的槽位写段等价。
//   placeSlotsTransaction（1811 BUILDING_PLACE_SLOTS）
//     放置后的槽位派生——SlotGroup.createSlots 的写段等价（batch-06
//     已承接建筑本体/灵石/引导计数）。
//
// 特征知识分界（w2 §3.3 "Kotlin 组装参数传入"）：BuildingFeatureRegistry/
// SlotGroup 单一事实源留 Kotlin——槽组种类（groups）、slotsPerInstance、
// displayName（长老殿"最后一座"判定）、featureKey（生产槽 buildingId）、
// 监牢/任务阁特例标志、生产槽 id（UUID 平台生成）与 slotIndex（同类型
// 建筑计数，pre-place 基数）均由 Kotlin 门面组装随请求传入；C++ 只做
// 纯数据变换，不复制注册表。
//
// 语义逐字对齐的三个已知边界：
//  - patrolConfigs 在拆除臂**不清**：PatrolTower.filterFromGameData 的
//    towerIdx 按"已删建筑仍在 placedBuildings"求 indexOfFirst ⇒ 两臂
//    实际均得 -1（bug-for-bug 兼容，Kotlin 原路径同序）；放置臂追加
//    恰一个 PatrolConfig（每塔一份）。
//  - 长老殿（ElderPositions）按 displayName 检查"是否还有同名气建筑"
//    （不含自身——拆除后传入即全表检查），最后一座才清空对应职务字段
//    （Elder="" + 弟子列保留 index 清 discipleId）。
//  - 监牢（REFLECTION_CLIFF）无实例归属记录 ⇒ 全量释放 REFLECTING；
//    任务阁（MISSION_HALL）清空 activeMissions 并释放存活 ON_MISSION。
//
// discipleIds（血炼 REFINING 破除）由 Kotlin 收集后传入（含 Room 生产
// repo 侧来源——平台存储 C++ 不可见，A3 前现状同）；C++ 按 id 直写
// statuses=IDLE + statusData 定向移除 "buildingId"（releaseBuilding-
// DiscipleIds 的协议写段；Gate 释放/Kotlin 运行态留 Kotlin）。
//
// 🔴 偏差登记（本事务的清扫范围边界）——两类槽组**留 Kotlin 清扫**：
//  ① productionSlots：C++ ProductionSlot 结构**无 buildingInstanceId 字段**
//     （Kotlin @ProtoNumber(21) 为 Kotlin 侧协议字段，C++ 行编解码不携带——
//     json_codec ProductionSlot 行无该键），实例级匹配在 C++ 不可表达；
//     这正是 handover "模型缺字段导致残差留 Kotlin" 根因在生产行上的实证。
//     生产行清扫与 Room repo 删除同属生产域（W4-A·A4 / 1820–1829 处置）。
//  ② ElderPositions（八种长老殿职务槽）：clearSpec 为 Kotlin 注册表内的
//     lambda 单一事实源（八变体字段各异）——C++ 复制即双算漂移源；
//     末座判定按 displayName 查 placedBuildings（镜像已在 C++，但规格
//     不复制）。两类槽组的过滤在 Kotlin 原路径同序补扫。
//
// RNG 契约：两事务均零 RNG（纯确定性集合变换；签名级证据 = 不接受
// RngManager/种子参数）；失败臂零写入（放置臂参数缺失/组未知 → failure
// 信封回退 Kotlin 原路径；清扫臂对未知组静默跳过——与 Kotlin "无该槽组
// 即无行可清" 同义）。
// ============================================================
#pragma once

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/state/models.h"

namespace gamecore::system::building_residual_tx {

using gamecore::state::GameState;

inline constexpr const char* kRefiningStatusName = "REFINING";
inline constexpr const char* kReflectingStatusName = "REFLECTING";
inline constexpr const char* kOnMissionStatusName = "ON_MISSION";
inline constexpr const char* kIdleStatusName = "IDLE";

/// statusData 键（DiscipleStatusData 单一来源同名键）
inline constexpr const char* kBuildingIdKey = "buildingId";
inline constexpr const char* kReflectionStartYearKey = "reflectionStartYear";
inline constexpr const char* kReflectionEndYearKey = "reflectionEndYear";

/// 槽组种类（本事务清扫范围 = 实例键控七集合 + 血炼；生产/长老组
/// 留 Kotlin——头注释偏差登记；枚举仅列 C++ 可清扫的组）
enum class SlotGroupKind {
    SpiritMine,
    PatrolTower,
    Residence,
    SpiritField,
    Warehouse,
    BloodRefining,
    Library,
};

/// 组名 → 枚举（dispatch 层解析用；未知组名返回 false——清扫臂静默跳过、
/// 放置臂按 failure 处理）
inline bool parseSlotGroupKind(const std::string& name, SlotGroupKind& out) {
    if (name == "SPIRIT_MINE") { out = SlotGroupKind::SpiritMine; return true; }
    if (name == "PATROL_TOWER") { out = SlotGroupKind::PatrolTower; return true; }
    if (name == "RESIDENCE") { out = SlotGroupKind::Residence; return true; }
    if (name == "SPIRIT_FIELD") { out = SlotGroupKind::SpiritField; return true; }
    if (name == "WAREHOUSE") { out = SlotGroupKind::Warehouse; return true; }
    if (name == "BLOOD_REFINING") { out = SlotGroupKind::BloodRefining; return true; }
    if (name == "LIBRARY") { out = SlotGroupKind::Library; return true; }
    return false;
}

/// 清扫结果信封
struct ClearResidualResult {
    bool ok = false;
    std::string errorType;
    std::string message;
    int32_t clearedTargets = 0;
};

/// 单个拆除目标（Kotlin BuildingFeature → 参数投影）
struct ResidualTarget {
    std::string instanceId;
    std::string displayName;
    std::vector<SlotGroupKind> groups;
    bool isMissionHall = false;
    bool isReflectionCliff = false;
    /// Kotlin 侧收集的关联弟子 id（含 Room 生产 repo 来源）——REFINING 破除用
    std::vector<std::string> discipleIds;
};

/// 事务 1810：拆除/没收槽位清扫（cleanupBuildingSlotsResidual 的协议写段等价）
///
/// 执行序对齐 Kotlin 原路径：槽位过滤（按组）→ 长老殿判定 → 监牢/任务阁
/// 特例 → REFINING 破除。零 RNG；未知组静默跳过（与 Kotlin "无该槽组即
/// 无行可清"同义）。
inline ClearResidualResult clearResidualTransaction(GameState& state,
                                                    const std::vector<ResidualTarget>& targets) {
    ClearResidualResult out;
    auto& gd = state.gameData;

    for (const auto& target : targets) {
        const auto& instanceId = target.instanceId;
        // 1) 槽位过滤（八集合——buildingInstanceId 键控集合逐组清除）
        for (const auto kind : target.groups) {
            switch (kind) {
                case SlotGroupKind::SpiritMine:
                    gd.spiritMineSlots.erase(
                        std::remove_if(gd.spiritMineSlots.begin(), gd.spiritMineSlots.end(),
                                       [&](const gamecore::state::SpiritMineSlot& s) {
                                           return s.buildingInstanceId == instanceId;
                                       }),
                        gd.spiritMineSlots.end());
                    break;
                case SlotGroupKind::PatrolTower:
                    // patrolConfigs 不清（两臂 towerIdx=-1 的 bug-for-bug 兼容——见头注释）
                    gd.patrolSlots.erase(
                        std::remove_if(gd.patrolSlots.begin(), gd.patrolSlots.end(),
                                       [&](const gamecore::state::PatrolSlot& s) {
                                           return s.buildingInstanceId == instanceId;
                                       }),
                        gd.patrolSlots.end());
                    break;
                case SlotGroupKind::Residence:
                    gd.residenceSlots.erase(
                        std::remove_if(gd.residenceSlots.begin(), gd.residenceSlots.end(),
                                       [&](const gamecore::state::ResidenceSlot& s) {
                                           return s.buildingInstanceId == instanceId;
                                       }),
                        gd.residenceSlots.end());
                    break;
                case SlotGroupKind::SpiritField:
                    gd.spiritFieldPlants.erase(
                        std::remove_if(gd.spiritFieldPlants.begin(), gd.spiritFieldPlants.end(),
                                       [&](const gamecore::state::SpiritFieldPlant& s) {
                                           return s.buildingInstanceId == instanceId;
                                       }),
                        gd.spiritFieldPlants.end());
                    break;
                case SlotGroupKind::Warehouse:
                    gd.warehouseGarrisons.erase(
                        std::remove_if(gd.warehouseGarrisons.begin(), gd.warehouseGarrisons.end(),
                                       [&](const gamecore::state::WarehouseGarrisonSlot& s) {
                                           return s.buildingInstanceId == instanceId;
                                       }),
                        gd.warehouseGarrisons.end());
                    break;
                case SlotGroupKind::Library:
                    gd.librarySlots.erase(
                        std::remove_if(gd.librarySlots.begin(), gd.librarySlots.end(),
                                       [&](const gamecore::state::LibrarySlot& s) {
                                           return s.buildingInstanceId == instanceId;
                                       }),
                        gd.librarySlots.end());
                    break;
                case SlotGroupKind::BloodRefining:
                    gd.activeBloodRefinements.erase(instanceId);
                    break;
            }
        }

        // 2) 监牢特例：全量释放 REFLECTING（无实例归属记录——Kotlin 同语义）
        if (target.isReflectionCliff) {
            for (std::size_t row = 0; row < state.disciples.ids.size(); ++row) {
                if (state.disciples.statuses[row] != kReflectingStatusName) continue;
                state.disciples.statuses[row] = kIdleStatusName;
                state.disciples.statusData[row].erase(kReflectionStartYearKey);
                state.disciples.statusData[row].erase(kReflectionEndYearKey);
            }
        }

        // 3) 任务阁特例：清空 activeMissions + 存活 ON_MISSION 回 IDLE
        if (target.isMissionHall) {
            gd.activeMissions.clear();
            for (std::size_t row = 0; row < state.disciples.ids.size(); ++row) {
                if (state.disciples.statuses[row] == kOnMissionStatusName &&
                    state.disciples.isAlive[row] == 1) {
                    state.disciples.statuses[row] = kIdleStatusName;
                }
            }
        }

        // 4) REFINING 破除（releaseBuildingDiscipleIds 协议写段；Kotlin 原序：
        //    id 在表内 + status==REFINING 即破，无 isAlive 过滤）
        for (const auto& dId : target.discipleIds) {
            if (!state.disciples.contains(dId)) continue;
            const std::size_t row = *state.disciples.rowOf(dId);
            if (state.disciples.statuses[row] != kRefiningStatusName) continue;
            state.disciples.statuses[row] = kIdleStatusName;
            state.disciples.statusData[row].erase(kBuildingIdKey);
        }

        ++out.clearedTargets;
    }

    out.ok = true;
    return out;
}

/// 放置参数（Kotlin SlotGroup.createSlots 参数投影；特征知识留 Kotlin）
struct PlaceSlotsParams {
    std::string instanceId;
    std::string activeSectId;   // activeId（灵田/矿场/仓库 sectId 冗余列）
    /// （组、slotsPerInstance）有序列表——仅传本事务清扫范围内的组
    ///（生产/长老组留 Kotlin——头注释偏差登记，Kotlin 不发即不建）
    std::vector<std::pair<SlotGroupKind, int32_t>> groups;
};

/// 放置结果信封
struct PlaceSlotsResult {
    bool ok = false;
    std::string errorType;
    std::string message;
    int32_t createdSlots = 0;
};

/// 事务 1811：放置槽位派生（SlotGroup.createSlots 写段等价——建筑本体/
/// 灵石/引导计数已由 batch-06 BUILDING_PLACE 承担，勿重复写）
///
/// 基数口径：index/slotIndex 基于执行时现状（放置不产槽 ⇒ 与 Kotlin
/// beforeNative 基数一致）；仓库/藏经阁 existingCount 按"同实例已存在行数"
/// 复刻 Kotlin 公式（新建筑恒 0，公式保留以逐字对齐）。
inline PlaceSlotsResult placeSlotsTransaction(GameState& state,
                                              const PlaceSlotsParams& params) {
    PlaceSlotsResult out;
    if (params.instanceId.empty()) {
        out.errorType = "Validation";
        out.message = "instanceId 为空";
        return out;
    }
    auto& gd = state.gameData;

    for (const auto& [kind, perInstance] : params.groups) {
        switch (kind) {
            case SlotGroupKind::SpiritMine: {
                const int32_t base = static_cast<int32_t>(gd.spiritMineSlots.size());
                for (int32_t offset = 0; offset < perInstance; ++offset) {
                    gamecore::state::SpiritMineSlot slot;
                    slot.index = base + offset;
                    slot.buildingInstanceId = params.instanceId;
                    slot.sectId = params.activeSectId;
                    gd.spiritMineSlots.push_back(slot);
                    ++out.createdSlots;
                }
                break;
            }
            case SlotGroupKind::PatrolTower: {
                const int32_t base = static_cast<int32_t>(gd.patrolSlots.size());
                for (int32_t offset = 0; offset < perInstance; ++offset) {
                    gamecore::state::PatrolSlot slot;
                    slot.index = base + offset;
                    slot.buildingInstanceId = params.instanceId;
                    gd.patrolSlots.push_back(slot);
                    ++out.createdSlots;
                }
                gd.patrolConfigs.push_back(gamecore::state::PatrolConfig{});  // 每塔一份
                break;
            }
            case SlotGroupKind::Residence: {
                for (int32_t offset = 0; offset < perInstance; ++offset) {
                    gamecore::state::ResidenceSlot slot;
                    slot.buildingInstanceId = params.instanceId;
                    slot.slotIndex = offset;
                    gd.residenceSlots.push_back(slot);
                    ++out.createdSlots;
                }
                break;
            }
            case SlotGroupKind::SpiritField: {
                gamecore::state::SpiritFieldPlant plant;
                plant.buildingInstanceId = params.instanceId;
                plant.sectId = params.activeSectId;
                gd.spiritFieldPlants.push_back(plant);
                ++out.createdSlots;
                break;
            }
            case SlotGroupKind::Warehouse: {
                int32_t existing = 0;
                for (const auto& row : gd.warehouseGarrisons) {
                    if (row.buildingInstanceId == params.instanceId) ++existing;
                }
                for (int32_t offset = 0; offset < perInstance; ++offset) {
                    gamecore::state::WarehouseGarrisonSlot slot;
                    slot.buildingInstanceId = params.instanceId;
                    slot.slotIndex = existing + offset;
                    slot.sectId = params.activeSectId;
                    gd.warehouseGarrisons.push_back(slot);
                    ++out.createdSlots;
                }
                break;
            }
            case SlotGroupKind::Library: {
                int32_t existing = 0;
                for (const auto& row : gd.librarySlots) {
                    if (row.buildingInstanceId == params.instanceId) ++existing;
                }
                for (int32_t offset = 0; offset < perInstance; ++offset) {
                    gamecore::state::LibrarySlot slot;
                    slot.index = existing + offset;
                    slot.buildingInstanceId = params.instanceId;
                    gd.librarySlots.push_back(slot);
                    ++out.createdSlots;
                }
                break;
            }
            case SlotGroupKind::BloodRefining:
                // 建造不产槽（血炼进度按需写入）——Kotlin createSlots
                // 返回空 SlotCreationResult 同义
                break;
        }
    }

    out.ok = true;
    return out;
}

}  // namespace gamecore::system::building_residual_tx
