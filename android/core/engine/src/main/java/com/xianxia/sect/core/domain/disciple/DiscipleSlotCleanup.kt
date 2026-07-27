package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.*
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

        val updatedResidenceSlots = if (includeResidence) {
            data.residenceSlots.map {
                if (it.discipleId == discipleId) it.copy(discipleId = "", discipleName = "") else it
            }
        } else {
            data.residenceSlots
        }

        val updatedActiveBloodRefinements = data.activeBloodRefinements.toMutableMap()
        updatedActiveBloodRefinements.entries.removeAll { it.value.discipleId == discipleId }

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

        return data.copy(
            spiritMineSlots = updatedSpiritMineSlots,
            librarySlots = updatedLibrarySlots,
            elderSlots = updatedElderSlots,
            residenceSlots = updatedResidenceSlots,
            activeBloodRefinements = updatedActiveBloodRefinements,
            patrolSlots = updatedPatrolSlots,
            warehouseGarrisons = updatedWarehouseGarrisons,
            battleTeams = updatedBattleTeams,
            worldMapSects = updatedWorldMapSects
        )
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
