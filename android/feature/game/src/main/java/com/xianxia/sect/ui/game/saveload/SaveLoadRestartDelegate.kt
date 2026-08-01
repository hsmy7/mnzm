package com.xianxia.sect.ui.game.saveload

import android.util.Log
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 游戏重启委托 — 管理重新开始游戏流程。
 */
class SaveLoadRestartDelegate(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    private val storageFacade: StorageFacade,
    private val stateStore: GameStateStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val TAG = "SaveLoadRestartDelegate"

    var isRestarting: Boolean = false

    suspend fun performRestartSave(slot: Int, previousSlot: Int): Boolean = withContext(dispatcher) {
        try {
            Log.i(TAG, "Saving current game to slot $slot before restart")
            val currentData = stateStore.unifiedState.value
            val saveData = com.xianxia.sect.data.model.SaveData(
                gameData = currentData.gameData ?: return@withContext false,
                disciples = emptyList(),
                equipmentStacks = currentData.equipmentStacks,
                equipmentInstances = currentData.equipmentInstances,
                manualStacks = currentData.manualStacks,
                manualInstances = currentData.manualInstances,
                pills = currentData.pills,
                materials = currentData.materials,
                herbs = currentData.herbs,
                seeds = currentData.seeds,
                storageBags = currentData.storageBags,
                teams = currentData.teams,
                battleLogs = currentData.battleLogs,
                alliances = emptyList(),
                productionSlots = emptyList(),
                // 2026-08-01 对抗性审查修复：缺该标志会使删表守卫失效
                stacksSerialized = true
            )
            val result = storageFacade.save(slot, saveData)
            if (result.isSuccess) {
                Log.i(TAG, "Restart save completed for slot $slot")
                true
            } else {
                Log.e(TAG, "Restart save failed for slot $slot")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Restart save error: ${e.message}", e)
            false
        }
    }
}
