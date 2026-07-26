package com.xianxia.sect.data.serialization.unified

import android.util.Log
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.backwardcompat.OldSaveFormatDeserializer
import com.xianxia.sect.data.unified.SerializationException
import kotlinx.serialization.serializer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SerializationModule @Inject constructor(
    private val serializationEngine: UnifiedSerializationEngine,
    private val oldSaveFormatDeserializer: OldSaveFormatDeserializer
) {

    companion object {
        private const val TAG = "SerializationModule"
    }

    fun serializeAndCompressSaveData(data: SaveData): ByteArray {
        return try {
            val context = SerializationContext(
                format = SerializationFormat.PROTOBUF,
                compression = CompressionType.LZ4,
                compressThreshold = 1024,
                includeChecksum = true
            )
            val result = serializationEngine.serialize(
                data,
                context,
                serializer<SaveData>()
            )
            result.data
        } catch (e: Exception) {
            Log.e(TAG, "Protobuf serialization failed", e)
            val rootCauseMsg = e.cause?.let { " [root: ${it.message}]" }.orEmpty()
            throw SerializationException("Failed to serialize save data via Protobuf${rootCauseMsg}", e)
        }
    }

    fun deserializeSaveData(data: ByteArray): SaveData {
        return try {
            // 尝试新格式（当前格式）
            val newFormatResult = tryDeserializeNewFormat(data)
            if (newFormatResult != null) {
                return newFormatResult
            }

            // 新格式失败 → 尝试旧格式（SerializableSaveData 兼容）
            Log.w(TAG, "新格式反序列化失败，尝试旧格式兼容层…")
            val oldFormatResult = oldSaveFormatDeserializer.tryDeserialize(data)
            if (oldFormatResult != null) {
                Log.i(TAG, "旧格式兼容层反序列化成功")
                return oldFormatResult
            }

            throw SerializationException("Protobuf deserialization failed in all formats (data may be corrupted)")
        } catch (e: SerializationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize save data via Protobuf", e)
            throw SerializationException("Failed to deserialize save data via Protobuf", e)
        }
    }

    private fun tryDeserializeNewFormat(data: ByteArray): SaveData? {
        return try {
            val context = SerializationContext(
                format = SerializationFormat.PROTOBUF,
                compression = CompressionType.LZ4,
                includeChecksum = true
            )
            val result = serializationEngine.deserialize<SaveData>(
                data,
                context,
                serializer()
            )
            if (result.isSuccess && result.data != null) {
                result.data
            } else {
                Log.w(TAG, "Protobuf deserialization returned invalid result, checksum valid: ${result.checksumValid}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "新格式反序列化异常（预期内，将尝试旧格式）", e)
            null
        }
    }
}
