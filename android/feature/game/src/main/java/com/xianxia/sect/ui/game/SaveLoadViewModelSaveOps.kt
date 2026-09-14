package com.xianxia.sect.ui.game

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.engine.getStateSnapshotSync
import com.xianxia.sect.data.model.SaveData
import kotlinx.coroutines.*

// ── 存档流程（守卫/内存闸门/落盘/结果反馈）（自 SaveLoadViewModel 拆出，行为零变更）─────────────────────
// batch-02 TooManyFunctions/LargeClass 收敛外移为同包扩展，调用点语法不变。

internal fun SaveLoadViewModel.isCloudSaveAvailable(): Boolean = persistenceFacade.sessionManager.isLoggedIn

// unifiedState（20Hz 锁竞争 + 50ms 采样延迟）→ 独立窄流直连（零延迟）

internal suspend fun SaveLoadViewModel.createSaveData(): SaveData {
    val snapshot = gameEngine.buildSaveSnapshot()
    return trimSaveData(snapshot)
}

internal fun SaveLoadViewModel.createSaveDataSync(): SaveData {
    val snapshot = gameEngine.getStateSnapshotSync()
    return trimSaveData(snapshot)
}

internal fun SaveLoadViewModel.saveGame(slotId: String? = null) {
    val slot = slotId?.toIntOrNull() ?: gameEngine.gameData.value?.currentSlot ?: 1

    // slot 0 = 上传至云端（带 saveLoadState 管理 + 结果反馈）
    if (slot == 0) {
        saveToCloudViaSlot()
        return
    }

    when (val guard = checkLocalSaveGuards()) {
        is LocalSaveGuard.GameNotLoaded -> return
        is LocalSaveGuard.Blocked -> {
            showError(guard.userMessage)
            return
        }
        LocalSaveGuard.Passed -> {}
    }

    // isSaving 同步占位——关闭双 tap 窗口：若等协程内才异步置位，
    // 第二次点击可穿过守卫注册并取消第一次保存；协程内
    // setSaveLoadState(isSaving=true) 为幂等重设
    stateStore.setSavingDirect(true)
    pendingSlotFlow.value = slot
    pendingActionFlow.value = "save"

    Log.i(SaveLoadViewModelConstants.TAG, "=== saveGame BEGIN === slot=$slot, slotId=$slotId")
    val startTime = System.currentTimeMillis()

    // job 身份由 perform* 内部 coroutineContext[Job] 自取，
    // 不经 lateinit 捕获（避免 IO worker 抢跑读未赋值 lateinit）
    val job = viewModelScope.launch(ioDispatcher.dispatcher) {
        // 本地保存流程
        val previousSlot = persistenceFacade.storageFacade.getCurrentSlot()
        performLocalSaveToSlot(slot, previousSlot, startTime)
    }
    gameEngineCore.registerActiveLoadJob(job) // 保存协程注册，看门狗可取消复位
}

internal fun SaveLoadViewModel.trimSaveData(snapshot: com.xianxia.sect.core.engine.GameStateSnapshot): SaveData =
    SaveDataTrimmer.trimSaveData(snapshot)

@Suppress("ExplicitGarbageCollectionCall") // 存档前低内存触发的刻意 gc：降低大快照序列化期间 OOM 概率
internal fun SaveLoadViewModel.canPerformSaveOperation(): Boolean {
    val runtime = Runtime.getRuntime()
    val maxMemory = runtime.maxMemory()
    val totalMemory = runtime.totalMemory()
    val freeMemory = runtime.freeMemory()

    val usedMemory = totalMemory - freeMemory
    val availableMemory = maxMemory - usedMemory
    val memoryRatio = availableMemory.toDouble() / maxMemory.toDouble()
    val memoryUsagePercent = (usedMemory * 100 / maxMemory)

    if (memoryRatio < 0.4) {
        Log.w(SaveLoadViewModelConstants.TAG, "Low memory before save: ${memoryUsagePercent}% used, triggering GC")
        System.gc()

        val newFree = runtime.freeMemory()
        val newAvailable = maxMemory - (runtime.totalMemory() - newFree)
        if (newAvailable.toDouble() / maxMemory < 0.3) {
            Log.e(SaveLoadViewModelConstants.TAG,
                "Insufficient memory after GC: only ${newAvailable/1024/1024}MB available")
            return false
        }
    }

    Log.d(
        SaveLoadViewModelConstants.TAG,
        "Memory status: max=${maxMemory / SaveLoadViewModelConstants.MB}MB, " +
            "used=${usedMemory / SaveLoadViewModelConstants.MB}MB (${memoryUsagePercent}%), " +
            "available=${availableMemory / SaveLoadViewModelConstants.MB}MB",
    )

    return true
}

