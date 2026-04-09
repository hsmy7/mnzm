package com.xianxia.sect.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "EncryptedSharedPreferences creation failed, falling back to plain SharedPreferences", e)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_LOGGED_IN, false)
        set(value) = edit { putBoolean(KEY_LOGGED_IN, value) }
    
    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = edit { putString(KEY_USER_ID, value) }
    
    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = edit { putString(KEY_USER_NAME, value) }
    
    var loginType: String?
        get() = prefs.getString(KEY_LOGIN_TYPE, null)
        set(value) = edit { putString(KEY_LOGIN_TYPE, value) }
    
    var hasAgreedPrivacy: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY_AGREED, false)
        set(value) = edit { putBoolean(KEY_PRIVACY_AGREED, value) }
    
    var complianceVerified: Boolean
        get() = prefs.getBoolean(KEY_COMPLIANCE_VERIFIED, false)
        set(value) = edit { putBoolean(KEY_COMPLIANCE_VERIFIED, value) }
    
    var unionId: String?
        get() = prefs.getString(KEY_UNION_ID, null)
        set(value) = edit { putString(KEY_UNION_ID, value) }
    
    fun saveLoginSession(userId: String, userName: String, loginType: String, unionId: String? = null) {
        edit {
            putBoolean(KEY_LOGGED_IN, true)
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_NAME, userName)
            putString(KEY_LOGIN_TYPE, loginType)
            putString(KEY_UNION_ID, unionId)
            putBoolean(KEY_COMPLIANCE_VERIFIED, false)
        }
    }
    
    fun saveComplianceVerified(unionId: String) {
        edit {
            putBoolean(KEY_COMPLIANCE_VERIFIED, true)
            putString(KEY_UNION_ID, unionId)
        }
    }
    
    fun markComplianceVerified() {
        edit {
            putBoolean(KEY_COMPLIANCE_VERIFIED, true)
        }
    }
    
    fun clearSession() {
        edit {
            putBoolean(KEY_LOGGED_IN, false)
            remove(KEY_USER_ID)
            remove(KEY_USER_NAME)
            remove(KEY_LOGIN_TYPE)
            putBoolean(KEY_COMPLIANCE_VERIFIED, false)
            remove(KEY_UNION_ID)
        }
    }

    private inline fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
    }
    
    companion object {
        private const val TAG = "SessionManager"
        private const val PREFS_NAME = "xianxia_session"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_LOGIN_TYPE = "login_type"
        private const val KEY_PRIVACY_AGREED = "privacy_agreed"
        private const val KEY_COMPLIANCE_VERIFIED = "compliance_verified"
        private const val KEY_UNION_ID = "union_id"
    }
}
