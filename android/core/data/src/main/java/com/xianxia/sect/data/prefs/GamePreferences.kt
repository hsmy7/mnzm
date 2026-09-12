package com.xianxia.sect.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.tencent.mmkv.MMKV
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一偏好存储封装（MMKV 实现）。
 *
 * ## 背景
 *
 * 普通业务偏好统一存于 MMKV——MMKV 官方支持 KMP，是 iOS 迁移的就绪方案；
 * SharedPreferences/DataStore 均为 Android 独占，不作为业务偏好存储引入。
 *
 * ## 语义
 *
 * - 单实例 `game_prefs` + 调用方自有键名（与旧 SharedPreferences 键名一致，
 *   一次性迁移后旧值无缝延续）
 * - 读取：MMKV 未就绪（初始化失败降级场景）时返回默认值
 * - 写入：MMKV 未就绪时记录警告并丢弃（写入点均在 Activity 交互阶段，
 *   Application.onCreate 后台初始化早已完成；丢失仅发生在初始化失败的
 *   极端降级场景，影响面为广告冷却/引导提示级轻量状态，不涉及存档）
 * - 单元测试：MMKV native 库在 Robolectric 沙箱不可用——依赖本类的测试
 *   注入内存 Fake [KeyValueStore]，本类自身仅以接口契约参与
 *
 * ## 豁免清单（有意保留 SharedPreferences，不迁入本封装）
 *
 * | 组件 | 豁免理由 |
 * |------|---------|
 * | `SessionManager` | EncryptedSharedPreferences 账户信息加密（AES-256），安全设计不可降级 |
 * | `SecureKeyManager` | 密钥托管 prefs，加密安全边界 |
 * | `CrashHandler` / `CrashRecoveryEngine` | 崩溃恢复标记需在 MMKV 初始化前可用（零初始化依赖），早期崩溃写入路径 |
 * | `TapDBManager`（时长追踪） | 第三方 SDK `PrefsGameDurationStorage` 接口固定 SharedPreferences，非自有偏好 |
 */
@Singleton
class GamePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) : KeyValueStore {
    // SDK 边界全量兜底
    @Suppress("TooGenericExceptionCaught")
    private val kv: MMKV?
        get() = try {
            MMKV.mmkvWithID(INSTANCE_ID)
        } catch (e: Throwable) {
            Log.e(TAG, "MMKV access failed (not initialized?)", e)
            null
        }

    // ── 类型化读取（默认值兜底） ──

    override fun contains(key: String): Boolean =
        kv?.containsKey(key) ?: false

    override fun getBoolean(key: String, default: Boolean): Boolean =
        kv?.decodeBool(key, default) ?: default

    override fun getString(key: String, default: String?): String? =
        kv?.decodeString(key, default) ?: default

    override fun getInt(key: String, default: Int): Int =
        kv?.decodeInt(key, default) ?: default

    override fun getLong(key: String, default: Long): Long =
        kv?.decodeLong(key, default) ?: default

    override fun getFloat(key: String, default: Float): Float =
        kv?.decodeFloat(key, default) ?: default

    // ── 类型化写入 ──

    override fun putBoolean(key: String, value: Boolean) {
        kv?.encode(key, value) ?: logWriteLoss(key)
    }

    override fun putString(key: String, value: String) {
        kv?.encode(key, value) ?: logWriteLoss(key)
    }

    override fun putInt(key: String, value: Int) {
        kv?.encode(key, value) ?: logWriteLoss(key)
    }

    override fun putLong(key: String, value: Long) {
        kv?.encode(key, value) ?: logWriteLoss(key)
    }

    override fun putFloat(key: String, value: Float) {
        kv?.encode(key, value) ?: logWriteLoss(key)
    }

    override fun remove(key: String) {
        kv?.removeValueForKey(key)
    }

    override fun clearAll() {
        kv?.clearAll()
    }

    private fun logWriteLoss(key: String) {
        Log.w(TAG, "MMKV 未就绪，写入丢弃（key=$key）")
    }

    /**
     * 一次性迁移旧 SharedPreferences 值（读旧值 → 写本存储 → 清空旧文件，幂等）。
     *
     * 仅支持 Boolean/String/Int/Long/Float 类型（普通偏好够用）；Set 等类型跳过。
     * MMKV 未就绪时不清空旧文件（下次调用重试，防数据丢失）。
     *
     * @param spName 旧 SharedPreferences 文件名（如 "ad_settings"）
     */
    // SDK 边界全量兜底
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override fun migrateFromSharedPreferences(spName: String) {
        val sp: SharedPreferences = try {
            context.getSharedPreferences(spName, Context.MODE_PRIVATE)
        } catch (e: Throwable) {
            Log.w(TAG, "无法读取旧 SharedPreferences（$spName），跳过迁移", e)
            return
        }
        if (sp.all.isEmpty()) return
        if (kv == null) {
            Log.w(TAG, "MMKV 未就绪，迁移延后（$spName）")
            return
        }
        migrateInto(sp, this)
        Log.i(TAG, "SharedPreferences 迁移完成：$spName")
    }

    companion object {
        private const val TAG = "GamePreferences"
        private const val INSTANCE_ID = "game_prefs"

        /**
         * 迁移核心纯函数（可测）：旧 SharedPreferences 全量键值写入目标 [KeyValueStore]
         * 后清空旧文件。仅支持 Boolean/String/Int/Long/Float（Set 等跳过不迁移）。
         *
         * @param sp 旧 SharedPreferences（非空）
         * @param target 目标存储（已就绪）
         * @return 迁移的键数
         */
        internal fun migrateInto(sp: SharedPreferences, target: KeyValueStore): Int {
            var migrated = 0
            sp.all.forEach { (key, value) ->
                when (value) {
                    is Boolean -> target.putBoolean(key, value)
                    is String -> target.putString(key, value)
                    is Int -> target.putInt(key, value)
                    is Long -> target.putLong(key, value)
                    is Float -> target.putFloat(key, value)
                    else -> return@forEach
                }
                migrated++
            }
            sp.edit().clear().apply()
            return migrated
        }
    }
}
