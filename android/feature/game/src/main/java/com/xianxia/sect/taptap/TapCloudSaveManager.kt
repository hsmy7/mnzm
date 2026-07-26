package com.xianxia.sect.taptap

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.SerializationModule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.lang.reflect.Proxy
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
    private val serializationModule: SerializationModule
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
    }

    /** 持久化缓存：云端存档的 UUID，用于更新而非创建 */
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 云存档操作并发锁，防止上传/下载同时进行 */
    private val cloudOpLock = AtomicBoolean(false)

    private fun getCachedArchiveUuid(): String? {
        val uuid = prefs.getString(KEY_ARCHIVE_UUID, null)
        if (uuid != null) DomainLog.d(TAG, "Using cached archive UUID: $uuid")
        return uuid
    }

    private fun saveCachedArchiveUuid(uuid: String) {
        prefs.edit().putString(KEY_ARCHIVE_UUID, uuid).apply()
        DomainLog.d(TAG, "Cached archive UUID: $uuid")
    }

    private fun clearCachedArchiveUuid() {
        prefs.edit().remove(KEY_ARCHIVE_UUID).apply()
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
    )

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
            } catch (e: Exception) {
                DomainLog.e(TAG, "Serialization failed during cloud upload", e)
                return CloudSaveResult.SerializationError(e.message ?: "序列化失败")
            }

            // 2. 检查文件大小（TapTap 限制单个存档 ≤10MB）
            if (serializedBytes.size > MAX_CLOUD_SAVE_SIZE_BYTES) {
                val actualMb = serializedBytes.size / (1024 * 1024)
                val maxMb = MAX_CLOUD_SAVE_SIZE_BYTES / (1024 * 1024)
                DomainLog.w(TAG, "Cloud save file too large: ${serializedBytes.size} bytes (${actualMb}MB > ${maxMb}MB)")
                return CloudSaveResult.FileTooLarge(MAX_CLOUD_SAVE_SIZE_BYTES, serializedBytes.size.toLong())
            }

            // 3. 写入临时文件
            val tempFile = File(context.cacheDir, CLOUD_SAVE_FILE_NAME)
            try {
                tempFile.parentFile?.mkdirs()
                tempFile.writeBytes(serializedBytes)
            } catch (e: Exception) {
                DomainLog.e(TAG, "Failed to write temp file for cloud upload", e)
                return CloudSaveResult.UnknownError("临时文件写入失败: ${e.message}")
            }

            // 4. 通过 TapTap Cloud Save API 上传
            return try {
                performTapTapUpload(tempFile, saveData)
                DomainLog.i(TAG, "Cloud save upload successful")
                CloudSaveResult.Success()
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
    suspend fun downloadSave(): CloudSaveResult {
        if (!cloudOpLock.compareAndSet(false, true)) {
            DomainLog.w(TAG, "Cloud save operation already in progress, rejecting concurrent download")
            return CloudSaveResult.NetworkError("云存档操作正在进行中，请稍后重试")
        }
        try {
            DomainLog.d(TAG, "Starting cloud save download...")

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
                } catch (e: Exception) {
                    DomainLog.e(TAG, "Deserialization failed during cloud download", e)
                    return CloudSaveResult.SerializationError(e.message ?: "反序列化失败")
                }

                DomainLog.i(TAG, "Cloud save download successful")
                return CloudSaveResult.Success(saveData)
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
     * 查询云存档是否存在及摘要信息。
     *
     * 优先从本地缓存读取（避免 TapTap API 最终一致性延迟），
     * 然后异步查询 API 更新缓存。
     */
    suspend fun checkCloudSave(): CloudSaveInfo {
        // 先尝试 API 查询
        return try {
            val info = performTapTapQuery()
            val extraData = info?.extra?.let { e ->
                try { JSONObject(e) } catch (ex: kotlinx.coroutines.CancellationException) { throw ex } catch (_: Exception) { null }
            }
            val apiResult = CloudSaveInfo(
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
            // API 查询成功且有数据时更新本地缓存
            if (apiResult.hasSaveData) {
                saveCloudSaveInfoToLocal(apiResult)
            }
            apiResult
        } catch (e: Exception) {
            DomainLog.w(TAG, "Failed to check cloud save from API, falling back to cache", e)
            // API 失败时降级到本地缓存
            loadCloudSaveInfoFromLocal() ?: CloudSaveInfo(hasSaveData = false)
        }
    }

    /** 将 CloudSaveInfo 持久化到本地 SharedPreferences */
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
            prefs.edit().putString(KEY_CLOUD_SAVE_INFO, json.toString()).apply()
        } catch (e: Exception) {
            DomainLog.w(TAG, "Failed to save cloud save info to local cache", e)
        }
    }

    /** 从本地 SharedPreferences 读取缓存的 CloudSaveInfo */
    private fun loadCloudSaveInfoFromLocal(): CloudSaveInfo? {
        return try {
            val jsonStr = prefs.getString(KEY_CLOUD_SAVE_INFO, null) ?: return null
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
    private suspend fun performTapTapUpload(tempFile: File, saveData: SaveData? = null) {
        val cloudSaveApi = CloudSaveApiReflector.resolve()
        if (cloudSaveApi == null) {
            DomainLog.w(TAG, "TapTap Cloud Save SDK not available")
            throw RuntimeException("TapTap 云存档 SDK 不可用，请确认已安装 TapTap 并登录")
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
    private suspend fun performTapTapQuery(): CloudSaveRawInfo? {
        val cloudSaveApi = CloudSaveApiReflector.resolve()
        if (cloudSaveApi == null) return null

        return try {
            cloudSaveApi.queryArchiveInfo(CLOUD_SAVE_ARCHIVE_NAME)
        } catch (e: Exception) {
            DomainLog.w(TAG, "Cloud save query failed", e)
            null
        }
    }

    // ── 云端孤立存档清理 ──

    /**
     * 一次性清理云端孤立存档。
     * 老玩家第一次上传前清理旧版本残留的存档，新玩家跳过。
     * 删除非 "mnzm_cloud_save" 名称的所有存档，然后建立新存档。
     */
    suspend fun oneTimeCleanup() {
        if (prefs.getBoolean(KEY_CLEANUP_DONE, false)) return

        val api = CloudSaveApiReflector.resolve() ?: return
        try {
            val allArchives = api.listAllArchives()
            val toDelete = allArchives.filter { it.name != CLOUD_SAVE_ARCHIVE_NAME }
            if (toDelete.isEmpty()) {
                prefs.edit().putBoolean(KEY_CLEANUP_DONE, true).apply()
                return
            }
            DomainLog.i(TAG, "oneTimeCleanup: deleting ${toDelete.size} orphan archives")
            for (archive in toDelete) {
                try { api.deleteArchive(archive.uuid) } catch (ex: kotlinx.coroutines.CancellationException) { throw ex } catch (_: Exception) { }
            }
            clearCachedArchiveUuid()
            prefs.edit().putBoolean(KEY_CLEANUP_DONE, true).apply()
            DomainLog.i(TAG, "oneTimeCleanup: done")
        } catch (e: Exception) {
            DomainLog.w(TAG, "oneTimeCleanup: failed, will retry next time", e)
        }
    }

    // ── 运行时反射 API 桥接（简化实现，避免 Proxy 类型推断问题） ──

    /**
     * 运行时反射解析 TapTap Cloud Save SDK。
     *
     * `tap-cloudsave` v4.10.x 的 Android API 在文档中未公开，
     * 因此通过反射动态检测可用的 API 类和回调接口。
     * 一旦 SDK API 确认，可替换为直接调用。
     */
    private object CloudSaveApiReflector {
        private const val TAG_REFL = "CloudSaveReflector"
        private var resolvedApi: CloudSaveApi? = null

        fun resolve(): CloudSaveApi? {
            if (resolvedApi != null) return resolvedApi
            resolvedApi = tryDetectXDSdkApi()
                ?: tryDetectTapSdkApi()
            if (resolvedApi != null) {
                DomainLog.i(TAG_REFL, "Resolved TapTap Cloud Save API: ${resolvedApi?.className ?: "unknown"}")
            } else {
                DomainLog.w(TAG_REFL, "No TapTap Cloud Save API found at runtime")
            }
            return resolvedApi
        }

        private fun tryDetectXDSdkApi(): CloudSaveApi? {
            return try {
                val clazz = Class.forName("com.xd.sdk.taptap.XDTapCloudSave")
                // 注意: XDTapCloudSave 在 tap-cloudsave artifact 中不存在,
                // 需要额外依赖 com.xd.sdk:xdsdk-taptap 才可用
                clazz.getMethod("getArchiveList",
                    Class.forName("com.taptap.sdk.cloudsave.internal.TapCloudSaveRequestCallback"))
                DomainLog.i(TAG_REFL, "Detected XDSDK TapCloudSave API")
                ReflectiveCloudSaveApi(clazz, "XDTapCloudSave")
            } catch (_: Exception) { null }
        }

        private fun tryDetectTapSdkApi(): CloudSaveApi? {
            return try {
                val clazz = Class.forName("com.taptap.sdk.cloudsave.TapTapCloudSave")
                DomainLog.i(TAG_REFL, "Detected native TapTapCloudSave API")
                ReflectiveCloudSaveApi(clazz, "TapTapCloudSave")
            } catch (_: Exception) { null }
        }
    }

    /** 反射调用的云存档 API 接口 */
    private interface CloudSaveApi {
        val className: String
        /** 上传存档，返回云端分配的 UUID。提供 uuid 时直接更新，否则创建新存档。 */
        suspend fun createOrUpdateArchive(archiveName: String, summary: String, filePath: String, uuid: String? = null, extra: String = "{}"): String?
        suspend fun downloadArchive(archiveName: String): ByteArray?
        suspend fun queryArchiveInfo(archiveName: String): CloudSaveRawInfo?
        /** 列出所有云端存档 */
        suspend fun listAllArchives(): List<ArchiveEntry>
        /** 删除指定 UUID 的云端存档 */
        suspend fun deleteArchive(uuid: String)
    }

    /** 云端存档条目摘要 */
    data class ArchiveEntry(
        val uuid: String,
        val name: String,
        val modifiedTime: Long
    )

    /**
     * 反射桥接的 CloudSave API 实现。
     *
     * 通过运行时反射调用静态方法，适配 TapTap Cloud Save SDK。
     * 同时支持 XDSDK (`com.xd.sdk.taptap.XDTapCloudSave`) 和
     * 原生 SDK (`com.taptap.sdk.cloudsave.TapTapCloudSave`)。
     * 两个 SDK 的静态方法签名完全一致。
     *
     * 回调桥接使用 [kotlinx.coroutines.suspendCancellableCoroutine]。
     * 由于目标回调接口的方法签名是运行时检测的，使用反射进行动态派发。
     */
    private class ReflectiveCloudSaveApi(
        private val apiClass: Class<*>,
        override val className: String
    ) : CloudSaveApi {

        @Suppress("UNCHECKED_CAST")
        override suspend fun createOrUpdateArchive(archiveName: String, summary: String, filePath: String, uuid: String?, extra: String): String? {
            val metadataClass = Class.forName("com.taptap.sdk.cloudsave.ArchiveMetadata")
            val builderClass = Class.forName("com.taptap.sdk.cloudsave.ArchiveMetadata\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            builder.javaClass.getMethod("setName", String::class.java).invoke(builder, archiveName)
            builder.javaClass.getMethod("setSummary", String::class.java).invoke(builder, summary)
            builder.javaClass.getMethod("setExtra", String::class.java).invoke(builder, extra)
            builder.javaClass.getMethod("setPlaytime", Integer.TYPE)
                .invoke(builder, (System.currentTimeMillis() / 1000).toInt())
            val metadata = builder.javaClass.getMethod("build").invoke(builder)

            val callbackClass = Class.forName("com.taptap.sdk.cloudsave.internal.TapCloudSaveRequestCallback")

            val effectiveUuid = uuid ?: findArchiveUuidByName(archiveName)

            if (effectiveUuid != null) {
                val result = suspendCallbackNullable<Any>(callbackClass) { callback ->
                    apiClass.getMethod("updateArchive",
                        String::class.java, metadataClass, String::class.java, String::class.java, callbackClass
                    ).invoke(null, effectiveUuid, metadata, filePath, null, callback)
                }
                return result?.let { getUuid(it) }
            } else {
                val result = suspendCallbackNullable<Any>(callbackClass) { callback ->
                    apiClass.getMethod("createArchive",
                        metadataClass, String::class.java, String::class.java, callbackClass
                    ).invoke(null, metadata, filePath, null, callback)
                }
                return result?.let { getUuid(it) }
            }
        }

        override suspend fun downloadArchive(archiveName: String): ByteArray? {
            val uuid = findArchiveUuidByName(archiveName) ?: return null
            val fileId = findArchiveFileIdByName(archiveName) ?: return null
            val callbackClass = Class.forName("com.taptap.sdk.cloudsave.internal.TapCloudSaveRequestCallback")

            return suspendCallbackNullable<ByteArray>(callbackClass) { callback ->
                apiClass.getMethod("getArchiveData",
                    String::class.java, String::class.java, callbackClass
                ).invoke(null, uuid, fileId, callback)
            }
        }

        override suspend fun queryArchiveInfo(archiveName: String): CloudSaveRawInfo? {
            val archives = listArchives()
            val target = archives?.firstOrNull { getName(it) == archiveName } ?: return null
            return CloudSaveRawInfo(
                lastModifiedTime = getModifiedTime(target) * 1000,
                fileSize = getSaveSize(target),
                description = getSummary(target) ?: "",
                extra = getExtra(target) ?: ""
            )
        }

        override suspend fun listAllArchives(): List<ArchiveEntry> {
            val archives = listArchives() ?: return emptyList()
            return archives.mapNotNull { a ->
                val uuid = getUuid(a) ?: ""
                val name = getName(a) ?: ""
                if (uuid.isBlank()) null else ArchiveEntry(uuid, name, getModifiedTime(a))
            }
        }

        override suspend fun deleteArchive(uuid: String) {
            val callbackClass = Class.forName("com.taptap.sdk.cloudsave.internal.TapCloudSaveRequestCallback")
            suspendCallbackUnit(callbackClass) { callback ->
                apiClass.getMethod("deleteArchive",
                    String::class.java, callbackClass
                ).invoke(null, uuid, callback)
            }
        }

        private suspend fun findArchiveUuidByName(archiveName: String): String? {
            return listArchives()?.firstOrNull { getName(it) == archiveName }?.let { getUuid(it) }
        }

        private suspend fun findArchiveFileIdByName(archiveName: String): String? {
            return listArchives()?.firstOrNull { getName(it) == archiveName }?.let { getFileId(it) }
        }

        @Suppress("UNCHECKED_CAST")
        private suspend fun listArchives(): List<Any>? {
            val callbackClass = Class.forName("com.taptap.sdk.cloudsave.internal.TapCloudSaveRequestCallback")
            @Suppress("UNCHECKED_CAST")
            val result: Any? = suspendCallbackNullable<Any?>(callbackClass) { callback ->
                apiClass.getMethod("getArchiveList", callbackClass).invoke(null, callback)
            }
            return result as? List<Any>
        }

        /** 返回 Unit 的回调桥接 */
        private suspend fun suspendCallbackUnit(
            callbackClass: Class<*>,
            invoke: (callback: Any) -> Unit
        ) {
            suspendCallbackNullable<Unit>(callbackClass, invoke)
        }

        /** 返回可为 null 的回调桥接 */
        @Suppress("UNCHECKED_CAST")
        private suspend fun <T> suspendCallbackNullable(
            callbackClass: Class<*>,
            invoke: (callback: Any) -> Unit
        ): T? = suspendCancellableCoroutine { continuation ->
            val callback = Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _: Any, method: java.lang.reflect.Method, args: Array<*>? ->
                handleCallbackMethod(method.name, args, object : CallbackHandler {
                    override fun onSuccess(value: Any?) {
                        if (continuation.isActive) {
                            @Suppress("UNCHECKED_CAST")
                            continuation.resumeWith(Result.success(value as? T))
                        }
                    }
                    override fun onError(e: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(e))
                        }
                    }
                })
                null
            }
            invoke(callback)
        }

        /** 回调结果处理器 */
        private interface CallbackHandler {
            fun onSuccess(value: Any?)
            fun onError(e: Throwable)
        }

        /**
         * 处理回调方法调用。
         *
         * 根据方法名分派到 [CallbackHandler]。
         * TapTap 回调模式：
         * - onArchiveCreated(ArchiveData) / onArchiveUpdated(ArchiveData) / onArchiveDeleted()
         *   → 成功，resume(Unit)
         * - onArchiveListResult(List<ArchiveData>) → 成功，返回列表
         * - onArchiveDataResult(byte[]) → 成功，返回数据（实际签名仅 1 参数）
         * - onRequestError(code, message) → 失败，根据 code 决定恢复或异常
         */
        @Suppress("UNCHECKED_CAST")
        private fun handleCallbackMethod(
            methodName: String,
            args: Array<*>?,
            handler: CallbackHandler
        ) {
            when (methodName) {
                "onArchiveCreated", "onArchiveUpdated", "onArchiveDeleted" -> {
                    // 返回 ArchiveData 对象（包含 uuid），供调用方缓存
                    handler.onSuccess(args?.getOrNull(0))
                }
                "onArchiveListResult" -> {
                    handler.onSuccess(args?.getOrNull(0))
                }
                "onArchiveDataResult" -> {
                    // 实际签名: onArchiveDataResult(byte[] data)，仅 1 参数
                    handler.onSuccess(args?.getOrNull(0))
                }
                "onArchiveCoverResult" -> {
                    handler.onSuccess(args?.getOrNull(0))
                }
                "onRequestError" -> {
                    val code = args?.getOrNull(0) as? Int ?: 0
                    val msg = args?.getOrNull(1) as? String ?: ""
                    when (code) {
                        400002 -> handler.onSuccess(null) // 存档不存在
                        400100 -> handler.onSuccess(null) // SDK 未就绪
                        400003 -> handler.onError(RuntimeException("云端存储空间不足或存档数量超限，请在 TapTap 中管理旧存档 [$code]: $msg"))
                        400007 -> handler.onError(RuntimeException("并发操作不允许，请稍后重试 [$code]: $msg"))
                        else -> handler.onError(RuntimeException("TapTap cloud save error [$code]: $msg"))
                    }
                }
                else -> {
                    DomainLog.w(TAG, "Unhandled callback method: $methodName")
                }
            }
        }

        private fun getName(archive: Any): String? = invokeGetterString(archive, "getName")
        private fun getSummary(archive: Any): String? = invokeGetterString(archive, "getSummary")
        private fun getExtra(archive: Any): String? = invokeGetterString(archive, "getExtra")
        private fun getSaveSize(archive: Any): Long = invokeGetterLong(archive, "getSaveSize")
        private fun getModifiedTime(archive: Any): Long = invokeGetterLong(archive, "getModifiedTime")
        private fun getUuid(archive: Any): String? = invokeGetterString(archive, "getUuid")
        private fun getFileId(archive: Any): String? = invokeGetterString(archive, "getFileId")

        private fun invokeGetterString(archive: Any, methodName: String): String? {
            return try {
                archive.javaClass.getMethod(methodName).invoke(archive)?.toString()
            } catch (e: Exception) {
                DomainLog.w(TAG, "invokeGetterString failed: method=$methodName class=${archive.javaClass.simpleName}", e)
                null
            }
        }

        private fun invokeGetterLong(archive: Any, methodName: String): Long {
            return try {
                archive.javaClass.getMethod(methodName).invoke(archive) as? Long ?: 0L
            } catch (_: Exception) { 0L }
        }
    }

    /**
     * TapGameSave API（v3 SDK 兼容，预留）。
     */
    private class TapGameSaveApi(private val apiClass: Class<*>) : CloudSaveApi {
        override val className: String = "TapGameSave"
        override suspend fun createOrUpdateArchive(archiveName: String, summary: String, filePath: String, uuid: String?, extra: String): String? =
            throw UnsupportedOperationException("TapGameSave API not yet adapted")
        override suspend fun downloadArchive(archiveName: String): ByteArray? =
            throw UnsupportedOperationException("TapGameSave API not yet adapted")
        override suspend fun queryArchiveInfo(archiveName: String): CloudSaveRawInfo? = null
        override suspend fun listAllArchives(): List<ArchiveEntry> = throw UnsupportedOperationException("TapGameSave API not yet adapted")
        override suspend fun deleteArchive(uuid: String) = throw UnsupportedOperationException("TapGameSave API not yet adapted")
    }

    /** 云存档原始信息（TapTap API 返回） */
    private data class CloudSaveRawInfo(
        val lastModifiedTime: Long,
        val fileSize: Long,
        val description: String,
        val extra: String = "{}"
    )
}
