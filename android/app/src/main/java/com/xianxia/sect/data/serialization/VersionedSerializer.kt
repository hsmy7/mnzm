package com.xianxia.sect.data.serialization

import android.util.Log
import java.io.*
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

interface VersionedBinarySerializer<T : Any> {
    val version: Int
    fun serialize(data: T): ByteArray
    fun deserialize(bytes: ByteArray): T
    fun serializeTo(data: T, output: OutputStream)
    fun deserializeFrom(input: InputStream): T
}

interface DataMigrator<T : Any> {
    val fromVersion: Int
    val toVersion: Int
    fun migrate(data: T): T
}

data class VersionedSerializationResult(
    val data: ByteArray,
    val version: Int,
    val compressed: Boolean,
    val checksum: String
)

data class DeserializationResult<T : Any>(
    val data: T,
    val version: Int,
    val migrated: Boolean,
    val migrationPath: List<Int>
)

class VersionedSerializer<T : Any>(
    private val currentVersion: Int,
    private val serializers: Map<Int, VersionedBinarySerializer<T>>,
    private val migrators: Map<Pair<Int, Int>, DataMigrator<T>>,
    private val compress: Boolean = true
) {
    companion object {
        private const val TAG = "VersionedSerializer"
        private const val MAGIC = "VSER"
        private const val HEADER_SIZE = 16
    }
    
    fun serialize(data: T): ByteArray {
        val serializer = serializers[currentVersion]
            ?: throw VersionedSerializationException("No serializer for version $currentVersion")
        
        val serialized = serializer.serialize(data)
        val checksum = calculateChecksum(serialized)
        
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { dos ->
            dos.writeBytes(MAGIC)
            dos.writeInt(currentVersion)
            dos.writeInt(serialized.size)
            dos.writeUTF(checksum.take(16))
            
            if (compress) {
                val compressed = compressData(serialized)
                dos.writeBoolean(true)
                dos.writeInt(compressed.size)
                dos.write(compressed)
            } else {
                dos.writeBoolean(false)
                dos.write(serialized)
            }
        }
        
        return output.toByteArray()
    }
    
    fun deserialize(bytes: ByteArray): DeserializationResult<T> {
        val input = ByteArrayInputStream(bytes)
        return deserializeFrom(input)
    }
    
    fun deserializeFrom(input: InputStream): DeserializationResult<T> {
        DataInputStream(input).use { dis ->
            val magic = ByteArray(4)
            dis.readFully(magic)
            
            if (String(magic) != MAGIC) {
                throw VersionedSerializationException("Invalid magic header: ${String(magic)}")
            }
            
            val version = dis.readInt()
            val expectedSize = dis.readInt()
            val storedChecksum = dis.readUTF()
            
            val isCompressed = dis.readBoolean()
            
            val data = if (isCompressed) {
                val compressedSize = dis.readInt()
                val compressed = ByteArray(compressedSize)
                dis.readFully(compressed)
                decompressData(compressed)
            } else {
                val raw = ByteArray(expectedSize)
                dis.readFully(raw)
                raw
            }
            
            val actualChecksum = calculateChecksum(data)
            if (actualChecksum.take(16) != storedChecksum) {
                Log.w(TAG, "Checksum mismatch: expected=$storedChecksum, actual=${actualChecksum.take(16)}")
            }
            
            val serializer = serializers[version]
                ?: throw VersionedSerializationException("No serializer for version $version")
            
            var result = serializer.deserialize(data)
            val migrationPath = mutableListOf<Int>()
            var migrated = false
            
            if (version != currentVersion) {
                val migrationResult = migrate(result, version, currentVersion)
                result = migrationResult.first
                migrationPath.addAll(migrationResult.second)
                migrated = true
            }
            
            return DeserializationResult(
                data = result,
                version = version,
                migrated = migrated,
                migrationPath = migrationPath
            )
        }
    }
    
    private fun migrate(data: T, fromVersion: Int, toVersion: Int): Pair<T, List<Int>> {
        var current = data
        var currentVersion = fromVersion
        val path = mutableListOf<Int>()
        
        while (currentVersion < toVersion) {
            val nextVersion = currentVersion + 1
            val migrator = migrators[currentVersion to nextVersion]
            
            if (migrator != null) {
                current = migrator.migrate(current)
                path.add(nextVersion)
                Log.d(TAG, "Migrated data from version $currentVersion to $nextVersion")
            } else {
                Log.w(TAG, "No migrator from $currentVersion to $nextVersion, skipping")
            }
            
            currentVersion = nextVersion
        }
        
        return current to path
    }
    
    private fun compressData(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.BEST_SPEED)
        DeflaterOutputStream(output, deflater).use { it.write(data) }
        return output.toByteArray()
    }
    
    private fun decompressData(data: ByteArray): ByteArray {
        val input = ByteArrayInputStream(data)
        val output = ByteArrayOutputStream()
        InflaterInputStream(input).use { it.copyTo(output) }
        return output.toByteArray()
    }
    
    private fun calculateChecksum(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}

