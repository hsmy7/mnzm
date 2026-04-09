package com.xianxia.sect.data.unified

import android.util.Log
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.CompressionType
import com.xianxia.sect.data.serialization.unified.SaveDataConverter
import com.xianxia.sect.data.serialization.unified.SerializationContext
import com.xianxia.sect.data.serialization.unified.SerializationFormat
import com.xianxia.sect.data.serialization.unified.SerializableSaveData
import com.xianxia.sect.data.serialization.unified.UnifiedSerializationEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SerializationHelper @Inject constructor(
    private val serializationEngine: UnifiedSerializationEngine,
    private val saveDataConverter: SaveDataConverter
) {
    
    companion object {
        private const val TAG = "SerializationHelper"
    }
    
    /**
     * 将 SaveData 序列化为 Protobuf 格式的字节数组。
     *
     * 统一使用 Protobuf + LZ4 压缩路径，不再支持 JSON/GZIP 回退。
     * 序列化失败时直接抛出 [SerializationException]，不做静默降级。
     *
     * @param data 待序列化的存档数据
     * @return 序列化并压缩后的字节数组
     * @throws SerializationException 序列化过程中发生不可恢复的错误
     */
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
            Log.e(TAG, "Protobuf serialization failed", e)
            throw SerializationException("Failed to serialize save data via Protobuf", e)
        }
    }
    
    fun deserializeSaveData(data: ByteArray): SaveData {
        return try {
            deserializeProtobufData(data)
                ?: throw SerializationException("Protobuf deserialization returned null (data may be corrupted or checksum invalid)")
        } catch (e: SerializationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize save data via Protobuf", e)
            throw SerializationException("Failed to deserialize save data via Protobuf", e)
        }
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
                Log.w(TAG, "Protobuf deserialization returned invalid result, checksum valid: ${result.checksumValid}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize protobuf data", e)
            null
        }
    }
}

/**
 * 序列化过程中的不可恢复异常。
 *
 * 当 Protobuf 序列化/反序列化流程发生错误且无法通过重试解决时抛出。
 * 替代了原有的静默回退到 JSON/GZIP 的行为。
 */
class SerializationException(message: String, cause: Throwable? = null) : Exception(message, cause)
