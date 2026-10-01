package com.xianxia.sect.ui.game.saveload

import android.util.Log
import com.xianxia.sect.data.cloud.ArbitrationVerdict
import com.xianxia.sect.data.cloud.CloudSavePayload
import com.xianxia.sect.data.cloud.SaveBackend
import com.xianxia.sect.data.cloud.SaveBackendError
import com.xianxia.sect.data.cloud.SaveBackendResult
import com.xianxia.sect.data.cloud.UploadLedger
import com.xianxia.sect.data.crypto.SavePayloadIntegrity
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.SaveDataReconciler
import com.xianxia.sect.data.unified.SaveResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云档 → 本地缓存的落盘段：下载 → 迁移/校验 → **落缓存** → 账本收敛，
 * 供存档弹窗云读档与自动进入（换设备续玩）两条入口复用——云档必须先落本地缓存
 * 再进 boot（缓存失败即中止，不带病进游戏），账本与云端基线同步收敛，
 * 后续本机保存才能与云端序号连续对账。
 *
 * IN1：落缓存失败 ⇒ 不收敛账本、不返回成功；IN2：账本收敛只走保存序号
 * （[UploadLedger.adoptCloudState]），零时钟。
 */
@Singleton
class CloudSaveCacheWriter @Inject constructor(
    private val saveBackend: SaveBackend,
    private val storageFacade: StorageFacade,
    private val uploadLedger: UploadLedger
) {

    /** 落盘段结果（VM 侧的玩家可见面由此分流，失败文案在此单点定义） */
    sealed interface Outcome {
        /** 已落本地缓存：[saveData] 供 VM 继续 boot */
        data class Written(
            val saveData: SaveData,
            val integrity: SavePayloadIntegrity
        ) : Outcome

        /** 真冲突待玩家二选一：conflicts 流已发事件，本段不写缓存不置错误 */
        data object ConflictPending : Outcome

        /** 如实失败（下载失败 / verdict 拒绝覆盖 / 版本异常 / 数据损坏 / 落盘失败） */
        data class Rejected(val message: String) : Outcome
    }

    /** 云档管线结果：成功带数据，失败带玩家可见原因（不用旁路可变状态回传） */
    private sealed interface PipelineResult {
        data class Ok(val data: SaveData) : PipelineResult
        data class Failed(val message: String) : PipelineResult
    }

    /** 下载云档并落到本地缓存。 */
    // ReturnCount：四级守卫（下载/verdict/管线/落盘）早退全是防御分支，
    // 改写成包装类型只会为凑数加一层信封
    @Suppress("ReturnCount")
    suspend fun downloadIntoCache(): Outcome {
        val payload = when (val result = saveBackend.download()) {
            is SaveBackendResult.Failure -> {
                if (result.error == SaveBackendError.CONFLICT) {
                    Log.w(TAG, "cloud download conflict — 等待玩家二选一")
                    return Outcome.ConflictPending
                }
                return Outcome.Rejected("云存档下载失败：${result.message}")
            }
            is SaveBackendResult.Success -> result.data
        }

        payload.verdictRejection()?.let { return it }

        val processed = when (val pipeline = processDownloadedCloudSave(payload.saveData)) {
            is PipelineResult.Failed -> return Outcome.Rejected(pipeline.message)
            is PipelineResult.Ok -> pipeline.data
        }

        val cacheResult = storageFacade.save(processed)
        if (!cacheResult.isSuccess) {
            val cacheError = (cacheResult as? SaveResult.Failure)?.message ?: "本地缓存写入失败"
            Log.e(TAG, "cloud cache write FAILED: error=$cacheError")
            return Outcome.Rejected("云存档落盘失败：$cacheError")
        }

        convergeLedger(payload.saveId)
        return Outcome.Written(processed, payload.integrity)
    }

    /**
     * verdict 分流（语义：非冲突但禁止覆盖的下载同样拒绝）：UPLOAD_PENDING = 本机有
     * 未上传新进度、云端并不更新 ⇒ 下载覆盖会丢本机进度，拒绝而非静默；后端 CONFLICT
     * 走 Failure 短路，Success 携带 CONFLICT 属防御兜底，同样按冲突待决处理
     * （不写缓存、不置错误）。
     */
    private fun CloudSavePayload.verdictRejection(): Outcome? = when (verdict) {
        ArbitrationVerdict.CONFLICT -> Outcome.ConflictPending
        ArbitrationVerdict.UPLOAD_PENDING -> Outcome.Rejected(UPLOAD_PENDING_MESSAGE)
        ArbitrationVerdict.LOCAL_BEHIND, ArbitrationVerdict.IN_SYNC -> null
    }

    /**
     * 账本收敛（IN2：只走保存序号）：下载即把本端基线对齐云端实际保存序号 W——
     * 落盘的不是新进度，**不走** `recordLocalSave`；W 未知（存量档 U11）保持原状。
     */
    private fun convergeLedger(cloudSaveId: Long?) {
        cloudSaveId?.let { saveId ->
            uploadLedger.adoptCloudState(saveId)
            Log.i(TAG, "cloud ledger adopted: W=$saveId（下载即云端基线，非新保存）")
        }
    }

    /**
     * 云档管线（与既有云读档同语义）：完整性校验（损坏拒绝/可修复继续）→ 堆叠重建。
     */
    private fun processDownloadedCloudSave(saveData: SaveData): PipelineResult {
        var processed = saveData
        when (val validation = SaveValidator.validate(processed)) {
            is IntegrityResult.Corrupted -> return PipelineResult.Failed("云存档数据损坏，无法加载")
            is IntegrityResult.Repaired -> {
                Log.w(TAG, "云存档完整性修复 ${validation.details.size} 项")
                processed = validation.data
            }
            is IntegrityResult.Passed -> {}
        }
        // 云档无堆叠数据时从实例重建兜底（与既有云读档同语义）
        return PipelineResult.Ok(SaveDataReconciler.reconcileStacks(processed))
    }

    private companion object {
        const val TAG = "CloudSaveCacheWriter"

        /** verdict=UPLOAD_PENDING 的拒绝文案（非冲突但同样禁止静默覆盖） */
        const val UPLOAD_PENDING_MESSAGE =
            "本机有未上传的新进度，已停止从云端覆盖：请联网等待自动上传完成后重试"
    }
}
