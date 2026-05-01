package com.xianxia.sect.taptap

import android.util.Log
import com.taptap.sdk.db.TapDB
import com.taptap.sdk.db.data.model.Event
import com.taptap.sdk.db.data.model.EventType
import com.taptap.sdk.db.data.model.WrapEvent
import org.json.JSONObject
import java.util.UUID

object TapDBManager {
    private const val TAG = "TapDBManager"

    private val instance: TapDB?
        get() = try {
            TapDB.getInstance()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get TapDB instance: ${e.message}")
            null
        }

    fun setUser(userId: String, name: String?) {
        try {
            val db = instance ?: return
            db.setUserId(userId)
            if (!name.isNullOrEmpty()) {
                db.addCommon(mapOf("user_name" to name))
            }
            Log.d(TAG, "setUser: userId=$userId, name=$name")
        } catch (e: Exception) {
            Log.e(TAG, "setUser failed: ${e.message}")
        }
    }

    fun clearUser() {
        try {
            val db = instance ?: return
            db.setUserId("")
            db.clearAllCommonProperties()
        } catch (e: Exception) {
            Log.e(TAG, "clearUser failed: ${e.message}")
        }
    }

    fun setLevel(level: Int) {
        try {
            val db = instance ?: return
            db.addCommon(mapOf("level" to level))
        } catch (e: Exception) {
            Log.e(TAG, "setLevel failed: ${e.message}")
        }
    }

    fun setServer(serverName: String) {
        try {
            val db = instance ?: return
            db.addCommon(mapOf("server" to serverName))
        } catch (e: Exception) {
            Log.e(TAG, "setServer failed: ${e.message}")
        }
    }

    fun trackEvent(eventName: String, properties: Map<String, Any> = emptyMap()) {
        try {
            val db = instance ?: return
            val json = JSONObject()
            properties.forEach { (key, value) -> json.put(key, value) }

            val event = Event(
                UUID.randomUUID().toString(),
                eventName,
                "track",
                System.currentTimeMillis(),
                json
            )
            val wrapEvent = WrapEvent.Builder()
                .setEventType(EventType.TRACK)
                .setEvent(event)
                .setUserId(db.currentUserId)
                .isAutomatically(false)
                .isCustomEvent(true)
                .build()

            db.submitEvent(wrapEvent)
            Log.d(TAG, "trackEvent: $eventName, properties=$properties")
        } catch (e: Exception) {
            Log.e(TAG, "trackEvent $eventName failed: ${e.message}")
        }
    }

    fun onCharge(
        orderId: String,
        productId: String,
        amount: Double,
        currency: String,
        payment: String
    ) {
        try {
            trackEvent(
                "#charge",
                mapOf(
                    "order_id" to orderId,
                    "product_id" to productId,
                    "amount" to amount,
                    "currency" to currency,
                    "payment" to payment
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "onCharge failed: ${e.message}")
        }
    }

    fun registerStaticProperties(properties: Map<String, Any>) {
        try {
            val db = instance ?: return
            val map = mutableMapOf<String, Any>()
            properties.forEach { (key, value) -> map[key] = value }
            db.addCommon(map)
        } catch (e: Exception) {
            Log.e(TAG, "registerStaticProperties failed: ${e.message}")
        }
    }
}
