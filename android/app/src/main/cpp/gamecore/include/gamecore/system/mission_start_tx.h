#pragma once

/**
 * mission_start_tx.h — 任务派遣事务（W4-D/D4 续·任务域收口批，ActionId
 * `MISSION_START_TX=1861`，段 1860–1869）。
 *
 * ## 承接的 Kotlin 写者（下沉前真源）
 * `GameEngineMissionOps.startMission`（任务阁 UI 派遣，ui-read-surface §4.4
 * RECRUIT 域 `activeMissions` 在册保留字段 + 弟子通道关闭的协议列阻断写者）：
 *   ① 逐队员 `releaseDiscipleToIdleInside`——全槽位清理（保留住所，
 *      clearAllSlotsDataOnly includeResidence=false）+ 状态重置
 *      （REFLECTING → 剥离 reflectionStart/EndYear → IDLE；
 *      REFINING → 剥离 buildingId → IDLE；其余 → IDLE）；
 *   ② `MissionSystem.createActiveMission`——模板字段拷贝 +
 *      队员 id/name/realmNameOnly 快照（纯函数零 RNG）；
 *   ③ `activeMissions` 追加。
 * 事务外部平台面（assignmentGate 释放 / Room 生产槽 Repository 清理 /
 * syncAllDiscipleStatuses）两臂同形留 Kotlin，不入事务。
 *
 * ## RNG 契约（红线 1）
 * **零抽取**。`ActiveMission.id` 在 Kotlin 侧为 `UUID.randomUUID()`（Java 随机，
 * 非游戏分区——原基线即零游戏抽取），由调用方生成后作 `activeMissionId` 参数
 * 传入（镜像生成字段口径：语义等价 = 唯一性，与 Kotlin Mission.id /
 * 商人收购 id 同类，不参与 RNG 终态对拍）。双臂抽取增量恒 0。
 *
 * ## 校验语义（与 Kotlin 逐位对齐）
 * - missionId 不在 availableMissions ⇒ 失败信封 MISSION_NOT_FOUND（零写入；
 *   Kotlin 回退臂按原语义继续——原路径不校验可用性，模板对象由 UI 给定）；
 * - 队员 id 不在弟子表 ⇒ 静默跳过该队员的槽位/状态清理（Kotlin
 *   `id !in discipleTables.ids → return` 同语义），ActiveMission 仍含全部
 *   传入 id（Kotlin createActiveMission 对全部传入弟子建快照同语义；name/realm
 *   快照在行缺失时为空串——UI 不可达路径）；
 * - memberCount 校验为 Kotlin `createActiveMission` 的 require（模板 requiredMemberCount
 *   不在 C++ Mission 协议内）⇒ C++ 臂不重复校验，误用输入由 Kotlin 臂抛出语义兜底。
 *
 * ## 零 Android 依赖 / 失败信封契约
 * 纯 C++20；参数缺失/类型不符由端口返回 INVALID_PARAMS 失败信封。
 */

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/level_generator.h"   // realmName（Kotlin GameConfig.Realm.getName 同源映射）
#include "gamecore/system/slot_cleanup.h"

