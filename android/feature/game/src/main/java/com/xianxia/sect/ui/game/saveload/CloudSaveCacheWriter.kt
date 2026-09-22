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
import com.xianxia.sect.data.migration.MigrationResult
import com.xianxia.sect.data.migration.SaveDataVersionMigrator
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.SaveDataReconciler
import com.xianxia.sect.data.unified.SaveResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云档 → 本地缓存的落盘段（SR-6 C4，从 SR-3 的 `handleCloudSlotPayload` 抽出复用）。
 *
 * 为什么现在抽：SR-3 把"下载 → 迁移/校验 → **落缓存** → 账本收敛"焊在
 * `SaveLoadViewModel` 的扩展函数里，且与 boot 链串在一起。SR-6 的两个新场景都要求
 * **不 boot**、且可以**源槽 ≠ 目标槽**：
 * - 矩阵格"本地无 × 云有 ⇒ 直接云档"：主菜单（尚未升档）就得能把云档落成
 *   本地缓存槽，玩家随后从常规入口进游戏；
 * - 存量单档 `mnzm_cloud_save`（slot 0）→ 玩家选定的空槽 N（SR-3 报告 §4.1 移交本批）。
 * 按 SR-3 §5.2 登记的口径执行收敛："管线序列 ~15 行有意重复……若后续批收敛，
 * 须以行为等价测试兜底" ⇒ 本批 `SaveLoadViewModelCloudSlotLoadTest` 13 例改为**持真实
 * 本组件**跑既有断言（不用 mock 空转），等价性由那批用例锚定。
 *
 * 模式门控**不在本组件内**（有意）：SR-3 的 LEGACY 拒绝发生在 VM 入口（既有云链硬红线，
 * 机制面而非 UI 面）；迁移侧的调用是 SR-6 新增的**显式玩家动作**，其正当性来自"本批就是
 * 把存量从 LEGACY 推上云"（方案 §4 SR-6），两者不共用一个闸才不会互相误伤。
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

    /** 落盘段结果（VM 与迁移协调器各自的玩家可见面由此分流，失败文案在此单点定义） */
    sealed interface Outcome {
        /** 已落本地缓存：[saveData] 供 VM 继续 boot；[targetSlot] 为实际写入槽 */
        data class Written(
            val targetSlot: Int,
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

    /**
     * 下载 [sourceSlot] 的云档并落到 [targetSlot] 本地缓存。
     *
     * 账本收敛只在 `sourceSlot == targetSlot` 时做：[UploadLedger] 的序号是**按槽独立**的
     * 计数列，把存量单档（slot 0）的 W 抄进目标槽等于凭空造出一个高位基线；目标槽保持
     * 空账本，玩家在该槽第一次保存时才从 1 起算（幂等且可与云端逐槽对账）。
     */
    // ReturnCount：本函数即 SR-3 handleCloudSlotPayload 的原样抽段（该函数同口径豁免），
    // 六处早退全是"下载/verdict/管线/落盘"四级守卫，改写成包装类型只会为凑数加一层信封
    @Suppress("ReturnCount")
    suspend fun downloadIntoCache(sourceSlot: Int, targetSlot: Int): Outcome {
        val payload = when (val result = saveBackend.download(sourceSlot)) {
            is SaveBackendResult.Failure -> {
                if (result.error == SaveBackendError.CONFLICT) {
                    Log.w(TAG, "cloud download conflict: source=$sourceSlot — 等待玩家二选一")
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

        val cacheResult = storageFacade.save(targetSlot, processed)
        if (!cacheResult.isSuccess) {
            val cacheError = (cacheResult as? SaveResult.Failure)?.message ?: "本地缓存写入失败"
            Log.e(TAG, "cloud cache write FAILED: target=$targetSlot error=$cacheError")
            return Outcome.Rejected("云存档落盘失败：$cacheError")
        }

        convergeLedger(sourceSlot, targetSlot, payload.saveId)
        return Outcome.Written(targetSlot, processed, payload.integrity)
    }

    /**
     * verdict 分流（SR-3 §4.2 语义原样保留）：UPLOAD_PENDING = 本机有未上传新进度、
     * 云端并不更新 ⇒ 下载覆盖会丢本机进度，拒绝而非静默；后端 CONFLICT 走 Failure 短路，
     * Success 携带 CONFLICT 属防御兜底，同样按冲突待决处理（不写缓存、不置错误）。
     */
    private fun CloudSavePayload.verdictRejection(): Outcome? = when (verdict) {
        ArbitrationVerdict.CONFLICT -> Outcome.ConflictPending
        ArbitrationVerdict.UPLOAD_PENDING -> Outcome.Rejected(UPLOAD_PENDING_MESSAGE)
        ArbitrationVerdict.LOCAL_BEHIND, ArbitrationVerdict.IN_SYNC -> null
    }

    /**
     * 账本收敛（IN2：只走保存序号）——只在 `sourceSlot == targetSlot` 时做：
     * [UploadLedger] 的序号是**按槽独立**的计数列，把存量单档（slot 0）的 W 抄进目标槽
     * 等于凭空造出一个高位基线；目标槽保持空账本，玩家在该槽第一次保存时才从 1 起算。
     * W 未知（存量档 U11）同样保持原状；**不走** `recordLocalSave`（落缓存不是新进度）。
     */
    private fun convergeLedger(sourceSlot: Int, targetSlot: Int, cloudSaveId: Long?) {
        if (sourceSlot != targetSlot) {
            Log.i(
                TAG,
                "cloud ledger untouched: source=$sourceSlot target=$targetSlot " +
                    "（跨槽迁移不抄序号，目标槽从自己的第一次保存起算）"
            )
            return
        }
        cloudSaveId?.let { saveId ->
            uploadLedger.adoptCloudState(targetSlot, saveId)
            Log.i(TAG, "cloud ledger adopted: slot=$targetSlot W=$saveId（下载即云端基线，非新保存）")
        }
    }

    /**
     * 云档管线（与既有云读档同语义）：版本迁移 → 完整性校验（损坏拒绝/可修复继续）→ 堆叠重建。
     */
    private fun processDownloadedCloudSave(saveData: SaveData): PipelineResult {
        val migration = SaveDataVersionMigrator.migrate(saveData)
        if (migration is MigrationResult.Rejected) {
            // saveVersion 越界（负数/伪造高版本）显式拒绝
            return PipelineResult.Failed("云存档版本异常：${migration.reason}")
        }
        var processed = (migration as MigrationResult.Migrated).data
        when (val validation = SaveValidator.validate(processed)) {
            is IntegrityResult.Corrupted -> return PipelineResult.Failed("云存档数据损坏，无法加载")
            is IntegrityResult.Repaired -> {
                Log.w(TAG, "云存档完整性修复 ${validation.details.size} 项")
                processed = validation.data
            }
            is IntegrityResult.Passed -> {}
        }
        // 旧格式云存档无堆叠数据：从实例重建兜底（与既有云读档同语义）
        return PipelineResult.Ok(SaveDataReconciler.reconcileStacks(processed))
    }

    private companion object {
        const val TAG = "CloudSaveCacheWriter"

        /** verdict=UPLOAD_PENDING 的拒绝文案（SR-3 §4.2 语义：非冲突但同样禁止静默覆盖） */
        const val UPLOAD_PENDING_MESSAGE =
            "本机此槽位有未上传的新进度，已停止从云端覆盖：请联网等待自动上传完成后重试"
    }
}
