package com.xianxia.sect.taptap

import android.content.Context
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.cloud.ArbitrationVerdict
import com.xianxia.sect.data.cloud.CloudSaveEntry
import com.xianxia.sect.data.cloud.CloudSavePayload
import com.xianxia.sect.data.cloud.SaveArbiter
import com.xianxia.sect.data.cloud.SaveBackend
import com.xianxia.sect.data.cloud.SaveBackendError
import com.xianxia.sect.data.cloud.SaveBackendResult
import com.xianxia.sect.data.cloud.SaveConflictEvent
import com.xianxia.sect.data.cloud.UploadLedger
import com.xianxia.sect.data.cloud.UploadReceipt
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.SerializationModule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SaveBackend 的 TapTap 实现（方案 D4，SR-2）：**包装**既有 CloudSaveApi 反射桥
 * （[CloudSaveApiReflector]，SDK 探测/回调桥接/超时兜底全部复用，不重写——SR-0 §3.1），
 * 在其上一层门面化槽位语义（slot → `slot_N` 云端命名，SR-0 §3.4）与脏标志仲裁
 * （[SaveArbiter]，IN2 无时钟）。
 *
 * - extra JSON 在现役协议（year/month/sect/disciples/stones/version）上新增 `saveId`
 *   （云端实际保存序号 W 的回带源；存量档无该字段 = W 未知 → 仲裁 U11 保守退化）；
 * - 槽位映射：slot 0 = 存量单档 `mnzm_cloud_save`（SR-6 迁移源），slot 1..6 = `slot_N`；
 * - 操作互斥：类内 Mutex（对齐 TapCloudSaveManager.cloudOpLock 语义）；
 * 上传队列侧另有单飞 worker（全局串行 + 共享冷却），两层互斥不冲突；
 * - IN3：本类是 feature 面唯一的云后端出口，UI 层只依赖 [SaveBackend] 接口；
 *   反射桥全路径无 TapTap SDK 编译期类型。
 */