namespace gamecore::system::mission_tx {

/// 派遣结果（started = 是否写入；errorCode 供端口失败信封，started 时为 nullptr）。
struct MissionStartResult {
    bool started;
    const char* errorCode;
};

/// 单队员状态重置（Kotlin releaseDiscipleToIdleInside 的 REFLECTING/REFINING 分支）。
inline void resetDiscipleStatusForDispatch(state::DiscipleStore& store, std::size_t row) {
    std::string& status = store.statuses[row];
    std::map<std::string, std::string>& statusData = store.statusData[row];
    if (status == "REFLECTING") {
        statusData.erase("reflectionStartYear");
        statusData.erase("reflectionEndYear");
    } else if (status == "REFINING") {
        statusData.erase("buildingId");
    }
    status = "IDLE";
}

/**
 * 任务派遣（零 RNG；语义与 Kotlin startMission 事务体逐位一致）。
 *
 * @param state 游戏状态
 * @param missionId 可用任务模板 id（availableMissions 查找键）
 * @param activeMissionId 新 ActiveMission 的 id（Kotlin UUID.randomUUID() 参数化传入）
 * @param discipleIds 派遣弟子 id（字符串形态）
 */
inline MissionStartResult startMissionTx(state::GameState& state,
                                         const std::string& missionId,
                                         const std::string& activeMissionId,
                                         const std::vector<std::string>& discipleIds) {
    state::GameData& gd = state.gameData;
    const state::Mission* tpl = nullptr;
    for (const auto& m : gd.availableMissions) {
        if (m.id == missionId) {
            tpl = &m;
            break;
        }
    }
    if (tpl == nullptr) {
        return {false, "MISSION_NOT_FOUND"};
    }

    auto& store = state.disciples;

    // ① 逐队员全槽位清理（保留住所）+ 状态重置（缺行静默跳过——Kotlin 同语义）
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
    for (const auto& did : discipleIds) {
        const auto row = store.rowOf(did);
        if (!row.has_value()) {
            continue;  // Kotlin `id !in discipleTables.ids → return` 同语义
        }
        const auto out = clearAllSlotsDataOnly(in, did, /*includeResidence=*/false);
        in = SlotCleanupInput{
            out.spiritMineSlots, out.librarySlots, out.elderSlots, out.residenceSlots,
            out.activeBloodRefinements, out.patrolSlots, out.warehouseGarrisons,
            out.battleTeams, out.worldMapSects, out.productionSlots,
            out.caveExplorationTeams, out.activeMissions};
        resetDiscipleStatusForDispatch(store, *row);
    }

    // 槽位清理结果写回（appointment_tx 同款拷出模式；activeMissions 经 Lite 形状合并）
    gd.spiritMineSlots = in.spiritMineSlots;
    gd.librarySlots = in.librarySlots;
    gd.elderSlots = in.elderSlots;
    gd.residenceSlots = in.residenceSlots;
    gd.activeBloodRefinements = in.activeBloodRefinements;
    gd.patrolSlots = in.patrolSlots;
    gd.warehouseGarrisons = in.warehouseGarrisons;
    gd.battleTeams = in.battleTeams;
    gd.worldMapSects = in.worldMapSects;
    gd.productionSlots = in.productionSlots;
    gd.caveExplorationTeams = in.caveExplorationTeams;
    gd.activeMissions = mergeMissionLiteList(gd.activeMissions, in.activeMissions);

    // ② ActiveMission 构造（模板拷贝 + 队员快照，零 RNG）
    state::ActiveMission am;
    am.id = activeMissionId;
    am.missionId = tpl->id;
    am.missionName = tpl->name;
    am.template_ = tpl->template_;
    am.difficulty = tpl->difficulty;
    am.discipleIds = discipleIds;
    am.discipleNames.reserve(discipleIds.size());
    am.discipleRealms.reserve(discipleIds.size());
    for (const auto& did : discipleIds) {
        const auto row = store.rowOf(did);
        am.discipleNames.push_back(row.has_value() ? store.names[*row] : std::string());
        am.discipleRealms.push_back(row.has_value()
                                        ? std::string(realmName(store.realms[*row]))
                                        : std::string());
    }
    am.startYear = gd.gameYear;
    am.startMonth = gd.gameMonth;
    am.duration = tpl->duration;
    am.rewards = tpl->rewards;
    am.missionType = tpl->missionType;
    am.enemyType = tpl->enemyType;
    am.triggerChance = tpl->triggerChance;

    // ③ 追加（Kotlin `activeMissions + activeMission` 同语义）
    gd.activeMissions.push_back(std::move(am));
    return {true, nullptr};
}

}  // namespace gamecore::system::mission_tx
