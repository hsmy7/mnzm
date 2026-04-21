@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.engine.BattleSystem
import com.xianxia.sect.core.engine.CaveExplorationSystem
import com.xianxia.sect.core.engine.BattleSystemResult
import com.xianxia.sect.core.event.DeathEvent
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.di.ApplicationScopeProvider
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@SystemPriority(order = 230)
@Singleton
class CombatService @Inject constructor(
    private val stateStore: GameStateStore,
    private val battleSystem: BattleSystem,
    private val productionSlotRepository: ProductionSlotRepository,
    private val eventService: EventService,
    private val eventBus: EventBus,
    private val applicationScopeProvider: ApplicationScopeProvider
) : GameSystem {
    override val systemName: String = "CombatService"
    private val scope get() = applicationScopeProvider.scope

    override fun initialize() {
        Log.d(TAG, "CombatService initialized as GameSystem")
    }

    override fun release() {
        Log.d(TAG, "CombatService released")
    }

    override suspend fun clear() {}
    private var currentGameData: GameData
        get() = stateStore.currentTransactionMutableState()?.gameData ?: stateStore.gameData.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.gameData = value; return }
            scope.launch { stateStore.update { gameData = value } }
        }

    private var currentDisciples: List<Disciple>
        get() = stateStore.currentTransactionMutableState()?.disciples ?: stateStore.disciples.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.disciples = value; return }
            scope.launch { stateStore.update { disciples = value } }
        }
    private var currentEquipmentInstances: List<EquipmentInstance>
        get() = stateStore.currentTransactionMutableState()?.equipmentInstances ?: stateStore.equipmentInstances.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.equipmentInstances = value; return }
            scope.launch { stateStore.update { equipmentInstances = value } }
        }
    private var currentManualInstances: List<ManualInstance>
        get() = stateStore.currentTransactionMutableState()?.manualInstances ?: stateStore.manualInstances.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.manualInstances = value; return }
            scope.launch { stateStore.update { manualInstances = value } }
        }

    private var currentBattleLogs: List<BattleLog>
        get() = stateStore.currentTransactionMutableState()?.battleLogs ?: stateStore.battleLogs.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.battleLogs = value; return }
            scope.launch { stateStore.update { battleLogs = value } }
        }

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
        val data = currentGameData

        val equipmentMap = currentEquipmentInstances.associateBy { it.id }
        val manualMap = currentManualInstances.associateBy { it.id }
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
        val data = currentGameData

        val equipmentMap = currentEquipmentInstances.associateBy { it.id }
        val manualMap = currentManualInstances.associateBy { it.id }
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
        deadMemberIds.forEach { memberId ->
            val discipleIndex = currentDisciples.indexOfFirst { it.id == memberId }
            if (discipleIndex < 0) return@forEach

            val disciple = currentDisciples[discipleIndex]
            val updatedDisciple = disciple.copy(isAlive = false, status = DiscipleStatus.IDLE)
            currentDisciples = currentDisciples.toMutableList().also { it[discipleIndex] = updatedDisciple }

            eventService.addGameEvent("${disciple.name} 在战斗中阵亡", EventType.DANGER)

            if (isOutsideSect) {
                eventBus.emitSync(DeathEvent(disciple.id, disciple.name, "战斗阵亡"))
                val updatedProficiencies = currentGameData.manualProficiencies.toMutableMap()
                updatedProficiencies.remove(disciple.id)
                if (updatedProficiencies != currentGameData.manualProficiencies) {
                    currentGameData = currentGameData.copy(manualProficiencies = updatedProficiencies)
                }
            } else {
                val returnEquipIds = mutableListOf<String>()
                disciple.weaponId?.let { returnEquipIds.add(it) }
                disciple.armorId?.let { returnEquipIds.add(it) }
                disciple.bootsId?.let { returnEquipIds.add(it) }
                disciple.accessoryId?.let { returnEquipIds.add(it) }
                disciple.storageBagItems.filter { it.itemType == "equipment_stack" || it.itemType == "equipment_instance" }.forEach { returnEquipIds.add(it.itemId) }

                returnEquipIds.forEach { eid ->
                    val eq = currentEquipmentInstances.find { it.id == eid } ?: return@forEach
                    currentEquipmentInstances = currentEquipmentInstances.map { e ->
                        if (e.id == eid) e.copy(isEquipped = false, ownerId = null) else e
                    }
                }

                disciple.manualIds.forEach { manualId ->
                    currentManualInstances = currentManualInstances.map {
                        if (it.id == manualId) it.copy(isLearned = false, ownerId = null) else it
                    }
                }
                disciple.storageBagItems.filter { it.itemType == "manual_stack" || it.itemType == "manual_instance" }.forEach { bagItem ->
                    currentManualInstances = currentManualInstances.map {
                        if (it.id == bagItem.itemId) it.copy(isLearned = false, ownerId = null) else it
                    }
                }
                val updatedProficiencies = currentGameData.manualProficiencies.toMutableMap()
                updatedProficiencies.remove(disciple.id)
                if (updatedProficiencies != currentGameData.manualProficiencies) {
                    currentGameData = currentGameData.copy(manualProficiencies = updatedProficiencies)
                }
            }
        }

        // 清理阵亡弟子的所有槽位
        if (deadMemberIds.isNotEmpty()) {
            val data = currentGameData

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

            currentGameData = data.copy(
                elderSlots = updatedElderSlots,
                spiritMineSlots = updatedSpiritMineSlots,
                librarySlots = updatedLibrarySlots
            )

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
            val discipleIndex = currentDisciples.indexOfFirst { it.id == memberId }
            if (discipleIndex >= 0 && !deadMemberIds.contains(memberId)) {
                val disciple = currentDisciples[discipleIndex]
                val mp = survivorMpMap[memberId] ?: disciple.currentMp
                val updatedStatus = if (disciple.status == DiscipleStatus.IN_TEAM) DiscipleStatus.IDLE else disciple.status
                val updatedDisciple = disciple.copyWith(
                    status = updatedStatus,
                    currentHp = hp,
                    currentMp = mp
                )
                currentDisciples = currentDisciples.toMutableList().also { it[discipleIndex] = updatedDisciple }
            }
        }
    }

    // ==================== 统计查询 ====================

    /**
     * Get total battles fought
     */
    fun getTotalBattlesCount(): Int {
        return currentBattleLogs.size
    }

    /**
     * Get recent battle results (last N)
     */
    fun getRecentBattles(count: Int = 10): List<BattleLog> {
        return currentBattleLogs.take(count)
    }

    /**
     * Get win rate for last N battles
     */
    fun getWinRate(lastNBattles: Int = 50): Double {
        val recentBattles = currentBattleLogs.take(lastNBattles)
        if (recentBattles.isEmpty()) return 0.0

        val wins = recentBattles.count { it.result == BattleResult.WIN }
        return wins.toDouble() / recentBattles.size
    }
}
