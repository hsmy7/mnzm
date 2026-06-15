package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弟子死亡/脱离时从所有槽位清理。
 * 由 DiscipleService 和 CultivationService 共用，
 * 确保新增槽位类型时不会漏掉。
 */
@Singleton
class DiscipleSlotCleanup @Inject constructor() {

    /**
     * 从 GameData 中清理指定弟子的所有槽位引用。
     * 返回更新后的 GameData。
     */
    fun clearAllSlots(data: GameData, discipleId: String): GameData {
        val updatedSpiritMineSlots = data.spiritMineSlots.map {
            if (it.discipleId == discipleId) it.copy(discipleId = "", discipleName = "") else it
        }

        val updatedLibrarySlots = data.librarySlots.map {
            if (it.discipleId == discipleId) it.copy(discipleId = "", discipleName = "") else it
        }

        val updatedElderSlots = clearElderSlots(data.elderSlots, discipleId)

        val updatedResidenceSlots = data.residenceSlots.map {
            if (it.discipleId == discipleId) it.copy(discipleId = "", discipleName = "") else it
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
            lawEnforcementReserveDisciples = updated.lawEnforcementReserveDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
            },
            herbGardenReserveDisciples = updated.herbGardenReserveDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
            },
            alchemyReserveDisciples = updated.alchemyReserveDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
            },
            forgeReserveDisciples = updated.forgeReserveDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
            },
            spiritMineDeaconDisciples = updated.spiritMineDeaconDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
            }
        )

        return updated
    }

    // -- 向后兼容：companion 桥接，现有调用点无需改动 --

    companion object {
        @Volatile
        private var _instance: DiscipleSlotCleanup? = null

        internal fun initialize(instance: DiscipleSlotCleanup) {
            _instance = instance
        }

        private val instance: DiscipleSlotCleanup
            get() = _instance ?: DiscipleSlotCleanup().also { _instance = it }

        fun clearAllSlots(data: GameData, discipleId: String): GameData =
            instance.clearAllSlots(data, discipleId)
    }
}
