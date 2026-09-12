package com.xianxia.sect.ui.game

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.loadData
import com.xianxia.sect.core.engine.setSaveLoadFlags
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import kotlinx.coroutines.*
import com.xianxia.sect.core.engine.sendWhitelistBonus
import com.xianxia.sect.core.engine.sendExclusiveBonus
import com.xianxia.sect.core.engine.sendStorageBagCompensation
import com.xianxia.sect.core.engine.clearActiveLoadJob

// ── 读档流程（守卫/循环停止/落库/启动序列/标志复位）（自 SaveLoadViewModel 拆出，行为零变更）─────────────────────
// batch-02 TooManyFunctions/LargeClass 收敛外移为同包扩展，调用点语法不变。

internal fun SaveLoadViewModel.loadGame(saveSlot: SaveSlot) = loadGameInternal(saveSlot, fromCloudLoad = false)

/**
 * 读档内部入口——[fromCloudLoad]=true 时仅绕过
 * `cloudDownloadLock` 守卫（云读档路径已持有该锁，云档已落盘后加载内存；
 * 其余守卫照常，绕过入口仅内部可达）。
 */
@Suppress("ReturnCount") // 读档多守卫（云锁/重启/加载/保存/loadLock/内存），多 return 为守卫风格
internal fun SaveLoadViewModel.loadGameInternal(saveSlot: SaveSlot, fromCloudLoad: Boolean) {
    // boot 进行中禁止读档（云会话/其他入口 boot 进行时）
    if (isBootOperationBlocked()) return
    // 云存档操作进行中禁止本地读档——否则云读档下载期间点本地档会与
    // 云下载并发写 DB/内存，导致"内存=本地档、DB=云档"静默分歧
    if (!fromCloudLoad && cloudDownloadLock.get()) {
        Log.w(SaveLoadViewModelConstants.TAG, "Cloud save operation in progress, ignoring loadGame request")
        showError("云存档操作进行中，请稍后读档")
        return
    }
    // restart 的 stopGameLoopAndWait 窗口内读档被立即拒绝——
    // 否则读档注册会取消 restart 协程（游戏循环停在已停止状态）
    if (isRestartingFlow.value) {
        Log.w(SaveLoadViewModelConstants.TAG, "Restarting, ignoring loadGame request")
        showError("游戏重置中，请稍后读档")
        return
    }
    if (stateStore.isLoading.value) {
        Log.w(SaveLoadViewModelConstants.TAG, "Already loading, ignoring loadGame request")
        return
    }
    if (stateStore.isSaving.value) {
        Log.w(SaveLoadViewModelConstants.TAG, "Currently saving, ignoring loadGame request")
        showError("正在保存中，请稍后读档")
        return
    }
    if (!loadLock.compareAndSet(false, true)) {
        Log.w(SaveLoadViewModelConstants.TAG, "loadLock busy, ignoring loadGame request")
        return
    }

    if (!canPerformSaveOperation()) {
        Log.e(SaveLoadViewModelConstants.TAG, "=== loadGame FAILED === insufficient memory")
        loadLock.set(false)
        showError("内存不足，无法读档。请关闭其他应用后重试。")
        return
    }

    Log.i(SaveLoadViewModelConstants.TAG,
        "=== loadGame BEGIN === slot=${saveSlot.slot}, sectName=${saveSlot.sectName}, " +
        "year=${saveSlot.gameYear}, month=${saveSlot.gameMonth}")
    val startTime = System.currentTimeMillis()

    // job 身份由 perform* 内部 coroutineContext[Job] 自取，
    // 不经 lateinit 捕获（避免 IO worker 抢跑读未赋值 lateinit）
    val job = viewModelScope.launch(ioDispatcher.dispatcher) {
        // 读档主流程
        performLoadToSlot(saveSlot, startTime)
    }
    gameEngineCore.registerActiveLoadJob(job)
}

