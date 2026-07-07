package com.xianxia.sect.ui.game.saveload

import android.util.Log
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.ensureHeavyDataLoaded
import com.xianxia.sect.core.engine.loadData
import com.xianxia.sect.core.engine.updateGameData
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.core.engine.domain.save.SavePipeline
import kotlinx.coroutines.*

/**
 * 存档加载委托 — 管理读档流程。
 */
class SaveLoadLoadDelegate(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    private val storageFacade: StorageFacade,
    private val stateStore: GameStateStore,
    private val savePipeline: SavePipeline,
    private val buildingConfigService: BuildingConfigService
) {
    private val TAG = "SaveLoadLoadDelegate"
    private var _isGameLoaded = false
    val isGameLoaded: Boolean get() = _isGameLoaded

    var uiCallbacks: UiCallbacks? = null

    interface UiCallbacks {
        fun showError(message: String)
        fun showSuccess(message: String)
        fun onLoadComplete(slot: Int)
        suspend fun onPreloadResources()
        suspend fun setLoadingState(isLoading: Boolean, slot: Int, action: String?)
    }

    suspend fun loadGame(saveSlot: SaveSlot): Boolean {
        if (stateStore.unifiedState.value.isLoading) {
            Log.w(TAG, "Already loading, ignoring loadGame request")
            return false
        }

        uiCallbacks?.let { cb ->
            cb.setLoadingState(isLoading = true, slot = saveSlot.slot, action = "load")
        }

        return try {
            if (_isGameLoaded) {
                Log.i(TAG, "Game already loaded, will reload from slot ${saveSlot.slot}")
                gameEngineCore.stopGameLoop()
                _isGameLoaded = false
            }

            savePipeline.waitForCurrentSave(timeoutMs = 5_000L)

            val saveData = withTimeoutOrNull(60_000L) {
                try {
                    storageFacade.load(saveSlot.slot).getOrNull()
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                    Log.e(TAG, "Error loading save data: ${e.message}", e)
                    null
                }
            }

            if (saveData == null) {
                uiCallbacks?.showError("读档超时或存档为空，请重试")
                return false
            }

            val effectiveSlot = StorageConstants.resolveEffectiveSlot(saveSlot.slot)
            storageFacade.setCurrentSlot(effectiveSlot)
            gameEngine.loadData(
                gameData = saveData.gameData.copy(currentSlot = effectiveSlot),
                disciples = saveData.disciples,
                equipmentStacks = saveData.equipmentStacks,
                equipmentInstances = saveData.equipmentInstances,
                manualStacks = saveData.manualStacks,
                manualInstances = saveData.manualInstances,
                pills = saveData.pills,
                materials = saveData.materials,
                herbs = saveData.herbs,
                seeds = saveData.seeds,
                storageBags = saveData.storageBags,
                teams = saveData.teams,
                battleLogs = saveData.battleLogs,
                alliances = saveData.alliances,
                productionSlots = saveData.productionSlots
            )
            gameEngine.ensureHeavyDataLoaded()

            // 修正建筑尺寸（×2 后兼容旧存档）
            gameEngine.updateGameData { data ->
                if (data.placedBuildings.isEmpty() && data.residenceSlots.isNotEmpty()) {
                    Log.wtf(TAG, "DATA INTEGRITY: placedBuildings empty but residenceSlots " +
                        "has ${data.residenceSlots.size} entries!")
                }
                val fixed = buildingConfigService.fixupBuildingSizes(data.placedBuildings)
                val withIds = GridBuildingData.ensureAllHaveInstanceId(fixed)
                if (withIds != data.placedBuildings) data.copy(placedBuildings = withIds) else data
            }

            // 建筑占地×2 后旧存档溢出迁移
            migrateOverflowBuildings()

            uiCallbacks?.onPreloadResources()
            _isGameLoaded = true
            uiCallbacks?.showSuccess("读档成功")

            Log.i(TAG, "loadGame SUCCESS: sectName=${saveData.gameData.sectName}")
            true
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            Log.e(TAG, "loadGame ERROR: ${e.message}", e)
            uiCallbacks?.showError("读档错误：${e.message}")
            false
        } finally {
            uiCallbacks?.setLoadingState(isLoading = false, slot = saveSlot.slot, action = null)
        }
    }

    // ================================================================
    // 建筑占地×2 迁移
    // ================================================================

    /** 灵田显示名（占地尺寸不变，迁移中优先保留） */
    private companion object {
        const val SPIRIT_FIELD_NAME = "灵田"
    }

    /**
     * 将新占地尺寸（×2）下放不下的建筑拆除，全额返还灵石，弟子恢复空闲。
     */
    private suspend fun migrateOverflowBuildings() {
        stateStore.update {
            val buildings = gameData.placedBuildings
            if (buildings.isEmpty()) return@update

            val result = computeBuildingOverflowMigration(
                buildings = buildings,
                gameData = gameData,
                buildingConfigService = buildingConfigService
            )
            if (result.demolished.isEmpty()) return@update

            Log.i(TAG, "旧存档建筑占地迁移：${result.demolished.size} 座建筑因空间不足被拆除，" +
                "返还灵石×${result.totalRefund}，解放弟子 ${result.freedDiscipleIds.size} 人")

            applyBuildingMigration(result)
        }
    }

    /**
     * 纯函数：计算哪些建筑放不下，返回拆除结果。
     * 可单独测试，不依赖 MutableGameState。
     */
    internal data class MigrationResult(
        val kept: List<GridBuildingData>,
        val demolished: List<GridBuildingData>,
        val totalRefund: Long,
        val freedDiscipleIds: Set<String>
    )

    internal fun computeBuildingOverflowMigration(
        buildings: List<GridBuildingData>,
        gameData: GameData,
        buildingConfigService: BuildingConfigService
    ): MigrationResult {
        val gridW = GameConfig.SectMap.WORLD_WIDTH_CELLS
        val gridH = GameConfig.SectMap.WORLD_HEIGHT_CELLS

        val sorted = buildings.sortedByDescending { b ->
            if (b.displayName == SPIRIT_FIELD_NAME) Long.MAX_VALUE
            else buildingConfigService.getBuildingConfigByDisplayName(b.displayName)?.cost ?: 1000L
        }

        val occupied = mutableSetOf<Long>()
        val kept = mutableListOf<GridBuildingData>()
        val demolished = mutableListOf<GridBuildingData>()
        var totalRefund = 0L
        val freedDiscipleIds = mutableSetOf<String>()

        for (b in sorted) {
            val cost = buildingConfigService.getBuildingConfigByDisplayName(
                b.displayName)?.cost ?: 1000L

            if (!canPlaceAt(b, gridW, gridH, occupied)) {
                demolished.add(b)
                totalRefund += cost
                collectFreedDiscipleIds(b, freedDiscipleIds, gameData)
                continue
            }
            markOccupied(b, occupied)
            kept.add(b)
        }

        return MigrationResult(
            kept = kept,
            demolished = demolished,
            totalRefund = totalRefund,
            freedDiscipleIds = freedDiscipleIds
        )
    }

    /** 检查建筑是否在地图内且不与其他建筑重叠 */
    private fun canPlaceAt(
        b: GridBuildingData,
        gridW: Int,
        gridH: Int,
        occupied: Set<Long>
    ): Boolean {
        if (b.gridX < 0 || b.gridY < 0 ||
            b.gridX + b.width > gridW ||
            b.gridY + b.height > gridH
        ) return false
        for (cx in b.gridX until b.gridX + b.width) {
            for (cy in b.gridY until b.gridY + b.height) {
                if (packCell(cx, cy) in occupied) return false
            }
        }
        return true
    }

    /** 标记建筑占据的格子 */
    private fun markOccupied(b: GridBuildingData, occupied: MutableSet<Long>) {
        for (cx in b.gridX until b.gridX + b.width) {
            for (cy in b.gridY until b.gridY + b.height) {
                occupied.add(packCell(cx, cy))
            }
        }
    }

    /** 将 (x, y) 格子编码为 Long（与 GridSystem.packCell 一致） */
    private fun packCell(x: Int, y: Int): Long =
        (x.toLong() shl 32) or (y.toLong() and 0xFFFF_FFFF)

    /** 收集被拆除建筑中已分配弟子的 ID */
    private fun collectFreedDiscipleIds(
        building: GridBuildingData,
        ids: MutableSet<String>,
        gameData: GameData
    ) {
        val name = building.displayName
        val instanceId = building.instanceId
        when {
            name == "炼丹炉" || name == "锻造坊" -> {
                val bid = if (name == "炼丹炉")
                    com.xianxia.sect.core.util.BuildingNames.ALCHEMY
                else
                    com.xianxia.sect.core.util.BuildingNames.FORGE
                gameData.productionSlots
                    .filter { it.buildingInstanceId == instanceId && it.buildingId == bid }
                    .mapNotNull { it.assignedDiscipleId }
                    .filter { it.isNotEmpty() }
                    .forEach { ids.add(it) }
            }
            name.contains("住所") -> gameData.residenceSlots
                .filter { it.buildingInstanceId == instanceId }
                .mapNotNull { it.discipleId }.filter { it.isNotEmpty() }
                .forEach { ids.add(it) }
            name == "仓库" -> gameData.warehouseGarrisons
                .filter { it.buildingInstanceId == instanceId }
                .mapNotNull { it.discipleId }.filter { it.isNotEmpty() }
                .forEach { ids.add(it) }
            name == "灵矿场" -> gameData.spiritMineSlots
                .filter { it.buildingInstanceId == instanceId }
                .mapNotNull { it.discipleId }.filter { it.isNotEmpty() }
                .forEach { ids.add(it) }
            name == "巡视楼" -> gameData.patrolSlots
                .filter { it.buildingInstanceId == instanceId }
                .mapNotNull { it.discipleId }.filter { it.isNotEmpty() }
                .forEach { ids.add(it) }
            name == "血炼池" -> gameData.activeBloodRefinements[instanceId]
                ?.discipleId?.takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        }
    }

    /** 应用迁移结果到 MutableGameState */
    private fun MutableGameState.applyBuildingMigration(
        result: MigrationResult
    ) {
        var gd = gameData.copy(
            placedBuildings = result.kept,
            spiritStones = gameData.spiritStones + result.totalRefund
        )
        val removedIds = result.demolished.map { it.instanceId }.toSet()
        gd = cleanupOrphanedSlots(gd, removedIds)
        gameData = gd

        for (didStr in result.freedDiscipleIds) {
            val id = didStr.toIntOrNull() ?: continue
            if (discipleTables.ids.contains(id)) {
                discipleTables.statuses[id] = DiscipleStatus.IDLE
            }
        }
    }

    /** 清除已拆除建筑的关联槽位数据 */
    private fun cleanupOrphanedSlots(
        gd: GameData,
        removedInstanceIds: Set<String>
    ): GameData {
        return gd.copy(
            productionSlots = gd.productionSlots.filter {
                it.buildingInstanceId !in removedInstanceIds
            },
            residenceSlots = gd.residenceSlots.filter {
                it.buildingInstanceId !in removedInstanceIds
            },
            spiritMineSlots = gd.spiritMineSlots.filter {
                it.buildingInstanceId !in removedInstanceIds
            },
            patrolSlots = gd.patrolSlots.filter {
                it.buildingInstanceId !in removedInstanceIds
            },
            warehouseGarrisons = gd.warehouseGarrisons.filter {
                it.buildingInstanceId !in removedInstanceIds
            },
            activeBloodRefinements = gd.activeBloodRefinements.filterKeys {
                it !in removedInstanceIds
            }
        )
    }
}
