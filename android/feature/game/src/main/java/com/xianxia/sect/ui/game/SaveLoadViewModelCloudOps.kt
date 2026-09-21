package com.xianxia.sect.ui.game

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.migration.MigrationResult
import com.xianxia.sect.data.migration.SaveDataVersionMigrator
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.SaveDataReconciler
import com.xianxia.sect.taptap.TapCloudSaveManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.xianxia.sect.data.model.SaveSlot

// ── 云存档流程（查询/上传/下载/操作状态机）（自 SaveLoadViewModel 拆出，行为零变更）─────────────────────
// batch-02 TooManyFunctions/LargeClass 收敛外移为同包扩展，调用点语法不变。

// ── 云存档操作方法 ──

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun SaveLoadViewModel.checkCloudSave() {
    val fetchVersion = cloudSaveInfoVersion.incrementAndGet()
    viewModelScope.launch(ioDispatcher.dispatcher) {
        try {
            // 老玩家首次使用：清理旧版本残留的孤立存档
            persistenceFacade.tapCloudSaveManager.oneTimeCleanup()
            // 查询云端存档信息
            val info = persistenceFacade.tapCloudSaveManager.checkCloudSave()
            if (fetchVersion == cloudSaveInfoVersion.get()) {
                cloudSaveInfoFlow.value = info
            }
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            Log.w(SaveLoadViewModelConstants.TAG, "checkCloudSave failed", e)
        }
    }
}

internal fun SaveLoadViewModel.uploadToCloudSave() {
    if (cloudSaveOperationStateFlow.value is CloudSaveOperationState.Uploading ||
        cloudSaveOperationStateFlow.value is CloudSaveOperationState.Downloading) return
    viewModelScope.launch(ioDispatcher.dispatcher) {
        // 云上传主流程
        performCloudUpload()
    }
}

