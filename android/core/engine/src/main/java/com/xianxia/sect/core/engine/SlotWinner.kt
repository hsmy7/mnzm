package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.MutableGameState


/**
 * 槽位赢家记录：弟子在首次出现的槽位（按 scanAndRegister 优先级顺序）。
 *
 * 供 [GameEngine.healDuplicateSlotAssignments] 双槽位自愈使用——
 * 清理重复槽位后按赢家信息重写回 GameData。
 */
internal data class SlotWinner(
    val discipleId: String,
    val category: SlotCategory,
    val slotType: String,
    val slotIndex: Int = -1
)

/** 秘境队伍占用的 gate 槽位类型名（与 GameEngineSecretRealmOps 一致，非持久化） */
private const val SECRET_REALM_SLOT_TYPE = "secret_realm"

/**
 * 扫描全部槽位（排除住所），记录每名弟子首次出现的槽位（赢家）与出现次数。
 * 供 [GameEngine.healDuplicateSlotAssignments] 使用（SlotWinner.kt 扫描侧）。
 */
internal fun MutableGameState.collectSlotWinners(
    data: GameData,
    winners: MutableMap<String, SlotWinner>,
    counts: MutableMap<String, Int>
) {
    fun register(discipleId: String?, winner: SlotWinner) {
        if (discipleId.isNullOrEmpty()) return
        winners.putIfAbsent(discipleId, winner)
        counts[discipleId] = (counts[discipleId] ?: 0) + 1
    }

    // 秘境成员优先：最先扫描 = 赢家（探索中弟子不应有岗位——原 Bug 场景
    // "秘境成员 + 岗位槽并存"旧档，保留秘境、清理岗位槽）
    registerSecretRealmMembers(data, ::register)

    val slots = data.elderSlots
    registerElderField(slots.viceSectMaster, "viceSectMaster", ::register)
    registerElderField(slots.herbGardenElder, "herbGardenElder", ::register)
    registerElderField(slots.alchemyElder, "alchemyElder", ::register)
    registerElderField(slots.forgeElder, "forgeElder", ::register)
    registerElderField(slots.outerElder, "outerElder", ::register)
    registerElderField(slots.preachingElder, "preachingElder", ::register)
    registerElderField(slots.lawEnforcementElder, "lawEnforcementElder", ::register)
    registerElderField(slots.innerElder, "innerElder", ::register)
    registerElderField(slots.recruitingElder, "recruitingElder", ::register)
    registerElderField(slots.qingyunPreachingElder, "qingyunPreachingElder", ::register)
    registerDirectList(slots.herbGardenDisciples, "herbGardenDisciple", ::register)
    registerDirectList(slots.alchemyDisciples, "alchemyDisciple", ::register)
    registerDirectList(slots.forgeDisciples, "forgeDisciple", ::register)
    registerDirectList(slots.preachingMasters, "preachingMaster", ::register)
    registerDirectList(slots.lawEnforcementDisciples, "lawEnforcementDisciple", ::register)
    registerDirectList(slots.qingyunPreachingMasters, "qingyunPreachingMaster", ::register)
    registerDirectList(slots.spiritMineDeaconDisciples, "spiritMineDeacon", ::register)

    data.spiritMineSlots.forEachIndexed { i, slot ->
        register(slot.discipleId, SlotWinner(slot.discipleId, SlotCategory.SPIRIT_MINE, "miner", i))
    }
    data.librarySlots.forEachIndexed { i, slot ->
        register(slot.discipleId, SlotWinner(slot.discipleId, SlotCategory.LIBRARY_SLOT, "library", i))
    }
    data.warehouseGarrisons.forEach { slot ->
        register(slot.discipleId, SlotWinner(slot.discipleId, SlotCategory.WAREHOUSE_GARRISON, slot.buildingInstanceId))
    }
    data.patrolSlots.forEachIndexed { i, slot ->
        register(slot.discipleId, SlotWinner(slot.discipleId, SlotCategory.PATROL_SLOT, "patrol", i))
    }
    data.activeBloodRefinements.forEach { (buildingId, refinement) ->
        register(refinement.discipleId, SlotWinner(refinement.discipleId, SlotCategory.BLOOD_REFINEMENT, buildingId))
    }
    data.worldMapSects.filter { it.isPlayerSect }.forEach { sect ->
        sect.garrisonSlots.forEach { slot ->
            register(slot.discipleId, SlotWinner(slot.discipleId, SlotCategory.GARRISON_SLOT, sect.id, slot.index))
        }
    }
    data.battleTeams.forEach { team ->
        team.slots.forEach { slot ->
            register(
                slot.discipleId,
                SlotWinner(slot.discipleId, SlotCategory.BATTLE_TEAM, team.id, slot.index)
            )
        }
    }
    data.productionSlots.forEach { slot ->
        register(
            slot.assignedDiscipleId,
            SlotWinner(
                slot.assignedDiscipleId.orEmpty(),
                SlotCategory.PRODUCTION_SLOT, slot.buildingType.name, slot.slotIndex
            )
        )
    }
}

private fun registerDirectList(
    list: List<DirectDiscipleSlot>,
    prefix: String,
    register: (String?, SlotWinner) -> Unit
) {
    list.forEachIndexed { i, slot ->
        register(
            slot.discipleId,
            SlotWinner(slot.discipleId.orEmpty(), SlotCategory.ELDER_POSITION, prefix, i)
        )
    }
}

/** 登记单个长老字段槽位（slotType 即字段名，供重写时定位）。 */
private fun registerElderField(
    id: String?,
    type: String,
    register: (String?, SlotWinner) -> Unit
) {
    register(id, SlotWinner(id.orEmpty(), SlotCategory.ELDER_POSITION, type))
}

/** 登记秘境活跃成员（赢家=秘境时成员保留在会话中，清理只清岗位槽）。 */
private fun registerSecretRealmMembers(
    data: GameData,
    register: (String?, SlotWinner) -> Unit
) {
    if (!data.secretRealmSession.isActive) return
    data.secretRealmSession.members.forEach { member ->
        register(
            member.discipleId,
            SlotWinner(
                member.discipleId, SlotCategory.EXPLORATION_TEAM,
                SECRET_REALM_SLOT_TYPE, -1
            )
        )
    }
}
