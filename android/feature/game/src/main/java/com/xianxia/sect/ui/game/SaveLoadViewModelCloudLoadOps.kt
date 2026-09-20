package com.xianxia.sect.ui.game

import android.util.Log
import com.xianxia.sect.core.engine.loadData
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.migration.MigrationResult
import com.xianxia.sect.data.migration.SaveDataVersionMigrator
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.SaveDataReconciler
import com.xianxia.sect.taptap.TapCloudSaveManager
import kotlinx.coroutines.*
import com.xianxia.sect.core.engine.sendWhitelistBonus

// ── 云读档流程（云档管线/云会话独立加载/槽位归一）（自 SaveLoadViewModel 拆出，行为零变更）─────────────────────
// batch-02 TooManyFunctions/LargeClass 收敛外移为同包扩展，调用点语法不变。

/**云读档主流程。 */
// 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
@Suppress("TooGenericExceptionCaught", "ReturnCount") // 云档多失败守卫（空数据/损坏/写入失败），多 return 为守卫风格
internal suspend fun SaveLoadViewModel.performCloudLoad() {
        try {
            loadingProgressFlow.value = 0.1f
            preloadPhaseFlow.value = SaveLoadViewModelConstants.PHASE_CLOUD_SYNC

            // 与本地读档/保存的并发互斥由 loadGame/saveGame 的
            // cloudDownloadLock 检查保证（本路径全程持有 cloudDownloadLock）
            // ——不设 isLoading（挂起设置会引入纯 JVM 测试环境无法恢复的
            // 协程挂起点，且主菜单场景循环未启动、isLoading 无冻结语义）

            if (!isCloudSaveAvailable()) {
                showError("请先登录 TapTap")
                return
            }

            val result = persistenceFacade.tapCloudSaveManager.downloadSave()

            when (result) {
                is TapCloudSaveManager.CloudSaveResult.Success -> handleCloudLoadSuccess(result)
                is TapCloudSaveManager.CloudSaveResult.NetworkError ->
                    showError("网络错误: ${result.message}")
                is TapCloudSaveManager.CloudSaveResult.AuthRequired ->
                    showError("请先登录 TapTap 账号")
                is TapCloudSaveManager.CloudSaveResult.SerializationError ->
                    showError("存档数据异常: ${result.message}")
                is TapCloudSaveManager.CloudSaveResult.FileTooLarge ->
                    showError("云存档文件过大，无法下载")
                is TapCloudSaveManager.CloudSaveResult.NoSaveExists ->
                    showError("云存档不存在")
                is TapCloudSaveManager.CloudSaveResult.VersionMismatch ->
                    showError("云存档来自版本 ${result.cloudVersion}，当前版本 ${result.currentVersion} 不支持加载")
                is TapCloudSaveManager.CloudSaveResult.UnknownError ->
                    showError("未知错误: ${result.message}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (e: OutOfMemoryError) {
            // 恶意/异常超大云档反序列化 OOM——OutOfMemoryError 不是
            // Exception，不捕获会直接崩溃；降级为错误提示
            Log.e(SaveLoadViewModelConstants.TAG, "云存档数据过大导致内存不足", e)
            showError("云存档数据过大，内存不足，无法加载")
        } catch (e: Exception) {
            Log.e(SaveLoadViewModelConstants.TAG, "loadFromCloudSave failed", e)
            showError("加载云存档失败: ${e.message}")
        } finally {
            cloudDownloadLock.set(false)
        }
}

/** 云读档 Success 分支：管线 → 云会话独立加载 */
@Suppress("ReturnCount") // 云档多失败守卫（空数据/损坏/写入失败），多 return 为守卫风格
internal suspend fun SaveLoadViewModel.handleCloudLoadSuccess(result: TapCloudSaveManager.CloudSaveResult.Success) {
    val saveData = result.saveData
    if (saveData == null) {
        showError("云存档数据为空")
        return
    }

    // 云档管线与本地读档同语义——
    // 版本迁移 → 完整性校验（损坏拒绝/可修复继续）→ 堆叠重建
    val migration = SaveDataVersionMigrator.migrate(saveData)
    if (migration is MigrationResult.Rejected) {
        // saveVersion 越界（负数/伪造高版本）显式拒绝
        showError("云存档版本异常：${migration.reason}")
        return
    }
    var processed = (migration as MigrationResult.Migrated).data
    val validation = SaveValidator.validate(processed)
    when (validation) {
        is IntegrityResult.Corrupted -> {
            showError("云存档数据损坏，无法加载")
            return
        }
        is IntegrityResult.Repaired -> {
            Log.w(SaveLoadViewModelConstants.TAG, "云存档完整性修复 ${validation.details.size} 项")
            processed = validation.data
        }
        is IntegrityResult.Passed -> {}
    }
    processed = SaveDataReconciler.reconcileStacks(processed)

    // 云存档为独立存档，读取不覆盖任何本地槽位——
    // 直接以云会话槽位 0 加载进内存（本地 1..6 槽位零影响，无需覆盖确认；
    // 覆盖确认弹窗仅游戏主界面可渲染，主菜单读档场景会永久卡死）
    persistenceFacade.storageFacade.setCurrentSlot(StorageConstants.CLOUD_SAVE_SLOT)
    val bootResult = applyCloudSaveToEngine(processed, StorageConstants.CLOUD_SAVE_SLOT)
    if (bootResult.isFailure) {
        showError("读取云存档失败: ${bootResult.exceptionOrNull()?.message}")
    }
}

/**
 * 云下载后的内存加载 + boot。
 *
 * 云会话独立加载——[effectiveSlot] 为云会话槽位
 * [StorageConstants.CLOUD_SAVE_SLOT]，不落盘任何本地槽位；返回 [Result] 由
 * 调用方决定成功/失败反馈（主菜单云读档与游戏内云下载反馈通道不同）。
 *
 * @return boot 结果；失败时消息可直接展示给玩家
 */
internal suspend fun SaveLoadViewModel.applyCloudSaveToEngine(reconciled: SaveData, effectiveSlot: Int): Result<Unit> {
    // 云档 slotId 为 @Transient 恒 0——只修 currentSlot
    // 会让 loadFromSnapshot 内 repository.setActiveSlot(gameData.slotId) 拿到 0，
    // 后续 repository 脏写指向错误槽位；slotId/currentSlot 必须同时修正。
    // 注（b02 发现 11 根治后口径精确化）：本处"恒 0"源于**存档序列化面**
    // （@Transient 不入 JSON，云档解码必为 0）——与已根治的"镜像每旬重置"
    // 是两个来源；镜像修复不影响本绕法必要性（云档侧恒 0 依旧成立）
    val resolvedGameData = reconcileCloudSlot(reconciled, effectiveSlot)
    // 玉符防回退：与 performLoadToSlot 同因——云下载替换快照前
    // 必须等待旧循环 finally 的玉符 checkpointNow 彻底完成，否则旧运行时值
    // 覆盖新档玉符四字段（cloudDownloadLock 已互斥 save/load，此处无并发洞）
    val stopped = gameEngineCore.stopGameLoopAndWait(SaveLoadViewModelConstants.GAME_LOOP_STOP_TIMEOUT_MS)
    if (!stopped) {
        Log.e(SaveLoadViewModelConstants.TAG, "=== cloud download FAILED === cannot stop game loop within timeout")
        return Result.failure(IllegalStateException("无法停止游戏循环，请重试"))
    }
    isTimeRunningFlow.value = false
    Log.d(SaveLoadViewModelConstants.TAG, "Game loop stopped for cloud download")

    // 云会话加载全程保持 isLoading=true——驱动存档弹窗（SaveSlotDialog/
    // CloudSaveDialog）的"转圈+读取中"反馈覆盖 boot 阶段；isLoading 不驱动
    // 全屏加载页（游戏内弹窗独立窗口 + 遮罩会盖住全屏）
    loadingProgressFlow.value = SaveLoadViewModelConstants.PROGRESS_START
    preloadPhaseFlow.value = SaveLoadViewModelConstants.PHASE_CLOUD_SYNC
    setSaveLoadState(isLoading = true, pendingSlot = 0, pendingAction = "load")

    try {
        gameEngine.loadData(
            gameData = resolvedGameData,
            disciples = reconciled.disciples,
            equipmentStacks = reconciled.equipmentStacks,
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

        // 与本地读档路径一致：AI 宗门 RNG 不在此播种——真源 = C++ GameCore::aiRng_，
        // 随 rngStates 9 号键（AI_SECT_MIRROR）续接归档态；旧档无该键时 native 侧按
        // GameData.mapSeed + 6×31337 播种（原 initForSlot 语义，见 applyLoadedSaveToEngine KDoc）

        val bootResult = persistenceFacade.bootSequenceController.boot(
            slot = effectiveSlot,
            onPreloadResources = { preloadGameResources() },
            onProgress = { progress ->
                loadingProgressFlow.value = SaveLoadViewModelConstants.PROGRESS_START + progress * (
                    SaveLoadViewModelConstants.PROGRESS_COMPLETE - SaveLoadViewModelConstants.PROGRESS_START)
            },
            onMapReady = { mapData -> mapPreloadDataFlow.value = mapData }
        )

        return if (bootResult.isSuccess) {
            // 与本地读档/新游戏路径一致：注入白名单福利
            gameEngine.sendWhitelistBonus(effectiveSlot)

            Result.success(Unit)
        } else {
            Result.failure(
                bootResult.exceptionOrNull() ?: IllegalStateException("云存档加载失败")
            )
        }
    } finally {
        // 复位加载标志（NonCancellable 保证取消路径也执行，对齐 C4 resetOwnedLoadState 模式）
        withContext(NonCancellable) {
            setSaveLoadState(isLoading = false, pendingSlot = null, pendingAction = null)
        }
    }
}

/**
 * 云档槽位解析——slotId 与 currentSlot 同时修正为目标槽位。
 *
 * 云档 gameData.slotId 为 @Transient 恒 0、currentSlot 是上传时来源槽位
 *（与目标槽位无关）；loadFromSnapshot 内部用 gameData.slotId 设置仓库
 * 活跃槽位，只修 currentSlot 会导致 repository 脏写指向槽位 0。
 *
 * 云存档独立会话——[effectiveSlot] 恒为
 * [StorageConstants.CLOUD_SAVE_SLOT]（0），云会话数据落 slot 0 云镜像，
 * 本地 1..6 槽位零影响。
 */
internal fun SaveLoadViewModel.reconcileCloudSlot(reconciled: SaveData, effectiveSlot: Int):
    com.xianxia.sect.core.model.GameData {
    return reconciled.gameData.copy(
        currentSlot = effectiveSlot,
        slotId = effectiveSlot
    )
}
