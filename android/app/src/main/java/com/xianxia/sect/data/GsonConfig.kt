package com.xianxia.sect.data

import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.Gson
import com.google.gson.GsonBuilder

object GsonConfig {
    
    val gson: Gson by lazy { createGson() }
    
    fun createGson(): Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd HH:mm:ss")
        .serializeNulls()
        .disableHtmlEscaping()
        .excludeFieldsWithModifiers(java.lang.reflect.Modifier.TRANSIENT)
        .setExclusionStrategies(KotlinInternalFieldExclusionStrategy())
        .registerTypeAdapterFactory(EnumFallbackTypeAdapterFactory())
        .create()
    
    fun createGsonForRoom(): Gson = GsonBuilder()
        .excludeFieldsWithModifiers(java.lang.reflect.Modifier.TRANSIENT)
        .setExclusionStrategies(KotlinInternalFieldExclusionStrategy())
        .registerTypeAdapterFactory(EnumFallbackTypeAdapterFactory())
        .create()
}

private class KotlinInternalFieldExclusionStrategy : ExclusionStrategy {
    private val computedPropertyNames = setOf(
        "displayTime",
        "isPlayerProtected",
        "playerProtectionRemainingYears",
        "saveTime",
        "Companion"
    )

    override fun shouldSkipField(f: FieldAttributes): Boolean {
        if (f.name.startsWith("$")) return true
        return computedPropertyNames.contains(f.name)
    }

    override fun shouldSkipClass(clazz: Class<*>): Boolean = false
}
