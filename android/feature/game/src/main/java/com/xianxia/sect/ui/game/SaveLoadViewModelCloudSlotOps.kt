// MatchingDeclarationName（文件级豁免，声明级 Suppress 对本规则无效）：本文件主语 =
// SaveLoadViewModel 云档下载落盘链扩展函数族，CloudSlotLoadOutcome 是其共享结果信封，
// 不为凑文件名拆文件（SR-2 聚合门面同口径注记）
@file:Suppress("MatchingDeclarationName")

package com.xianxia.sect.ui.game

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.data.cloud.SaveBackendMode
import com.xianxia.sect.data.crypto.SavePayloadIntegrity
import com.xianxia.sect.ui.game.saveload.CloudSaveCacheWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// ── 云档下载落盘链（下载 → 校验 → 落本地缓存 → 既有 boot 链）─────────────────────
// 云档下载不只进内存：落本地缓存后走 boot，账本基线与云端同步收敛（换设备续玩的
// 持久化面）。LEGACY 模式（默认）下本链整体拒绝：云会话路径
//（SaveLoadViewModelCloudLoadOps/CloudOps）与本链模式门控隔离。

/** 云档加载结果（loadCloudSave 入口的反馈分流依据） */
internal sealed interface CloudSlotLoadOutcome {
    /** 已完成：结果已置 cloudSaveOperationStateFlow（Success/Error），入口直接反馈 */
    data object Completed : CloudSlotLoadOutcome

    /** 真冲突待玩家二选一：conflicts 流已发事件，不置操作态、不报错（SR-3 冲突弹窗接管） */
    data object ConflictPending : CloudSlotLoadOutcome
}

/** SaveConflictEvent.source 的下载侧取值（与 TapTapSaveBackend.arbitrateAgainstCloud 一致） */
internal const val CONFLICT_SOURCE_DOWNLOAD = "download"

/**
 * 真冲突二选一收口（公开扩展——GameActivity 冲突弹窗回调，SR-3）。
 *
 * **选谁留档明确可见**（弹窗文案见 CloudConflictDialog），本函数按冲突来源分流：
 * - 下载侧（source="download"）：
 *   - keepLocal=true → 仅清待决态，不做任何覆盖动作（本机缓存与云端均原样）；
 *   - keepLocal=false → 账本基线收敛到云端序号 W（`adoptCloudState`，L=C=W，IN2
 *     序号语义）后重跑下载——仲裁转 IN_SYNC 正常通过（这是"玩家选云"的显式授权，
 *     非静默覆盖）；
 * - 上传侧（source="upload"）→ `UploadQueue.resolveConflict(keepLocal)`
 *   （keepLocal 授权越过冲突闸上传本机档 / keepCloud 丢弃待传并基线收敛，SR-2 Q10）。
 *
 * 重复调用/无待决冲突 = 无副作用；待决态先行清除（幂等，弹窗关闭即收口）。
 */
fun SaveLoadViewModel.resolveCloudConflict(keepLocal: Boolean) {
    val conflict = pendingCloudConflictFlow.value ?: return
    pendingCloudConflictFlow.value = null
    viewModelScope.launch(ioDispatcher.dispatcher) {
        if (conflict.source == CONFLICT_SOURCE_DOWNLOAD) {
            if (keepLocal) {
                Log.i(
                    SaveLoadViewModelConstants.TAG,
                    "conflict resolved (download): 玩家保留本机——不下载不覆盖"
                )
                return@launch
            }
            val cloudId = conflict.cloudSaveId
                ?: persistenceFacade.uploadLedger.lastConfirmedCloudId()
            persistenceFacade.uploadLedger.adoptCloudState(cloudId)
            Log.i(
                SaveLoadViewModelConstants.TAG,
                "conflict resolved (download): 玩家选云端（W=$cloudId）——基线收敛后重跑下载"
            )
            loadCloudSave()
        } else {
            Log.i(
                SaveLoadViewModelConstants.TAG,
                "conflict resolved (upload): keepLocal=$keepLocal —— 经上传队列收口"
            )
            persistenceFacade.uploadQueue.resolveConflict(keepLocal)
        }
    }
}

/**
 * 云档下载入口（自动进入换设备续玩链与冲突收口重跑下载共用）。
 *
 * CLOUD_TRANSITION+ 模式下：下载云档 → 校验 → **落本地缓存** → 既有 boot 链。
 * 守卫族与云下载入口对齐（boot/重启/保存/云锁/加载互斥）；真冲突时不置操作态，
 * 由冲突弹窗二选一接管（禁止静默覆盖，方案 §2/IN2）。
 */
