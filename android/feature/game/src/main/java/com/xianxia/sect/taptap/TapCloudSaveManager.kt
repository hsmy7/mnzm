package com.xianxia.sect.taptap

import android.content.Context
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.SerializationModule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TapTap 云存档管理器。
 *
 * 封装 TapTap Cloud Save API，提供存档上传/下载/查询功能。
 * 数据流：
 * - 上传：SaveData → SerializationModule.serializeAndCompressSaveData() → ByteArray → 临时文件 → TapTap API
 * - 下载：TapTap API → 临时文件 → ByteArray → SerializationModule.deserializeSaveData() → SaveData
 *
 * ## 依赖
 * - [SerializationModule] 负责 SaveData ↔ ByteArray 的序列化/反序列化
 * - TapTap Cloud Save SDK (tap-cloudsave) 负责云端传输
 *
 * ## 错误处理
 * 所有错误通过 [CloudSaveResult] sealed class 返回，不抛异常。
 */
@Singleton
class TapCloudSaveManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serializationModule: SerializationModule,
    private val keyValueStore: com.xianxia.sect.data.prefs.KeyValueStore,
    private val uploadLedger: com.xianxia.sect.data.cloud.UploadLedger
) {
    companion object {
        private const val TAG = "TapCloudSaveManager"

        /** 云存档临时文件路径 */
        private const val CLOUD_SAVE_FILE_NAME = "cloud_save_temp.dat"

        /** TapTap 云存档文件大小上限：10MB */
        private const val MAX_CLOUD_SAVE_SIZE_BYTES = 10L * 1024 * 1024

        /** 云存档唯一标识名称 */
        private const val CLOUD_SAVE_ARCHIVE_NAME = "mnzm_cloud_save"

        /** 下载文件大小上限：50MB（防御 OOM） */
        private const val MAX_DOWNLOAD_SIZE_BYTES = 50L * 1024 * 1024

        /** 云存档 UUID 缓存（避免每次上传都创建新存档） */
        private const val PREFS_NAME = "cloud_save_cache"
        private const val KEY_ARCHIVE_UUID = "archive_uuid"
        /** 云存档摘要信息本地缓存 key */
        private const val KEY_CLOUD_SAVE_INFO = "cloud_save_info"
        /** 是否已执行过一次性的孤立存档清理 */
        private const val KEY_CLEANUP_DONE = "cleanup_done"

        // SR-2：SDK 回调超时常量随反射桥搬移至 CloudSaveApiBridge.kt（CLOUD_OP_TIMEOUT_MS，纯代码搬移）

        /**
         * 版本号字符串比较（点分段数值比较，"4.0.9" < "4.0.13"）。
         *
         * **SR-2 拍板：兼容闸，非进度仲裁**（SR-0 §4.3C 预授权）。仅用于下载前拒绝
         * "云端 App 版本 > 当前 App"的不可加载档（[arbitrateCloudVersion]），判据是
         * **App 版本**不是进度新旧——与 IN2（仲裁无时钟/进度只看序号）相容：进度
         * "谁新"的唯一判定入口是 `SaveArbiter.arbitrate`（脏标志/保存序号）。
         *
         * @return 负数 cloud < current；0 相等；正数 cloud > current
         */
        fun compareVersions(cloud: String, current: String): Int {
            // 必须 trim：版本段含尾随空格时 toIntOrNull() 返回 null，
            // 会被归一化为 0，导致高版本仲裁被绕过
            val cloudParts = cloud.trim().split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.trim().split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(cloudParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val c = cloudParts.getOrElse(i) { 0 }
                val cur = currentParts.getOrElse(i) { 0 }
                if (c != cur) return c.compareTo(cur)
            }
            return 0
        }

        /**
         * 合并本地缓存与 API 摘要，返回应对外暴露的云存档摘要（SR-2 去时钟重写，IN2）。
         *
         * TapTap metadata 存在最终一致性延迟——上传后立刻查询
         * 可能返回"有存档但摘要全空"的旧 extra，直接采用会把真实游戏字段清零
         *（游戏内存档卡片显示全 0）；也可能返回旧但非空的摘要，把更新的本地数据降级。
         *
         * 规则（**无任何时钟比较**——旧规则 5 的 mtime 对比已随 IN2 退役）：
         * 1. API 确认云端无存档 → 有缓存用缓存，无缓存返回空
         * 2. 本地无缓存 → 只能采用 API 结果
         * 3. API 摘要为空（陈旧 extra）→ 保留本地缓存真实摘要
         * 4. 缓存无真实摘要但 API 有 → 采用 API
         * 5. 两者都有真实摘要 → 脏标志裁决：本地脏（有未确认上传，[UploadLedger]
         *    L>C，刚保存的本地摘要最新）→ 缓存；本地净 → API（服务器当前状态为准）。
         *    旧行为 = 按 lastModifiedTime 比大小（performCloudUpload 曾以挂钟
         *    System.currentTimeMillis() 伪造该值写入缓存，时钟偏移即长期误判，
         *    审计 §12-I 的"mtime-对-挂钟"落点），SR-2 整体退役。
         */
        internal fun resolveCloudSaveInfo(
            cached: CloudSaveInfo?,
            api: CloudSaveInfo,
            localDirty: Boolean
        ): CloudSaveInfo = when {
            !api.hasSaveData -> cached ?: api
            cached == null -> api
            !api.hasMeaningfulSummary() -> cached
            !cached.hasMeaningfulSummary() -> api
            localDirty -> cached
            else -> api
        }
    }

// SR-2：CloudSaveOperationTimeoutException 随反射桥搬移至 CloudSaveApiBridge.kt（纯代码搬移）

    /** 旧 SharedPreferences 一次性迁移守卫（偏好统一迁入 MMKV，幂等） */
    @Volatile
    private var migrated = false

    private fun ensureMigrated() {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            keyValueStore.migrateFromSharedPreferences(PREFS_NAME)
            migrated = true
        }
    }

    /** 云存档操作并发锁，防止上传/下载同时进行 */
    private val cloudOpLock = AtomicBoolean(false)

    private fun getCachedArchiveUuid(): String? {
        ensureMigrated()
        val uuid = keyValueStore.getString(KEY_ARCHIVE_UUID, null)
        if (uuid != null) DomainLog.d(TAG, "Using cached archive UUID: $uuid")
        return uuid
    }

    private fun saveCachedArchiveUuid(uuid: String) {
        keyValueStore.putString(KEY_ARCHIVE_UUID, uuid)
        DomainLog.d(TAG, "Cached archive UUID: $uuid")
    }

    private fun clearCachedArchiveUuid() {
        keyValueStore.remove(KEY_ARCHIVE_UUID)
        DomainLog.d(TAG, "Cleared cached archive UUID")
    }

    /** 云存档摘要信息 */
    data class CloudSaveInfo(
        val hasSaveData: Boolean,
        val lastModifiedTime: Long = 0L,
        val saveSize: Long = 0L,
        val description: String = "",
        /** 云端 extra JSON 中解析的游戏数据 */
        val gameYear: Int = 0,
        val gameMonth: Int = 0,
        val sectName: String = "",
        val discipleCount: Int = 0,
        val spiritStones: Long = 0L,
        /** 上传存档时的游戏版本号（用于跨版本兼容检查） */
        val appVersion: String = ""
    ) {
        /**
         * 摘要是否包含真实游戏数据（宗门/年/月/弟子/灵石任一非空）。
         *
         * TapTap 云存档 metadata 存在最终一致性延迟：上传后立刻查询可能返回
         * 旧 extra（游戏字段全空）；若把这种"有存档但摘要全空"的结果当真实数据
         * 持久化，会把本地缓存/内存中的真实摘要清零（游戏内卡片显示全 0 的根因）。
         */
        fun hasMeaningfulSummary(): Boolean =
            sectName.isNotBlank() || gameYear > 0 || gameMonth > 0 ||
                discipleCount > 0 || spiritStones > 0L
    }

    /** 云存档操作结果 */
    sealed class CloudSaveResult {
        /** 操作成功，[saveData] 仅在下载操作时有值 */
        data class Success(val saveData: SaveData? = null) : CloudSaveResult()
        data class NetworkError(val message: String) : CloudSaveResult()
        data class AuthRequired(val message: String) : CloudSaveResult()
        data class NoSaveExists(val message: String = "云存档不存在") : CloudSaveResult()
        data class FileTooLarge(val maxBytes: Long, val actualBytes: Long) : CloudSaveResult()
        data class SerializationError(val message: String) : CloudSaveResult()
        /** 云存档来自更新的游戏版本，当前版本不支持加载 */
        data class VersionMismatch(val cloudVersion: String, val currentVersion: String) : CloudSaveResult()
        data class UnknownError(val message: String) : CloudSaveResult()
    }

    /**
     * 上传存档到 TapTap 云端。
     *
     * 流程：
     * 1. 序列化 [saveData] 为字节数组
     * 2. 检查大小限制（TapTap 限制 10MB）
     * 3. 写入缓存临时文件
     * 4. 调用 TapTap API 上传
     * 5. 清理临时文件
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount") // 前者: 防御兜底异常源不可枚举; 后者: 上传各段取消穿透
    // rethrow 刻意独立抛出(结构化取消语义), 非疏忽超标
    suspend fun uploadSave(saveData: SaveData): CloudSaveResult {
        if (!cloudOpLock.compareAndSet(false, true)) {
            DomainLog.w(TAG, "Cloud save operation already in progress, rejecting concurrent upload")
            return CloudSaveResult.NetworkError("云存档操作正在进行中，请稍后重试")
        }
        try {
            DomainLog.d(TAG, "Starting cloud save upload...")

            // 1. 序列化 SaveData → ByteArray
            val serializedBytes = try {
                serializationModule.serializeAndCompressSaveData(saveData)
            } catch (e: CancellationException) {
                throw e // 取消穿透: 上传取消时中止, 不以序列化错误冒充
            } catch (e: Exception) {
                DomainLog.e(TAG, "Serialization failed during cloud upload", e)
                return CloudSaveResult.SerializationError(e.message ?: "序列化失败")
            }

            // 2. 检查文件大小（TapTap 限制单个存档 ≤10MB）
            if (serializedBytes.size > MAX_CLOUD_SAVE_SIZE_BYTES) {
                val actualMb = serializedBytes.size / (1024 * 1024)
                val maxMb = MAX_CLOUD_SAVE_SIZE_BYTES / (1024 * 1024)
                DomainLog
                    .w(TAG, "Cloud save file too large: ${serializedBytes.size} bytes (${actualMb}MB > ${maxMb}MB)")
                return CloudSaveResult.FileTooLarge(MAX_CLOUD_SAVE_SIZE_BYTES, serializedBytes.size.toLong())
            }

            // 3. 写入临时文件
            val tempFile = File(context.cacheDir, CLOUD_SAVE_FILE_NAME)
            try {
                tempFile.parentFile?.mkdirs()
                tempFile.writeBytes(serializedBytes)
            } catch (e: CancellationException) {
                throw e // 取消穿透: 本段为阻塞 IO 无挂起点, 分支防未来挂起点引入
            } catch (e: Exception) {
                DomainLog.e(TAG, "Failed to write temp file for cloud upload", e)
                return CloudSaveResult.UnknownError("临时文件写入失败: ${e.message}")
            }

            // 4. 通过 TapTap Cloud Save API 上传
            return try {
                performTapTapUpload(tempFile, saveData)
                DomainLog.i(TAG, "Cloud save upload successful")
                CloudSaveResult.Success()
            } catch (e: CancellationException) {
                throw e // 取消穿透: 上传取消时中止, 不以网络错误冒充(cloudOpLock 由 finally 释放)
            } catch (e: Exception) {
                DomainLog.e(TAG, "TapTap cloud save upload failed", e)
                CloudSaveResult.NetworkError(e.message ?: "上传失败")
            } finally {
                // 5. 清理临时文件
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        } finally {
            cloudOpLock.set(false)
        }
    }

    /**
     * 从 TapTap 云端下载存档。
     *
     * 流程：
     * 1. 调用 TapTap API 查询存档列表
     * 2. 有存档则下载到临时文件
     * 3. 读取字节数组
     * 4. 反序列化为 SaveData
     * 5. 清理临时文件
     */
    // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    @Suppress(
        "TooGenericExceptionCaught", "NestedBlockDepth", "ReturnCount", "CyclomaticComplexMethod"
    ) // 下载多阶段守卫（锁/仲裁/临时文件/反序列化/取消穿透），多 return 为守卫风格
    suspend fun downloadSave(): CloudSaveResult {
        if (!cloudOpLock.compareAndSet(false, true)) {
            DomainLog.w(TAG, "Cloud save operation already in progress, rejecting concurrent download")
            return CloudSaveResult.NetworkError("云存档操作正在进行中，请稍后重试")
        }
        try {
            DomainLog.d(TAG, "Starting cloud save download...")

            // 下载前版本仲裁——云档 extra JSON 含上传时版本号，
            // 云端版本高于当前 App 时直接拒绝，提示"版本不兼容"而非"存档数据异常"
            val preArbitration = arbitrateCloudVersion()
            if (preArbitration != null) return preArbitration

            val tempFile = File(context.cacheDir, CLOUD_SAVE_FILE_NAME)
            try {
                tempFile.parentFile?.mkdirs()

                // 1. 通过 TapTap API 下载存档
                val downloadSuccess = performTapTapDownload(tempFile)
                if (!downloadSuccess) {
                    return CloudSaveResult.NoSaveExists()
                }

                // 2. 检查文件是否为空
                if (!tempFile.exists() || tempFile.length() == 0L) {
                    return CloudSaveResult.NoSaveExists("云存档文件为空")
                }

                // 3. 读取字节
                val fileLen = tempFile.length()
                if (fileLen > MAX_DOWNLOAD_SIZE_BYTES) {
                    DomainLog.w(TAG, "Cloud save download file too large: ${fileLen} bytes")
                    return CloudSaveResult.FileTooLarge(MAX_DOWNLOAD_SIZE_BYTES, fileLen)
                }

                val bytes = tempFile.readBytes()

                // 4. 反序列化 ByteArray → SaveData
                val saveData = try {
                    serializationModule.deserializeSaveData(bytes)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DomainLog.e(TAG, "Deserialization failed during cloud download", e)
                    // 解码失败兜底仲裁——查一次云信息，若云端版本更高则改写为
                    // 版本不兼容而非笼统的"存档数据异常"
                    arbitrateCloudVersion()?.let { return it }
                    return CloudSaveResult.SerializationError(e.message ?: "反序列化失败")
                }

                DomainLog.i(TAG, "Cloud save download successful")
                return CloudSaveResult.Success(saveData)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 取消穿透: 下载取消时中止, 不以网络错误冒充(cloudOpLock 由 finally 释放)
            } catch (e: Exception) {
                DomainLog.e(TAG, "TapTap cloud save download failed", e)
                return CloudSaveResult.NetworkError(e.message ?: "下载失败")
            } finally {
                // 5. 清理临时文件
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        } finally {
            cloudOpLock.set(false)
        }
    }

    /**
     * 云跨版本**兼容闸**（SR-2 改注，行为不变）：查询云端 extra JSON 的 version 字段，
     * 云端 App 版本高于当前 App 时返回 [CloudSaveResult.VersionMismatch]——判据是
     * App 版本兼容性不是进度新旧，与 IN2 相容（进度"谁新"唯一入口 = SaveArbiter）。
     *
     * @return 版本不兼容结果；云端版本可接受/查询失败时返回 null（继续下载）
     */
    @Suppress("ReturnCount") // 查询失败/无版本/版本可接受多早退，守卫风格
    private suspend fun arbitrateCloudVersion(): CloudSaveResult? {
        val cloudInfo = performTapTapQuery() ?: return null
        val cloudVersion = cloudInfo.extra?.let { e ->
            try {
                JSONObject(e).optString("version", "")
            } catch (ex: kotlinx.coroutines.CancellationException) { throw ex }
            catch (_: Exception) { "" }
        } ?: ""
        if (cloudVersion.isNotBlank() && compareVersions(cloudVersion, GameConfig.Game.VERSION) > 0) {
            DomainLog.w(TAG, "云存档版本 $cloudVersion 高于当前 ${GameConfig.Game.VERSION}，拒绝下载")
            return CloudSaveResult.VersionMismatch(cloudVersion, GameConfig.Game.VERSION)
        }
        return null
    }

    /** 解析云端 extra JSON；解析失败返回 null，协程取消照常重抛 */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private fun parseCloudExtraJson(extra: String): JSONObject? {
        return try {
            JSONObject(extra)
        } catch (ex: kotlinx.coroutines.CancellationException) { throw ex } catch (_: Exception) { null }
    }

    /** 将 API 查询结果与 extra JSON 摘要映射为 CloudSaveInfo（checkCloudSave 拆分，无存档时字段为零值） */
    private fun toCloudSaveInfo(info: CloudSaveRawInfo?, extraData: JSONObject?): CloudSaveInfo = CloudSaveInfo(
        hasSaveData = info != null,
        lastModifiedTime = info?.lastModifiedTime ?: 0L,
        saveSize = info?.fileSize ?: 0L,
        description = info?.description ?: "",
        gameYear = extraData?.optInt("year", 0) ?: 0,
        gameMonth = extraData?.optInt("month", 0) ?: 0,
        sectName = extraData?.optString("sect", "") ?: "",
        discipleCount = extraData?.optInt("disciples", 0) ?: 0,
        spiritStones = extraData?.optLong("stones", 0L) ?: 0L,
        appVersion = extraData?.optString("version", "") ?: ""
    )

    /**
     * 查询云存档是否存在及摘要信息。
     *
     * 优先从本地缓存读取（避免 TapTap API 最终一致性延迟），
     * 然后异步查询 API 更新缓存。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    suspend fun checkCloudSave(): CloudSaveInfo {
        // 先尝试 API 查询
        return try {
            val cached = loadCloudSaveInfoFromLocal()
            val info = performTapTapQuery()
            val extraData = info?.extra?.let { e -> parseCloudExtraJson(e) }
            val apiResult = toCloudSaveInfo(info, extraData)
            // 防止 TapTap metadata 最终一致性延迟——上传后
            // 立刻查询可能返回"有存档但摘要全空"的旧 extra，直接采用会把真实游戏字段
            // 清零（游戏内存档卡片显示全 0）；也可能返回旧但非空的摘要，把更新的本地
            // 数据降级。合并策略见 [resolveCloudSaveInfo]；脏标志取存量单档（slot 0）
            // 账本——SR-2 起云上传队列确认写入同一账本。
            val result = resolveCloudSaveInfo(
                cached = cached,
                api = apiResult,
                localDirty = uploadLedger.isLocalDirty(StorageConstants.CLOUD_SAVE_SLOT)
            )
            // 仅持久化含真实摘要的结果，避免把陈旧空摘要写死进本地缓存
            if (result.hasSaveData && result.hasMeaningfulSummary()) {
                saveCloudSaveInfoToLocal(result)
            }
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消穿透: 查询取消时中止, 不降级本地缓存读
        } catch (e: Exception) {
            DomainLog.w(TAG, "Failed to check cloud save from API, falling back to cache", e)
            // API 失败时降级到本地缓存
            loadCloudSaveInfoFromLocal() ?: CloudSaveInfo(hasSaveData = false)
        }
    }

    /** 将 CloudSaveInfo 持久化到本地 SharedPreferences */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun saveCloudSaveInfoToLocal(info: CloudSaveInfo) {
        try {
            val json = JSONObject().apply {
                put("hasSaveData", info.hasSaveData)
                put("lastModifiedTime", info.lastModifiedTime)
                put("saveSize", info.saveSize)
                put("description", info.description)
                put("gameYear", info.gameYear)
                put("gameMonth", info.gameMonth)
                put("sectName", info.sectName)
                put("discipleCount", info.discipleCount)
                put("spiritStones", info.spiritStones)
                put("appVersion", info.appVersion)
            }
            ensureMigrated()
            keyValueStore.putString(KEY_CLOUD_SAVE_INFO, json.toString())
        } catch (e: Exception) {
            DomainLog.w(TAG, "Failed to save cloud save info to local cache", e)
        }
    }

    /** 从本地 SharedPreferences 读取缓存的 CloudSaveInfo */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private fun loadCloudSaveInfoFromLocal(): CloudSaveInfo? {
        return try {
            ensureMigrated()
            val jsonStr = keyValueStore.getString(KEY_CLOUD_SAVE_INFO, null) ?: return null
            val json = JSONObject(jsonStr)
            if (!json.optBoolean("hasSaveData", false)) return null
            CloudSaveInfo(
                hasSaveData = true,
                lastModifiedTime = json.optLong("lastModifiedTime", 0L),
                saveSize = json.optLong("saveSize", 0L),
                description = json.optString("description", ""),
                gameYear = json.optInt("gameYear", 0),
                gameMonth = json.optInt("gameMonth", 0),
                sectName = json.optString("sectName", ""),
                discipleCount = json.optInt("discipleCount", 0),
                spiritStones = json.optLong("spiritStones", 0L),
                appVersion = json.optString("appVersion", "")
            )
        } catch (e: Exception) {
            DomainLog.w(TAG, "Failed to load cloud save info from local cache", e)
            null
        }
    }

    // ── 以下为 TapTap API 的具体调用 ──

    /**
     * 执行 TapTap 云存档上传。
     *
     * 通过运行时反射调用 TapTap Cloud Save SDK，自动适配不同版本的 API。
     * 支持以下 API 模式（按优先级尝试）：
     * 1. `com.taptap.sdk.cloudsave.TapCloudSave` — 静态类模式
     * 2. `com.xd.sdk.taptap.XDTapCloudSave` — XDSDK 包装模式
     *
     * 如果运行时没有可用的 TapTap Cloud Save SDK，则只缓存文件到本地，记录警告。
     */
    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
    private suspend fun performTapTapUpload(tempFile: File, saveData: SaveData? = null) {
        val cloudSaveApi = CloudSaveApiReflector.resolve()
        if (cloudSaveApi == null) {
            DomainLog.w(TAG, "TapTap Cloud Save SDK not available")
            error("TapTap 云存档 SDK 不可用，请确认已安装 TapTap 并登录")
        }

        DomainLog.i(TAG, "Uploading cloud save via ${cloudSaveApi.className}, " +
            "file=${tempFile.name}, size=${tempFile.length()}")

        val (summary, extraJson) = if (saveData != null) {
            val gd = saveData.gameData
            val desc = "第${gd.gameYear}年${gd.gameMonth}月 ${gd.sectName}"
            val extra = JSONObject().apply {
                put("year", gd.gameYear)
                put("month", gd.gameMonth)
                put("sect", gd.sectName)
                put("disciples", saveData.disciples.size)
                put("stones", gd.spiritStones)
                put("version", GameConfig.Game.VERSION)
            }.toString()
            desc to extra
        } else {
            "模拟宗门云存档" to "{}"
        }
        val cachedUuid = getCachedArchiveUuid()

        try {
            val newUuid = cloudSaveApi.createOrUpdateArchive(
                archiveName = CLOUD_SAVE_ARCHIVE_NAME,
                summary = summary,
                filePath = tempFile.absolutePath,
                uuid = cachedUuid,
                extra = extraJson
            )
            if (newUuid != null && newUuid != cachedUuid) {
                saveCachedArchiveUuid(newUuid)
            }
            DomainLog.i(TAG, "Cloud save upload successful, uuid=${newUuid ?: cachedUuid}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消穿透: 取消时原样上抛, 不误记 error 日志/不清 UUID 缓存
        } catch (e: Exception) {
            if (e.message?.contains("400002") == true) {
                // 存档不存在（云端被删），清除缓存下次重新创建
                clearCachedArchiveUuid()
                DomainLog.w(TAG, "Cached archive UUID expired, will create new on next upload")
            }
            DomainLog.e(TAG, "Cloud save upload failed", e)
            throw e
        }
    }

    /**
     * 执行 TapTap 云存档下载。
     *
     * @return true=下载成功, false=云存档不存在
     */
    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
    private suspend fun performTapTapDownload(tempFile: File): Boolean {
        val cloudSaveApi = CloudSaveApiReflector.resolve()
        if (cloudSaveApi == null) {
            DomainLog.w(TAG, "TapTap Cloud Save SDK not available")
            return false
        }

        DomainLog.i(TAG, "Downloading cloud save via ${cloudSaveApi.className}")

        return try {
            val data = cloudSaveApi.downloadArchive(CLOUD_SAVE_ARCHIVE_NAME)
            if (data != null && data.isNotEmpty()) {
                tempFile.writeBytes(data)
                DomainLog.i(TAG, "Cloud save downloaded successfully, size=${data.size}")
                true
            } else {
                DomainLog.w(TAG, "Cloud save not found or empty")
                false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消穿透: 取消时原样上抛, 不误记 error 日志
        } catch (e: Exception) {
            DomainLog.e(TAG, "Cloud save download failed", e)
            throw e
        }
    }

    /**
     * 查询云存档信息。
     *
     * @return 存档信息，null 表示无存档
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private suspend fun performTapTapQuery(): CloudSaveRawInfo? {
        val cloudSaveApi = CloudSaveApiReflector.resolve()
        if (cloudSaveApi == null) return null

        return try {
            cloudSaveApi.queryArchiveInfo(CLOUD_SAVE_ARCHIVE_NAME)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消穿透: 查询取消时上抛, 不以 null 冒充"无存档"
        } catch (e: Exception) {
            DomainLog.w(TAG, "Cloud save query failed", e)
            null
        }
    }

    // ── 云端孤立存档清理 ──

    /**
     * 一次性云端存档检查（历史遗留"孤立存档清理"）。
     *
     * 多设备场景下非 "mnzm_cloud_save" 命名的存档可能是其他设备/其他命名版本
     * 的有效存档，删除不可逆——因此不删除任何未知命名的存档（保留数据），
     * 仅记录审计日志；清除缓存的 UUID 后完成一次性任务。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    suspend fun oneTimeCleanup() {
        ensureMigrated()
        if (keyValueStore.getBoolean(KEY_CLEANUP_DONE, false)) return

        val api = CloudSaveApiReflector.resolve() ?: return
        try {
            val allArchives = api.listAllArchives()
            val others = allArchives.filter { it.name != CLOUD_SAVE_ARCHIVE_NAME }
            if (others.isNotEmpty()) {
                DomainLog.i(
                    TAG,
                    "oneTimeCleanup: 发现 ${others.size} 个非当前命名存档，保留并记录: " +
                        others.joinToString { "${it.name}(${it.uuid.take(8)})" }
                )
            }
            clearCachedArchiveUuid()
            keyValueStore.putBoolean(KEY_CLEANUP_DONE, true)
            DomainLog.i(TAG, "oneTimeCleanup: done")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消穿透: 取消时上抛, 保持未完成标记下次重试
        } catch (e: Exception) {
            DomainLog.w(TAG, "oneTimeCleanup: failed, will retry next time", e)
        }
    }
}
