package com.xianxia.sect.core.util

/**
 * Analytics event tracking interface.
 * Implemented by the app layer (e.g., TapDB) and injected into engine services.
 */
interface AnalyticsTracker {
    fun trackEvent(eventName: String, properties: Map<String, Any> = emptyMap())
}