class VersionedSerializationException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SerializerRegistry {
    private val serializers = mutableMapOf<String, VersionedSerializer<*>>()
    
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getSerializer(type: Class<T>): VersionedSerializer<T>? {
        return serializers[type.name] as? VersionedSerializer<T>
    }
    
    fun <T : Any> register(type: Class<T>, serializer: VersionedSerializer<T>) {
        serializers[type.name] = serializer
    }
    
    inline fun <reified T : Any> register(serializer: VersionedSerializer<T>) {
        register(T::class.java, serializer)
    }
    
    inline fun <reified T : Any> getSerializer(): VersionedSerializer<T>? {
        return getSerializer(T::class.java)
    }
}

abstract class BaseBinarySerializer<T : Any>(
    override val version: Int
) : VersionedBinarySerializer<T> {
    
    override fun serialize(data: T): ByteArray {
        val output = ByteArrayOutputStream()
        serializeTo(data, output)
        return output.toByteArray()
    }
    
    override fun deserialize(bytes: ByteArray): T {
        return deserializeFrom(ByteArrayInputStream(bytes))
    }
    
    override fun serializeTo(data: T, output: OutputStream) {
        DataOutputStream(output).use { dos ->
            writeHeader(dos)
            writeData(dos, data)
        }
    }
    
    override fun deserializeFrom(input: InputStream): T {
        return DataInputStream(input).use { dis ->
            readHeader(dis)
            readData(dis)
        }
    }
    
    protected open fun writeHeader(dos: DataOutputStream) {
        dos.writeInt(version)
    }
    
    protected open fun readHeader(dis: DataInputStream) {
        val fileVersion = dis.readInt()
        if (fileVersion > version) {
            throw VersionedSerializationException("Version mismatch: file=$fileVersion, supported=$version")
        }
    }
    
    protected abstract fun writeData(dos: DataOutputStream, data: T)
    protected abstract fun readData(dis: DataInputStream): T
    
    protected fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
    
    protected fun DataInputStream.readString(): String {
        val size = readInt()
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
    
    protected fun DataOutputStream.writeStringList(list: List<String>) {
        writeInt(list.size)
        list.forEach { writeString(it) }
    }
    
    protected fun DataInputStream.readStringList(): List<String> {
        val size = readInt()
        return (0 until size).map { readString() }
    }
    
    protected fun DataOutputStream.writeIntList(list: List<Int>) {
        writeInt(list.size)
        list.forEach { writeInt(it) }
    }
    
    protected fun DataInputStream.readIntList(): List<Int> {
        val size = readInt()
        return (0 until size).map { readInt() }
    }
    
    protected fun DataOutputStream.writeLongList(list: List<Long>) {
        writeInt(list.size)
        list.forEach { writeLong(it) }
    }
    
    protected fun DataInputStream.readLongList(): List<Long> {
        val size = readInt()
        return (0 until size).map { readLong() }
    }
    
    protected fun <E : Enum<E>> DataOutputStream.writeEnum(value: E) {
        writeString(value.name)
    }
    
    protected inline fun <reified E : Enum<E>> DataInputStream.readEnum(): E {
        val name = readString()
        return enumValueOf(name)
    }
}

class SchemaEvolutionTracker {
    private val schemas = mutableMapOf<Int, SchemaInfo>()
    
    data class SchemaInfo(
        val version: Int,
        val fields: Map<String, FieldInfo>,
        val addedFields: Set<String>,
        val removedFields: Set<String>,
        val renamedFields: Map<String, String>
    )
    
    data class FieldInfo(
        val name: String,
        val type: String,
        val nullable: Boolean,
        val defaultValue: Any? = null
    )
    
    fun registerSchema(version: Int, info: SchemaInfo) {
        schemas[version] = info
    }
    
    fun getSchema(version: Int): SchemaInfo? = schemas[version]
    
    fun getMigrationPlan(fromVersion: Int, toVersion: Int): List<FieldMigration> {
        val migrations = mutableListOf<FieldMigration>()
        
        val fromSchema = schemas[fromVersion] ?: return emptyList()
        val toSchema = schemas[toVersion] ?: return emptyList()
        
        for ((fieldName, fieldInfo) in toSchema.fields) {
            if (fieldName !in fromSchema.fields) {
                migrations.add(FieldMigration.Add(fieldName, fieldInfo.defaultValue))
            }
        }
        
        for ((oldName, newName) in toSchema.renamedFields) {
            if (oldName in fromSchema.fields) {
                migrations.add(FieldMigration.Rename(oldName, newName))
            }
        }
        
        for (fieldName in fromSchema.fields.keys) {
            if (fieldName !in toSchema.fields && fieldName !in toSchema.renamedFields.keys) {
                migrations.add(FieldMigration.Remove(fieldName))
            }
        }
        
        return migrations
    }
    
    sealed class FieldMigration {
        data class Add(val fieldName: String, val defaultValue: Any?) : FieldMigration()
        data class Remove(val fieldName: String) : FieldMigration()
        data class Rename(val oldName: String, val newName: String) : FieldMigration()
    }
}
