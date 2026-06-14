package com.xianxia.sect.core.engine.domain.save

import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.engine.WorldMapGenerator
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveFacadeImpl @Inject constructor(
    private val saveService: SaveService,
    private val stateStore: GameStateStore,
    private val productionCoordinator: ProductionCoordinator,
    private val coroutineScope: CoroutineScope
) : SaveFacade {

    /**
     * 存档前防御性校验：如果 worldMapSects 意外为空，从配置表紧急重生。
     * 正常情况下此检查为无操作，仅在数据管线异常时触发。
     */
    private fun validateWorldMapSectsBeforeSave() {
        val gd = stateStore.gameDataSnapshot
        if (gd.worldMapSects.isNotEmpty()) return

        val sectName = gd.sectName
        if (sectName.isBlank()) {
            DomainLog.e("SaveFacade", "存档前检测到 worldMapSects 为空且 sectName 为空，" +
                "无法重生宗门数据，存档将包含空宗门列表")
            return
        }

        DomainLog.e("SaveFacade", "存档前检测到 worldMapSects 为空，" +
            "从 FixedSectPositions 紧急重生 sectName=$sectName")
        val generationResult = WorldMapGenerator.generateWorldSects(sectName)
        val sectRelations = WorldMapGenerator.initializeSectRelations(generationResult.sects)
        coroutineScope.launch {
            stateStore.update {
                val current = this.gameData
                this.gameData = current.copy(
                    worldMapSects = generationResult.sects,
                    sectRelations = sectRelations,
                    aiSectDisciples = if (current.aiSectDisciples.isEmpty()) generationResult.aiSectDisciples
                        else current.aiSectDisciples
                )
            }
        }
        DomainLog.w("SaveFacade", "worldMapSects 紧急重生已触发，" +
            "sects=${generationResult.sects.size}")
    }

    override fun getStateSnapshotSync(): GameStateSnapshot {
        validateWorldMapSectsBeforeSave()
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
        validateWorldMapSectsBeforeSave()
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
