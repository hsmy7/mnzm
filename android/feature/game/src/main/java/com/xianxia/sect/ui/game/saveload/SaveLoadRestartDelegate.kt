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
            // S9 修复（对抗性审查）：恢复"未初始化即中止"守卫——原 gameData ?: return false
            // 守卫在 P-8 迁移时丢失；gameDataSnapshot 恒非空默认值，若在状态未初始化时
            // 触发重启存档会用空 GameData 覆盖槽位（数据丢失面）。以 sectName 判空
            // （与 performRestartSave 的既有守卫一致——游戏未初始化时 sectName 为空）。
            if (stateStore.gameDataSnapshot.sectName.isBlank()) {
                Log.w(TAG, "performRestartSave: gameData not initialized, aborting")
                return@withContext false
            }
            // P-8：unifiedState → 独立窄流直读（gameData 为可空旧字段，经 gameDataSnapshot 更实时）
            val saveData = com.xianxia.sect.data.model.SaveData(
                gameData = stateStore.gameDataSnapshot,
                disciples = emptyList(),
                equipmentStacks = stateStore.equipmentStacks.value,
                equipmentInstances = stateStore.equipmentInstances.value,
                manualStacks = stateStore.manualStacks.value,
                manualInstances = stateStore.manualInstances.value,
                pills = stateStore.pills.value,
                materials = stateStore.materials.value,
                herbs = stateStore.herbs.value,
                seeds = stateStore.seeds.value,
                storageBags = stateStore.storageBags.value,
                teams = stateStore.teams.value,
                battleLogs = stateStore.battleLogs.value,
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
