package com.xianxia.sect.ui.game

import android.util.Log
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import kotlinx.coroutines.*
import com.xianxia.sect.core.engine.sendWhitelistBonus
import com.xianxia.sect.core.engine.sendExclusiveBonus
import com.xianxia.sect.core.engine.sendStorageBagCompensation
import com.xianxia.sect.core.engine.createNewGame
import com.xianxia.sect.core.engine.updateGameData

// ── 新游戏流程（首存/启动序列/福利注入/循环启停/资源预载）（自 SaveLoadViewModel 拆出，行为零变更）─────────────────────
// batch-02 TooManyFunctions/LargeClass 收敛外移为同包扩展，调用点语法不变。

/**
 *新游戏主流程。
 * 创建新游戏 → RNG 播种 → 首存（失败重试一次）→ BootSequenceController 启动。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 取消异常已前置分支处理, 泛型段为刻意终局兜底
internal suspend fun SaveLoadViewModel.performStartNewGame(sectName: String, slot: Int, startTime: Long) {
    var needSlotRefresh = false
    var gameStarted = false
    try {
        setSaveLoadState(isLoading = true, pendingSlot = slot, pendingAction = "newgame")

        loadingProgressFlow.value = SaveLoadViewModelConstants.PROGRESS_START

        Log.d(SaveLoadViewModelConstants.TAG,
            "startNewGame: Calling gameEngine.createNewGame(sectName=$sectName, slot=$slot)")
        gameEngine.createNewGame(sectName, slot)
        Log.d(SaveLoadViewModelConstants.TAG, "startNewGame: Game engine created new game successfully, " +
            "elapsed=${System.currentTimeMillis() - startTime}ms")

        // RNG 播种在 GameEngine.createNewGame 内部（引擎线程）完成：
        // initSystemSeed（8 分区）与 AISectDiscipleManager.initForSlot 均在引擎侧完成，
        // 避免 UI 协程与引擎线程 RNG 消费/播种并发竞争
        Log.d(SaveLoadViewModelConstants.TAG,
            "startNewGame: RNG seeded with mapSeed=${gameEngine.gameData.value.mapSeed}")

        persistenceFacade.storageFacade.setCurrentSlot(slot)
        Log.d(SaveLoadViewModelConstants.TAG, "Active slot set to $slot")

        var saveSuccess = performInitialSaveForNewGame(slot = slot)
        needSlotRefresh = true
        if (!saveSuccess) {
            return
        }

        gameStarted = performNewGameBoot(slot = slot, startTime = startTime)
    } catch (e: CancellationException) {
        Log.w(SaveLoadViewModelConstants.TAG, "startNewGame cancelled")
        throw e
    } catch (e: Exception) {
        Log.e(SaveLoadViewModelConstants.TAG, "=== startNewGame FAILED === error=${e.message}", e)
        showError(e.message ?: "开始新游戏失败")
    } finally {
        // NonCancellable 保证取消路径复位（详见 performLoadToSlot finally 注释）
        // 归属化复位：被取代的协程不复位标志/不清理
        resetOwnedLoadState("startNewGame")
        if (!gameStarted) {
            loadingProgressFlow.value = SaveLoadViewModelConstants.PROGRESS_START
        }
        if (needSlotRefresh) {
            try {
                saveSlotsFlow.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
            } catch (e: CancellationException) {
                // finally 清理路径不重抛：重抛会掩盖 try 体原始异常；协程取消由 Job 机制继续生效
                Log.w(SaveLoadViewModelConstants.TAG, "startNewGame: slot refresh cancelled during cleanup", e)
            } catch (e: Exception) {
                Log.w(SaveLoadViewModelConstants.TAG,
                    "startNewGame: Failed to refresh save slots after completion: ${e.message}")
            }
        }
    }
}

/**新游戏首存：保存进度置位 + 首次保存（失败重试一次） */
internal suspend fun SaveLoadViewModel.performInitialSaveForNewGame(slot: Int): Boolean {
    loadingProgressFlow.value = SaveLoadViewModelConstants.PROGRESS_SAVE_COMPLETE
    var saveSuccess = performSynchronousSave(slot)
    if (!saveSuccess) {
        Log.w(SaveLoadViewModelConstants.TAG, "startNewGame: First save attempt failed, retrying once for slot $slot")
        delay(500)
        saveSuccess = performSynchronousSave(slot)
    }
    if (!saveSuccess) {
        Log.e(SaveLoadViewModelConstants.TAG,
            "=== startNewGame SAVE FAILED AFTER RETRY === aborting game start for slot $slot")
        showError("保存失败，无法启动游戏。请检查存储空间后重试。")
    }
    return saveSuccess
}