@Suppress("ExplicitGarbageCollectionCall") // 存档前高内存触发的刻意 gc（阈值 70%），函数即为此设计
internal fun SaveLoadViewModel.performGarbageCollection() {
    val runtime = Runtime.getRuntime()
    val usedMemory = runtime.totalMemory() - runtime.freeMemory()
    val memoryUsagePercent = (usedMemory * 100 / runtime.maxMemory())

    if (memoryUsagePercent > 70) {
        Log.d(SaveLoadViewModelConstants.TAG, "High memory usage before save: ${memoryUsagePercent}%, triggering GC")
        System.gc()
    } else {
        Log.d(SaveLoadViewModelConstants.TAG, "Memory usage acceptable: ${memoryUsagePercent}%")
    }
}

internal suspend fun SaveLoadViewModel.waitForSaveLock(timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (saveLock.compareAndSet(false, true)) {
            return true
        }
        delay(50)
    }
    return false
}

/**
 * 本地保存前置守卫链：游戏已加载 → 操作互斥 → 内存充足。
 */
internal fun SaveLoadViewModel.checkLocalSaveGuards(): LocalSaveGuard {
    if (!isGameLoaded) {
        Log.w(SaveLoadViewModelConstants.TAG, "Game not loaded, ignoring saveGame request")
        return LocalSaveGuard.GameNotLoaded
    }

    val mutexGuard = checkSaveMutexGuards()
    if (mutexGuard != null) return mutexGuard

    if (!canPerformSaveOperation()) {
        Log.e(SaveLoadViewModelConstants.TAG, "=== saveGame FAILED === insufficient memory")
        return LocalSaveGuard.Blocked("内存不足，无法保存。请关闭其他应用后重试。")
    }
    return LocalSaveGuard.Passed
}

/**
 * 保存互斥守卫：云操作 → 重置 → 保存 → 读档。
 *
 * @return 任一互斥操作进行中返回拒绝结果；全部空闲返回 null
 */
internal fun SaveLoadViewModel.checkSaveMutexGuards(): LocalSaveGuard? {
    // 云存档操作进行中禁止本地保存——否则云下载落盘与本地保存
    // 并发写同一槽位
    if (cloudDownloadLock.get()) {
        Log.w(SaveLoadViewModelConstants.TAG, "Cloud save operation in progress, ignoring saveGame request")
        return LocalSaveGuard.Blocked("云存档操作进行中，请稍后保存")
    }

    // restart 的 stopGameLoopAndWait 窗口内保存被立即拒绝——
    // 否则保存注册会取消 restart 协程（isSaving 未置时守卫通过，restart 被杀）
    if (isRestartingFlow.value) {
        Log.w(SaveLoadViewModelConstants.TAG, "Restarting, ignoring saveGame request")
        return LocalSaveGuard.Blocked("游戏重置中，请稍后保存")
    }

    // isSaving 守卫——防止快速连点两次保存时后一次覆写前一次的
    // isSaving 标志（保存期间 tick 恢复 → torn 存档）
    if (stateStore.isSaving.value) {
        Log.w(SaveLoadViewModelConstants.TAG, "Currently saving, ignoring saveGame request")
        return LocalSaveGuard.Blocked("正在保存中，请稍后")
    }

    if (stateStore.isLoading.value || loadLock.get()) {
        Log.w(SaveLoadViewModelConstants.TAG, "Load in progress, ignoring saveGame request")
        return LocalSaveGuard.Blocked("正在读档中，请稍后保存")
    }
    return null
}

/**
 *本地保存主流程。
 * 快照 → 校验 → 保存 → 结果反馈 → 失败回滚 currentSlot。
 */
