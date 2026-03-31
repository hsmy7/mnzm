package com.xianxia.sect.data.schema

import android.util.Log
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object SchemaConfig {
    const val CURRENT_SCHEMA_VERSION = 1
    const val HEADER_SIZE = 8
    const val MAGIC_HEADER: Short = 0x5343
}

enum class FieldType(val code: Int) {
    INT(0),
    LONG(1),
    STRING(2),
    BOOLEAN(3),
    FLOAT(4),
    DOUBLE(5),
    BYTES(6),
    LIST(10),
    MAP(20),
    OBJECT(30)
}

data class SchemaField(
    val id: Int,
    val name: String,
    val type: FieldType,
    val deprecated: Boolean = false,
    val defaultValue: Any? = null,
    val sinceVersion: Int = 0,
    val removedInVersion: Int = Int.MAX_VALUE
)

data class SchemaVersion(
    val version: Int,
    val fields: Map<String, SchemaField>,
    val checksum: Long = 0
) {
    val isCompatible: Boolean
        get() = fields.isNotEmpty()
    
    fun isCompatibleWith(other: SchemaVersion): Boolean {
        if (other.version != version) return false
        for ((name, field) in other.fields) {
            val myField = fields[name]
            if (myField == null) {
                if (!other.fields.containsKey(name)) return false
            } else {
                if (myField.deprecated && myField.sinceVersion > version) continue
                val otherField = other.fields[name]
                if (otherField == null) return false
                if (otherField.sinceVersion > myField.sinceVersion) continue
            }
        }
        return true
    }
    
    fun getAddedFields(): List<SchemaField> {
        return fields.values.filter { it.sinceVersion == version }.toList()
    }
    
    fun getRemovedFields(): List<SchemaField> {
        return fields.values.filter { it.deprecated && it.sinceVersion > version }.toList()
    }
    
    fun hasField(name: String): Boolean = fields.containsKey(name)
    
    fun getField(name: String): SchemaField? {
        return fields[name]
    }
}

class SchemaEvolutionManager {
    companion object {
        private const val TAG = "SchemaEvolution"
    }
    
    private val schemas = ConcurrentHashMap<Int, SchemaVersion>()
    private val migrations = CopyOnWriteArrayList<MigrationStep>()
    private val currentVersion: Int = SchemaConfig.CURRENT_SCHEMA_VERSION
    
    fun registerSchema(schema: SchemaVersion) {
        schemas[schema.version] = schema
        Log.d(TAG, "Registered schema version ${schema.version} with ${schema.fields.size} fields")
    }
    
    fun registerMigration(fromVersion: Int, toVersion: Int, migration: MigrationStep): Boolean {
        val key = "$fromVersion->$toVersion"
        if (migrations.any { it.fromVersion == fromVersion && it.toVersion == toVersion }) {
            Log.w(TAG, "Migration $key already registered")
            return false
        }
        migrations.add(migration)
        Log.d(TAG, "Registered migration $key")
        return true
    }
    
    fun evolve(data: ByteArray, targetVersion: Int = currentVersion, clazz: Class<*>? = null): ByteArray {
        val header = readHeader(data)
        if (header == null) {
            Log.w(TAG, "Invalid data: no header found")
            return data
        }
        
        var currentSourceVersion = header.version
        if (currentSourceVersion == targetVersion) {
            return data
        }
        
        var result = data
        var currentSchema = schemas[currentSourceVersion]
        
        while (currentSchema != null && currentSourceVersion < targetVersion) {
            val nextVersion = currentSourceVersion + 1
            val migration = findMigration(currentSourceVersion, nextVersion)
            
            if (migration != null) {
                result = migration.transform(result)
                currentSourceVersion = nextVersion
                currentSchema = schemas[currentSourceVersion]
                Log.d(TAG, "Migrated from version ${currentSourceVersion - 1} to $currentSourceVersion")
            } else {
                Log.w(TAG, "No migration found from $currentSourceVersion to $nextVersion")
                break
            }
        }
        
        return result
    }
    
    fun writeHeader(buffer: ByteBuffer, data: ByteArray, version: Int) {
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(SchemaConfig.MAGIC_HEADER)
        buffer.putInt(version)
        buffer.putInt(data.size)
    }
    
