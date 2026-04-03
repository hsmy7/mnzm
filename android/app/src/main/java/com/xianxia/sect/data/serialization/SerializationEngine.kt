package com.xianxia.sect.data.serialization

import android.util.Log
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.serializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

object SerializationConfig {
    const val MAGIC_HEADER: Short = 0x5853
    const val VERSION: Byte = 1
    const val COMPRESSION_THRESHOLD = 256
    
    val MODE_PROTOBUF = SerializationMode.PROTOBUF
    val MODE_BINARY = SerializationMode.BINARY
    val MODE_BINARY_COMPRESSED = SerializationMode.BINARY_COMPRESSED
    val MODE_JSON = SerializationMode.JSON
}

enum class SerializationMode {
    PROTOBUF,
    BINARY,
    BINARY_COMPRESSED,
    JSON
}

enum class DataTypeCode(val code: Byte) {
    NULL(0),
    BOOLEAN(1), BYTE(2), SHORT(3), INT(4), LONG(5),
    FLOAT(6), DOUBLE(7), STRING(8),
    BYTE_ARRAY(9), INT_ARRAY(10), LONG_ARRAY(11), STRING_ARRAY(12),
    LIST(20), MAP(21), OBJECT(30),
    GAME_DATA(50), DISCIPLE(51), EQUIPMENT(52), MANUAL(53),
    PILL(54), MATERIAL(55), HERB(56), SEED(57),
    BATTLE_LOG(58), GAME_EVENT(59);

    companion object {
        fun fromCode(code: Byte): DataTypeCode = values().find { it.code == code } ?: NULL
    }
}

data class SerializationContext(
    val mode: SerializationMode = SerializationConfig.MODE_PROTOBUF,
    val needCompatibility: Boolean = false,
    val version: Int = 1
)

data class SerializationResult(
    val data: ByteArray,
    val originalSize: Int,
    val compressedSize: Int,
    val mode: SerializationMode,
    val checksum: Long
) {
    val compressionRatio: Double
        get() = if (originalSize > 0) compressedSize.toDouble() / originalSize else 1.0
}

