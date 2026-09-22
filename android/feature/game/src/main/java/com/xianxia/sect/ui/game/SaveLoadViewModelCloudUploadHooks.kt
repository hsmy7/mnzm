package com.xianxia.sect.ui.game

import android.util.Log
import com.xianxia.sect.data.cloud.shouldEnqueueCloudUpload
import com.xianxia.sect.data.model.SaveData
import kotlinx.coroutines.CancellationException

// ── 云上传队列触发钩子（SR-2，方案 D3 双步保存的第二步）─────────────────────
// 独立成文件避免 SaveOps/CloudOps 函数数触阈（detekt TooManyFunctions 上两批教训）。

/**
 * `onStop` 排空尝试（SR-4，方案 §2 触发矩阵"本地事务 + 队列尝试排空"）：
 * 本地已提交、进程可能随时被杀 ⇒ 请队列对下一条待传**跳过合并窗早点试传**。
 *
 * 边界：不改队列语义（冲突仲裁/共享冷却/退避/熔断照旧，排空是"早点试"不是"强行传"）；
 * **LEGACY 短路 = SR-2 硬红线**（不置位、不唤醒 ⇒ 默认模式队列零活动、零协程）。
 */
internal fun SaveLoadViewModel.requestCloudUploadDrain() {
    val mode = persistenceFacade.saveBackendModeProvider.current()
    if (!shouldEnqueueCloudUpload(mode)) {
        Log.d(SaveLoadViewModelConstants.TAG, "cloud upload drain skipped: mode=LEGACY")
        return
    }
    persistenceFacade.uploadQueue.requestDrain()
    Log.i(SaveLoadViewModelConstants.TAG, "cloud upload drain requested: mode=$mode")
}

/**
 * 本地保存成功后的云上传投递（D3：第一步本地事务已必成，此为第二步异步部分——
 * IN1：上传失败只降级，不回滚本地）。
 *
 * **LEGACY 短路 = SR-2 硬红线**：默认模式下 [shouldEnqueueCloudUpload] 返回 false，
 * 本钩子是纯函数判定的零副作用短路——无账本写入、无队列活动、无 UI 提示，
 * 保存链行为与 SR-2 落库前逐行一致（守卫测试：SaveBackendModeTest +
 * SaveLoadViewModelLoadTest LEGACY 段）。
 *
 * saveId 由 [UploadLedger.recordLocalSave] 派发（账本 L++ 并落待传指针）；
 * 窗口合并/退避/限频/冲突仲裁均由 UploadQueue 状态机负责（SR-0 Q1-Q10）。
 */
// TooGenericExceptionCaught：防御兜底——入队异常源跨账本存储/队列不可枚举，
// 如实提示后保存链继续（本地已提交，IN1），非静默吞噬
@Suppress("TooGenericExceptionCaught")
internal suspend fun SaveLoadViewModel.maybeEnqueueCloudUploadAfterLocalSave(slot: Int, saveData: SaveData) {
    val mode = persistenceFacade.saveBackendModeProvider.current()
    if (!shouldEnqueueCloudUpload(mode)) {
        Log.d(SaveLoadViewModelConstants.TAG, "cloud upload enqueue skipped: mode=LEGACY (slot=$slot)")
        return
    }
    try {
        persistenceFacade.uploadQueue.enqueue(slot, saveData)
        Log.i(SaveLoadViewModelConstants.TAG, "cloud upload enqueued: slot=$slot mode=$mode")
    } catch (e: CancellationException) {
        throw e // 取消穿透：入队取消时中止，不以失败冒充
    } catch (e: Exception) {
        // 入队失败如实提示（本地保存已成功，不谎报不静默——postSaveWarning 同纪律）
        Log.e(SaveLoadViewModelConstants.TAG, "cloud upload enqueue failed: slot=$slot", e)
        showError("云同步入队失败：本地已保存，未自动上传（${e.message}）")
    }
}
