package com.xianxia.sect.core.util

import android.util.Log

object GameLogger {
    
    @Volatile
    private var isDebugMode = true
    
    @Volatile
    private var isProductionMode = false
    
    private val sensitivePatterns = listOf(
        Regex("""password["\s:=]+[\w\-]+""", RegexOption.IGNORE_CASE),
        Regex("""token["\s:=]+[\w\-\.]+""", RegexOption.IGNORE_CASE),
        Regex("""key["\s:=]+[\w\-]+""", RegexOption.IGNORE_CASE),
        Regex("""secret["\s:=]+[\w\-]+""", RegexOption.IGNORE_CASE),
        Regex("""\b\d{16,19}\b"""),
        Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b""")
    )
    
    private const val MASK = "***MASKED***"
    
    fun setDebugMode(debug: Boolean) {
        isDebugMode = debug
    }
    
    fun setProductionMode(production: Boolean) {
        isProductionMode = production
    }
    
    private fun sanitize(message: String): String {
        if (!isProductionMode) return message
        
        var sanitized = message
        for (pattern in sensitivePatterns) {
            sanitized = sanitized.replace(pattern, MASK)
        }
        return sanitized
    }
    
    fun e(tag: String, message: String, error: Throwable? = null) {
        val sanitizedMessage = sanitize(message)
        if (error != null) {
            Log.e(tag, sanitizedMessage, error)
        } else {
            Log.e(tag, sanitizedMessage)
        }
    }
    
    fun w(tag: String, message: String) {
        Log.w(tag, sanitize(message))
    }
    
    fun i(tag: String, message: String) {
        Log.i(tag, sanitize(message))
    }
    
    fun d(tag: String, message: String) {
        if (isDebugMode) {
            Log.d(tag, sanitize(message))
        }
    }
    
    fun v(tag: String, message: String) {
        if (isDebugMode && !isProductionMode) {
            Log.v(tag, sanitize(message))
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
    
    fun logSecure(tag: String, operation: String, success: Boolean, details: String? = null) {
        val status = if (success) "SUCCESS" else "FAILED"
        val message = buildString {
            append("[$operation] $status")
            if (details != null && !isProductionMode) {
                append(" - $details")
            }
        }
        if (success) {
            d(tag, message)
        } else {
            w(tag, message)
        }
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
