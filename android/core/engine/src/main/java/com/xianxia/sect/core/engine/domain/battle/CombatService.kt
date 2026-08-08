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
import javax.inject.Inject
import javax.inject.Singleton



@Singleton
class CombatService @Inject constructor(
    private val stateStore: GameStateStore,
    private val battleSystem: BattleSystem,
    private val productionSlotRepository: ProductionSlotRepository,
    private val eventBus: EventBusPort,
    private val cultivationService: com.xianxia.sect.core.engine.service.CultivationService,
    // D-03：死亡统一入口（袋物品物化回仓库 + markDead）
    private val inventorySystem: com.xianxia.sect.core.engine.system.InventorySystem
) {

    companion object {
        private const val TAG = "CombatService"
    }

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
        if (griefUpdates.isNotEmpty() || deadMemberIds.isNotEmpty() ||
            proficiencyRemoveIds.isNotEmpty() || equipIdsToUnequip.isNotEmpty() || manualIdsToUnlearn.isNotEmpty()) {
            stateStore.update {
                val battleCurrentYear = gameData.gameYear
                val liveElderSlots = computeElderSlotUpdates(gameData, deadMemberIds)
                val liveSpiritMineSlots = gameData.spiritMineSlots.map { slot ->
                    if (slot.discipleId in deadMemberIds) slot.copy(discipleId = "", discipleName = "") else slot
                }
                val liveLibrarySlots = gameData.librarySlots.map { slot ->
                    if (slot.discipleId in deadMemberIds) slot.copy(discipleId = "", discipleName = "") else slot
                }
                val liveSurvivorUpdates = survivorHpMap.mapNotNull { (memberId, hp) ->
                    val id = memberId.toIntOrNull() ?: return@mapNotNull null
                    if (!discipleTables.ids.contains(id) || memberId in deadMemberIds) return@mapNotNull null
                    // clamp 上限用含血炼口径（P2 对抗性审查修复），防削血
                    val (finalMaxHp, finalMaxMp) = DiscipleStatCalculator.battleWritebackMaxHpMp(
                        this, discipleTables.assemble(id)
                    )
                    val mp = survivorMpMap[memberId] ?: discipleTables.currentMps[id]
                    val currentStatus = discipleTables.statuses[id]
                    val updatedStatus = if (currentStatus in setOf(DiscipleStatus.IN_TEAM, DiscipleStatus.GARRISONING)) DiscipleStatus.IDLE else currentStatus
                    SurvivorUpdate(id, hp.coerceIn(0, finalMaxHp), mp.coerceIn(0, finalMaxMp), updatedStatus)
                }
                // A. 悲痛期
                for ((id, griefEndYear) in griefUpdates) {
                    if (id in discipleTables.ids) {
                        val wasGrieving = discipleTables.griefEndYears
                            .getOrDefault(id, DiscipleTables.GRIEF_YEAR_NULL_SENTINEL) > 0
                        discipleTables.griefEndYears[id] = griefEndYear
                        // 记录丧亲日志（仅新陷入悲痛时）
                        if (!wasGrieving) {
                            val grievingAge = discipleTables.ages[id]
                            // 查找致悲的死亡弟子
                            val deadDisciple = deadDisciples.firstOrNull { dead ->
                                val deadId = dead.id.toIntOrNull() ?: return@firstOrNull false
                                val grievingDisciple = discipleTables.assemble(id)
                                DiscipleStatCalculator.areRelatives(
                                    grievingDisciple, dead
                                )
                            }
                            if (deadDisciple != null) {
                                val relationship = when {
                                    discipleTables.partnerIds.getOrNull(id) == deadDisciple.id -> "道侣"
                                    deadDisciple.id == discipleTables.partnerIds.getOrNull(id) -> "道侣"
                                    listOfNotNull(
                                        discipleTables.parentId1s.getOrNull(id),
                                        discipleTables.parentId2s.getOrNull(id)
                                    ).contains(deadDisciple.id) -> "父/母"
                                    deadDisciple.id == discipleTables.parentId1s.getOrNull(id) ||
                                    deadDisciple.id == discipleTables.parentId2s.getOrNull(id) -> "子女"
                                    else -> "亲属"
                                }
                                val currentEvents = discipleTables.lifeEvents
                                    .getOrDefault(id, emptyList())
                                discipleTables.lifeEvents[id] = currentEvents +
                                    "${grievingAge}岁：因${relationship}${deadDisciple.name}离世陷入悲痛，修炼速度降低50%"
                            }
                        }
                    }
                }

                // B. 标记死亡（D-03：统一入口——袋物品物化回仓库 + 清袋 + markDead）
                for ((id, _) in disciplesToKill) {
                    inventorySystem.materializeDiscipleBagAndMarkDead(this, id, battleCurrentYear, "battle")
                }

                // C. 装备/功法/熟练度
                if (proficiencyRemoveIds.isNotEmpty()) {
                    val mutable = gameData.manualProficiencies.toMutableMap()
                    proficiencyRemoveIds.forEach { mutable.remove(it) }
                    gameData = gameData.copy(manualProficiencies = mutable)
                }
                if (equipIdsToUnequip.isNotEmpty()) {
                    equipmentInstances = equipmentInstances.filter { it.id !in equipIdsToUnequip }
                }
                if (manualIdsToUnlearn.isNotEmpty()) {
                    manualInstances = manualInstances.filter { it.id !in manualIdsToUnlearn }
                }

                // D. 槽位清理
                gameData = gameData.copy(
                    elderSlots = liveElderSlots,
                    spiritMineSlots = liveSpiritMineSlots,
                    librarySlots = liveLibrarySlots
                )

                // E. 幸存者HP/MP
                for (su in liveSurvivorUpdates) {
                    if (su.id in discipleTables.ids) {
                        discipleTables.currentHps[su.id] = su.hp
                        discipleTables.currentMps[su.id] = su.mp
                    }
                }
            }
        }

        // ── 阶段 3：跨 Repository 写入（无法纳入 stateStore 事务） ──
        val forgeSlots = productionSlotRepository.getSlotsByBuildingId("forge")
        for (slot in forgeSlots) {
            if (slot.assignedDiscipleId in deadMemberIds && !slot.isWorking) {
                productionSlotRepository.updateSlotByBuildingId("forge", slot.slotIndex) { s ->
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
        val griefUpdates: List<Pair<Int, Int>> = if (deadDisciples.isNotEmpty()) {
            val currentDiscipleList = stateStore.discipleTables.assembleAll()
            val updatedList = DiscipleStatCalculator.applyGriefToRelatives(
                currentDiscipleList, deadDisciples, stateStore.gameData.value.gameYear
            )
            updatedList.mapNotNull { d ->
                val id = d.id.toInt()
                val griefYear = d.social.griefEndYear ?: return@mapNotNull null
                if (stateStore.discipleTables.ids.contains(id))
                    id to griefYear
                else null
            }
        } else emptyList()

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
            lawEnforcementDisciples = updated.lawEnforcementDisciples.mapNotNull { slot ->
                if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
            }
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
            preachingMasters = updated.preachingMasters.mapNotNull { slot ->
                if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
            },
            qingyunPreachingMasters = updated.qingyunPreachingMasters.mapNotNull { slot ->
                if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
            },
            herbGardenDisciples = updated.herbGardenDisciples.mapNotNull { slot ->
                if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
            },
            alchemyDisciples = updated.alchemyDisciples.mapNotNull { slot ->
                if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
            },
            forgeDisciples = updated.forgeDisciples.mapNotNull { slot ->
                if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
            },
            spiritMineDeaconDisciples = updated.spiritMineDeaconDisciples.mapNotNull { slot ->
                if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
            }
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
