package com.xianxia.sect.ui.game

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.data.cloud.ArbitrationVerdict
import com.xianxia.sect.data.cloud.CloudSavePayload
import com.xianxia.sect.data.cloud.SaveBackendError
import com.xianxia.sect.data.cloud.SaveBackendMode
import com.xianxia.sect.data.cloud.SaveBackendResult
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.migration.MigrationResult
import com.xianxia.sect.data.migration.SaveDataVersionMigrator
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.SaveDataReconciler
import com.xianxia.sect.data.unified.SaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// ── 云槽位下载落盘链（SR-3，方案 §2 读路径 CLOUD_TRANSITION 起）─────────────────────
// 审计 §3/§12-I 修复面：云档下载不再只进内存——下载 → 校验 → 迁移 → 落本地缓存 →
// 再走既有 boot 链。LEGACY 模式（默认）下本链整体拒绝：既有 slot 0 云会话路径
//（SaveLoadViewModelCloudLoadOps/CloudOps）本批逐行零触碰（硬红线，模式门控隔离）。

/** 云槽位加载结果（loadCloudSlot 入口的反馈分流依据） */
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
 *   - keepLocal=true → 仅清待决态，不做任何覆盖动作（本机槽位缓存与云端均原样）；
 *   - keepLocal=false → 账本基线收敛到云端序号 W（`adoptCloudState`，L=C=W，IN2
 *     序号语义）后重跑下载——仲裁转 IN_SYNC 正常通过（这是"玩家选云"的显式授权，
 *     非静默覆盖）；
 * - 上传侧（source="upload"）→ `UploadQueue.resolveConflict(slot, keepLocal)`
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
                    "conflict resolved (download): slot=${conflict.slot} 玩家保留本机——不下载不覆盖"
                )
                return@launch
            }
            val cloudId = conflict.cloudSaveId
                ?: persistenceFacade.uploadLedger.lastConfirmedCloudId(conflict.slot)
            persistenceFacade.uploadLedger.adoptCloudState(conflict.slot, cloudId)
            Log.i(
                SaveLoadViewModelConstants.TAG,
                "conflict resolved (download): slot=${conflict.slot} 玩家选云端（W=$cloudId）——基线收敛后重跑下载"
            )
            loadCloudSlot(conflict.slot)
        } else {
            Log.i(
                SaveLoadViewModelConstants.TAG,
                "conflict resolved (upload): slot=${conflict.slot} keepLocal=$keepLocal —— 经上传队列收口"
            )
            persistenceFacade.uploadQueue.resolveConflict(conflict.slot, keepLocal)
        }
    }
}

/**
 * 云槽位下载入口（公开扩展——GameActivity/app 面经此分发）。
 *
 * CLOUD_TRANSITION+ 模式下：下载云端 slot_N 档 → 校验 → 迁移 → **落本地缓存槽 N**
 * （审计 §3/§12-I 修复面）→ 既有 boot 链。守卫族与 loadGameFromSlot(slot=0) 云下载
 * 入口对齐（boot/重启/保存/云锁/加载互斥）；真冲突时不置操作态，由冲突弹窗二选一
 * 接管（禁止静默覆盖，方案 §2/IN2）。
 */
@Suppress("ReturnCount") // 云下载入口多守卫（boot/重启/保存/云锁/加载），多 return 为守卫风格
fun SaveLoadViewModel.loadCloudSlot(slot: Int) {
    // boot 进行中禁止任何云下载入口
    if (isBootOperationBlocked()) return
    if (isRestartingFlow.value) {
        Log.w(SaveLoadViewModelConstants.TAG, "Restarting, ignoring cloud slot load request")
        showError("游戏重置中，请稍后读取云存档")
        return
    }
    if (saveLock.get()) {
        Log.w(SaveLoadViewModelConstants.TAG, "Save in progress, ignoring cloud slot load request")
        showError("正在保存中，请稍后读取云存档")
        return
    }
    if (!cloudDownloadLock.compareAndSet(false, true)) {
        Log.w(SaveLoadViewModelConstants.TAG, "Cloud download already in progress, ignoring")
        return
    }
    if (stateStore.isLoading.value) {
        Log.w(SaveLoadViewModelConstants.TAG, "Load in progress, ignoring cloud slot load request")
        cloudDownloadLock.set(false)
        showError("正在加载中，请稍后读取云存档")
        return
    }
    viewModelScope.launch(ioDispatcher.dispatcher) {
        resetCloudSaveOperationState()
        // 立即置位：下载/落盘/boot 全程加载反馈（与 slot 0 云下载入口同模式）
        setSaveLoadState(isLoading = true, pendingSlot = slot, pendingAction = "load")
        try {
            val outcome = performCloudSlotLoad(slot)
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
            // performCloudSlotLoad 的 finally 已释放锁，此处幂等兜底（同 slot 0 入口）
            cloudDownloadLock.set(false)
            setSaveLoadState(isLoading = false, pendingSlot = null, pendingAction = null)
        }
    }
}