// TooGenericExceptionCaught：防御兜底——下载/加载链异常源跨 IO/SDK 不可枚举，
// 降级为错误提示+日志留痕（含 launch 协程体内 catch），非静默吞噬
@Suppress("ReturnCount", "TooGenericExceptionCaught")
fun SaveLoadViewModel.loadCloudSave() {
    // boot 进行中禁止任何云下载入口
    if (isBootOperationBlocked()) return
    if (isRestartingFlow.value) {
        Log.w(SaveLoadViewModelConstants.TAG, "Restarting, ignoring cloud save load request")
        showError("游戏重置中，请稍后读取云存档")
        return
    }
    if (saveLock.get()) {
        Log.w(SaveLoadViewModelConstants.TAG, "Save in progress, ignoring cloud save load request")
        showError("正在保存中，请稍后读取云存档")
        return
    }
    if (!cloudDownloadLock.compareAndSet(false, true)) {
        Log.w(SaveLoadViewModelConstants.TAG, "Cloud download already in progress, ignoring")
        return
    }
    if (stateStore.isLoading.value) {
        Log.w(SaveLoadViewModelConstants.TAG, "Load in progress, ignoring cloud save load request")
        cloudDownloadLock.set(false)
        showError("正在加载中，请稍后读取云存档")
        return
    }
    viewModelScope.launch(ioDispatcher.dispatcher) {
        resetCloudSaveOperationState()
        // 立即置位：下载/落盘/boot 全程加载反馈（与云下载入口同模式）
        setSaveLoadState(isLoading = true, pendingAction = "load")
        try {
            val outcome = performCloudSaveLoad()
            if (outcome === CloudSlotLoadOutcome.Completed) {
                when (val state = cloudSaveOperationStateFlow.value) {
                    is CloudSaveOperationState.Success -> showSuccess(state.message)
                    is CloudSaveOperationState.Error -> showError(state.message)
                    else -> {} // Idle/Downloading 不应出现于本入口
                }
            }
            // ConflictPending：冲突弹窗接管，此处不报错不反馈
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError("云存档加载失败: ${e.message}")
        } finally {
            // performCloudSaveLoad 的 finally 已释放锁，此处幂等兜底（同云下载入口）
            cloudDownloadLock.set(false)
            setSaveLoadState(isLoading = false, pendingAction = null)
        }
    }
}

/** 云档下载主流程（loadCloudSave 持锁后调用）。 */
// 防御兜底: 异常源跨IO/SDK不可枚举, 降级为错误提示+日志留痕, 非静默吞噬
@Suppress("TooGenericExceptionCaught", "ReturnCount") // 云档多失败守卫（模式/下载/冲突），多 return 为守卫风格
internal suspend fun SaveLoadViewModel.performCloudSaveLoad(): CloudSlotLoadOutcome {
    try {
        loadingProgressFlow.value = SaveLoadViewModelConstants.PROGRESS_START + 0.1f
        preloadPhaseFlow.value = SaveLoadViewModelConstants.PHASE_CLOUD_SYNC

        // 模式门控（LEGACY 硬红线：默认模式下本链整体不可达，机制面拒绝而非依赖 UI 不入口）
        val mode = persistenceFacade.saveBackendModeProvider.current()
        if (mode == SaveBackendMode.LEGACY) {
            cloudSaveOperationStateFlow.value =
                CloudSaveOperationState.Error("云存档下载未开启")
            return CloudSlotLoadOutcome.Completed
        }

        // 下载 → verdict 分流 → 云档管线 → 落缓存 → 账本收敛（CloudSaveCacheWriter 单点）
        return when (val outcome = persistenceFacade.cloudSaveCacheWriter.downloadIntoCache()) {
            is CloudSaveCacheWriter.Outcome.ConflictPending -> CloudSlotLoadOutcome.ConflictPending
            is CloudSaveCacheWriter.Outcome.Rejected -> {
                cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error(outcome.message)
                CloudSlotLoadOutcome.Completed
            }
            is CloudSaveCacheWriter.Outcome.Written -> bootFromCloudCache(outcome)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: OutOfMemoryError) {
        // 恶意/异常超大云档反序列化 OOM——OutOfMemoryError 不是 Exception，不捕获会
        // 直接崩溃；降级为错误提示（与既有云读档路径同纪律）
        Log.e(SaveLoadViewModelConstants.TAG, "云存档数据过大导致内存不足", e)
        cloudSaveOperationStateFlow.value =
            CloudSaveOperationState.Error("云存档数据过大，内存不足，无法加载")
        return CloudSlotLoadOutcome.Completed
    } catch (e: Exception) {
        Log.e(SaveLoadViewModelConstants.TAG, "cloud save load failed", e)
        cloudSaveOperationStateFlow.value =
            CloudSaveOperationState.Error("云存档下载失败: ${e.message}")
        return CloudSlotLoadOutcome.Completed
    } finally {
        cloudDownloadLock.set(false)
    }
}

/**
 * 落缓存成功后的 boot 分支（落盘段在 `CloudSaveCacheWriter`，本函数只剩 boot）。
 *
 * 顺序不变：**缓存写入成功才 boot**，缓存失败由 writer 返回 Rejected、
 * 本入口如实报错中止，不带病进游戏（IN1 同源纪律）。
 */
private suspend fun SaveLoadViewModel.bootFromCloudCache(
    outcome: CloudSaveCacheWriter.Outcome.Written
): CloudSlotLoadOutcome {
    val bootResult = applyCloudSaveToEngine(outcome.saveData)
    cloudSaveOperationStateFlow.value = if (bootResult.isSuccess) {
        // 完整性异常降级放行（P4 拍板），但必须让玩家看得见，不静默
        CloudSaveOperationState.Success(
            "云存档加载成功" + outcome.integrity.integrityNotice().let {
                if (it.isEmpty()) "" else "（$it）"
            }
        )
    } else {
        CloudSaveOperationState.Error("读取云存档失败: ${bootResult.exceptionOrNull()?.message}")
    }
    return CloudSlotLoadOutcome.Completed
}

/**
 * 载荷完整性判据 → 玩家可见提示。
 *
 * VERIFIED / UNSIGNED 无提示：无签名是早期云档的常态（向后兼容方向），提示它只会
 * 制造噪音。异常态必须非空——判据没有"算了但没人看"的中间态（IN6）。
 */
internal fun SavePayloadIntegrity.integrityNotice(): String = when (this) {
    SavePayloadIntegrity.VERIFIED, SavePayloadIntegrity.UNSIGNED -> ""
    SavePayloadIntegrity.MISMATCH -> "该云存档签名校验不通过，内容可能被改写，请确认进度无误"
    SavePayloadIntegrity.KEY_UNAVAILABLE -> "本机校验密钥暂不可用，未能验证云存档签名"
}