// TooManyFunctions：SaveBackend 端口五操作 + 错误/extra/槽位三映射 = 接口契约下界
@Suppress("TooManyFunctions")
@Singleton
class TapTapSaveBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serializationModule: SerializationModule,
    private val uploadLedger: UploadLedger
) : SaveBackend {

    private val opMutex = Mutex()

    private val _conflicts = MutableSharedFlow<SaveConflictEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val conflicts: SharedFlow<SaveConflictEvent> = _conflicts

    override suspend fun upload(slot: Int, saveData: SaveData, saveId: Long): SaveBackendResult<UploadReceipt> =
        opMutex.withLock {
            val api = CloudSaveApiReflector.resolve()
                ?: return@withLock SaveBackendResult.Failure(
                    SaveBackendError.SDK_UNAVAILABLE, "TapTap 云存档 SDK 不可用，请确认已安装 TapTap 并登录"
                )
            val archiveName = archiveNameFor(slot)
            DomainLog.i(TAG, "backend upload: slot=$slot archive=$archiveName saveId=$saveId")

            // 序列化 + 尺寸红线（TapTap 单档 ≤10MB，SR-0 §2.2）
            val bytes = try {
                serializationModule.serializeAndCompressSaveData(saveData)
            } catch (e: CancellationException) {
                throw e // 取消穿透：不以序列化错误冒充
            } catch (e: Exception) {
                DomainLog.e(TAG, "serialize failed for slot $slot", e)
                return@withLock SaveBackendResult.Failure(SaveBackendError.SERIALIZATION, e.message ?: "序列化失败", e)
            }
            if (bytes.size > MAX_CLOUD_SAVE_SIZE_BYTES) {
                return@withLock SaveBackendResult.Failure(
                    SaveBackendError.SIZE_LIMIT,
                    "存档过大：${bytes.size}B > 上限 $MAX_CLOUD_SAVE_SIZE_BYTES"
                )
            }

            val tempFile = File(context.cacheDir, tempFileName(slot))
            try {
                tempFile.parentFile?.mkdirs()
                tempFile.writeBytes(bytes)
                val (summary, extra) = buildSummaryAndExtra(saveData, saveId)
                api.createOrUpdateArchive(
                    archiveName = archiveName,
                    summary = summary,
                    filePath = tempFile.absolutePath,
                    uuid = null, // 按名查找（桥内 list 扫描）——多档语义下不缓存 UUID
                    extra = extra
                )
                DomainLog.i(TAG, "backend upload success: slot=$slot saveId=$saveId")
                SaveBackendResult.Success(UploadReceipt(confirmedSaveId = saveId))
            } catch (e: CancellationException) {
                throw e // 取消穿透：cloudOp 语义与 manager 一致
            } catch (e: Exception) {
                DomainLog.e(TAG, "backend upload failed: slot=$slot", e)
                SaveBackendResult.Failure(classify(e), e.message ?: "上传失败", e)
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }

    override suspend fun download(slot: Int): SaveBackendResult<CloudSavePayload> = opMutex.withLock {
        val api = CloudSaveApiReflector.resolve()
            ?: return@withLock SaveBackendResult.Failure(
                SaveBackendError.SDK_UNAVAILABLE, "TapTap 云存档 SDK 不可用，请确认已安装 TapTap 并登录"
            )
        val archiveName = archiveNameFor(slot)
        DomainLog.i(TAG, "backend download: slot=$slot archive=$archiveName")

        // IN2 仲裁：云端 W（extra.saveId）+ 本端账本 (L, C) —— 零时钟输入
        val rawInfo = try {
            api.queryArchiveInfo(archiveName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock SaveBackendResult.Failure(classify(e), e.message ?: "查询失败", e)
        }
        val cloudSaveId = rawInfo?.extra?.let { parseSaveId(it) }
        val verdict = SaveArbiter.arbitrate(
            lastLocalSaveId = uploadLedger.lastLocalSaveId(slot),
            lastConfirmedCloudId = uploadLedger.lastConfirmedCloudId(slot),
            cloudSaveId = cloudSaveId
        )
        if (verdict == ArbitrationVerdict.CONFLICT) {
            val conflict = SaveConflictEvent(
                slot = slot,
                lastLocalSaveId = uploadLedger.lastLocalSaveId(slot),
                lastConfirmedCloudId = uploadLedger.lastConfirmedCloudId(slot),
                cloudSaveId = cloudSaveId,
                source = "download"
            )
            DomainLog.w(TAG, "conflict on download: slot=$slot L=${conflict.lastLocalSaveId} " +
                "C=${conflict.lastConfirmedCloudId} W=$cloudSaveId — 显式暴露给 UI，不静默覆盖")
            _conflicts.tryEmit(conflict)
            return@withLock SaveBackendResult.Failure(
                SaveBackendError.CONFLICT,
                "本地与云端均有新进度，需要选择保留哪一份"
            )
        }

        // 下载 + 反序列化（尺寸防御 50MB 对齐 manager）
        val bytes = try {
            api.downloadArchive(archiveName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock SaveBackendResult.Failure(classify(e), e.message ?: "下载失败", e)
        }
        if (bytes == null || bytes.isEmpty()) {
            return@withLock SaveBackendResult.Failure(SaveBackendError.ARCHIVE_MISSING, "云存档不存在或为空")
        }
        if (bytes.size > MAX_DOWNLOAD_SIZE_BYTES) {
            return@withLock SaveBackendResult.Failure(
                SaveBackendError.SIZE_LIMIT, "云存档过大：${bytes.size}B"
            )
        }
        val saveData = try {
            serializationModule.deserializeSaveData(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "deserialize failed for slot $slot", e)
            return@withLock SaveBackendResult.Failure(SaveBackendError.SERIALIZATION, e.message ?: "反序列化失败", e)
        }
        DomainLog.i(TAG, "backend download success: slot=$slot W=$cloudSaveId verdict=$verdict")
        SaveBackendResult.Success(CloudSavePayload(saveData, cloudSaveId, verdict))
    }

    override suspend fun list(): SaveBackendResult<List<CloudSaveEntry>> {
        val api = CloudSaveApiReflector.resolve()
            ?: return SaveBackendResult.Failure(SaveBackendError.SDK_UNAVAILABLE, "TapTap 云存档 SDK 不可用")
        return try {
            val entries = api.listAllArchives().mapNotNull { archive ->
                slotFromArchiveName(archive.name)?.let { slot ->
                    CloudSaveEntry(
                        slot = slot,
                        archiveName = archive.name,
                        saveId = null, // 列表面不带 extra；逐档摘要/序号查询归 SR-3 选档 UI
                        sizeBytes = 0L,
                        modifiedTimeMs = archive.modifiedTime * 1000,
                        summary = null
                    )
                }
            }
            SaveBackendResult.Success(entries)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SaveBackendResult.Failure(classify(e), e.message ?: "列表查询失败", e)
        }
    }

    override suspend fun delete(slot: Int): SaveBackendResult<Unit> {
        val api = CloudSaveApiReflector.resolve()
            ?: return SaveBackendResult.Failure(SaveBackendError.SDK_UNAVAILABLE, "TapTap 云存档 SDK 不可用")
        return try {
            val target = api.listAllArchives().firstOrNull { it.name == archiveNameFor(slot) }
                ?: return SaveBackendResult.Failure(SaveBackendError.ARCHIVE_MISSING, "云存档不存在: slot=$slot")
            api.deleteArchive(target.uuid)
            DomainLog.i(TAG, "backend delete success: slot=$slot uuid=${target.uuid.take(8)}")
            SaveBackendResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SaveBackendResult.Failure(classify(e), e.message ?: "删除失败", e)
        }
    }

    override suspend fun currentCloudSaveId(slot: Int): SaveBackendResult<Long?> {
        val api = CloudSaveApiReflector.resolve() ?: return SaveBackendResult.Failure(
            SaveBackendError.SDK_UNAVAILABLE, "TapTap 云存档 SDK 不可用"
        )
        return try {
            val rawInfo = api.queryArchiveInfo(archiveNameFor(slot))
            SaveBackendResult.Success(rawInfo?.extra?.let { parseSaveId(it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SaveBackendResult.Failure(classify(e), e.message ?: "查询失败", e)
        }
    }

    // ── 槽位/extra/错误映射（internal 供同模块单测） ──

    companion object {
        /** 上传摘要 + extra JSON（现役协议 + saveId）；无实例状态依赖 */
        internal fun buildSummaryAndExtra(saveData: SaveData, saveId: Long): Pair<String, String> {
            val gd = saveData.gameData
            val summary = "第${gd.gameYear}年${gd.gameMonth}月 ${gd.sectName}"
            val extra = JSONObject().apply {
                put("year", gd.gameYear)
                put("month", gd.gameMonth)
                put("sect", gd.sectName)
                put("disciples", saveData.disciples.size)
                put("stones", gd.spiritStones)
                put("version", GameConfig.Game.VERSION)
                put(EXTRA_KEY_SAVE_ID, saveId) // W 回带源（SR-0 §4.1 状态模型）
            }.toString()
            return summary to extra
        }
        private const val TAG = "TapTapSaveBackend"
        private const val MAX_CLOUD_SAVE_SIZE_BYTES = 10L * 1024 * 1024
        private const val MAX_DOWNLOAD_SIZE_BYTES = 50L * 1024 * 1024
        private const val EXTRA_KEY_SAVE_ID = "saveId"

        /** 存量单档名（与 TapCloudSaveManager.CLOUD_SAVE_ARCHIVE_NAME 一致；slot 0 = 云会话槽） */
        internal const val LEGACY_ARCHIVE_NAME = "mnzm_cloud_save"

        /** slot → 云端 archive 名：0 = 存量单档（SR-6 迁移源），1..6 = slot_N（SR-0 §3.4） */
        internal fun archiveNameFor(slot: Int): String =
            if (slot == StorageConstants.CLOUD_SAVE_SLOT) LEGACY_ARCHIVE_NAME else "slot_$slot"

        /** 云端 archive 名 → slot；非槽位命名（其他设备/历史遗留）返回 null（oneTimeCleanup 同纪律：保留不动） */
        internal fun slotFromArchiveName(name: String): Int? = when {
            name == LEGACY_ARCHIVE_NAME -> StorageConstants.CLOUD_SAVE_SLOT
            name.startsWith("slot_") -> name.removePrefix("slot_").toIntOrNull()?.takeIf { it in 1..6 }
            else -> null
        }

        internal fun tempFileName(slot: Int): String = "cloud_save_temp_slot_$slot.dat"

        /** extra JSON → 云端保存序号 W；缺失/解析失败/0 = null（存量档 U11 保守退化） */
        // 防御兜底: extra 内容跨服务端版本不可枚举, 解析失败降级 null, 非静默吞噬
        @Suppress("TooGenericExceptionCaught")
        internal fun parseSaveId(extra: String): Long? = try {
            JSONObject(extra).optLong(EXTRA_KEY_SAVE_ID, 0L).takeIf { it > 0L }
        } catch (e: Exception) {
            null
        }

        /**
         * 异常 message → 类型化错误（桥侧错误码格式 `[400001]`，SR-0 §2.4 归组）。
         * 用于队列的退避/熔断分类。
         */
        internal fun classify(e: Exception): SaveBackendError {
            if (e is CloudSaveOperationTimeoutException) return SaveBackendError.TIMEOUT
            val message = e.message ?: return SaveBackendError.UNKNOWN
            return when {
                message.contains("[400001]") -> SaveBackendError.RATE_LIMITED
                message.contains("[400006]") -> SaveBackendError.TOKEN_EXPIRED
                message.contains("[400007]") -> SaveBackendError.CONCURRENT
                message.contains("[400002]") -> SaveBackendError.ARCHIVE_MISSING
                message.contains("[400000]") || message.contains("[400009]") -> SaveBackendError.SIZE_LIMIT
                message.contains("[400003]") || message.contains("[400004]") ||
                    message.contains("[400005]") -> SaveBackendError.QUOTA_EXCEEDED
                message.contains("300001") -> SaveBackendError.AUTH_REQUIRED
                message.contains("400100") -> SaveBackendError.SDK_UNAVAILABLE
                else -> SaveBackendError.NETWORK
            }
        }
    }
}
