#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "gamecore/state/models.h"

// ============================================================
// 弟子槽位清理
//
// 等价移植 Kotlin DiscipleSlotCleanup.clearAllSlotsDataOnly 的**纯数据变换**
// （11 类槽位统一清理，无 RNG、无状态）：
//   - spiritMineSlots / librarySlots / patrolSlots / warehouseGarrisons：
//     匹配弟子 → 清空 discipleId/discipleName
//   - elderSlots：10 个长老单值字段 + 7 个 DirectDiscipleSlot 列表
//     （匹配槽位降级为 DirectDiscipleSlot(index)，保留槽位索引）
//   - residenceSlots：includeResidence 时清空（工作分配保留住所语义）
//   - activeBloodRefinements：移除该弟子的进行中血炼记录
//   - battleTeams：槽位清空 + isAlive=true
//   - worldMapSects：玩家宗门 garrisonSlots 清空（GarrisonSlot(index) 保留索引）
//   - productionSlots：assignedDiscipleId → null + 名称清空
//   - caveExplorationTeams：移除死者成员；整队仅剩死者 → COMPLETED
//   - activeMissions：移除死者成员（ActiveMissionLite 精简协议，
//     完整模型由 Kotlin 保留映射）
//
// 已知边界：DiscipleAssignmentGate.release 注册表操作保留 Kotlin
// （纯内存门卫，非确定性快照逻辑）；本文件只做 GameData 槽位数据变换。
// ============================================================
namespace gamecore::system {

/// 槽位清理输入（对应 GameData 11 类槽位集合）
struct SlotCleanupInput {
    std::vector<state::SpiritMineSlot> spiritMineSlots;
    std::vector<state::LibrarySlot> librarySlots;
    state::ElderSlots elderSlots;
    std::vector<state::ResidenceSlot> residenceSlots;
    std::map<std::string, state::BloodRefinementProgress> activeBloodRefinements;
    std::vector<state::PatrolSlot> patrolSlots;
    std::vector<state::WarehouseGarrisonSlot> warehouseGarrisons;
    std::vector<state::BattleTeam> battleTeams;
    std::vector<state::WorldSect> worldMapSects;
    std::vector<state::ProductionSlot> productionSlots;
    std::vector<state::CaveExplorationTeam> caveExplorationTeams;
    std::vector<state::ActiveMissionLite> activeMissions;
};

/// 槽位清理结果（与输入同构，各字段为清理后集合）
struct SlotCleanupResult {
    std::vector<state::SpiritMineSlot> spiritMineSlots;
    std::vector<state::LibrarySlot> librarySlots;
    state::ElderSlots elderSlots;
    std::vector<state::ResidenceSlot> residenceSlots;
    std::map<std::string, state::BloodRefinementProgress> activeBloodRefinements;
    std::vector<state::PatrolSlot> patrolSlots;
    std::vector<state::WarehouseGarrisonSlot> warehouseGarrisons;
    std::vector<state::BattleTeam> battleTeams;
    std::vector<state::WorldSect> worldMapSects;
    std::vector<state::ProductionSlot> productionSlots;
    std::vector<state::CaveExplorationTeam> caveExplorationTeams;
    std::vector<state::ActiveMissionLite> activeMissions;
};

/// 长老槽位清理（Kotlin clearElderSlots：10 单值字段 + 7 列表，逐项独立判断）
inline state::ElderSlots clearElderSlotsCpp(const state::ElderSlots& slots,
                                            const std::string& discipleId) {
    state::ElderSlots out = slots;
    // 单值长老字段：匹配 → 清空
    if (out.viceSectMaster == discipleId) out.viceSectMaster.clear();
    if (out.herbGardenElder == discipleId) out.herbGardenElder.clear();
    if (out.alchemyElder == discipleId) out.alchemyElder.clear();
    if (out.forgeElder == discipleId) out.forgeElder.clear();
    if (out.outerElder == discipleId) out.outerElder.clear();
    if (out.preachingElder == discipleId) out.preachingElder.clear();
    if (out.lawEnforcementElder == discipleId) out.lawEnforcementElder.clear();
    if (out.innerElder == discipleId) out.innerElder.clear();
    if (out.recruitingElder == discipleId) out.recruitingElder.clear();
    if (out.qingyunPreachingElder == discipleId) out.qingyunPreachingElder.clear();
    // 列表槽位：匹配 → 降级为 DirectDiscipleSlot(index)（mapNotNull 保留索引）
    auto clearList = [&discipleId](const std::vector<state::DirectDiscipleSlot>& list) {
        std::vector<state::DirectDiscipleSlot> outList;
        for (const auto& slot : list) {
            if (slot.discipleId == discipleId) {
                state::DirectDiscipleSlot cleared;
                cleared.index = slot.index;
                outList.push_back(std::move(cleared));
            } else {
                outList.push_back(slot);
            }
        }
        return outList;
    };
    out.preachingMasters = clearList(out.preachingMasters);
    out.lawEnforcementDisciples = clearList(out.lawEnforcementDisciples);
    out.qingyunPreachingMasters = clearList(out.qingyunPreachingMasters);
    out.herbGardenDisciples = clearList(out.herbGardenDisciples);
    out.alchemyDisciples = clearList(out.alchemyDisciples);
    out.forgeDisciples = clearList(out.forgeDisciples);
    out.spiritMineDeaconDisciples = clearList(out.spiritMineDeaconDisciples);
    return out;
}

/// 洞府探索队清理（Kotlin clearCaveExplorationTeams）：
/// 移除死者成员；整队仅剩死者 → COMPLETED（空队终止语义）。
inline state::CaveExplorationTeam clearCaveExplorationTeam(
    const state::CaveExplorationTeam& team, const std::string& discipleId) {
    bool contains = false;
    for (const auto& id : team.memberIds) {
        if (id == discipleId) {
            contains = true;
            break;
        }
    }
    if (!contains) return team;
    std::vector<std::string> remainingIds;
    std::vector<std::string> remainingNames;
    for (std::size_t i = 0; i < team.memberIds.size(); ++i) {
        if (team.memberIds[i] != discipleId) {
            remainingIds.push_back(team.memberIds[i]);
            if (i < team.memberNames.size()) remainingNames.push_back(team.memberNames[i]);
        }
    }
    state::CaveExplorationTeam out = team;
    if (remainingIds.empty()) {
        out.memberIds.clear();
        out.memberNames.clear();
        out.status = "COMPLETED";  // CaveExplorationStatus.COMPLETED.name
    } else {
        out.memberIds = std::move(remainingIds);
        out.memberNames = std::move(remainingNames);
    }
    return out;
}

// ── S5：完整 ActiveMission ↔ Lite 清理协议转换助手（gameData.activeMissions
//    已升级为完整模型，清理 op 协议仍为 Lite；op 仅改 discipleIds/discipleNames，
//    按 id 1:1 合并回完整模型语义 = Kotlin clearActiveMissions 全字段 copy） ──

inline std::vector<state::ActiveMissionLite> toMissionLiteList(
    const std::vector<state::ActiveMission>& full) {
    std::vector<state::ActiveMissionLite> out;
    out.reserve(full.size());
    for (const auto& m : full) {
        state::ActiveMissionLite lite;
        lite.id = m.id;
        lite.discipleIds = m.discipleIds;
        lite.discipleNames = m.discipleNames;
        out.push_back(std::move(lite));
    }
    return out;
}

/// Lite 清理结果按 id 合并回完整任务模型（保序；清理不增任务，id 1:1）
inline std::vector<state::ActiveMission> mergeMissionLiteList(
    const std::vector<state::ActiveMission>& full,
    const std::vector<state::ActiveMissionLite>& liteList) {
    std::vector<state::ActiveMission> merged;
    merged.reserve(full.size());
    for (const auto& lite : liteList) {
        for (const auto& m : full) {
            if (m.id == lite.id) {
                state::ActiveMission out = m;
                out.discipleIds = lite.discipleIds;
                out.discipleNames = lite.discipleNames;
                merged.push_back(std::move(out));
                break;
            }
        }
    }
    return merged;
}

/// 悬赏任务清理（Kotlin clearActiveMissions 的成员过滤；ActiveMissionLite）
inline state::ActiveMissionLite clearActiveMission(
    const state::ActiveMissionLite& mission, const std::string& discipleId) {    bool contains = false;
    for (const auto& id : mission.discipleIds) {
        if (id == discipleId) {
            contains = true;
            break;
        }
    }
    if (!contains) return mission;
    state::ActiveMissionLite out = mission;
    std::vector<std::string> remainingIds;
    std::vector<std::string> remainingNames;
    for (std::size_t i = 0; i < mission.discipleIds.size(); ++i) {
        if (mission.discipleIds[i] != discipleId) {
            remainingIds.push_back(mission.discipleIds[i]);
            if (i < mission.discipleNames.size()) remainingNames.push_back(mission.discipleNames[i]);
        }
    }
    out.discipleIds = std::move(remainingIds);
    out.discipleNames = std::move(remainingNames);
    return out;
}

/// 全部槽位清理（Kotlin clearAllSlotsDataOnly；纯数据变换，无 Gate 操作）
inline SlotCleanupResult clearAllSlotsDataOnly(const SlotCleanupInput& in,
                                               const std::string& discipleId,
                                               bool includeResidence) {
    SlotCleanupResult out;
    // 1. 单字段槽位：匹配 → 清空 discipleId/discipleName
    for (const auto& s : in.spiritMineSlots) {
        state::SpiritMineSlot slot = s;
        if (slot.discipleId == discipleId) {
            slot.discipleId.clear();
            slot.discipleName.clear();
        }
        out.spiritMineSlots.push_back(std::move(slot));
    }
    for (const auto& s : in.librarySlots) {
        state::LibrarySlot slot = s;
        if (slot.discipleId == discipleId) {
            slot.discipleId.clear();
            slot.discipleName.clear();
        }
        out.librarySlots.push_back(std::move(slot));
    }
    for (const auto& s : in.patrolSlots) {
        state::PatrolSlot slot = s;
        if (slot.discipleId == discipleId) {
            slot.discipleId.clear();
            slot.discipleName.clear();
        }
        out.patrolSlots.push_back(std::move(slot));
    }
    for (const auto& s : in.warehouseGarrisons) {
        state::WarehouseGarrisonSlot slot = s;
        if (slot.discipleId == discipleId) {
            slot.discipleId.clear();
            slot.discipleName.clear();
        }
        out.warehouseGarrisons.push_back(std::move(slot));
    }
    // 2. 长老槽位
    out.elderSlots = clearElderSlotsCpp(in.elderSlots, discipleId);
    // 3. 住所槽位（includeResidence 时清空，否则原样）
    if (includeResidence) {
        for (const auto& s : in.residenceSlots) {
            state::ResidenceSlot slot = s;
            if (slot.discipleId == discipleId) {
                slot.discipleId.clear();
                slot.discipleName.clear();
            }
            out.residenceSlots.push_back(std::move(slot));
        }
    } else {
        out.residenceSlots = in.residenceSlots;
    }
    // 4. 血炼进度（移除该弟子条目；原地修改语义保留）
    for (const auto& [key, progress] : in.activeBloodRefinements) {
        if (progress.discipleId != discipleId) {
            out.activeBloodRefinements[key] = progress;
        }
    }
    // 5. 战斗队伍槽位（清空 + isAlive=true）
    for (const auto& t : in.battleTeams) {
        state::BattleTeam team = t;
        for (auto& slot : team.slots) {
            if (slot.discipleId == discipleId) {
                slot.discipleId.clear();
                slot.discipleName.clear();
                slot.isAlive = true;
            }
        }
        out.battleTeams.push_back(std::move(team));
    }
    // 6. 世界地图宗门驻防（仅玩家宗门；清空 → GarrisonSlot(index) 保留索引）
    for (const auto& s : in.worldMapSects) {
        state::WorldSect sect = s;
        if (sect.isPlayerSect) {
            std::vector<state::GarrisonSlot> cleared;
            for (const auto& slot : sect.garrisonSlots) {
                if (slot.discipleId == discipleId) {
                    state::GarrisonSlot gs;
                    gs.index = slot.index;
                    cleared.push_back(std::move(gs));
                } else {
                    cleared.push_back(slot);
                }
            }
            sect.garrisonSlots = std::move(cleared);
        }
        out.worldMapSects.push_back(std::move(sect));
    }
    // 7. 生产槽位（assignedDiscipleId → null + 名称清空）
    for (const auto& s : in.productionSlots) {
        state::ProductionSlot slot = s;
        if (slot.assignedDiscipleId.has_value() &&
            *slot.assignedDiscipleId == discipleId) {
            slot.assignedDiscipleId = std::nullopt;
            slot.assignedDiscipleName.clear();
        }
        out.productionSlots.push_back(std::move(slot));
    }
    // 8. 洞府探索队
    for (const auto& t : in.caveExplorationTeams) {
        out.caveExplorationTeams.push_back(clearCaveExplorationTeam(t, discipleId));
    }
    // 9. 悬赏任务（成员过滤）
    for (const auto& m : in.activeMissions) {
        out.activeMissions.push_back(clearActiveMission(m, discipleId));
    }
    return out;
}

}  // namespace gamecore::system
