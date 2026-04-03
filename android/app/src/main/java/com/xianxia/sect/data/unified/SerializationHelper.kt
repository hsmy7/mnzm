package com.xianxia.sect.data.unified

import android.util.Log
import com.xianxia.sect.data.GsonConfig
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.CompressionType
import com.xianxia.sect.data.serialization.unified.SaveDataConverter
import com.xianxia.sect.data.serialization.unified.SerializationContext
import com.xianxia.sect.data.serialization.unified.SerializationFormat
import com.xianxia.sect.data.serialization.unified.SerializableSaveData
import com.xianxia.sect.data.serialization.unified.UnifiedSerializationEngine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SerializationHelper @Inject constructor(
    private val serializationEngine: UnifiedSerializationEngine,
    private val saveDataConverter: SaveDataConverter
) {
    
    companion object {
        private const val TAG = "SerializationHelper"
        private const val GZIP_BUFFER_SIZE = 64 * 1024
    }
    
    private val gson = GsonConfig.createGson()
    
    fun serializeAndCompressSaveData(data: SaveData): ByteArray {
        return try {
            val serializableData = saveDataConverter.toSerializable(data)
            val context = SerializationContext(
                format = SerializationFormat.PROTOBUF,
                compression = CompressionType.LZ4,
                compressThreshold = 1024,
                includeChecksum = true
            )
            val result = serializationEngine.serialize(
                serializableData,
                context,
                kotlinx.serialization.serializer<SerializableSaveData>()
            )
            result.data
        } catch (e: Exception) {
            Log.w(TAG, "Protobuf serialization failed, falling back to GZIP", e)
            serializeAndCompressSaveDataLegacy(data)
        }
    }
    
    fun serializeAndCompressSaveDataLegacy(data: SaveData): ByteArray {
        val jsonBytes = gson.toJson(data).toByteArray(Charsets.UTF_8)
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos, GZIP_BUFFER_SIZE).use { gzip ->
            gzip.write(jsonBytes)
        }
        return baos.toByteArray()
    }
    
    fun decompressData(data: ByteArray): ByteArray? {
        return try {
            if (isGzipCompressed(data)) {
                GZIPInputStream(ByteArrayInputStream(data), GZIP_BUFFER_SIZE).use { input ->
                    input.readBytes()
                }
            } else {
                data
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decompress data", e)
            null
        }
    }
    
    fun deserializeSaveData(data: ByteArray): SaveData? {
        return try {
            if (isProtobufFormat(data)) {
                deserializeProtobufData(data)
            } else {
                deserializeJsonData(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize save data", e)
            null
        }
    }
    
    fun isGzipCompressed(data: ByteArray): Boolean {
        return data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()
    }
    
    fun isProtobufFormat(data: ByteArray): Boolean {
        if (data.size < 8) return false
        val magic = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        return magic == 0x5853
    }
    
    fun deserializeProtobufData(data: ByteArray): SaveData? {
        return try {
            val context = SerializationContext(
                format = SerializationFormat.PROTOBUF,
                compression = CompressionType.LZ4,
                includeChecksum = true
            )
            val result = serializationEngine.deserialize<SerializableSaveData>(
                data,
                context,
                kotlinx.serialization.serializer()
            )
            if (result.isSuccess && result.data != null) {
                saveDataConverter.fromSerializable(result.data)
            } else {
                Log.w(TAG, "Protobuf deserialization failed, checksum valid: ${result.checksumValid}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize protobuf data", e)
            null
        }
    }
    
    fun deserializeJsonData(data: ByteArray): SaveData? {
        return try {
            gson.fromJson(String(data, Charsets.UTF_8), SaveData::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize JSON data", e)
            null
        }
    }
    
    suspend fun tryLoadLegacyFormat(
        data: ByteArray,
        decryptData: suspend (ByteArray) -> ByteArray?
    ): SaveData? {
        return try {
            if (isGzipCompressed(data)) {
                GZIPInputStream(ByteArrayInputStream(data), GZIP_BUFFER_SIZE).use { input ->
                    InputStreamReader(input, Charsets.UTF_8).use { reader ->
                        gson.fromJson(reader, SaveData::class.java)
                    }
                }
            } else {
                val decrypted = decryptData(data)
                if (decrypted != null) {
                    if (isGzipCompressed(decrypted)) {
                        GZIPInputStream(ByteArrayInputStream(decrypted), GZIP_BUFFER_SIZE).use { input ->
                            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                                gson.fromJson(reader, SaveData::class.java)
                            }
                        }
                    } else {
                        gson.fromJson(String(decrypted, Charsets.UTF_8), SaveData::class.java)
                    }
                } else {
                    gson.fromJson(String(data, Charsets.UTF_8), SaveData::class.java)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load legacy format: ${e.message}")
            null
        }
    }
}
