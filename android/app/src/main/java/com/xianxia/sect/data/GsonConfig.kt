package com.xianxia.sect.data

import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.Gson
import com.google.gson.GsonBuilder

object GsonConfig {
    
    fun createGson(): Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd HH:mm:ss")
        .serializeNulls()
        .disableHtmlEscaping()
        .excludeFieldsWithModifiers(java.lang.reflect.Modifier.TRANSIENT)
        .setExclusionStrategies(KotlinInternalFieldExclusionStrategy())
        .create()
    
    fun createGsonForRoom(): Gson = GsonBuilder()
        .excludeFieldsWithModifiers(java.lang.reflect.Modifier.TRANSIENT)
        .setExclusionStrategies(KotlinInternalFieldExclusionStrategy())
        .create()
}

private class KotlinInternalFieldExclusionStrategy : ExclusionStrategy {
    override fun shouldSkipField(f: FieldAttributes): Boolean {
        return f.name.startsWith("$")
    }
    
    override fun shouldSkipClass(clazz: Class<*>): Boolean = false
}
