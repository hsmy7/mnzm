@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.di.ApplicationScopeProvider
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

data class GameStateSnapshot(
    val gameYear: Int = 1,
    val gameMonth: Int = 1,
    val gameDay: Int = 1,
    val isGameStarted: Boolean = false,
    val gameSpeed: Int = 1,
    val sectName: String = "",
    val spiritStones: Long = 0,
    val sectCultivation: Double = 0.0,
    val discipleCount: Int = 0,
    val equipmentCount: Int = 0,
    val manualCount: Int = 0,
    val pillCount: Int = 0,
    val materialCount: Int = 0,
    val herbCount: Int = 0,
    val seedCount: Int = 0,
    val explorationTeamCount: Int = 0,
    val caveExplorationTeamCount: Int = 0,
    val forgeSlotCount: Int = 0,
    val alchemySlotCount: Int = 0,
    val worldMapSectCount: Int = 0,
    val allianceCount: Int = 0,
    val eventCount: Int = 0,
    val battleLogCount: Int = 0,
    val caveCount: Int = 0,
    val activeCaveExplorationCount: Int = 0,
    val lastUpdated: Long = 0L
)

@SystemPriority(order = 900)
@Singleton
class SaveService @Inject constructor(
    private val stateStore: GameStateStore,
    private val productionSlotRepository: ProductionSlotRepository,
    private val applicationScopeProvider: ApplicationScopeProvider
) : GameSystem {
    override val systemName: String = "SaveService"
    private val scope get() = applicationScopeProvider.scope

    override fun initialize() {
        Log.d(TAG, "SaveService initialized as GameSystem")
    }

    override fun release() {
        Log.d(TAG, "SaveService released")
    }

    override suspend fun clear() {}

    companion object {
        private const val TAG = "SaveService"
    }

    fun getStateSnapshotSync(): GameStateSnapshot {
        val data = stateStore.gameData.value

        return GameStateSnapshot(
            gameYear = data.gameYear,
            gameMonth = data.gameMonth,
            gameDay = data.gameDay,
            isGameStarted = data.isGameStarted,
            gameSpeed = data.gameSpeed,
            sectName = data.sectName,
            spiritStones = data.spiritStones,
            sectCultivation = data.sectCultivation,
            discipleCount = stateStore.disciples.value.size,
            equipmentCount = stateStore.equipment.value.size,
            manualCount = stateStore.manuals.value.size,
            pillCount = stateStore.pills.value.size,
            materialCount = stateStore.materials.value.size,
            herbCount = stateStore.herbs.value.size,
            seedCount = stateStore.seeds.value.size,
            explorationTeamCount = stateStore.teams.value.size,
            caveExplorationTeamCount = data.caveExplorationTeams.size,
            forgeSlotCount = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.FORGE).count { it.isWorking },
            alchemySlotCount = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.ALCHEMY).count { it.isWorking },
            worldMapSectCount = data.worldMapSects.size,
            allianceCount = data.alliances.size,
            eventCount = stateStore.events.value.size,
            battleLogCount = stateStore.battleLogs.value.size,
            caveCount = data.cultivatorCaves.count { it.status == CaveStatus.AVAILABLE },
            activeCaveExplorationCount = data.caveExplorationTeams.count {
                it.status == CaveExplorationStatus.EXPLORING ||
                it.status == CaveExplorationStatus.TRAVELING
            },
            lastUpdated = System.currentTimeMillis()
        )
    }

    suspend fun getStateSnapshot(): GameStateSnapshot {
        return getStateSnapshotSync()
    }

    fun prepareForLoad() {
        scope.launch { stateStore.reset() }
        android.util.Log.d(TAG, "Prepared state for loading from save")
    }

    fun restoreFromLoad(loadedGameData: GameData) {
        scope.launch { stateStore.update { gameData = loadedGameData } }
        android.util.Log.d(TAG, "Restored state from save: year=${loadedGameData.gameYear}, disciples count would be set separately")
    }

    fun restoreCollections(
        disciples: List<Disciple>,
        equipment: List<Equipment>,
        manuals: List<Manual>,
        pills: List<Pill>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>,
        events: List<GameEvent>,
        battleLogs: List<BattleLog>,
        teams: List<ExplorationTeam>
    ) {
        scope.launch {
            stateStore.update {
                this.disciples = disciples
                this.equipment = equipment
                this.manuals = manuals
                this.pills = pills
                this.materials = materials
                this.herbs = herbs
                this.seeds = seeds
                this.events = events
                this.battleLogs = battleLogs
                this.teams = teams
            }
        }
        android.util.Log.d(TAG, "Restored collections: ${disciples.size} disciples, ${equipment.size} equipment")
    }

    fun validateState(): List<String> {
        val errors = mutableListOf<String>()
        val data = stateStore.gameData.value

        if (data.gameYear < 0) {
            errors.add("Invalid game year: ${data.gameYear}")
        }
        if (data.gameMonth < 1 || data.gameMonth > 12) {
            errors.add("Invalid game month: ${data.gameMonth}")
        }
        if (data.gameDay < 1 || data.gameDay > 30) {
            errors.add("Invalid game day: ${data.gameDay}")
        }

        val discipleIds = stateStore.disciples.value.map { it.id }.toSet()
        productionSlotRepository.getSlotsByBuildingId("forge").forEach { slot ->
            slot.assignedDiscipleId?.let { discipleId ->
                if (!discipleIds.contains(discipleId)) {
                    errors.add("ProductionSlot(forge) references non-existent disciple: $discipleId")
                }
            }
        }

        val equipmentIds = stateStore.equipment.value.map { it.id }
        val duplicateEquipmentIds = equipmentIds.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (duplicateEquipmentIds.isNotEmpty()) {
            errors.add("Duplicate equipment IDs found: $duplicateEquipmentIds")
        }

        return errors
    }

    fun getStateStatistics(): Map<String, Any> {
        val data = stateStore.gameData.value

        return mapOf(
            "gameYear" to data.gameYear,
            "gameMonth" to data.gameMonth,
            "spiritStones" to data.spiritStones,
            "sectCultivation" to data.sectCultivation,
            "discipleCount" to stateStore.disciples.value.size,
            "aliveDisciples" to stateStore.disciples.value.count { it.isAlive },
            "equipmentCount" to stateStore.equipment.value.size,
            "equippedEquipment" to stateStore.equipment.value.count { it.isEquipped },
            "manualCount" to stateStore.manuals.value.size,
            "pillCount" to stateStore.pills.value.size,
            "materialCount" to stateStore.materials.value.size,
            "herbCount" to stateStore.herbs.value.size,
            "seedCount" to stateStore.seeds.value.size,
            "eventCount" to stateStore.events.value.size,
            "battleLogCount" to stateStore.battleLogs.value.size,
            "explorationTeams" to stateStore.teams.value.size,
            "caveExplorations" to data.caveExplorationTeams.size,
            "activeForgingSlots" to productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.FORGE).count { it.isWorking },
            "activeAlchemySlots" to productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.ALCHEMY).count { it.isWorking },
            "worldSects" to data.worldMapSects.size,
            "alliances" to data.alliances.size,
            "cultivatorCaves" to data.cultivatorCaves.size
        )
    }

    fun isGameStarted(): Boolean {
        return stateStore.gameData.value.isGameStarted
    }

    fun getFormattedGameTime(): String {
        val data = stateStore.gameData.value
        return "${data.gameYear}年${data.gameMonth}月${data.gameDay}日"
    }
}
