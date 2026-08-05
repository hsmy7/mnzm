package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog
import kotlin.coroutines.cancellation.CancellationException

// GameEngineSelfHealOps.kt — 旧档双槽位数据自愈
// （SlotWinner 见 SlotWinner.kt）

/** 秘境队伍占用的 gate 槽位类型名（与 GameEngineSecretRealmOps 一致，非持久化） */
private const val SECRET_REALM_SLOT_TYPE = "secret_realm"

/**
 * 读档自愈：清理旧存档中"同一弟子出现在多个槽位"的残留数据。
 *
 * 按 [com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate] 的
 * scanAndRegister 顺序扫描，首个出现的槽位为赢家（保留），清空该弟子其余全部槽位后
 * 重写赢家。住所与工作共存是有意设计，扫描与清理均排除住所。
 *
 * 对健康存档（无重复弟子）零副作用——不修改任何数据。
 * 调用方应在 [com.xianxia.sect.core.engine.BootSequenceController] Step 6
 * rebuildFromGameData 之后调用，随后二次 rebuild 使注册表与自愈后数据一致。
 */
@Suppress("TooGenericExceptionCaught")
fun GameEngine.healDuplicateSlotAssignments() {
    try {
        gameEngineCore.launchInScope {
            try {
                val winners = mutableMapOf<String, SlotWinner>()
                val counts = mutableMapOf<String, Int>()
                stateStore.update {
                    collectSlotWinners(gameData, winners, counts)
                    val duplicates = counts.filterValues { it > 1 }.keys
                    if (duplicates.isEmpty()) return@update
                    DomainLog.d(
                        "GameEngine",
                        "healDuplicateSlots: 发现双槽位弟子 ${duplicates.size} 名，清理中"
                    )
                    for (discipleId in duplicates) {
                        val winner = winners[discipleId] ?: continue
                        // 血炼赢家：清理前缓存进度（clearAllSlots 会移除
                        // activeBloodRefinements 条目，进度含已消耗灵石/材料，重写时恢复）
                        val bloodProgress = if (winner.category == SlotCategory.BLOOD_REFINEMENT) {
                            gameData.activeBloodRefinements[winner.slotType]
                        } else null
                        // 住所与工作共存是有意设计：自愈只清工作槽位，保留住所
                        // （回归：includeResidence=true 会静默清掉玩家的住所分配）
                        gameData = DiscipleSlotCleanup(assignmentGate)
                            .clearAllSlotsDataOnly(gameData, discipleId)
                        gameData = rewriteWinnerInGameData(gameData, winner, bloodProgress)
                    }
                }
                // 清理涉及 gate 注册表：二次重建使注册表与自愈后数据一致
                // （生产槽走 Room Repository，与 BootSequenceController Step 6 同口径）
                assignmentGate.rebuildFromGameData(
                    gameData = stateStore.gameDataSnapshot,
                    productionSlots = try {
                        // ?: 防御测试/异常路径下 getSlots 返回 null（真实实现永远非空）
                        productionCoordinator.repository.getSlots() ?: emptyList()
                    } catch (e: Exception) {
                        DomainLog.w("GameEngine", "healDuplicateSlots: 读取生产槽失败，按空处理", e)
                        emptyList()
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainLog.e("GameEngine", "healDuplicateSlotAssignments 失败", e)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 防御：BootSequenceController 在 mock GameEngine 上调用时 gameEngineCore 为 null，
        // 自愈不应影响启动流程
        DomainLog.e("GameEngine", "healDuplicateSlotAssignments 入口失败", e)
    }
}

/** 扫描全部槽位（排除住所），记录每名弟子首次出现的槽位（赢家）与出现次数。 */
private fun MutableGameState.collectSlotWinners(
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

/** 按赢家记录重写回 GameData（槽位数据已在调用前清空）。 */
@Suppress("CyclomaticComplexMethod") // 13 类别确定性分发表（与 SlotCategory 一一对应，分支均为单行委托）
private fun MutableGameState.rewriteWinnerInGameData(
    data: GameData,
    winner: SlotWinner,
    bloodProgress: BloodRefinementProgress? = null
): GameData {
    val name = discipleTables.assemble(winner.discipleId.toIntOrNull() ?: -1)?.name ?: ""
    return when (winner.category) {
        SlotCategory.ELDER_POSITION -> rewriteElderWinner(data, winner, name)
        SlotCategory.SPIRIT_MINE -> data.copy(
            spiritMineSlots = data.spiritMineSlots.mapIndexed { i, slot ->
                if (i == winner.slotIndex) {
                    slot.copy(discipleId = winner.discipleId, discipleName = name)
                } else slot
            }
        )
        SlotCategory.LIBRARY_SLOT -> data.copy(
            librarySlots = data.librarySlots.mapIndexed { i, slot ->
                if (i == winner.slotIndex) {
                    slot.copy(discipleId = winner.discipleId, discipleName = name)
                } else slot
            }
        )
        SlotCategory.WAREHOUSE_GARRISON -> rewriteWarehouseWinner(data, winner, name)
        SlotCategory.PATROL_SLOT -> data.copy(
            patrolSlots = data.patrolSlots.mapIndexed { i, slot ->
                if (i == winner.slotIndex) {
                    slot.copy(discipleId = winner.discipleId, discipleName = name)
                } else slot
            }
        )
        SlotCategory.BLOOD_REFINEMENT -> if (bloodProgress != null) {
            data.copy(
                activeBloodRefinements = data.activeBloodRefinements + (winner.slotType to bloodProgress)
            )
        } else data // 进度已丢失则不强造（换岗语义：血炼视为放弃）
        SlotCategory.GARRISON_SLOT -> rewriteGarrisonWinner(data, winner, name)
        SlotCategory.BATTLE_TEAM -> rewriteBattleTeamWinner(data, winner, name)
        SlotCategory.PRODUCTION_SLOT -> rewriteProductionWinner(data, winner, name)
        SlotCategory.RESIDENCE_SLOT, SlotCategory.EXPLORATION_TEAM -> data // 住所/探索不参与自愈
    }
}

/** 仓库驻守：按 buildingInstanceId（slotType）定位。 */
private fun rewriteWarehouseWinner(data: GameData, winner: SlotWinner, name: String): GameData =
    data.copy(
        warehouseGarrisons = data.warehouseGarrisons.map { slot ->
            if (slot.buildingInstanceId == winner.slotType) {
                slot.copy(discipleId = winner.discipleId, discipleName = name)
            } else slot
        }
    )

/** 世界地图驻守：按 sectId（slotType）+ index 定位。 */
private fun rewriteGarrisonWinner(data: GameData, winner: SlotWinner, name: String): GameData =
    data.copy(
        worldMapSects = data.worldMapSects.map { sect ->
            if (sect.isPlayerSect && sect.id == winner.slotType) {
                sect.copy(garrisonSlots = sect.garrisonSlots.map { slot ->
                    if (slot.index == winner.slotIndex) {
                        slot.copy(discipleId = winner.discipleId, discipleName = name)
                    } else slot
                })
            } else sect
        }
    )

/** 战斗队伍：按 teamId（slotType）+ index 定位。 */
private fun rewriteBattleTeamWinner(data: GameData, winner: SlotWinner, name: String): GameData =
    data.copy(
        battleTeams = data.battleTeams.map { team ->
            if (team.id == winner.slotType) {
                team.copy(slots = team.slots.map { slot ->
                    if (slot.index == winner.slotIndex) {
                        slot.copy(discipleId = winner.discipleId, discipleName = name, isAlive = true)
                    } else slot
                })
            } else team
        }
    )

/** 生产槽：按 buildingType（slotType）+ index 定位。 */
private fun rewriteProductionWinner(data: GameData, winner: SlotWinner, name: String): GameData =
    data.copy(
        productionSlots = data.productionSlots.map { slot ->
            if (slot.buildingType.name == winner.slotType && slot.slotIndex == winner.slotIndex) {
                slot.copy(assignedDiscipleId = winner.discipleId, assignedDiscipleName = name)
            } else slot
        }
    )

/** 按槽位类型重写长老槽位（长老字段 + 亲传列表）。 */
private fun rewriteElderWinner(data: GameData, winner: SlotWinner, name: String): GameData {
    val slots = data.elderSlots
    val updated = when (winner.slotType) {
        "viceSectMaster" -> slots.copy(viceSectMaster = winner.discipleId)
        "herbGardenElder" -> slots.copy(herbGardenElder = winner.discipleId)
        "alchemyElder" -> slots.copy(alchemyElder = winner.discipleId)
        "forgeElder" -> slots.copy(forgeElder = winner.discipleId)
        "outerElder" -> slots.copy(outerElder = winner.discipleId)
        "preachingElder" -> slots.copy(preachingElder = winner.discipleId)
        "lawEnforcementElder" -> slots.copy(lawEnforcementElder = winner.discipleId)
        "innerElder" -> slots.copy(innerElder = winner.discipleId)
        "recruitingElder" -> slots.copy(recruitingElder = winner.discipleId)
        "qingyunPreachingElder" -> slots.copy(qingyunPreachingElder = winner.discipleId)
        else -> rewriteDirectElderList(slots, winner, name)
    }
    return data.copy(elderSlots = updated)
}

/** 重写亲传弟子列表（slotType 前缀区分七类）。 */
private fun rewriteDirectElderList(slots: ElderSlots, winner: SlotWinner, name: String): ElderSlots {
    val newSlot = DirectDiscipleSlot(
        index = winner.slotIndex,
        discipleId = winner.discipleId,
        discipleName = name,
        sectId = ""
    )
    fun setSlot(list: List<DirectDiscipleSlot>): List<DirectDiscipleSlot> {
        val mutable = list.toMutableList()
        while (mutable.size <= winner.slotIndex) mutable.add(DirectDiscipleSlot(index = mutable.size))
        mutable[winner.slotIndex] = newSlot
        return mutable
    }
    return when (winner.slotType) {
        "herbGardenDisciple" -> slots.copy(herbGardenDisciples = setSlot(slots.herbGardenDisciples))
        "alchemyDisciple" -> slots.copy(alchemyDisciples = setSlot(slots.alchemyDisciples))
        "forgeDisciple" -> slots.copy(forgeDisciples = setSlot(slots.forgeDisciples))
        "preachingMaster" -> slots.copy(preachingMasters = setSlot(slots.preachingMasters))
        "lawEnforcementDisciple" -> slots.copy(lawEnforcementDisciples = setSlot(slots.lawEnforcementDisciples))
        "qingyunPreachingMaster" -> slots.copy(qingyunPreachingMasters = setSlot(slots.qingyunPreachingMasters))
        "spiritMineDeacon" -> slots.copy(spiritMineDeaconDisciples = setSlot(slots.spiritMineDeaconDisciples))
        else -> slots
    }
}
