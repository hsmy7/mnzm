package com.xianxia.sect.data.serialization

import android.util.Log
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import com.xianxia.sect.data.serialization.unified.*
import com.xianxia.sect.data.serialization.unified.CompressionType as UCompressionType
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

object CacheSerializationConstants {
    const val MAGIC_HEADER: Short = 0x4350
    const val FORMAT_VERSION: Byte = 1
    const val HEADER_SIZE = 12
    const val CHECKSUM_SIZE = 32
    const val TYPE_NAME_MAX_LENGTH = 256
}

data class CacheSerializationContext(
    val compression: UCompressionType = UCompressionType.LZ4,
    val compressThreshold: Int = 256,
    val includeChecksum: Boolean = true
)

data class CacheSerializationResult(
    val data: ByteArray,
    val originalSize: Int,
    val compressedSize: Int,
    val compression: UCompressionType,
    val checksum: ByteArray,
    val typeName: String
) {
    val compressionRatio: Double
        get() = if (originalSize > 0) compressedSize.toDouble() / originalSize else 1.0
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CacheSerializationResult) return false
        return data.contentEquals(other.data)
    }
    
    override fun hashCode(): Int = data.contentHashCode()
}

@Singleton
class ProtobufCacheSerializer @Inject constructor() {
    companion object {
        private const val TAG = "ProtobufCacheSerializer"
    }
    
    /**
     * 统一的 ProtoBuf 实例（来自 NullSafeProtoBuf 工具类）
     *
     * 配置：encodeDefaults = true
     * 确保所有字段都被序列化，即使值为默认值
     */
    internal val protoBuf = NullSafeProtoBuf.protoBuf
    
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    
    internal val compressionLayer = LightweightCompressionLayer()
    internal val integrityLayer = LightweightIntegrityLayer()
    
    internal val serializerCache = ConcurrentHashMap<KClass<*>, KSerializer<*>>()
    
    @OptIn(InternalSerializationApi::class)
    fun <T : Any> serialize(
        data: T,
        context: CacheSerializationContext = CacheSerializationContext()
    ): CacheSerializationResult {
        val kClass = data::class
        val typeName = kClass.qualifiedName ?: "Unknown"
        
        @Suppress("UNCHECKED_CAST")
        val serializer = serializerCache.getOrPut(kClass) {
            kClass.serializer()
        } as KSerializer<T>
        
        val rawData = protoBuf.encodeToByteArray(serializer, data)
        
        val (compressedData, usedCompression) = if (rawData.size >= context.compressThreshold) {
            compressionLayer.compress(rawData, context.compression)
        } else {
            rawData to UCompressionType.LZ4
        }
        
        val checksum = if (context.includeChecksum) {
            integrityLayer.computeChecksum(rawData)
        } else {
            ByteArray(CacheSerializationConstants.CHECKSUM_SIZE)
        }
        
        val typeNameBytes = typeName.toByteArray(Charsets.UTF_8)
        
        val finalData = buildFinalData(
            compressedData,
            usedCompression,
            checksum,
            rawData.size,
            typeNameBytes
        )
        
        return CacheSerializationResult(
            data = finalData,
            originalSize = rawData.size,
            compressedSize = compressedData.size,
            compression = usedCompression,
            checksum = checksum,
            typeName = typeName
        )
    }
    
