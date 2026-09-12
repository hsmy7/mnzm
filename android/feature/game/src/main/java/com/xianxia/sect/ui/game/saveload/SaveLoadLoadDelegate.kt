package com.xianxia.sect.ui.game.saveload

import android.util.Log
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.ensureHeavyDataLoaded
import com.xianxia.sect.core.engine.loadData
import com.xianxia.sect.core.engine.updateGameData
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.ui.game.SaveLoadViewModelConstants
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
) {
    private companion object {
        private const val TAG = "SaveLoadLoadDelegate"
    }

    var uiCallbacks: UiCallbacks? = null

    interface UiCallbacks {
        fun showError(message: String)
        fun showSuccess(message: String)
        fun onLoadComplete(slot: Int)
        suspend fun onPreloadResources()
        suspend fun setLoadingState(isLoading: Boolean, slot: Int, action: String?)
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    suspend fun loadGame(saveSlot: SaveSlot): Boolean {
        if (stateStore.isLoading.value) {
            Log.w(TAG, "Already loading, ignoring loadGame request")
            return false
        }

        uiCallbacks?.let { cb ->
            cb.setLoadingState(isLoading = true, slot = saveSlot.slot, action = "load")
        }

        return try {
            if (!stopLoopIfRunning(slot = saveSlot.slot)) {
                return false
            }

            val saveData = loadSaveData(slot = saveSlot.slot)
            if (saveData == null) {
                uiCallbacks?.showError("读档超时或存档为空，请重试")
                return false
            }

            applyLoadedSave(saveData = saveData, effectiveSlot = saveSlot.slot)

            // 溢出迁移由 BootSequenceController Step 3.5 执行（须在
            // fixup/归一化之后；本 loadGame 为死代码，仅测试引用）

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

    /** 已加载场景下先停止旧游戏循环：玉符防回退 + 超时守卫 */
    @Suppress("ReturnCount")
    private suspend fun stopLoopIfRunning(slot: Int): Boolean {
        if (stateStore.runState.value != RunState.PLAYING) return true
        Log.i(TAG, "Game already loaded, will reload from slot $slot")
        // 玉符防回退：等待旧循环 finally 彻底完成（与
        // SaveLoadViewModel.performLoadToSlot 同因），非等待 stop 会让
        // checkpointNow 晚于快照替换、用旧运行时值覆盖新档玉符
        val stopped = gameEngineCore.stopGameLoopAndWait(
            SaveLoadViewModelConstants.GAME_LOOP_STOP_TIMEOUT_MS
        )
        if (!stopped) {
            uiCallbacks?.showError("无法停止游戏循环，请重试")
            return false
        }
        return true
    }

    /** 读档数据加载：超时保护 + 空档守卫 */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private suspend fun loadSaveData(slot: Int): SaveData? {
        return withTimeoutOrNull(60_000L) {
            try {
                storageFacade.load(slot).getOrNull()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                Log.e(TAG, "Error loading save data: ${e.message}", e)
                null
            }
        }
    }

    /** 读档数据应用：setCurrentSlot + loadData + 重数据加载 + 建筑尺寸修正 */
    private suspend fun applyLoadedSave(saveData: SaveData, effectiveSlot: Int) {
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
    }

    // ================================================================
    // 建筑占地×2 迁移：纯计算在 core/engine BuildingLoadSelfHeal，
    // 执行编排在 BootSequenceController Step 3.5（归一化/fixup 之后、边界迁移之前）
    // ================================================================

    /**
     * 纯函数：计算哪些建筑放不下，返回拆除结果（实现迁至 core/engine BuildingLoadSelfHeal，
     * 薄包装保持既有测试引用不变）。
     */
    internal fun computeBuildingOverflowMigration(
        buildings: List<GridBuildingData>,
        gameData: GameData,
        buildingConfigService: BuildingConfigService
    ): com.xianxia.sect.core.engine.MigrationResult =
        com.xianxia.sect.core.engine.computeBuildingOverflowMigration(
            buildings, gameData, buildingConfigService
        )

}
