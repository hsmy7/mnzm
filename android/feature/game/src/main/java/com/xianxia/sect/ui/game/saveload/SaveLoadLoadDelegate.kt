package com.xianxia.sect.ui.game.saveload

import android.util.Log
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.state.GameStateStore
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

    /** UI 回调接口 — 由 ViewModel 实现 */
    var uiCallbacks: UiCallbacks? = null

    interface UiCallbacks {
        fun showError(message: String)
        fun showSuccess(message: String)
        fun onLoadComplete(slot: Int)
        suspend fun onPreloadResources()
        suspend fun setLoadingState(isLoading: Boolean, slot: Int, action: String?)
    }

    suspend fun loadGame(saveSlot: SaveSlot): Boolean {
        val TAG = "SaveLoadLoadDelegate"
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

            // 修正建筑尺寸
            gameEngine.updateGameData { data ->
                if (data.placedBuildings.isEmpty() && data.residenceSlots.isNotEmpty()) {
                    Log.wtf(TAG, "DATA INTEGRITY: placedBuildings empty but residenceSlots " +
                        "has ${data.residenceSlots.size} entries!")
                }
                val fixed = buildingConfigService.fixupBuildingSizes(data.placedBuildings)
                val withIds = GridBuildingData.ensureAllHaveInstanceId(fixed)
                if (withIds != data.placedBuildings) data.copy(placedBuildings = withIds) else data
            }

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
}