    @OptIn(InternalSerializationApi::class)
    fun <T : Any> deserialize(
        data: ByteArray,
        clazz: Class<T>,
        context: CacheSerializationContext = CacheSerializationContext()
    ): T? {
        if (data.size < CacheSerializationConstants.HEADER_SIZE) {
            Log.w(TAG, "Data too small: ${data.size} bytes")
            return null
        }
        
        return try {
            val header = parseHeader(data)
            
            val payloadStart = CacheSerializationConstants.HEADER_SIZE + 
                header.typeNameLength +
                (if (header.hasChecksum) CacheSerializationConstants.CHECKSUM_SIZE else 0)
            
            if (payloadStart > data.size) {
                Log.w(TAG, "Invalid header: payloadStart=$payloadStart, dataSize=${data.size}")
                return null
            }
            
            val payload = data.copyOfRange(payloadStart, data.size)
            
            val rawData = compressionLayer.decompress(payload, header.compression, header.originalSize)
            
            if (header.hasChecksum && context.includeChecksum) {
                val storedChecksum = data.copyOfRange(
                    CacheSerializationConstants.HEADER_SIZE + header.typeNameLength,
                    CacheSerializationConstants.HEADER_SIZE + header.typeNameLength + CacheSerializationConstants.CHECKSUM_SIZE
                )
                val computedChecksum = integrityLayer.computeChecksum(rawData)
                
                if (!storedChecksum.contentEquals(computedChecksum)) {
                    Log.w(TAG, "Checksum mismatch detected")
                    return null
                }
            }
            
            val typeName = clazz.name
            val kClass = clazz.kotlin
            @Suppress("UNCHECKED_CAST")
            val serializer = serializerCache.getOrPut(kClass) {
                kClass.serializer()
            } as KSerializer<T>
            
            protoBuf.decodeFromByteArray(serializer, rawData)
        } catch (e: Exception) {
            Log.e(TAG, "Deserialization failed", e)
            null
        }
    }
    
    fun serializeAny(
        data: Any,
        typeName: String,
        rawData: ByteArray,
        context: CacheSerializationContext = CacheSerializationContext()
    ): CacheSerializationResult {
        val (compressedData, usedCompression) = if (rawData.size >= context.compressThreshold) {
            compressionLayer.compress(rawData, context.compression)
        } else {
            rawData to UCompressionType.LZ4
        }
        
        val checksum = if (context.includeChecksum) {
            integrityLayer.computeChecksum(rawData)
        } else {
            ByteArray(CacheSerializationConstants.CHECKSUM_SIZE)
        }
        
        val typeNameBytes = typeName.toByteArray(Charsets.UTF_8)
        
        val finalData = buildFinalData(
            compressedData,
            usedCompression,
            checksum,
            rawData.size,
            typeNameBytes
        )
        
        return CacheSerializationResult(
            data = finalData,
            originalSize = rawData.size,
            compressedSize = compressedData.size,
            compression = usedCompression,
            checksum = checksum,
            typeName = typeName
        )
    }
    
    fun deserializeAny(data: ByteArray): Pair<ByteArray?, String>? {
        if (data.size < CacheSerializationConstants.HEADER_SIZE) {
            return null
        }
        
        return try {
            val header = parseHeader(data)
            
            val typeNameBytes = data.copyOfRange(
                CacheSerializationConstants.HEADER_SIZE,
                CacheSerializationConstants.HEADER_SIZE + header.typeNameLength
            )
            val typeName = String(typeNameBytes, Charsets.UTF_8)
            
            val payloadStart = CacheSerializationConstants.HEADER_SIZE + 
                header.typeNameLength +
                (if (header.hasChecksum) CacheSerializationConstants.CHECKSUM_SIZE else 0)
            
            if (payloadStart > data.size) {
                return null to typeName
            }
            
            val payload = data.copyOfRange(payloadStart, data.size)
            val rawData = compressionLayer.decompress(payload, header.compression, header.originalSize)
            
            if (header.hasChecksum) {
                val storedChecksum = data.copyOfRange(
                    CacheSerializationConstants.HEADER_SIZE + header.typeNameLength,
                    CacheSerializationConstants.HEADER_SIZE + header.typeNameLength + CacheSerializationConstants.CHECKSUM_SIZE
                )
                val computedChecksum = integrityLayer.computeChecksum(rawData)
                
                if (!storedChecksum.contentEquals(computedChecksum)) {
                    Log.w(TAG, "Checksum mismatch detected for type: $typeName")
                    return null to typeName
                }
            }
            
            rawData to typeName
        } catch (e: Exception) {
            Log.e(TAG, "Deserialization failed", e)
            null
        }
    }
    