/**新游戏启动序列：BootSequenceController.boot + 福利注入 */
internal suspend fun SaveLoadViewModel.performNewGameBoot(slot: Int, startTime: Long): Boolean {
    // BootSequenceController 统一处理：建筑修正、BootPhase 推进、资源预加载、
    // 弟子快照预热、确保重数据加载、游戏循环启动、地图生成、最终状态切换
    val bootResult = persistenceFacade.bootSequenceController.boot(
        slot = slot,
        onPreloadResources = { preloadGameResources() },
        onProgress = { progress ->
            loadingProgressFlow.value = SaveLoadViewModelConstants.PROGRESS_START + progress * (
                SaveLoadViewModelConstants.PROGRESS_COMPLETE - SaveLoadViewModelConstants.PROGRESS_START)
        },
        onMapReady = { mapData -> mapPreloadDataFlow.value = mapData }
    )

    if (bootResult.isSuccess) {
        loadingProgressFlow.value = SaveLoadViewModelConstants.PROGRESS_COMPLETE

        // 白名单福利：1000 万灵石永久邮件（每档一次，非白名单自动跳过）
        gameEngine.sendWhitelistBonus(slot)

        // 专属福利：定向用户 1000 万灵石 + 10 单灵根弟子邮件
        //（2026-09-04 截止，每档一次，非目标用户自动跳过）
        gameEngine.sendExclusiveBonus(slot)

        // 补偿邮件：定向用户 10 个地品储物袋（3 天有效，每档一次，非目标用户自动跳过）
        gameEngine.sendStorageBagCompensation(slot)

        val gd = gameEngine.gameData.value
        Log.i(SaveLoadViewModelConstants.TAG, "=== startNewGame SUCCESS === " +
            "sectName=${gd.sectName}, year=${gd.gameYear}, month=${gd.gameMonth}, phase=${gd.gamePhase}, " +
            "spiritStones=${gd.spiritStones}, disciples=${gameEngine.disciples.value.size}, " +
            "totalElapsed=${System.currentTimeMillis() - startTime}ms")
        return true
    } else {
        val errorMsg = bootResult.exceptionOrNull()?.message ?: "启动失败"
        showError(errorMsg)
        return false
    }
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun SaveLoadViewModel.performSynchronousSave(slot: Int): Boolean {
    return try {
        val snapshot = gameEngine.buildSaveSnapshot()
        if (snapshot.gameData.sectName.isBlank()) {
            Log.e(SaveLoadViewModelConstants.TAG, "performSynchronousSave: gameData not initialized")
            return false
        }
        val updatedGameData = snapshot.gameData.copy(currentSlot = slot)
        val saveData = trimSaveData(snapshot).copy(gameData = updatedGameData)

        val result = withTimeoutOrNull(30_000L) {
            persistenceFacade.storageFacade.save(slot, saveData)
        }

        when {
            result == null -> {
                Log.e(SaveLoadViewModelConstants.TAG, "performSynchronousSave TIMEOUT for slot $slot")
                showError("保存超时，请稍后手动保存")
                false
            }
            result.isSuccess -> {
                gameEngine.updateGameData { updatedGameData }
                try {
                    saveSlotsFlow.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                    Log.e(SaveLoadViewModelConstants.TAG,
                        "Failed to refresh slots after synchronous save: ${e.message}", e)
                }
                Log.i(SaveLoadViewModelConstants.TAG, "performSynchronousSave SUCCESS for slot $slot")
                true
            }
            result is SaveResult.Failure && result.error == SaveError.KEY_DERIVATION_ERROR -> {
                Log.e(SaveLoadViewModelConstants.TAG, "performSynchronousSave KEY_DERIVATION_ERROR for slot $slot")
                showError("密钥错误：${result.message}\n请尝试清除应用数据或联系支持")
                false
            }
            result is SaveResult.Failure && result.error == SaveError.IO_ERROR -> {
                Log.e(SaveLoadViewModelConstants.TAG, "performSynchronousSave IO_ERROR for slot $slot")
                showError("存储错误：${result.message}\n请检查存储空间或重启应用")
                false
            }
            result is SaveResult.Failure -> {
                Log.e(SaveLoadViewModelConstants.TAG,
                    "performSynchronousSave FAILED for slot $slot: ${result.error} - ${result.message}")
                showError("保存失败，请稍后手动保存")
                false
            }
            else -> {
                Log.e(SaveLoadViewModelConstants.TAG, "performSynchronousSave FAILED for slot $slot: unknown error")
                showError("保存失败，请稍后手动保存")
                false
            }
        }
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) {
        Log.e(SaveLoadViewModelConstants.TAG, "performSynchronousSave ERROR: ${e.message}", e)
        showError("保存错误：${e.message}")
        false
    }
}

internal fun SaveLoadViewModel.startGameLoop() {
    gameEngineCore.startGameLoop()
    isTimeRunningFlow.value = true
    Log.d(SaveLoadViewModelConstants.TAG, "Game loop started via GameEngineCore")
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun SaveLoadViewModel.stopGameLoop() {
    try {
        gameEngineCore.stopGameLoop()
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) {
        Log.e(SaveLoadViewModelConstants.TAG, "Error stopping game loop", e)
    } finally {
        isTimeRunningFlow.value = false
    }
}

internal suspend fun SaveLoadViewModel.preloadGameResources() {
    preloadPhaseFlow.value = SaveLoadViewModelConstants.PHASE_DATA_PRELOAD
    val result = resourcePreloader.preloadGameResources(
        onProgress = { loadingProgressFlow.value = it },
        onPhase = { preloadPhaseFlow.value = it }
    )
    preloadedItemSpritesFlow.value = result.itemSprites
    atlasResultFlow.value = result.itemAtlas
    preloadedPortraitSpritesFlow.value = result.portraitSprites
    preloadedUiSpritesFlow.value = result.uiSprites
}

// generateMapPreloadData removed — now handled by BootSequenceController internally