/**
 *读档主流程。
 * 读取存档 → 引擎加载 → RNG 恢复 → BootSequenceController 启动。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun SaveLoadViewModel.performLoadToSlot(saveSlot: SaveSlot, startTime: Long) {
    try {
        setSaveLoadState(isLoading = true, pendingSlot = saveSlot.slot, pendingAction = "load")

        // 玉符防回退：loadData 前必须等待旧循环彻底停止——
        // boot Step 1 的 stopGameLoop 为非等待取消，旧循环 finally 的
        // JadeSymbolService.onLoopStop()（checkpointNow 绝对值覆盖写）在引擎线程
        // 异步执行，晚于 loadFromSnapshot 替换 gameData → 读档前的旧运行时值
        // 覆盖新档玉符四字段。
        // stopGameLoopAndWait 返回时 finally 已执行完毕（signal 完成于
        // onLoopStop 之后），此后引擎线程无任何玉符写，onLoopStart 从新档锚定。
        // 主菜单读档（循环未运行）立即返回，零开销。
        val stopped = gameEngineCore.stopGameLoopAndWait(SaveLoadViewModelConstants.GAME_LOOP_STOP_TIMEOUT_MS)
        if (!stopped) {
            Log.e(SaveLoadViewModelConstants.TAG, "=== loadGame FAILED === cannot stop game loop within timeout")
            showError("无法停止游戏循环，请重试")
            return
        }
        isTimeRunningFlow.value = false
        Log.d(SaveLoadViewModelConstants.TAG, "Game loop stopped for load operation")

        performGarbageCollection()

        Log.d(SaveLoadViewModelConstants.TAG, "Starting to load save data for slot ${saveSlot.slot}")
        val loadStartTime = System.currentTimeMillis()

        val saveData = loadSaveDataForSlot(saveSlot = saveSlot, loadStartTime = loadStartTime)
        if (saveData == null) {
            return
        }

        val effectiveSlot = saveSlot.slot
        applyLoadedSaveToEngine(saveData = saveData, effectiveSlot = effectiveSlot)

        // 建筑占地重叠/越界迁移由 BootSequenceController Step 3.5 执行
        //（Step 3 归一化+fixup 之后；云端路径同样生效）

        performLoadBoot(effectiveSlot = effectiveSlot, startTime = startTime)
    } catch (e: CancellationException) {
        Log.w(SaveLoadViewModelConstants.TAG, "loadGame cancelled")
        throw e
    } catch (e: Exception) {
        Log.e(SaveLoadViewModelConstants.TAG, "=== loadGame FAILED === error=${e.message}", e)
        showError("加载游戏失败: ${e.message}")
    } catch (e: OutOfMemoryError) {
        // OOM 是 Error 非 Exception——异常超大存档（如 crafted 大 id 弟子
        // 扩容平铺表）会直接崩溃；统一走用户可感知的失败提示，finally 复位不受影响
        Log.e(SaveLoadViewModelConstants.TAG, "=== loadGame FAILED === OutOfMemoryError: ${e.message}", e)
        showError("内存不足，读档失败。请关闭其他应用后重试。")
    } finally {
        // finally 复位必须用 NonCancellable——setSaveLoadFlags 是挂起函数，
        // 看门狗取消协程后挂起调用立即抛 CancellationException 截断 finally，
        // 导致 loadLock 泄漏（读档永久拒绝）与 isSaving/isLoading 永不复位。
        // 归属化复位（被取代的协程不复位标志）；loadLock 为操作私有无条件释放
        resetOwnedLoadState("loadGame")
        loadLock.set(false)
    }
}

/**读档数据加载：超时保护 + 空档守卫 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun SaveLoadViewModel.loadSaveDataForSlot(saveSlot: SaveSlot, loadStartTime: Long): SaveData? {
    val saveData = withTimeoutOrNull(60_000L) {
        try {
            val data = persistenceFacade.storageFacade.load(saveSlot.slot).getOrNull()
            Log.d(SaveLoadViewModelConstants.TAG, "Save data loaded in ${System.currentTimeMillis() - loadStartTime}ms")
            data
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            Log.e(SaveLoadViewModelConstants.TAG, "Error loading save data: ${e.message}", e)
            null
        }
    }
    if (saveData == null) {
        val elapsed = System.currentTimeMillis() - loadStartTime
        Log.e(SaveLoadViewModelConstants.TAG,
            "=== loadGame FAILED === timeout or null for slot ${saveSlot.slot}, elapsed=${elapsed}ms")
        showError(if (elapsed >= 60_000L) "读档超时，请重试" else "存档为空或已损坏，请重试")
    }
    return saveData
}

/**读档数据应用：setCurrentSlot + loadData + AI 宗门 RNG 初始化 */
internal suspend fun SaveLoadViewModel.applyLoadedSaveToEngine(saveData: SaveData, effectiveSlot: Int) {
    persistenceFacade.storageFacade.setCurrentSlot(effectiveSlot)
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

    // RNG 分区恢复已收敛到 GameStateStoreImpl.loadFromSnapshot 锁内
    // （状态 + RNG 原子切换），此处不再重复 restoreStates
    val loadedGd = gameEngine.gameData.value
    // 初始化 AI 宗门 RNG（基于地图种子确保确定性）
    AISectDiscipleManager.initForSlot(loadedGd.mapSeed.toLong())
}

