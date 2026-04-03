package com.xianxia.sect.taptap

import android.content.Context
import android.util.Log
import com.taptap.sdk.cloudsave.ArchiveData
import com.taptap.sdk.cloudsave.ArchiveMetadata
import com.taptap.sdk.cloudsave.TapTapCloudSave
import com.taptap.sdk.cloudsave.internal.TapCloudSaveRequestCallback
import com.xianxia.sect.data.GsonConfig
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.MigrationResult
import com.xianxia.sect.data.serialization.unified.SaveDataConverter
import com.xianxia.sect.data.serialization.unified.SaveDataMigrator
import com.xianxia.sect.data.serialization.unified.SerializableSaveData
import com.xianxia.sect.data.serialization.unified.UnifiedSerializationEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class CloudSaveInfo(
    val uuid: String,
    val fileId: String,
    val name: String,
    val summary: String,
    val extra: String,
    val playtime: Long,
    val modifiedTime: Long,
    val saveSize: Long
)

sealed class CloudSaveResult<out T> {
    data class Success<T>(val data: T) : CloudSaveResult<T>()
    data class Error(val code: Int, val message: String) : CloudSaveResult<Nothing>()
}

@Singleton
class TapTapCloudSaveManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serializationEngine: UnifiedSerializationEngine,
    private val saveDataConverter: SaveDataConverter,
    private val saveDataMigrator: SaveDataMigrator
) {
    companion object {
        private const val TAG = "TapTapCloudSave"
        private const val CLOUD_SAVE_VERSION = "1.0"
        private const val MAX_SAVE_SIZE_BYTES = 10 * 1024 * 1024L
        private const val LIST_COLLECT_TIMEOUT_MS = 3000L
        private const val LIST_DEBOUNCE_MS = 200L

        const val ERR_SIZE_EXCEEDED = -1
        const val ERR_UNKNOWN = -2
        const val ERR_SERIALIZE = -3
        const val ERR_FILE_NOT_FOUND = -4
        const val ERR_PARSE_FAILED = -5
        const val ERR_SDK_NOT_READY = -6
        const val ERR_TIMEOUT = -7
        const val ERR_CHECKSUM_MISMATCH = -8
        const val ERR_LEGACY_FORMAT = -9
        const val ERR_ENCRYPTION_FAILED = -10
        const val ERR_DECRYPTION_FAILED = -11
        
        private const val ENCRYPTION_MARKER = "enc:aes-gcm-v1"
    }

    private val operationMutex = Mutex()

    private val tempDir: File by lazy { File(context.cacheDir, "cloud_save_temp").apply { mkdirs() } }

    suspend fun uploadSave(
        slot: Int,
        saveData: SaveData,
        existingUuid: String? = null
    ): CloudSaveResult<String> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try {
                val serializableData = saveDataConverter.toSerializable(saveData)
                val result = serializationEngine.serialize(
                    serializableData,
                    serializer = SerializableSaveData.serializer()
                )
                val saveBytes = result.data

                if (saveBytes.size > MAX_SAVE_SIZE_BYTES) {
                    Log.w(TAG, "存档数据过大: ${saveBytes.size} bytes (原始: ${result.originalSize} bytes)")
                    return@withContext CloudSaveResult.Error(ERR_SIZE_EXCEEDED, "存档数据过大（${saveBytes.size} bytes），超过 ${MAX_SAVE_SIZE_BYTES / 1024 / 1024}MB 限制")
                }

                val encryptedBytes = try {
                    val cloudKey = com.xianxia.sect.data.crypto.SecureKeyManager.getOrCreateKey(context)
                    com.xianxia.sect.data.crypto.SaveCrypto.encryptAsync(saveBytes, cloudKey)
                } catch (e: Exception) {
                    Log.e(TAG, "云存档加密失败", e)
                    return@withContext CloudSaveResult.Error(ERR_ENCRYPTION_FAILED, "云存档加密失败: ${e.message}")
                }

                val tempFile = createTempFile(slot)
                tempFile.writeBytes(encryptedBytes)

                val summary = buildSummary(saveData)
                val extra = buildExtra(slot, saveData, true)

                Log.i(TAG, "开始上传云存档(已加密): slot=$slot, size=${encryptedBytes}bytes (原始${saveBytes}bytes), existingUuid=$existingUuid")

                val uploadResult = suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation {
                        tempFile.delete()
                        Log.d(TAG, "上传操作已取消，清理临时文件")
                    }

                    try {
                        val metadata = ArchiveMetadata.Builder()
                            .setName("Slot${slot}")
                            .setSummary(summary)
                            .setExtra(extra)
                            .setPlaytime(calculatePlaytime(saveData))
                            .build()

                        val callback = object : TapCloudSaveRequestCallback {
                            override fun onRequestError(errorCode: Int, errorMessage: String) {
                                Log.e(TAG, "上传云存档失败: errorCode=$errorCode, msg=$errorMessage")
                                tempFile.delete()
                                if (cont.isActive) cont.resume(CloudSaveResult.Error(errorCode, errorMessage))
                            }

                            override fun onArchiveCreated(archive: ArchiveData) {
                                val uuid = archive.uuid ?: ""
                                Log.i(TAG, "云存档上传成功: uuid=$uuid, name=${archive.name}")
                                tempFile.delete()
                                if (cont.isActive) cont.resume(CloudSaveResult.Success(uuid))
                            }
                        }

                        if (!existingUuid.isNullOrEmpty()) {
                            TapTapCloudSave.updateArchive(
                                existingUuid, metadata, tempFile.absolutePath, null, callback
                            )
                        } else {
                            TapTapCloudSave.createArchive(
                                metadata, tempFile.absolutePath, null, callback
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "调用SDK上传接口异常", e)
                        tempFile.delete()
                        if (cont.isActive) cont.resume(CloudSaveResult.Error(ERR_UNKNOWN, e.message ?: "未知错误"))
                    }
                }

                uploadResult
            } catch (e: Exception) {
                Log.e(TAG, "上传云存档异常", e)
                CloudSaveResult.Error(ERR_SERIALIZE, "序列化失败: ${e.message}")
            }
        }
    }

    suspend fun fetchCloudSaveList(): CloudSaveResult<List<CloudSaveInfo>> =
        operationMutex.withLock {
            try {
                Log.d(TAG, "获取云存档列表")

                val saves = mutableListOf<CloudSaveInfo>()
                var errorResult: CloudSaveResult.Error? = null
                var completed = false

                val contResult = suspendCancellableCoroutine<CloudSaveResult<List<CloudSaveInfo>>> { cont ->
                    cont.invokeOnCancellation {
                        completed = true
                        Log.d(TAG, "获取列表操作已取消")
                    }

                    TapTapCloudSave.getArchiveList(
                        object : TapCloudSaveRequestCallback {
                            override fun onRequestError(errorCode: Int, errorMessage: String) {
                                Log.e(TAG, "获取云存档列表失败: errorCode=$errorCode, msg=$errorMessage")
                                errorResult = CloudSaveResult.Error(errorCode, errorMessage)
                                completed = true
                                if (cont.isActive) errorResult?.let { cont.resume(it) }
                            }

                            override fun onArchiveCreated(archive: ArchiveData) {
                                if (completed) return
                                saves.add(toCloudSaveInfo(archive))
                            }
                        }
                    )

                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!completed && cont.isActive) {
                            completed = true
                            if (errorResult != null) {
                                errorResult?.let { cont.resume(it) }
                            } else {
                                Log.i(TAG, "云存档列表获取完成，共 ${saves.size} 条")
                                cont.resume(CloudSaveResult.Success(saves.toList()))
                            }
                        }
                    }, LIST_DEBOUNCE_MS)

                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!completed) {
                            completed = true
                            Log.w(TAG, "获取云存档列表超时，已收集 ${saves.size} 条结果")
                            if (saves.isNotEmpty()) {
                                if (cont.isActive) cont.resume(CloudSaveResult.Success(saves.toList()))
                            } else {
                                if (cont.isActive) cont.resume(CloudSaveResult.Error(ERR_TIMEOUT, "获取云存档列表超时"))
                            }
                        }
                    }, LIST_COLLECT_TIMEOUT_MS)
                }

                contResult
            } catch (e: Exception) {
                Log.e(TAG, "获取云存档列表异常", e)
                CloudSaveResult.Error(ERR_UNKNOWN, e.message ?: "未知错误")
            }
        }

    suspend fun downloadSave(uuid: String, fileId: String): CloudSaveResult<SaveData> =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                try {
                    if (!isValidId(uuid) || !isValidId(fileId)) {
                        return@withContext CloudSaveResult.Error(ERR_PARSE_FAILED, "无效的存档标识符")
                    }

                    Log.i(TAG, "开始下载云存档: uuid=$uuid, fileId=$fileId")

                    val rawResult = suspendCancellableCoroutine<CloudSaveResult<SerializableSaveData>> { cont ->
                        cont.invokeOnCancellation {
                            Log.d(TAG, "下载操作已取消: uuid=$uuid")
                        }

                        try {
                            TapTapCloudSave.getArchiveData(
                                uuid, fileId,
                                object : TapCloudSaveRequestCallback {
                                    override fun onRequestError(errorCode: Int, errorMessage: String) {
                                        Log.e(TAG, "下载云存档数据失败: errorCode=$errorCode, msg=$errorMessage")
                                        if (cont.isActive) cont.resume(
                                            CloudSaveResult.Error(errorCode, errorMessage)
                                        )
                                    }

                                    override fun onArchiveCreated(archive: ArchiveData) {
                                        try {
                                            val downloadedFile = findDownloadedFile(uuid, fileId)

                                            if (downloadedFile == null || !downloadedFile.exists() || downloadedFile.length() == 0L) {
                                                Log.e(TAG, "未找到下载的存档文件: uuid=$uuid, fileId=$fileId")
                                                if (cont.isActive) cont.resume(
                                                    CloudSaveResult.Error(ERR_FILE_NOT_FOUND, "下载的存档文件未找到或为空")
                                                )
                                                return
                                            }

                                            val encryptedBytes = downloadedFile.readBytes()
                                            
                                            val saveBytes = try {
                                                val extra = archive.extra ?: ""
                                                if (extra.contains(ENCRYPTION_MARKER)) {
                                                    Log.d(TAG, "检测到加密标记，执行云存档解密")
                                                    val cloudKey = com.xianxia.sect.data.crypto.SecureKeyManager.getOrCreateKey(context)
                                                    val decrypted = com.xianxia.sect.data.crypto.SaveCrypto.decrypt(encryptedBytes, cloudKey)
                                                    if (decrypted == null) {
                                                        Log.e(TAG, "云存档解密失败，返回null")
                                                        if (cont.isActive) cont.resume(
                                                            CloudSaveResult.Error(ERR_DECRYPTION_FAILED, "云存档解密失败: 密钥不匹配或数据损坏")
                                                        )
                                                        return
                                                    }
                                                    decrypted
                                                } else {
                                                    Log.d(TAG, "未检测到加密标记，使用原始数据(兼容旧格式)")
                                                    encryptedBytes
                                                }
                                            } catch (e: Exception) {
                                                Log.e(TAG, "云存档解密异常", e)
                                                if (cont.isActive) cont.resume(
                                                    CloudSaveResult.Error(ERR_DECRYPTION_FAILED, "云存档解密异常: ${e.message}")
                                                )
                                                return
                                            }

                                            val serializableData = deserializeCloudSaveData(saveBytes)
                                            if (serializableData == null) {
                                                downloadedFile.delete()
                                                if (cont.isActive) cont.resume(
                                                    CloudSaveResult.Error(ERR_PARSE_FAILED, "无法解析存档数据，Protobuf和JSON格式均失败")
                                                )
                                                return
                                            }

                                            Log.i(TAG, "云存档反序列化成功: version=${serializableData.version}, 格式检测完成")
                                            if (cont.isActive) cont.resume(CloudSaveResult.Success(serializableData))
                                        } catch (e: Exception) {
                                            Log.e(TAG, "解析云存档数据失败", e)
                                            if (cont.isActive) cont.resume(
                                                CloudSaveResult.Error(ERR_PARSE_FAILED, "解析存档数据失败: ${e.message}")
                                            )
                                        }
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "调用SDK下载接口异常", e)
                            if (cont.isActive) cont.resume(CloudSaveResult.Error(ERR_UNKNOWN, e.message ?: "下载异常"))
                        }
                    }

                    when (rawResult) {
                        is CloudSaveResult.Error -> rawResult
                        is CloudSaveResult.Success -> {
                            val migrationResult = saveDataMigrator.migrate(rawResult.data)
                            val finalSerializableData = when (migrationResult) {
                                is MigrationResult.Success -> migrationResult.data
                                is MigrationResult.Failed -> {
                                    Log.w(TAG, "存档迁移失败，使用原始数据: ${migrationResult.error.message}")
                                    migrationResult.fallbackData ?: rawResult.data
                                }
                            }

                            val saveData = saveDataConverter.fromSerializable(finalSerializableData)

                            if (saveData.gameData == null) {
                                Log.e(TAG, "转换后存档数据不完整: gameData为空")
                                CloudSaveResult.Error(ERR_PARSE_FAILED, "存档数据转换后不完整")
                            } else {
                                Log.i(TAG, "云存档下载并解析成功: sect=${saveData.gameData.sectName}, 弟子数=${saveData.disciples.size}, 迁移=${migrationResult is MigrationResult.Success}")
                                CloudSaveResult.Success(saveData)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "下载云存档异常", e)
                    CloudSaveResult.Error(ERR_UNKNOWN, e.message ?: "下载失败")
                }
            }
        }

    suspend fun deleteCloudSave(uuid: String): CloudSaveResult<Unit> =
        operationMutex.withLock {
            try {
                if (!isValidId(uuid)) {
                    return@withLock CloudSaveResult.Error(ERR_PARSE_FAILED, "无效的存档标识符")
                }

                Log.i(TAG, "删除云存档: uuid=$uuid")

                suspendCancellableCoroutine<CloudSaveResult<Unit>> { cont ->
                    TapTapCloudSave.deleteArchive(
                        uuid,
                        object : TapCloudSaveRequestCallback {
                            override fun onRequestError(errorCode: Int, errorMessage: String) {
                                Log.e(TAG, "删除云存档失败: errorCode=$errorCode, msg=$errorMessage")
                                if (cont.isActive) cont.resume(CloudSaveResult.Error(errorCode, errorMessage))
                            }

                            override fun onArchiveCreated(archive: ArchiveData) {
                                Log.i(TAG, "云存档删除成功: uuid=$uuid")
                                if (cont.isActive) cont.resume(CloudSaveResult.Success(Unit))
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "删除云存档异常", e)
                CloudSaveResult.Error(ERR_UNKNOWN, e.message ?: "删除失败")
            }
        }

    private fun findDownloadedFile(uuid: String, fileId: String): File? {
        val cacheDir = context.cacheDir ?: return null
        val filesDir = context.filesDir ?: return null

        val exactPatterns = listOf(
            "${uuid}_${fileId}",
            "${uuid}_${fileId}.dat",
            "${uuid}_${fileId}.save",
            "${uuid}_${fileId}.bin",
            uuid,
            fileId
        )

        val searchDirs = listOf(
            File(cacheDir, "taptap"),
            File(filesDir, "taptap"),
            File(cacheDir, "cloudsave"),
            cacheDir,
            tempDir
        )

        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue

            for (pattern in exactPatterns) {
                val exactMatch = File(dir, pattern)
                if (exactMatch.exists() && exactMatch.length() > 0) {
                    Log.d(TAG, "精确匹配到下载文件: ${exactMatch.absolutePath}")
                    return exactMatch
                }
            }

            val candidates = dir.listFiles()?.filter { f ->
                f.name.startsWith(uuid.take(8)) && f.length() > 0 &&
                (f.extension == "dat" || f.extension == "save" || f.extension == "bin" ||
                 f.extension.isEmpty())
            } ?: emptyList()

            if (candidates.isNotEmpty()) {
                val found = candidates.maxByOrNull { it.lastModified() }
                if (found != null) {
                    Log.d(TAG, "前缀匹配到下载文件: ${found.absolutePath}")
                    return found
                }
            }
        }

        val recentFiles = tempDir.listFiles()
            ?.filter { it.length() > 0 && it.lastModified() > System.currentTimeMillis() - 60000 }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (recentFiles.isNotEmpty()) {
            Log.d(TAG, "时间回退匹配到文件: ${recentFiles[0].absolutePath}")
            return recentFiles[0]
        }

        return null
    }

    private fun deserializeCloudSaveData(saveBytes: ByteArray): SerializableSaveData? {
        return if (isProtobufFormat(saveBytes)) {
            deserializeFromProtobuf(saveBytes)
        } else if (isJsonFormat(saveBytes)) {
            deserializeFromJson(saveBytes)
        } else {
            Log.e(TAG, "无法识别存档数据格式，长度=${saveBytes.size}, 前16字节hex=${saveBytes.take(16).joinToString("") { "%02x".format(it) }}")
            null
        }
    }

    private fun isProtobufFormat(data: ByteArray): Boolean {
        if (data.size < 2) return false
        val magic = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        return magic == 0x5853
    }

    private fun isJsonFormat(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val first = data[0].toChar()
        return first == '{' || first == '['
    }

    private fun deserializeFromProtobuf(saveBytes: ByteArray): SerializableSaveData? {
        return try {
            val result = serializationEngine.deserialize(
                saveBytes,
                serializer = SerializableSaveData.serializer()
            )

            when {
                !result.isSuccess || result.data == null -> {
                    Log.e(TAG, "Protobuf反序列化失败: ${result.error?.message}")
                    null
                }
                !result.checksumValid -> {
                    Log.e(TAG, "校验和不匹配，存档数据可能已损坏")
                    null
                }
                else -> {
                    Log.d(TAG, "Protobuf反序列化成功, 校验和有效")
                    result.data
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Protobuf反序列化异常", e)
            null
        }
    }

    private fun deserializeFromJson(saveBytes: ByteArray): SerializableSaveData? {
        return try {
            val jsonStr = String(saveBytes, Charsets.UTF_8)
            Log.i(TAG, "检测到旧版JSON格式云存档，尝试兼容解析, 长度=${saveBytes.size}")

            val saveData = GsonConfig.gson.fromJson(jsonStr, SaveData::class.java)
                ?: run {
                    Log.e(TAG, "JSON解析返回null")
                    return null
                }

            Log.i(TAG, "旧版JSON云存档解析成功: version=${saveData.version}, sect=${saveData.gameData.sectName}")
            saveDataConverter.toSerializable(saveData)
        } catch (e: Exception) {
            Log.e(TAG, "JSON回退解析失败", e)
            null
        }
    }

    private fun createTempFile(slot: Int): File {
        cleanupOldTempFiles()
        return File(tempDir, "upload_${slot}_${System.nanoTime()}.dat")
    }

    private fun cleanupOldTempFiles() {
        try {
            val cutoff = System.currentTimeMillis() - 3600000
            tempDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    private fun calculatePlaytime(saveData: SaveData): Int {
        val year = saveData.gameData.gameYear ?: 1
        val playHours = year.coerceIn(1, 9999).toLong() * 24L
        return playHours.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun toCloudSaveInfo(archive: ArchiveData): CloudSaveInfo = CloudSaveInfo(
        uuid = archive.uuid ?: "",
        fileId = archive.fileId ?: "",
        name = archive.name ?: "",
        summary = archive.summary ?: "",
        extra = archive.extra ?: "",
        playtime = archive.playtime?.toLong() ?: 0L,
        modifiedTime = archive.modifiedTime?.toLong() ?: 0L,
        saveSize = archive.saveSize?.toLong() ?: 0L
    )

    private fun isValidId(id: String): Boolean {
        return id.isNotEmpty() && !id.contains("..") &&
               !id.contains("/") && !id.contains("\\") &&
               !id.contains("\u0000")
    }

    private fun buildSummary(saveData: SaveData): String {
        val gd = saveData.gameData
        val sectName = gd.sectName.replace("|", "-").replace("\"", "'")
        return "$sectName|第${gd.gameYear}年${gd.gameMonth}月|弟子:${saveData.disciples.size}|灵石:${gd.spiritStones}"
    }

    private fun buildExtra(slot: Int, saveData: SaveData, encrypted: Boolean = false): String {
        val gd = saveData.gameData
        return StringBuilder().apply {
            append("{\"v\":\"").append(CLOUD_SAVE_VERSION).append("\",")
            append("\"slot\":").append(slot).append(",")
            append("\"year\":").append(gd.gameYear ?: 1).append(",")
            append("\"month\":").append(gd.gameMonth ?: 1).append(",")
            append("\"sect\":\"").append(escapeJson(gd.sectName ?: "")).append("\",")
            append("\"ts\":").append(saveData.timestamp ?: System.currentTimeMillis())
            if (encrypted) {
                append(",\"enc\":\"").append(ENCRYPTION_MARKER).append("\"")
            }
            append("}")
        }.toString()
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    }
}

object CloudEncryptionHelper {
    private const val TAG = "CloudEncHelper"
    private const val ENCRYPTION_MARKER = "enc:aes-gcm-v1"
    
    fun isEncrypted(extra: String?): Boolean {
        return extra?.contains(ENCRYPTION_MARKER) == true
    }
    
    suspend fun encryptForCloud(data: ByteArray, context: android.content.Context): com.xianxia.sect.taptap.CloudSaveResult<ByteArray> {
        return try {
            val cloudKey = com.xianxia.sect.data.crypto.SecureKeyManager.getOrCreateKey(context)
            val encrypted = com.xianxia.sect.data.crypto.SaveCrypto.encryptAsync(data, cloudKey)
            Log.d(TAG, "Cloud encryption successful: ${data.size} -> ${encrypted.size} bytes")
            com.xianxia.sect.taptap.CloudSaveResult.Success(encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Cloud encryption failed", e)
            com.xianxia.sect.taptap.CloudSaveResult.Error(
                TapTapCloudSaveManager.ERR_ENCRYPTION_FAILED,
                "Encryption failed: ${e.message}"
            )
        }
    }
    
    suspend fun decryptFromCloud(
        encryptedData: ByteArray,
        context: android.content.Context,
        extra: String? = null
    ): com.xianxia.sect.taptap.CloudSaveResult<ByteArray> {
        return try {
            if (!isEncrypted(extra)) {
                Log.d(TAG, "Data not encrypted, returning original")
                return com.xianxia.sect.taptap.CloudSaveResult.Success(encryptedData)
            }
            
            val cloudKey = com.xianxia.sect.data.crypto.SecureKeyManager.getOrCreateKey(context)
            val decrypted = com.xianxia.sect.data.crypto.SaveCrypto.decryptAsync(encryptedData, cloudKey)
            
            if (decrypted == null) {
                Log.e(TAG, "Cloud decryption returned null")
                return com.xianxia.sect.taptap.CloudSaveResult.Error(
                    TapTapCloudSaveManager.ERR_DECRYPTION_FAILED,
                    "Decryption failed: key mismatch or data corrupted"
                )
            }
            
            Log.d(TAG, "Cloud decryption successful: ${encryptedData.size} -> ${decrypted.size} bytes")
            com.xianxia.sect.taptap.CloudSaveResult.Success(decrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Cloud decryption exception", e)
            com.xianxia.sect.taptap.CloudSaveResult.Error(
                TapTapCloudSaveManager.ERR_DECRYPTION_FAILED,
                "Decryption exception: ${e.message}"
            )
        }
    }
    
    fun buildEncryptedExtra(
        slot: Int,
        saveData: com.xianxia.sect.data.model.SaveData,
        version: String = "1.0"
    ): String {
        val gd = saveData.gameData
        return StringBuilder().apply {
            append("{\"v\":\"").append(version).append("\",")
            append("\"slot\":").append(slot).append(",")
            append("\"year\":").append(gd.gameYear ?: 1).append(",")
            append("\"month\":").append(gd.gameMonth ?: 1).append(",")
            append("\"enc\":\"").append(ENCRYPTION_MARKER).append("\"")
            append("}")
        }.toString()
    }
}
