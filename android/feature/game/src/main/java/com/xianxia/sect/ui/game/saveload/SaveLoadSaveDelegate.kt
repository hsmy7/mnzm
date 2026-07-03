package com.xianxia.sect.ui.game.saveload

import android.util.Log
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.domain.save.SavePipeline
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.unified.SaveResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 存档保存委托 — 管理存档持久化、自动存档、保存状态。
 *
 * 设计为纯数据操作类，不持有 Compose 状态，通过回调通知 ViewModel 更新 UI。
 */
class SaveLoadSaveDelegate(
    private val gameEngine: GameEngine,
    private val storageFacade: StorageFacade,
    private val stateStore: GameStateStore,
    private val savePipeline: SavePipeline
) {
    private val TAG = SaveLoadViewModelConstants.TAG
    private val saveLock = AtomicBoolean(false)
    val pendingAutoSave = AtomicReference<SavePipeline.SaveSource?>(null)
    private val consecutiveSaveFailures = AtomicInteger(0)
    var pendingSlot: Int? = null
    var pendingAction: String? = null

    fun canPerformSaveOperation(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val availableMemory = maxMemory - usedMemory
        val memoryRatio = availableMemory.toDouble() / maxMemory.toDouble()
        val memoryUsagePercent = (usedMemory * 100 / maxMemory)

        if (memoryRatio < 0.4) {
            Log.w(TAG, "Low memory before save: ${memoryUsagePercent}% used, triggering GC")
            System.gc()
            val newFree = runtime.freeMemory()
            val newAvailable = maxMemory - (runtime.totalMemory() - newFree)
            if (newAvailable.toDouble() / maxMemory < 0.3) {
                Log.e(TAG, "Insufficient memory after GC: only ${newAvailable/1024/1024}MB available")
                return false
            }
        }
        return true
    }
}