    private fun readHeader(data: ByteArray): SchemaHeader? {
        if (data.size < SchemaConfig.HEADER_SIZE) {
            return null
        }
        
        val buffer = ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.short
        if (magic != SchemaConfig.MAGIC_HEADER) {
            return null
        }
        
        val version = buffer.int
        val size = buffer.int
        
        return SchemaHeader(version, size)
    }
    
    private fun findMigration(fromVersion: Int, toVersion: Int): MigrationStep? {
        return migrations.find { it.fromVersion == fromVersion && it.toVersion == toVersion }
    }
    
    fun getCurrentVersion(): Int = currentVersion
    
    fun getSchema(version: Int): SchemaVersion? = schemas[version]
    
    fun getAllSchemas(): Map<Int, SchemaVersion> = schemas.toMap()
    
    private data class SchemaHeader(val version: Int, val size: Int)
}

interface MigrationStep {
    val fromVersion: Int
    val toVersion: Int
    fun transform(data: ByteArray): ByteArray
}

class AddFieldMigration(
    override val fromVersion: Int,
    override val toVersion: Int,
    private val fieldName: String,
    private val fieldType: FieldType,
    private val defaultValue: Any
) : MigrationStep {
    override fun transform(data: ByteArray): ByteArray {
        val bais = ByteArrayInputStream(data)
        val dis = DataInputStream(bais)
        
        val existingData = ByteArray(dis.available())
        dis.readFully(existingData)
        
        val estimatedSize = getEstimatedSize(fieldType)
        val newData = ByteArray(existingData.size + estimatedSize + fieldName.toByteArray(Charsets.UTF_8).size + 1)
        
        val baos = ByteArrayOutputStream(newData.size)
        val dos = DataOutputStream(baos)
        dos.write(existingData)
        writeField(dos, fieldName, fieldType, defaultValue)
        
        return baos.toByteArray()
    }
    
    private fun getEstimatedSize(fieldType: FieldType): Int {
        return when (fieldType) {
            FieldType.INT -> 4
            FieldType.LONG -> 8
            FieldType.FLOAT -> 4
            FieldType.DOUBLE -> 8
            FieldType.BOOLEAN -> 1
            FieldType.STRING -> 4
            else -> 8
        }
    }
    
    private fun writeField(dos: DataOutputStream, name: String, type: FieldType, value: Any?) {
        dos.writeUTF(name)
        dos.writeByte(type.ordinal)
        when (type) {
            FieldType.INT -> dos.writeInt(value as Int)
            FieldType.LONG -> dos.writeLong(value as Long)
            FieldType.FLOAT -> dos.writeFloat(value as Float)
            FieldType.DOUBLE -> dos.writeDouble(value as Double)
            FieldType.BOOLEAN -> dos.writeBoolean(value as Boolean)
            FieldType.STRING -> {
                val bytes = (value as String).toByteArray(Charsets.UTF_8)
                dos.writeInt(bytes.size)
                dos.write(bytes)
            }
            FieldType.BYTES -> {
                val bytes = value as ByteArray
                dos.writeInt(bytes.size)
                dos.write(bytes)
            }
            FieldType.LIST -> {
                val list = value as List<*>
                dos.writeInt(list.size)
                for (item in list) {
                    writeFieldValue(dos, item)
                }
            }
            FieldType.MAP -> {
                val map = value as Map<*, *>
                dos.writeInt(map.size)
                for ((key, v) in map) {
                    writeFieldValue(dos, key)
                    writeFieldValue(dos, v)
                }
            }
            FieldType.OBJECT -> {
                val json = GsonConfig.gson.toJson(value)
                val bytes = json.toByteArray(Charsets.UTF_8)
                dos.writeInt(bytes.size)
                dos.write(bytes)
            }
        }
    }
    
    private fun writeFieldValue(dos: DataOutputStream, value: Any?) {
        when (value) {
            null -> dos.writeByte(0)
            is Int -> writeField(dos, "int", FieldType.INT, value)
            is Long -> writeField(dos, "long", FieldType.LONG, value)
            is Float -> writeField(dos, "float", FieldType.FLOAT, value)
            is Double -> writeField(dos, "double", FieldType.DOUBLE, value)
            is Boolean -> writeField(dos, "boolean", FieldType.BOOLEAN, value)
            is String -> writeField(dos, "string", FieldType.STRING, value)
            is ByteArray -> writeField(dos, "bytes", FieldType.BYTES, value)
            is List<*> -> writeField(dos, "list", FieldType.LIST, value)
            is Map<*, *> -> writeField(dos, "map", FieldType.MAP, value)
            else -> writeField(dos, "object", FieldType.OBJECT, value)
        }
    }
}

