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

    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
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

    // [已合并 ThrowsCount 理由: 多步骤事务/异常翻译边界：各 throw 对应不同失败路径的领域错误，刻意独立抛出保归因清晰，非疏忽计数超标] // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
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

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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
            // 校验和不匹配（传输/存储中字节被篡改但 protobuf 恰好仍可解码）
            // 必须拒绝，防止语义损坏的存档被静默加载；
            // hasChecksum=false 的旧格式帧仍可解码（兼容）
            if (result.isSuccess && result.data != null && result.checksumValid) {
                result.data
            } else {
                Log.w(
                    TAG,
                    "Protobuf deserialization rejected: success=${result.isSuccess}, " +
                        "checksumValid=${result.checksumValid}"
                )
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "新格式反序列化异常（预期内，将尝试旧格式）", e)
            null
        }
    }
}