/** 云槽位下载主流程（loadCloudSlot 持锁后调用）。 */
// 防御兜底: 异常源跨IO/SDK不可枚举, 降级为错误提示+日志留痕, 非静默吞噬
@Suppress("TooGenericExceptionCaught", "ReturnCount") // 云档多失败守卫（模式/下载/冲突），多 return 为守卫风格
internal suspend fun SaveLoadViewModel.performCloudSlotLoad(slot: Int): CloudSlotLoadOutcome {
    try {
        loadingProgressFlow.value = SaveLoadViewModelConstants.PROGRESS_START + 0.1f
        preloadPhaseFlow.value = SaveLoadViewModelConstants.PHASE_CLOUD_SYNC

        // 模式门控（LEGACY 硬红线：默认模式下本链整体不可达，机制面拒绝而非依赖 UI 不入口）
        val mode = persistenceFacade.saveBackendModeProvider.current()
        if (mode == SaveBackendMode.LEGACY) {
            cloudSaveOperationStateFlow.value =
                CloudSaveOperationState.Error("云存档槽位功能未开启")
            return CloudSlotLoadOutcome.Completed
        }

        when (val result = persistenceFacade.saveBackend.download(slot)) {
            is SaveBackendResult.Failure -> {
                if (result.error == SaveBackendError.CONFLICT) {
                    // 真冲突（本机脏 × 云端有更新）：conflicts 流已发事件，本链不落盘
                    // 不覆盖，冲突弹窗二选一接管（禁止静默覆盖，方案 §2/IN2）
                    Log.w(
                        SaveLoadViewModelConstants.TAG,
                        "cloud slot load conflict: slot=$slot — 等待玩家二选一"
                    )
                    return CloudSlotLoadOutcome.ConflictPending
                }
                cloudSaveOperationStateFlow.value =
                    CloudSaveOperationState.Error("云存档下载失败：${result.message}")
                return CloudSlotLoadOutcome.Completed
            }
            is SaveBackendResult.Success -> return handleCloudSlotPayload(slot, result.data)
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
        Log.e(SaveLoadViewModelConstants.TAG, "cloud slot load failed: slot=$slot", e)
        cloudSaveOperationStateFlow.value =
            CloudSaveOperationState.Error("云存档下载失败: ${e.message}")
        return CloudSlotLoadOutcome.Completed
    } finally {
        cloudDownloadLock.set(false)
    }
}

/**
 * 下载成功分支：verdict 分流 → 云档管线 → **落缓存** → 账本基线收敛 → 既有 boot 链。
 *
 * - verdict = UPLOAD_PENDING（本机有未上传新进度、云端并不更新）→ 拒绝覆盖：
 *   下载会丢本机未上传进度，如实提示等队列自动补传（区分于真冲突，SR-0 S5 语义）；
 * - 落缓存成功才 boot：缓存写入失败 = 如实报错中止，不带病进游戏（IN1 同源纪律）；
 * - 账本收敛 = 采纳云端为基线（非新保存）：W 已知时 `adoptCloudState(slot, W)`
 *   （L=C=W、待传清零，防止下一次仲裁把刚下载的档误判为待上传）；W 未知（存量档
 *   U11）保持账本原状；**不走** `recordLocalSave`（落缓存不是新进度，不制造假脏标志）。
 */
@Suppress("ReturnCount") // verdict/管线/落盘多失败守卫，多 return 为守卫风格
internal suspend fun SaveLoadViewModel.handleCloudSlotPayload(
    slot: Int,
    payload: CloudSavePayload
): CloudSlotLoadOutcome {
    when (payload.verdict) {
        // 防御兜底：后端 CONFLICT 走 Failure 短路，Success 不应携带 CONFLICT；此处按冲突待决处理
        ArbitrationVerdict.CONFLICT -> return CloudSlotLoadOutcome.ConflictPending
        ArbitrationVerdict.UPLOAD_PENDING -> {
            cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error(
                "本机此槽位有未上传的新进度，已停止从云端覆盖：请联网等待自动上传完成后重试"
            )
            return CloudSlotLoadOutcome.Completed
        }
        ArbitrationVerdict.LOCAL_BEHIND, ArbitrationVerdict.IN_SYNC -> {}
    }

    val processed = processDownloadedCloudSave(payload.saveData)
    if (processed == null) {
        // 管线失败已置操作态（版本异常/数据损坏）
        return CloudSlotLoadOutcome.Completed
    }

    // 落缓存（审计 §3/§12-I 修复面核心：云档写入本地缓存槽 N，不再只进内存）
    val cacheResult = persistenceFacade.storageFacade.save(slot, processed)
    if (!cacheResult.isSuccess) {
        val cacheError = (cacheResult as? SaveResult.Failure)?.message ?: "本地缓存写入失败"
        Log.e(
            SaveLoadViewModelConstants.TAG,
            "cloud slot cache write FAILED: slot=$slot error=$cacheError"
        )
        cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error(
            "云存档落盘失败：$cacheError"
        )
        return CloudSlotLoadOutcome.Completed
    }

    payload.saveId?.let { saveId ->
        persistenceFacade.uploadLedger.adoptCloudState(slot, saveId)
        Log.i(
            SaveLoadViewModelConstants.TAG,
            "cloud slot ledger adopted: slot=$slot W=$saveId（下载即云端基线，非新保存）"
        )
    }

    // 既有 boot 链（pendingSlot 参数化后回显目标槽 N）
    val bootResult = applyCloudSaveToEngine(processed, slot, pendingSlot = slot)
    if (bootResult.isSuccess) {
        cloudSaveOperationStateFlow.value = CloudSaveOperationState.Success("云存档加载成功")
    } else {
        cloudSaveOperationStateFlow.value = CloudSaveOperationState.Error(
            "读取云存档失败: ${bootResult.exceptionOrNull()?.message}"
        )
    }
    return CloudSlotLoadOutcome.Completed
}

/**
 * 云档管线（与既有云读档同语义）：版本迁移 → 完整性校验（损坏拒绝/可修复继续）→ 堆叠重建。
 * 失败已置操作态并返回 null。
 *
 * 独立成段而不复用 LEGACY handler——红线隔离：既有云读档路径本批零触碰
 * （约 15 行管线序列有意重复，完成报告如实登记）。
 */
@Suppress("ReturnCount") // 版本/校验多失败守卫，多 return 为守卫风格
private fun SaveLoadViewModel.processDownloadedCloudSave(saveData: SaveData): SaveData? {
    val migration = SaveDataVersionMigrator.migrate(saveData)
    if (migration is MigrationResult.Rejected) {
        // saveVersion 越界（负数/伪造高版本）显式拒绝
        cloudSaveOperationStateFlow.value =
            CloudSaveOperationState.Error("云存档版本异常：${migration.reason}")
        return null
    }
    var processed = (migration as MigrationResult.Migrated).data
    val validation = SaveValidator.validate(processed)
    when (validation) {
        is IntegrityResult.Corrupted -> {
            cloudSaveOperationStateFlow.value =
                CloudSaveOperationState.Error("云存档数据损坏，无法加载")
            return null
        }
        is IntegrityResult.Repaired -> {
            Log.w(
                SaveLoadViewModelConstants.TAG,
                "云存档完整性修复 ${validation.details.size} 项"
            )
            processed = validation.data
        }
        is IntegrityResult.Passed -> {}
    }
    // 旧格式云存档无堆叠数据：从实例重建兜底（与既有云读档同语义）
    return SaveDataReconciler.reconcileStacks(processed)
}