    fun getHeaderInfo(data: ByteArray): CacheHeaderInfo? {
        if (data.size < CacheSerializationConstants.HEADER_SIZE) return null
        
        return try {
            parseHeader(data).let {
                CacheHeaderInfo(
                    compression = it.compression,
                    originalSize = it.originalSize,
                    hasChecksum = it.hasChecksum
                )
            }
        } catch (e: Exception) {
            null
        }
    }
    
    internal fun buildFinalData(
        payload: ByteArray,
        compression: UCompressionType,
        checksum: ByteArray,
        originalSize: Int,
        typeNameBytes: ByteArray
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        dos.writeShort(CacheSerializationConstants.MAGIC_HEADER.toInt())
        dos.writeByte(CacheSerializationConstants.FORMAT_VERSION.toInt())
        dos.writeByte(compression.code.toInt())
        dos.writeByte(if (checksum.isNotEmpty()) 1 else 0)
        dos.writeInt(originalSize)
        dos.writeInt(typeNameBytes.size)
        
        dos.write(typeNameBytes)
        
        if (checksum.isNotEmpty()) {
            dos.write(checksum)
        }
        
        dos.write(payload)
        
        return baos.toByteArray()
    }
    
    internal data class HeaderInfo(
        val compression: UCompressionType,
        val hasChecksum: Boolean,
        val originalSize: Int,
        val typeNameLength: Int
    )
    
    internal fun parseHeader(data: ByteArray): HeaderInfo {
        val dis = DataInputStream(ByteArrayInputStream(data))
        
        val magic = dis.readShort()
        if (magic != CacheSerializationConstants.MAGIC_HEADER) {
            throw SerializationException("Invalid magic header: $magic")
        }
        
        dis.readByte()
        val compressionCode = dis.readByte()
        val hasChecksum = dis.readByte() != 0.toByte()
        val originalSize = dis.readInt()
        val typeNameLength = dis.readInt()
        
        return HeaderInfo(
            compression = UCompressionType.fromCode(compressionCode),
            hasChecksum = hasChecksum,
            originalSize = originalSize,
            typeNameLength = typeNameLength
        )
    }
}

data class CacheHeaderInfo(
    val compression: UCompressionType,
    val originalSize: Int,
    val hasChecksum: Boolean
)

class LightweightCompressionLayer {
    private val lz4Compressor = net.jpountz.lz4.LZ4Factory.fastestInstance().fastCompressor()
    private val lz4Decompressor = net.jpountz.lz4.LZ4Factory.fastestInstance().fastDecompressor()
    
    fun compress(data: ByteArray, algorithm: UCompressionType): Pair<ByteArray, UCompressionType> {
        return compressLZ4(data) to UCompressionType.LZ4
    }
    
    fun decompress(data: ByteArray, algorithm: UCompressionType, originalSize: Int): ByteArray {
        return decompressLZ4(data, originalSize)
    }
    
    private fun compressLZ4(data: ByteArray): ByteArray {
        val maxCompressedLength = lz4Compressor.maxCompressedLength(data.size)
        val compressed = ByteArray(maxCompressedLength)
        val compressedSize = lz4Compressor.compress(data, 0, data.size, compressed, 0)
        return compressed.copyOf(compressedSize)
    }
    
    private fun decompressLZ4(data: ByteArray, originalSize: Int): ByteArray {
        val decompressed = ByteArray(originalSize)
        lz4Decompressor.decompress(data, 0, decompressed, 0, originalSize)
        return decompressed
    }
}

class LightweightIntegrityLayer {
    private val digestProvider = ThreadLocal.withInitial {
        java.security.MessageDigest.getInstance("SHA-256")
    }
    
    fun computeChecksum(data: ByteArray): ByteArray {
        return digestProvider.get()?.digest(data)
            ?: throw IllegalStateException("MessageDigest not initialized in ThreadLocal")
    }

    fun verifyChecksum(data: ByteArray, expected: ByteArray): Boolean {
        return digestProvider.get()?.digest(data)?.contentEquals(expected)
            ?: false
    }
}