class RemoveFieldMigration(
    override val fromVersion: Int,
    override val toVersion: Int,
    private val fieldName: String
) : MigrationStep {
    companion object {
        private const val TAG = "RemoveFieldMigration"
    }
    
    override fun transform(data: ByteArray): ByteArray {
        return try {
            val bais = ByteArrayInputStream(data)
            val dis = DataInputStream(bais)
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            
            val header = dis.readUTF()
            dos.writeUTF(header)
            
            val version = dis.readInt()
            dos.writeInt(toVersion)
            
            val fieldCount = dis.readInt()
            var newFieldCount = 0
            
            val fields = mutableListOf<Pair<String, Any?>>()
            
            repeat(fieldCount) {
                val name = dis.readUTF()
                val typeOrdinal = dis.readInt()
                val type = FieldType.values().getOrNull(typeOrdinal) ?: FieldType.OBJECT
                
                val value = when (type) {
                    FieldType.INT -> dis.readInt()
                    FieldType.LONG -> dis.readLong()
                    FieldType.FLOAT -> dis.readFloat()
                    FieldType.DOUBLE -> dis.readDouble()
                    FieldType.BOOLEAN -> dis.readBoolean()
                    FieldType.STRING -> dis.readUTF()
                    FieldType.BYTES -> {
                        val len = dis.readInt()
                        ByteArray(len).also { dis.readFully(it) }
                    }
                    else -> null
                }
                
                if (name != fieldName) {
                    fields.add(name to value)
                    newFieldCount++
                }
            }
            
            dos.writeInt(newFieldCount)
            fields.forEach { (name, value) ->
                when (value) {
                    is Int -> {
                        dos.writeUTF(name)
                        dos.writeInt(FieldType.INT.ordinal)
                        dos.writeInt(value)
                    }
                    is Long -> {
                        dos.writeUTF(name)
                        dos.writeInt(FieldType.LONG.ordinal)
                        dos.writeLong(value)
                    }
                    is Float -> {
                        dos.writeUTF(name)
                        dos.writeInt(FieldType.FLOAT.ordinal)
                        dos.writeFloat(value)
                    }
                    is Double -> {
                        dos.writeUTF(name)
                        dos.writeInt(FieldType.DOUBLE.ordinal)
                        dos.writeDouble(value)
                    }
                    is Boolean -> {
                        dos.writeUTF(name)
                        dos.writeInt(FieldType.BOOLEAN.ordinal)
                        dos.writeBoolean(value)
                    }
                    is String -> {
                        dos.writeUTF(name)
                        dos.writeInt(FieldType.STRING.ordinal)
                        dos.writeUTF(value)
                    }
                    is ByteArray -> {
                        dos.writeUTF(name)
                        dos.writeInt(FieldType.BYTES.ordinal)
                        dos.writeInt(value.size)
                        dos.write(value)
                    }
                    else -> {
                        dos.writeUTF(name)
                        dos.writeInt(FieldType.OBJECT.ordinal)
                    }
                }
            }
            
            dos.flush()
            baos.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove field $fieldName", e)
            data
        }
    }
}

