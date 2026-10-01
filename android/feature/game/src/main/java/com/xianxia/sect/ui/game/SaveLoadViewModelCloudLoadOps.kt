package com.xianxia.sect.ui.game

import android.util.Log
import com.xianxia.sect.core.engine.loadData
import com.xianxia.sect.data.model.SaveData
import kotlinx.coroutines.*

// ── 云档下载后的内存加载 + boot（自 SaveLoadViewModel 拆出）─────────────────────

/**
 * 云下载后的内存加载 + boot。
 *
 * 云会话独立加载——下载快照只进内存加载，不写本地档；返回 [Result] 由
 * 调用方决定成功/失败反馈（游戏内云下载反馈通道不同）。
 *
 * @return boot 结果；失败时消息可直接展示给玩家
 */
internal suspend fun SaveLoadViewModel.applyCloudSaveToEngine(
    reconciled: SaveData
): Result<Unit> {
    // 玉符防回退：与 performLoadGame 同因——云下载替换快照前
    // 必须等待旧循环 finally 的玉符 checkpointNow 彻底完成，否则旧运行时值
    // 覆盖新档玉符四字段（cloudDownloadLock 已互斥 save/load，此处无并发洞）
    val stopped = gameEngineCore.stopGameLoopAndWait(SaveLoadViewModelConstants.GAME_LOOP_STOP_TIMEOUT_MS)
    if (!stopped) {
        Log.e(SaveLoadViewModelConstants.TAG, "=== cloud download FAILED === cannot stop game loop within timeout")
        return Result.failure(IllegalStateException("无法停止游戏循环，请重试"))
    }
    isTimeRunningFlow.value = false
    Log.d(SaveLoadViewModelConstants.TAG, "Game loop stopped for cloud download")

    // 云会话加载全程保持 isLoading=true——驱动存档弹窗的"转圈+读取中"
    // 反馈覆盖 boot 阶段；isLoading 不驱动全屏加载页（游戏内弹窗独立窗口
    // + 遮罩会盖住全屏）
    loadingProgressFlow.value = SaveLoadViewModelConstants.PROGRESS_START
    preloadPhaseFlow.value = SaveLoadViewModelConstants.PHASE_CLOUD_SYNC
    setSaveLoadState(isLoading = true, pendingAction = "load")

    try {
        gameEngine.loadData(
            gameData = reconciled.gameData,
            disciples = reconciled.disciples,
            equipmentInstances = reconciled.equipmentInstances,
            manualStacks = reconciled.manualStacks,
            manualInstances = reconciled.manualInstances,
            pills = reconciled.pills,
            materials = reconciled.materials,
            herbs = reconciled.herbs,
            seeds = reconciled.seeds,
            storageBags = reconciled.storageBags,
            battleLogs = reconciled.battleLogs,
            alliances = reconciled.alliances,
            productionSlots = reconciled.productionSlots
        )

        // 邮件整对象替换回表——本地邮件表替换为下载快照的 mails，boot/会话期
        // 新邮件在其上叠加；否则下次保存/上传会用本地残留旧表覆盖云邮件
        //（换设备丢邮件 = 已根治的缺口）。仅替换邮件表——云恢复全量落盘另有通道
        //（CloudSaveCacheWriter），此处不越界；失败上抛由调用方统一报错。
        persistenceFacade.storageFacade.replaceMails(
            reconciled.mails
        )

        // 与本地读档路径一致：AI 宗门 RNG 不在此播种——真源 = C++ GameCore::aiRng_，
        // 随 rngStates 9 号键（AI_SECT_MIRROR）续接归档态；旧档无该键时 native 侧按
        // GameData.mapSeed + 6×31337 播种（AISectDiscipleManager.initForSlot 语义，
        // 见 applyLoadedSaveToEngine KDoc）

        val bootResult = persistenceFacade.bootSequenceController.boot(
            onPreloadResources = { preloadGameResources() },
            onProgress = { progress ->
                loadingProgressFlow.value = SaveLoadViewModelConstants.PROGRESS_START + progress * (
                    SaveLoadViewModelConstants.PROGRESS_COMPLETE - SaveLoadViewModelConstants.PROGRESS_START)
            },
            onMapReady = { mapData -> mapPreloadDataFlow.value = mapData }
        )

        return if (bootResult.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(
                bootResult.exceptionOrNull() ?: IllegalStateException("云存档加载失败")
            )
        }
    } finally {
        // 复位加载标志（NonCancellable 保证取消路径也执行，对齐 C4 resetOwnedLoadState 模式）
        withContext(NonCancellable) {
            setSaveLoadState(isLoading = false, pendingAction = null)
        }
    }
}
