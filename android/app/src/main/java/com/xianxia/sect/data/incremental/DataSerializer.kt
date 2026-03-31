@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package com.xianxia.sect.data.incremental

import android.util.Log
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

object DataSerializer {
    private const val TAG = "DataSerializer"
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    
    private val serializerCache = ConcurrentHashMap<KClass<*>, KSerializer<*>>()

    fun <T : Any> serialize(value: T): ByteArray {
        return when (value) {
            is String -> value.toByteArray(StandardCharsets.UTF_8)
            is ByteArray -> value
            else -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val serializer = serializerCache.getOrPut(value::class) {
                        value::class.serializer()
                    } as KSerializer<T>
                    json.encodeToString(serializer, value).toByteArray(StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to serialize ${value::class.qualifiedName}: ${e.message}")
                    value.toString().toByteArray(StandardCharsets.UTF_8)
                }
            }
        }
    }

    fun <T : Any> deserialize(bytes: ByteArray, clazz: Class<T>): T {
        return when (clazz) {
            String::class.java -> bytes.toString(StandardCharsets.UTF_8) as T
            ByteArray::class.java -> bytes as T
            else -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val kClass = clazz.kotlin
                    val serializer = serializerCache.getOrPut(kClass) {
                        kClass.serializer()
                    } as KSerializer<T>
                    json.decodeFromString(serializer, bytes.toString(StandardCharsets.UTF_8))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to deserialize ${clazz.name}: ${e.message}")
                    throw e
                }
            }
        }
    }

    inline fun <reified T : Any> deserializeTyped(bytes: ByteArray): T {
        return deserialize(bytes, T::class.java)
    }
}
