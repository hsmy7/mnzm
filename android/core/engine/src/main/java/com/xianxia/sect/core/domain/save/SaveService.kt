package com.xianxia.sect.core.engine.domain.save

import com.xianxia.sect.core.model.CaveExplorationStatus
import com.xianxia.sect.core.model.CaveStatus
import com.xianxia.sect.core.model.GamePhase
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import javax.inject.Inject
import javax.inject.Singleton







data class GameStateSnapshot(
    val gameYear: Int = 1,
    val gameMonth: Int = 1,
    val gamePhase: Int = 0,
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
    val caveExplorationTeamCount: Int = 0,
    val forgeSlotCount: Int = 0,
    val alchemySlotCount: Int = 0,
    val worldMapSectCount: Int = 0,
    val allianceCount: Int = 0,
    val battleLogCount: Int = 0,
    val caveCount: Int = 0,
    val activeCaveExplorationCount: Int = 0,
    val lastUpdated: Long = 0L
)

@Singleton
class SaveService @Inject constructor(
    private val stateStore: GameStateStore,
    private val productionSlotRepository: ProductionSlotRepository,
    private val scopeProvider: CoroutineScopeProvider
) {
    private val scope get() = scopeProvider.scope

    fun getStateSnapshotSync(): GameStateSnapshot {
        val data = stateStore.gameData.value

        return GameStateSnapshot(
            gameYear = data.gameYear,
            gameMonth = data.gameMonth,
            gamePhase = data.gamePhase,
            // isGameStarted 已迁移到 GameLifecycle 运行时状态，存档快照中始终为 true
            isGameStarted = true,
            gameSpeed = 1,
            sectName = data.sectName,
            spiritStones = data.spiritStones,
            sectCultivation = data.sectCultivation,
            discipleCount = stateStore.disciples.value.size,
            equipmentCount = stateStore.equipmentStacks.value.size + stateStore.equipmentInstances.value.size,
            manualCount = stateStore.manualStacks.value.size + stateStore.manualInstances.value.size,
            pillCount = stateStore.pills.value.size,
            materialCount = stateStore.materials.value.size,
            herbCount = stateStore.herbs.value.size,
            seedCount = stateStore.seeds.value.size,
            caveExplorationTeamCount = data.caveExplorationTeams.size,
            forgeSlotCount = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.FORGE).count { it.isWorking },
            alchemySlotCount = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.ALCHEMY).count { it.isWorking },
            worldMapSectCount = data.worldMapSects.size,
            allianceCount = data.alliances.size,
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

    fun validateState(): List<String> {
        val errors = mutableListOf<String>()
        val data = stateStore.gameData.value

        if (data.gameYear < 0) {
            errors.add("Invalid game year: ${data.gameYear}")
        }
        if (data.gameMonth < 1 || data.gameMonth > 12) {
            errors.add("Invalid game month: ${data.gameMonth}")
        }
        if (data.gamePhase < 0 || data.gamePhase > 2) {
            errors.add("Invalid game phase: ${data.gamePhase}")
        }

        val discipleIds = stateStore.disciples.value.map { it.id }.toSet()
        productionSlotRepository.getSlotsByBuildingId("forge").forEach { slot ->
            slot.assignedDiscipleId?.let { discipleId ->
                if (!discipleIds.contains(discipleId)) {
                    errors.add("ProductionSlot(forge) references non-existent disciple: $discipleId")
                }
            }
        }

        val equipmentStackIds = stateStore.equipmentStacks.value.map { it.id }
        val equipmentInstanceIds = stateStore.equipmentInstances.value.map { it.id }
        val allEquipmentIds = equipmentStackIds + equipmentInstanceIds
        val duplicateEquipmentIds = allEquipmentIds.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
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
            "equipmentCount" to (stateStore.equipmentStacks.value.size + stateStore.equipmentInstances.value.size),
            "equippedEquipment" to stateStore.equipmentInstances.value.count { it.isEquipped },
            "manualCount" to (stateStore.manualStacks.value.size + stateStore.manualInstances.value.size),
            "pillCount" to stateStore.pills.value.size,
            "materialCount" to stateStore.materials.value.size,
            "herbCount" to stateStore.herbs.value.size,
            "seedCount" to stateStore.seeds.value.size,
            "battleLogCount" to stateStore.battleLogs.value.size,
            "caveExplorations" to data.caveExplorationTeams.size,
            "activeForgingSlots" to productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.FORGE).count { it.isWorking },
            "activeAlchemySlots" to productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.ALCHEMY).count { it.isWorking },
            "worldSects" to data.worldMapSects.size,
            "alliances" to data.alliances.size,
            "cultivatorCaves" to data.cultivatorCaves.size
        )
    }

    fun getFormattedGameTime(): String {
        val data = stateStore.gameData.value
        return "${data.gameYear}年${data.gameMonth}月${GamePhase.fromValue(data.gamePhase).displayName}"
    }
}