/**读档启动序列：BootSequenceController.boot + 福利注入 */
internal suspend fun SaveLoadViewModel.performLoadBoot(effectiveSlot: Int, startTime: Long): Boolean {
    // BootSequenceController 统一处理：建筑修正、BootPhase 推进、资源预加载、
    // 弟子快照预热、确保重数据加载、游戏循环启动、地图生成、最终状态切换
    val bootResult = persistenceFacade.bootSequenceController.boot(
        slot = effectiveSlot,
        onPreloadResources = { preloadGameResources() },
        onProgress = { progress ->
            loadingProgressFlow.value = SaveLoadViewModelConstants.PROGRESS_START + progress * (
                SaveLoadViewModelConstants.PROGRESS_COMPLETE - SaveLoadViewModelConstants.PROGRESS_START)
        },
        onMapReady = { mapData -> mapPreloadDataFlow.value = mapData },
        onSuccess = { showSuccess("读档成功") }
    )

    if (bootResult.isSuccess) {
        // 白名单福利：1000 万灵石永久邮件（每档一次，非白名单自动跳过）
        gameEngine.sendWhitelistBonus(effectiveSlot)

        // 专属福利：定向用户 1000 万灵石 + 10 单灵根弟子邮件
        //（2026-09-04 截止，每档一次，非目标用户自动跳过）
        gameEngine.sendExclusiveBonus(effectiveSlot)

        // 补偿邮件：定向用户 10 个地品储物袋（3 天有效，每档一次，非目标用户自动跳过）
        gameEngine.sendStorageBagCompensation(effectiveSlot)

        val gd = gameEngine.gameData.value
        Log.i(SaveLoadViewModelConstants.TAG, "=== loadGame SUCCESS === " +
            "sectName=${gd.sectName}, year=${gd.gameYear}, month=${gd.gameMonth}, phase=${gd.gamePhase}, " +
            "spiritStones=${gd.spiritStones}, disciples=${gameEngine.disciples.value.size}, " +
            "equipment=${gameEngine.equipmentInstances.value.size}, " +
                "manuals=${gameEngine.manualInstances.value.size}, " +
            "elapsed=${System.currentTimeMillis() - startTime}ms")
        return true
    } else {
        val errorMsg = bootResult.exceptionOrNull()?.message ?: "读档失败"
        showError(errorMsg)
        // boot 失败后必须清空地图预加载数据——残留的 mapPreloadData 会使
        // Crossfade 仍显示 MainGameScreen（引擎已停、runState=IDLE）
        // → "冻结的游戏画面"
        mapPreloadDataFlow.value = null
        return false
    }
}