// [已合并 ThrowsCount 理由: 多步骤事务/异常翻译边界：各 throw 对应不同失败路径的领域错误，刻意独立抛出保归因清晰，非疏忽计数超标] // 防御兜底: 取消异常已前置分支处理, 泛型段为刻意终局兜底
@Suppress("ThrowsCount", "TooGenericExceptionCaught")
internal suspend fun SaveLoadViewModel.performLocalSaveToSlot(slot: Int, previousSlot: Int, startTime: Long) {
    setSaveLoadState(isSaving = true, pendingSlot = slot, pendingAction = "save")

    try {
        if (!waitForSaveLock(timeoutMs = 5000)) {
            Log.e(SaveLoadViewModelConstants.TAG, "=== saveGame FAILED === saveLock busy after timeout")
            showError("保存操作繁忙，请稍后重试")
            return
        }

        val previousSlot = persistenceFacade.storageFacade.getCurrentSlot()
        persistenceFacade.storageFacade.setCurrentSlot(slot)

        try {
            performSaveOperation(slot = slot, previousSlot = previousSlot, startTime = startTime)
        } catch (e: OutOfMemoryError) {
            Log.e(SaveLoadViewModelConstants.TAG, "=== saveGame FAILED === OutOfMemoryError", e)
            persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
            showError("内存不足，保存失败。请关闭其他应用后重试。")
            try { saveSlotsFlow.value = persistenceFacade.storageFacade
                .getSaveSlotsSuspend() } catch (e: CancellationException) { throw e } catch (e2: Exception) { Log
                    .e(SaveLoadViewModelConstants.TAG, "Failed to refresh slots after OOM", e2) }
        } catch (e: CancellationException) {
            Log.w(SaveLoadViewModelConstants.TAG, "saveGame cancelled")
            persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
            throw e
        } catch (e: Exception) {
            Log.e(SaveLoadViewModelConstants.TAG, "=== saveGame FAILED === error=${e.message}", e)
            persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
            showError("保存失败: ${e.message}")
            try { saveSlotsFlow.value = persistenceFacade.storageFacade
                .getSaveSlotsSuspend() } catch (e: CancellationException) { throw e } catch (e2: Exception) { Log
                    .e(SaveLoadViewModelConstants.TAG, "Failed to refresh slots after save failure", e2) }
        } finally {
            saveLock.set(false)
        }
    } finally {
        // NonCancellable 保证取消路径复位（详见 performLoadToSlot finally 注释）
        // 归属化复位：被取代的协程不复位标志
        resetOwnedLoadState("saveGame")
    }
}

/**本地保存核心：快照 → 校验 → 落盘 → 结果反馈 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun SaveLoadViewModel.performSaveOperation(slot: Int, previousSlot: Int, startTime: Long) {
    performGarbageCollection()

    val snapshot = gameEngine.buildSaveSnapshot()
    Log.d(SaveLoadViewModelConstants.TAG, "saveGame snapshot: productionSlots=${snapshot.productionSlots.size}, " +
        "gameData.productionSlots=${snapshot.gameData.productionSlots.size}, " +
        "disciples=${snapshot.disciples.size}, equipment=${snapshot.equipmentInstances.size}")
    if (snapshot.gameData.sectName.isBlank()) {
        Log.e(SaveLoadViewModelConstants.TAG, "=== saveGame FAILED === gameData not initialized (sectName is blank)")
        persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
        showError("游戏数据未初始化")
        return
    }
    val updatedGameData = snapshot.gameData.copy(currentSlot = slot)
    val saveData = trimSaveData(snapshot).copy(gameData = updatedGameData)

    val saveResult = withTimeoutOrNull(30_000L) {
        persistenceFacade.storageFacade.save(slot, saveData)
    }

    if (saveResult != null && saveResult.isSuccess) {
        try {
            saveSlotsFlow.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            Log.e(SaveLoadViewModelConstants.TAG, "Failed to refresh slots after successful save: ${e.message}", e)
        }
        showSuccess("游戏保存成功")

        Log.i(SaveLoadViewModelConstants.TAG, "=== saveGame SUCCESS === " +
            "sectName=${snapshot.gameData.sectName}, year=${snapshot.gameData.gameYear}, " +
            "month=${snapshot.gameData.gameMonth}, phase=${snapshot.gameData.gamePhase}, " +
            "spiritStones=${snapshot.gameData.spiritStones}, " +
            "disciples=${saveData.disciples.size}, equipment=${saveData.equipmentInstances.size}, " +
            "manuals=${saveData.manualInstances.size}, elapsed=${System.currentTimeMillis() - startTime}ms")
    } else {
        persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
        val errorMsg = if (saveResult == null) "保存超时，请重试" else "保存失败，请重试"
        showError(errorMsg)
        Log.e(
            SaveLoadViewModelConstants.TAG,
            "=== saveGame FAILED === ${if (saveResult == null) "timeout" else "save returned failure"}",
        )
        try {
            saveSlotsFlow.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(SaveLoadViewModelConstants.TAG, "Failed to refresh slots after save failure", e)
        }
    }
}

/** 本地保存前置守卫结果 */
internal sealed interface LocalSaveGuard {
    data object Passed : LocalSaveGuard

    /** 仅记日志、无 UI 提示的拒绝 */
    data object GameNotLoaded : LocalSaveGuard

    /** 拒绝并向用户提示原因 */
    data class Blocked(val userMessage: String) : LocalSaveGuard
}