/**云上传主流程。 */
// RethrowCaughtException 与项目规范 8.1 冲突（CancellationException 必须重抛），压制
// 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
@Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod", "RethrowCaughtException")
internal suspend fun SaveLoadViewModel.performCloudUpload() {
        if (!isCloudSaveAvailable()) {
            cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("请先登录TapTap账号")
            return
        }

        cloudSaveOperationStateFlow.value = CloudSaveOperationState.Uploading

        try {
            val snapshot = gameEngine.buildSaveSnapshot()
            val saveData = createSaveDataFromSnapshot(snapshot)

            // 上传前校验：与本地保存/读档管线同语义——
            // 损坏拒绝、可修复用修复后数据、版本迁移到当前
            val uploadData = validateForUpload(saveData)
                ?: return

            val result = persistenceFacade.tapCloudSaveManager.uploadSave(uploadData)

            cloudSaveOperationStateFlow.value = when (result) {
                is TapCloudSaveManager.CloudSaveResult.Success -> {
                    // 直接用刚上传的 saveData 构造 CloudSaveInfo，避免 TapTap API 最终一致性延迟
                    val gd = saveData.gameData
                    val cloudInfo = TapCloudSaveManager.CloudSaveInfo(
                        hasSaveData = true,
                        lastModifiedTime = System.currentTimeMillis(),
                        description = "第${gd.gameYear}年${gd.gameMonth}月 ${gd.sectName}",
                        gameYear = gd.gameYear,
                        gameMonth = gd.gameMonth,
                        sectName = gd.sectName,
                        discipleCount = saveData.disciples.size,
                        spiritStones = gd.spiritStones,
                        appVersion = GameConfig.Game.VERSION
                    )
                    cloudSaveInfoFlow.value = cloudInfo
                    // 持久化到本地缓存，避免关闭对话框后 checkCloudSave() 因 API 延迟返回空
                    persistenceFacade.tapCloudSaveManager.saveCloudSaveInfoToLocal(cloudInfo)
                    cloudSaveInfoVersion.incrementAndGet()
                    CloudSaveOperationState.Success("云存档上传成功")
                }
                is TapCloudSaveManager.CloudSaveResult.NetworkError ->
                    CloudSaveOperationState.Error("网络错误: ${result.message}")
                is TapCloudSaveManager.CloudSaveResult.AuthRequired ->
                    CloudSaveOperationState.Error("请先登录TapTap账号")
                is TapCloudSaveManager.CloudSaveResult.SerializationError ->
                    CloudSaveOperationState.Error("序列化失败: ${result.message}")
                is TapCloudSaveManager.CloudSaveResult.FileTooLarge -> {
                    val actualMb = result.actualBytes / (1024 * 1024)
                    CloudSaveOperationState.Error("存档过大(${actualMb}MB)，无法上传")
                }
                is TapCloudSaveManager.CloudSaveResult.NoSaveExists ->
                    CloudSaveOperationState.Error("保存失败")
                is TapCloudSaveManager.CloudSaveResult.VersionMismatch ->
                    CloudSaveOperationState.Error("版本不兼容: ${result.cloudVersion}")
                is TapCloudSaveManager.CloudSaveResult.UnknownError ->
                    CloudSaveOperationState.Error("未知错误: ${result.message}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("上传失败: ${e.message}")
        }
}

/**
 * 上传前校验与版本迁移。
 *
 * @return 可上传的数据（迁移+校验通过，可修复时用修复后数据）；失败置错误
 * 状态并返回 null
 */
@Suppress("ReturnCount") // 版本/校验多失败守卫，多 return 为守卫风格
internal fun SaveLoadViewModel.validateForUpload(saveData: SaveData): SaveData? {
    val migration = SaveDataVersionMigrator.migrate(saveData)
    if (migration is MigrationResult.Rejected) {
        cloudSaveOperationStateFlow.value =
            CloudSaveOperationState.Error("存档版本异常，无法上传: ${migration.reason}")
        return null
    }
    var uploadData = (migration as MigrationResult.Migrated).data
    val validation = SaveValidator.validate(uploadData)
    when (validation) {
        is IntegrityResult.Corrupted -> {
            cloudSaveOperationStateFlow.value =
                CloudSaveOperationState.Error("存档数据损坏，无法上传")
            return null
        }
        is IntegrityResult.Repaired -> {
            Log.w(SaveLoadViewModelConstants.TAG, "上传前完整性修复 ${validation.details.size} 项")
            uploadData = validation.data
        }
        is IntegrityResult.Passed -> {}
    }
    return uploadData
}

@Suppress("ReturnCount") // 云下载多守卫（boot/重启/保存/云锁/加载），多 return 为守卫风格
internal fun SaveLoadViewModel.downloadFromCloudSave() {
    // boot/重启/保存进行中禁止云下载
    if (isBootOperationBlocked()) {
        cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("正在加载中，请稍后")
        return
    }
    if (isRestartingFlow.value) {
        Log.w(SaveLoadViewModelConstants.TAG, "Restarting, ignoring cloud download request")
        cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("游戏重置中，请稍后")
        return
    }
    if (saveLock.get()) {
        Log.w(SaveLoadViewModelConstants.TAG, "Save in progress, ignoring cloud download request")
        cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("正在保存中，请稍后")
        return
    }
    if (!cloudDownloadLock.compareAndSet(false, true)) {
        Log.w(SaveLoadViewModelConstants.TAG, "Cloud download already in progress, ignoring")
        return
    }
    // 与本地读档/加载重叠保护——云下载期间若有读档进行中，
    // 下载数据与加载状态并发会破坏状态一致性
    if (stateStore.isLoading.value) {
        Log.w(SaveLoadViewModelConstants.TAG, "Load in progress, ignoring cloud download request")
        cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("正在加载中，请稍后")
        cloudDownloadLock.set(false)
        return
    }
    viewModelScope.launch(ioDispatcher.dispatcher) {
        // 云下载主流程
        performCloudDownload()
    }
}

/**云下载主流程。 */
// 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
@Suppress("TooGenericExceptionCaught", "ReturnCount") // 云档多失败守卫（空数据/损坏/写入失败），多 return 为守卫风格
internal suspend fun SaveLoadViewModel.performCloudDownload() {
        try {
            if (!isCloudSaveAvailable()) {
                cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("请先登录TapTap账号")
                return
            }

            cloudSaveOperationStateFlow.value = CloudSaveOperationState.Downloading

            // 下载期间不设 isLoading——并发互斥由 loadGame/saveGame 的
            // cloudDownloadLock 检查保证；云会话独立加载，不落盘本地槽位
            //（无需备份）
            val result = persistenceFacade.tapCloudSaveManager.downloadSave()

            when (result) {
                is TapCloudSaveManager.CloudSaveResult.Success -> handleCloudDownloadSuccess(result)
                is TapCloudSaveManager.CloudSaveResult.NoSaveExists ->
                    cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("云存档不存在")
                is TapCloudSaveManager.CloudSaveResult.NetworkError ->
                    cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("网络错误: ${result.message}")
                is TapCloudSaveManager.CloudSaveResult.AuthRequired ->
                    cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("请先登录TapTap账号")
                is TapCloudSaveManager.CloudSaveResult.SerializationError ->
                    cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("反序列化失败: ${result.message}")
                is TapCloudSaveManager.CloudSaveResult.VersionMismatch ->
                    cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("版本不兼容: ${result.cloudVersion}")
                is TapCloudSaveManager.CloudSaveResult.UnknownError ->
                    cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("未知错误: ${result.message}")
                is TapCloudSaveManager.CloudSaveResult.FileTooLarge ->
                    cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("云存档文件过大")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (e: OutOfMemoryError) {
            // 恶意/异常超大云档反序列化 OOM 降级
            Log.e(SaveLoadViewModelConstants.TAG, "云存档数据过大导致内存不足", e)
            cloudSaveOperationStateFlow.value =
                CloudSaveOperationState.Error("云存档数据过大，内存不足，无法加载")
        } catch (e: Exception) {
            cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("下载失败: ${e.message}")
        } finally {
            cloudDownloadLock.set(false)
        }
}

/**
 * 云下载 Success 分支：管线 → 云会话独立加载
 */
@Suppress("ReturnCount") // 云档多失败守卫（空数据/损坏/写入失败），多 return 为守卫风格
internal suspend fun SaveLoadViewModel.handleCloudDownloadSuccess(result: TapCloudSaveManager.CloudSaveResult.Success) {
    val saveData = result.saveData
    if (saveData == null) {
        cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error("云存档为空")
        return
    }

    // 跨版本兼容提示：云端存档版本与当前版本不同时仅警告不阻止
    val cloudVersion = cloudSaveInfoFlow.value?.appVersion ?: ""
    if (cloudVersion.isNotBlank() && cloudVersion != GameConfig.Game.VERSION) {
        Log.w(SaveLoadViewModelConstants.TAG, "云存档版本 $cloudVersion ≠ 当前版本 ${GameConfig.Game.VERSION}，可能不兼容")
    }

    // 云档管线与本地读档同语义——
    // 版本迁移 → 完整性校验（损坏拒绝/可修复继续）→ 堆叠重建
    val migration = SaveDataVersionMigrator.migrate(saveData)
    if (migration is MigrationResult.Rejected) {
        // saveVersion 越界（负数/伪造高版本）显式拒绝
        cloudSaveOperationStateFlow.value =
            CloudSaveOperationState.Error("云存档版本异常：${migration.reason}")
        return
    }
    var processed = (migration as MigrationResult.Migrated).data
    val validation = SaveValidator.validate(processed)
    when (validation) {
        is IntegrityResult.Corrupted -> {
            cloudSaveOperationStateFlow.value =
                CloudSaveOperationState.Error("云存档数据损坏，无法加载")
            return
        }
        is IntegrityResult.Repaired -> {
            Log.w(SaveLoadViewModelConstants.TAG, "云存档完整性修复 ${validation.details.size} 项")
            processed = validation.data
        }
        is IntegrityResult.Passed -> {}
    }
    // 旧格式云存档无堆叠数据：从实例重建兜底
    val reconciled = SaveDataReconciler.reconcileStacks(processed)

    // 云存档为独立存档，下载不覆盖任何本地槽位——
    // 直接以云会话槽位 0 加载进内存，本地 1..6 槽位零影响。
    // 云会话的本地落盘（如重启保存）落在 slot 0 云镜像，
    // UI 槽位列表不暴露。
    persistenceFacade.storageFacade.setCurrentSlot(StorageConstants.CLOUD_SAVE_SLOT)
    val bootResult = applyCloudSaveToEngine(reconciled, StorageConstants.CLOUD_SAVE_SLOT)
    if (bootResult.isSuccess) {
        cloudSaveOperationStateFlow.value = CloudSaveOperationState.Success("云存档下载成功")
        cloudSaveInfoFlow.value = persistenceFacade.tapCloudSaveManager.checkCloudSave()
    } else {
        cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error(
            "读取云存档失败: ${bootResult.exceptionOrNull()?.message}"
        )
    }
}

internal fun SaveLoadViewModel.resetCloudSaveOperationState() {
    cloudSaveOperationStateFlow.value = CloudSaveOperationState.Idle
}

/**
 *slot=0 云保存流程（带 saveLoadState 管理 + 结果反馈）。
 * 从 saveGame 拆分。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun SaveLoadViewModel.saveToCloudViaSlot() {
    viewModelScope.launch(ioDispatcher.dispatcher) {
        resetCloudSaveOperationState()
        setSaveLoadState(isSaving = true, pendingSlot = 0, pendingAction = "save")
        try {
            uploadToCloudSave()
            // 等待云端操作完成（Uploading → Success/Error）
            cloudSaveOperationStateFlow.first {
                it is CloudSaveOperationState.Success || it is CloudSaveOperationState.Error
            }
            when (val state = cloudSaveOperationStateFlow.value) {
                is CloudSaveOperationState.Success -> {
                    showSuccess(state.message)
                    try {
                        saveSlotsFlow.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
                    } catch (e: CancellationException) { throw e }
                      catch (e: Exception) {
                        Log.w(SaveLoadViewModelConstants.TAG, "Failed to refresh slots after cloud save", e)
                    }
                }
                is CloudSaveOperationState.Error -> showError(state.message)
                else -> {} // Idle 不应出现
            }
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            showError("上传失败: ${e.message}")
        } finally {
            setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)
        }
    }
}

/**
 * 用真实云存档摘要覆盖 slot 0（云存档槽位）的硬编码占位字段。
 *
 * 无云存档时标记为空槽位（isEmpty=true），语义与主菜单选择存档界面的
 * "暂无云存档数据"一致；有云存档时展示宗门/年/月/弟子/灵石/保存时间。
 */
internal fun SaveLoadViewModel.mergeCloudSlot(
    slots: List<SaveSlot>,
    cloudInfo: TapCloudSaveManager.CloudSaveInfo
): List<SaveSlot> = slots.map { slot ->
    if (slot.slot != StorageConstants.CLOUD_SAVE_SLOT) {
        slot
    } else {
        SaveSlot(
            slot = StorageConstants.CLOUD_SAVE_SLOT,
            name = "云存档",
            timestamp = cloudInfo.lastModifiedTime,
            gameYear = cloudInfo.gameYear,
            gameMonth = cloudInfo.gameMonth,
            sectName = if (cloudInfo.hasSaveData && cloudInfo.sectName.isNotBlank()) {
                cloudInfo.sectName
            } else {
                "云存档"
            },
            discipleCount = cloudInfo.discipleCount,
            spiritStones = cloudInfo.spiritStones,
            isEmpty = !cloudInfo.hasSaveData
        )
    }
}

/**
 * 云上传 SaveData 构造（SR-1）：从 mails 表读**会话槽位**（getCurrentSlot——
 * 云会话为 CLOUD_SAVE_SLOT=0、常规会话为所在档）全量邮件入快照。
 * 云上传不走本地 save，邮件入云档只能在本构造点注入；读失败上抛 = 上传中止，
 * 不做空表静默降级。
 */
internal suspend fun SaveLoadViewModel.createSaveDataFromSnapshot(snapshot:
    com.xianxia.sect.core.engine.GameStateSnapshot): SaveData {
    val sessionMails = readSlotMails(persistenceFacade.storageFacade.getCurrentSlot())
    return SaveDataTrimmer.trimSaveData(snapshot, sessionMails)
}
