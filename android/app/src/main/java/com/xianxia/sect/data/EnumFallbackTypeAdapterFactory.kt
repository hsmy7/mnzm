package com.xianxia.sect.data

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.IOException

class EnumFallbackTypeAdapterFactory : TypeAdapterFactory {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val rawType = type.rawType as? Class<*> ?: return null
        if (!rawType.isEnum) return null
        
        val enumConstants = rawType.enumConstants as? Array<out Enum<*>> ?: return null
        return EnumFallbackTypeAdapter(enumConstants) as TypeAdapter<T>
    }
    
    private class EnumFallbackTypeAdapter(
        private val enumConstants: Array<out Enum<*>>
    ) : TypeAdapter<Enum<*>>() {
        
        private val nameToConstant: Map<String, Enum<*>> = enumConstants.associateBy { it.name }
        private val defaultConstant: Enum<*> = enumConstants.first()
        
        @Throws(IOException::class)
        override fun write(out: JsonWriter, value: Enum<*>?) {
            if (value == null) {
                out.nullValue()
            } else {
                out.value(value.name)
            }
        }
        
        @Throws(IOException::class)
        override fun read(reader: JsonReader): Enum<*>? {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                return null
            }
            
            val name = reader.nextString()
            return nameToConstant[name] ?: run {
                android.util.Log.w(
                    "EnumFallback",
                    "Unknown enum value '$name' for ${defaultConstant::class.java.simpleName}, using default: ${defaultConstant.name}"
                )
                defaultConstant
            }
        }
    }
}
