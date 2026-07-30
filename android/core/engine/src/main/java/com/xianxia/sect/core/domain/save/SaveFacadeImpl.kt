package com.xianxia.sect.core.engine.domain.save

import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.engine.WorldMapGenerator
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.GameRngManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveFacadeImpl @Inject constructor(
    private val saveService: SaveService,
    private val stateStore: GameStateStore,
    private val productionCoordinator: ProductionCoordinator,
    private val gameRngManager: GameRngManager
) : SaveFacade {

    /**
     * 存档前防御性校验：如果 worldMapSects 意外为空，从配置表紧急重生。
     * 正常情况下此检查为无操作，仅在数据管线异常时触发。
     * 注意：此方法必须在 getStateSnapshotSync() 之前同步执行完成，
     * 确保 snapshot 包含已修复的数据。
     */
    private fun validateWorldMapSectsBeforeSave() {
        val gd = stateStore.gameDataSnapshot
        // 阶段1：列表为空 → 重生
        if (gd.worldMapSects.isEmpty()) {
            regenerateSectsBeforeSave(gd.sectName)
            return
        }
        // 阶段2：列表非空但缺少玩家宗门 → 修复重生
        if (gd.worldMapSects.none { it.isPlayerSect }) {
            DomainLog.e("SaveFacade", "存档前检测到 worldMapSects 缺少玩家宗门，" +
                "同步重生 sectName=${gd.sectName}")
            regenerateSectsBeforeSave(gd.sectName)
        }
    }

    private fun SaveFacadeImpl.regenerateSectsBeforeSave(sectName: String) {
        if (sectName.isBlank()) {
            DomainLog.e("SaveFacade", "存档前 worldMapSects 缺失且 sectName 为空，" +
                "无法重生宗门数据，存档将包含不完整宗门列表")
            return
        }
        DomainLog.e("SaveFacade", "存档前同步重生 worldMapSects sectName=$sectName")
        val generationResult = WorldMapGenerator.generateWorldSects(sectName)
        val sectRelations = WorldMapGenerator.initializeSectRelations(generationResult.sects)
        // 同步更新：getStateSnapshotSync 在同一线程调用，确保 snapshot 包含修复后的数据
        stateStore.update {
            val current = this.gameData
            this.gameData = current.copy(
                worldMapSects = generationResult.sects,
                sectRelations = sectRelations,
                aiSectDisciples = if (current.aiSectDisciples.isEmpty()) generationResult.aiSectDisciples
                    else current.aiSectDisciples
            )
        }
        DomainLog.w("SaveFacade", "worldMapSects 同步重生完成，" +
            "sects=${generationResult.sects.size}")
    }

    override fun getStateSnapshotSync(): GameStateSnapshot {
        validateWorldMapSectsBeforeSave()
        // 导出 RNG 分区状态到 gameData，确保存档包含当前 PRNG 快照
        val exportedRng = gameRngManager.exportStates()
        val gd = stateStore.gameDataSnapshot
        return GameStateSnapshot(
            gameData = gd.copy(rngStates = exportedRng),
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
        // 导出 RNG 分区状态到 gameData，确保存档包含当前 PRNG 快照
        val exportedRng = gameRngManager.exportStates()
        val gd = stateStore.gameDataSnapshot
        return GameStateSnapshot(
            gameData = gd.copy(rngStates = exportedRng),
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
    override fun getFormattedGameTime(): String = saveService.getFormattedGameTime()
}
