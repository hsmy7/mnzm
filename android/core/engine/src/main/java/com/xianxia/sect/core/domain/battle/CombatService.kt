package com.xianxia.sect.core.engine.domain.battle

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.exploration.CaveExplorationSystem
import com.xianxia.sect.core.event.DeathEvent
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.DomainLog
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CombatService @Inject constructor(
    private val stateStore: GameStateStore,
    private val battleSystem: BattleSystem,
    private val productionSlotRepository: ProductionSlotRepository,
private val eventBus: EventBusPort,
    private val cultivationService: com.xianxia.sect.core.engine.service.CultivationService
) {

    companion object {
        private const val TAG = "CombatService"
    }

    // ==================== StateFlow 暴露 ====================

    /**
     * Get battle logs StateFlow
     */
    fun getBattleLogs(): StateFlow<List<BattleLog>> = stateStore.battleLogs

    // ==================== 战斗执行 ====================

    /**
     * Execute cave exploration battle against AI team
     */
    fun executeCaveAIBattle(
        playerDisciples: List<Disciple>,
        aiTeam: AICaveTeam
    ): BattleSystemResult {
        // 战斗前兜底：先结算参战弟子气血灵力恢复
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) cultivationService.recoverHpMpForBattleParticipants(ts, playerDisciples.map { it.id })
        val data = stateStore.gameData.value

        val equipmentMap = stateStore.equipmentInstances.value.associateBy { it.id }
        val manualMap = stateStore.manualInstances.value.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        val battle = CaveExplorationSystem.createAIBattle(
            playerDisciples = playerDisciples,
            playerEquipmentMap = equipmentMap,
            playerManualMap = manualMap,
            playerManualProficiencies = allProficiencies,
            aiTeam = aiTeam
        )

        return battleSystem.executeBattle(battle)
    }

    /**
     * Execute cave guardian battle
     */
    fun executeCaveGuardianBattle(
        playerDisciples: List<Disciple>,
        cave: CultivatorCave
    ): BattleSystemResult {
        // 战斗前兜底：先结算参战弟子气血灵力恢复
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) cultivationService.recoverHpMpForBattleParticipants(ts, playerDisciples.map { it.id })
        val data = stateStore.gameData.value

        val equipmentMap = stateStore.equipmentInstances.value.associateBy { it.id }
        val manualMap = stateStore.manualInstances.value.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        val battle = CaveExplorationSystem.createGuardianBattle(
            playerDisciples = playerDisciples,
            playerEquipmentMap = equipmentMap,
            playerManualMap = manualMap,
            playerManualProficiencies = allProficiencies,
            cave = cave
        )

        return battleSystem.executeBattle(battle)
    }

    // ==================== 战斗结果处理 ====================

    /**
     * Process battle casualties - update disciples status and handle deaths
     */
    suspend fun processBattleCasualties(
        deadMemberIds: Set<String>,
        survivorHpMap: Map<String, Int>,
        survivorMpMap: Map<String, Int> = emptyMap(),
        isOutsideSect: Boolean = true
    ) {
        // 亲人逝世影响：为所有存活亲属设置悲痛期
        val deadDisciples = stateStore.discipleTables.ids.filter { it.toString() in deadMemberIds }.map { stateStore.discipleTables.assemble(it) }
        if (deadDisciples.isNotEmpty()) {
            val currentDiscipleList = stateStore.discipleTables.assembleAll()
            val updatedDiscipleList = DiscipleStatCalculator.applyGriefToRelatives(
                currentDiscipleList, deadDisciples, stateStore.gameData.value.gameYear
            )
            // 将更新后的悲痛期写回组件表
            for (d in updatedDiscipleList) {
                val id = d.id.toInt()
                if (stateStore.discipleTables.ids.contains(id)) {
                    stateStore.discipleTables.griefEndYears[id] = d.social.griefEndYear
                }
            }
        }

        // Collect IDs for batch state store update
        val proficiencyRemoveIds = mutableSetOf<String>()
        val equipIdsToUnequip = mutableSetOf<String>()
        val manualIdsToUnlearn = mutableSetOf<String>()

        deadMemberIds.forEach { memberId ->
            val id = memberId.toIntOrNull()
            if (id == null || !stateStore.discipleTables.ids.contains(id)) return@forEach

            val disciple = stateStore.discipleTables.assemble(id)
            stateStore.discipleTables.isAlive[id] = 0
            stateStore.discipleTables.statuses[id] = DiscipleStatus.DEAD

            if (isOutsideSect) {
                eventBus.emitSync(DeathEvent(disciple.id, disciple.name, "战斗阵亡"))
                proficiencyRemoveIds.add(disciple.id)
            } else {
                val returnEquipIds = mutableListOf<String>()
                disciple.equipment.weaponId?.let { returnEquipIds.add(it) }
                disciple.equipment.armorId?.let { returnEquipIds.add(it) }
                disciple.equipment.bootsId?.let { returnEquipIds.add(it) }
                disciple.equipment.accessoryId?.let { returnEquipIds.add(it) }
                disciple.equipment.storageBagItems.filter { it.itemType == "equipment_stack" || it.itemType == "equipment_instance" }.forEach { returnEquipIds.add(it.itemId) }

                equipIdsToUnequip.addAll(returnEquipIds)
                manualIdsToUnlearn.addAll(disciple.manualIds)
                disciple.equipment.storageBagItems.filter {
                    it.itemType == "manual_stack" || it.itemType == "manual_instance"
                }.forEach { manualIdsToUnlearn.add(it.itemId) }
                proficiencyRemoveIds.add(disciple.id)
            }
        }

        // Batch state update for equipment, manuals, proficiencies
        if (proficiencyRemoveIds.isNotEmpty() || equipIdsToUnequip.isNotEmpty() || manualIdsToUnlearn.isNotEmpty()) {
            stateStore.update {
                if (proficiencyRemoveIds.isNotEmpty()) {
                    val mutable = gameData.manualProficiencies.toMutableMap()
                    proficiencyRemoveIds.forEach { mutable.remove(it) }
                    gameData = gameData.copy(manualProficiencies = mutable)
                }
                if (equipIdsToUnequip.isNotEmpty()) {
                    equipmentInstances = equipmentInstances.map { e ->
                        if (e.id in equipIdsToUnequip) e.copy(isEquipped = false, ownerId = null) else e
                    }
                }
                if (manualIdsToUnlearn.isNotEmpty()) {
                    manualInstances = manualInstances.map { m ->
                        if (m.id in manualIdsToUnlearn) m.copy(isLearned = false, ownerId = null) else m
                    }
                }
            }
        }

        // 清理阵亡弟子的所有槽位
        if (deadMemberIds.isNotEmpty()) {
            val data = stateStore.gameData.value

            val updatedElderSlots = data.elderSlots.let { slots ->
                var updated = slots

                if (updated.lawEnforcementElder in deadMemberIds) {
                    updated = updated.copy(lawEnforcementElder = "")
                }

                updated = updated.copy(
                    lawEnforcementDisciples = updated.lawEnforcementDisciples.mapNotNull { slot ->
                        if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                    },
                    lawEnforcementReserveDisciples = updated.lawEnforcementReserveDisciples.mapNotNull { slot ->
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
                    herbGardenReserveDisciples = updated.herbGardenReserveDisciples.mapNotNull { slot ->
                        if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                    },
                    alchemyDisciples = updated.alchemyDisciples.mapNotNull { slot ->
                        if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                    },
                    alchemyReserveDisciples = updated.alchemyReserveDisciples.mapNotNull { slot ->
                        if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                    },
                    forgeDisciples = updated.forgeDisciples.mapNotNull { slot ->
                        if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                    },
                    forgeReserveDisciples = updated.forgeReserveDisciples.mapNotNull { slot ->
                        if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                    },
                    spiritMineDeaconDisciples = updated.spiritMineDeaconDisciples.mapNotNull { slot ->
                        if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                    }
                )

                updated
            }

            val updatedSpiritMineSlots = data.spiritMineSlots.map { slot ->
                if (slot.discipleId in deadMemberIds) slot.copy(discipleId = "", discipleName = "") else slot
            }

            val updatedLibrarySlots = data.librarySlots.map { slot ->
                if (slot.discipleId in deadMemberIds) slot.copy(discipleId = "", discipleName = "") else slot
            }

            stateStore.update {
                gameData = data.copy(
                    elderSlots = updatedElderSlots,
                    spiritMineSlots = updatedSpiritMineSlots,
                    librarySlots = updatedLibrarySlots
                )
            }

            val forgeSlots = productionSlotRepository.getSlotsByBuildingId("forge")
            for (slot in forgeSlots) {
                if (slot.assignedDiscipleId in deadMemberIds && !slot.isWorking) {
                    productionSlotRepository.updateSlotByBuildingId("forge", slot.slotIndex) { s ->
                        s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                    }
                }
            }
        }

        // Update HP/MP for survivors
        survivorHpMap.forEach { (memberId, hp) ->
            val id = memberId.toIntOrNull()
            if (id != null && stateStore.discipleTables.ids.contains(id) && !deadMemberIds.contains(memberId)) {
                val maxHp = stateStore.discipleTables.baseHps[id]
                val maxMp = stateStore.discipleTables.baseMps[id]
                val mp = survivorMpMap[memberId] ?: stateStore.discipleTables.currentMps[id]
                val currentStatus = stateStore.discipleTables.statuses[id]
                val updatedStatus = if (currentStatus in setOf(DiscipleStatus.IN_TEAM, DiscipleStatus.GARRISONING)) DiscipleStatus.IDLE else currentStatus
                stateStore.discipleTables.currentHps[id] = hp.coerceIn(0, maxHp)
                stateStore.discipleTables.currentMps[id] = mp.coerceIn(0, maxMp)
                stateStore.discipleTables.statuses[id] = updatedStatus
            }
        }
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