class RenameFieldMigration(
    override val fromVersion: Int,
    override val toVersion: Int,
    private val oldName: String,
    private val newName: String
) : MigrationStep {
    companion object {
        private const val TAG = "RenameFieldMigration"
    }
    
    override fun transform(data: ByteArray): ByteArray {
        return try {
            val bais = ByteArrayInputStream(data)
            val dis = DataInputStream(bais)
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            
            val header = dis.readUTF()
            dos.writeUTF(header)
            
            val version = dis.readInt()
            dos.writeInt(toVersion)
            
            val fieldCount = dis.readInt()
            dos.writeInt(fieldCount)
            
            repeat(fieldCount) {
                val name = dis.readUTF()
                val typeOrdinal = dis.readInt()
                val type = FieldType.values().getOrNull(typeOrdinal) ?: FieldType.OBJECT
                
                val outputName = if (name == oldName) newName else name
                dos.writeUTF(outputName)
                dos.writeInt(typeOrdinal)
                
                when (type) {
                    FieldType.INT -> dos.writeInt(dis.readInt())
                    FieldType.LONG -> dos.writeLong(dis.readLong())
                    FieldType.FLOAT -> dos.writeFloat(dis.readFloat())
                    FieldType.DOUBLE -> dos.writeDouble(dis.readDouble())
                    FieldType.BOOLEAN -> dos.writeBoolean(dis.readBoolean())
                    FieldType.STRING -> {
                        val str = dis.readUTF()
                        dos.writeUTF(str)
                    }
                    FieldType.BYTES -> {
                        val len = dis.readInt()
                        dos.writeInt(len)
                        val bytes = ByteArray(len)
                        dis.readFully(bytes)
                        dos.write(bytes)
                    }
                    else -> {}
                }
            }
            
            dos.flush()
            baos.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename field $oldName to $newName", e)
            data
        }
    }
}

class TypeChangeMigration(
    override val fromVersion: Int,
    override val toVersion: Int,
    private val fieldName: String,
    private val converter: (Any?) -> Any?
) : MigrationStep {
    companion object {
        private const val TAG = "TypeChangeMigration"
    }
    
    override fun transform(data: ByteArray): ByteArray {
        return try {
            val bais = ByteArrayInputStream(data)
            val dis = DataInputStream(bais)
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            
            val header = dis.readUTF()
            dos.writeUTF(header)
            
            val version = dis.readInt()
            dos.writeInt(toVersion)
            
            val fieldCount = dis.readInt()
            dos.writeInt(fieldCount)
            
            repeat(fieldCount) {
                val name = dis.readUTF()
                val typeOrdinal = dis.readInt()
                val type = FieldType.values().getOrNull(typeOrdinal) ?: FieldType.OBJECT
                
                dos.writeUTF(name)
                
                if (name == fieldName) {
                    val oldValue = when (type) {
                        FieldType.INT -> dis.readInt()
                        FieldType.LONG -> dis.readLong()
                        FieldType.FLOAT -> dis.readFloat()
                        FieldType.DOUBLE -> dis.readDouble()
                        FieldType.BOOLEAN -> dis.readBoolean()
                        FieldType.STRING -> dis.readUTF()
                        else -> null
                    }
                    
                    val newValue = converter(oldValue)
                    when (newValue) {
                        is Int -> {
                            dos.writeInt(FieldType.INT.ordinal)
                            dos.writeInt(newValue)
                        }
                        is Long -> {
                            dos.writeInt(FieldType.LONG.ordinal)
                            dos.writeLong(newValue)
                        }
                        is Float -> {
                            dos.writeInt(FieldType.FLOAT.ordinal)
                            dos.writeFloat(newValue)
                        }
                        is Double -> {
                            dos.writeInt(FieldType.DOUBLE.ordinal)
                            dos.writeDouble(newValue)
                        }
                        is Boolean -> {
                            dos.writeInt(FieldType.BOOLEAN.ordinal)
                            dos.writeBoolean(newValue)
                        }
                        is String -> {
                            dos.writeInt(FieldType.STRING.ordinal)
                            dos.writeUTF(newValue)
                        }
                        else -> {
                            dos.writeInt(FieldType.OBJECT.ordinal)
                        }
                    }
                } else {
                    dos.writeInt(typeOrdinal)
                    when (type) {
                        FieldType.INT -> dos.writeInt(dis.readInt())
                        FieldType.LONG -> dos.writeLong(dis.readLong())
                        FieldType.FLOAT -> dos.writeFloat(dis.readFloat())
                        FieldType.DOUBLE -> dos.writeDouble(dis.readDouble())
                        FieldType.BOOLEAN -> dos.writeBoolean(dis.readBoolean())
                        FieldType.STRING -> dos.writeUTF(dis.readUTF())
                        FieldType.BYTES -> {
                            val len = dis.readInt()
                            dos.writeInt(len)
                            val bytes = ByteArray(len)
                            dis.readFully(bytes)
                            dos.write(bytes)
                        }
                        else -> {}
                    }
                }
            }
            
            dos.flush()
            baos.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert type for field $fieldName", e)
            data
        }
    }
}

object GsonConfig {
    val gson: Gson = Gson()
}
