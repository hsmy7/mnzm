package com.xianxia.sect.ui.game

import android.util.Log
import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.engine.restartGameSuspend
import com.xianxia.sect.data.model.SaveData
import kotlinx.coroutines.*
import com.xianxia.sect.core.engine.sendWhitelistBonus

// ── 重启流程（守卫/取锁/引擎重置/重存/收尾复位）（自 SaveLoadViewModel 拆出，行为零变更）─────────────────────
// batch-02 TooManyFunctions/LargeClass 收敛外移为同包扩展，调用点语法不变。

/**
 * 重启前置守卫（restartGame 拆分，未取锁阶段）：boot 进行中 → 云操作互斥 →
 * 游戏已加载。
 *
 * @return 守卫未通过返回 true（调用方直接返回）
 */
internal fun SaveLoadViewModel.restartBlockedBeforeLocks(): Boolean {
    // boot 进行中禁止重启（boot 的 runState 中途为
    // RELOADING，isGameLoaded 守卫失效，必须用 bootInProgress 兜底）
    if (isBootOperationBlocked()) return true

    // 云存档操作进行中禁止重启——云读档/云下载（不设 isLoading）期间
    // 重启可穿入，与云会话并发触发第二个 boot（报 boot() already in progress）
    if (cloudDownloadLock.get()) {
        Log.w(SaveLoadViewModelConstants.TAG, "Cloud save operation in progress, ignoring restartGame request")
        showError("云存档操作进行中，请稍后重置")
        return true
    }

    // boot 失败（runState=IDLE）后内存可能残留新档数据，
    // 此守卫防止用残留数据覆写磁盘（与 loadGame 同模式）
    if (!isGameLoaded) {
        Log.w(SaveLoadViewModelConstants.TAG, "Game not loaded, ignoring restartGame request")
        return true
    }
    return false
}

/**
 * 重启锁获取：saveLock CAS → loadLock CAS（取锁序
 * saveLock→loadLock，无死锁）→ 读档状态 → 内存充足；失败按已持有范围回退。
 *
 * @return 全部获取成功返回 true；失败时已按原语义释放并提示
 */
internal fun SaveLoadViewModel.acquireRestartLocks(): Boolean {
    if (!saveLock.compareAndSet(false, true)) {
        Log.w(SaveLoadViewModelConstants.TAG, "Already saving, ignoring restartGame request")
        return false
    }

    // restart 与 load 完整互斥——load 已抢 loadLock 时拒绝，
    // 防止 load 的 clear+insert 与 restart 的引擎重置/存档并发；取锁序
    // saveLock→loadLock，loadGame 只取 loadLock、saveGame 只读两锁，无死锁
    if (!loadLock.compareAndSet(false, true)) {
        Log.w(SaveLoadViewModelConstants.TAG, "Load in progress, ignoring restartGame request")
        saveLock.set(false)
        showError("正在读档中，请稍后重置")
        return false
    }

    if (stateStore.isLoading.value) {
        Log.w(SaveLoadViewModelConstants.TAG, "Already loading, ignoring restartGame request")
        releaseRestartLocks()
        return false
    }

    if (!canPerformSaveOperation()) {
        Log.e(SaveLoadViewModelConstants.TAG, "=== restartGame FAILED === insufficient memory")
        showError("内存不足，无法重置。请关闭其他应用后重试。")
        releaseRestartLocks()
        return false
    }
    return true
}

/** 重启锁回退：复位重启标记并按逆序释放 loadLock/saveLock */
internal fun SaveLoadViewModel.releaseRestartLocks() {
    isRestartingFlow.value = false
    loadLock.set(false)
    saveLock.set(false)
}

/**
 * 重开保护性预存（SR-2，审计 §2 重开顺序缺陷修正）：重置引擎**前**把当前内存态落盘。
 *
 * 复用 [performRestartSave] 全链（当前态快照 → 槽位邮件 → SaveData → 带超时落盘 →
 * 损坏自愈 → 失败回滚 currentSlot）——预存与"重置后落新档"同一条代码路径，零新保存逻辑。
 *
 * @return true = 预存成功（可安全重置引擎）；false = 预存失败（**必须中止重置**：
 * 旧档此时仅存在于盘上，继续重置将以重置态覆写唯一副本；已如实提示）
 */