class SerializationException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SmartSerializer {
    companion object {
        private const val TAG = "SmartSerializer"
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    
    @OptIn(ExperimentalSerializationApi::class)
    private val protoBuf = ProtoBuf {
        encodeDefaults = true
    }
    
    private val binarySerializer = LegacyBinarySerializer()
    private val jsonFallback = JsonFallbackSerializer(json)
    
    fun <T : Any> serialize(data: T, context: SerializationContext = SerializationContext()): SerializationResult {
        return try {
            when (selectMode(context)) {
                SerializationMode.PROTOBUF -> serializeProtobuf(data)
                SerializationMode.BINARY -> binarySerializer.serialize(data)
                SerializationMode.BINARY_COMPRESSED -> binarySerializer.serializeCompressed(data)
                SerializationMode.JSON -> jsonFallback.serialize(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Serialization failed for ${data::class.simpleName}, falling back to JSON", e)
            jsonFallback.serialize(data)
        }
    }
    
    @OptIn(InternalSerializationApi::class)
    private fun <T : Any> serializeProtobuf(data: T): SerializationResult {
        @Suppress("UNCHECKED_CAST")
        val serializer = data::class.serializer() as KSerializer<T>
        val bytes = protoBuf.encodeToByteArray(serializer, data)
        val checksum = bytes.fold(0L) { acc, byte -> acc * 31 + byte }
        return SerializationResult(
            data = bytes,
            originalSize = bytes.size,
            compressedSize = bytes.size,
            mode = SerializationMode.PROTOBUF,
            checksum = checksum
        )
    }
    
    fun <T : Any> deserialize(data: ByteArray, clazz: Class<T>, context: SerializationContext = SerializationContext()): T? {
        if (data.isEmpty()) return null
        
        return try {
            val mode = detectMode(data)
            when (mode) {
                SerializationMode.PROTOBUF -> deserializeProtobuf(data, clazz)
                SerializationMode.BINARY -> binarySerializer.deserialize(data, clazz)
                SerializationMode.BINARY_COMPRESSED -> binarySerializer.deserializeCompressed(data, clazz)
                SerializationMode.JSON -> jsonFallback.deserialize(data, clazz)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Deserialization failed for ${clazz.simpleName}", e)
            null
        }
    }
    
    @OptIn(InternalSerializationApi::class)
    private fun <T : Any> deserializeProtobuf(data: ByteArray, clazz: Class<T>): T? {
        return try {
            @Suppress("UNCHECKED_CAST")
            val serializer = clazz.kotlin.serializer() as KSerializer<T>
            protoBuf.decodeFromByteArray(serializer, data)
        } catch (e: Exception) {
            Log.e(TAG, "Protobuf deserialization failed", e)
            null
        }
    }
    
    private fun selectMode(context: SerializationContext): SerializationMode {
        return when {
            context.needCompatibility -> SerializationMode.JSON
            context.mode != SerializationConfig.MODE_PROTOBUF -> context.mode
            else -> SerializationMode.PROTOBUF
        }
    }
    
    private fun detectMode(data: ByteArray): SerializationMode {
        if (data.size < 4) return SerializationMode.JSON
        
        val buffer = ByteBuffer.wrap(data, 0, 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.short
        
        return if (magic == SerializationConfig.MAGIC_HEADER) {
            val flags = data[3].toInt()
            if (flags and 0x80 != 0) SerializationMode.BINARY_COMPRESSED else SerializationMode.BINARY
        } else {
            SerializationMode.JSON
        }
    }
}

class LegacyBinarySerializer {
    companion object {
        private const val TAG = "LegacyBinary"
    }
    
    fun <T : Any> serialize(data: T): SerializationResult {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        dos.writeShort(SerializationConfig.MAGIC_HEADER.toInt())
        dos.writeByte(SerializationConfig.VERSION.toInt())
        dos.writeByte(0)
        
        serializeValue(data, dos)
        
        dos.flush()
        val result = baos.toByteArray()
        val checksum = calculateChecksum(result)
        
        return SerializationResult(
            data = result,
            originalSize = result.size,
            compressedSize = result.size,
            mode = SerializationMode.BINARY,
            checksum = checksum
        )
    }
    
    fun <T : Any> serializeCompressed(data: T): SerializationResult {
        val uncompressed = serialize(data)
        val compressed = compress(uncompressed.data)
        
        val result = ByteArray(compressed.size + 4)
        System.arraycopy(compressed, 0, result, 4, compressed.size)
        result[3] = (result[3].toInt() or 0x80).toByte()
        
        val sizeBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        sizeBuffer.putInt(uncompressed.data.size)
        System.arraycopy(sizeBuffer.array(), 0, result, 0, 4)
        
        return SerializationResult(
            data = result,
            originalSize = uncompressed.originalSize,
            compressedSize = result.size,
            mode = SerializationMode.BINARY_COMPRESSED,
            checksum = uncompressed.checksum
        )
    }
    
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> deserialize(data: ByteArray, clazz: Class<T>): T? {
        if (data.size < 4) return null
        
        val dis = DataInputStream(ByteArrayInputStream(data))
        val magic = dis.readShort()
        if (magic != SerializationConfig.MAGIC_HEADER) return null
        
        dis.readByte()
        val flags = dis.readByte()
        
        return if ((flags.toInt() and 0x80) != 0) {
            deserializeCompressed(data, clazz)
        } else {
            deserializeValue(dis, clazz) as? T
        }
    }
    
    fun <T : Any> deserializeCompressed(data: ByteArray, clazz: Class<T>): T? {
        if (data.size < 8) return null
        
        val sizeBuffer = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN)
        val uncompressedSize = sizeBuffer.int
        
        val compressed = data.copyOfRange(4, data.size)
        val uncompressed = decompress(compressed, uncompressedSize)
        
        return deserialize(uncompressed, clazz)
    }
    
    private fun serializeValue(value: Any?, dos: DataOutputStream) {
        when (value) {
            null -> dos.writeByte(DataTypeCode.NULL.code.toInt())
            is Boolean -> { dos.writeByte(DataTypeCode.BOOLEAN.code.toInt()); dos.writeBoolean(value) }
            is Byte -> { dos.writeByte(DataTypeCode.BYTE.code.toInt()); dos.writeByte(value.toInt()) }
            is Short -> { dos.writeByte(DataTypeCode.SHORT.code.toInt()); dos.writeShort(value.toInt()) }
            is Int -> { dos.writeByte(DataTypeCode.INT.code.toInt()); writeVarInt(dos, value) }
            is Long -> { dos.writeByte(DataTypeCode.LONG.code.toInt()); writeVarLong(dos, value) }
            is Float -> { dos.writeByte(DataTypeCode.FLOAT.code.toInt()); dos.writeFloat(value) }
            is Double -> { dos.writeByte(DataTypeCode.DOUBLE.code.toInt()); dos.writeDouble(value) }
            is String -> {
                dos.writeByte(DataTypeCode.STRING.code.toInt())
                val bytes = value.toByteArray(Charsets.UTF_8)
                writeVarInt(dos, bytes.size)
                dos.write(bytes)
            }
            is ByteArray -> {
                dos.writeByte(DataTypeCode.BYTE_ARRAY.code.toInt())
                writeVarInt(dos, value.size)
                dos.write(value)
            }
            is IntArray -> {
                dos.writeByte(DataTypeCode.INT_ARRAY.code.toInt())
                writeVarInt(dos, value.size)
                value.forEach { writeVarInt(dos, it) }
            }
            is LongArray -> {
                dos.writeByte(DataTypeCode.LONG_ARRAY.code.toInt())
                writeVarInt(dos, value.size)
                value.forEach { writeVarLong(dos, it) }
            }
            is List<*> -> {
                dos.writeByte(DataTypeCode.LIST.code.toInt())
                writeVarInt(dos, value.size)
                value.forEach { serializeValue(it, dos) }
            }
            is Map<*, *> -> {
                dos.writeByte(DataTypeCode.MAP.code.toInt())
                writeVarInt(dos, value.size)
                value.forEach { (k, v) -> serializeValue(k, dos); serializeValue(v, dos) }
            }
            else -> {
                dos.writeByte(DataTypeCode.OBJECT.code.toInt())
                val typeName = (value::class.qualifiedName ?: "Unknown").toByteArray(Charsets.UTF_8)
                dos.writeInt(typeName.size)
                dos.write(typeName)
                val bytes = value.toString().toByteArray(Charsets.UTF_8)
                writeVarInt(dos, bytes.size)
                dos.write(bytes)
            }
        }
    }
    
    private fun deserializeValue(dis: DataInputStream, clazz: Class<*>): Any? {
        val typeCode = dis.readByte()
        return when (DataTypeCode.fromCode(typeCode)) {
            DataTypeCode.NULL -> null
            DataTypeCode.BOOLEAN -> dis.readBoolean()
            DataTypeCode.BYTE -> dis.readByte()
            DataTypeCode.SHORT -> dis.readShort()
            DataTypeCode.INT -> readVarInt(dis)
            DataTypeCode.LONG -> readVarLong(dis)
            DataTypeCode.FLOAT -> dis.readFloat()
            DataTypeCode.DOUBLE -> dis.readDouble()
            DataTypeCode.STRING -> { val len = readVarInt(dis); val b = ByteArray(len); dis.readFully(b); String(b, Charsets.UTF_8) }
            DataTypeCode.BYTE_ARRAY -> { val len = readVarInt(dis); val b = ByteArray(len); dis.readFully(b); b }
            DataTypeCode.INT_ARRAY -> { val len = readVarInt(dis); IntArray(len) { readVarInt(dis) } }
            DataTypeCode.LONG_ARRAY -> { val len = readVarInt(dis); LongArray(len) { readVarLong(dis) } }
            DataTypeCode.LIST -> { val len = readVarInt(dis); (0 until len).map { deserializeValue(dis, Any::class.java) } }
            DataTypeCode.MAP -> { val len = readVarInt(dis); (0 until len).associate { deserializeValue(dis, Any::class.java) to deserializeValue(dis, Any::class.java) } }
            DataTypeCode.OBJECT -> {
                val typeNameLen = dis.readInt(); val tn = ByteArray(typeNameLen); dis.readFully(tn)
                val len = readVarInt(dis); val b = ByteArray(len); dis.readFully(b)
                String(b, Charsets.UTF_8)
            }
            else -> null
        }
    }
    
    private fun writeVarInt(dos: DataOutputStream, value: Int) {
        var v = value
        while (v and -0x80 != 0) { dos.writeByte(v and 0x7F or 0x80); v = v ushr 7 }
        dos.writeByte(v)
    }
    
    private fun readVarInt(dis: DataInputStream): Int {
        var result = 0; var shift = 0; var b: Int
        do { b = dis.readByte().toInt(); result = result or (b and 0x7F shl shift); shift += 7 } while (b and 0x80 != 0)
        return result
    }
    
    private fun writeVarLong(dos: DataOutputStream, value: Long) {
        var v = value
        while (v and -0x80L != 0L) { dos.writeByte(v.toInt() and 0x7F or 0x80); v = v ushr 7 }
        dos.writeByte(v.toInt())
    }
    
    private fun readVarLong(dis: DataInputStream): Long {
        var result = 0L; var shift = 0; var b: Int
        do { b = dis.readByte().toInt(); result = result or (b.toLong() and 0x7F shl shift); shift += 7 } while (b and 0x80 != 0)
        return result
    }
    
    private fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(data); deflater.finish()
        val output = ByteArray(data.size + 64)
        val size = deflater.deflate(output); deflater.end()
        return output.copyOf(size)
    }
    
    private fun decompress(data: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater(); inflater.setInput(data)
        val output = ByteArray(expectedSize); inflater.inflate(output); inflater.end()
        return output
    }
    
    private fun calculateChecksum(data: ByteArray): Long = data.fold(0L) { acc, b -> acc * 31 + b }
}

class JsonFallbackSerializer(private val json: Json) {
    @OptIn(InternalSerializationApi::class)
    fun <T : Any> serialize(data: T): SerializationResult {
        @Suppress("UNCHECKED_CAST")
        val serializer = data::class.serializer() as KSerializer<T>
        val bytes = json.encodeToString(serializer, data).toByteArray(Charsets.UTF_8)
        return SerializationResult(
            data = bytes, originalSize = bytes.size, compressedSize = bytes.size,
            mode = SerializationMode.JSON, checksum = bytes.fold(0L) { acc, b -> acc * 31 + b }
        )
    }
    
    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> deserialize(data: ByteArray, clazz: Class<T>): T? {
        return try {
            json.decodeFromString(clazz.kotlin.serializer() as KSerializer<T>, String(data, Charsets.UTF_8))
        } catch (e: Exception) { null }
    }
}
