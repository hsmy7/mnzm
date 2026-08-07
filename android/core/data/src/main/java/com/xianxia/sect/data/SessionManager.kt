package com.xianxia.sect.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Bugly #3107：加密 prefs 构造失败（AndroidKeyStore 主密钥损坏，ErrorCode -33）
    // 时自动恢复（删损坏密钥 + 删加密文件重建）；重建失败降级明文并持久化标记
    private val prefs: SharedPreferences = createSessionPrefs(context)
    
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

    var privacyCheckboxConfirmed: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY_CHECKBOX_CONFIRMED, false)
        set(value) = edit { putBoolean(KEY_PRIVACY_CHECKBOX_CONFIRMED, value) }
    
    var complianceVerified: Boolean
        get() = prefs.getBoolean(KEY_COMPLIANCE_VERIFIED, false)
        set(value) = edit { putBoolean(KEY_COMPLIANCE_VERIFIED, value) }
    
    var unionId: String?
        get() = prefs.getString(KEY_UNION_ID, null)
        set(value) = edit { putString(KEY_UNION_ID, value) }

    var avatar: String?
        get() = prefs.getString(KEY_AVATAR, null)
        set(value) = edit { putString(KEY_AVATAR, value) }

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        set(value) = edit { putBoolean(KEY_SOUND_ENABLED, value) }

    var musicEnabled: Boolean
        get() = prefs.getBoolean(KEY_MUSIC_ENABLED, true)
        set(value) = edit { putBoolean(KEY_MUSIC_ENABLED, value) }

    /**
     * 性能模式（三档：ENERGY_SAVING/BALANCED/PERFORMANCE，默认 BALANCED）。
     * 设备级设置，不随存档迁移；非法值由读取方回退默认。
     */
    var performanceMode: String
        get() = prefs.getString(KEY_PERFORMANCE_MODE, "BALANCED") ?: "BALANCED"
        set(value) = edit { putString(KEY_PERFORMANCE_MODE, value) }

    fun saveLoginSession(
        userId: String,
        userName: String,
        loginType: String,
        unionId: String? = null,
        avatar: String? = null
    ) {
        edit {
            putBoolean(KEY_LOGGED_IN, true)
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_NAME, userName)
            putString(KEY_LOGIN_TYPE, loginType)
            putString(KEY_UNION_ID, unionId)
            putString(KEY_AVATAR, avatar)
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
            remove(KEY_AVATAR)
        }
    }

    private inline fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
    }
    
    companion object {
        private const val TAG = "SessionManager"
        internal const val PREFS_NAME = "xianxia_session"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_LOGIN_TYPE = "login_type"
        private const val KEY_PRIVACY_AGREED = "privacy_agreed"
        private const val KEY_PRIVACY_CHECKBOX_CONFIRMED = "privacy_checkbox_confirmed"
        private const val KEY_COMPLIANCE_VERIFIED = "compliance_verified"
        private const val KEY_UNION_ID = "union_id"
        private const val KEY_AVATAR = "avatar"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_MUSIC_ENABLED = "music_enabled"
        private const val KEY_PERFORMANCE_MODE = "performance_mode"

        // Bugly #3107：明文降级标记（写入明文 fallback prefs，防止每次启动
        // 重复失败的 Keystore 流程）；MASTER_KEY_ALIAS 必须与 MasterKey.Builder
        // 默认 alias 一致（库内部拼接 "_androidx_security_master_key_"）
        internal const val KEY_ENCRYPTION_DOWNGRADED = "encryption_downgraded"
        internal const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

        /** 默认 MasterKey 构建器（测试可注入失败替代） */
        internal fun defaultMasterKey(context: Context): MasterKey =
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

        /**
         * 创建会话偏好存储（Bugly #3107 修复）。
         *
         * 1. 明文 fallback 已有降级标记 → 直接返回明文（避免每次启动重复失败的 Keystore 流程）
         * 2. 加密创建失败（主密钥损坏 ErrorCode -33）→ 删除损坏密钥 + 删除加密文件 → 重建加密
         * 3. 重建仍失败 → 明文降级并持久化标记
         *
         * 数据敏感度低（登录态/隐私同意/音效开关），密钥损坏时数据本就不可读，
         * 降级丢失可接受（需重新同意隐私、重新登录）。
         */
        internal fun createSessionPrefs(
            context: Context,
            buildMasterKey: (Context) -> MasterKey = ::defaultMasterKey
        ): SharedPreferences {
            val fallback = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (fallback.getBoolean(KEY_ENCRYPTION_DOWNGRADED, false)) return fallback
            // runCatching 捕获创建失败（KeyStoreException 等安全异常 / IOException）——
            // 同步非协程上下文，无 CancellationException 场景
            val encrypted = runCatching { buildEncrypted(context, buildMasterKey) }
            return if (encrypted.isSuccess) {
                encrypted.getOrThrow()
            } else {
                Log.w(TAG, "加密 prefs 创建失败（主密钥损坏？），尝试删除密钥重建", encrypted.exceptionOrNull())
                val recovered = runCatching {
                    recoverKeystore(context)
                    buildEncrypted(context, buildMasterKey)
                }
                if (recovered.isSuccess) {
                    recovered.getOrThrow()
                } else {
                    Log.e(TAG, "Keystore 恢复失败，降级为明文存储", recovered.exceptionOrNull())
                    fallback.edit().putBoolean(KEY_ENCRYPTION_DOWNGRADED, true).apply()
                    fallback
                }
            }
        }

        private fun buildEncrypted(
            context: Context,
            buildMasterKey: (Context) -> MasterKey
        ): SharedPreferences {
            val masterKey = buildMasterKey(context)
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        /** 清除损坏密钥与加密文件（恢复路径，任何失败由调用方降级兜底） */
        private fun recoverKeystore(context: Context) {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            ks.deleteEntry(MASTER_KEY_ALIAS)
            context.deleteSharedPreferences(PREFS_NAME)
        }
    }
}