internal suspend fun SaveLoadViewModel.protectivePreSaveBeforeRestart(slot: Int, previousSlot: Int): Boolean {
    setSaveLoadState(isSaving = true, pendingSlot = slot, pendingAction = "save")
    val success = try {
        performRestartSave(slot = slot, previousSlot = previousSlot)
    } finally {
        setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)
    }
    if (!success) {
        Log.e(
            SaveLoadViewModelConstants.TAG,
            "=== restartGame ABORTED === protective pre-save failed (slot=$slot), old save preserved"
        )
        showError("重置中止：预存当前进度失败，原存档已保留，请重试")
    } else {
        Log.i(SaveLoadViewModelConstants.TAG, "restartGame: protective pre-save OK (slot=$slot)")
    }
    return success
}

/**
 *重启主流程。
 * 停止循环 → 重置引擎 → RNG 重新播种 → 重启存档 → BootSequenceController 启动。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun SaveLoadViewModel.performRestartGame(wasRunning: Boolean) {
    var previousSlot = 1
    try {
        isRestartingFlow.value = true

        if (!stopLoopForRestart(wasRunning = wasRunning)) {
            return
        }

        performGarbageCollection()

        val currentData = gameEngine.gameData.value
        val sectName = currentData.sectName.ifBlank { "QingYunSect" }
        val currentSlot = currentData.currentSlot.let { if (it >= 0) it else 1 }
        previousSlot = persistenceFacade.storageFacade.getCurrentSlot()

        Log.i(SaveLoadViewModelConstants.TAG,
            "=== restartGame BEGIN === currentSlot=$currentSlot, previousSlot=$previousSlot, sectName=$sectName")

        persistenceFacade.storageFacade.setCurrentSlot(currentSlot)

        // SR-2 修复（审计 §2 重开顺序缺陷，先预存 → 后重置 → 再落新档）：
        // 旧实现先 restartEngineAndReseed（内存态被引擎重置覆盖）后落新档——若重置后
        // 落盘前被杀/失败，旧档被重置态覆写。保护性预存复用 performRestartSave 全链
        //（快照/邮件/落盘/超时/损坏自愈/槽位回滚）；预存失败 = 中止重置：此时引擎
        // 未动、旧档仍在盘上，如实提示后玩家可直接重试。
        if (!protectivePreSaveBeforeRestart(slot = currentSlot, previousSlot = previousSlot)) return

        restartEngineAndReseed(sectName = sectName, currentSlot = currentSlot)

        setSaveLoadState(isSaving = true, pendingSlot = currentSlot, pendingAction = "save")

        val saveSuccess = performRestartSave(slot = currentSlot, previousSlot = previousSlot)

        setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)

        if (saveSuccess) {
            Log.i(SaveLoadViewModelConstants.TAG, "=== restartGame SAVE SUCCESS === slot=$currentSlot")
            restartVersionFlow.value++
            showSuccess("游戏已重置")
        } else {
            Log.e(SaveLoadViewModelConstants.TAG, "=== restartGame SAVE FAILED === slot=$currentSlot")
            showError("游戏已重置，但保存失败，请手动保存")
        }

        performRestartBoot(currentSlot = currentSlot)
    } catch (e: CancellationException) {
        Log.w(SaveLoadViewModelConstants.TAG, "restartGame cancelled")
        throw e
    } catch (e: OutOfMemoryError) {
        Log.e(SaveLoadViewModelConstants.TAG, "=== restartGame FAILED === OutOfMemoryError", e)
        persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
        showError("内存不足，重置失败。请关闭其他应用后重试。")
        setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)
    } catch (e: Exception) {
        Log.e(SaveLoadViewModelConstants.TAG, "=== restartGame FAILED === error=${e.message}", e)
        persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
        showError(e.message ?: "重置游戏失败")
        setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)
    } finally {
        resetRestartState(wasRunning = wasRunning)
    }
}

/**重启前停止游戏循环：超时守卫 + 状态复位 */
@Suppress("ReturnCount")
internal suspend fun SaveLoadViewModel.stopLoopForRestart(wasRunning: Boolean): Boolean {
    if (!wasRunning) return true
    val stopped = gameEngineCore.stopGameLoopAndWait(5000)
    if (!stopped) {
        Log.e(SaveLoadViewModelConstants.TAG, "Failed to stop game loop within timeout")
        showError("无法停止游戏循环，请重试")
        return false
    }
    isTimeRunningFlow.value = false
    Log.d(SaveLoadViewModelConstants.TAG, "Game loop stopped for restart operation")
    return true
}

