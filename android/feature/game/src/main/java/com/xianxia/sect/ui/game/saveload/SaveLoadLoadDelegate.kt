package com.xianxia.sect.ui.game.saveload

import android.util.Log
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.applyBuildingMigrationOnEngine
import com.xianxia.sect.core.engine.ensureHeavyDataLoaded
import com.xianxia.sect.core.engine.loadData
import com.xianxia.sect.core.engine.updateGameData
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.model.SaveData
import kotlinx.coroutines.*

/**
 * 存档加载委托 — 管理读档流程。
 */
class SaveLoadLoadDelegate(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    private val storageFacade: StorageFacade,
    private val stateStore: GameStateStore,
    private val buildingConfigService: BuildingConfigService,
    private val spiritStoneWallet: SpiritStoneWallet
) {
    private val TAG = "SaveLoadLoadDelegate"

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
            if (stateStore.runState.value == RunState.PLAYING) {
                Log.i(TAG, "Game already loaded, will reload from slot ${saveSlot.slot}")
                gameEngineCore.stopGameLoop()
            }

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

            val effectiveSlot = saveSlot.slot
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
     *
     * **注意：** 须按 `sectId` 分组检测，不同宗门的建筑使用独立网格，坐标互不干扰。
     * 若混合检测，占领宗门内坐标与主营地重合的建筑会被误拆（# 架构债务修复）。
     */
    internal suspend fun migrateOverflowBuildings() {
        // 2026-08-01：计算（纯函数）留在主线程，状态应用走引擎线程入口
        // （修复前直接 stateStore.update 主线程直写，违反双线程模型）
        // 2026-08-01 对抗性审查修复：统一读 gameDataSnapshot（实时快照）——
        // unifiedState 是 sample(50) 批处理派生流，加载界面无订阅时恒为旧会话值，
        // 会拿旧布局覆盖新档建筑（毁档级）。实时快照与 loadData 后的状态一致。
        val gd = stateStore.gameDataSnapshot
        val allBuildings = gd.placedBuildings
        if (allBuildings.isEmpty()) return

        val buildingsBySect = allBuildings.groupBy { it.sectId }
        val allKept = mutableListOf<GridBuildingData>()
        var totalRefund = 0L
        val allFreedDiscipleIds = mutableSetOf<String>()

        for ((_, sectBuildings) in buildingsBySect) {
            val result = computeBuildingOverflowMigration(
                buildings = sectBuildings,
                gameData = gd,
                buildingConfigService = buildingConfigService
            )
            allKept.addAll(result.kept)
            totalRefund += result.totalRefund
            allFreedDiscipleIds.addAll(result.freedDiscipleIds)
        }

        if (allKept.size == allBuildings.size) return  // 无建筑被拆除

        Log.i(TAG, "旧存档建筑占地迁移：${allBuildings.size - allKept.size} 座建筑因空间不足被拆除，" +
            "返还灵石×${totalRefund}，解放弟子 ${allFreedDiscipleIds.size} 人")

        gameEngine.applyBuildingMigrationOnEngine(
            kept = allKept,
            totalRefund = totalRefund,
            freedDiscipleIds = allFreedDiscipleIds
        )
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
            // 空名称建筑无配置可查，退路造价为 0（防经济不一致）
            val cost = if (b.displayName.isBlank()) 0L
                else buildingConfigService.getBuildingConfigByDisplayName(
                    b.displayName)?.cost ?: 1000L

            if (!canPlaceAt(b, gridW, gridH, occupied)) {
                demolished.add(b)
                // 饱和加法防止溢出导致灵石变为负数
                if (totalRefund > Long.MAX_VALUE - cost) totalRefund = Long.MAX_VALUE
                else totalRefund += cost
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
        // 零/负尺寸建筑无法占格，视为不可放置
        if (b.width <= 0 || b.height <= 0) return false
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

    /** 收集被拆除建筑中已分配弟子的 ID（通过 BuildingFeatureRegistry + SlotGroup） */
    private fun collectFreedDiscipleIds(
        building: GridBuildingData,
        ids: MutableSet<String>,
        gameData: GameData
    ) {
        val feature = com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
            .findByDisplayName(building.displayName) ?: return
        ids.addAll(feature.slotGroups.flatMap { it.collectDiscipleIds(gameData, building.instanceId, feature) })
    }

}
