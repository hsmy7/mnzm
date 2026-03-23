package com.xianxia.sect.core.util

import android.util.Log

object GameLogger {
    
    @Volatile
    private var isDebugMode = true
    
    fun setDebugMode(debug: Boolean) {
        isDebugMode = debug
    }
    
    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) {
            Log.e(tag, message, error)
        } else {
            Log.e(tag, message)
        }
    }
    
    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }
    
    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }
    
    fun d(tag: String, message: String) {
        if (isDebugMode) {
            Log.d(tag, message)
        }
    }
    
    fun e(tag: String, message: String, context: ErrorContext, error: Throwable? = null) {
        val fullMessage = buildContextMessage(message, context)
        e(tag, fullMessage, error)
    }
    
    fun w(tag: String, message: String, context: ErrorContext) {
        val fullMessage = buildContextMessage(message, context)
        w(tag, fullMessage)
    }
    
    private fun buildContextMessage(message: String, context: ErrorContext): String {
        val parts = mutableListOf<String>()
        context.year?.let { parts.add("Year=$it") }
        context.month?.let { parts.add("Month=$it") }
        context.sectName?.let { parts.add("Sect=$it") }
        context.operation?.let { parts.add("Op=$it") }
        
        return if (parts.isEmpty()) message else "$message - ${parts.joinToString(", ")}"
    }
}

data class ErrorContext(
    val year: Int? = null,
    val month: Int? = null,
    val sectName: String? = null,
    val operation: String? = null
) {
    companion object {
        fun empty() = ErrorContext()
        
        fun of(year: Int? = null, month: Int? = null, sectName: String? = null, operation: String? = null) =
            ErrorContext(year, month, sectName, operation)
    }
}
