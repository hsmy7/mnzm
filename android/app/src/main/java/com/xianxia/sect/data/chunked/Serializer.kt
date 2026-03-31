package com.xianxia.sect.data.chunked

import android.util.Log
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlinx.serialization.builtins.ListSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

interface Serializer {
    fun <T : Any> serialize(data: T): ByteArray
    fun <T : Any> deserialize(data: ByteArray, clazz: Class<T>): T
    fun <T : Any> serializeList(data: List<T>, elementClass: Class<T>): ByteArray
    fun <T : Any> deserializeList(data: ByteArray, elementClass: Class<T>): List<T>
    val name: String
    val formatVersion: Int
}

class KotlinJsonSerializer : Serializer {
    companion object {
        private const val TAG = "KotlinJsonSerializer"
        private const val CURRENT_FORMAT_VERSION = 2
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    override val name: String = "KOTLIN_JSON"
    override val formatVersion: Int = CURRENT_FORMAT_VERSION

    override fun <T : Any> serialize(data: T): ByteArray {
        return try {
            @OptIn(InternalSerializationApi::class)
            @Suppress("UNCHECKED_CAST")
            val serializer = data::class.serializer() as KSerializer<T>
            val jsonStr = json.encodeToString(serializer, data)
            jsonStr.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize ${data::class.java.simpleName}", e)
            throw e
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> deserialize(data: ByteArray, clazz: Class<T>): T {
        return try {
            val jsonStr = String(data, Charsets.UTF_8)
            @OptIn(InternalSerializationApi::class)
            val serializer = clazz.kotlin.serializer() as KSerializer<T>
            json.decodeFromString(serializer, jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize ${clazz.simpleName}", e)
            throw e
        }
    }

    override fun <T : Any> serializeList(data: List<T>, elementClass: Class<T>): ByteArray {
        return try {
            if (data.isEmpty()) {
                return "[]".toByteArray(Charsets.UTF_8)
            }
            @OptIn(InternalSerializationApi::class)
            @Suppress("UNCHECKED_CAST")
            val listSerializer = ListSerializer(elementClass.kotlin.serializer() as KSerializer<T>)
            val jsonStr = json.encodeToString(listSerializer, data)
            jsonStr.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize list", e)
            throw e
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> deserializeList(data: ByteArray, elementClass: Class<T>): List<T> {
        return try {
            val jsonStr = String(data, Charsets.UTF_8)
            @OptIn(InternalSerializationApi::class)
            val listSerializer = ListSerializer(elementClass.kotlin.serializer() as KSerializer<T>)
            json.decodeFromString(listSerializer, jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize list of ${elementClass.simpleName}", e)
            throw e
        }
    }
}

class ProtobufSerializer : Serializer {
    companion object {
        private const val TAG = "ProtobufSerializer"
        private const val CURRENT_FORMAT_VERSION = 1
    }
    
    @OptIn(ExperimentalSerializationApi::class)
    private val protoBuf = kotlinx.serialization.protobuf.ProtoBuf {
        encodeDefaults = true
    }

    override val name: String = "PROTOBUF"
    override val formatVersion: Int = CURRENT_FORMAT_VERSION

    override fun <T : Any> serialize(data: T): ByteArray {
        return try {
            @OptIn(InternalSerializationApi::class)
            @Suppress("UNCHECKED_CAST")
            val serializer = data::class.serializer() as KSerializer<T>
            protoBuf.encodeToByteArray(serializer, data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize ${data::class.java.simpleName}", e)
            throw e
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> deserialize(data: ByteArray, clazz: Class<T>): T {
        return try {
            @OptIn(InternalSerializationApi::class)
            val serializer = clazz.kotlin.serializer() as KSerializer<T>
            protoBuf.decodeFromByteArray(serializer, data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize ${clazz.simpleName}", e)
            throw e
        }
    }

    override fun <T : Any> serializeList(data: List<T>, elementClass: Class<T>): ByteArray {
        return try {
            @OptIn(InternalSerializationApi::class)
            @Suppress("UNCHECKED_CAST")
            val listSerializer = ListSerializer(elementClass.kotlin.serializer() as KSerializer<T>)
            protoBuf.encodeToByteArray(listSerializer, data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize list", e)
            throw e
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> deserializeList(data: ByteArray, elementClass: Class<T>): List<T> {
        return try {
            @OptIn(InternalSerializationApi::class)
            val listSerializer = ListSerializer(elementClass.kotlin.serializer() as KSerializer<T>)
            protoBuf.decodeFromByteArray(listSerializer, data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize list of ${elementClass.simpleName}", e)
            throw e
        }
    }
}

class CompositeSerializer(
    private val primary: Serializer,
    private val fallback: Serializer
) : Serializer {
    override val name: String = "${primary.name}+${fallback.name}"
    override val formatVersion: Int = primary.formatVersion

    override fun <T : Any> serialize(data: T): ByteArray {
        return try {
            primary.serialize(data)
        } catch (e: Exception) {
            fallback.serialize(data)
        }
    }

    override fun <T : Any> deserialize(data: ByteArray, clazz: Class<T>): T {
        return try {
            primary.deserialize(data, clazz)
        } catch (e: Exception) {
            fallback.deserialize(data, clazz)
        }
    }

    override fun <T : Any> serializeList(data: List<T>, elementClass: Class<T>): ByteArray {
        return try {
            primary.serializeList(data, elementClass)
        } catch (e: Exception) {
            fallback.serializeList(data, elementClass)
        }
    }

    override fun <T : Any> deserializeList(data: ByteArray, elementClass: Class<T>): List<T> {
        return try {
            primary.deserializeList(data, elementClass)
        } catch (e: Exception) {
            fallback.deserializeList(data, elementClass)
        }
    }
}

class BinaryStreamSerializer {
    companion object {
        private const val MAGIC_HEADER = 0x42534B
        private const val VERSION = 1
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    fun <T : Any> serializeToStream(data: T, output: OutputStream) {
        val typeName = data::class.qualifiedName ?: "Unknown"
        val typeNameBytes = typeName.toByteArray(Charsets.UTF_8)
        @OptIn(InternalSerializationApi::class)
        @Suppress("UNCHECKED_CAST")
        val serializer = data::class.serializer() as KSerializer<T>
        val jsonStr = json.encodeToString(serializer, data)
        val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
        
        output.write(MAGIC_HEADER)
        output.write(VERSION)
        output.write(typeNameBytes.size)
        output.write(typeNameBytes)
        output.write(jsonBytes.size)
        output.write(jsonBytes)
    }
    
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> deserializeFromStream(input: InputStream, clazz: Class<T>): T? {
        val magic = input.read()
        if (magic != MAGIC_HEADER) {
            Log.w("BinaryStreamSerializer", "Invalid magic header: $magic")
            return null
        }
        
        val version = input.read()
        if (version != VERSION) {
            Log.w("BinaryStreamSerializer", "Unsupported version: $version")
            return null
        }
        
        val typeNameLen = input.read()
        val typeNameBytes = ByteArray(typeNameLen)
        input.read(typeNameBytes)
        val typeName = String(typeNameBytes, Charsets.UTF_8)
        
        val jsonLen = input.read()
        val jsonBytes = ByteArray(jsonLen)
        input.read(jsonBytes)
        val jsonStr = String(jsonBytes, Charsets.UTF_8)
        
        return try {
            @OptIn(InternalSerializationApi::class)
            val serializer = clazz.kotlin.serializer() as KSerializer<T>
            json.decodeFromString(serializer, jsonStr)
        } catch (e: Exception) {
            Log.e("BinaryStreamSerializer", "Failed to deserialize $typeName", e)
            null
        }
    }
}