// 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬

/**
 * finally 归属化复位——clearActiveLoadJob 归属判定与标志复位原子化。
 * 被新操作取代的协程（owned=false）不复位标志、不清理注册，避免旧协程 finally
 * 抹掉新操作的在途状态；取消路径由 NonCancellable 保证复位。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun SaveLoadViewModel.resetOwnedLoadState(operation: String) {
    // 协程身份从协程体内部自取：本函数仅在 perform* 的 finally 内调用
    //（必在 launch 协程体内），coroutineContext[Job] 与 launch 返回的 job
    // 是同一实例
    val job = kotlin.coroutines.coroutineContext[Job] ?: return
    val owned = gameEngineCore.clearActiveLoadJob(job)
    if (!owned) return
    try {
        withContext(NonCancellable) {
            gameEngine.setSaveLoadFlags(false, false)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(SaveLoadViewModelConstants.TAG, "$operation: Failed to reset save/load state in finally block", e)
        pendingSlotFlow.value = null
        pendingActionFlow.value = null
    }
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun SaveLoadViewModel.setSaveLoadState(
    isSaving: Boolean? = null,
    isLoading: Boolean? = null,
    pendingSlot: Int? = pendingSlotFlow.value,
    pendingAction: String? = pendingActionFlow.value
) {
    // Q-1：isSaving/isLoading 同步收敛到引擎原子入口（单事务设置两标志）
    val finalIsSaving = isSaving ?: stateStore.isSaving.value
    val finalIsLoading = isLoading ?: stateStore.isLoading.value
    if (isSaving != null || isLoading != null) {
        try {
            gameEngine.setSaveLoadFlags(finalIsSaving, finalIsLoading)
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            Log.w(SaveLoadViewModelConstants.TAG, "Failed to sync save/load flags to stateStore: ${e.message}")
        }
    }

    pendingSlotFlow.value = pendingSlot
    pendingActionFlow.value = pendingAction
}

internal fun SaveLoadViewModel.cancelSaveLoad() {
    gameEngine.launchOnEngine { setSaveLoadState(isSaving = false, isLoading = false, pendingSlot = null,
        pendingAction = null) }
}

internal fun SaveLoadViewModel.setPendingSave(slot: Int) {
    pendingSlotFlow.value = slot
    pendingActionFlow.value = "save"
}

internal fun SaveLoadViewModel.setPendingLoad(slot: Int) {
    pendingSlotFlow.value = slot
    pendingActionFlow.value = "load"
}

internal fun SaveLoadViewModel.clearPendingAction() {
    pendingSlotFlow.value = null
    pendingActionFlow.value = null
}

/**
 * boot 触发入口统一前置守卫。
 *
 * 云读档/云下载路径持有 [cloudDownloadLock] 期间不设 isLoading（互斥
 * 依赖其他入口查该锁），startNewGame/restartGame 也不查
 * cloudDownloadLock；且 boot 进行中 runState 短暂置为 RELOADING 使
 * isGameLoaded 守卫失效——存在并发窗口，第二个 boot 会被
 * BootSequenceController 拒绝式保护挡下报 "boot() already in progress"。
 *
 * 统一守卫：所有会触发 boot 的入口先查 [BootSequenceController.bootInProgress]，
 * 再补齐各自缺失的不对称锁检查，从入口处阻断并发 boot（在状态被污染之前）。
 *
 * @return true 表示操作被阻断（调用方应直接 return）
 */
internal fun SaveLoadViewModel.isBootOperationBlocked(): Boolean {
    if (persistenceFacade.bootSequenceController.bootInProgress.value) {
        Log.w(SaveLoadViewModelConstants.TAG, "Boot in progress, ignoring operation")
        showError("正在加载游戏，请稍候")
        return true
    }
    return false
}
