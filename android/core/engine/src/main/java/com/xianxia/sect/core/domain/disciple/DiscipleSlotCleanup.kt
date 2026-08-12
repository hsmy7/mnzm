package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.ActiveMission
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.CaveExplorationStatus
import com.xianxia.sect.core.model.CaveExplorationTeam
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton



/**
 * 弟子死亡/脱离时从所有槽位清理。
 * 由 DiscipleService 和 CultivationService 共用，
 * 确保新增槽位类型时不会漏掉。
 *
 * 清理槽位的同时自动调用 [DiscipleAssignmentGate.release] 同步注册表。
 */
@Singleton
class DiscipleSlotCleanup @Inject constructor(
    private val assignmentGate: DiscipleAssignmentGate,
) {

    /**
     * 从 GameData 中清理指定弟子的所有槽位引用。
     * 同时清理 Gate 注册表。
     *
     * ⚠️ `assignmentGate.release()` 始终被调用，即使弟子未注册门卫（如纯住所弟子）。
     * 对未注册 ID，release 是空操作，安全。
     * 但如果未来 [DiscipleAssignmentGate.scanAndRegister] 开始扫描住所，
     * 需在此处同步评估 includeResidence 与 release 的关系。
     *
     * @param includeResidence 是否清理住所槽位。工作分配应传 false（保留住所），
     *                         死亡/逐出应传 true。默认为 false。
     * @return 更新后的 GameData。
     */
    fun clearAllSlots(data: GameData, discipleId: String, includeResidence: Boolean = false): GameData {
        // 同步清理 Gate 注册表（对纯住所弟子是空操作）
        assignmentGate.release(discipleId)
        return clearAllSlotsDataOnly(data, discipleId, includeResidence)
    }

    /**
     * 仅清理 GameData 槽位引用，不操作 Gate 注册表。
     * 专供 [stateStore.update] 事务内部使用——调用方在事务外自行调用 [assignmentGate.release]。
     *
     * @param includeResidence 同 [clearAllSlots]。
     * @return 更新后的 GameData（不含 Gate 操作）。
     */
    fun clearAllSlotsDataOnly(data: GameData, discipleId: String, includeResidence: Boolean = false): GameData {

        val updatedSpiritMineSlots = data.spiritMineSlots.map {
            if (it.discipleId == discipleId) it.copy(discipleId = "", discipleName = "") else it
        }

        val updatedLibrarySlots = data.librarySlots.map {
            if (it.discipleId == discipleId) it.copy(discipleId = "", discipleName = "") else it
        }

        val updatedElderSlots = clearElderSlots(data.elderSlots, discipleId)

        val updatedResidenceSlots = clearResidenceSlots(
            data.residenceSlots, discipleId, includeResidence
        )

        val updatedActiveBloodRefinements = clearActiveBloodRefinements(
            data.activeBloodRefinements, discipleId
        )

        val updatedPatrolSlots = data.patrolSlots.map {
            if (it.discipleId == discipleId) it.copy(discipleId = "", discipleName = "") else it
        }

        val updatedWarehouseGarrisons = data.warehouseGarrisons.map {
            if (it.discipleId == discipleId) it.copy(discipleId = "", discipleName = "") else it
        }

        val updatedBattleTeams = data.battleTeams.map { team ->
            team.copy(slots = team.slots.map { slot ->
                if (slot.discipleId == discipleId)
                    slot.copy(discipleId = "", discipleName = "", isAlive = true)
                else slot
            })
        }

        val updatedWorldMapSects = data.worldMapSects.map { sect ->
            if (sect.isPlayerSect) {
                sect.copy(garrisonSlots = sect.garrisonSlots.map { slot ->
                    if (slot.discipleId == discipleId) GarrisonSlot(index = slot.index) else slot
                })
            } else sect
        }

        // 生产槽位（炼丹/锻造/灵植工人）——与槽位清理统一覆盖，防止弟子卸任/死亡/叛逃后槽位残留
        val updatedProductionSlots = data.productionSlots.map {
            if (it.assignedDiscipleId == discipleId)
                it.copy(assignedDiscipleId = null, assignedDiscipleName = "")
            else it
        }

        val updatedCaveExplorationTeams = clearCaveExplorationTeams(
            data.caveExplorationTeams, discipleId
        )
        val updatedActiveMissions = clearActiveMissions(data.activeMissions, discipleId)

        return data.copy(
            spiritMineSlots = updatedSpiritMineSlots,
            librarySlots = updatedLibrarySlots,
            elderSlots = updatedElderSlots,
            residenceSlots = updatedResidenceSlots,
            activeBloodRefinements = updatedActiveBloodRefinements,
            patrolSlots = updatedPatrolSlots,
            warehouseGarrisons = updatedWarehouseGarrisons,
            battleTeams = updatedBattleTeams,
            worldMapSects = updatedWorldMapSects,
            productionSlots = updatedProductionSlots,
            caveExplorationTeams = updatedCaveExplorationTeams,
            activeMissions = updatedActiveMissions
        )
    }

    /**
     * 事务内全量槽位清理（state 级）：Gate 注册表 + GameData 槽位（含洞府探索队/悬赏任务）。
     *
     * 专供 [stateStore.update] 事务内部使用——assignmentGate.release 为纯内存操作，
     * 事务内调用安全。
     *
     * 不清理 [MutableGameState.secretRealmSession] 的 members——秘境队伍已有
     * 恢复净化（读档自愈）覆盖，避免此处与秘境生命周期竞态。
     *
     * @param state 事务内的可变游戏状态
     * @param discipleId 要清除的弟子 ID
     * @param includeResidence 是否清理住所槽位，同 [clearAllSlots]
     */
    fun clearAllSlotsState(
        state: MutableGameState,
        discipleId: String,
        includeResidence: Boolean = false
    ) {
        assignmentGate.release(discipleId)
        state.gameData = clearAllSlotsDataOnly(state.gameData, discipleId, includeResidence)
    }

    /**
     * 住所槽位清理：includeResidence 为 true 时清空，否则原样返回（工作分配保留住所语义）。
     */
    private fun clearResidenceSlots(
        slots: List<ResidenceSlot>,
        discipleId: String,
        includeResidence: Boolean
    ): List<ResidenceSlot> = if (includeResidence) {
        slots.map {
            if (it.discipleId == discipleId) it.copy(discipleId = "", discipleName = "") else it
        }
    } else {
        slots
    }

    /**
     * 血炼进度清理：移除指定弟子的进行中血炼记录（原地修改语义保留）。
     */
    private fun clearActiveBloodRefinements(
        refinements: Map<String, BloodRefinementProgress>,
        discipleId: String
    ): Map<String, BloodRefinementProgress> {
        val updated = refinements.toMutableMap()
        updated.entries.removeAll { it.value.discipleId == discipleId }
        return updated
    }

    /**
     * 洞府探索队清理：移除死者成员，剩余成员继续探索；
     * 整队仅剩死者则标记 COMPLETED（与洞窟探索"空队终止"语义一致）。
     */
    private fun clearCaveExplorationTeams(
        teams: List<CaveExplorationTeam>,
        discipleId: String
    ): List<CaveExplorationTeam> = teams.map { team ->
        if (discipleId !in team.memberIds) {
            team
        } else {
            val remainingIds = team.memberIds.filter { it != discipleId }
            val remainingNames = team.memberNames.filterIndexed { i, _ ->
                team.memberIds[i] != discipleId
            }
            if (remainingIds.isEmpty()) {
                team.copy(
                    memberIds = emptyList(), memberNames = emptyList(),
                    status = CaveExplorationStatus.COMPLETED
                )
            } else {
                team.copy(memberIds = remainingIds, memberNames = remainingNames)
            }
        }
    }

    /**
     * 悬赏任务清理：移除死者成员，其余成员继续执行。
     */
    private fun clearActiveMissions(
        missions: List<ActiveMission>,
        discipleId: String
    ): List<ActiveMission> = missions.map { mission ->
        if (discipleId !in mission.discipleIds) {
            mission
        } else {
            val remainingIds = mission.discipleIds.filter { it != discipleId }
            val remainingNames = mission.discipleNames.filterIndexed { i, _ ->
                mission.discipleIds[i] != discipleId
            }
            mission.copy(discipleIds = remainingIds, discipleNames = remainingNames)
        }
    }

    private fun clearElderSlots(slots: ElderSlots, discipleId: String): ElderSlots {
        var updated = slots

        if (updated.viceSectMaster == discipleId) updated = updated.copy(viceSectMaster = "")
        if (updated.herbGardenElder == discipleId) updated = updated.copy(herbGardenElder = "")
        if (updated.alchemyElder == discipleId) updated = updated.copy(alchemyElder = "")
        if (updated.forgeElder == discipleId) updated = updated.copy(forgeElder = "")
        if (updated.outerElder == discipleId) updated = updated.copy(outerElder = "")
        if (updated.preachingElder == discipleId) updated = updated.copy(preachingElder = "")
        if (updated.lawEnforcementElder == discipleId) updated = updated.copy(lawEnforcementElder = "")
        if (updated.innerElder == discipleId) updated = updated.copy(innerElder = "")
        // 回归：纳徒长老此前漏清——双槽位可经此槽残留（gate 扫描覆盖 10 个长老字段，
        // 清理清单必须与之一一对应）
        if (updated.recruitingElder == discipleId) updated = updated.copy(recruitingElder = "")
        if (updated.qingyunPreachingElder == discipleId) updated = updated.copy(qingyunPreachingElder = "")

        updated = updated.copy(
            preachingMasters = updated.preachingMasters.mapNotNull { slot ->
                if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
            },
            lawEnforcementDisciples = updated.lawEnforcementDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
            },
            qingyunPreachingMasters = updated.qingyunPreachingMasters.mapNotNull { slot ->
                if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
            },
            herbGardenDisciples = updated.herbGardenDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
            },
            alchemyDisciples = updated.alchemyDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
            },
            forgeDisciples = updated.forgeDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
            },
            spiritMineDeaconDisciples = updated.spiritMineDeaconDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
            }
        )

        return updated
    }
}
