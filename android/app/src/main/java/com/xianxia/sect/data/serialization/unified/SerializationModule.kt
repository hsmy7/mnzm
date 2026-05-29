package com.xianxia.sect.data.serialization.unified

import android.util.Log
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.unified.SerializationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SerializationModule @Inject constructor(
    private val serializationEngine: UnifiedSerializationEngine,
    private val saveDataConverter: SaveDataConverter,
    private val saveDataMigrator: SaveDataMigrator
) {

    companion object {
        private const val TAG = "SerializationModule"
    }

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
                val data = result.data
                val migrated = if (saveDataMigrator.needsMigration(data.version)) {
                    when (val r = saveDataMigrator.migrate(data)) {
                        is MigrationResult.Success -> r.data
                        is MigrationResult.Failed -> data
                    }
                } else data
                saveDataConverter.fromSerializable(migrated)
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