/**引擎重置 + RNG 重新播种 */
internal suspend fun SaveLoadViewModel.restartEngineAndReseed(sectName: String, currentSlot: Int) {
    // initSystemSeed 与 AISectDiscipleManager.initForSlot 由
    // restartGameSuspend 在引擎上下文内执行（播种在引擎线程、生成世界前
    // 完成），此处不得在 UI 协程重复执行——重复播种属全局 RNG 分区的
    // 跨线程竞争点
    gameEngine.restartGameSuspend(sectName, currentSlot)
    Log.d(
        SaveLoadViewModelConstants.TAG,
        "restartGame: engine restarted, RNG seeded on engine thread " +
            "with mapSeed=${gameEngine.gameData.value.mapSeed}"
    )
}

/**重启后启动序列：BootSequenceController.boot + 福利注入 */
internal suspend fun SaveLoadViewModel.performRestartBoot(currentSlot: Int): Boolean {
    // BootSequenceController 统一处理生命周期、游戏循环重启、地图生成
    val bootResult = persistenceFacade.bootSequenceController.boot(
        slot = currentSlot,
        onPreloadResources = { preloadGameResources() },
        onProgress = { progress ->
            loadingProgressFlow.value = SaveLoadViewModelConstants.PROGRESS_START + progress * (
                SaveLoadViewModelConstants.PROGRESS_COMPLETE - SaveLoadViewModelConstants.PROGRESS_START)
        },
        onMapReady = { mapData -> mapPreloadDataFlow.value = mapData }
    )

    if (bootResult.isSuccess) {
        isTimeRunningFlow.value = true
        // 重开即新档：与主菜单新游戏路径一致，注入白名单福利
        gameEngine.sendWhitelistBonus(currentSlot)
        return true
    } else {
        Log.e(SaveLoadViewModelConstants.TAG,
            "restartGame: boot sequence failed after restart, error=${bootResult.exceptionOrNull()?.message}")
        return false
    }
}

