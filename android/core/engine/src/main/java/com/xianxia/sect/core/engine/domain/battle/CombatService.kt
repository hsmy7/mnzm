package com.xianxia.sect.core.engine.domain.battle

import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.accessoryId
import com.xianxia.sect.core.model.armorId
import com.xianxia.sect.core.model.bootsId
import com.xianxia.sect.core.model.griefEndYear
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.model.weaponId
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.event.DeathEvent
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.engine.domain.disciple.battleWritebackMaxHpMp
import com.xianxia.sect.core.engine.domain.disciple.areRelatives
import com.xianxia.sect.core.engine.domain.disciple.applyGriefToRelatives



@Singleton
class CombatService @Inject constructor(
    private val stateStore: GameStateStore,
    private val productionSlotRepository: ProductionSlotRepository,
    private val eventBus: EventBusPort,
    // 死亡统一入口（袋物品物化回仓库 + markDead）
    private val inventorySystem: com.xianxia.sect.core.engine.system.InventorySystem
) {

    // ==================== StateFlow 暴露 ====================

    /**
     * Get battle logs StateFlow
     */
    fun getBattleLogs(): StateFlow<List<BattleLog>> = stateStore.battleLogs

    // ==================== 战斗结果处理 ====================

    /**
     * Process battle casualties - update disciples status and handle deaths.
     *
     * 所有状态写入（弟子标记、装备、槽位、HP/MP）在单次 [stateStore.update] 事务中完成，
     * 避免中途失败导致数据不一致。
     */
    suspend fun processBattleCasualties(
        deadMemberIds: Set<String>,
        survivorHpMap: Map<String, Int>,
        survivorMpMap: Map<String, Int> = emptyMap(),
        isOutsideSect: Boolean = true
    ) {
        // ── 阶段 1：只读收集（事务外） ──
        // 收集死亡弟子信息、装备/功法ID、槽位更新、幸存者HP/MP
        val collected = collectCasualtyData(deadMemberIds, isOutsideSect)
        val griefUpdates = collected.griefUpdates
        val proficiencyRemoveIds = collected.proficiencyRemoveIds
        val equipIdsToUnequip = collected.equipIdsToUnequip
        val manualIdsToUnlearn = collected.manualIdsToUnlearn
        val disciplesToKill = collected.disciplesToKill
        // slot/HP/年份更新已移入 stateStore.update 内部（锁内读取最新状态）

        // ── 阶段 2：单事务原子写入 ──
        val deadDisciples = collected.deadDisciples
        val hasCasualtyEffects = griefUpdates.isNotEmpty() || deadMemberIds.isNotEmpty() ||
            proficiencyRemoveIds.isNotEmpty() || equipIdsToUnequip.isNotEmpty() ||
            manualIdsToUnlearn.isNotEmpty()
        if (hasCasualtyEffects) {
            stateStore.update {
                val battleCurrentYear = gameData.gameYear
                val liveElderSlots = computeElderSlotUpdates(gameData, deadMemberIds)
                val liveSpiritMineSlots = gameData.spiritMineSlots.map { slot ->
                    if (slot.discipleId in deadMemberIds) slot.copy(discipleId = "", discipleName = "") else slot
                }
                val liveLibrarySlots = gameData.librarySlots.map { slot ->
                    if (slot.discipleId in deadMemberIds) slot.copy(discipleId = "", discipleName = "") else slot
                }
                val liveSurvivorUpdates = computeSurvivorUpdates(
                    state = this, survivorHpMap = survivorHpMap,
                    survivorMpMap = survivorMpMap, deadMemberIds = deadMemberIds
                )
                // A. 悲痛期
                applyGriefUpdatesToTables(state = this, griefUpdates = griefUpdates, deadDisciples = deadDisciples)
                // B. 标记死亡（统一入口——袋物品物化回仓库 + 清袋 + markDead）
                markCasualtiesDead(
                    state = this, disciplesToKill = disciplesToKill,
                    battleCurrentYear = battleCurrentYear
                )
                // C. 装备/功法/熟练度
                removeCasualtyItems(
                    state = this, proficiencyRemoveIds = proficiencyRemoveIds,
                    equipIdsToUnequip = equipIdsToUnequip, manualIdsToUnlearn = manualIdsToUnlearn
                )
                // D. 槽位清理
                gameData = gameData.copy(
                    elderSlots = liveElderSlots,
                    spiritMineSlots = liveSpiritMineSlots,
                    librarySlots = liveLibrarySlots,
                    // 生产槽镜像清理（镜像残留会让死弟子在读档重建/自愈时重新挂回生产界面）
                    productionSlots = gameData.productionSlots.map {
                        if (it.assignedDiscipleId in deadMemberIds)
                            it.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                        else it
                    }
                )
                // E. 幸存者HP/MP
                applySurvivorHpMpUpdates(state = this, liveSurvivorUpdates = liveSurvivorUpdates)
            }
        }

        // ── 阶段 3：跨 Repository 写入（无法纳入 stateStore 事务） ──
        clearDeadFromProductionRepository(deadMemberIds)
    }

    /** 幸存者 HP/MP 更新计算（processBattleCasualties 拆分，锁内读取最新状态） */
    private fun computeSurvivorUpdates(
        state: MutableGameState,
        survivorHpMap: Map<String, Int>,
        survivorMpMap: Map<String, Int>,
        deadMemberIds: Set<String>
    ): List<SurvivorUpdate> {
        return survivorHpMap.mapNotNull { (memberId, hp) ->
            val id = memberId.toIntOrNull() ?: return@mapNotNull null
            if (!state.discipleTables.ids.contains(id) || memberId in deadMemberIds) return@mapNotNull null
            // clamp 上限用含血炼口径，防削血
            val (finalMaxHp, finalMaxMp) = DiscipleStatCalculator.battleWritebackMaxHpMp(
                state, state.discipleTables.assemble(id)
            )
            val mp = survivorMpMap[memberId] ?: state.discipleTables.currentMps[id]
            val currentStatus = state.discipleTables.statuses[id]
            val updatedStatus = if (currentStatus in setOf(DiscipleStatus.IN_TEAM,
                DiscipleStatus.GARRISONING)) DiscipleStatus.IDLE else currentStatus
            SurvivorUpdate(id, hp.coerceIn(0, finalMaxHp), mp.coerceIn(0, finalMaxMp), updatedStatus)
        }
    }

    /** 悲痛期写入：GriefEndYear + 丧亲日志 */
    @Suppress("NestedBlockDepth")
    private fun applyGriefUpdatesToTables(
        state: MutableGameState,
        griefUpdates: List<Pair<Int, Int>>,
        deadDisciples: List<Disciple>
    ) {
        // A. 悲痛期
        for ((id, griefEndYear) in griefUpdates) {
            if (id in state.discipleTables.ids) {
                val wasGrieving = state.discipleTables.griefEndYears
                    .getOrDefault(id, DiscipleTables.GRIEF_YEAR_NULL_SENTINEL) > 0
                state.discipleTables.griefEndYears[id] = griefEndYear
                // 记录丧亲日志（仅新陷入悲痛时）
                if (!wasGrieving) {
                    val grievingAge = state.discipleTables.ages[id]
                    // 查找致悲的死亡弟子
                    val deadDisciple = deadDisciples.firstOrNull { dead ->
                        if (dead.id.toIntOrNull() == null) return@firstOrNull false
                        val grievingDisciple = state.discipleTables.assemble(id)
                        DiscipleStatCalculator.areRelatives(
                            grievingDisciple, dead
                        )
                    }
                    if (deadDisciple != null) {
                        val relationship = when {
                            state.discipleTables.partnerIds.getOrNull(id) == deadDisciple.id -> "道侣"
                            deadDisciple.id == state.discipleTables.partnerIds.getOrNull(id) -> "道侣"
                            listOfNotNull(
                                state.discipleTables.parentId1s.getOrNull(id),
                                state.discipleTables.parentId2s.getOrNull(id)
                            ).contains(deadDisciple.id) -> "父/母"
                            deadDisciple.id == state.discipleTables.parentId1s.getOrNull(id) ||
                            deadDisciple.id == state.discipleTables.parentId2s.getOrNull(id) -> "子女"
                            else -> "亲属"
                        }
                        val currentEvents = state.discipleTables.lifeEvents
                            .getOrDefault(id, emptyList())
                        state.discipleTables.lifeEvents[id] = currentEvents +
                            "${grievingAge}岁：因${relationship}${deadDisciple.name}离世陷入悲痛，修炼速度降低50%"
                    }
                }
            }
        }
    }

    /** 阵亡标记：统一入口（袋物品物化回仓库 + 清袋 + markDead） */
    private fun markCasualtiesDead(
        state: MutableGameState,
        disciplesToKill: Map<Int, Disciple>,
        battleCurrentYear: Int
    ) {
        // B. 标记死亡（统一入口——袋物品物化回仓库 + 清袋 + markDead）
        for ((id, _) in disciplesToKill) {
            inventorySystem.materializeDiscipleBagAndMarkDead(state, id, battleCurrentYear, "battle")
        }
    }

    /** 阵亡装备/功法/熟练度清理 */
    private fun removeCasualtyItems(
        state: MutableGameState,
        proficiencyRemoveIds: Set<String>,
        equipIdsToUnequip: Set<String>,
        manualIdsToUnlearn: Set<String>
    ) {
        // C. 装备/功法/熟练度
        if (proficiencyRemoveIds.isNotEmpty()) {
            val mutable = state.gameData.manualProficiencies.toMutableMap()
            proficiencyRemoveIds.forEach { mutable.remove(it) }
            state.gameData = state.gameData.copy(manualProficiencies = mutable)
        }
        if (equipIdsToUnequip.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances.filter { it.id !in equipIdsToUnequip }
        }
        if (manualIdsToUnlearn.isNotEmpty()) {
            state.manualInstances = state.manualInstances.filter { it.id !in manualIdsToUnlearn }
        }
    }

    /** 幸存者 HP/MP 回写 */
    private fun applySurvivorHpMpUpdates(
        state: MutableGameState,
        liveSurvivorUpdates: List<SurvivorUpdate>
    ) {
        // E. 幸存者HP/MP
        for (su in liveSurvivorUpdates) {
            if (su.id in state.discipleTables.ids) {
                state.discipleTables.currentHps[su.id] = su.hp
                state.discipleTables.currentMps[su.id] = su.mp
            }
        }
    }

    /** 阶段 3 — 跨 Repository 清理：全建筑生产槽移除阵亡弟子 */
    private suspend fun clearDeadFromProductionRepository(deadMemberIds: Set<String>) {
        // 全建筑清理（含进行中工作槽）：弟子已阵亡，生产中断；
        // 只清单一槽位会让炼丹/灵田槽残留（死弟子继续显示在生产界面）。
        val allSlots = productionSlotRepository.getSlots()
        for (slot in allSlots) {
            if (slot.assignedDiscipleId in deadMemberIds) {
                productionSlotRepository.updateSlotByBuildingId(slot.buildingId, slot.slotIndex) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
        }
    }

    /** 伤亡结算的只读收集结果（阶段 1） */
    private data class CasualtyData(
        val deadDisciples: List<Disciple>,
        val griefUpdates: List<Pair<Int, Int>>,
        val proficiencyRemoveIds: Set<String>,
        val equipIdsToUnequip: Set<String>,
        val manualIdsToUnlearn: Set<String>,
        val disciplesToKill: Map<Int, Disciple>
    )

    /**
     * 阶段 1 — 只读收集（事务外）：悲痛期、死亡弟子装备/功法 ID、死亡事件广播。
     */
    private fun collectCasualtyData(
        deadMemberIds: Set<String>,
        isOutsideSect: Boolean
    ): CasualtyData {
        val deadDisciples = stateStore.discipleTables.ids
            .filter { it.toString() in deadMemberIds }
            .map { stateStore.discipleTables.assemble(it) }
        val griefUpdates = collectGriefUpdates(stateStore, deadDisciples)

        val proficiencyRemoveIds = mutableSetOf<String>()
        val equipIdsToUnequip = mutableSetOf<String>()
        val manualIdsToUnlearn = mutableSetOf<String>()
        val disciplesToKill = mutableMapOf<Int, Disciple>()

        deadMemberIds.forEach { memberId ->
            val id = memberId.toIntOrNull() ?: return@forEach
            if (!stateStore.discipleTables.ids.contains(id)) return@forEach
            val disciple = stateStore.discipleTables.assemble(id)
            disciplesToKill[id] = disciple

            if (isOutsideSect) {
                eventBus.emitSync(DeathEvent(disciple.id, disciple.name, "战斗阵亡"))
                proficiencyRemoveIds.add(disciple.id)
            } else {
                collectInSectItemLoss(disciple, equipIdsToUnequip, manualIdsToUnlearn)
                proficiencyRemoveIds.add(disciple.id)
            }
        }
        return CasualtyData(
            deadDisciples, griefUpdates, proficiencyRemoveIds, equipIdsToUnequip, manualIdsToUnlearn, disciplesToKill
        )
    }

    // 幸存者HP/MP更新数据
    private data class SurvivorUpdate(val id: Int, val hp: Int, val mp: Int, val newStatus: DiscipleStatus)

    // 计算阵亡弟子相关的 Elder 槽位更新
    private fun computeElderSlotUpdates(
        data: GameData,
        deadMemberIds: Set<String>
    ): ElderSlots {
        var updated = data.elderSlots
        if (updated.lawEnforcementElder in deadMemberIds)
            updated = updated.copy(lawEnforcementElder = "")
        updated = updated.copy(
            lawEnforcementDisciples = clearDeadDirectSlots(updated.lawEnforcementDisciples, deadMemberIds)
        )
        if (updated.viceSectMaster in deadMemberIds) updated = updated.copy(viceSectMaster = "")
        if (updated.innerElder in deadMemberIds) updated = updated.copy(innerElder = "")
        if (updated.outerElder in deadMemberIds) updated = updated.copy(outerElder = "")
        if (updated.preachingElder in deadMemberIds) updated = updated.copy(preachingElder = "")
        if (updated.herbGardenElder in deadMemberIds) updated = updated.copy(herbGardenElder = "")
        if (updated.alchemyElder in deadMemberIds) updated = updated.copy(alchemyElder = "")
        if (updated.forgeElder in deadMemberIds) updated = updated.copy(forgeElder = "")
        if (updated.qingyunPreachingElder in deadMemberIds) updated = updated.copy(qingyunPreachingElder = "")
        updated = updated.copy(
            preachingMasters = clearDeadDirectSlots(updated.preachingMasters, deadMemberIds),
            qingyunPreachingMasters = clearDeadDirectSlots(updated.qingyunPreachingMasters, deadMemberIds),
            herbGardenDisciples = clearDeadDirectSlots(updated.herbGardenDisciples, deadMemberIds),
            alchemyDisciples = clearDeadDirectSlots(updated.alchemyDisciples, deadMemberIds),
            forgeDisciples = clearDeadDirectSlots(updated.forgeDisciples, deadMemberIds),
            spiritMineDeaconDisciples = clearDeadDirectSlots(updated.spiritMineDeaconDisciples, deadMemberIds)
        )
        return updated
    }

    // ==================== 统计查询 ====================

    /**
     * Get total battles fought
     */
    fun getTotalBattlesCount(): Int {
        return stateStore.battleLogs.value.size
    }

    /**
     * Get recent battle results (last N)
     */
    fun getRecentBattles(count: Int = 10): List<BattleLog> {
        return stateStore.battleLogs.value.take(count)
    }

    /**
     * Get win rate for last N battles
     */
    fun getWinRate(lastNBattles: Int = 50): Double {
        val recentBattles = stateStore.battleLogs.value.take(lastNBattles)
        if (recentBattles.isEmpty()) return 0.0

        val wins = recentBattles.count { it.result == BattleResult.WIN }
        return wins.toDouble() / recentBattles.size
    }
}

/** 悲痛期更新收集：亲属悲痛年份映射（仅存活在册弟子） */
private fun collectGriefUpdates(
    stateStore: GameStateStore,
    deadDisciples: List<Disciple>
): List<Pair<Int, Int>> {
    if (deadDisciples.isEmpty()) return emptyList()
    val currentDiscipleList = stateStore.discipleTables.assembleAll()
    val updatedList = DiscipleStatCalculator.applyGriefToRelatives(
        currentDiscipleList, deadDisciples, stateStore.gameData.value.gameYear
    )
    return updatedList.mapNotNull { d ->
        val id = d.id.toInt()
        val griefYear = d.social.griefEndYear ?: return@mapNotNull null
        if (stateStore.discipleTables.ids.contains(id))
            id to griefYear
        else null
    }
}

/** 宗门内阵亡的装备/功法回收收集：四槽装备 + 储物袋装备/功法 */
private fun collectInSectItemLoss(
    disciple: Disciple,
    equipIdsToUnequip: MutableSet<String>,
    manualIdsToUnlearn: MutableSet<String>
) {
    val returnEquipIds = mutableListOf<String>()
    disciple.equipment.weaponId?.let { returnEquipIds.add(it) }
    disciple.equipment.armorId?.let { returnEquipIds.add(it) }
    disciple.equipment.bootsId?.let { returnEquipIds.add(it) }
    disciple.equipment.accessoryId?.let { returnEquipIds.add(it) }
    disciple.equipment.storageBagItems
        .filter { it.itemType == "equipment_stack" || it.itemType == "equipment_instance" }
        .forEach { returnEquipIds.add(it.itemId) }
    equipIdsToUnequip.addAll(returnEquipIds)
    manualIdsToUnlearn.addAll(disciple.manualIds)
    disciple.equipment.storageBagItems
        .filter { it.itemType == "manual_stack" || it.itemType == "manual_instance" }
        .forEach { manualIdsToUnlearn.add(it.itemId) }
}

/** 直接弟子槽位清空：阵亡弟子槽位重置为空槽 */
private fun clearDeadDirectSlots(
    slots: List<DirectDiscipleSlot>,
    deadMemberIds: Set<String>
): List<DirectDiscipleSlot> = slots.mapNotNull { slot ->
    if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
}
