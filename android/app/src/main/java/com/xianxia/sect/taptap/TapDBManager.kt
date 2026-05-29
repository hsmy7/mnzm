package com.xianxia.sect.taptap

import android.app.Application
import android.util.Log
import com.taptap.sdk.core.TapTapEvent
import com.taptap.sdk.core.TapTapPurchasedEvent
import com.taptap.sdk.db.TapDB
import com.taptap.sdk.db.biz.gameplay.GameDurationService
import org.json.JSONObject

object TapDBManager {
    private const val TAG = "TapDBManager"

    private var gameDurationService: GameDurationService? = null

    private val dbInstance: TapDB?
        get() = try {
            TapDB.getInstance()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get TapDB instance: ${e.message}")
            null
        }

    fun startGameDurationTracking(app: Application) {
        try {
            gameDurationService = GameDurationService.Builder(app).build()
            Log.d(TAG, "Game duration tracking started")
        } catch (e: Exception) {
            Log.e(TAG, "startGameDurationTracking failed: ${e.message}")
        }
    }

    fun stopGameDurationTracking() {
        try {
            gameDurationService = null
            Log.d(TAG, "Game duration tracking stopped")
        } catch (e: Exception) {
            Log.e(TAG, "stopGameDurationTracking failed: ${e.message}")
        }
    }

    fun setUser(userId: String, name: String?) {
        try {
            val properties = JSONObject()
            if (!name.isNullOrEmpty()) {
                properties.put("user_name", name)
            }
            TapTapEvent.setUserId(userId, properties)
            Log.d(TAG, "setUser: userId=$userId, name=$name")
        } catch (e: Exception) {
            Log.e(TAG, "setUser failed: ${e.message}")
        }
    }

    fun clearUser() {
        try {
            TapTapEvent.clearUser()
            dbInstance?.clearAllCommonProperties()
        } catch (e: Exception) {
            Log.e(TAG, "clearUser failed: ${e.message}")
        }
    }

    fun setLevel(level: Int) {
        try {
            dbInstance?.addCommon(mapOf("level" to level))
        } catch (e: Exception) {
            Log.e(TAG, "setLevel failed: ${e.message}")
        }
    }

    fun setServer(serverName: String) {
        try {
            dbInstance?.addCommon(mapOf("server" to serverName))
        } catch (e: Exception) {
            Log.e(TAG, "setServer failed: ${e.message}")
        }
    }

    fun trackEvent(eventName: String, properties: Map<String, Any> = emptyMap()) {
        try {
            val json = JSONObject()
            properties.forEach { (key, value) -> json.put(key, value) }
            TapTapEvent.logEvent(eventName, json)
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
            val purchasedEvent = TapTapPurchasedEvent(
                orderId,
                productId,
                amount,
                currency,
                payment,
                JSONObject()
            )
            TapTapEvent.logPurchasedEvent(purchasedEvent)
            Log.d(TAG, "onCharge: orderId=$orderId, product=$productId, amount=$amount")
        } catch (e: Exception) {
            Log.e(TAG, "onCharge failed: ${e.message}")
        }
    }

    fun registerStaticProperties(properties: Map<String, Any>) {
        try {
            dbInstance?.addCommon(properties)
        } catch (e: Exception) {
            Log.e(TAG, "registerStaticProperties failed: ${e.message}")
        }
    }
}
