package com.xianxia.sect.core.engine.domain.save

import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveFacadeImpl @Inject constructor(
    private val saveService: SaveService,
    private val stateStore: GameStateStore,
    private val productionCoordinator: ProductionCoordinator
) : SaveFacade {

    override fun getStateSnapshotSync(): GameStateSnapshot {
        return GameStateSnapshot(
            gameData = stateStore.gameDataSnapshot,
            disciples = stateStore.disciplesSnapshot,
            equipmentStacks = stateStore.equipmentStacksSnapshot,
            equipmentInstances = stateStore.equipmentInstancesSnapshot,
            manualStacks = stateStore.manualStacksSnapshot,
            manualInstances = stateStore.manualInstancesSnapshot,
            pills = stateStore.pillsSnapshot,
            materials = stateStore.materialsSnapshot,
            herbs = stateStore.herbsSnapshot,
            seeds = stateStore.seedsSnapshot,
            storageBags = stateStore.storageBagsSnapshot,
            teams = stateStore.teamsSnapshot,
            battleLogs = stateStore.battleLogsSnapshot,
            alliances = stateStore.gameDataSnapshot.alliances,
            productionSlots = productionCoordinator.repository.getSlots()
        )
    }

    override suspend fun getStateSnapshot(): GameStateSnapshot {
        val gd = stateStore.gameDataSnapshot
        return GameStateSnapshot(
            gameData = gd,
            disciples = stateStore.disciplesSnapshot,
            equipmentStacks = stateStore.equipmentStacksSnapshot,
            equipmentInstances = stateStore.equipmentInstancesSnapshot,
            manualStacks = stateStore.manualStacksSnapshot,
            manualInstances = stateStore.manualInstancesSnapshot,
            pills = stateStore.pillsSnapshot,
            materials = stateStore.materialsSnapshot,
            herbs = stateStore.herbsSnapshot,
            seeds = stateStore.seedsSnapshot,
            storageBags = stateStore.storageBagsSnapshot,
            teams = stateStore.teamsSnapshot,
            battleLogs = stateStore.battleLogsSnapshot,
            alliances = gd.alliances,
            productionSlots = productionCoordinator.repository.getSlots()
        )
    }

    override suspend fun loadFromSave(
        loadedGameData: GameData,
        disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack>,
        equipmentInstances: List<EquipmentInstance>,
        manualStacks: List<ManualStack>,
        manualInstances: List<ManualInstance>,
        pills: List<Pill>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>,
        battleLogs: List<BattleLog>,
        teams: List<ExplorationTeam>
    ) = saveService.loadFromSave(
        loadedGameData, disciples, equipmentStacks, equipmentInstances, manualStacks, manualInstances, pills,
        materials, herbs, seeds, battleLogs, teams
    )

    override fun validateState(): List<String> = saveService.validateState()
    override fun getStateStatistics(): Map<String, Any> = saveService.getStateStatistics()
    override fun isGameStarted(): Boolean = saveService.isGameStarted()
    override fun getFormattedGameTime(): String = saveService.getFormattedGameTime()
}
