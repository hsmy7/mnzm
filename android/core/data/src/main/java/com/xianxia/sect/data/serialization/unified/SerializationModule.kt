package com.xianxia.sect.data.serialization.unified

import android.util.Log
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.unified.SerializationException
import kotlinx.serialization.serializer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SerializationModule @Inject constructor(
    private val serializationEngine: UnifiedSerializationEngine
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
            Log.e(TAG, "Failed to deserialize protobuf data", e)
            null
        }
    }
}