/**重启收尾复位：锁成对释放 + 归属化清理 + 兜底日志 */
internal suspend fun SaveLoadViewModel.resetRestartState(wasRunning: Boolean) {
    isRestartingFlow.value = false
    // loadLock 与 saveLock 成对复位（入口同步抢锁，
    // 协程结束/取消统一释放，防泄漏）
    loadLock.set(false)
    // restart 归属化清理 + 标志复位——取消路径（isSaving=true 后
    // CancellationException）不复位标志会导致 isSaving 泄漏
    resetOwnedLoadState("restartGame")
    saveLock.set(false)
    // 兜底：若 boot() 未执行（提前 return），则仅记录日志
    // BootSequenceController.boot() 在内部已处理游戏循环恢复
    if (wasRunning && !isTimeRunningFlow.value) {
        Log.w(SaveLoadViewModelConstants.TAG,
            "restartGame: game loop not running after restart finally, boot() may have failed")
    }
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun SaveLoadViewModel.performRestartSave(slot: Int, previousSlot: Int): Boolean {
    return withContext(ioDispatcher.dispatcher) {
        try {
            val snapshot = gameEngine.buildSaveSnapshot()
            if (snapshot.gameData.sectName.isBlank()) {
                Log.e(SaveLoadViewModelConstants.TAG, "performRestartSave: gameData not initialized")
                persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
                return@withContext false
            }

            Log.d(SaveLoadViewModelConstants.TAG,
                "performRestartSave snapshot: productionSlots=${snapshot.productionSlots.size}, " +
                "gameData.productionSlots=${snapshot.gameData.productionSlots.size}, " +
                "disciples=${snapshot.disciples.size}")

            // SR-1：重启预存从 mails 表读当前 slot 全量入快照
            // （读失败经外层 catch 如实报失败并回滚 currentSlot）
            val slotMails = readSlotMails(slot)
            val saveData = buildRestartSaveData(snapshot = snapshot, mails = slotMails)
            persistRestartSave(slot = slot, previousSlot = previousSlot, saveData = saveData)
        } catch (e: OutOfMemoryError) {
            Log.e(SaveLoadViewModelConstants.TAG, "performRestartSave OutOfMemoryError for slot $slot", e)
            persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
            false
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            Log.e(SaveLoadViewModelConstants.TAG, "performRestartSave error for slot $slot", e)
            persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
            false
        }
    }
}

/**重启存档数据组装：快照 + 槽位邮件快照 → SaveData（stacksSerialized 防旧堆叠泄漏；mails 必填见 SaveDataTrimmer） */
internal fun SaveLoadViewModel.buildRestartSaveData(
    snapshot: GameStateSnapshot,
    mails: List<com.xianxia.sect.core.model.MailEntity>
): SaveData {
    return SaveData(
        gameData = snapshot.gameData,
        disciples = snapshot.disciples,
        equipmentStacks = snapshot.equipmentStacks,
        equipmentInstances = snapshot.equipmentInstances,
        manualStacks = snapshot.manualStacks,
        manualInstances = snapshot.manualInstances,
        pills = snapshot.pills,
        materials = snapshot.materials,
        herbs = snapshot.herbs,
        seeds = snapshot.seeds,
        battleLogs = snapshot.battleLogs,
        alliances = snapshot.alliances,
        productionSlots = snapshot.productionSlots,
        storageBags = snapshot.storageBags,
        mails = mails,
        // restart 保存必须带该标志，否则删表守卫失效，
        // 旧世界堆叠残留泄漏进新世界
        stacksSerialized = true
    )
}

/**重启存档落盘：超时保护 + 结果分派 + 损坏自愈 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun SaveLoadViewModel.persistRestartSave(slot: Int, previousSlot: Int, saveData: SaveData): Boolean {
    val success = withTimeoutOrNull(30_000L) {
        persistenceFacade.storageFacade.save(slot, saveData).isSuccess
    }

    return when (success) {
        true -> {
            try {
                saveSlotsFlow.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                Log.e(SaveLoadViewModelConstants.TAG, "Failed to refresh slots after restart save: ${e.message}", e)
            }
            Log.i(SaveLoadViewModelConstants.TAG, "performRestartSave success for slot $slot")
            true
        }
        null -> {
            Log.e(SaveLoadViewModelConstants.TAG, "performRestartSave timeout for slot $slot")
            persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
            if (persistenceFacade.storageFacade.isSaveCorruptedSuspend(slot)) {
                // 真恢复（读 .sav/.bak → 写回 DB）；旧实现是空函数，日志谎报"已尝试恢复"
                val restored = persistenceFacade.storageFacade.restoreFromBackupIfCorrupted(slot)
                Log.w(
                    SaveLoadViewModelConstants.TAG,
                    "Save may be corrupted, backup restore " +
                        "${if (restored) "succeeded" else "failed"} for slot $slot"
                )
            }
            false
        }
        false -> {
            Log.e(SaveLoadViewModelConstants.TAG, "performRestartSave failed for slot $slot")
            persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
            false
        }
    }
}
