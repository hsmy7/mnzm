package com.xianxia.sect.data.cloud

import com.xianxia.sect.data.crypto.SavePayloadIntegrity
import com.xianxia.sect.data.model.SaveData
import kotlinx.coroutines.flow.SharedFlow

/**
 * 云存档后端接口（方案 D4，SR-2 落地）。
 *
 * 业务层（feature / 后续 SaveOrchestrator）只依赖本接口，**零 TapTap SDK 类型引用**
 * （IN3，静态守卫：`StorageLayerSdkIsolationGuardTest` + core:data 测试 classpath 负向断言）。
 * 现实现 = `TapTapSaveBackend`（feature/game，包装既有 CloudSaveApi 反射桥，探测逻辑复用不重写）；
 * 未来换自家游戏服务器时新增实现即可切换（D4 预埋点）。
 *
 * 槽位语义：`slot` 1..6 映射云端 `slot_N` 命名（SR-0 §3.4）；slot [com.xianxia.sect.data.
 * StorageConstants.CLOUD_SAVE_SLOT]（=0）映射存量单档 `mnzm_cloud_save`（SR-6 迁移源）。
 *
 * 仲裁（IN2 无时钟）：本接口不提供"谁新"判定；进度新旧判定唯一入口是
 * [SaveArbiter.arbitrate]（脏标志/保存序号）。云端的实际保存序号 W 经
 * extra JSON 的 `saveId` 字段回带（[CloudSavePayload.saveId] / [currentCloudSaveId]）；
 * 存量档无该字段 = W 未知（null），仲裁按 U11 保守退化。
 */
interface SaveBackend {

    /** 真冲突事件流（仲裁判 CONFLICT 时发出，UI 层订阅弹窗二选一——禁止静默覆盖，方案 §2/IN2） */
    val conflicts: SharedFlow<SaveConflictEvent>

    /**
     * 上传 [saveData] 到云端 [slot]，携带保存序号 [saveId]（进 extra JSON，作为云端 W 的回带源）。
     * 幂等性：同 saveId 重复上传覆盖同一云档（确认丢失重传场景，SR-0 S6）。
     */
    suspend fun upload(slot: Int, saveData: SaveData, saveId: Long): SaveBackendResult<UploadReceipt>

    /**
     * 下载云端 [slot] 存档。返回载荷携带云端实际保存序号与本端脏标志仲裁 verdict；
     * verdict = CONFLICT 时**调用方不得直接应用载荷**（必须走冲突 UI）。
     */
    suspend fun download(slot: Int): SaveBackendResult<CloudSavePayload>

    /** 列出云端全部槽位存档（SR-3 槽位列表数据源） */
    suspend fun list(): SaveBackendResult<List<CloudSaveEntry>>

    /** 删除云端 [slot] 存档 */
    suspend fun delete(slot: Int): SaveBackendResult<Unit>

    /**
     * 查询云端 [slot] 当前实际保存序号（W）。null = 云无档或存量为旧格式（extra 无 saveId）。
     * 上传队列上传前仲裁（Q10）与本端"净"判定的数据源。
     */
    suspend fun currentCloudSaveId(slot: Int): SaveBackendResult<Long?>
}

/** 后端操作结果（类型化错误，队列按错误分类决定退避/重试/熔断） */
sealed class SaveBackendResult<out T> {
    data class Success<T>(val data: T) : SaveBackendResult<T>()
    data class Failure(
        val error: SaveBackendError,
        val message: String,
        val cause: Throwable? = null
    ) : SaveBackendResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
}

/**
 * 后端错误分类（SR-0 §2.4：队列必须处理的错误码归组）。
 * 400001→RATE_LIMITED；400006→TOKEN_EXPIRED；400007→CONCURRENT；400002→ARCHIVE_MISSING；
 * 400000/400009→SIZE_LIMIT（构造缺陷族）；400003/400004/400005→QUOTA_EXCEEDED；
 * 300001→AUTH_REQUIRED；SDK 不可达→SDK_UNAVAILABLE；其余→NETWORK/UNKNOWN。
 * CONFLICT 非传输错误：下载前仲裁判真冲突（下载路径的显式暴露形态，UI 二选一）。
 */
enum class SaveBackendError {
    RATE_LIMITED,
    TOKEN_EXPIRED,
    CONCURRENT,
    ARCHIVE_MISSING,
    SIZE_LIMIT,
    QUOTA_EXCEEDED,
    AUTH_REQUIRED,
    NETWORK,
    TIMEOUT,
    SERIALIZATION,
    SDK_UNAVAILABLE,
    CONFLICT,
    UNKNOWN
}

/** 上传成功回执（confirmedSaveId = 已确认上云的保存序号） */
data class UploadReceipt(val confirmedSaveId: Long)

/**
 * 下载载荷：存档数据 + 云端实际保存序号（W，null=存量档未知）+ 本端脏标志仲裁 verdict
 * + 载荷完整性判据（SR-5：HMAC 验签结果，默认 UNSIGNED = 云档无签名）。
 */
data class CloudSavePayload(
    val saveData: SaveData,
    val saveId: Long?,
    val verdict: ArbitrationVerdict,
    val integrity: SavePayloadIntegrity = SavePayloadIntegrity.UNSIGNED
)

/** 云端存档条目（槽位列表/删除定位；摘要供 SR-3 选档 UI 渲染） */
data class CloudSaveEntry(
    val slot: Int,
    val archiveName: String,
    val saveId: Long?,
    val sizeBytes: Long,
    val modifiedTimeMs: Long,
    val summary: CloudSaveSummary?
)

/** 云端摘要（extra JSON 协议 year/month/sect/disciples/stones/version 直映射） */
data class CloudSaveSummary(
    val gameYear: Int,
    val gameMonth: Int,
    val sectName: String,
    val discipleCount: Int,
    val spiritStones: Long,
    val appVersion: String
)

/**
 * 真冲突事件（仲裁 CONFLICT 时由后端发出）：本地有未确认上传（L>C）且云端有
 * 另一端的新进度（W>C 且 W≠L）——双方各有新进度，必须玩家二选一（SR-0 §4.2 S3/S4）。
 */
data class SaveConflictEvent(
    val slot: Int,
    val lastLocalSaveId: Long,
    val lastConfirmedCloudId: Long,
    val cloudSaveId: Long?,
    /** 冲突发现时的操作面（"download"/"upload"），供 UI 文案归因 */
    val source: String
)
